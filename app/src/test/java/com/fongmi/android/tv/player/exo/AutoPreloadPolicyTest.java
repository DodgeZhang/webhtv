package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.player.PlaybackRoute;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AutoPreloadPolicyTest {

    @Test
    public void startsWithConservativeSingleThreadBaseline() {
        AutoPreloadPolicy.Decision decision = evaluate(new AutoPreloadPolicy(), 0, PlaybackRoute.DIRECT_REMOTE_HTTP, 8_000, 10, 0, 0, false);
        assertEquals(1, decision.threads());
        assertEquals(20_000, decision.durationMs());
        assertTrue(decision.enabled());
    }

    @Test
    public void moderateHeadroomUsesShortDegradedRange() {
        AutoPreloadPolicy.Decision decision = evaluate(new AutoPreloadPolicy(), 0, PlaybackRoute.DIRECT_REMOTE_HTTP, 10_000, 10, 15, 0, false);
        assertEquals(1, decision.threads());
        assertEquals(10_000, decision.durationMs());
    }

    @Test
    public void disruptionPausesThenResumesSingleThread() {
        AutoPreloadPolicy policy = new AutoPreloadPolicy();
        policy.disrupt(1_000);
        assertFalse(evaluate(policy, 10_999, PlaybackRoute.DIRECT_REMOTE_HTTP, 20_000, 10, 30, 0, false).enabled());
        assertEquals(1, evaluate(policy, 11_000, PlaybackRoute.DIRECT_REMOTE_HTTP, 20_000, 10, 30, 0, false).threads());
    }

    @Test
    public void fastModeRequiresSustainedBufferAndBandwidthHeadroom() {
        AutoPreloadPolicy policy = new AutoPreloadPolicy();
        assertEquals(1, evaluate(policy, 0, PlaybackRoute.DIRECT_REMOTE_HTTP, 20_000, 10, 30, 0, false).threads());
        assertEquals(1, evaluate(policy, 29_999, PlaybackRoute.DIRECT_REMOTE_HTTP, 20_000, 10, 30, 0, false).threads());
        AutoPreloadPolicy.Decision fast = evaluate(policy, 30_000, PlaybackRoute.DIRECT_REMOTE_HTTP, 20_000, 10, 30, 0, false);
        assertEquals(2, fast.threads());
        assertEquals(30_000, fast.durationMs());
    }

    @Test
<<<<<<< HEAD
    public void externalLoopbackNeverExceedsOneThread() {
=======
    public void weakEffectiveThroughputImmediatelyPauses() {
        AutoPreloadPolicy.Decision decision = new AutoPreloadPolicy().evaluate(
                inputs(0, PlaybackRoute.DIRECT_REMOTE_HTTP, 20_000,
                        trustedThroughput(0, 11, 11, 15, 200, false),
                        safeSystem(), stableTrend(0, 20_000), false, false));

        assertFalse(decision.enabled());
        assertEquals("throughput-deficit", decision.reason());
    }

    @Test
    public void shortWindowCollapseImmediatelyPauses() {
        AutoPreloadPolicy.Decision decision = new AutoPreloadPolicy().evaluate(
                inputs(0, PlaybackRoute.DIRECT_REMOTE_HTTP, 20_000,
                        trustedThroughput(0, 25, 9, 30, 200, false),
                        safeSystem(), stableTrend(0, 20_000), false, false));

        assertFalse(decision.enabled());
        assertEquals("short-window-deficit", decision.reason());
    }

    @Test
    public void shortWindowDeclineCancelsFastBudgetBeforePlaybackRisk() {
        AutoPreloadPolicy policy = fastPolicy();

        AutoPreloadPolicy.Decision decision = policy.evaluate(
                inputs(35_000, PlaybackRoute.DIRECT_REMOTE_HTTP, 20_000,
                        trustedThroughput(35_000, 30, 20, 30, 200, false),
                        safeSystem(), stableTrend(35_000, 20_000), false, true));

        assertEquals(1, decision.threads());
        assertEquals(10_000, decision.durationMs());
        assertEquals("short-window-decline", decision.reason());
    }

    @Test
    public void dangerousTimeToEmptyImmediatelyPauses() {
        ForwardBufferTrend.Snapshot draining = trend(0, -1_000, 10_000,
                ForwardBufferTrend.Confidence.MEDIUM);

        AutoPreloadPolicy.Decision decision = new AutoPreloadPolicy().evaluate(
                inputs(0, PlaybackRoute.DIRECT_REMOTE_HTTP, 10_000,
                        trustedThroughput(0, 30, 30, 30, 200, false),
                        safeSystem(), draining, false, false));

        assertFalse(decision.enabled());
        assertEquals("time-to-empty", decision.reason());
    }

    @Test
    public void unavailableAndUnvalidatedNetworksPause() {
        AutoPreloadPolicy.Decision unavailable = new AutoPreloadPolicy().evaluate(
                withSystem(0, system(false, true, false, false,
                        PlaybackAutoContext.DataSaverState.DISABLED,
                        PlaybackAutoContext.PowerState.NORMAL,
                        PlaybackAutoContext.ThermalState.NOMINAL,
                        PlaybackAutoContext.NetworkTransport.WIFI)));
        AutoPreloadPolicy.Decision unvalidated = new AutoPreloadPolicy().evaluate(
                withSystem(0, system(true, false, false, false,
                        PlaybackAutoContext.DataSaverState.DISABLED,
                        PlaybackAutoContext.PowerState.NORMAL,
                        PlaybackAutoContext.ThermalState.NOMINAL,
                        PlaybackAutoContext.NetworkTransport.WIFI)));

        assertEquals("network-unavailable", unavailable.reason());
        assertEquals("network-unvalidated", unvalidated.reason());
        assertFalse(unavailable.enabled());
        assertFalse(unvalidated.enabled());
    }

    @Test
    public void meteredAndRoamingDegradeWhileDataSaverPauses() {
        AutoPreloadPolicy.Decision metered = new AutoPreloadPolicy().evaluate(
                withSystem(0, system(true, true, true, false,
                        PlaybackAutoContext.DataSaverState.DISABLED,
                        PlaybackAutoContext.PowerState.NORMAL,
                        PlaybackAutoContext.ThermalState.NOMINAL,
                        PlaybackAutoContext.NetworkTransport.CELLULAR)));
        AutoPreloadPolicy.Decision roaming = new AutoPreloadPolicy().evaluate(
                withSystem(0, system(true, true, false, true,
                        PlaybackAutoContext.DataSaverState.DISABLED,
                        PlaybackAutoContext.PowerState.NORMAL,
                        PlaybackAutoContext.ThermalState.NOMINAL,
                        PlaybackAutoContext.NetworkTransport.CELLULAR)));
        AutoPreloadPolicy.Decision dataSaver = new AutoPreloadPolicy().evaluate(
                withSystem(0, system(true, true, false, false,
                        PlaybackAutoContext.DataSaverState.ENABLED,
                        PlaybackAutoContext.PowerState.NORMAL,
                        PlaybackAutoContext.ThermalState.NOMINAL,
                        PlaybackAutoContext.NetworkTransport.WIFI)));

        assertEquals("metered", metered.reason());
        assertEquals("roaming", roaming.reason());
        assertEquals("data-saver", dataSaver.reason());
        assertTrue(metered.enabled());
        assertTrue(roaming.enabled());
        assertEquals(1, metered.threads());
        assertEquals(1, roaming.threads());
        assertEquals(10_000, metered.durationMs());
        assertEquals(10_000, roaming.durationMs());
        assertFalse(dataSaver.enabled());
    }

    @Test
    public void powerSaverAndSevereThermalPause() {
        AutoPreloadPolicy.Decision power = new AutoPreloadPolicy().evaluate(
                withSystem(0, system(true, true, false, false,
                        PlaybackAutoContext.DataSaverState.DISABLED,
                        PlaybackAutoContext.PowerState.POWER_SAVE,
                        PlaybackAutoContext.ThermalState.NOMINAL,
                        PlaybackAutoContext.NetworkTransport.WIFI)));
        AutoPreloadPolicy.Decision thermal = new AutoPreloadPolicy().evaluate(
                withSystem(0, system(true, true, false, false,
                        PlaybackAutoContext.DataSaverState.DISABLED,
                        PlaybackAutoContext.PowerState.NORMAL,
                        PlaybackAutoContext.ThermalState.SEVERE,
                        PlaybackAutoContext.NetworkTransport.WIFI)));

        assertEquals("power-save", power.reason());
        assertEquals("thermal-pressure", thermal.reason());
        assertFalse(power.enabled());
        assertFalse(thermal.enabled());
    }

    @Test
    public void moderateThermalUsesShortSingleThreadRange() {
        AutoPreloadPolicy.Decision decision = new AutoPreloadPolicy().evaluate(
                withSystem(0, system(true, true, false, false,
                        PlaybackAutoContext.DataSaverState.DISABLED,
                        PlaybackAutoContext.PowerState.NORMAL,
                        PlaybackAutoContext.ThermalState.MODERATE,
                        PlaybackAutoContext.NetworkTransport.WIFI)));

        assertEquals(1, decision.threads());
        assertEquals(10_000, decision.durationMs());
        assertEquals("thermal-moderate", decision.reason());
    }

    @Test
    public void memoryPressureImmediatelyPauses() {
        AutoPreloadPolicy.Inputs safe = safeInputs(0);
        AutoPreloadPolicy.Inputs pressured = new AutoPreloadPolicy.Inputs(
                safe.session(), safe.sessionMatches(), safe.route(), safe.bufferedMs(),
                safe.mediaBitrateBitsPerSecond(), safe.rebufferCount(), safe.loading(),
                safe.trend(), safe.throughput(), safe.system(), true, false, 0);

        AutoPreloadPolicy.Decision decision = new AutoPreloadPolicy().evaluate(pressured);

        assertFalse(decision.enabled());
        assertEquals("memory-pressure", decision.reason());
    }

    @Test
    public void vpnAndLimitedAppServicePathNeverReachFastMode() {
        AutoPreloadPolicy vpn = new AutoPreloadPolicy();
        AutoPreloadPolicy.SystemEvidence vpnSystem = system(
                true, true, false, false,
                PlaybackAutoContext.DataSaverState.DISABLED,
                PlaybackAutoContext.PowerState.NORMAL,
                PlaybackAutoContext.ThermalState.NOMINAL,
                PlaybackAutoContext.NetworkTransport.VPN);
        vpn.evaluate(inputs(0, PlaybackRoute.DIRECT_REMOTE_HTTP, 20_000,
                trustedThroughput(0, 40, 40, 40, 100, false),
                vpnSystem, stableTrend(0, 20_000), false, false));
        AutoPreloadPolicy.Decision vpnLater = vpn.evaluate(inputs(
                60_000, PlaybackRoute.DIRECT_REMOTE_HTTP, 20_000,
                trustedThroughput(60_000, 40, 40, 40, 100, false),
                vpnSystem, stableTrend(60_000, 20_000), false, false));

        AutoPreloadPolicy app = new AutoPreloadPolicy();
        AutoPreloadPolicy.ThroughputEvidence limited = throughput(
                0, 50, 50, 50, 100, false,
                ExoThroughputPathPolicy.Trust.LIMITED);
        app.evaluate(inputs(0, PlaybackRoute.APP_LOCAL_SERVICE, 20_000,
                limited, safeSystem(), stableTrend(0, 20_000), false, false));
        AutoPreloadPolicy.Decision appLater = app.evaluate(inputs(
                60_000, PlaybackRoute.APP_LOCAL_SERVICE, 20_000,
                throughput(60_000, 50, 50, 50, 100, false,
                        ExoThroughputPathPolicy.Trust.LIMITED),
                safeSystem(), stableTrend(60_000, 20_000), false, false));

        assertEquals(1, vpnLater.threads());
        assertEquals(10_000, vpnLater.durationMs());
        assertEquals(1, appLater.threads());
        assertEquals(10_000, appLater.durationMs());
    }

    @Test
    public void staleThroughputAndHighPredictionErrorStayConservative() {
        AutoPreloadPolicy.ThroughputEvidence stale = trustedThroughput(
                0, 30, 30, 30, 200, false);
        AutoPreloadPolicy.Decision staleDecision = new AutoPreloadPolicy().evaluate(
                inputs(65_001, PlaybackRoute.DIRECT_REMOTE_HTTP, 20_000,
                        new AutoPreloadPolicy.ThroughputEvidence(
                                false,
                                stale.effectiveBitsPerSecond(),
                                stale.shortBitsPerSecond(),
                                stale.longBitsPerSecond(),
                                stale.longSampleCount(),
                                stale.longWindowMs(),
                                stale.predictionErrorPermille(),
                                stale.confidence(),
                                stale.pathTrust(),
                                stale.pathConfidence(),
                                false,
                                0),
                        safeSystem(), stableTrend(65_001, 20_000), false, false));
        AutoPreloadPolicy.Decision inaccurate = new AutoPreloadPolicy().evaluate(
                inputs(0, PlaybackRoute.DIRECT_REMOTE_HTTP, 20_000,
                        trustedThroughput(0, 30, 30, 30, 1_000, false),
                        safeSystem(), stableTrend(0, 20_000), false, false));

        assertEquals("throughput-evidence-unknown", staleDecision.reason());
        assertEquals("prediction-error", inaccurate.reason());
        assertEquals(10_000, staleDecision.durationMs());
        assertEquals(10_000, inaccurate.durationMs());
    }

    @Test
    public void contentionBlocksPromotionButOwnFastTaskDoesNotOscillate() {
        AutoPreloadPolicy blocked = new AutoPreloadPolicy();
        blocked.evaluate(inputs(0, PlaybackRoute.DIRECT_REMOTE_HTTP, 20_000,
                trustedThroughput(0, 30, 30, 30, 200, true),
                safeSystem(), stableTrend(0, 20_000), false, false));
        AutoPreloadPolicy.Decision blockedLater = blocked.evaluate(inputs(
                60_000, PlaybackRoute.DIRECT_REMOTE_HTTP, 20_000,
                trustedThroughput(60_000, 30, 30, 30, 200, true),
                safeSystem(), stableTrend(60_000, 20_000), false, false));

        AutoPreloadPolicy fast = fastPolicy();
        AutoPreloadPolicy.Decision held = fast.evaluate(inputs(
                35_000, PlaybackRoute.DIRECT_REMOTE_HTTP, 20_000,
                trustedThroughput(35_000, 30, 30, 30, 200, true),
                safeSystem(), stableTrend(35_000, 20_000), false, true));

        assertEquals(1, blockedLater.threads());
        assertEquals(10_000, blockedLater.durationMs());
        assertEquals(2, held.threads());
        assertEquals("fast", held.mode());
    }

    @Test
    public void disruptionPausesThenRecoversWithFastCooldown() {
        AutoPreloadPolicy policy = fastPolicy();
        policy.disrupt(31_000, AutoPreloadPolicy.Reason.NETWORK_CHANGED);

        assertFalse(policy.evaluate(safeInputs(40_999)).enabled());
        assertEquals(1, policy.evaluate(safeInputs(41_000)).threads());
        assertEquals(1, policy.evaluate(safeInputs(71_000)).threads());
        assertEquals(2, policy.evaluate(safeInputs(91_000)).threads());
    }

    @Test
    public void rebufferIncreaseImmediatelyPauses() {
        AutoPreloadPolicy.Inputs safe = safeInputs(0);
        AutoPreloadPolicy.Inputs rebuffered = new AutoPreloadPolicy.Inputs(
                safe.session(), safe.sessionMatches(), safe.route(), safe.bufferedMs(),
                safe.mediaBitrateBitsPerSecond(), 1, safe.loading(), safe.trend(),
                safe.throughput(), safe.system(), false, false, 0);

        AutoPreloadPolicy.Decision decision = new AutoPreloadPolicy().evaluate(rebuffered);

        assertFalse(decision.enabled());
        assertEquals("rebuffer", decision.reason());
    }

    @Test
    public void unknownAppProxyMediaKeepsPreloadPausedUntilForegroundReserveRecovers() {
>>>>>>> upstream/beta
        AutoPreloadPolicy policy = new AutoPreloadPolicy();
        evaluate(policy, 0, PlaybackRoute.EXTERNAL_LOOPBACK_PROXY, 30_000, 10, 50, 0, false);
        assertEquals(1, evaluate(policy, 60_000, PlaybackRoute.EXTERNAL_LOOPBACK_PROXY, 30_000, 10, 50, 0, false).threads());
    }

    @Test
    public void weakBandwidthImmediatelyPausesPreload() {
        AutoPreloadPolicy policy = new AutoPreloadPolicy();
        assertFalse(evaluate(policy, 0, PlaybackRoute.DIRECT_REMOTE_HTTP, 20_000, 10, 11, 0, false).enabled());
    }

    @Test
    public void fastModeFallsBackBeforePlaybackIsAtRisk() {
        AutoPreloadPolicy policy = new AutoPreloadPolicy();
        evaluate(policy, 0, PlaybackRoute.DIRECT_REMOTE_HTTP, 20_000, 10, 30, 0, false);
        assertEquals(2, evaluate(policy, 30_000, PlaybackRoute.DIRECT_REMOTE_HTTP, 20_000, 10, 30, 0, false).threads());
        assertEquals(1, evaluate(policy, 35_000, PlaybackRoute.DIRECT_REMOTE_HTTP, 11_999, 10, 30, 0, false).threads());
    }

    private static AutoPreloadPolicy.Decision evaluate(AutoPreloadPolicy policy, long nowMs, PlaybackRoute route, long bufferedMs, long bitrateMbps, long bandwidthMbps, int rebufferCount, boolean loading) {
        return policy.evaluate(nowMs, route, bufferedMs, bitrateMbps * 1_000_000, bandwidthMbps * 1_000_000, rebufferCount, loading);
    }
}
