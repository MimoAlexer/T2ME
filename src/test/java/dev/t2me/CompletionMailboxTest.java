package dev.t2me;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CompletionMailboxTest {
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
