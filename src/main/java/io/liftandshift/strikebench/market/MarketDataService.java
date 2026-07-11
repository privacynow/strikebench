package io.liftandshift.strikebench.market;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.market.ports.NewsFilingsProvider;
import io.liftandshift.strikebench.market.ports.RatesProvider;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

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
    private final boolean fixtureOnlyChain;
    private volatile MarketDataProvider demoProvider;
    private volatile NewsFilingsProvider demoNewsProvider;
    private volatile RatesProvider demoRatesProvider;

    private final Cache<String, Quote> quoteCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(15)).maximumSize(500).build();
    private final Cache<String, OptionChain> chainCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(60)).maximumSize(200).build();
    private final Cache<String, List<LocalDate>> expirationsCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(60)).maximumSize(200).build();
    private final Cache<String, CandleSeries> candlesCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(1)).maximumSize(100).build();
    private final Cache<String, List<NewsItem>> newsCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(5)).maximumSize(200).build();
    private final Cache<Integer, RateQuote> rateCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(1)).maximumSize(20).build();

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

    public MarketLane lane(String worldId) { return MarketLane.of(worldId, fixtureOnlyChain); }

    /** The complete analysis lane: execution market plus the caller's selected dataset. */
    public MarketLane lane(String worldId, io.liftandshift.strikebench.db.AnalysisContext context) {
        return MarketLane.of(worldId, fixtureOnlyChain, context);
    }

    public void invalidateAll() {
        quoteCache.invalidateAll();
        chainCache.invalidateAll();
        expirationsCache.invalidateAll();
        candlesCache.invalidateAll();
        newsCache.invalidateAll();
        rateCache.invalidateAll();
    }

    /** Durable bar imports/backfills changed history without changing the active dataset id. */
    public void invalidateHistoricalData() {
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

    public Optional<Quote> quote(String symbol) {
        String sym = norm(symbol);
        Quote q = quoteCache.get(sym, s ->
                firstNonEmpty(Domain.QUOTES, p -> p.quote(s).orElse(null)));
        return Optional.ofNullable(q).map(this::gateQuote)
                .filter(x -> fixtureOnlyChain || observedEvidence(x.evidence()));
    }

    public List<LocalDate> expirations(String symbol) {
        String sym = norm(symbol);
        List<LocalDate> r = expirationsCache.get(sym, s ->
                firstNonEmptyList(Domain.OPTIONS, p -> p.expirations(s)));
        return r == null ? List.of() : r;
    }

    public Optional<OptionChain> chain(String symbol, LocalDate expiration) {
        String k = norm(symbol) + "|" + expiration;
        OptionChain c = chainCache.get(k, key ->
                firstNonEmpty(Domain.OPTIONS, p -> p.chain(norm(symbol), expiration).orElse(null)));
        return Optional.ofNullable(c).map(this::gateChain)
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
        CandleSeries r = candlesCache.get(k, key -> {
            // Persisted bars (Data Center backfills / snapshots / CSV ingest) win over live provider
            // calls — the whole point of storing history is that the read path uses it.
            if (candleStore != null) {
                try {
                    Optional<CandleSeries> stored = candleStore.candles(norm(symbol), from, to, dataset);
                    if (stored.isPresent() && !stored.get().candles().isEmpty()) return stored.get();
                } catch (Exception e) { log.debug("candle store read failed for {}: {}", symbol, e.toString()); }
            }
            CandleSeries fromProviders = candleSeriesFromProviders(symbol, from, to);
            return fromProviders.candles().isEmpty() ? null : fromProviders;
        });
        return r == null ? CandleSeries.EMPTY : r;
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
                    recordOk(p.name(), Domain.CANDLES);
                    Freshness f = "fixture".equals(p.name()) ? Freshness.FIXTURE : Freshness.EOD;
                    return new CandleSeries(candles, p.name(), f);
                }
                recordEmpty(p.name(), Domain.CANDLES);
            } catch (Exception e) {
                recordError(p.name(), Domain.CANDLES, e);
            }
        }
        return CandleSeries.EMPTY;
    }

    private static final String FIXTURE = "fixture";

    /**
     * Aggregates every observed news provider so filings and headlines coexist. An explicitly
     * configured Demo build may use its demo source; an Observed provider chain never falls back
     * to fabricated headlines when observed news is unavailable.
     */
    public List<NewsItem> news(String symbol) {
        String sym = norm(symbol);
        List<NewsItem> r = newsCache.get(sym, s -> {
            if (fixtureOnlyChain) {
                List<NewsItem> demo = new ArrayList<>();
                for (NewsFilingsProvider p : newsProviders) {
                    if (FIXTURE.equals(p.name())) gatherNews(p.name(), () -> p.news(s), demo);
                }
                return demo.isEmpty() ? null : dedupSortedNews(demo);
            }
            List<NewsItem> real = new ArrayList<>();
            for (NewsFilingsProvider p : newsProviders) {
                if (FIXTURE.equals(p.name())) continue;
                gatherNews(p.name(), () -> p.news(s), real);
            }
            for (MarketDataProvider p : providersFor(Domain.NEWS)) {
                if (FIXTURE.equals(p.name())) continue;
                gatherNews(p.name(), () -> p.news(s), real);
            }
            return real.isEmpty() ? null : dedupSortedNews(real);
        });
        return r == null ? List.of() : r;
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
            if (world(worldId).isPresent()) {
                return new RateQuote(0.04,
                        io.liftandshift.strikebench.model.DataEvidence.of("simulated rate assumption", Freshness.SIMULATED));
            }
            return new RateQuote(0.04,
                    io.liftandshift.strikebench.model.DataEvidence.missing("unknown simulated market"));
        }
        RateQuote r = rateCache.get(days, d -> {
            for (RatesProvider p : ratesProviders) {
                // Fixture rates belong only to the explicit Demo build/lane. In an Observed
                // chain, exhausted Treasury/FRED sources fall through to the MODELED default;
                // disclosure never grants a Demo input permission to enter Observed pricing.
                if (!fixtureOnlyChain && FIXTURE.equalsIgnoreCase(p.name())) continue;
                try {
                    OptionalDouble v = p.riskFreeRate(d);
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
            return null;
        });
        return r == null ? RateQuote.modeledDefault(0.04) : r;
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
        String detail = publicProviderFailure(e);
        log.warn("Market source {} could not serve {}: {}", provider, d, detail);
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
