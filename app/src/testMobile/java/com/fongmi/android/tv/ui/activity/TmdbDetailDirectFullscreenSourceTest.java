package com.fongmi.android.tv.ui.activity;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TmdbDetailDirectFullscreenSourceTest {

    @Test
    public void detailDirectPlayUsesCurrentPageFullscreenPlayer() throws Exception {
        String source = source();
        String onPlay = method(source, "private void onPlay()");
        String fullscreen = method(source, "private void playDetailFullscreen()");
        String reveal = method(source, "private void revealDetailPlayerFullscreen()");

        assertTrue("play action must delegate to the active detail-mode controller",
                onPlay.contains("modeController.play();"));
        assertFalse("detail direct play must not switch to VideoActivity",
                onPlay.contains("playDefaultPlayback();")
                        || onPlay.contains("VideoActivity.startDirectTmdb("));
        assertTrue("detail direct play must prepare current-page playback and reveal fullscreen",
                fullscreen.contains("detailPlayerFullscreenPending = !current;")
                        && fullscreen.contains("if (current) revealDetailPlayerFullscreen();")
                        && fullscreen.contains("else playInline();"));
        assertTrue("the current-page player must enter fullscreen when playback is ready",
                reveal.contains("if (!inlineFullscreen) enterInlineFullscreen();"));
    }

    private static String source() throws Exception {
        Path path = Path.of("src", "main", "java", "com", "fongmi", "android", "tv", "ui", "activity", "TmdbDetailActivity.java");
        if (!Files.isRegularFile(path)) path = Path.of("app").resolve(path);
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        int end = start < 0 ? -1 : source.indexOf("\n    private void ", start + signature.length());
        assertTrue("missing method: " + signature, start >= 0 && end > start);
        return source.substring(start, end);
    }
}
