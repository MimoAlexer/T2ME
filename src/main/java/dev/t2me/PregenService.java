package dev.t2me;

import com.mojang.datafixers.util.Either;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class PregenService {
    public static final PregenService INSTANCE = new PregenService();

    private static final TicketType<TicketKey> PREGEN_TICKET = TicketType.create(
            T2ME.MOD_ID,
            Comparator.comparing(TicketKey::jobId)
                    .thenComparing(TicketKey::ticketId)
    );
    private static final int TICKET_DISTANCE = 0;

    private final AdaptiveLimiter limiter = new AdaptiveLimiter();

    private volatile MinecraftServer server;
    private ThreadPoolExecutor requestExecutor;
    private T2MEData data;
    private PregenJob job;
    private CompatibilityGuard.Report compatibility;
    private AdaptiveLimiter.Decision lastDecision =
            new AdaptiveLimiter.Decision(0, "not started", 0L);
    private long tickStartedNanos;
    private long tickCounter;
    private long autoResumeAtTick = Long.MAX_VALUE;
    private long lastProgressLogTick;

    private PregenService() {
    }

    public void attach(MinecraftServer server) {
        if (requestExecutor != null) {
            requestExecutor.shutdownNow();
        }
        requestExecutor = createRequestExecutor();
        this.server = server;
        this.job = null;
        this.tickCounter = 0L;
        this.autoResumeAtTick = Long.MAX_VALUE;
        this.compatibility = CompatibilityGuard.inspect();
        this.data = server.overworld().getDataStorage().computeIfAbsent(
                T2MEData::load,
                T2MEData::new,
                T2MEData.DATA_NAME
        );

        data.snapshot().ifPresent(snapshot -> {
            this.job = PregenJob.restore(snapshot);
            if (snapshot.state() == JobState.RUNNING) {
                this.job.pause("paused safely after server restart");
                if (T2MEConfig.AUTO_RESUME.get()) {
                    autoResumeAtTick = 200L;
                }
                persist();
            }
        });

        T2ME.LOGGER.info("T2ME compatibility: {}", compatibility.summary());
        if (compatibility.canaryLoaded()) {
            T2ME.LOGGER.info(
                    "Canary detected; T2ME intentionally leaves Lithium-style engine patches to Canary"
            );
        }
        if (compatibility.blocksStart()) {
            T2ME.LOGGER.warn(
                    "T2ME start guard active because these mods are loaded: {}",
                    compatibility.blockingReason()
            );
        }
    }

    public void detach() {
        if (server == null) {
            return;
        }
        if (job != null && !job.state().isTerminal()) {
            releaseAllTickets(job);
            job.requeueAllInFlight();
            // Preserve RUNNING in SavedData as an explicit request to resume.
            // On the next attach it is converted to PAUSED first, then resumed
            // only after the configured healthy-start delay.
            persist();
        }
        server = null;
        if (requestExecutor != null) {
            requestExecutor.shutdownNow();
            requestExecutor = null;
        }
        data = null;
        autoResumeAtTick = Long.MAX_VALUE;
    }

    public void tickStart() {
        tickStartedNanos = System.nanoTime();
    }

    public void tickEnd() {
        if (server == null) {
            return;
        }

        long now = System.nanoTime();
        if (tickStartedNanos != 0L) {
            limiter.recordTick(now - tickStartedNanos);
        }
        tickCounter++;
        lastDecision = limiter.decide(server);

        if (job != null
                && tickCounter >= autoResumeAtTick
                && job.state() == JobState.PAUSED) {
            autoResumeAtTick = Long.MAX_VALUE;
            if (!compatibility.blocksStart() && job.resume()) {
                T2ME.LOGGER.info("Auto-resumed T2ME job {}", job.id());
                persist();
            }
        }

        if (job != null && job.state() == JobState.RUNNING) {
            long stallAge = job.oldestInFlightAgeNanos(now);
            long stallLimit = T2MEConfig.STALL_TIMEOUT_SECONDS.get() * 1_000_000_000L;
            if (stallAge >= stallLimit && job.inFlightCount() > 0) {
                long packed = job.oldestInFlightPacked();
                job.pause(
                        "stalled for " + (stallAge / 1_000_000_000L)
                                + "s at chunk "
                                + SpiralChunkPlan.unpackX(packed) + ","
                                + SpiralChunkPlan.unpackZ(packed)
                );
                T2ME.LOGGER.error("{}", job.message());
                persist();
            } else {
                dispatch(now);
            }
        }

        int saveInterval = T2MEConfig.SAVE_INTERVAL_TICKS.get();
        if (job != null && tickCounter % saveInterval == 0L) {
            persist();
        }

        int logSeconds = T2MEConfig.PROGRESS_LOG_INTERVAL_SECONDS.get();
        long logIntervalTicks = logSeconds * 20L;
        if (job != null
                && logIntervalTicks > 0L
                && tickCounter - lastProgressLogTick >= logIntervalTicks) {
            lastProgressLogTick = tickCounter;
            T2ME.LOGGER.info("{}", statusLine());
        }
    }

    public OperationResult start(
            String dimension,
            int centerBlockX,
            int centerBlockZ,
            int radiusBlocks,
            PregenShape shape
    ) {
        if (server == null) {
            return OperationResult.failure("T2ME is not attached to a server");
        }
        compatibility = CompatibilityGuard.inspect();
        if (compatibility.blocksStart()) {
            return OperationResult.failure(
                    "start blocked by: " + compatibility.blockingReason()
                            + ". Disable the competing mod or explicitly change the T2ME guard."
            );
        }
        if (job != null && !job.state().isTerminal()) {
            return OperationResult.failure(
                    "job " + job.id() + " is already " + job.state().name().toLowerCase(Locale.ROOT)
            );
        }
        ServerLevel level = resolveLevel(dimension);
        if (level == null) {
            return OperationResult.failure("unknown or unloaded dimension: " + dimension);
        }

        try {
            job = new PregenJob(
                    dimension,
                    centerBlockX,
                    centerBlockZ,
                    radiusBlocks,
                    shape
            );
        } catch (RuntimeException error) {
            return OperationResult.failure("could not create plan: " + rootMessage(error));
        }
        autoResumeAtTick = Long.MAX_VALUE;
        lastProgressLogTick = tickCounter;
        persist();
        T2ME.LOGGER.info(
                "Started T2ME job {}: dimension={}, center={},{} radius={} shape={} target={}",
                job.id(),
                dimension,
                centerBlockX,
                centerBlockZ,
                radiusBlocks,
                shape.serializedName(),
                job.target()
        );
        return OperationResult.success(statusLine());
    }

    public OperationResult pause() {
        if (job == null) {
            return OperationResult.failure("no T2ME job exists");
        }
        if (job.state() != JobState.RUNNING) {
            return OperationResult.failure("job is " + job.state().name().toLowerCase(Locale.ROOT));
        }
        job.pause("paused by operator");
        persist();
        return OperationResult.success(statusLine());
    }

    public OperationResult resume() {
        if (job == null) {
            return OperationResult.failure("no T2ME job exists");
        }
        compatibility = CompatibilityGuard.inspect();
        if (compatibility.blocksStart()) {
            return OperationResult.failure("resume blocked by: " + compatibility.blockingReason());
        }
        if (!job.resume()) {
            return OperationResult.failure("job is " + job.state().name().toLowerCase(Locale.ROOT));
        }
        persist();
        return OperationResult.success(statusLine());
    }

    public OperationResult cancel() {
        if (job == null) {
            return OperationResult.failure("no T2ME job exists");
        }
        if (job.state().isTerminal()) {
            return OperationResult.failure("job is already " + job.state().name().toLowerCase(Locale.ROOT));
        }
        releaseAllTickets(job);
        job.cancel();
        persist();
        return OperationResult.success(statusLine());
    }

    public String statusLine() {
        if (job == null) {
            return "T2ME: no job";
        }
        long now = System.nanoTime();
        double cps5 = job.completionsPerSecond(5L, now);
        double cps60 = job.completionsPerSecond(60L, now);
        double percent = job.target() == 0L
                ? 100.0D
                : job.completed() * 100.0D / job.target();
        long remaining = Math.max(0L, job.target() - job.completed());
        String eta = cps60 > 0.01D
                ? formatDuration((long) Math.ceil(remaining / cps60))
                : "unknown";

        return String.format(
                Locale.ROOT,
                "T2ME job=%s state=%s dim=%s center=%d,%d radius=%d shape=%s "
                        + "done=%d/%d (%.2f%%) inFlight=%d retryQueue=%d failures=%d "
                        + "cps5=%.2f cps60=%.2f eta=%s throttle=%s message=%s",
                job.id(),
                job.state().name().toLowerCase(Locale.ROOT),
                job.dimension(),
                job.centerBlockX(),
                job.centerBlockZ(),
                job.radiusBlocks(),
                job.shape().serializedName(),
                job.completed(),
                job.target(),
                percent,
                job.inFlightCount(),
                job.retryQueueSize(),
                job.failures(),
                cps5,
                cps60,
                eta,
                lastDecision.reason(),
                job.message()
        );
    }

    public String metricsLine() {
        double headroomGiB = lastDecision.heapHeadroomBytes() / (1024.0D * 1024.0D * 1024.0D);
        return String.format(
                Locale.ROOT,
                "T2ME metrics: tickEWMA=%.2fms tickPeak=%.2fms admission=%d "
                        + "heapHeadroom=%.2fGiB players=%d | %s",
                limiter.tickEwmaMillis(),
                limiter.tickPeakMillis(),
                lastDecision.maxInFlight(),
                headroomGiB,
                server == null ? 0 : server.getPlayerCount(),
                statusLine()
        );
    }

    public String configLine() {
        return "T2ME config: maxInFlight=" + T2MEConfig.MAX_IN_FLIGHT.get()
                + ", dispatchPerTick=" + T2MEConfig.MAX_DISPATCH_PER_TICK.get()
                + ", targetMSPT=" + T2MEConfig.TARGET_TICK_MILLIS.get()
                + ", hardStopMSPT=" + T2MEConfig.HARD_STOP_TICK_MILLIS.get()
                + ", minHeapMiB=" + T2MEConfig.MIN_HEAP_HEADROOM_MIB.get()
                + ", stallSeconds=" + T2MEConfig.STALL_TIMEOUT_SECONDS.get()
                + ", maxRetries=" + T2MEConfig.MAX_RETRIES.get()
                + ", autoResume=" + T2MEConfig.AUTO_RESUME.get()
                + ", allowCompeting=" + T2MEConfig.ALLOW_COMPETING_PREGENERATORS.get();
    }

    private void dispatch(long nowNanos) {
        if (job == null || server == null) {
            return;
        }
        ServerLevel level = resolveLevel(job.dimension());
        if (level == null) {
            job.fail("dimension unloaded: " + job.dimension());
            persist();
            return;
        }

        int capacity = Math.max(0, lastDecision.maxInFlight() - job.inFlightCount());
        int dispatchCount = Math.min(capacity, T2MEConfig.MAX_DISPATCH_PER_TICK.get());
        for (int issued = 0; issued < dispatchCount && job.hasDispatchableWork(); issued++) {
            long packed = job.pollNext();
            issue(level, job.id(), packed, nowNanos);
            if (job.state() != JobState.RUNNING) {
                break;
            }
        }
    }

    private void issue(ServerLevel level, UUID jobId, long packed, long nowNanos) {
        int chunkX = SpiralChunkPlan.unpackX(packed);
        int chunkZ = SpiralChunkPlan.unpackZ(packed);
        ChunkPos position = new ChunkPos(chunkX, chunkZ);
        ServerChunkCache chunks = level.getChunkSource();
        TicketKey ticket = new TicketKey(jobId, UUID.randomUUID(), packed);

        chunks.addRegionTicket(PREGEN_TICKET, position, TICKET_DISTANCE, ticket);
        job.markInFlight(packed, nowNanos, ticket.ticketId());

        final CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> future;
        MinecraftServer boundServer = server;
        try {
            ThreadPoolExecutor executor = requestExecutor;
            if (executor == null || executor.isShutdown()) {
                throw new RejectedExecutionException("request coordinator is stopped");
            }
            /*
             * In Forge 1.20.1 this public method has two paths. Calling it on
             * ServerChunkCache's main thread performs a managedBlock until the
             * requested status completes. Calling it off that thread schedules
             * the request onto the main-thread processor and returns an outer
             * future. The coordinator does only that supported API call; it
             * never reads or mutates a chunk, level, ticket, or SavedData.
             */
            future = CompletableFuture
                    .supplyAsync(() -> {
                        if (server != boundServer) {
                            throw new CompletionException(
                                    new IllegalStateException("server detached")
                            );
                        }
                        return chunks.getChunkFuture(
                                chunkX,
                                chunkZ,
                                ChunkStatus.FULL,
                                true
                        );
                    }, executor)
                    .thenCompose(inner -> inner);
        } catch (RuntimeException error) {
            chunks.removeRegionTicket(PREGEN_TICKET, position, TICKET_DISTANCE, ticket);
            job.complete(
                    packed,
                    ticket.ticketId(),
                    false,
                    rootMessage(error),
                    T2MEConfig.MAX_RETRIES.get(),
                    System.nanoTime()
            );
            persist();
            return;
        }

        String dimension = job.dimension();
        future.whenComplete((result, error) -> boundServer.execute(
                () -> completeOnServerThread(ticket, dimension, result, error)
        ));
    }

    private void completeOnServerThread(
            TicketKey ticket,
            String dimension,
            Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure> result,
            Throwable error
    ) {
        long packed = ticket.packed();
        ServerLevel level = resolveLevel(dimension);
        if (level != null) {
            ChunkPos position = new ChunkPos(
                    SpiralChunkPlan.unpackX(packed),
                    SpiralChunkPlan.unpackZ(packed)
            );
            level.getChunkSource().removeRegionTicket(
                    PREGEN_TICKET,
                    position,
                    TICKET_DISTANCE,
                    ticket
            );
        }
        if (job == null || !job.id().equals(ticket.jobId())) {
            return;
        }

        boolean successful = error == null && result != null && result.left().isPresent();
        String failure = "unknown chunk loading failure";
        if (error != null) {
            failure = rootMessage(error);
        } else if (result != null && result.right().isPresent()) {
            failure = result.right().get().toString();
        }

        PregenJob.Completion completion = job.complete(
                packed,
                ticket.ticketId(),
                successful,
                failure,
                T2MEConfig.MAX_RETRIES.get(),
                System.nanoTime()
        );
        if (completion == PregenJob.Completion.PAUSED) {
            T2ME.LOGGER.error("{}", job.message());
        } else if (job.state() == JobState.COMPLETED) {
            T2ME.LOGGER.info("Completed {}", statusLine());
        }
        if (completion != PregenJob.Completion.IGNORED) {
            persist();
        }
    }

    private void releaseAllTickets(PregenJob targetJob) {
        ServerLevel level = resolveLevel(targetJob.dimension());
        if (level == null) {
            return;
        }
        List<PregenJob.TicketReference> pending = targetJob.inFlightTickets();
        for (PregenJob.TicketReference reference : pending) {
            long packed = reference.packed();
            ChunkPos position = new ChunkPos(
                    SpiralChunkPlan.unpackX(packed),
                    SpiralChunkPlan.unpackZ(packed)
            );
            level.getChunkSource().removeRegionTicket(
                    PREGEN_TICKET,
                    position,
                    TICKET_DISTANCE,
                    new TicketKey(targetJob.id(), reference.ticketId(), packed)
            );
        }
    }

    private ServerLevel resolveLevel(String dimension) {
        if (server == null) {
            return null;
        }
        ResourceLocation location = ResourceLocation.tryParse(dimension);
        if (location == null) {
            return null;
        }
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, location);
        return server.getLevel(key);
    }

    private void persist() {
        if (data == null) {
            return;
        }
        if (job == null) {
            data.clear();
        } else {
            data.snapshot(job.snapshot());
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        if (cursor instanceof CompletionException && cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return cursor.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static String formatDuration(long seconds) {
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long remainder = seconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%dh%02dm%02ds", hours, minutes, remainder);
        }
        return String.format(Locale.ROOT, "%dm%02ds", minutes, remainder);
    }

    private static ThreadPoolExecutor createRequestExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(32),
                runnable -> {
                    Thread thread = new Thread(runnable, "T2ME-Request-Coordinator");
                    thread.setDaemon(true);
                    thread.setUncaughtExceptionHandler((ignored, error) ->
                            T2ME.LOGGER.error("T2ME request coordinator failed", error));
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        executor.prestartCoreThread();
        return executor;
    }

    public record OperationResult(boolean successful, String message) {
        public static OperationResult success(String message) {
            return new OperationResult(true, message);
        }

        public static OperationResult failure(String message) {
            return new OperationResult(false, message);
        }
    }

    private record TicketKey(UUID jobId, UUID ticketId, long packed) {
    }
}
