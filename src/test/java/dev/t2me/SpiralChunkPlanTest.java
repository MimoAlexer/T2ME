package dev.t2me;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpiralChunkPlanTest {
    @Test
    void spiralStartsAtCenterAndCoversFirstRing() {
        List<SpiralChunkPlan.ChunkCoordinate> expected = List.of(
                new SpiralChunkPlan.ChunkCoordinate(0, 0),
                new SpiralChunkPlan.ChunkCoordinate(1, 0),
                new SpiralChunkPlan.ChunkCoordinate(1, 1),
                new SpiralChunkPlan.ChunkCoordinate(0, 1),
                new SpiralChunkPlan.ChunkCoordinate(-1, 1),
                new SpiralChunkPlan.ChunkCoordinate(-1, 0),
                new SpiralChunkPlan.ChunkCoordinate(-1, -1),
                new SpiralChunkPlan.ChunkCoordinate(0, -1),
                new SpiralChunkPlan.ChunkCoordinate(1, -1)
        );

        for (int index = 0; index < expected.size(); index++) {
            assertEquals(expected.get(index), SpiralChunkPlan.spiralPosition(index));
        }
    }

    @Test
    void squarePlanHasNoDuplicatesAndExactTarget() {
        SpiralChunkPlan plan = new SpiralChunkPlan(0, 0, 32, PregenShape.SQUARE);
        Set<Long> chunks = consume(plan);
        assertEquals(plan.targetCount(), chunks.size());
        assertEquals(25, chunks.size());
        assertFalse(plan.hasNext());
    }

    @Test
    void circlePlanHasNoDuplicates() {
        SpiralChunkPlan plan = new SpiralChunkPlan(7, -11, 512, PregenShape.CIRCLE);
        Set<Long> chunks = consume(plan);
        assertEquals(plan.targetCount(), chunks.size());
        assertTrue(chunks.size() > 3_000);
    }

    @Test
    void cursorResumesExactSequence() {
        SpiralChunkPlan first = new SpiralChunkPlan(23, -41, 256, PregenShape.CIRCLE);
        for (int index = 0; index < 200; index++) {
            first.nextPacked();
        }
        long cursor = first.cursor();
        List<Long> expected = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            expected.add(first.nextPacked());
        }

        SpiralChunkPlan resumed = new SpiralChunkPlan(23, -41, 256, PregenShape.CIRCLE);
        resumed.cursor(cursor);
        List<Long> actual = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            actual.add(resumed.nextPacked());
        }
        assertEquals(expected, actual);
    }

    @Test
    void rejectsInvalidCursor() {
        SpiralChunkPlan plan = new SpiralChunkPlan(0, 0, 16, PregenShape.CIRCLE);
        assertThrows(IllegalArgumentException.class, () -> plan.cursor(-1));
        assertThrows(
                IllegalArgumentException.class,
                () -> plan.cursor(plan.candidateCount() + 1)
        );
    }

    @Test
    void rejectsUnsafeRadiusAndWorldBounds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SpiralChunkPlan(
                        0,
                        0,
                        SpiralChunkPlan.MAX_RADIUS_BLOCKS + 1,
                        PregenShape.CIRCLE
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new SpiralChunkPlan(
                        SpiralChunkPlan.MAX_ABSOLUTE_BLOCK_COORDINATE,
                        0,
                        16,
                        PregenShape.CIRCLE
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new SpiralChunkPlan(
                        Integer.MIN_VALUE,
                        Integer.MAX_VALUE,
                        16,
                        PregenShape.SQUARE
                )
        );
    }

    @Test
    void acceptsPlanExactlyAtSafeWorldBound() {
        SpiralChunkPlan plan = new SpiralChunkPlan(
                SpiralChunkPlan.MAX_ABSOLUTE_BLOCK_COORDINATE - 16,
                -SpiralChunkPlan.MAX_ABSOLUTE_BLOCK_COORDINATE + 16,
                16,
                PregenShape.CIRCLE
        );
        assertTrue(plan.targetCount() > 0);
    }

    private static Set<Long> consume(SpiralChunkPlan plan) {
        Set<Long> chunks = new HashSet<>();
        while (plan.hasNext()) {
            assertTrue(chunks.add(plan.nextPacked()), "duplicate chunk");
        }
        return chunks;
    }
}
