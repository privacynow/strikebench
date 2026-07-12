package io.liftandshift.strikebench.market;

import io.liftandshift.strikebench.util.EventBus;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;

/**
 * Reusable politeness gate for external data providers — the generalization of the discipline
 * the Cboe incident taught us (CboeProvider keeps its own inline, test-pinned copy):
 * <ul>
 *   <li><b>Concurrency cap</b>: at most N in-flight requests per provider.</li>
 *   <li><b>Spacing</b>: a minimum gap between request starts (burst smoothing).</li>
 *   <li><b>Circuit breaker</b>: a rate-limit response (HTTP 429, or Yahoo's 999) trips a
 *       provider-wide cooldown — no request of any kind until it clears. Trips are announced
 *       on the event bus as {@code provider.cooldown} so the UI can show its calm status chip.</li>
 *   <li><b>Prefetch budget</b>: speculative work is welcome only when the provider is healthy
 *       AND has a free permit — a guess must never queue against real demand.</li>
 * </ul>
 */
public final class ProviderPoliteness {

    private final String provider;
    private final Semaphore concurrency;
    private final long spacingMs;
    private final long cooldownMs;
    private volatile long cooldownUntilMs = 0;
    private long nextAllowedMs = 0; // guarded by `this`
    private EventBus events;        // optional

    public ProviderPoliteness(String provider, int maxConcurrency, long spacingMs, long cooldownMs) {
        this.provider = provider;
        this.concurrency = new Semaphore(Math.max(1, maxConcurrency), true);
        this.spacingMs = Math.max(0, spacingMs);
        this.cooldownMs = Math.max(1_000, cooldownMs);
    }

    public void setEvents(EventBus events) { this.events = events; }

    public boolean coolingDown() { return System.currentTimeMillis() < cooldownUntilMs; }

    public long cooldownUntilMs() { return cooldownUntilMs; }

    /** Healthy AND a permit free — the only state in which speculative (prefetch) work may run. */
    public boolean prefetchBudget() { return !coolingDown() && concurrency.availablePermits() > 0; }

    /**
     * Runs one provider request under the gate. While cooling down, returns
     * {@code coolingDownFallback} WITHOUT making the request. A rate-limit failure
     * (message contains "HTTP 429" or "HTTP 999") trips the breaker and rethrows.
     */
    public <T> T call(Callable<T> request, T coolingDownFallback) {
        if (coolingDown()) return coolingDownFallback;
        boolean acquired = false;
        try {
            concurrency.acquire();
            acquired = true;
            pace();
            if (coolingDown()) return coolingDownFallback; // tripped while we waited
            return request.call();
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("HTTP 429") || msg.contains("HTTP 999")) trip();
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException(e);
        } finally {
            if (acquired) concurrency.release();
        }
    }

    /** Trips the provider-wide breaker and announces it (idempotent while already cooling). */
    public void trip() {
        boolean wasCooling = coolingDown();
        cooldownUntilMs = System.currentTimeMillis() + cooldownMs;
        if (!wasCooling && events != null) {
            events.publish("provider.cooldown", Map.of("provider", provider, "untilMs", cooldownUntilMs));
        }
    }

    /** Serializes a minimum gap between request starts, shared across all threads. */
    private synchronized void pace() {
        long now = System.currentTimeMillis();
        long wait = nextAllowedMs - now;
        if (wait > 0) {
            try { Thread.sleep(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        nextAllowedMs = Math.max(now, nextAllowedMs) + spacingMs;
    }
}
