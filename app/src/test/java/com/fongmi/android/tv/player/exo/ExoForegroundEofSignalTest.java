package com.fongmi.android.tv.player.exo;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ExoForegroundEofSignalTest {

    @After
    public void clearSignal() {
        // Process-level state: without this, cases would leak into one another.
        ExoForegroundEofSignal.reset();
    }

    @Test
    public void neverObservedRecoveryReportsUnreachableAge() {
        ExoForegroundEofSignal.reset();
        // Must be far above any yield window. Returning 0 here would make a healthy session look
        // like a recovering one and suspend preload permanently.
        assertEquals(Long.MAX_VALUE, ExoForegroundEofSignal.ageMs(0));
        assertEquals(Long.MAX_VALUE, ExoForegroundEofSignal.ageMs(1_000_000));
    }

    @Test
    public void ageGrowsFromTheRecordedMoment() {
        ExoForegroundEofSignal.reset();
        ExoForegroundEofSignal.record(10_000);

        assertEquals(0, ExoForegroundEofSignal.ageMs(10_000));
        assertEquals(5_000, ExoForegroundEofSignal.ageMs(15_000));
        assertEquals(15_000, ExoForegroundEofSignal.ageMs(25_000));
    }

    @Test
    public void laterRecoveryReplacesTheEarlierOne() {
        ExoForegroundEofSignal.reset();
        ExoForegroundEofSignal.record(10_000);
        ExoForegroundEofSignal.record(40_000);

        // A second burst must restart the window, not inherit the first one's age.
        assertEquals(0, ExoForegroundEofSignal.ageMs(40_000));
        assertEquals(1_000, ExoForegroundEofSignal.ageMs(41_000));
    }

    @Test
    public void clockGoingBackwardsIsClampedToZero() {
        ExoForegroundEofSignal.reset();
        ExoForegroundEofSignal.record(50_000);

        // elapsedRealtime is monotonic in practice, but a negative age must never be reported as a
        // value that could compare below a yield window by accident.
        assertEquals(0, ExoForegroundEofSignal.ageMs(49_000));
        assertTrue(ExoForegroundEofSignal.ageMs(49_000) >= 0);
    }

    @Test
    public void resetReturnsToUnobserved() {
        ExoForegroundEofSignal.record(10_000);
        ExoForegroundEofSignal.reset();

        assertEquals(Long.MAX_VALUE, ExoForegroundEofSignal.ageMs(10_000));
    }
}
