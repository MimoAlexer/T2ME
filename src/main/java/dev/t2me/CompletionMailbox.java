package dev.t2me;

import java.util.ArrayDeque;

/** A job-scoped handoff: workers publish values; only the server consumes them. */
final class CompletionMailbox<T> {
    private final ArrayDeque<T> queue = new ArrayDeque<>();
    private volatile boolean open = true;
    private boolean drainScheduled;

    boolean isOpen() {
        return open;
    }

    /** Publishes a value and claims the single drain task if one is not already owned. */
    synchronized boolean offer(T value) {
        if (!open) {
            return false;
        }
        queue.addLast(value);
        if (drainScheduled) {
            return false;
        }
        drainScheduled = true;
        return true;
    }

    synchronized T poll() {
        return queue.pollFirst();
    }

    /**
     * Only the scheduled task releases its claim. A tick-end poll must leave it
     * intact. Retaining the claim while requesting one successor avoids a lost
     * wakeup when a producer publishes after the final poll but before this call.
     */
    synchronized boolean finishScheduledDrain(boolean scheduleSuccessor) {
        if (!drainScheduled) {
            return false;
        }
        if (open && scheduleSuccessor && !queue.isEmpty()) {
            return true;
        }
        drainScheduled = false;
        return false;
    }

    synchronized void close() {
        open = false;
        queue.clear();
        drainScheduled = false;
    }
}
