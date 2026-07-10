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

    private final Cache<String, Quote> quoteCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(15)).maximumSize(500).build();
    private final Cache<String, OptionChain> chainCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(60)).maximumSize(200).build();
    private final Cache<String, List<LocalDate>> expirationsCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(60)).maximumSize(200).build();
    private final Cache<String, CandleSeries> candlesCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(1)).maximumSize(100).build();
    private final Cache<String, List<NewsItem>> newsCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(5)).maximumSize(200).build();
    private final Cache<Integer, Double> rateCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(1)).maximumSize(20).build();

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

    public Optional<Quote> quote(String symbol) {
        String sym = norm(symbol);
        Quote q = quoteCache.get(sym, s ->
                firstNonEmpty(Domain.QUOTES, p -> p.quote(s).orElse(null)));
        return Optional.ofNullable(q).map(this::gateQuote);
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
        return Optional.ofNullable(c).map(this::gateChain);
    }

    public List<Candle> candles(String symbol, LocalDate from, LocalDate to) {
        return candleSeries(symbol, from, to).candles();
    }

    /** Candles WITH provenance — consumers must label demo (fixture) history in live mode. */
    public CandleSeries candleSeries(String symbol, LocalDate from, LocalDate to) {
        String k = norm(symbol) + "|" + from + "|" + to;
        CandleSeries r = candlesCache.get(k, key -> {
            // Persisted bars (Data Center backfills / snapshots / CSV ingest) win over live provider
            // calls — the whole point of storing history is that the read path uses it.
            if (candleStore != null) {
                try {
                    Optional<CandleSeries> stored = candleStore.candles(norm(symbol), from, to);
                    if (stored.isPresent() && !stored.get().candles().isEmpty()) return stored.get();
                } catch (Exception e) { log.debug("candle store read failed for {}: {}", symbol, e.toString()); }
            }
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
            return null;
        });
        return r == null ? CandleSeries.EMPTY : r;
    }

    private static final String FIXTURE = "fixture";

    /**
     * AGGREGATES every real news provider so filings (EDGAR) and headlines (RSS) COEXIST — the old
     * winner-take-all returned the first non-empty provider, which let EDGAR filings suppress every
     * headline in live mode. The fixture stays a strict last-resort fallback, so its demo headlines
     * never mix into real data (the "fixture masquerading as real" rule).
     */
    public List<NewsItem> news(String symbol) {
        String sym = norm(symbol);
        List<NewsItem> r = newsCache.get(sym, s -> {
            List<NewsItem> real = new ArrayList<>();
            for (NewsFilingsProvider p : newsProviders) {
                if (FIXTURE.equals(p.name())) continue;
                gatherNews(p.name(), () -> p.news(s), real);
            }
            for (MarketDataProvider p : providersFor(Domain.NEWS)) {
                if (FIXTURE.equals(p.name())) continue;
                gatherNews(p.name(), () -> p.news(s), real);
            }
            if (!real.isEmpty()) return dedupSortedNews(real);

            // No real news anywhere — fall back to the fixture (demo) provider.
            for (NewsFilingsProvider p : newsProviders) {
                if (!FIXTURE.equals(p.name())) continue;
                List<NewsItem> demo = new ArrayList<>();
                gatherNews(p.name(), () -> p.news(s), demo);
                if (!demo.isEmpty()) return dedupSortedNews(demo);
            }
            return null;
        });
        return r == null ? List.of() : r;
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
    public double riskFreeRate(int days) {
        Double r = rateCache.get(days, d -> {
            for (RatesProvider p : ratesProviders) {
                try {
                    OptionalDouble v = p.riskFreeRate(d);
                    if (v.isPresent()) { recordOk(p.name(), Domain.RATES); return v.getAsDouble(); }
                    recordEmpty(p.name(), Domain.RATES);
                } catch (Exception e) {
                    recordError(p.name(), Domain.RATES, e);
                }
            }
            return null;
        });
        return r == null ? 0.04 : r;
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
        return providers.stream().filter(p -> p.domains().contains(d)).toList();
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
        log.warn("provider {} failed for {}: {}", provider, d, e.toString());
        statusByKey.merge(key(provider, d),
                new ProviderStatusInfo(provider, d.name(), "ERROR", e.getClass().getSimpleName() + ": " + e.getMessage(), null, System.currentTimeMillis()),
                (old, fresh) -> new ProviderStatusInfo(provider, d.name(), "ERROR", fresh.detail(), old.lastSuccessEpochMs(), fresh.lastErrorEpochMs()));
    }

    private static String norm(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }
}
