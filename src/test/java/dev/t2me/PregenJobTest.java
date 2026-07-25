package dev.t2me;

import org.junit.jupiter.api.Test;

import java.util.UUID;

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
        UUID activeTicket = UUID.randomUUID();
        job.markInFlight(packed, 1L, activeTicket);

        assertEquals(
                PregenJob.Completion.IGNORED,
                job.complete(
                        packed,
                        UUID.randomUUID(),
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
        UUID firstTicket = UUID.randomUUID();
        job.markInFlight(packed, 1L, firstTicket);

        assertEquals(
                PregenJob.Completion.RETRY,
                job.complete(packed, firstTicket, false, "first", 1, 2L)
        );
        assertEquals(packed, job.pollNext());

        UUID secondTicket = UUID.randomUUID();
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
        job.markInFlight(first, 1L, UUID.randomUUID());
        job.markInFlight(second, 1L, UUID.randomUUID());

        job.requeueAllInFlight();

        assertEquals(0, job.inFlightCount());
        assertEquals(2, job.retryQueueSize());
        assertEquals(first, job.pollNext());
        assertEquals(second, job.pollNext());
    }
}
