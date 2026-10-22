package org.factorio.local.launcher;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
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
    private final String inputSocketName=socketName+"-text";
    private final String binDirectory;
    private volatile boolean directText;
    private volatile boolean running=true;
    private volatile LocalSocket active;
    private volatile int requests,rejected;
    private volatile int inputEdits,inputKeys,inputFailures,visibilityUpdates,visibilityEchoes;
    private volatile String inputError;
    private volatile String error;
    public KeyboardBridge(Context context,Listener listener) throws Exception {
        File library=new File(context.getApplicationInfo().nativeLibraryDir,"librea_ime_opener.so");
        RuntimeLibraries.requireLibrary(library);
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
        result.put("REA_IME_TEXT_SOCKET",inputSocketName);
        result.put("PATH",binDirectory+":/system/bin");
        result.put("SDL_ENABLE_STEAM_SCREEN_KEYBOARD","1");result.put("SDL_ENABLE_SCREEN_KEYBOARD","1");
        return result;
    }
    /** The adapter resolves SDL capabilities in the actual child, not from an engine digest. */
    static String hintAdapter(Context context) throws Exception {
        File library=new File(context.getApplicationInfo().nativeLibraryDir,"librea_ime_hint.so");
        RuntimeLibraries.requireLibrary(library);
        return library.getAbsolutePath();
    }
    public boolean hasDirectText(){
        if(!directText&&running)directText=sendInput(4,0,0,new byte[0],0);
        return directText;
    }
    public boolean edit(int moveBefore,int deleteBefore,String text,int moveAfter) {
        if(!directText||!running||text==null||Math.abs((long)moveBefore)>2048||deleteBefore<0||deleteBefore>2048||Math.abs((long)moveAfter)>2048)return false;
        byte[] bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if(bytes.length>65536||text.indexOf('\0')>=0)return false;
        boolean accepted=sendInput(1,moveBefore,deleteBefore,bytes,moveAfter);if(accepted)inputEdits++;return accepted;
    }
    public boolean key(int androidCode) {
        boolean accepted=sendInput(2,androidCode,0,new byte[0],0);if(accepted)inputKeys++;return accepted;
    }
    public boolean visibility(boolean shown) {
        boolean accepted=sendInput(3,shown?1:0,0,new byte[0],0);if(accepted)visibilityUpdates++;return accepted;
    }
    private synchronized boolean sendInput(int kind,int a,int b,byte[] bytes,int d) {
        if((kind!=4&&!directText)||!running)return false;
        try(LocalSocket client=new LocalSocket()) {
            client.connect(new LocalSocketAddress(inputSocketName,LocalSocketAddress.Namespace.ABSTRACT));client.setSoTimeout(1500);
            if(client.getPeerCredentials().getUid()!=Process.myUid())throw new IOException("Input peer rejected");
            DataOutputStream output=new DataOutputStream(client.getOutputStream());
            output.writeInt(0x52495431);output.writeInt(kind);output.writeInt(a);output.writeInt(b);output.writeInt(bytes.length);output.writeInt(d);
            output.write(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII));output.write(bytes);output.flush();
            DataInputStream input=new DataInputStream(client.getInputStream());
            if(input.readByte()!='O'||input.readByte()!='K'||input.readByte()!='\n')throw new IOException("Input not accepted");
            return true;
        }catch(Exception failure){if(kind!=4){inputFailures++;inputError=failure.getClass().getSimpleName();}return false;}
    }
    private void run(Listener listener) {
        while(running) {
            try(LocalSocket client=server.accept()) {
                active=client;client.setSoTimeout(2000);
                if(client.getPeerCredentials().getUid()!=Process.myUid())throw new IOException("Keyboard peer rejected");
                InputStream in=client.getInputStream();ByteArrayOutputStream line=new ByteArrayOutputStream();
                int n;for(;(n=in.read())!=-1&&n!='\n';) {if(n<32||n>126||line.size()>=431)throw new IOException("Keyboard packet rejected");line.write(n);}
                if(n!='\n')throw new IOException("Incomplete keyboard packet");
                String value=line.toString("US-ASCII"),prefix="RIME1 "+token+" ",echoPrefix="RIME2 "+token+" ";
                boolean echo=value.startsWith(echoPrefix);
                if(!value.startsWith(prefix)&&!echo)throw new IOException("Keyboard session rejected");
                KeyboardRequest request=KeyboardRequest.parse(value.substring(prefix.length()));
                if(echo)visibilityEchoes++;else {listener.requested(request);requests++;}
                client.getOutputStream().write(new byte[]{'O','K','\n'});
            }catch(Exception failure){if(running){rejected++;error=failure.getClass().getSimpleName();}}
            finally{active=null;}
        }
    }
    public JSONObject report() throws Exception {
        return new JSONObject().put("requests",requests).put("rejected",rejected).put("running",running)
            .put("lastError",error==null?JSONObject.NULL:error).put("gameFileModified",false)
            .put("directTextSelected",directText).put("inputEdits",inputEdits).put("inputKeys",inputKeys).put("inputFailures",inputFailures)
            .put("visibilityUpdates",visibilityUpdates).put("visibilityEchoes",visibilityEchoes)
            .put("lastInputError",inputError==null?JSONObject.NULL:inputError);
    }
    @Override public void close() {
        running=false;try{if(active!=null)active.close();}catch(IOException ignored){}try{server.close();}catch(IOException ignored){}
    }
}
