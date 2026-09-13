package com.fongmi.android.tv.player.exo;

import androidx.media3.common.C;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AutoLoadControlTest {

    @Test
    public void seekRecoveryUsesMedia3UserActionThresholdWithoutWeakeningRebuffer() {
        assertEquals(ExoPlaybackThresholdCoordinator.Episode.REBUFFER,
                AutoLoadControl.playbackEpisode(true, true));
        assertEquals(ExoPlaybackThresholdCoordinator.Episode.SEEK,
                AutoLoadControl.playbackEpisode(false, true));
        assertEquals(ExoPlaybackThresholdCoordinator.Episode.STARTUP,
                AutoLoadControl.playbackEpisode(false, false));

        ExoPlaybackThresholdPolicy.Decision policy = ExoPlaybackThresholdPolicy.resolve(
                ExoPlaybackThresholdPolicy.Inputs.unknown());
        ExoPlaybackThresholdCoordinator.Selection criticalSeek =
                new ExoPlaybackThresholdCoordinator.Selection(
                        com.fongmi.android.tv.player.PlaybackAutoContext.SessionToken.none(),
                        ExoPlaybackThresholdCoordinator.Episode.SEEK,
                        8_000,
                        15_000,
                        policy,
                        true,
                        ExoPlaybackThresholdCoordinator.Action.LOCK);
        ExoPlaybackThresholdCoordinator.Selection conservativeRebuffer =
                new ExoPlaybackThresholdCoordinator.Selection(
                        com.fongmi.android.tv.player.PlaybackAutoContext.SessionToken.none(),
                        ExoPlaybackThresholdCoordinator.Episode.REBUFFER,
                        8_000,
                        15_000,
                        policy,
                        true,
                        ExoPlaybackThresholdCoordinator.Action.LOCK);

        assertEquals(1_000, criticalSeek.thresholdMs());
        assertEquals(15_000, conservativeRebuffer.thresholdMs());
    }

    @Test
    public void seekMarkerIsSessionBoundAndExpires() {
        ExoPlaybackThresholdCoordinator coordinator =
                new ExoPlaybackThresholdCoordinator();
        com.fongmi.android.tv.player.PlaybackAutoContext.SessionToken session =
                new com.fongmi.android.tv.player.PlaybackAutoContext.SessionToken(
                        "p-seek-1", 1);

        coordinator.markSeek(session, 10_000);

        assertTrue(coordinator.isSeekPending(session, 10_001));
        assertFalse(coordinator.isSeekPending(
                com.fongmi.android.tv.player.PlaybackAutoContext.SessionToken.none(),
                10_001));
        assertFalse(coordinator.isSeekPending(
                session,
                10_000 + ExoPlaybackThresholdCoordinator.SEEK_RECOVERY_TIMEOUT_MS + 1));
    }

    @Test
    public void usesAdaptiveVodRebufferThreshold() {
        assertFalse(AutoLoadControl.reachedAdaptiveThreshold(2_999_000, 1f, C.TIME_UNSET, 3_000));
        assertTrue(AutoLoadControl.reachedAdaptiveThreshold(3_000_000, 1f, C.TIME_UNSET, 3_000));
        assertFalse(AutoLoadControl.reachedAdaptiveThreshold(7_999_000, 1f, C.TIME_UNSET, 8_000));
        assertTrue(AutoLoadControl.reachedAdaptiveThreshold(8_000_000, 1f, C.TIME_UNSET, 8_000));
    }

    @Test
    public void playbackSpeedUsesPlayoutDuration() {
        assertFalse(AutoLoadControl.reachedAdaptiveThreshold(3_999_000, 2f, C.TIME_UNSET, 2_000));
        assertTrue(AutoLoadControl.reachedAdaptiveThreshold(4_000_000, 2f, C.TIME_UNSET, 2_000));
    }

    @Test
    public void liveOffsetKeepsMedia3HalfOffsetLimit() {
        assertFalse(AutoLoadControl.reachedAdaptiveThreshold(1_999_000, 1f, 4_000_000, 8_000));
        assertTrue(AutoLoadControl.reachedAdaptiveThreshold(2_000_000, 1f, 4_000_000, 8_000));
    }
}
