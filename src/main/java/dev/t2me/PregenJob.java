package dev.t2me;

import net.minecraft.nbt.CompoundTag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

public final class PregenJob {
    private static final long ONE_SECOND_NANOS = 1_000_000_000L;
    private static final int RATE_BUCKETS = 60;

    private final UUID id;
    private final String dimension;
    private final SpiralChunkPlan plan;
    private final long createdEpochMillis;
    private final ArrayDeque<Long> retryQueue = new ArrayDeque<>();
    private final Map<Long, Integer> retryCounts = new HashMap<>();
    private final LinkedHashMap<Long, InFlight> inFlight = new LinkedHashMap<>();
    private final long[] completionBucketSeconds = new long[RATE_BUCKETS];
    private final long[] completionBucketCounts = new long[RATE_BUCKETS];

    private JobState state;
    private long completed;
    private long failures;
    private String message;
    private long runStartedNanos;
    private double latencyEwmaMillis;

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
        this.id = id;
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
        SpiralChunkPlan plan = new SpiralChunkPlan(
                snapshot.centerBlockX(),
                snapshot.centerBlockZ(),
                snapshot.radiusBlocks(),
                snapshot.shape()
        );
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
        return job;
    }

    public long pollNext() {
        Long retry = retryQueue.pollFirst();
        if (retry != null) {
            return retry;
        }
        if (!plan.hasNext()) {
            throw new NoSuchElementException("job exhausted");
        }
        return plan.nextPacked();
    }

    public boolean hasDispatchableWork() {
        return !retryQueue.isEmpty() || plan.hasNext();
    }

    public void markInFlight(long packed, long startedNanos, long ticketId) {
        if (inFlight.putIfAbsent(packed, new InFlight(startedNanos, ticketId)) != null) {
            throw new IllegalStateException("chunk already in flight: " + packed);
        }
    }

    public Completion complete(
            long packed,
            long ticketId,
            boolean successful,
            String error,
            int maxRetries,
            long nowNanos
    ) {
        InFlight current = inFlight.get(packed);
        if (current == null || current.ticketId() != ticketId) {
            return Completion.IGNORED;
        }
        inFlight.remove(packed);

        if (successful) {
            completed++;
            retryCounts.remove(packed);
            recordCompletion(nowNanos);
            double latencyMillis = Math.max(
                    0.0D,
                    (nowNanos - current.startedNanos()) / 1_000_000.0D
            );
            latencyEwmaMillis = latencyEwmaMillis == 0.0D
                    ? latencyMillis
                    : latencyEwmaMillis + 0.1D * (latencyMillis - latencyEwmaMillis);
            if (!hasDispatchableWork() && inFlight.isEmpty()) {
                state = JobState.COMPLETED;
                message = "completed";
            }
            return Completion.SUCCESS;
        }

        failures++;
        int retry = retryCounts.merge(packed, 1, Integer::sum);
        if (retry <= maxRetries) {
            retryQueue.addLast(packed);
            message = "retry " + retry + "/" + maxRetries + " for "
                    + coordinateText(packed) + ": " + error;
            return Completion.RETRY;
        }

        state = JobState.PAUSED;
        retryCounts.put(packed, 0);
        retryQueue.addFirst(packed);
        message = "paused after " + retry + " failures at "
                + coordinateText(packed) + ": " + error;
        return Completion.PAUSED;
    }

    public void requeueAllInFlight() {
        if (inFlight.isEmpty()) {
            return;
        }
        List<Long> pending = new ArrayList<>(inFlight.keySet());
        inFlight.clear();
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
        Arrays.fill(completionBucketSeconds, 0L);
        Arrays.fill(completionBucketCounts, 0L);
        return true;
    }

    public void cancel() {
        state = JobState.CANCELLED;
        message = "cancelled";
        retryQueue.clear();
        retryCounts.clear();
        inFlight.clear();
    }

    public void fail(String reason) {
        state = JobState.FAILED;
        message = reason;
    }

    public long oldestInFlightAgeNanos(long nowNanos) {
        long oldest = Long.MAX_VALUE;
        for (InFlight flight : inFlight.values()) {
            oldest = Math.min(oldest, flight.startedNanos());
        }
        return oldest == Long.MAX_VALUE ? 0L : Math.max(0L, nowNanos - oldest);
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
        long boundedWindow = Math.max(1L, Math.min(RATE_BUCKETS, windowSeconds));
        long currentSecond = nowNanos / ONE_SECOND_NANOS;
        long cutoffSecond = currentSecond - boundedWindow + 1L;
        long count = 0L;
        for (int index = 0; index < RATE_BUCKETS; index++) {
            long bucketSecond = completionBucketSeconds[index];
            if (bucketSecond >= cutoffSecond && bucketSecond <= currentSecond) {
                count += completionBucketCounts[index];
            }
        }
        double elapsedSeconds = Math.max(
                1.0D,
                Math.max(0L, nowNanos - runStartedNanos) / (double) ONE_SECOND_NANOS
        );
        return count / Math.min(boundedWindow, elapsedSeconds);
    }

    public Snapshot snapshot() {
        List<Long> pending = new ArrayList<>(retryQueue);
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
                Map.copyOf(retryCounts)
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

    public double latencyEwmaMillis() {
        return latencyEwmaMillis;
    }

    private void recordCompletion(long nowNanos) {
        long second = nowNanos / ONE_SECOND_NANOS;
        int index = (int) Math.floorMod(second, RATE_BUCKETS);
        if (completionBucketSeconds[index] != second) {
            completionBucketSeconds[index] = second;
            completionBucketCounts[index] = 0L;
        }
        completionBucketCounts[index]++;
    }

    private static String coordinateText(long packed) {
        return SpiralChunkPlan.unpackX(packed) + "," + SpiralChunkPlan.unpackZ(packed);
    }

    private record InFlight(long startedNanos, long ticketId) {
    }

    public record TicketReference(long packed, long ticketId) {
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
            pending = List.copyOf(pending);
            retryCounts = Map.copyOf(retryCounts);
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
            long[] retryCoordinates = new long[retryCounts.size()];
            int[] retryAttempts = new int[retryCounts.size()];
            int retryIndex = 0;
            for (Map.Entry<Long, Integer> entry : retryCounts.entrySet()) {
                retryCoordinates[retryIndex] = entry.getKey();
                retryAttempts[retryIndex] = entry.getValue();
                retryIndex++;
            }
            tag.putLongArray("RetryCoordinates", retryCoordinates);
            tag.putIntArray("RetryAttempts", retryAttempts);
            return tag;
        }

        public static Snapshot load(CompoundTag tag) {
            long[] pendingArray = tag.getLongArray("Pending");
            List<Long> pending = new ArrayList<>(pendingArray.length);
            for (long packed : pendingArray) {
                pending.add(packed);
            }
            long[] retryCoordinates = tag.getLongArray("RetryCoordinates");
            int[] retryAttempts = tag.getIntArray("RetryAttempts");
            Map<Long, Integer> retryCounts = new HashMap<>();
            int retryLength = Math.min(retryCoordinates.length, retryAttempts.length);
            for (int index = 0; index < retryLength; index++) {
                if (retryAttempts[index] > 0) {
                    retryCounts.put(retryCoordinates[index], retryAttempts[index]);
                }
            }
            return new Snapshot(
                    tag.hasUUID("Id") ? tag.getUUID("Id") : UUID.randomUUID(),
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
                    List.copyOf(pending),
                    Map.copyOf(retryCounts)
            );
        }
    }
}
