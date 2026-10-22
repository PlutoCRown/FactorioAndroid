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
        live.accept("FPS 60 refresh rate 120\n");
        check(live.snapshot().size()==5,"refresh rate is not stored as a frame rate");
        check(GamepadRuntime.supportsEngine("7091e6d320e78210416c5e60492a316ad80b11d37917f66e6e72e088952b9562"),"existing engine retains its native gamepad adapter");
        check(!GamepadRuntime.supportsEngine("ebe1914b13c3ff45766d75001dda1ae87ae1a6f170feaf86973d782aa2e69de0"),"new engine does not use old SDL addresses");
        check(!GamepadRuntime.supportsEngine(null),"missing identity has no optional adapter");
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
