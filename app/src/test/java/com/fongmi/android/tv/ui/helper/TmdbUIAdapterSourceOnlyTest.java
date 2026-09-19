package com.fongmi.android.tv.ui.helper;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TmdbUIAdapterSourceOnlyTest {

    @Test
    public void loadSourceIsLocalAndSeedsSeasonEpisodeCache() throws Exception {
        String source = readAdapter();
        int start = source.indexOf("public void loadSource(TmdbBundle bundle");
        int end = source.indexOf("private static String seasonEpisodeKey", start);
        String method = source.substring(start, end);

        assertTrue(method.contains("seasonEpisodeCache.put(seasonEpisodeKey(tmdbItem, entry.getKey()), List.copyOf(entry.getValue()));"));
        assertTrue(method.contains("seasonResolution = TmdbSeasonResolver.resolve("));
        assertTrue(method.contains("episodeMetadataLoaded = true;"));
        assertTrue(method.contains("requestSeasonNumber < 0 && payload != null && payload.getSeasonNumber() >= 0"));
        assertFalse(method.contains("tmdbService."));
        assertFalse(method.contains("Setting.putTmdb"));
        assertFalse(method.contains("saveMatch("));
    }

    @Test
    public void sourceOnlyBlocksNetworkPagesAndReadsEmbeddedVideos() throws Exception {
        String source = readAdapter();
        String related = method(source, "public void loadRelatedVideosAsync", "public boolean hasMoreRecommendations");
        String recommendations = method(source, "public void loadMoreRecommendations", "public void loadMorePersonalTmdbRecommendations");
        String personal = method(source, "private void loadMorePersonalRecommendations", "private void applyRelatedRatingEnrichment");
        String refresh = method(source, "public void refreshPersonalRecommendations", "private void loadMorePersonalRecommendations");
        String ai = method(source, "private void loadPersonalAiRecommendationsAsync", "private void applyPersonalAiRatingEnrichment");

        assertTrue(related.contains("if (sourceOnly) {"));
        assertTrue(related.contains("TmdbSourceAdapter.videos("));
        int localStart = related.indexOf("if (sourceOnly) {");
        int readyCheck = related.indexOf("if (!isReady())", localStart);
        assertTrue(localStart >= 0 && readyCheck > localStart);
        assertFalse(related.substring(localStart, readyCheck).contains("tmdbService."));
        assertTrue(recommendations.contains("if (sourceOnly) {"));
        assertTrue(personal.contains("if (sourceOnly) {"));
        assertTrue(refresh.contains("if (sourceOnly) {"));
        assertTrue(ai.contains("if (sourceOnly) return;"));
    }

    private static String readAdapter() throws Exception {
        return Files.readString(root().resolve(Path.of("app", "src", "main", "java", "com", "fongmi", "android", "tv", "ui", "helper", "TmdbUIAdapter.java")), StandardCharsets.UTF_8);
    }

    private static String method(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue("missing method " + startMarker, start >= 0 && end > start);
        return source.substring(start, end);
    }

    private static Path root() {
        return Files.exists(Path.of("app", "src", "main")) ? Path.of(".") : Path.of("..");
    }
}
