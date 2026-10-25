package org.factorio.local.launcher;

import org.json.JSONObject;

/** System Vulkan features, queried once per process; failures preserve the existing selection. */
final class GraphicsCapabilities {
    private static JSONObject cached;

    private static native String query();

    static synchronized JSONObject current() {
        if (cached == null) {
            try {
                System.loadLibrary("rea_direct_present");
                cached = new JSONObject(query());
            } catch (Throwable failure) {
                cached = new JSONObject();
                try {
                    cached.put("status", "unavailable").put("error", failure.toString());
                } catch (Exception ignored) {
                }
            }
        }
        return cached;
    }

    static boolean needsLegacy(boolean known, boolean dynamicRendering, boolean nullDescriptor) {
        return known && (!dynamicRendering || !nullDescriptor);
    }

    static boolean needsLegacy() {
        JSONObject values = current();
        return needsLegacy(
                "ok".equals(values.optString("status")),
                values.optBoolean("dynamicRendering"),
                values.optBoolean("nullDescriptor"));
    }
}
