package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.player.PlaybackRoute;

final class AutoPreloadPolicy {

    static final int PAUSED_THREADS = 0;
    static final int NORMAL_THREADS = 1;
    static final int FAST_THREADS = 2;
    static final long DEGRADED_DURATION_MS = 10_000;
    static final long NORMAL_DURATION_MS = 20_000;
    static final long FAST_DURATION_MS = 30_000;
    private static final long NORMAL_BUFFER_MS = 8_000;
    private static final long FAST_BUFFER_MS = 20_000;
    private static final long FAST_FALLBACK_BUFFER_MS = 12_000;
    private static final long NORMAL_STABLE_MS = 20_000;
    private static final long RESUME_DELAY_MS = 10_000;
    private static final long WEAK_RESUME_DELAY_MS = 15_000;
    private static final long FAST_STABLE_MS = 30_000;
    private static final long FAST_COOLDOWN_MS = 60_000;
    private static final double PAUSE_RATIO = 1.15;
    private static final double RESUME_RATIO = 1.40;
    private static final double FAST_RATIO = 3.00;
    private static final double FAST_FALLBACK_RATIO = 2.00;

    private Mode mode = Mode.NORMAL;
    private long resumeAfterMs;
    private long fastBlockedUntilMs;
    private long stableSinceMs = Long.MIN_VALUE;
    private int lastRebufferCount;

    Decision evaluate(long nowMs, PlaybackRoute route, long bufferedMs, long mediaBitrate, long bandwidthEstimate, int rebufferCount, boolean loading) {
        if (rebufferCount > lastRebufferCount) disrupt(nowMs);
        lastRebufferCount = Math.max(lastRebufferCount, rebufferCount);
        double ratio = ratio(bandwidthEstimate, mediaBitrate);
        boolean knownRatio = ratio > 0;
        if ((loading && bufferedMs < PreCachePolicy.INITIAL_SAFE_BUFFER_MS) || (knownRatio && ratio < PAUSE_RATIO)) {
            pause(nowMs, WEAK_RESUME_DELAY_MS);
            return decision();
        }
        if (mode == Mode.PAUSED) {
            if (nowMs < resumeAfterMs || bufferedMs < NORMAL_BUFFER_MS || (knownRatio && ratio < RESUME_RATIO)) return decision();
            mode = Mode.NORMAL;
            stableSinceMs = nowMs;
        }
        if (mode == Mode.DEGRADED && bufferedMs >= FAST_FALLBACK_BUFFER_MS && (!knownRatio || ratio >= FAST_FALLBACK_RATIO)) {
            if (stableSinceMs == Long.MIN_VALUE) stableSinceMs = nowMs;
            if (nowMs - stableSinceMs >= NORMAL_STABLE_MS) {
                mode = Mode.NORMAL;
                stableSinceMs = nowMs;
            }
        }
        if (mode == Mode.FAST && (!supportsFast(route) || bufferedMs < FAST_FALLBACK_BUFFER_MS || (knownRatio && ratio < FAST_FALLBACK_RATIO))) {
            mode = Mode.NORMAL;
            stableSinceMs = nowMs;
        }
        if (mode == Mode.NORMAL && knownRatio && (bufferedMs < FAST_FALLBACK_BUFFER_MS || ratio < FAST_FALLBACK_RATIO)) {
            mode = Mode.DEGRADED;
            stableSinceMs = nowMs;
        }
        if (bufferedMs < NORMAL_BUFFER_MS || (knownRatio && ratio < RESUME_RATIO)) {
            stableSinceMs = Long.MIN_VALUE;
        } else if (stableSinceMs == Long.MIN_VALUE) {
            stableSinceMs = nowMs;
        }
        if (mode == Mode.NORMAL
                && supportsFast(route)
                && knownRatio
                && ratio >= FAST_RATIO
                && bufferedMs >= FAST_BUFFER_MS
                && nowMs >= fastBlockedUntilMs
                && stableSinceMs != Long.MIN_VALUE
                && nowMs - stableSinceMs >= FAST_STABLE_MS) {
            mode = Mode.FAST;
        }
        return decision();
    }

    void disrupt(long nowMs) {
<<<<<<< HEAD
        pause(nowMs, RESUME_DELAY_MS);
=======
        disrupt(nowMs, Reason.DISRUPTED);
    }

    void disrupt(long nowMs, Reason reason) {
        pause(Math.max(0, nowMs), RESUME_DELAY_MS,
                reason == null ? Reason.DISRUPTED : reason);
    }

    private Reason hardPauseReason(Inputs input) {
        if (!input.sessionMatches()) return Reason.SESSION_MISMATCH;
        if (input.memoryPreloadPaused()) return Reason.MEMORY_PRESSURE;
        SystemEvidence system = input.system();
        if (system.networkUsable()) {
            if (Boolean.FALSE.equals(system.available())) return Reason.NETWORK_UNAVAILABLE;
            if (Boolean.FALSE.equals(system.validated())) return Reason.NETWORK_UNVALIDATED;
            if (system.dataSaver() == PlaybackAutoContext.DataSaverState.ENABLED) {
                return Reason.DATA_SAVER;
            }
        }
        if (system.powerUsable()
                && system.power() == PlaybackAutoContext.PowerState.POWER_SAVE) {
            return Reason.POWER_SAVE;
        }
        if (system.thermalUsable()
                && (system.thermal() == PlaybackAutoContext.ThermalState.SEVERE
                || system.thermal() == PlaybackAutoContext.ThermalState.CRITICAL)) {
            return Reason.THERMAL_PRESSURE;
        }
        if (unknownAppProxyRecovery(input)
                && input.bufferedMs() < APP_PROXY_RECOVERY_BUFFER_MS) {
            return Reason.FOREGROUND_RECOVERY;
        }
        if (input.loading() && input.bufferedMs() < PreCachePolicy.INITIAL_SAFE_BUFFER_MS) {
            return Reason.FRONT_BUFFER_LOW;
        }
        if (input.bufferedMs() < FRONT_BUFFER_PAUSE_MS) return Reason.FRONT_BUFFER_LOW;

        TrendEvidence trend = TrendEvidence.from(input.trend(), input.nowElapsedMs());
        if (trend.usable()) {
            if (trend.timeToEmptyMs() >= 0
                    && trend.timeToEmptyMs() <= CRITICAL_TIME_TO_EMPTY_MS) {
                return Reason.TIME_TO_EMPTY;
            }
            if (trend.slopeMsPerSecond() <= PAUSE_SLOPE_MS_PER_SECOND
                    && input.bufferedMs() < NORMAL_BUFFER_MS) {
                return Reason.BUFFER_DECLINING;
            }
        }

        ThroughputEvidence throughput = input.throughput();
        if (throughput.usable()
                && throughput.pathTrust() != ExoThroughputPathPolicy.Trust.BLOCKED
                && input.mediaBitrateBitsPerSecond() > 0) {
            double effectiveRatio = ratio(
                    throughput.effectiveBitsPerSecond(), input.mediaBitrateBitsPerSecond());
            double shortRatio = ratio(
                    throughput.shortBitsPerSecond(), input.mediaBitrateBitsPerSecond());
            if (effectiveRatio > 0 && effectiveRatio < PAUSE_RATIO) {
                return Reason.THROUGHPUT_DEFICIT;
            }
            if (shortRatio > 0 && shortRatio < SHORT_PAUSE_RATIO) {
                return Reason.SHORT_WINDOW_DEFICIT;
            }
        }
        return null;
    }

    private boolean resumeEligible(Inputs input) {
        long requiredBuffer = unknownAppProxyRecovery(input)
                ? APP_PROXY_RECOVERY_BUFFER_MS
                : input.route() == PlaybackRoute.EXTERNAL_LOOPBACK_PROXY
                ? EXTERNAL_LOOPBACK_RESUME_BUFFER_MS
                : NORMAL_BUFFER_MS;
        if (input.bufferedMs() < requiredBuffer) return false;
        TrendEvidence trend = TrendEvidence.from(input.trend(), input.nowElapsedMs());
        if (trend.usable()) {
            if (trend.timeToEmptyMs() >= 0
                    && trend.timeToEmptyMs() <= WARNING_TIME_TO_EMPTY_MS) return false;
            if (trend.slopeMsPerSecond() < NORMAL_SLOPE_MS_PER_SECOND) return false;
        }
        ThroughputEvidence throughput = input.throughput();
        if (!throughput.usable()
                || throughput.pathTrust() == ExoThroughputPathPolicy.Trust.BLOCKED
                || input.mediaBitrateBitsPerSecond() <= 0) {
            return true;
        }
        double effectiveRatio = ratio(
                throughput.effectiveBitsPerSecond(), input.mediaBitrateBitsPerSecond());
        double shortRatio = ratio(
                throughput.shortBitsPerSecond(), input.mediaBitrateBitsPerSecond());
        return (effectiveRatio <= 0 || effectiveRatio >= RESUME_RATIO)
                && (shortRatio <= 0 || shortRatio >= SHORT_RESUME_RATIO);
    }

    private Reason cautionReason(Inputs input, boolean holdingFast) {
        SystemEvidence system = input.system();
        if (system.networkUsable()) {
            if (Boolean.TRUE.equals(system.roaming())) return Reason.ROAMING;
            if (Boolean.TRUE.equals(system.metered())) return Reason.METERED;
        }
        if (system.networkCostUsable()) {
            if (system.networkCost() == PlaybackAutoContext.NetworkCost.ROAMING) {
                return Reason.ROAMING;
            }
            if (system.networkCost() == PlaybackAutoContext.NetworkCost.METERED) {
                return Reason.METERED;
            }
        }
        if (system.thermalUsable()
                && system.thermal() == PlaybackAutoContext.ThermalState.MODERATE) {
            return Reason.THERMAL_MODERATE;
        }
        if (system.networkUsable()
                && system.dataSaver() == PlaybackAutoContext.DataSaverState.WHITELISTED) {
            return Reason.DATA_SAVER_WHITELISTED;
        }
        if (!system.explicitlySafe()) return Reason.SYSTEM_EVIDENCE_UNKNOWN;
        if (system.transport() == PlaybackAutoContext.NetworkTransport.VPN) {
            return Reason.VPN;
        }
        if (input.route() == PlaybackRoute.OTHER) return Reason.PATH_LIMITED;
        if (input.bufferedMs() < NORMAL_BUFFER_MS) return Reason.FRONT_BUFFER_MARGIN;

        TrendEvidence trend = TrendEvidence.from(input.trend(), input.nowElapsedMs());
        if (!trend.usable()) return Reason.BUFFER_EVIDENCE_UNKNOWN;
        if (trend.timeToEmptyMs() >= 0
                && trend.timeToEmptyMs() <= WARNING_TIME_TO_EMPTY_MS) {
            return Reason.TIME_TO_EMPTY;
        }
        if (trend.slopeMsPerSecond() < NORMAL_SLOPE_MS_PER_SECOND) {
            return Reason.BUFFER_DECLINING;
        }

        if (input.route() == PlaybackRoute.EXTERNAL_LOOPBACK_PROXY) {
            if (input.bufferedMs() < EXTERNAL_LOOPBACK_NORMAL_BUFFER_MS) {
                return Reason.EXTERNAL_BUFFER_MARGIN;
            }
            return null;
        }

        ThroughputEvidence throughput = input.throughput();
        if (!throughput.usable()) return Reason.THROUGHPUT_EVIDENCE_UNKNOWN;
        if (throughput.pathTrust() != ExoThroughputPathPolicy.Trust.TRUSTED
                || !confidenceAtLeast(
                throughput.pathConfidence(), PlaybackAutoContext.Confidence.MEDIUM)) {
            return Reason.PATH_LIMITED;
        }
        if (throughput.preloadContended() && !input.preloadActive()) {
            return Reason.PRELOAD_CONTENTION;
        }
        if (!confidenceAtLeast(
                throughput.confidence(), PlaybackAutoContext.Confidence.LOW)) {
            return Reason.THROUGHPUT_EVIDENCE_UNKNOWN;
        }
        if (throughput.predictionErrorPermille() < 0
                || throughput.predictionErrorPermille()
                > NORMAL_MAX_PREDICTION_ERROR_PERMILLE) {
            return Reason.PREDICTION_ERROR;
        }
        if (input.mediaBitrateBitsPerSecond() <= 0) return Reason.MEDIA_BITRATE_UNKNOWN;
        double effectiveRatio = ratio(
                throughput.effectiveBitsPerSecond(), input.mediaBitrateBitsPerSecond());
        double shortRatio = ratio(
                throughput.shortBitsPerSecond(), input.mediaBitrateBitsPerSecond());
        if (effectiveRatio < NORMAL_RATIO || shortRatio < NORMAL_SHORT_RATIO) {
            return Reason.THROUGHPUT_MARGIN;
        }
        if (materiallyBelow(
                throughput.shortBitsPerSecond(), throughput.longBitsPerSecond(), 80)) {
            return Reason.SHORT_WINDOW_DECLINE;
        }
        if (holdingFast && !fastHoldEligible(input)) return Reason.FAST_FALLBACK;
        return null;
    }

    private boolean fastEligible(Inputs input, boolean holdingFast) {
        if (!supportsFast(input.route()) || input.bufferedMs() < FAST_BUFFER_MS) return false;
        SystemEvidence system = input.system();
        if (!system.explicitlySafe()
                || system.transport() == PlaybackAutoContext.NetworkTransport.VPN) return false;
        TrendEvidence trend = TrendEvidence.from(input.trend(), input.nowElapsedMs());
        if (!trend.usable()
                || !trend.confidenceAtLeast(ForwardBufferTrend.Confidence.MEDIUM)
                || trend.slopeMsPerSecond() < 0) return false;

        ThroughputEvidence throughput = input.throughput();
        if (!throughput.usable()
                || throughput.pathTrust() != ExoThroughputPathPolicy.Trust.TRUSTED
                || !confidenceAtLeast(
                throughput.pathConfidence(), PlaybackAutoContext.Confidence.MEDIUM)
                || !confidenceAtLeast(
                throughput.confidence(), PlaybackAutoContext.Confidence.MEDIUM)
                || throughput.longSampleCount() < ExoThroughputEstimator.MIN_UPGRADE_SAMPLES
                || throughput.longWindowMs() < ExoThroughputEstimator.MIN_UPGRADE_WINDOW_MS
                || throughput.predictionErrorPermille() < 0
                || throughput.predictionErrorPermille()
                > ExoThroughputEstimator.MAX_UPGRADE_ERROR_PERMILLE
                || throughput.preloadContended() && !(holdingFast && input.preloadActive())
                || input.mediaBitrateBitsPerSecond() <= 0) return false;
        return ratio(throughput.effectiveBitsPerSecond(), input.mediaBitrateBitsPerSecond())
                >= FAST_RATIO
                && ratio(throughput.shortBitsPerSecond(), input.mediaBitrateBitsPerSecond())
                >= FAST_WINDOW_RATIO
                && ratio(throughput.longBitsPerSecond(), input.mediaBitrateBitsPerSecond())
                >= FAST_WINDOW_RATIO
                && !materiallyBelow(
                throughput.shortBitsPerSecond(), throughput.longBitsPerSecond(), 80);
    }

    private boolean fastHoldEligible(Inputs input) {
        if (!supportsFast(input.route())
                || input.bufferedMs() < FAST_FALLBACK_BUFFER_MS
                || !input.system().explicitlySafe()
                || input.system().transport() == PlaybackAutoContext.NetworkTransport.VPN) {
            return false;
        }
        TrendEvidence trend = TrendEvidence.from(input.trend(), input.nowElapsedMs());
        if (!trend.usable() || trend.slopeMsPerSecond() < NORMAL_SLOPE_MS_PER_SECOND) {
            return false;
        }
        ThroughputEvidence throughput = input.throughput();
        if (!throughput.usable()
                || throughput.pathTrust() != ExoThroughputPathPolicy.Trust.TRUSTED
                || !confidenceAtLeast(
                throughput.pathConfidence(), PlaybackAutoContext.Confidence.MEDIUM)
                || throughput.predictionErrorPermille() < 0
                || throughput.predictionErrorPermille()
                > NORMAL_MAX_PREDICTION_ERROR_PERMILLE
                || throughput.preloadContended() && !input.preloadActive()
                || materiallyBelow(
                throughput.shortBitsPerSecond(), throughput.longBitsPerSecond(), 80)
                || input.mediaBitrateBitsPerSecond() <= 0) return false;
        return ratio(throughput.effectiveBitsPerSecond(), input.mediaBitrateBitsPerSecond())
                >= FAST_FALLBACK_RATIO
                && ratio(throughput.shortBitsPerSecond(), input.mediaBitrateBitsPerSecond())
                >= FAST_FALLBACK_SHORT_RATIO;
    }

    private void pause(long nowMs, long delayMs, Reason reason) {
        mode = Mode.PAUSED;
        resumeAfterMs = Math.max(resumeAfterMs, nowMs + Math.max(0, delayMs));
>>>>>>> upstream/beta
        fastBlockedUntilMs = Math.max(fastBlockedUntilMs, nowMs + FAST_COOLDOWN_MS);
    }

    private void pause(long nowMs, long delayMs) {
        mode = Mode.PAUSED;
        resumeAfterMs = Math.max(resumeAfterMs, nowMs + delayMs);
        stableSinceMs = Long.MIN_VALUE;
    }

    private Decision decision() {
        return switch (mode) {
            case PAUSED -> new Decision(PAUSED_THREADS, 0, "paused");
            case DEGRADED -> new Decision(NORMAL_THREADS, DEGRADED_DURATION_MS, "degraded");
            case FAST -> new Decision(FAST_THREADS, FAST_DURATION_MS, "fast");
            default -> new Decision(NORMAL_THREADS, NORMAL_DURATION_MS, "normal");
        };
    }

    private static boolean supportsFast(PlaybackRoute route) {
        return route == PlaybackRoute.DIRECT_REMOTE_HTTP || route == PlaybackRoute.APP_LOCAL_SERVICE;
    }

    private static double ratio(long bandwidthEstimate, long mediaBitrate) {
        if (bandwidthEstimate <= 0 || mediaBitrate <= 0) return 0;
        return (double) bandwidthEstimate / (double) mediaBitrate;
    }

    record Decision(int threads, long durationMs, String mode) {

        boolean enabled() {
            return threads > 0 && durationMs > 0;
        }
    }

    private enum Mode {
        PAUSED,
        DEGRADED,
        NORMAL,
        FAST
    }
}
