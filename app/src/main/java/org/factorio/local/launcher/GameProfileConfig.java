package org.factorio.local.launcher;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;

/** Path mapping and explicitly selected graphics options; preserve other settings. */
final class GameProfileConfig {
    private static void validateVersion(String original) throws IOException {
        if(original==null||original.length()>1024*1024||!original.split("\\r?\\n",2)[0].equals("; version=13"))
            throw new IOException("游戏配置格式不受支持，已保留原文件，请导出诊断");
    }

    /** Reads only a single locale from supported config text; never modifies the source. */
    static String readLocale(String original) throws IOException {
        validateVersion(original);
        String locale=null;boolean general=false,foundGeneral=false,foundLocale=false;
        for(String raw:original.split("\\r?\\n",-1)) {
            int comment=raw.indexOf(';');
            String line=(comment<0?raw:raw.substring(0,comment)).trim();
            if(line.isEmpty())continue;
            if(line.startsWith("[")) {
                if(!line.endsWith("]"))throw new IOException("配置区段格式不受支持，已保留原文件");
                general=line.equals("[general]");
                if(general&&foundGeneral)throw new IOException("配置包含重复的通用区段，已保留原文件");
                foundGeneral|=general;
                continue;
            }
            if(!general)continue;
            int equals=line.indexOf('=');
            if(equals<0||!line.substring(0,equals).trim().equals("locale"))continue;
            if(foundLocale)throw new IOException("配置包含重复的语言设置，已保留原文件");
            foundLocale=true;locale=line.substring(equals+1).trim();
            if(locale.length()>32||!locale.matches("[A-Za-z]{2,8}(?:[-_][A-Za-z0-9]{1,8})*"))
                throw new IOException("游戏语言设置格式不受支持，已保留原文件");
        }
        return locale;
    }

    static String mapPaths(String original,String read,String write) throws IOException {
        validateVersion(original);
        if(read.indexOf('\n')>=0||write.indexOf('\n')>=0||read.indexOf('\r')>=0||write.indexOf('\r')>=0)
            throw new IOException("游戏数据路径包含无效字符");
        StringBuilder updated=new StringBuilder();boolean path=false,found=false;
        String[] lines=original.split("\\r?\\n",-1);int count=lines.length;
        if(count>0&&lines[count-1].isEmpty())count--;
        for(int i=0;i<count;i++) {
            String line=lines[i],trimmed=line.trim();
            if(trimmed.startsWith("[")&&trimmed.endsWith("]")) {
                if(path)appendPaths(updated,read,write);
                path=trimmed.equals("[path]");
                if(path&&found)throw new IOException("配置包含重复的路径区段，已保留原文件");
                found|=path;
            }
            if(path&&(trimmed.matches("read-data\\s*=.*")||trimmed.matches("write-data\\s*=.*")))continue;
            updated.append(line).append('\n');
        }
        if(path||!found) {
            if(!found)updated.append("[path]\n");
            appendPaths(updated,read,write);
        }
        return updated.toString();
    }
    private static void appendPaths(StringBuilder result,String read,String write) {
        result.append("read-data=").append(read).append("\nwrite-data=").append(write).append('\n');
    }

    static String applyGraphicsPreset(String original,String preset) throws IOException {
        validateVersion(original);
        if("game".equals(preset))return original;
        Map<String,String> options=new LinkedHashMap<>();
        if("compat".equals(preset)) {
            options.put("graphics-quality","medium");options.put("max-texture-size","4096");options.put("video-memory-usage","high");
        } else if("balanced".equals(preset)) {
            options.put("graphics-quality","medium");options.put("max-texture-size","8192");options.put("video-memory-usage","high");
        } else if("quality".equals(preset)) {
            options.put("graphics-quality","high");options.put("max-texture-size","8192");options.put("video-memory-usage","all");
        } else throw new IOException("未知画质预设，配置未修改");
        // Observed Mali/Zink compression self-test failed. CPU preparation of
        // texture assets does not change the GPU used to render game frames.
        options.put("gpu-accelerated-compression","false");
        options.put("gpu-accelerated-mipmap-compression","false");
        // Direct Zink high-resolution sprites exceeded memory with no DXT.
        // High-quality texture compression passed the full-size game test;
        // keep the previously validated medium presets unchanged.
        options.put("texture-compression-level","quality".equals(preset)?"high-quality":"none");
        StringBuilder out=new StringBuilder();boolean graphics=false,found=false;
        HashSet<String> seen=new HashSet<>();
        String[] lines=original.split("\\r?\\n",-1);int count=lines.length;
        if(count>0&&lines[count-1].isEmpty())count--;
        for(int i=0;i<count;i++) {
            String raw=lines[i];int comment=raw.indexOf(';');
            String effective=(comment<0?raw:raw.substring(0,comment)).trim();
            if(effective.startsWith("[")) {
                if(!effective.endsWith("]"))throw new IOException("配置区段格式不受支持，配置未修改");
                if(graphics)appendOptions(out,options);
                graphics=effective.equals("[graphics]");
                if(graphics&&found)throw new IOException("配置包含重复的图形区段，配置未修改");
                found|=graphics;
            }
            int equals=effective.indexOf('=');
            if(graphics&&equals>=0) {
                String key=effective.substring(0,equals).trim();
                if(options.containsKey(key)) {
                    if(!seen.add(key))throw new IOException("配置包含重复的图形设置，配置未修改");
                    continue;
                }
            }
            out.append(raw).append('\n');
        }
        if(!found)out.append("[graphics]\n");
        if(graphics||!found)appendOptions(out,options);
        return out.toString();
    }
    private static void appendOptions(StringBuilder out,Map<String,String> options) {
        for(Map.Entry<String,String> option:options.entrySet())out.append(option.getKey()).append('=').append(option.getValue()).append('\n');
    }
}
