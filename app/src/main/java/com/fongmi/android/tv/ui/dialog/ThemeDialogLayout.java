package com.fongmi.android.tv.ui.dialog;

/**
 * Window sizing for the theme colour editor.
 *
 * <p>The editor previously inherited Material's default alert width, so the 13 colour
 * slots, the 3 opacity sliders and the live preview were squeezed into a narrow column
 * and the dialog covered only about 58% x 70% of a 1920x1080 screen - measured on
 * device at [405,160][1515,920]. It now follows the same contract as the ad-block
 * statistics dialog: fill the screen minus a constant safety margin.
 *
 * <p>The margin is a fixed dp value rather than a screen percentage. A percentage
 * silently changes the usable area with the panel resolution, while the editor's rows
 * have a fixed dp height and the preview needs a predictable amount of room; a fixed
 * margin keeps the same physical gutter on every device. It also keeps the dialog from
 * ever reaching the very edge of an overscanning TV panel.
 */
final class ThemeDialogLayout {

    /** Matches the ad-block statistics dialog's mobile gutter. */
    static final int MOBILE_MARGIN_DP = 16;

    /** Matches the ad-block statistics dialog's television gutter. */
    static final int LEANBACK_MARGIN_DP = 24;

    private ThemeDialogLayout() {
    }

    /** Screen edge gutter in dp for the running flavour. */
    static int marginDp(boolean leanback) {
        return leanback ? LEANBACK_MARGIN_DP : MOBILE_MARGIN_DP;
    }

    /** Window width that keeps {@code margin} on both sides, never below one pixel. */
    static int width(int screenWidth, int margin) {
        return Math.max(1, Math.max(1, screenWidth) - Math.max(0, margin) * 2);
    }

    /** Window height that keeps {@code margin} above and below, never below one pixel. */
    static int height(int screenHeight, int margin) {
        return Math.max(1, Math.max(1, screenHeight) - Math.max(0, margin) * 2);
    }
}
