package dev.t2me;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PregenJobTest {
    @Test
    void newJobsUseRegionOrderAndExplicitSpiralJobsRemainAvailable() {
        PregenJob region = new PregenJob("minecraft:overworld", 511, -1, 96, PregenShape.SQUARE);
        PregenJob spiral = new PregenJob("minecraft:overworld", 511, -1, 96,
                PregenShape.SQUARE, ChunkOrder.SPIRAL);
        assertEquals(ChunkOrder.REGION, region.order());
        assertEquals(ChunkOrder.REGION, region.snapshot().order());
        assertEquals(ChunkOrder.SPIRAL, spiral.order());
        assertEquals(spiral.target(), region.target());
        assertInstanceOf(RegionChunkPlan.class, ChunkOrder.REGION.createPlan(511, -1, 96, PregenShape.SQUARE));
        assertInstanceOf(SpiralChunkPlan.class, ChunkOrder.SPIRAL.createPlan(511, -1, 96, PregenShape.SQUARE));
    }

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

    @Test
    void snapshotIncludesPolledCoordinatesEvenBeforeTicketCreation() {
        PregenJob job = squareJob();
        long first = job.pollNext();
        long second = job.pollNext();
        UUID ticket = UUID.randomUUID();
        job.markInFlight(second, 1L, ticket);
        assertEquals(PregenJob.Completion.SUCCESS,
                job.complete(second, ticket, true, "", 2, 2L));

        PregenJob restored = PregenJob.restore(job.snapshot());
        assertEquals(List.of(first), restored.snapshot().pending());
        Set<Long> visited = new HashSet<>();
        visited.add(second);
        while (restored.hasDispatchableWork()) {
            long packed = restored.pollNext();
            assertTrue(visited.add(packed), "checkpoint must not redispatch completed chunks");
            succeed(restored, packed, 3L);
        }
        assertEquals(restored.target(), visited.size());
        assertEquals(restored.target(), restored.completed());
        assertEquals(JobState.COMPLETED, restored.state());
    }

    @Test
    void requeuesCoordinatesWhoseTicketCreationDidNotFinish() {
        PregenJob job = squareJob();
        long packed = job.pollNext();
        job.requeueAllInFlight();
        job.requeueAllInFlight();
        assertEquals(List.of(packed), job.snapshot().pending());
        assertEquals(packed, job.pollNext());
        succeed(job, packed, 2L);
        assertEquals(1L, job.completed());
    }

    @Test
    void retryBudgetSurvivesCheckpointRestore() {
        PregenJob job = squareJob();
        long packed = job.pollNext();
        UUID first = UUID.randomUUID();
        job.markInFlight(packed, 1L, first);
        assertEquals(PregenJob.Completion.RETRY,
                job.complete(packed, first, false, "first failure", 1, 2L));

        PregenJob restored = PregenJob.restore(job.snapshot());
        assertEquals(packed, restored.pollNext());
        UUID second = UUID.randomUUID();
        restored.markInFlight(packed, 3L, second);
        assertEquals(PregenJob.Completion.PAUSED,
                restored.complete(packed, second, false, "second failure", 1, 4L));
        assertEquals(2L, restored.failures());
        assertEquals(List.of(packed), restored.snapshot().pending());
        assertTrue(restored.resume());
        assertEquals(packed, restored.pollNext());
        succeed(restored, packed, 5L);
        assertEquals(1L, restored.completed());
        assertTrue(restored.snapshot().retryCounts().isEmpty());
    }

    @Test
    void staleTicketCannotCompleteAReissuedCoordinate() {
        PregenJob job = squareJob();
        long packed = job.pollNext();
        UUID oldTicket = UUID.randomUUID();
        job.markInFlight(packed, 1L, oldTicket);
        job.requeueAllInFlight();
        assertEquals(packed, job.pollNext());
        UUID newTicket = UUID.randomUUID();
        job.markInFlight(packed, 2L, newTicket);

        assertEquals(PregenJob.Completion.IGNORED,
                job.complete(packed, oldTicket, true, "", 2, 3L));
        assertEquals(0L, job.completed());
        assertEquals(1, job.inFlightCount());
        assertEquals(PregenJob.Completion.SUCCESS,
                job.complete(packed, newTicket, true, "", 2, 4L));
    }

    @Test
    void terminalJobsCannotBeResurrectedByCallbacks() {
        for (boolean cancelled : List.of(true, false)) {
            PregenJob job = new PregenJob("minecraft:overworld", 0, 0, 0, PregenShape.SQUARE);
            long packed = job.pollNext();
            UUID ticket = UUID.randomUUID();
            job.markInFlight(packed, 1L, ticket);
            if (cancelled) {
                job.cancel();
            } else {
                job.fail("dimension unavailable");
            }
            JobState expected = cancelled ? JobState.CANCELLED : JobState.FAILED;
            assertEquals(PregenJob.Completion.IGNORED,
                    job.complete(packed, ticket, true, "", 2, 2L));
            assertEquals(expected, job.state());
            assertEquals(0L, job.completed());
            assertFalse(job.resume());
            assertThrows(IllegalStateException.class, job::pollNext);
            assertEquals(expected, PregenJob.restore(job.snapshot()).state());
        }
    }

    @Test
    void finalCompletionIsCountedExactlyOnce() {
        PregenJob job = new PregenJob("minecraft:overworld", -1, -1, 0, PregenShape.CIRCLE);
        long packed = job.pollNext();
        UUID ticket = UUID.randomUUID();
        job.markInFlight(packed, 1L, ticket);
        assertEquals(PregenJob.Completion.SUCCESS,
                job.complete(packed, ticket, true, "", 0, 2L));
        assertEquals(PregenJob.Completion.IGNORED,
                job.complete(packed, ticket, true, "", 0, 3L));
        assertEquals(JobState.COMPLETED, job.state());
        assertEquals(1L, job.completed());
        assertFalse(job.hasDispatchableWork());
    }

    @Test
    void backgroundFailureDoesNotOverwriteOperatorPauseReason() {
        PregenJob job = squareJob();
        long packed = job.pollNext();
        UUID ticket = UUID.randomUUID();
        job.markInFlight(packed, 1L, ticket);
        job.pause("paused by operator");
        assertEquals(PregenJob.Completion.RETRY,
                job.complete(packed, ticket, false, "temporary failure", 2, 2L));
        assertEquals(JobState.PAUSED, job.state());
        assertEquals("paused by operator", job.message());
    }

    @Test
    void invalidRetryLimitDoesNotLoseAnOutstandingChunk() {
        PregenJob job = squareJob();
        long packed = job.pollNext();
        UUID ticket = UUID.randomUUID();
        job.markInFlight(packed, 1L, ticket);
        assertThrows(IllegalArgumentException.class,
                () -> job.complete(packed, ticket, false, "failure", -1, 2L));
        assertEquals(1, job.inFlightCount());
        assertEquals(List.of(packed), job.snapshot().pending());
    }

    @Test
    void rollingRatesExpireAndDoNotLeakAcrossResume() {
        PregenJob job = squareJob();
        long started = job.runStartedNanos();
        succeed(job, job.pollNext(), started + 1_000_000_000L);
        succeed(job, job.pollNext(), started + 2_000_000_000L);
        assertEquals(1.0D, job.completionsPerSecond(5L, started + 2_000_000_000L));
        assertEquals(0.2D, job.completionsPerSecond(5L, started + 6_100_000_000L));
        assertEquals(0.0D, job.completionsPerSecond(60L, started + 62_100_000_000L));

        succeed(job, job.pollNext(), started + 65_000_000_000L);
        assertEquals(0.2D, job.completionsPerSecond(5L, started + 65_000_000_000L));
        job.pause("pause");
        assertTrue(job.resume());
        assertEquals(0.0D, job.completionsPerSecond(5L, job.runStartedNanos() + 1_000_000_000L));
        assertThrows(IllegalArgumentException.class, () -> job.completionsPerSecond(0L, 0L));
        assertThrows(IllegalArgumentException.class, () -> job.completionsPerSecond(61L, 0L));
    }

    @Test
    void rollingRatesKeepABoundedDenominatorBeforeTheRunStart() {
        PregenJob job = squareJob();
        long started = job.runStartedNanos();
        succeed(job, job.pollNext(), started - 2_000_000_000L);
        assertEquals(10.0D, job.completionsPerSecond(5L, started - 1_000_000_000L));
        assertEquals(0.0D, job.completionsPerSecond(5L, started + 3_100_000_000L));
    }

    @Test
    void startupRateUsesObservedTimeInsteadOfAnEntireMinute() {
        PregenJob job = new PregenJob("minecraft:overworld", 0, 0, 128, PregenShape.SQUARE);
        long started = job.runStartedNanos();
        for (int index = 0; index < 80; index++) {
            succeed(job, job.pollNext(), started + 8_000_000_000L);
        }
        assertEquals(10.0D, job.completionsPerSecond(60L, started + 8_000_000_000L));
        assertEquals(16.0D, job.completionsPerSecond(5L, started + 8_000_000_000L));
    }

    @Test
    void resumeRateStartsANewObservationWindowWithA100MillisecondFloor() {
        PregenJob job = squareJob();
        succeed(job, job.pollNext(), job.runStartedNanos() + 4_000_000_000L);
        job.pause("pause");
        assertTrue(job.resume());
        long resumed = job.runStartedNanos();
        assertEquals(0.0D, job.completionsPerSecond(60L, resumed));
        succeed(job, job.pollNext(), resumed + 50_000_000L);
        assertEquals(10.0D, job.completionsPerSecond(60L, resumed + 50_000_000L));
        succeed(job, job.pollNext(), resumed + 2_000_000_000L);
        assertEquals(1.0D, job.completionsPerSecond(60L, resumed + 2_000_000_000L));
    }

    @Test
    void ticketValidationLeavesReservationRecoverable() {
        PregenJob job = squareJob();
        long packed = job.pollNext();
        assertThrows(NullPointerException.class, () -> job.markInFlight(packed, 1L, null));
        assertEquals(List.of(packed), job.snapshot().pending());
        assertThrows(IllegalStateException.class,
                () -> job.markInFlight(SpiralChunkPlan.pack(100, 100), 1L, UUID.randomUUID()));
        succeed(job, packed, 2L);
    }

    private static PregenJob squareJob() {
        return new PregenJob("minecraft:overworld", 0, 0, 32, PregenShape.SQUARE);
    }

    private static void succeed(PregenJob job, long packed, long nowNanos) {
        UUID ticket = UUID.randomUUID();
        job.markInFlight(packed, nowNanos, ticket);
        assertEquals(PregenJob.Completion.SUCCESS,
                job.complete(packed, ticket, true, "", 2, nowNanos));
    }
}
