package org.factorio.tests;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Process;
import java.io.*;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Runs as the installed debug app UID. No game launch, UI interaction or typed text. */
public final class KeyboardOpenerSmoke {
    private static final String token=UUID.randomUUID().toString();
    private static int roundtrip(Path alias,String url,boolean validToken)throws Exception {
        String name="ime-smoke-"+UUID.randomUUID();AtomicReference<Throwable> failure=new AtomicReference<>();
        try(LocalServerSocket server=new LocalServerSocket(name)) {
            Thread receiver=new Thread(()-> {
                try(LocalSocket client=server.accept()) {
                    client.setSoTimeout(4000);
                    if(client.getPeerCredentials().getUid()!=Process.myUid())throw new IOException("Wrong peer UID");
                    BufferedReader input=new BufferedReader(new InputStreamReader(client.getInputStream(),"US-ASCII"));
                    String line=input.readLine();
                    if(!("RIME1 "+(validToken?token:"00000000-0000-0000-0000-000000000000")+" "+url).equals(line))throw new IOException("Wire packet differs");
                    if(validToken)client.getOutputStream().write(new byte[]{'O','K','\n'});
                }catch(Throwable t){failure.set(t);}
            });receiver.setDaemon(true);receiver.start();
            ProcessBuilder builder=new ProcessBuilder(alias.toString(),url);
            builder.environment().put("REA_IME_SOCKET",name);
            builder.environment().put("REA_IME_TOKEN",validToken?token:"00000000-0000-0000-0000-000000000000");
            java.lang.Process child=builder.start();if(!child.waitFor(6,TimeUnit.SECONDS)){child.destroyForcibly();throw new IOException("Helper timed out");}
            receiver.join(5000);if(receiver.isAlive())throw new IOException("Receiver timed out");
            if(failure.get()!=null)throw new IOException("Receiver failed",failure.get());
            return child.exitValue();
        }
    }
    private static void productionBridge(String pkg)throws Exception {
        android.os.Looper.prepareMainLooper();
        Class<?> activityThread=Class.forName("android.app.ActivityThread");
        Object thread=activityThread.getMethod("systemMain").invoke(null);
        java.lang.reflect.Method system=activityThread.getDeclaredMethod("getSystemContext");system.setAccessible(true);
        android.content.Context systemContext=(android.content.Context)system.invoke(thread);
        android.content.Context context=systemContext.createPackageContext(pkg,0);
        Class<?> type=Class.forName("org.factorio.local.launcher.KeyboardBridge"),listener=Class.forName("org.factorio.local.launcher.KeyboardBridge$Listener");
        java.util.concurrent.atomic.AtomicInteger received=new java.util.concurrent.atomic.AtomicInteger();
        Object callback=java.lang.reflect.Proxy.newProxyInstance(listener.getClassLoader(),new Class<?>[]{listener},(proxy,method,values)->{if(method.getName().equals("requested"))received.incrementAndGet();return null;});
        try(AutoCloseable bridge=(AutoCloseable)type.getConstructor(android.content.Context.class,listener).newInstance(context,callback)) {
            @SuppressWarnings("unchecked") java.util.Map<String,String> environment=(java.util.Map<String,String>)type.getMethod("environment").invoke(bridge);
            for(int mode=0;mode<7;mode++) {
                String url=mode==4?"steam://close/keyboard":"steam://open/keyboard?XPosition=0&YPosition=0&Width=300&Height=24&Mode="+(mode<4?mode:0);
                if(mode==6)url="steam://open/keyboard?Mode=0";
                ProcessBuilder builder=new ProcessBuilder(environment.get("PATH").split(":")[0]+"/xdg-open",url);
                builder.environment().putAll(environment);if(mode==5)builder.environment().put("REA_IME_TOKEN",UUID.randomUUID().toString());
                java.lang.Process child=builder.start();if(!child.waitFor(6,TimeUnit.SECONDS)){child.destroyForcibly();throw new IOException("Production bridge timed out");}
                if(child.exitValue()!=(mode<5?0:74))throw new IOException("Production bridge unexpected exit "+mode);
            }
            org.json.JSONObject report=(org.json.JSONObject)type.getMethod("report").invoke(bridge);
            if(received.get()!=5||report.getInt("requests")!=5||report.getInt("rejected")!=2)throw new IOException("Production bridge counters differ");
            System.out.println("IME_PRODUCTION_BRIDGE modes=4 close=true rejected=2 passed=true gameStarted=false imeShown=false");
        }
    }
    public static void main(String[] args) {
        try{run(args);System.exit(0);}catch(Throwable failure){failure.printStackTrace();System.exit(1);}
    }
    private static void run(String[] args)throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("installed helper and private cache directory and package required");
        Path directory=Files.createTempDirectory(Path.of(args[1]),"ime-smoke-");Path alias=directory.resolve("xdg-open");
        try {
            Files.createSymbolicLink(alias,Path.of(args[0]));
            String open="steam://open/keyboard?XPosition=0&YPosition=0&Width=300&Height=24&Mode=0";
            if(roundtrip(alias,open,true)!=0||roundtrip(alias,"steam://close/keyboard",true)!=0)throw new IOException("Valid keyboard request failed");
            if(roundtrip(alias,open,false)!=74)throw new IOException("Rejected session was not rejected");
            ProcessBuilder invalid=new ProcessBuilder(alias.toString(),"https://example.invalid/");invalid.environment().put("REA_IME_SOCKET","unused");invalid.environment().put("REA_IME_TOKEN",token);
            java.lang.Process child=invalid.start();if(!child.waitFor(5,TimeUnit.SECONDS)||child.exitValue()!=64)throw new IOException("Unrelated URL was not rejected");
            System.out.println("IME_HELPER_SMOKE uid="+Process.myUid()+" checks=4 passed=true symlink=true sameUidSocket=true gameStarted=false");
        }finally{Files.deleteIfExists(alias);Files.deleteIfExists(directory);}
        productionBridge(args[2]);
    }
}
