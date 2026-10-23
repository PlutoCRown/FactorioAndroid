package org.factorio.local.launcher;

import android.content.Context;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Exercise the real importer against the exact lease used by GameSession. */
public final class ImportLeaseTests {
    private static void blocked(Context context) throws Exception {
        try {
            GameImporter.importGame(context, null, null);
            throw new AssertionError("Import should be blocked");
        } catch (GameImporter.GameInUseException expected) {
        }
        if (context.resolverCalls != 0) throw new AssertionError("Blocked import opened a package");
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("hold")) {
            try (RandomAccessFile f = new RandomAccessFile(args[1], "rw");
                    FileLock lease = f.getChannel().lock()) {
                System.out.println("READY");
                System.out.flush();
                System.in.read();
            }
            return;
        }
        try (ImportProgress.Input input =
                new ImportProgress.Input(new ByteArrayInputStream(new byte[20]))) {
            if (input.read() != 0
                    || input.read(new byte[4]) != 4
                    || input.skip(3) != 3
                    || input.bytes != 8)
                throw new AssertionError("Compressed byte count incorrect");
            if (input.read(new byte[100]) != 12 || input.read() != -1 || input.bytes != 20)
                throw new AssertionError("EOF double-counted");
        }
        ImportProgress known =
                new ImportProgress(ImportProgress.Stage.EXTRACT, 50, 100, 900, 3, 10);
        if (known.fraction() != .5f) throw new AssertionError("Known size progress");
        if (new ImportProgress(ImportProgress.Stage.EXTRACT, 100, 100, 900, 3, 10).fraction()
                != .99f) throw new AssertionError("Read-ahead must not announce completion");
        if (new ImportProgress(ImportProgress.Stage.EXTRACT, 100, -1, 900, 3, 10).fraction() != -1f
                || new ImportProgress(ImportProgress.Stage.VERIFY, 100, 100, 900, 3, 10).fraction()
                        != -1f)
            throw new AssertionError("Unknown size and verification are indeterminate");
        java.util.List<ImportProgress> updates = new java.util.ArrayList<>();
        ImportProgress.Reporter reporter = new ImportProgress.Reporter(updates::add);
        reporter.update(ImportProgress.Stage.OPEN, 0, -1, 0, 0, true);
        reporter.update(ImportProgress.Stage.EXTRACT, 0, 100, 0, 0, false);
        reporter.update(ImportProgress.Stage.EXTRACT, 10, 100, 10, 0, false);
        reporter.update(ImportProgress.Stage.VERIFY, 100, 100, 100, 1, false);
        reporter.update(ImportProgress.Stage.FINISH, 100, 100, 100, 1, false);
        if (updates.size() != 4
                || updates.get(1).stage != ImportProgress.Stage.EXTRACT
                || updates.get(3).stage != ImportProgress.Stage.FINISH)
            throw new AssertionError("Stage/throttle regression");
        System.out.println(
                "PASS 5 progress checks: byte counts, fraction, cap, fallback, stage/throttle");
        Path root = Files.createTempDirectory("factorio-import-lease-");
        Context context = new Context(root.toFile());
        File lockPath = new File(GameSession.profileDirectory(context), "session.lock");
        try (RandomAccessFile file = new RandomAccessFile(lockPath, "rw");
                FileLock lease = file.getChannel().lock()) {
            blocked(context);
        }
        System.out.println("PASS same-process lease prevents package replacement");
        File javaBin = new File(System.getProperty("java.home"), "bin/java");
        if (!javaBin.isFile()) javaBin = new File(System.getProperty("java.home"), "bin/java.exe");
        Process child =
                new ProcessBuilder(
                                javaBin.getPath(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                ImportLeaseTests.class.getName(),
                                "hold",
                                lockPath.getPath())
                        .start();
        try {
            String ready =
                    new BufferedReader(
                                    new InputStreamReader(
                                            child.getInputStream(), StandardCharsets.UTF_8))
                            .readLine();
            if (!"READY".equals(ready))
                throw new AssertionError("Child did not acquire session lease");
            blocked(context);
            System.out.println("PASS separate game process lease prevents package replacement");
        } finally {
            child.getOutputStream().close();
            if (!child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                child.destroyForcibly();
                throw new AssertionError("Lease holder did not exit");
            }
        }
        byte[] marker = "preserve existing import metadata".getBytes(StandardCharsets.UTF_8);
        Path descriptor = root.resolve("active-game.json");
        Files.write(descriptor, marker);
        for (int i = 0; i < 2; i++)
            try {
                GameImporter.importGame(context, null, null);
                throw new AssertionError("Host package provider should stop extraction");
            } catch (UnsupportedOperationException expected) {
                if (!expected.getMessage().equals("host-test-import-open")) throw expected;
            }
        if (context.resolverCalls != 2)
            throw new AssertionError("Import lease not released after failure");
        System.out.println("PASS failed package open releases lease for retry");
        if (!java.util.Arrays.equals(Files.readAllBytes(descriptor), marker))
            throw new AssertionError("Metadata overwritten on failed import");
        try (java.util.stream.Stream<Path> children = Files.list(root.resolve("games"))) {
            if (children.findAny().isPresent())
                throw new AssertionError("Failed import staging was retained");
        }
        System.out.println("PASS failed import preserves metadata and removes its staging");
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path p :
                    (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator)
                Files.delete(p);
        }
        System.out.println("ImportLeaseTests: 9 passed");
    }
}
