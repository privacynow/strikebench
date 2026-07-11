package io.liftandshift.strikebench.market;

import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.market.providers.FixtureProvider;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.NewsItem;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.model.SymbolMatch;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDataServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T15:30:00Z"), ZoneId.of("America/New_York"));

    /** Wraps the fixture and counts calls, to observe caching and chain traversal. */
    static final class CountingProvider implements MarketDataProvider {
        final FixtureProvider inner = new FixtureProvider(CLOCK);
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
