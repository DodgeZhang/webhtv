package com.fongmi.android.tv.theme;

import android.content.res.Configuration;
import android.content.res.Resources;

import androidx.appcompat.app.AppCompatActivity;

/** Read-only application entry point for future theme adoption. */
public final class ThemeController {

    private static volatile ThemeTokens current = ThemeTokens.light();

    private ThemeController() {
    }

    public static void apply(AppCompatActivity activity, ThemeTokens tokens) {
        ThemeTokens safe = tokens == null ? ThemeTokens.light() : tokens;
        current = safe;
        int color = safe.colorSurface();
        activity.getWindow().setStatusBarColor(color);
        activity.getWindow().setNavigationBarColor(color);
    }

    public static ThemeTokens current() {
        return current;
    }

    public static void refresh() {
        boolean systemDark = (Resources.getSystem().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        current = ThemeResolver.resolve(ThemeMode.SYSTEM, ThemeSeed.NONE, 0, 0, systemDark);
    }
}
