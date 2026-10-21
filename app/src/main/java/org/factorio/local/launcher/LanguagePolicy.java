package org.factorio.local.launcher;

import java.util.*;

/** Language selection shared by initial game configuration and SDL's Unix locale hints. */
final class LanguagePolicy {
    static String language(String tags) {
        if(tags!=null)for(String tag:tags.split(",")) {
            tag=tag.trim();if(!tag.matches("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*"))continue;
            String code=Locale.forLanguageTag(tag).getLanguage();
            if(code.equals("zh")||code.equals("en"))return code;
        }
        return "en";
    }
    static String gameLocale(String tags){return language(tags).equals("zh")?"zh-CN":"en";}
    static Map<String,String> environment(String tags) {
        boolean chinese=language(tags).equals("zh");
        Map<String,String> env=new LinkedHashMap<>();
        env.put("LANG",chinese?"zh_CN.UTF-8":"en_US.UTF-8");
        env.put("LANGUAGE",chinese?"zh_CN:zh:en":"en_US:en");
        return env;
    }
}
