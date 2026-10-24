package org.factorio.local.launcher;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.json.JSONObject;

/** Bounded, per-session evidence written by engine workers, independent of UI callbacks. */
final class SessionDiagnostics {
    static final int LIMIT = 1024 * 1024;
    private final File directory, proc;
    private final long epoch;
    private final RollingLog stdout, memory, events;
    private String lastPhase;
    private int enginePid, ownerPid;
    private String startTicks;
    private volatile String lastError;
    private long peakRss, peakHwm, lowestAvailable = Long.MAX_VALUE;
    private int samples;
    private JSONObject processState;

    SessionDiagnostics(File files, long epoch) throws IOException {
        this(files, epoch, new File("/proc"));
    }

    SessionDiagnostics(File files, long epoch, File proc) throws IOException {
        this.proc = proc;
        this.epoch = epoch;
        directory = directory(files, epoch);
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Cannot create session diagnostics");
        stdout = new RollingLog(new File(directory, "engine-stdout.log"), LIMIT);
        memory = new RollingLog(new File(directory, "memory-samples.jsonl"), LIMIT);
        events = new RollingLog(new File(directory, "session-events.jsonl"), LIMIT);
        prune(directory);
    }

    static File directory(File files, long epoch) throws IOException {
        if (epoch <= 0) throw new IOException("Invalid diagnostic session");
        File parent = new File(files, "session-diagnostics"),
                dir = new File(parent, "session-" + epoch);
        if (Files.isSymbolicLink(parent.toPath()) || Files.isSymbolicLink(dir.toPath()))
            throw new IOException("Diagnostic directory is a link");
        return dir;
    }

    File graphicsLog(String name) {
        if (!name.equals("mesa.log") && !name.equals("direct-present-report.txt"))
            throw new IllegalArgumentException("Unknown graphics log");
        return new File(directory, name);
    }

    private static void prune(File current) {
        File[] dirs =
                current.getParentFile()
                        .listFiles(
                                f ->
                                        f.getName().matches("session-[0-9]+")
                                                && f.isDirectory()
                                                && !Files.isSymbolicLink(f.toPath()));
        if (dirs == null) return;
        Arrays.sort(dirs, Comparator.comparing(File::getName).reversed());
        int kept = 1;
        for (File dir : dirs) {
            if (dir.equals(current) || kept++ < 20) continue;
            File[] children = dir.listFiles();
            if (children != null)
                for (File child : children)
                    if (child.isFile() && !Files.isSymbolicLink(child.toPath())) child.delete();
            dir.delete();
        }
    }

    static int pid(String description) {
        Matcher match = Pattern.compile("pid=(\\d+)").matcher(description);
        return match.find() ? Integer.parseInt(match.group(1)) : -1;
    }

    private interface Write {
        void run() throws Exception;
    }

    private void attempt(Write write) {
        try {
            write.run();
        } catch (Exception error) {
            lastError = error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }

    void started(String description, int owner, File engineLog) {
        enginePid = pid(description);
        ownerPid = owner;
        attempt(
                () -> {
                    try {
                        if (enginePid > 0)
                            startTicks =
                                    startTicks(read(new File(proc, enginePid + "/stat"), 8192));
                    } catch (IOException unavailable) {
                        /* Keep PID and start time even when proc is restricted. */
                    }
                    JSONObject state =
                            new JSONObject()
                                    .put("sessionEpoch", epoch)
                                    .put("enginePid", enginePid)
                                    .put("windowPid", ownerPid)
                                    .put("startedAtEpochMillis", System.currentTimeMillis())
                                    .put("state", "running");
                    if (startTicks != null) state.put("engineStartTicks", startTicks);
                    if (engineLog != null) state.put("engineLogPath", engineLog.getAbsolutePath());
                    processState = state;
                    writeJson("engine-process.json", state);
                });
    }

    void output(byte[] bytes, int length) {
        attempt(() -> stdout.append(bytes, length));
    }

    synchronized void phase(String phase) {
        if (phase.equals(lastPhase)) return;
        lastPhase = phase;
        attempt(
                () -> {
                    byte[] row =
                            (new JSONObject()
                                                    .put("sessionEpoch", epoch)
                                                    .put("timestamp", System.currentTimeMillis())
                                                    .put("phase", phase)
                                                    .toString()
                                            + "\n")
                                    .getBytes(StandardCharsets.UTF_8);
                    events.append(row, row.length);
                });
    }

    void sample() {
        attempt(
                () -> {
                    JSONObject sample =
                            new JSONObject()
                                    .put("timestamp", System.currentTimeMillis())
                                    .put("enginePid", enginePid);
                    if (enginePid > 0) {
                        try {
                            String actual =
                                    startTicks(read(new File(proc, enginePid + "/stat"), 8192));
                            if (startTicks != null && !startTicks.equals(actual))
                                sample.put("engineStatus", "pidReused");
                            else {
                                Map<String, String> status =
                                        fields(read(new File(proc, enginePid + "/status"), 16384));
                                sample.put("engine", new JSONObject(status));
                                peakRss = Math.max(peakRss, kib(status.get("VmRSS")));
                                peakHwm = Math.max(peakHwm, kib(status.get("VmHWM")));
                            }
                        } catch (IOException absent) {
                            sample.put("engineStatus", "unavailable")
                                    .put("engineReadError", absent.getClass().getSimpleName());
                        }
                    }
                    try {
                        sample.put(
                                "window",
                                new JSONObject(
                                        fields(read(new File(proc, ownerPid + "/status"), 16384))));
                    } catch (IOException absent) {
                        sample.put("windowStatus", "unavailable");
                    }
                    Map<String, String> system = fields(read(new File(proc, "meminfo"), 16384));
                    JSONObject available = new JSONObject();
                    for (String key :
                            new String[] {"MemTotal", "MemAvailable", "SwapTotal", "SwapFree"})
                        if (system.containsKey(key)) available.put(key, system.get(key));
                    sample.put("system", available);
                    if (system.containsKey("MemAvailable"))
                        lowestAvailable =
                                Math.min(lowestAvailable, kib(system.get("MemAvailable")));
                    try {
                        sample.put("memoryPressure", read(new File(proc, "pressure/memory"), 4096));
                    } catch (IOException unsupported) {
                        /* Optional kernel interface. */
                    }
                    byte[] row = (sample.toString() + "\n").getBytes(StandardCharsets.UTF_8);
                    memory.append(row, row.length);
                    samples++;
                    writeJson(
                            "memory-summary.json",
                            new JSONObject()
                                    .put("sessionEpoch", epoch)
                                    .put("sampleCount", samples)
                                    .put("updatedAtEpochMillis", System.currentTimeMillis())
                                    .put("peakSampledEngineRssKiB", peakRss)
                                    .put("peakObservedEngineVmHwmKiB", peakHwm)
                                    .put(
                                            "lowestSampledSystemAvailableKiB",
                                            lowestAvailable == Long.MAX_VALUE
                                                    ? JSONObject.NULL
                                                    : lowestAvailable)
                                    .put(
                                            "scope",
                                            "One-second process samples; excludes unreported GPU allocations and may miss the final instant"));
                });
    }

    void finished(JSONObject result) {
        sample();
        attempt(
                () -> {
                    if (processState != null) {
                        processState
                                .put("state", result.has("exitCode") ? "exited" : "unconfirmed")
                                .put("endedAtEpochMillis", System.currentTimeMillis());
                        if (result.has("exitCode"))
                            processState.put("exitCode", result.getInt("exitCode"));
                        writeJson("engine-process.json", processState);
                    }
                });
        attempt(
                () ->
                        writeJson(
                                "engine-result.json",
                                new JSONObject(result.toString())
                                        .put("sessionEpoch", epoch)
                                        .put("enginePid", enginePid)
                                        .put("windowPid", ownerPid)
                                        .put("recordedAtEpochMillis", System.currentTimeMillis())
                                        .put(
                                                "diagnosticWriteError",
                                                lastError == null ? JSONObject.NULL : lastError)));
    }

    void sessionResult(JSONObject result) {
        attempt(
                () ->
                        writeJson(
                                "session-engine-result.json",
                                new JSONObject(result.toString())
                                        .put("sessionEpoch", epoch)
                                        .put("recordedAtEpochMillis", System.currentTimeMillis())));
    }

    void windowTermination(int owner) {
        if (ownerPid == 0) ownerPid = owner;
        attempt(
                () ->
                        writeJson(
                                "window-termination.json",
                                new JSONObject()
                                        .put("sessionEpoch", epoch)
                                        .put("timestamp", System.currentTimeMillis())
                                        .put("windowPid", ownerPid)
                                        .put("enginePid", enginePid)
                                        .put("requestedBy", "window")
                                        .put("reason", "activityDestroyed")
                                        .put("signalRequested", "SIGKILL")
                                        .put(
                                                "scope",
                                                "Window cleanup intent, not an engine OOM diagnosis or proof of normal engine exit")));
    }

    private void writeJson(String name, JSONObject value) throws Exception {
        atomicWrite(new File(directory, name), value.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    static void atomicWrite(File target, byte[] bytes) throws IOException {
        if (Files.isSymbolicLink(target.toPath()))
            throw new IOException("Diagnostic file is a link");
        File pending = File.createTempFile("diagnostic-", ".pending", target.getParentFile());
        try {
            Files.write(pending.toPath(), bytes);
            Files.move(
                    pending.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(pending.toPath());
        }
    }

    private static String read(File file, int limit) throws IOException {
        try (InputStream in = new FileInputStream(file);
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int n;
            while ((n = in.read(buffer, 0, Math.min(buffer.length, limit - out.size()))) > 0)
                out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static String startTicks(String stat) throws IOException {
        int close = stat.lastIndexOf(')');
        String[] values = stat.substring(close + 1).trim().split("\\s+");
        if (close < 0 || values.length < 20) throw new IOException("Incomplete process stat");
        return values[19]; // Field 22; the parenthesized command may contain spaces.
    }

    static Map<String, String> fields(String text) {
        Set<String> wanted =
                new HashSet<>(
                        Arrays.asList(
                                "State",
                                "VmRSS",
                                "VmHWM",
                                "VmSwap",
                                "VmSize",
                                "RssAnon",
                                "RssFile",
                                "RssShmem",
                                "Threads",
                                "MemTotal",
                                "MemAvailable",
                                "SwapTotal",
                                "SwapFree"));
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && wanted.contains(line.substring(0, colon)))
                values.put(line.substring(0, colon), line.substring(colon + 1).trim());
        }
        return values;
    }

    private static long kib(String value) {
        return value == null ? 0 : Long.parseLong(value.split("\\s+")[0]);
    }

    static final class RollingLog {
        private final File file;
        private final int limit;

        RollingLog(File file, int limit) {
            this.file = file;
            this.limit = limit;
        }

        synchronized void append(byte[] bytes, int length) throws IOException {
            if (Files.isSymbolicLink(file.toPath())) throw new IOException("Log file is a link");
            if (file.length() + length > limit && file.exists())
                Files.move(
                        file.toPath(),
                        new File(file.getPath() + ".previous").toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            int keep = Math.min(length, limit);
            try (OutputStream out = new FileOutputStream(file, true)) {
                out.write(bytes, length - keep, keep);
            }
        }
    }
}
