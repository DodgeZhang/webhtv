package com.fongmi.android.tv.cache;

import androidx.media3.mpvplayer.MpvHlsCacheCoordinator;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.loader.BaseLoader;
import com.fongmi.android.tv.api.parser.EpgParser;
import com.fongmi.android.tv.player.exo.MediaSourceFactory;
import com.fongmi.android.tv.player.karaoke.KaraokeTrackRepository;
import com.fongmi.android.tv.player.lyrics.LyricsRepository;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.web.WebHomeRawAdapter;
import com.fongmi.android.tv.web.ext.WebHomeExtensionRegistry;
import com.github.catvod.utils.Path;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class CacheCleanupManager {

    private static final long TEMP_RETENTION_MS = 24L * 60L * 60L * 1000L;
    private static final long TEMP_MINIMUM_AGE_MS = 60L * 60L * 1000L;
    private static final long LEGACY_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final Set<String> PROTECTED_NAMES = Set.of(
            "mpv-playback-recovery.lock",
            "mpv-playback-recovery.state",
            "mpv-playback-recovery.result"
    );
    private static final AtomicBoolean CANCELLED = new AtomicBoolean();
    private static final Object RUN_LOCK = new Object();
    private static volatile boolean running;

    private CacheCleanupManager() {
    }

    public static void execute(CacheCleanupPlan plan, Consumer<CacheCleanupResult> callback) {
        execute(plan, "manual", null, callback);
    }

    public static void execute(CacheCleanupPlan plan, String reason, Consumer<CacheCleanupResult> callback) {
        execute(plan, reason, null, callback);
    }

    public static void execute(CacheCleanupPlan plan, Consumer<CacheCleanupProgress> progress,
                               Consumer<CacheCleanupResult> callback) {
        execute(plan, "manual", progress, callback);
    }

    public static void execute(CacheCleanupPlan plan, String reason,
                               Consumer<CacheCleanupProgress> progress,
                               Consumer<CacheCleanupResult> callback) {
        if (plan == null || plan.modules().isEmpty()) {
            App.post(() -> callback.accept(new CacheCleanupResult(null, CacheCleanupStatus.NOT_ALLOWED,
                    0, 0, 0, 0, List.of("empty plan"))));
            return;
        }
        synchronized (RUN_LOCK) {
            if (running) {
                App.post(() -> callback.accept(new CacheCleanupResult(plan.modules().get(0),
                        CacheCleanupStatus.FAILED, 0, 0, 0, 0, List.of("cleanup already running"))));
                return;
            }
            running = true;
            CANCELLED.set(false);
        }
        new Thread(() -> run(plan, reason, progress, callback), "cache-cleanup").start();
    }

    public static boolean isRunning() {
        return running;
    }

    public static void cancel() {
        if (running) CANCELLED.set(true);
    }

    public static void applyConfiguredLimits() {
        File cache = App.get().getCacheDir();
        long retention = CachePolicyStore.getRetentionDays() * 24L * 60L * 60L * 1000L;
        long now = System.currentTimeMillis();
        CacheRetentionManager.applyLimit(new File(cache, "lyrics"),
                CachePolicyStore.getLimit(CacheModuleId.LYRICS), retention, () -> now, Set.of());
        CacheRetentionManager.applyLimit(new File(cache, "karaoke_tracks"),
                CachePolicyStore.getLimit(CacheModuleId.KARAOKE), retention, () -> now, Set.of());
        CacheRetentionManager.applyLimit(new File(cache, "webhome_ext"),
                CachePolicyStore.getLimit(CacheModuleId.WEBHOME_EXT), retention, () -> now, Set.of());
        CacheRetentionManager.applyLimit(new File(cache, "epg"),
                CachePolicyStore.getLimit(CacheModuleId.EPG), Math.min(retention, 6L * 60L * 60L * 1000L),
                () -> now, Set.of());
    }

    private static void run(CacheCleanupPlan plan, String reason,
                            Consumer<CacheCleanupProgress> progress,
                            Consumer<CacheCleanupResult> callback) {
        long startedAt = System.currentTimeMillis();
        ArrayList<CacheCleanupResult> results = new ArrayList<>();
        try {
            int total = plan.modules().size();
            for (int index = 0; index < total; index++) {
                CacheModuleId id = plan.modules().get(index);
                if (CANCELLED.get()) {
                    results.add(new CacheCleanupResult(id, CacheCleanupStatus.CANCELLED,
                            0, 0, 0, 0, List.of("cancelled")));
                    break;
                }
                if (progress != null) {
                    int completed = index;
                    App.post(() -> progress.accept(new CacheCleanupProgress(id, completed, total, 0, 0)));
                }
                results.add(cleanModule(id, plan.mode()));
            }
        } catch (Throwable error) {
            CacheModuleId id = plan.modules().get(0);
            results.add(new CacheCleanupResult(id, CacheCleanupStatus.FAILED,
                    0, 0, 0, 0, List.of(error.getClass().getSimpleName())));
        } finally {
            synchronized (RUN_LOCK) {
                running = false;
            }
        }
        long finishedAt = System.currentTimeMillis();
        CacheCleanupResult result = aggregate(results);
        CacheCleanupJournal.record(new CacheCleanupRecord(
                startedAt, Math.max(0, finishedAt - startedAt), reason == null ? "manual" : reason,
                plan.mode(), result.status(), result.bytesBefore(), result.bytesAfter(),
                result.deletedFiles(), result.skippedFiles(), result.warnings()));
        App.post(() -> callback.accept(result));
    }

    private static CacheCleanupResult cleanModule(CacheModuleId id, CacheCleanupMode mode) {
        CacheMeasurement before = measure(id);
        boolean playing = PlaybackService.isRunning();
        CacheCleanupStatus gate = CachePolicyEngine.directCleanupStatus(id, playing);
        if (gate != CacheCleanupStatus.COMPLETED) {
            return new CacheCleanupResult(id, gate, before.bytes(), before.bytes(), 0,
                    before.fileCount(), List.of(gate.name().toLowerCase()));
        }
        Outcome outcome;
        try {
            outcome = executeCleanup(id, mode);
        } catch (Throwable error) {
            return new CacheCleanupResult(id, CacheCleanupStatus.FAILED,
                    before.bytes(), before.bytes(), 0, before.fileCount(),
                    List.of(error.getClass().getSimpleName()));
        }
        CacheMeasurement after = measure(id);
        long deleted = Math.max(0, before.fileCount() - after.fileCount());
        long skipped = Math.max(0, after.fileCount());
        return new CacheCleanupResult(id,
                outcome.success ? CacheCleanupStatus.COMPLETED : CacheCleanupStatus.PARTIAL,
                before.bytes(), after.bytes(), deleted, skipped, outcome.warnings);
    }

    private static Outcome executeCleanup(CacheModuleId id, CacheCleanupMode mode) {
        File cache = App.get().getCacheDir();
        long limit = CachePolicyStore.getLimit(id);
        long retention = CachePolicyStore.getRetentionDays() * 24L * 60L * 60L * 1000L;
        return switch (id) {
            case EXO -> outcome(MediaSourceFactory.clearCacheIfIdle());
            case MPV_HLS -> outcome(MpvHlsCacheCoordinator.shared(Path.cache("mpv_hls")).clearIfIdle());
            case MPV_DEMUXER -> clearTree(new File(cache, "mpv-demuxer-cache"));
            case MPV_RUNTIME -> clearTrees(new File(cache, "mpv_lut_shaders"), new File(cache, "fontconfig"));
            case LYRICS -> mode == CacheCleanupMode.MODULE
                    ? outcome(LyricsRepository.clearCache() >= 0)
                    : outcome(CacheRetentionManager.applyLimit(new File(cache, "lyrics"), limit,
                    retention, System::currentTimeMillis, Set.of()));
            case KARAOKE -> mode == CacheCleanupMode.MODULE
                    ? outcome(KaraokeTrackRepository.clearCache())
                    : outcome(CacheRetentionManager.applyLimit(new File(cache, "karaoke_tracks"), limit,
                    retention, System::currentTimeMillis, Set.of()));
            case WEBHOME_EXT -> {
                WebHomeExtensionRegistry.get().clear();
                yield new Outcome(true, List.of());
            }
            case WEBHOME_RAW -> outcome(WebHomeRawAdapter.clearCache());
            case EPG -> mode == CacheCleanupMode.MODULE ? outcome(EpgParser.clearCache())
                    : outcome(CacheRetentionManager.applyLimit(new File(cache, "epg"), limit,
                    Math.min(retention, 6L * 60L * 60L * 1000L), System::currentTimeMillis, Set.of()));
            case GLIDE -> {
                Glide.get(App.get()).clearDiskCache();
                yield new Outcome(true, List.of());
            }
            case PLUGIN_SCRIPTS -> clearPluginCache(retention, Set.copyOf(BaseLoader.get().activePluginKeys()));
            case TEMP_FILES -> clearTemporaryFiles(TEMP_RETENTION_MS, limit);
            case LEGACY_FILES -> clearAgedTree(new File(cache, "restore-legacy"),
                    mode == CacheCleanupMode.MODULE ? 0 : LEGACY_RETENTION_MS);
        };
    }

    private static Outcome clearTemporaryFiles(long retentionMs, long limitBytes) {
        File cache = App.get().getCacheDir();
        File[] files = cache.listFiles(File::isFile);
        if (files == null) return new Outcome(false, List.of("cache root unreadable"));
        long now = System.currentTimeMillis();
        boolean success = true;
        ArrayList<String> warnings = new ArrayList<>();
        ArrayList<File> remaining = new ArrayList<>();
        for (File file : files) {
            String name = file.getName();
            if (!isTemporaryName(name)) continue;
            if (!isExpired(file, now, retentionMs)) {
                remaining.add(file);
                continue;
            }
            if (!file.delete()) {
                success = false;
                warnings.add("delete failed: " + name);
            }
        }
        success &= CacheRetentionManager.enforceFileLimit(cache, remaining, limitBytes,
                TEMP_MINIMUM_AGE_MS, now);
        return new Outcome(success, warnings);
    }

    private static Outcome clearPluginCache(long retentionMs, Set<String> activeKeys) {
        File cache = App.get().getCacheDir();
        boolean success = true;
        ArrayList<String> warnings = new ArrayList<>();
        success &= deleteExpiredPluginFiles(new File(cache, "jar"), ".jar", activeKeys, retentionMs, warnings);
        success &= deleteExpiredPluginFiles(new File(cache, "py"), ".py", activeKeys, retentionMs, warnings);
        success &= deleteExpiredPluginFiles(new File(cache, "js"), ".js", activeKeys, retentionMs, warnings);
        return new Outcome(success, warnings);
    }

    private static boolean deleteExpiredPluginFiles(File root, String suffix, Set<String> activeKeys,
                                                    long retentionMs, List<String> warnings) {
        if (!root.isDirectory()) return true;
        File[] files = root.listFiles(File::isFile);
        if (files == null) return false;
        long now = System.currentTimeMillis();
        boolean success = true;
        for (File file : files) {
            String name = file.getName();
            if (!name.endsWith(suffix)) continue;
            String key = name.substring(0, name.length() - suffix.length());
            if (activeKeys.contains(key) || !isExpired(file, now, retentionMs)) continue;
            if (!file.delete()) {
                success = false;
                warnings.add("delete failed: " + name);
            }
        }
        return success;
    }

    private static Outcome clearAgedTree(File root, long retentionMs) {
        if (root == null || !root.exists()) return new Outcome(true, List.of());
        if (retentionMs <= 0) return clearTree(root);
        long cutoff = System.currentTimeMillis() - retentionMs;
        boolean success = deleteAged(root, cutoff, new ArrayList<>());
        return new Outcome(success, List.of());
    }

    private static boolean deleteAged(File file, long cutoff, List<String> warnings) {
        if (file == null || !file.exists()) return true;
        if (CachePathSafety.isSymbolicLink(file)) return false;
        boolean success = true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) return false;
            for (File child : children) success &= deleteAged(child, cutoff, warnings);
        }
        if (file.lastModified() >= cutoff) return success;
        if (!file.delete()) {
            warnings.add("delete failed: " + file.getName());
            return false;
        }
        return success;
    }

    private static Outcome clearTrees(File... roots) {
        boolean success = true;
        ArrayList<String> warnings = new ArrayList<>();
        for (File root : roots) {
            Outcome result = clearTree(root);
            success &= result.success;
            warnings.addAll(result.warnings);
        }
        return new Outcome(success, warnings);
    }

    private static Outcome clearTree(File root) {
        if (root == null || !root.exists()) return new Outcome(true, List.of());
        boolean success = deleteTree(root);
        return new Outcome(success, success ? List.of() : List.of("delete failed: " + root.getName()));
    }

    private static boolean deleteTree(File file) {
        if (file == null || !file.exists()) return true;
        if (CachePathSafety.isSymbolicLink(file) || PROTECTED_NAMES.contains(file.getName())) return false;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        return !file.exists() || file.delete();
    }

    private static boolean isExpired(File file, long now, long retentionMs) {
        long modified = file.lastModified();
        return retentionMs <= 0 || modified > 0 && now - modified >= retentionMs;
    }

    private static boolean isTemporaryName(String name) {
        return name.equals("update.apk")
                || name.startsWith("pushed-")
                || name.startsWith("pushed-url-")
                || name.startsWith("apk-push-")
                || name.startsWith("webhtv-sync-")
                || name.startsWith("webhtv-login-state-")
                || name.startsWith("webhtv-mpv-sync-")
                || name.startsWith("webhtv-mpv-restore-")
                || name.startsWith("webhtv-git-restore-")
                || name.endsWith(".tmp");
    }

    private static CacheMeasurement measure(CacheModuleId id) {
        CacheModule module = CacheInventory.find(id);
        return module == null ? CacheMeasurement.unavailable(id, "unknown module")
                : CacheInventory.measure(module, Long.MAX_VALUE);
    }

    private static CacheCleanupResult aggregate(List<CacheCleanupResult> results) {
        if (results.isEmpty()) return new CacheCleanupResult(null, CacheCleanupStatus.NOT_ALLOWED,
                0, 0, 0, 0, List.of("empty results"));
        long before = 0, after = 0, deleted = 0, skipped = 0;
        ArrayList<String> warnings = new ArrayList<>();
        for (CacheCleanupResult result : results) {
            before += result.bytesBefore();
            after += result.bytesAfter();
            deleted += result.deletedFiles();
            skipped += result.skippedFiles();
            warnings.addAll(result.warnings());
        }
        CacheCleanupStatus status = results.stream().anyMatch(r -> r.status() == CacheCleanupStatus.CANCELLED)
                ? CacheCleanupStatus.CANCELLED
                : results.stream().anyMatch(r -> r.status() == CacheCleanupStatus.FAILED)
                ? CacheCleanupStatus.FAILED
                : results.stream().anyMatch(r -> r.status() == CacheCleanupStatus.DEFERRED
                || r.status() == CacheCleanupStatus.PARTIAL)
                ? CacheCleanupStatus.PARTIAL
                : results.stream().allMatch(r -> r.status() == CacheCleanupStatus.NOT_ALLOWED)
                ? CacheCleanupStatus.NOT_ALLOWED : CacheCleanupStatus.COMPLETED;
        return new CacheCleanupResult(results.get(0).id(), status, before, after, deleted, skipped, warnings);
    }

    private static Outcome outcome(boolean success) {
        return new Outcome(success, success ? List.of() : List.of("owner cleanup failed"));
    }

    private record Outcome(boolean success, List<String> warnings) {
    }
}
