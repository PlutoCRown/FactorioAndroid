package org.factorio.local.launcher;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Separate GNU process only; none of these libraries is loaded into ART. */
final class DirectZinkRuntime {
    static JSONObject prepare(Context context, boolean maliRequested) throws Exception {
        JSONObject manifest;
        try (InputStream in = context.getAssets().open("direct-zink.json");
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[4096];
            for (int n; (n = in.read(b)) != -1; ) {
                if (out.size() + n > 16384)
                    throw new IOException("Direct runtime manifest too large");
                out.write(b, 0, n);
            }
            manifest = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        }
        if (manifest.optInt("schema", 1) != 1)
            throw new IOException("Unsupported direct runtime manifest schema");
        GraphicsDriver driver = GraphicsDriver.current();
        boolean compatibleMesa = MesaRuntime.apply(manifest, driver, maliRequested);
        String mesaVersion = compatibleMesa ? MesaRuntime.VERSION : "26.3.0-devel";
        File directory =
                new File(
                        context.getFilesDir(),
                        "runtime/direct-zink" + (compatibleMesa ? "-compat" : ""));
        File linker = new File(directory, "linker");
        if (!linker.isDirectory() && !linker.mkdirs())
            throw new IOException("Cannot create direct runtime");
        JSONArray rows = manifest.getJSONArray("libraries");
        Set<String> names = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String packaged = row.getString("packagedName");
            if (!packaged.matches("lib[A-Za-z0-9_.+-]+\\.so") || !names.add(packaged))
                throw new IOException("Invalid direct library name");
            File installed = new File(context.getApplicationInfo().nativeLibraryDir, packaged);
            RuntimeLibraries.requireLibrary(installed);
            JSONArray aliases = row.getJSONArray("aliases");
            for (int j = 0; j < aliases.length(); j++) {
                String name = aliases.getString(j);
                if (!name.matches("[A-Za-z0-9_./+-]+")
                        || name.startsWith("/")
                        || name.contains("..")
                        || name.contains("//")) throw new IOException("Invalid direct alias path");
                int slash = name.lastIndexOf('/');
                File parent = slash < 0 ? directory : new File(directory, name.substring(0, slash));
                if (!parent.isDirectory() && !parent.mkdirs())
                    throw new IOException("Cannot create direct module directory");
                RuntimeLibraries.alias(parent, name.substring(slash + 1), installed);
            }
        }
        File vendor = new File(directory, "mesa-vendor.json");
        try (FileOutputStream out = new FileOutputStream(vendor)) {
            out.write(
                    new JSONObject()
                            .put("file_format_version", "1.0.0")
                            .put(
                                    "ICD",
                                    new JSONObject()
                                            .put(
                                                    "library_path",
                                                    new File(directory, "libEGL_mesa.so.0")
                                                            .getAbsolutePath()))
                            .toString()
                            .getBytes(StandardCharsets.UTF_8));
        }
        File icd = new File(directory, "direct-icd.json");
        try (FileOutputStream out = new FileOutputStream(icd)) {
            out.write(
                    new JSONObject()
                            .put("file_format_version", "1.0.0")
                            .put(
                                    "ICD",
                                    new JSONObject()
                                            .put(
                                                    "library_path",
                                                    new File(directory, "librea_direct_icd.so")
                                                            .getAbsolutePath())
                                            .put("api_version", "1.4.0"))
                            .toString()
                            .getBytes(StandardCharsets.UTF_8));
        }
        // Wait only for our Surface receiver's Unix socket, never for a capability probe.
        File socket = new File(context.getFilesDir(), "direct-present.sock");
        long until = android.os.SystemClock.uptimeMillis() + 15000;
        while (!socket.exists() && android.os.SystemClock.uptimeMillis() < until) Thread.sleep(50);
        if (!socket.exists()) throw new IOException("Android Surface receiver unavailable");
        return new JSONObject()
                .put("icdFile", icd.getAbsolutePath())
                .put("surfaceSocket", socket.getAbsolutePath())
                .put("vendorFile", vendor.getAbsolutePath())
                .put("desktopMesa", mesaVersion)
                .put("mesaSource", compatibleMesa ? MesaRuntime.SOURCE : JSONObject.NULL)
                .put("compatibleMesa", compatibleMesa)
                .put("graphicsDriver", driver.name().toLowerCase(Locale.ROOT))
                .put("adrenoCompatibility", driver == GraphicsDriver.ADRENO)
                .put("maliCompatibility", compatibleMesa && driver == GraphicsDriver.MALI)
                .put("maliCompatibilityRequested", maliRequested)
                .put("directory", directory.getAbsolutePath())
                .put("tlsPreload", new File(directory, "librea_gnu_tls.so").getAbsolutePath())
                .put("linkerDirectory", linker.getAbsolutePath())
                .put("virglUsed", false)
                .put("presentationCpuReadbackRequired", false)
                .put("presentationPath", "gpu-ahb-android-surface");
    }

    static void environment(JSONObject runtime, Map<String, String> env) throws Exception {
        env.put("__EGL_VENDOR_LIBRARY_FILENAMES", runtime.getString("vendorFile"));
        env.put(
                "LIBGL_DRIVERS_PATH",
                new File(runtime.getString("directory"), "dri").getAbsolutePath());
        env.put(
                "GBM_BACKENDS_PATH",
                new File(runtime.getString("directory"), "gbm").getAbsolutePath());
        env.put("EGL_LOG_LEVEL", "debug");
        env.put("GALLIUM_DRIVER", "zink");
        env.put("MESA_LOADER_DRIVER_OVERRIDE", "zink");
        env.put("LIBGL_ALWAYS_SOFTWARE", "0");
        env.remove("LIBGL_DRI3_DISABLE");
        env.put("LIBGL_KOPPER_DRI2", "true");
        env.remove("LIBGL_KOPPER_DISABLE");
        env.put("VK_DRIVER_FILES", runtime.getString("icdFile"));
        env.put("VK_ICD_FILENAMES", runtime.getString("icdFile"));
        env.put("REA_DIRECT_SOCKET", runtime.getString("surfaceSocket"));
        env.put("HYBRIS_LINKER_DIR", runtime.getString("linkerDirectory"));
        env.put("HYBRIS_ANDROID_SDK_VERSION", Integer.toString(android.os.Build.VERSION.SDK_INT));
        env.put(
                "HYBRIS_LD_LIBRARY_PATH",
                "/system/lib64:/vendor/lib64:/apex/com.android.runtime/lib64:/apex/com.android.runtime/lib64/bionic");
        env.put("LIBVULKAN", "/system/lib64/libvulkan.so");
        if ("mali".equals(runtime.optString("graphicsDriver")))
            env.put("HYBRIS_MALI_MMUD_SKIP_LOADER_CHECK", "0");
        if (runtime.optBoolean("adrenoCompatibility")) env.put("MESA_GL_VERSION_OVERRIDE", "3.3");
    }
}
