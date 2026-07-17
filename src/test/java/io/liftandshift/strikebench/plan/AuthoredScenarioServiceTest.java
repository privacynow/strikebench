package io.liftandshift.strikebench.plan;

import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.sim.IvSpec;
import io.liftandshift.strikebench.sim.PathEnsembleService;
import io.liftandshift.strikebench.sim.ScenarioSpec;
import io.liftandshift.strikebench.support.TestDb;
import io.liftandshift.strikebench.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The authored-scenario freeze: it must author FROM a stored fan of the SAME Plan context,
 * round-trip the spec with its waypoints exactly, persist the standing-decision-9 fill label,
 * and mint a deterministic fingerprint receipt.
 */
class AuthoredScenarioServiceTest {
    private Db db;
    private PlanService plans;
    private PlanOutcomeService outcomes;
    private AuthoredScenarioService authored;

    @BeforeEach void setUp() {
        db = TestDb.fresh();
        Clock clock = Clock.fixed(Instant.parse("2026-07-16T15:00:00Z"), ZoneOffset.UTC);
        plans = new PlanService(db, clock);
        outcomes = new PlanOutcomeService(db, clock);
        authored = new AuthoredScenarioService(db, clock);
    }

    @AfterEach void close() { if (db != null) db.close(); }

    private Plan.View newPlan(String name, String symbol) {
        return plans.create(null, Plan.MarketKind.DEMO, null, null,
                new Plan.CreateRequest(name, symbol, "DIRECTIONAL", null, null,
                        "bullish", 30, null, "conservative", null, null, null, null));
    }

    private PlanOutcomeService.StoredEnsemble storeBaseFan(Plan.View plan) {
        ScenarioSpec spec = ScenarioSpec.preset(ScenarioSpec.Shape.CHOP, 1, 0.25, 4242L, 3);
        var ensemble = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.PARAMETRIC,
                new PathEnsembleService.Scope(plan.symbol(), "demo", AnalysisContext.OBSERVED), 250, spec,
                new double[][]{{250, 251}, {250, 249}, {250, 252}}, null, "paths-test");
        return outcomes.saveEnsemble(null, plan, ensemble, IvSpec.flat(0.25), 0.04, null,
                Json.parse("{\"basis\":\"canvas-base\"}"));
    }

    private static ScenarioSpec authoredSpec(List<ScenarioSpec.Waypoint> pins, ScenarioSpec.PathModel model) {
        return new ScenarioSpec(model, ScenarioSpec.Shape.CHOP, 30, 1, 0.05, 0.25,
                6, -0.02, 0.03, 6, ScenarioSpec.Heston.fromVol(0.25), 7L, 100, pins);
    }

    @Test void authoredScenarioRoundTripsWaypointsFillLabelAndLineage() {
        Plan.View plan = newPlan("canvas-roundtrip", "AAPL");
        var base = storeBaseFan(plan);
        var pins = List.of(new ScenarioSpec.Waypoint(5, 0.95, null),
                new ScenarioSpec.Waypoint(12, 1.06, 0.02));
        ScenarioSpec spec = authoredSpec(pins, ScenarioSpec.PathModel.GBM);

        var saved = authored.save(null, plan, base.id(), spec);
        assertThat(saved.baseEnsembleId()).isEqualTo(base.id());
        assertThat(saved.contextRev()).isEqualTo(plan.context().rev());
        assertThat(saved.waypointFill()).isEqualTo("EXACT_CONDITIONAL");
        assertThat(saved.fingerprint()).hasSize(64);
        assertThat(saved.spec()).isEqualTo(spec.sane());
        assertThat(saved.spec().waypoints()).containsExactlyElementsOf(pins);

        var loaded = authored.load(null, plan.id(), saved.id());
        assertThat(loaded).isEqualTo(saved);
        assertThat(loaded.spec().waypoints().get(1).tolerance()).isEqualTo(0.02);

        var listed = authored.list(null, plan.id(), plan.context().rev());
        assertThat(listed).hasSize(1);
        assertThat(listed.getFirst()).isEqualTo(saved);
        assertThat(authored.list(null, plan.id(), plan.context().rev() + 1)).isEmpty();
    }

    @Test void fingerprintIsDeterministicForIdenticalAuthoringAndSensitiveToTheSpec() {
        Plan.View plan = newPlan("canvas-fingerprint", "AAPL");
        var base = storeBaseFan(plan);
        var pins = List.of(new ScenarioSpec.Waypoint(5, 0.95));
        var first = authored.save(null, plan, base.id(), authoredSpec(pins, ScenarioSpec.PathModel.GBM));
        var again = authored.save(null, plan, base.id(), authoredSpec(pins, ScenarioSpec.PathModel.GBM));
        assertThat(again.id()).isNotEqualTo(first.id());
        assertThat(again.fingerprint()).isEqualTo(first.fingerprint());

        var movedPin = authored.save(null, plan, base.id(),
                authoredSpec(List.of(new ScenarioSpec.Waypoint(6, 0.95)), ScenarioSpec.PathModel.GBM));
        assertThat(movedPin.fingerprint()).isNotEqualTo(first.fingerprint());
    }

    @Test void nonGaussianAuthoringPersistsTheGuidedInterpolationLabel() {
        Plan.View plan = newPlan("canvas-honesty", "AAPL");
        var base = storeBaseFan(plan);
        var pins = List.of(new ScenarioSpec.Waypoint(9, 1.04));
        var heston = authored.save(null, plan, base.id(), authoredSpec(pins, ScenarioSpec.PathModel.HESTON));
        assertThat(heston.waypointFill()).isEqualTo("GUIDED_INTERPOLATION");
        assertThat(authored.load(null, plan.id(), heston.id()).waypointFill())
                .isEqualTo("GUIDED_INTERPOLATION");
        // No pins = plain Monte Carlo, honestly labeled NONE.
        var plain = authored.save(null, plan, base.id(), authoredSpec(List.of(), ScenarioSpec.PathModel.HESTON));
        assertThat(plain.waypointFill()).isEqualTo("NONE");
    }

    @Test void authoringRequiresTheSameFanAndTheCurrentPlanAssumptions() {
        Plan.View plan = newPlan("canvas-guards", "AAPL");
        var base = storeBaseFan(plan);
        ScenarioSpec spec = authoredSpec(List.of(new ScenarioSpec.Waypoint(5, 0.95)),
                ScenarioSpec.PathModel.GBM);

        assertThatThrownBy(() -> authored.save(null, plan, "pen_doesnotexist", spec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not belong to the current Plan assumptions");

        Plan.View other = newPlan("canvas-guards-other", "MSFT");
        var otherFan = storeBaseFan(other);
        assertThatThrownBy(() -> authored.save(null, plan, otherFan.id(), spec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not belong to the current Plan assumptions");

        Plan.View revised = plans.updateContext(null, plan.id(), new Plan.ContextUpdateRequest(
                plan.version(), null, 45, null, null, null, null, null, null, java.util.Set.of()));
        assertThat(revised.context().rev()).isEqualTo(plan.context().rev() + 1);
        assertThatThrownBy(() -> authored.save(null, plan, base.id(), spec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("assumptions changed");
    }
}
