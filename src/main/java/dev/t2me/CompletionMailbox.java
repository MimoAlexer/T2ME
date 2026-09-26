package dev.t2me;

import java.util.ArrayDeque;

/** A job-scoped handoff: workers publish values; only the server consumes them. */
final class CompletionMailbox<T> {
    private final ArrayDeque<T> queue = new ArrayDeque<>();
    private volatile boolean open = true;

    boolean isOpen() {
        return open;
    }

    synchronized void offer(T value) {
        if (open) {
            queue.addLast(value);
        }
    }

    synchronized T poll() {
        return queue.pollFirst();
    }

    synchronized void close() {
        open = false;
        queue.clear();
    }
}
