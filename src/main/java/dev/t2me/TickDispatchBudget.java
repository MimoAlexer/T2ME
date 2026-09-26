package dev.t2me;

/** Server-thread budget shared by tick-end dispatch and all completion refills. */
final class TickDispatchBudget {
    private int used;

    void reset() {
        used = 0;
    }

    boolean tryAcquire(int configuredLimit) {
        if (used >= configuredLimit) {
            return false;
        }
        used++;
        return true;
    }
}
