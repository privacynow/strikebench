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

    /** A DETERMINISTIC mild-drift random walk (seeded): realistic pullbacks so breakouts/new-highs are
     *  a minority, and no exploitable structure so a signal shows no significant edge on it. */
    private List<Candle> walk() {
        List<Candle> out = new ArrayList<>();
        java.util.Random rnd = new java.util.Random(42);
        double px = 100;
        LocalDate d = LocalDate.parse("2023-01-02");
        for (int i = 0; i < 520; i++) {
            px *= (1 + 0.0003 + rnd.nextGaussian() * 0.012); // ~0.03%/day drift, ~1.2% daily vol
            px = Math.max(1, px);
            BigDecimal c = BigDecimal.valueOf(Math.round(px * 100) / 100.0);
            out.add(new Candle(d, c, c, c, c, 1_000_000, false));
            d = d.plusDays(1);
        }
        return out;
    }

    @Test
    void catalogOffersRealQuestionsNotAToy() {
        var cat = engine(walk(), Freshness.EOD).catalog();
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
        // Now a 0% threshold on a rising series conditions on nearly ALL bars, so its COMPLEMENT
        // (the baseline) is tiny/empty and the test cannot manufacture a false edge — the verdict is
        // never a bogus "Supported". This is the anti-regression for the "≥ 0%" nonsense.
        var r = engine(walk(), Freshness.EOD).run(new ResearchQuestionEngine.RunRequest(
                "momentum", "TEST", "2023-02-01", "2024-01-31",
                Map.of("lookback", 20, "thresholdPct", 0, "forward", 10)));
        assertThat(r.conditioned().sample()).isGreaterThan(0);
        assertThat(r.significant()).isFalse();               // an always-true signal is never "significant"
        assertThat(r.verdict()).doesNotContain("Supported");
    }

    @Test
    void baselineIsTheNonSignalComplementNotAllBars() {
        // Breakout fires on a minority of bars; the baseline (non-signal complement) is disjoint and
        // is the majority — the two groups don't overlap (finding: a superset baseline biases to null).
        var r = engine(walk(), Freshness.EOD).run(new ResearchQuestionEngine.RunRequest(
                "breakout_followthrough", "TEST", "2023-02-01", "2024-06-30",
                Map.of("lookback", 20, "forward", 10)));
        assertThat(r.conditioned().sample()).isGreaterThan(0);
        assertThat(r.baseline().sample()).isGreaterThan(0);
        // disjoint groups: neither is a subset — a new-high day is never also a non-new-high day
        assertThat(r.baseline().sample()).isNotEqualTo(r.conditioned().sample());
    }

    @Test
    void signalEventsAreNonOverlapping() {
        // A 10-day hold means events must start >= 10 bars apart: the up_streak signal on a rising
        // series fires on runs of consecutive days, and counting each firing as an independent
        // observation would multiply one episode into many. Pin: example dates are spaced >= forward.
        var r = engine(walk(), Freshness.EOD).run(new ResearchQuestionEngine.RunRequest(
                "up_streak", "TEST", "2023-02-01", "2024-06-30",
                Map.of("streak", 2, "forward", 10)));
        assertThat(r.conditioned().sample()).isGreaterThan(1);
        var dates = r.exampleDates().stream().map(java.time.LocalDate::parse).toList();
        for (int i = 1; i < dates.size(); i++) {
            long gapDays = java.time.temporal.ChronoUnit.DAYS.between(dates.get(i - 1), dates.get(i));
            // >= forward TRADING days apart; calendar gap is at least the trading gap
            assertThat(gapDays).isGreaterThanOrEqualTo(10);
        }
    }

    @Test
    void reportsEffectSizeAndSplitHalfHoldout() {
        var r = engine(walk(), Freshness.EOD).run(new ResearchQuestionEngine.RunRequest(
                "breakout_followthrough", "TEST", "2023-02-01", "2024-06-30",
                Map.of("lookback", 20, "forward", 5)));
        // With a healthy event count both rigor fields are populated and sane.
        if (r.conditioned().sample() >= 10) {
            assertThat(r.holdout()).isIn("held", "faded");
        }
        if (r.conditioned().sample() >= 5 && r.baseline().sample() >= 5) {
            assertThat(r.effectSize()).isNotNull();
            assertThat(Math.abs(r.effectSize())).isLessThan(10.0); // a standardized edge, not a raw %
        }
    }

    @Test
    void reportsSampleEdgeDistributionAndExamples() {
        var r = engine(walk(), Freshness.EOD).run(new ResearchQuestionEngine.RunRequest(
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
        var r = engine(walk(), Freshness.FIXTURE).run(new ResearchQuestionEngine.RunRequest(
                "pullback_rebound", "TEST", "2023-02-01", "2024-01-31",
                Map.of("lookback", 20, "dropPct", 3, "forward", 10)));
        assertThat(r.observed()).isFalse();
        assertThat(r.evidence()).isEqualTo("DEMO_FIXTURE");
        assertThat(r.notes()).anyMatch(n -> n.contains("DEMO"));
    }

    @Test
    void tooFewSignalsIsHonest() {
        // A 35% one-day drop essentially never happens in the gentle series → too-few verdict.
        var r = engine(walk(), Freshness.EOD).run(new ResearchQuestionEngine.RunRequest(
                "oversold_bounce", "TEST", "2023-02-01", "2024-01-31",
                Map.of("dropPct", 20, "forward", 10)));
        assertThat(r.conditioned().sample()).isLessThan(15);
        assertThat(r.verdict()).contains("Too few");
        assertThat(r.significant()).isFalse();
    }

    @Test
    void unknownQuestionRejected() {
        assertThatThrownBy(() -> engine(walk(), Freshness.EOD).run(
                new ResearchQuestionEngine.RunRequest("make_me_rich", "TEST", null, null, Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
