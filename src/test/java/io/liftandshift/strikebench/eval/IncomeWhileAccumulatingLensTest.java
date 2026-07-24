package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.position.PositionDomain;
import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.recommend.LegView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The first composite-objective lens: declared income WITH an appetite for share-adding
 * assignment. Deep-discount puts earn an entry-discount component; income carries the
 * "if repeatable" honesty label with the redeploy alternative; covered upside and
 * concentration get explicit cautions. Undeclared or plain-income contexts are untouched.
 */
class IncomeWhileAccumulatingLensTest {

    private final StrategyEvaluator evaluator = new StrategyEvaluator();

    private Candidate cashSecuredPut(String strike) {
        List<LegView> legs = List.of(
                new LegView("SELL", "PUT", strike, "2026-08-21", 1, "3.50", 100, "OPEN"));
        return new Candidate("CASH_SECURED_PUT", "Cash-secured put", "acquisition_income",
                "SELL " + strike + "P Aug21", legs, 1, 35_000L, 35_000L,
                Math.round(Double.parseDouble(strike) * 100) * 100L - 35_000L, List.of(),
                0.60, 1_800L, 0.70, "DELAYED", List.of(), 0.6,
                "Get paid to bid below the market", "Keep the premium", "Assigned in a selloff",
                "A crash through the strike", "You collect premium up front",
                "ACQUIRE", List.of("INCOME", "ACQUIRE"),
                0.35, 5.1, null, null, false, null, null);
    }

    private Candidate coveredCall() {
        List<LegView> legs = List.of(
                new LegView("BUY", "STOCK", null, null, 1, "252.00", 100, "OPEN"),
                new LegView("SELL", "CALL", "260", "2026-08-21", 1, "3.00", 100, "OPEN"));
        return new Candidate("COVERED_CALL", "Covered call", "covered_income", "BUY 100sh / SELL 260C",
                legs, 1, 30_000L, 33_000L, 2_490_000L, List.of(),
                0.55, 1_500L, 0.70, "DELAYED", List.of(), 0.6,
                "Income against shares", "Premium plus gains to 260", "Shares keep downside",
                "Runs far above 260", "You collect premium",
                "EXIT", List.of("INCOME", "EXIT"),
                0.40, 4.2, null, null, false, null, null);
    }

    private EvalContext ctx(DeclaredObjective declared, PortfolioExposureContext exposure) {
        return new EvalContext("AAPL", 25_200L, java.time.LocalDate.parse("2026-07-22"), 30, 0.30, 0.25,
                List.of(0.22, 0.26, 0.30, 0.34, 0.38, 0.29),
                10_000_000L, true, 65, 0, 0.04,
                io.liftandshift.strikebench.model.DataEvidence.of("treasury",
                        io.liftandshift.strikebench.model.Freshness.EOD), exposure, declared);
    }

    private static DeclaredObjective incomeWhileAccumulating() {
        return new DeclaredObjective("INCOME", null, 21, "PREFER_BELOW_BASIS", "this account's declared objective");
    }

    private StrategyEvaluation evaluate(Candidate c, EvalContext context) {
        return evaluator.evaluate(c,
                new StrategySpec("AAPL", c.strategy(), c.intent(), "month", null, "balanced", "decision"),
                context);
    }

    @Test void deepDiscountPutsEarnTheEntryDiscountComponent() {
        var shallow = evaluate(cashSecuredPut("247"), ctx(incomeWhileAccumulating(), null));
        var deep = evaluate(cashSecuredPut("214"), ctx(incomeWhileAccumulating(), null));
        var shallowComponent = shallow.score().components().stream()
                .filter(k -> k.name().equals("Accumulation entry discount")).findFirst().orElseThrow();
        var deepComponent = deep.score().components().stream()
                .filter(k -> k.name().equals("Accumulation entry discount")).findFirst().orElseThrow();
        assertThat(deepComponent.value()).isGreaterThan(shallowComponent.value());
        assertThat(deepComponent.note()).contains("below the current price").contains("paid to bid");
    }

    @Test void incomeCarriesTheIfRepeatableHonestyLabelAndRedeployAlternative() {
        var e = evaluate(cashSecuredPut("240"), ctx(incomeWhileAccumulating(), null));
        assertThat(e.explanation().failureModes()).anySatisfy(line -> assertThat(line)
                .contains("IF this cycle is repeatable")
                .contains("Redeployed"));
    }

    @Test void rentingOutUpsideOfAccumulatedSharesDrawsTheNavErosionCaution() {
        var e = evaluate(coveredCall(), ctx(incomeWhileAccumulating(), null));
        assertThat(e.explanation().failureModes()).anySatisfy(line -> assertThat(line)
                .contains("SELL shares you declared you are building"));
    }

    @Test void assignmentThatConcentratesTheBookIsNamed() {
        var exposure = new PortfolioExposureContext(PositionDomain.ExecutionLane.REAL,
                5_000_000L, 4_000_000L, 1_500_000L, true, "tracked account marks");
        var e = evaluate(cashSecuredPut("240"), ctx(incomeWhileAccumulating(), exposure));
        assertThat(e.explanation().failureModes()).anySatisfy(line -> assertThat(line)
                .contains("Concentration")
                .contains("gross exposure"));
    }

    @Test void plainIncomeWithoutAccumulationAppetiteGetsNoLens() {
        DeclaredObjective plainIncome = new DeclaredObjective("INCOME", null, 21, "AVOID", "test");
        var e = evaluate(cashSecuredPut("240"), ctx(plainIncome, null));
        assertThat(e.score().components())
                .noneMatch(k -> k.name().equals("Accumulation entry discount"));
        assertThat(e.explanation().failureModes())
                .noneMatch(line -> line.contains("IF this cycle is repeatable"));
    }

    @Test void undeclaredContextsAreCompletelyUntouched() {
        var e = evaluate(cashSecuredPut("240"), ctx(null, null));
        assertThat(e.score().components())
                .noneMatch(k -> k.name().equals("Accumulation entry discount"));
    }
}
