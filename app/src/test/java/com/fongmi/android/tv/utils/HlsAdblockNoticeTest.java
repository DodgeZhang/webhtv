package com.fongmi.android.tv.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HlsAdblockNoticeTest {

    @Test
    public void debouncesRepeatedPlaylistRefreshesWithinWindow() {
        String url = "https://video.example.com/live/notice-test.m3u8";

        assertTrue(HlsAdblockNotice.shouldNotify(url, 100_000L));
        assertFalse(HlsAdblockNotice.shouldNotify(url, 129_999L));
        assertTrue(HlsAdblockNotice.shouldNotify(url, 130_000L));
    }

    @Test
    public void tracksDifferentPlaylistsIndependently() {
        long now = 200_000L;

        assertTrue(HlsAdblockNotice.shouldNotify("https://a.example.com/live.m3u8", now));
        assertTrue(HlsAdblockNotice.shouldNotify("https://b.example.com/live.m3u8", now));
    }

    @Test
    public void noticeReportsSegmentCountAndTotalAdDuration() {
        String message = HlsAdblockNotice.message(3, 27.5);

        assertEquals("已跳过 3 个广告片段，总广告时长 27.5 秒", message);
    }

    @Test
    public void noticeKeepsSegmentCountWhenDurationIsUnknown() {
        assertEquals("已跳过 2 个广告片段", HlsAdblockNotice.message(2, 0));
        assertEquals("已跳过 2 个广告片段", HlsAdblockNotice.message(2, Double.NaN));
        assertEquals("已跳过 2 个广告片段", HlsAdblockNotice.message(2, -1));
    }

    @Test
    public void noticeSwitchesToMinutesForLongAdBreaks() {
        assertEquals("已跳过 30 个广告片段，总广告时长 1 分钟", HlsAdblockNotice.message(30, 60));
        assertEquals("已跳过 45 个广告片段，总广告时长 2 分 5 秒", HlsAdblockNotice.message(45, 125));
    }

    @Test
    public void everyHlsAdblockChannelUsesTheSharedDurationNotice() throws Exception {
        java.nio.file.Path root = java.nio.file.Paths.get("src", "main", "java");
        java.util.List<java.nio.file.Path> channels = java.util.List.of(
                root.resolve("com/fongmi/android/tv/server/process/M3u8.java"),
                root.resolve("com/fongmi/android/tv/player/exo/ExoHlsAdblockDataSource.java"),
                root.resolve("androidx/media3/mpvplayer/MpvHlsProxy.java"));
        for (java.nio.file.Path channel : channels) {
            String source = new String(java.nio.file.Files.readAllBytes(channel),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertTrue("channel must route through the shared notice: " + channel,
                    source.contains("HlsAdblockNotice.message("));
            assertFalse("channel must not keep the duration-less inline notice: " + channel,
                    source.contains("个广告片段（"));
        }
    }
}
