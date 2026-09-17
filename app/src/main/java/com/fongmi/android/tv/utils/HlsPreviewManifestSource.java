package com.fongmi.android.tv.utils;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Short-lived manifest source cache used while recording HLS removal details. */
public final class HlsPreviewManifestSource {

    private static final int MAX_ENTRIES = 32;
    private static final long MAX_AGE_MS = 10 * 60 * 1000L;
    private static final Map<String, Entry> sources = new LinkedHashMap<>(16, 0.75f, true);

    private HlsPreviewManifestSource() {
    }

    public static synchronized void publish(String url, String manifest) {
        if (empty(url) || empty(manifest)) return;
        purge(System.currentTimeMillis());
        sources.put(url, new Entry(manifest, System.currentTimeMillis()));
        while (sources.size() > MAX_ENTRIES) sources.remove(sources.keySet().iterator().next());
    }

    public static synchronized String current(String url) {
        if (empty(url)) return "";
        purge(System.currentTimeMillis());
        Entry entry = sources.get(url);
        return entry == null ? "" : entry.manifest;
    }

    private static void purge(long now) {
        Iterator<Map.Entry<String, Entry>> iterator = sources.entrySet().iterator();
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
