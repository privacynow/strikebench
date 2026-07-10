package io.liftandshift.strikebench.market;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.Quote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory market-data engine — one owned, always-available "current market state" that sits ABOVE
 * the {@link MarketDataService} provider chain. It:
 * <ul>
 *   <li>warms the active universe on boot so the first visitor never pays cold-provider latency,</li>
 *   <li>refreshes tracked symbols in the background (faster during regular hours, slower when the
 *       market is closed — nothing is moving),</li>
 *   <li>serves the last-known snapshot immediately (stale-while-refresh) instead of blocking a
 *       request thread on a live download,</li>
 *   <li>collapses concurrent refreshes of the same symbol onto ONE provider call (singleflight), so
 *       ten UI requests for AAPL never become ten Cboe downloads.</li>
 * </ul>
 * The engine is quote-level state (the tape, the quotes batch, streaming); full chains stay
 * on-demand through {@link MarketDataService}. Honesty is preserved: each snapshot carries the
 * provider's {@link Freshness}, so demo/fixture data never masquerades as live.
 */
public final class MarketDataEngine {

    private static final Logger log = LoggerFactory.getLogger(MarketDataEngine.class);

    /** A symbol's last-known quote state, plus engine bookkeeping (refresh time, in-flight, error). */
    public record MarketSnapshot(String symbol, String description, BigDecimal last, BigDecimal bid,
                                 BigDecimal ask, BigDecimal prevClose, boolean optionable,
                                 Freshness freshness, String source, long asOfEpochMs,
                                 long lastRefreshEpochMs, boolean refreshing, String error) {}

    /** Operational status for the Data Center: what's warm, what's stale, what's in flight, latency. */
    public record EngineStatus(boolean enabled, boolean running, boolean marketOpen, int refreshInterval,
                               int tracked, int warmed, int stale, int inFlight, int errors,
                               long avgLatencyMs, long lastRefreshEpochMs, List<SymbolStatus> symbols) {}

    public record SymbolStatus(String symbol, boolean warmed, boolean refreshing, String freshness,
                               String source, long ageMs, String error) {}

    private final MarketDataService market;
    private final UniverseService universe;
    private final AppConfig cfg;
    private final Clock clock;
    private io.liftandshift.strikebench.market.ports.SnapshotStore snapshotStore; // persist/boot last-known quotes (nullable)

    private final Map<String, MarketSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAccess = new ConcurrentHashMap<>();   // for LRU eviction of tracked symbols
    private final Map<String, java.util.concurrent.CompletableFuture<Void>> inFlight = new ConcurrentHashMap<>(); // singleflight: callers JOIN the in-flight refresh
    private final AtomicLong refreshCount = new AtomicLong();
    private final AtomicLong refreshLatencyTotalMs = new AtomicLong();
    private volatile long lastRefreshEpochMs = 0L;

    private ExecutorService refreshPool;      // bounded — polite to providers
    /** How many user-blocking fetches are in flight — background work YIELDS while this is nonzero. */
    private final java.util.concurrent.atomic.AtomicInteger pendingInteractive = new java.util.concurrent.atomic.AtomicInteger();
    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    public MarketDataEngine(MarketDataService market, UniverseService universe, AppConfig cfg, Clock clock) {
        this.market = market;
        this.universe = universe;
        this.cfg = cfg;
        this.clock = clock;
    }

    /** Inject the persistence store so the engine boots stale-first and mirrors refreshes to DB. */
    public void setSnapshotStore(io.liftandshift.strikebench.market.ports.SnapshotStore store) {
        this.snapshotStore = store;
    }

    // ---- Lifecycle ----

    /** Warms the active universe and starts the background refresh loop. Idempotent; safe if disabled. */
    public synchronized void start() {
        if (running) return;
        // The serving path (quotes()) always works; the scheduler + warm only run when enabled.
        refreshPool = Executors.newFixedThreadPool(8, daemon("mkt-engine-refresh"));
        running = true;
        // Boot STALE-FIRST: seed memory from persisted last-known quotes so the tape/tiles paint
        // instantly (labeled stale), instead of waiting on a heavy/throttled provider to warm.
        if (snapshotStore != null) {
            try {
                int seeded = 0;
                for (MarketSnapshot s : snapshotStore.loadAll()) {
                    if (s.last() != null) { snapshots.put(s.symbol(), s); track(s.symbol()); seeded++; }
                }
                if (seeded > 0) log.info("market engine seeded {} last-known quotes from disk (stale-first)", seeded);
            } catch (Exception e) { log.warn("snapshot seed failed: {}", e.toString()); }
        }
        if (!cfg.engineEnabled()) {
            log.info("market engine: serving path on, background refresh DISABLED (ENGINE_ENABLED=false)");
            return;
        }
        int tick = Math.max(5, Math.min(cfg.engineQuoteRefreshSeconds(), cfg.engineQuoteRefreshClosedSeconds()));
        scheduler = Executors.newSingleThreadScheduledExecutor(daemon("mkt-engine-scheduler"));
        try {
            // The ACTIVE sector warms immediately (that's what the first screen shows). The rest of the
            // universe warms a few seconds later so switching sectors / looking up any ticker is instant,
            // without contending the visible screen's fetches at boot.
            List<String> active = universe.active().symbols();
            // TRICKLE even the active sector in live mode: firing all symbols at once queues them
            // fairly AHEAD of interactive requests at the heavy provider's semaphore (a 15-symbol
            // sector imposed ~18s of queueing on the first user click). Spaced ~1.5s apart, at most
            // one warm task ever waits at the gate; fixture mode warms instantly.
            long warmSpacingMs = cfg.fixturesOnly() ? 0 : 1500;
            for (int i = 0; i < active.size(); i++) {
                String s = active.get(i);
                track(s);
                if (warmSpacingMs == 0) backgroundRefresh(s);
                else scheduler.schedule(() -> backgroundRefresh(s), i * warmSpacingMs, TimeUnit.MILLISECONDS);
            }
            // Full-universe warming is OFF by default: with a heavy keyless source like Cboe (every
            // "quote" is a full option-chain payload), warming ~95 symbols rate-limits us. Only the
            // active/visible sector warms unless ENGINE_WARM_FULL_UNIVERSE is set (light/licensed feed).
            if (cfg.engineWarmFullUniverse()) {
                log.info("market engine warming active sector ({}); full universe staged", active.size());
                scheduler.schedule(this::warmFullUniverse, 12, TimeUnit.SECONDS);
            } else {
                log.info("market engine warming active sector only ({} symbols); full-universe warm disabled", active.size());
            }
        } catch (Exception e) {
            log.warn("market engine warm failed: {}", e.toString());
        }
        scheduler.scheduleWithFixedDelay(this::tick, tick, tick, TimeUnit.SECONDS);
    }

    /**
     * Warm the whole curated universe, but TRICKLED in small batches spaced a few seconds apart so
     * the refresh pool is never flooded — on-demand quotes() (the visible screen) stay responsive
     * while the rest of the universe fills in over ~half a minute. Capped by ENGINE_MAX_TRACKED.
     */
    private void warmFullUniverse() {
        try {
            List<String> all = universe.warmSymbols();
            int cap = Math.max(20, cfg.engineMaxTracked());
            if (all.size() > cap) all = all.subList(0, cap);
            int batch = 8;
            for (int i = 0; i < all.size(); i += batch) {
                List<String> chunk = new ArrayList<>(all.subList(i, Math.min(i + batch, all.size())));
                long delaySec = 1L + (i / batch) * 3L; // ~8 symbols every 3s
                if (scheduler != null && !scheduler.isShutdown()) {
                    scheduler.schedule(() -> chunk.forEach(s -> { track(s); backgroundRefresh(s); }), delaySec, TimeUnit.SECONDS);
                }
            }
            log.info("market engine trickling full universe ({} symbols) in batches of {}", all.size(), batch);
        } catch (Exception e) {
            log.warn("market engine full-universe warm failed: {}", e.toString());
        }
    }

    public synchronized void stop() {
        running = false;
        if (scheduler != null) scheduler.shutdownNow();
        if (refreshPool != null) refreshPool.shutdownNow();
    }

    // ---- Serving ----

    /**
     * The batch the tape and /api/quotes call: returns snapshots for the requested symbols, serving
     * warm state instantly and fetching only the genuinely-missing ones (in parallel). Stale-but-
     * present symbols are returned immediately and refreshed in the background.
     */
    public List<MarketSnapshot> quotes(List<String> symbols) {
        List<String> missing = new ArrayList<>();
        for (String raw : symbols) {
            String s = norm(raw);
            if (s.isEmpty()) continue;
            track(s);
            MarketSnapshot snap = snapshots.get(s);
            if (snap == null) missing.add(s);
            else if (isStale(snap)) refreshAsync(s); // serve stale, refresh behind
        }
        // Fill the truly-cold symbols in parallel so a first request is complete without becoming a
        // sequential download chain (the original /api/quotes cold-start waterfall).
        if (!missing.isEmpty()) fetchBlocking(missing);

        List<MarketSnapshot> out = new ArrayList<>();
        for (String raw : symbols) {
            MarketSnapshot snap = snapshots.get(norm(raw));
            if (snap != null && snap.last() != null) out.add(snap);
        }
        return out;
    }

    /** Single-symbol accessor: warm state now (refresh behind if stale), or a blocking fetch if cold. */
    public Optional<MarketSnapshot> quote(String symbol) {
        String s = norm(symbol);
        if (s.isEmpty()) return Optional.empty();
        track(s);
        MarketSnapshot snap = snapshots.get(s);
        if (snap == null) { fetchBlocking(List.of(s)); return Optional.ofNullable(snapshots.get(s)); }
        if (isStale(snap)) refreshAsync(s);
        return Optional.of(snap);
    }

    // ---- Refresh internals ----

    private void tick() {
        try {
            // A user is waiting on a cold fetch right now: skip this background cycle entirely so
            // scheduled refreshes never queue ahead of (or beside) the interactive request at the
            // provider's concurrency gate. The next tick picks the stale symbols back up.
            if (pendingInteractive.get() > 0) return;
            int interval = currentIntervalSeconds();
            long now = clock.millis();
            for (String s : new ArrayList<>(lastAccess.keySet())) {
                MarketSnapshot snap = snapshots.get(s);
                long age = snap == null ? Long.MAX_VALUE : now - snap.lastRefreshEpochMs();
                if (age >= interval * 1000L) refreshAsync(s);
            }
            evictOverflow();
        } catch (Exception e) {
            log.debug("engine tick error: {}", e.toString());
        }
    }

    /**
     * Singleflight: exactly one in-flight refresh per symbol, shared by every caller. computeIfAbsent
     * is atomic, so a warm-on-boot refresh and a concurrent request for the same symbol join ONE
     * provider call instead of racing (and a blocking caller can await the warm's future).
     */
    private java.util.concurrent.CompletableFuture<Void> refreshFuture(String symbol) {
        if (refreshPool == null || refreshPool.isShutdown()) {
            try { doRefresh(symbol); } catch (Exception e) { /* recorded on the snapshot */ }
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        return inFlight.computeIfAbsent(symbol, s -> {
            markRefreshing(s, true);
            try {
                return java.util.concurrent.CompletableFuture
                        .runAsync(() -> { try { doRefresh(s); } finally { markRefreshing(s, false); } }, refreshPool)
                        .whenComplete((v, e) -> inFlight.remove(s));
            } catch (java.util.concurrent.RejectedExecutionException ex) {
                // Pool shutting down between the isShutdown() check and submit — clear the flag and
                // do NOT cache anything (computeIfAbsent stores nothing when the mapping throws).
                markRefreshing(s, false);
                throw ex;
            }
        });
    }

    /**
     * FORCED, BLOCKING refresh — the truthful backend of the Data Center's "refresh now" job:
     * returns only after the provider round-trip lands (or times out), so a job item can never
     * report success for a refresh that hasn't happened. Joins any in-flight refresh (singleflight).
     */
    public boolean refreshBlocking(String symbol, long timeoutMs) {
        track(symbol);
        try {
            refreshFuture(symbol).get(Math.max(1000, timeoutMs), TimeUnit.MILLISECONDS);
            MarketSnapshot snap = snapshots.get(norm(symbol));
            return snap != null && snap.last() != null && snap.error() == null;
        } catch (Exception e) {
            return false;
        }
    }

    private void refreshAsync(String symbol) {
        try { refreshFuture(symbol); } catch (Exception e) { /* pool rejected; next tick retries */ }
    }

    /** Background variant: yields to any pending interactive fetch (the tick retries the symbol). */
    private void backgroundRefresh(String symbol) {
        if (pendingInteractive.get() > 0) return;
        refreshAsync(symbol);
    }

    /** Blocking parallel fill for cold symbols: JOINS any in-flight (e.g. warm) refresh, never skips. */
    private void fetchBlocking(List<String> symbols) {
        pendingInteractive.incrementAndGet();
        try {
            List<java.util.concurrent.CompletableFuture<Void>> futures = new ArrayList<>();
            for (String s : symbols) {
                try { futures.add(refreshFuture(s)); } catch (Exception e) { /* skip; snapshot may stay cold */ }
            }
            java.util.concurrent.CompletableFuture
                    .allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0]))
                    .get(cfg.httpTimeoutMs() + 5000L, TimeUnit.MILLISECONDS);
        } catch (Exception e) { /* leave whatever we have; snapshots carry any error */
        } finally {
            pendingInteractive.decrementAndGet();
        }
    }

    private void doRefresh(String symbol) {
        long t0 = clock.millis();
        try {
            Optional<Quote> q = market.quote(symbol);
            long t1 = clock.millis();
            refreshCount.incrementAndGet();
            refreshLatencyTotalMs.addAndGet(Math.max(0, t1 - t0));
            lastRefreshEpochMs = t1;
            if (q.isEmpty()) {
                putError(symbol, "no quote from any provider");
                return;
            }
            Quote v = q.get();
            commit(symbol, new MarketSnapshot(v.symbol(), v.description(), v.last(), v.bid(), v.ask(),
                    v.prevClose(), v.optionable(), v.freshness(), v.source(), v.asOfEpochMs(), t1, false, null));
        } catch (Exception e) {
            putError(symbol, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Write a snapshot only if the symbol is still tracked — a refresh finishing after eviction must
     *  not re-orphan the symbol (present in snapshots, absent from lastAccess). */
    private void commit(String symbol, MarketSnapshot snap) {
        if (lastAccess.containsKey(symbol)) snapshots.put(symbol, snap);
        else snapshots.remove(symbol);
        // Mirror real quotes to disk (best-effort) so the next boot is stale-first. Errors ignored.
        if (snapshotStore != null && snap.last() != null && snap.error() == null) {
            try { snapshotStore.save(snap); } catch (Exception e) { /* persistence is best-effort */ }
        }
    }

    private void putError(String symbol, String error) {
        MarketSnapshot prev = snapshots.get(symbol);
        long now = clock.millis();
        if (prev != null) {
            // keep the last good data, just record the failed refresh
            commit(symbol, new MarketSnapshot(prev.symbol(), prev.description(), prev.last(), prev.bid(),
                    prev.ask(), prev.prevClose(), prev.optionable(), prev.freshness(), prev.source(),
                    prev.asOfEpochMs(), now, false, error));
        } else {
            commit(symbol, new MarketSnapshot(symbol, null, null, null, null, null, false,
                    Freshness.MISSING, null, 0L, now, false, error));
        }
    }

    private void markRefreshing(String symbol, boolean refreshing) {
        MarketSnapshot s = snapshots.get(symbol);
        if (s == null) return;
        if (s.refreshing() == refreshing) return;
        snapshots.put(symbol, new MarketSnapshot(s.symbol(), s.description(), s.last(), s.bid(), s.ask(),
                s.prevClose(), s.optionable(), s.freshness(), s.source(), s.asOfEpochMs(),
                s.lastRefreshEpochMs(), refreshing, s.error()));
    }

    // ---- Tracking / eviction ----

    private void track(String symbol) { lastAccess.put(symbol, clock.millis()); }

    /** LRU-evict tracked symbols beyond the cap, but never the active universe (it stays warm). */
    private void evictOverflow() {
        int cap = Math.max(20, cfg.engineMaxTracked());
        if (lastAccess.size() <= cap) return;
        java.util.Set<String> keep;
        try { keep = new java.util.HashSet<>(universe.active().symbols()); }
        catch (Exception e) { keep = java.util.Set.of(); }
        List<Map.Entry<String, Long>> byAge = new ArrayList<>(lastAccess.entrySet());
        byAge.sort(Comparator.comparingLong(Map.Entry::getValue)); // oldest access first
        for (Map.Entry<String, Long> e : byAge) {
            if (lastAccess.size() <= cap) break;
            String s = e.getKey();
            if (keep.contains(s)) continue;
            lastAccess.remove(s);
            snapshots.remove(s);
        }
    }

    // ---- Status ----

    public EngineStatus status() {
        long now = clock.millis();
        int interval = currentIntervalSeconds();
        int warmed = 0, stale = 0, errors = 0;
        List<SymbolStatus> syms = new ArrayList<>();
        List<String> tracked = new ArrayList<>(lastAccess.keySet());
        tracked.sort(Comparator.naturalOrder());
        for (String s : tracked) {
            MarketSnapshot snap = snapshots.get(s);
            boolean w = snap != null && snap.last() != null;
            if (w) warmed++;
            if (snap != null && isStale(snap)) stale++;
            if (snap != null && snap.error() != null) errors++;
            syms.add(new SymbolStatus(s, w, snap != null && snap.refreshing(),
                    snap == null ? "MISSING" : snap.freshness().name(),
                    snap == null ? null : snap.source(),
                    snap == null ? -1 : now - snap.lastRefreshEpochMs(),
                    snap == null ? null : snap.error()));
        }
        long avg = refreshCount.get() == 0 ? 0 : refreshLatencyTotalMs.get() / refreshCount.get();
        return new EngineStatus(cfg.engineEnabled(), running, MarketHours.isRegularSession(clock.instant()),
                interval, tracked.size(), warmed, stale, inFlight.size(), errors, avg, lastRefreshEpochMs, syms);
    }

    // ---- Helpers ----

    private int currentIntervalSeconds() {
        return MarketHours.isRegularSession(clock.instant())
                ? Math.max(5, cfg.engineQuoteRefreshSeconds())
                : Math.max(5, cfg.engineQuoteRefreshClosedSeconds());
    }

    private boolean isStale(MarketSnapshot snap) {
        return clock.millis() - snap.lastRefreshEpochMs() >= currentIntervalSeconds() * 1000L;
    }

    private static String norm(String s) { return s == null ? "" : s.trim().toUpperCase(Locale.ROOT); }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> { Thread t = new Thread(r, name); t.setDaemon(true); return t; };
    }

    /** JSON-friendly rows for the tape / quotes batch (keeps the existing /api/quotes shape + extras). */
    public static Map<String, Object> toRow(MarketSnapshot s) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("symbol", s.symbol());
        row.put("description", s.description());
        row.put("last", s.last() == null ? null : s.last().toPlainString());
        row.put("bid", s.bid() == null ? null : s.bid().toPlainString());
        row.put("ask", s.ask() == null ? null : s.ask().toPlainString());
        row.put("prevClose", s.prevClose() == null ? null : s.prevClose().toPlainString());
        row.put("optionable", s.optionable());
        row.put("freshness", s.freshness().name());
        row.put("asOf", s.asOfEpochMs());
        row.put("refreshing", s.refreshing());
        return row;
    }
}
