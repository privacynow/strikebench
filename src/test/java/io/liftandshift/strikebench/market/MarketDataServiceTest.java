package io.liftandshift.strikebench.market;

import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.market.providers.FixtureProvider;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.NewsItem;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.model.SymbolMatch;
import io.liftandshift.strikebench.support.ObservedFixtureProvider;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDataServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T15:30:00Z"), ZoneId.of("America/New_York"));

    @Test
    void partialObservedHistoryRechecksSoonerThanCompleteHistory() {
        assertThat(MarketDataService.candleCacheTtl(true)).isEqualTo(java.time.Duration.ofMinutes(5));
        assertThat(MarketDataService.candleCacheTtl(false)).isEqualTo(java.time.Duration.ofHours(1));
    }

    /** Wraps the fixture and counts calls, to observe caching and chain traversal. */
    static final class CountingProvider implements MarketDataProvider {
        final ObservedFixtureProvider inner = new ObservedFixtureProvider(CLOCK);
        final AtomicInteger quoteCalls = new AtomicInteger();
        @Override public String name() { return "counting"; }
        @Override public Set<Domain> domains() { return Set.of(Domain.QUOTES, Domain.OPTIONS, Domain.CANDLES); }
        @Override public List<SymbolMatch> lookup(String q) { return inner.lookup(q); }
        @Override public Optional<Quote> quote(String s) { quoteCalls.incrementAndGet(); return inner.quote(s); }
        @Override public List<LocalDate> expirations(String s) { return inner.expirations(s); }
        @Override public Optional<OptionChain> chain(String s, LocalDate e) { return inner.chain(s, e); }
        @Override public List<Candle> candles(String s, LocalDate f, LocalDate t) { return inner.candles(s, f, t); }
    }

    /** Always fails, to verify fall-through and ERROR status. */
    static final class BrokenProvider implements MarketDataProvider {
        @Override public String name() { return "broken"; }
        @Override public Set<Domain> domains() { return Set.of(Domain.QUOTES, Domain.OPTIONS, Domain.CANDLES); }
        @Override public List<SymbolMatch> lookup(String q) { throw new RuntimeException("boom"); }
        @Override public Optional<Quote> quote(String s) { throw new RuntimeException("boom"); }
        @Override public List<LocalDate> expirations(String s) { throw new RuntimeException("boom"); }
        @Override public Optional<OptionChain> chain(String s, LocalDate e) { throw new RuntimeException("boom"); }
        @Override public List<Candle> candles(String s, LocalDate f, LocalDate t) { throw new RuntimeException("boom"); }
    }

    /** Empty observed source with counters: empty is a real cacheable result, not an exception. */
    static final class EmptyCountingProvider implements MarketDataProvider {
        final AtomicInteger quoteCalls = new AtomicInteger();
        final AtomicInteger chainCalls = new AtomicInteger();
        @Override public String name() { return "empty-observed"; }
        @Override public Set<Domain> domains() { return Set.of(Domain.QUOTES, Domain.OPTIONS); }
        @Override public List<SymbolMatch> lookup(String q) { return List.of(); }
        @Override public Optional<Quote> quote(String s) {
            quoteCalls.incrementAndGet();
            try { Thread.sleep(75); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return Optional.empty();
        }
        @Override public List<LocalDate> expirations(String s) { return List.of(); }
        @Override public Optional<OptionChain> chain(String s, LocalDate e) {
            chainCalls.incrementAndGet();
            return Optional.empty();
        }
        @Override public List<Candle> candles(String s, LocalDate f, LocalDate t) { return List.of(); }
    }

    static final class GenerationProvider implements MarketDataProvider {
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch firstEntered = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        @Override public String name() { return "generation-observed"; }
        @Override public Set<Domain> domains() { return Set.of(Domain.QUOTES); }
        @Override public List<SymbolMatch> lookup(String q) { return List.of(); }
        @Override public Optional<Quote> quote(String symbol) {
            int call = calls.incrementAndGet();
            if (call == 1) {
                firstEntered.countDown();
                try { releaseFirst.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            String price = call == 1 ? "100.00" : "200.00";
            return Optional.of(new Quote(symbol, "Generation " + call, new java.math.BigDecimal(price),
                    null, null, null, null, null, null, true, CLOCK.millis(), name(),
                    io.liftandshift.strikebench.model.Freshness.DELAYED));
        }
        @Override public List<LocalDate> expirations(String s) { return List.of(); }
        @Override public Optional<OptionChain> chain(String s, LocalDate e) { return Optional.empty(); }
        @Override public List<Candle> candles(String s, LocalDate f, LocalDate t) { return List.of(); }
    }

    /** A provider accidentally mounted in the observed chain but explicitly returning modeled evidence. */
    static final class SyntheticCandleProvider implements MarketDataProvider {
        private final ObservedFixtureProvider inner = new ObservedFixtureProvider(CLOCK);
        @Override public String name() { return "synthetic"; }
        @Override public Set<Domain> domains() { return Set.of(Domain.CANDLES); }
        @Override public List<SymbolMatch> lookup(String q) { return List.of(); }
        @Override public Optional<Quote> quote(String s) { return Optional.empty(); }
        @Override public List<LocalDate> expirations(String s) { return List.of(); }
        @Override public Optional<OptionChain> chain(String s, LocalDate e) { return Optional.empty(); }
        @Override public List<Candle> candles(String s, LocalDate f, LocalDate t) { return inner.candles(s, f, t); }
    }

    @Test
    void quotesAreCachedWithinTtl() {
        CountingProvider p = new CountingProvider();
        MarketDataService svc = new MarketDataService(List.of(p), List.of(), List.of());
        assertThat(svc.quote("AAPL")).isPresent();
        assertThat(svc.quote("AAPL")).isPresent();
        assertThat(svc.quote("aapl")).isPresent(); // normalized to same key
        assertThat(p.quoteCalls.get()).isEqualTo(1);
        assertThat(svc.quote("SPY")).isPresent();
        assertThat(p.quoteCalls.get()).isEqualTo(2);
    }

    @Test
    void concurrentEmptyReadsSingleflightAndNegativeCacheUntilInvalidated() throws Exception {
        EmptyCountingProvider p = new EmptyCountingProvider();
        MarketDataService svc = new MarketDataService(List.of(p), List.of(), List.of());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Optional<Quote>>> calls = java.util.stream.IntStream.range(0, 12)
                    .mapToObj(i -> executor.submit(() -> {
                        start.await(2, TimeUnit.SECONDS);
                        return svc.quote("MISSING");
                    })).toList();
            start.countDown();
            for (Future<Optional<Quote>> call : calls) assertThat(call.get(2, TimeUnit.SECONDS)).isEmpty();
        }
        assertThat(p.quoteCalls).hasValue(1);
        assertThat(svc.quote("MISSING")).isEmpty();
        assertThat(p.quoteCalls).hasValue(1);

        LocalDate expiry = LocalDate.of(2026, 8, 21);
        assertThat(svc.chain("MISSING", expiry)).isEmpty();
        assertThat(svc.chain("MISSING", expiry)).isEmpty();
        assertThat(p.chainCalls).hasValue(1);

        svc.invalidateAll();
        assertThat(svc.quote("MISSING")).isEmpty();
        assertThat(svc.chain("MISSING", expiry)).isEmpty();
        assertThat(p.quoteCalls).hasValue(2);
        assertThat(p.chainCalls).hasValue(2);
    }

    @Test
    void invalidationStartsANewFlightAndLateOldLoadCannotRepopulateCache() throws Exception {
        GenerationProvider provider = new GenerationProvider();
        MarketDataService svc = new MarketDataService(List.of(provider), List.of(), List.of());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Optional<Quote>> oldFlight = executor.submit(() -> svc.quote("AAPL"));
            assertThat(provider.firstEntered.await(1, TimeUnit.SECONDS)).isTrue();

            svc.invalidateAll();
            Future<Optional<Quote>> newFlight = executor.submit(() -> svc.quote("AAPL"));
            assertThat(newFlight.get(1, TimeUnit.SECONDS).orElseThrow().last()).isEqualByComparingTo("200.00");
            provider.releaseFirst.countDown();
            assertThat(oldFlight.get(1, TimeUnit.SECONDS).orElseThrow().last()).isEqualByComparingTo("100.00");
        }

        assertThat(provider.calls).hasValue(2);
        assertThat(svc.quote("AAPL").orElseThrow().last()).isEqualByComparingTo("200.00");
        assertThat(provider.calls).hasValue(2);
    }

    @Test
    void observedFailureStaysUnavailableWhileExplicitDemoStillWorks() {
        FixtureProvider fixture = new FixtureProvider(CLOCK);
        MarketDataService svc = new MarketDataService(List.of(new BrokenProvider()), List.of(), List.of());
        svc.setDemoSources(fixture, fixture, fixture);

        assertThat(svc.quote("AAPL")).isEmpty();
        Quote q = svc.quote("AAPL", "demo").orElseThrow();
        assertThat(q.source()).isEqualTo("fixture");

        Map<String, List<ProviderStatusInfo>> status = svc.status();
        List<ProviderStatusInfo> quotes = status.get("QUOTES");
        assertThat(quotes).extracting(ProviderStatusInfo::provider).containsExactly("broken");
        assertThat(quotes.stream().filter(s -> s.provider().equals("broken")).findFirst().orElseThrow().state()).isEqualTo("ERROR");
    }

    @Test
    void observedProviderGapUsesTheEnginesDurableLastKnownQuoteWithoutChangingItsLane() {
        MarketDataService svc = new MarketDataService(List.of(new BrokenProvider()), List.of(), List.of());
        var snapshot = new MarketDataEngine.MarketSnapshot("AAPL", "Apple", new java.math.BigDecimal("316.48"),
                new java.math.BigDecimal("316.40"), new java.math.BigDecimal("316.50"),
                new java.math.BigDecimal("315.32"), true, io.liftandshift.strikebench.model.Freshness.DELAYED,
                "cboe", CLOCK.millis() - 60_000, CLOCK.millis() - 60_000, false, null);
        svc.setQuoteSnapshotStore(new io.liftandshift.strikebench.market.ports.SnapshotStore() {
            @Override public void save(MarketDataEngine.MarketSnapshot ignored) {}
            @Override public List<MarketDataEngine.MarketSnapshot> loadAll() { return List.of(snapshot); }
            @Override public Optional<MarketDataEngine.MarketSnapshot> load(String symbol) {
                return "AAPL".equals(symbol) ? Optional.of(snapshot) : Optional.empty();
            }
        });

        Quote q = svc.quote("AAPL").orElseThrow();
        assertThat(q.last()).isEqualByComparingTo("316.48");
        assertThat(q.freshness()).isEqualTo(io.liftandshift.strikebench.model.Freshness.STALE);
        assertThat(q.evidence().provenance()).isEqualTo(io.liftandshift.strikebench.model.DataProvenance.OBSERVED);
        assertThat(svc.quote("MSFT")).isEmpty();
    }

    @Test
    void fixtureProviderCanNeverBecomeAnObservedFallback() {
        FixtureProvider fixture = new FixtureProvider(CLOCK);
        MarketDataService svc = new MarketDataService(
                List.of(new BrokenProvider(), fixture), List.of(fixture), List.of(fixture));
        svc.setDemoSources(fixture, fixture, fixture);

        assertThat(svc.quote("AAPL")).isEmpty();
        assertThat(svc.expirations("AAPL")).isEmpty();
        assertThat(svc.candleSeries("AAPL", LocalDate.now(CLOCK).minusMonths(1), LocalDate.now(CLOCK)).isEmpty()).isTrue();
        assertThat(svc.news("AAPL")).isEmpty();
        assertThat(svc.riskFreeRateQuote(30).evidence().provenance())
                .isEqualTo(io.liftandshift.strikebench.model.DataProvenance.MODELED);
        assertThat(svc.quote("AAPL", "demo")).isPresent();
        assertThat(svc.expirations("AAPL", "demo")).isNotEmpty();
        assertThat(svc.riskFreeRateQuote(30, "demo").evidence().provenance())
                .isEqualTo(io.liftandshift.strikebench.model.DataProvenance.DEMO);
    }

    @Test
    void observedCandleReadRejectsModeledEvidenceEvenWhenProviderIsMounted() {
        MarketDataService svc = new MarketDataService(List.of(new SyntheticCandleProvider()), List.of(), List.of());
        LocalDate to = LocalDate.now(CLOCK);
        assertThat(svc.candleSeries("AAPL", to.minusMonths(3), to).isEmpty()).isTrue();
    }

    @Test
    void observedCandleReadRejectsDemoRowsReturnedByTheStore() {
        LocalDate to = LocalDate.now(CLOCK), from = to.minusDays(5);
        var candle = new Candle(to.minusDays(1), new java.math.BigDecimal("100"),
                new java.math.BigDecimal("101"), new java.math.BigDecimal("99"),
                new java.math.BigDecimal("100.50"), 1_000, true);
        io.liftandshift.strikebench.market.ports.CandleStore badStore =
                (symbol, f, t, dataset) -> Optional.of(new io.liftandshift.strikebench.market.ports.CandleStore.Read(
                        new CandleSeries(List.of(candle), "fixture", io.liftandshift.strikebench.model.Freshness.FIXTURE),
                        CandleCoverage.assess(List.of(candle), f, t)));
        MarketDataService svc = new MarketDataService(List.of(), List.of(), List.of(), badStore);
        assertThat(svc.candleSeries("AAPL", from, to).isEmpty()).isTrue();
    }

    @Test
    void statusNeverThrowsEvenWithZeroProviders() {
        MarketDataService svc = new MarketDataService(List.of(), List.of(), List.of());
        Map<String, List<ProviderStatusInfo>> status = svc.status();
        assertThat(status).containsKeys("QUOTES", "OPTIONS", "CANDLES", "NEWS", "RATES");
        assertThat(svc.quote("AAPL")).isEmpty();
        assertThat(svc.expirations("AAPL")).isEmpty();
        assertThat(svc.news("AAPL")).isEmpty();
        assertThat(svc.riskFreeRate(30)).isEqualTo(0.04); // educational default
    }

    @Test
    void unknownStatusSeededBeforeFirstUse() {
        FixtureProvider fixture = new FixtureProvider(CLOCK);
        MarketDataService svc = new MarketDataService(List.of(fixture), List.of(fixture), List.of(fixture));
        Map<String, List<ProviderStatusInfo>> status = svc.status();
        assertThat(status.get("QUOTES")).hasSize(1);
        assertThat(status.get("QUOTES").getFirst().state()).isEqualTo("UNKNOWN");
    }

    @Test
    void chainAndNewsAndRatesFlow() {
        FixtureProvider fixture = new FixtureProvider(CLOCK);
        MarketDataService svc = new MarketDataService(List.of(fixture), List.of(fixture), List.of(fixture));
        LocalDate exp = svc.expirations("SPY").getFirst();
        assertThat(svc.chain("SPY", exp)).isPresent();
        List<NewsItem> news = svc.news("SPY");
        assertThat(news).isNotEmpty();
        assertThat(svc.riskFreeRate(30)).isEqualTo(0.04);
        assertThat(svc.lookup("apple")).extracting(SymbolMatch::symbol).contains("AAPL");
    }

    /** A canned news source (headlines OR filings), to exercise aggregation vs winner-take-all. */
    record FakeNews(String name, List<NewsItem> items) implements io.liftandshift.strikebench.market.ports.NewsFilingsProvider {
        @Override public List<NewsItem> news(String symbol) { return items; }
    }

    @Test
    void unknownExplicitWorldFailsClosedInsteadOfLeakingObservedData() {
        ObservedFixtureProvider observed = new ObservedFixtureProvider(CLOCK);
        var headline = new NewsItem("AAPL", "Observed headline", "wire", "http://wire/1", 1_000);
        MarketDataService svc = new MarketDataService(List.of(observed),
                List.of(new FakeNews("wire", List.of(headline))), List.of());
        svc.setWorldResolver(id -> Optional.empty());
        LocalDate to = LocalDate.now(CLOCK), from = to.minusMonths(1);
        LocalDate exp = observed.expirations("AAPL").getFirst();

        assertThat(svc.quote("AAPL")).isPresent();
        assertThat(svc.quote("AAPL", "deleted-world")).isEmpty();
        assertThat(svc.expirations("AAPL", "deleted-world")).isEmpty();
        assertThat(svc.chain("AAPL", exp, "deleted-world")).isEmpty();
        assertThat(svc.candleSeries("AAPL", from, to, "deleted-world",
                io.liftandshift.strikebench.db.AnalysisContext.OBSERVED).isEmpty()).isTrue();
        assertThat(svc.news("AAPL", "deleted-world")).isEmpty();
        assertThat(svc.lookup("AAPL", "deleted-world")).isEmpty();
        assertThat(svc.riskFreeRateQuote(30, "deleted-world").evidence().provenance())
                .isEqualTo(io.liftandshift.strikebench.model.DataProvenance.MISSING);
    }

    @Test
    void newsAggregatesEveryRealProviderSoFilingsAndHeadlinesCoexist() {
        FixtureProvider fixture = new FixtureProvider(CLOCK);
        var headline = new NewsItem("AAPL", "Apple beats earnings", "Reuters", "http://r/1", 3_000);
        var filing = new NewsItem("AAPL", "10-Q filing", "SEC EDGAR", "http://sec/1", 1_000);
        var svc = new MarketDataService(List.of(fixture),
                List.of(new FakeNews("news-rss", List.of(headline)), new FakeNews("edgar", List.of(filing)), fixture),
                List.of(fixture));

        List<NewsItem> news = svc.news("AAPL");
        // Both real sources are present (the old winner-take-all would have returned only the first),
        // newest first, and the fixture's demo headlines are NOT mixed in.
        assertThat(news).extracting(NewsItem::headline).containsExactly("Apple beats earnings", "10-Q filing");
        assertThat(news).extracting(NewsItem::source).doesNotContain("fixture");
    }

    @Test
    void newsNeverFallsBackToFixtureOutsideExplicitDemo() {
        FixtureProvider fixture = new FixtureProvider(CLOCK);
        var svc = new MarketDataService(List.of(), List.of(new FakeNews("edgar", List.of())), List.of());
        svc.setDemoSources(fixture, fixture, fixture);
        assertThat(svc.news("SPY")).isEmpty();
        assertThat(svc.news("SPY", "demo")).isNotEmpty();
    }
}
