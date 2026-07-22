package io.liftandshift.strikebench.sim;

import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JointBookScenarioTest {
    private static final LocalDate ANCHOR = LocalDate.parse("2026-07-22");

    @Test
    void sumsOnlySynchronizedPathIndexesNotIndependentQuantiles() {
        ScenarioSpec spec = spec(4);
        double[][] a = {{100, 80}, {100, 120}, {100, 90}, {100, 110}};
        double[][] b = {{100, 120}, {100, 80}, {100, 110}, {100, 90}};
        var joint = joint(spec, Map.of("AAA", a, "BBB", b));
        var valuator = new ScenarioCanvasValuator();

        var report = valuator.valueJointBook(joint, ScenarioCanvasSpec.defaults(), .04, List.of(
                stock("a", "AAA"), stock("b", "BBB")), 4);

        assertThat(report.stepBands()).hasSize(2);
        var terminal = report.stepBands().getLast();
        assertThat(terminal.pnlP5Cents()).isZero();
        assertThat(terminal.pnlP50Cents()).isZero();
        assertThat(terminal.pnlP95Cents()).isZero();
        assertThat(report.displayPaths()).allSatisfy(path ->
                assertThat(path.steps().getLast().pnlCents()).isZero());
        assertThat(report.positions()).extracting(
                ScenarioCanvasValuator.BookPositionReceipt::horizonP10Cents)
                .allMatch(value -> value < 0);
        assertThat(report.notes()).anyMatch(note -> note.contains("never added"));
        assertThat(report.jointFingerprint()).isEqualTo(joint.fingerprint());
    }

    @Test
    void reportsAssignmentAndDrawdownFromTheSameCorrelatedPaths() {
        ScenarioSpec spec = spec(4);
        double[][] paths = {{100, 80}, {100, 85}, {100, 100}, {100, 120}};
        var joint = joint(spec, Map.of("AAA", paths));
        var expiry = io.liftandshift.strikebench.market.MarketHours.tradingDateAfter(ANCHOR, 1);
        var put = Leg.option(LegAction.SELL, OptionType.PUT, BigDecimal.valueOf(90), expiry,
                1, BigDecimal.ZERO);
        var input = new ScenarioCanvasValuator.JointPositionInput("AAA",
                new ScenarioCanvasValuator.PositionInput("put", "AAA short put", "PRACTICE",
                        "ACTIVE_TRADE", new PathPosition(ANCHOR, List.of(put)), 1, null, false), .30);

        var report = new ScenarioCanvasValuator().valueJointBook(joint,
                physicalCanvas(), .04, List.of(input), 4);

        assertThat(report.assignments().chanceAnyAssignmentPct()).isEqualTo(50.0);
        assertThat(report.assignments().p90AbsoluteShares()).isEqualTo(100);
        assertThat(report.assignments().maximumAbsoluteShares()).isEqualTo(100);
        assertThat(report.tailScenarios()).anySatisfy(tail -> {
            assertThat(tail.assignmentShares()).containsEntry("AAA", 100L);
            assertThat(tail.shortContractsAssigned()).isEqualTo(1);
        });
        assertThat(report.p10MaxDrawdownCents()).isNegative();
        assertThat(report.positions().getFirst().anchorBasis()).isEqualTo("MODELED_CURRENT_VALUE");
    }

    private static ScenarioCanvasValuator.JointPositionInput stock(String key, String symbol) {
        var leg = Leg.stockShares(LegAction.BUY, 1, BigDecimal.valueOf(100));
        return new ScenarioCanvasValuator.JointPositionInput(symbol,
                new ScenarioCanvasValuator.PositionInput(key, symbol + " shares", "PRACTICE",
                        "HELD_SHARES", new PathPosition(ANCHOR, List.of(leg)), 1, null, false), .25);
    }

    private static ScenarioCanvasSpec physicalCanvas() {
        return new ScenarioCanvasSpec("NYSE", null, "No dividend input in this unit fixture.",
                0, 0, ScenarioCanvasSpec.SurfaceDynamics.STICKY_MONEYNESS,
                ScenarioCanvasSpec.SettlementPolicy.PHYSICAL_IF_ITM,
                ScenarioCanvasSpec.ExercisePolicy.EXPIRATION_ONLY, List.of(), null);
    }

    private static ScenarioSpec spec(int paths) {
        return new ScenarioSpec(ScenarioSpec.PathModel.BLOCK_BOOTSTRAP,
                ScenarioSpec.Shape.CHOP, 1, 1, 0, .25,
                0, 0, 0, 6, ScenarioSpec.Heston.fromVol(.25), 7L, paths);
    }

    private static PathEnsembleService.JointEnsemble joint(ScenarioSpec spec,
                                                            Map<String, double[][]> paths) {
        LinkedHashMap<String, PathEnsembleService.Ensemble> members = new LinkedHashMap<>();
        List<PathEnsembleService.SymbolCorrelationCoverage> coverage = paths.keySet().stream()
                .sorted().map(symbol -> new PathEnsembleService.SymbolCorrelationCoverage(symbol,
                        61, 60, 60, 0, .25,
                        DataEvidence.of("stored observed bars", Freshness.EOD))).toList();
        List<String> symbols = paths.keySet().stream().sorted().toList();
        List<PathEnsembleService.CorrelationPair> pairs = new java.util.ArrayList<>();
        for (int left = 0; left < symbols.size(); left++) {
            for (int right = left + 1; right < symbols.size(); right++) {
                pairs.add(new PathEnsembleService.CorrelationPair(symbols.get(left),
                        symbols.get(right), -1, 60));
            }
        }
        var correlation = new PathEnsembleService.CorrelationEvidence(true, 60,
                LocalDate.parse("2026-04-28"), ANCHOR, coverage, pairs,
                DataEvidence.of("stored observed bars", Freshness.EOD),
                "Unit fixture with exact aligned sessions; no fill-forward.", null);
        for (String symbol : symbols) {
            members.put(symbol, new PathEnsembleService.Ensemble(
                    PathEnsembleService.Basis.JOINT_ALIGNED_BOOTSTRAP,
                    new PathEnsembleService.Scope(symbol, "observed", AnalysisContext.OBSERVED),
                    paths.get(symbol)[0][0], spec, paths.get(symbol), null,
                    PathEnsembleService.JOINT_MODEL_VERSION, ANCHOR));
        }
        return new PathEnsembleService.JointEnsemble(members, correlation,
                PathEnsembleService.JOINT_MODEL_VERSION, "joint-test-fingerprint", ANCHOR,
                "Same source path index across every member; independent fans are never summed.");
    }
}
