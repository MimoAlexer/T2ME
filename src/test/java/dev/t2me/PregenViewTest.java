package dev.t2me;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PregenViewTest {
    private static final ThreadedWorldgenEngine.Stats ENGINE_STATS =
            new ThreadedWorldgenEngine.Stats(true, 8, 3, 12, 27);

    @Test
    void progressAndStateTextArePresentationSafe() {
        PregenView running = view(JobState.RUNNING, 25L, 100L);
        PregenView overComplete = view(JobState.COMPLETED, 125L, 100L);
        PregenView negative = view(JobState.PAUSED, -10L, 100L);
        PregenView noTarget = view(JobState.COMPLETED, 0L, 0L);

        assertTrue(running.hasJob());
        assertEquals(25.0D, running.percent());
        assertEquals(0.25F, running.progressFraction());
        assertEquals("Running", running.stateText());
        assertEquals(100.0D, overComplete.percent());
        assertEquals(0.0D, negative.percent());
        assertEquals(100.0D, noTarget.percent());

        PregenView none = PregenView.none(
                12.5D,
                64,
                80,
                "pipeline filling",
                ENGINE_STATS
        );
        assertFalse(none.hasJob());
        assertEquals("No job", none.stateText());
    }

    @Test
    void formatsCountsAndDurationsReadably() {
        assertEquals("1,234,567", PregenView.formatCount(1_234_567L));
        assertEquals("unknown", PregenView.formatDuration(-1L));
        assertEquals("0m 00s", PregenView.formatDuration(0L));
        assertEquals("2m 05s", PregenView.formatDuration(125L));
        assertEquals("1h 02m 03s", PregenView.formatDuration(3_723L));
        assertEquals("1d 02h 03m", PregenView.formatDuration(93_784L));
    }

    @Test
    void sanitizesBlankMultilineAndOversizedMessages() {
        assertEquals("", PregenView.sanitize(null, 20));
        assertEquals("", PregenView.sanitize(" \t ", 20));
        assertEquals("", PregenView.sanitize("message", 0));
        assertEquals(
                "first second third",
                PregenView.sanitize(" first\nsecond\rthird ", 30)
        );
        assertEquals("abcdefg…", PregenView.sanitize("abcdefghijk", 8));
    }

    private static PregenView view(
            JobState state,
            long completed,
            long target
    ) {
        return new PregenView(
                UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
                state,
                "minecraft:overworld",
                0,
                0,
                10_000,
                PregenShape.CIRCLE,
                completed,
                target,
                64,
                2,
                1L,
                150.0D,
                140.0D,
                60L,
                20.0D,
                40.0D,
                96,
                112,
                "searching peak throughput",
                "",
                ENGINE_STATS
        );
    }
}
