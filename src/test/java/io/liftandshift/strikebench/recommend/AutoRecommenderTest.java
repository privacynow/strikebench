package io.liftandshift.strikebench.recommend;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.eval.EvaluationService;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.MarketHours;
import io.liftandshift.strikebench.market.providers.FixtureProvider;
import io.liftandshift.strikebench.market.sim.SimulatedWorld;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import io.liftandshift.strikebench.support.TestPrices;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutoRecommenderTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T15:30:00Z"), ZoneId.of("America/New_York"));
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 8);
    private static final long BP = 10_000_000L;

    private AutoRecommender auto;
    private OpportunityScanner opportunityScanner;
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
        EvaluationService evaluations = new EvaluationService(market, db, CLOCK);
        OpportunityScanKernel scanKernel = new OpportunityScanKernel();
        auto = new AutoRecommender(new SignalEngine(market, CLOCK), engine, evaluations, cfg, scanKernel);
        opportunityScanner = new OpportunityScanner(engine, evaluations, scanKernel);
    }

    private static AutoRecommender.AutoRequest req(List<String> horizons, Long targetProfit, Boolean allow0dte) {
        return new AutoRecommender.AutoRequest(null, horizons, 3, targetProfit, null, null, null,
                "balanced", allow0dte, List.of("DIRECTIONAL"), null, null);
    }

    @Test
    void opportunityScannerNormalizesUniverseAndUsesDecisionOrder() {
        OpportunityScanner.ScanResult result = opportunityScanner.scan(
                List.of("aapl", " AAPL ", "spy"), "DIRECTIONAL", "bullish", "month", "balanced",
                BP, "local", 2, null, null);

        assertThat(result.scanned()).isEqualTo(2);
        assertThat(result.ranked()).isNotEmpty().hasSizeLessThanOrEqualTo(2);
        double previous = Double.POSITIVE_INFINITY;
        for (var evaluation : result.ranked()) {
            assertThat(evaluation.decisionScore()).isLessThanOrEqualTo(previous);
            previous = evaluation.decisionScore();
        }
    }

    @Test
    void sharedTraversalLeavesEachScanPolicyInChargeOfItsOwnRanking() {
        List<String> universe = List.of("QQQ", "AAPL", "SPY");
        AutoRecommender.AutoResult scout = auto.run(new AutoRecommender.AutoRequest(
                universe, List.of("month"), 3, null, null, null, null,
                "balanced", false, List.of("INCOME"), null, null), BP);
        assertThat(scout.picks()).isNotEmpty();
        for (int i = 1; i < scout.picks().size(); i++) {
            AutoRecommender.Pick previous = scout.picks().get(i - 1);
            AutoRecommender.Pick current = scout.picks().get(i);
            assertThat(previous.opportunityScore()).isGreaterThanOrEqualTo(current.opportunityScore());
            if (previous.opportunityScore() == current.opportunityScore()) {
                assertThat(previous.symbol().compareTo(current.symbol())).isLessThanOrEqualTo(0);
            }
        }

        OpportunityScanner.ScanResult portfolio = opportunityScanner.scan(
                universe, "INCOME", "neutral", "month", "balanced",
                BP, "local", 3, null, null);
        assertThat(portfolio.ranked()).isNotEmpty();
        assertThat(portfolio.ranked()).extracting(
                        io.liftandshift.strikebench.eval.StrategyEvaluation::symbol)
                .doesNotHaveDuplicates();
        for (int i = 1; i < portfolio.ranked().size(); i++) {
            assertThat(portfolio.ranked().get(i - 1).decisionScore())
                    .isGreaterThanOrEqualTo(portfolio.ranked().get(i).decisionScore());
        }
    }

    @Test
    void scansUniversePicksOptionableSymbolsWithEvidence() {
        AutoRecommender.AutoResult result = auto.run(req(List.of("week", "month"), null, false), BP);

        assertThat(result.picks()).isNotEmpty().hasSizeLessThanOrEqualTo(3);
        for (AutoRecommender.Pick pick : result.picks()) {
            assertThat(pick.signals().optionable()).isTrue();
            assertThat(pick.signals().thesis()).isIn("BULLISH", "BEARISH", "NEUTRAL", "VOLATILE");
            assertThat(pick.signals().rationale()).isNotEmpty();
            assertThat(pick.opportunityScore()).isBetween(0.0, 1.0);
            assertThat(pick.opportunity().goal()).isEqualTo("DIRECTIONAL");
            assertThat(pick.opportunity().score()).isEqualTo(pick.opportunityScore());
            assertThat(pick.opportunity().summary()).isNotBlank();
            assertThat(pick.opportunity().volatilityEvidence()).isNotNull();
            assertThat(pick.opportunity().eventEvidence()).isNotNull();
            assertThat(pick.bestIdea()).isNotNull();
            // default horizons without 0DTE opt-in
            assertThat(pick.horizons()).extracting(AutoRecommender.HorizonIdeas::horizon)
                    .containsExactly("week", "month");
            for (AutoRecommender.HorizonIdeas h : pick.horizons()) {
                // Two curated ideas plus at most one explicitly labeled teaching counterexample.
                assertThat(h.candidates()).hasSizeLessThanOrEqualTo(3);
                for (AutoRecommender.ScoredCandidate sc : h.candidates()) {
                    assertThat(sc.evaluation().candidate().maxLossCents()).isPositive();
                    io.liftandshift.strikebench.strategy.StrategyFamily family =
                            io.liftandshift.strikebench.strategy.StrategyFamily.valueOf(sc.evaluation().candidate().strategy());
                    assertThat(family.blockedByDefault()).isFalse();
                }
            }
        }
        assertThat(result.disclaimer()).containsIgnoringCase("not predictions");
        // VTSAX is in the fixture universe and must be skipped with a reason
        assertThat(result.skipped()).isEmpty(); // fixture universe has only optionable symbols
    }

    @Test
    void opportunityRankingUsesVolatilityInTheDirectionOfTheDeclaredGoal() {
        assertThat(AutoRecommender.volatilityFit(1.8, io.liftandshift.strikebench.strategy.StrategyIntent.INCOME))
                .isGreaterThan(AutoRecommender.volatilityFit(0.6,
                        io.liftandshift.strikebench.strategy.StrategyIntent.INCOME));
        assertThat(AutoRecommender.volatilityFit(0.6, io.liftandshift.strikebench.strategy.StrategyIntent.HEDGE))
                .isGreaterThan(AutoRecommender.volatilityFit(1.8,
                        io.liftandshift.strikebench.strategy.StrategyIntent.HEDGE));
        assertThat(AutoRecommender.volatilityFit(null,
                io.liftandshift.strikebench.strategy.StrategyIntent.INCOME)).isNull();
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
                                        .valueOf(scored.evaluation().candidate().strategy())
                                        .fits(io.liftandshift.strikebench.strategy.StrategyFamily.Thesis
                                        .valueOf(override.toUpperCase()))).isTrue())));
    }

    @Test
    void explicitViewNarrowsIncomeScoutThroughTheExistingStrategyCatalog() {
        AutoRecommender.AutoResult focused = auto.run(new AutoRecommender.AutoRequest(
                List.of("AAPL"), List.of("month"), 1, null, null, null, null,
                "balanced", false, List.of("INCOME"), null, "bullish"), BP);

        assertThat(focused.picks().stream().flatMap(pick -> pick.horizons().stream())
                .flatMap(horizon -> horizon.candidates().stream())).isNotEmpty();
        assertThat(focused.picks()).singleElement().satisfies(pick ->
                assertThat(pick.horizons()).allSatisfy(horizon ->
                        assertThat(horizon.candidates()).allSatisfy(scored ->
                                assertThat(io.liftandshift.strikebench.strategy.StrategyFamily
                                        .valueOf(scored.evaluation().candidate().strategy())
                                        .fits(io.liftandshift.strikebench.strategy.StrategyFamily.Thesis.BULLISH))
                                        .isTrue())));
    }

    @Test
    void missingDailyHistoryIsNotReportedAsAnEconomicallyUnfavorableScan() {
        var incomplete = new io.liftandshift.strikebench.eval.EconomicAssessment(
                io.liftandshift.strikebench.eval.EconomicAssessment.Verdict.MIXED,
                "COMPARE_CAREFULLY", "Economics incomplete", "history missing",
                -100L, null, 260L, -0.5, false,
                List.of(io.liftandshift.strikebench.eval.EconomicAssessment.DAILY_HISTORY_REASON));
        var rows = List.of(scored(incomplete));

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
        var rows = List.of(scored(unsupported));

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
        var rows = List.of(scored(blocked));

        assertThat(AutoRecommender.noFavorableNote(rows, true))
                .contains("every candidate failed a mechanical or account check")
                .contains("not an economic verdict")
                .doesNotContain("daily closes");
    }

    @Test
    void explicitUniverseSkipsNonOptionableWithReason() {
        AutoRecommender.AutoRequest request = new AutoRecommender.AutoRequest(
                List.of("AAPL", "VTSAX", "ZZZZ"), List.of("week", "month"), 3, null, null, null, null,
                "balanced", false, List.of("DIRECTIONAL"), null, null);
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

        // A directional scan expresses a market VIEW: with a bullish override a same-day long call /
        // debit spread builds, which is what this test checks. (Share-backed families like covered
        // calls no longer pad a directional fan, so the view must actually produce a directional
        // structure — the honest behavior.)
        AutoRecommender.AutoRequest spyOnly = new AutoRecommender.AutoRequest(
                List.of("SPY"), List.of("0DTE"), 1, null, null, null, null,
                "aggressive", true, List.of("DIRECTIONAL"), null, "bullish");
        AutoRecommender.AutoResult with = auto.run(spyOnly, BP);
        assertThat(with.picks()).hasSize(1);
        AutoRecommender.HorizonIdeas zeroDte = with.picks().getFirst().horizons().getFirst();
        assertThat(zeroDte.horizon()).isEqualTo("0DTE");
        assertThat(zeroDte.candidates()).isNotEmpty();
        for (AutoRecommender.ScoredCandidate sc : zeroDte.candidates()) {
            for (LegView leg : sc.evaluation().candidate().legs()) {
                if (leg.expiration() != null) assertThat(LocalDate.parse(leg.expiration())).isEqualTo(TODAY);
            }
        }
    }

    @Test
    void observedZeroDteUsesEasternDateWhenTheHostClockIsStillOnThePriorPhoenixDay() {
        Instant boundary = Instant.parse("2026-07-09T04:30:00Z");
        Clock phoenixClock = Clock.fixed(boundary, ZoneId.of("America/Phoenix"));
        // The deterministic exchange fixture lists against the US market date. Keeping its zone
        // Eastern isolates the assertion: the scan clock's zone must not filter that valid book.
        Clock exchangeClock = Clock.fixed(boundary, MarketHours.EASTERN);
        AutoRecommender boundaryAuto = autoFor(new FixtureProvider(exchangeClock), phoenixClock, null);

        AutoRecommender.AutoResult result = boundaryAuto.run(zeroDteSpyRequest(), BP);

        assertZeroDteExpirations(result, LocalDate.of(2026, 7, 9));
    }

    @Test
    void simulatedZeroDteUsesTheSelectedWorldDateInsteadOfTheHostDate() {
        SimulatedWorld world = new SimulatedWorld(new SimulatedWorld.Config(
                "sim-auto-clock", "Clock world", Map.of("SPY", 1.0),
                Map.of("SPY", 562.10), "CALM", 0.20, 77L,
                "2026-07-10T10:00:00", 1.0, null, null));
        FixtureProvider observedFixture = new FixtureProvider(CLOCK);
        AutoRecommender simulatedAuto = autoFor(observedFixture, CLOCK, world);

        AutoRecommender.AutoResult result = simulatedAuto.run(
                zeroDteSpyRequest(), BP, List.of(), "sim-auto-clock");

        assertZeroDteExpirations(result, LocalDate.of(2026, 7, 10));
    }

    private AutoRecommender autoFor(FixtureProvider fixture, Clock deskClock, SimulatedWorld world) {
        MarketDataService market = new MarketDataService(List.of(fixture), List.of(fixture), List.of(fixture));
        if (world != null) {
            market.setWorldResolver(id -> world.config().worldId().equals(id)
                    ? java.util.Optional.of(world) : java.util.Optional.empty());
        }
        AppConfig cfg = new AppConfig(Map.of("FIXTURES_ONLY", "true"));
        RecommendationEngine engine = new RecommendationEngine(market, deskClock);
        return new AutoRecommender(new SignalEngine(market, deskClock), engine,
                new EvaluationService(market, db, deskClock), cfg);
    }

    private static AutoRecommender.AutoRequest zeroDteSpyRequest() {
        return new AutoRecommender.AutoRequest(
                List.of("SPY"), List.of("0DTE"), 1, null, null, null, null,
                "aggressive", true, List.of("DIRECTIONAL"), null, "bullish");
    }

    private static void assertZeroDteExpirations(AutoRecommender.AutoResult result, LocalDate date) {
        assertThat(result.picks()).hasSize(1);
        AutoRecommender.HorizonIdeas horizon = result.picks().getFirst().horizons().getFirst();
        assertThat(horizon.horizon()).isEqualTo("0DTE");
        assertThat(horizon.candidates()).isNotEmpty();
        assertThat(horizon.candidates()).allSatisfy(scored ->
                assertThat(scored.evaluation().candidate().legs()).allSatisfy(leg -> {
                    if (leg.expiration() != null) {
                        assertThat(LocalDate.parse(leg.expiration())).isEqualTo(date);
                    }
                }));
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
                    assertThat(sc.evaluation().assessment().economics()).isNotNull();
                    assertThat(sc.evaluation().decisionScore()).isBetween(0.0, 100.0)
                            .isLessThanOrEqualTo(previous);
                    previous = sc.evaluation().decisionScore();
                }
            }
        }
        assertThat(sawCandidate).isTrue();
    }

    @Test
    void respectsMaxLossBudget() {
        AutoRecommender.AutoRequest request = new AutoRecommender.AutoRequest(
                null, List.of("month"), 3, null, 50_000L, null, null, "balanced", false,
                List.of("DIRECTIONAL"), null, null);
        AutoRecommender.AutoResult result = auto.run(request, BP);
        for (AutoRecommender.Pick p : result.picks()) {
            for (AutoRecommender.HorizonIdeas h : p.horizons()) {
                for (AutoRecommender.ScoredCandidate sc : h.candidates()) {
                    assertThat(sc.evaluation().candidate().maxLossCents()).isLessThanOrEqualTo(50_000L);
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
                .map(scored -> scored.evaluation().candidate())
                .filter(c -> c.strategy().equals("COVERED_CALL")).findFirst().orElseThrow();
        assertThat(cc.usesHeldShares()).isTrue();
        assertThat(cc.qty()).isEqualTo(2); // both free lots covered
    }

    @Test
    void heldIntentUsesTheSameKernelTraversalAndProgressDenominator() {
        List<AutoRecommender.Progress> frames = new ArrayList<>();
        List<AutoRecommender.HoldingInfo> holdings =
                List.of(new AutoRecommender.HoldingInfo("AAPL", 200, 20_000L));
        AutoRecommender.AutoRequest request = new AutoRecommender.AutoRequest(
                List.of("SPY", "QQQ"), List.of("month"), 3,
                null, null, null, null, "balanced", false,
                List.of("EXIT"), null, null);

        auto.runWithFrontier(request, BP, holdings, null,
                evaluations -> practiceContext(List.of("AAPL")), frames::add);

        List<AutoRecommender.Progress> signals = frames.stream()
                .filter(frame -> "SIGNALS".equals(frame.phase())).toList();
        assertThat(signals).singleElement().satisfies(frame -> {
            assertThat(frame.symbol()).isEqualTo("AAPL");
            assertThat(frame.phaseCompleted()).isEqualTo(1);
            assertThat(frame.phaseTotal()).isEqualTo(1);
            assertThat(frame.counts().universeConsidered()).isEqualTo(1);
        });
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
            assertThat(p.opportunity().goal()).isEqualTo("INCOME");
            assertThat(p.opportunity().summary()).containsAnyOf("premium", "IV-versus-realized");
            assertThat(p.bestIdea().available()).isTrue();
            assertThat(p.bestIdea().family()).isNotBlank();
            assertThat(p.bestIdea().economicVerdict())
                    .isIn("FAVORABLE", "MIXED", "UNFAVORABLE", "UNAVAILABLE");
            for (AutoRecommender.HorizonIdeas h : p.horizons()) {
                for (AutoRecommender.ScoredCandidate sc : h.candidates()) {
                    assertThat(io.liftandshift.strikebench.strategy.StrategyFamily.valueOf(sc.evaluation().candidate().strategy())
                            .servesIntent(io.liftandshift.strikebench.strategy.StrategyIntent.INCOME)).isTrue();
                }
            }
        }
    }

    private static AutoRecommender.ScoredCandidate scored(
            io.liftandshift.strikebench.eval.EconomicAssessment economics) {
        Candidate candidate = new Candidate("TEST", "Test", "test", "test", List.of(), 1,
                TestPrices.optionOnly(1, 0), null, 1, List.of(), 1.0, "DELAYED", List.of(), 1.0,
                "test", "test", "test", "test", "test", "DIRECTIONAL", List.of("DIRECTIONAL"),
                null, null, null, null, false, null, null,
                io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.Receipt.unavailable(
                        "This policy fixture deliberately has no market-implied evaluation."));
        var score = new io.liftandshift.strikebench.eval.ScoreBreakdown(true, List.of(), 50, 50, List.of());
        var assessment = new io.liftandshift.strikebench.eval.FourOutputAssessment(
                new io.liftandshift.strikebench.eval.FourOutputAssessment.MechanicalAssessment(true, List.of()),
                economics,
                new io.liftandshift.strikebench.eval.FourOutputAssessment.ObjectiveCoherence(
                        io.liftandshift.strikebench.eval.FourOutputAssessment.Coherence.UNDECLARED,
                        "test", "test", List.of()),
                new io.liftandshift.strikebench.eval.FourOutputAssessment.PortfolioImpacts(
                        null, null, List.of("test")));
        var evaluation = new io.liftandshift.strikebench.eval.StrategyEvaluation("test", null, candidate,
                null, null, null, null, null, score, assessment, null, null, null, null, null, null);
        return new AutoRecommender.ScoredCandidate(null, evaluation);
    }


    @Test
    void compensationViewRanksPremiumCollectorsBesideTheDecisionOrderWithNamedComponents() {
        OpportunityScanner.ScanResult result = opportunityScanner.scan(
                List.of("AAPL", "SPY"), "INCOME", "neutral", "month", "balanced",
                BP, "local", 4, null, null);
        assertThat(result.compensationBasis())
                .contains("Premium compensation")
                .contains("never substituted")
                .contains("never replaces");
        assertThat(result.compensation()).isNotEmpty().allSatisfy(entry -> {
            assertThat(entry.score()).isBetween(0.0, 100.0);
            assertThat(entry.components()).extracting(CompensationView.CompensationComponent::name)
                    .containsAnyOf("Collateral premium yield", "Defined-risk period premium")
                    .contains("Variance risk premium", "Gap risk",
                            "Earnings proximity", "Liquidity", "Capital efficiency");
            entry.components().forEach(component ->
                    assertThat(component.note()).as(component.name() + " explains itself").isNotBlank());
        });
        // Beside, not instead: the decision-ordered ranked list is untouched by the view.
        assertThat(result.ranked()).isNotEmpty();
        var yields = result.compensation().stream()
                .map(CompensationView.CompensationEntry::score).toList();
        assertThat(yields).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    /**
     * A scanned symbol that produced no row must SAY why. It used to disappear in silence whenever
     * its packages priced and then lost every one of them to the viability screen — a different
     * fact from "nothing was built here", and the notes could not tell them apart (program §3.2).
     */
    @Test
    void everyScannedSymbolEitherSurfacesARowOrIsNamedInTheNotes() {
        List<String> universe = List.of("AAPL", "SPY", "QQQ", "IWM");
        OpportunityScanner.ScanResult result = opportunityScanner.scanWithFrontier(
                universe, "INCOME", "neutral", "month", "balanced", BP, "local", 10, null, null,
                evaluations -> practiceContext(universe));

        java.util.Set<String> surfaced = new java.util.LinkedHashSet<>();
        result.ranked().forEach(evaluation -> surfaced.add(evaluation.symbol()));
        if (result.frontier() != null) {
            result.frontier().decisionRanking().forEach(entry -> surfaced.add(entry.symbol()));
        }

        assertThat(universe).allSatisfy(symbol -> {
            if (surfaced.contains(symbol)) return;
            assertThat(result.notes())
                    .as("%s produced no row, so the scan must say why rather than drop it", symbol)
                    .anySatisfy(note -> assertThat(note).startsWith(symbol + ":"));
        });
        assertThat(result.notes()).allSatisfy(note -> assertThat(note)
                .as("a note names its symbol and its reason, never a bare symbol")
                .matches("[A-Z.]+: .+"));
    }

    /**
     * Audit §8.2: one {@code completed/total} pair cannot describe a scan, because its denominator
     * changes meaning between phases. Four independent counts do, and each of them only ever rises.
     */
    @Test
    void scanProgressReportsFourIndependentMonotonicCountsWithNoSharedDenominator() {
        List<String> universe = List.of("AAPL", "SPY", "QQQ");
        List<AutoRecommender.Progress> frames = new java.util.ArrayList<>();
        AutoRecommender.AutoResult result = auto.runWithFrontier(
                new AutoRecommender.AutoRequest(universe, List.of("month"), 1, null, null, null, null,
                        "balanced", false, List.of("INCOME"), null, null),
                BP, List.of(), null, evaluations -> practiceContext(universe), frames::add);

        assertThat(frames).extracting(AutoRecommender.Progress::phase)
                .contains("STARTING", "SIGNALS", "IDEAS", "BOOK");

        int universeConsidered = 0, evidenceEligible = 0, packagesEvaluated = 0, rowsRetained = 0;
        for (AutoRecommender.Progress frame : frames) {
            AutoRecommender.ScanCounts counts = frame.counts();
            assertThat(counts.universeConsidered()).as("universe considered is monotonic")
                    .isGreaterThanOrEqualTo(universeConsidered);
            assertThat(counts.evidenceEligible()).as("evidence-eligible is monotonic")
                    .isGreaterThanOrEqualTo(evidenceEligible);
            assertThat(counts.packagesEvaluated()).as("packages evaluated is monotonic")
                    .isGreaterThanOrEqualTo(packagesEvaluated);
            assertThat(counts.rowsRetained()).as("rows retained is monotonic")
                    .isGreaterThanOrEqualTo(rowsRetained);
            universeConsidered = counts.universeConsidered();
            evidenceEligible = counts.evidenceEligible();
            packagesEvaluated = counts.packagesEvaluated();
            rowsRetained = counts.rowsRetained();
        }

        int signalsTotal = frames.stream().filter(frame -> "SIGNALS".equals(frame.phase()))
                .mapToInt(AutoRecommender.Progress::phaseTotal).max().orElseThrow();
        int ideasTotal = frames.stream().filter(frame -> "IDEAS".equals(frame.phase()))
                .mapToInt(AutoRecommender.Progress::phaseTotal).max().orElseThrow();
        assertThat(signalsTotal).isEqualTo(universe.size());
        assertThat(ideasTotal).as("the phase denominator is phase-local, never one scan-wide total")
                .isEqualTo(1).isNotEqualTo(signalsTotal);

        AutoRecommender.ScanCounts finalCounts = frames.getLast().counts();
        assertThat(finalCounts.universeConsidered()).isEqualTo(universe.size());
        assertThat(finalCounts.evidenceEligible()).isPositive()
                .isLessThanOrEqualTo(finalCounts.universeConsidered());
        assertThat(finalCounts.packagesEvaluated()).isGreaterThanOrEqualTo(finalCounts.rowsRetained());
        assertThat(finalCounts.rowsRetained()).isPositive()
                .isEqualTo(AutoRecommender.surfaced(result).size())
                .isEqualTo(result.frontier().decisionRanking().size());
        assertThat(result.counts()).isEqualTo(finalCounts);
    }

    @Test
    void ideaProgressDenominatorCountsTheWorkActuallyScheduled() {
        List<AutoRecommender.Progress> frames = new ArrayList<>();
        auto.runWithFrontier(
                new AutoRecommender.AutoRequest(List.of("AAPL"), List.of("month"), 5,
                        null, null, null, null, "balanced", false,
                        List.of("INCOME", "DIRECTIONAL"), null, null),
                BP, List.of(), null, evaluations -> practiceContext(List.of("AAPL")), frames::add);

        List<AutoRecommender.Progress> ideas = frames.stream()
                .filter(frame -> "IDEAS".equals(frame.phase())).toList();
        assertThat(ideas).hasSize(2);
        assertThat(ideas).allSatisfy(frame -> assertThat(frame.phaseTotal())
                .as("one eligible symbol × two declared goals, not maxPicks × goals")
                .isEqualTo(2));
        assertThat(ideas.getLast().phaseCompleted()).isEqualTo(2);
        assertThat(frames.getLast().phaseTotal()).isEqualTo(2);
    }

    @Test
    void parallelSignalWorkNeverInvokesProgressListenerConcurrentlyOrOutOfOrder() {
        List<String> universe = List.of("AAPL", "SPY", "QQQ");
        var firstSignal = new java.util.concurrent.atomic.AtomicBoolean(true);
        var firstEntered = new java.util.concurrent.CountDownLatch(1);
        var anotherEntered = new java.util.concurrent.CountDownLatch(1);
        var overlapped = new java.util.concurrent.atomic.AtomicBoolean(false);
        List<AutoRecommender.Progress> frames =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        auto.runWithFrontier(
                new AutoRecommender.AutoRequest(universe, List.of("month"), 1, null, null, null, null,
                        "balanced", false, List.of("INCOME"), null, null),
                BP, List.of(), null, evaluations -> practiceContext(universe), frame -> {
                    if ("SIGNALS".equals(frame.phase())) {
                        if (firstSignal.compareAndSet(true, false)) {
                            firstEntered.countDown();
                            try {
                                overlapped.set(anotherEntered.await(
                                        1, java.util.concurrent.TimeUnit.SECONDS));
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(interrupted);
                            }
                        } else if (firstEntered.getCount() == 0) {
                            anotherEntered.countDown();
                        }
                    }
                    frames.add(frame);
                });

        assertThat(overlapped.get()).as("progress callbacks are serialized after parallel signal work")
                .isFalse();
        int considered = 0;
        int eligible = 0;
        for (AutoRecommender.Progress frame : frames) {
            assertThat(frame.counts().universeConsidered()).isGreaterThanOrEqualTo(considered);
            assertThat(frame.counts().evidenceEligible()).isGreaterThanOrEqualTo(eligible);
            considered = frame.counts().universeConsidered();
            eligible = frame.counts().evidenceEligible();
        }
    }

    /**
     * Audit §8.2: two genuinely different structures on one symbol are two results. Keeping only
     * the symbol's single best silently discarded the alternative the user never got to see.
     */
    @Test
    void opportunityScanRetainsAlternativeStructuresOnTheSameSymbol() {
        OpportunityScanner.ScanResult result = opportunityScanner.scanWithFrontier(
                List.of("AAPL"), "INCOME", "neutral", "month", "balanced", BP, "local", 5, null, null,
                evaluations -> practiceContext(List.of("AAPL")));

        assertThat(result.frontier().decisionRanking()).hasSizeGreaterThan(1);
        assertThat(result.frontier().decisionRanking())
                .extracting(RedeploymentFrontier.Entry::symbol).containsOnly("AAPL");
        assertThat(result.frontier().decisionRanking())
                .extracting(RedeploymentFrontier.Entry::strategy).doesNotHaveDuplicates();
        assertThat(result.frontier().decisionRanking())
                .extracting(entry -> entry.identity().key()).doesNotHaveDuplicates();
        assertThat(result.ranked())
                .as("portfolio construction still proposes one structure per symbol").hasSize(1);
        assertThat(db.query("SELECT COUNT(*) n FROM strategy_evaluation WHERE symbol='AAPL' "
                        + "AND world_id IS NULL", r -> r.lng("n")).getFirst())
                .as("every retained row stays adoptable, not only the allocated one")
                .isGreaterThanOrEqualTo(result.frontier().decisionRanking().size());
    }

    private static RedeploymentFrontier.Context practiceContext(List<String> universe) {
        return new RedeploymentFrontier.Context(
                new RedeploymentFrontier.UniverseScope("WATCHLIST", "Selected watchlist", universe),
                "practice",
                List.of(new RedeploymentFrontier.BookLane("PRACTICE", "practice", "Practice",
                        (io.liftandshift.strikebench.eval.PortfolioExposureContext) null,
                        null, null, 0L, "SYSTEM_CALCULATED")),
                null);
    }

    @Test
    void redeploymentFrontierKeepsEconomicsCompensationAndBookPolicySeparate() {
        AutoRecommender.AutoResult raw = auto.run(new AutoRecommender.AutoRequest(
                List.of("AAPL", "SPY"), List.of("month"), 2, null, null, null, null,
                "balanced", false, List.of("INCOME"), null, null), BP);
        List<io.liftandshift.strikebench.eval.StrategyEvaluation> evaluations = raw.picks().stream()
                .flatMap(pick -> pick.horizons().stream())
                .flatMap(horizon -> horizon.candidates().stream())
                .map(AutoRecommender.ScoredCandidate::evaluation).toList();
        assertThat(evaluations).isNotEmpty();
        String symbol = evaluations.getFirst().symbol();
        var practiceExposure = new io.liftandshift.strikebench.eval.PortfolioExposureContext(
                io.liftandshift.strikebench.position.PositionDomain.ExecutionLane.PRACTICE,
                0, 0, 0, true, "exact Practice exposure");
        var realExposure = new io.liftandshift.strikebench.eval.PortfolioExposureContext(
                io.liftandshift.strikebench.position.PositionDomain.ExecutionLane.REAL,
                0, 0, 0, true, "tracked exposure receipt");
        var hardLimit = new io.liftandshift.strikebench.paper.AccountObjectiveService.AccountCapacityPolicy(
                List.of(new io.liftandshift.strikebench.paper.AccountObjectiveService.ScopedCeiling(
                        symbol, 1L,
                        io.liftandshift.strikebench.paper.AccountObjectiveService.Enforcement.HARD)),
                List.of(), List.of(), null);
        var lanes = List.of(
                new RedeploymentFrontier.BookLane("PRACTICE", "practice", "Practice",
                        practiceExposure, null, null, 0L, "SYSTEM_CALCULATED"),
                new RedeploymentFrontier.BookLane("REAL", "tracked", "Tracked",
                        realExposure, null, hardLimit, 0L, "MODEL_DERIVED"));
        var universe = new RedeploymentFrontier.UniverseScope(
                "ACTIVE", "Current universe", List.of("AAPL", "SPY"));

        var result = RedeploymentFrontier.compose(evaluations, raw.compensation(),
                new RedeploymentFrontier.Context(universe, "tracked", lanes, null));

        assertThat(result.decisionRanking()).hasSameSizeAs(evaluations);
        assertThat(result.compensationRanking()).containsExactlyElementsOf(raw.compensation());
        assertThat(result.compensationBasis()).contains("never replaces");
        assertThat(result.decisionRanking()).allSatisfy(entry -> {
            assertThat(entry.dataCompleteness().basis()).contains("existing input");
            assertThat(entry.bookImpacts()).extracting(RedeploymentFrontier.LaneImpact::lane)
                    .containsExactly("PRACTICE", "REAL");
        });
        assertThat(result.decisionRanking().stream()
                .filter(entry -> entry.symbol().equals(symbol)).toList())
                .allSatisfy(entry -> assertThat(entry.qualification()).isEqualTo("ACCOUNT_BLOCKED"));

        var source = new RedeploymentFrontier.RedeploymentSource("pldr_test", "tracked", symbol,
                "CLOSE_ALL", 1, 4_700L, 1L, -10_000L, null, null,
                "EXECUTABLE", "frozen lifecycle action");
        var redeployment = RedeploymentFrontier.compose(evaluations, raw.compensation(),
                new RedeploymentFrontier.Context(universe, "tracked", lanes, source));
        assertThat(redeployment.notes()).anyMatch(note -> note.contains("Capital optionality is restored"));
        assertThat(redeployment.decisionRanking()).allSatisfy(entry ->
                assertThat(entry.replacement().status()).isEqualTo("DOES_NOT_QUALIFY"));
    }
}
