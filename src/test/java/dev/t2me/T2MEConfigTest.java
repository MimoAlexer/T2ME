package dev.t2me;

import com.electronwill.nightconfig.core.CommentedConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class T2MEConfigTest {
    @AfterEach
    void unloadConfig() {
        T2MEConfig.SPEC.setConfig(null);
    }

    @Test
    void migratesOnlyTheExactLegacyPerformanceProfile() {
        T2MEConfig.SPEC.setConfig(CommentedConfig.inMemory());
        T2MEConfig.MAX_IN_FLIGHT.set(8);
        T2MEConfig.MAX_DISPATCH_PER_TICK.set(4);
        T2MEConfig.TARGET_TICK_MILLIS.set(45);
        T2MEConfig.HARD_STOP_TICK_MILLIS.set(55);
        T2MEConfig.MIN_HEAP_HEADROOM_MIB.set(1024);
        T2MEConfig.REDUCE_WHEN_PLAYERS_ONLINE.set(true);

        assertTrue(T2MEConfig.migrateLegacyPerformanceDefaults());
        assertEquals(384, T2MEConfig.MAX_IN_FLIGHT.get());
        assertEquals(64, T2MEConfig.MAX_DISPATCH_PER_TICK.get());
        assertEquals(48, T2MEConfig.TARGET_TICK_MILLIS.get());
        assertEquals(65, T2MEConfig.HARD_STOP_TICK_MILLIS.get());
        assertEquals(512, T2MEConfig.MIN_HEAP_HEADROOM_MIB.get());
        assertFalse(T2MEConfig.REDUCE_WHEN_PLAYERS_ONLINE.get());
        assertFalse(T2MEConfig.migrateLegacyPerformanceDefaults());
    }

    @Test
    void preservesAnyCustomizedLegacyProfile() {
        T2MEConfig.SPEC.setConfig(CommentedConfig.inMemory());
        T2MEConfig.MAX_IN_FLIGHT.set(12);
        T2MEConfig.MAX_DISPATCH_PER_TICK.set(4);
        T2MEConfig.TARGET_TICK_MILLIS.set(45);
        T2MEConfig.HARD_STOP_TICK_MILLIS.set(55);
        T2MEConfig.MIN_HEAP_HEADROOM_MIB.set(1024);
        T2MEConfig.REDUCE_WHEN_PLAYERS_ONLINE.set(true);

        assertFalse(T2MEConfig.migrateLegacyPerformanceDefaults());
        assertEquals(12, T2MEConfig.MAX_IN_FLIGHT.get());
        assertEquals(4, T2MEConfig.MAX_DISPATCH_PER_TICK.get());
    }
}
