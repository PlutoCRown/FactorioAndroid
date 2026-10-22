import java.io.File;
import java.lang.reflect.*;
import java.util.*;

/** Exercise the production supervisor with own shell children, without loading the game. */
public final class EngineExitProbe {
    static Object run(String script,File directory)throws Exception {
        Class<?> session=Class.forName("org.factorio.local.launcher.GameSession");
        Object control=session.getConstructor(String.class,int.class).newInstance("compat",1);
        Class<?> runtime=Class.forName("org.factorio.local.launcher.RuntimeProbe");
        Method command=null;
        for(Method candidate:runtime.getDeclaredMethods())if(candidate.getName().equals("command")&&candidate.getParameterCount()==10){command=candidate;break;}
        if(command==null)throw new IllegalStateException("Production command method missing");
        command.setAccessible(true);
        try {return command.invoke(null,Arrays.asList("/system/bin/sh","-c",script),directory,directory,Collections.emptyMap(),0,control,null,null,null,0L);}
        catch(InvocationTargetException failure){throw new RuntimeException(failure.getCause());}
    }
    public static void main(String[] args)throws Exception {
        File directory=new File(args[0]);
        long start=System.nanoTime();
        Object normal=run("echo '1.000 Goodbye'; exit 0",directory);
        String normalText=normal.toString();
        if(!normalText.contains("\"exitCode\":0")||normalText.contains("\"fatalSignal\""))throw new AssertionError("Normal child misclassified: "+normalText);
        String quick=run("echo '0.000 Error CrashHandler.cpp:614: Received SIGABRT'; exit 0",directory).toString();
        if(!quick.contains("\"fatalSignal\":\"SIGABRT\"")||!quick.contains("\"passed\":false"))throw new AssertionError("Fast fatal exit misclassified: "+quick);
        Object fatal=run("echo '0.100 Initialised OpenGL:[1] fixture'; echo '0.200 Factorio initialised'; echo '1.000 Goodbye'; echo '0.000 Error CrashHandler.cpp:614: Received SIGSEGV'; exec /system/bin/sleep 60",directory);
        String fatalText=fatal.toString();
        if(!fatalText.contains("\"fatalSignal\":\"SIGSEGV\"")||!fatalText.contains("\"initialized\":\"true\"")||!fatalText.contains("\"passed\":false"))throw new AssertionError("Fatal child not supervised: "+fatalText);
        if(fatalText.contains("\"normalExitCompleted\":true"))throw new AssertionError("Goodbye alone hid a crash");
        String orderly=run("echo '1.000 Quitting: user-quit.'; echo '1.100 Goodbye'; echo '0.000 Error CrashHandler.cpp:614: Received SIGSEGV'; exec /system/bin/sleep 60",directory).toString();
        if(!orderly.contains("\"normalExitCompleted\":true")||!orderly.contains("\"fatalSignal\":\"SIGSEGV\"")||!orderly.contains("\"passed\":false"))throw new AssertionError("Teardown diagnostic or normal quit classification lost: "+orderly);
        double elapsed=(System.nanoTime()-start)/1e9;
        if(elapsed>12)throw new AssertionError("Child supervision exceeded bound: "+elapsed);
        System.out.println("ENGINE_EXIT_PROBE normal_exit=true fast_fatal=true fatal_after_renderer=true completed_quit_teardown=true child_ended=true elapsed="+elapsed+" passed=true");
    }
}
