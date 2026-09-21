package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.os.Bundle;
import android.view.View;
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
import com.fongmi.android.tv.theme.ThemeColorPickerDialog;
import com.fongmi.android.tv.theme.ThemeController;
import com.fongmi.android.tv.theme.ThemeEditor;
import com.fongmi.android.tv.theme.ThemePreviewView;
import com.fongmi.android.tv.theme.ThemeProfile;
import com.fongmi.android.tv.theme.ThemeProfileStore;

/**
 * B-safe theme editor: 13 colour slots, 3 opacity slots, light/dark switching,
 * live preview, apply/cancel/reset.
 *
 * <p>All mutations go through {@link ThemeEditor}; nothing is persisted until the
 * user presses apply, and the panel is rebuilt from the draft after every change so
 * the displayed values can never drift from what would be saved.
 */
public final class ThemeDialog extends DialogFragment implements ThemePreviewView.Callbacks {

    private ThemeEditor editor;
    private boolean dark;
    private LinearLayout panel;
    private TextView status;

    public static void show(Fragment fragment) {
        new ThemeDialog().show(fragment.getChildFragmentManager(), ThemeDialog.class.getSimpleName());
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        editor = ThemeEditor.load();
        dark = isDarkNow();
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(4);
        root.setPadding(pad, pad, pad, pad);

        root.addView(buildModeRow());
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

        return new androidx.appcompat.app.AlertDialog.Builder(requireContext())
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
        Button reset = ((androidx.appcompat.app.AlertDialog) dialog).getButton(Dialog.BUTTON_NEUTRAL);
        reset.setOnClickListener(view -> {
            ThemeProfileStore.ApplyResult result = editor.reset();
            if (!result.success()) {
                setStatus(result.error());
                return;
            }
            editor = ThemeEditor.load();
            rebuildPanel();
            setStatus(getString(R.string.theme_editor_reset_done));
            if (getParentFragment() instanceof AppearanceDialog appearance) appearance.onThemeProfileApplied();
        });
    }

    private View buildModeRow() {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(8), dp(6), dp(8), dp(2));

        TextView label = new TextView(requireContext());
        label.setText(R.string.theme_editor_edit_mode);
        row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button toggle = new Button(requireContext());
        toggle.setAllCaps(false);
        toggle.setText(dark ? R.string.theme_editor_dark : R.string.theme_editor_light);
        toggle.setOnClickListener(view -> {
            dark = !dark;
            toggle.setText(dark ? R.string.theme_editor_dark : R.string.theme_editor_light);
            rebuildPanel();
        });
        row.addView(toggle);
        return row;
    }

    private View buildPresetRow() {
        HorizontalScrollView scroll = new HorizontalScrollView(requireContext());
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(8), dp(2), dp(8), dp(2));
        String[][] presets = {
                {getString(R.string.theme_editor_preset_default), ThemeProfile.SEED_NONE, null},
                {getString(R.string.theme_editor_preset_wallpaper), ThemeProfile.SEED_WALLPAPER, null},
                {getString(R.string.theme_editor_preset_blue), ThemeProfile.SEED_CUSTOM, "#0B57D0"},
                {getString(R.string.theme_editor_preset_teal), ThemeProfile.SEED_CUSTOM, "#00897B"},
                {getString(R.string.theme_editor_preset_green), ThemeProfile.SEED_CUSTOM, "#146C2E"},
                {getString(R.string.theme_editor_preset_orange), ThemeProfile.SEED_CUSTOM, "#FB8C00"},
                {getString(R.string.theme_editor_preset_red), ThemeProfile.SEED_CUSTOM, "#B3261E"},
                {getString(R.string.theme_editor_preset_purple), ThemeProfile.SEED_CUSTOM, "#8E24AA"},
        };
        for (String[] preset : presets) {
            Button button = new Button(requireContext());
            button.setAllCaps(false);
            button.setText(preset[0]);
            button.setOnClickListener(view -> {
                ThemeEditor.Result result = editor.setSeed(preset[1], preset[2]);
                if (!result.success()) setStatus(result.error());
                else rebuildPanel();
            });
            row.addView(button);
        }
        scroll.addView(row);
        return scroll;
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
