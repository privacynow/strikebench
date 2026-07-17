package io.liftandshift.strikebench.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The headline diagnostic: the declared objective against the implied stance, both axes. */
class ObjectiveCoherenceTest {

    private static ImpliedStance implied(ImpliedStance.Direction d, ImpliedStance.Shape vol,
                                         ImpliedStance.Carry carry, ImpliedStance.Tail tail) {
        return new ImpliedStance(d, ImpliedStance.Shape.FLAT, vol, carry, tail, "label", "summary");
    }

    private static StanceVector stance(int durationCalendarDays) {
        return new StanceVector(100_00, 0, 0, 0, null, null, null, null, durationCalendarDays);
    }

    private static DeclaredObjective declared(String objective, String thesis, Integer sessions) {
        return new DeclaredObjective(objective, thesis, sessions, null, "test declaration");
    }

    @Test void matchingDirectionAndCoveredHorizonIsCoherent() {
        var verdict = StrategyEvaluator.objectiveCoherence(
                declared("DIRECTIONAL", "bullish", 21),
                implied(ImpliedStance.Direction.BULLISH, ImpliedStance.Shape.FLAT,
                        ImpliedStance.Carry.FLAT, ImpliedStance.Tail.LIMITED),
                stance(35));
        assertThat(verdict.verdict()).isEqualTo(FourOutputAssessment.Coherence.COHERENT);
    }

    @Test void oppositeDirectionIsIncoherentAndSaysSoPlainly() {
        var verdict = StrategyEvaluator.objectiveCoherence(
                declared("DIRECTIONAL", "bullish", 21),
                implied(ImpliedStance.Direction.BEARISH, ImpliedStance.Shape.FLAT,
                        ImpliedStance.Carry.FLAT, ImpliedStance.Tail.LIMITED),
                stance(35));
        assertThat(verdict.verdict()).isEqualTo(FourOutputAssessment.Coherence.INCOHERENT);
        assertThat(verdict.directionAssessment()).contains("bullish").contains("bearish");
    }

    @Test void neutralDeclaredAgainstDirectionalPositionIsMixedNotWrong() {
        var verdict = StrategyEvaluator.objectiveCoherence(
                declared("DIRECTIONAL", "neutral", 21),
                implied(ImpliedStance.Direction.BULLISH, ImpliedStance.Shape.FLAT,
                        ImpliedStance.Carry.FLAT, ImpliedStance.Tail.LIMITED),
                stance(35));
        assertThat(verdict.verdict()).isEqualTo(FourOutputAssessment.Coherence.MIXED);
    }

    @Test void volatileViewAgainstShortVolatilityIsIncoherent() {
        var verdict = StrategyEvaluator.objectiveCoherence(
                declared("DIRECTIONAL", "volatile", 21),
                implied(ImpliedStance.Direction.NEUTRAL, ImpliedStance.Shape.SHORT,
                        ImpliedStance.Carry.POSITIVE, ImpliedStance.Tail.BOTH),
                stance(35));
        assertThat(verdict.verdict()).isEqualTo(FourOutputAssessment.Coherence.INCOHERENT);
        assertThat(verdict.directionAssessment()).containsIgnoringCase("short volatility");
    }

    @Test void incomeWithNegativeCarryIsIncoherentOnTheCarryAxis() {
        var verdict = StrategyEvaluator.objectiveCoherence(
                declared("INCOME", null, 21),
                implied(ImpliedStance.Direction.NEUTRAL, ImpliedStance.Shape.FLAT,
                        ImpliedStance.Carry.NEGATIVE, ImpliedStance.Tail.LIMITED),
                stance(20));
        assertThat(verdict.verdict()).isEqualTo(FourOutputAssessment.Coherence.INCOHERENT);
        assertThat(verdict.directionAssessment()).containsIgnoringCase("carry");
    }

    @Test void incomeCyclesShorterThanTheObjectiveHorizonStayCoherent() {
        var verdict = StrategyEvaluator.objectiveCoherence(
                declared("INCOME", null, 63),
                implied(ImpliedStance.Direction.NEUTRAL, ImpliedStance.Shape.FLAT,
                        ImpliedStance.Carry.POSITIVE, ImpliedStance.Tail.LIMITED),
                stance(30));
        assertThat(verdict.verdict()).isEqualTo(FourOutputAssessment.Coherence.COHERENT);
        assertThat(verdict.durationAssessment()).containsIgnoringCase("cycle");
    }

    @Test void hedgeWhosePrimaryTailIsDownsideIsIncoherent() {
        var verdict = StrategyEvaluator.objectiveCoherence(
                declared("HEDGE", null, 21),
                implied(ImpliedStance.Direction.NEUTRAL, ImpliedStance.Shape.FLAT,
                        ImpliedStance.Carry.FLAT, ImpliedStance.Tail.DOWNSIDE),
                stance(35));
        assertThat(verdict.verdict()).isEqualTo(FourOutputAssessment.Coherence.INCOHERENT);
    }

    @Test void structureExpiringBeforeTheDeclaredHorizonIsIncoherentOnDuration() {
        var verdict = StrategyEvaluator.objectiveCoherence(
                declared("DIRECTIONAL", "bullish", 42), // ~59 calendar days
                implied(ImpliedStance.Direction.BULLISH, ImpliedStance.Shape.FLAT,
                        ImpliedStance.Carry.FLAT, ImpliedStance.Tail.LIMITED),
                stance(10));
        assertThat(verdict.verdict()).isEqualTo(FourOutputAssessment.Coherence.INCOHERENT);
        assertThat(verdict.durationAssessment()).contains("before the thesis");
    }

    @Test void payingForFarMoreTimeThanTheViewIsMixed() {
        var verdict = StrategyEvaluator.objectiveCoherence(
                declared("DIRECTIONAL", "bullish", 10), // ~14 calendar days
                implied(ImpliedStance.Direction.BULLISH, ImpliedStance.Shape.FLAT,
                        ImpliedStance.Carry.FLAT, ImpliedStance.Tail.LIMITED),
                stance(120));
        assertThat(verdict.verdict()).isEqualTo(FourOutputAssessment.Coherence.MIXED);
    }

    @Test void nothingDeclaredStaysHonestlyUndeclared() {
        var verdict = StrategyEvaluator.objectiveCoherence(null,
                implied(ImpliedStance.Direction.BULLISH, ImpliedStance.Shape.FLAT,
                        ImpliedStance.Carry.FLAT, ImpliedStance.Tail.LIMITED),
                stance(30));
        assertThat(verdict.verdict()).isEqualTo(FourOutputAssessment.Coherence.UNDECLARED);
    }

    @Test void missingStanceWithholdsInsteadOfGuessing() {
        var verdict = StrategyEvaluator.objectiveCoherence(
                declared("DIRECTIONAL", "bullish", 21), null, null);
        assertThat(verdict.verdict()).isEqualTo(FourOutputAssessment.Coherence.UNAVAILABLE);
        assertThat(verdict.reasons()).anySatisfy(r -> assertThat(r).containsIgnoringCase("withheld"));
    }

    @Test void reasonsQuoteBothSidesForTheUser() {
        var verdict = StrategyEvaluator.objectiveCoherence(
                declared("DIRECTIONAL", "bullish", 21),
                implied(ImpliedStance.Direction.BEARISH, ImpliedStance.Shape.FLAT,
                        ImpliedStance.Carry.FLAT, ImpliedStance.Tail.LIMITED),
                stance(35));
        assertThat(String.join(" ", verdict.reasons()))
                .contains("test declaration")
                .contains("Economics are unchanged");
        assertThat(verdict.reasons()).hasSizeGreaterThanOrEqualTo(2);
    }
}
