package dev.t2me;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
        PregenJob job = new PregenJob("minecraft:overworld", -17, 15, 32,
                PregenShape.CIRCLE, ChunkOrder.SPIRAL);
        long packed = job.pollNext();
        UUID ticket = UUID.randomUUID();
        job.markInFlight(packed, 1L, ticket);
        job.complete(packed, ticket, false, "failure", 2, 2L);
        CompoundTag legacy = job.snapshot().save();
        legacy.remove("RetryCoordinates");
        legacy.remove("RetryCounts");
        legacy.remove("Order");

        PregenJob restored = PregenJob.restore(PregenJob.Snapshot.load(legacy));
        assertEquals(job.id(), restored.id());
        assertEquals(ChunkOrder.SPIRAL, restored.order());
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
                original.createdEpochMillis(), original.message(), pending, retries, original.order());
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
        corrupt.putLong("Cursor", Long.MAX_VALUE);
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

    @Test
    void legacyConstructorsAndMissingOrderResumeTheExactSpiralSequence() {
        for (PregenShape shape : PregenShape.values()) {
            SpiralChunkPlan original = new SpiralChunkPlan(-17, 15, 96, shape);
            List<Long> completed = new ArrayList<>();
            for (int index = 0; index < 23; index++) {
                completed.add(original.nextPacked());
            }
            long firstPending = original.nextPacked();
            long secondPending = original.nextPacked();
            long legacyCursor = original.cursor();
            List<Long> expectedRemainder = new ArrayList<>(List.of(firstPending, secondPending));
            while (original.hasNext()) {
                expectedRemainder.add(original.nextPacked());
            }

            PregenJob.Snapshot legacy = new PregenJob.Snapshot(UUID.randomUUID(), "minecraft:overworld",
                    -17, 15, 96, shape, legacyCursor, completed.size(), 0L,
                    JobState.RUNNING, 1234L, "running", List.of(firstPending, secondPending));
            PregenJob.Snapshot legacyWithRetries = new PregenJob.Snapshot(legacy.id(), legacy.dimension(),
                    legacy.centerBlockX(), legacy.centerBlockZ(), legacy.radiusBlocks(), legacy.shape(),
                    legacy.cursor(), legacy.completed(), 1L, legacy.state(), legacy.createdEpochMillis(),
                    legacy.message(), legacy.pending(), Map.of(firstPending, 1));
            assertEquals(ChunkOrder.SPIRAL, legacy.order());
            assertEquals(ChunkOrder.SPIRAL, legacyWithRetries.order());
            assertEquals(ChunkOrder.SPIRAL, PregenJob.restore(legacyWithRetries).order());

            CompoundTag oldSave = legacy.save();
            oldSave.remove("Order");
            PregenJob restored = PregenJob.restore(PregenJob.Snapshot.load(oldSave));
            assertEquals(ChunkOrder.SPIRAL, restored.order());
            List<Long> resumed = finish(restored);
            assertEquals(expectedRemainder, resumed, "legacy cursor must never be interpreted as region order");
            completed.addAll(resumed);
            assertEquals(restored.target(), new HashSet<>(completed).size());
            assertEquals(restored.target(), restored.completed());
            assertEquals(JobState.COMPLETED, restored.state());
        }
    }

    @Test
    void regionCheckpointsRetainOrderAcrossRepeatedRestartsAndRegionBoundaries() {
        for (PregenShape shape : PregenShape.values()) {
            ChunkPlan reference = ChunkOrder.REGION.createPlan(511, -1, 96, shape);
            List<Long> expected = new ArrayList<>();
            while (reference.hasNext()) {
                expected.add(reference.nextPacked());
            }
            PregenJob current = new PregenJob("minecraft:overworld", 511, -1, 96, shape);
            List<Long> visited = new ArrayList<>();
            for (int index = 0; index < 11; index++) {
                long packed = current.pollNext();
                visited.add(packed);
                succeed(current, packed);
            }
            long reserved = current.pollNext();
            long outstanding = current.pollNext();
            current.markInFlight(outstanding, 1L, UUID.randomUUID());
            CompoundTag checkpoint = current.snapshot().save();
            assertEquals("region", checkpoint.getString("Order"));
            current = PregenJob.restore(PregenJob.Snapshot.load(checkpoint));
            assertEquals(List.of(reserved, outstanding), current.snapshot().pending());

            while (current.hasDispatchableWork()) {
                assertEquals(ChunkOrder.REGION, current.order());
                long packed = current.pollNext();
                visited.add(packed);
                succeed(current, packed);
                if (visited.size() % 17 == 0) {
                    current = PregenJob.restore(PregenJob.Snapshot.load(current.snapshot().save()));
                }
            }
            assertEquals(expected, visited);
            assertEquals(expected.size(), new HashSet<>(visited).size());
            assertEquals(current.target(), current.completed());
            assertEquals(JobState.COMPLETED, current.state());
        }
    }

    @Test
    void explicitSpiralOrderIsRetainedByNewCheckpointSerialization() {
        PregenJob job = new PregenJob("minecraft:overworld", 0, 0, 32, PregenShape.SQUARE, ChunkOrder.SPIRAL);
        long pending = job.pollNext();
        CompoundTag saved = job.snapshot().save();
        assertEquals("spiral", saved.getString("Order"));
        PregenJob restored = PregenJob.restore(PregenJob.Snapshot.load(saved));
        assertEquals(ChunkOrder.SPIRAL, restored.order());
        assertEquals(pending, restored.pollNext());
    }

    @Test
    void unknownOrWrongTypeOrderIsRejectedAndOriginalDataRetained() {
        for (String order : List.of("unknown", "", "region-v2")) {
            CompoundTag bad = job().snapshot().save();
            bad.putString("Order", order);
            assertThrows(IllegalArgumentException.class, () -> PregenJob.Snapshot.load(bad));
            CompoundTag root = new CompoundTag();
            root.put("Job", bad);
            T2MEData data = T2MEData.load(root);
            assertTrue(data.loadError().isPresent());
            assertEquals(root, data.save(new CompoundTag()));
        }
        CompoundTag wrongType = job().snapshot().save();
        wrongType.putInt("Order", 1);
        assertThrows(IllegalArgumentException.class, () -> PregenJob.Snapshot.load(wrongType));
    }

    @Test
    void pendingCoordinateValidationUsesTheSavedOrder() {
        for (ChunkOrder order : ChunkOrder.values()) {
            PregenJob job = new PregenJob("minecraft:overworld", 511, -1, 96, PregenShape.SQUARE, order);
            job.pollNext();
            CompoundTag valid = job.snapshot().save();
            assertEquals(order, PregenJob.restore(PregenJob.Snapshot.load(valid)).order());
            long unvisited = job.pollNext();
            valid.putLongArray("Pending", new long[]{unvisited});
            assertThrows(IllegalArgumentException.class, () -> PregenJob.Snapshot.load(valid));
        }
    }

    private static List<Long> finish(PregenJob job) {
        List<Long> visited = new ArrayList<>();
        while (job.hasDispatchableWork()) {
            long packed = job.pollNext();
            visited.add(packed);
            succeed(job, packed);
        }
        return visited;
    }

    private static void succeed(PregenJob job, long packed) {
        UUID ticket = UUID.randomUUID();
        job.markInFlight(packed, 1L, ticket);
        assertEquals(PregenJob.Completion.SUCCESS,
                job.complete(packed, ticket, true, "", 2, 2L));
    }

    private static PregenJob job() {
        return new PregenJob("minecraft:overworld", -17, 15, 32, PregenShape.CIRCLE);
    }
}
