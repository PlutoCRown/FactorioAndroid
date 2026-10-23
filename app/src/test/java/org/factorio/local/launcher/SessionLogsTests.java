package org.factorio.local.launcher;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.json.JSONObject;

/**
 * Refresh delayed exports, retain the right session, and preserve evidence without UI callbacks.
 */
public final class SessionLogsTests {
    private static int checks;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }

    private static void write(File file, String text) throws Exception {
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static String member(File archive, String name) throws Exception {
        try (ZipFile zip = new ZipFile(archive)) {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) return null;
            try (InputStream in = zip.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private static void report(File root, long epoch, String status) throws Exception {
        write(
                new File(root, "game-session-report.json"),
                new JSONObject()
                        .put("collectedAtEpochMillis", epoch)
                        .put("updatedAtEpochMillis", epoch + 1)
                        .put("phase", status)
                        .put(
                                "engineProbeProgress",
                                new JSONObject().put("outputTail", "stdout fallback"))
                        .toString());
    }

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("session-logs-").toFile();
        try {
            report(root, 1000, "running");
            write(new File(root, "last-process-exits.json"), "{\"generation\":1}");
            File archive = SessionLogs.capture(root);
            check(
                    member(archive, "session-report.json").contains("running"),
                    "first running snapshot captured");
            check(
                    "stdout fallback".equals(member(archive, "factorio-current.log")),
                    "progress output retained without writeData or engineLog");
            report(root, 1000, "finished");
            write(new File(root, "last-process-exits.json"), "{\"generation\":2}");
            SessionLogs.refresh(root, "logs:" + archive.getName());
            check(
                    member(archive, "session-report.json").contains("finished")
                            && member(archive, "last-process-exits.json").contains(":2"),
                    "same session exports refresh report and OS exits");
            long captured =
                    new JSONObject(member(archive, "archive-info.json"))
                            .getLong("capturedAtEpochMillis");
            check(captured > 1000, "archive capture time independent of session start");
            byte[] previous = Files.readAllBytes(archive.toPath());
            report(root, 2000, "new-session");
            SessionLogs.refresh(root, "logs:" + archive.getName());
            check(
                    Arrays.equals(previous, Files.readAllBytes(archive.toPath())),
                    "old selected archive never contains new session data");
            File proc = new File(root, "proc-fixture");
            String stat =
                    "12345 (worker with ) spaces) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 999 20";
            write(new File(proc, "12345/stat"), stat);
            write(
                    new File(proc, "12345/status"),
                    "State: R\nVmRSS: 300 kB\nVmHWM: 400 kB\nVmSwap: 100 kB\n");
            write(new File(proc, "54321/status"), "VmRSS: 200 kB\n");
            write(
                    new File(proc, "meminfo"),
                    "MemTotal: 10000 kB\nMemAvailable: 8000 kB\nSwapFree: 4000 kB\n");
            SessionDiagnostics trace = new SessionDiagnostics(root, 2000, proc);
            trace.started("Process[pid=12345, hasExited=false]", 54321, null);
            trace.sample();
            write(new File(proc, "12345/status"), "VmRSS: 900 kB\nVmHWM: 1000 kB\n");
            write(new File(proc, "meminfo"), "MemTotal: 10000 kB\nMemAvailable: 7000 kB\n");
            trace.sample();
            write(new File(proc, "12345/stat"), stat.replace("999", "1001"));
            write(new File(proc, "12345/status"), "VmRSS: 9999 kB\nVmHWM: 9999 kB\n");
            trace.sample();
            trace.output("last output before death".getBytes(StandardCharsets.UTF_8), 24);
            trace.finished(
                    new JSONObject()
                            .put("exitCode", 137)
                            .put("terminationRequestedBy", "unconfirmed"));
            trace.windowTermination(54321);
            File fresh = SessionLogs.capture(root);
            check(
                    new JSONObject(member(fresh, "engine-result.json")).getInt("exitCode") == 137,
                    "engine exit retained while UI report is unfinished");
            check(
                    member(fresh, "engine-stdout.log").contains("last output before death"),
                    "stdout preserved independently of UI callbacks");
            check(
                    member(fresh, "window-termination.json").contains("activityDestroyed"),
                    "window self cleanup has separate evidence");
            JSONObject summary = new JSONObject(member(fresh, "memory-summary.json"));
            check(
                    summary.getLong("peakSampledEngineRssKiB") == 900
                            && summary.getLong("peakObservedEngineVmHwmKiB") == 1000
                            && summary.getLong("lowestSampledSystemAvailableKiB") == 7000,
                    "sampled peak and low water marks survive later samples");
            check(
                    member(fresh, "memory-samples.jsonl").contains("pidReused")
                            && !member(fresh, "memory-samples.jsonl").contains("9999 kB"),
                    "PID reuse cannot collect a different process");
            check(
                    new JSONObject(member(fresh, "archive-info.json"))
                            .getBoolean("engineResultAvailable"),
                    "final evidence explicitly marked");
            check(
                    SessionDiagnostics.pid("Process[pid=42, hasExited=false]") == 42,
                    "child PID parsed");
            check(SessionDiagnostics.pid("unknown") == -1, "unknown PID stays unknown");
            check(
                    "999".equals(SessionDiagnostics.startTicks(stat)),
                    "process identity handles spaces and parentheses");
            check(
                    "300 kB"
                                    .equals(
                                            SessionDiagnostics.fields(
                                                            "VmRSS: 300 kB\nUid: 123\nVmHWM: 400 kB")
                                                    .get("VmRSS"))
                            && !SessionDiagnostics.fields("Uid: 123").containsKey("Uid"),
                    "memory parser keeps defined fields and units");
            File log = new File(root, "rolling.log");
            SessionDiagnostics.RollingLog rolling = new SessionDiagnostics.RollingLog(log, 8);
            rolling.append("123456".getBytes(StandardCharsets.UTF_8), 6);
            rolling.append("abcdef".getBytes(StandardCharsets.UTF_8), 6);
            check(
                    log.length() == 6 && new File(log.getPath() + ".previous").length() == 6,
                    "rolling logs bounded to current and previous segment");
            rolling.append("0123456789012345".getBytes(StandardCharsets.UTF_8), 16);
            check(
                    log.length() == 8
                            && "89012345"
                                    .equals(
                                            new String(
                                                    Files.readAllBytes(log.toPath()),
                                                    StandardCharsets.UTF_8)),
                    "oversize chunk retains only tail");
            write(new File(root, "game-session-report.json"), "invalid JSON");
            byte[] valid = Files.readAllBytes(fresh.toPath());
            try {
                SessionLogs.capture(root);
                throw new AssertionError("invalid report accepted");
            } catch (org.json.JSONException expected) {
                checks++;
            }
            check(
                    Arrays.equals(valid, Files.readAllBytes(fresh.toPath())),
                    "failed refresh preserves existing archive");
            System.out.println("SessionLogsTests: " + checks + " checks passed");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root.toPath())) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(path);
            }
        }
    }
}
