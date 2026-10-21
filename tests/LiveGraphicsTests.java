package org.factorio.local.launcher;

public final class LiveGraphicsTests {
    private static int count;
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);count++;}
    public static void main(String[] args){
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
        System.out.println("LiveGraphicsTests: "+count+" passed");
    }
}
