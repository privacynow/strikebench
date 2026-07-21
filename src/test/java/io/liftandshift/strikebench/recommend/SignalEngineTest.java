package io.liftandshift.strikebench.recommend;

import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.providers.FixtureProvider;
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
        assertThat(s.positiveHeadlines()).isEmpty();
        assertThat(s.negativeHeadlines()).isEmpty();
        assertThat(s.sentimentScore()).isZero();
        assertThat(s.eventRisk()).isFalse();
        assertThat(s.sentimentScorerVersion()).isEqualTo(NewsSentimentScorer.VERSION);
        assertThat(s.sentimentAggregate().available()).isFalse();
        assertThat(s.sentimentAggregate().basis()).isEqualTo(NewsSentimentScorer.DEMO_BASIS);
        assertThat(s.headlineSentiment()).isEmpty();
        assertThat(s.volatilityEvidence().impliedAvailable()).isTrue();
        assertThat(s.volatilityEvidence().realizedAvailable()).isTrue();
        assertThat(s.volatilityEvidence().impliedSource()).isEqualTo("fixture");
        assertThat(s.volatilityEvidence().realizedSource()).isEqualTo("fixture");
        assertThat(s.volatilityEvidence().realizedObservations()).isGreaterThan(30);
        assertThat(s.volatilityEvidence().unavailableReasons()).isEmpty();
        assertThat(s.eventEvidence().available()).isFalse();
        assertThat(s.eventEvidence().basis()).isEqualTo(NewsSentimentScorer.DEMO_BASIS);
        assertThat(s.eventEvidence().scorerVersion()).isEqualTo(NewsSentimentScorer.VERSION);
        assertThat(s.rationale()).noneSatisfy(r -> assertThat(r)
                .containsIgnoringCase("earnings/guidance-type"));
        assertThat(s.liquidityScore()).isBetween(0.0, 1.0);
    }

    @Test
    void fabricatedFixtureHeadlinesNeverInfluenceDemoSignals() {
        SignalEngine.Signals spy = engine.analyze("SPY").orElseThrow();
        assertThat(spy.positiveHeadlines()).isEmpty();
        assertThat(spy.negativeHeadlines()).isEmpty();
        assertThat(spy.sentimentScore()).isZero();
        assertThat(spy.eventRisk()).isFalse();
        assertThat(spy.sentimentAggregate().available()).isFalse();
        assertThat(spy.sentimentAggregate().basis()).isEqualTo(NewsSentimentScorer.DEMO_BASIS);

        SignalEngine.Signals tsla = engine.analyze("TSLA").orElseThrow();
        assertThat(tsla.positiveHeadlines()).isEmpty();
        assertThat(tsla.negativeHeadlines()).isEmpty();
        assertThat(tsla.sentimentScore()).isZero();
        assertThat(tsla.eventRisk()).isFalse();
        assertThat(tsla.sentimentScorerVersion()).isEqualTo(NewsSentimentScorer.VERSION);
        assertThat(tsla.sentimentAggregate().available()).isFalse();
    }

    @Test
    void nonOptionableAndUnknownSymbols() {
        SignalEngine.Signals vtsax = engine.analyze("VTSAX").orElseThrow();
        assertThat(vtsax.optionable()).isFalse();
        assertThat(vtsax.ivAtm()).isNull();
        assertThat(vtsax.volatilityEvidence().impliedAvailable()).isFalse();
        assertThat(vtsax.volatilityEvidence().unavailableReasons())
                .anySatisfy(reason -> assertThat(reason).contains("No listed option chain"));
        assertThat(engine.analyze("ZZZZ")).isEmpty();
    }

    @Test
    void signalsAreDeterministic() {
        SignalEngine.Signals a = engine.analyze("QQQ").orElseThrow();
        SignalEngine.Signals b = engine.analyze("QQQ").orElseThrow();
        assertThat(a).isEqualTo(b);
    }
}
