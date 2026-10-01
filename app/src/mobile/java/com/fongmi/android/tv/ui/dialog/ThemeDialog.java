package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.theme.ThemeColorPickerDialog;
import com.fongmi.android.tv.theme.ThemeController;
import com.fongmi.android.tv.theme.ThemeEditor;
import com.fongmi.android.tv.theme.ThemeMode;
import com.fongmi.android.tv.theme.ThemePaletteStyle;
import com.fongmi.android.tv.theme.ThemePreviewView;
import com.fongmi.android.tv.theme.ThemeProfile;
import com.fongmi.android.tv.theme.ThemeProfileStore;
import com.fongmi.android.tv.theme.ThemeResolver;
import com.fongmi.android.tv.theme.ThemeTokens;
import com.fongmi.android.tv.theme.WebHtvAlertDialogBuilder;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;

/**
 * B-safe theme editor: 13 colour slots, 3 opacity slots, light/dark switching,
 * live preview, apply/cancel/reset.
 *
 * <p>All mutations go through {@link ThemeEditor}; nothing is persisted until the
 * user presses apply, and the panel is rebuilt from the draft after every change so
 * the displayed values can never drift from what would be saved.
 */
public final class ThemeDialog extends DialogFragment implements ThemePreviewView.Callbacks {

    private static final int[] PRESET_LABELS = {
            R.string.theme_editor_preset_default,
            R.string.theme_editor_preset_wallpaper,
            R.string.theme_editor_preset_blue,
            R.string.theme_editor_preset_teal,
            R.string.theme_editor_preset_green,
            R.string.theme_editor_preset_orange,
            R.string.theme_editor_preset_red,
            R.string.theme_editor_preset_purple,
    };
    private static final String[] PRESET_SOURCES = {
            ThemeProfile.SEED_NONE,
            ThemeProfile.SEED_WALLPAPER,
            ThemeProfile.SEED_CUSTOM,
            ThemeProfile.SEED_CUSTOM,
            ThemeProfile.SEED_CUSTOM,
            ThemeProfile.SEED_CUSTOM,
            ThemeProfile.SEED_CUSTOM,
            ThemeProfile.SEED_CUSTOM,
    };
    private static final String[] PRESET_COLORS = {
            null, null, "#0B57D0", "#00897B", "#146C2E", "#FB8C00", "#B3261E", "#8E24AA",
    };

    private ThemeEditor editor;
    private boolean dark;
    private LinearLayout root;
    private LinearLayout panel;
    private LinearLayout rowPresets;
    private LinearLayout rowPalette;
    private TextView status;

    public static void show(Fragment fragment) {
        new ThemeDialog().show(fragment.getChildFragmentManager(), ThemeDialog.class.getSimpleName());
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        editor = ThemeEditor.load();
        dark = isDarkNow();
        root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(4);
        root.setPadding(pad, pad, pad, pad);

        root.addView(buildModeRow());
        root.addView(buildPaletteRow());
        root.addView(buildPresetRow());

        status = new TextView(requireContext());
        status.setPadding(dp(8), dp(6), dp(8), dp(4));
        root.addView(status);

        ScrollView scroll = new ScrollView(requireContext());
        panel = new LinearLayout(requireContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(panel);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        rebuildPanel();

        return new WebHtvAlertDialogBuilder(requireContext())
                .setTitle(R.string.setting_theme_color)
                .setView(root)
                .setPositiveButton(R.string.theme_editor_apply, (dialog, which) -> applyDraft())
                .setNeutralButton(R.string.theme_editor_reset, null)
                .setNegativeButton(R.string.theme_editor_cancel, null)
                .create();
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog == null) return;
        configureWindow(dialog);
        Button reset = ((androidx.appcompat.app.AlertDialog) dialog).getButton(Dialog.BUTTON_NEUTRAL);
        reset.setOnClickListener(view -> {
            ThemeProfileStore.ApplyResult result = editor.reset();
            if (!result.success()) {
                setStatus(result.error());
                return;
            }
            editor = ThemeEditor.load();
            fillPaletteRow();
            fillPresetRow();
            rebuildPanel();
            setStatus(getString(R.string.theme_editor_reset_done));
            if (getParentFragment() instanceof AppearanceDialog appearance) appearance.onThemeProfileApplied();
        });
    }

    /**
     * Enlarges the editor to the same near-full-screen footprint as the ad-block
     * statistics dialog.
     *
     * <p>Only shrinking the gutter would not be enough: the editor carries a title row,
     * a preset row, a status line and the action buttons around a weighted scroll area,
     * so the window itself has to grow or the 13 colour slots and the live preview stay
     * compressed into the middle of the screen. The dialog was measured on device at
     * [405,160][1515,920] on a 1920x1080 panel - about 58% by 70% of the screen.
     *
     * <p>The gutter is a constant dp value, not a screen percentage, so the visible
     * margin is identical on every panel size and never reaches the edge of an
     * overscanning television.
     *
     * <p>The panel is not a view background: {@code WebHtvAlertDialogBuilder} paints it
     * through the window background. That drawable is therefore carried over with Material's
     * inset wrapper stripped - the wrapper is what kept the panel narrower than the window,
     * while the panel itself has to stay because nothing inside this editor paints its own
     * card.
     */
    private void configureWindow(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        int margin = ResUtil.dp2px(ThemeDialogLayout.marginDp(Util.isLeanback()));
        int width = ThemeDialogLayout.width(ResUtil.getScreenWidth(requireContext()), margin);
        int height = ThemeDialogLayout.height(ResUtil.getScreenHeight(requireContext()), margin);
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = width;
        params.height = height;
        params.gravity = Gravity.CENTER;
        // Capture the themed panel before touching the window background, then reinstall it
        // without Material's inset gutter. Handing the window a transparent fill instead
        // erases the panel and leaves the page behind the editor showing through it.
        View decorView = window.getDecorView();
        Drawable panel = ThemeDialogLayout.panelBackground(decorView.getBackground());
        if (panel != null) window.setBackgroundDrawable(panel);
        decorView.setPadding(0, 0, 0, 0);
        window.setAttributes(params);
        window.setLayout(width, height);
        // AlertController installs the custom view with a wrap_content height, so a tall
        // window on its own still leaves the panel centred in a short box. Matching the
        // parent lets the weighted scroll area absorb the freed space.
        if (root != null && root.getLayoutParams() != null) {
            ViewGroup.LayoutParams rootParams = root.getLayoutParams();
            rootParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            rootParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            root.setLayoutParams(rootParams);
        }
    }

    private View buildModeRow() {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(8), dp(6), dp(8), dp(2));

        // Platform widgets created from the Activity context carry no Material role, so they
        // must be coloured explicitly or they keep the framework default (Material's
        // #49454F) and become unreadable once the user picks a dark custom surface.
        TextView label = new TextView(requireContext());
        label.setText(R.string.theme_editor_edit_mode);
        label.setTextColor(ThemeController.current().colorOnSurface());
        row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button toggle = new Button(requireContext());
        toggle.setAllCaps(false);
        toggle.setText(dark ? R.string.theme_editor_dark : R.string.theme_editor_light);
        toggle.setTextColor(ThemeController.current().colorOnSurface());
        toggle.setBackground(outlinedPill());
        toggle.setOnClickListener(view -> {
            dark = !dark;
            toggle.setText(dark ? R.string.theme_editor_dark : R.string.theme_editor_light);
            rebuildPanel();
        });
        row.addView(toggle);

        Button importButton = new Button(requireContext());
        importButton.setAllCaps(false);
        importButton.setText(R.string.theme_editor_import);
        importButton.setTextColor(ThemeController.current().colorPrimary());
        importButton.setBackground(outlinedPill());
        importButton.setOnClickListener(view -> ThemeImportDialog.show(this, profile -> {
            editor = new ThemeEditor(profile);
            fillPaletteRow();
            fillPresetRow();
            rebuildPanel();
            setStatus(getString(R.string.theme_editor_dirty));
        }));
        row.addView(importButton);
        return row;
    }

    private View buildPaletteRow() {
        HorizontalScrollView scroll = new HorizontalScrollView(requireContext());
        rowPalette = new LinearLayout(requireContext());
        rowPalette.setOrientation(LinearLayout.HORIZONTAL);
        rowPalette.setPadding(dp(8), dp(2), dp(8), dp(2));
        fillPaletteRow();
        scroll.addView(rowPalette);
        return scroll;
    }

    private void fillPaletteRow() {
        if (rowPalette == null) return;
        rowPalette.removeAllViews();
        String active = ThemeProfile.normalizePaletteStyle(editor.draft().paletteStyle);
        for (ThemePaletteStyle style : ThemePaletteStyle.values()) {
            boolean selected = active.equals(style.id());
            int fill = paletteColor(style);
            Button button = new Button(requireContext());
            button.setAllCaps(false);
            button.setText(getString(paletteLabel(style)));
            button.setTextColor(readableOn(fill));
            button.setBackground(presetBackground(fill, selected, true));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.rightMargin = dp(8);
            button.setLayoutParams(params);
            button.setOnClickListener(view -> {
                editor.setPaletteStyle(style.id());
                fillPaletteRow();
                rebuildPanel();
            });
            rowPalette.addView(button);
        }
    }

    private int paletteColor(ThemePaletteStyle style) {
        ThemeEditor probe = new ThemeEditor(editor.draft());
        probe.setPaletteStyle(style.id());
        ThemeProfile profile = probe.draft();
        int seed = ThemeProfileStore.legacyThemeColor(profile);
        ThemeTokens tokens = ThemeResolver.resolve(dark ? ThemeMode.DARK : ThemeMode.LIGHT,
                ThemePreviewView.seedOf(profile), seed, Setting.getWallColor(), profile, null, dark);
        return tokens.colorPrimary();
    }

    private int paletteLabel(ThemePaletteStyle style) {
        return switch (style) {
            case TONAL_SPOT -> R.string.theme_editor_palette_tonal;
            case VIBRANT -> R.string.theme_editor_palette_vibrant;
            case EXPRESSIVE -> R.string.theme_editor_palette_expressive;
            case RAINBOW -> R.string.theme_editor_palette_rainbow;
            case FRUIT_SALAD -> R.string.theme_editor_palette_fruit_salad;
            case FIDELITY -> R.string.theme_editor_palette_fidelity;
            case CONTENT -> R.string.theme_editor_palette_content;
            case NEUTRAL -> R.string.theme_editor_palette_neutral;
            case MONOCHROME -> R.string.theme_editor_palette_monochrome;
        };
    }

    private View buildPresetRow() {
        HorizontalScrollView scroll = new HorizontalScrollView(requireContext());
        rowPresets = new LinearLayout(requireContext());
        rowPresets.setOrientation(LinearLayout.HORIZONTAL);
        rowPresets.setPadding(dp(8), dp(2), dp(8), dp(2));
        fillPresetRow();
        scroll.addView(rowPresets);
        return scroll;
    }

    /** Rebuilds the preset chips so the active seed always shows a visible highlight. */
    private void fillPresetRow() {
        if (rowPresets == null) return;
        rowPresets.removeAllViews();
        ThemeProfile profile = editor.draft();
        String activeSource = ThemeProfile.normalizeSeedSource(profile.seedSource);
        String activeColor = profile.seedColor;
        for (int i = 0; i < PRESET_LABELS.length; i++) {
            String source = PRESET_SOURCES[i];
            String color = PRESET_COLORS[i];
            boolean active = activeSource.equals(source)
                    && (!ThemeProfile.SEED_CUSTOM.equals(source)
                        || String.valueOf(activeColor).equalsIgnoreCase(color));
            rowPresets.addView(createPresetButton(getString(PRESET_LABELS[i]), source, color, active));
        }
    }

    private Button createPresetButton(String label, String seedSource, String seedColor, boolean active) {
        boolean colorPreview = ThemeProfile.SEED_CUSTOM.equals(seedSource);
        int fill = colorPreview ? presetColor(seedSource, seedColor) : neutralPresetBackground();
        Button button = new Button(requireContext());
        button.setAllCaps(false);
        button.setText(label);
        button.setTextColor(colorPreview ? readableOn(fill) : ThemeController.current().colorOnSurface());
        button.setBackground(presetBackground(fill, active, colorPreview));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.rightMargin = dp(8);
        button.setLayoutParams(params);
        button.setOnClickListener(view -> {
            ThemeEditor.Result result = editor.setSeed(seedSource, seedColor);
            if (!result.success()) {
                setStatus(result.error());
                return;
            }
            fillPresetRow();
            fillPaletteRow();
            rebuildPanel();
        });
        return button;
    }

    /** Resolves the preset's own resolved primary color so the chip previews reality. */
    private int presetColor(String seedSource, String seedColor) {
        ThemeEditor probeEditor = new ThemeEditor(editor.draft());
        ThemeEditor.Result seedResult = probeEditor.setSeed(seedSource, seedColor);
        if (!seedResult.success()) return ThemeController.current().colorPrimary();
        ThemeProfile probe = probeEditor.draft();
        try {
            int seedColorValue = ThemeProfileStore.legacyThemeColor(probe);
            ThemeTokens tokens = ThemeResolver.resolve(dark ? ThemeMode.DARK : ThemeMode.LIGHT,
                    ThemePreviewView.seedOf(probe), seedColorValue,
                    Setting.getWallColor(), probe, null, dark);
            return tokens.colorPrimary();
        } catch (RuntimeException ignored) {
            return ThemeController.current().colorPrimary();
        }
    }

    private int neutralPresetBackground() {
        return ThemeController.current().colorSurfaceContainerHighest();
    }

    /**
     * A themed pill for the framework {@code Button}, which ships its own light background
     * and would otherwise become white-on-white once its text follows the theme.
     */
    private GradientDrawable outlinedPill() {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(dp(18));
        shape.setColor(ThemeController.current().colorSurfaceContainer());
        shape.setStroke(dp(1), ThemeController.current().colorOutline());
        return shape;
    }

    private GradientDrawable presetBackground(int fill, boolean active, boolean colorPreview) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(dp(18));
        shape.setColor(fill);
        if (active) {
            shape.setStroke(dp(3), colorPreview ? readableOn(fill) : ThemeController.current().colorPrimary());
        } else if (colorPreview) {
            shape.setStroke(dp(1), ThemeController.current().colorOutline());
        }
        return shape;
    }

    private int readableOn(int color) {
        double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255d;
        return luminance > 0.6d ? Color.BLACK : Color.WHITE;
    }

    private void rebuildPanel() {
        panel.removeAllViews();
        panel.addView(ThemePreviewView.createPanel(requireContext(), editor, dark, this));
    }

    private void setStatus(String message) {
        status.setTextColor(ThemeController.current().colorOnSurfaceVariant());
        status.setText(message == null ? "" : message);
    }

    @Override
    public void onColorSlotClicked(ThemeEditor.Slot slot, boolean slotDark) {
        String initial = editor.valueOf(slot, slotDark);
        ThemeColorPickerDialog.create(requireContext(), labelOf(slot), initial, hex -> {
            ThemeEditor.Result result = editor.set(slot, slotDark, hex, 0f);
            if (!result.success()) setStatus(result.error());
            else rebuildPanel();
        }).show();
    }

    @Override
    public void onDraftChanged() {
        rebuildPanel();
        setStatus(getString(R.string.theme_editor_dirty));
    }

    private void applyDraft() {
        ThemeProfileStore.ApplyResult result = editor.apply();
        if (!result.success()) {
            setStatus(getString(R.string.theme_editor_save_failed, result.error()));
            return;
        }
        if (getParentFragment() instanceof AppearanceDialog appearance) appearance.onThemeProfileApplied();
        dismissAllowingStateLoss();
        RefreshEvent.theme();
    }

    private boolean isDarkNow() {
        return ThemeController.isNight(requireContext());
    }

    private String labelOf(ThemeEditor.Slot slot) {
        return getString(ThemePreviewView.labelOf(slot));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
