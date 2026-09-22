package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.os.Bundle;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.cache.CacheCenter;
import com.fongmi.android.tv.cache.CacheFormat;
import com.fongmi.android.tv.cache.CacheMeasurement;
import com.fongmi.android.tv.cache.CacheModuleId;
import com.fongmi.android.tv.cache.CacheSnapshot;
import com.fongmi.android.tv.databinding.DialogCacheManagementBinding;
import com.fongmi.android.tv.utils.FileUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CacheManagementDialog extends BaseAlertDialog {

    private static final CacheModuleId[] MODULE_ORDER = CacheModuleId.values();
    private DialogCacheManagementBinding binding;
    private boolean loading;

    public static void show(Fragment fragment) {
        new CacheManagementDialog().show(fragment.getChildFragmentManager(), null);
    }

    public static void show(FragmentActivity activity) {
        new CacheManagementDialog().show(activity.getSupportFragmentManager(), null);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = getBuilder().setView(getBinding().getRoot()).create();
        initView();
        initEvent();
        return dialog;
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogCacheManagementBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setTitle(R.string.cache_management_title);
    }

    protected void initView() {
        renderModules(null);
        refresh(false);
    }

    protected void initEvent() {
        binding.refresh.setOnClickListener(view -> refresh(true));
        binding.close.setOnClickListener(view -> dismiss());
    }

    private void refresh(boolean force) {
        if (loading) return;
        loading = true;
        binding.status.setText(R.string.cache_management_scanning);
        CacheCenter.get().requestSnapshot(force, this::render);
    }

    private void render(CacheSnapshot snapshot) {
        loading = false;
        if (binding == null || !isAdded()) return;
        long quota = snapshot.systemQuotaBytes();
        String total = FileUtil.byteCountToDisplaySize(snapshot.totalBytes());
        binding.summary.setText(quota > 0
                ? getString(R.string.cache_management_summary_with_quota, total, FileUtil.byteCountToDisplaySize(quota))
                : getString(R.string.cache_management_summary, total));
        binding.status.setText(snapshot.warnings().isEmpty()
                ? getString(R.string.cache_management_scanned_at, new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()))
                : getString(R.string.cache_management_partial, snapshot.warnings().size()));
        renderModules(snapshot);
    }

    private void renderModules(@Nullable CacheSnapshot snapshot) {
        binding.modules.removeAllViews();
        long totalBytes = snapshot == null ? 0 : snapshot.totalBytes();
        java.util.ArrayList<CacheMeasurement> ordered = snapshot == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(snapshot.modules());
        ordered.sort((left, right) -> {
            int byBytes = Long.compare(right.bytes(), left.bytes());
            if (byBytes != 0) return byBytes;
            return Integer.compare(left.id().ordinal(), right.id().ordinal());
        });
        if (snapshot == null) for (CacheModuleId id : MODULE_ORDER) addRow(id, null, 0);
        else for (CacheMeasurement measurement : ordered) addRow(measurement.id(), measurement, totalBytes);
    }

    private void addRow(CacheModuleId id, @Nullable CacheMeasurement measurement, long totalBytes) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 10, 0, 10);
        String title = getModuleName(id);
        if (measurement != null) title += "  " + CacheFormat.percent(measurement.bytes(), totalBytes);
        row.addView(text(title, 16, 0xFF202124));
        String detail;
        if (measurement == null) detail = getString(R.string.cache_management_scanning);
        else detail = getString(R.string.cache_management_module_detail,
                FileUtil.byteCountToDisplaySize(measurement.bytes()),
                measurement.fileCount(),
                formatTime(measurement.newestModifiedMs()));
        row.addView(text(detail, 13, 0xFF5F6368));
        binding.modules.addView(row);
    }

    private android.widget.TextView text(CharSequence value, int size, int color) {
        android.widget.TextView view = new android.widget.TextView(requireContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private String getModuleName(CacheModuleId id) {
        return getString(switch (id) {
            case EXO -> R.string.cache_module_exo;
            case MPV_HLS -> R.string.cache_module_mpv_hls;
            case MPV_DEMUXER -> R.string.cache_module_mpv_demuxer;
            case MPV_RUNTIME -> R.string.cache_module_mpv_runtime;
            case LYRICS -> R.string.cache_module_lyrics;
            case KARAOKE -> R.string.cache_module_karaoke;
            case WEBHOME_EXT -> R.string.cache_module_webhome_ext;
            case WEBHOME_RAW -> R.string.cache_module_webhome_raw;
            case EPG -> R.string.cache_module_epg;
            case GLIDE -> R.string.cache_module_glide;
            case PLUGIN_SCRIPTS -> R.string.cache_module_plugin_scripts;
            case TEMP_FILES -> R.string.cache_module_temp_files;
            case LEGACY_FILES -> R.string.cache_module_legacy_files;
        });
    }

    private String formatTime(long value) {
        if (value <= 0) return getString(R.string.none);
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(value));
    }

}
