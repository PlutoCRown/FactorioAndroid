package org.factorio.local.launcher;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Strict streaming tar subset for unmodified official game bundles. Never follows links. */
public final class SafeTarReader implements Closeable {
    public static final long MAX_TOTAL = 16L * 1024 * 1024 * 1024;
    private final InputStream input;
    private final Set<String> seen = new HashSet<>();
    private long remaining, padding, total;
    private int entries;
    private String longName;
    private final Map<String,String> pax = new HashMap<>();
    private boolean ended;

    public static final class Entry {
        public final String name;
        public final long size;
        public final boolean directory;
        Entry(String name, long size, boolean directory) {
            this.name = name; this.size = size; this.directory = directory;
        }
    }
    public SafeTarReader(InputStream input) { this.input = input; }
    private void check() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Import cancelled");
    }
    private int readRaw(byte[] bytes, int offset, int length) throws IOException {
        check();
        int n = input.read(bytes, offset, length);
        if (n > 0 && (total += n) > MAX_TOTAL) throw new IOException("展开内容超过 16 GiB 限制");
        return n;
    }
    private void exact(byte[] bytes) throws IOException {
        int at = 0;
        while (at < bytes.length) {
            int n = readRaw(bytes, at, bytes.length - at);
            if (n < 0) throw new EOFException("安装包被截断");
            if (n > 0) at += n;
        }
    }
    private static boolean zero(byte[] bytes) {
        for (byte b : bytes) if (b != 0) return false;
        return true;
    }
    private static long octal(byte[] bytes, int offset, int size) throws IOException {
        if ((bytes[offset] & 128) != 0) throw new IOException("不支持 tar base-256 数值");
        long value = 0; boolean digits = false, finished = false;
        for (int i = offset; i < offset + size; i++) {
            int b = bytes[i] & 255;
            if (b == 0 || b == ' ') { if (digits) finished = true; continue; }
            if (finished || b < '0' || b > '7') throw new IOException("无效的 tar 数值");
            digits = true;
            if (value > (Long.MAX_VALUE - 7) / 8) throw new IOException("tar 数值溢出");
            value = value * 8 + b - '0';
        }
        return value;
    }
    private static String text(byte[] bytes, int offset, int length) {
        int end = offset;
        while (end < offset + length && bytes[end] != 0) end++;
        return new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
    }
    public static String safeName(String name) throws IOException {
        if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
        if (name.length() > 1024 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0
                || name.indexOf(':') >= 0 || name.startsWith("/"))
            throw new IOException("安装包路径无效");
        String[] parts = name.split("/", -1);
        if (!parts[0].equals("factorio")) throw new IOException("安装包根目录必须为 factorio");
        for (String part : parts) if (part.isEmpty() || part.equals(".") || part.equals(".."))
            throw new IOException("安装包包含越界路径");
        return name;
    }
    private void finishEntry() throws IOException {
        byte[] buffer = new byte[32768];
        while (remaining > 0) read(buffer, 0, (int)Math.min(buffer.length, remaining));
        while (padding > 0) {
            int n = readRaw(buffer, 0, (int)Math.min(buffer.length, padding));
            if (n < 0) throw new EOFException("tar 填充区被截断");
            padding -= n;
        }
    }
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (remaining == 0) return -1;
        int n = readRaw(buffer, offset, (int)Math.min(length, remaining));
        if (n < 0) throw new EOFException("tar 文件内容被截断");
        remaining -= n;
        return n;
    }
    private byte[] metadata(long size) throws IOException {
        if (size > 65536) throw new IOException("tar 扩展头过大");
        byte[] data = new byte[(int)size]; int at = 0;
        while (at < data.length) {
            int n = read(data, at, data.length - at);
            if (n > 0) at += n;
        }
        finishEntry();
        return data;
    }
    private void parsePax(byte[] data) throws IOException {
        int at = 0;
        while (at < data.length) {
            int space = at;
            while (space < data.length && data[space] != ' ') space++;
            if (space == data.length || space - at > 7) throw new IOException("无效 PAX 记录");
            int length;
            try { length = Integer.parseInt(new String(data, at, space-at, StandardCharsets.US_ASCII)); }
            catch (NumberFormatException e) { throw new IOException("无效 PAX 长度", e); }
            int end = at + length;
            if (length <= space-at+1 || end > data.length || data[end-1] != '\n')
                throw new IOException("PAX 记录越界");
            String record = new String(data, space+1, end-space-2, StandardCharsets.UTF_8);
            int equal = record.indexOf('=');
            if (equal <= 0) throw new IOException("无效 PAX 属性");
            String key = record.substring(0, equal);
            if (key.startsWith("GNU.sparse") || key.equals("linkpath"))
                throw new IOException("不支持稀疏文件或链接");
            pax.put(key, record.substring(equal+1));
            at = end;
        }
    }
    public Entry next() throws IOException {
        if (ended) return null;
        finishEntry();
        for (;;) {
            byte[] header = new byte[512]; exact(header);
            if (zero(header)) {
                byte[] second = new byte[512]; exact(second);
                if (!zero(second)) throw new IOException("tar 结束标记无效");
                byte[] tail = new byte[32768];
                for (int n; (n=readRaw(tail,0,tail.length)) != -1;)
                    for (int i=0;i<n;i++) if (tail[i]!=0) throw new IOException("tar 尾部有非零内容");
                if (longName != null || !pax.isEmpty()) throw new IOException("扩展头缺少文件");
                ended = true; return null;
            }
            long checksum = octal(header,148,8), actual=0;
            for (int i=0;i<512;i++) actual += (i>=148 && i<156) ? 32 : (header[i]&255);
            if (checksum != actual) throw new IOException("tar 头校验失败");
            long size=octal(header,124,12);
            if (size > 2L*1024*1024*1024) throw new IOException("安装包单文件超过 2 GiB");
            remaining=size; padding=(512-size%512)%512;
            int type=header[156]&255;
            if (++entries>200000) throw new IOException("安装包文件数量过多");
            if (type=='L') { byte[] bytes=metadata(size); longName=text(bytes,0,bytes.length); continue; }
            if (type=='x') { parsePax(metadata(size)); continue; }
            if (type!='0' && type!=0 && type!='5') throw new IOException("不支持的 tar 类型（含链接）: "+type);
            String name=text(header,0,100), prefix=text(header,345,155);
            // The prefix field is POSIX ustar only; GNU format uses that area differently.
            if (header[257]=='u' && header[262]==0 && !prefix.isEmpty()) name=prefix+"/"+name;
            if (longName!=null) name=longName;
            if (pax.containsKey("path")) name=pax.get("path");
            if (pax.containsKey("size")) {
                try { size=Long.parseLong(pax.get("size")); }
                catch (NumberFormatException e) { throw new IOException("无效 PAX 文件大小",e); }
                if (size<0 || size>2L*1024*1024*1024) throw new IOException("PAX 文件过大");
                remaining=size; padding=(512-size%512)%512;
            }
            longName=null; pax.clear(); name=safeName(name);
            boolean directory=type=='5';
            if (directory && size!=0) throw new IOException("目录包含意外数据");
            if (!seen.add(name)) throw new IOException("安装包重复路径: "+name);
            return new Entry(name,size,directory);
        }
    }
    public long bytesRead() { return total; }
    public void close() throws IOException { input.close(); }
}
