package com.fongmi.android.tv.theme;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.app.Dialog;
import android.os.Build;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

/** Read-only application entry point for future theme adoption. */
public final class ThemeController {

    private static volatile ThemeTokens current = ThemeTokens.light();
    private static volatile ThemeTokens baseline = ThemeTokens.light();
    private static volatile ThemeProfile profile = ThemeProfile.defaultProfile();

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

    /** The frozen Layer 1 palette the binder compares against. */
    public static ThemeTokens baseline() {
        return baseline;
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
        ThemeProfile stored = null;
        try {
            stored = ThemeProfileStore.load();
        } catch (RuntimeException ignored) {
            // Preference access is unavailable; Layer 1 defaults remain valid.
        }
        return resolveWith(stored);
    }

    private static ThemeTokens resolveWith(ThemeProfile profile) {
        boolean systemDark = (Resources.getSystem().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        ThemeMode themeMode = currentThemeMode();
        int themeColor = com.fongmi.android.tv.setting.Setting.getThemeColor();
        if (themeColor == -1) {
            return ThemeResolver.resolve(themeMode, ThemeSeed.NONE, 0, 0, profile, null, systemDark);
        }
        ThemeSeed seed = themeColor == 0 ? ThemeSeed.WALLPAPER : ThemeSeed.EXPLICIT;
        int explicit = themeColor == 0 ? 0 : themeColor;
        int wallpaper = com.fongmi.android.tv.setting.Setting.getWallColor();
        return ThemeResolver.resolve(themeMode, seed, explicit, wallpaper, profile, null, systemDark);
    }

    /**
     * The frozen compiled palette the binder compares view colours against.
     *
     * <p>Every {@code ?attr/color*} attribute was resolved from the static
     * {@code webhtv_color_*} resources when the view was inflated, so this baseline
     * must ignore {@code theme_color}/{@code wall_color}: those preferences only
     * reach the activity through {@link #resolveWith(ThemeProfile)}. Deriving the
     * baseline from the active seed made it describe colours no inflated view ever
     * held, and {@link ThemeBinder} then had nothing left to match - the feature
     * silently degraded to the legacy site dialog.
     */
    private static ThemeTokens frozenPalette() {
        boolean systemDark = (Resources.getSystem().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return ThemeResolver.resolve(currentThemeMode(), ThemeSeed.NONE, 0, 0, null, null, systemDark);
    }

    private static ThemeMode currentThemeMode() {
        int mode = com.fongmi.android.tv.setting.Setting.getThemeMode();
        return mode < 0 ? ThemeMode.SYSTEM : (mode == 0 ? ThemeMode.LIGHT : ThemeMode.DARK);
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
        ThemeProfile stored = null;
        try {
            stored = ThemeProfileStore.load();
        } catch (RuntimeException ignored) {
            // Preference access is unavailable; Layer 1 defaults remain valid.
        }
        profile = stored == null ? ThemeProfile.defaultProfile() : stored;
        baseline = frozenPalette();
        current = resolveWith(profile);
        if (activity == null || activity.isFinishing()) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        if (activity.getWindow().getStatusBarColor() != android.graphics.Color.TRANSPARENT) {
            activity.getWindow().setStatusBarColor(current.colorSurface());
        }
        if (activity.getWindow().getNavigationBarColor() != android.graphics.Color.TRANSPARENT) {
            activity.getWindow().setNavigationBarColor(current.colorSurface());
        }
    }

    /**
     * Applies the active profile to an already-created view tree.
     *
     * <p>When no profile override is active this returns immediately, so the
     * default Layer 1 path is byte-identical and costs nothing.
     */
    public static void bindTheme(View root) {
        ThemeBinder.bind(root, baseline, current);
    }

    /** Same controlled channel for a dialog or bottom-sheet window. */
    public static void bindDialog(Dialog dialog) {
        if (dialog == null || dialog.getWindow() == null) return;
        bindTheme(dialog.getWindow().getDecorView());
    }

    /**
     * Applies the active profile to a window-level background drawable.
     *
     * <p>Alert dialog panels live on the window rather than on a view, so they need
     * this second entry point next to {@link #bindTheme(View)}. It is a no-op while the
     * active tokens equal the frozen baseline.
     */
    public static void bindWindowBackground(Drawable background) {
        if (background == null) return;
        ThemeBinder.bindWindowBackground(background, baseline, current);
    }

    /** True when the active palette differs from the frozen compiled baseline. */
    public static boolean hasProfileOverrides() {
        return !baseline.equals(current);
    }

    public static ThemeProfile activeProfile() {
        return profile;
    }
}
