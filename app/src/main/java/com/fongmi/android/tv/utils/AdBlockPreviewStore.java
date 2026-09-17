package com.fongmi.android.tv.utils;

import android.net.Uri;

import androidx.annotation.Nullable;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Short-lived in-memory storage for sensitive HLS preview manifests. */
public final class AdBlockPreviewStore {

    private static final int MAX_ENTRIES = 128;
    private static final long MAX_AGE_MS = 10 * 60 * 1000L;
    private static final Map<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

    private AdBlockPreviewStore() {
    }

    public static synchronized void register(String key, String manifest) {
        if (empty(key) || empty(manifest)) return;
        purge(System.currentTimeMillis());
        entries.put(key, new Entry(manifest, System.currentTimeMillis()));
        while (entries.size() > MAX_ENTRIES) entries.remove(entries.keySet().iterator().next());
    }

    public static synchronized boolean has(String key) {
        return get(key) != null;
    }

    @Nullable
    public static synchronized String get(String key) {
        if (empty(key)) return null;
        purge(System.currentTimeMillis());
        Entry entry = entries.get(key);
        return entry == null ? null : entry.manifest;
    }

    public static Uri uri(String key) {
        return Uri.parse("webhtv-ad-preview://" + Uri.encode(key) + "/index.m3u8");
    }

    static synchronized void clearForTest() {
        entries.clear();
    }

    private static void purge(long now) {
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().getValue().createdAt > MAX_AGE_MS) iterator.remove();
        }
    }

    private static boolean empty(String value) {
        return value == null || value.isBlank();
    }

    private record Entry(String manifest, long createdAt) {
    }
}
