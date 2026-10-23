package org.factorio.local.launcher;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import java.io.File;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Own-package OS exit records survive loss of the window process itself. */
final class SessionExitDiagnostics {
    static synchronized void collect(Context context) {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            ActivityManager manager =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            JSONArray records = new JSONArray();
            for (ApplicationExitInfo exit :
                    manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 16)) {
                records.put(
                        new JSONObject()
                                .put("pid", exit.getPid())
                                .put("process", exit.getProcessName())
                                .put("timestamp", exit.getTimestamp())
                                .put("reason", exit.getReason())
                                .put("reasonName", reasonName(exit.getReason()))
                                .put("status", exit.getStatus())
                                .put("description", exit.getDescription())
                                .put("pssKiB", exit.getPss())
                                .put("rssKiB", exit.getRss()));
            }
            JSONObject report =
                    new JSONObject()
                            .put("collectedAtEpochMillis", System.currentTimeMillis())
                            .put("records", records)
                            .put(
                                    "scope",
                                    "Own Android package only; OS records may omit GNU engine child exits");
            SessionDiagnostics.atomicWrite(
                    new File(context.getFilesDir(), "last-process-exits.json"),
                    report.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Exception unavailable) {
            android.util.Log.w(
                    "FactorioLauncher",
                    "Own exit history unavailable: " + unavailable.getClass().getSimpleName());
        }
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF:
                return "EXIT_SELF";
            case ApplicationExitInfo.REASON_SIGNALED:
                return "SIGNALED";
            case ApplicationExitInfo.REASON_LOW_MEMORY:
                return "LOW_MEMORY";
            case ApplicationExitInfo.REASON_CRASH:
                return "CRASH";
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
                return "CRASH_NATIVE";
            case ApplicationExitInfo.REASON_ANR:
                return "ANR";
            case ApplicationExitInfo.REASON_USER_REQUESTED:
                return "USER_REQUESTED";
            case ApplicationExitInfo.REASON_USER_STOPPED:
                return "USER_STOPPED";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED:
                return "DEPENDENCY_DIED";
            default:
                return "OTHER_OR_UNKNOWN";
        }
    }
}
