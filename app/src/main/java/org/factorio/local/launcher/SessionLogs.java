package org.factorio.local.launcher;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.json.JSONObject;

/** Refreshable, bounded snapshots; an older session never reads a newer session's profile log. */
final class SessionLogs {
    static File directory(File files) throws IOException {
        File dir = new File(files, "logs");
        if (Files.isSymbolicLink(dir.toPath()) || (!dir.isDirectory() && !dir.mkdirs()))
            throw new IOException("Log directory unavailable");
        return dir;
    }

    static File resolve(File files, String document) throws IOException {
        if ("logs".equals(document)) return directory(files);
        if (document == null || !document.matches("logs:session-[0-9]+\\.zip"))
            throw new FileNotFoundException("Unknown log document");
        File file = new File(directory(files), document.substring(5));
        if (Files.isSymbolicLink(file.toPath()) || !file.isFile())
            throw new FileNotFoundException("Log unavailable");
        return file;
    }

    static synchronized File refresh(File files, String document) throws Exception {
        File selected = resolve(files, document);
        long epoch =
                Long.parseLong(selected.getName().substring(8, selected.getName().length() - 4));
        File report = new File(files, "game-session-report.json");
        if (!safe(report, 2097152)) return selected;
        JSONObject current =
                new JSONObject(
                        new String(Files.readAllBytes(report.toPath()), StandardCharsets.UTF_8));
        return current.optLong("collectedAtEpochMillis") == epoch ? capture(files) : selected;
    }

    static synchronized File capture(File files) throws Exception {
        File report = new File(files, "game-session-report.json");
        if (!safe(report, 2097152)) throw new IOException("Session report unavailable");
        byte[] session = Files.readAllBytes(report.toPath());
        JSONObject details = new JSONObject(new String(session, StandardCharsets.UTF_8));
        long epoch = details.optLong("collectedAtEpochMillis");
        if (epoch <= 0) throw new IOException("Session identity unavailable");
        File dir = directory(files), target = new File(dir, "session-" + epoch + ".zip");
        if (Files.isSymbolicLink(target.toPath())) throw new IOException("Archive is a link");
        File trace = SessionDiagnostics.directory(files, epoch);
        File pending = File.createTempFile("session-", ".pending", dir);
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(pending))) {
                entry(zip, "session-report.json", session);
                JSONObject engine = details.optJSONObject("engineWindowProbe");
                if (engine == null) engine = details.optJSONObject("engineProbeProgress");
                byte[] engineLog = engineLog(files, epoch, engine);
                if (engineLog != null) entry(zip, "factorio-current.log", engineLog);
                for (String name : new String[] {"device-report.json", "last-process-exits.json"})
                    add(zip, name, new File(files, name), 2097152);
                for (String name :
                        new String[] {
                            "engine-process.json",
                            "engine-result.json",
                            "session-engine-result.json",
                            "memory-summary.json",
                            "window-termination.json"
                        }) add(zip, name, new File(trace, name), 2097152);
                for (String name : new String[] {"engine-stdout.log", "memory-samples.jsonl"}) {
                    byte[] tail = rollingTail(new File(trace, name), SessionDiagnostics.LIMIT);
                    if (tail != null) entry(zip, name, tail);
                }
                JSONObject info =
                        new JSONObject()
                                .put("sessionEpoch", epoch)
                                .put("capturedAtEpochMillis", System.currentTimeMillis())
                                .put(
                                        "reportUpdatedAtEpochMillis",
                                        details.optLong("updatedAtEpochMillis"))
                                .put(
                                        "engineResultAvailable",
                                        safe(new File(trace, "engine-result.json"), 2097152))
                                .put(
                                        "scope",
                                        "Missing final engine result means its exit cause remains unconfirmed; OS records may omit native child exits");
                entry(zip, "archive-info.json", info.toString(2).getBytes(StandardCharsets.UTF_8));
                entry(
                        zip,
                        "README.txt",
                        ("Local session diagnostics. The current session archive is refreshed before export.\n"
                                        + "Engine output and memory samples are bounded tails; process samples may miss the final instant and do not include all GPU allocations.\n"
                                        + "Window SIGKILL cleanup is separate from engine exit. Missing final evidence is not classified as OOM.\n"
                                        + "May include game/mod names, paths and device information. No saves or game assets are included.\n")
                                .getBytes(StandardCharsets.UTF_8));
            }
            JSONObject latest =
                    new JSONObject(
                            new String(
                                    Files.readAllBytes(report.toPath()), StandardCharsets.UTF_8));
            if (latest.optLong("collectedAtEpochMillis") != epoch)
                throw new IOException("Session changed during capture");
            Files.move(
                    pending.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(pending.toPath());
        }
        File[] archives = dir.listFiles((d, n) -> n.matches("session-[0-9]+\\.zip"));
        if (archives != null) {
            Arrays.sort(archives, Comparator.comparing(File::getName).reversed());
            for (int i = 20; i < archives.length; i++) Files.deleteIfExists(archives[i].toPath());
        }
        return target;
    }

    private static boolean safe(File file, int limit) {
        return file.isFile() && file.length() <= limit && !Files.isSymbolicLink(file.toPath());
    }

    private static void add(ZipOutputStream zip, String name, File file, int limit)
            throws IOException {
        if (safe(file, limit)) entry(zip, name, Files.readAllBytes(file.toPath()));
    }

    private static void entry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static byte[] tail(File file, int limit) throws IOException {
        if (!file.isFile() || Files.isSymbolicLink(file.toPath())) return null;
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            int size = (int) Math.min(input.length(), limit);
            byte[] bytes = new byte[size];
            input.seek(input.length() - size);
            input.readFully(bytes);
            return bytes;
        }
    }

    private static byte[] rollingTail(File file, int limit) throws IOException {
        byte[] old = tail(new File(file.getPath() + ".previous"), limit),
                current = tail(file, limit);
        if (old == null && current == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (old != null) out.write(old);
        if (current != null) out.write(current);
        byte[] all = out.toByteArray();
        int start = Math.max(0, all.length - limit), end = all.length;
        if (file.getName().endsWith(".jsonl")) {
            if (start > 0) {
                while (start < end && all[start] != '\n') start++;
                if (start < end) start++;
            }
            while (end > start && all[end - 1] != '\n') end--;
        }
        return Arrays.copyOfRange(all, start, end);
    }

    private static byte[] engineLog(File files, long epoch, JSONObject engine) throws Exception {
        File profile;
        try {
            profile = GameDocumentIds.directory(files, GameProfiles.ID);
        } catch (IOException absent) {
            profile = null;
        }
        if (profile != null) {
            File user = new File(profile, "userdata"), log = new File(user, "factorio-current.log");
            if (!Files.isSymbolicLink(user.toPath()) && log.lastModified() >= epoch) {
                byte[] bytes = tail(log, SessionDiagnostics.LIMIT);
                if (bytes != null) return bytes;
            }
        }
        if (engine == null) return null;
        String retained = engine.optString("engineLog");
        if (retained.isEmpty()) retained = engine.optString("outputTail");
        byte[] bytes = retained.getBytes(StandardCharsets.UTF_8);
        return bytes.length == 0
                ? null
                : Arrays.copyOfRange(
                        bytes, Math.max(0, bytes.length - SessionDiagnostics.LIMIT), bytes.length);
    }
}
