package dev.t2me;

import java.util.NoSuchElementException;

/**
 * Allocation-free sequential chunk traversal. Candidate chunks are visited
 * in a square spiral and filtered against a block-space circle or square.
 *
 * <p>The cursor counts inspected candidates, not accepted chunks. That makes a
 * single long sufficient to resume the exact traversal after a restart.</p>
 */
public final class SpiralChunkPlan implements ChunkPlan {
    private static final int CHUNK_SIZE = 16;
    static final int MAX_RADIUS_BLOCKS = 20_000;
    static final int MAX_ABSOLUTE_BLOCK_COORDINATE = 29_999_984;

    private final int centerBlockX;
    private final int centerBlockZ;
    private final int centerChunkX;
    private final int centerChunkZ;
    private final int radiusBlocks;
    private final PregenShape shape;
    private final int minChunkX;
    private final int maxChunkX;
    private final int minChunkZ;
    private final int maxChunkZ;
    private final int[] rowMinimumX;
    private final int[] rowMaximumX;
    private final long candidateCount;
    private final long targetCount;
    private long cursor;
    private long scanCursor;
    private int relativeX;
    private int relativeZ;
    private int ring;
    private boolean prepared;
    private long preparedChunk;

    public SpiralChunkPlan(
            int centerBlockX,
            int centerBlockZ,
            int radiusBlocks,
            PregenShape shape
    ) {
        if (radiusBlocks < 0 || radiusBlocks > MAX_RADIUS_BLOCKS) {
            throw new IllegalArgumentException(
                    "radiusBlocks must be between 0 and " + MAX_RADIUS_BLOCKS
            );
        }
        if (shape == null) {
            throw new IllegalArgumentException("shape is required");
        }

        long minimumBlockX = (long) centerBlockX - radiusBlocks;
        long maximumBlockX = (long) centerBlockX + radiusBlocks;
        long minimumBlockZ = (long) centerBlockZ - radiusBlocks;
        long maximumBlockZ = (long) centerBlockZ + radiusBlocks;
        if (minimumBlockX < -MAX_ABSOLUTE_BLOCK_COORDINATE
                || maximumBlockX > MAX_ABSOLUTE_BLOCK_COORDINATE
                || minimumBlockZ < -MAX_ABSOLUTE_BLOCK_COORDINATE
                || maximumBlockZ > MAX_ABSOLUTE_BLOCK_COORDINATE) {
            throw new IllegalArgumentException(
                    "center plus radius must stay within +/-"
                            + MAX_ABSOLUTE_BLOCK_COORDINATE
            );
        }

        this.centerBlockX = centerBlockX;
        this.centerBlockZ = centerBlockZ;
        this.centerChunkX = Math.floorDiv(centerBlockX, CHUNK_SIZE);
        this.centerChunkZ = Math.floorDiv(centerBlockZ, CHUNK_SIZE);
        this.radiusBlocks = radiusBlocks;
        this.shape = shape;

        this.minChunkX = Math.toIntExact(Math.floorDiv(minimumBlockX, CHUNK_SIZE));
        this.maxChunkX = Math.toIntExact(Math.floorDiv(maximumBlockX, CHUNK_SIZE));
        this.minChunkZ = Math.toIntExact(Math.floorDiv(minimumBlockZ, CHUNK_SIZE));
        this.maxChunkZ = Math.toIntExact(Math.floorDiv(maximumBlockZ, CHUNK_SIZE));
        int ringRadius = Math.max(
                Math.max(Math.abs(minChunkX - centerChunkX), Math.abs(maxChunkX - centerChunkX)),
                Math.max(Math.abs(minChunkZ - centerChunkZ), Math.abs(maxChunkZ - centerChunkZ))
        );

        long side = Math.addExact(Math.multiplyExact((long) ringRadius, 2L), 1L);
        this.candidateCount = Math.multiplyExact(side, side);
        if (shape == PregenShape.SQUARE) {
            this.rowMinimumX = null;
            this.rowMaximumX = null;
            this.targetCount = (long) (maxChunkX - minChunkX + 1)
                    * (maxChunkZ - minChunkZ + 1);
        } else {
            // For each row, the closest block Z gives its widest intersection
            // with the circle. Integer square roots preserve inclusive block
            // boundaries, including tangencies and negative coordinates.
            int rows = maxChunkZ - minChunkZ + 1;
            this.rowMinimumX = new int[rows];
            this.rowMaximumX = new int[rows];
            long count = 0L;
            long radiusSquared = (long) radiusBlocks * radiusBlocks;
            for (int row = 0; row < rows; row++) {
                long minimumZ = (long) (minChunkZ + row) * CHUNK_SIZE;
                long nearestZ = clamp(centerBlockZ, minimumZ, minimumZ + CHUNK_SIZE - 1L);
                long deltaZ = nearestZ - centerBlockZ;
                int reach = (int) floorSqrt(radiusSquared - deltaZ * deltaZ);
                rowMinimumX[row] = Math.floorDiv(centerBlockX - reach, CHUNK_SIZE);
                rowMaximumX[row] = Math.floorDiv(centerBlockX + reach, CHUNK_SIZE);
                count += rowMaximumX[row] - rowMinimumX[row] + 1L;
            }
            this.targetCount = count;
        }
    }

    public boolean hasNext() {
        if (prepared) {
            return true;
        }
        while (scanCursor < candidateCount) {
            int chunkX = centerChunkX + relativeX;
            int chunkZ = centerChunkZ + relativeZ;
            advanceCandidate();
            if (accepts(chunkX, chunkZ)) {
                preparedChunk = pack(chunkX, chunkZ);
                prepared = true;
                return true;
            }
        }
        return false;
    }

    public long nextPacked() {
        if (hasNext()) {
            cursor = scanCursor;
            prepared = false;
            return preparedChunk;
        }
        cursor = candidateCount;
        throw new NoSuchElementException("chunk plan exhausted");
    }

    public long cursor() {
        return cursor;
    }

    public void cursor(long cursor) {
        validateCursor(cursor);
        this.cursor = cursor;
        this.scanCursor = cursor;
        this.prepared = false;
        if (cursor < candidateCount) {
            ChunkCoordinate relative = spiralPosition(cursor);
            this.relativeX = relative.x();
            this.relativeZ = relative.z();
            this.ring = Math.max(Math.abs(relativeX), Math.abs(relativeZ));
        }
    }

    public long targetCount() {
        return targetCount;
    }

    public long candidateCount() {
        return candidateCount;
    }

    public int centerBlockX() {
        return centerBlockX;
    }

    public int centerBlockZ() {
        return centerBlockZ;
    }

    public int radiusBlocks() {
        return radiusBlocks;
    }

    public PregenShape shape() {
        return shape;
    }

    public static ChunkCoordinate spiralPosition(long index) {
        if (index < 0L) {
            throw new IllegalArgumentException("index must be non-negative");
        }
        if (index == 0L) {
            return new ChunkCoordinate(0, 0);
        }

        long ring = (floorSqrt(index) + 1L) / 2L;
        long side = ring * 2L;
        // The first index in the ring is representable even for Long.MAX_VALUE;
        // the last index need not be. Avoid both overflow and floating rounding
        // at perfect-square boundaries.
        long offset = index - (side - 1L) * (side - 1L);

        long x;
        long z;
        if (offset < side) {
            x = ring;
            z = 1L - ring + offset;
        } else if (offset < side * 2L) {
            x = ring - 1L - (offset - side);
            z = ring;
        } else if (offset < side * 3L) {
            x = -ring;
            z = ring - 1L - (offset - side * 2L);
        } else {
            x = 1L - ring + (offset - side * 3L);
            z = -ring;
        }
        return new ChunkCoordinate(Math.toIntExact(x), Math.toIntExact(z));
    }

    public static long pack(int chunkX, int chunkZ) {
        return (chunkX & 0xffffffffL) | ((long) chunkZ << 32);
    }

    public static int unpackX(long packed) {
        return (int) packed;
    }

    public static int unpackZ(long packed) {
        return (int) (packed >>> 32);
    }

    /** Membership in the requested shape, using its inclusive block bounds. */
    boolean contains(long packed) {
        return accepts(unpackX(packed), unpackZ(packed));
    }

    /** Whether an accepted chunk occurs before the persisted candidate cursor. */
    public boolean wasVisited(long packed, long cursor) {
        validateCursor(cursor);
        if (!contains(packed)) {
            return false;
        }
        int x = unpackX(packed) - centerChunkX;
        int z = unpackZ(packed) - centerChunkZ;
        int candidateRing = Math.max(Math.abs(x), Math.abs(z));
        if (candidateRing == 0) {
            return cursor > 0L;
        }
        long side = candidateRing * 2L;
        long offset;
        if (x == candidateRing && z > -candidateRing) {
            offset = z + candidateRing - 1L;
        } else if (z == candidateRing) {
            offset = side + candidateRing - x - 1L;
        } else if (x == -candidateRing) {
            offset = side * 2L + candidateRing - z - 1L;
        } else {
            offset = side * 3L + x + candidateRing - 1L;
        }
        return (side - 1L) * (side - 1L) + offset < cursor;
    }

    /**
     * Counts accepted candidates before a checkpoint in O(chunk rows), without
     * enumerating the region or changing traversal state.
     */
    public long acceptedBefore(long cursor) {
        validateCursor(cursor);
        if (cursor == 0L) {
            return 0L;
        }
        if (cursor == candidateCount) {
            return targetCount;
        }
        ChunkCoordinate relative = spiralPosition(cursor);
        int r = Math.max(Math.abs(relative.x()), Math.abs(relative.z()));
        int side = r * 2;
        long remaining = cursor - (side - 1L) * (side - 1L);
        long count = countRectangle(centerChunkX - r + 1, centerChunkX + r - 1,
                centerChunkZ - r + 1, centerChunkZ + r - 1);

        int used = (int) Math.min(remaining, side);
        count += countRectangle(centerChunkX + r, centerChunkX + r,
                centerChunkZ - r + 1, centerChunkZ - r + used);
        remaining -= used;
        used = (int) Math.min(remaining, side);
        count += countRectangle(centerChunkX + r - used, centerChunkX + r - 1,
                centerChunkZ + r, centerChunkZ + r);
        remaining -= used;
        used = (int) Math.min(remaining, side);
        count += countRectangle(centerChunkX - r, centerChunkX - r,
                centerChunkZ + r - used, centerChunkZ + r - 1);
        remaining -= used;
        used = (int) remaining;
        count += countRectangle(centerChunkX - r + 1, centerChunkX - r + used,
                centerChunkZ - r, centerChunkZ - r);
        return count;
    }

    private boolean accepts(int chunkX, int chunkZ) {
        if (chunkZ < minChunkZ || chunkZ > maxChunkZ) {
            return false;
        }
        if (shape == PregenShape.SQUARE) {
            return chunkX >= minChunkX && chunkX <= maxChunkX;
        }
        int row = chunkZ - minChunkZ;
        return chunkX >= rowMinimumX[row] && chunkX <= rowMaximumX[row];
    }

    private long countRectangle(int minimumX, int maximumX, int minimumZ, int maximumZ) {
        minimumX = Math.max(minimumX, minChunkX);
        maximumX = Math.min(maximumX, maxChunkX);
        minimumZ = Math.max(minimumZ, minChunkZ);
        maximumZ = Math.min(maximumZ, maxChunkZ);
        if (minimumX > maximumX || minimumZ > maximumZ) {
            return 0L;
        }
        if (shape == PregenShape.SQUARE) {
            return (long) (maximumX - minimumX + 1) * (maximumZ - minimumZ + 1);
        }
        long count = 0L;
        for (int chunkZ = minimumZ; chunkZ <= maximumZ; chunkZ++) {
            int row = chunkZ - minChunkZ;
            int first = Math.max(minimumX, rowMinimumX[row]);
            int last = Math.min(maximumX, rowMaximumX[row]);
            count += Math.max(0, last - first + 1);
        }
        return count;
    }

    private void advanceCandidate() {
        scanCursor++;
        if (relativeX == ring && relativeZ == -ring) {
            ring++;
            relativeX++;
        } else if (relativeX == ring && relativeZ < ring) {
            relativeZ++;
        } else if (relativeZ == ring && relativeX > -ring) {
            relativeX--;
        } else if (relativeX == -ring && relativeZ > -ring) {
            relativeZ--;
        } else {
            relativeX++;
        }
    }

    private void validateCursor(long cursor) {
        if (cursor < 0L || cursor > candidateCount) {
            throw new IllegalArgumentException(
                    "cursor " + cursor + " outside 0.." + candidateCount
            );
        }
    }

    private static long floorSqrt(long value) {
        long root = (long) Math.sqrt(value);
        while (root > 0L && root > value / root) {
            root--;
        }
        while (root + 1L <= value / (root + 1L)) {
            root++;
        }
        return root;
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public record ChunkCoordinate(int x, int z) {
    }
}
