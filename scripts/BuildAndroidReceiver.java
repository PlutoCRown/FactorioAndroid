import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Compile only the app-owned AHB receiver. No device, NDK installation or captured system DSO. */
class BuildAndroidReceiver {
    static final String HEADERS_SHA="73502f9b04fce210c430c83bf9ae2b8e332ba5c319f3eb94a61ba5cef090f787";
    static String sha(Path path)throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
    static void run(Path root,List<String> args)throws Exception {
        ProcessBuilder pb=new ProcessBuilder(args).directory(root.toFile()).inheritIO();
        pb.environment().put("ZIG_GLOBAL_CACHE_DIR",root.resolve(".cache/zig-global").toString());
        pb.environment().put("ZIG_LOCAL_CACHE_DIR",root.resolve(".cache/zig-android-receiver").toString());
        if(pb.start().waitFor()!=0)throw new IOException("Android receiver build failed");
    }
    static Path build(Path root,Path zig,Path output)throws Exception {
        Path cache=root.resolve(".cache/android-native");Files.createDirectories(cache);
        Path archive=cache.resolve("ndk-sysroot_30_aarch64.deb");
        if(!Files.isRegularFile(archive)||!sha(archive).equals(HEADERS_SHA)) {
            Path part=archive.resolveSibling(archive.getFileName()+".part");
            var connection=URI.create("https://packages.termux.dev/apt/termux-main/pool/main/n/ndk-sysroot/ndk-sysroot_30_aarch64.deb").toURL().openConnection();
            connection.setConnectTimeout(30000);connection.setReadTimeout(120000);
            try(InputStream in=connection.getInputStream()){Files.copy(in,part,StandardCopyOption.REPLACE_EXISTING);}
            if(!sha(part).equals(HEADERS_SHA))throw new IOException("Android public headers checksum differs");
            Files.move(part,archive,StandardCopyOption.REPLACE_EXISTING);
        }
        Path extracted=cache.resolve("headers"),header=extracted.resolve("data/data/com.termux/files/usr/include");
        Path receipt=extracted.resolve(".source.sha256");
        if(!Files.isRegularFile(receipt)||!Files.readString(receipt).trim().equals(HEADERS_SHA)) {
            Files.createDirectories(extracted);Path tar=cache.resolve("data.tar.xz");
            List<String> unpack=System.getProperty("os.name").startsWith("Windows")
                ?List.of("tar","-xOf",archive.toString(),"data.tar.xz")
                :List.of("ar","p",archive.toString(),"data.tar.xz");
            Process process=new ProcessBuilder(unpack).directory(root.toFile())
                .redirectOutput(tar.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            if(process.waitFor()!=0)throw new IOException("Cannot unpack Android header archive");
            run(root,List.of("tar","-xf",tar.toString(),"-C",extracted.toString()));
            Files.writeString(receipt,HEADERS_SHA+"\n");
        }
        // Symbol-only link inputs are our own build outputs, never device libraries.
        for(String name:List.of("c","dl")) {
            List<String> cmd=new ArrayList<>(List.of(zig.toString(),"cc","-target","aarch64-linux-android.26","-std=c11","-O2","-fPIC","-fno-builtin","-nostdlib","-c"));
            if(name.equals("dl"))cmd.add("-DREA_STUB_LIBDL=1");
            cmd.addAll(List.of(root.resolve("native-input/android-link-stubs.c").toString(),"-o",cache.resolve("lib"+name+".o").toString()));run(root,cmd);
            run(root,List.of(zig.toString(),"ld.lld","-shared","-soname","lib"+name+".so",cache.resolve("lib"+name+".o").toString(),"-o",cache.resolve("lib"+name+".so").toString()));
        }
        Path object=cache.resolve("direct-present.o");
        run(root,List.of(zig.toString(),"cc","-target","aarch64-linux-android.26","-D_GNU_SOURCE=1","-std=c11","-O2","-fPIC","-fvisibility=hidden","-nostdlib","-Wall","-Wextra","-Werror",
            "-I",root.resolve("native-input/direct-present-include").toString(),"-isystem",header.toString(),"-isystem",header.resolve("aarch64-linux-android").toString(),
            "-c",root.resolve("native-input/direct-present-server.c").toString(),"-o",object.toString()));
        Files.createDirectories(output.getParent());
        run(root,List.of(zig.toString(),"ld.lld","-shared","--no-undefined","--allow-shlib-undefined","-z","relro","-z","now","-z","max-page-size=16384","--build-id=sha1","-soname","librea_direct_present.so",
            object.toString(),cache.resolve("libdl.so").toString(),cache.resolve("libc.so").toString(),"-o",output.toString()));
        return output;
    }
    public static void main(String[] args)throws Exception {
        Path root=Path.of("").toAbsolutePath();String os=System.getProperty("os.name"),arch=System.getProperty("os.arch");
        String host=os.startsWith("Windows")?"x86_64-windows":(arch.equals("aarch64")||arch.equals("arm64")?"aarch64":"x86_64")+(os.startsWith("Mac")?"-macos":"-linux");
        Path zig=root.resolve("tools/zig-"+host+"-0.15.2/"+(os.startsWith("Windows")?"zig.exe":"zig"));
        Path output=args.length==1?Path.of(args[0]):root.resolve("app/build/generated/ime/jni/arm64-v8a/librea_direct_present.so");
        build(root,zig,output);
    }
}
