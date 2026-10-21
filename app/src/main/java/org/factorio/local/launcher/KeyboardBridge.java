package org.factorio.local.launcher;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Process;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Same-UID, per-session SDL keyboard request channel. Contains no typed text. */
public final class KeyboardBridge implements AutoCloseable {
    public interface Listener {void requested(KeyboardRequest request);}
    private final LocalServerSocket server;
    private final String token=UUID.randomUUID().toString();
    private final String socketName="factorio-ime-"+Process.myPid()+"-"+UUID.randomUUID();
    private final String binDirectory;
    private volatile boolean running=true;
    private volatile LocalSocket active;
    private volatile int requests,rejected;
    private volatile String error;
    public KeyboardBridge(Context context,Listener listener) throws Exception {
        Properties manifest=new Properties();try(InputStream in=context.getAssets().open("ime-native.properties")){manifest.load(in);}
        File library=new File(context.getApplicationInfo().nativeLibraryDir,"librea_ime_opener.so");
        if(!"librea_ime_opener.so".equals(manifest.getProperty("name"))||library.length()!=Long.parseLong(manifest.getProperty("bytes"))||
            !RuntimeLibraries.digest(library).equals(manifest.getProperty("sha256")))throw new IOException("Keyboard helper verification failed");
        File bin=new File(context.getCacheDir(),"ime-bin");if(!bin.isDirectory()&&!bin.mkdirs())throw new IOException("Cannot prepare keyboard helper");
        Path alias=new File(bin,"xdg-open").toPath();
        if(Files.exists(alias,LinkOption.NOFOLLOW_LINKS)) {
            if(!Files.isSymbolicLink(alias))throw new IOException("Unexpected keyboard helper path");
            Files.delete(alias);
        }
        Files.createSymbolicLink(alias,library.toPath());binDirectory=bin.getAbsolutePath();
        server=new LocalServerSocket(socketName);
        Thread worker=new Thread(()->run(listener),"game-keyboard-requests");worker.setDaemon(true);worker.start();
    }
    public Map<String,String> environment() {
        Map<String,String> result=new HashMap<>();result.put("REA_IME_SOCKET",socketName);result.put("REA_IME_TOKEN",token);
        result.put("PATH",binDirectory+":/system/bin");
        result.put("SDL_ENABLE_STEAM_SCREEN_KEYBOARD","1");result.put("SDL_ENABLE_SCREEN_KEYBOARD","1");
        return result;
    }
    private void run(Listener listener) {
        while(running) {
            try(LocalSocket client=server.accept()) {
                active=client;client.setSoTimeout(2000);
                if(client.getPeerCredentials().getUid()!=Process.myUid())throw new IOException("Keyboard peer rejected");
                InputStream in=client.getInputStream();ByteArrayOutputStream line=new ByteArrayOutputStream();
                int n;for(;(n=in.read())!=-1&&n!='\n';) {if(n<32||n>126||line.size()>=431)throw new IOException("Keyboard packet rejected");line.write(n);}
                if(n!='\n')throw new IOException("Incomplete keyboard packet");
                String value=line.toString("US-ASCII"),prefix="RIME1 "+token+" ";
                if(!value.startsWith(prefix))throw new IOException("Keyboard session rejected");
                KeyboardRequest request=KeyboardRequest.parse(value.substring(prefix.length()));
                listener.requested(request);requests++;client.getOutputStream().write(new byte[]{'O','K','\n'});
            }catch(Exception failure){if(running){rejected++;error=failure.getClass().getSimpleName();}}
            finally{active=null;}
        }
    }
    public JSONObject report() throws Exception {
        return new JSONObject().put("requests",requests).put("rejected",rejected).put("running",running)
            .put("lastError",error==null?JSONObject.NULL:error).put("gameFileModified",false);
    }
    @Override public void close() {
        running=false;try{if(active!=null)active.close();}catch(IOException ignored){}try{server.close();}catch(IOException ignored){}
    }
}
