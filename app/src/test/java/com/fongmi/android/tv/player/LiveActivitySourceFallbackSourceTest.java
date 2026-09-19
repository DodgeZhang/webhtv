package com.fongmi.android.tv.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

public class LiveActivitySourceFallbackSourceTest {

    @Test
    public void eachFetchReplacesPreviousBufferingTimeout() throws Exception {
        assertFetchTimeoutIsReplaced(Path.of("src/leanback/java/com/fongmi/android/tv/ui/activity/LiveActivity.java"));
        assertFetchTimeoutIsReplaced(Path.of("src/mobile/java/com/fongmi/android/tv/ui/activity/LiveActivity.java"));
    }

    @Test
    public void sessionGatePreventsConsecutiveSourceChangesOnRepeatedFailure() throws Exception {
        for (Path path : List.of(
                Path.of("src/leanback/java/com/fongmi/android/tv/ui/activity/LiveActivity.java"),
                Path.of("src/mobile/java/com/fongmi/android/tv/ui/activity/LiveActivity.java"))) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            assertTrue(path.toString(), source.contains("private boolean mFailedThisSession;"));
            assertTrue(path.toString(), source.contains("mFailedThisSession = true;"));
            assertTrue(path.toString(), source.contains("mFailedThisSession = false;"));
        }
    }

    @Test
    public void failedAttemptIsReleasedOnlyWhenReplacementStartsBuffering() throws Exception {
        for (Path path : List.of(
                Path.of("src/leanback/java/com/fongmi/android/tv/ui/activity/LiveActivity.java"),
                Path.of("src/mobile/java/com/fongmi/android/tv/ui/activity/LiveActivity.java"))) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            assertTrue(path.toString(), source.contains(
                    "case Player.STATE_BUFFERING:\n                mFailedThisSession = false;"));
            assertFalse(path.toString(), source.contains("if (mFailedThisSession) return;"));
            assertFalse(path.toString(), source.contains(
                    "player().stop();\n        mFailedThisSession = false;\n        resetAdapter();"));
            assertFalse(path.toString(), source.contains(
                    "mFailedThisSession = false;\n        clearPendingReload();"));
        }
    }

    private static void assertFetchTimeoutIsReplaced(Path path) throws Exception {
        String source = Files.readString(path, StandardCharsets.UTF_8);
        String expected = "App.removeCallbacks(mBufferingTimeout);\n"
                + "        App.post(mBufferingTimeout, LIVE_BUFFERING_TIMEOUT);";
        assertEquals(path.toString(), 2, occurrences(source, expected));
    }

    private static int occurrences(String source, String target) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(target, offset)) >= 0) {
            count++;
            offset += target.length();
        }
        return count;
    }
}
