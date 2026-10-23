package org.factorio.local.launcher;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONObject;

/** Display only own bounded runtime evidence, without device-wide logs or screenshots. */
public final class RuntimeGraphicsInfo {
    private RuntimeGraphicsInfo() {}

    public static String describe(Context context) throws Exception {
        File file = new File(context.getFilesDir(), "game-session-report.json");
        if (!file.isFile()) return "还没有游戏运行记录。启动一次游戏后，可在这里查看实际渲染器和分辨率。";
        if (file.length() > 2097152) throw new java.io.IOException("运行报告过大，请导出诊断查看");
        JSONObject report =
                new JSONObject(
                        new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        JSONObject detail = report.optJSONObject("engineWindowProbe");
        if (detail == null) detail = report.optJSONObject("engineProbeProgress");
        if (detail == null) detail = new JSONObject();
        JSONObject live = detail.optJSONObject("liveGraphics");
        if (live == null) live = new JSONObject();
        StringBuilder text = new StringBuilder();
        long updated =
                report.optLong("updatedAtEpochMillis", report.optLong("collectedAtEpochMillis"));
        text.append("报告更新：")
                .append(
                        new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
                                .format(new Date(updated)));
        text.append("\n游戏加载：")
                .append(
                        !live.optString("fatalLine").isEmpty()
                                ? "引擎已报告崩溃"
                                : "true".equals(live.optString("initialized"))
                                        ? "已完成初始化"
                                        : report.optBoolean("sessionComplete")
                                                ? "会话已结束"
                                                : "准备或加载中");
        if (!live.optString("fatalLine").isEmpty())
            text.append("\n").append(live.optString("fatalLine"));
        String renderer = live.optString("rendererLine");
        text.append("\n\n实际游戏渲染器：\n").append(renderer.isEmpty() ? "引擎尚未报告" : renderer);
        if (renderer.isEmpty()) {
            JSONObject caps = detail.optJSONObject("glCapabilities");
            String output = caps == null ? "" : caps.optString("output");
            for (String line : output.split("\\r?\\n"))
                if (line.startsWith("OpenGL core profile renderer:")
                        || line.startsWith("OpenGL core profile version:"))
                    text.append("\n上下文预检：").append(line);
        }
        JSONObject size = report.optJSONObject("renderSize");
        if (size != null)
            text.append("\n\n渲染分辨率：")
                    .append(size.optInt("width"))
                    .append('×')
                    .append(size.optInt("height"))
                    .append("（")
                    .append(size.optInt("scalePercent"))
                    .append("%）\n屏幕 Surface：")
                    .append(size.optInt("surfaceWidth"))
                    .append('×')
                    .append(size.optInt("surfaceHeight"));
        String preset = report.optString("graphicsPreset");
        text.append("\n画质预设：")
                .append(
                        preset.equals("compat")
                                ? "兼容起步"
                                : preset.equals("balanced")
                                        ? "均衡画质"
                                        : preset.equals("quality") ? "高画质" : "使用游戏内设置");
        for (String key : new String[] {"graphicsOptions", "textureProcessor"})
            if (!live.optString(key).isEmpty()) text.append("\n\n").append(live.optString(key));
        text.append("\n\n渲染 FPS：暂未采集，请以游戏内计数器为准。屏幕刷新率不等于游戏 FPS。");
        if (report.has("error")) text.append("\n\n启动信息：").append(report.optString("error"));
        return text.toString();
    }
}
