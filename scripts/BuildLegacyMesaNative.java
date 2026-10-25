import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/**
 * Assemble the traditional Zink providers; the ZIP is a source-build cache, not a binary input
 * requirement.
 */
class BuildLegacyMesaNative {
    static final Map<String, String> PROVIDERS =
            Map.of(
                    "dri/zink_dri.so", "librea_legacy_mesa_gallium.so",
                    "libEGL_mesa.so.0", "librea_legacy_mesa_egl.so",
                    "libGLX_mesa.so.0", "librea_legacy_mesa_glx.so",
                    "libgbm.so.1", "librea_legacy_mesa_gbm.so",
                    "libglapi.so.0", "librea_legacy_mesa_glapi.so");

    static String sha(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    static String inputHash(Path root) throws Exception {
        List<Path> files;
        try (var stream = Files.list(root.resolve("native-input/mesa-legacy"))) {
            files =
                    new ArrayList<>(
                            stream.filter(
                                            p ->
                                                    p.getFileName()
                                                            .toString()
                                                            .matches(".*\\.(patch|json)$"))
                                    .sorted()
                                    .toList());
        }
        files.add(root.resolve("scripts/build-legacy-mesa.py"));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Path file : files) {
            digest.update(
                    root.relativize(file)
                            .toString()
                            .replace('\\', '/')
                            .getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(
                    Files.readString(file).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static Properties metadata(Path archive) throws Exception {
        Properties p = new Properties();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry member = zip.getEntry("legacy-mesa.properties");
            if (member == null || member.getSize() > 16384)
                throw new IOException("Missing legacy build metadata");
            try (InputStream input = zip.getInputStream(member)) {
                p.load(input);
            }
        }
        return p;
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        String inputHash = inputHash(root);
        String supplied = System.getenv("FACTORIO_LEGACY_MESA_ARCHIVE");
        Path archive =
                supplied == null || supplied.isBlank()
                        ? root.resolve(".cache/mesa-legacy-providers.zip")
                        : Path.of(supplied);
        boolean current = false;
        try {
            current = inputHash.equals(metadata(archive).getProperty("buildInputsSha256"));
        } catch (Exception missing) {
        }
        if (!current) {
            if (System.getProperty("os.name").startsWith("Windows"))
                throw new IOException(
                        "Rebuild .cache/mesa-legacy-providers.zip with python3 scripts/build-legacy-mesa.py on macOS/Linux, then supply it here; see native-input/mesa-legacy/README.md");
            Process build =
                    new ProcessBuilder(
                                    "python3",
                                    root.resolve("scripts/build-legacy-mesa.py").toString())
                            .directory(root.toFile())
                            .inheritIO()
                            .start();
            if (build.waitFor() != 0) throw new IOException("Traditional Zink source build failed");
        }
        Properties p = metadata(archive);
        if (!inputHash.equals(p.getProperty("buildInputsSha256")))
            throw new IOException("Legacy build cache is stale");
        Path out = root.resolve("app/build/generated/mesa");
        Properties packaged = new Properties();
        packaged.putAll(p);
        Map<String, byte[]> contents = new TreeMap<>();
        Set<String> allowed = new HashSet<>(PROVIDERS.keySet());
        allowed.addAll(List.of("legacy-mesa.properties", "source-build.json"));
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Set<String> seen = new HashSet<>();
            for (ZipEntry member : Collections.list(zip.entries())) {
                String name = member.getName();
                if (!allowed.contains(name)
                        || !seen.add(name)
                        || member.getSize() < 1
                        || member.getSize() > 64 * 1024 * 1024)
                    throw new IOException("Unexpected legacy provider entry: " + name);
                if (!PROVIDERS.containsKey(name)) continue;
                byte[] data;
                try (InputStream input = zip.getInputStream(member)) {
                    data = input.readNBytes((int) member.getSize() + 1);
                }
                if (data.length != Long.parseLong(p.getProperty(name + ".bytes"))
                        || !sha(data).equals(p.getProperty(name + ".sha256")))
                    throw new IOException("Legacy provider cache damaged: " + name);
                String target = PROVIDERS.get(name);
                contents.put(target, data);
                packaged.setProperty(target + ".bytes", String.valueOf(data.length));
                packaged.setProperty(target + ".sha256", sha(data));
            }
            if (!seen.equals(allowed)) throw new IOException("Incomplete legacy provider archive");
        }
        for (var entry : contents.entrySet()) {
            Path target = out.resolve("jni/arm64-v8a/" + entry.getKey());
            Files.createDirectories(target.getParent());
            if (!Files.isRegularFile(target)
                    || !Arrays.equals(Files.readAllBytes(target), entry.getValue()))
                Files.write(target, entry.getValue());
        }
        StringBuilder text = new StringBuilder();
        for (String key : new TreeSet<>(packaged.stringPropertyNames()))
            text.append(key).append('=').append(packaged.getProperty(key)).append('\n');
        Path target = out.resolve("assets/legacy-mesa.properties");
        Files.createDirectories(target.getParent());
        Files.writeString(target, text.toString());
        System.out.println(
                "Traditional Mesa providers verified: "
                        + contents.size()
                        + " libraries, version "
                        + p.getProperty("version"));
    }
}
