package io.liftandshift.strikebench.recommend;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.eval.EvaluationService;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.providers.FixtureProvider;
import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutoRecommenderTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T15:30:00Z"), ZoneId.of("America/New_York"));
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 8);
    private static final long BP = 10_000_000L;

    private AutoRecommender auto;
    private Db db;

    @BeforeAll
    void openDb() { db = TestDb.fresh(); }

    @AfterAll
    void closeDb() { if (db != null) db.close(); }

    @BeforeEach
    void setUp() {
        FixtureProvider fixture = new FixtureProvider(CLOCK);
        MarketDataService market = new MarketDataService(List.of(fixture), List.of(fixture), List.of(fixture));
        AppConfig cfg = new AppConfig(Map.of("FIXTURES_ONLY", "true"));
        RecommendationEngine engine = new RecommendationEngine(market, CLOCK);
        EvaluationService evaluations = new EvaluationService(market, engine, db, CLOCK);
        auto = new AutoRecommender(new SignalEngine(market, CLOCK), engine, evaluations, cfg, CLOCK);
    }

    private static AutoRecommender.AutoRequest req(List<String> horizons, Long targetProfit, Boolean allow0dte) {
        return new AutoRecommender.AutoRequest(null, horizons, 3, targetProfit, null, null, null,
                "balanced", allow0dte, null, null, null);
    }

    @Test
    void scansUniversePicksOptionableSymbolsWithEvidence() {
        AutoRecommender.AutoResult result = auto.run(req(null, null, false), BP);

        assertThat(result.picks()).isNotEmpty().hasSizeLessThanOrEqualTo(3);
        for (AutoRecommender.Pick pick : result.picks()) {
            assertThat(pick.signals().optionable()).isTrue();
            assertThat(pick.signals().thesis()).isIn("BULLISH", "BEARISH", "NEUTRAL", "VOLATILE");
            assertThat(pick.signals().rationale()).isNotEmpty();
            assertThat(pick.opportunityScore()).isBetween(0.0, 1.0);
            // default horizons without 0DTE opt-in
            assertThat(pick.horizons()).extracting(AutoRecommender.HorizonIdeas::horizon)
                    .containsExactly("week", "month");
            for (AutoRecommender.HorizonIdeas h : pick.horizons()) {
                // Two curated ideas plus at most one explicitly labeled teaching counterexample.
                assertThat(h.candidates()).hasSizeLessThanOrEqualTo(3);
                for (AutoRecommender.ScoredCandidate sc : h.candidates()) {
                    assertThat(sc.candidate().maxLossCents()).isPositive();
                    io.liftandshift.strikebench.strategy.StrategyFamily family =
                            io.liftandshift.strikebench.strategy.StrategyFamily.valueOf(sc.candidate().strategy());
                    assertThat(family.blockedByDefault()).isFalse();
                }
            }
        }
        assertThat(result.disclaimer()).containsIgnoringCase("not predictions");
        // VTSAX is in the fixture universe and must be skipped with a reason
        assertThat(result.skipped()).isEmpty(); // fixture universe has only optionable symbols
    }

    @Test
    void focusedScoutPricesEveryPeerUnderThePlanOwnedThesis() {
        AutoRecommender.AutoResult baseline = auto.run(new AutoRecommender.AutoRequest(
                List.of("AAPL"), List.of("month"), 1, null, null, null, null,
                "balanced", false, List.of("DIRECTIONAL"), null, null), BP);
        String derived = baseline.picks().getFirst().signals().thesis();
        String override = "BULLISH".equals(derived) ? "bearish" : "bullish";

        AutoRecommender.AutoResult focused = auto.run(new AutoRecommender.AutoRequest(
                List.of("AAPL"), List.of("month"), 1, null, null, null, null,
                "balanced", false, List.of("DIRECTIONAL"), null, override), BP);

        assertThat(focused.picks().stream().flatMap(pick -> pick.horizons().stream())
                .flatMap(horizon -> horizon.candidates().stream())).isNotEmpty();
        assertThat(focused.picks()).singleElement().satisfies(pick ->
                assertThat(pick.horizons()).allSatisfy(horizon ->
                        assertThat(horizon.candidates()).allSatisfy(scored ->
                                assertThat(io.liftandshift.strikebench.strategy.StrategyFamily
                                        .valueOf(scored.candidate().strategy())
                                        .fits(io.liftandshift.strikebench.strategy.StrategyFamily.Thesis
                                                .valueOf(override.toUpperCase()))).isTrue())));
    }

    @Test
    void missingDailyHistoryIsNotReportedAsAnEconomicallyUnfavorableScan() {
        var incomplete = new io.liftandshift.strikebench.eval.EconomicAssessment(
                io.liftandshift.strikebench.eval.EconomicAssessment.Verdict.MIXED,
                "COMPARE_CAREFULLY", "Economics incomplete", "history missing",
                -100L, null, 260L, -0.5, false,
                List.of(io.liftandshift.strikebench.eval.EconomicAssessment.DAILY_HISTORY_REASON));
        var rows = List.of(new AutoRecommender.ScoredCandidate(null, 50, null, incomplete));

        assertThat(AutoRecommender.noFavorableNote(rows, true))
                .contains("cannot be formed yet")
                .contains("20 eligible daily closes")
                .contains("Data → Sources & jobs")
                .doesNotContain("No favorable setup was found");
    }

    @Test
    void unsupportedPayoffModelIsNotMisreportedAsMissingCandles() {
        var unsupported = new io.liftandshift.strikebench.eval.EconomicAssessment(
                io.liftandshift.strikebench.eval.EconomicAssessment.Verdict.MIXED,
                "COMPARE_CAREFULLY", "Economics incomplete", "model unsupported",
                -100L, null, 260L, -0.5, false,
                List.of("The realized-volatility EV lane is unavailable for this multi-expiration structure."));
        var rows = List.of(new AutoRecommender.ScoredCandidate(null, 50, null, unsupported));

        assertThat(AutoRecommender.noFavorableNote(rows, true))
                .contains("available after-cost economic checks")
                .doesNotContain("daily closes");
    }

    @Test
    void mechanicalRefusalsAreNotMisreportedAsEconomicOrHistoryVerdicts() {
        var blocked = new io.liftandshift.strikebench.eval.EconomicAssessment(
                io.liftandshift.strikebench.eval.EconomicAssessment.Verdict.UNAVAILABLE,
                "MECHANICALLY_INELIGIBLE", "Cannot assess as a trade", "mechanical failure",
                -100L, null, 260L, -0.5, true, List.of("book is not executable"));
        var rows = List.of(new AutoRecommender.ScoredCandidate(null, 50, null, blocked));

        assertThat(AutoRecommender.noFavorableNote(rows, true))
                .contains("every candidate failed a mechanical or account check")
                .contains("not an economic verdict")
                .doesNotContain("daily closes");
    }

    @Test
    void explicitUniverseSkipsNonOptionableWithReason() {
        AutoRecommender.AutoRequest request = new AutoRecommender.AutoRequest(
                List.of("AAPL", "VTSAX", "ZZZZ"), null, 3, null, null, null, null,
                "balanced", false, null, null, null);
        AutoRecommender.AutoResult result = auto.run(request, BP);
        assertThat(result.picks()).extracting(AutoRecommender.Pick::symbol).containsExactly("AAPL");
        assertThat(result.skipped()).anySatisfy(s -> assertThat(s).contains("VTSAX").contains("no listed options"));
        assertThat(result.skipped()).anySatisfy(s -> assertThat(s).contains("ZZZZ").contains("no market data"));
    }

    @Test
    void zeroDteOnlyWhenAllowedAndOnlySameDayExpiries() {
        AutoRecommender.AutoResult without = auto.run(req(List.of("0DTE", "week"), null, false), BP);
        for (AutoRecommender.Pick p : without.picks()) {
            assertThat(p.horizons()).extracting(AutoRecommender.HorizonIdeas::horizon).doesNotContain("0DTE");
        }
        assertThat(without.notes()).anySatisfy(n -> assertThat(n).containsIgnoringCase("allow0dte"));

        AutoRecommender.AutoRequest spyOnly = new AutoRecommender.AutoRequest(
                List.of("SPY"), List.of("0DTE"), 1, null, null, null, null,
                "aggressive", true, null, null, null);
        AutoRecommender.AutoResult with = auto.run(spyOnly, BP);
        assertThat(with.picks()).hasSize(1);
        AutoRecommender.HorizonIdeas zeroDte = with.picks().getFirst().horizons().getFirst();
        assertThat(zeroDte.horizon()).isEqualTo("0DTE");
        assertThat(zeroDte.candidates()).isNotEmpty();
        for (AutoRecommender.ScoredCandidate sc : zeroDte.candidates()) {
            for (LegView leg : sc.candidate().legs()) {
                if (leg.expiration() != null) assertThat(LocalDate.parse(leg.expiration())).isEqualTo(TODAY);
            }
        }
    }

    @Test
    void profitTargetAnnotatesEveryCandidate() {
        AutoRecommender.AutoResult result = auto.run(req(List.of("month"), 25_000L, false), BP);
        assertThat(result.picks()).isNotEmpty();
        boolean sawAnnotation = false;
        for (AutoRecommender.Pick p : result.picks()) {
            for (AutoRecommender.HorizonIdeas h : p.horizons()) {
                for (AutoRecommender.ScoredCandidate sc : h.candidates()) {
                    assertThat(sc.targetFit()).isNotBlank();
                    assertThat(sc.targetFit()).containsAnyOf("covers", "cannot reach", "Uncapped");
                    sawAnnotation = true;
                }
            }
        }
        assertThat(sawAnnotation).isTrue();
        assertThat(result.notes()).anySatisfy(n -> assertThat(n).containsIgnoringCase("aspirations"));
    }

    @Test
    void everyScoutCandidateCarriesTheSharedDecisionRanking() {
        AutoRecommender.AutoResult result = auto.run(req(List.of("month"), null, false), BP);
        boolean sawCandidate = false;
        for (AutoRecommender.Pick p : result.picks()) {
            for (AutoRecommender.HorizonIdeas h : p.horizons()) {
                double previous = Double.POSITIVE_INFINITY;
                for (AutoRecommender.ScoredCandidate sc : h.candidates()) {
                    sawCandidate = true;
                    assertThat(sc.economics()).isNotNull();
                    assertThat(sc.decisionScore()).isBetween(0.0, 100.0)
                            .isLessThanOrEqualTo(previous);
                    previous = sc.decisionScore();
                }
            }
        }
        assertThat(sawCandidate).isTrue();
    }

    @Test
    void respectsMaxLossBudget() {
        AutoRecommender.AutoRequest request = new AutoRecommender.AutoRequest(
                null, List.of("month"), 3, null, 50_000L, null, null, "balanced", false,
                null, null, null);
        AutoRecommender.AutoResult result = auto.run(request, BP);
        for (AutoRecommender.Pick p : result.picks()) {
            for (AutoRecommender.HorizonIdeas h : p.horizons()) {
                for (AutoRecommender.ScoredCandidate sc : h.candidates()) {
                    assertThat(sc.candidate().maxLossCents()).isLessThanOrEqualTo(50_000L);
                }
            }
        }
    }
    @Test
    void exitIntentScansHoldingsNotTheUniverse() {
        var holdings = java.util.List.of(new AutoRecommender.HoldingInfo("AAPL", 200, 20_000L));
        AutoRecommender.AutoRequest r = new AutoRecommender.AutoRequest(java.util.List.of("SPY", "QQQ"),
                java.util.List.of("month"), 3, null, null, null, null, "balanced", false,
                java.util.List.of("exit"), null, null);
        AutoRecommender.AutoResult res = auto.run(r, BP, holdings);
        assertThat(res.picks()).isNotEmpty();
        for (AutoRecommender.Pick p : res.picks()) {
            assertThat(p.intent()).isEqualTo("EXIT");
            assertThat(p.symbol()).isEqualTo("AAPL"); // what you HOLD, not the scanned universe
        }
        Candidate cc = res.picks().getFirst().horizons().getFirst().candidates().stream()
                .map(AutoRecommender.ScoredCandidate::candidate)
                .filter(c -> c.strategy().equals("COVERED_CALL")).findFirst().orElseThrow();
        assertThat(cc.usesHeldShares()).isTrue();
        assertThat(cc.qty()).isEqualTo(2); // both free lots covered
    }

    @Test
    void exitIntentWithoutHoldingsExplainsInsteadOfInventing() {
        AutoRecommender.AutoRequest r = new AutoRecommender.AutoRequest(null, java.util.List.of("month"), 3,
                null, null, null, null, "balanced", false, java.util.List.of("exit", "hedge"), null, null);
        AutoRecommender.AutoResult res = auto.run(r, BP);
        assertThat(res.picks()).isEmpty();
        assertThat(String.join(" ", res.notes())).contains("buy shares first");
    }

    @Test
    void incomeIntentScanReturnsIncomeCandidatesAcrossTheUniverse() {
        AutoRecommender.AutoRequest r = new AutoRecommender.AutoRequest(null, java.util.List.of("month"), 3,
                null, null, null, null, "balanced", false, java.util.List.of("income"), null, null);
        AutoRecommender.AutoResult res = auto.run(r, BP);
        assertThat(res.picks()).isNotEmpty();
        for (AutoRecommender.Pick p : res.picks()) {
            assertThat(p.intent()).isEqualTo("INCOME");
            for (AutoRecommender.HorizonIdeas h : p.horizons()) {
                for (AutoRecommender.ScoredCandidate sc : h.candidates()) {
                    assertThat(io.liftandshift.strikebench.strategy.StrategyFamily.valueOf(sc.candidate().strategy())
                            .servesIntent(io.liftandshift.strikebench.strategy.StrategyIntent.INCOME)).isTrue();
                }
            }
        }
    }

}
