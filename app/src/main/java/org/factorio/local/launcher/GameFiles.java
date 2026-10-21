package org.factorio.local.launcher;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Only the two user-data trees; never imported engine files or app internals. */
final class GameFiles {
    private final File profile;
    GameFiles(File profile) {this.profile=profile;}
    File directory(String root) throws IOException {
        if(!root.equals("saves")&&!root.equals("mods"))throw new IOException("未知游戏目录");
        File data=new File(profile.toPath().toRealPath().toFile(),"userdata");
        File dir=new File(data,root);
        if(Files.isSymbolicLink(data.toPath())||Files.isSymbolicLink(dir.toPath()))throw new IOException("游戏目录不能是符号链接");
        if(!dir.isDirectory()&&!dir.mkdirs()&&!dir.isDirectory())throw new IOException("无法创建游戏目录");
        // Do not let a symlink change the scope of a published root.
        if(!data.getCanonicalFile().equals(data.getAbsoluteFile())||!dir.getCanonicalFile().equals(dir.getAbsoluteFile()))
            throw new IOException("游戏目录不能是符号链接");
        return dir;
    }
    File resolve(String id) throws IOException {
        int split=id.indexOf(':');String root=split<0?id:id.substring(0,split);
        File base=directory(root),file=base;
        if(split>=0) {
            String relative=id.substring(split+1);
            for(String part:relative.split("/",-1)) {
                checkName(part);file=new File(file,part);
                if(Files.isSymbolicLink(file.toPath()))throw new IOException("不能访问符号链接");
            }
        }
        if(!file.getCanonicalFile().equals(file.getAbsoluteFile()))throw new IOException("不能访问符号链接");
        return file;
    }
    String childId(String parent,String name) throws IOException {checkName(name);return parent+(parent.indexOf(':')<0?":":"/")+name;}
    static void checkName(String name) throws IOException {
        if(name==null||name.isEmpty()||name.equals(".")||name.equals("..")||name.startsWith(".")||name.length()>180)
            throw new IOException("文件名无效");
        for(int i=0;i<name.length();i++)if(name.charAt(i)<32||"/\\:*?\"<>|".indexOf(name.charAt(i))>=0)
            throw new IOException("文件名不能包含路径或特殊字符");
    }
    static final class Lease implements AutoCloseable {
        final RandomAccessFile file;final FileLock lock;
        Lease(File profile) throws IOException {
            file=new RandomAccessFile(new File(profile,"session.lock"),"rw");
            FileLock candidate=null;
            try {
                try {candidate=file.getChannel().tryLock();}catch(OverlappingFileLockException ignored) {}
                if(candidate==null)throw new BusyException();
                lock=candidate;
            }catch(IOException|RuntimeException e){file.close();throw e;}
        }
        public void close() throws IOException {try {lock.release();}finally{file.close();}}
    }
    static final class BusyException extends IOException {
        BusyException(){super("Save and end the game session before editing saves or mods.");}
    }
    Lease edit() throws IOException {return new Lease(profile);}
    String unusedName(String parent,String requested) throws IOException {
        checkName(requested);int dot=requested.lastIndexOf('.');
        String stem=dot>0?requested.substring(0,dot):requested,ext=dot>0?requested.substring(dot):"";
        for(int n=0;n<10000;n++) {
            String candidate=n==0?requested:stem+" ("+n+")"+ext;
            if(!Files.exists(resolve(childId(parent,candidate)).toPath(),LinkOption.NOFOLLOW_LINKS))return candidate;
        }
        throw new IOException("同名文件过多，请重命名后再试");
    }
    String importSave(InputStream input,String name) throws IOException {
        checkName(name);
        if(!name.toLowerCase(Locale.ROOT).endsWith(".zip"))throw new IOException("请选择 Factorio 的 .zip 存档，无需解压。");
        try(Lease lease=edit()) {
            File dir=directory("saves"),pending=File.createTempFile(".save-import-",".part",dir);
            try {
                try(OutputStream output=new FileOutputStream(pending)) {
                    byte[] buffer=new byte[128*1024];long bytes=0;int read;
                    while((read=input.read(buffer))!=-1) {
                        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("存档导入已取消");
                        bytes+=read;if(bytes>2L*1024*1024*1024)throw new IOException("存档超过 2 GB，已取消导入。");
                        output.write(buffer,0,read);
                    }
                }
                validateSave(pending);
                String target=unusedName("saves",name);
                // A hard-link publishes the complete file atomically without ever
                // replacing an existing save, even if another writer wins a race.
                Files.createLink(new File(dir,target).toPath(),pending.toPath());
                return target;
            }finally {Files.deleteIfExists(pending.toPath());}
        }
    }
    private static void validateSave(File file) throws IOException {
        try(ZipFile zip=new ZipFile(file)) {
            int count=0;long expanded=0;Set<String> mapRoots=new HashSet<>(),names=new HashSet<>();
            Enumeration<? extends ZipEntry> entries=zip.entries();
            while(entries.hasMoreElements()) {
                ZipEntry entry=entries.nextElement();String name=entry.getName();
                if(!names.add(name))throw new IOException("存档 ZIP 有重复文件。");
                if(++count>100000||name.startsWith("/")||name.contains("\\"))throw new IOException("存档 ZIP 内容无效。");
                String[] parts=name.split("/");
                for(String part:parts)if(part.equals("..")||part.equals("."))throw new IOException("存档 ZIP 路径无效。");
                if(entry.getSize()<0||entry.getSize()>4L*1024*1024*1024-expanded)throw new IOException("存档展开大小无效或过大。");
                expanded+=entry.getSize();
                String leaf=parts.length==0?"":parts[parts.length-1];
                // Chunked saves use level.dat0, level.dat1, ... . Requiring
                // only the old level.dat would reject normal Factorio saves.
                if(!entry.isDirectory()&&parts.length<=2&&(leaf.equals("level.dat")||leaf.equals("level.dat0"))) {
                    if(entry.getSize()==0)throw new IOException("存档地图数据为空。");
                    try(InputStream level=zip.getInputStream(entry)){if(level.read()<0)throw new IOException("无法读取存档。");}
                    mapRoots.add(parts.length==1?"":parts[0]);
                }
            }
            if(mapRoots.size()!=1)throw new IOException("这个 ZIP 不是可识别的单个 Factorio 存档（缺少 level.dat 或 level.dat0）。");
        }catch(ZipException e){throw new IOException("ZIP 已损坏或不是 Factorio 存档。",e);}
    }
}
