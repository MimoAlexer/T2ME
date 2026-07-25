package dev.t2me;

import net.minecraft.server.MinecraftServer;

public final class AdaptiveLimiter {
    private static final double EWMA_ALPHA = 0.08D;

    private double tickEwmaMillis;
    private double tickPeakMillis;
    private long samples;

    public void recordTick(long elapsedNanos) {
        double millis = elapsedNanos / 1_000_000.0D;
        if (samples++ == 0L) {
            tickEwmaMillis = millis;
        } else {
            tickEwmaMillis += EWMA_ALPHA * (millis - tickEwmaMillis);
        }
        tickPeakMillis = Math.max(millis, tickPeakMillis * 0.995D);
    }

    public Decision decide(MinecraftServer server) {
        int configuredLimit = T2MEConfig.MAX_IN_FLIGHT.get();
        int processorLimit = Math.max(
                2,
                Math.min(32, Runtime.getRuntime().availableProcessors() * 2)
        );
        int limit = Math.min(configuredLimit, processorLimit);

        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        long headroom = Math.max(0L, runtime.maxMemory() - used);
        long required = T2MEConfig.MIN_HEAP_HEADROOM_MIB.get() * 1024L * 1024L;
        if (headroom < required) {
            return new Decision(0, "low heap headroom", headroom);
        }

        if (samples > 20L && tickEwmaMillis >= T2MEConfig.HARD_STOP_TICK_MILLIS.get()) {
            return new Decision(0, "MSPT hard stop", headroom);
        }
        if (samples > 20L && tickEwmaMillis >= T2MEConfig.TARGET_TICK_MILLIS.get()) {
            limit = Math.max(1, limit / 2);
        }

        if (T2MEConfig.REDUCE_WHEN_PLAYERS_ONLINE.get()
                && server.getPlayerCount() > 0) {
            limit = Math.max(1, limit / 2);
            return new Decision(limit, "players online", headroom);
        }

        String reason = limit < configuredLimit ? "processor cap" : "healthy";
        return new Decision(limit, reason, headroom);
    }

    public double tickEwmaMillis() {
        return tickEwmaMillis;
    }

    public double tickPeakMillis() {
        return tickPeakMillis;
    }

    public record Decision(int maxInFlight, String reason, long heapHeadroomBytes) {
    }
}
