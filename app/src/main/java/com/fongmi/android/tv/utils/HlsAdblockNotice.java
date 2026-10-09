package com.fongmi.android.tv.utils;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Playback-session debounce for repeated live-playlist refreshes, plus the shared notice text. */
public final class HlsAdblockNotice {

    private static final long WINDOW_MS = 30_000L;
    private static final int MAX_ENTRIES = 64;
    private static final Map<String, Long> RECENT = new LinkedHashMap<>();

    private HlsAdblockNotice() {}

    public static synchronized boolean shouldNotify(String playlistUrl, long nowMs) {
        String key = playlistUrl == null ? "" : playlistUrl;
        Long previous = RECENT.get(key);
        if (previous != null && nowMs - previous < WINDOW_MS) return false;
        RECENT.put(key, nowMs);
        while (RECENT.size() > MAX_ENTRIES) RECENT.remove(RECENT.keySet().iterator().next());
        return true;
    }

    /**
     * Builds the single user-visible ad-block confirmation used by every HLS channel.
     *
     * <p>The notice always reports the number of removed ad segments and, whenever the removed
     * duration is known, the total ad time as well. The duration is included for both the
     * structured rule path and the legacy fallback path, because a user who only sees a segment
     * count cannot tell whether one 2-second bumper or a 3-minute ad break was removed.</p>
     */
    public static String message(int removedSegments, double removedDurationSec) {
        String count = "已跳过 " + Math.max(0, removedSegments) + " 个广告片段";
        if (!(removedDurationSec > 0)) return count;
        return count + "，总广告时长 " + durationText(removedDurationSec);
    }

    /** Formats a removed-ad duration as seconds below one minute and as minutes above it. */
    public static String durationText(double seconds) {
        double safe = Double.isFinite(seconds) ? Math.max(0d, seconds) : 0d;
        if (safe < 60d) return String.format(Locale.US, "%.1f 秒", safe);
        long total = Math.round(safe);
        long minutes = total / 60L;
        long remainder = total % 60L;
        return remainder == 0 ? minutes + " 分钟" : minutes + " 分 " + remainder + " 秒";
    }
}
