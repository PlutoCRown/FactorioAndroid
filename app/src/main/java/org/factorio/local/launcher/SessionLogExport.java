package org.factorio.local.launcher;

import java.io.File;
import java.util.*;
import java.util.regex.*;
import org.json.*;

/** Export-only transforms. The live reports used by the launcher are never rewritten. */
final class SessionLogExport {
    private static final Pattern NATIVE =
            Pattern.compile("/data/app/(?:~~[^/\\s\"']+/)?[^/\\s\"']+/lib/arm64");
    private final File files;
    private final String packageName;

    SessionLogExport(File files, JSONObject session) {
        this.files = files;
        JSONObject application = session.optJSONObject("application");
        packageName = application == null ? "" : application.optString("packageName");
    }

    String text(String value) {
        String result = value.replace(files.getAbsolutePath(), "$APP_FILES");
        if (!packageName.isEmpty()) {
            String prefix = "/(?:data/user/[0-9]+|data/data)/" + Pattern.quote(packageName);
            result = result.replaceAll(prefix + "/files", Matcher.quoteReplacement("$APP_FILES"));
            result = result.replaceAll(prefix + "/cache", Matcher.quoteReplacement("$APP_CACHE"));
        }
        return NATIVE.matcher(result).replaceAll(Matcher.quoteReplacement("$NATIVE_LIB"));
    }

    Object normalize(Object value) throws Exception {
        if (value instanceof String) return text((String) value);
        if (value instanceof JSONObject) {
            JSONObject source = (JSONObject) value, result = new JSONObject();
            for (Iterator<String> keys = source.keys(); keys.hasNext(); ) {
                String key = keys.next();
                if (key.equals("REA_IME_TOKEN")) continue;
                result.put(key, normalize(source.get(key)));
            }
            // Short command probes commonly store the exact same text twice.
            if (!result.optString("output").isEmpty()
                    && result.optString("output").equals(result.optString("outputTail")))
                result.remove("outputTail");
            return result;
        }
        if (value instanceof JSONArray) {
            JSONArray source = (JSONArray) value, result = new JSONArray();
            for (int i = 0; i < source.length(); i++) result.put(normalize(source.get(i)));
            return result;
        }
        return value;
    }

    static JSONObject copy(JSONObject value) throws Exception {
        return new JSONObject(value.toString());
    }

    static void externalText(JSONObject json, String key, String file, Map<String, byte[]> entries)
            throws Exception {
        byte[] bytes = entries.get(file);
        if (bytes == null || json.optString(key).isEmpty()) return;
        String raw = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        if (raw.contains(json.optString(key))) {
            json.remove(key);
            json.put(key + "File", file);
        }
    }

    static JSONObject summary(
            long epoch,
            JSONObject session,
            JSONObject engine,
            JSONObject device,
            JSONObject memory,
            JSONObject engineSession)
            throws Exception {
        JSONObject result = new JSONObject().put("schema", 1).put("sessionEpoch", epoch);
        pick(
                result,
                session,
                "application",
                "deviceAtSessionStart",
                "phase",
                "exitStatus",
                "error",
                "renderSize",
                "desktopOpenGLInitialized",
                "engineInitializationLogged",
                "graphicsPreset");
        if (device != null) {
            JSONObject info = new JSONObject();
            pick(
                    info,
                    device,
                    "model",
                    "manufacturer",
                    "androidRelease",
                    "sdkInt",
                    "collectedAtEpochMillis");
            JSONObject graphics = device.optJSONObject("graphics");
            if (graphics != null) pick(info, graphics, "glVendor", "glRenderer", "glVersion");
            info.put(
                    "scope",
                    "Last Android EGL/GLES device check; not the game's Vulkan context or session memory");
            if (!info.has("collectedAtEpochMillis"))
                info.put("collectedAtEpochMillis", JSONObject.NULL);
            result.put("lastDeviceCheck", info);
        }
        if (engine != null) {
            JSONObject outcome = new JSONObject();
            pick(
                    outcome,
                    engine,
                    "started",
                    "exitCode",
                    "startupError",
                    "fatalSignal",
                    "terminationRequestedBy",
                    "terminationReason",
                    "startupTermination",
                    "userStopRequested",
                    "normalExitCompleted",
                    "outputError",
                    "diagnosticWriteError");
            result.put("engine", outcome);
        }
        if (engineSession != null) {
            pick(result, engineSession, "gameVersion", "directZinkRuntime");
            if (!result.has("error") && engineSession.has("error"))
                result.put("error", engineSession.get("error"));
        }
        if (memory != null) result.put("sampledMemory", memory);
        result.put(
                "exitInterpretation",
                "137 alone is not OOM; launcher/window cleanup and the original failure are separate evidence");
        return result;
    }

    private static void pick(JSONObject to, JSONObject from, String... keys) throws Exception {
        for (String key : keys) if (from.has(key)) to.put(key, from.get(key));
    }

    static JSONObject sessionExits(
            JSONObject source, long epoch, JSONObject session, JSONObject process)
            throws Exception {
        JSONArray records = source.optJSONArray("records");
        if (records == null) return source;
        Set<Integer> pids = new HashSet<>();
        if (session.optInt("pid") > 0) pids.add(session.optInt("pid"));
        if (process != null)
            for (String key : new String[] {"enginePid", "windowPid"})
                if (process.optInt(key) > 0) pids.add(process.optInt(key));
        JSONObject result = copy(source);
        JSONArray selected = new JSONArray();
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.getJSONObject(i);
            if (record.optLong("timestamp") >= epoch
                    && (pids.isEmpty() || pids.contains(record.optInt("pid"))))
                selected.put(record);
        }
        return result.put("records", selected)
                .put("sessionEpoch", epoch)
                .put("omittedOtherRecords", records.length() - selected.length())
                .put(
                        "scope",
                        "Own-package exits matching this session's time and known PIDs; no records does not prove a normal exit or exclude an unreported native-child exit");
    }
}
