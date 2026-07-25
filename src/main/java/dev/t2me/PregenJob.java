package dev.t2me;

import net.minecraft.nbt.CompoundTag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

public final class PregenJob {
    private static final long ONE_SECOND_NANOS = 1_000_000_000L;

    private final UUID id;
    private final String dimension;
    private final SpiralChunkPlan plan;
    private final long createdEpochMillis;
    private final ArrayDeque<Long> retryQueue = new ArrayDeque<>();
    private final Map<Long, Integer> retryCounts = new HashMap<>();
    private final LinkedHashMap<Long, InFlight> inFlight = new LinkedHashMap<>();
    private final ArrayDeque<Long> completionNanos = new ArrayDeque<>();

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

    public void markInFlight(long packed, long startedNanos, UUID ticketId) {
        if (inFlight.putIfAbsent(packed, new InFlight(startedNanos, ticketId)) != null) {
            throw new IllegalStateException("chunk already in flight: " + packed);
        }
    }

    public Completion complete(
            long packed,
            UUID ticketId,
            boolean successful,
            String error,
            int maxRetries,
            long nowNanos
    ) {
        InFlight current = inFlight.get(packed);
        if (current == null || !current.ticketId().equals(ticketId)) {
            return Completion.IGNORED;
        }
        inFlight.remove(packed);

        if (successful) {
            completed++;
            retryCounts.remove(packed);
            completionNanos.addLast(nowNanos);
            pruneCompletionSamples(nowNanos);
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
        pruneCompletionSamples(nowNanos);
        long cutoff = nowNanos - windowSeconds * ONE_SECOND_NANOS;
        int count = 0;
        for (long completion : completionNanos) {
            if (completion >= cutoff) {
                count++;
            }
        }
        return count / (double) windowSeconds;
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
                pending
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

    private void pruneCompletionSamples(long nowNanos) {
        long cutoff = nowNanos - 60L * ONE_SECOND_NANOS;
        while (!completionNanos.isEmpty() && completionNanos.peekFirst() < cutoff) {
            completionNanos.removeFirst();
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
            List<Long> pending
    ) {
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
            return tag;
        }

        public static Snapshot load(CompoundTag tag) {
            long[] pendingArray = tag.getLongArray("Pending");
            List<Long> pending = new ArrayList<>(pendingArray.length);
            for (long packed : pendingArray) {
                pending.add(packed);
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
                    List.copyOf(pending)
            );
        }
    }
}
