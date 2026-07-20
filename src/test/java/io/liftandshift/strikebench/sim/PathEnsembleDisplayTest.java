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
    }
}
