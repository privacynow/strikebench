package io.liftandshift.strikebench.market;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.market.ports.NewsFilingsProvider;
import io.liftandshift.strikebench.market.ports.RatesProvider;
import io.liftandshift.strikebench.market.ports.CandleStore;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.NewsItem;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.model.SymbolMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Routes market data requests through an ordered provider chain (highest priority first),
 * caches results, applies freshness gates, and tracks per-provider/per-domain health for
 * /api/status. Status reporting never throws — zero providers is a valid, reportable state.
 */
public final class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    /** Data older than these gates is downgraded to STALE (still shown, but labeled). */
    private static final long QUOTE_STALE_MS = Duration.ofMinutes(10).toMillis();
    private static final long CHAIN_STALE_MS = Duration.ofMinutes(30).toMillis();

    private final List<MarketDataProvider> providers;
    private final List<NewsFilingsProvider> newsProviders;
    private final List<RatesProvider> ratesProviders;
    private final io.liftandshift.strikebench.market.ports.CandleStore candleStore; // stored bars first (nullable)
    private volatile io.liftandshift.strikebench.market.ports.WarmOptionStore warmOptions; // warm chain fallback (nullable)
    private volatile io.liftandshift.strikebench.market.ports.SnapshotStore quoteSnapshotStore;
    private final boolean fixtureOnlyChain;
    private volatile MarketDataProvider demoProvider;
    private volatile NewsFilingsProvider demoNewsProvider;
    private volatile RatesProvider demoRatesProvider;

    private static final Duration EMPTY_QUOTE_TTL = Duration.ofSeconds(3);
    private static final Duration EMPTY_OPTION_TTL = Duration.ofSeconds(5);
    private static final Duration EMPTY_HISTORY_TTL = Duration.ofSeconds(15);
    private static final Duration EMPTY_NEWS_TTL = Duration.ofSeconds(30);
    private static final Duration MODELED_RATE_TTL = Duration.ofMinutes(5);
    private final Cache<String, Optional<Quote>> quoteCache = Caffeine.newBuilder()
            .expireAfter(MarketDataService.<Quote>optionalExpiry(Duration.ofSeconds(15), EMPTY_QUOTE_TTL))
            .maximumSize(500).build();
    private final Cache<String, Optional<OptionChain>> chainCache = Caffeine.newBuilder()
            .expireAfter(MarketDataService.<OptionChain>optionalExpiry(Duration.ofSeconds(60), EMPTY_OPTION_TTL))
            .maximumSize(200).build();
    private final Cache<String, List<LocalDate>> expirationsCache = Caffeine.newBuilder()
            .expireAfter(MarketDataService.<LocalDate>listExpiry(Duration.ofSeconds(60), EMPTY_OPTION_TTL))
            .maximumSize(200).build();
    private record CachedCandleSeries(CandleSeries series, boolean partialObserved) {}
    private static final Duration COMPLETE_CANDLE_CACHE_TTL = Duration.ofHours(1);
    private static final Duration PARTIAL_CANDLE_CACHE_TTL = Duration.ofMinutes(5);
    private final Cache<String, Optional<CachedCandleSeries>> candlesCache = Caffeine.newBuilder()
            .expireAfter(new Expiry<String, Optional<CachedCandleSeries>>() {
                @Override public long expireAfterCreate(String key, Optional<CachedCandleSeries> value, long currentTime) {
                    return value.map(v -> candleCacheTtl(v.partialObserved())).orElse(EMPTY_HISTORY_TTL).toNanos();
                }
                @Override public long expireAfterUpdate(String key, Optional<CachedCandleSeries> value, long currentTime,
                                                        long currentDuration) {
                    return value.map(v -> candleCacheTtl(v.partialObserved())).orElse(EMPTY_HISTORY_TTL).toNanos();
                }
                @Override public long expireAfterRead(String key, Optional<CachedCandleSeries> value, long currentTime,
                                                      long currentDuration) {
                    return currentDuration;
                }
            }).maximumSize(100).build();
    private final Cache<String, List<NewsItem>> newsCache = Caffeine.newBuilder()
            .expireAfter(MarketDataService.<NewsItem>listExpiry(Duration.ofMinutes(5), EMPTY_NEWS_TTL))
            .maximumSize(200).build();
    private final Cache<Integer, RateQuote> rateCache = Caffeine.newBuilder()
            .expireAfter(new Expiry<Integer, RateQuote>() {
                private long ttl(RateQuote value) {
                    return (value != null && value.evidence() != null
                            && value.evidence().provenance() == io.liftandshift.strikebench.model.DataProvenance.MODELED
                            ? MODELED_RATE_TTL : Duration.ofHours(1)).toNanos();
                }
                @Override public long expireAfterCreate(Integer key, RateQuote value, long now) { return ttl(value); }
                @Override public long expireAfterUpdate(Integer key, RateQuote value, long now, long current) { return ttl(value); }
                @Override public long expireAfterRead(Integer key, RateQuote value, long now, long current) { return current; }
            }).maximumSize(20).build();

    /** Provider I/O never runs while a Caffeine mapping lock is held. One explicit future per
     * domain/key collapses concurrent misses, while cacheGeneration prevents an old request from
     * repopulating data after a world/reset invalidation. */
    private final Map<String, CompletableFuture<Object>> inFlightLoads = new ConcurrentHashMap<>();
    private final AtomicLong cacheGeneration = new AtomicLong();

    private final Map<String, ProviderStatusInfo> statusByKey = new ConcurrentHashMap<>();

    public MarketDataService(List<MarketDataProvider> providers,
                             List<NewsFilingsProvider> newsProviders,
                             List<RatesProvider> ratesProviders) {
        this(providers, newsProviders, ratesProviders, null);
    }

    /** With a {@link io.liftandshift.strikebench.market.ports.CandleStore}: persisted daily bars are
     *  served before the provider chain, so a Data Center backfill actually feeds Research/backtests. */
    public MarketDataService(List<MarketDataProvider> providers,
                             List<NewsFilingsProvider> newsProviders,
                             List<RatesProvider> ratesProviders,
                             io.liftandshift.strikebench.market.ports.CandleStore candleStore) {
        this.candleStore = candleStore;
        this.providers = List.copyOf(providers);
        this.newsProviders = List.copyOf(newsProviders);
        this.ratesProviders = List.copyOf(ratesProviders);
        this.fixtureOnlyChain = !this.providers.isEmpty()
                && this.providers.stream().allMatch(p -> FIXTURE.equals(p.name()))
                && this.newsProviders.stream().allMatch(p -> FIXTURE.equals(p.name()))
                && this.ratesProviders.stream().allMatch(p -> FIXTURE.equals(p.name()));
        // Seed UNKNOWN status so /api/status is complete before first use
        for (MarketDataProvider p : this.providers) {
            for (Domain d : p.domains()) {
                if (d == Domain.RATES || d == Domain.NEWS) continue; // reported via dedicated lists
                statusByKey.putIfAbsent(key(p.name(), d), ProviderStatusInfo.unknown(p.name(), d.name()));
            }
        }
        for (NewsFilingsProvider p : this.newsProviders) {
            statusByKey.putIfAbsent(key(p.name(), Domain.NEWS), ProviderStatusInfo.unknown(p.name(), Domain.NEWS.name()));
        }
        for (RatesProvider p : this.ratesProviders) {
            statusByKey.putIfAbsent(key(p.name(), Domain.RATES), ProviderStatusInfo.unknown(p.name(), Domain.RATES.name()));
        }
    }

    /** Mounts fixtures behind the explicit Demo lane; it does not add them to Observed. */
    public void setDemoSources(MarketDataProvider market, NewsFilingsProvider news, RatesProvider rates) {
        this.demoProvider = market;
        this.demoNewsProvider = news;
        this.demoRatesProvider = rates;
    }

    /** Shares the engine's durable observed quote mirror; never mounted in Demo-only builds. */
    public void setQuoteSnapshotStore(io.liftandshift.strikebench.market.ports.SnapshotStore store) {
        this.quoteSnapshotStore = store;
    }

    /**
     * Mounts the warm option-chain store (last-known {@code option_bar} chains). Used only as an
     * observed-lane fallback when the live provider chain yields nothing, so an exhausted or
     * rate-limited provider serves the last-known chain instead of an empty "no listed options".
     */
    public void setWarmOptionStore(io.liftandshift.strikebench.market.ports.WarmOptionStore store) {
        this.warmOptions = store;
    }

    public MarketLane lane(String worldId) { return MarketLane.of(worldId, fixtureOnlyChain); }

    /** The complete analysis lane: execution market plus the caller's selected dataset. */
    public MarketLane lane(String worldId, io.liftandshift.strikebench.db.AnalysisContext context) {
        return MarketLane.of(worldId, fixtureOnlyChain, context);
    }

    private static <T> Expiry<String, Optional<T>> optionalExpiry(Duration present, Duration empty) {
        return new Expiry<>() {
            private long ttl(Optional<T> value) { return (value.isPresent() ? present : empty).toNanos(); }
            @Override public long expireAfterCreate(String key, Optional<T> value, long now) { return ttl(value); }
            @Override public long expireAfterUpdate(String key, Optional<T> value, long now, long current) { return ttl(value); }
            @Override public long expireAfterRead(String key, Optional<T> value, long now, long current) { return current; }
        };
    }

    private static <T> Expiry<String, List<T>> listExpiry(Duration present, Duration empty) {
        return new Expiry<>() {
            private long ttl(List<T> value) { return (value == null || value.isEmpty() ? empty : present).toNanos(); }
            @Override public long expireAfterCreate(String key, List<T> value, long now) { return ttl(value); }
            @Override public long expireAfterUpdate(String key, List<T> value, long now, long current) { return ttl(value); }
            @Override public long expireAfterRead(String key, List<T> value, long now, long current) { return current; }
        };
    }

    @SuppressWarnings("unchecked")
    private <K, V> V cached(Cache<K, V> cache, K key, String domain, Supplier<V> loader) {
        V hit = cache.getIfPresent(key);
        if (hit != null) return hit;
        long generation = cacheGeneration.get();
        String flightKey = domain + "|" + generation + "|" + key;
        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> joined = inFlightLoads.putIfAbsent(flightKey, mine);
        if (joined != null) {
            try { return (V) joined.join(); }
            catch (CompletionException e) {
                if (e.getCause() instanceof RuntimeException runtime) throw runtime;
                throw e;
            }
        }
        try {
            V loaded = java.util.Objects.requireNonNull(loader.get(), "cache loader returned null");
            if (cacheGeneration.get() == generation) cache.put(key, loaded);
            mine.complete(loaded);
            return loaded;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlightLoads.remove(flightKey, mine);
        }
    }

    public void invalidateAll() {
        cacheGeneration.incrementAndGet();
        quoteCache.invalidateAll();
        chainCache.invalidateAll();
        expirationsCache.invalidateAll();
        candlesCache.invalidateAll();
        newsCache.invalidateAll();
        rateCache.invalidateAll();
    }

    /** Durable bar imports/backfills changed history without changing the active dataset id. */
    public void invalidateHistoricalData() {
        cacheGeneration.incrementAndGet();
        candlesCache.invalidateAll();
    }

    // ---- Public API ----

    public List<SymbolMatch> lookup(String query) {
        List<SymbolMatch> merged = new ArrayList<>();
        for (MarketDataProvider p : providersFor(Domain.QUOTES)) {
            try {
                for (SymbolMatch m : p.lookup(query)) {
                    if (merged.stream().noneMatch(x -> x.symbol().equalsIgnoreCase(m.symbol()))) merged.add(m);
                }
                recordOk(p.name(), Domain.QUOTES);
            } catch (Exception e) {
                recordError(p.name(), Domain.QUOTES, e);
            }
        }
        return merged;
    }

    /** Lookup within the selected market; explicit worlds never suggest symbols from Observed. */
    public List<SymbolMatch> lookup(String query, String worldId) {
        if (observedWorld(worldId)) return lookup(query);
        String needle = norm(query);
        return worldSymbols(worldId).orElseGet(java.util.Set::of).stream()
                .filter(sym -> needle.isBlank() || sym.contains(needle))
                .sorted()
                .limit(40)
                .map(sym -> quote(sym, worldId)
                        .map(q -> new SymbolMatch(sym,
                                q.description() == null || q.description().isBlank()
                                        ? marketLabel(worldId) + " symbol" : q.description(),
                                q.optionable()))
                        .orElseGet(() -> new SymbolMatch(sym, marketLabel(worldId) + " symbol", true)))
                .toList();
    }

    private static String marketLabel(String worldId) {
        return "demo".equalsIgnoreCase(worldId) ? "Demo market" : "Simulated market";
    }

    /** Resolves a simulated world by id. Unknown explicit ids stay unavailable; they never fall through to Observed. */
    private volatile java.util.function.Function<String, java.util.Optional<io.liftandshift.strikebench.market.sim.SimulatedWorld>> worldResolver;

    public void setWorldResolver(java.util.function.Function<String, java.util.Optional<io.liftandshift.strikebench.market.sim.SimulatedWorld>> r) {
        this.worldResolver = r;
    }

    private java.util.Optional<io.liftandshift.strikebench.market.sim.SimulatedWorld> world(String worldId) {
        if (worldId == null || worldId.isBlank() || "observed".equals(worldId)
                || "demo".equals(worldId) || worldResolver == null) {
            return java.util.Optional.empty();
        }
        try { return worldResolver.apply(worldId); } catch (RuntimeException e) { return java.util.Optional.empty(); }
    }

    private static boolean observedWorld(String worldId) {
        return worldId == null || worldId.isBlank() || "observed".equalsIgnoreCase(worldId);
    }

    /** The lane's effective clock: the world's sim instant inside a simulated session, else empty. */
    public Optional<java.time.Instant> simInstant(String worldId) {
        return world(worldId).map(w -> w.simTime()
                .atZone(java.time.ZoneId.of("America/New_York")).toInstant());
    }

    /** The world's own symbol set (empty optional = observed lane). */
    public Optional<java.util.Set<String>> worldSymbols(String worldId) {
        if ("demo".equals(worldId) && demoProvider instanceof io.liftandshift.strikebench.market.providers.FixtureProvider f) {
            return Optional.of(f.symbols());
        }
        return world(worldId).map(io.liftandshift.strikebench.market.sim.SimulatedWorld::symbols);
    }

    /** World-aware quote: a simulated world serves ITS data (labeled SIMULATED); else observed. */
    public Optional<Quote> quote(String symbol, String worldId) {
        if ("demo".equals(worldId)) return demoProvider == null ? Optional.empty() : demoProvider.quote(norm(symbol));
        var w = world(worldId);
        if (w.isPresent()) return w.get().quote(symbol);
        return observedWorld(worldId) ? quote(symbol) : Optional.empty();
    }

    public List<LocalDate> expirations(String symbol, String worldId) {
        if ("demo".equals(worldId)) return demoProvider == null ? List.of() : demoProvider.expirations(norm(symbol));
        var w = world(worldId);
        if (w.isPresent()) return w.get().quote(symbol).isPresent() ? w.get().expirations() : List.of();
        return observedWorld(worldId) ? expirations(symbol) : List.of();
    }

    public Optional<OptionChain> chain(String symbol, LocalDate expiration, String worldId) {
        if ("demo".equals(worldId)) return demoProvider == null ? Optional.empty() : demoProvider.chain(norm(symbol), expiration);
        var w = world(worldId);
        if (w.isPresent()) return w.get().chain(symbol, expiration);
        return observedWorld(worldId) ? chain(symbol, expiration) : Optional.empty();
    }

    public CandleSeries candleSeries(String symbol, LocalDate from, LocalDate to, String worldId,
                                     io.liftandshift.strikebench.db.AnalysisContext actx) {
        // A saved Scenario is the explicit analysis lane on top of either normal Observed or
        // an explicit Demo build. It must read its own persisted bars before the baseline
        // market provider; live simulated exchanges remain mutually exclusive with datasets.
        if (actx != null && actx.synthetic()
                && (worldId == null || worldId.isBlank() || "observed".equals(worldId) || "demo".equals(worldId))) {
            return candleSeries(symbol, from, to, actx);
        }
        if ("demo".equals(worldId)) {
            if (demoProvider == null) return CandleSeries.EMPTY;
            List<Candle> cs = demoProvider.candles(norm(symbol), from, to);
            return cs.size() < 2 ? CandleSeries.EMPTY : new CandleSeries(cs, "fixture", Freshness.FIXTURE);
        }
        var w = world(worldId);
        if (w.isPresent()) {
            List<Candle> cs = w.get().candles(norm(symbol), from, to);
            return cs.size() < 2 ? CandleSeries.EMPTY
                    : new CandleSeries(cs, "simulated", Freshness.SIMULATED);
        }
        return observedWorld(worldId) ? candleSeries(symbol, from, to, actx) : CandleSeries.EMPTY;
    }

    /**
     * A history read that is guaranteed not to contact an external provider. This is the read
     * contract for automatically composed surfaces such as the Book: opening the desk may use
     * history the user already owns, but it must never spend a provider request budget merely to
     * paint a chart. Explicit Demo fixtures and live simulated worlds are local sources; Observed
     * and saved Scenario lanes read only the injected {@link CandleStore}.
     */
    public record LocalCandleRead(CandleSeries series, CandleCoverage coverage, String basis) {
        public LocalCandleRead {
            series = series == null ? CandleSeries.EMPTY : series;
            if (coverage == null) throw new IllegalArgumentException("local candle coverage is required");
            basis = basis == null || basis.isBlank()
                    ? "Local history only; external provider acquisition was not attempted."
                    : basis;
        }
    }

    public LocalCandleRead localCandleSeries(String symbol, LocalDate from, LocalDate to,
                                             String worldId,
                                             io.liftandshift.strikebench.db.AnalysisContext actx) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("a valid local history range is required");
        }
        String sym = norm(symbol);
        io.liftandshift.strikebench.db.AnalysisContext analysis = actx == null
                ? io.liftandshift.strikebench.db.AnalysisContext.OBSERVED : actx;
        String dataset = analysis.datasetId();

        // A saved Scenario is a locally persisted analysis dataset, even when it overlays the
        // ordinary Observed or Demo exchange lane. Never fall through to either baseline here.
        if (analysis.synthetic()
                && (worldId == null || worldId.isBlank()
                    || "observed".equals(worldId) || "demo".equals(worldId))) {
            return localStoredCandles(sym, from, to, dataset, false);
        }
        if ("demo".equals(worldId)) {
            List<Candle> candles = demoProvider == null ? List.of()
                    : demoProvider.candles(sym, from, to);
            CandleSeries series = candles.size() < 2 ? CandleSeries.EMPTY
                    : new CandleSeries(candles, "fixture", Freshness.FIXTURE);
            return new LocalCandleRead(series, CandleCoverage.assess(series.candles(), from, to),
                    "Built-in Demo history already resident in the application; no external "
                            + "provider acquisition was attempted.");
        }
        var simulated = world(worldId);
        if (simulated.isPresent()) {
            List<Candle> candles = simulated.get().candles(sym, from, to);
            CandleSeries series = candles.size() < 2 ? CandleSeries.EMPTY
                    : new CandleSeries(candles, "simulated", Freshness.SIMULATED);
            return new LocalCandleRead(series, CandleCoverage.assess(series.candles(), from, to),
                    "History from the already-running simulated world; no external provider "
                            + "acquisition was attempted.");
        }
        if (observedWorld(worldId)) {
            return localStoredCandles(sym, from, to, dataset, true);
        }
        return emptyLocalRead(from, to,
                "Unknown market lane; no local history was eligible and no provider acquisition was attempted.");
    }

    private LocalCandleRead localStoredCandles(String symbol, LocalDate from, LocalDate to,
                                               String dataset, boolean requireObserved) {
        if (candleStore != null) {
            try {
                Optional<CandleStore.Read> stored = candleStore.candles(symbol, from, to, dataset);
                if (stored.isPresent() && !stored.get().series().candles().isEmpty()
                        && (!requireObserved || fixtureOnlyChain
                            || observedEvidence(stored.get().series().evidence()))) {
                    return new LocalCandleRead(stored.get().series(), stored.get().coverage(),
                            "Persisted " + (requireObserved ? "Observed" : "Scenario")
                                    + " history only; no provider acquisition was attempted.");
                }
            } catch (RuntimeException e) {
                log.debug("local-only candle store read failed for {}: {}", symbol, e.toString());
            }
        }
        return emptyLocalRead(from, to,
                "No eligible persisted history was available; no provider acquisition was attempted.");
    }

    private static LocalCandleRead emptyLocalRead(LocalDate from, LocalDate to, String basis) {
        return new LocalCandleRead(CandleSeries.EMPTY,
                CandleCoverage.assess(List.of(), from, to), basis);
    }

    public Optional<Quote> quote(String symbol) {
        String sym = norm(symbol);
        Optional<Quote> loaded = cached(quoteCache, sym, "quote", () -> {
            Quote q = firstNonEmpty(Domain.QUOTES, p -> p.quote(sym).orElse(null));
            if (q == null && !fixtureOnlyChain && quoteSnapshotStore != null) {
                try { q = quoteSnapshotStore.load(sym).map(MarketDataService::snapshotQuote).orElse(null); }
                catch (RuntimeException e) { log.debug("Last-known quote lookup failed for {}", sym, e); }
            }
            return Optional.ofNullable(q);
        });
        Quote q = loaded.orElse(null);
        return Optional.ofNullable(q).map(this::gateQuote)
                .filter(x -> fixtureOnlyChain || observedEvidence(x.evidence()));
    }

    private static Quote snapshotQuote(io.liftandshift.strikebench.market.MarketDataEngine.MarketSnapshot s) {
        return new Quote(s.symbol(), s.description(), s.last(), s.bid(), s.ask(), s.prevClose(),
                null, null, null, s.optionable(), s.asOfEpochMs(), s.source(), Freshness.STALE);
    }

    public List<LocalDate> expirations(String symbol) {
        String sym = norm(symbol);
        return cached(expirationsCache, sym, "expirations", () -> {
            List<LocalDate> values = firstNonEmptyList(Domain.OPTIONS, p -> p.expirations(sym));
            // The live provider yielded nothing (exhausted, rate-limited, or absent): fall back to
            // the expirations present in the last-known warm capture rather than reporting none.
            if ((values == null || values.isEmpty()) && warmOptions != null) {
                List<LocalDate> warm = warmOptions.latestExpirations(sym);
                if (warm != null && !warm.isEmpty()) values = warm;
            }
            return values == null ? List.of() : List.copyOf(values);
        });
    }

    public Optional<OptionChain> chain(String symbol, LocalDate expiration) {
        String k = norm(symbol) + "|" + expiration;
        Optional<OptionChain> loaded = cached(chainCache, k, "chain", () -> {
            OptionChain live = firstNonEmpty(Domain.OPTIONS, p -> p.chain(norm(symbol), expiration).orElse(null));
            // Same warm fallback as expirations(): a live miss serves the last-known stored chain
            // (labeled EOD via its "stored" source) so a scan reads warm data instead of empty.
            if (live == null && warmOptions != null) {
                live = warmOptions.latestChain(norm(symbol), expiration)
                        .map(io.liftandshift.strikebench.market.ports.WarmOptionStore.Read::chain)
                        .orElse(null);
            }
            return Optional.ofNullable(live);
        });
        return loaded.map(this::gateChain)
                .filter(x -> fixtureOnlyChain || observedEvidence(x.evidence()));
    }

    public List<Candle> candles(String symbol, LocalDate from, LocalDate to) {
        return candleSeries(symbol, from, to).candles();
    }

    /** Context-aware variant: the caller's analysis dataset drives the stored-bar read. */
    public List<Candle> candles(String symbol, LocalDate from, LocalDate to,
                                io.liftandshift.strikebench.db.AnalysisContext actx) {
        return candleSeries(symbol, from, to, actx).candles();
    }

    /** Observed-world candles — background machinery, paper-money paths and recommendations. */
    public CandleSeries candleSeries(String symbol, LocalDate from, LocalDate to) {
        return candleSeries(symbol, from, to, io.liftandshift.strikebench.db.AnalysisContext.OBSERVED);
    }

    /**
     * Candles WITH provenance. Demo history is served only by the explicit Demo overload; the
     * dataset comes from the EXPLICIT context (no ambient request state), so virtual-thread
     * fan-outs keep the caller's world and background work always reads observed.
     */
    public CandleSeries candleSeries(String symbol, LocalDate from, LocalDate to,
                                     io.liftandshift.strikebench.db.AnalysisContext actx) {
        String dataset = actx == null ? io.liftandshift.strikebench.db.DatasetService.OBSERVED : actx.datasetId();
        // The dataset id is part of the cache key: switching datasets must never serve another
        // dataset's cached candles.
        String k = dataset + "|" + norm(symbol) + "|" + from + "|" + to;
        Optional<CachedCandleSeries> r = cached(candlesCache, k, "candles",
                () -> loadCandles(symbol, from, to, dataset));
        return r.map(CachedCandleSeries::series).orElse(CandleSeries.EMPTY);
    }

    private Optional<CachedCandleSeries> loadCandles(String symbol, LocalDate from, LocalDate to,
                                                      String dataset) {
        CandleStore.Read storedFallback = null;
        // Complete persisted bars win. Partial observed history remains a fallback, but first
        // gives an eligible provider the chance to enrich the requested range.
        if (candleStore != null) {
            try {
                Optional<CandleStore.Read> stored = candleStore.candles(norm(symbol), from, to, dataset);
                if (stored.isPresent() && !stored.get().series().candles().isEmpty()
                        && (fixtureOnlyChain || !io.liftandshift.strikebench.db.DatasetService.OBSERVED.equals(dataset)
                            || observedEvidence(stored.get().series().evidence()))) {
                    if (!io.liftandshift.strikebench.db.DatasetService.OBSERVED.equals(dataset)
                            || stored.get().coverage().complete()) {
                        return Optional.of(cachedCandles(stored.get().series(), dataset, from, to));
                    }
                    storedFallback = stored.get();
                }
            } catch (Exception e) { log.debug("candle store read failed for {}: {}", symbol, e.toString()); }
        }
        // Generated datasets are closed worlds. Missing scenario bars stay unavailable;
        // falling through here would splice observed or Demo prices into a scenario lane.
        if (!io.liftandshift.strikebench.db.DatasetService.OBSERVED.equals(dataset)) return Optional.empty();
        CandleSeries fromProviders = candleSeriesFromProviders(symbol, from, to);
        if (!fromProviders.candles().isEmpty() && !fixtureOnlyChain
                && observedEvidence(fromProviders.evidence()) && candleStore != null) {
            try {
                var providerCoverage = CandleCoverage.assess(fromProviders.candles(), from, to);
                int persisted = candleStore.persistObserved(norm(symbol), fromProviders);
                if (persisted > 0) log.debug("Saved {} observed daily bars for local reuse", persisted);
                if (!providerCoverage.complete()) {
                    Optional<CandleStore.Read> refreshed = candleStore.candles(norm(symbol), from, to, dataset);
                    if (refreshed.isPresent()
                            && (refreshed.get().coverage().complete()
                            || refreshed.get().coverage().availableSessions() >= providerCoverage.availableSessions())) {
                        return Optional.of(cachedCandles(refreshed.get().series(), dataset, from, to));
                    }
                }
            } catch (RuntimeException e) {
                // The provider result remains usable for this request. Storage is an additive
                // optimization and must not turn a valid observed read into an outage.
                log.warn("Observed daily history could not be saved for local reuse");
            }
        }
        if (!fromProviders.candles().isEmpty()) {
            return Optional.of(cachedCandles(fromProviders, dataset, from, to));
        }
        return storedFallback == null ? Optional.empty()
                : Optional.of(cachedCandles(storedFallback.series(), dataset, from, to));
    }

    private static CachedCandleSeries cachedCandles(CandleSeries series, String dataset,
                                                     LocalDate from, LocalDate to) {
        boolean partialObserved = io.liftandshift.strikebench.db.DatasetService.OBSERVED.equals(dataset)
                && series != null && !series.candles().isEmpty()
                && !CandleCoverage.assess(series.candles(), from, to).complete();
        return new CachedCandleSeries(series, partialObserved);
    }

    static Duration candleCacheTtl(boolean partialObserved) {
        return partialObserved ? PARTIAL_CANDLE_CACHE_TTL : COMPLETE_CANDLE_CACHE_TTL;
    }

    /**
     * Providers ONLY — never the stored-bars read path. The backfill writer MUST use this: going
     * through {@link #candleSeries} let an incomplete store answer its own backfill request, so a
     * partial history could only ever "backfill" the rows it already had.
     */
    public CandleSeries candleSeriesFromProviders(String symbol, LocalDate from, LocalDate to) {
        for (MarketDataProvider p : providersFor(Domain.CANDLES)) {
            try {
                List<Candle> candles = p.candles(norm(symbol), from, to);
                if (candles != null && !candles.isEmpty()) {
                    Freshness f = "fixture".equals(p.name()) ? Freshness.FIXTURE : Freshness.EOD;
                    CandleSeries series = new CandleSeries(candles, p.name(), f);
                    if (fixtureOnlyChain || observedEvidence(series.evidence())) {
                        recordOk(p.name(), Domain.CANDLES);
                        return series;
                    }
                    recordEmpty(p.name(), Domain.CANDLES);
                    log.warn("Ignoring non-observed candle evidence from provider {} in the observed market", p.name());
                    continue;
                }
                recordEmpty(p.name(), Domain.CANDLES);
            } catch (Exception e) {
                recordError(p.name(), Domain.CANDLES, e,
                        "symbol " + norm(symbol) + " · " + from + " to " + to);
            }
        }
        return CandleSeries.EMPTY;
    }

    /** Configured observed candle-source keys, for the Data Center's explicit acquisition planner. */
    public List<String> candleSourceNames() {
        return providersFor(Domain.CANDLES).stream().map(MarketDataProvider::name).distinct().toList();
    }

    /**
     * One named observed provider only. Acquisition jobs use this instead of silently falling
     * through to a different source whose rights, adjustment basis, and request budget differ.
     */
    public CandleSeries candleSeriesFromProvider(String source, String symbol, LocalDate from, LocalDate to) {
        String wanted = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
        MarketDataProvider provider = providersFor(Domain.CANDLES).stream()
                .filter(p -> p.name().equalsIgnoreCase(wanted)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Candle source '" + wanted + "' is not configured"));
        try {
            List<Candle> candles = provider.candles(norm(symbol), from, to);
            if (candles == null || candles.isEmpty()) {
                recordEmpty(provider.name(), Domain.CANDLES);
                return CandleSeries.EMPTY;
            }
            Freshness freshness = FIXTURE.equals(provider.name()) ? Freshness.FIXTURE : Freshness.EOD;
            CandleSeries series = new CandleSeries(candles, provider.name(), freshness);
            if (!fixtureOnlyChain && !observedEvidence(series.evidence())) {
                recordEmpty(provider.name(), Domain.CANDLES);
                return CandleSeries.EMPTY;
            }
            recordOk(provider.name(), Domain.CANDLES);
            return series;
        } catch (RuntimeException e) {
            recordError(provider.name(), Domain.CANDLES, e,
                    "symbol " + norm(symbol) + " · " + from + " to " + to);
            throw e;
        }
    }

    private static final String FIXTURE = "fixture";

    /**
     * Aggregates every observed news provider so filings and headlines coexist. An explicitly
     * configured Demo build may use its demo source; an Observed provider chain never falls back
     * to fabricated headlines when observed news is unavailable.
     */
    public List<NewsItem> news(String symbol) {
        String sym = norm(symbol);
        return cached(newsCache, sym, "news", () -> {
            if (fixtureOnlyChain) {
                List<NewsItem> demo = new ArrayList<>();
                for (NewsFilingsProvider p : newsProviders) {
                    if (FIXTURE.equals(p.name())) gatherNews(p.name(), () -> p.news(sym), demo);
                }
                return demo.isEmpty() ? List.of() : dedupSortedNews(demo);
            }
            List<NewsItem> real = new ArrayList<>();
            for (NewsFilingsProvider p : newsProviders) {
                if (FIXTURE.equals(p.name())) continue;
                gatherNews(p.name(), () -> p.news(sym), real);
            }
            for (MarketDataProvider p : providersFor(Domain.NEWS)) {
                if (FIXTURE.equals(p.name())) continue;
                gatherNews(p.name(), () -> p.news(sym), real);
            }
            return real.isEmpty() ? List.of() : dedupSortedNews(real);
        });
    }

    /** World-aware news: simulated worlds have none; Demo gets Fixture Wire; Observed gets only real sources. */
    public List<NewsItem> news(String symbol, String worldId) {
        if ("demo".equals(worldId)) return demoNewsProvider == null ? List.of() : demoNewsProvider.news(norm(symbol));
        if (world(worldId).isPresent()) return List.of();
        return observedWorld(worldId) ? news(symbol) : List.of();
    }

    private void gatherNews(String provider, java.util.function.Supplier<List<NewsItem>> call, List<NewsItem> acc) {
        try {
            List<NewsItem> items = call.get();
            if (items != null && !items.isEmpty()) { recordOk(provider, Domain.NEWS); acc.addAll(items); }
            else recordEmpty(provider, Domain.NEWS);
        } catch (Exception e) {
            recordError(provider, Domain.NEWS, e);
        }
    }

    /** Dedup by url (fallback source|headline), newest first. */
    private static List<NewsItem> dedupSortedNews(List<NewsItem> items) {
        Map<String, NewsItem> byKey = new LinkedHashMap<>();
        for (NewsItem n : items) {
            String key = (n.url() != null && !n.url().isBlank()) ? n.url() : (n.source() + "|" + n.headline());
            byKey.putIfAbsent(key, n);
        }
        List<NewsItem> out = new ArrayList<>(byKey.values());
        out.sort(java.util.Comparator.comparingLong(NewsItem::publishedEpochMs).reversed());
        return out;
    }

    /** Annualized risk-free rate for the horizon; falls back to a 4% educational default. */
    public RateQuote riskFreeRateQuote(int days) {
        return riskFreeRateQuote(days, null);
    }

    public RateQuote riskFreeRateQuote(int days, String worldId) {
        if ("demo".equals(worldId) && demoRatesProvider != null) {
            OptionalDouble v = demoRatesProvider.riskFreeRate(days);
            if (v.isPresent()) return new RateQuote(v.getAsDouble(),
                    io.liftandshift.strikebench.model.DataEvidence.of("fixture", Freshness.FIXTURE));
        }
        if (!observedWorld(worldId)) {
            var simulated = world(worldId);
            if (simulated.isPresent()) {
                return new RateQuote(simulated.get().rateAnnual(),
                        io.liftandshift.strikebench.model.DataEvidence.of(simulated.get().rateSource(), Freshness.SIMULATED));
            }
            return new RateQuote(RateQuote.DEFAULT_MODELED_RATE,
                    io.liftandshift.strikebench.model.DataEvidence.missing("unknown simulated market"));
        }
        return cached(rateCache, days, "rate", () -> {
            for (RatesProvider p : ratesProviders) {
                // Fixture rates belong only to the explicit Demo build/lane. In an Observed
                // chain, exhausted Treasury/FRED sources fall through to the MODELED default;
                // disclosure never grants a Demo input permission to enter Observed pricing.
                if (!fixtureOnlyChain && FIXTURE.equalsIgnoreCase(p.name())) continue;
                try {
                    OptionalDouble v = p.riskFreeRate(days);
                    if (v.isPresent()) {
                        recordOk(p.name(), Domain.RATES);
                        return new RateQuote(v.getAsDouble(),
                                io.liftandshift.strikebench.model.DataEvidence.of(p.name(),
                                        FIXTURE.equalsIgnoreCase(p.name()) ? Freshness.FIXTURE : Freshness.EOD));
                    }
                    recordEmpty(p.name(), Domain.RATES);
                } catch (Exception e) {
                    recordError(p.name(), Domain.RATES, e);
                }
            }
            return RateQuote.modeledDefault(RateQuote.DEFAULT_MODELED_RATE);
        });
    }

    public double riskFreeRate(int days) {
        return riskFreeRateQuote(days).annualRate();
    }

    /** Per-domain provider health. NEVER throws; complete even with zero providers or zero calls. */
    public Map<String, List<ProviderStatusInfo>> status() {
        Map<String, List<ProviderStatusInfo>> out = new LinkedHashMap<>();
        for (Domain d : Domain.values()) out.put(d.name(), new ArrayList<>());
        for (ProviderStatusInfo info : statusByKey.values()) {
            out.computeIfAbsent(info.domain(), x -> new ArrayList<>()).add(info);
        }
        out.values().forEach(l -> l.sort(java.util.Comparator.comparing(ProviderStatusInfo::provider)));
        return out;
    }

    // ---- Chain traversal ----

    private List<MarketDataProvider> providersFor(Domain d) {
        return providers.stream()
                .filter(p -> p.domains().contains(d))
                .filter(p -> fixtureOnlyChain || !FIXTURE.equalsIgnoreCase(p.name()))
                .toList();
    }

    private static boolean observedEvidence(io.liftandshift.strikebench.model.DataEvidence e) {
        return e != null && (e.provenance() == io.liftandshift.strikebench.model.DataProvenance.OBSERVED
                || e.provenance() == io.liftandshift.strikebench.model.DataProvenance.BROKER);
    }

    private <T> T firstNonEmpty(Domain domain, Function<MarketDataProvider, T> op) {
        for (MarketDataProvider p : providersFor(domain)) {
            try {
                T result = op.apply(p);
                if (result != null) { recordOk(p.name(), domain); return result; }
                recordEmpty(p.name(), domain);
            } catch (Exception e) {
                recordError(p.name(), domain, e);
            }
        }
        return null;
    }

    private <T> List<T> firstNonEmptyList(Domain domain, Function<MarketDataProvider, List<T>> op) {
        for (MarketDataProvider p : providersFor(domain)) {
            try {
                List<T> result = op.apply(p);
                if (result != null && !result.isEmpty()) { recordOk(p.name(), domain); return result; }
                recordEmpty(p.name(), domain);
            } catch (Exception e) {
                recordError(p.name(), domain, e);
            }
        }
        return null;
    }

    // ---- Freshness gates ----

    private Quote gateQuote(Quote q) {
        if (isLive(q.freshness()) && ageMs(q.asOfEpochMs()) > QUOTE_STALE_MS) {
            return new Quote(q.symbol(), q.description(), q.last(), q.bid(), q.ask(), q.prevClose(),
                    q.dayHigh(), q.dayLow(), q.volume(), q.optionable(), q.asOfEpochMs(), q.source(), Freshness.STALE);
        }
        return q;
    }

    private OptionChain gateChain(OptionChain c) {
        if (isLive(c.freshness()) && ageMs(c.asOfEpochMs()) > CHAIN_STALE_MS) {
            return new OptionChain(c.underlying(), c.expiration(), c.underlyingPrice(), c.calls(), c.puts(),
                    c.asOfEpochMs(), c.source(), Freshness.STALE);
        }
        return c;
    }

    private static boolean isLive(Freshness f) {
        return f == Freshness.REALTIME || f == Freshness.DELAYED;
    }

    private static long ageMs(long asOfEpochMs) {
        return System.currentTimeMillis() - asOfEpochMs;
    }

    // ---- Status bookkeeping ----

    private static String key(String provider, Domain d) { return provider + "|" + d; }

    private void recordOk(String provider, Domain d) {
        statusByKey.merge(key(provider, d),
                new ProviderStatusInfo(provider, d.name(), "OK", null, System.currentTimeMillis(), null),
                (old, fresh) -> new ProviderStatusInfo(provider, d.name(), "OK", null, fresh.lastSuccessEpochMs(), old.lastErrorEpochMs()));
    }

    private void recordEmpty(String provider, Domain d) {
        statusByKey.merge(key(provider, d),
                new ProviderStatusInfo(provider, d.name(), "EMPTY", "no data for last request", null, null),
                (old, fresh) -> "OK".equals(old.state())
                        ? old // an earlier success outranks a later miss for health display
                        : new ProviderStatusInfo(provider, d.name(), "EMPTY", fresh.detail(), old.lastSuccessEpochMs(), old.lastErrorEpochMs()));
    }

    private void recordError(String provider, Domain d, Exception e) {
        recordError(provider, d, e, null);
    }

    private void recordError(String provider, Domain d, Exception e, String requestContext) {
        String detail = publicProviderFailure(e);
        String diagnostic = e instanceof io.liftandshift.strikebench.market.providers.Http.ProviderHttpException
                ? e.getMessage()
                : detail;
        if (requestContext == null || requestContext.isBlank()) {
            log.warn("Market source {} could not serve {}: {}", provider, d, diagnostic);
        } else {
            log.warn("Market source {} could not serve {} for {}: {}",
                    provider, d, requestContext, diagnostic);
        }
        log.debug("Market source failure detail for " + provider + " " + d, e);
        statusByKey.merge(key(provider, d),
                new ProviderStatusInfo(provider, d.name(), "ERROR", detail, null, System.currentTimeMillis()),
                (old, fresh) -> new ProviderStatusInfo(provider, d.name(), "ERROR", fresh.detail(), old.lastSuccessEpochMs(), fresh.lastErrorEpochMs()));
    }

    static String publicProviderFailure(Exception e) {
        if (e instanceof io.liftandshift.strikebench.market.providers.Http.ProviderHttpException http) {
            return http.statusCode() > 0 ? "source returned HTTP " + http.statusCode() : "source connection failed";
        }
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("timeout") || message.contains("timed out")) return "source timed out";
        if (Thread.currentThread().isInterrupted() || message.contains("interrupted")) return "source request was interrupted";
        return "source request failed";
    }

    private static String norm(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }
}
