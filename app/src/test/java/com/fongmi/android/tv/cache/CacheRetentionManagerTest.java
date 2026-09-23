package com.fongmi.android.tv.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileSystemException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public class CacheRetentionManagerTest {

    @Test
    public void enforceLimitDeletesOldestFilesFirst() throws Exception {
        Path root = Files.createTempDirectory("cache-retention");
        try {
            File old = write(root, "old.bin", "1234567890", 1_000L);
            File fresh = write(root, "fresh.bin", "1234567890", 2_000L);

            boolean success = CacheRetentionManager.applyLimit(root.toFile(), 10,
                    0, () -> 3_000L, Set.of());

            assertTrue(success);
            assertFalse(old.exists());
            assertFalse(fresh.exists());
        } finally {
            delete(root.toFile());
        }
    }

    @Test
    public void retentionDeletesOnlyExpiredFiles() throws Exception {
        Path root = Files.createTempDirectory("cache-retention-ttl");
        try {
            File expired = write(root, "expired.bin", "123", 1_000L);
            File fresh = write(root, "fresh.bin", "123", 9_000L);

            AtomicLong now = new AtomicLong(10_000L);
            boolean success = CacheRetentionManager.applyLimit(root.toFile(), 0,
                    5_000L, now::get, Set.of());

            assertTrue(success);
            assertFalse(expired.exists());
            assertTrue(fresh.exists());
        } finally {
            delete(root.toFile());
        }
    }

    @Test
    public void excludedNamesAreNeverDeleted() throws Exception {
        Path root = Files.createTempDirectory("cache-retention-exclude");
        try {
            File keep = write(root, "keep.lock", "123", 1L);
            CacheRetentionManager.applyLimit(root.toFile(), 0, 1L,
                    () -> 10_000L, Set.of("keep.lock"));
            assertTrue(keep.exists());
        } finally {
            delete(root.toFile());
        }
    }

    @Test
    public void symbolicLinksAreNotFollowedOrDeleted() throws Exception {
        Path outside = Files.createTempDirectory("cache-retention-outside");
        Path root = Files.createTempDirectory("cache-retention-link");
        File victim = write(outside, "victim.bin", "secret", 1L);
        try {
            try {
                Files.createSymbolicLink(root.resolve("link.bin"), victim.toPath());
            } catch (UnsupportedOperationException | FileSystemException error) {
                Assume.assumeTrue("symbolic links unavailable", false);
            }

            boolean success = CacheRetentionManager.applyLimit(root.toFile(), 0, 1L,
                    () -> 10_000L, Set.of());

            assertTrue(success);
            assertTrue(victim.exists());
        } finally {
            delete(root.toFile());
            delete(outside.toFile());
        }
    }

    private static File write(Path root, String name, String value, long modified) throws Exception {
        Path file = root.resolve(name);
        Files.writeString(file, value, StandardCharsets.UTF_8);
        assertTrue(file.toFile().setLastModified(modified));
        return file.toFile();
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
