import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Keep the downloaded baseline intact; stage a coherent, source-built Mesa provider set. */
class BuildMesaNative {
    static final String SOURCE="ba042a8e79513c87006f4dd2dc15103724c26cfc";
    static final List<String> NAMES=List.of("librea_compat_direct_present.so","librea_compat_direct_icd.so","librea_compat_mesa_gallium.so","librea_compat_mesa_egl.so",
        "librea_compat_mesa_glx.so","librea_compat_mesa_gbm.so","librea_compat_mesa_gbm_dri.so","librea_compat_mesa_dril.so");
    static String sha(byte[] bytes)throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static String sourceHash(Path root)throws Exception {
        Path source=root.resolve("native-input/direct-wsi");MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(var stream=Files.walk(source)) {
            for(Path file:stream.filter(Files::isRegularFile).filter(p->p.toString().matches(".*\\.(c|h|inc)$"))
                    .sorted(Comparator.comparing(p->source.relativize(p).toString().replace('\\','/'))).toList()) {
                digest.update(source.relativize(file).toString().replace('\\','/').getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte)0);digest.update(Files.readString(file).replace("\r\n","\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        digest.update("direct-present-server.c\0".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update(Files.readString(root.resolve("native-input/direct-present-server.c")).replace("\r\n","\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for(String name:List.of("native-input/mesa-compat/inputs.json","native-input/mesa-compat/qualcomm-static-color-write.patch","scripts/build-mesa-runtime.py")) {
            digest.update((name+"\0").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update(Files.readString(root.resolve(name)).replace("\r\n","\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    static boolean current(Path archive,String sourceHash) {
        try(ZipFile zip=new ZipFile(archive.toFile())) {
            ZipEntry entry=zip.getEntry("mesa-native.properties");if(entry==null||entry.getSize()>16384)return false;
            Properties p=new Properties();try(InputStream in=zip.getInputStream(entry)){p.load(in);}
            return SOURCE.equals(p.getProperty("source"))&&"26.1.0-devel".equals(p.getProperty("version"))&&sourceHash.equals(p.getProperty("icdSourceSha256"));
        }catch(Exception absent){return false;}
    }
    public static void main(String[] args)throws Exception {
        Path root=Path.of("").toAbsolutePath(),output=root.resolve("app/build/generated/mesa");
        String supplied=System.getenv("FACTORIO_MESA_ARCHIVE");
        Path archive=supplied==null||supplied.isBlank()?root.resolve(".cache/mesa-runtime.zip"):Path.of(supplied).toAbsolutePath();
        String sourceHash=sourceHash(root);
        if(!current(archive,sourceHash)) {
            if(supplied!=null&&!supplied.isBlank())throw new IOException("Supplied Mesa archive is missing or has different adapter sources");
            if(System.getProperty("os.name").startsWith("Windows"))
                throw new IOException("Set FACTORIO_MESA_ARCHIVE to the Mesa runtime ZIP built by scripts/build-mesa-runtime.py on macOS/Linux (see docs/MESA-COMPATIBILITY.md)");
            Process process=new ProcessBuilder("python3",root.resolve("scripts/build-mesa-runtime.py").toString()).directory(root.toFile()).inheritIO().start();
            if(process.waitFor()!=0)throw new IOException("Mesa source build failed");
        }
        Map<String,byte[]> files=new LinkedHashMap<>();Properties metadata=new Properties();
        try(ZipFile zip=new ZipFile(archive.toFile())) {
            Set<String> allowed=new HashSet<>(NAMES);allowed.add("mesa-native.properties");Set<String> seen=new HashSet<>();
            for(ZipEntry entry:Collections.list(zip.entries())) {
                if(!allowed.contains(entry.getName())||!seen.add(entry.getName())||entry.getSize()<1||entry.getSize()>64*1024*1024)
                    throw new IOException("Unexpected Mesa archive member: "+entry.getName());
                try(InputStream in=zip.getInputStream(entry)) {files.put(entry.getName(),in.readNBytes((int)entry.getSize()+1));}
            }
            if(!seen.equals(allowed))throw new IOException("Incomplete Mesa provider set");
        }
        metadata.load(new ByteArrayInputStream(files.get("mesa-native.properties")));
        if(!SOURCE.equals(metadata.getProperty("source"))||!"26.1.0-devel".equals(metadata.getProperty("version"))||!sourceHash.equals(metadata.getProperty("icdSourceSha256")))throw new IOException("Mesa source identity differs");
        for(String name:NAMES) {
            byte[] data=files.get(name);
            if(data.length!=Long.parseLong(metadata.getProperty(name+".bytes"))||!sha(data).equals(metadata.getProperty(name+".sha256")))
                throw new IOException("Mesa provider checksum differs: "+name);
        }
        // Publish only after verifying the complete archive.
        for(String name:files.keySet()) {
            Path target=output.resolve(name.endsWith(".so")?"jni/arm64-v8a/"+name:"assets/"+name);
            Files.createDirectories(target.getParent());byte[] data=files.get(name);
            if(!Files.isRegularFile(target)||!Arrays.equals(Files.readAllBytes(target),data))Files.write(target,data);
        }
        System.out.println("Mesa compatibility providers verified: "+NAMES.size()+" libraries, source "+SOURCE);
    }
}
