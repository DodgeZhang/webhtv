package com.fongmi.android.tv.theme;

import com.google.android.material.color.utilities.DynamicScheme;
import com.google.android.material.color.utilities.Hct;
import com.google.android.material.color.utilities.SchemeTonalSpot;

/** Resolves semantic tokens without exposing seed colors directly to UI surfaces. */
public final class ThemeResolver {

    private static final ThreadLocal<String> LAST_DIAGNOSTIC = ThreadLocal.withInitial(() -> "default");

    private ThemeResolver() {
    }

    public static ThemeTokens resolve(ThemeMode mode, ThemeSeed seed, int seedColor,
                                      int wallpaperColor, boolean systemDark) {
        boolean dark = mode == ThemeMode.DARK || (mode != ThemeMode.LIGHT && systemDark);
        ThemeTokens fallback = dark ? ThemeTokens.dark() : ThemeTokens.light();
        if (seed == null || seed == ThemeSeed.NONE) {
            LAST_DIAGNOSTIC.set("default:" + (dark ? "dark" : "light"));
            return fallback;
        }
        int source = seed == ThemeSeed.WALLPAPER ? wallpaperColor : seedColor;
        if (!isOpaque(source)) {
            LAST_DIAGNOSTIC.set("fallback:invalid-seed:" + seed.name().toLowerCase(java.util.Locale.US));
            return fallback;
        }
        try {
            ThemeTokens candidate = derive(source, dark, fallback);
            candidate.requireContrast();
            LAST_DIAGNOSTIC.set("seed:" + seed.name().toLowerCase(java.util.Locale.US));
            return candidate;
        } catch (RuntimeException error) {
            LAST_DIAGNOSTIC.set("fallback:seed-contrast:" + error.getMessage());
            return fallback;
        }
    }

    public static String lastDiagnostic() {
        return LAST_DIAGNOSTIC.get();
    }

    static ThemeTokens requireOrFallback(ThemeTokens candidate, ThemeTokens fallback) {
        try {
            return candidate.requireContrast();
        } catch (IllegalArgumentException error) {
            LAST_DIAGNOSTIC.set("fallback:seed-contrast:" + error.getMessage());
            return fallback;
        }
    }

    private static ThemeTokens derive(int seedColor, boolean dark, ThemeTokens fallback) {
        DynamicScheme scheme = new SchemeTonalSpot(Hct.fromInt(seedColor), dark, 0.0);
        return new ThemeTokens(
                scheme.getPrimary(), scheme.getOnPrimary(), scheme.getPrimaryContainer(), scheme.getOnPrimaryContainer(),
                scheme.getSecondary(), scheme.getOnSecondary(), scheme.getSecondaryContainer(), scheme.getOnSecondaryContainer(),
                scheme.getTertiary(), scheme.getOnTertiary(), scheme.getError(), scheme.getOnError(),
                scheme.getErrorContainer(), scheme.getOnErrorContainer(),
                fallback.colorSuccess(), fallback.colorOnSuccess(), fallback.colorSuccessContainer(), fallback.colorOnSuccessContainer(),
                fallback.colorWarning(), fallback.colorOnWarning(), fallback.colorWarningContainer(), fallback.colorOnWarningContainer(),
                scheme.getSurface(), scheme.getSurfaceDim(), scheme.getSurfaceBright(), scheme.getSurfaceContainerLowest(),
                scheme.getSurfaceContainerLow(), scheme.getSurfaceContainer(), scheme.getSurfaceContainerHigh(), scheme.getSurfaceContainerHighest(),
                scheme.getOnSurface(), scheme.getOnSurfaceVariant(), scheme.getOutline(), scheme.getOutlineVariant(),
                scheme.getInverseSurface(), scheme.getInverseOnSurface(), scheme.getInversePrimary(), scheme.getScrim(),
                scheme.getShadow(), scheme.getPrimary(), fallback.focusScale(), fallback.colorPlayerControl(),
                fallback.colorPlayerControlMuted(), fallback.colorPlayerControlActive(), fallback.colorPlayerScrim(),
                fallback.colorHealthGood(), fallback.colorHealthWarn(), fallback.colorHealthBad(), fallback.colorOverlayLight(), fallback.colorOverlayDark()
        );
    }

    private static boolean isOpaque(int color) {
        return ((color >>> 24) & 0xFF) == 0xFF;
    }
}
