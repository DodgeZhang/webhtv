package com.fongmi.android.tv.theme;

/** Produces a bounded, read-only token snapshot for trusted WebTheme pages. */
public final class ThemeWebBridge {

    private ThemeWebBridge() {
    }

    public static String snapshotJson(ThemeTokens tokens) {
        ThemeTokens safe = tokens == null ? ThemeTokens.light() : tokens;
        return "{\"primary\":\"" + hex(safe.colorPrimary()) + "\""
                + ",\"onPrimary\":\"" + hex(safe.colorOnPrimary()) + "\""
                + ",\"surface\":\"" + hex(safe.colorSurface()) + "\""
                + ",\"surfaceContainer\":\"" + hex(safe.colorSurfaceContainer()) + "\""
                + ",\"surfaceContainerHigh\":\"" + hex(safe.colorSurfaceContainerHigh()) + "\""
                + ",\"onSurface\":\"" + hex(safe.colorOnSurface()) + "\""
                + ",\"onSurfaceVariant\":\"" + hex(safe.colorOnSurfaceVariant()) + "\""
                + ",\"outline\":\"" + hex(safe.colorOutline()) + "\""
                + ",\"outlineVariant\":\"" + hex(safe.colorOutlineVariant()) + "\""
                + ",\"error\":\"" + hex(safe.colorError()) + "\""
                + ",\"success\":\"" + hex(safe.colorSuccess()) + "\""
                + ",\"warning\":\"" + hex(safe.colorWarning()) + "\""
                + ",\"focus\":\"" + hex(safe.colorFocus()) + "\""
                + ",\"focusScale\":" + safe.focusScale() + "}";
    }

    private static String hex(int color) {
        return String.format(java.util.Locale.US, "#%08X", color);
    }
}
