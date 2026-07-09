package io.liftandshift.strikebench.research;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.market.CandleSeries;
import io.liftandshift.strikebench.market.Domain;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.market.ports.NewsFilingsProvider;
import io.liftandshift.strikebench.market.ports.RatesProvider;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.model.SymbolMatch;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The research-question workbench: baseline-relative, no-look-ahead, honest stats. */
class ResearchQuestionEngineTest {

    /** A candle provider that serves a scripted synthetic series with a known regime. */
    static final class SeriesProvider implements MarketDataProvider {
        final List<Candle> candles;
        final Freshness freshness;
        SeriesProvider(List<Candle> candles, Freshness f) { this.candles = candles; this.freshness = f; }
        public String name() { return freshness == Freshness.FIXTURE ? "fixture" : "test"; }
        public Set<Domain> domains() { return Set.of(Domain.CANDLES); }
        public List<SymbolMatch> lookup(String q) { return List.of(); }
        public Optional<Quote> quote(String s) { return Optional.empty(); }
        public List<LocalDate> expirations(String s) { return List.of(); }
        public Optional<OptionChain> chain(String s, LocalDate e) { return Optional.empty(); }
        public List<Candle> candles(String s, LocalDate from, LocalDate to) {
            List<Candle> out = new ArrayList<>();
            for (Candle c : candles) if (!c.date().isBefore(from) && !c.date().isAfter(to)) out.add(c);
            return out;
        }
    }

    private ResearchQuestionEngine engine(List<Candle> candles, Freshness f) {
        MarketDataService market = new MarketDataService(
                List.<MarketDataProvider>of(new SeriesProvider(candles, f)),
                List.<NewsFilingsProvider>of(), List.<RatesProvider>of());
        return new ResearchQuestionEngine(market);
    }

    /** A steady uptrend: 400 bars rising 0.3%/day with a small oscillation. */
    private List<Candle> uptrend() {
        List<Candle> out = new ArrayList<>();
        double px = 100;
        LocalDate d = LocalDate.parse("2023-01-02");
        for (int i = 0; i < 400; i++) {
            px *= (1 + 0.003 + 0.004 * Math.sin(i / 3.0)); // drift + wiggle
            BigDecimal c = BigDecimal.valueOf(Math.round(px * 100) / 100.0);
            out.add(new Candle(d, c, c, c, c, 1_000_000, false));
            d = d.plusDays(1);
        }
        return out;
    }

    @Test
    void catalogOffersRealQuestionsNotAToy() {
        var cat = engine(uptrend(), Freshness.EOD).catalog();
        assertThat(cat).extracting(ResearchQuestionEngine.Question::key)
                .contains("pullback_rebound", "breakout_followthrough", "oversold_bounce", "momentum", "up_streak");
        // Each carries a plain-language label and at least the forward-days param.
        assertThat(cat).allSatisfy(q -> {
            assertThat(q.plain()).isNotBlank();
            assertThat(q.params()).isNotEmpty();
        });
    }

    @Test
    void momentumIsBaselineRelative_neverTheDegenerateAlwaysTrue() {
        // The old toy: "20-day momentum >= 0%" fired on ~every bar and compared to a bare 50%.
        // Now a 0% threshold on a rising series conditions on nearly ALL bars, so the conditioned
        // set ≈ the baseline and the verdict must be "no clear edge" — NOT a false ">= 0%" win.
        var r = engine(uptrend(), Freshness.EOD).run(new ResearchQuestionEngine.RunRequest(
                "momentum", "TEST", "2023-02-01", "2024-01-31",
                Map.of("lookback", 20, "thresholdPct", 0, "forward", 10)));
        assertThat(r.conditioned().sample()).isGreaterThan(0);
        assertThat(r.baseline().sample()).isGreaterThanOrEqualTo(r.conditioned().sample());
        // conditioned ≈ baseline when the condition is nearly always true → tiny edge, not "supported"
        assertThat(Math.abs(r.winRateEdgePct())).isLessThan(5.0);
        assertThat(r.verdict()).doesNotContain("Supported");
    }

    @Test
    void reportsSampleEdgeDistributionAndExamples() {
        var r = engine(uptrend(), Freshness.EOD).run(new ResearchQuestionEngine.RunRequest(
                "breakout_followthrough", "TEST", "2023-02-01", "2024-06-30",
                Map.of("lookback", 20, "forward", 10)));
        assertThat(r.conditioned().sample()).isGreaterThanOrEqualTo(1);
        assertThat(r.baseline().sample()).isGreaterThan(r.conditioned().sample());
        assertThat(r.distribution()).isNotEmpty();
        assertThat(r.exampleDates()).isNotEmpty();
        assertThat(r.observed()).isTrue();
        assertThat(r.evidence()).startsWith("OBSERVED");
        // win rate is a real percentage, not the old bare-50% comparison
        assertThat(r.conditioned().winRatePct()).isBetween(0.0, 100.0);
    }

    @Test
    void fixtureHistoryIsLabeledDemoNotObserved() {
        var r = engine(uptrend(), Freshness.FIXTURE).run(new ResearchQuestionEngine.RunRequest(
                "pullback_rebound", "TEST", "2023-02-01", "2024-01-31",
                Map.of("lookback", 20, "dropPct", 3, "forward", 10)));
        assertThat(r.observed()).isFalse();
        assertThat(r.evidence()).isEqualTo("DEMO_FIXTURE");
        assertThat(r.notes()).anyMatch(n -> n.contains("DEMO"));
    }

    @Test
    void tooFewSignalsIsHonest() {
        // A 35% one-day drop essentially never happens in the gentle series → too-few verdict.
        var r = engine(uptrend(), Freshness.EOD).run(new ResearchQuestionEngine.RunRequest(
                "oversold_bounce", "TEST", "2023-02-01", "2024-01-31",
                Map.of("dropPct", 20, "forward", 10)));
        assertThat(r.conditioned().sample()).isLessThan(15);
        assertThat(r.verdict()).contains("Too few");
        assertThat(r.significant()).isFalse();
    }

    @Test
    void unknownQuestionRejected() {
        assertThatThrownBy(() -> engine(uptrend(), Freshness.EOD).run(
                new ResearchQuestionEngine.RunRequest("make_me_rich", "TEST", null, null, Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
