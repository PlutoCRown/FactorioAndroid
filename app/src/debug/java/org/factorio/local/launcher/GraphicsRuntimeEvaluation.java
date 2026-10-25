package org.factorio.local.launcher;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;

/** Debug-only provider override for device probes; no user preference is created. */
final class GraphicsRuntimeEvaluation {
    static boolean useSourceAdapters(Context context) {
        return new File(context.getFilesDir(), "graphics-runtime-evaluation.json").isFile();
    }

    static File config(Context context, File original) throws Exception {
        File request = new File(context.getFilesDir(), "graphics-runtime-evaluation.json");
        if (!request.isFile()) return original;
        JSONObject overrides = GameImporter.readJson(request).optJSONObject("config");
        if (overrides == null || overrides.length() == 0) return original;
        String text = new String(Files.readAllBytes(original.toPath()), StandardCharsets.UTF_8);
        StringBuilder additions = new StringBuilder();
        for (String key :
                new String[] {
                    "gpu-accelerated-compression",
                    "gpu-accelerated-mipmap-compression",
                    "texture-compression-level"
                }) {
            if (!overrides.has(key)) continue;
            String value = overrides.getString(key);
            if (key.equals("texture-compression-level")
                    ? !(value.equals("none") || value.equals("high-quality"))
                    : !(value.equals("true") || value.equals("false")))
                throw new java.io.IOException("Unsupported diagnostic config value");
            text = text.replaceAll("(?m)^\\s*" + key + "\\s*=.*$", "");
            additions.append(key).append("=").append(value).append("\n");
        }
        if (additions.length() == 0) return original;
        text = text.replaceFirst("(?m)^\\[graphics\\]\\s*$", "[graphics]\n" + additions);
        File copy = new File(context.getCacheDir(), "graphics-evaluation-config.ini");
        Files.write(copy.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return copy;
    }

    static JSONObject apply(Context context, JSONObject runtime) throws Exception {
        File request = new File(context.getFilesDir(), "graphics-runtime-evaluation.json");
        if (!request.isFile()) return runtime;
        JSONObject options = GameImporter.readJson(request);
        File directory = new File(context.getFilesDir(), "runtime/legacy-zink-evaluation");
        File bridge = new File(runtime.getString("directory"));
        for (String name :
                new String[] {
                    "libvulkan.so.1",
                    "libvulkan.so",
                    "libhybris-common.so.1",
                    "libhardware.so.2",
                    "libgralloc.so.1",
                    "libui.so.1",
                    "librea_direct_icd.so",
                    "librea_gnu_tls.so"
                }) {
            RuntimeLibraries.alias(directory, name, new File(bridge, name));
        }
        for (String name :
                new String[] {
                    "libEGL_mesa.so.0",
                    "libGLX_mesa.so.0",
                    "libgbm.so.1",
                    "libglapi.so.0",
                    "dri/zink_dri.so",
                    "mesa-vendor.json",
                    "source-build.json"
                }) {
            if (!new File(directory, name).isFile())
                throw new java.io.IOException("Evaluation provider missing: " + name);
        }
        File icd = new File(directory, "direct-icd.json");
        Files.write(
                icd.toPath(),
                new JSONObject()
                        .put("file_format_version", "1.0.0")
                        .put(
                                "ICD",
                                new JSONObject()
                                        .put(
                                                "library_path",
                                                new File(
                                                                context.getApplicationInfo()
                                                                        .nativeLibraryDir,
                                                                "librea_compat_direct_icd.so")
                                                        .getAbsolutePath())
                                        .put("api_version", "1.4.0"))
                        .toString()
                        .getBytes(StandardCharsets.UTF_8));
        JSONObject build = GameImporter.readJson(new File(directory, "source-build.json"));
        String version = build.getString("version");
        runtime.put("directory", directory.getAbsolutePath())
                .put("icdFile", icd.getAbsolutePath())
                .put("vendorFile", new File(directory, "mesa-vendor.json").getAbsolutePath())
                .put("desktopMesa", version)
                .put("mesaSource", "mesa-" + version)
                .put("compatibleMesa", false)
                .put("graphicsEvaluation", true);
        if (options.optBoolean("requestGl33", false))
            runtime.put("evaluationGlVersionOverride", "3.3");
        if (options.has("zinkDebug")) {
            String flags = options.getString("zinkDebug");
            if (!flags.matches("[a-z0-9_,]{1,160}"))
                throw new java.io.IOException("Invalid Zink diagnostic flags");
            runtime.put("evaluationZinkDebug", flags);
        }
        return runtime;
    }
}
