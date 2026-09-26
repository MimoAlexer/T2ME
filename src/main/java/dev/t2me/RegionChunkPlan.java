package dev.t2me;

import java.util.Arrays;
import java.util.NoSuchElementException;

/**
 * Visits storage regions center-out and traverses each region along a Hilbert
 * curve. Keeping nearby requests in one region avoids repeatedly returning to
 * distant region files as a world-sized chunk spiral expands.
 *
 * <p>Each included region contributes 1,024 candidate slots, including slots
 * rejected by the requested shape. This cursor format is independent of the
 * legacy spiral cursor and must be persisted together with its traversal order.
 * Planning counts row intersections, never all candidate chunks.</p>
 */
public final class RegionChunkPlan implements ChunkPlan {
    private static final int REGION_SHIFT = 5;
    private static final int REGION_SIZE = 1 << REGION_SHIFT;
    private static final int SLOTS_PER_REGION = REGION_SIZE * REGION_SIZE;
    private static final int SLOT_SHIFT = REGION_SHIFT * 2;
    private static final int[] LOCAL_X = new int[SLOTS_PER_REGION];
    private static final int[] LOCAL_Z = new int[SLOTS_PER_REGION];
    private static final int[] LOCAL_INDEX = new int[SLOTS_PER_REGION];

    static {
        // Standard iterative Hilbert d-to-(x,y) construction: rotate/reflect
        // each sub-square, then place it in the next larger square.
        for (int index = 0; index < SLOTS_PER_REGION; index++) {
            int x = 0;
            int z = 0;
            int remaining = index;
            for (int size = 1; size < REGION_SIZE; size <<= 1) {
                int horizontal = (remaining >>> 1) & 1;
                int vertical = (remaining ^ horizontal) & 1;
                if (vertical == 0) {
                    if (horizontal == 1) {
                        x = size - 1 - x;
                        z = size - 1 - z;
                    }
                    int swap = x;
                    x = z;
                    z = swap;
                }
                x += size * horizontal;
                z += size * vertical;
                remaining >>>= 2;
            }
            LOCAL_X[index] = x;
            LOCAL_Z[index] = z;
            LOCAL_INDEX[(z << REGION_SHIFT) | x] = index;
        }
    }

    private final int centerBlockX;
    private final int centerBlockZ;
    private final int radiusBlocks;
    private final PregenShape shape;
    private final int minimumChunkX;
    private final int maximumChunkX;
    private final int minimumChunkZ;
    private final int maximumChunkZ;
    private final int minimumRegionX;
    private final int minimumRegionZ;
    private final int regionWidth;
    private final int[] rowMinimumX;
    private final int[] rowMaximumX;
    private final int[] regionX;
    private final int[] regionZ;
    private final int[] regionRanks;
    private final long[] acceptedPrefixes;
    private final long targetCount;
    private final long candidateCount;
    private long cursor;
    private long scanCursor;
    private boolean prepared;
    private long preparedChunk;

    public RegionChunkPlan(int centerBlockX, int centerBlockZ, int radiusBlocks, PregenShape shape) {
        if (radiusBlocks < 0 || radiusBlocks > SpiralChunkPlan.MAX_RADIUS_BLOCKS) {
            throw new IllegalArgumentException("radiusBlocks must be between 0 and "
                    + SpiralChunkPlan.MAX_RADIUS_BLOCKS);
        }
        if (shape == null) {
            throw new IllegalArgumentException("shape is required");
        }
        long minimumBlockX = (long) centerBlockX - radiusBlocks;
        long maximumBlockX = (long) centerBlockX + radiusBlocks;
        long minimumBlockZ = (long) centerBlockZ - radiusBlocks;
        long maximumBlockZ = (long) centerBlockZ + radiusBlocks;
        int bound = SpiralChunkPlan.MAX_ABSOLUTE_BLOCK_COORDINATE;
        if (minimumBlockX < -bound || maximumBlockX > bound
                || minimumBlockZ < -bound || maximumBlockZ > bound) {
            throw new IllegalArgumentException("center plus radius must stay within +/-" + bound);
        }
        this.centerBlockX = centerBlockX;
        this.centerBlockZ = centerBlockZ;
        this.radiusBlocks = radiusBlocks;
        this.shape = shape;
        this.minimumChunkX = Math.floorDiv((int) minimumBlockX, 16);
        this.maximumChunkX = Math.floorDiv((int) maximumBlockX, 16);
        this.minimumChunkZ = Math.floorDiv((int) minimumBlockZ, 16);
        this.maximumChunkZ = Math.floorDiv((int) maximumBlockZ, 16);

        if (shape == PregenShape.CIRCLE) {
            int rows = maximumChunkZ - minimumChunkZ + 1;
            rowMinimumX = new int[rows];
            rowMaximumX = new int[rows];
            long radiusSquared = (long) radiusBlocks * radiusBlocks;
            for (int row = 0; row < rows; row++) {
                long minimumZ = (long) (minimumChunkZ + row) * 16L;
                long nearestZ = Math.max(minimumZ, Math.min(minimumZ + 15L, centerBlockZ));
                long deltaZ = nearestZ - centerBlockZ;
                long squaredReach = radiusSquared - deltaZ * deltaZ;
                int reach = (int) Math.sqrt(squaredReach);
                // Operands are at most 20,000 here, so exact products fit long.
                while ((long) reach * reach > squaredReach) reach--;
                while ((long) (reach + 1) * (reach + 1) <= squaredReach) reach++;
                rowMinimumX[row] = Math.floorDiv(centerBlockX - reach, 16);
                rowMaximumX[row] = Math.floorDiv(centerBlockX + reach, 16);
            }
        } else {
            rowMinimumX = null;
            rowMaximumX = null;
        }

        minimumRegionX = Math.floorDiv(minimumChunkX, REGION_SIZE);
        minimumRegionZ = Math.floorDiv(minimumChunkZ, REGION_SIZE);
        int maximumRegionX = Math.floorDiv(maximumChunkX, REGION_SIZE);
        int maximumRegionZ = Math.floorDiv(maximumChunkZ, REGION_SIZE);
        regionWidth = maximumRegionX - minimumRegionX + 1;
        int capacity = regionWidth * (maximumRegionZ - minimumRegionZ + 1);
        regionRanks = new int[capacity];
        Arrays.fill(regionRanks, -1);
        int[] plannedX = new int[capacity];
        int[] plannedZ = new int[capacity];
        long[] prefixes = new long[capacity + 1];
        int centerRegionX = Math.floorDiv(centerBlockX, 16 * REGION_SIZE);
        int centerRegionZ = Math.floorDiv(centerBlockZ, 16 * REGION_SIZE);
        int ringRadius = Math.max(Math.max(centerRegionX - minimumRegionX,
                        maximumRegionX - centerRegionX),
                Math.max(centerRegionZ - minimumRegionZ, maximumRegionZ - centerRegionZ));
        int side = ringRadius * 2 + 1;
        int included = 0;
        for (int slot = 0; slot < side * side; slot++) {
            var relative = SpiralChunkPlan.spiralPosition(slot);
            int x = centerRegionX + relative.x();
            int z = centerRegionZ + relative.z();
            if (x < minimumRegionX || x > maximumRegionX
                    || z < minimumRegionZ || z > maximumRegionZ) {
                continue;
            }
            int count = countRegion(x, z);
            if (count == 0) {
                continue;
            }
            plannedX[included] = x;
            plannedZ[included] = z;
            regionRanks[(z - minimumRegionZ) * regionWidth + x - minimumRegionX] = included;
            prefixes[included + 1] = prefixes[included] + count;
            included++;
        }
        regionX = Arrays.copyOf(plannedX, included);
        regionZ = Arrays.copyOf(plannedZ, included);
        acceptedPrefixes = Arrays.copyOf(prefixes, included + 1);
        targetCount = prefixes[included];
        candidateCount = (long) included * SLOTS_PER_REGION;
    }

    @Override
    public boolean hasNext() {
        if (prepared) return true;
        while (scanCursor < candidateCount) {
            int region = (int) (scanCursor >>> SLOT_SHIFT);
            int local = (int) (scanCursor & (SLOTS_PER_REGION - 1));
            scanCursor++;
            int x = (regionX[region] << REGION_SHIFT) + LOCAL_X[local];
            int z = (regionZ[region] << REGION_SHIFT) + LOCAL_Z[local];
            if (accepts(x, z)) {
                preparedChunk = SpiralChunkPlan.pack(x, z);
                prepared = true;
                return true;
            }
        }
        return false;
    }

    @Override
    public long nextPacked() {
        if (hasNext()) {
            cursor = scanCursor;
            prepared = false;
            return preparedChunk;
        }
        cursor = candidateCount;
        throw new NoSuchElementException("chunk plan exhausted");
    }

    @Override
    public long cursor() {
        return cursor;
    }

    @Override
    public void cursor(long cursor) {
        validateCursor(cursor);
        this.cursor = cursor;
        scanCursor = cursor;
        prepared = false;
    }

    @Override
    public long acceptedBefore(long cursor) {
        validateCursor(cursor);
        int region = (int) (cursor >>> SLOT_SHIFT);
        long count = acceptedPrefixes[region];
        int partial = (int) (cursor & (SLOTS_PER_REGION - 1));
        if (partial > 0) {
            int baseX = regionX[region] << REGION_SHIFT;
            int baseZ = regionZ[region] << REGION_SHIFT;
            for (int slot = 0; slot < partial; slot++) {
                if (accepts(baseX + LOCAL_X[slot], baseZ + LOCAL_Z[slot])) count++;
            }
        }
        return count;
    }

    @Override
    public boolean wasVisited(long packed, long cursor) {
        validateCursor(cursor);
        int x = SpiralChunkPlan.unpackX(packed);
        int z = SpiralChunkPlan.unpackZ(packed);
        if (!accepts(x, z)) return false;
        int region = regionRanks[(Math.floorDiv(z, REGION_SIZE) - minimumRegionZ) * regionWidth
                + Math.floorDiv(x, REGION_SIZE) - minimumRegionX];
        if (region < 0) return false;
        int local = LOCAL_INDEX[((z & (REGION_SIZE - 1)) << REGION_SHIFT)
                | (x & (REGION_SIZE - 1))];
        return (long) region * SLOTS_PER_REGION + local < cursor;
    }

    @Override
    public long targetCount() {
        return targetCount;
    }

    @Override
    public long candidateCount() {
        return candidateCount;
    }

    @Override
    public int centerBlockX() {
        return centerBlockX;
    }

    @Override
    public int centerBlockZ() {
        return centerBlockZ;
    }

    @Override
    public int radiusBlocks() {
        return radiusBlocks;
    }

    @Override
    public PregenShape shape() {
        return shape;
    }

    private int countRegion(int regionX, int regionZ) {
        int firstX = Math.max(minimumChunkX, regionX << REGION_SHIFT);
        int lastX = Math.min(maximumChunkX, (regionX << REGION_SHIFT) + REGION_SIZE - 1);
        int firstZ = Math.max(minimumChunkZ, regionZ << REGION_SHIFT);
        int lastZ = Math.min(maximumChunkZ, (regionZ << REGION_SHIFT) + REGION_SIZE - 1);
        if (shape == PregenShape.SQUARE) {
            return (lastX - firstX + 1) * (lastZ - firstZ + 1);
        }
        int count = 0;
        for (int z = firstZ; z <= lastZ; z++) {
            int row = z - minimumChunkZ;
            count += Math.max(0, Math.min(lastX, rowMaximumX[row])
                    - Math.max(firstX, rowMinimumX[row]) + 1);
        }
        return count;
    }

    private boolean accepts(int chunkX, int chunkZ) {
        if (chunkZ < minimumChunkZ || chunkZ > maximumChunkZ) return false;
        if (shape == PregenShape.SQUARE) {
            return chunkX >= minimumChunkX && chunkX <= maximumChunkX;
        }
        int row = chunkZ - minimumChunkZ;
        return chunkX >= rowMinimumX[row] && chunkX <= rowMaximumX[row];
    }

    private void validateCursor(long cursor) {
        if (cursor < 0L || cursor > candidateCount) {
            throw new IllegalArgumentException("cursor " + cursor + " outside 0.." + candidateCount);
        }
    }
}
