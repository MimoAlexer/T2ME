package dev.t2me;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class PregenJob {
    private static final long ONE_SECOND_NANOS = 1_000_000_000L;
    private static final long RATE_BUCKET_NANOS = ONE_SECOND_NANOS / 10L;
    private static final int RATE_BUCKET_COUNT = 601;

    private final UUID id;
    private final String dimension;
    private final SpiralChunkPlan plan;
    private final long createdEpochMillis;
    private final ArrayDeque<Long> retryQueue = new ArrayDeque<>();
    private final Map<Long, Integer> retryCounts = new HashMap<>();
    // A coordinate remains owned by the job even if ticket creation fails after polling.
    private final Set<Long> dispatching = new LinkedHashSet<>();
    private final LinkedHashMap<Long, InFlight> inFlight = new LinkedHashMap<>();
    private final long[] completionBuckets = new long[RATE_BUCKET_COUNT];
    private final long[] completionCounts = new long[RATE_BUCKET_COUNT];

    private JobState state;
    private long completed;
    private long failures;
    private String message;
    private long runStartedNanos;

    public PregenJob(
            String dimension,
            int centerBlockX,
            int centerBlockZ,
            int radiusBlocks,
            PregenShape shape
    ) {
        this(
                UUID.randomUUID(),
                dimension,
                new SpiralChunkPlan(centerBlockX, centerBlockZ, radiusBlocks, shape),
                System.currentTimeMillis(),
                JobState.RUNNING,
                0L,
                0L,
                "running"
        );
    }

    private PregenJob(
            UUID id,
            String dimension,
            SpiralChunkPlan plan,
            long createdEpochMillis,
            JobState state,
            long completed,
            long failures,
            String message
    ) {
        this.id = Objects.requireNonNull(id, "id");
        if (dimension == null || dimension.isBlank() || ResourceLocation.tryParse(dimension) == null) {
            throw new IllegalArgumentException("invalid dimension: " + dimension);
        }
        this.dimension = dimension;
        this.plan = plan;
        this.createdEpochMillis = createdEpochMillis;
        this.state = state;
        this.completed = completed;
        this.failures = failures;
        this.message = message;
        this.runStartedNanos = System.nanoTime();
    }

    public static PregenJob restore(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        SpiralChunkPlan plan = new SpiralChunkPlan(
                snapshot.centerBlockX(),
                snapshot.centerBlockZ(),
                snapshot.radiusBlocks(),
                snapshot.shape()
        );
        validateSnapshot(snapshot, plan);
        plan.cursor(snapshot.cursor());
        PregenJob job = new PregenJob(
                snapshot.id(),
                snapshot.dimension(),
                plan,
                snapshot.createdEpochMillis(),
                snapshot.state(),
                snapshot.completed(),
                snapshot.failures(),
                snapshot.message()
        );
        snapshot.pending().forEach(job.retryQueue::addLast);
        job.retryCounts.putAll(snapshot.retryCounts());
        if (!job.state.isTerminal() && job.completed == plan.targetCount()) {
            job.state = JobState.COMPLETED;
            job.message = "completed";
        }
        return job;
    }

    public long pollNext() {
        if (state.isTerminal()) {
            throw new IllegalStateException("job is " + state);
        }
        Long retry = retryQueue.pollFirst();
        if (retry != null) {
            dispatching.add(retry);
            return retry;
        }
        if (!plan.hasNext()) {
            throw new NoSuchElementException("job exhausted");
        }
        long packed = plan.nextPacked();
        dispatching.add(packed);
        return packed;
    }

    public boolean hasDispatchableWork() {
        return !retryQueue.isEmpty() || plan.hasNext();
    }

    public void markInFlight(long packed, long startedNanos, UUID ticketId) {
        Objects.requireNonNull(ticketId, "ticketId");
        if (!dispatching.contains(packed)) {
            throw new IllegalStateException("chunk was not polled for dispatch: " + packed);
        }
        if (inFlight.putIfAbsent(packed, new InFlight(startedNanos, ticketId)) != null) {
            throw new IllegalStateException("chunk already in flight: " + packed);
        }
        dispatching.remove(packed);
    }

    public Completion complete(
            long packed,
            UUID ticketId,
            boolean successful,
            String error,
            int maxRetries,
            long nowNanos
    ) {
        if (state.isTerminal()) {
            return Completion.IGNORED;
        }
        InFlight current = inFlight.get(packed);
        if (current == null || !current.ticketId().equals(ticketId)) {
            return Completion.IGNORED;
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be non-negative");
        }
        inFlight.remove(packed);

        if (successful) {
            completed++;
            retryCounts.remove(packed);
            recordCompletion(nowNanos);
            if (completed == target() && dispatching.isEmpty() && inFlight.isEmpty()) {
                state = JobState.COMPLETED;
                message = "completed";
            }
            return Completion.SUCCESS;
        }

        failures++;
        int retry = retryCounts.merge(packed, 1, Integer::sum);
        if (retry <= maxRetries) {
            retryQueue.addLast(packed);
            if (state == JobState.RUNNING) {
                message = "retry " + retry + "/" + maxRetries + " for "
                        + coordinateText(packed) + ": " + error;
            }
            return Completion.RETRY;
        }

        state = JobState.PAUSED;
        retryCounts.remove(packed);
        retryQueue.addFirst(packed);
        message = "paused after " + retry + " failures at "
                + coordinateText(packed) + ": " + error;
        return Completion.PAUSED;
    }

    public void requeueAllInFlight() {
        if (inFlight.isEmpty() && dispatching.isEmpty()) {
            return;
        }
        List<Long> pending = new ArrayList<>(inFlight.keySet());
        pending.addAll(dispatching);
        inFlight.clear();
        dispatching.clear();
        for (int index = pending.size() - 1; index >= 0; index--) {
            retryQueue.addFirst(pending.get(index));
        }
    }

    public void pause(String reason) {
        if (state == JobState.RUNNING) {
            state = JobState.PAUSED;
            message = reason;
        }
    }

    public boolean resume() {
        if (state != JobState.PAUSED) {
            return false;
        }
        state = JobState.RUNNING;
        message = "running";
        runStartedNanos = System.nanoTime();
        java.util.Arrays.fill(completionCounts, 0L);
        return true;
    }

    public void cancel() {
        state = JobState.CANCELLED;
        message = "cancelled";
        retryQueue.clear();
        retryCounts.clear();
        dispatching.clear();
        inFlight.clear();
    }

    public void fail(String reason) {
        state = JobState.FAILED;
        message = reason;
    }

    public long oldestInFlightAgeNanos(long nowNanos) {
        // Entries are issued on the server thread and retain admission order.
        return inFlight.isEmpty() ? 0L
                : Math.max(0L, nowNanos - inFlight.values().iterator().next().startedNanos());
    }

    public long oldestInFlightPacked() {
        return inFlight.isEmpty() ? Long.MIN_VALUE : inFlight.keySet().iterator().next();
    }

    public List<Long> inFlightCoordinates() {
        return List.copyOf(inFlight.keySet());
    }

    public List<TicketReference> inFlightTickets() {
        List<TicketReference> tickets = new ArrayList<>(inFlight.size());
        inFlight.forEach((packed, flight) ->
                tickets.add(new TicketReference(packed, flight.ticketId())));
        return List.copyOf(tickets);
    }

    public double completionsPerSecond(long windowSeconds, long nowNanos) {
        if (windowSeconds <= 0L || windowSeconds > 60L) {
            throw new IllegalArgumentException("rate window must be between 1 and 60 seconds");
        }
        long currentBucket = Math.floorDiv(nowNanos, RATE_BUCKET_NANOS);
        long cutoffBucket = currentBucket - windowSeconds * 10L;
        long count = 0L;
        // Fixed 100 ms buckets bound memory and query work even for existing chunks.
        for (int index = 0; index < RATE_BUCKET_COUNT; index++) {
            if (completionBuckets[index] >= cutoffBucket
                    && completionBuckets[index] <= currentBucket) {
                count += completionCounts[index];
            }
        }
        return count / (double) windowSeconds;
    }

    public Snapshot snapshot() {
        List<Long> pending = new ArrayList<>(retryQueue);
        pending.addAll(dispatching);
        pending.addAll(inFlight.keySet());
        return new Snapshot(
                id,
                dimension,
                plan.centerBlockX(),
                plan.centerBlockZ(),
                plan.radiusBlocks(),
                plan.shape(),
                plan.cursor(),
                completed,
                failures,
                state,
                createdEpochMillis,
                message,
                pending,
                retryCounts
        );
    }

    public UUID id() {
        return id;
    }

    public String dimension() {
        return dimension;
    }

    public int centerBlockX() {
        return plan.centerBlockX();
    }

    public int centerBlockZ() {
        return plan.centerBlockZ();
    }

    public int radiusBlocks() {
        return plan.radiusBlocks();
    }

    public PregenShape shape() {
        return plan.shape();
    }

    public long target() {
        return plan.targetCount();
    }

    public long completed() {
        return completed;
    }

    public long failures() {
        return failures;
    }

    public int inFlightCount() {
        return inFlight.size();
    }

    public int retryQueueSize() {
        return retryQueue.size();
    }

    public JobState state() {
        return state;
    }

    public String message() {
        return message;
    }

    public long createdEpochMillis() {
        return createdEpochMillis;
    }

    public long runStartedNanos() {
        return runStartedNanos;
    }

    private void recordCompletion(long nowNanos) {
        long bucket = Math.floorDiv(nowNanos, RATE_BUCKET_NANOS);
        int index = Math.floorMod(bucket, RATE_BUCKET_COUNT);
        if (completionBuckets[index] != bucket) {
            completionBuckets[index] = bucket;
            completionCounts[index] = 0L;
        }
        completionCounts[index]++;
    }

    private static void validateSnapshot(Snapshot snapshot, SpiralChunkPlan plan) {
        long visited = plan.acceptedBefore(snapshot.cursor());
        if (snapshot.completed() > plan.targetCount() || snapshot.completed() > visited) {
            throw new IllegalArgumentException("completed count exceeds target or visited chunks");
        }
        Set<Long> pending = new HashSet<>();
        for (long packed : snapshot.pending()) {
            if (!pending.add(packed)) {
                throw new IllegalArgumentException("duplicate pending chunk: " + coordinateText(packed));
            }
            if (!plan.wasVisited(packed, snapshot.cursor())) {
                throw new IllegalArgumentException("pending chunk was not visited: " + coordinateText(packed));
            }
        }
        // Cancellation intentionally drops remaining coordinates. Every other state must
        // account for all visited chunks, otherwise resuming could silently leave holes.
        if (snapshot.state() != JobState.CANCELLED
                && snapshot.completed() + pending.size() != visited) {
            throw new IllegalArgumentException("checkpoint loses visited chunks: completed="
                    + snapshot.completed() + ", pending=" + pending.size() + ", visited=" + visited);
        }
        if (snapshot.state() == JobState.COMPLETED
                && (snapshot.completed() != plan.targetCount() || !pending.isEmpty())) {
            throw new IllegalArgumentException("completed job has unfinished chunks");
        }
        if (!pending.containsAll(snapshot.retryCounts().keySet())) {
            throw new IllegalArgumentException("retry counts refer to non-pending chunks");
        }
    }

    private static String coordinateText(long packed) {
        return SpiralChunkPlan.unpackX(packed) + "," + SpiralChunkPlan.unpackZ(packed);
    }

    private record InFlight(long startedNanos, UUID ticketId) {
    }

    public record TicketReference(long packed, UUID ticketId) {
    }

    public enum Completion {
        SUCCESS,
        RETRY,
        PAUSED,
        IGNORED
    }

    public record Snapshot(
            UUID id,
            String dimension,
            int centerBlockX,
            int centerBlockZ,
            int radiusBlocks,
            PregenShape shape,
            long cursor,
            long completed,
            long failures,
            JobState state,
            long createdEpochMillis,
            String message,
            List<Long> pending,
            Map<Long, Integer> retryCounts
    ) {
        public Snapshot {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(shape, "shape");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(message, "message");
            if (dimension == null || dimension.isBlank() || ResourceLocation.tryParse(dimension) == null) {
                throw new IllegalArgumentException("invalid dimension: " + dimension);
            }
            if (cursor < 0L || completed < 0L || failures < 0L) {
                throw new IllegalArgumentException("cursor and progress counters must be non-negative");
            }
            pending = List.copyOf(pending);
            retryCounts = Map.copyOf(retryCounts);
            for (int count : retryCounts.values()) {
                if (count <= 0 || count == Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("invalid retry count: " + count);
                }
            }
        }

        /** Source compatibility for checkpoints created before retry persistence. */
        public Snapshot(UUID id, String dimension, int centerBlockX, int centerBlockZ,
                        int radiusBlocks, PregenShape shape, long cursor, long completed,
                        long failures, JobState state, long createdEpochMillis,
                        String message, List<Long> pending) {
            this(id, dimension, centerBlockX, centerBlockZ, radiusBlocks, shape, cursor,
                    completed, failures, state, createdEpochMillis, message, pending, Map.of());
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("Id", id);
            tag.putString("Dimension", dimension);
            tag.putInt("CenterBlockX", centerBlockX);
            tag.putInt("CenterBlockZ", centerBlockZ);
            tag.putInt("RadiusBlocks", radiusBlocks);
            tag.putString("Shape", shape.name());
            tag.putLong("Cursor", cursor);
            tag.putLong("Completed", completed);
            tag.putLong("Failures", failures);
            tag.putString("State", state.name());
            tag.putLong("CreatedEpochMillis", createdEpochMillis);
            tag.putString("Message", message);
            long[] pendingArray = new long[pending.size()];
            for (int index = 0; index < pending.size(); index++) {
                pendingArray[index] = pending.get(index);
            }
            tag.putLongArray("Pending", pendingArray);
            if (!retryCounts.isEmpty()) {
                // The original fields and cursor retain their 0.1 meaning. Older versions
                // can still read this checkpoint, ignoring the optional retry history.
                long[] retryCoordinates = new long[retryCounts.size()];
                int[] retryAttempts = new int[retryCounts.size()];
                int index = 0;
                for (long packed : pending) {
                    Integer attempts = retryCounts.get(packed);
                    if (attempts != null) {
                        retryCoordinates[index] = packed;
                        retryAttempts[index++] = attempts;
                    }
                }
                if (index != retryCounts.size()) {
                    throw new IllegalStateException("retry counts refer to non-pending chunks");
                }
                tag.putLongArray("RetryCoordinates", retryCoordinates);
                tag.putIntArray("RetryCounts", retryAttempts);
            }
            return tag;
        }

        public static Snapshot load(CompoundTag tag) {
            if (!tag.hasUUID("Id")) {
                throw new IllegalArgumentException("checkpoint has no valid Id");
            }
            requireTag(tag, "Dimension", Tag.TAG_STRING);
            requireTag(tag, "CenterBlockX", Tag.TAG_INT);
            requireTag(tag, "CenterBlockZ", Tag.TAG_INT);
            requireTag(tag, "RadiusBlocks", Tag.TAG_INT);
            requireTag(tag, "Shape", Tag.TAG_STRING);
            requireTag(tag, "Cursor", Tag.TAG_LONG);
            requireTag(tag, "Completed", Tag.TAG_LONG);
            requireTag(tag, "Failures", Tag.TAG_LONG);
            requireTag(tag, "State", Tag.TAG_STRING);
            requireTag(tag, "CreatedEpochMillis", Tag.TAG_LONG);
            requireTag(tag, "Message", Tag.TAG_STRING);
            requireTag(tag, "Pending", Tag.TAG_LONG_ARRAY);
            long[] pendingArray = tag.getLongArray("Pending");
            List<Long> pending = new ArrayList<>(pendingArray.length);
            for (long packed : pendingArray) {
                pending.add(packed);
            }
            Map<Long, Integer> retryCounts = new HashMap<>();
            if (tag.contains("RetryCoordinates") || tag.contains("RetryCounts")) {
                requireTag(tag, "RetryCoordinates", Tag.TAG_LONG_ARRAY);
                requireTag(tag, "RetryCounts", Tag.TAG_INT_ARRAY);
                long[] coordinates = tag.getLongArray("RetryCoordinates");
                int[] attempts = tag.getIntArray("RetryCounts");
                if (coordinates.length != attempts.length) {
                    throw new IllegalArgumentException("retry coordinates/counts have different lengths");
                }
                for (int index = 0; index < coordinates.length; index++) {
                    if (retryCounts.putIfAbsent(coordinates[index], attempts[index]) != null) {
                        throw new IllegalArgumentException("duplicate retry coordinate");
                    }
                }
            }
            Snapshot snapshot = new Snapshot(
                    tag.getUUID("Id"),
                    tag.getString("Dimension"),
                    tag.getInt("CenterBlockX"),
                    tag.getInt("CenterBlockZ"),
                    tag.getInt("RadiusBlocks"),
                    PregenShape.parse(tag.getString("Shape")),
                    tag.getLong("Cursor"),
                    tag.getLong("Completed"),
                    tag.getLong("Failures"),
                    JobState.valueOf(tag.getString("State")),
                    tag.getLong("CreatedEpochMillis"),
                    tag.getString("Message"),
                    pending,
                    retryCounts
            );
            validateSnapshot(snapshot, new SpiralChunkPlan(snapshot.centerBlockX(),
                    snapshot.centerBlockZ(), snapshot.radiusBlocks(), snapshot.shape()));
            return snapshot;
        }

        private static void requireTag(CompoundTag tag, String key, int type) {
            if (!tag.contains(key, type)) {
                throw new IllegalArgumentException("checkpoint field missing or wrong type: " + key);
            }
        }
    }
}
