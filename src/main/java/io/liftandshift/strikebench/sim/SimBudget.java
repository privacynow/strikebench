package io.liftandshift.strikebench.sim;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Process-wide simulation budget: at most two Monte-Carlo runs in flight. Each run is already
 * work-capped ({@link ScenarioSpec#MAX_TOTAL_POINTS}); this bounds the MULTIPLE — concurrent
 * requests queue briefly and then fail loudly instead of stacking 50MB matrices until the heap
 * dies. Callers hold the permit for the duration of generate+price.
 */
final class SimBudget {

    private static final Semaphore PERMITS = new Semaphore(2, true);

    private SimBudget() {}

    /** Acquires a run permit (short queue tolerated); returns the release handle. */
    static AutoCloseable acquire() {
        try {
            if (!PERMITS.tryAcquire(8, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The simulation engine is busy — try again in a moment.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the simulation engine.");
        }
        return PERMITS::release;
    }
}
