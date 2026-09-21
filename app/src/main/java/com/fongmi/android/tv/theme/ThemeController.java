package com.fongmi.android.tv.theme;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

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

    /**
     * Keeps AppCompat's DayNight configuration aligned with the persisted
     * appearance mode so the whole activity tree (mobile and leanback) shares
     * one resolution order: explicit mode > system > product default.
     */
    public static void applyNightModeToApp() {
        int mode = com.fongmi.android.tv.setting.Setting.getThemeMode();
        int delegate = switch (mode) {
            case 0 -> AppCompatDelegate.MODE_NIGHT_NO;
            case 1 -> AppCompatDelegate.MODE_NIGHT_YES;
            default -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        };
        if (AppCompatDelegate.getDefaultNightMode() != delegate) {
            AppCompatDelegate.setDefaultNightMode(delegate);
        }
    }

    public static ThemeTokens current() {
        return current;
    }

    /**
     * Single source of truth for "the current app appearance is dark".
     *
     * <p>The Activity configuration already reflects a forced {@code theme_mode}, so
     * ordinary UI must ask this method instead of reading the raw system night bit,
     * which would contradict an explicit light/dark choice.
     */
    public static boolean isNight(Context context) {
        Configuration configuration = context == null ? Resources.getSystem().getConfiguration()
                : context.getResources().getConfiguration();
        return (configuration.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    public static void refresh() {
        boolean systemDark = (Resources.getSystem().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        current = ThemeResolver.resolve(ThemeMode.SYSTEM, ThemeSeed.NONE, 0, 0, systemDark);
    }

    /**
     * Resolves the active semantic snapshot from the persisted appearance
     * preferences. {@code themeColor} follows the existing WebHTV contract:
     * {@code -1} disables tinting, {@code 0} uses the wallpaper seed and any
     * other value is an explicit ARGB seed.
     */
    public static ThemeTokens resolveFromPreferences() {
        boolean systemDark = (Resources.getSystem().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int mode = com.fongmi.android.tv.setting.Setting.getThemeMode();
        ThemeMode themeMode = mode < 0 ? ThemeMode.SYSTEM : (mode == 0 ? ThemeMode.LIGHT : ThemeMode.DARK);
        int themeColor = com.fongmi.android.tv.setting.Setting.getThemeColor();
        if (themeColor == -1) {
            return ThemeResolver.resolve(themeMode, ThemeSeed.NONE, 0, 0, systemDark);
        }
        ThemeSeed seed = themeColor == 0 ? ThemeSeed.WALLPAPER : ThemeSeed.EXPLICIT;
        int explicit = themeColor == 0 ? 0 : themeColor;
        int wallpaper = com.fongmi.android.tv.setting.Setting.getWallColor();
        return ThemeResolver.resolve(themeMode, seed, explicit, wallpaper, systemDark);
    }

    /**
     * Applies the persisted appearance snapshot to one activity.
     *
     * <p>Only the read-only token snapshot is refreshed here. System-bar colors
     * are updated solely when the window still owns an opaque bar; edge-to-edge
     * windows keep their transparent bars so this hook cannot regress the
     * existing immersive layouts.
     */
    public static void applyFromPreferences(AppCompatActivity activity) {
        current = resolveFromPreferences();
        if (activity == null || activity.isFinishing()) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        if (activity.getWindow().getStatusBarColor() != android.graphics.Color.TRANSPARENT) {
            activity.getWindow().setStatusBarColor(current.colorSurface());
        }
        if (activity.getWindow().getNavigationBarColor() != android.graphics.Color.TRANSPARENT) {
            activity.getWindow().setNavigationBarColor(current.colorSurface());
        }
    }
}
