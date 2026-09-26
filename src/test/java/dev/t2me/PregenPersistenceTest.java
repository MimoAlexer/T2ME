package dev.t2me;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PregenPersistenceTest {
    @Test
    void nbtRoundTripPreservesRetriesReservationsAndOutstandingTickets() {
        PregenJob job = job();
        long retry = job.pollNext();
        UUID ticket = UUID.randomUUID();
        job.markInFlight(retry, 1L, ticket);
        job.complete(retry, ticket, false, "temporary failure", 2, 2L);
        assertEquals(retry, job.pollNext());
        job.markInFlight(retry, 3L, UUID.randomUUID());
        long reserved = job.pollNext();

        PregenJob.Snapshot expected = job.snapshot();
        PregenJob.Snapshot loaded = PregenJob.Snapshot.load(expected.save());
        assertEquals(expected, loaded);
        PregenJob restored = PregenJob.restore(loaded);
        assertEquals(List.of(reserved, retry), restored.snapshot().pending());
        assertEquals(Map.of(retry, 1), restored.snapshot().retryCounts());
        assertEquals(0, restored.inFlightCount());
    }

    @Test
    void loadsLegacyCheckpointWithoutRetryHistory() {
        PregenJob job = job();
        long packed = job.pollNext();
        UUID ticket = UUID.randomUUID();
        job.markInFlight(packed, 1L, ticket);
        job.complete(packed, ticket, false, "failure", 2, 2L);
        CompoundTag legacy = job.snapshot().save();
        legacy.remove("RetryCoordinates");
        legacy.remove("RetryCounts");

        PregenJob restored = PregenJob.restore(PregenJob.Snapshot.load(legacy));
        assertEquals(job.id(), restored.id());
        assertEquals(job.failures(), restored.failures());
        assertEquals(packed, restored.pollNext());
        assertTrue(restored.snapshot().retryCounts().isEmpty());
    }

    @Test
    void snapshotsDefensivelyCopyMutableInputs() {
        PregenJob job = job();
        long packed = job.pollNext();
        PregenJob.Snapshot original = job.snapshot();
        List<Long> pending = new ArrayList<>(List.of(packed));
        Map<Long, Integer> retries = new HashMap<>(Map.of(packed, 1));
        PregenJob.Snapshot snapshot = new PregenJob.Snapshot(original.id(), original.dimension(),
                original.centerBlockX(), original.centerBlockZ(), original.radiusBlocks(),
                original.shape(), original.cursor(), 0L, 1L, original.state(),
                original.createdEpochMillis(), original.message(), pending, retries);
        pending.clear();
        retries.clear();
        assertEquals(List.of(packed), snapshot.pending());
        assertEquals(Map.of(packed, 1), snapshot.retryCounts());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.pending().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.retryCounts().clear());
    }

    @Test
    void rejectsMissingFieldsInsteadOfDefaultingProgressToZero() {
        CompoundTag valid = job().snapshot().save();
        for (String field : List.of("Id", "Dimension", "CenterBlockX", "CenterBlockZ",
                "RadiusBlocks", "Shape", "Cursor", "Completed", "Failures", "State",
                "CreatedEpochMillis", "Message", "Pending")) {
            CompoundTag malformed = valid.copy();
            malformed.remove(field);
            assertThrows(IllegalArgumentException.class, () -> PregenJob.Snapshot.load(malformed), field);
        }
        CompoundTag wrongType = valid.copy();
        wrongType.putString("Cursor", "0");
        assertThrows(IllegalArgumentException.class, () -> PregenJob.Snapshot.load(wrongType));
    }

    @Test
    void rejectsLostDuplicatedAndUnvisitedCoordinates() {
        PregenJob job = job();
        long packed = job.pollNext();
        CompoundTag valid = job.snapshot().save();

        CompoundTag missing = valid.copy();
        missing.putLongArray("Pending", new long[0]);
        assertThrows(IllegalArgumentException.class, () -> PregenJob.Snapshot.load(missing));

        CompoundTag duplicated = valid.copy();
        duplicated.putLongArray("Pending", new long[]{packed, packed});
        assertThrows(IllegalArgumentException.class, () -> PregenJob.Snapshot.load(duplicated));

        CompoundTag unvisited = valid.copy();
        unvisited.putLongArray("Pending", new long[]{job.pollNext()});
        assertThrows(IllegalArgumentException.class, () -> PregenJob.Snapshot.load(unvisited));

        CompoundTag outside = valid.copy();
        outside.putLongArray("Pending", new long[]{SpiralChunkPlan.pack(500, 500)});
        assertThrows(IllegalArgumentException.class, () -> PregenJob.Snapshot.load(outside));
    }

    @Test
    void rejectsImpossibleCountersAndIncompleteCompletedJobs() {
        CompoundTag valid = job().snapshot().save();
        for (String counter : List.of("Cursor", "Completed", "Failures")) {
            CompoundTag negative = valid.copy();
            negative.putLong(counter, -1L);
            assertThrows(IllegalArgumentException.class, () -> PregenJob.Snapshot.load(negative));
        }
        CompoundTag impossible = valid.copy();
        impossible.putLong("Completed", Long.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> PregenJob.Snapshot.load(impossible));

        CompoundTag incomplete = valid.copy();
        incomplete.putString("State", "COMPLETED");
        assertThrows(IllegalArgumentException.class, () -> PregenJob.Snapshot.load(incomplete));

        CompoundTag unknownDimension = valid.copy();
        unknownDimension.putString("Dimension", "invalid dimension!");
        assertThrows(IllegalArgumentException.class, () -> PregenJob.Snapshot.load(unknownDimension));
    }

    @Test
    void rejectsMalformedRetryHistory() {
        PregenJob job = job();
        long packed = job.pollNext();
        CompoundTag valid = job.snapshot().save();

        CompoundTag missingCounts = valid.copy();
        missingCounts.putLongArray("RetryCoordinates", new long[]{packed});
        assertThrows(IllegalArgumentException.class, () -> PregenJob.Snapshot.load(missingCounts));

        CompoundTag mismatched = missingCounts.copy();
        mismatched.putIntArray("RetryCounts", new int[0]);
        assertThrows(IllegalArgumentException.class, () -> PregenJob.Snapshot.load(mismatched));

        CompoundTag negative = missingCounts.copy();
        negative.putIntArray("RetryCounts", new int[]{-1});
        assertThrows(IllegalArgumentException.class, () -> PregenJob.Snapshot.load(negative));

        CompoundTag unknownCoordinate = valid.copy();
        unknownCoordinate.putLongArray("RetryCoordinates", new long[]{job.pollNext()});
        unknownCoordinate.putIntArray("RetryCounts", new int[]{1});
        assertThrows(IllegalArgumentException.class, () -> PregenJob.Snapshot.load(unknownCoordinate));
    }

    @Test
    void restoreNormalizesAnExhaustedRunningCheckpoint() {
        PregenJob job = new PregenJob("minecraft:overworld", 0, 0, 0, PregenShape.CIRCLE);
        long packed = job.pollNext();
        UUID ticket = UUID.randomUUID();
        job.markInFlight(packed, 1L, ticket);
        job.complete(packed, ticket, true, "", 0, 2L);
        CompoundTag checkpoint = job.snapshot().save();
        checkpoint.putString("State", "RUNNING");
        PregenJob restored = PregenJob.restore(PregenJob.Snapshot.load(checkpoint));
        assertEquals(JobState.COMPLETED, restored.state());
        assertEquals(restored.target(), restored.completed());
        assertFalse(restored.hasDispatchableWork());
    }

    @Test
    void savedDataPreservesCorruptJobVerbatimUntilExplicitClear() {
        CompoundTag corrupt = job().snapshot().save();
        corrupt.putLong("Cursor", 42L);
        CompoundTag root = new CompoundTag();
        root.put("Job", corrupt);

        T2MEData data = T2MEData.load(root);
        assertTrue(data.snapshot().isEmpty());
        assertTrue(data.loadError().isPresent());
        assertEquals(root, data.save(new CompoundTag()));
        assertThrows(IllegalStateException.class, () -> data.snapshot(job().snapshot()));
        data.save(new CompoundTag()).getCompound("Job").putString("State", "CANCELLED");
        assertEquals(root, data.save(new CompoundTag()), "callers cannot mutate retained raw data");

        data.clear();
        assertTrue(data.loadError().isEmpty());
        assertFalse(data.save(root.copy()).contains("Job"));
        data.snapshot(job().snapshot());
        assertTrue(data.snapshot().isPresent());
    }

    @Test
    void savedDataPreservesJobWithWrongNbtType() {
        CompoundTag root = new CompoundTag();
        root.put("Job", StringTag.valueOf("damaged job"));
        T2MEData data = T2MEData.load(root);
        assertTrue(data.loadError().isPresent());
        assertEquals(root, data.save(new CompoundTag()));
    }

    @Test
    void emptyAndValidSavedDataLoadNormally() {
        T2MEData empty = T2MEData.load(new CompoundTag());
        assertTrue(empty.snapshot().isEmpty());
        assertTrue(empty.loadError().isEmpty());

        PregenJob.Snapshot snapshot = job().snapshot();
        T2MEData original = new T2MEData();
        original.snapshot(snapshot);
        T2MEData restored = T2MEData.load(original.save(new CompoundTag()));
        assertEquals(snapshot, restored.snapshot().orElseThrow());
        assertTrue(restored.loadError().isEmpty());
    }

    private static PregenJob job() {
        return new PregenJob("minecraft:overworld", -17, 15, 32, PregenShape.CIRCLE);
    }
}
