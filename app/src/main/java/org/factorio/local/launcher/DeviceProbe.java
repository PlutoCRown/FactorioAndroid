package org.factorio.local.launcher;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.opengl.*;
import android.os.Build;
import android.system.*;
import org.json.*;

final class DeviceProbe {
    static JSONObject collect(Context context) throws Exception {
        JSONObject root=new JSONObject();
        root.put("schema",1);
        root.put("model",Build.MODEL); root.put("manufacturer",Build.MANUFACTURER);
        root.put("device",Build.DEVICE); root.put("androidRelease",Build.VERSION.RELEASE);
        root.put("sdkInt",Build.VERSION.SDK_INT); root.put("displayBuild",Build.DISPLAY);
        root.put("abis",new JSONArray(Build.SUPPORTED_ABIS));
        root.put("pageSize",Os.sysconf(OsConstants._SC_PAGESIZE));
        root.put("kernel",Os.uname().release);
        ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();
        ((ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(memory);
        root.put("totalMemoryBytes",memory.totalMem); root.put("availableMemoryBytes",memory.availMem);
        root.put("lowMemory",memory.lowMemory);
        JSONArray vulkan=new JSONArray();
        for(FeatureInfo f:context.getPackageManager().getSystemAvailableFeatures())
            if(f.name!=null && f.name.startsWith("android.hardware.vulkan"))
                vulkan.put(new JSONObject().put("name",f.name).put("version",f.version));
        root.put("declaredVulkanFeatures",vulkan);
        root.put("vulkanLimit","Package-manager declarations only; no Vulkan device/context was created.");
        root.put("graphics",graphics());
        root.put("factorioLaunched",false);
        root.put("runtimeStatus","This probe checks Android EGL/GLES; engine and desktop renderer capabilities are established by the running session.");
        return root;
    }
    private static JSONObject graphics() throws Exception {
        JSONObject report=new JSONObject();
        report.put("scope","Android system EGL/GLES offscreen pbuffer; engine renderer capabilities are reported separately.");
        EGLDisplay display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        EGLContext context=EGL14.EGL_NO_CONTEXT; EGLSurface surface=EGL14.EGL_NO_SURFACE;
        try {
            int[] version=new int[2];
            if(display==EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display,version,0,version,1))
                throw new Exception("EGL 初始化失败: 0x"+Integer.toHexString(EGL14.eglGetError()));
            report.put("eglVendor",EGL14.eglQueryString(display,EGL14.EGL_VENDOR));
            report.put("eglVersion",EGL14.eglQueryString(display,EGL14.EGL_VERSION));
            report.put("eglExtensions",EGL14.eglQueryString(display,EGL14.EGL_EXTENSIONS));
            EGLConfig[] configs=new EGLConfig[1]; int[] count=new int[1];
            int[] attributes={EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE,0x40,EGL14.EGL_RED_SIZE,8,
                EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_NONE};
            if(!EGL14.eglChooseConfig(display,attributes,0,configs,0,1,count,0)||count[0]==0)
                throw new Exception("没有可用的 GLES3 pbuffer 配置");
            context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE},0);
            surface=EGL14.eglCreatePbufferSurface(display,configs[0],
                new int[]{EGL14.EGL_WIDTH,16,EGL14.EGL_HEIGHT,16,EGL14.EGL_NONE},0);
            if(context==EGL14.EGL_NO_CONTEXT || surface==EGL14.EGL_NO_SURFACE
                    || !EGL14.eglMakeCurrent(display,surface,surface,context))
                throw new Exception("GLES3 上下文创建失败: 0x"+Integer.toHexString(EGL14.eglGetError()));
            report.put("glVendor",GLES30.glGetString(GLES30.GL_VENDOR));
            report.put("glRenderer",GLES30.glGetString(GLES30.GL_RENDERER));
            report.put("glVersion",GLES30.glGetString(GLES30.GL_VERSION));
            report.put("glslVersion",GLES30.glGetString(GLES30.GL_SHADING_LANGUAGE_VERSION));
            int[] max=new int[1]; GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE,max,0);
            report.put("maxTextureSize",max[0]);
            GLES30.glGetIntegerv(GLES30.GL_NUM_EXTENSIONS,max,0);
            JSONArray extensions=new JSONArray();
            for(int i=0;i<max[0];i++) extensions.put(GLES30.glGetStringi(GLES30.GL_EXTENSIONS,i));
            report.put("glExtensions",extensions);
            GLES30.glClearColor(0.25f,0.5f,0.75f,1); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
            java.nio.ByteBuffer pixel=java.nio.ByteBuffer.allocateDirect(4);
            GLES30.glReadPixels(0,0,1,1,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,pixel);
            int error=GLES30.glGetError();
            report.put("pixel",new JSONArray(new int[]{pixel.get(0)&255,pixel.get(1)&255,pixel.get(2)&255,pixel.get(3)&255}));
            boolean rendered=error==GLES30.GL_NO_ERROR && Math.abs((pixel.get(0)&255)-64)<=2
                && Math.abs((pixel.get(1)&255)-128)<=2 && Math.abs((pixel.get(2)&255)-191)<=2;
            report.put("clearReadbackPassed",rendered); report.put("glError",error);
            report.put("contextCreated",true);
        } catch(Exception e) { report.put("error",e.getMessage()); report.put("contextCreated",false); }
        finally {
            if(display!=EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
                if(surface!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,surface);
                if(context!=EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,context);
                EGL14.eglTerminate(display); EGL14.eglReleaseThread();
            }
        }
        return report;
    }
}
