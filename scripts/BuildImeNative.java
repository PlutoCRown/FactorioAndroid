import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Compile the small musl/static xdg-open endpoint with a pinned project compiler. */
class BuildImeNative {
    static String sha(Path p)throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));}
    static void run(Path root,List<String> command)throws Exception {
        ProcessBuilder pb=new ProcessBuilder(command).directory(root.toFile()).inheritIO();
        pb.environment().put("ZIG_GLOBAL_CACHE_DIR",root.resolve(".cache/zig-global").toString());
        pb.environment().put("ZIG_LOCAL_CACHE_DIR",root.resolve(".cache/zig-ime").toString());
        if(pb.start().waitFor()!=0)throw new IOException("Keyboard native build failed");
    }
    public static void main(String[] args)throws Exception {
        Path root=Path.of("").toAbsolutePath();String os=System.getProperty("os.name");boolean win=os.startsWith("Windows");
        if(!win&&!os.equals("Linux"))throw new IOException("Build on Windows or Linux; no Android tools are installed on macOS");
        String host=win?"x86_64-windows":"x86_64-linux";
        Path zig=root.resolve("tools/zig-"+host+"-0.15.2/"+(win?"zig.exe":"zig"));
        Properties compiler=new Properties();try(InputStream in=Files.newInputStream(root.resolve("scripts/ime-compiler.properties"))){compiler.load(in);}
        if(win) {
            if(!Files.isRegularFile(zig)||!sha(zig).equals(compiler.getProperty(host+".executableSha256")))
                throw new IOException("Prepare the pinned Zig 0.15.2 Windows compiler in tools/zig-x86_64-windows-0.15.2");
        } else {
            Path receipt=root.resolve(".cache/ime-linux-compiler.sha256");
            if(!Files.exists(zig)) {
                Path archive=root.resolve(".cache/zig-x86_64-linux-0.15.2.tar.xz");Files.createDirectories(archive.getParent());
                String expected=compiler.getProperty(host+".sha256");
                if(!Files.exists(archive)||!sha(archive).equals(expected)) {
                    Path part=archive.resolveSibling(archive.getFileName()+".part");
                    java.net.URLConnection connection=new java.net.URL(compiler.getProperty(host+".url")).openConnection();
                    connection.setConnectTimeout(20000);connection.setReadTimeout(60000);
                    try(InputStream in=connection.getInputStream()){Files.copy(in,part,StandardCopyOption.REPLACE_EXISTING);}
                    if(Files.size(part)!=Long.parseLong(compiler.getProperty(host+".bytes"))||!sha(part).equals(expected))throw new IOException("Compiler download checksum differs");
                    Files.move(part,archive,StandardCopyOption.REPLACE_EXISTING);
                }
                Files.createDirectories(root.resolve("tools"));
                run(root,List.of("tar","-xf",archive.toString(),"-C",root.resolve("tools").toString()));
                Files.writeString(receipt,sha(zig));
            }
            if(!Files.exists(receipt)||!sha(zig).equals(Files.readString(receipt).trim()))throw new IOException("Keyboard Linux compiler identity differs");
        }
        Path source=root.resolve("native-input/ime/keyboard-opener.c"),base=root.resolve("app/build/generated/ime");
        Path nativeDir=base.resolve("jni/arm64-v8a"),assets=base.resolve("assets");Files.createDirectories(nativeDir);Files.createDirectories(assets);
        Path out=nativeDir.resolve("librea_ime_opener.so");
        run(root,List.of(zig.toString(),"cc","-target","aarch64-linux-musl","-std=c11","-O2","-static","-s","-Wall","-Wextra","-Werror","-Wl,-z,max-page-size=16384","-Wl,--build-id=sha1",source.toString(),"-o",out.toString()));
        String value="name=librea_ime_opener.so\nbytes="+Files.size(out)+"\nsha256="+sha(out)+"\nsourceSha256="+sha(source)+"\n";
        Files.writeString(assets.resolve("ime-native.properties"),value);
        System.out.println("Compiled keyboard helper: "+Files.size(out)+" bytes");
    }
}
