package org.factorio.local.launcher;
import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Separate GNU process only; none of these libraries is loaded into ART. */
final class DirectZinkRuntime {
    static JSONObject prepare(Context context) throws Exception {
        JSONObject manifest;
        try(InputStream in=context.getAssets().open("direct-zink.json");ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[4096];for(int n;(n=in.read(b))!=-1;){if(out.size()+n>16384)throw new IOException("Direct runtime manifest too large");out.write(b,0,n);}
            manifest=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
        }
        if(!manifest.getString("id").equals("direct-zink-hybris-game-trial-1"))throw new IOException("Direct runtime identity mismatch");
        boolean compatibleMesa=MesaRuntime.apply(context,manifest);
        String mesaVersion=compatibleMesa?MesaRuntime.VERSION:"26.3.0-devel";
        String mesaPrefix=compatibleMesa?"librea_compat_mesa_":"librea_direct_mesa_";
        File directory=new File(context.getFilesDir(),"runtime/direct-zink-hybris-game-trial-1"+(compatibleMesa?"-mesa-compat":""));
        File linker=new File(directory,"linker");if(!linker.isDirectory()&&!linker.mkdirs())throw new IOException("Cannot create direct runtime");
        Map<String,String> names=new HashMap<>();names.put("librea_vulkan_loader.so","libvulkan.so.1,libvulkan.so");names.put(compatibleMesa?"librea_compat_direct_icd.so":"librea_direct_icd.so","librea_direct_icd.so");names.put("librea_hybris_common.so","libhybris-common.so.1");names.put("librea_hybris_q.so","linker/q.so");names.put("librea_gnu_tls.so","librea_gnu_tls.so");
        names.put(mesaPrefix+"glx.so","libGLX_mesa.so.0");names.put(mesaPrefix+"gallium.so","libgallium-"+mesaVersion+".so");names.put(mesaPrefix+"egl.so","libEGL_mesa.so.0");names.put(mesaPrefix+"gbm.so","libgbm.so.1");names.put(mesaPrefix+"dril.so","dri/zink_dri.so");names.put(mesaPrefix+"gbm_dri.so","gbm/dri_gbm.so");
        names.put("librea_direct_hardware.so","libhardware.so.2");names.put("librea_direct_gralloc.so","libgralloc.so.1");names.put("librea_direct_ui.so","libui.so.1");
        JSONArray rows=manifest.getJSONArray("libraries");if(rows.length()!=names.size())throw new IOException("Incomplete direct runtime");
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);String packaged=row.getString("packagedName"),allowed=names.remove(packaged);if(allowed==null)throw new IOException("Unexpected direct library");
            File installed=new File(context.getApplicationInfo().nativeLibraryDir,packaged);if(installed.length()!=row.getLong("bytes")||!RuntimeLibraries.digest(installed).equals(row.getString("sha256")))throw new IOException("Direct library hash mismatch: "+packaged);
            JSONArray aliases=row.getJSONArray("aliases");String[] expected=allowed.split(",");if(aliases.length()!=expected.length)throw new IOException("Direct aliases mismatch");
            for(int j=0;j<expected.length;j++){if(!expected[j].equals(aliases.getString(j)))throw new IOException("Direct alias mismatch");int slash=expected[j].lastIndexOf('/');File parent=slash<0?directory:new File(directory,expected[j].substring(0,slash));if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Cannot create direct module directory");RuntimeLibraries.alias(parent,expected[j].substring(slash+1),installed);}
        }
        File vendor=new File(directory,"mesa-vendor.json");try(FileOutputStream out=new FileOutputStream(vendor)){out.write(new JSONObject().put("file_format_version","1.0.0").put("ICD",new JSONObject().put("library_path",new File(directory,"libEGL_mesa.so.0").getAbsolutePath())).toString().getBytes(StandardCharsets.UTF_8));}
        File icd=new File(directory,"direct-icd.json");try(FileOutputStream out=new FileOutputStream(icd)){out.write(new JSONObject().put("file_format_version","1.0.0").put("ICD",new JSONObject().put("library_path",new File(directory,"librea_direct_icd.so").getAbsolutePath()).put("api_version","1.4.0")).toString().getBytes(StandardCharsets.UTF_8));}
        // Wait only for our Surface receiver's Unix socket, never for a capability probe.
        File socket=new File(context.getFilesDir(),"direct-present.sock");long until=android.os.SystemClock.uptimeMillis()+15000;
        while(!socket.exists()&&android.os.SystemClock.uptimeMillis()<until)Thread.sleep(50);
        if(!socket.exists())throw new IOException("Android Surface receiver unavailable");
        return new JSONObject().put("icdFile",icd.getAbsolutePath()).put("surfaceSocket",socket.getAbsolutePath()).put("vendorFile",vendor.getAbsolutePath()).put("desktopMesa",mesaVersion).put("mesaSource",compatibleMesa?MesaRuntime.SOURCE:JSONObject.NULL).put("adrenoCompatibility",compatibleMesa).put("directory",directory.getAbsolutePath()).put("tlsPreload",new File(directory,"librea_gnu_tls.so").getAbsolutePath()).put("linkerDirectory",linker.getAbsolutePath()).put("virglUsed",false).put("presentationCpuReadbackRequired",false).put("presentationPath","gpu-ahb-android-surface");
    }
    static void environment(JSONObject runtime,Map<String,String> env) throws Exception {
        env.put("__EGL_VENDOR_LIBRARY_FILENAMES",runtime.getString("vendorFile"));env.put("LIBGL_DRIVERS_PATH",new File(runtime.getString("directory"),"dri").getAbsolutePath());env.put("GBM_BACKENDS_PATH",new File(runtime.getString("directory"),"gbm").getAbsolutePath());env.put("EGL_LOG_LEVEL","debug");
        env.put("GALLIUM_DRIVER","zink");env.put("MESA_LOADER_DRIVER_OVERRIDE","zink");env.put("LIBGL_ALWAYS_SOFTWARE","0");env.remove("LIBGL_DRI3_DISABLE");env.put("LIBGL_KOPPER_DRI2","true");env.remove("LIBGL_KOPPER_DISABLE");
        env.put("VK_DRIVER_FILES",runtime.getString("icdFile"));env.put("VK_ICD_FILENAMES",runtime.getString("icdFile"));env.put("REA_DIRECT_SOCKET",runtime.getString("surfaceSocket"));
        env.put("HYBRIS_LINKER_DIR",runtime.getString("linkerDirectory"));env.put("HYBRIS_ANDROID_SDK_VERSION","29");env.put("HYBRIS_LD_LIBRARY_PATH","/system/lib64:/vendor/lib64:/apex/com.android.runtime/lib64:/apex/com.android.runtime/lib64/bionic");env.put("LIBVULKAN","/system/lib64/libvulkan.so");
        if(maliGlesPresent())env.put("HYBRIS_MALI_MMUD_SKIP_LOADER_CHECK","0");
        // Allow the game's requested context on the compatibility runtime.
        // An API version override is not proof of full feature conformance;
        // scene rendering must be verified separately.
        if(new File("/vendor/lib64/hw/vulkan.adreno.so").isFile()) {
            env.put("MESA_GL_VERSION_OVERRIDE","3.3");
        }
    }
    /** The property hook lives inside libGLES_mali.so. Other GPUs do not load it. */
    private static boolean maliGlesPresent() {
        String[] paths={"/vendor/lib64/egl/libGLES_mali.so","/vendor/lib/egl/libGLES_mali.so",
            "/vendor/lib64/libGLES_mali.so","/vendor/lib/libGLES_mali.so",
            "/system/vendor/lib64/egl/libGLES_mali.so","/odm/lib64/egl/libGLES_mali.so"};
        for(String path:paths)if(new File(path).isFile())return true;
        return false;
    }
}
