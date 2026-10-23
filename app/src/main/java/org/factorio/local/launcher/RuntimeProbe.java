package org.factorio.local.launcher;

import android.content.Context;
import android.os.Process;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Engine execution with an APK-installed GNU loader and explicit session ownership. */
public final class RuntimeProbe {
    public interface Progress {
        void update(String phase, JSONObject detail) throws Exception;
    }

    public static JSONObject applicationIdentity(Context context) throws Exception {
        android.content.pm.PackageInfo info =
                context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        return new JSONObject()
                .put("packageName", context.getPackageName())
                .put("versionName", info.versionName)
                .put("versionCode", info.versionCode)
                .put("exitPolicyVersion", SessionExit.POLICY_VERSION);
    }

    static void signalOwnChild(java.lang.Process child, int signal) throws Exception {
        if (!child.isAlive()) return;
        java.util.regex.Matcher match =
                java.util.regex.Pattern.compile("pid=(\\d+)").matcher(child.toString());
        if (!match.find()) throw new IOException("Cannot identify diagnostic child PID");
        int pid = Integer.parseInt(match.group(1));
        String status =
                new String(
                        Files.readAllBytes(Paths.get("/proc/" + pid + "/status")),
                        StandardCharsets.UTF_8);
        java.util.regex.Matcher parent =
                java.util.regex.Pattern.compile("(?m)^PPid:\\s+(\\d+)").matcher(status);
        java.util.regex.Matcher uid =
                java.util.regex.Pattern.compile("(?m)^Uid:\\s+(\\d+)").matcher(status);
        if (!parent.find()
                || !uid.find()
                || Integer.parseInt(parent.group(1)) != Process.myPid()
                || Integer.parseInt(uid.group(1)) != Process.myUid())
            throw new IOException("Diagnostic child identity no longer matches");
        android.system.Os.kill(pid, signal);
    }

    static JSONObject killOwnChild(java.lang.Process child) throws Exception {
        JSONObject result = new JSONObject();
        if (!child.isAlive()) return result.put("alreadyExited", true);
        signalOwnChild(child, android.system.OsConstants.SIGKILL);
        return result.put("parentAndUidVerified", true)
                .put("signal", "SIGKILL")
                .put("exited", child.waitFor(5, TimeUnit.SECONDS));
    }

    private static JSONObject command(List<String> arguments, File working, File temp)
            throws Exception {
        return command(arguments, working, temp, Collections.emptyMap());
    }

    private static JSONObject command(
            List<String> arguments, File working, File temp, Map<String, String> extraEnvironment)
            throws Exception {
        return command(arguments, working, temp, extraEnvironment, 30);
    }

    private static JSONObject command(
            List<String> arguments,
            File working,
            File temp,
            Map<String, String> extraEnvironment,
            int timeoutSeconds)
            throws Exception {
        return command(arguments, working, temp, extraEnvironment, timeoutSeconds, null, null);
    }

    private static JSONObject command(
            List<String> arguments,
            File working,
            File temp,
            Map<String, String> extraEnvironment,
            int timeoutSeconds,
            GameSession session,
            Progress progress)
            throws Exception {
        return command(
                arguments,
                working,
                temp,
                extraEnvironment,
                timeoutSeconds,
                session,
                progress,
                null);
    }

    private static JSONObject command(
            List<String> arguments,
            File working,
            File temp,
            Map<String, String> extraEnvironment,
            int timeoutSeconds,
            GameSession session,
            Progress progress,
            AudioDiskBridge audioBridge)
            throws Exception {
        return command(
                arguments,
                working,
                temp,
                extraEnvironment,
                timeoutSeconds,
                session,
                progress,
                audioBridge,
                null,
                0);
    }

    private static JSONObject command(
            List<String> arguments,
            File working,
            File temp,
            Map<String, String> extraEnvironment,
            int timeoutSeconds,
            GameSession session,
            Progress progress,
            AudioDiskBridge audioBridge,
            File startupLog,
            long logStart)
            throws Exception {
        JSONObject result =
                new JSONObject()
                        .put("arguments", new JSONArray(arguments))
                        .put("timeoutSeconds", timeoutSeconds);
        ProcessBuilder builder =
                new ProcessBuilder(arguments).directory(working).redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("HOME", working.getAbsolutePath());
        environment.put("TMPDIR", temp.getAbsolutePath());
        environment.put("LC_ALL", "C");
        environment.put("PATH", "/system/bin");
        environment.put("GLIBC_TUNABLES", "glibc.pthread.rseq=0");
        environment.putAll(extraEnvironment);
        Map<String, String> reportedEnvironment = new HashMap<>(extraEnvironment);
        reportedEnvironment.remove("REA_IME_TOKEN");
        result.put("extraEnvironment", new JSONObject(reportedEnvironment));
        result.put("glibcTunables", "glibc.pthread.rseq=0");
        java.lang.Process process;
        LiveGraphics liveGraphics = session == null ? null : new LiveGraphics();
        if (session != null && session.stopping()) throw new InterruptedIOException("启动已取消");
        try {
            process = builder.start();
        } catch (IOException e) {
            return result.put("started", false).put("error", e.toString()).put("passed", false);
        }
        SessionDiagnostics diagnostics = session == null ? null : session.diagnostics();
        if (diagnostics != null) {
            diagnostics.started(process.toString(), Process.myPid(), startupLog);
            diagnostics.sample();
        }
        if (startupLog != null)
            result.put("writeData", startupLog.getParentFile().getAbsolutePath());
        java.util.concurrent.atomic.AtomicReference<String> outputTail =
                new java.util.concurrent.atomic.AtomicReference<>("");
        FutureTask<String> reader =
                new FutureTask<>(
                        () -> {
                            ByteArrayOutputStream out = new ByteArrayOutputStream();
                            ByteArrayOutputStream tail = new ByteArrayOutputStream();
                            byte[] bytes = new byte[4096];
                            boolean truncated = false;
                            try (InputStream input = process.getInputStream()) {
                                for (int n; (n = input.read(bytes)) != -1; ) {
                                    if (diagnostics != null) diagnostics.output(bytes, n);
                                    if (liveGraphics != null)
                                        liveGraphics.accept(
                                                new String(bytes, 0, n, StandardCharsets.UTF_8));
                                    if (audioBridge != null)
                                        audioBridge.acceptEngineOutput(
                                                new String(bytes, 0, n, StandardCharsets.UTF_8));
                                    int keep = Math.min(n, 65536 - out.size());
                                    if (keep > 0) out.write(bytes, 0, keep);
                                    if (keep < n) truncated = true;
                                    if (tail.size() + n > 65536) {
                                        byte[] previous = tail.toByteArray();
                                        tail.reset();
                                        int retain = Math.min(previous.length, 65536 - n);
                                        tail.write(previous, previous.length - retain, retain);
                                    }
                                    tail.write(bytes, 0, n);
                                    outputTail.set(
                                            new String(tail.toByteArray(), StandardCharsets.UTF_8));
                                }
                            }
                            if (liveGraphics != null) liveGraphics.finish();
                            return new String(out.toByteArray(), StandardCharsets.UTF_8)
                                    + (truncated ? "\n[output truncated]" : "");
                        });
        Thread thread = new Thread(reader, "factorio-probe-output");
        thread.setDaemon(true);
        thread.start();
        try {
            if (session != null) session.attachEngine(process, audioBridge);
            boolean exited;
            if (session == null && audioBridge != null) {
                int elapsed = 0;
                while (!(exited =
                        process.waitFor(Math.min(5, timeoutSeconds - elapsed), TimeUnit.SECONDS))) {
                    elapsed += Math.min(5, timeoutSeconds - elapsed);
                    result.put("audioBridge", audioBridge.report());
                    if (progress != null) progress.update("audioStreaming", result);
                    if (elapsed >= timeoutSeconds) break;
                }
            } else if (session == null) exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            else {
                result.put("started", true)
                        .put("unlimitedSession", true)
                        .put("processDescription", process.toString());
                result.put("liveGraphics", new JSONObject(liveGraphics.snapshot()));
                if (progress != null) progress.update("engineProcessStarted", result);
                result.put("lifecycle", session.lifecycleState());
                int ticks = 0;
                while (!(exited = process.waitFor(1, TimeUnit.SECONDS)) && !session.stopping()) {
                    if (diagnostics != null) diagnostics.sample();
                    Map<String, String> live = liveGraphics.snapshot();
                    if (live.containsKey("fatalSignal")) {
                        result.put("fatalSignal", live.get("fatalSignal"))
                                .put("fatalLine", live.get("fatalLine"));
                        result.put("terminationRequestedBy", "launcher")
                                .put("terminationReason", "fatalSignalCleanup");
                        if (progress != null) progress.update("engineFatal", result);
                        process.destroy();
                        exited = process.waitFor(1, TimeUnit.SECONDS);
                        if (!exited) {
                            result.put("fatalTermination", killOwnChild(process));
                            exited = !process.isAlive();
                        }
                        break;
                    }
                    if (startupLog != null
                            && !liveGraphics.snapshot().containsKey("rendererLine")) {
                        String failure = StartupFailure.read(startupLog, logStart);
                        if (failure != null) {
                            result.put("startupError", failure);
                            result.put("terminationRequestedBy", "launcher")
                                    .put("terminationReason", "startupFailureCleanup");
                            process.destroy();
                            exited = process.waitFor(5, TimeUnit.SECONDS);
                            if (!exited) {
                                result.put("startupTermination", killOwnChild(process));
                                exited = !process.isAlive();
                            }
                            break;
                        }
                    }
                    result.put("lifecycle", session.lifecycleState());
                    ticks++;
                    if (ticks % 2 == 0) {
                        String tail = outputTail.get();
                        if (tail != null && !tail.isEmpty()) {
                            result.put("outputTail", tail);
                            if (progress != null) progress.update("engineOutput", result);
                        }
                    }
                    if (audioBridge != null && ticks % 5 == 0) {
                        result.put("liveGraphics", new JSONObject(liveGraphics.snapshot()));
                        result.put("audioBridge", audioBridge.report());
                        if (progress != null) progress.update("audioStreaming", result);
                    }
                }
                if (!exited && session.stopping()) {
                    result.put("userStopRequested", true);
                    result.put("terminationRequestedBy", "launcher")
                            .put("terminationReason", "userStop");
                    process.destroy();
                    exited = process.waitFor(5, TimeUnit.SECONDS);
                    if (!exited) result.put("stopTermination", killOwnChild(process));
                }
            }
            if (!exited && session == null)
                result.put("terminationRequestedBy", "launcher")
                        .put("terminationReason", "timeout")
                        .put("timeoutTermination", killOwnChild(process));
            result.put("started", true)
                    .put("timedOut", session == null && !exited)
                    .put("processDescription", process.toString());
            if (!process.isAlive()) result.put("exitCode", process.exitValue());
            try {
                result.put("output", reader.get(3, TimeUnit.SECONDS));
            } catch (Exception e) {
                result.put("outputError", e.toString());
            }
            result.put("outputTail", outputTail.get());
            if (liveGraphics != null) {
                Map<String, String> live = liveGraphics.snapshot();
                result.put("liveGraphics", new JSONObject(live));
                if (live.containsKey("fatalSignal"))
                    result.put("fatalSignal", live.get("fatalSignal"))
                            .put("fatalLine", live.get("fatalLine"));
                if (live.containsKey("userQuitRequested")) result.put("userQuitRequested", true);
                if (live.containsKey("normalExitCompleted"))
                    result.put("normalExitCompleted", true)
                            .put("userExitConfirmed", true)
                            .put("normalExitEvidenceSource", "stdout");
                if (live.containsKey("faultPhase"))
                    result.put("faultPhase", live.get("faultPhase"));
            }
            return result.put(
                    "passed",
                    exited
                            && process.exitValue() == 0
                            && !result.has("outputError")
                            && !result.has("fatalSignal"));
        } catch (Exception error) {
            result.put("monitorError", error.toString());
            throw error;
        } finally {
            if (session != null) session.detachEngine(process);
            if (process.isAlive()) {
                try {
                    if (!result.has("terminationReason"))
                        result.put("terminationRequestedBy", "launcher")
                                .put("terminationReason", "finalCleanup");
                    result.put("finalTermination", killOwnChild(process));
                } catch (Exception e) {
                    result.put("terminationError", e.toString());
                    process.destroyForcibly();
                }
            }
            if (!process.isAlive())
                result.put("exitCode", process.exitValue())
                        .put("processDescription", process.toString());
            if (!result.has("terminationRequestedBy"))
                result.put("terminationRequestedBy", "unconfirmed");
            try {
                process.getInputStream().close();
            } catch (IOException ignored) {
            }
            reader.cancel(true);
            result.put("outputTail", outputTail.get());
            if (diagnostics != null) diagnostics.finished(result);
        }
    }

    static JSONObject collect(Context context, String mode) throws Exception {
        return collect(context, mode, null, null, null);
    }

    static JSONObject collectSession(
            Context context, File serverTemp, GameSession control, Progress progress)
            throws Exception {
        return collect(context, "window-gl-directzink-egl-session", serverTemp, progress, control);
    }

    private static JSONObject collect(
            Context context, String mode, File serverTemp, Progress progress, GameSession control)
            throws Exception {
        boolean manual = control != null;
        if (!mode.equals("version")
                && !mode.equals("help")
                && !(manual && mode.equals("window-gl-directzink-egl-session")))
            throw new IOException("Unsupported runtime mode");
        boolean withDisplay = manual, graphical = manual, controller = manual;
        JSONObject result =
                new JSONObject()
                        .put("schema", 1)
                        .put("probeVersion", "0.2.0-runtime-probe")
                        .put("application", applicationIdentity(context))
                        .put(
                                "scope",
                                graphical
                                        ? "Ordinary app; bounded graphics initialization in isolated user directory"
                                        : "Ordinary Android application ProcessBuilder; bounded engine diagnostics, no graphics")
                        .put("mode", mode)
                        .put("uid", Process.myUid())
                        .put("graphicalGameLaunched", false)
                        .put("runtimeReady", false)
                        .put("collectedAtEpochMillis", System.currentTimeMillis());
        if (manual)
            result.put("userStarted", true)
                    .put("unlimitedSession", true)
                    .put("persistentProfile", true)
                    .put(
                            "scope",
                            "User-started engine session; persistent profile; no automatic graphics timeout");
        try {
            result.put(
                    "selinux",
                    new String(
                                    Files.readAllBytes(Paths.get("/proc/self/attr/current")),
                                    StandardCharsets.UTF_8)
                            .replace("\0", "")
                            .trim());
        } catch (Exception e) {
            result.put("selinuxReadError", e.toString());
        }
        JSONObject game = GameImporter.current(context);
        if (game == null) return result.put("passed", false).put("error", "请先导入官网安装包");
        String relative = game.optString("relativeDirectory");
        if (!relative.matches("games/game-[0-9a-f-]{36}/factorio"))
            throw new IOException("游戏目录记录无效");
        File gameDirectory = new File(context.getFilesDir(), relative).getCanonicalFile();
        File engine = new File(gameDirectory, "bin/arm64/factorio");
        if (progress != null) progress.update("verifyEngine", result);
        RuntimeLibraries.requireLibrary(engine);
        result.put("gameVersion", game.optString("version"));
        controller = manual;
        result.put("nativeGamepadAdapterSelected", controller);
        File nativeDirectory = new File(context.getApplicationInfo().nativeLibraryDir);
        result.put("nativeLibraryDirectory", nativeDirectory.getAbsolutePath());
        JSONObject manifest;
        try (InputStream input = context.getAssets().open("runtime-glibc.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = input.read(buffer)) != -1; ) {
                if (out.size() + n > 65536) throw new IOException("运行库清单过大");
                out.write(buffer, 0, n);
            }
            manifest = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        }
        String runtimeId = "gnu";
        if (!runtimeId.matches("[A-Za-z0-9_.-]+")
                || runtimeId.equals(".")
                || runtimeId.equals("..")) throw new IOException("运行库版本标识无效");
        result.put("glibcVersion", manifest.getString("version"))
                .put("runtimeVendor", manifest.optString("vendor", "unknown"));
        File aliases = new File(context.getFilesDir(), "runtime/" + runtimeId);
        if (!aliases.isDirectory() && !aliases.mkdirs()) throw new IOException("无法创建运行库引用目录");
        JSONArray libraries = manifest.getJSONArray("libraries"), verified = new JSONArray();
        Set<String> expected =
                new HashSet<>(
                        Arrays.asList(
                                "ld-linux-aarch64.so.1",
                                "libc.so.6",
                                "libm.so.6",
                                "libresolv.so.2"));
        File loader = null;
        for (int i = 0; i < libraries.length(); i++) {
            JSONObject library = libraries.getJSONObject(i);
            String name = library.getString("name"), packaged = library.getString("packagedName");
            if (!expected.remove(name) || !packaged.matches("librea_[a-z]+\\.so"))
                throw new IOException("运行库清单无效");
            File installed = new File(nativeDirectory, packaged);
            RuntimeLibraries.requireLibrary(installed);
            Path alias = new File(aliases, name).toPath();
            if (Files.isSymbolicLink(alias)) Files.delete(alias);
            else if (Files.exists(alias)) throw new IOException("运行库引用位置有意外文件");
            Files.createSymbolicLink(alias, installed.toPath());
            verified.put(
                    new JSONObject()
                            .put("name", name)
                            .put("installed", installed.getAbsolutePath()));
            if (name.equals("ld-linux-aarch64.so.1")) loader = installed;
        }
        if (!expected.isEmpty() || loader == null) throw new IOException("运行库不完整");
        result.put("libraries", verified)
                .put("glibcPackageSha256", manifest.getString("packageSha256"));
        String libraryPath = aliases.getAbsolutePath();
        if (withDisplay) {
            if (progress != null) progress.update("prepareX11", result);
            File clients = WindowRuntime.prepareClients(context);
            libraryPath += ":" + clients.getAbsolutePath();
            result.put("x11ClientsDirectory", clients.getAbsolutePath());
        }
        JSONObject directRuntime = null, dependencies = null;
        if (withDisplay) {
            dependencies = RuntimeLibraries.prepare(context);
            directRuntime = DirectZinkRuntime.prepare(context);
            libraryPath =
                    directRuntime.getString("directory")
                            + ":"
                            + libraryPath
                            + ":"
                            + dependencies.getString("directory");
            result.put("directZinkRuntime", directRuntime)
                    .put("rendererRequested", "zink")
                    .put("presentationPath", "gpu-ahb-android-surface");
        }
        List<String> base =
                new ArrayList<>(
                        Arrays.asList(
                                loader.getAbsolutePath(),
                                "--inhibit-cache",
                                "--library-path",
                                libraryPath));
        if (directRuntime != null)
            base.addAll(Arrays.asList("--preload", directRuntime.getString("tlsPreload")));
        if (progress != null) progress.update("runtimeVerified", result);
        List<String> list = new ArrayList<>(base);
        list.add("--list");
        list.add(engine.getAbsolutePath());
        JSONObject resolved = command(list, gameDirectory, context.getCacheDir());
        result.put("dependencyList", resolved);
        if (!resolved.optBoolean("passed"))
            return result.put("passed", false).put("error", "应用内加载器或依赖解析未通过，见诊断输出");
        List<String> version = new ArrayList<>(base);
        version.add(engine.getAbsolutePath());
        version.add("--version");
        JSONObject executed = command(version, gameDirectory, context.getCacheDir());
        result.put("engineVersion", executed);
        boolean passed = executed.optBoolean("passed");
        result.put("passed", passed).put("ordinaryAppEngineVersionPassed", passed);
        if (!passed) return result.put("error", "引擎版本命令执行失败，见诊断输出");
        if (passed && mode.equals("help")) {
            List<String> help = new ArrayList<>(base);
            help.add(engine.getAbsolutePath());
            help.add("--help");
            JSONObject usage = command(help, gameDirectory, context.getCacheDir());
            result.put("engineHelp", usage).put("passed", usage.optBoolean("passed"));
        }
        if (passed && graphical) {
            File session =
                    manual
                            ? GameSession.prepareProfile(
                                    context,
                                    gameDirectory,
                                    control.graphicsPreset(),
                                    control.spriteLoadingLimit(),
                                    control.virtualGamepadEnabled())
                            : new File(
                                    context.getFilesDir(),
                                    "graphics-probe/session-" + UUID.randomUUID());
            if (manual)
                result.put("graphicsPreset", control.graphicsPreset())
                        .put("spriteLoadingLimit", control.spriteLoadingLimit())
                        .put("virtualGamepadEnabled", control.virtualGamepadEnabled());
            File user = new File(session, "userdata");
            if (!user.isDirectory() && !user.mkdirs()) throw new IOException("无法创建图形验证数据目录");
            File config = new File(session, "config.ini");
            // Existing persistent configuration format; this marker is not a game version gate.
            String settings =
                    "[path]\nread-data="
                            + new File(gameDirectory, "data").getAbsolutePath()
                            + "\nwrite-data="
                            + user.getAbsolutePath()
                            + "\n";
            if (!manual) {
                File formalConfig = new File(GameSession.profileDirectory(context), "config.ini");
                if (formalConfig.isFile()) {
                    if (formalConfig.length() > 1024 * 1024) throw new IOException("配置文件过大，已保留原文件");
                    String locale =
                            GameProfileConfig.readLocale(
                                    new String(
                                            Files.readAllBytes(formalConfig.toPath()),
                                            StandardCharsets.UTF_8));
                    if (locale != null) {
                        settings += "[general]\nlocale=" + locale + "\n";
                        result.put("localeInherited", locale);
                    }
                }
                Files.write(config.toPath(), settings.getBytes(StandardCharsets.UTF_8));
            }
            int renderWidth = 1280, renderHeight = 720;
            if (withDisplay) {
                File geometryFile = new File(serverTemp, "render-size.json");
                if (!geometryFile.isFile() || geometryFile.length() > 4096)
                    throw new IOException("游戏窗口分辨率尚未就绪");
                JSONObject geometry =
                        new JSONObject(
                                new String(
                                        Files.readAllBytes(geometryFile.toPath()),
                                        StandardCharsets.UTF_8));
                com.termux.x11.RenderSize expectedSize =
                        new com.termux.x11.RenderSize(
                                geometry.getInt("surfaceWidth"),
                                geometry.getInt("surfaceHeight"),
                                geometry.getInt("scalePercent"));
                renderWidth = geometry.getInt("width");
                renderHeight = geometry.getInt("height");
                if (renderWidth != expectedSize.width || renderHeight != expectedSize.height)
                    throw new IOException("游戏窗口分辨率记录不一致");
                result.put("renderSize", geometry);
            }
            List<String> graphics = new ArrayList<>(base);
            graphics.add(engine.getAbsolutePath());
            graphics.addAll(
                    Arrays.asList(
                            "--config",
                            config.getAbsolutePath(),
                            "--executable-path",
                            engine.getParent(),
                            "--window-size",
                            renderWidth + "x" + renderHeight));
            if (!manual) graphics.addAll(Arrays.asList("--force-graphics-preset", "very-low"));
            graphics.addAll(Arrays.asList("--no-log-rotation", "--verbose"));
            result.put("graphicalStartupAttempted", true)
                    .put("graphicsReady", false)
                    .put("sessionDirectory", session.getAbsolutePath());
            if (manual)
                result.put("configFile", config.getAbsolutePath())
                        .put("readData", new File(gameDirectory, "data").getAbsolutePath())
                        .put("writeData", user.getAbsolutePath());
            else result.put("configuration", settings);
            if (progress != null) progress.update("graphicsStarting", result);
            Map<String, String> windowEnvironment = new HashMap<>();
            // SDL's Unix preferred-locale detection reads LANG/LANGUAGE directly.
            // Keep LC_ALL=C for existing numeric/runtime behavior; the persistent
            // [general] locale is seeded separately and never overrides a choice.
            windowEnvironment.putAll(AppLanguage.environment(context));
            result.put(
                            "androidLanguageTags",
                            context.getResources().getConfiguration().getLocales().toLanguageTags())
                    .put("languageEnvironment", new JSONObject(AppLanguage.environment(context)));
            File graphicsTemp = context.getCacheDir();
            if (withDisplay) {
                graphicsTemp = serverTemp;
                // libxcb 1.17 accepts an explicit socket pathname as DISPLAY.
                // This bypasses its compiled Termux prefix without patching it.
                File socket = new File(serverTemp, ".X11-unix/X93");
                if (!Files.exists(socket.toPath()))
                    throw new IOException("Private X11 socket is missing");
                windowEnvironment.put("DISPLAY", socket.getAbsolutePath());
                windowEnvironment.put("SDL_VIDEODRIVER", "x11");
                // SDL3 uses this name; SDL2's variable is kept for diagnosis.
                windowEnvironment.put("SDL_VIDEO_DRIVER", "x11");
                windowEnvironment.put("XDG_RUNTIME_DIR", serverTemp.getAbsolutePath());
            }
            File mesaLog = new File(context.getFilesDir(), "mesa.log");
            if (mesaLog.isFile() && !mesaLog.delete())
                mesaLog =
                        new File(
                                context.getFilesDir(),
                                "mesa-" + System.currentTimeMillis() + ".log");
            windowEnvironment.put("MESA_LOG_FILE", mesaLog.getAbsolutePath());
            windowEnvironment.put("__GLX_VENDOR_LIBRARY_NAME", "mesa");
            windowEnvironment.put(
                    "SDL_EGL_LIBRARY",
                    new File(dependencies.getString("directory"), "libEGL.so.1").getAbsolutePath());
            windowEnvironment.put("SDL_VIDEO_FORCE_EGL", "1");
            windowEnvironment.put("EGL_PLATFORM", "x11");
            result.put("contextBackendRequested", "EGL");
            DirectZinkRuntime.environment(directRuntime, windowEnvironment);
            windowEnvironment.put("REA_ENGINE_PATH", engine.getCanonicalPath());
            if (controller) {
                JSONObject pad = GamepadRuntime.prepare(context);
                result.put("gamepadRuntime", pad)
                        .put("gamepadSocket", pad.getString("gamepadSocket"));
                windowEnvironment.put("LD_PRELOAD", pad.getString("library"));
                windowEnvironment.put("REA_GAMEPAD_SOCKET", pad.getString("gamepadSocket"));
                if (progress != null) progress.update("gamepadRelayReady", result);
            }
            if (manual) windowEnvironment.putAll(control.keyboardEnvironment());
            String hintAdapter = manual ? KeyboardBridge.hintAdapter(context) : null;
            result.put("keyboardHintAdapterAvailable", hintAdapter != null);
            if (hintAdapter != null) {
                String existing = windowEnvironment.get("LD_PRELOAD");
                windowEnvironment.put(
                        "LD_PRELOAD",
                        existing == null ? hintAdapter : existing + ":" + hintAdapter);
            }
            AudioDiskBridge audioBridge = AudioDiskBridge.create(context);
            try {
                if (audioBridge != null) {
                    windowEnvironment.putAll(audioBridge.environment());
                    result.put("audioBridge", audioBridge.report());
                    if (progress != null) progress.update("audioRelayReady", result);
                }
                File log = new File(user, "factorio-current.log");
                SessionEngineLog.begin(log);
                long logStartBytes = 0;
                JSONObject initialized =
                        command(
                                graphics,
                                session,
                                graphicsTemp,
                                windowEnvironment,
                                0,
                                control,
                                progress,
                                audioBridge,
                                log,
                                logStartBytes);
                result.put("graphicsInitialization", initialized)
                        .put("passed", false)
                        .put(
                                "assessment",
                                "Window/renderer evidence must be reviewed; an exit code alone is not graphics success");
                if (manual) {
                    result.put("graphicalGameLaunched", initialized.optBoolean("started"))
                            .put(
                                    "assessment",
                                    "User-started persistent session; renderer and gameplay evidence are separate from process exit");
                    try {
                        Map<String, String> disk = SessionEngineLog.evidence(log);
                        initialized.put("engineLogEvidence", new JSONObject(disk));
                        if (disk.containsKey("userQuitRequested"))
                            initialized.put("userQuitRequested", true);
                        if (disk.containsKey("normalExitCompleted"))
                            initialized
                                    .put("normalExitCompleted", true)
                                    .put("userExitConfirmed", true)
                                    .put(
                                            "normalExitEvidenceSource",
                                            initialized.has("normalExitEvidenceSource")
                                                    ? "stdout,engineLog"
                                                    : "engineLog");
                        if (!initialized.has("fatalSignal") && disk.containsKey("fatalSignal"))
                            initialized
                                    .put("fatalSignal", disk.get("fatalSignal"))
                                    .put("fatalLine", disk.get("fatalLine"))
                                    .put("passed", false);
                    } catch (IOException unavailable) {
                        initialized.put(
                                "engineLogEvidenceError", unavailable.getClass().getSimpleName());
                    }
                    initialized
                            .put("userQuitRequested", initialized.optBoolean("userQuitRequested"))
                            .put("userStopRequested", initialized.optBoolean("userStopRequested"))
                            .put("userExitConfirmed", initialized.optBoolean("userExitConfirmed"))
                            .put(
                                    "normalExitCompleted",
                                    initialized.optBoolean("normalExitCompleted"))
                            .put(
                                    "normalExitEvidenceSource",
                                    initialized.optString("normalExitEvidenceSource", "none"))
                            .put(
                                    "shutdownFault",
                                    initialized.optBoolean("userExitConfirmed")
                                            && initialized.has("fatalSignal"));
                    if (initialized.has("fatalSignal"))
                        initialized.put(
                                "faultPhase",
                                initialized.optBoolean("shutdownFault") ? "shutdown" : "runtime");
                    String outcome =
                            SessionExit.classify(
                                    initialized.optBoolean("userStopRequested"),
                                    initialized.optBoolean("normalExitCompleted"),
                                    initialized.has("startupError"),
                                    initialized.optBoolean("started"),
                                    initialized.optString("fatalSignal"),
                                    initialized.optInt("exitCode", -1));
                    result.put("exitStatus", outcome)
                            .put(
                                    "diagnosticSuggested",
                                    outcome.equals("failed")
                                            || outcome.equals("interrupted")
                                            || outcome.equals("unknown"));
                    if (outcome.equals("failed")) {
                        if (initialized.has("startupError"))
                            result.put("error", initialized.getString("startupError"));
                        else if (initialized.has("fatalSignal"))
                            result.put(
                                    "error",
                                    "Engine fatal signal: " + initialized.getString("fatalSignal"));
                        else result.put("error", initialized.optString("error", "引擎进程未能启动"));
                    }
                    if (outcome.equals("interrupted"))
                        result.put(
                                "error",
                                "Engine ended without a normal shutdown (exit code "
                                        + initialized.optInt("exitCode", -1)
                                        + "); cause unconfirmed");
                }
                if (log.isFile()) {
                    try (RandomAccessFile input = new RandomAccessFile(log, "r")) {
                        long bytes = input.length(),
                                start = bytes < logStartBytes ? 0 : logStartBytes;
                        long sessionBytes = bytes - start;
                        input.seek(Math.max(start, bytes - 65536));
                        byte[] tail = new byte[(int) Math.min(sessionBytes, 65536)];
                        input.readFully(tail);
                        result.put("engineLog", new String(tail, StandardCharsets.UTF_8))
                                .put("engineLogBytes", bytes)
                                .put("engineLogStartOffset", start)
                                .put("engineLogSessionBytes", sessionBytes)
                                .put("engineLogTruncated", sessionBytes > 65536);
                    }
                } else result.put("engineLogMissing", true);
            } finally {
                if (audioBridge != null) {
                    audioBridge.close();
                    result.put("audioCleanupCompleted", audioBridge.awaitClosed())
                            .put("audioBridge", audioBridge.report());
                }
                if (controller && result.has("gamepadSocket"))
                    Files.deleteIfExists(new File(result.getString("gamepadSocket")).toPath());
            }
        }
        return result;
    }
}
