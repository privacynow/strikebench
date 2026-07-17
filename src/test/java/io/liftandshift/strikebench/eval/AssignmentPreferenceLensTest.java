package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.recommend.LegView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The declared assignment preference is an objective LENS: it reweights assignment-bearing
 * structures through a named, quoted score component and never touches structures that
 * cannot be assigned. ACCEPT (or nothing declared) adds no component at all.
 */
class AssignmentPreferenceLensTest {

    private final StrategyEvaluator evaluator = new StrategyEvaluator();

    /** A covered call: short call against shares, 40% chance the shares are called away. */
    private Candidate coveredCall(Double assignmentProb) {
        List<LegView> legs = List.of(
                new LegView("BUY", "STOCK", null, null, 1, "252.00", 100, "OPEN"),
                new LegView("SELL", "CALL", "260", "2026-08-21", 1, "3.00", 100, "OPEN"));
        return new Candidate("COVERED_CALL", "Covered call", "covered_income", "BUY 100sh / SELL 260C Aug21",
                legs, 1, 30_000L, 33_000L, 2_490_000L, List.of("249.00"),
                0.55, 1_500L, 0.70, "DELAYED", List.of(),
                0.6, "Income against held shares",
                "Keep the premium plus gains to $260", "Shares keep their downside",
                "AAPL far above $260 caps the upside", "You collect $300 up front",
                "EXIT", List.of("INCOME", "EXIT"),
                assignmentProb, 4.2, null, null, false, null, null);
    }

    /** A cash-secured put: assignment BUYS shares at the strike. */
    private Candidate cashSecuredPut(double assignmentProb) {
        List<LegView> legs = List.of(
                new LegView("SELL", "PUT", "245", "2026-08-21", 1, "3.50", 100, "OPEN"));
        return new Candidate("CASH_SECURED_PUT", "Cash-secured put", "acquisition_income", "SELL 245P Aug21",
                legs, 1, 35_000L, 35_000L, 2_415_000L, List.of("241.50"),
                0.60, 1_800L, 0.70, "DELAYED", List.of(),
                0.6, "Get paid to bid below the market",
                "Keep the premium if AAPL holds above $245", "You must buy at $245 in a selloff",
                "A crash through $245", "You collect $350 up front",
                "ACQUIRE", List.of("INCOME", "ACQUIRE"),
                assignmentProb, 5.1, "241.50", null, false, null, null);
    }

    private EvalContext ctx(String assignmentPreference) {
        DeclaredObjective declared = assignmentPreference == null ? null
                : new DeclaredObjective("INCOME", null, 21, assignmentPreference, "your account objective");
        return new EvalContext("AAPL", 25_200L, java.time.LocalDate.parse("2026-07-22"), 30, 0.30, 0.25,
                List.of(0.22, 0.26, 0.30, 0.34, 0.38, 0.29),
                10_000_000L, true, 65, 0, 0.04,
                io.liftandshift.strikebench.model.DataEvidence.of("treasury",
                        io.liftandshift.strikebench.model.Freshness.EOD), null, declared);
    }

    private Optional<ScoreBreakdown.Component> assignmentFit(Candidate c, String preference) {
        StrategyEvaluation e = evaluator.evaluate(c,
                new StrategySpec("AAPL", c.strategy(), c.intent(), "month", null, "balanced", "decision"),
                ctx(preference));
        return e.score().components().stream()
                .filter(k -> k.name().equals("Assignment fit")).findFirst();
    }

    @Test void acceptAndUndeclaredAddNoComponent() {
        assertThat(assignmentFit(coveredCall(0.40), null)).isEmpty();
        assertThat(assignmentFit(coveredCall(0.40), "ACCEPT")).isEmpty();
    }

    @Test void structuresWithoutAssignmentExposureAreNeverTouched() {
        assertThat(assignmentFit(coveredCall(null), "AVOID")).isEmpty();
    }

    @Test void avoidPunishesAssignmentOdds() {
        var fit = assignmentFit(coveredCall(0.40), "AVOID").orElseThrow();
        assertThat(fit.value()).isEqualTo(0.60);
        assertThat(fit.note()).contains("avoid assignment").contains("40%");
    }

    @Test void seekRewardsAssignmentOdds() {
        var fit = assignmentFit(cashSecuredPut(0.35), "SEEK").orElseThrow();
        assertThat(fit.value()).isEqualTo(0.35);
        assertThat(fit.note()).contains("seek assignment");
    }

    @Test void preferBelowBasisDistinguishesBuyingFromLosingShares() {
        var acquiring = assignmentFit(cashSecuredPut(0.35), "PREFER_BELOW_BASIS").orElseThrow();
        assertThat(acquiring.value()).isEqualTo(0.35);
        assertThat(acquiring.note()).contains("adds shares");

        var exiting = assignmentFit(coveredCall(0.40), "PREFER_BELOW_BASIS").orElseThrow();
        assertThat(exiting.value()).isEqualTo(0.60);
        assertThat(exiting.note()).contains("sell yours");
    }

    @Test void theLensReordersAnAvoiderAwayFromHighAssignmentOdds() {
        StrategyEvaluation hot = evaluator.evaluate(coveredCall(0.90),
                new StrategySpec("AAPL", "COVERED_CALL", "EXIT", "month", null, "balanced", "decision"), ctx("AVOID"));
        StrategyEvaluation cool = evaluator.evaluate(coveredCall(0.10),
                new StrategySpec("AAPL", "COVERED_CALL", "EXIT", "month", null, "balanced", "decision"), ctx("AVOID"));
        assertThat(cool.rankScore()).isGreaterThan(hot.rankScore());
    }
}
