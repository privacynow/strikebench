package io.liftandshift.strikebench.sim;

import io.liftandshift.strikebench.pricing.LognormalTerminal;
import io.liftandshift.strikebench.pricing.ExpectedMove;
import io.liftandshift.strikebench.util.Numbers;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B4 expected-move receipt: the /api/research/{symbol}/expected-move endpoint serializes exactly
 * what the typed MarketImpliedRange factories compute, and that computation is the ONE risk-neutral lognormal
 * terminal (no parallel expected-move math). Also pins the hide-on-invalid contract the cone relies
 * on (available=false rather than a fabricated band).
 */
class MarketImpliedRangeTest {

    @Test
    void ofProducesTheRiskNeutralRangeFromTheOneLognormalTerminal() {
        double spot = 100, iv = 0.4, rate = 0.04;
        int sessions = 21, calendarDays = 30;
        var r = SimulationEngine.MarketImpliedRange.forScenarioHorizon(
                spot, iv, new ExpectedMove.ScenarioHorizon(sessions),
                "2026-02-20", calendarDays, rate);
        assertThat(r).isNotNull();

        // The bounds MUST equal the same LognormalTerminal the POP/range consolidation owns.
        var term = LognormalTerminal.of(spot, iv, sessions / 252.0, rate);
        double width = term.sd() * 0.994457883209753;
        assertThat(r.p16()).isEqualTo(Numbers.round2(Math.exp(term.mu() - width)));
        assertThat(r.p50()).isEqualTo(Numbers.round2(Math.exp(term.mu())));
        assertThat(r.p84()).isEqualTo(Numbers.round2(Math.exp(term.mu() + width)));

        assertThat(r.p16()).isLessThan(r.p50());
        assertThat(r.p50()).isLessThan(r.p84());
        assertThat(r.halfWidth()).isEqualTo((r.p84() - r.p16()) / 2.0);
        assertThat(r.atmIv()).isEqualTo(iv);
        assertThat(r.horizonSessions()).isEqualTo(sessions);
        assertThat(r.expirationCalendarDays()).isEqualTo(calendarDays);
        assertThat(r.expiration()).isEqualTo("2026-02-20");
        assertThat(r.basis()).contains("Risk-neutral").contains("not a forecast");
    }

    @Test
    void theRangeStatesItsOwnMovePercentagesSoNoSurfaceReDerivesThem() {
        var r = SimulationEngine.MarketImpliedRange.forScenarioHorizon(
                100, 0.4, new ExpectedMove.ScenarioHorizon(21),
                "2026-02-20", 30, 0.04);
        assertThat(r.upMovePct(100.0)).isEqualTo(Numbers.round2((r.p84() - 100) / 100 * 100));
        assertThat(r.downMovePct(100.0)).isEqualTo(Numbers.round2((r.p16() - 100) / 100 * 100));
        assertThat(r.upMovePct(100.0)).isPositive();
        assertThat(r.downMovePct(100.0)).isNegative();
        // Without a usable anchor there is no ratio to state — and no percentage to print.
        assertThat(r.upMovePct(null)).isNull();
        assertThat(r.upMovePct(0.0)).isNull();
        assertThat(r.downMovePct(-1.0)).isNull();
    }

    @Test
    void ofHidesTheConeOnInvalidInputs() {
        var horizon = new ExpectedMove.ScenarioHorizon(21);
        assertThat(SimulationEngine.MarketImpliedRange.forScenarioHorizon(
                100, 0, horizon, "e", 30, 0.04)).isNull();        // no IV
        assertThat(SimulationEngine.MarketImpliedRange.forScenarioHorizon(
                100, -0.1, horizon, "e", 30, 0.04)).isNull();     // negative IV
        assertThat(SimulationEngine.MarketImpliedRange.forScenarioHorizon(
                100, Double.NaN, horizon, "e", 30, 0.04)).isNull();
        assertThat(SimulationEngine.MarketImpliedRange.forScenarioHorizon(
                0, 0.4, horizon, "e", 30, 0.04)).isNull();        // no spot
    }

    @Test
    void scenarioHorizonMustBeExplicitlyPositive() {
        assertThatThrownBy(() -> new ExpectedMove.ScenarioHorizon(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void higherIvWidensTheRange() {
        var horizon = new ExpectedMove.ScenarioHorizon(30);
        var calm = SimulationEngine.MarketImpliedRange.forScenarioHorizon(
                100, 0.20, horizon, "e", 45, 0.04);
        var tense = SimulationEngine.MarketImpliedRange.forScenarioHorizon(
                100, 0.80, horizon, "e", 45, 0.04);
        assertThat(tense.p84() - tense.p16()).isGreaterThan(calm.p84() - calm.p16());
    }
}
