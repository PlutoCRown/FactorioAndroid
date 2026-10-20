package org.factorio.local.launcher;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Own-package OS exit records survive loss of the window process itself. */
final class SessionExitDiagnostics {
    static void collect(Context context) {
        if(Build.VERSION.SDK_INT<30)return;
        try {
            ActivityManager manager=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
            JSONArray records=new JSONArray();
            for(ApplicationExitInfo exit:manager.getHistoricalProcessExitReasons(context.getPackageName(),0,16)) {
                records.put(new JSONObject().put("pid",exit.getPid()).put("process",exit.getProcessName())
                    .put("timestamp",exit.getTimestamp()).put("reason",exit.getReason()).put("status",exit.getStatus())
                    .put("description",exit.getDescription()).put("pssKiB",exit.getPss()).put("rssKiB",exit.getRss()));
            }
            JSONObject report=new JSONObject().put("collectedAtEpochMillis",System.currentTimeMillis())
                .put("records",records).put("scope","Own Android package only; OS records may omit GNU engine child exits");
            File pending=new File(context.getFilesDir(),"last-process-exits.json.pending");
            Files.write(pending.toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
            Files.move(pending.toPath(),new File(context.getFilesDir(),"last-process-exits.json").toPath(),
                StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        }catch(Exception unavailable){android.util.Log.w("FactorioLauncher","Own exit history unavailable: "+unavailable.getClass().getSimpleName());}
    }
}
