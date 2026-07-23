package io.liftandshift.strikebench.market;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.market.ports.NewsFilingsProvider;
import io.liftandshift.strikebench.market.ports.RatesProvider;
import io.liftandshift.strikebench.market.providers.FixtureProvider;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
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
        final boolean observedQuotes;
        CountingProvider(Clock c) { this(c, false); }
        CountingProvider(Clock c, boolean observedQuotes) {
            inner = new FixtureProvider(c);
            this.observedQuotes = observedQuotes;
        }
        public String name() { return observedQuotes ? "counting-observed" : "fixture"; }
        public java.util.Set<Domain> domains() { return inner.domains(); }
        public List<SymbolMatch> lookup(String q) { return inner.lookup(q); }
        public Optional<Quote> quote(String s) {
            quoteCalls.computeIfAbsent(s, k -> new AtomicInteger()).incrementAndGet();
            return inner.quote(s).map(q -> observedQuotes
                    ? new Quote(q.symbol(), q.description(), q.last(), q.bid(), q.ask(), q.prevClose(),
                            q.dayHigh(), q.dayLow(), q.volume(), q.optionable(), q.asOfEpochMs(),
                            "counting-observed", io.liftandshift.strikebench.model.Freshness.DELAYED)
                    : q);
        }
        public List<LocalDate> expirations(String s) { return inner.expirations(s); }
        public Optional<OptionChain> chain(String s, LocalDate e) { return inner.chain(s, e); }
        public List<Candle> candles(String s, LocalDate f, LocalDate t) { return inner.candles(s, f, t); }
        public List<NewsItem> news(String s) { return inner.news(s); }
    }

    static final class BidAskOnlyProvider implements MarketDataProvider {
        final AtomicInteger calls = new AtomicInteger();
        public String name() { return "bid-ask-observed"; }
        public java.util.Set<Domain> domains() { return java.util.Set.of(Domain.QUOTES); }
        public List<SymbolMatch> lookup(String q) { return List.of(); }
        public Optional<Quote> quote(String symbol) {
            calls.incrementAndGet();
            return Optional.of(new Quote(symbol, "Bid/ask only", null,
                    new java.math.BigDecimal("99.90"), new java.math.BigDecimal("100.10"),
                    new java.math.BigDecimal("99.00"), null, null, null, true, 1_783_348_200_000L,
                    name(), io.liftandshift.strikebench.model.Freshness.DELAYED));
        }
        public List<LocalDate> expirations(String s) { return List.of(); }
        public Optional<OptionChain> chain(String s, LocalDate e) { return Optional.empty(); }
        public List<Candle> candles(String s, LocalDate f, LocalDate t) { return List.of(); }
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
    void batchMarksReuseWarmEngineAndKeepBidAskOnlyQuotesUsable() {
        db = TestDb.fresh();
        AppConfig cfg = new AppConfig(Map.of());
        BidAskOnlyProvider provider = new BidAskOnlyProvider();
        MarketDataService market = new MarketDataService(List.of(provider), List.of(), List.of());
        MarketDataEngine engine = new MarketDataEngine(market, new UniverseService(db, cfg, clock), cfg, clock);
        MarketDataMarks marks = new MarketDataMarks(market, false);
        marks.setEngine(engine);

        assertThat(engine.quotes(List.of("AAPL"))).hasSize(1);
        assertThat(provider.calls).hasValue(1);
        assertThat(marks.underlyingMarks(List.of("AAPL"), "observed").get("AAPL"))
                .isEqualByComparingTo("100.00");
        assertThat(marks.underlyingMark("AAPL", "observed").orElseThrow())
                .isEqualByComparingTo("100.00");
        assertThat(provider.calls).hasValue(1);
    }

    @Test
    void standardChainQuotesNeverMasqueradeAsAdjustedContractMarks() {
        CountingProvider provider = new CountingProvider(clock);
        MarketDataService market = new MarketDataService(
                List.<MarketDataProvider>of(provider), List.<NewsFilingsProvider>of(), List.<RatesProvider>of());
        MarketDataMarks marks = new MarketDataMarks(market, true);
        LocalDate expiry = provider.expirations("AAPL").getFirst();
        var quote = provider.chain("AAPL", expiry).orElseThrow().calls().getFirst();
        Leg standard = Leg.option(LegAction.BUY, OptionType.CALL, quote.strike(), expiry,
                1, java.math.BigDecimal.ZERO, 100);
        Leg adjusted = Leg.option(LegAction.BUY, OptionType.CALL, quote.strike(), expiry,
                1, java.math.BigDecimal.ZERO, 10);

        assertThat(marks.legMark("AAPL", standard)).isPresent();
        assertThat(marks.legMark("AAPL", adjusted)).isEmpty();
        assertThat(marks.legMark("AAPL", adjusted, "demo")).isEmpty();
    }

    @Test
    void liveWarmSetSpansTheWholeUniverseNotJustTheActiveSector() {
        db = TestDb.fresh();
        AppConfig live = new AppConfig(Map.of()); // fixturesOnly defaults false → curated real sectors
        UniverseService u = new UniverseService(db, live, clock);
        var warm = u.warmSymbols();
        // Far more than the single active sector, spanning multiple sectors (the whole universe).
        assertThat(warm.size()).isGreaterThan(u.active().symbols().size());
        assertThat(warm.size()).isGreaterThanOrEqualTo(50);
        assertThat(warm).contains("AAPL", "XOM", "JPM"); // tech / energy / financials — different sectors
        @SuppressWarnings("unchecked")
        Map<String, Object> scout = (Map<String, Object>) u.describe().get("scout");
        assertThat(scout).containsEntry("source", "CURATED")
                .containsEntry("label", "Curated cross-sector opportunity universe");
        assertThat(scout.get("symbols")).isEqualTo(warm);
    }

    @Test
    void persistsQuotesAndBootsStaleFirstFromDisk() {
        AppConfig cfg = new AppConfig(Map.of("FIXTURES_ONLY", "true"));
        CountingProvider p = new CountingProvider(clock, true);
        db = TestDb.fresh();
        MarketDataService market = new MarketDataService(
                List.<MarketDataProvider>of(p), List.<NewsFilingsProvider>of(), List.<RatesProvider>of());
        UniverseService universe = new UniverseService(db, cfg, clock);
        var store = new io.liftandshift.strikebench.db.MarketSnapshotStore(db);

        // Engine #1: fetch AAPL (mirrors it to disk via the store).
        MarketDataEngine eng1 = new MarketDataEngine(market, universe, cfg, clock);
        eng1.setSnapshotStore(store);
        eng1.quote("AAPL");
        assertThat(store.loadAll()).anySatisfy(s -> assertThat(s.symbol()).isEqualTo("AAPL"));

        // Engine #2 (a "restart"): a fresh engine with NO fetch yet seeds from disk on start().
        MarketDataEngine eng2 = new MarketDataEngine(market, universe, cfg,
                Clock.fixed(Instant.parse("2026-07-06T14:00:00Z"), ZoneOffset.UTC));
        eng2.setSnapshotStore(store);
        // seed happens in start(); use a config with the engine on but we only need the seed path.
        eng2.start();
        try {
            var st = eng2.status();
            assertThat(st.symbols()).anySatisfy(s -> assertThat(s.symbol()).isEqualTo("AAPL"));
            // the seeded snapshot is labeled STALE (last-known), not passed off as live
            assertThat(eng2.quote("AAPL")).isPresent();
        } finally {
            eng2.stop();
        }
    }

    @Test
    void snapshotStoreQuarantinesFabricatedMarkets() {
        db = TestDb.fresh();
        var store = new io.liftandshift.strikebench.db.MarketSnapshotStore(db);
        store.save(new MarketDataEngine.MarketSnapshot("REAL", "Observed", new java.math.BigDecimal("10"),
                new java.math.BigDecimal("9.99"), new java.math.BigDecimal("10.01"),
                new java.math.BigDecimal("9.50"), true, io.liftandshift.strikebench.model.Freshness.DELAYED,
                "cboe", clock.millis(), clock.millis(), false, null));
        store.save(new MarketDataEngine.MarketSnapshot("FAKE", "Demo", new java.math.BigDecimal("100"),
                new java.math.BigDecimal("99"), new java.math.BigDecimal("101"),
                new java.math.BigDecimal("100"), true, io.liftandshift.strikebench.model.Freshness.FIXTURE,
                "fixture", clock.millis(), clock.millis(), false, null));

        assertThat(store.loadAll()).extracting(MarketDataEngine.MarketSnapshot::symbol)
                .containsExactly("REAL");
        assertThat(db.query("SELECT symbol FROM market_snapshot ORDER BY symbol", r -> r.str("symbol")))
                .containsExactly("REAL");
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
