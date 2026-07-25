package dev.t2me;

import java.util.NoSuchElementException;

/**
 * Allocation-light deterministic chunk traversal. Candidate chunks are visited
 * in a square spiral and filtered against a block-space circle or square.
 *
 * <p>The cursor counts inspected candidates, not accepted chunks. That makes a
 * single long sufficient to resume the exact traversal after a restart.</p>
 */
public final class SpiralChunkPlan {
    private static final int CHUNK_SIZE = 16;
    static final int MAX_RADIUS_BLOCKS = 20_000;
    static final int MAX_ABSOLUTE_BLOCK_COORDINATE = 29_999_984;

    private final int centerBlockX;
    private final int centerBlockZ;
    private final int centerChunkX;
    private final int centerChunkZ;
    private final int radiusBlocks;
    private final PregenShape shape;
    private final int ringRadius;
    private final long candidateCount;
    private final long targetCount;
    private long cursor;

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

        int minChunkX = Math.toIntExact(Math.floorDiv(minimumBlockX, CHUNK_SIZE));
        int maxChunkX = Math.toIntExact(Math.floorDiv(maximumBlockX, CHUNK_SIZE));
        int minChunkZ = Math.toIntExact(Math.floorDiv(minimumBlockZ, CHUNK_SIZE));
        int maxChunkZ = Math.toIntExact(Math.floorDiv(maximumBlockZ, CHUNK_SIZE));
        this.ringRadius = Math.max(
                Math.max(Math.abs(minChunkX - centerChunkX), Math.abs(maxChunkX - centerChunkX)),
                Math.max(Math.abs(minChunkZ - centerChunkZ), Math.abs(maxChunkZ - centerChunkZ))
        );

        long side = Math.addExact(Math.multiplyExact((long) ringRadius, 2L), 1L);
        this.candidateCount = Math.multiplyExact(side, side);
        this.targetCount = countAccepted();
    }

    public boolean hasNext() {
        long probe = cursor;
        while (probe < candidateCount) {
            ChunkCoordinate coordinate = candidateAt(probe++);
            if (accepts(coordinate.x(), coordinate.z())) {
                return true;
            }
        }
        return false;
    }

    public long nextPacked() {
        while (cursor < candidateCount) {
            ChunkCoordinate coordinate = candidateAt(cursor++);
            if (accepts(coordinate.x(), coordinate.z())) {
                return pack(coordinate.x(), coordinate.z());
            }
        }
        throw new NoSuchElementException("chunk plan exhausted");
    }

    public long cursor() {
        return cursor;
    }

    public void cursor(long cursor) {
        if (cursor < 0L || cursor > candidateCount) {
            throw new IllegalArgumentException(
                    "cursor " + cursor + " outside 0.." + candidateCount
            );
        }
        this.cursor = cursor;
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

        long ring = (long) Math.ceil((Math.sqrt(index + 1.0D) - 1.0D) / 2.0D);
        long side = ring * 2L;
        long maximum = (side + 1L) * (side + 1L) - 1L;
        long distance = maximum - index;

        long x;
        long z;
        if (distance < side) {
            x = ring - distance;
            z = -ring;
        } else if (distance < side * 2L) {
            x = -ring;
            z = -ring + (distance - side);
        } else if (distance < side * 3L) {
            x = -ring + (distance - side * 2L);
            z = ring;
        } else {
            x = ring;
            z = ring - (distance - side * 3L);
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

    private ChunkCoordinate candidateAt(long index) {
        ChunkCoordinate relative = spiralPosition(index);
        return new ChunkCoordinate(
                Math.addExact(centerChunkX, relative.x()),
                Math.addExact(centerChunkZ, relative.z())
        );
    }

    private long countAccepted() {
        long count = 0L;
        for (long index = 0L; index < candidateCount; index++) {
            ChunkCoordinate coordinate = candidateAt(index);
            if (accepts(coordinate.x(), coordinate.z())) {
                count++;
            }
        }
        return count;
    }

    private boolean accepts(int chunkX, int chunkZ) {
        long minimumX = (long) chunkX * CHUNK_SIZE;
        long minimumZ = (long) chunkZ * CHUNK_SIZE;
        long maximumX = minimumX + CHUNK_SIZE - 1L;
        long maximumZ = minimumZ + CHUNK_SIZE - 1L;

        if (shape == PregenShape.SQUARE) {
            long shapeMinimumX = (long) centerBlockX - radiusBlocks;
            long shapeMaximumX = (long) centerBlockX + radiusBlocks;
            long shapeMinimumZ = (long) centerBlockZ - radiusBlocks;
            long shapeMaximumZ = (long) centerBlockZ + radiusBlocks;
            return maximumX >= shapeMinimumX
                    && minimumX <= shapeMaximumX
                    && maximumZ >= shapeMinimumZ
                    && minimumZ <= shapeMaximumZ;
        }

        long nearestX = clamp(centerBlockX, minimumX, maximumX);
        long nearestZ = clamp(centerBlockZ, minimumZ, maximumZ);
        long deltaX = nearestX - centerBlockX;
        long deltaZ = nearestZ - centerBlockZ;
        long radiusSquared = (long) radiusBlocks * radiusBlocks;
        return deltaX * deltaX + deltaZ * deltaZ <= radiusSquared;
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public record ChunkCoordinate(int x, int z) {
    }
}
