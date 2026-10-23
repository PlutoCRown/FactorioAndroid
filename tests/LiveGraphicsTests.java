package org.factorio.local.launcher;

public final class LiveGraphicsTests {
    private static int count;
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);count++;}
    public static void main(String[] args) throws Exception {
        LiveGraphics live=new LiveGraphics();
        live.accept(" 0.250 Initialised OpenGL:[1] zink Mali; driver: 4.3\n");
        live.accept(" 1.0 Graphics options: [Graphics quality: medium] [DXT: none]\n");
        live.accept(" 3.0 Texture processor created (2048). Enabled: NO/NO.\n");
        check(live.snapshot().get("rendererLine").contains("zink Mali"),"renderer line kept");
        check(live.snapshot().get("graphicsOptions").contains("DXT: none"),"graphics options kept");
        check(live.snapshot().get("textureProcessor").contains("NO/NO"),"texture processor kept");
        check(!live.snapshot().containsKey("initialized"),"renderer line is not initialization");
        live.accept(" 16.848 Factorio initialised\n");
        check("true".equals(live.snapshot().get("initialized")),"initialization marker kept");
        live.accept(" 0.380 Error CrashHandler.cpp:614: Received SIGSEGV\n");
        check(live.snapshot().get("fatalLine").contains("Received SIGSEGV"),"crash line kept");
        check("SIGSEGV".equals(live.snapshot().get("fatalSignal")),"fatal cause is retained after graphics initialized");
        live.accept(" 0.000 Error CrashHandler.cpp:614: Received SIGTERM\n");
        check("SIGSEGV".equals(live.snapshot().get("fatalSignal")),"supervisor termination cannot replace the original fault");
        live.accept("FPS 60 refresh rate 120\n");
        check(live.snapshot().size()==6,"refresh rate is not stored as a frame rate");
        LiveGraphics ordinary=new LiveGraphics();ordinary.accept(" 1.000 Goodbye\n Warning: SIGSEGV example\n");
        check(!ordinary.snapshot().containsKey("fatalSignal"),"normal goodbye and ordinary signal mentions are not a crash");
        check(!ordinary.snapshot().containsKey("normalExitCompleted"),"goodbye without a user quit is insufficient");
        LiveGraphics orderly=new LiveGraphics();
        orderly.accept(" 200.100 Quitting: user-");orderly.accept("quit.\n 200.500 Goodbye\n");
        orderly.accept(" 0.000 Error CrashHandler.cpp:614: Received SIGSEGV\n");
        check("true".equals(orderly.snapshot().get("normalExitCompleted")),"completed user quit survives a later teardown fault");
        check("SIGSEGV".equals(orderly.snapshot().get("fatalSignal")),"teardown fault remains in diagnostics");
        LiveGraphics interrupted=new LiveGraphics();
        interrupted.accept(" 1.000 Quitting: user-quit.\n 0.000 Error CrashHandler.cpp:614: Received SIGSEGV\n 1.100 Goodbye\n");
        check(!interrupted.snapshot().containsKey("normalExitCompleted"),"fault before goodbye remains an abnormal exit");
        LiveGraphics reversed=new LiveGraphics();reversed.accept(" 1.000 Goodbye\n 1.100 Quitting: user-quit.\n");
        check(!reversed.snapshot().containsKey("normalExitCompleted"),"completion requires ordered exit markers");
        LiveGraphics prefixed=new LiveGraphics();
        prefixed.accept(" 40.100 Info MainLoop.cpp:10: Quitting: user-quit.\r\n 40.200 Info MainLoop.cpp:11: Goodbye");prefixed.finish();
        check("true".equals(prefixed.snapshot().get("normalExitCompleted")),"log prefixes and missing final newline preserve completion");
        check("normal".equals(SessionExit.classify(false,true,false,true,"SIGSEGV",137)),"normal completion takes precedence over teardown termination");
        check("unknown".equals(SessionExit.classify(false,false,false,true,"",137)),"137 alone does not prove failure or normal exit");
        check("failed".equals(SessionExit.classify(false,false,false,true,"SIGSEGV",137)),"explicit runtime fault still reports failure");
        check("failed".equals(SessionExit.classify(false,true,true,true,"",137)),"startup failure is never hidden by completion evidence");
        check("stopped".equals(SessionExit.classify(true,false,false,true,"",137)),"launcher stop is not diagnosed from cleanup code");
        java.nio.file.Path session=java.nio.file.Files.createTempDirectory("factorio-session-log-");
        java.io.File current=session.resolve("factorio-current.log").toFile();
        try {
            java.nio.file.Files.write(current.toPath(),"1.000 Quitting: user-quit.\n1.100 Goodbye\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            SessionEngineLog.begin(current);
            check(!current.exists()&&java.nio.file.Files.exists(session.resolve("factorio-previous.log")),"old log retained outside current launch");
            check(SessionEngineLog.evidence(current).isEmpty(),"old quit evidence cannot classify a new launch");
            java.nio.file.Files.write(current.toPath(),"5.000 Quitting: user-quit.\n5.100 Goodbye".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            check("true".equals(SessionEngineLog.evidence(current).get("normalExitCompleted")),"file-only normal exit is detected independently of stdout");
        }finally {
            java.nio.file.Files.deleteIfExists(current.toPath());java.nio.file.Files.deleteIfExists(session.resolve("factorio-previous.log"));java.nio.file.Files.delete(session);
        }
        java.nio.file.Path log=java.nio.file.Files.createTempFile("factorio-startup-",".log");
        try {
            String failure=" 0.362 Error SDLWindow.cpp:342: Failed to create an application window. SDL_Error: Could not initialize EGL\n";
            java.nio.file.Files.write(log,failure.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            long previous=java.nio.file.Files.size(log);
            check(StartupFailure.read(log.toFile(),previous)==null,"old launch failure is ignored");
            java.nio.file.Files.write(log," 0.000 Operating system: Linux\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),java.nio.file.StandardOpenOption.APPEND);
            check(StartupFailure.read(log.toFile(),previous)==null,"ordinary startup is not a fatal window error");
            java.nio.file.Files.write(log,failure.getBytes(java.nio.charset.StandardCharsets.UTF_8),java.nio.file.StandardOpenOption.APPEND);
            check(StartupFailure.read(log.toFile(),previous).contains("Could not initialize EGL"),"fatal window error found without waiting for stdout or exit");
            long next=java.nio.file.Files.size(log);
            java.nio.file.Files.write(log," 0.532 Error Util.cpp:81: Failed to create OpenGL context: EGL_BAD_MATCH\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),java.nio.file.StandardOpenOption.APPEND);
            check(StartupFailure.read(log.toFile(),next).contains("EGL_BAD_MATCH"),"context creation failure also ends startup");
        }finally {java.nio.file.Files.deleteIfExists(log);}
        System.out.println("LiveGraphicsTests: "+count+" passed");
    }
}
