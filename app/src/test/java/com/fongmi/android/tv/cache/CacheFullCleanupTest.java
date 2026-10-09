package com.fongmi.android.tv.cache;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Locks the two user-reported defects fixed for this branch.
 *
 * <p>1. The temporary-file row reported freshly written {@code webhtv-*.zip} / {@code update.apk}
 * leftovers and then released nothing, because the row's own button applied the 24-hour retention
 * meant for background runs. An explicit request must ignore that window while still protecting a
 * transfer that is running right now.</p>
 *
 * <p>2. The settings row's long-press shortcut has to clear everything the way the pre-split
 * one-key clear did, which needs a residual sweep over whatever no module claims.</p>
 */
public class CacheFullCleanupTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    /** The reported defect: a fresh archive is exactly what the user asked to remove. */
    @Test
    public void explicitRequestDeletesFreshTemporaryFiles() throws Exception {
        File cache = cacheDir("temp-explicit");
        File archive = write(cache, "webhtv-sync-4242.zip", 4096);
        File apk = write(cache, "update.apk", 2048);
        archive.setLastModified(NOW - 60_000L);
        apk.setLastModified(NOW - 60_000L);

        CacheCleanupManager.Outcome outcome =
                CacheCleanupManager.clearTemporaryFiles(cache, 0L, 0L, false, false, NOW);

        assertTrue(outcome.success());
        assertFalse("a fresh sync archive must not survive the row's own button", archive.exists());
        assertFalse("a fresh update download must not survive the row's own button", apk.exists());
    }

    /** The same files stay for a background or tiered run: only an explicit request is immediate. */
    @Test
    public void backgroundRetentionStillKeepsFreshTemporaryFiles() throws Exception {
        File cache = cacheDir("temp-retained");
        File archive = write(cache, "webhtv-sync-4242.zip", 4096);
        archive.setLastModified(NOW - 60_000L);

        CacheCleanupManager.Outcome outcome =
                CacheCleanupManager.clearTemporaryFiles(cache, DAY_MS, 0L, false, false, NOW);

        assertTrue(outcome.success());
        assertTrue("an age-windowed run must keep a one-minute-old archive", archive.exists());
    }

    /** A transfer that is running right now survives even the immediate request. */
    @Test
    public void runningTransferSurvivesTheExplicitRequest() throws Exception {
        File cache = cacheDir("temp-in-use");
        File apk = write(cache, "update.apk", 2048);
        File pushed = write(cache, "pushed-url-9.apk", 1024);
        apk.setLastModified(NOW - 60_000L);
        pushed.setLastModified(NOW - 60_000L);

        CacheCleanupManager.Outcome outcome =
                CacheCleanupManager.clearTemporaryFiles(cache, 0L, 0L, true, true, NOW);

        assertTrue(outcome.success());
        assertTrue("the running updater keeps its download", apk.exists());
        assertTrue("the running push keeps its archive", pushed.exists());
    }

    /** The sweep removes what no module claims, including the scripts nobody is loading. */
    @Test
    public void sweepRemovesLeftoversAndIdlePluginScripts() throws Exception {
        File cache = cacheDir("sweep-leftovers");
        File leftover = write(cache, "unknown-owner.bin", 512);
        File staleTree = new File(cache, "stale-dir");
        write(staleTree, "deep/data.bin", 128);
        File idleScript = write(new File(cache, "js"), "idle-site.js", 256);
        File activeScript = write(new File(cache, "js"), "active-site.js", 256);

        ArrayList<String> warnings = new ArrayList<>();
        CacheCleanupManager.sweepResidual(cache, List.of(activeScript), false, false, warnings);

        assertFalse(leftover.exists());
        assertFalse("a tree no module owns is a leftover", staleTree.exists());
        assertFalse(idleScript.exists());
        assertTrue("the script of a running loader must survive", activeScript.exists());
        assertTrue("nothing in this scenario is a failure: " + warnings, warnings.isEmpty());
    }

    /**
     * The two carve-outs the full clean documents: a transfer that is running right now, and the
     * trees the caller preserved because a playback cache has to wait for playback to stop.
     */
    @Test
    public void sweepKeepsPreservedTreesTransfersAndRecoveryFiles() throws Exception {
        File cache = cacheDir("sweep-carveouts");
        File playback = new File(cache, "exo");
        write(playback, "segment.bin", 4096);
        File recovery = write(cache, "mpv-playback-recovery.state", 64);
        File apk = write(cache, "update.apk", 2048);
        File leftover = write(cache, "unknown-owner.bin", 512);

        ArrayList<String> warnings = new ArrayList<>();
        CacheCleanupManager.sweepResidual(cache, List.of(playback), true, false, warnings);

        assertTrue("a deferred playback cache keeps its tree", new File(playback, "segment.bin").exists());
        assertTrue("the mpv recovery file is never swept", recovery.exists());
        assertTrue("the running updater keeps its download", apk.exists());
        assertFalse(leftover.exists());
        assertFalse("the skipped files must be reported: " + warnings, warnings.isEmpty());
    }

    private static File write(File dir, String name, int size) throws Exception {
        File file = new File(dir, name);
        File parent = file.getParentFile();
        assertTrue("could not create " + parent, parent != null && (parent.isDirectory() || parent.mkdirs()));
        Files.write(file.toPath(), new byte[size]);
        return file;
    }

    private static File cacheDir(String prefix) throws Exception {
        Path base = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        return Files.createTempDirectory(base, prefix).toFile();
    }
}
