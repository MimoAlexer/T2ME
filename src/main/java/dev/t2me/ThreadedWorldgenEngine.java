package dev.t2me;

import com.mojang.datafixers.util.Either;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Experimental C2ME-inspired stage executor.
 *
 * <p>Only chunks inside the active T2ME pregeneration scope are eligible.
 * Lighting and FULL conversion remain on Forge's native path. FEATURES and
 * structure stages are separately gated because modded generators frequently
 * keep mutable state that is not safe to access concurrently.</p>
 */
public final class ThreadedWorldgenEngine {
    private static final int CHUNK_SIZE = 16;
    private static final int DEPENDENCY_MARGIN_CHUNKS = 12;
    private static final NeighborhoodLockManager LOCKS = new NeighborhoodLockManager();
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();
    private static final ThreadLocal<Boolean> WORLDGEN_WORKER =
            ThreadLocal.withInitial(() -> false);
    private static volatile ThreadPoolExecutor executor;
    private static final Executor INLINE_WORLDGEN_EXECUTOR = command -> {
        ThreadPoolExecutor pool = executor;
        if (Boolean.TRUE.equals(WORLDGEN_WORKER.get())) {
            command.run();
        } else if (pool != null && !pool.isShutdown()) {
            pool.execute(command);
        } else {
            throw new RejectedExecutionException("T2ME worldgen engine is stopped");
        }
    };

    private static volatile Scope activeScope;
    private static volatile boolean threadedStructures;
    private static volatile boolean threadedFeatures;
    private static volatile int configuredParallelism;

    private ThreadedWorldgenEngine() {
    }

    public static synchronized void activate(
            Scope scope,
            int requestedThreads,
            boolean structures,
            boolean features
    ) {
        activeScope = Objects.requireNonNull(scope, "scope");
        threadedStructures = structures;
        threadedFeatures = features;

        int resolvedThreads = requestedThreads > 0
                ? requestedThreads
                : Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
        resolvedThreads = Math.max(1, Math.min(64, resolvedThreads));
        if (executor == null
                || executor.isShutdown()
                || configuredParallelism != resolvedThreads) {
            if (executor != null) {
                executor.shutdown();
            }
            configuredParallelism = resolvedThreads;
            executor = createExecutor(resolvedThreads);
            T2ME.LOGGER.info(
                    "T2ME threaded worldgen activated with {} workers",
                    resolvedThreads
            );
        }
    }

    public static void deactivate() {
        activeScope = null;
    }

    public static synchronized void shutdown() {
        activeScope = null;
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
        configuredParallelism = 0;
    }

    public static CompletableFuture<Either<
            ChunkAccess,
            ChunkHolder.ChunkLoadingFailure
            >> schedule(
            ChunkStatus status,
            ServerLevel level,
            ChunkPos position,
            Executor originalExecutor,
            Function<Executor, CompletableFuture<Either<
                    ChunkAccess,
                    ChunkHolder.ChunkLoadingFailure
                    >>> action
    ) {
        Scope scope = activeScope;
        ThreadPoolExecutor pool = executor;
        int lockRadius = lockRadius(status);
        if (scope == null
                || pool == null
                || pool.isShutdown()
                || !scope.contains(level, position)
                || lockRadius < 0) {
            return action.apply(originalExecutor);
        }

        long[] lockCoordinates = neighborhood(position, lockRadius);
        CompletableFuture<Either<
                ChunkAccess,
                ChunkHolder.ChunkLoadingFailure
                >> result = new CompletableFuture<>();
        LOCKS.acquire(lockCoordinates).whenComplete((token, lockError) -> {
            if (lockError != null) {
                result.completeExceptionally(lockError);
                return;
            }
            ThreadPoolExecutor currentPool = executor;
            if (currentPool == null || currentPool.isShutdown()) {
                token.close();
                result.completeExceptionally(new RejectedExecutionException(
                        "T2ME worldgen engine stopped before the stage lock became available"
                ));
                return;
            }
            Runnable stage = () -> runStage(
                    () -> action.apply(INLINE_WORLDGEN_EXECUTOR),
                    token,
                    result
            );
            try {
                executeOnCurrentPool(currentPool, stage);
            } catch (Throwable schedulingError) {
                token.close();
                result.completeExceptionally(schedulingError);
            }
        });
        return result;
    }

    private static void executeOnCurrentPool(
            ThreadPoolExecutor preferred,
            Runnable stage
    ) {
        try {
            preferred.execute(stage);
        } catch (RejectedExecutionException firstRejection) {
            ThreadPoolExecutor replacement = executor;
            if (replacement == null
                    || replacement == preferred
                    || replacement.isShutdown()) {
                throw firstRejection;
            }
            try {
                replacement.execute(stage);
            } catch (RejectedExecutionException secondRejection) {
                secondRejection.addSuppressed(firstRejection);
                throw secondRejection;
            }
        }
    }

    public static Stats stats() {
        ThreadPoolExecutor pool = executor;
        return new Stats(
                activeScope != null,
                configuredParallelism,
                pool == null ? 0 : pool.getActiveCount(),
                pool == null ? 0 : pool.getQueue().size(),
                LOCKS.lockedCoordinates()
        );
    }

    private static void runStage(
            Supplier<CompletableFuture<Either<
                    ChunkAccess,
                    ChunkHolder.ChunkLoadingFailure
                    >>> action,
            NeighborhoodLockManager.Token token,
            CompletableFuture<Either<
                    ChunkAccess,
                    ChunkHolder.ChunkLoadingFailure
                    >> result
    ) {
        final CompletableFuture<Either<
                ChunkAccess,
                ChunkHolder.ChunkLoadingFailure
                >> stage;
        try {
            stage = Objects.requireNonNull(action.get(), "worldgen stage future");
        } catch (Throwable error) {
            token.close();
            result.completeExceptionally(error);
            return;
        }
        stage.whenComplete((value, error) -> {
            token.close();
            if (error == null) {
                result.complete(value);
            } else {
                result.completeExceptionally(error);
            }
        });
    }

    private static int lockRadius(ChunkStatus status) {
        if (status == ChunkStatus.BIOMES
                || status == ChunkStatus.NOISE
                || status == ChunkStatus.SURFACE
                || status == ChunkStatus.CARVERS
                || status == ChunkStatus.SPAWN) {
            return 0;
        }
        if (threadedStructures
                && (status == ChunkStatus.STRUCTURE_STARTS
                || status == ChunkStatus.STRUCTURE_REFERENCES)) {
            return 0;
        }
        if (threadedFeatures && status == ChunkStatus.FEATURES) {
            return 1;
        }
        return -1;
    }

    private static long[] neighborhood(ChunkPos center, int radius) {
        int side = radius * 2 + 1;
        long[] coordinates = new long[side * side];
        int index = 0;
        for (int chunkX = center.x - radius; chunkX <= center.x + radius; chunkX++) {
            for (int chunkZ = center.z - radius; chunkZ <= center.z + radius; chunkZ++) {
                coordinates[index++] = SpiralChunkPlan.pack(chunkX, chunkZ);
            }
        }
        return coordinates;
    }

    private static ThreadPoolExecutor createExecutor(int threads) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread worker = new Thread(
                            () -> {
                                WORLDGEN_WORKER.set(true);
                                try {
                                    runnable.run();
                                } finally {
                                    WORLDGEN_WORKER.remove();
                                }
                            },
                            "T2ME-Worldgen-" + THREAD_SEQUENCE.incrementAndGet()
                    );
                    worker.setDaemon(true);
                    worker.setPriority(Math.max(
                            Thread.MIN_PRIORITY,
                            Thread.NORM_PRIORITY - 1
                    ));
                    worker.setUncaughtExceptionHandler((ignored, error) ->
                            T2ME.LOGGER.error("T2ME worldgen worker failed", error));
                    return worker;
                }
        );
        pool.prestartAllCoreThreads();
        return pool;
    }

    public record Scope(
            String dimension,
            int centerBlockX,
            int centerBlockZ,
            int radiusBlocks,
            PregenShape shape
    ) {
        public boolean contains(ServerLevel level, ChunkPos position) {
            if (!dimension.equals(level.dimension().location().toString())) {
                return false;
            }
            long marginBlocks = (long) DEPENDENCY_MARGIN_CHUNKS * CHUNK_SIZE;
            long effectiveRadius = radiusBlocks + marginBlocks;
            long blockX = (long) position.x * CHUNK_SIZE + CHUNK_SIZE / 2L;
            long blockZ = (long) position.z * CHUNK_SIZE + CHUNK_SIZE / 2L;
            long deltaX = blockX - centerBlockX;
            long deltaZ = blockZ - centerBlockZ;
            if (shape == PregenShape.SQUARE) {
                return Math.abs(deltaX) <= effectiveRadius
                        && Math.abs(deltaZ) <= effectiveRadius;
            }
            return deltaX * deltaX + deltaZ * deltaZ
                    <= effectiveRadius * effectiveRadius;
        }
    }

    public record Stats(
            boolean enabled,
            int workers,
            int activeWorkers,
            int queuedStages,
            int lockedCoordinates
    ) {
    }
}
