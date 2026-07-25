package io.liftandshift.strikebench.recommend;

import io.liftandshift.strikebench.eval.PortfolioExposureContext;
import io.liftandshift.strikebench.eval.StrategyEvaluation;
import io.liftandshift.strikebench.eval.StrategySpec;
import io.liftandshift.strikebench.paper.PackagePriceReceipt;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit §8.2 / program §6.2: a scan row is identified by
 * {@code symbol + strategy family + exact package + expiration + declarations} — never by symbol
 * alone — and the frontier keeps one row per identity.
 */
class ScoutResultIdentityTest {

    @Test void twoStructuresOnOneSymbolAreTwoDifferentResults() {
        StrategyEvaluation spread = evaluation("eval-spread", "IWM", "BULL_PUT_SPREAD", "INCOME",
                List.of(shortPut("210", "2026-09-18"), longPut("205", "2026-09-18")));
        StrategyEvaluation coveredCall = evaluation("eval-covered", "IWM", "COVERED_CALL", "INCOME",
                List.of(shortCall("230", "2026-09-18")));

        assertThat(ResultIdentity.of(spread).key()).isNotEqualTo(ResultIdentity.of(coveredCall).key());
        assertThat(ResultIdentity.of(spread).symbol()).isEqualTo("IWM");
        assertThat(ResultIdentity.of(spread).family()).isEqualTo("BULL_PUT_SPREAD");
        assertThat(ResultIdentity.of(spread).expiration()).isEqualTo("2026-09-18");
        assertThat(ResultIdentity.of(spread).goal()).isEqualTo("INCOME");
        assertThat(ResultIdentity.of(spread).horizonSessions()).isEqualTo(30);
    }

    @Test void identityCoversStrikesExpirationAndDeclarations() {
        StrategyEvaluation base = evaluation("eval-1", "IWM", "BULL_PUT_SPREAD", "INCOME",
                List.of(shortPut("210", "2026-09-18"), longPut("205", "2026-09-18")));

        assertThat(ResultIdentity.of(evaluation("eval-2", "IWM", "BULL_PUT_SPREAD", "INCOME",
                List.of(shortPut("212", "2026-09-18"), longPut("207", "2026-09-18")))).key())
                .as("different strikes are a different package").isNotEqualTo(ResultIdentity.of(base).key());
        assertThat(ResultIdentity.of(evaluation("eval-3", "IWM", "BULL_PUT_SPREAD", "INCOME",
                List.of(shortPut("210", "2026-10-16"), longPut("205", "2026-10-16")))).key())
                .as("different expiration is a different package").isNotEqualTo(ResultIdentity.of(base).key());
        assertThat(ResultIdentity.of(evaluation("eval-4", "IWM", "BULL_PUT_SPREAD", "DIRECTIONAL",
                List.of(shortPut("210", "2026-09-18"), longPut("205", "2026-09-18")))).key())
                .as("a different declared goal is a different question").isNotEqualTo(ResultIdentity.of(base).key());
    }

    @Test void identityIgnoresLegOrderAndPriceLevel() {
        StrategyEvaluation asRanked = evaluation("eval-a", "IWM", "BULL_PUT_SPREAD", "INCOME",
                List.of(shortPut("210", "2026-09-18"), longPut("205", "2026-09-18")));
        StrategyEvaluation repriced = evaluation("eval-b", "IWM", "BULL_PUT_SPREAD", "INCOME",
                List.of(longPut("205", "2026-09-18"), shortPut("210", "2026-09-18")),
                PackagePriceReceipt.unavailable(1, PackagePriceReceipt.FeeSide.OPENING,
                        "a later scan could not price it"));

        assertThat(ResultIdentity.of(repriced).key())
                .as("re-pricing the same contracts is the same row, not a second one")
                .isEqualTo(ResultIdentity.of(asRanked).key());
    }

    @Test void frontierRetainsBothStructuresAndCollapsesOnlyTheIdenticalRow() {
        StrategyEvaluation spread = evaluation("eval-spread", "IWM", "BULL_PUT_SPREAD", "INCOME",
                List.of(shortPut("210", "2026-09-18"), longPut("205", "2026-09-18")));
        StrategyEvaluation coveredCall = evaluation("eval-covered", "IWM", "COVERED_CALL", "INCOME",
                List.of(shortCall("230", "2026-09-18")));
        StrategyEvaluation spreadAgain = evaluation("eval-spread-again", "IWM", "BULL_PUT_SPREAD", "INCOME",
                List.of(shortPut("210", "2026-09-18"), longPut("205", "2026-09-18")));

        RedeploymentFrontier.Result frontier = RedeploymentFrontier.compose(
                List.of(spread, coveredCall, spreadAgain), List.of(), context());

        assertThat(frontier.decisionRanking()).hasSize(2);
        assertThat(frontier.decisionRanking()).extracting(RedeploymentFrontier.Entry::symbol)
                .containsExactly("IWM", "IWM");
        assertThat(frontier.decisionRanking()).extracting(RedeploymentFrontier.Entry::strategy)
                .containsExactlyInAnyOrder("BULL_PUT_SPREAD", "COVERED_CALL");
        assertThat(frontier.decisionRanking()).extracting(RedeploymentFrontier.Entry::evaluationId)
                .containsExactlyInAnyOrder("eval-spread", "eval-covered");
        assertThat(frontier.decisionRanking()).extracting(entry -> entry.identity().key())
                .doesNotHaveDuplicates();
    }

    private static RedeploymentFrontier.Context context() {
        return new RedeploymentFrontier.Context(
                new RedeploymentFrontier.UniverseScope("WATCHLIST", "Selected watchlist", List.of("IWM")),
                "acct-1",
                List.of(new RedeploymentFrontier.BookLane("PRACTICE", "acct-1", "Practice",
                        (PortfolioExposureContext) null, null, null, null, "SYSTEM_CALCULATED")),
                null);
    }

    private static StrategyEvaluation evaluation(String id, String symbol, String family,
                                                 String goal, List<LegView> legs) {
        return evaluation(id, symbol, family, goal, legs, null);
    }

    private static StrategyEvaluation evaluation(String id, String symbol, String family, String goal,
                                                 List<LegView> legs, PackagePriceReceipt price) {
        Candidate candidate = new Candidate(family, family + " package", "RANGE", "label", legs, 1,
                price, 25_000L, 75_000L, List.of("207.50"), 0.62, 1_200L, 0.8, "FIXTURE",
                List.of(), 0.7, "why", "upside", "risk", "invalidate", "explanation",
                goal, List.of(goal), 0.2, 12.5, "207.50", "note", false, 0, null);
        return new StrategyEvaluation(id,
                new StrategySpec(symbol, family, goal, "30d", "neutral", "balanced", "decision"),
                candidate, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static LegView shortPut(String strike, String expiration) {
        return new LegView("SELL", "PUT", strike, expiration, 1, "3.10", 100, "OPEN");
    }

    private static LegView longPut(String strike, String expiration) {
        return new LegView("BUY", "PUT", strike, expiration, 1, "1.85", 100, "OPEN");
    }

    private static LegView shortCall(String strike, String expiration) {
        return new LegView("SELL", "CALL", strike, expiration, 1, "2.40", 100, "OPEN");
    }
}
