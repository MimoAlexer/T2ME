package dev.t2me;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Non-blocking multi-key lock used by experimental world-generation stages.
 *
 * <p>Acquisition is serialized briefly, but callers wait on futures instead of
 * parking worker threads. A task may therefore reserve an entire chunk
 * neighborhood without deadlocking another task that requests overlapping
 * coordinates in a different order.</p>
 */
final class NeighborhoodLockManager {
    private final Object monitor = new Object();
    private final Map<Long, CompletableFuture<Void>> tails = new HashMap<>();

    CompletableFuture<Token> acquire(long[] coordinates) {
        if (coordinates.length == 0) {
            return CompletableFuture.completedFuture(Token.EMPTY);
        }

        CompletableFuture<Void> release = new CompletableFuture<>();
        Set<CompletableFuture<Void>> dependencies =
                Collections.newSetFromMap(new IdentityHashMap<>());
        synchronized (monitor) {
            for (long coordinate : coordinates) {
                CompletableFuture<Void> previous = tails.put(coordinate, release);
                if (previous != null && previous != release) {
                    dependencies.add(previous);
                }
            }
        }

        CompletableFuture<Void> ready = dependencies.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.allOf(dependencies.stream()
                        .map(previous -> previous.handle((ignored, error) -> null))
                        .toArray(CompletableFuture[]::new));
        return ready.thenApply(ignored -> new Token(this, coordinates, release));
    }

    int lockedCoordinates() {
        synchronized (monitor) {
            return tails.size();
        }
    }

    private void release(long[] coordinates, CompletableFuture<Void> signal) {
        synchronized (monitor) {
            for (long coordinate : coordinates) {
                tails.remove(coordinate, signal);
            }
        }
        signal.complete(null);
    }

    static final class Token implements AutoCloseable {
        private static final Token EMPTY = new Token();

        private final NeighborhoodLockManager owner;
        private final long[] coordinates;
        private final CompletableFuture<Void> signal;
        private final AtomicBoolean closed;

        private Token() {
            owner = null;
            coordinates = new long[0];
            signal = null;
            closed = new AtomicBoolean(true);
        }

        private Token(
                NeighborhoodLockManager owner,
                long[] coordinates,
                CompletableFuture<Void> signal
        ) {
            this.owner = owner;
            this.coordinates = coordinates;
            this.signal = signal;
            this.closed = new AtomicBoolean();
        }

        @Override
        public void close() {
            if (owner != null && closed.compareAndSet(false, true)) {
                owner.release(coordinates, signal);
            }
        }
    }
}
