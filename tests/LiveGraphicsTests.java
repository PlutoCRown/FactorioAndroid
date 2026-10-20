package org.factorio.local.launcher;
import java.util.Map;

public final class LiveGraphicsTests {
    private static int count;
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);count++;}
    public static void main(String[] args){
        LiveGraphics live=new LiveGraphics();
        live.accept(" 0.250 Initialised Open");
        check(live.snapshot().isEmpty(),"partial lines do not become evidence");
        live.accept("GL:[1] virgl (zink Mali); driver: 4.3\r\n");
        check(live.snapshot().get("rendererLine").contains("zink Mali"),"renderer across chunks parsed");
        live.accept(" 1.0 Graphics options: [Graphics quality: medium] [DXT: none]\n 3.0 Texture processor created (2048). Enabled: NO/NO.\n");
        check(live.snapshot().get("graphicsOptions").contains("DXT: none"),"actual compression level retained");
        check(live.snapshot().get("textureProcessor").contains("NO/NO"),"GPU generation remains a separate field");
        check(!live.snapshot().containsKey("initialized"),"renderer alone is not completed initialization");
        live.accept(" 16.848 Factorio initialised\n");
        check("true".equals(live.snapshot().get("initialized")),"engine initialization marker parsed");
        Map<String,String> detached=live.snapshot();detached.clear();
        check(!live.snapshot().isEmpty(),"report snapshots do not mutate parser state");
        LiveGraphics next=new LiveGraphics();
        check(next.snapshot().isEmpty(),"new engine cannot inherit previous output");
        StringBuilder tooLong=new StringBuilder();for(int i=0;i<9000;i++)tooLong.append('x');
        next.accept(tooLong+"Initialised OpenGL: invalid\nFPS 60 refresh rate 120\n");
        check(next.snapshot().isEmpty(),"oversized lines and arbitrary FPS are ignored");
        next.accept(" Initialised OpenGL:[1] new engine\n");
        check(next.snapshot().get("rendererLine").endsWith("new engine"),"parser recovers after oversized input");
        live.accept(" 0.380 Error CrashHandler.cpp:614: Received SIGSE");
        check(!live.snapshot().containsKey("fatalLine"),"partial crash line cannot replace observed status");
        live.accept("GV\n");
        check(live.snapshot().get("fatalLine").contains("Received SIGSEGV"),"crash after initialization is retained independently");
        check(!next.snapshot().containsKey("fatalLine"),"crash evidence does not leak across engine sessions");
        System.out.println("LiveGraphicsTests: "+count+" passed");
    }
}
