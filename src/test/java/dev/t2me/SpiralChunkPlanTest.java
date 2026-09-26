package dev.t2me;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
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

    @Test
    void matchesLegacyOrderAndShapeForEveryChunkOffset() {
        int[] radii = {0, 1, 15, 16, 17, 31, 32, 33};
        for (PregenShape shape : PregenShape.values()) {
            for (int x = -16; x < 0; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int radius : radii) {
                        assertMatchesLegacy(new SpiralChunkPlan(x, z, radius, shape));
                    }
                }
            }
        }
    }

    @Test
    void matchesLegacyOrderAtRandomCentersAndWorldEdges() {
        Random random = new Random(20260926L);
        for (int attempt = 0; attempt < 80; attempt++) {
            assertMatchesLegacy(new SpiralChunkPlan(
                    random.nextInt(200_001) - 100_000,
                    random.nextInt(200_001) - 100_000,
                    random.nextInt(513),
                    PregenShape.values()[attempt % PregenShape.values().length]
            ));
        }
        for (PregenShape shape : PregenShape.values()) {
            int edge = SpiralChunkPlan.MAX_ABSOLUTE_BLOCK_COORDINATE - 33;
            assertMatchesLegacy(new SpiralChunkPlan(edge, -edge, 33, shape));
            assertMatchesLegacy(new SpiralChunkPlan(-edge, edge, 33, shape));
        }
    }

    @Test
    void everyCandidateCursorResumesIncludingRejectedBoundaryCandidates() {
        for (PregenShape shape : PregenShape.values()) {
            SpiralChunkPlan plan = new SpiralChunkPlan(-17, 14, 49, shape);
            for (long cursor = 0L; cursor <= plan.candidateCount(); cursor++) {
                plan.cursor(cursor);
                List<Long> expected = legacySequence(plan, cursor);
                List<Long> actual = new ArrayList<>();
                while (plan.hasNext()) {
                    long before = plan.cursor();
                    assertTrue(plan.hasNext());
                    assertEquals(before, plan.cursor(), "lookahead changed checkpoint");
                    actual.add(plan.nextPacked());
                }
                assertEquals(expected, actual, "resume at candidate " + cursor);
                assertThrows(NoSuchElementException.class, plan::nextPacked);
                assertEquals(plan.candidateCount(), plan.cursor());
            }
        }
    }

    @Test
    void resettingCursorDiscardsLookaheadAndExhaustion() {
        SpiralChunkPlan plan = new SpiralChunkPlan(3, -7, 32, PregenShape.CIRCLE);
        assertTrue(plan.hasNext());
        assertEquals(0L, plan.cursor());
        plan.cursor(7L);
        assertEquals(legacySequence(plan, 7L).get(0).longValue(), plan.nextPacked());
        plan.cursor(plan.candidateCount());
        assertFalse(plan.hasNext());
        assertFalse(plan.hasNext());
        plan.cursor(0L);
        assertMatchesLegacy(plan);
    }

    @Test
    void checkpointHelpersMatchIndependentlyEnumeratedPrefix() {
        for (PregenShape shape : PregenShape.values()) {
            for (int radius : new int[]{0, 1, 16, 33, 128}) {
                SpiralChunkPlan plan = new SpiralChunkPlan(-7, 19, radius, shape);
                Set<Long> visited = new HashSet<>();
                for (long cursor = 0L; cursor <= plan.candidateCount(); cursor++) {
                    assertEquals(visited.size(), plan.acceptedBefore(cursor));
                    for (long packed : visited) {
                        assertTrue(plan.wasVisited(packed, cursor));
                    }
                    if (cursor < plan.candidateCount()) {
                        long packed = legacyCandidate(plan, cursor);
                        assertFalse(plan.wasVisited(packed, cursor));
                        boolean accepts = legacyAccepts(plan, packed);
                        assertEquals(accepts, plan.contains(packed));
                        if (accepts) {
                            visited.add(packed);
                        }
                    }
                }
                assertFalse(plan.contains(SpiralChunkPlan.pack(Integer.MAX_VALUE, Integer.MIN_VALUE)));
                assertFalse(plan.wasVisited(SpiralChunkPlan.pack(Integer.MAX_VALUE, Integer.MIN_VALUE),
                        plan.candidateCount()));
                assertEquals(0L, plan.cursor(), "validation changed checkpoint");
                assertThrows(IllegalArgumentException.class, () -> plan.acceptedBefore(-1));
                assertThrows(IllegalArgumentException.class,
                        () -> plan.wasVisited(0L, plan.candidateCount() + 1L));
            }
        }
    }

    @Test
    void largestAllowedPlansCountAndResumeWithoutFullTraversal() {
        for (PregenShape shape : PregenShape.values()) {
            SpiralChunkPlan plan = new SpiralChunkPlan(-7, 9,
                    SpiralChunkPlan.MAX_RADIUS_BLOCKS, shape);
            assertEquals(6_255_001L, plan.candidateCount());
            long expected = countByChunkBounds(plan);
            assertEquals(expected, plan.targetCount());
            assertEquals(expected, plan.acceptedBefore(plan.candidateCount()));
            plan.cursor(plan.candidateCount() - 25L);
            List<Long> expectedTail = legacySequence(plan, plan.cursor());
            List<Long> actualTail = new ArrayList<>();
            while (plan.hasNext()) {
                actualTail.add(plan.nextPacked());
            }
            assertEquals(expectedTail, actualTail);
        }
    }

    @Test
    void randomAccessSpiralIsExactAtLargeRingBoundariesAndLongLimit() {
        for (long ring : new long[]{1L, 2L, 1250L, 1_000_000_000L, 1_518_500_250L}) {
            long start = (ring * 2L - 1L) * (ring * 2L - 1L);
            assertEquals(new SpiralChunkPlan.ChunkCoordinate((int) ring, (int) (1L - ring)),
                    SpiralChunkPlan.spiralPosition(start));
            assertEquals(new SpiralChunkPlan.ChunkCoordinate((int) (ring - 1L), (int) (1L - ring)),
                    SpiralChunkPlan.spiralPosition(start - 1L));
        }
        // The last ring's final corner exceeds Long.MAX_VALUE. Derive this
        // partial-ring position using exact, independently calculated constants.
        assertEquals(new SpiralChunkPlan.ChunkCoordinate(-1_373_026_057, 1_518_500_250),
                SpiralChunkPlan.spiralPosition(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> SpiralChunkPlan.spiralPosition(-1L));
    }

    private static void assertMatchesLegacy(SpiralChunkPlan plan) {
        List<Long> expected = legacySequence(plan, 0L);
        List<Long> actual = new ArrayList<>();
        while (plan.hasNext()) {
            actual.add(plan.nextPacked());
        }
        assertEquals(expected, actual);
        assertEquals(expected.size(), plan.targetCount());
        assertEquals(expected.size(), new HashSet<>(actual).size());
    }

    private static List<Long> legacySequence(SpiralChunkPlan plan, long cursor) {
        List<Long> result = new ArrayList<>();
        for (long index = cursor; index < plan.candidateCount(); index++) {
            long candidate = legacyCandidate(plan, index);
            if (legacyAccepts(plan, candidate)) {
                result.add(candidate);
            }
        }
        return result;
    }

    private static long legacyCandidate(SpiralChunkPlan plan, long index) {
        long x = 0L;
        long z = 0L;
        if (index > 0L) {
            long ring = (long) Math.ceil((Math.sqrt(index + 1.0D) - 1.0D) / 2.0D);
            long side = ring * 2L;
            long distance = (side + 1L) * (side + 1L) - 1L - index;
            if (distance < side) {
                x = ring - distance;
                z = -ring;
            } else if (distance < side * 2L) {
                x = -ring;
                z = -ring + distance - side;
            } else if (distance < side * 3L) {
                x = -ring + distance - side * 2L;
                z = ring;
            } else {
                x = ring;
                z = ring - distance + side * 3L;
            }
        }
        return SpiralChunkPlan.pack(Math.floorDiv(plan.centerBlockX(), 16) + (int) x,
                Math.floorDiv(plan.centerBlockZ(), 16) + (int) z);
    }

    private static boolean legacyAccepts(SpiralChunkPlan plan, long packed) {
        long minimumX = (long) SpiralChunkPlan.unpackX(packed) * 16L;
        long minimumZ = (long) SpiralChunkPlan.unpackZ(packed) * 16L;
        long deltaX = Math.max(minimumX - plan.centerBlockX(),
                Math.max(0L, plan.centerBlockX() - (minimumX + 15L)));
        long deltaZ = Math.max(minimumZ - plan.centerBlockZ(),
                Math.max(0L, plan.centerBlockZ() - (minimumZ + 15L)));
        if (plan.shape() == PregenShape.SQUARE) {
            return deltaX <= plan.radiusBlocks() && deltaZ <= plan.radiusBlocks();
        }
        return deltaX * deltaX + deltaZ * deltaZ <= (long) plan.radiusBlocks() * plan.radiusBlocks();
    }

    private static long countByChunkBounds(SpiralChunkPlan plan) {
        int firstX = Math.floorDiv(plan.centerBlockX() - plan.radiusBlocks(), 16);
        int lastX = Math.floorDiv(plan.centerBlockX() + plan.radiusBlocks(), 16);
        int firstZ = Math.floorDiv(plan.centerBlockZ() - plan.radiusBlocks(), 16);
        int lastZ = Math.floorDiv(plan.centerBlockZ() + plan.radiusBlocks(), 16);
        long count = 0L;
        for (int z = firstZ; z <= lastZ; z++) {
            for (int x = firstX; x <= lastX; x++) {
                if (legacyAccepts(plan, SpiralChunkPlan.pack(x, z))) {
                    count++;
                }
            }
        }
        return count;
    }

    private static Set<Long> consume(SpiralChunkPlan plan) {
        Set<Long> chunks = new HashSet<>();
        while (plan.hasNext()) {
            assertTrue(chunks.add(plan.nextPacked()), "duplicate chunk");
        }
        return chunks;
    }
}
