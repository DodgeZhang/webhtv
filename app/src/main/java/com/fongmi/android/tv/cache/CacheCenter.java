package com.fongmi.android.tv.cache;

import android.content.Context;
import android.os.Build;
import android.os.storage.StorageManager;

import com.fongmi.android.tv.App;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class CacheCenter {

    private static final long SNAPSHOT_CACHE_MS = 3_000L;
    private static volatile CacheCenter instance;

    private final Context context;
    private final CacheInventory inventory;
    private final ExecutorService executor;
    private volatile CacheSnapshot snapshot;
    private volatile long snapshotAtMs;

    private CacheCenter(Context context) {
        this.context = context.getApplicationContext();
        this.inventory = new CacheInventory(context);
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cache-inventory");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static CacheCenter get() {
        CacheCenter current = instance;
        if (current != null) return current;
        synchronized (CacheCenter.class) {
            if (instance == null) instance = new CacheCenter(App.get());
            return instance;
        }
    }

    public void requestSnapshot(boolean force, Consumer<CacheSnapshot> callback) {
        long now = System.currentTimeMillis();
        CacheSnapshot current = snapshot;
        if (!force && current != null && now - snapshotAtMs < SNAPSHOT_CACHE_MS) {
            App.post(() -> callback.accept(current));
            return;
        }
        executor.execute(() -> {
            CacheSnapshot next = inventory.scan();
            snapshot = next;
            snapshotAtMs = System.currentTimeMillis();
            App.post(() -> callback.accept(next));
        });
    }

    public long systemQuotaBytes() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return 0;
        try {
            StorageManager manager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
            return manager == null ? 0 : Math.max(0, manager.getCacheQuotaBytes(manager.getUuidForPath(context.getCacheDir())));
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
