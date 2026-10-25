package org.factorio.local.launcher;

import java.io.*;
import java.util.*;
import org.json.*;

/** Build-generated hashes cover the six providers as one coherent Mesa version. */
final class MesaRuntime {
    static final String VERSION = "26.1.0-devel";
    static final String SOURCE = "ba042a8e79513c87006f4dd2dc15103724c26cfc";

    static boolean compatibleDevice() {
        return GraphicsDriver.current().compatibleMesa();
    }

    static boolean apply(JSONObject manifest, GraphicsDriver driver) throws Exception {
        if (!driver.compatibleMesa()) return false;
        // BuildMesaNative verifies the coherent provider set before packaging.
        // Provider selection uses driver files, never a device model or game hash.
        Set<String> expected =
                new HashSet<>(
                        Arrays.asList(
                                "librea_direct_icd.so",
                                "librea_direct_mesa_gallium.so",
                                "librea_direct_mesa_egl.so",
                                "librea_direct_mesa_glx.so",
                                "librea_direct_mesa_gbm.so",
                                "librea_direct_mesa_gbm_dri.so",
                                "librea_direct_mesa_dril.so"));
        JSONArray rows = manifest.getJSONArray("libraries");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String name = row.getString("packagedName");
            if (!expected.remove(name)) continue;
            String packaged =
                    name.equals("librea_direct_icd.so")
                            ? "librea_compat_direct_icd.so"
                            : name.replace("librea_direct_mesa_", "librea_compat_mesa_");
            row.put("packagedName", packaged).put("sourceBuilt", true);
            if (name.equals("librea_direct_mesa_gallium.so"))
                row.put("aliases", new JSONArray().put("libgallium-" + VERSION + ".so"));
        }
        if (!expected.isEmpty()) throw new IOException("Incomplete Mesa providers");
        return true;
    }
}
