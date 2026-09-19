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
        String source = Files.readString(root().resolve(Path.of("app", "src", "main", "java", "com", "fongmi", "android", "tv", "ui", "helper", "TmdbUIAdapter.java")), StandardCharsets.UTF_8);
        int start = source.indexOf("public void loadSource(TmdbBundle bundle");
        int end = source.indexOf("private static String seasonEpisodeKey", start);
        String method = source.substring(start, end);

        assertTrue(method.contains("seasonEpisodeCache.put(seasonEpisodeKey(tmdbItem, entry.getKey()), List.copyOf(entry.getValue()));"));
        assertTrue(method.contains("seasonResolution = TmdbSeasonResolver.resolve("));
        assertTrue(method.contains("episodeMetadataLoaded = true;"));
        assertFalse(method.contains("tmdbService."));
        assertFalse(method.contains("Setting.putTmdb"));
        assertFalse(method.contains("saveMatch("));
    }

    private static Path root() {
        return Files.exists(Path.of("app", "src", "main")) ? Path.of(".") : Path.of("..");
    }
}
