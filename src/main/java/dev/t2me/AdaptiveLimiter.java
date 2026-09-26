package dev.t2me;

import net.minecraft.server.MinecraftServer;

/** Server-thread admission control; outstanding work is never cancelled by this controller. */
public final class AdaptiveLimiter {
    public static final int MAX_PIPELINE_SIZE = 256;

    private static final double EWMA_ALPHA = 0.08D;
    private static final double RECOVERY_FRACTION = 0.9D;
    private static final int INITIAL_WINDOW = 4;
    private static final int GROWTH_INTERVAL_TICKS = 10;
    private static final int BACKOFF_INTERVAL_TICKS = 5;
    private static final int RECOVERY_TICKS = 20;
    private static final long MIB = 1024L * 1024L;

    private double tickEwmaMillis;
    private double tickPeakMillis;
    private double latestTickMillis;
    private long samples;
    private long lastDecisionSample = -1L;
    private long lastBackoffSample = -BACKOFF_INTERVAL_TICKS;
    private int window;
    private int healthyTicks;
    private int recoveryTicks;
    private boolean heapStopped;
    private boolean tickStopped;

    public void reset() {
        tickEwmaMillis = 0.0D;
        tickPeakMillis = 0.0D;
        latestTickMillis = 0.0D;
        samples = 0L;
        lastDecisionSample = -1L;
        lastBackoffSample = -BACKOFF_INTERVAL_TICKS;
        window = 0;
        healthyTicks = 0;
        recoveryTicks = 0;
        heapStopped = false;
        tickStopped = false;
    }

    /** Starts a new job conservatively without forgetting current server-health pressure. */
    public void resetAdmission() {
        window = heapStopped || tickStopped ? 1 : 0;
        healthyTicks = 0;
    }

    public void recordTick(long elapsedNanos) {
        // A negative duration is not a health sample (e.g. an uninitialized timer).
        if (elapsedNanos < 0L) {
            return;
        }
        latestTickMillis = elapsedNanos / 1_000_000.0D;
        if (samples++ == 0L) {
            tickEwmaMillis = latestTickMillis;
        } else {
            tickEwmaMillis += EWMA_ALPHA * (latestTickMillis - tickEwmaMillis);
        }
        tickPeakMillis = Math.max(latestTickMillis, tickPeakMillis * 0.995D);
    }

    public Decision decide(MinecraftServer server) {
        return decide(server, true);
    }

    public Decision decide(MinecraftServer server, boolean active) {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        long headroom = Math.max(0L, runtime.maxMemory() - used);
        Settings settings = new Settings(
                T2MEConfig.MAX_IN_FLIGHT.get(),
                T2MEConfig.TARGET_TICK_MILLIS.get(),
                T2MEConfig.HARD_STOP_TICK_MILLIS.get(),
                T2MEConfig.MIN_HEAP_HEADROOM_MIB.get() * MIB,
                T2MEConfig.REDUCE_WHEN_PLAYERS_ONLINE.get()
        );
        return decide(settings, headroom, runtime.maxMemory(), server.getPlayerCount(), active);
    }

    /**
     * Deterministic core with no server or configuration reads. One recorded tick can
     * advance the controller only once, even if status is queried repeatedly.
     * Idle/paused jobs cannot ramp the window up without exercising the pipeline.
     */
    public Decision decide(
            Settings settings,
            long heapHeadroomBytes,
            long maxHeapBytes,
            int players,
            boolean active
    ) {
        if (maxHeapBytes <= 0L) {
            throw new IllegalArgumentException("maxHeapBytes must be positive");
        }
        long headroom = Math.max(0L, Math.min(maxHeapBytes, heapHeadroomBytes));
        boolean freshSample = samples != lastDecisionSample;
        lastDecisionSample = samples;
        if (window == 0) {
            window = Math.min(INITIAL_WINDOW, settings.maxInFlight());
        }
        window = Math.min(window, settings.maxInFlight());

        // A fixed 1 GiB reserve would permanently stop a server with a <=1 GiB heap.
        long reserve = Math.min(settings.minHeapHeadroomBytes(), maxHeapBytes / 4L);
        long recoveryMargin = Math.min(
                maxHeapBytes / 16L,
                Math.max(16L * MIB, reserve / 8L)
        );
        if (headroom < reserve || (heapStopped && headroom < reserve + recoveryMargin)) {
            heapStopped = true;
            backOffToMinimum();
            return new Decision(0, "low heap headroom", headroom);
        }
        heapStopped = false;

        // Preserve the stricter hard-stop setting if a config reload reverses the thresholds.
        double target = Math.min(settings.targetTickMillis(), settings.hardStopTickMillis() - 1.0D);
        double recoveryTarget = target * RECOVERY_FRACTION;
        if (samples > 0L && (latestTickMillis >= settings.hardStopTickMillis()
                || tickEwmaMillis >= settings.hardStopTickMillis())) {
            tickStopped = true;
            backOffToMinimum();
            return new Decision(0, "MSPT hard stop", headroom);
        }

        boolean healthy = samples > 0L
                && latestTickMillis <= recoveryTarget
                && tickEwmaMillis <= recoveryTarget;
        if (tickStopped) {
            if (!healthy) {
                recoveryTicks = 0;
            } else if (freshSample) {
                recoveryTicks++;
            }
            if (recoveryTicks < RECOVERY_TICKS) {
                return new Decision(0, "MSPT recovery", headroom);
            }
            tickStopped = false;
            recoveryTicks = 0;
        }

        if (samples > 0L && (latestTickMillis >= target || tickEwmaMillis >= target)) {
            healthyTicks = 0;
            if (freshSample && samples - lastBackoffSample >= BACKOFF_INTERVAL_TICKS) {
                window = Math.max(1, window / 2);
                lastBackoffSample = samples;
            }
            return decision(settings, players, headroom, "MSPT target");
        }

        if (!active || !healthy) {
            healthyTicks = 0;
        } else if (freshSample && ++healthyTicks >= GROWTH_INTERVAL_TICKS) {
            // Probe gradually; the cap is a number of futures, not a worker-thread count.
            int increment = Math.max(1, Math.min(8, (window + 3) / 4));
            window = Math.min(settings.maxInFlight(), window + increment);
            healthyTicks = 0;
        }
        String reason = !healthy && samples > 0L ? "MSPT recovery"
                : window < settings.maxInFlight() ? "ramping" : "healthy";
        return decision(settings, players, headroom, reason);
    }

    private void backOffToMinimum() {
        window = 1;
        healthyTicks = 0;
        recoveryTicks = 0;
        lastBackoffSample = samples;
    }

    private Decision decision(Settings settings, int players, long headroom, String reason) {
        int limit = window;
        if (settings.reduceWhenPlayersOnline() && players > 0) {
            limit = Math.max(1, limit / 2);
            reason += "; players online";
        }
        return new Decision(limit, reason, headroom);
    }

    public double tickEwmaMillis() {
        return tickEwmaMillis;
    }

    public double tickPeakMillis() {
        return tickPeakMillis;
    }

    public record Settings(
            int maxInFlight,
            int targetTickMillis,
            int hardStopTickMillis,
            long minHeapHeadroomBytes,
            boolean reduceWhenPlayersOnline
    ) {
        public Settings {
            if (maxInFlight < 1 || maxInFlight > MAX_PIPELINE_SIZE
                    || targetTickMillis < 1 || hardStopTickMillis < 2
                    || minHeapHeadroomBytes < 0L) {
                throw new IllegalArgumentException("Invalid admission settings");
            }
        }
    }

    public record Decision(int maxInFlight, String reason, long heapHeadroomBytes) {
    }
}
