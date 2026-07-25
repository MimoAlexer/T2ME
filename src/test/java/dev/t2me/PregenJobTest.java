package dev.t2me;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PregenJobTest {
    @Test
    void ignoresCompletionFromAStaleTicket() {
        PregenJob job = new PregenJob(
                "minecraft:overworld",
                0,
                0,
                16,
                PregenShape.CIRCLE
        );
        long packed = job.pollNext();
        long activeTicket = 11L;
        job.markInFlight(packed, 1L, activeTicket);

        assertEquals(
                PregenJob.Completion.IGNORED,
                job.complete(
                        packed,
                        12L,
                        true,
                        "",
                        2,
                        2L
                )
        );
        assertEquals(1, job.inFlightCount());
        assertEquals(0, job.completed());

        assertEquals(
                PregenJob.Completion.SUCCESS,
                job.complete(packed, activeTicket, true, "", 2, 3L)
        );
        assertEquals(0, job.inFlightCount());
        assertEquals(1, job.completed());
    }

    @Test
    void pausesAndPreservesCoordinateAfterRetryLimit() {
        PregenJob job = new PregenJob(
                "minecraft:overworld",
                0,
                0,
                16,
                PregenShape.CIRCLE
        );
        long packed = job.pollNext();
        long firstTicket = 21L;
        job.markInFlight(packed, 1L, firstTicket);

        assertEquals(
                PregenJob.Completion.RETRY,
                job.complete(packed, firstTicket, false, "first", 1, 2L)
        );
        assertEquals(packed, job.pollNext());

        long secondTicket = 22L;
        job.markInFlight(packed, 3L, secondTicket);
        assertEquals(
                PregenJob.Completion.PAUSED,
                job.complete(packed, secondTicket, false, "second", 1, 4L)
        );
        assertEquals(JobState.PAUSED, job.state());
        assertEquals(2, job.failures());
        assertEquals(1, job.retryQueueSize());
        assertEquals(packed, job.pollNext());
    }

    @Test
    void requeuesEveryOutstandingCoordinateInOrder() {
        PregenJob job = new PregenJob(
                "minecraft:overworld",
                0,
                0,
                32,
                PregenShape.SQUARE
        );
        long first = job.pollNext();
        long second = job.pollNext();
        job.markInFlight(first, 1L, 31L);
        job.markInFlight(second, 1L, 32L);

        job.requeueAllInFlight();

        assertEquals(0, job.inFlightCount());
        assertEquals(2, job.retryQueueSize());
        assertEquals(first, job.pollNext());
        assertEquals(second, job.pollNext());
    }

    @Test
    void snapshotPreservesRetryBudget() {
        PregenJob job = new PregenJob(
                "minecraft:overworld",
                0,
                0,
                16,
                PregenShape.CIRCLE
        );
        long packed = job.pollNext();
        job.markInFlight(packed, 1L, 41L);
        assertEquals(
                PregenJob.Completion.RETRY,
                job.complete(packed, 41L, false, "first", 1, 2L)
        );

        PregenJob restored = PregenJob.restore(job.snapshot());
        assertEquals(packed, restored.pollNext());
        restored.markInFlight(packed, 3L, 42L);

        assertEquals(
                PregenJob.Completion.PAUSED,
                restored.complete(packed, 42L, false, "second", 1, 4L)
        );
        assertEquals(JobState.PAUSED, restored.state());
    }

    @Test
    void shortRunRateUsesElapsedTimeAndResetsOnResume() {
        PregenJob job = new PregenJob(
                "minecraft:overworld",
                0,
                0,
                32,
                PregenShape.SQUARE
        );
        long started = job.runStartedNanos();
        for (long ticket = 51L; ticket <= 52L; ticket++) {
            long packed = job.pollNext();
            job.markInFlight(packed, started, ticket);
            assertEquals(
                    PregenJob.Completion.SUCCESS,
                    job.complete(
                            packed,
                            ticket,
                            true,
                            "",
                            2,
                            started + 2_000_000_000L
                    )
            );
        }

        assertEquals(
                1.0D,
                job.completionsPerSecond(60L, started + 2_000_000_000L),
                0.000_001D
        );

        job.pause("test");
        job.resume();
        assertEquals(
                0.0D,
                job.completionsPerSecond(60L, job.runStartedNanos())
        );
    }
}
