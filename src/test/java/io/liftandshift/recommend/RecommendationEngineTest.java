package io.liftandshift.recommend;

import io.liftandshift.market.MarketDataService;
import io.liftandshift.market.providers.FixtureProvider;
import io.liftandshift.strategy.StrategyFamily;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationEngineTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T15:30:00Z"), ZoneId.of("America/New_York"));
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 8);
    private static final long BP = 10_000_000L; // $100k

    private RecommendationEngine engine;

    @BeforeEach
    void setUp() {
        FixtureProvider fixture = new FixtureProvider(CLOCK);
        MarketDataService market = new MarketDataService(List.of(fixture), List.of(fixture), List.of(fixture));
        engine = new RecommendationEngine(market, CLOCK);
    }

    private static RecommendationEngine.Request req(String symbol, String thesis, String horizon, String mode) {
        return new RecommendationEngine.Request(symbol, thesis, horizon, mode, null, null, null, null, true, false);
    }

    private static RecommendationEngine.Request intentReq(String intent, RecommendationEngine.Holdings holdings,
                                                          RecommendationEngine.Filters filters) {
        return new RecommendationEngine.Request("AAPL", null, "month", "balanced", null, null, null, null,
                true, false, intent, holdings, filters);
    }

    @Test
    void incomeIntentSelectsIncomeFamiliesWithYieldAndAssignmentMetrics() {
        RecommendationEngine.Holdings h = new RecommendationEngine.Holdings(100, 20_000L, null);
        RecommendationEngine.Result result = engine.recommend(intentReq("income", h, null), BP);
        assertThat(result.intent()).isEqualTo("INCOME");
        assertThat(result.candidates()).isNotEmpty();
        for (Candidate c : result.candidates()) {
            StrategyFamily family = StrategyFamily.valueOf(c.strategy());
            assertThat(family.servesIntent(io.liftandshift.strategy.StrategyIntent.INCOME)).isTrue();
            assertThat(c.intent()).isEqualTo("INCOME");
            if (c.entryNetPremiumCents() > 0 && !family.multiExpiration()) {
                assertThat(c.assignmentProb()).isNotNull().isBetween(0.0, 1.0);
                assertThat(c.intentNote()).contains("Collect");
            }
        }
        // Covered call against the held shares: share-backed yield, sane magnitude (not a
        // four-digit annualized best case — that bug is pinned here)
        Candidate cc = result.candidates().stream()
                .filter(c -> c.strategy().equals("COVERED_CALL")).findFirst().orElseThrow();
        assertThat(cc.usesHeldShares()).isTrue();
        assertThat(cc.annualizedYieldPct()).isNotNull().isBetween(1.0, 100.0);
        // Defined-risk spreads never quote an annualized "yield" — R:R covers that honestly
        result.candidates().stream()
                .filter(c -> !StrategyFamily.valueOf(c.strategy()).needsStock()
                        && !c.strategy().equals("CASH_SECURED_PUT"))
                .forEach(c -> assertThat(c.annualizedYieldPct()).isNull());
    }

    @Test
    void exitIntentWritesCoveredCallsAgainstHeldSharesAtTheTargetPrice() {
        // User story: holds 300 AAPL from $200, wants out at $260+
        RecommendationEngine.Holdings h = new RecommendationEngine.Holdings(300, 20_000L, 26_000L);
        RecommendationEngine.Result result = engine.recommend(intentReq("exit", h, null), BP);
        Candidate cc = result.candidates().stream()
                .filter(c -> c.strategy().equals("COVERED_CALL")).findFirst().orElseThrow();
        assertThat(cc.usesHeldShares()).isTrue();
        assertThat(cc.legs()).allSatisfy(l -> assertThat(l.type()).isNotEqualTo("STOCK")); // no stock buy — uses held shares
        assertThat(cc.qty()).isEqualTo(3); // covers all 300 shares
        assertThat(cc.sharesNeeded()).isEqualTo(300);
        assertThat(cc.maxLossCents()).isZero(); // no NEW cash at risk
        assertThat(cc.combinedMaxLossCents()).isPositive(); // but the shares' own downside is disclosed
        assertThat(cc.entryNetPremiumCents()).isPositive();
        // Short strike honors the target sell price
        double strike = Double.parseDouble(cc.legs().getFirst().strike());
        assertThat(strike).isGreaterThanOrEqualTo(260.0);
        assertThat(cc.effectivePrice()).isNotNull();
        assertThat(Double.parseDouble(cc.effectivePrice())).isGreaterThan(260.0);
        assertThat(cc.intentNote()).contains("sell 300 shares").contains("goal");
        assertThat(cc.assignmentProb()).isNotNull();
    }

    @Test
    void acquireIntentSellsPutsAtOrBelowTheDesiredBuyPrice() {
        RecommendationEngine.Holdings h = new RecommendationEngine.Holdings(null, null, 24_000L); // want AAPL at $240
        RecommendationEngine.Result result = engine.recommend(intentReq("acquire", h, null), BP);
        Candidate csp = result.candidates().stream()
                .filter(c -> c.strategy().equals("CASH_SECURED_PUT")).findFirst().orElseThrow();
        double strike = Double.parseDouble(csp.legs().getFirst().strike());
        assertThat(strike).isLessThanOrEqualTo(240.0);
        assertThat(csp.qty()).isEqualTo(1); // never silently scale into the whole account
        assertThat(Double.parseDouble(csp.effectivePrice())).isLessThan(strike); // strike minus premium
        assertThat(csp.intentNote()).contains("buy 100 shares").contains("below today's");
        // The blocked naked-put educational rejection should mention undefined risk
        assertThat(result.rejected()).anySatisfy(r -> assertThat(r.strategy()).isEqualTo("NAKED_PUT"));
    }

    @Test
    void hedgeIntentProtectsHeldSharesWithPutsAndCollars() {
        RecommendationEngine.Holdings h = new RecommendationEngine.Holdings(200, 21_000L, null);
        RecommendationEngine.Result result = engine.recommend(intentReq("hedge", h, null), BP);
        assertThat(result.candidates()).isNotEmpty();
        Candidate pp = result.candidates().stream()
                .filter(c -> c.strategy().equals("PROTECTIVE_PUT")).findFirst().orElseThrow();
        assertThat(pp.usesHeldShares()).isTrue();
        assertThat(pp.maxLossCents()).isPositive(); // the hedge costs its debit
        assertThat(pp.intentNote()).contains("Guarantees a sale price");
        assertThat(pp.qty()).isLessThanOrEqualTo(2); // never hedge more lots than held
        for (Candidate c : result.candidates()) {
            assertThat(StrategyFamily.valueOf(c.strategy()).servesIntent(io.liftandshift.strategy.StrategyIntent.HEDGE)).isTrue();
        }
    }

    @Test
    void filtersRejectCandidatesWithHumanReadableReasons() {
        RecommendationEngine.Filters strictPop = new RecommendationEngine.Filters(0.99, null, null, null);
        RecommendationEngine.Result r1 = engine.recommend(intentReq("income", null, strictPop), BP);
        assertThat(r1.candidates()).isEmpty();
        assertThat(r1.rejected()).anySatisfy(rej ->
                assertThat(String.join(" ", rej.reasons())).contains("below your minimum"));

        RecommendationEngine.Filters noAssignment = new RecommendationEngine.Filters(null, 0.0001, null, null);
        RecommendationEngine.Result r2 = engine.recommend(intentReq("income", null, noAssignment), BP);
        assertThat(r2.rejected()).anySatisfy(rej ->
                assertThat(String.join(" ", rej.reasons())).contains("Assignment probability"));

        RecommendationEngine.Filters richYield = new RecommendationEngine.Filters(null, null, 500.0, null);
        RecommendationEngine.Holdings h = new RecommendationEngine.Holdings(100, 20_000L, null);
        RecommendationEngine.Result r3 = engine.recommend(intentReq("income", h, richYield), BP);
        assertThat(r3.candidates()).isEmpty();
        assertThat(r3.rejected()).anySatisfy(rej ->
                assertThat(String.join(" ", rej.reasons())).containsAnyOf("yield", "minimum"));
    }

    @Test
    void unknownIntentIsRejectedLoudly() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                engine.recommend(intentReq("yolo", null, null), BP))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("intent");
    }

    @Test
    void bullishLearningModeReturnsOnlyBeginnerDefinedRiskStrategies() {
        RecommendationEngine.Result result = engine.recommend(req("AAPL", "bullish", "month", "learning"), BP);
        assertThat(result.candidates()).isNotEmpty();
        Set<String> allowed = Set.of("LONG_CALL", "DEBIT_CALL_SPREAD", "COVERED_CALL");
        for (Candidate c : result.candidates()) {
            assertThat(allowed).contains(c.strategy());
            StrategyFamily family = StrategyFamily.valueOf(c.strategy());
            assertThat(family.definedRisk()).isTrue();
            assertThat(c.maxLossCents()).isPositive().isLessThanOrEqualTo(result.riskBudgetCents());
            assertThat(c.legs()).isNotEmpty();
            assertThat(c.whyConsidered()).isNotBlank();
            assertThat(c.bestUpside()).isNotBlank();
            assertThat(c.biggestRisk()).isNotBlank();
            assertThat(c.wouldInvalidate()).isNotBlank();
            assertThat(c.beginnerExplanation()).isNotBlank();
            assertThat(c.confidence()).isBetween(0.0, 1.0);
            // learning default risk budget = 1% of $100k = $1,000
            assertThat(result.riskBudgetCents()).isEqualTo(100_000L);
        }
        assertThat(result.disclaimer()).containsIgnoringCase("not financial advice");
    }

    @Test
    void neutralConservativeIncludesCreditStructuresAndRejectsNakedCall() {
        RecommendationEngine.Result result = engine.recommend(req("AAPL", "neutral", "month", "conservative"), BP);
        assertThat(result.candidates()).isNotEmpty();
        assertThat(result.candidates()).extracting(Candidate::strategy)
                .anyMatch(s -> s.equals("IRON_CONDOR") || s.equals("CREDIT_PUT_SPREAD") || s.equals("CREDIT_CALL_SPREAD"));
        assertThat(result.rejected()).anySatisfy(r -> {
            assertThat(r.strategy()).isEqualTo("NAKED_CALL");
            assertThat(String.join(" ", r.reasons())).containsIgnoringCase("undefined risk");
        });
    }

    @Test
    void learningModeNeverSuggestsCreditSpreadsEvenWhenNeutral() {
        RecommendationEngine.Result result = engine.recommend(req("AAPL", "neutral", "month", "learning"), BP);
        assertThat(result.candidates()).extracting(Candidate::strategy)
                .noneMatch(s -> s.contains("CREDIT") || s.equals("IRON_CONDOR") || s.equals("IRON_BUTTERFLY"));
    }

    @Test
    void zeroDteExcludedByDefault() {
        RecommendationEngine.Result result = engine.recommend(req("SPY", "neutral", "0DTE", "aggressive"), BP);
        for (Candidate c : result.candidates()) {
            for (LegView leg : c.legs()) {
                if (leg.expiration() != null) {
                    assertThat(LocalDate.parse(leg.expiration())).isAfter(TODAY);
                }
            }
        }
        assertThat(result.notes()).anySatisfy(n -> assertThat(n).containsIgnoringCase("0DTE"));
    }

    @Test
    void zeroDteAllowedWhenOptedIn() {
        RecommendationEngine.Request request = new RecommendationEngine.Request(
                "SPY", "neutral", "0DTE", "aggressive", null, null, null, null, true, true);
        RecommendationEngine.Result result = engine.recommend(request, BP);
        assertThat(result.candidates()).isNotEmpty();
        assertThat(result.candidates()).anySatisfy(c -> {
            assertThat(c.legs()).anySatisfy(l -> assertThat(l.expiration()).isEqualTo(TODAY.toString()));
            assertThat(c.warnings()).anySatisfy(w -> assertThat(w).contains("0DTE"));
        });
    }

    @Test
    void nonOptionableSymbolYieldsNoCandidates() {
        RecommendationEngine.Result result = engine.recommend(req("VTSAX", "bullish", "month", "balanced"), BP);
        assertThat(result.candidates()).isEmpty();
        assertThat(result.notes()).anySatisfy(n -> assertThat(n).containsIgnoringCase("no listed options"));
    }

    @Test
    void unknownSymbolYieldsNote() {
        RecommendationEngine.Result result = engine.recommend(req("ZZZZ", "bullish", "month", "balanced"), BP);
        assertThat(result.candidates()).isEmpty();
        assertThat(result.notes()).anySatisfy(n -> assertThat(n).containsIgnoringCase("no market data"));
    }

    @Test
    void minConfidenceFiltersEverything() {
        RecommendationEngine.Request request = new RecommendationEngine.Request(
                "AAPL", "bullish", "month", "balanced", null, null, 0.999, null, true, false);
        RecommendationEngine.Result result = engine.recommend(request, BP);
        assertThat(result.candidates()).isEmpty();
        assertThat(result.rejected()).anySatisfy(r ->
                assertThat(String.join(" ", r.reasons())).containsIgnoringCase("confidence"));
    }

    @Test
    void allowedStrategiesWhitelistRespected() {
        RecommendationEngine.Request request = new RecommendationEngine.Request(
                "AAPL", "bullish", "month", "aggressive", null, null, null, List.of("LONG_CALL"), true, false);
        RecommendationEngine.Result result = engine.recommend(request, BP);
        assertThat(result.candidates()).isNotEmpty();
        assertThat(result.candidates()).allSatisfy(c -> assertThat(c.strategy()).isEqualTo("LONG_CALL"));
    }

    @Test
    void riskBudgetScalesWithPctAndCapsQty() {
        RecommendationEngine.Request request = new RecommendationEngine.Request(
                "AAPL", "bullish", "month", "balanced", null, 0.10, null, null, true, false);
        RecommendationEngine.Result result = engine.recommend(request, BP);
        assertThat(result.riskBudgetCents()).isEqualTo(1_000_000L); // 10% of $100k
        for (Candidate c : result.candidates()) {
            assertThat(c.maxLossCents()).isLessThanOrEqualTo(result.riskBudgetCents());
            assertThat(c.qty()).isBetween(1, 5);
        }
    }

    @Test
    void candidatesAreRankedByScoreDescending() {
        RecommendationEngine.Result result = engine.recommend(req("AAPL", "neutral", "month", "aggressive"), BP);
        List<Candidate> c = result.candidates();
        assertThat(c.size()).isGreaterThanOrEqualTo(2);
        for (int i = 1; i < c.size(); i++) {
            assertThat(c.get(i - 1).score()).isGreaterThanOrEqualTo(c.get(i).score());
        }
        assertThat(c).hasSizeLessThanOrEqualTo(5);
    }

    @Test
    void volatileThesisOffersStraddlesAndStrangles() {
        // "Big move, direction unknown" must offer the canonical structures for it —
        // long straddle/strangle are first-class families, defined risk (the debit).
        RecommendationEngine.Result result = engine.recommend(req("AAPL", "volatile", "month", "balanced"), BP);
        Set<String> offered = new java.util.HashSet<>();
        for (Candidate c : result.candidates()) offered.add(c.strategy());
        assertThat(offered).containsAnyOf("LONG_STRADDLE", "LONG_STRANGLE");
        for (Candidate c : result.candidates()) {
            if (!c.strategy().equals("LONG_STRADDLE") && !c.strategy().equals("LONG_STRANGLE")) continue;
            assertThat(c.legs()).hasSize(2);
            assertThat(c.entryNetPremiumCents()).isNegative();              // both legs bought
            assertThat(c.maxLossCents()).isEqualTo(-c.entryNetPremiumCents()); // risk = the debit
            assertThat(c.maxProfitCents()).isNull();                        // uncapped either way
            assertThat(c.breakevens()).hasSize(2);                          // one below, one above
        }
    }
}
