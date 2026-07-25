package dev.t2me;

import com.mojang.datafixers.util.Either;
import dev.t2me.mixin.ServerChunkCacheAccessor;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class PregenService {
    public static final PregenService INSTANCE = new PregenService();

    private static final TicketType<TicketKey> PREGEN_TICKET = TicketType.create(
            T2ME.MOD_ID,
            Comparator.comparing(TicketKey::jobId)
                    .thenComparingLong(TicketKey::ticketId)
    );
    private static final int TICKET_DISTANCE = 0;

    private final AdaptiveLimiter limiter = new AdaptiveLimiter();
    private final ConcurrentLinkedQueue<CompletionEvent> completionQueue =
            new ConcurrentLinkedQueue<>();
    private final PregenDisplay display = new PregenDisplay();

    private volatile MinecraftServer server;
    private T2MEData data;
    private PregenJob job;
    private CompatibilityGuard.Report compatibility;
    private AdaptiveLimiter.Decision lastDecision =
            new AdaptiveLimiter.Decision(0, 0, "not started", 0L);
    private long tickStartedNanos;
    private long tickCounter;
    private long autoResumeAtTick = Long.MAX_VALUE;
    private long lastProgressLogTick;
    private long ticketSequence;

    private PregenService() {
    }

    public void attach(MinecraftServer server) {
        if (T2MEConfig.migrateLegacyPerformanceDefaults()) {
            T2ME.LOGGER.info(
                    "Migrated the exact T2ME 0.1 scheduler defaults to the 0.2 development performance profile"
            );
        }
        this.server = server;
        this.job = null;
        this.tickCounter = 0L;
        this.tickStartedNanos = 0L;
        this.ticketSequence = 0L;
        this.autoResumeAtTick = Long.MAX_VALUE;
        this.completionQueue.clear();
        this.limiter.reset();
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
        display.attach(server);
        updateWorldgenActivation();

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
        display.detach();
        if (job != null && !job.state().isTerminal()) {
            releaseAllTickets(job);
            job.requeueAllInFlight();
            // Preserve RUNNING in SavedData as an explicit request to resume.
            // On the next attach it is converted to PAUSED first, then resumed
            // only after the configured healthy-start delay.
            persist();
        }
        ThreadedWorldgenEngine.shutdown();
        completionQueue.clear();
        server = null;
        data = null;
        autoResumeAtTick = Long.MAX_VALUE;
    }

    public void tickStart() {
        tickStartedNanos = System.nanoTime();
        drainCompletions();
    }

    public void tickEnd() {
        if (server == null) {
            return;
        }

        long now = System.nanoTime();
        tickCounter++;
        double cps5 = job == null ? 0.0D : job.completionsPerSecond(5L, now);
        int inFlight = job == null ? 0 : job.inFlightCount();
        lastDecision = limiter.decide(server, cps5, inFlight, tickCounter);

        if (job != null
                && tickCounter >= autoResumeAtTick
                && job.state() == JobState.PAUSED) {
            autoResumeAtTick = Long.MAX_VALUE;
            if (!compatibility.blocksStart() && job.resume()) {
                T2ME.LOGGER.info("Auto-resumed T2ME job {}", job.id());
                updateWorldgenActivation();
                persist();
            }
        }

        if (job != null && job.state() == JobState.RUNNING) {
            long stallAge = job.oldestInFlightAgeNanos(now);
            long stallLimit = T2MEConfig.STALL_TIMEOUT_SECONDS.get() * 1_000_000_000L;
            if (stallAge >= stallLimit && job.inFlightCount() > 0) {
                long packed = job.oldestInFlightPacked();
                releaseAllTickets(job);
                job.requeueAllInFlight();
                job.pause(
                        "stalled for " + (stallAge / 1_000_000_000L)
                                + "s at chunk "
                                + SpiralChunkPlan.unpackX(packed) + ","
                                + SpiralChunkPlan.unpackZ(packed)
                );
                T2ME.LOGGER.error("{}", job.message());
                updateWorldgenActivation();
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
        display.update(tickCounter, progressView());
        if (tickStartedNanos != 0L) {
            limiter.recordTick(System.nanoTime() - tickStartedNanos);
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
        updateWorldgenActivation();
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
        return OperationResult.success(
                "Started " + shape.serializedName() + " pregeneration for "
                        + PregenView.formatCount(job.target())
                        + " chunks. Use /t2me status for live details."
        );
    }

    public OperationResult pause() {
        if (job == null) {
            return OperationResult.failure("no T2ME job exists");
        }
        if (job.state() != JobState.RUNNING) {
            return OperationResult.failure("job is " + job.state().name().toLowerCase(Locale.ROOT));
        }
        job.pause("paused by operator");
        updateWorldgenActivation();
        persist();
        return OperationResult.success(
                "Pregeneration paused at "
                        + String.format(Locale.ROOT, "%.2f%%", progressView().percent())
                        + "."
        );
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
        updateWorldgenActivation();
        persist();
        return OperationResult.success("Pregeneration resumed.");
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
        updateWorldgenActivation();
        persist();
        return OperationResult.success("Pregeneration cancelled; all T2ME tickets were released.");
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
        ThreadedWorldgenEngine.Stats engine = ThreadedWorldgenEngine.stats();
        return String.format(
                Locale.ROOT,
                "T2ME metrics: tickEWMA=%.2fms tickPeak=%.2fms admission=%d window=%d "
                        + "heapHeadroom=%.2fGiB players=%d latencyEWMA=%.1fms "
                        + "workers=%d/%d stageQueue=%d locks=%d | %s",
                limiter.tickEwmaMillis(),
                limiter.tickPeakMillis(),
                lastDecision.maxInFlight(),
                lastDecision.adaptiveWindow(),
                headroomGiB,
                server == null ? 0 : server.getPlayerCount(),
                job == null ? 0.0D : job.latencyEwmaMillis(),
                engine.activeWorkers(),
                engine.workers(),
                engine.queuedStages(),
                engine.lockedCoordinates(),
                statusLine()
        );
    }

    public String configLine() {
        return "T2ME config: min/initial/maxInFlight="
                + T2MEConfig.MIN_IN_FLIGHT.get() + "/"
                + T2MEConfig.INITIAL_IN_FLIGHT.get() + "/"
                + T2MEConfig.MAX_IN_FLIGHT.get()
                + ", dispatchPerTick=" + T2MEConfig.MAX_DISPATCH_PER_TICK.get()
                + ", completionsPerTick=" + T2MEConfig.MAX_COMPLETIONS_PER_TICK.get()
                + ", targetMSPT=" + T2MEConfig.TARGET_TICK_MILLIS.get()
                + ", hardStopMSPT=" + T2MEConfig.HARD_STOP_TICK_MILLIS.get()
                + ", minHeapMiB=" + T2MEConfig.MIN_HEAP_HEADROOM_MIB.get()
                + ", threadedWorldgen=" + T2MEConfig.THREADED_WORLDGEN.get()
                + ", worldgenThreads=" + T2MEConfig.WORLDGEN_THREADS.get()
                + ", threadedStructures=" + T2MEConfig.THREADED_STRUCTURES.get()
                + ", threadedFeatures=" + T2MEConfig.THREADED_FEATURES.get()
                + ", stallSeconds=" + T2MEConfig.STALL_TIMEOUT_SECONDS.get()
                + ", maxRetries=" + T2MEConfig.MAX_RETRIES.get()
                + ", autoResume=" + T2MEConfig.AUTO_RESUME.get()
                + ", allowCompeting=" + T2MEConfig.ALLOW_COMPETING_PREGENERATORS.get();
    }

    public PregenView progressView() {
        if (job == null) {
            return PregenView.none(
                    limiter.tickEwmaMillis(),
                    lastDecision.maxInFlight(),
                    lastDecision.adaptiveWindow(),
                    lastDecision.reason(),
                    ThreadedWorldgenEngine.stats()
            );
        }
        long now = System.nanoTime();
        double cps5 = job.completionsPerSecond(5L, now);
        double cps60 = job.completionsPerSecond(60L, now);
        long remaining = Math.max(0L, job.target() - job.completed());
        long etaSeconds = cps60 > 0.01D
                ? (long) Math.ceil(remaining / cps60)
                : -1L;
        return new PregenView(
                job.id(),
                job.state(),
                job.dimension(),
                job.centerBlockX(),
                job.centerBlockZ(),
                job.radiusBlocks(),
                job.shape(),
                job.completed(),
                job.target(),
                job.inFlightCount(),
                job.retryQueueSize(),
                job.failures(),
                cps5,
                cps60,
                etaSeconds,
                job.latencyEwmaMillis(),
                limiter.tickEwmaMillis(),
                lastDecision.maxInFlight(),
                lastDecision.adaptiveWindow(),
                lastDecision.reason(),
                job.message(),
                ThreadedWorldgenEngine.stats()
        );
    }

    public void playerJoined(net.minecraft.server.level.ServerPlayer player) {
        display.playerJoined(player);
    }

    public void playerLeft(net.minecraft.server.level.ServerPlayer player) {
        display.playerLeft(player);
    }

    private void dispatch(long nowNanos) {
        if (job == null || server == null) {
            return;
        }
        ServerLevel level = resolveLevel(job.dimension());
        if (level == null) {
            job.requeueAllInFlight();
            job.pause(
                    "dimension unloaded: " + job.dimension()
                            + "; reload it before resuming"
            );
            updateWorldgenActivation();
            persist();
            return;
        }

        int capacity = Math.max(0, lastDecision.maxInFlight() - job.inFlightCount());
        int dispatchCount = Math.min(capacity, T2MEConfig.MAX_DISPATCH_PER_TICK.get());
        if (dispatchCount == 0) {
            return;
        }

        ServerChunkCache chunks = level.getChunkSource();
        ServerChunkCacheAccessor direct = (ServerChunkCacheAccessor) chunks;
        List<IssuedRequest> batch = new ArrayList<>(dispatchCount);
        for (int issued = 0; issued < dispatchCount && job.hasDispatchableWork(); issued++) {
            long packed = job.pollNext();
            int chunkX = SpiralChunkPlan.unpackX(packed);
            int chunkZ = SpiralChunkPlan.unpackZ(packed);
            ChunkPos position = new ChunkPos(chunkX, chunkZ);
            TicketKey ticket = new TicketKey(job.id(), ++ticketSequence, packed);
            chunks.addRegionTicket(PREGEN_TICKET, position, TICKET_DISTANCE, ticket);
            job.markInFlight(packed, nowNanos, ticket.ticketId());
            batch.add(new IssuedRequest(ticket, position, job.dimension()));
            if (job.state() != JobState.RUNNING) {
                break;
            }
        }
        if (batch.isEmpty()) {
            return;
        }

        try {
            /*
             * One distance-manager pass materializes every holder added above.
             * Calling the private main-thread method directly avoids the
             * public method's managedBlock/off-thread round trip and its extra
             * UNKNOWN ticket. This is the same fast Forge entry point used by
             * modern high-throughput pregenerators.
             */
            direct.t2me$runDistanceManagerUpdates();
        } catch (Throwable error) {
            String failure = rootMessage(error);
            batch.forEach(request -> completionQueue.add(new CompletionEvent(
                    request.ticket(),
                    request.dimension(),
                    false,
                    failure
            )));
            return;
        }

        for (IssuedRequest request : batch) {
            CompletableFuture<Either<
                    ChunkAccess,
                    ChunkHolder.ChunkLoadingFailure
                    >> future;
            try {
                future = direct.t2me$getChunkFutureMainThread(
                        request.position().x,
                        request.position().z,
                        ChunkStatus.FULL,
                        false
                );
            } catch (Throwable error) {
                completionQueue.add(new CompletionEvent(
                        request.ticket(),
                        request.dimension(),
                        false,
                        rootMessage(error)
                ));
                continue;
            }
            future.whenComplete((result, error) ->
                    completionQueue.add(completionEvent(request, result, error)));
        }
    }

    private void drainCompletions() {
        if (server == null || completionQueue.isEmpty()) {
            return;
        }
        Set<ServerChunkCache> touchedCaches = new HashSet<>();
        boolean persistTransition = false;
        int maximum = T2MEConfig.MAX_COMPLETIONS_PER_TICK.get();
        for (int drained = 0; drained < maximum; drained++) {
            CompletionEvent event = completionQueue.poll();
            if (event == null) {
                break;
            }
            TicketKey ticket = event.ticket();
            ServerLevel level = resolveLevel(event.dimension());
            if (level != null) {
                ServerChunkCache chunks = level.getChunkSource();
                chunks.removeRegionTicket(
                        PREGEN_TICKET,
                        new ChunkPos(
                                SpiralChunkPlan.unpackX(ticket.packed()),
                                SpiralChunkPlan.unpackZ(ticket.packed())
                        ),
                        TICKET_DISTANCE,
                        ticket
                );
                touchedCaches.add(chunks);
            }
            if (job == null || !job.id().equals(ticket.jobId())) {
                continue;
            }

            JobState before = job.state();
            PregenJob.Completion completion = job.complete(
                    ticket.packed(),
                    ticket.ticketId(),
                    event.successful(),
                    event.failure(),
                    T2MEConfig.MAX_RETRIES.get(),
                    System.nanoTime()
            );
            if (completion == PregenJob.Completion.PAUSED) {
                T2ME.LOGGER.error("{}", job.message());
                persistTransition = true;
            } else if (before != JobState.COMPLETED
                    && job.state() == JobState.COMPLETED) {
                T2ME.LOGGER.info("Completed {}", statusLine());
                persistTransition = true;
            }
        }
        for (ServerChunkCache chunks : touchedCaches) {
            ((ServerChunkCacheAccessor) chunks).t2me$runDistanceManagerUpdates();
        }
        updateWorldgenActivation();
        if (persistTransition) {
            persist();
        }
    }

    private static CompletionEvent completionEvent(
            IssuedRequest request,
            Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure> result,
            Throwable error
    ) {
        if (error != null) {
            return new CompletionEvent(
                    request.ticket(),
                    request.dimension(),
                    false,
                    rootMessage(error)
            );
        }
        if (result != null && result.left().isPresent()) {
            return new CompletionEvent(
                    request.ticket(),
                    request.dimension(),
                    true,
                    ""
            );
        }
        String failure = result != null && result.right().isPresent()
                ? result.right().get().toString()
                : "unknown chunk loading failure";
        return new CompletionEvent(
                request.ticket(),
                request.dimension(),
                false,
                failure
        );
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
        if (!pending.isEmpty()) {
            ((ServerChunkCacheAccessor) level.getChunkSource())
                    .t2me$runDistanceManagerUpdates();
        }
    }

    private void updateWorldgenActivation() {
        if (!T2MEConfig.THREADED_WORLDGEN.get()
                || job == null
                || (job.state() != JobState.RUNNING && job.inFlightCount() == 0)) {
            ThreadedWorldgenEngine.deactivate();
            return;
        }
        ThreadedWorldgenEngine.activate(
                new ThreadedWorldgenEngine.Scope(
                        job.dimension(),
                        job.centerBlockX(),
                        job.centerBlockZ(),
                        job.radiusBlocks(),
                        job.shape()
                ),
                T2MEConfig.WORLDGEN_THREADS.get(),
                T2MEConfig.THREADED_STRUCTURES.get(),
                T2MEConfig.THREADED_FEATURES.get()
        );
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

    public record OperationResult(boolean successful, String message) {
        public static OperationResult success(String message) {
            return new OperationResult(true, message);
        }

        public static OperationResult failure(String message) {
            return new OperationResult(false, message);
        }
    }

    private record TicketKey(UUID jobId, long ticketId, long packed) {
    }

    private record IssuedRequest(
            TicketKey ticket,
            ChunkPos position,
            String dimension
    ) {
    }

    private record CompletionEvent(
            TicketKey ticket,
            String dimension,
            boolean successful,
            String failure
    ) {
    }
}
