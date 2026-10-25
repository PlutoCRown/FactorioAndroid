package org.factorio.local.launcher;

import android.content.Context;
import java.io.File;
import org.json.JSONObject;

/** Release builds do not accept diagnostic provider overrides. */
final class GraphicsRuntimeEvaluation {
    static boolean useSourceAdapters(Context context) {
        return false;
    }

    static File config(Context context, File original) {
        return original;
    }

    static JSONObject apply(Context context, JSONObject runtime) {
        return runtime;
    }
}
