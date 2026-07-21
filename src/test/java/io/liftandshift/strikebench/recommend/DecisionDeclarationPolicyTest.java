package io.liftandshift.strikebench.recommend;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class DecisionDeclarationPolicyTest {

    @Test
    void missingRecommendationNamesEveryDecisionFactInsteadOfDefaulting() {
        assertThatThrownBy(() -> DecisionDeclarationPolicy.requireRecommendation(
                "Strategy recommendation", null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbol")
                .hasMessageContaining("goal (intent)")
                .hasMessageContaining("market view (thesis)")
                .hasMessageContaining("horizon")
                .hasMessageContaining("risk posture")
                .hasMessageContaining("no decision default was substituted");
    }

    @Test
    void invalidTolerantParserValuesAreRejectedAtTheBoundary() {
        var invalid = request("sideways-ish", "eventually", "mystery");

        assertThatThrownBy(() -> DecisionDeclarationPolicy.requireRecommendation(
                "Strategy recommendation", invalid, true))
                .hasMessageContaining("market view")
                .hasMessageContaining("bullish, bearish, neutral, volatile");
        assertThatThrownBy(() -> DecisionDeclarationPolicy.requireRecommendation(
                "Strategy recommendation", request("neutral", "eventually", "balanced"), true))
                .hasMessageContaining("horizon")
                .hasMessageContaining("0dte, week, month, quarter");
        assertThatThrownBy(() -> DecisionDeclarationPolicy.requireRecommendation(
                "Strategy recommendation", request("neutral", "month", "mystery"), true))
                .hasMessageContaining("risk posture");
    }

    @Test
    void completeRecommendationScoutLadderAndConstructionDeclarationsPassUnchanged() {
        var request = request("neutral", "month", "balanced");
        var scout = new AutoRecommender.AutoRequest(null, List.of("month"), 3,
                null, null, null, null, "balanced", false,
                List.of("INCOME"), null, null);

        assertThatNoException().isThrownBy(() -> DecisionDeclarationPolicy.requireRecommendation(
                "Strategy recommendation", request, true));
        assertThatNoException().isThrownBy(() -> DecisionDeclarationPolicy.requireLadder(
                "Strike ladder", request));
        assertThatNoException().isThrownBy(() -> DecisionDeclarationPolicy.requireScout(
                "Universe Scout", scout));
        assertThatNoException().isThrownBy(() -> DecisionDeclarationPolicy.requireConstruction(
                "Portfolio construction", "INCOME", "neutral", "month", "balanced", "DECISION"));
    }

    @Test
    void exactTradingSessionHorizonsPassWithoutBucketConversion() {
        var recommendation = request("neutral", "30d", "balanced");
        var scout = new AutoRecommender.AutoRequest(null, List.of("30d", "45d"), 3,
                null, null, null, null, "balanced", false,
                List.of("INCOME"), null, null);

        assertThatNoException().isThrownBy(() -> DecisionDeclarationPolicy.requireRecommendation(
                "Strategy recommendation", recommendation, true));
        assertThatNoException().isThrownBy(() -> DecisionDeclarationPolicy.requireScout(
                "Universe Scout", scout));
        assertThatThrownBy(() -> DecisionDeclarationPolicy.requireRecommendation(
                "Strategy recommendation", request("neutral", "757d", "balanced"), true))
                .hasMessageContaining("maximum 756d");
    }

    private static RecommendationEngine.Request request(String thesis, String horizon, String risk) {
        return new RecommendationEngine.Request("AAPL", thesis, horizon, risk,
                null, null, null, null, true, false, "INCOME", null, null);
    }
}
