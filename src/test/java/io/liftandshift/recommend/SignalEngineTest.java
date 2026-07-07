package io.liftandshift.recommend;

import io.liftandshift.market.MarketDataService;
import io.liftandshift.market.providers.FixtureProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SignalEngineTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T15:30:00Z"), ZoneId.of("America/New_York"));

    private SignalEngine engine;

    @BeforeEach
    void setUp() {
        FixtureProvider fixture = new FixtureProvider(CLOCK);
        MarketDataService market = new MarketDataService(List.of(fixture), List.of(fixture), List.of(fixture));
        engine = new SignalEngine(market, CLOCK);
    }

    @Test
    void keywordSentimentCountsStemHits() {
        assertThat(SignalEngine.countHits("apple beats expectations as growth accelerates", SignalEngine.POSITIVE)).isGreaterThanOrEqualTo(2);
        assertThat(SignalEngine.countHits("regulators open probe into practices", SignalEngine.NEGATIVE)).isGreaterThanOrEqualTo(1);
        assertThat(SignalEngine.countHits("company schedules a meeting", SignalEngine.POSITIVE)).isZero();
        assertThat(SignalEngine.countHits("company schedules a meeting", SignalEngine.NEGATIVE)).isZero();
    }

    @Test
    void analyzeProducesFullEvidenceForOptionableSymbol() {
        SignalEngine.Signals s = engine.analyze("AAPL").orElseThrow();
        assertThat(s.optionable()).isTrue();
        assertThat(s.ret20d()).isNotNull();
        assertThat(s.ivAtm()).isBetween(0.05, 2.0);
        assertThat(s.hv30()).isGreaterThan(0.01);
        assertThat(s.ivHvRatio()).isGreaterThan(0.0);
        assertThat(s.volSignal()).isIn("RICH", "CHEAP", "FAIR");
        assertThat(s.thesis()).isIn("BULLISH", "BEARISH", "NEUTRAL", "VOLATILE");
        assertThat(s.confidence()).isBetween(0.0, 1.0);
        assertThat(s.rationale()).isNotEmpty();
        // AAPL fixture news has one clearly positive and one clearly negative headline
        assertThat(s.positiveHeadlines()).anySatisfy(h -> assertThat(h).containsIgnoringCase("beats"));
        assertThat(s.negativeHeadlines()).anySatisfy(h -> assertThat(h).containsIgnoringCase("probe"));
        assertThat(s.eventRisk()).isTrue(); // "quarterly earnings call" headline
        assertThat(s.liquidityScore()).isBetween(0.0, 1.0);
    }

    @Test
    void sentimentDiffersAcrossFixtureSymbols() {
        SignalEngine.Signals spy = engine.analyze("SPY").orElseThrow();
        assertThat(spy.positiveHeadlines()).isNotEmpty(); // "rally to record close ... cools"
        assertThat(spy.negativeHeadlines()).isEmpty();
        assertThat(spy.sentimentScore()).isGreaterThan(0);

        SignalEngine.Signals tsla = engine.analyze("TSLA").orElseThrow();
        assertThat(tsla.positiveHeadlines()).isNotEmpty();
        assertThat(tsla.negativeHeadlines()).isNotEmpty(); // recall/scrutiny
    }

    @Test
    void nonOptionableAndUnknownSymbols() {
        SignalEngine.Signals vtsax = engine.analyze("VTSAX").orElseThrow();
        assertThat(vtsax.optionable()).isFalse();
        assertThat(vtsax.ivAtm()).isNull();
        assertThat(engine.analyze("ZZZZ")).isEmpty();
    }

    @Test
    void signalsAreDeterministic() {
        SignalEngine.Signals a = engine.analyze("QQQ").orElseThrow();
        SignalEngine.Signals b = engine.analyze("QQQ").orElseThrow();
        assertThat(a).isEqualTo(b);
    }
}
