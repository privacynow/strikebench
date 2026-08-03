package io.liftandshift.strikebench.market;

import io.liftandshift.strikebench.util.EventBus;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Reusable politeness gate for external data providers — the generalization of the discipline
 * the Cboe incident taught us. Cboe and Yahoo both use this owner rather than maintaining
 * provider-local breaker copies:
 * <ul>
 *   <li><b>Concurrency cap</b>: at most N in-flight requests per provider.</li>
 *   <li><b>Spacing</b>: a minimum gap between request starts (burst smoothing).</li>
 *   <li><b>Circuit breaker</b>: a denial/rate-limit response (HTTP 403/429, or Yahoo's 999), or
 *       three consecutive ordinary failures, trips a provider-wide cooldown. Trips are announced
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
    private long lastProbeMs = 0;   // guarded by `this`
    private final long probeIntervalMs;
    /** Explicit upstream denials require the whole quiet period. Ordinary outages may use a
     * spaced half-open probe so recovery is noticed sooner. */
    private volatile boolean recoveryProbeAllowed = true;
    private EventBus events;        // optional
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    public ProviderPoliteness(String provider, int maxConcurrency, long spacingMs, long cooldownMs) {
        // Half-open cadence: while cooling, let a single recovery probe through this often, so a
        // provider that healed unblocks in seconds instead of waiting out the whole cooldown.
        // Capped at the cooldown itself, so a short cooldown never probes before it simply expires.
        this(provider, maxConcurrency, spacingMs, cooldownMs, Math.min(45_000L, Math.max(1_000, cooldownMs)));
    }

    /** Test seam: explicit half-open probe cadence. */
    ProviderPoliteness(String provider, int maxConcurrency, long spacingMs, long cooldownMs, long probeIntervalMs) {
        this.provider = provider;
        this.concurrency = new Semaphore(Math.max(1, maxConcurrency), true);
        this.spacingMs = Math.max(0, spacingMs);
        this.cooldownMs = Math.max(1_000, cooldownMs);
        this.probeIntervalMs = Math.max(1, probeIntervalMs);
    }

    public void setEvents(EventBus events) { this.events = events; }

    public boolean coolingDown() { return System.currentTimeMillis() < cooldownUntilMs; }

    public long cooldownUntilMs() { return cooldownUntilMs; }

    /**
     * Restores an active provider breaker after an ordinary process restart. Expired values are
     * deliberately ignored, and a shorter stored value can never shorten a breaker already tripped
     * in this process.
     */
    public synchronized void seedCooldown(long untilMs) {
        long now = System.currentTimeMillis();
        if (untilMs > now) {
            cooldownUntilMs = Math.max(cooldownUntilMs, untilMs);
            // The durable setting intentionally stores only the deadline. On restart, take the
            // conservative interpretation and honor it fully instead of probing a possibly active
            // rate-limit ban.
            recoveryProbeAllowed = false;
            noteCooldownStart(now);
        }
    }

    /** The first half-open probe waits one full interval AFTER a trip/restore, never immediately. */
    private synchronized void noteCooldownStart(long now) { lastProbeMs = now; }

    /** Healthy AND a permit free — the only state in which speculative (prefetch) work may run. */
    public boolean prefetchBudget() { return !coolingDown() && concurrency.availablePermits() > 0; }

    /**
     * Runs one provider request under the gate. While cooling down, returns
     * {@code coolingDownFallback} WITHOUT making the request. A rate-limit failure
     * (message contains "HTTP 429" or "HTTP 999") trips the breaker and rethrows.
     */
    /**
     * {@code countsAsProviderFailure} distinguishes a bad individual
     * request (for example Yahoo HTTP 400 for one unsupported symbol) from an upstream outage.
     * Request-local failures still propagate, but they neither advance nor preserve the
     * provider-wide consecutive-failure count.
     */
    public <T> T call(Callable<T> request, T coolingDownFallback,
                      Predicate<Exception> countsAsProviderFailure) {
        boolean probing = false;
        long probedDeadline = 0L;
        if (coolingDown()) {
            // Half-open: at most one spaced recovery probe actually runs; everything else falls
            // back immediately without touching the provider. A 403/429/999 denial is different:
            // the upstream explicitly asked for quiet, so it receives the complete cooldown.
            if (!recoveryProbeAllowed || !claimProbe()) return coolingDownFallback;
            probing = true;
            probedDeadline = cooldownUntilMs;
        }
        boolean acquired = false;
        try {
            concurrency.acquire();
            acquired = true;
            pace();
            if (!probing && coolingDown()) return coolingDownFallback; // tripped while we waited
            T value = request.call();
            consecutiveFailures.set(0);
            if (probing) recover(probedDeadline); // only this probe's unchanged breaker may close
            return value;
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            boolean denied = msg.contains("HTTP 403") || msg.contains("HTTP 429") || msg.contains("HTTP 999");
            boolean providerFailure = countsAsProviderFailure == null || countsAsProviderFailure.test(e);
            if (denied) {
                trip(false);
            } else if (providerFailure && consecutiveFailures.incrementAndGet() >= 3) {
                trip(true);
            } else if (!providerFailure) {
                consecutiveFailures.set(0);
            }
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException(e);
        } finally {
            if (acquired) concurrency.release();
        }
    }

    /** Trips the provider-wide breaker for an ordinary outage, which may be recovery-probed. */
    public void trip() {
        trip(true);
    }

    /** Every advanced deadline is announced so the durable state cannot trail the live breaker. */
    private synchronized void trip(boolean allowRecoveryProbe) {
        long now = System.currentTimeMillis();
        long priorDeadline = cooldownUntilMs;
        cooldownUntilMs = now + cooldownMs;
        if (priorDeadline <= now) recoveryProbeAllowed = allowRecoveryProbe;
        else recoveryProbeAllowed = recoveryProbeAllowed && allowRecoveryProbe;
        noteCooldownStart(now);
        if (cooldownUntilMs > priorDeadline && events != null) {
            events.publish("provider.cooldown", Map.of("provider", provider, "untilMs", cooldownUntilMs));
        }
    }

    /** Half-open gate: grants at most one recovery probe per {@code probeIntervalMs} while cooling. */
    private synchronized boolean claimProbe() {
        long now = System.currentTimeMillis();
        if (now < cooldownUntilMs && now - lastProbeMs >= probeIntervalMs) {
            lastProbeMs = now;
            return true;
        }
        return false;
    }

    /** A recovery probe came back clean — close the breaker and clear its durable deadline. */
    private synchronized void recover(long probedDeadline) {
        // A request that failed while this probe was in flight may have extended the breaker.
        // Its newer denial wins; an older successful probe cannot clear that later cooldown.
        if (cooldownUntilMs != probedDeadline) return;
        cooldownUntilMs = 0;
        recoveryProbeAllowed = true;
        consecutiveFailures.set(0);
        if (events != null) events.publish("provider.cooldown", Map.of("provider", provider, "untilMs", 0L));
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
