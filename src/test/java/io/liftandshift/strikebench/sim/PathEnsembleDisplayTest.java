package io.liftandshift.strikebench.sim;

import io.liftandshift.strikebench.db.AnalysisContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PathEnsembleDisplayTest {

    @Test void namedWaypointsRankOriginalPathsWithoutCreatingAnotherFan() {
        var base = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.PARAMETRIC,
                new PathEnsembleService.Scope("MU", "demo", AnalysisContext.OBSERVED), 100,
                ScenarioSpec.preset(ScenarioSpec.Shape.CHOP, 2, .25, 9L, 4),
                new double[][]{{100, 90, 99}, {100, 90, 100}, {100, 110, 100}, {100, 105, 98}},
                null, "paths-test");
        var named = new ScenarioSpec(ScenarioSpec.PathModel.GBM, ScenarioSpec.Shape.CHOP, 2, 1,
                0, .25, 0, 0, 0, 6, ScenarioSpec.Heston.fromVol(.25), 9L, 4,
                List.of(new ScenarioSpec.Waypoint(1, .90), new ScenarioSpec.Waypoint(2, 1.0, .02)));

        var projected = new PathEnsembleService(null,
                Clock.fixed(Instant.parse("2026-07-20T12:00:00Z"), ZoneOffset.UTC))
                .displayPaths(base, named, 2);

        assertThat(projected.selection()).isEqualTo("NEAREST_AUTHORED_WAYPOINTS");
        assertThat(projected.totalPathCount()).isEqualTo(4);
        assertThat(projected.paths()).extracting(PathEnsembleService.DisplayPath::sourcePathIndex)
                .containsExactly(1, 0);
        assertThat(projected.paths()).extracting(PathEnsembleService.DisplayPath::role)
                .containsExactly("FOCUS", "CONTEXT");
        assertThat(projected.receipt().version())
                .isEqualTo(PathEnsembleService.DISPLAY_SELECTION_VERSION);
        assertThat(projected.receipt().focusSourcePathIndex()).isEqualTo(1);
        assertThat(projected.receipt().waypointCount()).isEqualTo(2);
        assertThat(projected.receipt().explicitToleranceCount()).isEqualTo(1);
        assertThat(projected.receipt().withinToleranceCount()).isGreaterThanOrEqualTo(2);
        assertThat(projected.receipt().selectedWithinToleranceCount()).isEqualTo(2);
        assertThat(projected.bandBasis())
                .isEqualTo("CONDITIONED_NEAREST_QUINTILE_PLUS_FULL_TOLERANCE_SET");
        assertThat(projected.bandPathCount()).isGreaterThanOrEqualTo(projected.paths().size());
        assertThat(projected.bands()).hasSize(3);
        assertThat(projected.bands()).allSatisfy(band -> {
            assertThat(band.p10()).isLessThanOrEqualTo(band.p50());
            assertThat(band.p50()).isLessThanOrEqualTo(band.p90());
        });
        assertThat(projected.paths().getFirst().terminalQuantile()).isBetween(0.0, 1.0);
        assertThat(projected.paths()).allSatisfy(path -> assertThat(path.withinExplicitTolerance()).isTrue());
        assertThat(projected.paths().getFirst().prices()).containsExactly(100, 90, 100);
        // The projection is a defensive copy; it cannot mutate the persisted/path-owned matrix.
        double[] returned = projected.paths().getFirst().prices();
        returned[1] = 0;
        assertThat(base.paths()[1][1]).isEqualTo(90);
    }

    @Test void unconstrainedProjectionUsesTheActualTerminalMedianAsItsFocus() {
        var base = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.PARAMETRIC,
                new PathEnsembleService.Scope("MU", "demo", AnalysisContext.OBSERVED), 100,
                ScenarioSpec.preset(ScenarioSpec.Shape.CHOP, 1, .25, 9L, 6),
                new double[][]{{100, 80}, {100, 90}, {100, 100}, {100, 110}, {100, 120}, {100, 130}},
                null, "paths-test");

        var projected = new PathEnsembleService(null,
                Clock.fixed(Instant.parse("2026-07-20T12:00:00Z"), ZoneOffset.UTC))
                .displayPaths(base, null, 4);

        var focus = projected.paths().stream().filter(path -> "FOCUS".equals(path.role()))
                .findFirst().orElseThrow();
        assertThat(focus.sourcePathIndex()).isEqualTo(2);
        assertThat(focus.terminalQuantile()).isEqualTo(.4);
        assertThat(projected.receipt().rule()).isEqualTo("TERMINAL_QUANTILES");
        assertThat(projected.receipt().focusSourcePathIndex()).isEqualTo(2);
        assertThat(projected.receipt().withinToleranceCount()).isZero();
        assertThat(projected.receipt().selectedWithinToleranceCount()).isZero();
        assertThat(projected.bandBasis()).isEqualTo("FULL_STORED_ENSEMBLE");
        assertThat(projected.bandPathCount()).isEqualTo(6);
        assertThat(projected.bands()).hasSize(2);
    }

    @Test void fractionalSessionPinsUseStoredIntradayStepsAndABroaderBandNeighborhood() {
        var spec = new ScenarioSpec(ScenarioSpec.PathModel.GBM, ScenarioSpec.Shape.CHOP,
                1, 4, 0, .25, 0, 0, 0, 6, ScenarioSpec.Heston.fromVol(.25), 17L, 10);
        var base = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.PARAMETRIC,
                new PathEnsembleService.Scope("MU", "demo", AnalysisContext.OBSERVED), 100,
                spec, new double[][]{
                    {100, 96, 94, 96, 98}, {100, 98, 96, 98, 100},
                    {100, 101, 99, 101, 102}, {100, 103, 105, 106, 108},
                    {100, 97, 101, 104, 107}, {100, 99, 102, 105, 109},
                    {100, 102, 103, 102, 101}, {100, 95, 99, 103, 106},
                    {100, 101, 104, 108, 110}, {100, 104, 102, 104, 105}},
                null, "paths-test");

        var projected = new PathEnsembleService(null,
                Clock.fixed(Instant.parse("2026-07-20T12:00:00Z"), ZoneOffset.UTC))
                .displayPathsAtProgress(base, List.of(
                        new PathEnsembleService.DisplayWaypoint(.25, .96, .02),
                        new PathEnsembleService.DisplayWaypoint(.50, .94, .02),
                        new PathEnsembleService.DisplayWaypoint(1, .98, .02)), 1);

        assertThat(projected.paths()).hasSize(1);
        assertThat(projected.paths().getFirst().sourcePathIndex()).isZero();
        assertThat(projected.paths().getFirst().prices()).hasSize(5);
        assertThat(projected.bands()).hasSize(5);
        assertThat(projected.bands()).extracting(PathEnsembleService.DisplayBand::sessionProgress)
                .containsExactly(0.0, .25, .5, .75, 1.0);
        assertThat(projected.bandPathCount()).isGreaterThan(projected.paths().size());
        assertThat(projected.interpretation()).contains("no new paths were generated");
    }

    @Test void maximumResolutionArtifactProjectsABoundedDeterministicWireView() {
        var spec = new ScenarioSpec(ScenarioSpec.PathModel.GBM, ScenarioSpec.Shape.CHOP,
                756, 96, 0, .25, 0, 0, 0, 6,
                ScenarioSpec.Heston.fromVol(.25), 71L, 20);
        int steps = spec.totalSteps();
        double[][] source = new double[2][steps + 1];
        for (int step = 0; step <= steps; step++) {
            source[0][step] = 100 + step / 10_000.0;
            source[1][step] = 100 - step / 20_000.0;
        }
        var base = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.PARAMETRIC,
                new PathEnsembleService.Scope("MU", "demo", AnalysisContext.OBSERVED), 100,
                spec, source, null, "paths-test");

        var projected = new PathEnsembleService(null,
                Clock.fixed(Instant.parse("2026-07-20T12:00:00Z"), ZoneOffset.UTC))
                .displayPaths(base, null, PathEnsembleService.MAX_DISPLAY_PATHS);

        assertThat(projected.paths()).hasSize(2).allSatisfy(path ->
                assertThat(path.prices()).hasSize(PathEnsembleService.MAX_DISPLAY_POINTS_PER_SERIES));
        assertThat(projected.bands()).hasSize(PathEnsembleService.MAX_DISPLAY_POINTS_PER_SERIES);
        assertThat(projected.bands().getFirst().step()).isZero();
        assertThat(projected.bands().getLast().step()).isEqualTo(steps);
        assertThat(projected.receipt().sourcePointCount()).isEqualTo(steps + 1);
        assertThat(projected.receipt().returnedPointCount())
                .isEqualTo(PathEnsembleService.MAX_DISPLAY_POINTS_PER_SERIES);
        assertThat(projected.paths()).allSatisfy(path -> {
            assertThat(path.prices()[0]).isEqualTo(100);
            assertThat(path.prices()[path.prices().length - 1])
                    .isEqualTo(source[path.sourcePathIndex()][steps]);
        });
        assertThat(projected.interpretation()).contains("deterministic checkpoints")
                .contains("endpoints are retained");
    }
}
