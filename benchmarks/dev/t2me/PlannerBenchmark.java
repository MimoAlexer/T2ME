package dev.t2me;

import java.util.Arrays;
import java.util.Locale;

/**
 * Standalone planner microbenchmark against T2ME's original planner algorithm.
 * This does not benchmark Minecraft generation, disk I/O, or another mod.
 */
public final class PlannerBenchmark {
    private static volatile long blackhole;
    private static final int WARMUPS = 3;
    private static final int SAMPLES = 7;

    private PlannerBenchmark() {
    }

    public static void main(String[] arguments) {
        int radius = arguments.length == 0 ? 20_000 : Integer.parseInt(arguments[0]);
        if (radius < 0 || radius > SpiralChunkPlan.MAX_RADIUS_BLOCKS) {
            throw new IllegalArgumentException("radius must be in 0..20000");
        }
        System.out.printf(Locale.ROOT, "JVM: %s %s; radius: %,d blocks; median of %d samples after %d warmups%n",
                System.getProperty("java.vendor"), System.getProperty("java.version"), radius, SAMPLES, WARMUPS);
        System.out.println("Planner CPU only: original T2ME algorithm vs current. This is NOT world-generation throughput.");
        for (PregenShape shape : PregenShape.values()) {
            benchmark(radius, shape);
        }
    }

    private static void benchmark(int radius, PregenShape shape) {
        double[] oldCreate = new double[SAMPLES];
        double[] newCreate = new double[SAMPLES];
        double[] oldTraverse = new double[SAMPLES];
        double[] newTraverse = new double[SAMPLES];
        long target = 0L;
        for (int trial = -WARMUPS; trial < SAMPLES; trial++) {
            long started = System.nanoTime();
            LegacyPlan original = new LegacyPlan(7, -11, radius, shape);
            long originalCreateNanos = System.nanoTime() - started;
            blackhole = original.target;

            // Batch the much shorter construction phase to reduce clock noise.
            int batch = shape == PregenShape.SQUARE ? 100_000 : 1_000;
            started = System.nanoTime();
            for (int iteration = 0; iteration < batch; iteration++) {
                SpiralChunkPlan current = new SpiralChunkPlan(7 + iteration % 2, -11, radius, shape);
                blackhole = current.targetCount();
            }
            double currentCreateNanos = (System.nanoTime() - started) / (double) batch;

            SpiralChunkPlan current = new SpiralChunkPlan(7, -11, radius, shape);
            if (original.target != current.targetCount()) {
                throw new AssertionError("target mismatch");
            }
            target = current.targetCount();
            started = System.nanoTime();
            long oldHash = 0L;
            long oldCount = 0L;
            while (original.hasNext()) {
                oldHash = oldHash * 31L + original.nextPacked();
                oldCount++;
            }
            long originalTraverseNanos = System.nanoTime() - started;
            started = System.nanoTime();
            long newHash = 0L;
            long newCount = 0L;
            while (current.hasNext()) {
                newHash = newHash * 31L + current.nextPacked();
                newCount++;
            }
            long currentTraverseNanos = System.nanoTime() - started;
            if (oldCount != target || newCount != target || oldHash != newHash) {
                throw new AssertionError("sequence mismatch");
            }
            blackhole = newHash;
            if (trial >= 0) {
                oldCreate[trial] = originalCreateNanos / 1_000_000.0D;
                newCreate[trial] = currentCreateNanos / 1_000_000.0D;
                oldTraverse[trial] = originalTraverseNanos / 1_000_000.0D;
                newTraverse[trial] = currentTraverseNanos / 1_000_000.0D;
            }
        }
        System.out.printf(Locale.ROOT, "%n%s: %,d accepted chunks; counts and ordered sequence hashes match%n", shape, target);
        System.out.printf(Locale.ROOT, "  create:   original %.3f ms; current %.6f ms%n", median(oldCreate), median(newCreate));
        System.out.printf(Locale.ROOT, "  traverse: original %.3f ms; current %.3f ms (%.2fx)%n",
                median(oldTraverse), median(newTraverse), median(oldTraverse) / median(newTraverse));
    }

    private static double median(double[] values) {
        Arrays.sort(values);
        return values[values.length / 2];
    }

    // Kept here as the pre-optimization baseline, rather than using any current
    // planner helpers whose implementation could distort the comparison.
    private static final class LegacyPlan {
        private final int centerX;
        private final int centerZ;
        private final int radius;
        private final PregenShape shape;
        private final long candidates;
        private final long target;
        private long cursor;

        private LegacyPlan(int centerX, int centerZ, int radius, PregenShape shape) {
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.radius = radius;
            this.shape = shape;
            int chunkX = Math.floorDiv(centerX, 16);
            int chunkZ = Math.floorDiv(centerZ, 16);
            int ring = Math.max(Math.max(Math.abs(Math.floorDiv(centerX - radius, 16) - chunkX),
                            Math.abs(Math.floorDiv(centerX + radius, 16) - chunkX)),
                    Math.max(Math.abs(Math.floorDiv(centerZ - radius, 16) - chunkZ),
                            Math.abs(Math.floorDiv(centerZ + radius, 16) - chunkZ)));
            this.candidates = (ring * 2L + 1L) * (ring * 2L + 1L);
            long count = 0L;
            for (long index = 0; index < candidates; index++) {
                Coordinate coordinate = candidateAt(index);
                if (accepts(coordinate.x(), coordinate.z())) {
                    count++;
                }
            }
            this.target = count;
        }

        private boolean hasNext() {
            for (long probe = cursor; probe < candidates; probe++) {
                Coordinate coordinate = candidateAt(probe);
                if (accepts(coordinate.x(), coordinate.z())) {
                    return true;
                }
            }
            return false;
        }

        private long nextPacked() {
            while (cursor < candidates) {
                Coordinate coordinate = candidateAt(cursor++);
                if (accepts(coordinate.x(), coordinate.z())) {
                    return (coordinate.x() & 0xffffffffL) | ((long) coordinate.z() << 32);
                }
            }
            throw new AssertionError("exhausted");
        }

        private Coordinate candidateAt(long index) {
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
            return new Coordinate(Math.floorDiv(centerX, 16) + (int) x, Math.floorDiv(centerZ, 16) + (int) z);
        }

        private boolean accepts(int chunkX, int chunkZ) {
            long minimumX = (long) chunkX * 16L;
            long minimumZ = (long) chunkZ * 16L;
            long maximumX = minimumX + 15L;
            long maximumZ = minimumZ + 15L;
            if (shape == PregenShape.SQUARE) {
                return maximumX >= (long) centerX - radius && minimumX <= (long) centerX + radius
                        && maximumZ >= (long) centerZ - radius && minimumZ <= (long) centerZ + radius;
            }
            long deltaX = Math.max(minimumX, Math.min(maximumX, centerX)) - centerX;
            long deltaZ = Math.max(minimumZ, Math.min(maximumZ, centerZ)) - centerZ;
            return deltaX * deltaX + deltaZ * deltaZ <= (long) radius * radius;
        }
    }

    private record Coordinate(int x, int z) {
    }
}
