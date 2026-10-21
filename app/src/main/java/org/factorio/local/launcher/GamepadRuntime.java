package org.factorio.local.launcher;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Verifies the GNU SDL relay for the fixed engine. Never loads it into ART/Bionic. */
public final class GamepadRuntime {
    private static final String ENGINE_SHA256="7091e6d320e78210416c5e60492a316ad80b11d37917f66e6e72e088952b9562";
    private static final String LIBRARY_SHA256="c5f7624ccdaf635dd487a51c1c3093ddb2e4e3a257c8545d1477c4f387ba56c1";
    private GamepadRuntime() {}

    /** Optional adapter capability, never an engine launch allowlist. */
    static boolean supportsEngine(String engineHash) {return ENGINE_SHA256.equals(engineHash);}

    /** Returns library and gamepadSocket paths; the GNU child owns socket creation/cleanup. */
    public static JSONObject prepare(Context context) throws Exception {
        JSONObject manifest;
        try(InputStream in=context.getAssets().open("gamepad-bridge.json")) {
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[2048];
            for(int n;(n=in.read(buffer))!=-1;) {
                if(out.size()+n>8192)throw new IOException("Gamepad manifest too large");
                out.write(buffer,0,n);
            }
            manifest=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
        }
        String id=manifest.getString("id");
        if(!"native-sdl-relay-1-engine-2.1.22".equals(id)||!ENGINE_SHA256.equals(manifest.getString("engineSha256"))||
            !"3.4.14".equals(manifest.getString("sdlVersion")))throw new IOException("Unsupported native gamepad relay");
        JSONObject entry=manifest.getJSONObject("library");
        if(!"librea_gamepad_bridge.so".equals(entry.getString("packagedName"))||entry.getLong("bytes")!=28968L||
            !LIBRARY_SHA256.equals(entry.getString("sha256")))throw new IOException("Unexpected gamepad library manifest");
        File library=new File(context.getApplicationInfo().nativeLibraryDir,"librea_gamepad_bridge.so");
        if(!library.isFile()||library.length()!=28968L||!LIBRARY_SHA256.equals(RuntimeLibraries.digest(library)))
            throw new IOException("Native gamepad relay checksum mismatch");
        File directory=new File(context.getCacheDir(),"x11");
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot prepare private gamepad directory");
        File socket=new File(directory,"pad-"+UUID.randomUUID().toString().replace("-","").substring(0,12)+".sock");
        String socketPath=socket.getAbsolutePath();
        if(socketPath.length()>100||socket.exists())throw new IOException("Invalid private gamepad socket path");
        return new JSONObject().put("id",id).put("engineSha256",ENGINE_SHA256).put("sdlVersion","3.4.14")
            .put("library",library.getAbsolutePath()).put("librarySha256",LIBRARY_SHA256)
            .put("gamepadSocket",socketPath).put("sameUidSocketOnly",true).put("libraryLoadedInArt",false);
    }
}
