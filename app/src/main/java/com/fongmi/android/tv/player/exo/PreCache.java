package com.fongmi.android.tv.player.exo;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.preload.PreCacheHelper;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.player.PlaybackRoute;
import com.fongmi.android.tv.player.PlaybackTrace;
import com.fongmi.android.tv.setting.PlaybackPerformanceSetting;
<<<<<<< HEAD
=======
import com.fongmi.android.tv.setting.PlaybackPerformanceCatalog;
import com.fongmi.android.tv.setting.PlaybackExperimentSetting;
import com.fongmi.android.tv.player.PlaybackExperimentPolicy;
>>>>>>> upstream/dev
import com.fongmi.android.tv.setting.PreloadSetting;
import com.fongmi.android.tv.setting.PlayerSetting;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class PreCache implements Player.Listener {

    public static boolean isPlaylistPreloadEnabled() {
        return PreloadSetting.isPreload(PlayerSetting.EXO)
                && PlaybackExperimentSetting.isAllowed(PlaybackExperimentPolicy.Action.EXO_AUTO_PRELOAD);
    }

    public boolean setPlaylistPreloadDurationMs(Player player, long durationMs) {
        if (!(player instanceof ExoPlayer exoPlayer) || durationMs < 0) return false;
        if (durationMs > 0 && !isPlaylistPreloadEnabled()) return false;
        exoPlayer.setPreloadConfiguration(new ExoPlayer.PreloadConfiguration(
                durationMs > Long.MAX_VALUE / 1000L ? Long.MAX_VALUE : durationMs * 1000L));
        return true;
    }

    private static final String TAG = "TV-exo-preload";
    private static final long TICK_MS = 5000;
    private static final long MIN_STEP_MS = 5000;
    private static final long MAX_STEP_MS = 30000;
    private static final long BUFFER_GAP_MS = 1250;
<<<<<<< HEAD
    private static final int STEP_DIV = 4;
=======
    private static final long DISK_RANGE_GAP_TOLERANCE_MS = 2000;
    private static final int PRELOAD_FAILURE_CIRCUIT_THRESHOLD = 2;
>>>>>>> 2d58d9085640098e3842a859fc3afa15050ac280

    private final PreloadLifecycleTracker lifecycle = new PreloadLifecycleTracker();
    private final PreCacheHelper.Listener preCacheListener = new PreCacheHelper.Listener() {
        @Override
        public void onPrepared(MediaItem originalMediaItem, MediaItem preparedMediaItem) {
            long sessionId = lifecycle.sessionId();
            if (sessionId > 0) PlaybackTrace.log("exo-preload", playbackTraceId, "event=helper-prepared session=%d generation=%d", sessionId, generation);
        }

        @Override
        public void onPreCacheCompleted(MediaItem mediaItem) {
            finishTask(PreloadLifecycleTracker.TaskEvent.Outcome.COMPLETED, "completed", null);
        }

        @Override
        public void onPrepareError(MediaItem mediaItem, IOException exception) {
<<<<<<< HEAD
            finishTask(PreloadLifecycleTracker.TaskEvent.Outcome.PREPARE_ERROR, "prepare-error", exception);
=======
            if (BuildConfig.DEBUG) Log.w(TAG, "prepare failed " + errorDetails(exception));
            handleTaskError(PreloadLifecycleTracker.TaskEvent.Outcome.PREPARE_ERROR, "prepare-error", exception);
>>>>>>> upstream/beta
        }

        @Override
        public void onDownloadError(MediaItem mediaItem, IOException exception) {
            finishTask(PreloadLifecycleTracker.TaskEvent.Outcome.DOWNLOAD_ERROR, "download-error", exception);
        }
    };
    private ThreadPoolExecutor executor;
    private PreCacheHelper helper;
    private Handler handler;
    private HandlerThread worker;
    private Player player;
    private PlaybackRoute route;
    private PlaybackRoute.Resolution routeResolution = PlaybackRoute.resolve(null);
    private volatile String playbackTraceId = PlaybackTrace.NONE;
    private Runnable scheduledTask;
    private int threads;
    private volatile long generation;
    private long lastStartMs;
    private long seekStartMs;
    private boolean playable;
<<<<<<< HEAD
=======
    private boolean refillActive;
    private boolean seekPreloadSuppressed;
    private boolean preloadErrorCircuitOpen;
    private int preloadFailureStreak;
    private boolean externalPreloadCircuitOpen;
    private boolean diskPreloadCircuitOpen;
    private boolean memoryPreloadPaused;
>>>>>>> 2d58d9085640098e3842a859fc3afa15050ac280
    private BufferGate bufferGate;
    private long preloadNotBeforeMs = C.TIME_UNSET;
    private long nextRangeNotBeforeMs = C.TIME_UNSET;
    private AutoPreloadPolicy autoPolicy;

    public void start(Player player, MediaItem mediaItem, String playbackTraceId, PlaybackRoute.Resolution routeResolution) {
        stop("replace-media");
        this.playbackTraceId = PlaybackTrace.normalize(playbackTraceId);
        PriorityTaskDataSource.resetDiagnostics();
<<<<<<< HEAD
        if (!PreloadSetting.isPreload(PlayerSetting.EXO) || !canPreCache(mediaItem)) return;
<<<<<<< HEAD
=======
=======
        boolean enabled = PreloadSetting.isPreload(PlayerSetting.EXO);
        PreCacheEligibility eligibility = eligibility(mediaItem);
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "start enabled=" + enabled
                    + " eligible=" + eligibility.eligible()
                    + " reason=" + eligibility.reason()
                    + " scheme=" + eligibility.scheme()
                    + " concatenating=" + eligibility.concatenating()
                    + " mime=" + eligibility.mimeType());
        }
        if (!enabled || !eligibility.eligible()) return;
>>>>>>> upstream/beta
        boolean automaticPreload = PlaybackPerformanceSetting.isAuto(
                PlayerSetting.EXO,
                PlaybackPerformanceCatalog.PRELOAD);
        boolean automaticTuning = PlaybackPerformanceSetting.hasAutomaticOptions(
                PlayerSetting.EXO,
                PlaybackPerformanceCatalog.PRELOAD_THREADS,
                PlaybackPerformanceCatalog.PRELOAD_TIME);
        boolean automatic = automaticPreload || automaticTuning;
        boolean experimentAllowed = PlaybackExperimentSetting.isAllowed(
                PlaybackExperimentPolicy.Action.EXO_AUTO_PRELOAD);
        if (automatic && !experimentAllowed) {
            if (!automaticPreload) {
                automatic = false;
            } else {
                if (BuildConfig.DEBUG) {
                    Log.i(TAG, "start skipped reason=experiment-suppressed automatic=true");
                }
                PlaybackTrace.log("exo-preload", this.playbackTraceId,
                        "event=experiment-suppressed action=keep-foreground-only");
                return;
            }
        }
>>>>>>> upstream/dev
        this.player = player;
        this.handler = new Handler(player.getApplicationLooper());
        this.routeResolution = routeResolution == null ? PlaybackRoute.resolve(mediaItem.localConfiguration.uri.toString()) : routeResolution;
        this.route = this.routeResolution.route();
<<<<<<< HEAD
        this.autoPolicy = PlaybackPerformanceSetting.isAuto(PlayerSetting.EXO) ? new AutoPreloadPolicy() : null;
=======
        this.autoPolicy = automatic ? new AutoPreloadPolicy() : null;
        this.autoSession = autoPolicy == null
                ? PlaybackAutoContext.SessionToken.none() : currentAutoSession();
        this.lastAutoInputs = null;
        this.lastAutoDecision = null;
        // Keep the exact MediaItem used by foreground playback.  Preload must
        // not infer or override a MIME type from a route classifier: signed
        // direct-media URLs are commonly classified as HLS while returning a
        // media segment/stream, which makes Exo hand them to HlsMediaSource
        // and repeatedly fail with "Input does not start with #EXTM3U".
>>>>>>> upstream/beta
        this.helper = createHelper(mediaItem);
<<<<<<< HEAD
<<<<<<< HEAD
=======
        if (this.helper == null) {
            if (BuildConfig.DEBUG) Log.i(TAG, "start skipped reason=worker-unavailable");
            stop("worker-unavailable");
            return;
        }
=======
>>>>>>> 2d58d9085640098e3842a859fc3afa15050ac280
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "session active automatic=" + automatic
                    + " experimentAllowed=" + experimentAllowed
                    + " threads=" + PreloadSetting.getPreloadThreads(PlayerSetting.EXO)
                    + " chunkMs=" + PreloadSetting.getPreloadDurationMs(PlayerSetting.EXO)
                    + " aheadMs=" + PreloadSetting.getPreloadAheadDurationMs(PlayerSetting.EXO));
        }
        bindMemoryPressure();
        bindSystemConditions();
>>>>>>> upstream/dev
        clearSeek();
        lastStartMs = C.TIME_UNSET;
        playable = false;
<<<<<<< HEAD
=======
        refillActive = true;
        seekPreloadSuppressed = false;
        preloadErrorCircuitOpen = false;
        preloadFailureStreak = 0;
        externalPreloadCircuitOpen = false;
        diskPreloadCircuitOpen = false;
>>>>>>> 2d58d9085640098e3842a859fc3afa15050ac280
        bufferGate = BufferGate.FIRST_FRAME;
        preloadNotBeforeMs = C.TIME_UNSET;
        nextRangeNotBeforeMs = C.TIME_UNSET;
        this.player.addListener(this);
        logSession(lifecycle.beginSession(), "generation=%d %s configuredThreads=%d effectiveThreads=%d durationTargetMs=%d cacheCapacityBytes=%d", generation, this.routeResolution.logSummary(), PreloadSetting.getPreloadThreads(PlayerSetting.EXO), threads, PreloadSetting.getPreloadDurationMs(PlayerSetting.EXO), MediaSourceFactory.getCacheCapacityBytes());
        transition(PreloadLifecycleTracker.State.WAIT_FIRST_FRAME, "session-start", "generation=%d position=%d buffered=%d loading=%s", generation, player.getCurrentPosition(), player.getTotalBufferedDuration(), player.isLoading());
        check();
    }

    /** Rebinds preload state to the item Media3 made current without rebuilding the player. */
    public void onMediaItemTransition(
            Player player,
            MediaItem mediaItem,
            String playbackTraceId,
            PlaybackRoute.Resolution routeResolution) {
        start(player, mediaItem, playbackTraceId, routeResolution);
    }

    public void stop() {
        stop("player-stop");
    }

    public void stop(String reason) {
        boolean active = helper != null || player != null;
        PriorityTaskDataSource.DiagnosticSnapshot priority = active ? PriorityTaskDataSource.getDiagnosticSnapshot() : null;
        long stoppedGeneration = generation;
        stopCurrentTask(reason);
        if (active) {
            logSession(lifecycle.endSession(reason), "generation=%d nextGeneration=%d waitCount=%d waitTotalMs=%d", stoppedGeneration, generation, priority.waitCount(), priority.waitTotalMs());
        }
        if (player != null) player.removeListener(this);
        if (helper != null) helper.release(false);
        handler = null;
        helper = null;
        player = null;
        route = null;
        routeResolution = PlaybackRoute.resolve(null);
        playbackTraceId = PlaybackTrace.NONE;
        autoPolicy = null;
        clearSeek();
        lastStartMs = C.TIME_UNSET;
        playable = false;
<<<<<<< HEAD
=======
        refillActive = true;
        seekPreloadSuppressed = false;
        preloadErrorCircuitOpen = false;
        preloadFailureStreak = 0;
        externalPreloadCircuitOpen = false;
        diskPreloadCircuitOpen = false;
        memoryPreloadPaused = false;
>>>>>>> 2d58d9085640098e3842a859fc3afa15050ac280
        bufferGate = BufferGate.FIRST_FRAME;
        preloadNotBeforeMs = C.TIME_UNSET;
        nextRangeNotBeforeMs = C.TIME_UNSET;
    }

    public void release() {
        stop("release");
        ThreadPoolExecutor retiringExecutor = executor;
        HandlerThread retiringWorker = worker;
        executor = null;
        worker = null;
        threads = 0;
        if (retiringWorker == null) {
            shutdownExecutor(retiringExecutor);
            return;
        }
        // PreCacheHelper.release() posts cancellation to this same looper.
        // Queue resource teardown behind it so SegmentDownloader cannot submit
        // work to an executor which has already entered SHUTTING_DOWN.
        new Handler(retiringWorker.getLooper()).post(() -> {
            shutdownExecutor(retiringExecutor);
            retiringWorker.quitSafely();
        });
    }

    @Override
    public void onPlaybackStateChanged(int state) {
        if (state == Player.STATE_BUFFERING) {
            deferPreload("buffering");
            if (autoPolicy != null) autoPolicy.disrupt(SystemClock.elapsedRealtime());
            if (playable) bufferGate = BufferGate.RECOVERY;
            transition(PreloadLifecycleTracker.State.CANCELLED_BUFFERING, "buffering", "generation=%d position=%d buffered=%d loading=%s", generation, player.getCurrentPosition(), player.getTotalBufferedDuration(), player.isLoading());
            stopCurrentTask("buffering");
        } else if (state == Player.STATE_READY && playable) {
            check();
        } else if (isStopped(state)) {
            cancel();
        }
    }

    @Override
    public void onRenderedFirstFrame() {
        markPlayable();
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        if (player == null) return;
        if (isPlaying && seekPreloadSuppressed) check();
        if (!isPlaying || playable) return;
        if (!player.getCurrentTracks().containsType(C.TRACK_TYPE_VIDEO) && player.getCurrentTracks().containsType(C.TRACK_TYPE_AUDIO)) {
            markPlayable();
        }
    }

    @Override
    public void onIsLoadingChanged(boolean isLoading) {
        if (playable && bufferGate != BufferGate.OPEN) check();
    }

    @Override
    public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition, @NonNull Player.PositionInfo newPosition, int reason) {
        if (!isSeek(reason) || helper == null) return;
        deferPreload("seek");
        transition(PreloadLifecycleTracker.State.CANCELLED_SEEK, "seek", "generation=%d oldPosition=%d newPosition=%d", generation, oldPosition.positionMs, newPosition.positionMs);
        if (autoPolicy != null) autoPolicy.disrupt(SystemClock.elapsedRealtime());
        seekPreloadSuppressed = true;
        preloadFailureStreak = 0;
        stopCurrentTask("seek");
        markSeek(newPosition.positionMs);
<<<<<<< HEAD
=======
        refillActive = false;
>>>>>>> 2d58d9085640098e3842a859fc3afa15050ac280
        if (playable) bufferGate = BufferGate.RECOVERY;
        check();
    }

    private void check() {
        check(generation);
    }

    private void check(long expectedGeneration) {
        if (expectedGeneration != generation) {
            PlaybackTrace.log("exo-preload", playbackTraceId, "event=stale-skip session=%d expectedGeneration=%d currentGeneration=%d", lifecycle.sessionId(), expectedGeneration, generation);
            return;
        }
        cancel();
<<<<<<< HEAD
        if (update()) schedule(expectedGeneration);
=======
        long nowMs = SystemClock.elapsedRealtime();
        if (nextRangeNotBeforeMs != C.TIME_UNSET && nowMs < nextRangeNotBeforeMs) {
            scheduleAt(expectedGeneration, nextRangeNotBeforeMs - nowMs);
            return;
        }
        if (update()) schedule(generation);
>>>>>>> upstream/beta
    }

    private boolean update() {
        if (helper == null || player == null) return false;
        if (!PreloadSetting.isPreload(PlayerSetting.EXO)) {
            stop("disabled");
            return false;
        }
        int state = player.getPlaybackState();
        if (isStopped(state)) return false;
        if (seekPreloadSuppressed) {
            SafeBufferStatus status = getSafeBufferStatus();
            if (!shouldReleaseSeekPreloadSuppression(state, player.isPlaying(), status.loading(), status.safe())) {
                transition(PreloadLifecycleTracker.State.WAIT_RECOVERY_BUFFER, "seek-suppressed", "generation=%d state=%d playing=%s requiredMs=%d bufferedMs=%d loading=%s", generation, state, player.isPlaying(), status.requiredMs(), status.bufferedMs(), status.loading());
                return true;
            }
            seekPreloadSuppressed = false;
            refillActive = true;
            PlaybackTrace.log("exo-preload", playbackTraceId, "event=seek-suppressed-release session=%d generation=%d bufferedMs=%d requiredMs=%d", lifecycle.sessionId(), generation, status.bufferedMs(), status.requiredMs());
        }
        if (state != Player.STATE_READY) return true;
        if (!playable) {
            transition(PreloadLifecycleTracker.State.WAIT_FIRST_FRAME, "first-frame", "generation=%d position=%d buffered=%d loading=%s", generation, player.getCurrentPosition(), player.getTotalBufferedDuration(), player.isLoading());
            return true;
        }
<<<<<<< HEAD
=======
        if (!PreCachePolicy.isPlaybackStableForPreload(
                SystemClock.elapsedRealtime(),
                preloadNotBeforeMs,
                player.isPlaying(),
                player.isLoading())) {
            PreloadLifecycleTracker.State waitState = bufferGate == BufferGate.RECOVERY
                    ? PreloadLifecycleTracker.State.WAIT_RECOVERY_BUFFER
                    : PreloadLifecycleTracker.State.WAIT_INITIAL_BUFFER;
            transition(waitState, "playback-stability-grace",
                    "generation=%d notBefore=%d position=%d buffered=%d playing=%s loading=%s",
                    generation, preloadNotBeforeMs, player.getCurrentPosition(),
                    player.getTotalBufferedDuration(), player.isPlaying(), player.isLoading());
            return true;
        }
        PreloadPausePolicy.Decision pauseDecision = getPauseDecision();
        if (!pauseDecision.allowed()) {
            if (lifecycle.hasActiveTask()) stopCurrentTask("pause-" + pauseDecision.reason().label());
            transition(PreloadLifecycleTracker.State.PAUSED_USER, pauseDecision.reason().label(), "generation=%d position=%d buffered=%d policy=%d", generation, player.getCurrentPosition(), player.getTotalBufferedDuration(), PreloadSetting.getPausePreloadPolicy(PlayerSetting.EXO));
            return true;
        }
        if (memoryPreloadPaused) {
            transition(PreloadLifecycleTracker.State.PAUSED_MEMORY, "memory-pressure", "generation=%d position=%d buffered=%d", generation, player.getCurrentPosition(), player.getTotalBufferedDuration());
            return false;
        }
>>>>>>> upstream/beta
        if (bufferGate != BufferGate.OPEN) {
            SafeBufferStatus status = getSafeBufferStatus();
            if (!status.safe()) {
                PreloadLifecycleTracker.State waitState = status.recovery() ? PreloadLifecycleTracker.State.WAIT_RECOVERY_BUFFER : PreloadLifecycleTracker.State.WAIT_INITIAL_BUFFER;
                transition(waitState, status.recovery() ? "recovery-watermark" : "initial-watermark", "generation=%d recovery=%s requiredMs=%d bufferedMs=%d loading=%s bitrate=%d effectiveCapacityBytes=%d capacityDurationMs=%d", generation, status.recovery(), status.requiredMs(), status.bufferedMs(), status.loading(), status.bitrate(), status.effectiveCapacityBytes(), status.capacityDurationMs());
                return true;
            }
        }
        bufferGate = BufferGate.OPEN;
        if (player.isCurrentMediaItemLive()) {
            transition(PreloadLifecycleTracker.State.SKIPPED, "live", "generation=%d", generation);
            stop("live");
            return false;
        }
<<<<<<< HEAD
=======
        if (preloadErrorCircuitOpen) {
            transition(PreloadLifecycleTracker.State.PAUSED_AUTO, "preload-error-circuit-open", "generation=%d position=%d buffered=%d", generation, player.getCurrentPosition(), player.getTotalBufferedDuration());
            return false;
        }
        if (diskPreloadCircuitOpen) {
            transition(PreloadLifecycleTracker.State.PAUSED_STORAGE, "disk-preload-circuit-open", "generation=%d position=%d buffered=%d", generation, player.getCurrentPosition(), player.getTotalBufferedDuration());
            return false;
        }
        if (externalPreloadCircuitOpen) {
            transition(PreloadLifecycleTracker.State.PAUSED_AUTO, "external-preload-circuit-open", "generation=%d route=%s position=%d buffered=%d", generation, route, player.getCurrentPosition(), player.getTotalBufferedDuration());
            return true;
        }
        ExoCacheWritePolicy.Decision cacheDecision = MediaSourceFactory.getCacheWriteDecision();
        if (!cacheDecision.writeAllowed()) {
            pauseForStorage(cacheDecision);
            return true;
        }
        AutoPreloadPolicy.Decision previousAutoDecision = lastAutoDecision;
>>>>>>> 2d58d9085640098e3842a859fc3afa15050ac280
        AutoPreloadPolicy.Decision autoDecision = getAutoDecision();
        if (autoDecision != null && !autoDecision.enabled()) {
            transition(PreloadLifecycleTracker.State.PAUSED_AUTO, "auto-" + autoDecision.mode(), "generation=%d route=%s mode=%s position=%d buffered=%d bandwidth=%d bitrate=%d", generation, route, autoDecision.mode(), player.getCurrentPosition(), player.getTotalBufferedDuration(), PlaybackAnalyticsListener.getSnapshot().bandwidthEstimate(), getSelectedBitrate());
            return true;
        }
        if (autoDecision != null) setEffectiveThreads(autoDecision.threads());
        long startMs = getStart();
        long lengthMs = getLength(startMs, autoDecision == null ? PreloadSetting.getPreloadDurationMs(PlayerSetting.EXO) : autoDecision.durationMs());
        if (lengthMs <= 0) {
            transition(PreloadLifecycleTracker.State.NO_RANGE, "no-range", "generation=%d startMs=%d durationMs=%d", generation, startMs, player.getDuration());
            clearSeek();
            return true;
        }
        if (!shouldPreCache(startMs)) return true;
        long bitrate = getSelectedBitrate();
        long estimatedBytes = ExoPlaybackDiagnostics.estimateBytes(bitrate, lengthMs);
        PriorityTaskDataSource.DiagnosticSnapshot priority = PriorityTaskDataSource.getDiagnosticSnapshot();
        transition(PreloadLifecycleTracker.State.PRELOADING, "task-start", "generation=%d route=%s threads=%d", generation, route, threads);
        for (PreloadLifecycleTracker.TaskEvent event : lifecycle.startTask(generation, startMs, lengthMs)) {
            if (event.type() == PreloadLifecycleTracker.TaskEvent.Type.END) {
                logTask(event, "reason=next-range");
            } else {
                logTask(event, "estimatedBytes=%d bitrate=%d position=%d buffered=%d loading=%s waitCount=%d waitTotalMs=%d", estimatedBytes, bitrate, player.getCurrentPosition(), player.getTotalBufferedDuration(), player.isLoading(), priority.waitCount(), priority.waitTotalMs());
            }
        }
        try {
            helper.preCache(startMs, lengthMs);
<<<<<<< HEAD
<<<<<<< HEAD
        } catch (RuntimeException | Error e) {
            finishTask(PreloadLifecycleTracker.TaskEvent.Outcome.START_ERROR, "start-error", e);
=======
        } catch (RuntimeException e) {
            PreloadLifecycleTracker.TaskEvent event = finishTask(PreloadLifecycleTracker.TaskEvent.Outcome.INTERNAL_ERROR, "start-error", e);
            if (event != null && ExoCacheWriteErrorClassifier.isDiskWriteFailure(e)) {
                openDiskCircuit("start-error", e);
                return false;
            }
            stop("start-error");
            return false;
        } catch (Error e) {
=======
        } catch (RuntimeException | Error e) {
>>>>>>> 2d58d9085640098e3842a859fc3afa15050ac280
            PreloadLifecycleTracker.TaskEvent event = finishTask(PreloadLifecycleTracker.TaskEvent.Outcome.START_ERROR, "start-error", e);
            if (event != null && ExoCacheWriteErrorClassifier.isDiskWriteFailure(e)) {
                openDiskCircuit("start-error", e);
                return false;
            }
>>>>>>> upstream/dev
            throw e;
        }
        lastStartMs = startMs;
        clearSeek();
        return true;
    }

    private void schedule(long expectedGeneration) {
        scheduleAt(expectedGeneration, TICK_MS);
    }

    private void scheduleAt(long expectedGeneration, long delayMs) {
        if (handler == null || expectedGeneration != generation) return;
        cancel();
        scheduledTask = () -> check(expectedGeneration);
        handler.postDelayed(scheduledTask, Math.max(0, delayMs));
    }

    private void cancel() {
        if (handler != null && scheduledTask != null) handler.removeCallbacks(scheduledTask);
        scheduledTask = null;
    }

    private void stopCurrentTask(String reason) {
        logTask(lifecycle.endTask(PreloadLifecycleTracker.TaskEvent.Outcome.CANCELLED), "reason=%s", reason);
        generation++;
        cancel();
        if (helper != null) helper.stop();
        lastStartMs = C.TIME_UNSET;
    }

    private SafeBufferStatus getSafeBufferStatus() {
        long durationMs = player.getDuration();
        long positionMs = player.getCurrentPosition();
        long remainingMs = durationMs > 0 && positionMs >= 0 ? Math.max(0, durationMs - positionMs) : C.TIME_UNSET;
        boolean recovery = bufferGate == BufferGate.RECOVERY;
        long bitrate = getSelectedBitrate();
        int effectiveCapacityBytes = ExoUtil.getBufferBudget().effectiveTargetBytes();
        long requiredMs = PreCachePolicy.safeBufferTargetMs(recovery, remainingMs, bitrate, effectiveCapacityBytes);
        long bufferedMs = player.getTotalBufferedDuration();
        boolean loading = player.isLoading();
        boolean safe = PreCachePolicy.hasSafeBuffer(bufferedMs, loading, requiredMs, recovery);
        return new SafeBufferStatus(safe, recovery, requiredMs, bufferedMs, loading, bitrate, effectiveCapacityBytes, ExoPlaybackDiagnostics.capacityDurationMs(effectiveCapacityBytes, bitrate));
    }

    private long getSelectedBitrate() {
        Format video = TrackUtil.selectedFormat(player.getCurrentTracks(), C.TRACK_TYPE_VIDEO);
        Format audio = TrackUtil.selectedFormat(player.getCurrentTracks(), C.TRACK_TYPE_AUDIO);
        return ExoPlaybackDiagnostics.combinedBitrate(video, audio);
    }

    private void markPlayable() {
        if (!playable) {
            playable = true;
            bufferGate = BufferGate.INITIAL;
            deferPreload("first-frame");
        }
        check();
    }

<<<<<<< HEAD
<<<<<<< HEAD
=======
=======
    private void deferPreload(String reason) {
        long now = SystemClock.elapsedRealtime();
        if (preloadNotBeforeMs > now) return;
        preloadNotBeforeMs = now > Long.MAX_VALUE - PreCachePolicy.PLAYBACK_STABILITY_GRACE_MS
                ? Long.MAX_VALUE : now + PreCachePolicy.PLAYBACK_STABILITY_GRACE_MS;
        PlaybackTrace.log("exo-preload", playbackTraceId,
                "event=stability-grace reason=%s notBefore=%d", reason, preloadNotBeforeMs);
    }

>>>>>>> upstream/beta
    private void bindMemoryPressure() {
        memoryPreloadPaused = false;
        if (autoPolicy == null || !autoSession.active()) return;
        ExoMemoryPressureCoordinator coordinator = ExoMemoryPressureCoordinator.process();
        memoryPressureRegistration = coordinator.addListener(this::onMemoryPressureDecision);
        ExoMemoryPressurePolicy.Decision current = coordinator.currentDecision(autoSession);
        memoryPreloadPaused = current != null && current.preloadPaused();
    }

    private void unbindMemoryPressure() {
        ExoMemoryPressureCoordinator.Registration registration = memoryPressureRegistration;
        memoryPressureRegistration = null;
        if (registration != null) registration.close();
    }

    private void onMemoryPressureDecision(ExoMemoryPressureCoordinator.Update update) {
        if (update == null || update.decision() == null || handler == null) return;
        PlaybackAutoContext.SessionToken expectedSession = autoSession;
        if (!expectedSession.equals(update.session())) return;
        Handler currentHandler = handler;
        currentHandler.post(() -> applyMemoryPressureDecision(
                expectedSession, update.decision()));
    }

    private void applyMemoryPressureDecision(
            PlaybackAutoContext.SessionToken expectedSession,
            ExoMemoryPressurePolicy.Decision decision) {
        if (player == null || handler == null
                || !autoSession.equals(expectedSession)
                || decision == null) {
            return;
        }
        boolean paused = decision.preloadPaused();
        if (paused == memoryPreloadPaused) return;
        memoryPreloadPaused = paused;
        if (paused) {
            if (lifecycle.hasActiveTask()) stopCurrentTask("memory-pressure");
            else cancel();
            transition(PreloadLifecycleTracker.State.PAUSED_MEMORY, decision.reason().label(), "generation=%d mode=%s effectiveBytes=%d", generation, decision.mode().label(), decision.effectiveTargetBytes());
            publishMemoryPreloadDecision(
                    decision,
                    PlaybackTelemetry.DecisionOutcome.SUPPRESSED,
                    "memory-pressure");
            return;
        }
        bufferGate = BufferGate.RECOVERY;
        transition(PreloadLifecycleTracker.State.WAIT_RECOVERY_BUFFER, "memory-recovered", "generation=%d effectiveBytes=%d", generation, decision.effectiveTargetBytes());
        publishMemoryPreloadDecision(
                decision,
                PlaybackTelemetry.DecisionOutcome.APPLIED,
                "memory-recovered");
        check();
    }

    private void bindSystemConditions() {
        if (autoPolicy == null || !autoSession.active()) return;
        systemConditionBridge = new ExoPreloadSystemConditionBridge(
                autoSession,
                PlaybackSystemConditionCoordinator.process(),
                this::onSystemConditionUpdate);
    }

    private void unbindSystemConditions() {
        ExoPreloadSystemConditionBridge bridge = systemConditionBridge;
        systemConditionBridge = null;
        if (bridge != null) bridge.close();
    }

    private void onSystemConditionUpdate(
            PlaybackSystemConditionCoordinator.Update update) {
        if (update == null || handler == null) return;
        PlaybackAutoContext.SessionToken expectedSession = autoSession;
        if (!expectedSession.equals(update.session())) return;
        Handler currentHandler = handler;
        currentHandler.post(() -> applySystemConditionUpdate(expectedSession, update));
    }

    private void applySystemConditionUpdate(
            PlaybackAutoContext.SessionToken expectedSession,
            PlaybackSystemConditionCoordinator.Update update) {
        if (player == null || handler == null || autoPolicy == null
                || update == null || !autoSession.equals(expectedSession)
                || !expectedSession.equals(update.session())) return;
        long nowMs = SystemClock.elapsedRealtime();
        AutoPreloadPolicy.Reason disruption =
                ExoPreloadSystemConditionBridge.disruption(update, nowMs);
        if (disruption != null) autoPolicy.disrupt(nowMs, disruption);
        check();
    }

    private void openExternalCircuit(String reason, Throwable error) {
        if (route != PlaybackRoute.EXTERNAL_LOOPBACK_PROXY || externalPreloadCircuitOpen) return;
        externalPreloadCircuitOpen = true;
        PlaybackTrace.log("exo-preload", playbackTraceId, "event=circuit-open session=%d generation=%d route=%s reason=%s error=%s action=stop-preload-keep-playback", lifecycle.sessionId(), generation, route, reason, error == null ? "-" : error.getClass().getSimpleName());
        stopCurrentTask("external-preload-circuit-open");
        transition(PreloadLifecycleTracker.State.PAUSED_AUTO, "external-preload-circuit-open", "generation=%d route=%s", generation, route);
    }

    private void handleTaskError(PreloadLifecycleTracker.TaskEvent.Outcome outcome, String reason, Throwable error) {
        if (finishTask(outcome, reason, error) == null) return;
        if (ExoCacheWriteErrorClassifier.isDiskWriteFailure(error)) openDiskCircuit(reason, error);
        else if (route == PlaybackRoute.EXTERNAL_LOOPBACK_PROXY) openExternalCircuit(reason, error);
        else if (shouldOpenPreloadFailureCircuit(++preloadFailureStreak)) openPreloadErrorCircuit(reason, error);
    }

    private void openPreloadErrorCircuit(String reason, Throwable error) {
        if (preloadErrorCircuitOpen) return;
        preloadErrorCircuitOpen = true;
        PlaybackTrace.log("exo-preload", playbackTraceId, "event=preload-circuit-open session=%d generation=%d reason=%s error=%s action=stop-preload-keep-playback", lifecycle.sessionId(), generation, reason, error == null ? "-" : error.getClass().getSimpleName());
        stopCurrentTask("preload-error-circuit-open");
        transition(PreloadLifecycleTracker.State.PAUSED_AUTO, "preload-error-circuit-open", "generation=%d failures=%d", generation, preloadFailureStreak);
    }

    private void openDiskCircuit(String reason, Throwable error) {
        if (diskPreloadCircuitOpen) return;
        diskPreloadCircuitOpen = true;
        ExoCacheWritePolicy.Decision decision = MediaSourceFactory.getCacheWriteDecision();
        publishStorageDecision(decision, PlaybackTelemetry.DecisionOutcome.FAILED, reason);
        PlaybackTrace.log("exo-preload", playbackTraceId, "event=disk-circuit-open session=%d generation=%d reason=%s error=%s policy=%s action=stop-preload-keep-playback", lifecycle.sessionId(), generation, reason, error == null ? "-" : error.getClass().getSimpleName(), decision.reason().label());
        stopCurrentTask("disk-preload-circuit-open");
        transition(PreloadLifecycleTracker.State.PAUSED_STORAGE, "disk-preload-circuit-open", "generation=%d policy=%s", generation, decision.reason().label());
    }

    private void pauseForStorage(ExoCacheWritePolicy.Decision decision) {
        String reason = "storage-" + decision.reason().label();
        publishStorageDecision(decision, PlaybackTelemetry.DecisionOutcome.SUPPRESSED, reason);
        if (lifecycle.hasActiveTask()) stopCurrentTask(reason);
        transition(PreloadLifecycleTracker.State.PAUSED_STORAGE, reason, "generation=%d actualCapacityBytes=%d safeCapacityBytes=%d cacheSizeBytes=%d availableBytes=%d reserveBytes=%d reclaimBytes=%d", generation, decision.actualCapacityBytes(), decision.effectiveCapacityBytes(), decision.existingCacheBytes(), decision.availableStorageBytes(), decision.reserveBytes(), decision.reclaimBytes());
    }

>>>>>>> 2d58d9085640098e3842a859fc3afa15050ac280
    private PreCacheHelper createHelper(MediaItem mediaItem) {
        DataSource.Factory upstreamFactory = MediaSourceFactory.createUpstreamDataSourceFactory(ExoUtil.extractHeaders(mediaItem));
        return new PreCacheHelper.Factory(MediaSourceFactory.scopedCache(ExoUtil.extractHeaders(mediaItem)), upstreamFactory, ExoUtil.buildRenderersFactory(), getWorker().getLooper())
                .setDownloadExecutor(getExecutor())
                .setListener(preCacheListener)
                .create(mediaItem);
    }

    private String errorDetails(Throwable error) {
        if (error == null) return "type=-";
        StringBuilder details = new StringBuilder();
        Throwable current = error;
        for (int depth = 0; current != null && depth < 4; depth++) {
            if (depth > 0) details.append(" <- ");
            details.append(current.getClass().getSimpleName());
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                String safe = message.replaceAll("https?://\\S+", "<url>")
                        .replace('\n', ' ').replace('\r', ' ');
                details.append(':').append(safe, 0, Math.min(safe.length(), 240));
            }
            current = current.getCause();
        }
        return details.toString();
    }

    private PreCacheEligibility eligibility(MediaItem mediaItem) {
        if (mediaItem == null) {
            return new PreCacheEligibility(false, "missing-item", "-", false, "-");
        }
        if (mediaItem.localConfiguration == null) {
            return new PreCacheEligibility(false, "missing-local-config", "-", false, "-");
        }
        MediaItem.LocalConfiguration local = mediaItem.localConfiguration;
        String scheme = local.uri.getScheme();
        String url = local.uri.toString();
        boolean http = "http".equalsIgnoreCase(scheme)
                || "https".equalsIgnoreCase(scheme);
        boolean concatenating = MediaSourceFactory.isConcatenatingUrl(url);
        String reason = !http ? "unsupported-scheme"
                : concatenating ? "concatenating-url" : "eligible";
        return new PreCacheEligibility(
                http && !concatenating,
                reason,
                scheme == null ? "-" : scheme,
                concatenating,
                local.mimeType == null ? "-" : local.mimeType);
    }

<<<<<<< HEAD
    static boolean canPreCache(String scheme, String url) {
        return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) && !MediaSourceFactory.isConcatenatingUrl(url);
    }

    private long getStart() {
        if (hasSeek()) return Math.max(0, seekStartMs);
        long bufferedPositionMs = player.getBufferedPosition();
        if (bufferedPositionMs < 0) return Math.max(0, player.getCurrentPosition());
        return bufferedPositionMs > Long.MAX_VALUE - BUFFER_GAP_MS ? bufferedPositionMs : bufferedPositionMs + BUFFER_GAP_MS;
=======
    private long getStart(long effectiveBufferedEndMs) {
        long startMs = Math.max(0, effectiveBufferedEndMs);
        return startMs > Long.MAX_VALUE - BUFFER_GAP_MS ? startMs : startMs + BUFFER_GAP_MS;
>>>>>>> 2d58d9085640098e3842a859fc3afa15050ac280
    }

    private boolean shouldPreCache(long startMs) {
        if (hasSeek()) return true;
        if (lastStartMs == C.TIME_UNSET) return true;
        return Math.abs(startMs - lastStartMs) >= getStep();
    }

    private boolean isStopped(int state) {
        return state == Player.STATE_ENDED || state == Player.STATE_IDLE;
    }

    private long getLength(long startMs, long durationTargetMs) {
        long durationMs = player.getDuration();
        if (durationMs <= 0) return 0;
        long remainingMs = Math.max(0, durationMs - startMs);
        return PreCachePolicy.preloadLengthMs(durationTargetMs, remainingMs, getSelectedBitrate(), MediaSourceFactory.getCacheCapacityBytes());
    }

    private long getStep() {
        return Math.clamp(PreloadSetting.getPreloadDurationMs(PlayerSetting.EXO) / STEP_DIV, MIN_STEP_MS, MAX_STEP_MS);
    }

    private void markSeek(long startMs) {
        seekStartMs = startMs;
    }

    private void clearSeek() {
        seekStartMs = C.TIME_UNSET;
    }

    private boolean hasSeek() {
        return seekStartMs != C.TIME_UNSET;
    }

    private Executor getExecutor() {
        int requested = PreloadSetting.getPreloadThreads(PlayerSetting.EXO);
        int count = route == null ? requested : route.effectivePreloadThreads(requested);
        if (autoPolicy != null && PlaybackPerformanceSetting.isAuto(
                PlayerSetting.EXO,
                PlaybackPerformanceCatalog.PRELOAD_THREADS)) {
            count = route == null ? AutoPreloadPolicy.NORMAL_THREADS
                    : route.effectivePreloadThreads(AutoPreloadPolicy.NORMAL_THREADS);
        }
        if (executor != null) {
            setEffectiveThreads(count);
            return executor;
        }
        retireExecutor();
        threads = count;
        return executor = new ThreadPoolExecutor(count, count, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    }

    private AutoPreloadPolicy.Decision getAutoDecision() {
        if (autoPolicy == null) return null;
        PlaybackAnalyticsListener.Snapshot snapshot = PlaybackAnalyticsListener.getSnapshot();
<<<<<<< HEAD
        return autoPolicy.evaluate(SystemClock.elapsedRealtime(), route, player.getTotalBufferedDuration(), getSelectedBitrate(), snapshot.bandwidthEstimate(), snapshot.rebufferCount(), player.isLoading());
=======
        lastAutoInputs = AutoPreloadPolicy.Inputs.capture(
                nowMs,
                autoSession,
                route,
                player.getTotalBufferedDuration(),
                getSelectedBitrate(),
                snapshot.rebufferCount(),
                player.isLoading(),
                PlaybackAnalyticsListener.getBufferTrend(),
                PlaybackAnalyticsListener.getThroughputSnapshot(),
                PlaybackAutoContextStore.process().snapshot(),
                memoryPreloadPaused,
                lifecycle.hasActiveTask());
        return effectiveAutoDecision(autoPolicy.evaluate(lastAutoInputs));
    }

    private AutoPreloadPolicy.Decision effectiveAutoDecision(
            AutoPreloadPolicy.Decision decision) {
        if (decision == null) return null;
        boolean automaticPreload = PlaybackPerformanceSetting.isAuto(
                PlayerSetting.EXO,
                PlaybackPerformanceCatalog.PRELOAD);
        // A short throughput/buffer warning must reduce the disk task size, not
        // disable disk preloading. Foreground playback already owns the higher
        // priority data source, so keeping a single small background task is
        // safe and preserves the old continuously-growing disk buffer.
        if (automaticPreload && !decision.enabled()
                && !isHardAutomaticPause(decision.reason())) {
            return new AutoPreloadPolicy.Decision(
                    AutoPreloadPolicy.NORMAL_THREADS,
                    AutoPreloadPolicy.DEGRADED_DURATION_MS,
                    "degraded",
                    decision.reason() + "-continuous");
        }
        if (automaticPreload && !decision.enabled()) return decision;
        int effectiveThreads = PlaybackPerformanceSetting.isAuto(
                PlayerSetting.EXO,
                PlaybackPerformanceCatalog.PRELOAD_THREADS)
                ? Math.max(AutoPreloadPolicy.NORMAL_THREADS, decision.threads())
                : PreloadSetting.getPreloadThreads(PlayerSetting.EXO);
        long effectiveDurationMs = PlaybackPerformanceSetting.isAuto(
                PlayerSetting.EXO,
                PlaybackPerformanceCatalog.PRELOAD_TIME)
                ? (decision.durationMs() > 0
                ? decision.durationMs() : AutoPreloadPolicy.DEGRADED_DURATION_MS)
                : PreloadSetting.getPreloadDurationMs(PlayerSetting.EXO);
        return new AutoPreloadPolicy.Decision(
                effectiveThreads,
                effectiveDurationMs,
                decision.mode(),
                decision.reason());
    }

    private static boolean isHardAutomaticPause(String reason) {
        return switch (reason == null ? "" : reason) {
            case "session-mismatch", "memory-pressure", "network-unavailable",
                    "network-unvalidated", "data-saver", "power-save",
                    "thermal-pressure" -> true;
            default -> false;
        };
    }

    private PlaybackAutoContext.SessionToken currentAutoSession() {
        PlaybackAutoContext context = PlaybackAutoContextStore.process().snapshot();
        if (!context.active()
                || !context.session().traceId().equals(playbackTraceId)
                || context.kernel().hasValue()
                && context.kernel().value() != PlaybackAutoContext.Kernel.EXO) {
            return PlaybackAutoContext.SessionToken.none();
        }
        return context.session();
    }

    private void publishAutoPreloadDecision(
            AutoPreloadPolicy.Decision decision,
            PlaybackTelemetry.DecisionOutcome outcome,
            String reason) {
        if (player == null) return;
        PlaybackAnalyticsListener.Snapshot snapshot = PlaybackAnalyticsListener.getSnapshot();
        AutoPreloadPolicy.Inputs inputs = lastAutoInputs == null
                ? AutoPreloadPolicy.Inputs.unknown() : lastAutoInputs;
        AutoPreloadPolicy.ThroughputEvidence throughput = inputs.throughput();
        AutoPreloadPolicy.SystemEvidence system = inputs.system();
        ForwardBufferTrend.Snapshot trend = inputs.trend();
        String mode = decision == null ? "manual" : decision.mode();
        int selectedThreads = decision == null ? threads : decision.threads();
        long selectedDurationMs = decision == null
                ? PreloadSetting.getPreloadDurationMs(PlayerSetting.EXO) : decision.durationMs();
        String throughputEvidence = String.format(
                Locale.US,
                "samples:%d,window:%d,error:%d,trust:%s,confidence:%s",
                throughput.longSampleCount(),
                throughput.longWindowMs(),
                throughput.predictionErrorPermille(),
                throughput.pathTrust().label(),
                throughput.pathConfidence().label());
        String bufferEvidence = String.format(
                Locale.US,
                "slope:%d,tte:%d",
                trend.slopeMsPerSecond(),
                trend.timeToEmptyMs());
        String runtimeState = String.format(
                Locale.US,
                "loading:%s,rebuffer:%d,memory:%s,contention:%s",
                player.isLoading(),
                snapshot.rebufferCount(),
                inputs.memoryPreloadPaused(),
                throughput.preloadContended());
        String systemState = String.format(
                Locale.US,
                "%s,%s,%s,%s,%s,%s,%s",
                system.networkCost().label(),
                system.validated(),
                system.metered(),
                system.roaming(),
                system.dataSaver().label(),
                system.power().label(),
                system.thermal().label());
        PlaybackTelemetryCoordinator.process().publishDecision(playbackTraceId,
                new PlaybackTelemetry.DecisionEvent(
                        PlaybackTelemetry.DecisionDomain.PRELOAD,
                        outcome,
                        "preload-idle",
                        mode,
                        outcome == PlaybackTelemetry.DecisionOutcome.REQUESTED ? "task-requested" : "paused",
                        reason,
                        outcome == PlaybackTelemetry.DecisionOutcome.SUPPRESSED
                                ? decision == null ? mode : decision.reason() : "none",
                        List.of(
                                route == null ? PlaybackTelemetry.DecisionInput.unknown("route") : PlaybackTelemetry.DecisionInput.text(
                                        "route", route.name().toLowerCase(Locale.US), PlaybackAutoContext.ValueSource.ROUTE_CLASSIFIER, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.number("threads", selectedThreads, PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.number("duration_ms", selectedDurationMs, PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.number("buffered_ms", Math.max(0, player.getTotalBufferedDuration()), PlaybackAutoContext.ValueSource.PLAYER_CALLBACK, PlaybackAutoContext.Confidence.HIGH),
                                getSelectedBitrate() > 0 ? PlaybackTelemetry.DecisionInput.number("media_bitrate_bps", getSelectedBitrate(), PlaybackAutoContext.ValueSource.ESTIMATOR, PlaybackAutoContext.Confidence.MEDIUM) : PlaybackTelemetry.DecisionInput.unknown("media_bitrate_bps"),
                                throughput.usable() ? PlaybackTelemetry.DecisionInput.number("effective_bps", throughput.effectiveBitsPerSecond(), PlaybackAutoContext.ValueSource.ESTIMATOR, throughput.confidence()) : PlaybackTelemetry.DecisionInput.unknown("effective_bps"),
                                throughput.usable() ? PlaybackTelemetry.DecisionInput.number("short_bps", throughput.shortBitsPerSecond(), PlaybackAutoContext.ValueSource.ESTIMATOR, throughput.confidence()) : PlaybackTelemetry.DecisionInput.unknown("short_bps"),
                                throughput.usable() ? PlaybackTelemetry.DecisionInput.number("long_bps", throughput.longBitsPerSecond(), PlaybackAutoContext.ValueSource.ESTIMATOR, throughput.confidence()) : PlaybackTelemetry.DecisionInput.unknown("long_bps"),
                                PlaybackTelemetry.DecisionInput.text("throughput_evidence", throughputEvidence, PlaybackAutoContext.ValueSource.ESTIMATOR, throughput.confidence()),
                                PlaybackTelemetry.DecisionInput.text("buffer_evidence", bufferEvidence, PlaybackAutoContext.ValueSource.ESTIMATOR, trend.known() ? PlaybackAutoContext.Confidence.MEDIUM : PlaybackAutoContext.Confidence.UNKNOWN),
                                PlaybackTelemetry.DecisionInput.text("runtime_state", runtimeState, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.text("system_state", systemState, PlaybackAutoContext.ValueSource.SYSTEM_API, system.explicitlySafe() ? PlaybackAutoContext.Confidence.HIGH : PlaybackAutoContext.Confidence.LOW))),
                SystemClock.elapsedRealtime());
    }

    private void publishMemoryPreloadDecision(
            ExoMemoryPressurePolicy.Decision decision,
            PlaybackTelemetry.DecisionOutcome outcome,
            String reason) {
        if (decision == null) return;
        PlaybackTelemetryCoordinator.process().publishDecision(playbackTraceId,
                new PlaybackTelemetry.DecisionEvent(
                        PlaybackTelemetry.DecisionDomain.PRELOAD,
                        outcome,
                        outcome == PlaybackTelemetry.DecisionOutcome.SUPPRESSED
                                ? "preload-active" : "memory-paused",
                        decision.preloadPaused() ? "memory-paused" : "preload-eligible",
                        decision.preloadPaused() ? "paused" : "recheck-buffer",
                        reason,
                        decision.preloadPaused() ? decision.reason().label() : "none",
                        List.of(
                                PlaybackTelemetry.DecisionInput.text("memory_mode", decision.mode().label(), PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.number("baseline_bytes", decision.baselineTargetBytes(), PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.number("effective_bytes", decision.effectiveTargetBytes(), PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.bool("preload_paused", decision.preloadPaused(), PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.number("buffered_ms", Math.max(0, player == null ? 0 : player.getTotalBufferedDuration()), PlaybackAutoContext.ValueSource.PLAYER_CALLBACK, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.number("normal_samples", decision.normalSamples(), PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH))),
                SystemClock.elapsedRealtime());
    }

    private void publishStorageDecision(
            ExoCacheWritePolicy.Decision decision,
            PlaybackTelemetry.DecisionOutcome outcome,
            String reason) {
        if (decision == null) return;
        PlaybackTelemetryCoordinator.process().publishDecision(playbackTraceId,
                new PlaybackTelemetry.DecisionEvent(
                        PlaybackTelemetry.DecisionDomain.CACHE,
                        outcome,
                        "cache-write",
                        "preload-write",
                        decision.writeAllowed() ? "allowed" : "blocked",
                        reason,
                        decision.reason().label(),
                        List.of(
                                PlaybackTelemetry.DecisionInput.bool("write_allowed", decision.writeAllowed(), PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.number("actual_capacity_bytes", decision.actualCapacityBytes(), PlaybackAutoContext.ValueSource.SYSTEM_API, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.number("safe_capacity_bytes", decision.effectiveCapacityBytes(), PlaybackAutoContext.ValueSource.SYSTEM_API, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.number("cache_size_bytes", decision.existingCacheBytes(), PlaybackAutoContext.ValueSource.SYSTEM_API, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.number("available_bytes", decision.availableStorageBytes(), PlaybackAutoContext.ValueSource.SYSTEM_API, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.number("reserve_bytes", decision.reserveBytes(), PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.number("reclaim_bytes", decision.reclaimBytes(), PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH))),
                SystemClock.elapsedRealtime());
>>>>>>> upstream/dev
    }

    private void setEffectiveThreads(int requested) {
        if (executor == null) return;
        int count = route == null ? requested : route.effectivePreloadThreads(requested);
        if (count == threads) return;
        if (count > threads) {
            executor.setMaximumPoolSize(count);
            executor.setCorePoolSize(count);
        } else {
            executor.setCorePoolSize(count);
            executor.setMaximumPoolSize(count);
        }
        threads = count;
        long sessionId = lifecycle.sessionId();
        if (sessionId > 0) PlaybackTrace.log("exo-preload", playbackTraceId, "event=threads session=%d generation=%d threads=%d route=%s", sessionId, generation, threads, route);
    }

    private void retireExecutor() {
        if (executor == null) return;
        ThreadPoolExecutor retiringExecutor = executor;
        executor = null;
        if (worker == null) shutdownExecutor(retiringExecutor);
        else new Handler(worker.getLooper()).post(() -> shutdownExecutor(retiringExecutor));
    }

    private void shutdownExecutor(ThreadPoolExecutor target) {
        if (target == null) return;
        target.shutdownNow();
    }

    private HandlerThread getWorker() {
        if (worker != null) return worker;
        worker = new HandlerThread("CurrentMediaPreCache");
        worker.start();
        return worker;
    }

    private boolean isSeek(int reason) {
        return reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT;
    }

    private void transition(PreloadLifecycleTracker.State state, String reason, String format, Object... args) {
        PreloadLifecycleTracker.StateEvent event = lifecycle.transition(state, reason);
        if (event == null) return;
        PlaybackTrace.log("exo-preload", playbackTraceId, "event=state session=%d from=%s to=%s reason=%s %s", event.sessionId(), event.from().label(), event.to().label(), event.reason(), detail(format, args));
    }

    private void logSession(PreloadLifecycleTracker.SessionEvent event, String format, Object... args) {
        if (event == null) return;
        String type = event.type() == PreloadLifecycleTracker.SessionEvent.Type.START ? "session-start" : "session-end";
        PlaybackTrace.log("exo-preload", playbackTraceId, "event=%s session=%d reason=%s %s", type, event.sessionId(), event.reason(), detail(format, args));
    }

    private void logTask(PreloadLifecycleTracker.TaskEvent event, String format, Object... args) {
        if (event == null) return;
        String type = event.type() == PreloadLifecycleTracker.TaskEvent.Type.START ? "task-start" : "task-end";
        String outcome = event.outcome() == null ? "-" : event.outcome().label();
        String detail = detail(format, args);
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "event=" + type
                    + " session=" + event.sessionId()
                    + " task=" + event.taskId()
                    + " generation=" + event.generation()
                    + " outcome=" + outcome
                    + " startMs=" + event.startMs()
                    + " lengthMs=" + event.lengthMs()
                    + " " + detail);
        }
        PlaybackTrace.log("exo-preload", playbackTraceId, "event=%s session=%d task=%d generation=%d outcome=%s startMs=%d lengthMs=%d %s", type, event.sessionId(), event.taskId(), event.generation(), outcome, event.startMs(), event.lengthMs(), detail);
    }

    private void finishTask(PreloadLifecycleTracker.TaskEvent.Outcome outcome, String reason, Throwable error) {
        PreloadLifecycleTracker.TaskEvent event = lifecycle.endTask(outcome);
        if (event == null) return;
        if (error == null) logTask(event, "reason=%s", reason);
        else logTask(event, "reason=%s error=%s", reason, error.getClass().getSimpleName());
        PreloadLifecycleTracker.State state = outcome == PreloadLifecycleTracker.TaskEvent.Outcome.COMPLETED ? PreloadLifecycleTracker.State.WAIT_NEXT_RANGE : PreloadLifecycleTracker.State.WAIT_RETRY;
        transition(state, reason, "generation=%d task=%d", event.generation(), event.taskId());
<<<<<<< HEAD
=======
        if (outcome == PreloadLifecycleTracker.TaskEvent.Outcome.COMPLETED) {
            preloadFailureStreak = 0;
            diskBufferStore.recordCompleted(mediaKey, event.startMs(), saturatedAdd(event.startMs(), event.lengthMs()));
            nextRangeNotBeforeMs = saturatedAdd(
                    SystemClock.elapsedRealtime(),
                    PreCachePolicy.nextRangeDelayMs(true));
            requestImmediateCheck(event.generation());
        }
        return event;
    }

    private void requestImmediateCheck(long expectedGeneration) {
        if (handler == null) return;
        long delayMs = nextRangeNotBeforeMs == C.TIME_UNSET
                ? 0
                : Math.max(0, nextRangeNotBeforeMs - SystemClock.elapsedRealtime());
        scheduleAt(expectedGeneration, delayMs);
    }

    private static long saturatedAdd(long value, long increment) {
        if (increment <= 0) return value;
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    static boolean shouldReleaseSeekPreloadSuppression(int playbackState, boolean playing, boolean loading, boolean safeBuffer) {
        return playbackState == Player.STATE_READY && playing && !loading && safeBuffer;
    }

    static boolean shouldOpenPreloadFailureCircuit(int consecutiveFailures) {
        return consecutiveFailures >= PRELOAD_FAILURE_CIRCUIT_THRESHOLD;
    }

    private void beginPreloadTraffic() {
        closePreloadTraffic();
        preloadTrafficRegistration = ExoPreloadTrafficCoordinator.process().acquire(
                playbackTraceId,
                ExoPreloadTrafficCoordinator.Source.CUSTOM);
    }

    private void closePreloadTraffic() {
        ExoPreloadTrafficCoordinator.Registration registration = preloadTrafficRegistration;
        preloadTrafficRegistration = null;
        if (registration != null) registration.close();
    }

    private void beginTaskMetrics() {
        taskStartRealtimeMs = SystemClock.elapsedRealtime();
        taskPreparedDurationMs = C.TIME_UNSET;
        taskCacheBytesBefore = MediaSourceFactory.getCache().getCacheSpace();
    }

    private void logTaskEnd(PreloadLifecycleTracker.TaskEvent event, String reason, Throwable error) {
        if (event == null) return;
        long elapsedMs = taskStartRealtimeMs == C.TIME_UNSET ? C.TIME_UNSET : Math.max(0, SystemClock.elapsedRealtime() - taskStartRealtimeMs);
        long cacheBytesAdded = taskCacheDelta();
        PlaybackCacheMetrics.Snapshot cacheMetrics = PlaybackCacheMetrics.snapshot();
        if (error == null) {
            logTask(event, "reason=%s elapsedMs=%d prepareMs=%d cacheBytesAdded=%d cachedBytesRead=%d", reason, elapsedMs, taskPreparedDurationMs, cacheBytesAdded, cacheMetrics.cachedBytesRead());
        } else {
            logTask(event, "reason=%s error=%s elapsedMs=%d prepareMs=%d cacheBytesAdded=%d cachedBytesRead=%d", reason, error.getClass().getSimpleName(), elapsedMs, taskPreparedDurationMs, cacheBytesAdded, cacheMetrics.cachedBytesRead());
        }
        taskStartRealtimeMs = C.TIME_UNSET;
        taskPreparedDurationMs = C.TIME_UNSET;
        taskCacheBytesBefore = 0;
    }

    private long taskCacheDelta() {
        if (taskStartRealtimeMs == C.TIME_UNSET) return 0;
        return Math.max(0, MediaSourceFactory.getCache().getCacheSpace() - taskCacheBytesBefore);
>>>>>>> 2d58d9085640098e3842a859fc3afa15050ac280
    }

    private static String detail(String format, Object... args) {
        if (format == null || format.isBlank()) return "";
        try {
            return String.format(Locale.US, format, args);
        } catch (Throwable ignored) {
            return "detail-format-error";
        }
    }

    private record SafeBufferStatus(boolean safe, boolean recovery, long requiredMs, long bufferedMs, boolean loading, long bitrate, int effectiveCapacityBytes, long capacityDurationMs) {
    }

    private record PreCacheEligibility(boolean eligible, String reason,
                                       String scheme, boolean concatenating,
                                       String mimeType) {
    }

    private enum BufferGate {
        FIRST_FRAME,
        INITIAL,
        RECOVERY,
        OPEN
    }

}
