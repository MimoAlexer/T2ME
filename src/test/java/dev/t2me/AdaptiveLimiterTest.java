package dev.t2me;

import com.electronwill.nightconfig.core.CommentedConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveLimiterTest {
    @BeforeEach
    void loadTestConfig() {
        T2MEConfig.SPEC.setConfig(CommentedConfig.inMemory());
        T2MEConfig.MIN_IN_FLIGHT.set(1);
        T2MEConfig.INITIAL_IN_FLIGHT.set(8);
        T2MEConfig.MAX_IN_FLIGHT.set(256);
        T2MEConfig.CONTROL_INTERVAL_TICKS.set(5);
        T2MEConfig.TARGET_TICK_MILLIS.set(48);
        T2MEConfig.HARD_STOP_TICK_MILLIS.set(100);
        T2MEConfig.MIN_HEAP_HEADROOM_MIB.set(0);
        T2MEConfig.REDUCE_WHEN_PLAYERS_ONLINE.set(false);
    }

    @AfterEach
    void unloadTestConfig() {
        T2MEConfig.SPEC.setConfig(null);
    }

    @Test
    void healthySaturatedPipelineIncreasesTheWindow() {
        AdaptiveLimiter limiter = new AdaptiveLimiter();
        AdaptiveLimiter.Decision baseline = limiter.decide(
                null,
                100.0D,
                8,
                0L
        );
        AdaptiveLimiter.Decision increased = limiter.decide(
                null,
                100.0D,
                baseline.adaptiveWindow(),
                5L
        );

        assertEquals(8, baseline.adaptiveWindow());
        assertTrue(increased.adaptiveWindow() > baseline.adaptiveWindow());
        assertEquals(increased.adaptiveWindow(), increased.maxInFlight());
        assertEquals("searching peak throughput", increased.reason());
    }

    @Test
    void sustainedHighMsptBacksOffTheWindow() {
        AdaptiveLimiter limiter = new AdaptiveLimiter();
        AdaptiveLimiter.Decision baseline = limiter.decide(
                null,
                100.0D,
                8,
                0L
        );
        AdaptiveLimiter.Decision increased = limiter.decide(
                null,
                100.0D,
                baseline.adaptiveWindow(),
                5L
        );
        for (int sample = 0; sample < 21; sample++) {
            limiter.recordTick(60_000_000L);
        }

        AdaptiveLimiter.Decision backedOff = limiter.decide(
                null,
                100.0D,
                increased.adaptiveWindow(),
                10L
        );

        assertEquals(
                AdaptiveLimiter.multiplicativeDecrease(
                        increased.adaptiveWindow(),
                        1,
                        0.8D
                ),
                backedOff.adaptiveWindow()
        );
        assertEquals("MSPT backoff", backedOff.reason());
    }

    @Test
    void multiplicativeDecreaseFloorsAtConfiguredMinimum() {
        assertEquals(80, AdaptiveLimiter.multiplicativeDecrease(100, 32, 0.8D));
        assertEquals(50, AdaptiveLimiter.multiplicativeDecrease(100, 32, 0.5D));
        assertEquals(32, AdaptiveLimiter.multiplicativeDecrease(32, 32, 0.5D));
        assertEquals(32, AdaptiveLimiter.multiplicativeDecrease(33, 32, 0.5D));
    }

    @Test
    void tickStatisticsUseEwmaAndResetCleanly() {
        AdaptiveLimiter limiter = new AdaptiveLimiter();

        limiter.recordTick(10_000_000L);
        limiter.recordTick(30_000_000L);

        assertEquals(11.6D, limiter.tickEwmaMillis(), 0.000_001D);
        assertEquals(30.0D, limiter.tickPeakMillis(), 0.000_001D);

        limiter.reset();

        assertEquals(0.0D, limiter.tickEwmaMillis());
        assertEquals(0.0D, limiter.tickPeakMillis());
    }
}
