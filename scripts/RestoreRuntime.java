import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** JDK 11+ source launcher; no Python, Android tools, or downloaded code is run. */
public class RestoreRuntime {
    private static final class Entry {
        final String hash,path;final long bytes;
        Entry(String hash,long bytes,String path){this.hash=hash;this.bytes=bytes;this.path=path;}
    }
    private static String hex(byte[] value) {
        StringBuilder out=new StringBuilder();for(byte b:value)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
    }
    private static String hash(Path file) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=Files.newInputStream(file)){byte[] b=new byte[131072];for(int n;(n=in.read(b))!=-1;)digest.update(b,0,n);}
        return hex(digest.digest());
    }
    private static void require(boolean value,String message) throws IOException {if(!value)throw new IOException(message);}
    private static Path safePath(Path root,String name) throws IOException {
        require(name.matches("app/src/main/jniLibs/arm64-v8a/lib[A-Za-z0-9_]+\\.so")||name.equals("app/src/main/assets/window-xkb.zip"),"Unexpected payload path: "+name);
        Path target=root.resolve(name);
        for(Path p=target;!p.equals(root);p=p.getParent())require(!Files.isSymbolicLink(p),"Refusing a symbolic-link runtime path: "+p);
        return target;
    }
    private static Map<String,Entry> entries(Path root) throws Exception {
        Map<String,Entry> out=new LinkedHashMap<>();
        for(String line:Files.readAllLines(root.resolve("scripts/runtime-files.tsv"),StandardCharsets.UTF_8)) {
            if(line.startsWith("#")||line.trim().isEmpty())continue;
            String[] fields=line.split("\t",-1);require(fields.length==3&&fields[0].matches("[a-f0-9]{64}"),"Invalid runtime manifest");
            long bytes=Long.parseLong(fields[1]);require(bytes>0&&bytes<100L*1024*1024,"Invalid runtime file size");
            safePath(root,fields[2]);require(out.put(fields[2],new Entry(fields[0],bytes,fields[2]))==null,"Duplicate runtime file");
        }
        require(out.size()==53,"Expected 52 native libraries and one XKB archive");return out;
    }
    private static boolean matches(Path path,Entry entry) throws Exception {
        return Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&Files.size(path)==entry.bytes&&hash(path).equals(entry.hash);
    }
    private static boolean archiveMatches(Path file,Properties config) throws Exception {
        return Files.isRegularFile(file)&&Files.size(file)==Long.parseLong(config.getProperty("bytes"))&&hash(file).equals(config.getProperty("sha256"));
    }
    private static InputStream openDownload(URL initial,String token) throws Exception {
        URL url=initial;
        for(int hop=0;hop<8;hop++) {
            require(url.getProtocol().equals("https"),"Runtime downloads require HTTPS");
            HttpURLConnection connection=(HttpURLConnection)url.openConnection();connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(20000);connection.setReadTimeout(60000);
            connection.setRequestProperty("User-Agent","FactorioAndroid-runtime-restore");
            connection.setRequestProperty("Accept","application/octet-stream");
            // Never forward a GitHub token to the signed asset/CDN redirect.
            if(url.getHost().equals("api.github.com")&&!token.isEmpty())connection.setRequestProperty("Authorization","Bearer "+token);
            int code=connection.getResponseCode();
            if(code==200)return connection.getInputStream();
            if(code==301||code==302||code==303||code==307||code==308) {
                String location=connection.getHeaderField("Location");connection.disconnect();
                require(location!=null,"Missing runtime download redirect");url=url.toURI().resolve(location).toURL();continue;
            }
            connection.disconnect();
            throw new IOException("Runtime download returned HTTP "+code+". Private repositories require GITHUB_TOKEN (Contents: read), or use --archive <downloaded ZIP>.");
        }
        throw new IOException("Too many runtime download redirects");
    }
    private static Path download(Path cache,Properties config) throws Exception {
        Path archive=cache.resolve(config.getProperty("sha256")+".zip");
        if(archiveMatches(archive,config))return archive;
        String token=System.getenv("GITHUB_TOKEN");if(token==null||token.isEmpty())token=System.getenv("GH_TOKEN");if(token==null)token="";
        String address=config.getProperty(token.isEmpty()?"url":"apiUrl");
        require(address!=null&&!address.isEmpty(),"Runtime download source is not configured");
        if(!token.isEmpty())require(URI.create(address).getHost().equals("api.github.com"),"Token download endpoint must be api.github.com");
        long expected=Long.parseLong(config.getProperty("bytes"));Path part=cache.resolve("download.part");
        System.out.println("Downloading pinned runtime ("+expected+" bytes)...");
        try(InputStream in=openDownload(URI.create(address).toURL(),token);OutputStream out=Files.newOutputStream(part)) {
            byte[] buffer=new byte[131072];long total=0;
            for(int n;(n=in.read(buffer))!=-1;){total+=n;require(total<=expected,"Runtime download exceeds pinned size");out.write(buffer,0,n);}
        }catch(Exception error){Files.deleteIfExists(part);throw error;}
        if(!archiveMatches(part,config)){Files.deleteIfExists(part);throw new IOException("Runtime ZIP checksum mismatch; nothing was installed");}
        Files.move(part,archive,StandardCopyOption.REPLACE_EXISTING);return archive;
    }
    private static void restore(Path root,Path archive,Map<String,Entry> expected,boolean force,Path cache) throws Exception {
        Path stage=Files.createTempDirectory(cache,"stage-");Set<String> seen=new HashSet<>();
        try {
            try(ZipInputStream zip=new ZipInputStream(Files.newInputStream(archive))) {
                for(ZipEntry item;(item=zip.getNextEntry())!=null;) {
                    String name=item.getName();require(!item.isDirectory()&&seen.add(name),"Invalid or repeated ZIP member");
                    Entry entry=expected.get(name);
                    if(entry==null) {
                        require(name.equals("RUNTIME-NOTICES.txt")||name.equals("RUNTIME-SOURCES.md"),"Unexpected ZIP member: "+name);
                        long total=0;byte[] buffer=new byte[8192];for(int n;(n=zip.read(buffer))!=-1;){total+=n;require(total<=8*1024*1024,"Runtime notice too large");}
                        continue;
                    }
                    Path target=safePath(stage,name);Files.createDirectories(target.getParent());
                    try(OutputStream out=Files.newOutputStream(target)) {
                        long total=0;byte[] buffer=new byte[131072];
                        for(int n;(n=zip.read(buffer))!=-1;){total+=n;require(total<=entry.bytes,"Expanded runtime file too large");out.write(buffer,0,n);}
                    }
                    require(matches(target,entry),"Runtime member checksum mismatch: "+name);
                }
            }
            require(seen.containsAll(expected.keySet()),"Incomplete runtime ZIP; nothing was installed");
            // Stage and verify every member before publishing any runtime file.
            for(Entry entry:expected.values()) {
                Path target=safePath(root,entry.path);
                if(matches(target,entry))continue;
                require(force||!Files.exists(target,LinkOption.NOFOLLOW_LINKS),"Existing runtime differs; use --force to restore the pinned version: "+entry.path);
                require(!Files.exists(target)||Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS),"Runtime target is not a regular file");
            }
            for(Entry entry:expected.values()) {
                Path target=safePath(root,entry.path);if(matches(target,entry))continue;
                Files.createDirectories(target.getParent());Path source=stage.resolve(entry.path);
                try{Files.move(source,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                catch(AtomicMoveNotSupportedException unsupported){Files.move(source,target,StandardCopyOption.REPLACE_EXISTING);}
            }
        }finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(stage)) {
                for(Path file:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(file);
            }
        }
    }
    private static void run(String[] args) throws Exception {
        Path root=Paths.get("").toAbsolutePath(),archive=null;boolean check=false,force=false;
        for(int i=0;i<args.length;i++) {
            if(args[i].equals("--root")&&i+1<args.length)root=Paths.get(args[++i]).toAbsolutePath();
            else if(args[i].equals("--archive")&&i+1<args.length)archive=Paths.get(args[++i]).toAbsolutePath();
            else if(args[i].equals("--check"))check=true;
            else if(args[i].equals("--force"))force=true;
            else throw new IOException("Usage: java scripts/RestoreRuntime.java [--root PROJECT] [--archive ZIP] [--check] [--force]");
        }
        root=root.toRealPath();require(Files.isRegularFile(root.resolve("app/src/main/AndroidManifest.xml")),"Run this command from the project root, or pass --root");
        Map<String,Entry> expected=entries(root);boolean ready=true;
        for(Entry entry:expected.values()) {
            Path file=safePath(root,entry.path);
            if(!matches(file,entry)) {
                ready=false;
                if(check)System.err.println("Missing or mismatched runtime: "+entry.path);
                else require(force||!Files.exists(file,LinkOption.NOFOLLOW_LINKS),"Existing runtime differs; use --force to restore: "+entry.path);
            }
        }
        if(check){require(ready,"Runtime verification failed");System.out.println("Runtime verified: 52 native libraries + XKB");return;}
        if(!ready) {
            Properties config=new Properties();try(InputStream in=Files.newInputStream(root.resolve("scripts/runtime-payload.properties"))){config.load(in);}
            require(config.getProperty("sha256","").matches("[a-f0-9]{64}"),"Invalid pinned archive hash");
            Path cache=root.resolve(".cache/runtime");Files.createDirectories(cache);
            try(FileChannel channel=FileChannel.open(cache.resolve("restore.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock=channel.tryLock()) {
                require(lock!=null,"Another runtime restoration is running");
                if(archive==null)archive=download(cache,config);
                require(archiveMatches(archive,config),"Runtime ZIP checksum mismatch; nothing was installed");
                restore(root,archive,expected,force,cache);
            }
        }
        for(Entry entry:expected.values())require(matches(safePath(root,entry.path),entry),"Final runtime verification failed: "+entry.path);
        Path marker=root.resolve(".factorio-migration-workspace.json");
        if(!Files.exists(marker))Files.write(marker,"{}\n".getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
        System.out.println("Runtime ready: 52 native libraries + XKB (all SHA-256 verified)");
    }
    public static void main(String[] args) {
        try{run(args);}catch(Exception error){System.err.println("Runtime restore failed: "+error.getMessage());System.exit(1);}
    }
}
