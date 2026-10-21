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
        System.out.println("LiveGraphicsTests: "+count+" passed");
    }
}
