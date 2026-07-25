package dev.t2me;

import net.minecraft.server.MinecraftServer;

/**
 * Additive-increase/multiplicative-decrease controller for the chunk pipeline.
 *
 * <p>A request window is intentionally much larger than the worker count:
 * every FULL request fans out into Forge's dependency graph and keeps several
 * generation stages busy. The controller searches upward while throughput is
 * healthy and backs off quickly under tick-time or heap pressure.</p>
 */
public final class AdaptiveLimiter {
    private static final double EWMA_ALPHA = 0.08D;

    private double tickEwmaMillis;
    private double tickPeakMillis;
    private long samples;
    private int window;
    private int bestWindow;
    private double bestCps;
    private int increaseCooldown;

    public void reset() {
        tickEwmaMillis = 0.0D;
        tickPeakMillis = 0.0D;
        samples = 0L;
        window = 0;
        bestWindow = 0;
        bestCps = 0.0D;
        increaseCooldown = 0;
    }

    public void recordTick(long elapsedNanos) {
        double millis = elapsedNanos / 1_000_000.0D;
        if (samples++ == 0L) {
            tickEwmaMillis = millis;
        } else {
            tickEwmaMillis += EWMA_ALPHA * (millis - tickEwmaMillis);
        }
        tickPeakMillis = Math.max(millis, tickPeakMillis * 0.995D);
    }

    public Decision decide(
            MinecraftServer server,
            double completionsPerSecond,
            int inFlight,
            long tickCounter
    ) {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        long headroom = Math.max(0L, runtime.maxMemory() - used);
        long required = T2MEConfig.MIN_HEAP_HEADROOM_MIB.get() * 1024L * 1024L;

        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        long maxHeapGiB = Math.max(1L, runtime.maxMemory() / (1024L * 1024L * 1024L));
        int memoryWindow = Math.toIntExact(Math.min(512L, maxHeapGiB * 24L));
        int maximum = Math.max(
                1,
                Math.min(T2MEConfig.MAX_IN_FLIGHT.get(), Math.max(64, memoryWindow))
        );
        int minimum = Math.min(T2MEConfig.MIN_IN_FLIGHT.get(), maximum);
        if (window == 0) {
            window = clamp(T2MEConfig.INITIAL_IN_FLIGHT.get(), minimum, maximum);
            bestWindow = window;
        } else {
            window = clamp(window, minimum, maximum);
            bestWindow = clamp(bestWindow, minimum, maximum);
        }

        boolean controlTick = tickCounter
                % T2MEConfig.CONTROL_INTERVAL_TICKS.get() == 0L;
        if (headroom < required) {
            if (controlTick) {
                window = multiplicativeDecrease(window, minimum, 0.5D);
                increaseCooldown = 4;
            }
            return new Decision(0, window, "low heap headroom", headroom);
        }

        if (samples > 20L
                && tickEwmaMillis >= T2MEConfig.HARD_STOP_TICK_MILLIS.get()) {
            if (controlTick) {
                window = multiplicativeDecrease(window, minimum, 0.5D);
                increaseCooldown = 3;
            }
            return new Decision(0, window, "MSPT hard stop", headroom);
        }

        String reason = "searching peak throughput";
        if (controlTick) {
            if (samples > 20L
                    && tickEwmaMillis >= T2MEConfig.TARGET_TICK_MILLIS.get()) {
                window = multiplicativeDecrease(window, minimum, 0.8D);
                increaseCooldown = 2;
                reason = "MSPT backoff";
            } else if (increaseCooldown > 0) {
                increaseCooldown--;
                reason = "backoff cooldown";
            } else if (inFlight >= Math.max(1, window * 3 / 4)) {
                if (completionsPerSecond > bestCps * 1.01D) {
                    bestCps = completionsPerSecond;
                    bestWindow = window;
                } else if (bestCps > 0.0D
                        && completionsPerSecond < bestCps * 0.85D
                        && window > bestWindow + processors * 2) {
                    window = bestWindow;
                    increaseCooldown = 2;
                    reason = "restored best window";
                } else {
                    window = Math.min(maximum, window + Math.max(4, processors));
                }
            } else {
                reason = "pipeline filling";
            }
        }

        int admission = window;
        if (T2MEConfig.REDUCE_WHEN_PLAYERS_ONLINE.get()
                && server.getPlayerCount() > 0) {
            admission = Math.max(1, admission / 2);
            reason = "players online";
        } else if (window >= maximum) {
            reason = "maximum window";
        }
        return new Decision(admission, window, reason, headroom);
    }

    public double tickEwmaMillis() {
        return tickEwmaMillis;
    }

    public double tickPeakMillis() {
        return tickPeakMillis;
    }

    static int multiplicativeDecrease(int value, int minimum, double factor) {
        return Math.max(minimum, (int) Math.floor(value * factor));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public record Decision(
            int maxInFlight,
            int adaptiveWindow,
            String reason,
            long heapHeadroomBytes
    ) {
    }
}
