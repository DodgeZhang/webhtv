package com.fongmi.android.tv.ui.activity;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TmdbDetailDirectPlayTransitionSourceTest {

    private static final Path SOURCE = Paths.get("src/main/java/com/fongmi/android/tv/ui/activity/TmdbDetailActivity.java");

    @Test
    public void firstDirectPlayWaitsForFirstFrameBeforeCoveringDetail() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        String playMethod = methodBody(source, "private void playDetailFullscreen()");

        assertTrue(source.contains("private boolean detailPlayerFullscreenPending;"));
        assertTrue(playMethod.contains("detailPlayerFullscreenPending = !current;"));
        assertTrue(playMethod.contains("if (current) revealDetailPlayerFullscreen();"));
        assertTrue(playMethod.contains("else playInline();"));
        assertFalse(playMethod.contains("enterInlineFullscreen();"));
        assertTrue(source.contains("protected void onFirstFrameRendered()"));
        assertTrue(source.contains("revealDetailPlayerFullscreen();"));
    }

    @Test
    public void closingDirectPlayClearsPendingTransitionState() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        String closeMethod = methodBody(source, "private void closeDetailFullscreenPlayer()");

        assertTrue(closeMethod.contains("detailPlayerFullscreenPending = false;"));
        assertTrue(closeMethod.contains("inlinePlaybackGeneration++;"));
        assertTrue(closeMethod.contains("currentInlineResult = null;"));
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) return "";
        int brace = source.indexOf('{', start);
        int depth = 0;
        for (int i = brace; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return source.substring(start, i + 1);
        }
        return "";
    }
}
