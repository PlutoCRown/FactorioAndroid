import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Cross-platform entry: restore, Gradle build/test, then verify the APK payloads. */
class BuildLauncher {
    static Path root,java,sdk;
    static boolean windows=System.getProperty("os.name").startsWith("Windows");
    static String env(String name){String s=System.getenv(name);return s==null?"":s;}
    static String sha(byte[] data)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
    static String quote(String s){return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";}
    static void run(List<String> args)throws Exception {
        Process p=new ProcessBuilder(args).directory(root.toFile()).inheritIO().start();
        int code=p.waitFor();if(code!=0)throw new IOException("Tool exited "+code+": "+Path.of(args.get(0)).getFileName());
    }
    static String capture(String... args)throws Exception {
        Process p=new ProcessBuilder(args).directory(root.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        String text=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        if(p.waitFor()!=0)throw new IOException("Verification failed: "+Path.of(args[0]).getFileName());return text;
    }
    static Path tool(Path tools,String name){return tools.resolve(name+(windows?".exe":""));}
    static void signing()throws Exception {
        Path local=root.resolve(".local");Files.createDirectories(local);Path store=local.resolve("release.keystore");
        String secret=env("FACTORIO_KEYSTORE_BASE64");
        if(!secret.isBlank()) {
            if(env("FACTORIO_KEY_ALIAS").isBlank()||env("FACTORIO_KEYSTORE_PASSWORD").isBlank())throw new IOException("Signing secrets incomplete");
            Files.write(store,Base64.getDecoder().decode(secret));return;
        }
        if(Files.exists(store))return;
        Path passFile=local.resolve("release-keystore.pass");
        if(!Files.exists(passFile)) {
            byte[] random=new byte[24];new SecureRandom().nextBytes(random);
            Files.writeString(passFile,Base64.getUrlEncoder().withoutPadding().encodeToString(random)+"\n");
            if(!windows)Files.setPosixFilePermissions(passFile,PosixFilePermissions.fromString("rw-------"));
        }
        String pass=Files.readString(passFile).trim();
        run(List.of(java.getParent().resolve(windows?"keytool.exe":"keytool").toString(),"-genkeypair","-noprompt","-keystore",store.toString(),"-alias","factoriorelease","-storepass",pass,"-keypass",pass,"-dname","CN=Factorio Local Release","-keyalg","RSA","-keysize","2048","-validity","10000"));
    }
    static void verifyPayload(Path apk)throws Exception {
        int count=0;
        try(ZipFile zip=new ZipFile(apk.toFile())) {
            for(String row:Files.readAllLines(root.resolve("scripts/runtime-files.tsv"))) {
                if(row.isBlank()||row.startsWith("#"))continue;
                String[] parts=row.split("\t");String path=parts[2];String entry;
                if(path.startsWith("app/src/main/jniLibs/")){entry="lib/"+path.substring("app/src/main/jniLibs/".length());count++;}
                else entry="assets/"+path.substring("app/src/main/assets/".length());
                ZipEntry member=zip.getEntry(entry);if(member==null)throw new IOException("APK missing "+entry);
                byte[] data=zip.getInputStream(member).readAllBytes();
                if(data.length!=Long.parseLong(parts[1])||!sha(data).equals(parts[0]))throw new IOException("APK payload changed: "+entry);
            }
            long actual=zip.stream().filter(x->x.getName().startsWith("lib/")&&x.getName().endsWith(".so")).count();
            if(actual!=count)throw new IOException("Unexpected native payload count: "+actual);
        }
        System.out.println("APK runtime payload verified: "+count+" native libraries + XKB");
    }
    public static void main(String[] args)throws Exception {
        if(Runtime.version().feature()<17)throw new IOException("JDK 17+ required for Kotlin/Compose/AGP");
        root=Path.of("").toAbsolutePath();boolean performance=false;
        for(int i=0;i<args.length;i++) {
            if(args[i].equals("--root"))root=Path.of(args[++i]).toAbsolutePath();
            else if(args[i].equals("--performance-package"))performance=true;
            else throw new IOException("Unknown argument: "+args[i]);
        }
        java=Path.of(System.getProperty("java.home"),"bin",windows?"java.exe":"java");
        sdk=Path.of(env("ANDROID_HOME"));Path tools=sdk.resolve("build-tools/36.0.0");
        if(!Files.exists(tool(tools,"aapt2")))throw new IOException("Build Tools 36.0.0 required in ANDROID_HOME");
        if(!Files.exists(sdk.resolve("platforms/android-36/android.jar")))throw new IOException("Android SDK platform 36 required");
        run(List.of(java.toString(),root.resolve("scripts/RestoreRuntime.java").toString()));signing();
        String gradleSha="76805e32c009c0cf0dd5d206bddc9fb22ea42e84db904b764f3047de095493f3";
        Path wrapper=root.resolve("gradle/wrapper/gradle-wrapper.jar");
        if(!sha(Files.readAllBytes(wrapper)).equals(gradleSha))throw new IOException("Gradle wrapper checksum mismatch");
        List<String> command=new ArrayList<>(List.of(java.toString(),"-classpath",wrapper.toString(),"org.gradle.wrapper.GradleWrapperMain","--no-daemon","--console=plain",":app:testStandardDebugUnitTest",":app:assembleStandardRelease"));
        if(performance)command.add(":app:assemblePerformanceRelease");run(command);
        Properties version=new Properties();try(InputStream in=Files.newInputStream(root.resolve("gradle.properties"))){version.load(in);}
        String versionName=version.getProperty("factorioVersionName"),versionFile=versionName.split("-",2)[0];
        Path artifacts=root.resolve("artifacts");Files.createDirectories(artifacts);List<String> packages=new ArrayList<>();
        for(String flavor:performance?List.of("standard","performance"):List.of("standard")) {
            boolean perf=flavor.equals("performance");String id=perf?"com.miHoYo.Yuanshen":"dev.plutocrown.factorio";
            String filename="FactorioLauncher-"+versionFile+(perf?"-YuanShen-sign":"")+".apk";
            Path apk=artifacts.resolve(filename);Files.copy(root.resolve("app/build/outputs/apk/"+flavor+"/release/app-"+flavor+"-release.apk"),apk,StandardCopyOption.REPLACE_EXISTING);
            verifyPayload(apk);
            run(List.of(java.toString(),"-jar",tools.resolve("lib/apksigner.jar").toString(),"verify","--verbose","--print-certs",apk.toString()));
            run(List.of(tool(tools,"zipalign").toString(),"-c","4",apk.toString()));
            String badge=capture(tool(tools,"aapt2").toString(),"dump","badging",apk.toString());
            if(!badge.contains("package: name='"+id+"'")||!badge.contains("launchable-activity: name='org.factorio.local.launcher.MainActivity'")||badge.contains("application-debuggable"))throw new IOException("APK identity or release mode incorrect");
            packages.add("{\"apk\":"+quote(apk.toString())+",\"bytes\":"+Files.size(apk)+",\"sha256\":"+quote(sha(Files.readAllBytes(apk)))+",\"package\":"+quote(id)+",\"performancePackage\":"+perf+"}");
        }
        String result="{\"version\":"+quote(versionName)+",\"versionCode\":"+version.getProperty("factorioVersionCode")+",\"buildType\":\"release\",\"ui\":\"Kotlin / Jetpack Compose / Material 3\",\"nativeLibraries\":true,\"nativeLibrariesVerified\":52,\"buildTools\":\"36.0.0\",\"packages\":["+String.join(",",packages)+"]}";
        Files.writeString(artifacts.resolve("build-result.json"),result+"\n");System.out.println("__FACTORIO_REPORT__"+result);
    }
}
