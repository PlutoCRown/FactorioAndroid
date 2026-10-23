package org.factorio.local.launcher;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.json.JSONObject;

/** Local, bounded session snapshots; never traverses game assets or saves. */
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

    static File capture(File files) throws Exception {
        File report = new File(files, "game-session-report.json");
        if (!report.isFile() || report.length() > 2097152 || Files.isSymbolicLink(report.toPath()))
            throw new IOException("Session report unavailable");
        byte[] session = Files.readAllBytes(report.toPath());
        JSONObject details = new JSONObject(new String(session, StandardCharsets.UTF_8));
        long epoch = details.optLong("collectedAtEpochMillis");
        if (epoch <= 0) throw new IOException("Session identity unavailable");
        File dir = directory(files), target = new File(dir, "session-" + epoch + ".zip");
        if (target.isFile() && !Files.isSymbolicLink(target.toPath())) return target;
        File pending = File.createTempFile("session-", ".pending", dir);
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(pending))) {
                entry(zip, "session-report.json", session);
                JSONObject engine = details.optJSONObject("engineWindowProbe");
                if (engine == null) engine = details.optJSONObject("engineProbeProgress");
                byte[] engineLog = engineLog(files, engine);
                if (engineLog != null) entry(zip, "factorio-current.log", engineLog);
                for (String name : new String[] {"device-report.json", "last-process-exits.json"}) {
                    File source = new File(files, name);
                    if (source.isFile()
                            && source.length() <= 2097152
                            && !Files.isSymbolicLink(source.toPath()))
                        entry(zip, name, Files.readAllBytes(source.toPath()));
                }
                entry(
                        zip,
                        "README.txt",
                        ("Local session diagnostics. Engine log is the retained tail (up to 1 MiB) when available.\n"
                                        + "May include game/mod names, file paths and device information. No saves or game assets are included.\n")
                                .getBytes(StandardCharsets.UTF_8));
            }
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

    private static void entry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static byte[] engineLog(File files, JSONObject engine) throws Exception {
        if (engine == null) return null;
        String write = engine.optString("writeData");
        if (!write.isEmpty())
            try {
                File user = new File(write), profile = user.getParentFile();
                if (profile != null
                        && "userdata".equals(user.getName())
                        && GameDocumentIds.directory(files, profile.getName()).equals(profile)
                        && !Files.isSymbolicLink(user.toPath())) {
                    File log = new File(user, "factorio-current.log");
                    if (log.isFile() && !Files.isSymbolicLink(log.toPath()))
                        try (RandomAccessFile input = new RandomAccessFile(log, "r")) {
                            int size = (int) Math.min(input.length(), 1048576);
                            byte[] bytes = new byte[size];
                            input.seek(input.length() - size);
                            input.readFully(bytes);
                            return bytes;
                        }
                }
            } catch (IOException unavailable) {
                /* Fall back to the retained report from this session. */
            }
        String retained = engine.optString("engineLog");
        return retained.isEmpty() ? null : retained.getBytes(StandardCharsets.UTF_8);
    }
}
