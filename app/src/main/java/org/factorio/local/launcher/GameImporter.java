package org.factorio.local.launcher;

import android.content.Context;
import android.net.Uri;
import android.os.StatFs;
import org.json.*;
import org.tukaani.xz.XZInputStream;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.UUID;

final class GameImporter {
    interface Progress { void update(String message); }
    private static final String BASELINE="5d6ff79eb190a29d4fb78b86d50fa28e099feffbc8b394c3d4f515a886ab5bf1";
    private static final long RESERVE=256L*1024*1024;
    static String hex(byte[] bytes) {
        StringBuilder out=new StringBuilder();
        for(byte b:bytes) out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        return out.toString();
    }
    private static String digest(File file) throws Exception {
        MessageDigest sha=MessageDigest.getInstance("SHA-256"); byte[] buffer=new byte[65536];
        try(InputStream in=new FileInputStream(file)) {
            for(int n;(n=in.read(buffer))!=-1;) {
                if(Thread.currentThread().isInterrupted()) throw new InterruptedIOException("导入已取消");
                sha.update(buffer,0,n);
            }
        }
        return hex(sha.digest());
    }
    static JSONObject readJson(File file) throws Exception {
        if(file.length()>1024*1024) throw new IOException("元数据文件过大");
        return new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
    }
    static JSONObject current(Context context) {
        try { return readJson(new File(context.getFilesDir(),"active-game.json")); }
        catch(Exception e) { return null; }
    }
    private static JSONObject elf(File file) throws Exception {
        try(RandomAccessFile in=new RandomAccessFile(file,"r")) {
            byte[] bytes=new byte[64]; in.readFully(bytes);
            if(bytes[0]!=127 || bytes[1]!='E' || bytes[2]!='L' || bytes[3]!='F'
                    || bytes[4]!=2 || bytes[5]!=1) throw new IOException("引擎不是小端 ELF64");
            ByteBuffer header=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int type=header.getShort(16)&65535, machine=header.getShort(18)&65535;
            if(machine!=183 || (type!=2 && type!=3)) throw new IOException("请选择 Linux ARM64 引擎");
            long offset=header.getLong(32); int stride=header.getShort(54)&65535, count=header.getShort(56)&65535;
            if(stride!=56 || count==0 || count>128 || offset<64 || offset>in.length()-(long)count*stride)
                throw new IOException("ELF 程序头无效");
            JSONObject result=new JSONObject().put("machine","AArch64").put("type",type);
            String interpreter=null;
            for(int i=0;i<count;i++) {
                in.seek(offset+(long)i*stride); byte[] ph=new byte[56]; in.readFully(ph);
                ByteBuffer p=ByteBuffer.wrap(ph).order(ByteOrder.LITTLE_ENDIAN);
                if(p.getInt(0)==3) {
                    long at=p.getLong(8), length=p.getLong(32);
                    if(length<2 || length>1024 || at<0 || at>in.length()-length)
                        throw new IOException("ELF 加载器字段无效");
                    byte[] value=new byte[(int)length]; in.seek(at); in.readFully(value);
                    if(value[value.length-1]!=0) throw new IOException("ELF 加载器字段未终止");
                    interpreter=new String(value,0,value.length-1,StandardCharsets.US_ASCII);
                }
            }
            if(!"/lib/ld-linux-aarch64.so.1".equals(interpreter))
                throw new IOException("不是预期的 glibc ARM64 图形客户端");
            return result.put("interpreter",interpreter);
        }
    }
    private static void remove(File file) {
        // Only our fresh staging / imported instance trees; tar links are rejected.
        File[] children=file.listFiles();
        if(children!=null) for(File child:children) remove(child);
        file.delete();
    }
    static final class GameInUseException extends IOException {
        GameInUseException(){super("请先结束游戏会话，再更换安装包。");}
    }
    static JSONObject importGame(Context context, Uri uri, Progress progress) throws Exception {
        // The game runs in :window. Use its existing cross-process session
        // lease while replacing imported files, including the old tree cleanup.
        File path=new File(GameSession.profileDirectory(context),"session.lock");
        try(RandomAccessFile guard=new RandomAccessFile(path,"rw");java.nio.channels.FileChannel channel=guard.getChannel()) {
            java.nio.channels.FileLock lease;
            try {lease=channel.tryLock();}catch(java.nio.channels.OverlappingFileLockException e){lease=null;}
            if(lease==null)throw new GameInUseException();
            try {return importPackage(context,uri,progress);}finally{lease.release();}
        }
    }
    private static JSONObject importPackage(Context context, Uri uri, Progress progress) throws Exception {
        File root=new File(context.getFilesDir(),"games");
        if(!root.isDirectory() && !root.mkdirs()) throw new IOException("无法创建游戏目录");
        String id=UUID.randomUUID().toString();
        File staging=new File(root,"staging-"+id), destination=new File(root,"game-"+id);
        if(!staging.mkdir()) throw new IOException("无法创建临时目录");
        boolean committed=false; long extracted=0; int files=0, graphics=0;
        JSONObject previous=current(context);
        MessageDigest sha=MessageDigest.getInstance("SHA-256");
        long last=0;
        try {
            InputStream selected=context.getContentResolver().openInputStream(uri);
            if(selected==null) throw new IOException("无法打开选中的文件");
            try(InputStream compressed=new DigestInputStream(selected,sha);
                XZInputStream xz=new XZInputStream(compressed,65536);
                SafeTarReader tar=new SafeTarReader(xz)) {
                byte[] buffer=new byte[65536];
                for(SafeTarReader.Entry entry;(entry=tar.next())!=null;) {
                    File target=new File(staging,entry.name);
                    if(!target.getCanonicalPath().startsWith(staging.getCanonicalPath()+File.separator))
                        throw new IOException("归档路径越界");
                    if(entry.directory) {
                        if(!target.isDirectory() && !target.mkdirs()) throw new IOException("无法建立游戏目录");
                        continue;
                    }
                    if(new StatFs(staging.getAbsolutePath()).getAvailableBytes()<entry.size+RESERVE)
                        throw new IOException("可用空间不足；需要展开游戏并保留 256 MiB");
                    File parent=target.getParentFile();
                    if(!parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法建立资源目录");
                    if(!target.createNewFile()) throw new IOException("安装包重复文件");
                    try(OutputStream out=new BufferedOutputStream(new FileOutputStream(target))) {
                        for(int n;(n=tar.read(buffer,0,buffer.length))!=-1;) out.write(buffer,0,n);
                    }
                    // This preflight version only imports and inspects; never launches executable code.
                    target.setExecutable(false,false);
                    extracted+=entry.size; files++;
                    if(entry.name.startsWith("factorio/data/base/graphics/")) graphics++;
                    long now=System.currentTimeMillis();
                    if(now-last>500) {
                        progress.update("正在导入："+(extracted/(1024*1024))+" MiB · "+files+" 个文件");
                        last=now;
                    }
                }
            }
            String archiveHash=hex(sha.digest());
            File game=new File(staging,"factorio"), engine=new File(game,"bin/arm64/factorio");
            if(!engine.isFile() || !new File(game,"data/core/info.json").isFile()
                    || !new File(game,"data/core/graphics/factorio.png").isFile() || graphics==0)
                throw new IOException("缺少图形客户端必需内容；请检查是否误选 headless、差分包或其他架构");
            JSONObject base=readJson(new File(game,"data/base/info.json"));
            if(!"base".equals(base.optString("name"))) throw new IOException("基础资源元数据无效");
            String version=base.getString("version");
            if(!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) throw new IOException("版本字段无效");
            File expansion=new File(game,"data/space-age/info.json");
            if(expansion.isFile() && !version.equals(readJson(expansion).optString("version")))
                throw new IOException("Space Age 与基础版版本不一致");
            progress.update("正在核验 ARM64 引擎与文件哈希…");
            JSONObject report=new JSONObject().put("schema",1).put("version",version)
                .put("spaceAge",expansion.isFile()).put("archiveSha256",archiveHash)
                .put("engineSha256",digest(engine)).put("elf",elf(engine))
                .put("fileCount",files).put("unpackedBytes",extracted)
                .put("officialBaselineHashMatched",BASELINE.equals(archiveHash))
                .put("relativeDirectory","games/"+destination.getName()+"/factorio")
                .put("gameLaunched",false).put("runtimeReady",false);
            if(!staging.renameTo(destination)) throw new IOException("无法提交游戏目录");
            File descriptor=new File(context.getFilesDir(),"active-game.json");
            File pending=new File(context.getFilesDir(),"active-game.json.pending");
            try(FileOutputStream out=new FileOutputStream(pending)) {
                out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            Files.move(pending.toPath(),descriptor.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            committed=true;
            // User data will live separately; this tree has only imported, never-run game files.
            if(previous!=null) {
                String old=previous.optString("relativeDirectory");
                if(old.matches("games/game-[0-9a-f-]{36}/factorio")
                        && !old.equals(report.getString("relativeDirectory")))
                    remove(new File(context.getFilesDir(),old).getParentFile());
            }
            return report;
        } finally {
            if(!committed) { remove(staging); remove(destination); }
        }
    }
}
