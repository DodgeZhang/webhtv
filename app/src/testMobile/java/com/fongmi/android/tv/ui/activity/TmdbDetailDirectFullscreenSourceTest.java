package com.fongmi.android.tv.ui.activity;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TmdbDetailDirectFullscreenSourceTest {

    @Test
    public void detailDirectPlayLaunchesVideoActivityInsteadOfInlineDetailPlayer() throws Exception {
        String source = source();
        String onPlay = method(source, "private void onPlay()");
        String defaultPlayback = method(source, "private void playDefaultPlayback()");

        assertTrue("detail-player mode must launch the standalone player directly",
                onPlay.contains("if (isPlayerMode())")
                        && onPlay.contains("playDefaultPlayback();")
                        && onPlay.indexOf("playDefaultPlayback();") < onPlay.indexOf("modeController.play();"));
        assertTrue("the direct route must stop before falling through to inline playback",
                onPlay.contains("playDefaultPlayback();\n            return;"));
        assertTrue("the direct route must open VideoActivity with resolved TMDB playback state",
                defaultPlayback.contains("VideoActivity.startDirectTmdb(this"));
        int playerBranchEnd = onPlay.indexOf("        if (enterInlineFullscreenIfCurrentInlinePlayback");
        assertFalse("detail direct play must not enter the embedded fullscreen implementation",
                onPlay.substring(0, playerBranchEnd).contains("enterInlineFullscreenIfCurrentInlinePlayback"));
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
