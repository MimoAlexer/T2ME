package dev.t2me;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveLimiterTest {
    private static final long MIB = 1024L * 1024L;
    private static final long MAX_HEAP = 8L * 1024L * MIB;
    private static final long HEALTHY_HEADROOM = 4L * 1024L * MIB;
    private static final AdaptiveLimiter.Settings DEFAULTS = settings(32);

    @Test
    void rampsOnlyOnFreshHealthyActiveTicksAndRespectsCeiling() {
        AdaptiveLimiter limiter = new AdaptiveLimiter();
        assertEquals(4, decide(limiter).maxInFlight());
        for (int tick = 0; tick < 100; tick++) {
            limiter.recordTick(5_000_000L);
            assertEquals(4, limiter.decide(DEFAULTS, HEALTHY_HEADROOM, MAX_HEAP, 0, false).maxInFlight());
        }
        for (int repeat = 0; repeat < 100; repeat++) {
            assertEquals(4, decide(limiter).maxInFlight());
        }
        for (int tick = 0; tick < 9; tick++) {
            assertEquals(4, tick(limiter, 5).maxInFlight());
        }
        assertEquals(5, tick(limiter, 5).maxInFlight());
        for (int tick = 0; tick < 200; tick++) {
            assertTrue(tick(limiter, 5).maxInFlight() <= 32);
        }
        assertEquals(32, decide(limiter).maxInFlight());
    }

    @Test
    void supportsLargerPipelinesWithoutAnArtificialProcessorCap() {
        AdaptiveLimiter limiter = new AdaptiveLimiter();
        AdaptiveLimiter.Settings maximum = settings(256);
        AdaptiveLimiter.Decision decision = null;
        for (int tick = 0; tick < 500; tick++) {
            limiter.recordTick(5_000_000L);
            decision = limiter.decide(maximum, HEALTHY_HEADROOM, MAX_HEAP, 0, true);
            assertTrue(decision.maxInFlight() <= 256);
        }
        assertEquals(256, decision.maxInFlight());
    }

    @Test
    void immediatelyStopsOnAnIsolatedLongTickWithoutWaitingForEwma() {
        AdaptiveLimiter limiter = new AdaptiveLimiter();
        for (int tick = 0; tick < 100; tick++) {
            tick(limiter, 5);
        }
        assertEquals(0, tick(limiter, 60).maxInFlight());
        assertTrue(limiter.tickEwmaMillis() < DEFAULTS.hardStopTickMillis());
        for (int tick = 0; tick < 19; tick++) {
            assertEquals(0, tick(limiter, 5).maxInFlight());
        }
        assertEquals(1, tick(limiter, 5).maxInFlight());
    }

    @Test
    void startupTicksAlsoApplyHardStopAndRecoveryNeedsNewSamples() {
        AdaptiveLimiter limiter = new AdaptiveLimiter();
        assertEquals(0, tick(limiter, 60).maxInFlight());
        for (int repeat = 0; repeat < 100; repeat++) {
            assertEquals(0, decide(limiter).maxInFlight());
        }
        // EWMA needs to cool before the twenty-tick recovery interval can start.
        for (int tick = 0; tick < 19; tick++) {
            assertEquals(0, tick(limiter, 5).maxInFlight());
        }
        for (int tick = 0; tick < 30; tick++) {
            tick(limiter, 5);
        }
        assertTrue(decide(limiter).maxInFlight() > 0);
    }

    @Test
    void softPressureHalvesWindowAtMostOncePerFiveTicks() {
        AdaptiveLimiter limiter = warmedLimiter();
        assertEquals(16, tick(limiter, 46).maxInFlight());
        for (int repeat = 0; repeat < 50; repeat++) {
            assertEquals(16, decide(limiter).maxInFlight());
        }
        for (int tick = 0; tick < 4; tick++) {
            assertEquals(16, tick(limiter, 46).maxInFlight());
        }
        assertEquals(8, tick(limiter, 46).maxInFlight());
        for (int tick = 0; tick < 100; tick++) {
            tick(limiter, 46);
        }
        assertEquals(1, decide(limiter).maxInFlight());
    }

    @Test
    void doesNotGrowInTheRecoveryBand() {
        AdaptiveLimiter limiter = new AdaptiveLimiter();
        for (int tick = 0; tick < 100; tick++) {
            assertEquals(4, tick(limiter, 42).maxInFlight());
        }
    }

    @Test
    void heapPressureHasHysteresisAndResumesAtMinimumWindow() {
        AdaptiveLimiter limiter = warmedLimiter();
        assertEquals(0, limiter.decide(DEFAULTS, 1023L * MIB, MAX_HEAP, 0, true).maxInFlight());
        assertEquals(0, limiter.decide(DEFAULTS, 1024L * MIB, MAX_HEAP, 0, true).maxInFlight());
        assertEquals(0, limiter.decide(DEFAULTS, 1151L * MIB, MAX_HEAP, 0, true).maxInFlight());
        assertEquals(1, limiter.decide(DEFAULTS, 1152L * MIB, MAX_HEAP, 0, true).maxInFlight());
    }

    @Test
    void heapReserveScalesDownForSmallServers() {
        AdaptiveLimiter limiter = new AdaptiveLimiter();
        long smallHeap = 1024L * MIB;
        assertEquals(4, limiter.decide(DEFAULTS, 512L * MIB, smallHeap, 0, true).maxInFlight());
        assertEquals(0, limiter.decide(DEFAULTS, 255L * MIB, smallHeap, 0, true).maxInFlight());
        assertEquals(0, limiter.decide(DEFAULTS, 280L * MIB, smallHeap, 0, true).maxInFlight());
        assertEquals(1, limiter.decide(DEFAULTS, 288L * MIB, smallHeap, 0, true).maxInFlight());
    }

    @Test
    void playersHalveAdmissionAndSingleRequestConfigsNeverStarve() {
        AdaptiveLimiter limiter = warmedLimiter();
        assertEquals(16, limiter.decide(DEFAULTS, HEALTHY_HEADROOM, MAX_HEAP, 1, true).maxInFlight());
        assertEquals(32, limiter.decide(
                new AdaptiveLimiter.Settings(32, 45, 55, 1024L * MIB, false),
                HEALTHY_HEADROOM, MAX_HEAP, 1, true
        ).maxInFlight());
        assertEquals(1, limiter.decide(settings(1), HEALTHY_HEADROOM, MAX_HEAP, 1, true).maxInFlight());
    }

    @Test
    void loweredConfigCeilingTakesEffectImmediately() {
        AdaptiveLimiter limiter = warmedLimiter();
        assertEquals(3, limiter.decide(settings(3), HEALTHY_HEADROOM, MAX_HEAP, 0, true).maxInFlight());
        assertEquals(3, decide(limiter).maxInFlight());
    }

    @Test
    void reversedThresholdsPreserveHardStop() {
        AdaptiveLimiter limiter = new AdaptiveLimiter();
        limiter.recordTick(60_000_000L);
        assertEquals(0, limiter.decide(
                new AdaptiveLimiter.Settings(32, 100, 55, 1024L * MIB, true),
                HEALTHY_HEADROOM, MAX_HEAP, 0, true
        ).maxInFlight());
    }

    @Test
    void resettingAdmissionPreservesHealthButFullResetClearsIt() {
        AdaptiveLimiter limiter = warmedLimiter();
        limiter.resetAdmission();
        assertEquals(4, decide(limiter).maxInFlight());
        assertEquals(0, tick(limiter, 100).maxInFlight());
        limiter.resetAdmission();
        assertEquals(0, decide(limiter).maxInFlight());
        limiter.reset();
        assertEquals(0.0D, limiter.tickEwmaMillis());
        assertEquals(0.0D, limiter.tickPeakMillis());
        assertEquals(4, decide(limiter).maxInFlight());
    }

    @Test
    void validatesBoundedSettingsAndIgnoresNegativeDurations() {
        assertThrows(IllegalArgumentException.class, () -> settings(0));
        assertThrows(IllegalArgumentException.class, () -> settings(257));
        AdaptiveLimiter limiter = new AdaptiveLimiter();
        limiter.recordTick(-1L);
        assertEquals(0.0D, limiter.tickEwmaMillis());
        assertThrows(IllegalArgumentException.class,
                () -> limiter.decide(DEFAULTS, 0L, 0L, 0, true));
    }

    private static AdaptiveLimiter warmedLimiter() {
        AdaptiveLimiter limiter = new AdaptiveLimiter();
        for (int tick = 0; tick < 100; tick++) {
            tick(limiter, 5);
        }
        assertEquals(32, decide(limiter).maxInFlight());
        return limiter;
    }

    private static AdaptiveLimiter.Decision tick(AdaptiveLimiter limiter, long millis) {
        limiter.recordTick(millis * 1_000_000L);
        return decide(limiter);
    }

    private static AdaptiveLimiter.Decision decide(AdaptiveLimiter limiter) {
        return limiter.decide(DEFAULTS, HEALTHY_HEADROOM, MAX_HEAP, 0, true);
    }

    private static AdaptiveLimiter.Settings settings(int maximum) {
        return new AdaptiveLimiter.Settings(maximum, 45, 55, 1024L * MIB, true);
    }
}
