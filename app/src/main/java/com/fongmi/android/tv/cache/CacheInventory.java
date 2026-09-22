package com.fongmi.android.tv.cache;

import android.content.Context;
import android.os.Build;
import android.os.storage.StorageManager;

import com.fongmi.android.tv.utils.FileUtil;
import com.github.catvod.utils.Path;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class CacheInventory {

    private static final long MODULE_TIMEOUT_NS = 2_000_000_000L;

    private final Context context;

    public CacheInventory(Context context) {
        this.context = context.getApplicationContext();
    }

    public CacheSnapshot scan() {
        long startNs = System.nanoTime();
        ArrayList<CacheMeasurement> measurements = new ArrayList<>();
        ArrayList<String> warnings = new ArrayList<>();
        long totalBytes = 0;
        for (CacheModule module : modules()) {
            CacheMeasurement measurement = measure(module, System.nanoTime() + MODULE_TIMEOUT_NS);
            measurements.add(measurement);
            totalBytes = saturatedAdd(totalBytes, measurement.bytes());
            warnings.addAll(measurement.warnings());
        }
        FileUtil.StorageSpace storage = FileUtil.getStorageSpace(Path.cache());
        return new CacheSnapshot(
                System.nanoTime() / 1_000_000L,
                totalBytes,
                cacheQuotaBytes(context),
                storage.availableBytes(),
                storage.totalBytes(),
                Collections.unmodifiableList(measurements),
                Collections.unmodifiableList(warnings)
        );
    }

    static CacheMeasurement measure(CacheModule module, long deadlineNs) {
        return measureRoots(module.id(), module.roots(), deadlineNs);
    }

    static CacheMeasurement measureRoots(CacheModuleId id, List<CacheRoot> roots) {
        return measureRoots(id, roots, Long.MAX_VALUE);
    }

    static CacheMeasurement measureRoots(CacheModuleId id, List<CacheRoot> roots, long deadlineNs) {
        ScanResult result = new ScanResult();
        for (CacheRoot root : roots) {
            if (root == null || root.root() == null) continue;
            scan(root.root(), root, result, deadlineNs, 0);
        }
        CacheAvailability availability = result.warnings.isEmpty()
                ? result.fileCount == 0 ? CacheAvailability.EMPTY : CacheAvailability.AVAILABLE
                : CacheAvailability.PARTIAL;
        return new CacheMeasurement(
                id,
                result.bytes,
                result.fileCount,
                result.oldestModifiedMs == Long.MAX_VALUE ? 0 : result.oldestModifiedMs,
                result.newestModifiedMs,
                availability,
                Collections.unmodifiableList(result.warnings)
        );
    }

    private static List<CacheModule> modules() {
        File cache = Path.cache();
        Set<String> managedRoots = Set.of(
                "exo", "mpv_hls", "mpv-demuxer-cache", "mpv_lut_shaders", "fontconfig",
                "lyrics", "karaoke_tracks", "webhome_ext", "webhome_raw", "epg",
                "image_manager_disk_cache", "js", "py", "jar", "restore-legacy"
        );
        return List.of(
                module(CacheModuleId.EXO, CacheGroup.PLAYBACK, List.of(CacheRoot.tree(new File(cache, "exo"))), CacheCleanability.DEFER_UNTIL_IDLE, CacheEvictionPolicy.LRU, false, true, true, true),
                module(CacheModuleId.MPV_HLS, CacheGroup.PLAYBACK, List.of(CacheRoot.tree(new File(cache, "mpv_hls"))), CacheCleanability.DEFER_UNTIL_IDLE, CacheEvictionPolicy.LRU_WITH_TTL, false, true, true, true),
                module(CacheModuleId.MPV_DEMUXER, CacheGroup.PLAYBACK, List.of(CacheRoot.tree(new File(cache, "mpv-demuxer-cache"))), CacheCleanability.DEFER_UNTIL_IDLE, CacheEvictionPolicy.OWNER_MANAGED, false, true, true, true),
                module(CacheModuleId.MPV_RUNTIME, CacheGroup.PLAYBACK, List.of(
                        CacheRoot.tree(new File(cache, "mpv_lut_shaders")),
                        CacheRoot.tree(new File(cache, "fontconfig"))
                ), CacheCleanability.DEFER_UNTIL_IDLE, CacheEvictionPolicy.TTL, false, true, true, true),
                module(CacheModuleId.LYRICS, CacheGroup.MEDIA, List.of(CacheRoot.tree(new File(cache, "lyrics"))), CacheCleanability.SAFE_NOW, CacheEvictionPolicy.LRU_WITH_TTL, true, true, false, false),
                module(CacheModuleId.KARAOKE, CacheGroup.MEDIA, List.of(CacheRoot.tree(new File(cache, "karaoke_tracks"))), CacheCleanability.SAFE_NOW, CacheEvictionPolicy.COUNT_THEN_LRU, true, true, false, false),
                module(CacheModuleId.WEBHOME_EXT, CacheGroup.NETWORK, List.of(CacheRoot.tree(new File(cache, "webhome_ext"))), CacheCleanability.SAFE_NOW, CacheEvictionPolicy.LRU_WITH_TTL, true, true, false, false),
                module(CacheModuleId.WEBHOME_RAW, CacheGroup.NETWORK, List.of(CacheRoot.tree(new File(cache, "webhome_raw"))), CacheCleanability.OWNER_MANAGED, CacheEvictionPolicy.LRU, false, true, true, false),
                module(CacheModuleId.EPG, CacheGroup.NETWORK, List.of(CacheRoot.tree(new File(cache, "epg"))), CacheCleanability.SAFE_NOW, CacheEvictionPolicy.TTL, true, true, false, false),
                module(CacheModuleId.GLIDE, CacheGroup.MEDIA, List.of(CacheRoot.tree(new File(cache, "image_manager_disk_cache"))), CacheCleanability.OWNER_MANAGED, CacheEvictionPolicy.OWNER_MANAGED, false, true, true, false),
                module(CacheModuleId.PLUGIN_SCRIPTS, CacheGroup.PLUGIN, List.of(
                        CacheRoot.tree(new File(cache, "js")),
                        CacheRoot.tree(new File(cache, "py")),
                        CacheRoot.tree(new File(cache, "jar"))
                ), CacheCleanability.DEFER_UNTIL_IDLE, CacheEvictionPolicy.LRU_WITH_TTL, false, true, true, false),
                module(CacheModuleId.TEMP_FILES, CacheGroup.TEMPORARY, List.of(CacheRoot.files(cache, Set.of(".apk", ".zip", ".tmp", ".log"), protectedNames())), CacheCleanability.SAFE_NOW, CacheEvictionPolicy.AGE_BASED, true, true, false, false),
                module(CacheModuleId.LEGACY_FILES, CacheGroup.LEGACY, List.of(CacheRoot.orphanTree(cache, union(managedRoots, protectedNames()), Set.of(".apk", ".zip", ".tmp", ".log"))), CacheCleanability.SAFE_NOW, CacheEvictionPolicy.AGE_BASED, true, true, false, false)
        );
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        java.util.HashSet<String> result = new java.util.HashSet<>(first);
        result.addAll(second);
        return Set.copyOf(result);
    }

    private static CacheModule module(CacheModuleId id, CacheGroup group, List<CacheRoot> roots,
                                      CacheCleanability cleanability, CacheEvictionPolicy policy,
                                      boolean automatic, boolean manual, boolean ownerIdle,
                                      boolean appIdle) {
        return new Module(id, group, roots, cleanability, policy,
                new CacheProtection(automatic, manual, ownerIdle, appIdle, 0, 0, ownerIdle));
    }

    private static Set<String> protectedNames() {
        return Set.of(
                "mpv-playback-recovery.lock",
                "mpv-playback-recovery.state",
                "mpv-playback-recovery.result"
        );
    }

    private static void scan(File file, CacheRoot root, ScanResult result, long deadlineNs, int depth) {
        if (file == null || System.nanoTime() > deadlineNs) {
            if (file != null && System.nanoTime() > deadlineNs) result.warning("scan timeout");
            return;
        }
        if (isSymbolicLink(file)) {
            result.warning("symbolic link skipped: " + file.getName());
            return;
        }
        if (root.excludeNames().contains(file.getName())) return;
        if (file.isFile()) {
            if (!root.recursive() && depth > 1) return;
            if (matchesSuffix(file.getName(), root.excludeSuffixes())) return;
            if (!matchesSuffix(file.getName(), root.includeSuffixes())) return;
            result.accept(file);
            return;
        }
        if (!file.isDirectory()) return;
        File[] children = file.listFiles();
        if (children == null) {
            result.warning("unreadable directory: " + file.getName());
            return;
        }
        if (!root.recursive() && depth > 0) return;
        for (File child : children) scan(child, root, result, deadlineNs, depth + 1);
    }

    private static boolean matchesSuffix(String name, Set<String> suffixes) {
        if (suffixes.isEmpty()) return true;
        for (String suffix : suffixes) if (name.endsWith(suffix)) return true;
        return false;
    }

    private static boolean isSymbolicLink(File file) {
        try {
            return !file.getCanonicalFile().equals(file.getAbsoluteFile());
        } catch (IOException e) {
            return true;
        }
    }

    private static long cacheQuotaBytes(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return 0;
        try {
            StorageManager manager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
            return manager == null ? 0 : Math.max(0, manager.getCacheQuotaBytes(manager.getUuidForPath(Path.cache())));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static long saturatedAdd(long first, long second) {
        return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }

    private record Module(CacheModuleId id, CacheGroup group, List<CacheRoot> roots,
                          CacheCleanability cleanability, CacheEvictionPolicy evictionPolicy,
                          CacheProtection protection) implements CacheModule {
    }

    private static final class ScanResult {
        private long bytes;
        private long fileCount;
        private long oldestModifiedMs = Long.MAX_VALUE;
        private long newestModifiedMs;
        private final ArrayList<String> warnings = new ArrayList<>();

        private void accept(File file) {
            bytes = saturatedAdd(bytes, Math.max(0, file.length()));
            fileCount = saturatedAdd(fileCount, 1);
            long modified = file.lastModified();
            if (modified > 0) {
                if (oldestModifiedMs == Long.MAX_VALUE || modified < oldestModifiedMs) oldestModifiedMs = modified;
                if (modified > newestModifiedMs) newestModifiedMs = modified;
            }
        }

        private void warning(String warning) {
            if (!warnings.contains(warning)) warnings.add(warning);
        }
    }
}
