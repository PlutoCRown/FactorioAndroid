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
        JSONObject details =
                new JSONObject(
                        new String(Files.readAllBytes(report.toPath()), StandardCharsets.UTF_8));
        long epoch = details.optLong("collectedAtEpochMillis");
        if (epoch <= 0) throw new IOException("Session identity unavailable");
        File dir = directory(files), target = new File(dir, "session-" + epoch + ".zip");
        if (Files.isSymbolicLink(target.toPath())) throw new IOException("Archive is a link");
        File trace = SessionDiagnostics.directory(files, epoch);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        JSONObject inventory = new JSONObject();
        for (String name : new String[] {"device-report.json", "last-process-exits.json"})
            readSource(entries, inventory, name, new File(files, name), false, false);
        for (String name :
                new String[] {
                    "engine-process.json",
                    "engine-result.json",
                    "session-engine-result.json",
                    "memory-summary.json",
                    "window-termination.json"
                }) {
            readSource(entries, inventory, name, new File(trace, name), false, false);
            JSONObject value = json(entries, inventory, name);
            if (value != null && value.optLong("sessionEpoch") != epoch) {
                entries.remove(name);
                inventory.getJSONObject(name).put("status", "different-session");
            }
        }
        for (String name :
                new String[] {"engine-stdout.log", "memory-samples.jsonl", "session-events.jsonl"})
            readSource(entries, inventory, name, new File(trace, name), true, true);
        for (String name : new String[] {"mesa.log", "direct-present-report.txt"}) {
            File source = new File(trace, name);
            if (!source.exists()) {
                File legacy = new File(files, name);
                if (legacy.lastModified() >= epoch) source = legacy;
            }
            readSource(entries, inventory, name, source, true, false);
        }
        JSONObject embedded = details.optJSONObject("engineWindowProbe");
        JSONObject engineSession = json(entries, inventory, "session-engine-result.json");
        if (engineSession == null && embedded != null) {
            engineSession = SessionLogExport.copy(embedded);
            engineSession.put("exportSource", "session-report.engineWindowProbe");
        }
        JSONObject progress = details.optJSONObject("engineProbeProgress");
        JSONObject logFallback = engineSession != null ? engineSession : progress;
        JSONObject gameLogInfo =
                new JSONObject()
                        .put("status", "missing")
                        .put("limitBytes", SessionDiagnostics.LIMIT);
        inventory.put("factorio-current.log", gameLogInfo);
        byte[] engineLog = engineLog(files, epoch, logFallback, gameLogInfo);
        if (engineLog != null) entries.put("factorio-current.log", engineLog);
        JSONObject engine = json(entries, inventory, "engine-result.json");
        boolean finalEngineResult = engine != null;
        if (engineSession != null) {
            engineSession = SessionLogExport.copy(engineSession);
            JSONObject initialized = engineSession.optJSONObject("graphicsInitialization");
            if (engine != null && initialized != null) {
                // Session exit reconciliation may add evidence after the worker's final write.
                for (Iterator<String> keys = initialized.keys(); keys.hasNext(); ) {
                    String key = keys.next();
                    engine.put(key, initialized.get(key));
                }
                engineSession.remove("graphicsInitialization");
                engineSession.put("graphicsInitializationFile", "engine-result.json");
            }
            SessionLogExport.externalText(
                    engineSession, "engineLog", "factorio-current.log", entries);
            putJson(entries, "session-engine-result.json", engineSession);
        }
        if (engine == null && engineSession != null)
            engine = engineSession.optJSONObject("graphicsInitialization");
        JSONObject session = SessionLogExport.copy(details);
        session.remove("engineWindowProbe");
        session.remove("engineProbeProgress");
        if (engineSession != null) session.put("engineReportFile", "session-engine-result.json");
        if (progress != null && engineSession == null) {
            progress = SessionLogExport.copy(progress);
            SessionLogExport.externalText(progress, "outputTail", "engine-stdout.log", entries);
            putJson(entries, "engine-progress.json", progress);
            session.put("engineProgressFile", "engine-progress.json");
        }
        if (finalEngineResult) {
            SessionLogExport.externalText(engine, "output", "engine-stdout.log", entries);
            SessionLogExport.externalText(engine, "outputTail", "engine-stdout.log", entries);
            putJson(entries, "engine-result.json", engine);
        }
        JSONObject device = json(entries, inventory, "device-report.json");
        JSONObject memory = json(entries, inventory, "memory-summary.json");
        JSONObject exits = json(entries, inventory, "last-process-exits.json");
        JSONObject process = json(entries, inventory, "engine-process.json");
        if (exits != null)
            putJson(
                    entries,
                    "last-process-exits.json",
                    SessionLogExport.sessionExits(
                            exits, epoch, details, process == null ? engine : process));
        putJson(entries, "session-report.json", session);
        putJson(
                entries,
                "summary.json",
                SessionLogExport.summary(epoch, details, engine, device, memory, engineSession));
        SessionLogExport export = new SessionLogExport(files, details);
        for (String name : new ArrayList<>(entries.keySet())) {
            String text = new String(entries.get(name), StandardCharsets.UTF_8);
            if (name.endsWith(".json"))
                text = ((JSONObject) export.normalize(new JSONObject(text))).toString(2);
            else text = export.text(text);
            entries.put(name, text.getBytes(StandardCharsets.UTF_8));
            if (inventory.has(name))
                inventory.getJSONObject(name).put("exportedBytes", entries.get(name).length);
        }
        JSONObject info =
                new JSONObject()
                        .put("schema", 2)
                        .put("sessionEpoch", epoch)
                        .put("capturedAtEpochMillis", System.currentTimeMillis())
                        .put("reportUpdatedAtEpochMillis", details.optLong("updatedAtEpochMillis"))
                        .put("engineResultAvailable", finalEngineResult)
                        .put("files", inventory)
                        .put(
                                "scope",
                                "Missing final engine result leaves exit cause unconfirmed; inspect missing/empty/error/truncated file entries. Device checks may predate this session.");
        putJson(entries, "archive-info.json", info);
        entries.put(
                "README.txt",
                ("Start with summary.json, then archive-info.json for missing files and truncation.\n"
                                + "Mesa and Android presentation logs are scoped to the session; older archives are never refreshed from a newer session.\n"
                                + "Repeated nested reports and exact duplicate text are replaced by archive file references. Live reports are unchanged.\n"
                                + "$APP_FILES, $APP_CACHE and $NATIVE_LIB replace private installation roots. Game/mod names and relative paths remain. REA_IME_TOKEN is excluded.\n"
                                + "Device-report.json is the last Android EGL/GLES check, not the game's Vulkan context or a current memory sample. Actual Vulkan extensions are not probed by export.\n"
                                + "Engine output and memory samples are bounded tails; samples can miss final peaks and GPU allocations. Exit code 137 alone is not OOM; window/launcher cleanup is separate evidence.\n"
                                + "No saves, game assets, device-wide logcat or screenshots are included.\n")
                        .getBytes(StandardCharsets.UTF_8));
        File pending = File.createTempFile("session-", ".pending", dir);
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(pending))) {
                for (Map.Entry<String, byte[]> item : entries.entrySet())
                    entry(zip, item.getKey(), item.getValue());
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

    private static void putJson(Map<String, byte[]> entries, String name, JSONObject value) {
        entries.put(name, value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static JSONObject json(Map<String, byte[]> entries, JSONObject inventory, String name)
            throws Exception {
        byte[] bytes = entries.get(name);
        if (bytes == null) return null;
        try {
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (org.json.JSONException invalid) {
            entries.remove(name);
            inventory.put(name, new JSONObject().put("status", "invalid-json"));
            return null;
        }
    }

    private static void readSource(
            Map<String, byte[]> entries,
            JSONObject inventory,
            String name,
            File source,
            boolean boundedTail,
            boolean rolling)
            throws Exception {
        JSONObject info =
                new JSONObject()
                        .put("limitBytes", boundedTail ? SessionDiagnostics.LIMIT : 2097152);
        inventory.put(name, info);
        if (Files.isSymbolicLink(source.toPath())
                || Files.isSymbolicLink(source.toPath().getParent())) {
            info.put("status", "unsafe-link");
            return;
        }
        File previous = new File(source.getPath() + ".previous");
        if (!source.isFile() && !(rolling && previous.isFile())) {
            info.put("status", "missing");
            return;
        }
        long size =
                source.length()
                        + (rolling && !Files.isSymbolicLink(previous.toPath())
                                ? previous.length()
                                : 0);
        info.put("sourceBytes", size).put("modifiedAtEpochMillis", source.lastModified());
        if (!boundedTail && size > 2097152) {
            info.put("status", "too-large");
            return;
        }
        try {
            byte[] bytes =
                    rolling
                            ? rollingTail(source, SessionDiagnostics.LIMIT)
                            : boundedTail
                                    ? tail(source, SessionDiagnostics.LIMIT)
                                    : Files.readAllBytes(source.toPath());
            if (bytes == null) {
                info.put("status", "missing");
                return;
            }
            info.put("status", bytes.length == 0 ? "empty" : "included")
                    .put("truncated", size > bytes.length)
                    .put("hasPreviousSegment", rolling && previous.isFile());
            // Empty JSON is recorded as empty, rather than aborting an otherwise useful export.
            if (bytes.length > 0 || !name.endsWith(".json")) entries.put(name, bytes);
        } catch (IOException unavailable) {
            info.put("status", "read-error")
                    .put("errorType", unavailable.getClass().getSimpleName());
        }
    }

    private static boolean safe(File file, int limit) {
        return file.isFile() && file.length() <= limit && !Files.isSymbolicLink(file.toPath());
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

    private static byte[] engineLog(File files, long epoch, JSONObject engine, JSONObject info)
            throws Exception {
        File profile;
        try {
            profile = GameDocumentIds.directory(files, GameProfiles.ID);
        } catch (IOException absent) {
            profile = null;
        }
        if (profile != null) {
            File user = new File(profile, "userdata"), log = new File(user, "factorio-current.log");
            if (!Files.isSymbolicLink(user.toPath()) && log.lastModified() >= epoch) {
                try {
                    byte[] bytes = tail(log, SessionDiagnostics.LIMIT);
                    if (bytes != null) {
                        info.put("status", bytes.length == 0 ? "empty" : "included")
                                .put("source", "current-profile-log")
                                .put("sourceBytes", log.length())
                                .put("modifiedAtEpochMillis", log.lastModified())
                                .put("truncated", log.length() > bytes.length);
                        return bytes;
                    }
                } catch (IOException unavailable) {
                    info.put("status", "read-error")
                            .put("readErrorType", unavailable.getClass().getSimpleName());
                }
            }
        }
        if (engine == null) return null;
        String retained = engine.optString("engineLog");
        byte[] bytes = retained.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) return null;
        byte[] tail =
                Arrays.copyOfRange(
                        bytes, Math.max(0, bytes.length - SessionDiagnostics.LIMIT), bytes.length);
        info.put("status", "included")
                .put("source", "retained-session-game-log")
                .put("originalSizeKnown", engine.has("engineLogSessionBytes"))
                .put("sourceBytes", engine.optLong("engineLogSessionBytes", bytes.length))
                .put(
                        "truncated",
                        engine.optBoolean("engineLogTruncated") || bytes.length > tail.length);
        return tail;
    }
}
