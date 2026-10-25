package org.factorio.local.launcher;

import android.content.Context;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.json.JSONObject;

/** User-started engine lifetime and stable writable profile, separate from bounded probes. */
public final class GameSession implements AutoCloseable {
    private volatile boolean stopRequested, backgroundPaused;
    private java.lang.Process engine;
    private AudioDiskBridge audio;
    private SessionDiagnostics diagnostics;

    public void setDiagnosticSession(File files, long epoch) {
        try {
            diagnostics = new SessionDiagnostics(files, epoch);
        } catch (IOException unavailable) {
            android.util.Log.w("FactorioLauncher", "Session diagnostics unavailable", unavailable);
        }
    }

    SessionDiagnostics diagnostics() {
        return diagnostics;
    }

    public File presentationLog(File files) {
        return diagnostics == null
                ? new File(files, "direct-present-report.txt")
                : diagnostics.graphicsLog("direct-present-report.txt");
    }

    public void recordPhase(String phase) {
        if (diagnostics != null) diagnostics.phase(phase);
    }

    public void recordWindowTermination() {
        if (diagnostics != null) diagnostics.windowTermination(android.os.Process.myPid());
    }

    private boolean engineStopped;
    private long backgroundPauses, backgroundResumes;

    public synchronized void setBackgroundPaused(boolean paused) throws Exception {
        backgroundPaused = paused;
        applyBackgroundState();
    }

    private void applyBackgroundState() throws Exception {
        if (engine == null || !engine.isAlive()) return;
        boolean stop = backgroundPaused && !stopRequested;
        if (stop != engineStopped) {
            RuntimeProbe.signalOwnChild(
                    engine,
                    stop ? android.system.OsConstants.SIGSTOP : android.system.OsConstants.SIGCONT);
            engineStopped = stop;
            if (stop) backgroundPauses++;
            else backgroundResumes++;
        }
        if (audio != null) audio.setBackgroundPaused(stop);
    }

    synchronized void attachEngine(java.lang.Process child, AudioDiskBridge bridge)
            throws Exception {
        engine = child;
        audio = bridge;
        engineStopped = false;
        applyBackgroundState();
    }

    synchronized void detachEngine(java.lang.Process child) {
        if (engine == child) {
            engine = null;
            audio = null;
            engineStopped = false;
        }
    }

    public synchronized JSONObject lifecycleState() throws Exception {
        return new JSONObject()
                .put("backgroundPauseRequested", backgroundPaused)
                .put("engineStoppedForBackground", engineStopped)
                .put("backgroundPauseCount", backgroundPauses)
                .put("backgroundResumeCount", backgroundResumes);
    }

    /** Only a live :window process with its own non-ended report can be resumed. */
    public static boolean hasActiveWindow(Context context) {
        try {
            File file = new File(context.getFilesDir(), "game-session-report.json");
            if (!file.isFile() || file.length() > 2097152) return false;
            JSONObject r =
                    new JSONObject(
                            new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            if (!r.optBoolean("userStarted")
                    || r.optBoolean("activityClosed")
                    || r.optBoolean("sessionComplete")
                    || r.has("error")) return false;
            int pid = r.optInt("pid", -1);
            if (pid <= 0 || r.optInt("uid", -1) != android.os.Process.myUid()) return false;
            String status =
                    new String(
                            Files.readAllBytes(new File("/proc/" + pid + "/status").toPath()),
                            StandardCharsets.UTF_8);
            if (!java.util.regex.Pattern.compile(
                            "(?m)^Uid:\\s+" + android.os.Process.myUid() + "(?:\\s|$)")
                    .matcher(status)
                    .find()) return false;
            String name =
                    new String(
                                    Files.readAllBytes(
                                            new File("/proc/" + pid + "/cmdline").toPath()),
                                    StandardCharsets.UTF_8)
                            .split("\\u0000", 2)[0];
            return name.equals(context.getPackageName() + ":window");
        } catch (Exception absent) {
            return false;
        }
    }

    private java.util.Map<String, String> keyboardEnvironment = java.util.Collections.emptyMap();

    public synchronized void setKeyboardEnvironment(java.util.Map<String, String> environment) {
        keyboardEnvironment = new java.util.HashMap<>(environment);
    }

    synchronized java.util.Map<String, String> keyboardEnvironment() {
        return new java.util.HashMap<>(keyboardEnvironment);
    }

    private final String graphicsPreset;
    private final int spriteLoadingLimit;
    private final boolean virtualGamepad;

    public GameSession(String preset, int loadingLimit) {
        this(preset, loadingLimit, false);
    }

    public GameSession(String preset, int loadingLimit, boolean virtualGamepad) {
        this.virtualGamepad = virtualGamepad;
        graphicsPreset = preset == null ? "compat" : preset;
        spriteLoadingLimit =
                GameProfileConfig.validSpriteLoadingLimit(loadingLimit) ? loadingLimit : 1;
    }

    boolean virtualGamepadEnabled() {
        return virtualGamepad;
    }

    String graphicsPreset() {
        return graphicsPreset;
    }

    int spriteLoadingLimit() {
        return spriteLoadingLimit;
    }

    public boolean stopping() {
        return stopRequested;
    }

    @Override
    public synchronized void close() {
        stopRequested = true;
        try {
            applyBackgroundState();
        } catch (Exception ignored) {
        }
    }

    public JSONObject run(Context context, File windowTemp, RuntimeProbe.Progress progress)
            throws Exception {
        File profile = profileDirectory(context);
        try (RandomAccessFile lockFile =
                        new RandomAccessFile(new File(profile, "session.lock"), "rw");
                FileChannel channel = lockFile.getChannel()) {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null;
            }
            if (lock == null) throw new IOException("这个游戏档案已在运行，请先结束之前的会话");
            try {
                if (stopRequested) throw new InterruptedIOException("启动已取消");
                JSONObject result =
                        RuntimeProbe.collectSession(context, windowTemp, this, progress);
                if (diagnostics != null) diagnostics.sessionResult(result);
                Path pending =
                        new File(context.getFilesDir(), "game-session-engine.json.pending")
                                .toPath();
                Files.write(pending, result.toString(2).getBytes(StandardCharsets.UTF_8));
                Files.move(
                        pending,
                        new File(context.getFilesDir(), "game-session-engine.json").toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                return result;
            } finally {
                try {
                    VirtualGamepadConfig.restore(new File(profile, "config.ini").toPath());
                } finally {
                    lock.release();
                }
            }
        }
    }

    static File profileDirectory(Context context) throws Exception {
        return GameProfiles.directory(context.getFilesDir());
    }

    static File prepareProfile(Context context, File gameDirectory, String preset, int loadingLimit)
            throws Exception {
        return prepareProfile(context, gameDirectory, preset, loadingLimit, false);
    }

    static File prepareProfile(
            Context context,
            File gameDirectory,
            String preset,
            int loadingLimit,
            boolean virtualGamepad)
            throws Exception {
        File profile = profileDirectory(context), userdata = new File(profile, "userdata");
        if (!userdata.isDirectory() && !userdata.mkdirs()) throw new IOException("无法创建持久游戏数据目录");
        File config = new File(profile, "config.ini");
        VirtualGamepadConfig.restore(config.toPath());
        if (config.length() > 1024 * 1024) throw new IOException("配置文件过大，已保留原文件");
        File template = new File(gameDirectory, "config/config.ini");
        String bundled;
        try (InputStream input = context.getAssets().open("config/default-config.ini");
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[8192];
            for (int n; (n = input.read(bytes)) != -1; ) output.write(bytes, 0, n);
            bundled = new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
        String original =
                config.isFile()
                        ? new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8)
                        : template.isFile() && template.length() <= 1024 * 1024
                                ? new String(
                                        Files.readAllBytes(template.toPath()),
                                        StandardCharsets.UTF_8)
                                : bundled;
        String repaired = GameProfileConfig.ensureFormatHeader(original, bundled);
        File formatBackup = new File(profile, "config.before-format-repair.ini");
        if (config.isFile() && !repaired.equals(original) && !formatBackup.exists())
            Files.copy(config.toPath(), formatBackup.toPath());
        original = repaired;
        File descriptor = new File(profile, "profile.json");
        if (descriptor.isFile()) {
            JSONObject old = GameImporter.readJson(descriptor);
            if (old.optInt("graphicsPolicyVersion", 0) < 2
                    && !"game".equals(old.optString("graphicsPreset")))
                original = GameProfileConfig.releaseLegacyGpuOverrides(original);
        }
        String read = new File(gameDirectory, "data").getCanonicalPath(),
                write = userdata.getCanonicalPath();
        String updated = GameProfileConfig.mapPaths(original, read, write);
        updated = GameProfileConfig.initializeLocale(updated, AppLanguage.gameLocale(context));
        updated = GameProfileConfig.applyGraphicsPreset(updated, preset);
        updated = GameProfileConfig.applySpriteLoadingLimit(updated, loadingLimit);
        File graphicsBackup = new File(profile, "config.before-graphics-presets.ini");
        if ((!"game".equals(preset) || loadingLimit != 0)
                && config.isFile()
                && !graphicsBackup.exists()
                && !updated.equals(original)) Files.copy(config.toPath(), graphicsBackup.toPath());
        Path pending = new File(profile, "config.ini.pending").toPath();
        Files.write(pending, updated.getBytes(StandardCharsets.UTF_8));
        Files.move(
                pending,
                config.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        if (virtualGamepad) VirtualGamepadConfig.enable(config.toPath());
        JSONObject metadata =
                new JSONObject()
                        .put("schema", 1)
                        .put("id", profile.getName())
                        .put("graphicsPolicyVersion", 2)
                        .put("virtualGamepadEnabled", virtualGamepad)
                        .put(
                                "formatBackup",
                                formatBackup.isFile()
                                        ? formatBackup.getAbsolutePath()
                                        : JSONObject.NULL)
                        .put("graphicsPreset", preset)
                        .put("spriteLoadingLimit", loadingLimit)
                        .put(
                                "graphicsBackup",
                                graphicsBackup.isFile()
                                        ? graphicsBackup.getAbsolutePath()
                                        : JSONObject.NULL)
                        .put("locale", GameProfileConfig.readLocale(updated))
                        .put("initialLocaleFallback", AppLanguage.gameLocale(context))
                        .put("readData", read)
                        .put("writeData", write)
                        .put("configuration", config.getAbsolutePath())
                        .put("saves", new File(userdata, "saves").getAbsolutePath())
                        .put("mods", new File(userdata, "mods").getAbsolutePath());
        Files.write(
                new File(profile, "profile.json").toPath(),
                metadata.toString(2).getBytes(StandardCharsets.UTF_8));
        return profile;
    }
}
