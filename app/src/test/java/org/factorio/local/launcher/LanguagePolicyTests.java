package org.factorio.local.launcher;

import java.util.Map;

public final class LanguagePolicyTests {
    private static int checks;

    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }

    public static void main(String[] args) {
        for (String tags :
                new String[] {
                    "zh", "zh-CN", "zh-TW", "zh-Hant-HK", "zh-Hans-SG", "ja-JP,zh-CN,en-US"
                })
            check(LanguagePolicy.gameLocale(tags).equals("zh-CN"), "Chinese preference " + tags);
        for (String tags :
                new String[] {
                    "en", "en-GB", "en-US,zh-CN", "de-DE", "fr-FR,ja-JP", "", "not_a_locale", null
                })
            check(
                    LanguagePolicy.gameLocale(tags).equals("en"),
                    "English preference/fallback " + tags);
        Map<String, String> chinese = LanguagePolicy.environment("zh-Hant-TW");
        check(chinese.get("LANG").equals("zh_CN.UTF-8"), "Unix primary locale");
        check(chinese.get("LANGUAGE").equals("zh_CN:zh:en"), "SDL fallback list");
        Map<String, String> english = LanguagePolicy.environment("fr-FR");
        check(
                english.get("LANG").equals("en_US.UTF-8")
                        && english.get("LANGUAGE").equals("en_US:en"),
                "fallback environment");
        check(
                !chinese.containsKey("LC_ALL") && !chinese.containsKey("LC_NUMERIC"),
                "numeric/runtime locale unaffected");
        check(
                LanguagePolicy.environment("zh-CN\nPATH=bad").equals(english),
                "invalid tags cannot inject environment");
        System.out.println("LanguagePolicyTests: " + checks + " checks passed");
    }
}
