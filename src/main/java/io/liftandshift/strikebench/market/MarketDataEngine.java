package io.liftandshift.strikebench.market;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.model.Symbol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

    /**
     * A transport/storage view over the normalized {@link Quote}, plus refresh bookkeeping.
     * MarketDataService owns the Quote value; the engine owns only when/how it is refreshed.
     */
    public record MarketSnapshot(Quote quote, long lastRefreshEpochMs,
                                 boolean refreshing, String error) {
        public MarketSnapshot {
            if (quote == null) throw new IllegalArgumentException("market snapshot needs a quote");
        }

        /** Compatibility constructor for durable rows and provider-focused tests. */
        public MarketSnapshot(String symbol, String description, BigDecimal last, BigDecimal bid,
                              BigDecimal ask, BigDecimal prevClose, boolean optionable,
                              Freshness freshness, String source, long asOfEpochMs,
                              long lastRefreshEpochMs, boolean refreshing, String error) {
            this(new Quote(symbol, description, last, bid, ask, prevClose,
                    null, null, null, optionable, asOfEpochMs, source, freshness),
                    lastRefreshEpochMs, refreshing, error);
        }

        public static MarketSnapshot of(Quote quote, long lastRefreshEpochMs,
                                        boolean refreshing, String error) {
            return new MarketSnapshot(quote, lastRefreshEpochMs, refreshing, error);
        }

        public String symbol() { return quote.symbol(); }
        public String description() { return quote.description(); }
        public BigDecimal last() { return quote.last(); }
        public BigDecimal bid() { return quote.bid(); }
        public BigDecimal ask() { return quote.ask(); }
        public BigDecimal prevClose() { return quote.prevClose(); }
        public boolean optionable() { return quote.optionable(); }
        public Freshness freshness() { return quote.freshness(); }
        public String source() { return quote.source(); }
        public long asOfEpochMs() { return quote.asOfEpochMs(); }

        /** Live/marks path — preserves the snapshot's own freshness tier. */
        public Quote toQuote() { return quote; }

        /** Durable last-known-quote fallback — forces STALE so it can never present as live. */
        public Quote toStaleQuote() {
            return new Quote(symbol(), description(), last(), bid(), ask(), prevClose(),
                    quote.dayHigh(), quote.dayLow(), quote.volume(), optionable(), asOfEpochMs(),
                    source(), Freshness.STALE);
        }
    }

    private record RefreshState(long lastRefreshEpochMs, boolean refreshing, String error) {}

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
    private final Map<Symbol, RefreshState> refreshState = new ConcurrentHashMap<>();
    private final Map<Symbol, Long> lastAccess = new ConcurrentHashMap<>();   // for LRU eviction of tracked symbols
    private final Map<Symbol, java.util.concurrent.CompletableFuture<Void>> inFlight = new ConcurrentHashMap<>(); // singleflight: callers JOIN the in-flight refresh
    private final AtomicLong refreshCount = new AtomicLong();
    private final AtomicLong refreshLatencyTotalMs = new AtomicLong();
    private volatile long lastRefreshEpochMs = 0L;

    private java.util.concurrent.ThreadPoolExecutor refreshPool; // bounded + PRIORITY-ordered
    /** interactive(0) > active-screen(1) > explicit job(2) > warm(3) — queued work executes in this order. */
    static final int P_INTERACTIVE = 0, P_SCREEN = 1, P_JOB = 2, P_WARM = 3;
    private final java.util.concurrent.atomic.AtomicLong taskSeq = new java.util.concurrent.atomic.AtomicLong();

    /** A queued refresh with an explicit class: the queue drains user-facing work FIRST, always. */
    private static final class PriorityTask implements Runnable, Comparable<PriorityTask> {
        final int priority; final long seq; final Runnable body;
        final java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
        PriorityTask(int priority, long seq, Runnable body) { this.priority = priority; this.seq = seq; this.body = body; }
        @Override public void run() { started.set(true); body.run(); }
        @Override public int compareTo(PriorityTask o) {
            return priority != o.priority ? Integer.compare(priority, o.priority) : Long.compare(seq, o.seq);
        }
    }

    /** The queued (not yet started) task per symbol — so a higher-priority join can ESCALATE it. */
    private final java.util.concurrent.ConcurrentHashMap<Symbol, PriorityTask> queuedTask = new java.util.concurrent.ConcurrentHashMap<>();
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

    /** Inject the persistence store so the engine boots stale-first and mirrors eligible refreshes. */
    public void setSnapshotStore(io.liftandshift.strikebench.market.ports.SnapshotStore store) {
        market.setQuoteSnapshotStore(store);
    }

    // ---- Lifecycle ----

    /** Warms the active universe and starts the background refresh loop. Idempotent; safe if disabled. */
    public synchronized void start() {
        if (running) return;
        // The serving path (quotes()) always works; the scheduler + warm only run when enabled.
        refreshPool = new java.util.concurrent.ThreadPoolExecutor(8, 8, 30, TimeUnit.SECONDS,
                new java.util.concurrent.PriorityBlockingQueue<>(), daemon("mkt-engine-refresh"));
        running = true;
        // Boot STALE-FIRST: seed memory from persisted last-known quotes so the tape/tiles paint
        // instantly (labeled stale), instead of waiting on a heavy/throttled provider to warm.
        int seeded = 0;
        for (Quote quote : market.cachedQuotes()) {
            Symbol symbol = Symbol.of(quote.symbol());
            refreshState.put(symbol, new RefreshState(quote.asOfEpochMs(), false, null));
            track(symbol);
            seeded++;
        }
        if (seeded > 0) {
            log.info("market engine restored {} last-known quotes from the quote cache", seeded);
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
                Symbol s = Symbol.of(active.get(i));
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
            log.warn("Initial market warmup did not complete; symbols will load on demand");
            log.debug("Initial market warmup detail", e);
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
                    scheduler.schedule(() -> chunk.stream().map(Symbol::of)
                            .forEach(s -> { track(s); backgroundRefresh(s); }), delaySec, TimeUnit.SECONDS);
                }
            }
            log.info("market engine trickling full universe ({} symbols) in batches of {}", all.size(), batch);
        } catch (Exception e) {
            log.warn("Background universe warmup did not complete; symbols will load on demand");
            log.debug("Background universe warmup detail", e);
        }
    }

    public synchronized void stop() {
        running = false;
        if (scheduler != null) scheduler.shutdownNow();
        if (refreshPool != null) refreshPool.shutdownNow();
        awaitStopped(scheduler);
        awaitStopped(refreshPool);
    }

    private static void awaitStopped(java.util.concurrent.ExecutorService executor) {
        if (executor == null) return;
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                log.warn("Market-data worker did not stop within the shutdown window");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- Serving ----

    /**
     * The batch the tape and /api/quotes call: returns snapshots for the requested symbols, serving
     * warm state instantly and fetching only the genuinely-missing ones (in parallel). Stale-but-
     * present symbols are returned immediately and refreshed in the background.
     */
    /** MEMORY-ONLY read: what the engine already knows, with zero fetch side effects — the
     *  anchor resolver's background tier reads this so a cold sector can never cause a burst. */
    public java.util.Optional<MarketSnapshot> peek(String symbol) {
        Symbol key = Symbol.optional(symbol);
        return key == null ? Optional.empty() : snapshot(key);
    }

    public List<MarketSnapshot> quotes(List<String> symbols) {
        List<Symbol> requested = symbols == null ? List.of() : symbols.stream()
                .map(Symbol::optional).filter(java.util.Objects::nonNull).distinct().toList();
        List<Symbol> missing = new ArrayList<>();
        for (Symbol s : requested) {
            track(s);
            Quote quote = market.peekQuote(s.value()).orElse(null);
            if (quote == null) missing.add(s);
            else if (refreshDue(s)) refreshAsync(s); // serve current cached evidence, refresh behind
        }
        // Fill the truly-cold symbols in parallel so a first request is complete without becoming a
        // sequential download chain (the original /api/quotes cold-start waterfall).
        if (!missing.isEmpty()) fetchBlocking(missing);

        List<MarketSnapshot> out = new ArrayList<>();
        for (Symbol symbol : requested) {
            snapshot(symbol).filter(MarketDataEngine::hasUsablePrice).ifPresent(out::add);
        }
        return out;
    }

    /** A snapshot can still carry an honest mark when the feed omits last trade: a sane
     * two-sided book or the explicitly stale previous close remains usable and disclosed.
     * The rule itself belongs to {@link Quote#markBasis()} — the engine asks, it does not
     * keep a second copy of it. */
    private static boolean hasUsablePrice(MarketSnapshot snapshot) {
        return snapshot != null && snapshot.toQuote().markBasis() != Quote.MarkBasis.UNAVAILABLE;
    }

    /**
     * WHY this symbol has no usable mark right now, in the engine's own words. Callers publish
     * this instead of dropping the symbol or substituting a zero (§3.2); the engine is the only
     * thing that knows whether the symbol was never fetched, failed, or is still in flight.
     */
    public String unavailableReason(String symbol) {
        Symbol s = Symbol.of(symbol);
        Quote quote = market.peekQuote(s.value()).orElse(null);
        RefreshState state = refreshState.get(s);
        if (quote == null && state == null) {
            return "the market engine holds no snapshot for " + s
                    + " — no configured provider returned data for that symbol";
        }
        if (state != null && state.error() != null && !state.error().isBlank()) {
            return "the last " + s + " refresh failed: " + state.error();
        }
        if (state != null && state.refreshing()) {
            return s + " is being fetched now — no last trade, two-sided book or previous close"
                    + " has arrived yet";
        }
        return "the " + s + " snapshot from " + (quote == null ? "the configured sources" : quote.source())
                + " carries no last trade, no two-sided book and no previous close";
    }

    /** Single-symbol accessor: warm state now (refresh behind if stale), or a blocking fetch if cold. */
    public Optional<MarketSnapshot> quote(String symbol) {
        Symbol s = Symbol.optional(symbol);
        if (s == null) return Optional.empty();
        track(s);
        if (market.peekQuote(s.value()).isEmpty()) fetchBlocking(List.of(s));
        else if (refreshDue(s)) refreshAsync(s);
        return snapshot(s);
    }

    /** One public current-quote authority for every exchange mode. */
    public Optional<Quote> currentQuote(String symbol, String worldId) {
        if (worldId == null || worldId.isBlank() || "observed".equalsIgnoreCase(worldId)) {
            return quote(symbol).map(MarketSnapshot::toQuote);
        }
        return market.quote(symbol, worldId);
    }

    /** Memory-only counterpart used by request paths that promise not to acquire provider data. */
    public Optional<Quote> peekCurrentQuote(String symbol, String worldId) {
        if (worldId == null || worldId.isBlank() || "observed".equalsIgnoreCase(worldId)) {
            return peek(symbol).map(MarketSnapshot::toQuote);
        }
        return market.quote(symbol, worldId);
    }

    private Optional<MarketSnapshot> snapshot(Symbol symbol) {
        return market.peekQuote(symbol.value()).map(quote -> {
            RefreshState state = refreshState.get(symbol);
            long refreshedAt = state == null ? quote.asOfEpochMs() : state.lastRefreshEpochMs();
            return MarketSnapshot.of(quote, refreshedAt,
                    state != null && state.refreshing(), state == null ? null : state.error());
        });
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
            for (Symbol s : new ArrayList<>(lastAccess.keySet())) {
                RefreshState state = refreshState.get(s);
                long age = state == null ? Long.MAX_VALUE : now - state.lastRefreshEpochMs();
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
    private java.util.concurrent.CompletableFuture<Void> refreshFuture(Symbol symbol) {
        return refreshFuture(symbol, P_SCREEN);
    }

    private java.util.concurrent.CompletableFuture<Void> refreshFuture(Symbol symbol, int priority) {
        if (refreshPool == null || refreshPool.isShutdown()) {
            try { doRefresh(symbol); } catch (Exception e) { /* recorded on the snapshot */ }
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        var joined = inFlight.get(symbol);
        if (joined != null) {
            // PRIORITY INVERSION FIX (R12): an interactive caller joining a QUEUED warm task must
            // not wait at warm priority — if the task hasn't started, requeue it at the new class.
            escalate(symbol, priority);
            return joined;
        }
        return inFlight.computeIfAbsent(symbol, s -> {
            markRefreshing(s, true);
            java.util.concurrent.CompletableFuture<Void> f = new java.util.concurrent.CompletableFuture<>();
            Runnable body = () -> {
                try { doRefresh(s); } finally {
                    markRefreshing(s, false);
                    inFlight.remove(s);
                    queuedTask.remove(s);
                    f.complete(null);
                }
            };
            try {
                PriorityTask task = new PriorityTask(priority, taskSeq.incrementAndGet(), body);
                queuedTask.put(s, task);
                refreshPool.execute(task);
                return f;
            } catch (java.util.concurrent.RejectedExecutionException ex) {
                // Pool shutting down between the isShutdown() check and submit — clear the flag and
                // do NOT cache anything (computeIfAbsent stores nothing when the mapping throws).
                markRefreshing(s, false);
                queuedTask.remove(s);
                throw ex;
            }
        });
    }

    private void escalate(Symbol symbol, int priority) {
        PriorityTask task = queuedTask.get(symbol);
        if (task == null || priority >= task.priority || task.started.get()) return;
        if (refreshPool.remove(task)) { // only a task still WAITING can be requeued
            PriorityTask bumped = new PriorityTask(priority, taskSeq.incrementAndGet(), task.body);
            queuedTask.put(symbol, bumped);
            try { refreshPool.execute(bumped); }
            catch (java.util.concurrent.RejectedExecutionException e) { queuedTask.remove(symbol); }
        }
    }

    /**
     * FORCED, BLOCKING refresh — the truthful backend of the Data Center's "refresh now" job:
     * returns only after the provider round-trip lands (or times out), so a job item can never
     * report success for a refresh that hasn't happened. Joins any in-flight refresh (singleflight).
     */
    public boolean refreshBlocking(String symbol, long timeoutMs) {
        Symbol key = Symbol.of(symbol);
        track(key);
        try {
            refreshFuture(key, P_JOB).get(Math.max(1000, timeoutMs), TimeUnit.MILLISECONDS);
            RefreshState state = refreshState.get(key);
            return market.peekQuote(key.value()).map(Quote::mark).orElse(null) != null
                    && state != null && state.error() == null;
        } catch (Exception e) {
            return false;
        }
    }

    private void refreshAsync(Symbol symbol) {
        try { refreshFuture(symbol); } catch (Exception e) { /* pool rejected; next tick retries */ }
    }

    /** Background variant: WARM class (drains last) + yields while a user fetch is in flight. */
    private void backgroundRefresh(Symbol symbol) {
        if (pendingInteractive.get() > 0) return;
        try { refreshFuture(symbol, P_WARM); } catch (Exception e) { /* pool rejected; next tick retries */ }
    }

    /** Blocking parallel fill for cold symbols: JOINS any in-flight (e.g. warm) refresh, never skips. */
    private void fetchBlocking(List<Symbol> symbols) {
        pendingInteractive.incrementAndGet();
        try {
            List<java.util.concurrent.CompletableFuture<Void>> futures = new ArrayList<>();
            for (Symbol s : symbols) {
                try { futures.add(refreshFuture(s, P_INTERACTIVE)); } catch (Exception e) { /* skip; snapshot may stay cold */ }
            }
            java.util.concurrent.CompletableFuture
                    .allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0]))
                    .get(cfg.httpTimeoutMs() + 5000L, TimeUnit.MILLISECONDS);
        } catch (Exception e) { /* leave whatever we have; snapshots carry any error */
        } finally {
            pendingInteractive.decrementAndGet();
        }
    }

    private void doRefresh(Symbol symbol) {
        long t0 = clock.millis();
        try {
            MarketAcquisition<Quote> acquisition = market.refreshQuoteAcquisition(symbol.value());
            Optional<Quote> q = acquisition.value().map(quote -> {
                // MarketDataService owns the sole age gate. The engine records acquisition status;
                // it does not reinterpret or promote the quote's freshness.
                return market.peekQuote(quote.symbol()).orElse(quote);
            });
            long t1 = clock.millis();
            refreshCount.incrementAndGet();
            refreshLatencyTotalMs.addAndGet(Math.max(0, t1 - t0));
            if (q.isEmpty()) {
                putError(symbol, "no quote from any provider");
                return;
            }
            if (!acquisition.fresh()) {
                putError(symbol, acquisition.detail());
                return;
            }
            lastRefreshEpochMs = t1;
            if (lastAccess.containsKey(symbol)) {
                refreshState.put(symbol, new RefreshState(t1, false, null));
            } else {
                refreshState.remove(symbol);
            }
        } catch (Exception e) {
            putError(symbol, MarketDataService.publicProviderFailure(e));
            log.debug("Market refresh failure detail for " + symbol, e);
        }
    }

    private void putError(Symbol symbol, String error) {
        long now = clock.millis();
        if (lastAccess.containsKey(symbol)) {
            refreshState.put(symbol, new RefreshState(now, false, error));
        } else {
            refreshState.remove(symbol);
        }
    }

    private void markRefreshing(Symbol symbol, boolean refreshing) {
        refreshState.compute(symbol, (ignored, state) -> {
            long last = state == null ? 0L : state.lastRefreshEpochMs();
            String error = state == null ? null : state.error();
            return new RefreshState(last, refreshing, error);
        });
    }

    // ---- Tracking / eviction ----

    private void track(Symbol symbol) { lastAccess.put(symbol, clock.millis()); }

    /** LRU-evict tracked symbols beyond the cap, but never the active universe (it stays warm). */
    private void evictOverflow() {
        int cap = Math.max(20, cfg.engineMaxTracked());
        if (lastAccess.size() <= cap) return;
        java.util.Set<Symbol> keep;
        try {
            keep = universe.active().symbols().stream().map(Symbol::of)
                    .collect(java.util.stream.Collectors.toSet());
        }
        catch (Exception e) { keep = java.util.Set.of(); }
        List<Map.Entry<Symbol, Long>> byAge = new ArrayList<>(lastAccess.entrySet());
        byAge.sort(Comparator.comparingLong(Map.Entry::getValue)); // oldest access first
        for (Map.Entry<Symbol, Long> e : byAge) {
            if (lastAccess.size() <= cap) break;
            Symbol s = e.getKey();
            if (keep.contains(s)) continue;
            lastAccess.remove(s);
            refreshState.remove(s);
        }
    }

    // ---- Status ----

    public EngineStatus status() {
        long now = clock.millis();
        int interval = currentIntervalSeconds();
        int warmed = 0, stale = 0, errors = 0;
        List<SymbolStatus> syms = new ArrayList<>();
        List<Symbol> tracked = new ArrayList<>(lastAccess.keySet());
        tracked.sort(Comparator.naturalOrder());
        for (Symbol s : tracked) {
            Quote quote = market.peekQuote(s.value()).orElse(null);
            RefreshState state = refreshState.get(s);
            boolean w = quote != null && quote.mark() != null;
            if (w) warmed++;
            if (refreshDue(s)) stale++;
            if (state != null && state.error() != null) errors++;
            syms.add(new SymbolStatus(s.value(), w, state != null && state.refreshing(),
                    quote == null ? "MISSING" : quote.markFreshness().name(),
                    quote == null ? null : quote.source(),
                    state == null ? -1 : now - state.lastRefreshEpochMs(),
                    state == null ? null : state.error()));
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

    /**
     * Operational refresh cadence, deliberately not called "freshness": quote trust/age is owned
     * by MarketDataService and carried on Quote. This only answers whether the warmer should run.
     */
    private boolean refreshDue(Symbol symbol) {
        RefreshState state = refreshState.get(symbol);
        return state == null
                || clock.millis() - state.lastRefreshEpochMs() >= currentIntervalSeconds() * 1000L;
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> { Thread t = new Thread(r, name); t.setDaemon(true); return t; };
    }

    // The engine no longer hand-builds a quote row. It once carried its own rowMark() (mid, else
    // last, else previous close) and its own map shape, which made the batch a SECOND price
    // authority beside Quote.mark(). Callers now serve ApiResponses.QuoteView.of(snapshot.toQuote())
    // — one row shape, one price decision, for the batch, the tape and the research result.
}
