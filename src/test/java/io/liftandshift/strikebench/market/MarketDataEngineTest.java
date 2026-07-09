package io.liftandshift.strikebench.market;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.market.ports.NewsFilingsProvider;
import io.liftandshift.strikebench.market.ports.RatesProvider;
import io.liftandshift.strikebench.market.providers.FixtureProvider;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.model.SymbolMatch;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.NewsItem;
import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The in-memory market-data engine: warm serving, singleflight, stale-while-refresh, status. */
class MarketDataEngineTest {

    private Db db;
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-06T14:00:00Z"), ZoneOffset.UTC); // Mon, RTH

    @AfterEach void closeDb() { if (db != null) db.close(); }

    /** Counts how many times each symbol was actually fetched from the underlying provider. */
    static final class CountingProvider implements MarketDataProvider {
        final FixtureProvider inner;
        final Map<String, AtomicInteger> quoteCalls = new ConcurrentHashMap<>();
        CountingProvider(Clock c) { inner = new FixtureProvider(c); }
        public String name() { return "counting"; }
        public java.util.Set<Domain> domains() { return inner.domains(); }
        public List<SymbolMatch> lookup(String q) { return inner.lookup(q); }
        public Optional<Quote> quote(String s) {
            quoteCalls.computeIfAbsent(s, k -> new AtomicInteger()).incrementAndGet();
            return inner.quote(s);
        }
        public List<LocalDate> expirations(String s) { return inner.expirations(s); }
        public Optional<OptionChain> chain(String s, LocalDate e) { return inner.chain(s, e); }
        public List<Candle> candles(String s, LocalDate f, LocalDate t) { return inner.candles(s, f, t); }
        public List<NewsItem> news(String s) { return inner.news(s); }
    }

    private MarketDataEngine engine(CountingProvider p, AppConfig cfg) {
        db = TestDb.fresh();
        MarketDataService market = new MarketDataService(
                List.<MarketDataProvider>of(p), List.<NewsFilingsProvider>of(), List.<RatesProvider>of());
        UniverseService universe = new UniverseService(db, cfg, clock);
        return new MarketDataEngine(market, universe, cfg, clock);
    }

    @Test
    void quotesServesTheBatchAndFillsColdSymbolsOnce() {
        AppConfig cfg = new AppConfig(Map.of("FIXTURES_ONLY", "true"));
        CountingProvider p = new CountingProvider(clock);
        MarketDataEngine eng = engine(p, cfg);

        var rows = eng.quotes(List.of("AAPL", "SPY", "QQQ"));
        assertThat(rows).extracting(MarketDataEngine.MarketSnapshot::symbol)
                .contains("AAPL", "SPY", "QQQ");
        assertThat(rows).allSatisfy(r -> assertThat(r.last()).isNotNull());
        // Each cold symbol fetched exactly once — the batch is not a per-symbol sequential re-download.
        assertThat(p.quoteCalls.get("AAPL").get()).isEqualTo(1);
    }

    @Test
    void repeatedSymbolInOneBatchCollapsesToASingleFetch() {
        AppConfig cfg = new AppConfig(Map.of("FIXTURES_ONLY", "true"));
        CountingProvider p = new CountingProvider(clock);
        MarketDataEngine eng = engine(p, cfg);

        var rows = eng.quotes(List.of("AAPL", "AAPL", "AAPL"));
        assertThat(rows).extracting(MarketDataEngine.MarketSnapshot::symbol).containsOnly("AAPL");
        // Singleflight: three requests for AAPL in one batch → one provider fetch.
        assertThat(p.quoteCalls.get("AAPL").get()).isEqualTo(1);
    }

    @Test
    void warmSymbolIsServedFromMemoryWithoutRefetchingWhenFresh() {
        AppConfig cfg = new AppConfig(Map.of("FIXTURES_ONLY", "true"));
        CountingProvider p = new CountingProvider(clock);
        MarketDataEngine eng = engine(p, cfg);

        eng.quote("AAPL");            // cold: one fetch
        eng.quote("AAPL");            // warm + fresh (fixed clock → not stale): no refetch
        eng.quote("AAPL");
        assertThat(p.quoteCalls.get("AAPL").get()).isEqualTo(1);
    }

    @Test
    void statusReportsWarmedTrackedAndInterval() {
        AppConfig cfg = new AppConfig(Map.of("FIXTURES_ONLY", "true"));
        CountingProvider p = new CountingProvider(clock);
        MarketDataEngine eng = engine(p, cfg);

        eng.quotes(List.of("AAPL", "SPY"));
        var st = eng.status();
        assertThat(st.tracked()).isGreaterThanOrEqualTo(2);
        assertThat(st.warmed()).isGreaterThanOrEqualTo(2);
        assertThat(st.marketOpen()).isTrue(); // fixed clock is a weekday during RTH
        assertThat(st.refreshInterval()).isEqualTo(cfg.engineQuoteRefreshSeconds());
        assertThat(st.symbols()).anySatisfy(s -> assertThat(s.symbol()).isEqualTo("AAPL"));
    }
}
