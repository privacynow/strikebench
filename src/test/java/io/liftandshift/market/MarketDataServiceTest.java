package io.liftandshift.market;

import io.liftandshift.market.ports.MarketDataProvider;
import io.liftandshift.market.providers.FixtureProvider;
import io.liftandshift.model.Candle;
import io.liftandshift.model.NewsItem;
import io.liftandshift.model.OptionChain;
import io.liftandshift.model.Quote;
import io.liftandshift.model.SymbolMatch;
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
    void brokenProviderFallsThroughToFixtureAndStatusRecordsBoth() {
        FixtureProvider fixture = new FixtureProvider(CLOCK);
        MarketDataService svc = new MarketDataService(List.of(new BrokenProvider(), fixture), List.of(fixture), List.of(fixture));

        Quote q = svc.quote("AAPL").orElseThrow();
        assertThat(q.source()).isEqualTo("fixture");

        Map<String, List<ProviderStatusInfo>> status = svc.status();
        List<ProviderStatusInfo> quotes = status.get("QUOTES");
        assertThat(quotes).extracting(ProviderStatusInfo::provider).contains("broken", "fixture");
        assertThat(quotes.stream().filter(s -> s.provider().equals("broken")).findFirst().orElseThrow().state()).isEqualTo("ERROR");
        assertThat(quotes.stream().filter(s -> s.provider().equals("fixture")).findFirst().orElseThrow().state()).isEqualTo("OK");
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
}
