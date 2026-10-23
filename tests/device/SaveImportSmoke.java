package org.factorio.local.launcher;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Runs as the app UID in an isolated cache directory, never real user saves. */
public final class SaveImportSmoke {
    public static void main(String[] args) {
        int exit = 1;
        try {
            run(args[0]);
            if (args.length > 1) packageImport(args[0], args[1]);
            exit = 0;
        } catch (Throwable e) {
            e.printStackTrace(System.out);
        }
        System.out.flush();
        System.exit(exit);
    }

    private static void packageImport(String cache, String pkg) throws Exception {
        android.os.Looper.prepareMainLooper();
        Class<?> type = Class.forName("android.app.ActivityThread");
        Object thread = type.getMethod("systemMain").invoke(null);
        java.lang.reflect.Method get = type.getDeclaredMethod("getSystemContext");
        get.setAccessible(true);
        android.content.Context app =
                ((android.content.Context) get.invoke(thread)).createPackageContext(pkg, 0);
        Path root = Files.createTempDirectory(Path.of(cache), "package-import-smoke-");
        android.content.Context context =
                new android.content.ContextWrapper(app) {
                    @Override
                    public File getFilesDir() {
                        return root.toFile();
                    }
                };
        try {
            File fixture = new File(cache, "import-progress-fixture.tar.xz");
            java.util.List<ImportProgress> progress = new ArrayList<>();
            org.json.JSONObject first =
                    GameImporter.importGame(
                            context, android.net.Uri.fromFile(fixture), progress::add);
            java.util.List<ImportProgress.Stage> stages = new ArrayList<>();
            for (ImportProgress update : progress)
                if (stages.isEmpty() || stages.get(stages.size() - 1) != update.stage)
                    stages.add(update.stage);
            if (!stages.equals(Arrays.asList(ImportProgress.Stage.values())))
                throw new AssertionError("Import stages: " + stages);
            ImportProgress end = progress.get(progress.size() - 1);
            if (end.inputBytes != fixture.length()
                    || end.outputBytes != first.getLong("unpackedBytes")
                    || end.files != first.getInt("fileCount")
                    || end.files != 5) throw new AssertionError("Progress counters incorrect");
            if (end.totalBytes > 0)
                throw new AssertionError("file:// should exercise unknown-size fallback");
            File old = new File(root.toFile(), first.getString("relativeDirectory"));
            org.json.JSONObject second =
                    GameImporter.importGame(context, android.net.Uri.fromFile(fixture), p -> {});
            if (old.exists()
                    || !new File(root.toFile(), second.getString("relativeDirectory"))
                            .isDirectory()) throw new AssertionError("Replacement cleanup failed");
            if (!GameImporter.current(context)
                    .getString("archiveSha256")
                    .equals(second.getString("archiveSha256")))
                throw new AssertionError("Active import metadata incorrect");
            System.out.println(
                    "PACKAGE_IMPORT_SMOKE passed=true stages="
                            + stages
                            + " byteCounters=true unknownSize=true replacementCleanup=true isolatedCache=true");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path p : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(p);
            }
        }
    }

    private static void run(String cache) throws Exception {
        Path root = Files.createTempDirectory(Path.of(cache), "save-import-smoke-");
        try {
            Path original = root.resolve("link-source");
            Files.write(original, new byte[] {1});
            try {
                Files.createLink(root.resolve("link-target"), original);
                System.out.println("HARD_LINK supported=true");
            } catch (Exception e) {
                System.out.println("HARD_LINK supported=false error=" + e);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                zip.putNextEntry(new ZipEntry("map/level.dat0"));
                zip.write(new byte[] {1, 2, 3});
                zip.closeEntry();
            }
            byte[] save = bytes.toByteArray();
            GameFiles files = new GameFiles(root.toFile());
            String first = files.importSave(new ByteArrayInputStream(save), "test.zip");
            String next = files.importSave(new ByteArrayInputStream(save), "test.zip");
            if (!first.equals("test.zip") || !next.equals("test (1).zip"))
                throw new AssertionError("Collision handling failed");
            if (!Arrays.equals(save, Files.readAllBytes(files.resolve("saves:test.zip").toPath())))
                throw new AssertionError("Save changed");
            System.out.println(
                    "SAVE_IMPORT_SMOKE passed=true byteExact=true collisionSafe=true isolatedCache=true");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path p : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(p);
            }
        }
    }
}
