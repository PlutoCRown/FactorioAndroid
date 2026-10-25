package org.factorio.local.launcher;

import android.content.Context;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.json.JSONArray;
import org.json.JSONObject;

/** Coherent traditional-renderpass providers, selected by actual Vulkan features. */
final class LegacyMesaRuntime {
    static String apply(Context context, JSONObject manifest) throws Exception {
        Properties properties = new Properties();
        try (InputStream input = context.getAssets().open("legacy-mesa.properties")) {
            properties.load(input);
        }
        String version = properties.getProperty("version");
        if (version == null || version.isEmpty())
            throw new IOException("Legacy Mesa version missing");
        JSONArray original = manifest.getJSONArray("libraries"), rows = new JSONArray();
        int replacements = 0;
        for (int i = 0; i < original.length(); i++) {
            JSONObject row = original.getJSONObject(i);
            String name = row.getString("packagedName");
            if (name.equals("librea_direct_mesa_gbm_dri.so")
                    || name.equals("librea_direct_mesa_dril.so")) continue;
            if (name.equals("librea_direct_icd.so")) {
                row.put("packagedName", "librea_compat_direct_icd.so").put("sourceBuilt", true);
                replacements++;
            } else if (name.startsWith("librea_direct_mesa_")) {
                row.put("packagedName", name.replace("librea_direct_mesa_", "librea_legacy_mesa_"))
                        .put("sourceBuilt", true);
                if (name.endsWith("_gallium.so"))
                    row.put(
                            "aliases",
                            new JSONArray().put("dri/zink_dri.so").put("libgallium_dri.so"));
                replacements++;
            }
            rows.put(row);
        }
        if (replacements != 5)
            throw new IOException("Incomplete traditional Mesa provider mapping");
        rows.put(
                new JSONObject()
                        .put("packagedName", "librea_legacy_mesa_glapi.so")
                        .put("aliases", new JSONArray().put("libglapi.so.0"))
                        .put("sourceBuilt", true));
        manifest.put("libraries", rows);
        return version;
    }
}
