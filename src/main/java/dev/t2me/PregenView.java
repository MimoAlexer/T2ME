package dev.t2me;

import java.util.Locale;
import java.util.UUID;

/**
 * Immutable presentation snapshot shared by commands, logs, and the boss bar.
 */
public record PregenView(
        UUID jobId,
        JobState state,
        String dimension,
        int centerBlockX,
        int centerBlockZ,
        int radiusBlocks,
        PregenShape shape,
        long completed,
        long target,
        int inFlight,
        int retryQueue,
        long failures,
        double cps5,
        double cps60,
        long etaSeconds,
        double latencyEwmaMillis,
        double tickEwmaMillis,
        int admission,
        int adaptiveWindow,
        String throttle,
        String message,
        ThreadedWorldgenEngine.Stats engine
) {
    public static PregenView none(
            double tickEwmaMillis,
            int admission,
            int adaptiveWindow,
            String throttle,
            ThreadedWorldgenEngine.Stats engine
    ) {
        return new PregenView(
                null,
                null,
                "",
                0,
                0,
                0,
                null,
                0L,
                0L,
                0,
                0,
                0L,
                0.0D,
                0.0D,
                -1L,
                0.0D,
                tickEwmaMillis,
                admission,
                adaptiveWindow,
                throttle,
                "",
                engine
        );
    }

    public boolean hasJob() {
        return jobId != null;
    }

    public double percent() {
        if (target <= 0L) {
            return 100.0D;
        }
        return Math.max(0.0D, Math.min(100.0D, completed * 100.0D / target));
    }

    public float progressFraction() {
        return (float) (percent() / 100.0D);
    }

    public String stateText() {
        if (state == null) {
            return "No job";
        }
        String lower = state.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    public static String formatCount(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    public static String formatDuration(long seconds) {
        if (seconds < 0L) {
            return "unknown";
        }
        long days = seconds / 86_400L;
        long hours = (seconds % 86_400L) / 3_600L;
        long minutes = (seconds % 3_600L) / 60L;
        long remainder = seconds % 60L;
        if (days > 0L) {
            return String.format(
                    Locale.ROOT,
                    "%dd %02dh %02dm",
                    days,
                    hours,
                    minutes
            );
        }
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%dh %02dm %02ds", hours, minutes, remainder);
        }
        return String.format(Locale.ROOT, "%dm %02ds", minutes, remainder);
    }

    public static String sanitize(String value, int maximumLength) {
        if (value == null || value.isBlank() || maximumLength <= 0) {
            return "";
        }
        String singleLine = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (singleLine.length() <= maximumLength) {
            return singleLine;
        }
        return singleLine.substring(0, Math.max(0, maximumLength - 1)) + "…";
    }
}
