package dev.t2me;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RegionChunkPlanTest {
    @Test
    void exactCoverageMatchesLegacyAtAllChunkOffsetsAndBoundaryRadii() {
        for (PregenShape shape : PregenShape.values()) {
            for (int x = -16; x < 0; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int radius : new int[]{0, 1, 15, 16, 17, 31, 32, 33}) {
                        assertCoverage(x, z, radius, shape);
                    }
                }
            }
        }
    }

    @Test
    void coverageMatchesAcrossPositiveAndNegativeStorageRegionEdges() {
        Random random = new Random(738124L);
        for (int attempt = 0; attempt < 60; attempt++) {
            assertCoverage(random.nextInt(8193) - 4096, random.nextInt(8193) - 4096,
                    random.nextInt(1025), PregenShape.values()[attempt % 2]);
        }
        for (PregenShape shape : PregenShape.values()) {
            for (int offset : new int[]{-1, 0, 1, 15, 16, 31}) {
                assertCoverage(-512 + offset, 512 - offset, 512, shape);
            }
            int edge = SpiralChunkPlan.MAX_ABSOLUTE_BLOCK_COORDINATE - 33;
            assertCoverage(edge, -edge, 33, shape);
            assertCoverage(-edge, edge, 33, shape);
        }
    }

    @Test
    void wholeRegionsHaveHilbertAdjacencyAndInverseCursorLookup() {
        for (int center : new int[]{255, -257}) {
            RegionChunkPlan plan = new RegionChunkPlan(center, center, 255, PregenShape.SQUARE);
            assertEquals(1024L, plan.targetCount());
            assertEquals(1024L, plan.candidateCount());
            long previous = 0L;
            Set<Long> seen = new HashSet<>();
            for (int index = 0; index < 1024; index++) {
                long packed = plan.nextPacked();
                assertTrue(seen.add(packed));
                assertEquals(index + 1L, plan.cursor());
                assertFalse(plan.wasVisited(packed, index));
                assertTrue(plan.wasVisited(packed, index + 1L));
                if (index > 0) {
                    int distance = Math.abs(SpiralChunkPlan.unpackX(packed) - SpiralChunkPlan.unpackX(previous))
                            + Math.abs(SpiralChunkPlan.unpackZ(packed) - SpiralChunkPlan.unpackZ(previous));
                    assertEquals(1, distance, "Hilbert curve jumped at " + index);
                }
                previous = packed;
            }
            assertFalse(plan.hasNext());
        }
    }

    @Test
    void regionsAreContiguousInTraversalAndVisitedCenterOut() {
        int centerX = -529;
        int centerZ = 1040;
        RegionChunkPlan plan = new RegionChunkPlan(centerX, centerZ, 1400, PregenShape.CIRCLE);
        int centerRegionX = Math.floorDiv(centerX, 512);
        int centerRegionZ = Math.floorDiv(centerZ, 512);
        Set<Long> completedRegions = new HashSet<>();
        Long previousRegion = null;
        int previousRing = 0;
        long first = 0L;
        while (plan.hasNext()) {
            long chunk = plan.nextPacked();
            int regionX = Math.floorDiv(SpiralChunkPlan.unpackX(chunk), 32);
            int regionZ = Math.floorDiv(SpiralChunkPlan.unpackZ(chunk), 32);
            long region = SpiralChunkPlan.pack(regionX, regionZ);
            if (previousRegion == null || region != previousRegion) {
                if (previousRegion == null) first = region;
                assertTrue(completedRegions.add(region), "returned to an earlier storage region");
                int ring = Math.max(Math.abs(regionX - centerRegionX), Math.abs(regionZ - centerRegionZ));
                assertTrue(ring >= previousRing, "region order moved inward");
                previousRing = ring;
                previousRegion = region;
            }
        }
        assertEquals(SpiralChunkPlan.pack(centerRegionX, centerRegionZ), first);
        // Every candidate region intersects the requested shape.
        assertEquals(completedRegions.size() * 1024L, plan.candidateCount());
    }

    @Test
    void everyCandidateCursorHasExactCoverageAndResumeIncludingRejectedSlots() {
        for (PregenShape shape : PregenShape.values()) {
            RegionChunkPlan plan = new RegionChunkPlan(-1, 511, 17, shape);
            List<Long> chunks = new ArrayList<>();
            List<Long> chunkCursors = new ArrayList<>();
            while (plan.hasNext()) {
                chunks.add(plan.nextPacked());
                chunkCursors.add(plan.cursor());
            }
            int expectedCount = 0;
            for (long cursor = 0; cursor <= plan.candidateCount(); cursor++) {
                while (expectedCount < chunks.size() && chunkCursors.get(expectedCount) <= cursor) {
                    expectedCount++;
                }
                plan.cursor(cursor);
                assertEquals(expectedCount, plan.acceptedBefore(cursor));
                assertEquals(expectedCount < chunks.size(), plan.hasNext());
                assertEquals(cursor, plan.cursor(), "lookahead changed checkpoint");
                assertEquals(expectedCount < chunks.size(), plan.hasNext());
                if (expectedCount < chunks.size()) {
                    long expected = chunks.get(expectedCount);
                    assertFalse(plan.wasVisited(expected, cursor));
                    assertEquals(expected, plan.nextPacked());
                    assertEquals(chunkCursors.get(expectedCount).longValue(), plan.cursor());
                }
                if (expectedCount > 0) {
                    assertTrue(plan.wasVisited(chunks.get(expectedCount - 1), cursor));
                }
            }
        }
    }

    @Test
    void checkpointsReproduceTheCompleteRemainingSequence() {
        for (PregenShape shape : PregenShape.values()) {
            RegionChunkPlan first = new RegionChunkPlan(519, -1009, 700, shape);
            List<Long> all = new ArrayList<>();
            List<Long> positions = new ArrayList<>();
            while (first.hasNext()) {
                all.add(first.nextPacked());
                positions.add(first.cursor());
            }
            for (int split : new int[]{0, 1, 500, 1024, all.size() - 1, all.size()}) {
                long cursor = split == 0 ? 0L : positions.get(split - 1);
                RegionChunkPlan resumed = new RegionChunkPlan(519, -1009, 700, shape);
                resumed.cursor(cursor);
                assertEquals(split, resumed.acceptedBefore(cursor));
                List<Long> tail = new ArrayList<>();
                while (resumed.hasNext()) tail.add(resumed.nextPacked());
                assertEquals(all.subList(split, all.size()), tail);
            }
        }
    }

    @Test
    void largestPlansCountByRowsAndKeepBoundedCandidateRegions() {
        for (PregenShape shape : PregenShape.values()) {
            for (int center : new int[]{-511, 0, 15, 511}) {
                RegionChunkPlan plan = new RegionChunkPlan(center, -center,
                        SpiralChunkPlan.MAX_RADIUS_BLOCKS, shape);
                SpiralChunkPlan legacy = new SpiralChunkPlan(center, -center,
                        SpiralChunkPlan.MAX_RADIUS_BLOCKS, shape);
                assertEquals(legacy.targetCount(), plan.targetCount());
                assertEquals(plan.targetCount(), plan.acceptedBefore(plan.candidateCount()));
                assertTrue(plan.candidateCount() <= 80L * 80L * 1024L);
                plan.cursor(plan.candidateCount() - 1024L);
                long before = plan.acceptedBefore(plan.cursor());
                long remaining = 0L;
                while (plan.hasNext()) {
                    long chunk = plan.nextPacked();
                    assertTrue(legacy.contains(chunk));
                    assertTrue(plan.wasVisited(chunk, plan.cursor()));
                    remaining++;
                }
                assertEquals(plan.targetCount(), before + remaining);
            }
        }
    }

    @Test
    void rejectsInvalidInputAndCanResetAfterExhaustion() {
        assertThrows(IllegalArgumentException.class, () -> new RegionChunkPlan(0, 0, -1, PregenShape.CIRCLE));
        assertThrows(IllegalArgumentException.class, () -> new RegionChunkPlan(0, 0, 20001, PregenShape.CIRCLE));
        assertThrows(IllegalArgumentException.class, () -> new RegionChunkPlan(0, 0, 1, null));
        assertThrows(IllegalArgumentException.class,
                () -> new RegionChunkPlan(Integer.MIN_VALUE, Integer.MAX_VALUE, 16, PregenShape.SQUARE));
        assertThrows(IllegalArgumentException.class,
                () -> new RegionChunkPlan(SpiralChunkPlan.MAX_ABSOLUTE_BLOCK_COORDINATE, 0, 16, PregenShape.SQUARE));
        RegionChunkPlan plan = new RegionChunkPlan(-1, 1, 0, PregenShape.CIRCLE);
        assertThrows(IllegalArgumentException.class, () -> plan.cursor(-1L));
        assertThrows(IllegalArgumentException.class, () -> plan.cursor(plan.candidateCount() + 1L));
        assertThrows(IllegalArgumentException.class, () -> plan.acceptedBefore(-1L));
        assertThrows(IllegalArgumentException.class, () -> plan.wasVisited(0L, -1L));
        assertFalse(plan.wasVisited(SpiralChunkPlan.pack(Integer.MIN_VALUE, Integer.MAX_VALUE), 1024L));
        long only = plan.nextPacked();
        assertEquals(SpiralChunkPlan.pack(-1, 0), only);
        assertFalse(plan.hasNext());
        assertThrows(NoSuchElementException.class, plan::nextPacked);
        assertEquals(plan.candidateCount(), plan.cursor());
        plan.cursor(0L);
        assertTrue(plan.hasNext());
        assertEquals(only, plan.nextPacked());
    }

    private static void assertCoverage(int x, int z, int radius, PregenShape shape) {
        SpiralChunkPlan legacy = new SpiralChunkPlan(x, z, radius, shape);
        RegionChunkPlan region = new RegionChunkPlan(x, z, radius, shape);
        assertEquals(legacy.targetCount(), region.targetCount());
        Set<Long> expected = new HashSet<>();
        while (legacy.hasNext()) expected.add(legacy.nextPacked());
        Set<Long> actual = new HashSet<>();
        while (region.hasNext()) {
            assertTrue(actual.add(region.nextPacked()), "duplicate chunk");
        }
        assertEquals(expected, actual);
        assertEquals(actual.size(), region.targetCount());
        assertEquals(region.targetCount(), region.acceptedBefore(region.candidateCount()));
    }
}
