package dev.t2me;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeighborhoodLockManagerTest {
    @Test
    void overlappingNeighborhoodWaitsForEverySharedCoordinate() {
        NeighborhoodLockManager locks = new NeighborhoodLockManager();
        NeighborhoodLockManager.Token first = locks.acquire(
                new long[]{1L, 2L}
        ).join();
        CompletableFuture<NeighborhoodLockManager.Token> waiting =
                locks.acquire(new long[]{2L, 3L});

        assertFalse(waiting.isDone());
        assertEquals(3, locks.lockedCoordinates());

        first.close();

        assertTrue(waiting.isDone());
        assertEquals(2, locks.lockedCoordinates());
        waiting.join().close();
        assertEquals(0, locks.lockedCoordinates());
    }

    @Test
    void nonOverlappingNeighborhoodsAcquireAndReleaseIndependently() {
        NeighborhoodLockManager locks = new NeighborhoodLockManager();
        NeighborhoodLockManager.Token first = locks.acquire(
                new long[]{10L, 11L}
        ).join();
        CompletableFuture<NeighborhoodLockManager.Token> secondFuture =
                locks.acquire(new long[]{20L, 21L});

        assertTrue(secondFuture.isDone());
        NeighborhoodLockManager.Token second = secondFuture.join();
        assertEquals(4, locks.lockedCoordinates());

        first.close();
        assertEquals(2, locks.lockedCoordinates());
        second.close();
        assertEquals(0, locks.lockedCoordinates());
    }

    @Test
    void duplicateCoordinatesDoNotWaitOnTheirOwnReservation() {
        NeighborhoodLockManager locks = new NeighborhoodLockManager();

        CompletableFuture<NeighborhoodLockManager.Token> acquired =
                locks.acquire(new long[]{7L, 7L});

        assertTrue(acquired.isDone());
        assertEquals(1, locks.lockedCoordinates());
        acquired.join().close();
        assertEquals(0, locks.lockedCoordinates());
    }

    @Test
    void exceptionalStageReleaseUnblocksItsWaiter() {
        NeighborhoodLockManager locks = new NeighborhoodLockManager();
        NeighborhoodLockManager.Token first = locks.acquire(
                new long[]{42L}
        ).join();
        CompletableFuture<NeighborhoodLockManager.Token> waiting =
                locks.acquire(new long[]{42L});

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> CompletableFuture.<Void>failedFuture(
                        new IllegalStateException("stage failed")
                ).whenComplete((ignored, error) -> first.close()).join()
        );

        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertTrue(waiting.isDone());
        NeighborhoodLockManager.Token second = waiting.join();
        assertEquals(1, locks.lockedCoordinates());
        second.close();
        second.close();
        assertEquals(0, locks.lockedCoordinates());
    }
}
