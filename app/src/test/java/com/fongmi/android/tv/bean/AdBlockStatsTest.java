package com.fongmi.android.tv.bean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class AdBlockStatsTest {

    @Test
    public void incrementBlocksUpdatesAllDimensionsAtomically() {
        AdBlockStats stats = new AdBlockStats();

        stats.incrementBlocks("demo", "EXO", "rule-a", 3);
        stats.incrementBlocks("demo", "EXO", "hls.legacy-fallback", 2);

        assertEquals(5, stats.getTotalBlocked());
        assertEquals(5, stats.getSiteBlockedCount("demo"));
        assertEquals(3, stats.getRuleBlockedCount("rule-a"));
        assertEquals(2, stats.getRuleBlockedCount("hls.legacy-fallback"));
        assertEquals(5, stats.getPipelineBlockedCount("EXO"));
    }

    @Test
    public void nonPositiveCountsAreIgnoredAndResetClearsEveryDimension() {
        AdBlockStats stats = new AdBlockStats();

        stats.incrementBlocks("demo", "MPV", "rule-a", 4);
        stats.incrementBlocks("demo", "MPV", "rule-a", 0);
        stats.incrementBlocks("demo", "MPV", "rule-a", -2);
        stats.reset();

        assertEquals(0, stats.getTotalBlocked());
        assertEquals(0, stats.getSiteBlockedCount("demo"));
        assertEquals(0, stats.getRuleBlockedCount("rule-a"));
        assertEquals(0, stats.getPipelineBlockedCount("MPV"));
    }
    @Test
    public void recordBlockLogKeepsEverySegmentAndAllRequestedFields() {
        AdBlockStats stats = new AdBlockStats();

        stats.recordBlockLog(1000L, "采集源A", "线路一", "ads.example.com", "rule-a", 6.5);
        stats.recordBlockLog(2000L, "采集源A", "线路一", "ads.example.com", "rule-a", 4.0);

        List<AdBlockLog> logs = stats.getBlockLogs();
        assertEquals(2, logs.size());
        AdBlockLog latest = logs.get(0);
        assertEquals(2000L, latest.getBlockedAt());
        assertEquals("采集源A", latest.getSourceName());
        assertEquals("线路一", latest.getPipelineName());
        assertEquals("ads.example.com", latest.getAdDomain());
        assertEquals("rule-a", latest.getRuleId());
        assertEquals(4.0, latest.getSegmentDurationSeconds(), 0.001);
    }

    @Test
    public void blockLogsCanBeGroupedBySourceRuleAndPipeline() {
        AdBlockStats stats = new AdBlockStats();
        stats.recordBlockLog(1000L, "采集源A", "线路一", "a.example", "rule-a", 3.0);
        stats.recordBlockLog(2000L, "采集源A", "线路二", "b.example", "rule-b", 5.0);
        stats.recordBlockLog(3000L, "采集源B", "线路一", "a.example", "rule-a", 7.0);

        assertEquals(2, stats.getBlockLogsBySource("采集源A").size());
        assertEquals(2, stats.getBlockLogsByRule("rule-a").size());
        assertEquals(2, stats.getBlockLogsByPipeline("线路一").size());
        assertTrue(stats.getSourceNamesWithBlocks().contains("采集源A"));
        assertTrue(stats.getSourceNamesWithBlocks().contains("采集源B"));
    }

    @Test
    public void resetAlsoClearsDetailedBlockLogs() {
        AdBlockStats stats = new AdBlockStats();
        stats.recordBlockLog(1000L, "采集源A", "线路一", "a.example", "rule-a", 3.0);

        stats.reset();

        assertTrue(stats.getBlockLogs().isEmpty());
    }

    @Test
    public void recordBlockLogsUsesPerRuleCountsAndDistributesDuration() {
        AdBlockStats stats = new AdBlockStats();
        java.util.Map<String, Long> ruleCounts = new java.util.LinkedHashMap<>();
        ruleCounts.put("rule-a", 2L);
        ruleCounts.put("rule-b", 1L);

        stats.recordBlockLogs(1000L, "采集源A", "EXO", "ads.example.com", ruleCounts, 12.0);

        assertEquals(3, stats.getBlockLogs().size());
        assertEquals(2, stats.getBlockLogsByRule("rule-a").size());
        assertEquals(1, stats.getBlockLogsByRule("rule-b").size());
        assertEquals(4.0, stats.getBlockLogs().get(0).getSegmentDurationSeconds(), 0.001);
    }

    @Test
    public void detailedLogsKeepOnlyTheMostRecentEntries() {
        AdBlockStats stats = new AdBlockStats();
        for (int index = 0; index < 1005; index++) {
            stats.recordBlockLog(index, "采集源A", "EXO", "ads.example.com", "rule-a", 1.0);
        }

        assertEquals(1000, stats.getBlockLogs().size());
        assertEquals(1004L, stats.getBlockLogs().get(0).getBlockedAt());
        assertEquals(5L, stats.getBlockLogs().get(999).getBlockedAt());
    }

}
