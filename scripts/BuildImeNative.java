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
    static String host(String os,String arch)throws IOException {
        if(os.startsWith("Windows"))return "x86_64-windows";
        if(os.equals("Linux"))return "x86_64-linux";
        if(os.startsWith("Mac")) {
            if(arch.equals("aarch64")||arch.equals("arm64"))return "aarch64-macos";
            if(arch.equals("x86_64")||arch.equals("amd64"))return "x86_64-macos";
            throw new IOException("Unsupported macOS architecture: "+arch);
        }
        throw new IOException("Unsupported build host: "+os);
    }
    public static void main(String[] args)throws Exception {
        Path root=Path.of("").toAbsolutePath();String os=System.getProperty("os.name");boolean win=os.startsWith("Windows");
        String host=host(os,System.getProperty("os.arch"));
        Path zig=root.resolve("tools/zig-"+host+"-0.15.2/"+(win?"zig.exe":"zig"));
        Properties compiler=new Properties();try(InputStream in=Files.newInputStream(root.resolve("scripts/ime-compiler.properties"))){compiler.load(in);}
        if(win) {
            if(!Files.isRegularFile(zig)||!sha(zig).equals(compiler.getProperty(host+".executableSha256")))
                throw new IOException("Prepare the pinned Zig 0.15.2 Windows compiler in tools/zig-x86_64-windows-0.15.2");
        } else {
            Path receipt=root.resolve(".cache/"+(host.endsWith("linux")?"ime-linux-compiler.sha256":"ime-"+host+"-compiler.sha256"));
            if(!Files.exists(zig)) {
                Path archive=root.resolve(".cache/zig-"+host+"-0.15.2.tar.xz");Files.createDirectories(archive.getParent());
                String expected=compiler.getProperty(host+".sha256");
                if(!Files.exists(archive)||!sha(archive).equals(expected)) {
                    Path part=archive.resolveSibling(archive.getFileName()+".part");
                    java.net.URLConnection connection=new java.net.URL(compiler.getProperty(host+".url")).openConnection();
                    connection.setConnectTimeout(20000);connection.setReadTimeout(120000);
                    try(InputStream in=connection.getInputStream()){Files.copy(in,part,StandardCopyOption.REPLACE_EXISTING);}
                    if(Files.size(part)!=Long.parseLong(compiler.getProperty(host+".bytes"))||!sha(part).equals(expected))throw new IOException("Compiler download checksum differs");
                    Files.move(part,archive,StandardCopyOption.REPLACE_EXISTING);
                }
                Files.createDirectories(root.resolve("tools"));
                run(root,List.of("tar","-xf",archive.toString(),"-C",root.resolve("tools").toString()));
                Files.writeString(receipt,sha(zig));
            }
            if(!Files.exists(receipt)||!sha(zig).equals(Files.readString(receipt).trim()))throw new IOException("Keyboard compiler identity differs");
        }
        Path source=root.resolve("native-input/ime/keyboard-opener.c"),base=root.resolve("app/build/generated/ime");
        Path nativeDir=base.resolve("jni/arm64-v8a"),assets=base.resolve("assets");Files.createDirectories(nativeDir);Files.createDirectories(assets);
        Path out=nativeDir.resolve("librea_ime_opener.so");
        run(root,List.of(zig.toString(),"cc","-target","aarch64-linux-musl","-std=c11","-O2","-static","-s","-Wall","-Wextra","-Werror","-Wl,-z,max-page-size=16384","-Wl,--build-id=sha1",source.toString(),"-o",out.toString()));
        String value="name=librea_ime_opener.so\nbytes="+Files.size(out)+"\nsha256="+sha(out)+"\nsourceSha256="+sha(source)+"\n";
        Path guardSource=root.resolve("native-input/ime/sdl-hint-guard.c"),guardHeader=root.resolve("native-input/ime/sdl-hint-interfaces.h");
        Path guard=nativeDir.resolve("librea_ime_hint.so");
        run(root,List.of(zig.toString(),"cc","-target","aarch64-linux-gnu.2.38","-std=c11","-O2","-shared","-fPIC","-s",
            "-Wall","-Wextra","-Werror","-Wl,-z,max-page-size=16384","-Wl,--build-id=sha1",guardSource.toString(),"-o",guard.toString(),"-ldl","-pthread"));
        value+="hint.name=librea_ime_hint.so\nhint.bytes="+Files.size(guard)+"\nhint.sha256="+sha(guard)+"\nhint.sourceSha256="+sha(guardSource)+"\nhint.headerSha256="+sha(guardHeader)+"\n";
        value+="hint.engineSha256=ebe1914b13c3ff45766d75001dda1ae87ae1a6f170feaf86973d782aa2e69de0\n";
        Files.writeString(assets.resolve("ime-native.properties"),value);
        System.out.println("Compiled keyboard helper: "+Files.size(out)+" bytes");
    }
}
