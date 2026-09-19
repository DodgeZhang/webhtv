package com.fongmi.android.tv.ui.activity;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.TmdbItem;
import com.fongmi.android.tv.databinding.ActivityFollowingBinding;
import com.fongmi.android.tv.following.Following;
import com.fongmi.android.tv.following.FollowingNotifier;
import com.fongmi.android.tv.following.FollowingScheduler;
import com.fongmi.android.tv.following.FollowingSettings;
import com.fongmi.android.tv.following.FollowingSource;
import com.fongmi.android.tv.following.FollowingStore;
import com.fongmi.android.tv.following.FollowingUpdateCoordinator;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.adapter.FollowingAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public class FollowingActivity extends AppCompatActivity implements FollowingAdapter.Listener {

    public static final String EXTRA_IDENTITY_KEY = "following_identity_key";
    private ActivityFollowingBinding binding;
    private FollowingAdapter adapter;
    private String focusIdentity;
    private String pendingNotifyIdentity;
    private final ActivityResultLauncher<String> notificationPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (pendingNotifyIdentity == null) return;
                if (granted) {
                    FollowingSettings.setNotificationsEnabled(true);
                    FollowingStore.setNotifyEnabled(pendingNotifyIdentity, true);
                }
                pendingNotifyIdentity = null;
                load();
            });

    public static Intent intent(Context context, String identityKey) {
        Intent intent = new Intent(context, FollowingActivity.class);
        if (!TextUtils.isEmpty(identityKey)) intent.putExtra(EXTRA_IDENTITY_KEY, identityKey);
        return intent;
    }

    public static void start(Activity activity, String identityKey) {
        activity.startActivity(intent(activity, identityKey));
    }

    public static void start(Context context) {
        context.startActivity(intent(context, ""));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setTheme(R.style.Theme_App);
        super.onCreate(savedInstanceState);
        if (!FollowingSettings.isEnabled()) {
            Notify.show(R.string.following_enabled_hint);
            finish();
            return;
        }
        binding = ActivityFollowingBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        focusIdentity = getIntent().getStringExtra(EXTRA_IDENTITY_KEY);
        FollowingNotifier.createChannel();
        FollowingScheduler.ensurePeriodic(this);
        initView();
        FollowingScheduler.enqueueDueNow(this);
    }

    private void initView() {
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        binding.toolbar.setNavigationOnClickListener(view -> finish());
        binding.recycler.setLayoutManager(new LinearLayoutManager(this));
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 8));
        binding.recycler.setAdapter(adapter = new FollowingAdapter(this));
        binding.check.setOnClickListener(view -> checkAll());
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (binding != null) load();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Util.hideSystemUI(this);
    }

    private void load() {
        binding.loading.setVisibility(View.VISIBLE);
        Task.execute(() -> {
            List<Following> following = FollowingStore.list();
            List<FollowingAdapter.Row> rows = new ArrayList<>();
            for (Following item : following) rows.add(new FollowingAdapter.Row(item, FollowingStore.preferredSource(item.identityKey)));
            int unread = FollowingStore.unreadCount();
            App.post(() -> render(rows, unread));
        });
    }

    private void render(List<FollowingAdapter.Row> rows, int unread) {
        if (isFinishing() || binding == null) return;
        binding.loading.setVisibility(View.GONE);
        binding.empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        binding.recycler.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        binding.summary.setText(getString(R.string.following_summary, rows.size(), unread));
        adapter.setItems(rows);
        int index = TextUtils.isEmpty(focusIdentity) ? -1 : adapter.indexOf(focusIdentity);
        if (index >= 0) {
            binding.recycler.post(() -> {
                binding.recycler.scrollToPosition(index);
                focusIdentity = null;
            });
        }
    }

    private void checkAll() {
        List<Following> items = FollowingStore.list();
        if (items.isEmpty()) return;
        binding.check.setEnabled(false);
        Notify.show(R.string.following_checking);
        Task.execute(() -> {
            FollowingUpdateCoordinator coordinator = new FollowingUpdateCoordinator();
            int limit = Math.min(5, items.size());
            boolean success = false;
            for (int i = 0; i < limit; i++) success |= coordinator.checkNow(items.get(i).identityKey, true);
            boolean finalSuccess = success;
            App.post(() -> {
                if (binding == null) return;
                binding.check.setEnabled(true);
                Notify.show(finalSuccess ? R.string.following_check_done : R.string.following_check_failed);
                load();
            });
        });
    }

    @Override
    public void onContinue(Following item, FollowingSource source) {
        FollowingStore.reconcile(item);
        History history = FollowingStore.historyFor(item, source);
        if (history != null) {
            TmdbDetailActivity.startFromHistory(this, history);
            return;
        }
        FollowingSource target = preferred(item, source);
        if (target == null || TextUtils.isEmpty(target.siteKey) || TextUtils.isEmpty(target.vodId)) {
            Notify.show(R.string.following_check_failed);
            return;
        }
        TmdbItem tmdb = item.tmdbId > 0 ? new TmdbItem(item.tmdbId, item.mediaType, item.vodName,
                "", "", item.vodPic, "", "", 0.0) : null;
        TmdbDetailActivity.start(this, target.siteKey, target.vodId, item.vodName, item.vodPic, "", tmdb, Setting.getDetailOpenMode());
    }

    @Override
    public void onCheck(Following item) {
        binding.loading.setVisibility(View.VISIBLE);
        Task.execute(() -> {
            boolean success = new FollowingUpdateCoordinator().checkNow(item.identityKey, true);
            App.post(() -> {
                if (binding == null) return;
                Notify.show(success ? R.string.following_check_done : R.string.following_check_failed);
                load();
            });
        });
    }

    @Override
    public void onRead(Following item) {
        FollowingStore.markRead(item.identityKey);
        load();
    }

    @Override
    public void onToggleNotify(Following item) {
        boolean enabled = !item.notifyEnabled;
        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingNotifyIdentity = item.identityKey;
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        if (enabled) FollowingSettings.setNotificationsEnabled(true);
        FollowingStore.setNotifyEnabled(item.identityKey, enabled);
        load();
    }

    @Override
    public void onChangeSource(Following item) {
        try {
            Class<?> type = Class.forName("com.fongmi.android.tv.ui.activity.SearchActivity");
            type.getMethod("start", Activity.class, String.class).invoke(null, this, item.vodName);
        } catch (Throwable error) {
            Notify.show(error.getMessage());
        }
    }

    @Override
    public void onDelete(Following item) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.following_delete_title)
                .setMessage(R.string.following_delete_message)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.following_cancel, (dialog, which) -> {
                    FollowingScheduler.cancelNext(this, item.identityKey);
                    FollowingStore.delete(item.identityKey);
                    load();
                })
                .show();
    }

    private FollowingSource preferred(Following item, FollowingSource source) {
        if (source != null) return source;
        FollowingSource target = new FollowingSource();
        target.followingKey = item.identityKey;
        target.cid = item.cid;
        target.siteKey = item.siteKey;
        target.vodId = item.vodId;
        target.vodName = item.vodName;
        target.vodPic = item.vodPic;
        target.preferred = true;
        return target;
    }
}
