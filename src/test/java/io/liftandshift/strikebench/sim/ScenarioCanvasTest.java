package io.liftandshift.strikebench.sim;

import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.market.Domain;
import io.liftandshift.strikebench.market.EventService;
import io.liftandshift.strikebench.market.MarketHours;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.market.ports.NewsFilingsProvider;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.NewsItem;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.model.ScenarioStory;
import io.liftandshift.strikebench.model.SymbolMatch;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioCanvasTest {

    @Test
    void displayedPercentilesInterpolateValuesWhileFocusSelectsARealPath() {
        LocalDate anchor = LocalDate.of(2026, 7, 2);
        var spec = new ScenarioSpec(ScenarioSpec.PathModel.GBM, ScenarioSpec.Shape.CHOP,
                1, 1, 0, .25, 0, 0, 0, 4, null, 42, 3);
        double[][] paths = {
                {100, 80},
                {100, 100},
                {100, 120},
                {100, 140}
        };
        var ensemble = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.PARAMETRIC,
                new PathEnsembleService.Scope("MU", "observed", AnalysisContext.OBSERVED),
                100, spec, paths, null, PathGenerator.MODEL_VERSION, anchor);

        var report = new ScenarioCanvasValuator().value(ensemble, IvSpec.flat(.25),
                ScenarioCanvasSpec.defaults(), .04, List.of());
        var terminal = report.underlying().getLast();

        assertThat(terminal.p10()).isEqualTo(86);
        assertThat(terminal.p50()).isEqualTo(110);
        assertThat(terminal.p90()).isEqualTo(134);
        assertThat(report.focusSourcePathIndex()).isEqualTo(1);
        assertThat(terminal.focusPrice()).isEqualTo(100);
    }

    @Test void serverOwnsOneExhaustiveTypedDefaultPolicyForEveryNamedStory() {
        var catalog = ScenarioCanvasTemplateService.storyCatalog();

        assertThat(catalog.keySet())
                .containsExactly(ScenarioStory.values());
        assertThat(catalog).hasSize(8);
        assertThat(catalog.get(ScenarioStory.MARKET_CRASH))
                .isEqualTo(new ScenarioCanvasTemplateService.StoryPolicy(-20, 14, 5));
        assertThat(catalog.get(ScenarioStory.GAP_DOWN))
                .isEqualTo(new ScenarioCanvasTemplateService.StoryPolicy(-9, 8, 1));
        assertThat(catalog.get(ScenarioStory.ORDERLY_PULLBACK))
                .isEqualTo(new ScenarioCanvasTemplateService.StoryPolicy(-6, 4, 3));
        assertThat(catalog.get(ScenarioStory.CHOPPY_SIDEWAYS))
                .isEqualTo(new ScenarioCanvasTemplateService.StoryPolicy(-1, 1, 5));
        assertThat(catalog.get(ScenarioStory.FLAT_RANGE))
                .isEqualTo(new ScenarioCanvasTemplateService.StoryPolicy(0, 0, 7));
        assertThat(catalog.get(ScenarioStory.GRIND_HIGHER))
                .isEqualTo(new ScenarioCanvasTemplateService.StoryPolicy(6, -2, 10));
        assertThat(catalog.get(ScenarioStory.STRONG_RALLY))
                .isEqualTo(new ScenarioCanvasTemplateService.StoryPolicy(13, -4, 10));
        assertThat(catalog.get(ScenarioStory.MELT_UP))
                .isEqualTo(new ScenarioCanvasTemplateService.StoryPolicy(20, -6, 12));
        assertThatThrownBy(() -> catalog.put(ScenarioStory.FLAT_RANGE,
                new ScenarioCanvasTemplateService.StoryPolicy(1, 1, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void namedStoryNullControlsUseServerDefaultsAndClampElapsedToStoredHorizon() {
        var spec = ScenarioSpec.preset(ScenarioSpec.Shape.CHOP, 3, .30, 142, 3);
        double[][] storedPaths = {
                {100, 99, 98, 97},
                {100, 101, 102, 103},
                {100, 98, 99, 97}};
        var ensemble = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.PARAMETRIC,
                new PathEnsembleService.Scope("MU", "observed", AnalysisContext.OBSERVED),
                100, spec, storedPaths, null, PathGenerator.MODEL_VERSION,
                LocalDate.of(2026, 7, 2));

        var resolved = ScenarioCanvasTemplateService.resolveInteraction(
                ensemble, spec, IvSpec.flat(.30), ScenarioCanvasSpec.defaults(),
                new ScenarioCanvasTemplateService.Interaction(
                        ScenarioStory.MELT_UP,
                        null, null, null, null));

        assertThat(resolved.declaration()).isEqualTo(
                new ScenarioCanvasTemplateService.Interaction(
                        ScenarioStory.MELT_UP,
                        20.0, -6.0, 3, null));
        assertThat(resolved.scenario().waypoints())
                .extracting(ScenarioSpec.Waypoint::dayIndex)
                .containsExactly(1, 2, 3);
        assertThat(resolved.scenario().waypoints())
                .extracting(ScenarioSpec.Waypoint::priceRatio)
                .containsExactly(1.016, 1.076, 1.20);
        assertThat(resolved.canvas().ivNodes())
                .extracting(ScenarioCanvasSpec.IvNode::dayIndex)
                .containsExactly(0, 3);
        assertThat(resolved.canvas().ivNodes().getLast().atmIv()).isEqualTo(.24);
        assertThat(resolved.pathWaypoints()).isEmpty();
        assertThat(resolved.sourcePathIndex()).isNull();
        assertThat(ensemble.paths()).isSameAs(storedPaths);
        assertThat(ensemble.paths()[0]).containsExactly(100, 99, 98, 97);
    }

    @Test void explicitStoryControlsOverrideEveryDefaultWithoutCreatingAnotherPathSource() {
        var spec = ScenarioSpec.preset(ScenarioSpec.Shape.CHOP, 8, .30, 142, 3);
        double[][] storedPaths = {
                {100, 99, 98, 97, 96, 95, 94, 93, 92},
                {100, 101, 102, 103, 104, 105, 106, 107, 108}};
        var ensemble = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.PARAMETRIC,
                new PathEnsembleService.Scope("MU", "observed", AnalysisContext.OBSERVED),
                100, spec, storedPaths, null, PathGenerator.MODEL_VERSION,
                LocalDate.of(2026, 7, 2));
        var override = new ScenarioCanvasTemplateService.Interaction(
                ScenarioStory.MARKET_CRASH,
                -11.0, 3.0, 6, null);

        var resolved = ScenarioCanvasTemplateService.resolveInteraction(
                ensemble, spec, IvSpec.flat(.30), ScenarioCanvasSpec.defaults(), override);

        assertThat(resolved.declaration()).isEqualTo(override);
        assertThat(resolved.scenario().horizonDays()).isEqualTo(spec.horizonDays());
        assertThat(resolved.scenario().waypoints().getLast().dayIndex()).isEqualTo(6);
        assertThat(resolved.scenario().waypoints().getLast().priceRatio()).isEqualTo(.89);
        assertThat(resolved.canvas().ivNodes().getLast().atmIv()).isCloseTo(.33,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(resolved.pathWaypoints()).isEmpty();
        assertThat(resolved.sourcePathIndex()).isNull();
        assertThat(ensemble.paths()).isSameAs(storedPaths);
    }

    @Test void storyDefaultsAndOverridesAreValidatedWhileExactPathDefaultsStayExact() {
        var spec = ScenarioSpec.preset(ScenarioSpec.Shape.CHOP, 4, .25, 91, 4);
        double[][] storedPaths = {
                {100, 90, 80, 70, 60}, {100, 95, 90, 85, 80},
                {100, 100, 100, 100, 100}, {100, 105, 110, 115, 120}};
        var ensemble = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.PARAMETRIC,
                new PathEnsembleService.Scope("MU", "observed", AnalysisContext.OBSERVED),
                100, spec, storedPaths, null, PathGenerator.MODEL_VERSION,
                LocalDate.of(2026, 7, 2));

        var exact = ScenarioCanvasTemplateService.resolveInteraction(
                ensemble, spec, IvSpec.flat(.30), ScenarioCanvasSpec.defaults(),
                new ScenarioCanvasTemplateService.Interaction(
                        null, null, null, null, 3));
        assertThat(exact.declaration()).isEqualTo(
                new ScenarioCanvasTemplateService.Interaction(
                        null, null, 0.0, 4, 3));
        assertThat(exact.sourcePathIndex()).isEqualTo(3);
        assertThat(exact.scenario()).isNull();
        assertThat(exact.pathWaypoints()).isEmpty();
        assertThat(ensemble.paths()).isSameAs(storedPaths);

        assertThatThrownBy(() -> ScenarioCanvasTemplateService.resolveInteraction(
                ensemble, spec, IvSpec.flat(.30), ScenarioCanvasSpec.defaults(),
                new ScenarioCanvasTemplateService.Interaction(
                        ScenarioStory.GAP_DOWN,
                        Double.NaN, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("movePct");
        assertThatThrownBy(() -> ScenarioCanvasTemplateService.resolveInteraction(
                ensemble, spec, IvSpec.flat(.30), ScenarioCanvasSpec.defaults(),
                new ScenarioCanvasTemplateService.Interaction(
                        ScenarioStory.GAP_DOWN,
                        null, null, 5, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stored ensemble horizon");
        assertThatThrownBy(() -> ScenarioCanvasTemplateService.resolveInteraction(
                ensemble, spec, IvSpec.flat(.30), ScenarioCanvasSpec.defaults(),
                new ScenarioCanvasTemplateService.Interaction(
                        null, null, null, null, 4)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the stored ensemble");
    }

    @Test void compactDeskStoryIsResolvedOnlyByTheScenarioOwner() {
        var spec = new ScenarioSpec(ScenarioSpec.PathModel.GBM, ScenarioSpec.Shape.CHOP,
                6, 1, 0, .30, 0, 0, 0, 6, null, 142, 3);
        var ensemble = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.PARAMETRIC,
                new PathEnsembleService.Scope("MU", "observed", AnalysisContext.OBSERVED),
                100, spec, new double[][]{
                    {100, 99, 98, 97, 96, 95, 94},
                    {100, 101, 100, 102, 101, 103, 102},
                    {100, 98, 96, 94, 93, 92, 91}},
                null, PathGenerator.MODEL_VERSION, LocalDate.of(2026, 7, 2));
        var declaration = new ScenarioCanvasTemplateService.Interaction(
                ScenarioStory.GAP_DOWN, -9.0, 12.0, 3, null);

        var resolved = ScenarioCanvasTemplateService.resolveInteraction(
                ensemble, spec, IvSpec.flat(.30), ScenarioCanvasSpec.defaults(), declaration);

        assertThat(resolved.declaration()).isEqualTo(declaration);
        assertThat(resolved.sourcePathIndex()).isNull();
        assertThat(resolved.pathWaypoints()).isEmpty();
        assertThat(resolved.scenario().waypoints())
                .extracting(ScenarioSpec.Waypoint::dayIndex)
                .containsExactly(1, 2, 3);
        assertThat(resolved.scenario().waypoints())
                .extracting(ScenarioSpec.Waypoint::priceRatio)
                .containsExactly(.91, .9172, .91);
        assertThat(resolved.canvas().ivNodes())
                .extracting(ScenarioCanvasSpec.IvNode::dayIndex)
                .containsExactly(0, 3);
        assertThat(resolved.canvas().ivNodes().getFirst().atmIv()).isEqualTo(.30);
        assertThat(resolved.canvas().ivNodes().getLast().atmIv()).isEqualTo(.42);
    }

    @Test void exactFanClickPreservesImmutableSourceIdentityWithoutAuthoredPins() {
        var spec = ScenarioSpec.preset(ScenarioSpec.Shape.CHOP, 2, .25, 91, 4);
        var ensemble = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.PARAMETRIC,
                new PathEnsembleService.Scope("MU", "observed", AnalysisContext.OBSERVED),
                100, spec, new double[][]{
                    {100, 90, 80}, {100, 95, 90}, {100, 100, 100}, {100, 105, 110}},
                null, PathGenerator.MODEL_VERSION, LocalDate.of(2026, 7, 2));
        var declaration = new ScenarioCanvasTemplateService.Interaction(
                null, null, -5.0, 2, 3);

        var resolved = ScenarioCanvasTemplateService.resolveInteraction(
                ensemble, spec, IvSpec.flat(.30), ScenarioCanvasSpec.defaults(), declaration);

        assertThat(resolved.declaration()).isEqualTo(declaration);
        assertThat(resolved.sourcePathIndex()).isEqualTo(3);
        assertThat(resolved.scenario()).isNull();
        assertThat(resolved.pathWaypoints()).isEmpty();
        assertThatThrownBy(() -> ScenarioCanvasTemplateService.resolveInteraction(
                ensemble, spec, IvSpec.flat(.30), ScenarioCanvasSpec.defaults(),
                new ScenarioCanvasTemplateService.Interaction(
                        ScenarioStory.MELT_UP, 20.0, 0.0, 2, 3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alternative scenario sources");
    }

    @Test void ivNodesInterpolateThenEvolveStrikeAndTermSurface() {
        var canvas = new ScenarioCanvasSpec("NYSE", 0.012, "entered dividend yield", -0.30, 0.08,
                ScenarioCanvasSpec.SurfaceDynamics.STICKY_MONEYNESS,
                ScenarioCanvasSpec.SettlementPolicy.CASH_INTRINSIC,
                ScenarioCanvasSpec.ExercisePolicy.EXPIRATION_ONLY,
                List.of(new ScenarioCanvasSpec.IvNode(0, .40),
                        new ScenarioCanvasSpec.IvNode(4, .20)), null).sane(8);

        assertThat(canvas.atmIv(2, 8, .99)).isCloseTo(.30,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(canvas.surfaceIv(2, 8, .99, 100, 100, 110, 90.0 / 365.0))
                .isNotEqualTo(.30)
                .isBetween(.20, .40);
        assertThat(canvas.dividendYieldForPricing()).isEqualTo(.012);
    }

    @Test void calendarClockCarriesWeekendAndExchangeHolidayTime() {
        var spec = ScenarioSpec.preset(ScenarioSpec.Shape.CHOP, 2, .25, 7, 20);
        LocalDate thursdayBeforeIndependenceDay = LocalDate.of(2026, 7, 2);
        List<LocalDate> sessions = ScenarioSpec.sessionDates(thursdayBeforeIndependenceDay, 2);
        double[] dt = spec.calendarStepYears(thursdayBeforeIndependenceDay);

        assertThat(sessions).containsExactly(LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 7));
        assertThat(dt[0]).isEqualTo(4.0 / 365.0);
        assertThat(dt[1]).isEqualTo(1.0 / 365.0);
    }

    @Test void symbolScopeRepricesMultiplePositionsAndTransformsFrontExpiry() {
        LocalDate anchor = LocalDate.of(2026, 7, 2);
        LocalDate front = MarketHours.tradingDateAfter(anchor, 2);
        var spec = new ScenarioSpec(ScenarioSpec.PathModel.GBM, ScenarioSpec.Shape.CHOP,
                4, 1, 0, .25, 0, 0, 0, 6, null, 42, 3);
        double[][] paths = {
                {100, 104, 108, 112, 116},
                {100, 102, 106, 109, 113},
                {100,  98, 101, 103, 105}
        };
        var ensemble = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.PARAMETRIC,
                new PathEnsembleService.Scope("MU", "observed", AnalysisContext.OBSERVED),
                100, spec, paths, null, PathGenerator.MODEL_VERSION, anchor);
        var call = new PathPosition(anchor, List.of(Leg.option(LegAction.BUY, OptionType.CALL,
                new BigDecimal("100"), front, 1, BigDecimal.ZERO)));
        var shares = new PathPosition(anchor, List.of(Leg.stockShares(LegAction.BUY, 100, new BigDecimal("100"))));
        var canvas = new ScenarioCanvasSpec("NYSE", null, "dividend unavailable", 0, 0,
                ScenarioCanvasSpec.SurfaceDynamics.STICKY_MONEYNESS,
                ScenarioCanvasSpec.SettlementPolicy.PHYSICAL_IF_ITM,
                ScenarioCanvasSpec.ExercisePolicy.EXPIRATION_ONLY,
                List.of(new ScenarioCanvasSpec.IvNode(0, .30),
                        new ScenarioCanvasSpec.IvNode(2, .20)), null);

        var report = new ScenarioCanvasValuator().value(ensemble, IvSpec.flat(.30), canvas, .04,
                List.of(new ScenarioCanvasValuator.PositionInput("ours", "Calendar front", "REAL",
                                "TRACKED_STRUCTURE", call, 1, 500L, false),
                        new ScenarioCanvasValuator.PositionInput("stock", "100 shares", "BASELINE",
                                "STOCK_BASELINE", shares, 1, 1_000_000L, false)));

        assertThat(report.positions()).extracting(ScenarioCanvasValuator.PositionPath::key)
                .containsExactly("ours", "stock");
        assertThat(report.positions().getFirst().transformations()).singleElement()
                .satisfies(t -> assertThat(t.day()).isEqualTo(2));
        assertThat(report.positions().getFirst().legs().getFirst().days().get(3).state())
                .isEqualTo("PHYSICAL_SETTLEMENT_TRANSFORMED");
        assertThat(report.comparison()).extracting(ScenarioCanvasValuator.ComparisonRow::key)
                .containsExactlyInAnyOrder("ours", "stock");
        assertThat(report.underlying()).hasSize(5);

        var focused = new ScenarioCanvasValuator().value(ensemble, IvSpec.flat(.30), canvas, .04,
                List.of(new ScenarioCanvasValuator.PositionInput("ours", "Calendar front", "REAL",
                        "TRACKED_STRUCTURE", call, 1, 500L, false)), 2);
        assertThat(focused.focusSourcePathIndex()).isEqualTo(2);
        assertThat(focused.underlying().get(1).focusPrice()).isEqualTo(98);
        var focusDay = focused.positions().getFirst().days().get(1);
        var focusLegDay = focused.positions().getFirst().legs().getFirst().days().get(1);
        assertThat(focusDay.focusValueCents()).isEqualTo(focusLegDay.valueCents());
        assertThat(focusDay.focusPnlCents()).isEqualTo(focusDay.focusValueCents() - 500L);
        assertThat(focusDay.greeks()).isEqualTo(focusLegDay.greeks());
    }

    @Test void focusPathCheckpointsRepriceEveryStoredSimulationStepWithoutReplacingDailyBands() {
        LocalDate anchor = LocalDate.of(2026, 7, 2);
        var spec = new ScenarioSpec(ScenarioSpec.PathModel.GBM, ScenarioSpec.Shape.CHOP,
                2, 3, 0, .30, 0, 0, 0, 6, null, 142, 3);
        double[][] paths = {
                {100, 101, 102, 103, 104, 105, 106},
                {100, 100, 101, 100, 102, 101, 103},
                {100,  98, 102,  97, 103,  99, 105}
        };
        var ensemble = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.PARAMETRIC,
                new PathEnsembleService.Scope("MU", "observed", AnalysisContext.OBSERVED),
                100, spec, paths, null, PathGenerator.MODEL_VERSION, anchor);
        var call = new PathPosition(anchor, List.of(Leg.option(LegAction.BUY, OptionType.CALL,
                new BigDecimal("100"), MarketHours.tradingDateAfter(anchor, 10), 1,
                BigDecimal.ZERO)));
        var iv = IvSpec.flat(.30);
        var canvas = ScenarioCanvasSpec.defaults();

        var report = new ScenarioCanvasValuator().value(ensemble, iv, canvas, .04,
                List.of(new ScenarioCanvasValuator.PositionInput("call", "Two calls", "PLAN",
                        "PROPOSAL", call, 2, 900L, true)), 2,
                List.of(new ScenarioCanvasValuator.DisplayPathSelection(0, "CONTEXT"),
                        new ScenarioCanvasValuator.DisplayPathSelection(2, "FOCUS")));

        assertThat(report.underlying()).hasSize(3);
        assertThat(report.underlyingSteps()).hasSize(7);
        assertThat(report.underlyingSteps())
                .extracting(ScenarioCanvasValuator.UnderlyingStep::focusPrice)
                .containsExactly(100.0, 98.0, 102.0, 97.0, 103.0, 99.0, 105.0);
        assertThat(report.underlyingSteps())
                .extracting(ScenarioCanvasValuator.UnderlyingStep::sessionProgress)
                .containsExactly(0.0, .3333, .6667, 1.0, 1.3333, 1.6667, 2.0);
        assertThat(report.underlying().get(1).focusPrice())
                .isEqualTo(report.underlyingSteps().get(3).focusPrice());

        var position = report.positions().getFirst();
        assertThat(position.days()).hasSize(3);
        assertThat(position.steps()).hasSize(7);
        assertThat(position.stepBands()).hasSize(7);
        assertThat(position.displayPaths())
                .extracting(ScenarioCanvasValuator.DisplayPositionPath::sourcePathIndex)
                .containsExactly(0, 2);
        assertThat(position.displayPaths().get(1).role()).isEqualTo("FOCUS");
        assertThat(position.displayPaths().get(1).steps()).hasSize(7);
        assertThat(position.legs().getFirst().days()).hasSize(3);
        assertThat(position.legs().getFirst().steps()).hasSize(7);
        assertThat(position.days().get(1).focusValueCents())
                .isEqualTo(position.steps().get(3).focusValueCents());
        assertThat(position.legs().getFirst().days().get(1).valueCents())
                .isEqualTo(position.legs().getFirst().steps().get(3).valueCents());

        double[] elapsed = PathValuationKernel.elapsed(spec.calendarStepYears(anchor));
        double[] ivPath = iv.path(spec.totalSteps(), elapsed[spec.totalSteps()] / spec.totalSteps(),
                spec.stepsPerDay());
        int[] transformations = PathValuationKernel.transformationSteps(call, paths[2],
                spec.totalSteps(), spec.stepsPerDay(), elapsed, ivPath, canvas, .04);
        long expectedStepTwo = Math.round(PathValuationKernel.valueCanvas(call, paths[2], 2,
                spec.totalSteps(), spec.stepsPerDay(), elapsed, ivPath, canvas, .04,
                transformations) * 2 * 100);
        assertThat(position.steps().get(2).focusValueCents()).isEqualTo(expectedStepTwo);
        assertThat(position.steps().get(2).focusValueCents())
                .isEqualTo(position.legs().getFirst().steps().get(2).valueCents());
        assertThat(position.steps().get(2).focusPnlCents()).isEqualTo(expectedStepTwo - 900L);
        assertThat(position.displayPaths().get(1).steps().get(2).pnlCents())
                .isEqualTo(expectedStepTwo - 900L);
        assertThat(position.stepBands().get(2).pnlP10Cents())
                .isLessThanOrEqualTo(position.stepBands().get(2).pnlP50Cents());
        assertThat(position.stepBands().get(2).pnlP50Cents())
                .isLessThanOrEqualTo(position.stepBands().get(2).pnlP90Cents());
        assertThat(position.steps().get(2).greeks())
                .isEqualTo(position.legs().getFirst().steps().get(2).greeks());
    }

    /**
     * LEAK 4: the desk used to compute the scenario readout's percent move and vol shift itself,
     * from a spot and an IV baseline it picked, and print the result as a financial fact. Every
     * frame now states both against anchors the track names, so the browser has nothing left to
     * derive.
     */
    @Test void everyAnimationFrameStatesItsOwnMoveAndVolShiftAgainstNamedAnchors() {
        LocalDate anchor = LocalDate.of(2026, 7, 2);
        var spec = new ScenarioSpec(ScenarioSpec.PathModel.GBM, ScenarioSpec.Shape.CHOP,
                6, 1, 0, .30, 0, 0, 0, 6, null, 142, 3);
        double[][] paths = {
                {100, 101, 102, 103, 104, 105, 106},
                {100, 100, 101, 100, 102, 101, 103},
                {100,  98, 102,  97, 103,  99, 105}
        };
        var ensemble = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.PARAMETRIC,
                new PathEnsembleService.Scope("MU", "observed", AnalysisContext.OBSERVED),
                100, spec, paths, null, PathGenerator.MODEL_VERSION, anchor);
        // A rising ATM term structure, so a vol shift of zero would be a real failure and not an
        // accident of a flat surface.
        var canvas = new ScenarioCanvasSpec("NYSE", null, "no dividend", 0, 0,
                ScenarioCanvasSpec.SurfaceDynamics.STICKY_MONEYNESS,
                ScenarioCanvasSpec.SettlementPolicy.CASH_INTRINSIC,
                ScenarioCanvasSpec.ExercisePolicy.EXPIRATION_ONLY,
                List.of(new ScenarioCanvasSpec.IvNode(0, .20),
                        new ScenarioCanvasSpec.IvNode(6, .40)), null).sane(6);
        var call = new PathPosition(anchor, List.of(Leg.option(LegAction.BUY, OptionType.CALL,
                new BigDecimal("100"), MarketHours.tradingDateAfter(anchor, 10), 1,
                BigDecimal.ZERO)));

        var report = new ScenarioCanvasValuator().value(ensemble, IvSpec.flat(.30), canvas, .04,
                List.of(new ScenarioCanvasValuator.PositionInput("call", "One call", "PLAN",
                        "PROPOSAL", call, 1, 900L, true)), 2, List.of());

        var frames = report.underlyingSteps();
        assertThat(frames).hasSize(7);
        // Percent move is measured from the ensemble anchor spot, so frame 0 is exactly flat.
        assertThat(frames)
                .extracting(ScenarioCanvasValuator.UnderlyingStep::moveFromSpotPct)
                .containsExactly(0.0, -2.0, 2.0, -3.0, 3.0, -1.0, 5.0);
        // Vol shift is measured in vol POINTS from frame 0's ATM vol, and it actually moves.
        assertThat(frames.getFirst().ivShiftPoints()).isEqualTo(0.0);
        assertThat(frames.get(3).ivShiftPoints()).isCloseTo(10.0,
                org.assertj.core.data.Offset.offset(.01));
        assertThat(frames.getLast().ivShiftPoints()).isCloseTo(20.0,
                org.assertj.core.data.Offset.offset(.01));
        assertThat(frames)
                .extracting(ScenarioCanvasValuator.UnderlyingStep::ivShiftPoints)
                .isSorted();

        var track = report.animation();
        assertThat(track).isNotNull();
        assertThat(track.frameRule()).isEqualTo("SELECT_NEAREST_FRAME_NO_INTERPOLATION");
        assertThat(track.frameSource()).isEqualTo("underlyingSteps");
        assertThat(track.positionFrameSource()).isEqualTo("positions[].steps");
        assertThat(track.frameCount()).isEqualTo(frames.size());
        assertThat(track.frameCount()).isEqualTo(report.positions().getFirst().steps().size());
        assertThat(track.sourceStepCount()).isEqualTo(6);
        assertThat(track.stepsPerDay()).isEqualTo(1);
        assertThat(track.anchorSpot()).isEqualTo(100.0);
        assertThat(track.horizonSessions()).isEqualTo(frames.getLast().sessionProgress());
        assertThat(track.baselineAtmIv()).isEqualTo(frames.getFirst().atmIv());
        assertThat(track.baselineAtmIvSource()).isEqualTo("TRACK_FRAME_0");
        assertThat(report.notes()).anyMatch(note -> note.contains("Math.round(t x terminalFrameIndex)"));
    }

    /**
     * LEAK 4: the package's life boundary is a backend fact. The desk used to re-derive it by
     * string-comparing session dates against leg expirations, which decided how far the scrub could
     * go — and therefore which frame's P/L it printed.
     */
    @Test void eachPackageStatesItsEconomicTerminalBoundaryWithoutLettingTheDeskGuess() {
        LocalDate anchor = LocalDate.of(2026, 7, 2);
        var spec = new ScenarioSpec(ScenarioSpec.PathModel.GBM, ScenarioSpec.Shape.CHOP,
                6, 4, 0, .30, 0, 0, 0, 6, null, 142, 3);
        double[][] paths = new double[3][spec.totalSteps() + 1];
        for (int step = 0; step <= spec.totalSteps(); step++) {
            paths[0][step] = 100 + step;
            paths[1][step] = 100 + step * .5;
            paths[2][step] = 100 - step * .1;
        }
        var ensemble = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.PARAMETRIC,
                new PathEnsembleService.Scope("MU", "observed", AnalysisContext.OBSERVED),
                100, spec, paths, null, PathGenerator.MODEL_VERSION, anchor);
        LocalDate frontExpiry = MarketHours.tradingDateAfter(anchor, 3);
        LocalDate finalExpiry = MarketHours.tradingDateAfter(anchor, 5);
        var cashCalendar = new PathPosition(anchor, List.of(
                Leg.option(LegAction.BUY, OptionType.CALL, new BigDecimal("100"), frontExpiry, 1,
                        BigDecimal.ZERO),
                Leg.option(LegAction.SELL, OptionType.CALL, new BigDecimal("110"),
                        finalExpiry, 1, BigDecimal.ZERO)));
        var outlivesHorizon = new PathPosition(anchor, List.of(Leg.option(LegAction.BUY,
                OptionType.CALL, new BigDecimal("100"), MarketHours.tradingDateAfter(anchor, 30),
                1, BigDecimal.ZERO)));
        var sharesOnly = new PathPosition(anchor, List.of(
                Leg.stockShares(LegAction.BUY, 100, new BigDecimal("100"))));
        var sharesAndOption = new PathPosition(anchor, List.of(
                Leg.stockShares(LegAction.BUY, 100, new BigDecimal("100")),
                Leg.option(LegAction.BUY, OptionType.PUT, new BigDecimal("95"), anchor, 1,
                        BigDecimal.ZERO)));
        var zeroDteCash = new PathPosition(anchor, List.of(Leg.option(LegAction.SELL,
                OptionType.PUT, new BigDecimal("95"), anchor, 1, BigDecimal.ZERO)));

        var report = new ScenarioCanvasValuator().value(ensemble, IvSpec.flat(.30),
                ScenarioCanvasSpec.defaults(), .04, List.of(
                        new ScenarioCanvasValuator.PositionInput("calendar", "Cash calendar", "REAL",
                                "TRACKED_STRUCTURE", cashCalendar, 1, 500L, false),
                        new ScenarioCanvasValuator.PositionInput("long", "Long dated", "PLAN",
                                "PROPOSAL", outlivesHorizon, 1, 900L, true),
                        new ScenarioCanvasValuator.PositionInput("STOCK:MU", "Buy and hold",
                                "BASELINE", "STOCK_BASELINE", sharesOnly, 1, 10_000L, false),
                        new ScenarioCanvasValuator.PositionInput("covered", "Stock plus put", "REAL",
                                "TRACKED_STRUCTURE", sharesAndOption, 1, 10_000L, false),
                        new ScenarioCanvasValuator.PositionInput("zero", "Zero DTE cash put", "REAL",
                                "TRACKED_STRUCTURE", zeroDteCash, 1, 100L, false)),
                2, List.of());

        var byKey = report.positions().stream().collect(java.util.stream.Collectors
                .toMap(ScenarioCanvasValuator.PositionPath::key, path -> path.animation()));

        // Cash-settled multi-expiry packages resolve only when their FINAL option settles.
        var calendar = byKey.get("calendar");
        assertThat(calendar.frameCount()).isEqualTo(25);
        assertThat(calendar.terminalFrameIndex()).isEqualTo(20);
        assertThat(calendar.terminalSessionProgress()).isEqualTo(5.0);
        assertThat(calendar.finalOptionExpiration()).isEqualTo(finalExpiry.toString());
        assertThat(calendar.boundaryReason()).isEqualTo("FINAL_CASH_SETTLEMENT");
        assertThat(calendar.exposureResolvedAtBoundary()).isTrue();
        assertThat(calendar.unavailableReason()).isNull();
        assertThat(report.underlyingSteps().get(calendar.terminalFrameIndex()).sessionDate())
                .isEqualTo(finalExpiry.toString());

        // A cash option that outlives the fan remains unresolved at the horizon.
        var longDated = byKey.get("long");
        assertThat(longDated.terminalFrameIndex()).isEqualTo(24);
        assertThat(longDated.boundaryReason())
                .isEqualTo("HORIZON_END_OPTION_OUTLIVES_TRACK");
        assertThat(longDated.exposureResolvedAtBoundary()).isFalse();
        assertThat(longDated.finalOptionExpiration())
                .isEqualTo(MarketHours.tradingDateAfter(anchor, 30).toString());

        // Explicit shares keep the position live through the horizon, alone or beside an option.
        var stock = byKey.get("STOCK:MU");
        assertThat(stock.terminalFrameIndex()).isEqualTo(24);
        assertThat(stock.boundaryReason()).isEqualTo("HORIZON_END_STOCK_EXPOSURE");
        assertThat(stock.exposureResolvedAtBoundary()).isFalse();
        assertThat(stock.finalOptionExpiration()).isNull();
        assertThat(stock.unavailableReason()).isNull();

        var covered = byKey.get("covered");
        assertThat(covered.terminalFrameIndex()).isEqualTo(24);
        assertThat(covered.boundaryReason()).isEqualTo("HORIZON_END_STOCK_EXPOSURE");
        assertThat(covered.finalOptionExpiration()).isEqualTo(anchor.toString());
        assertThat(covered.exposureResolvedAtBoundary()).isFalse();

        // A cash-settled 0DTE option resolves at the current session's first closing frame.
        var zero = byKey.get("zero");
        assertThat(zero.terminalFrameIndex()).isEqualTo(4);
        assertThat(zero.terminalSessionProgress()).isEqualTo(1.0);
        assertThat(zero.finalOptionExpiration()).isEqualTo(anchor.toString());
        assertThat(zero.boundaryReason()).isEqualTo("FINAL_CASH_SETTLEMENT");
        assertThat(zero.exposureResolvedAtBoundary()).isTrue();

        var physicalCanvas = new ScenarioCanvasSpec("NYSE", null, "no dividend", 0, 0,
                ScenarioCanvasSpec.SurfaceDynamics.STICKY_MONEYNESS,
                ScenarioCanvasSpec.SettlementPolicy.PHYSICAL_IF_ITM,
                ScenarioCanvasSpec.ExercisePolicy.EXPIRATION_ONLY, List.of(), null);
        var physicalReport = new ScenarioCanvasValuator().value(ensemble, IvSpec.flat(.30),
                physicalCanvas, .04, List.of(
                        new ScenarioCanvasValuator.PositionInput("physical", "Physical call", "REAL",
                                "TRACKED_STRUCTURE", new PathPosition(anchor, List.of(
                                Leg.option(LegAction.BUY, OptionType.CALL, new BigDecimal("100"),
                                        frontExpiry, 1, BigDecimal.ZERO))), 1, 500L, false)));
        var physical = physicalReport.positions().getFirst().animation();
        assertThat(physical.terminalFrameIndex()).isEqualTo(24);
        assertThat(physical.finalOptionExpiration()).isEqualTo(frontExpiry.toString());
        assertThat(physical.boundaryReason()).isEqualTo("HORIZON_END_PHYSICAL_EXPOSURE");
        assertThat(physical.exposureResolvedAtBoundary()).isFalse();
    }

    @Test void storedEnsembleAnchorDrivesBothCanvasDistributionAndDailyValuationClock() {
        LocalDate ensembleAnchor = LocalDate.of(2026, 7, 1);
        LocalDate positionAsOf = LocalDate.of(2026, 7, 6);
        var spec = new ScenarioSpec(ScenarioSpec.PathModel.GBM, ScenarioSpec.Shape.CHOP,
                4, 1, 0, .25, 0, 0, 0, 6, null, 79, 3);
        double[][] paths = {
                {100, 101, 102, 103, 104},
                {100, 100, 101, 102, 103},
                {100,  99, 100, 101, 102}
        };
        var ensemble = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.PARAMETRIC,
                new PathEnsembleService.Scope("MU", "observed", AnalysisContext.OBSERVED),
                100, spec, paths, null, PathGenerator.MODEL_VERSION, ensembleAnchor);
        var position = new PathPosition(positionAsOf, List.of(Leg.option(LegAction.BUY, OptionType.CALL,
                new BigDecimal("100"), MarketHours.tradingDateAfter(positionAsOf, 30), 1, BigDecimal.ZERO)));
        var iv = new IvSpec(.80, -.40, 12, .20, -1, 0, .03, 4);
        var canvas = ScenarioCanvasSpec.defaults();

        var distribution = new ScenarioSimulator().compare(ensemble,
                List.of(new ScenarioSimulator.CompareItem("call", position, null, null, 0, 1)),
                1, iv, canvas, .04).report().results().getFirst().result();
        assertThatThrownBy(() -> new ScenarioSimulator().compare(ensemble,
                List.of(new ScenarioSimulator.CompareItem("call", position, null, null, -1, 1)),
                1, iv, canvas, .04))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fees cannot be negative");
        var daily = new ScenarioCanvasValuator().value(ensemble, iv, canvas, .04,
                List.of(new ScenarioCanvasValuator.PositionInput("call", "Call", "PLAN",
                        "PROPOSAL", position, 1, null, true)));

        assertThat(distribution.p50Cents()).isCloseTo(daily.comparison().getFirst().horizonP50Cents(),
                org.assertj.core.data.Offset.offset(1L));
        assertThat(daily.notes()).anyMatch(note -> note.contains("Daily Greeks")
                && note.contains("representative path"));
    }

    @Test void optionExpiringAfterCanvasHorizonRetainsTimeValueAndLiveState() {
        LocalDate anchor = LocalDate.of(2026, 7, 1);
        var position = new PathPosition(anchor, List.of(Leg.option(LegAction.BUY, OptionType.CALL,
                new BigDecimal("100"), MarketHours.tradingDateAfter(anchor, 30), 1, BigDecimal.ZERO)));
        int steps = 4;
        double[] path = {100, 100, 100, 100, 100};
        var spec = ScenarioSpec.preset(ScenarioSpec.Shape.CHOP, steps, .30, 91, 1);
        double[] elapsed = PathValuationKernel.elapsed(spec.calendarStepYears(anchor));
        double[] iv = IvSpec.flat(.30).path(steps, elapsed[steps] / steps, 1);
        var canvas = ScenarioCanvasSpec.defaults();
        int[] transformations = PathValuationKernel.transformationSteps(position, path, steps, 1,
                elapsed, iv, canvas, .04);

        var horizon = PathValuationKernel.legPoint(position, position.legs().getFirst(), path,
                steps, steps, 1, elapsed, iv, canvas, .04, transformations[0]);

        assertThat(transformations[0]).isGreaterThan(steps);
        assertThat(horizon.state()).isEqualTo("LIVE_MODELED");
        assertThat(horizon.optionPrice()).isGreaterThan(0);
        // LegPoint reports contract delta in share-equivalents (100 multiplier), not raw 0..1 delta.
        assertThat(horizon.deltaShares()).isBetween(40.0, 70.0);
    }

    @Test void dailyOnlyPositionNamesItsUnavailableAnimationWithoutInventingABoundary() {
        var path = new ScenarioCanvasValuator.PositionPath("daily", "Daily only", "REAL",
                "TRACKED_STRUCTURE", false, null, List.of(), List.of(), List.of());

        assertThat(path.animation().frameCount()).isZero();
        assertThat(path.animation().terminalFrameIndex()).isEqualTo(-1);
        assertThat(path.animation().terminalSessionProgress()).isNull();
        assertThat(path.animation().finalOptionExpiration()).isNull();
        assertThat(path.animation().boundaryReason()).isEqualTo("NO_FRAMES");
        assertThat(path.animation().exposureResolvedAtBoundary()).isFalse();
        assertThat(path.animation().unavailableReason()).contains("daily grid only");
    }

    @Test void maximumResolutionCanvasBoundsOnlyItsWireCheckpoints() {
        LocalDate anchor = LocalDate.of(2026, 7, 1);
        var spec = new ScenarioSpec(ScenarioSpec.PathModel.GBM, ScenarioSpec.Shape.CHOP,
                756, 96, 0, .25, 0, 0, 0, 6,
                ScenarioSpec.Heston.fromVol(.25), 93, 20);
        int steps = spec.totalSteps();
        double[][] paths = new double[2][steps + 1];
        for (int step = 0; step <= steps; step++) {
            paths[0][step] = 100 - step / 100_000.0;
            paths[1][step] = 100 + step / 100_000.0;
        }
        var ensemble = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.PARAMETRIC,
                new PathEnsembleService.Scope("MU", "observed", AnalysisContext.OBSERVED),
                100, spec, paths, null, PathGenerator.MODEL_VERSION, anchor);
        int expiryDay = 333;
        int exactExpiryStep = expiryDay * spec.stepsPerDay();
        var option = new PathPosition(anchor, List.of(Leg.option(LegAction.BUY, OptionType.CALL,
                new BigDecimal("100"), MarketHours.tradingDateAfter(anchor, expiryDay), 1,
                BigDecimal.ZERO)));

        var report = new ScenarioCanvasValuator().value(ensemble, IvSpec.flat(.25),
                ScenarioCanvasSpec.defaults(), .04, List.of(
                        new ScenarioCanvasValuator.PositionInput("call", "Long call", "PLAN",
                                "PROPOSAL", option, 1, 0L, true)));

        assertThat(report.underlying()).hasSize(757); // full daily bands remain authoritative
        assertThat(report.underlyingSteps())
                .hasSize(PathEnsembleService.MAX_DISPLAY_POINTS_PER_SERIES);
        assertThat(report.underlyingSteps().getFirst().step()).isZero();
        assertThat(report.underlyingSteps().getLast().step()).isEqualTo(steps);
        assertThat(report.underlyingSteps())
                .extracting(ScenarioCanvasValuator.UnderlyingStep::step)
                .contains(exactExpiryStep);
        assertThat(report.positions().getFirst().steps())
                .extracting(ScenarioCanvasValuator.PositionStep::step)
                .containsExactlyElementsOf(report.underlyingSteps().stream()
                        .map(ScenarioCanvasValuator.UnderlyingStep::step).toList());
        var animation = report.positions().getFirst().animation();
        assertThat(report.underlyingSteps().get(animation.terminalFrameIndex()).step())
                .isEqualTo(exactExpiryStep);
        assertThat(animation.terminalSessionProgress()).isEqualTo((double) expiryDay);
        assertThat(animation.boundaryReason()).isEqualTo("FINAL_CASH_SETTLEMENT");
        assertThat(animation.exposureResolvedAtBoundary()).isTrue();

        int[] sharedDisplaySteps = report.underlyingSteps().stream()
                .mapToInt(ScenarioCanvasValuator.UnderlyingStep::step).toArray();
        var pathProjection = new PathEnsembleService(null, Clock.systemUTC())
                .displayPaths(ensemble, null, 8, sharedDisplaySteps);
        assertThat(pathProjection.receipt().displaySteps())
                .containsExactlyElementsOf(report.underlyingSteps().stream()
                        .map(ScenarioCanvasValuator.UnderlyingStep::step).toList());
        assertThat(pathProjection.bands().get(animation.terminalFrameIndex()).step())
                .isEqualTo(exactExpiryStep);
        assertThat(pathProjection.bands().get(animation.terminalFrameIndex()).sessionProgress())
                .isEqualTo(animation.terminalSessionProgress());
        var focusedPricePath = pathProjection.paths().stream()
                .filter(path -> path.sourcePathIndex() == report.focusSourcePathIndex())
                .findFirst().orElseThrow();
        assertThat(focusedPricePath.prices()[animation.terminalFrameIndex()])
                .isEqualTo(report.underlyingSteps().get(animation.terminalFrameIndex()).focusPrice());

        var stored = new io.liftandshift.strikebench.plan.PlanOutcomeService.StoredEnsemble(
                "pen_shared_grid", "shared-grid-fingerprint", "PARAMETRIC", 1, null, "CURRENT",
                ensemble, IvSpec.flat(.25), ScenarioCanvasSpec.defaults(), .04,
                23_400.0 / spec.stepsPerDay(), "test", "MODELED",
                Instant.parse("2026-07-01T16:00:00Z").toString());
        var preview = new SimulationEngine(null, null, null, Clock.systemUTC(), null)
                .previewFromStored(stored, List.of(), null, .04);
        var previewProjection = SimulationEngine.projectPreview(ensemble,
                preview.sampleSourcePathIndices(), preview.sampleFocusIndex(), sharedDisplaySteps);
        assertThat(previewProjection.receipt().displaySteps())
                .containsExactlyElementsOf(report.underlyingSteps().stream()
                        .map(ScenarioCanvasValuator.UnderlyingStep::step).toList());
        assertThat(previewProjection.stepBands().get(animation.terminalFrameIndex()).step())
                .isEqualTo(exactExpiryStep);
        assertThat(previewProjection.stepBands().get(animation.terminalFrameIndex()).sessionProgress())
                .isEqualTo(animation.terminalSessionProgress());
        int previewFocusSource = previewProjection.sampleSourcePathIndices()
                .get(previewProjection.sampleFocusIndex());
        assertThat(previewFocusSource).isEqualTo(report.focusSourcePathIndex());
        assertThat(previewProjection.samples().get(previewProjection.sampleFocusIndex())
                .get(animation.terminalFrameIndex()))
                .isEqualTo(io.liftandshift.strikebench.util.Numbers.round2(
                        report.underlyingSteps().get(animation.terminalFrameIndex()).focusPrice()));
    }

    @Test void symbolScopeSharesTheProcessBudgetAndRefusesPathologicalWork() {
        LocalDate anchor = LocalDate.of(2026, 7, 1);
        var spec = new ScenarioSpec(ScenarioSpec.PathModel.GBM, ScenarioSpec.Shape.CHOP,
                756, 4, 0, .25, 0, 0, 0, 6, null, 93, 20);
        double[][] deliberatelyOversizedPathCount = new double[2_000][1];
        var ensemble = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.PARAMETRIC,
                new PathEnsembleService.Scope("MU", "observed", AnalysisContext.OBSERVED),
                100, spec, deliberatelyOversizedPathCount, null, PathGenerator.MODEL_VERSION, anchor);
        var shares = new PathPosition(anchor,
                List.of(Leg.stockShares(LegAction.BUY, 100, new BigDecimal("100"))));
        List<ScenarioCanvasValuator.PositionInput> positions = java.util.stream.IntStream.range(0, 32)
                .mapToObj(index -> new ScenarioCanvasValuator.PositionInput("p" + index,
                        "Position " + index, "REAL", "TRACKED_STRUCTURE", shares,
                        1, 1_000_000L, false))
                .toList();

        assertThatThrownBy(() -> new ScenarioCanvasValuator().value(ensemble, IvSpec.flat(.30),
                ScenarioCanvasSpec.defaults(), .04, positions))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Canvas comparison is too large")
                .hasMessageContaining("leg-steps");
    }

    @Test void templateReceiptDetectsDrift() {
        var unsigned = new ScenarioCanvasSpec.TemplateReceipt(
                ScenarioCanvasSpec.TemplateKind.HISTORICAL_REPLAY, "owned CSV", "OBSERVED",
                LocalDate.of(2026, 7, 1), LocalDate.of(2025, 1, 2), LocalDate.of(2025, 2, 2),
                21, true, true, "observed underlying; modeled options", "no look-ahead", "");
        var signed = unsigned.signed();
        assertThat(signed.validFingerprint()).isTrue();
        assertThat(new ScenarioCanvasSpec.TemplateReceipt(signed.kind(), signed.source(), signed.provenance(),
                signed.inputAsOf(), signed.windowFrom(), signed.windowTo(), signed.observations() + 1,
                signed.observed(), signed.noHindsight(), signed.legDayProvenance(), signed.note(),
                signed.fingerprint()).validFingerprint()).isFalse();
    }

    @Test void earningsTemplateUsesCanonicalSecFilingWindowAnalogsNotOrdinaryGaps() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-10T15:30:00Z"), ZoneOffset.UTC);
        FilingAnalogProvider provider = new FilingAnalogProvider(LocalDate.of(2026, 7, 10));
        MarketDataService market = new MarketDataService(List.of(provider), List.of(provider), List.of());
        EventService events = new EventService(market, clock);
        ScenarioCanvasTemplateService templates = new ScenarioCanvasTemplateService(market, events, clock);
        ScenarioSpec spec = ScenarioSpec.preset(ScenarioSpec.Shape.CHOP, 30, .30, 92, 40);

        var seeded = templates.apply("AAPL", "observed", AnalysisContext.OBSERVED, 100, .40,
                spec, ScenarioCanvasSpec.defaults(),
                new ScenarioCanvasTemplateService.Request(
                        ScenarioCanvasSpec.TemplateKind.EARNINGS_GAP_UP, null, null, null, null));

        assertThat(events.quarterlyReportDates("AAPL")).containsExactly(
                LocalDate.of(2026, 4, 30), LocalDate.of(2026, 1, 29), LocalDate.of(2025, 10, 30));
        assertThat(seeded.spec().waypoints()).singleElement().satisfies(pin ->
                assertThat(pin.priceRatio()).isCloseTo(1.085,
                        org.assertj.core.data.Offset.offset(.0001)));
        assertThat(seeded.canvas().template().observed()).isTrue();
        assertThat(seeded.canvas().template().source()).contains("SEC EDGAR filing dates");
        assertThat(seeded.canvas().template().observations()).isEqualTo(3);
        assertThat(seeded.canvas().template().note()).contains("filing-window analogs")
                .contains("proxies").doesNotContain("ordinary trading-day gaps");
    }

    @Test void shorterHistoricalReplayNamesWhereObservedClosesEndAndModelResumes() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-10T15:30:00Z"), ZoneOffset.UTC);
        FilingAnalogProvider provider = new FilingAnalogProvider(LocalDate.of(2026, 7, 10));
        MarketDataService market = new MarketDataService(List.of(provider), List.of(provider), List.of());
        var templates = new ScenarioCanvasTemplateService(market, new EventService(market, clock), clock);

        var seeded = templates.apply("AAPL", "observed", AnalysisContext.OBSERVED, 100, .35,
                ScenarioSpec.preset(ScenarioSpec.Shape.CHOP, 10, .30, 23, 40),
                ScenarioCanvasSpec.defaults(), new ScenarioCanvasTemplateService.Request(
                        ScenarioCanvasSpec.TemplateKind.HISTORICAL_REPLAY, null, null,
                        "2026-06-01", "2026-06-05"));

        assertThat(seeded.canvas().template().note())
                .contains("later canvas sessions resume the stored conditional path model")
                .contains("not historical observations");
    }

    private static final class FilingAnalogProvider implements MarketDataProvider, NewsFilingsProvider {
        private final LocalDate anchor;
        private final List<LocalDate> filings = List.of(LocalDate.of(2026, 4, 30),
                LocalDate.of(2026, 1, 29), LocalDate.of(2025, 10, 30));

        FilingAnalogProvider(LocalDate anchor) { this.anchor = anchor; }
        @Override public String name() { return "observed-test"; }
        @Override public Set<Domain> domains() { return Set.of(Domain.CANDLES); }
        @Override public List<SymbolMatch> lookup(String query) { return List.of(); }
        @Override public Optional<Quote> quote(String symbol) { return Optional.empty(); }
        @Override public List<LocalDate> expirations(String symbol) { return List.of(); }
        @Override public Optional<OptionChain> chain(String symbol, LocalDate expiration) { return Optional.empty(); }
        @Override public List<Candle> candles(String symbol, LocalDate from, LocalDate to) {
            java.util.ArrayList<Candle> out = new java.util.ArrayList<>();
            double priorClose = 100;
            for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
                if (!MarketHours.isTradingDay(date)) continue;
                double gap = date.equals(LocalDate.of(2025, 10, 28)) ? .04
                        : date.equals(LocalDate.of(2026, 1, 27)) ? .07
                        : date.equals(LocalDate.of(2026, 4, 28)) ? .10 : .001;
                BigDecimal open = BigDecimal.valueOf(priorClose * (1 + gap));
                BigDecimal close = open.multiply(BigDecimal.valueOf(1.0002));
                out.add(new Candle(date, open, close.max(open), open.min(close), close, 1_000_000, false));
                priorClose = close.doubleValue();
            }
            return out;
        }
        @Override public List<NewsItem> news(String symbol) {
            return filings.stream().map(date -> new NewsItem("AAPL", "10-Q quarterly report", "SEC EDGAR",
                    "https://example.invalid/" + date,
                    date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())).toList();
        }
    }
}
