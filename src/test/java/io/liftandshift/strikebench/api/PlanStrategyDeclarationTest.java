package io.liftandshift.strikebench.api;

import io.liftandshift.strikebench.plan.Plan;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pins the server boundary that prevents Strategy from inventing missing Plan assumptions. */
final class PlanStrategyDeclarationTest {

    @Test
    void directionalPlanCannotRankUntilDirectionHorizonAndRiskAreDeclared() {
        Plan.View incomplete = plan("DIRECTIONAL", null, null, null);

        assertThatThrownBy(() -> PlanStrategyController.requireDeclaredView(incomplete))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("direction")
                .hasMessageContaining("horizon")
                .hasMessageContaining("risk posture");
    }

    @Test
    void exactStagedDeclarationCanRankWithoutSubstitution() {
        Plan.View staged = plan("DIRECTIONAL", "bearish", 23, "balanced");

        assertThatNoException().isThrownBy(() -> PlanStrategyController.requireDeclaredView(staged));
    }

    @Test
    void everyGoalRequiresTheForwardViewItWillActuallyEvaluate() {
        assertThatThrownBy(() -> PlanStrategyController.requireDeclaredView(
                plan("INCOME", null, null, null)))
                .hasMessageContaining("direction")
                .hasMessageContaining("horizon")
                .hasMessageContaining("risk posture");
        assertThatNoException().isThrownBy(() -> PlanStrategyController.requireDeclaredView(
                plan("INCOME", "neutral", 21, "conservative")));
    }

    private static Plan.View plan(String intent, String thesis, Integer horizon, String risk) {
        Plan.ContextRevision context = new Plan.ContextRevision(
                "pctx_test", 1, thesis, horizon, null, risk,
                null, null, null, null, "hash", "test", "2026-07-17T00:00:00Z");
        return new Plan.View("plan_test", null, "AAPL", intent, Plan.MarketKind.DEMO,
                null, null, "Test", Plan.Status.ACTIVE, Plan.Stage.UNDERSTAND,
                1, true, true, context, "2026-07-17T00:00:00Z", "2026-07-17T00:00:00Z");
    }
}
