package dev.t2me;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CompletionMailboxTest {
    @Test
    void tickEndPollingDoesNotReleaseTheQueuedTaskReservation() {
        CompletionMailbox<Integer> mailbox = new CompletionMailbox<>();
        assertTrue(mailbox.offer(1));
        assertFalse(mailbox.offer(2));
        // A regular tick drains these before the scheduled task gets CPU time.
        assertEquals(1, mailbox.poll());
        assertEquals(2, mailbox.poll());
        assertNull(mailbox.poll());
        assertFalse(mailbox.offer(3));
        assertEquals(3, mailbox.poll());
        assertFalse(mailbox.finishScheduledDrain(true));
        assertTrue(mailbox.offer(4));
    }

    @Test
    void producerAfterLastPollTransfersExactlyOneSuccessorReservation() {
        CompletionMailbox<Integer> mailbox = new CompletionMailbox<>();
        assertTrue(mailbox.offer(1));
        assertEquals(1, mailbox.poll());
        assertNull(mailbox.poll());
        assertFalse(mailbox.offer(2));
        assertTrue(mailbox.finishScheduledDrain(true));
        // The successor is already reserved, even before it starts running.
        assertFalse(mailbox.offer(3));
        assertEquals(2, mailbox.poll());
        assertEquals(3, mailbox.poll());
        assertFalse(mailbox.finishScheduledDrain(true));
        assertTrue(mailbox.offer(4));
    }

    @Test
    void closingPreventsAReservedDrainFromSchedulingItsSuccessor() {
        CompletionMailbox<Integer> mailbox = new CompletionMailbox<>();
        assertTrue(mailbox.offer(1));
        mailbox.close();
        assertFalse(mailbox.finishScheduledDrain(true));
        assertFalse(mailbox.offer(2));
        assertNull(mailbox.poll());
    }

    @Test
    void deferredNestedDrainLeavesWorkForTheTickFallbackWithoutSpinning() {
        CompletionMailbox<Integer> mailbox = new CompletionMailbox<>();
        assertTrue(mailbox.offer(1));
        assertFalse(mailbox.finishScheduledDrain(false));
        assertEquals(1, mailbox.poll());
        assertTrue(mailbox.offer(2));
        assertEquals(2, mailbox.poll());
        assertFalse(mailbox.finishScheduledDrain(true));
    }

    @Test
    void concurrentProducersReserveOneDrainForABurst() throws Exception {
        CompletionMailbox<Integer> mailbox = new CompletionMailbox<>();
        var executor = Executors.newFixedThreadPool(4);
        AtomicInteger reservations = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        var producers = new ArrayList<Future<?>>();
        try {
            for (int worker = 0; worker < 4; worker++) {
                producers.add(executor.submit(() -> {
                    start.await();
                    for (int index = 0; index < 250; index++) {
                        if (mailbox.offer(index)) reservations.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> producer : producers) producer.get(5, TimeUnit.SECONDS);
            assertEquals(1, reservations.get());
            int drained = 0;
            while (mailbox.poll() != null) drained++;
            assertEquals(1000, drained);
            assertFalse(mailbox.finishScheduledDrain(true));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void producerRacingDrainFinishNeverLosesOrDuplicatesTheWakeup() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            for (int attempt = 0; attempt < 200; attempt++) {
                CompletionMailbox<Integer> mailbox = new CompletionMailbox<>();
                assertTrue(mailbox.offer(1));
                assertEquals(1, mailbox.poll());
                CountDownLatch start = new CountDownLatch(1);
                Future<Boolean> producer = executor.submit(() -> {
                    start.await();
                    return mailbox.offer(2);
                });
                Future<Boolean> consumer = executor.submit(() -> {
                    start.await();
                    return mailbox.finishScheduledDrain(true);
                });
                start.countDown();
                int wakeups = (producer.get(5, TimeUnit.SECONDS) ? 1 : 0)
                        + (consumer.get(5, TimeUnit.SECONDS) ? 1 : 0);
                assertEquals(1, wakeups);
                assertEquals(2, mailbox.poll());
                assertFalse(mailbox.finishScheduledDrain(true));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void closingDiscardsQueuedAndLateCompletions() {
        CompletionMailbox<Integer> mailbox = new CompletionMailbox<>();
        mailbox.offer(1);
        mailbox.close();
        mailbox.offer(2);
        assertFalse(mailbox.isOpen());
        assertNull(mailbox.poll());
    }

    @Test
    void oldJobCannotPublishIntoNewJob() {
        CompletionMailbox<Integer> old = new CompletionMailbox<>();
        old.close();
        CompletionMailbox<Integer> next = new CompletionMailbox<>();
        old.offer(42);
        next.offer(7);
        assertNull(old.poll());
        assertEquals(7, next.poll());
        assertNull(next.poll());
    }

    @Test
    void concurrentProducersCannotRepopulateClosedMailbox() throws Exception {
        CompletionMailbox<Integer> mailbox = new CompletionMailbox<>();
        var executor = Executors.newFixedThreadPool(4);
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int worker = 0; worker < 4; worker++) {
                executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    for (int i = 0; i < 10_000; i++) mailbox.offer(i);
                    return null;
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            mailbox.close();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertNull(mailbox.poll());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}
