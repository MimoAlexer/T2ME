package dev.t2me;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickDispatchBudgetTest {
    @Test
    void tickEndAndAnyNumberOfCompletionRefillsShareOneBudget() {
        TickDispatchBudget budget = new TickDispatchBudget();
        assertEquals(8, admit(budget, 12, 8));
        assertEquals(4, admit(budget, 12, 8));
        for (int refill = 0; refill < 100; refill++) {
            assertEquals(0, admit(budget, 12, 8));
        }
        budget.reset();
        assertEquals(12, admit(budget, 12, 100));
    }

    @Test
    void reloadingTheLimitDoesNotForgetRequestsAlreadyIssuedThisTick() {
        TickDispatchBudget budget = new TickDispatchBudget();
        assertEquals(20, admit(budget, 32, 20));
        assertFalse(budget.tryAcquire(16));
        assertEquals(12, admit(budget, 32, 100));
        assertFalse(budget.tryAcquire(32));
    }

    @Test
    void failedRequestsAndReplacementJobsDoNotRefundAUsedAdmission() {
        TickDispatchBudget budget = new TickDispatchBudget();
        assertTrue(budget.tryAcquire(1));
        // Issuance failed or the job was cancelled. Its successor uses the same
        // service budget; only a new tick may grant another attempt.
        assertFalse(budget.tryAcquire(1));
        budget.reset();
        assertTrue(budget.tryAcquire(1));
        assertFalse(budget.tryAcquire(1));
    }

    private static int admit(TickDispatchBudget budget, int limit, int requested) {
        int admitted = 0;
        while (admitted < requested && budget.tryAcquire(limit)) admitted++;
        return admitted;
    }
}
