package org.factorio.local.launcher;

import android.content.Context;
import java.util.Map;

final class AppLanguage {
    private static String tags(Context context) {
        return context.getResources().getConfiguration().getLocales().toLanguageTags();
    }

    static String gameLocale(Context context) {
        return LanguagePolicy.gameLocale(tags(context));
    }

    static Map<String, String> environment(Context context) {
        return LanguagePolicy.environment(tags(context));
    }
}
