package io.liftandshift.strikebench.sim;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The scenario canvas's engine contract: authored waypoints are hit exactly, Gaussian fills are
 * genuine conditional sampling (the bridge law holds between pins), non-Gaussian fills carry the
 * GUIDED_INTERPOLATION label (standing decision 9), determinism survives pinning, and the
 * trading-calendar derivation counts real NYSE sessions instead of assuming 252.
 */
class WaypointPathTest {

    private final PathGenerator gen = new PathGenerator();

    private static ScenarioSpec gbm(int horizonDays, double vol, long seed, int paths,
                                    List<ScenarioSpec.Waypoint> pins) {
        return new ScenarioSpec(ScenarioSpec.PathModel.GBM, ScenarioSpec.Shape.CHOP,
                horizonDays, 1, 0.0, vol, 0, 0, 0, 6, ScenarioSpec.Heston.fromVol(vol),
                seed, paths, pins);
    }

    // ---- exact pinning ----

    @Test
    void gbmMultiWaypointPathsHitEveryPinExactly() {
        var pins = List.of(new ScenarioSpec.Waypoint(10, 0.95),
                new ScenarioSpec.Waypoint(20, 1.08, 0.02),
                new ScenarioSpec.Waypoint(40, 1.00));
        PathGenerator.Generated result = gen.generateLabeled(gbm(60, 0.30, 42, 200, pins), 100, null);
        assertThat(result.waypointFill()).isEqualTo(PathGenerator.WaypointFill.EXACT_CONDITIONAL);
        for (double[] path : result.paths()) {
            assertThat(path[0]).isEqualTo(100.0);
            assertThat(path[10]).isCloseTo(95.0, within(1e-9));
            assertThat(path[20]).isCloseTo(108.0, within(1e-9));
            assertThat(path[40]).isCloseTo(100.0, within(1e-9));
        }
    }

    @Test
    void intradayGranularityPinsTheEndOfTheWaypointDay() {
        var pins = List.of(new ScenarioSpec.Waypoint(3, 1.04));
        var spec = new ScenarioSpec(ScenarioSpec.PathModel.GBM, ScenarioSpec.Shape.CHOP,
                5, 4, 0.0, 0.30, 0, 0, 0, 6, ScenarioSpec.Heston.fromVol(0.30), 5, 50, pins);
        double[][] paths = gen.generate(spec, 100, null);
        for (double[] path : paths) assertThat(path[3 * 4]).isCloseTo(104.0, within(1e-9));
    }

    @Test
    void brownianBridgeKeepsItsEndpointAndHitsInteriorPins() {
        var pins = List.of(new ScenarioSpec.Waypoint(10, 0.94));
        var spec = new ScenarioSpec(ScenarioSpec.PathModel.BROWNIAN_BRIDGE, ScenarioSpec.Shape.GRIND_UP,
                20, 1, 0.20, 0.30, 0, 0, 0, 6, ScenarioSpec.Heston.fromVol(0.30), 1, 60, pins);
        double endpoint = 100 * Math.exp(0.20 * 20 / 252.0);
        for (double[] path : gen.generate(spec, 100, null)) {
            assertThat(path[10]).isCloseTo(94.0, within(1e-9));
            assertThat(path[20]).isCloseTo(endpoint, within(1e-6)); // the model's endpoint pin survives
        }

        // A waypoint ON the final day overrides the model's endpoint pin — the author's pin wins.
        var overridden = new ScenarioSpec(ScenarioSpec.PathModel.BROWNIAN_BRIDGE, ScenarioSpec.Shape.GRIND_UP,
                20, 1, 0.20, 0.30, 0, 0, 0, 6, ScenarioSpec.Heston.fromVol(0.30), 1, 60,
                List.of(new ScenarioSpec.Waypoint(20, 1.10)));
        for (double[] path : gen.generate(overridden, 100, null)) {
            assertThat(path[20]).isCloseTo(110.0, within(1e-9));
        }
    }

    // ---- the fills stay distribution-honest between pins (exact bridge law) ----

    @Test
    void gbmDispersionBetweenPinsMatchesTheBrownianBridgeLaw() {
        // Pins at day 10 and day 30, both at spot. Conditionally the log price between them is a
        // Brownian bridge: Var[log S_t] = sigma^2*dt*(t-a)(b-t)/(b-a). The correction must neither
        // crush the model's dispersion nor inflate it — that is what "exact conditional" means.
        double sigma = 0.30, dt = 1.0 / 252.0;
        var pins = List.of(new ScenarioSpec.Waypoint(10, 1.0), new ScenarioSpec.Waypoint(30, 1.0));
        double[][] paths = gen.generate(gbm(40, sigma, 77, 4000, pins), 100, null);
        double sdMid = logStdevAt(paths, 20);
        double sdQuarter = logStdevAt(paths, 15);
        double theoryMid = sigma * Math.sqrt(dt * (10.0 * 10.0 / 20.0));      // (20-10)(30-20)/(30-10)
        double theoryQuarter = sigma * Math.sqrt(dt * (5.0 * 15.0 / 20.0));   // (15-10)(30-15)/(30-10)
        assertThat(sdMid).isBetween(theoryMid * 0.92, theoryMid * 1.08);
        assertThat(sdQuarter).isBetween(theoryQuarter * 0.92, theoryQuarter * 1.08);
        assertThat(logStdevAt(paths, 10)).isLessThan(1e-12); // pins have zero dispersion
        assertThat(logStdevAt(paths, 30)).isLessThan(1e-12);
        // The conditional mean between equal pins is the pin level itself (no smuggled drift).
        double meanMid = 0;
        for (double[] p : paths) meanMid += Math.log(p[20] / 100.0);
        assertThat(meanMid / paths.length).isCloseTo(0.0, within(0.005));
    }

    @Test
    void tailBeyondTheLastPinDiffusesFreelyFromThePinnedValue() {
        // After the last pin the conditional law is plain diffusion started at the pin: at
        // day 30+k the log variance is sigma^2*dt*k, anchored at the day-30 pin.
        double sigma = 0.30, dt = 1.0 / 252.0;
        var pins = List.of(new ScenarioSpec.Waypoint(30, 1.05));
        double[][] paths = gen.generate(gbm(40, sigma, 91, 4000, pins), 100, null);
        double sdTail = logStdevAt(paths, 40);
        double theory = sigma * Math.sqrt(dt * 10);
        assertThat(sdTail).isBetween(theory * 0.92, theory * 1.08);
        double mean = 0;
        for (double[] p : paths) mean += Math.log(p[40] / (100.0 * 1.05));
        // Martingale-corrected noise: E[exp(tail)] = 1, so the mean log sits at -var/2.
        assertThat(mean / paths.length).isCloseTo(-0.5 * sigma * sigma * dt * 10, within(0.005));
    }

    // ---- honesty labels (standing decision 9) ----

    @Test
    void nonGaussianModelsCarryTheGuidedInterpolationLabelAndStillHitPins() {
        var pins = List.of(new ScenarioSpec.Waypoint(7, 0.93), new ScenarioSpec.Waypoint(15, 1.02));
        double[] hist = new double[120];
        for (int i = 0; i < hist.length; i++) hist[i] = 0.012 * Math.sin(i / 3.0);
        for (var model : new ScenarioSpec.PathModel[]{
                ScenarioSpec.PathModel.STUDENT_T, ScenarioSpec.PathModel.JUMP_DIFFUSION,
                ScenarioSpec.PathModel.HESTON, ScenarioSpec.PathModel.BLOCK_BOOTSTRAP}) {
            var spec = new ScenarioSpec(model, ScenarioSpec.Shape.CHOP, 20, 1, 0.0, 0.30,
                    6, -0.02, 0.03, 4.5, ScenarioSpec.Heston.fromVol(0.30), 11, 80, pins);
            PathGenerator.Generated result = gen.generateLabeled(spec, 100, hist);
            assertThat(result.waypointFill())
                    .as(model + " must be labeled guided interpolation, never exact conditional sampling")
                    .isEqualTo(PathGenerator.WaypointFill.GUIDED_INTERPOLATION);
            for (double[] path : result.paths()) {
                assertThat(path[7]).isCloseTo(93.0, within(1e-9));
                assertThat(path[15]).isCloseTo(102.0, within(1e-9));
            }
        }
    }

    @Test
    void labelIsNoneWithoutWaypointsAndExactOnlyForGaussianModels() {
        assertThat(PathGenerator.waypointFill(gbm(20, 0.3, 1, 10, List.of())))
                .isEqualTo(PathGenerator.WaypointFill.NONE);
        assertThat(PathGenerator.waypointFill(null)).isEqualTo(PathGenerator.WaypointFill.NONE);
        var pin = List.of(new ScenarioSpec.Waypoint(5, 1.01));
        assertThat(PathGenerator.waypointFill(gbm(20, 0.3, 1, 10, pin)))
                .isEqualTo(PathGenerator.WaypointFill.EXACT_CONDITIONAL);
        var bridge = new ScenarioSpec(ScenarioSpec.PathModel.BROWNIAN_BRIDGE, ScenarioSpec.Shape.CHOP,
                20, 1, 0, 0.3, 0, 0, 0, 6, ScenarioSpec.Heston.fromVol(0.3), 1, 10, pin);
        assertThat(PathGenerator.waypointFill(bridge)).isEqualTo(PathGenerator.WaypointFill.EXACT_CONDITIONAL);
        // The stored-fan object derives the same label — downstream surfaces read it from there.
        var ensemble = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.PARAMETRIC,
                new PathEnsembleService.Scope("MU", "observed", null), 100,
                gbm(20, 0.3, 1, 10, pin), new double[][]{{100, 101}}, null, "paths-test");
        assertThat(ensemble.waypointFill()).isEqualTo(PathGenerator.WaypointFill.EXACT_CONDITIONAL);
    }

    // ---- determinism ----

    @Test
    void sameSeedReproducesIdenticalWaypointPathsAndPathCountIndependenceHolds() {
        var pins = List.of(new ScenarioSpec.Waypoint(10, 0.97), new ScenarioSpec.Waypoint(25, 1.06));
        double[][] a = gen.generate(gbm(40, 0.3, 42, 50, pins), 100, null);
        double[][] b = gen.generate(gbm(40, 0.3, 42, 50, pins), 100, null);
        for (int p = 0; p < a.length; p++) assertThat(a[p]).containsExactly(b[p]);
        double[][] more = gen.generate(gbm(40, 0.3, 42, 200, pins), 100, null);
        assertThat(more[0]).containsExactly(a[0]);
        assertThat(more[49]).containsExactly(a[49]);
        double[][] other = gen.generate(gbm(40, 0.3, 43, 50, pins), 100, null);
        assertThat(other[0]).isNotEqualTo(a[0]);
    }

    // ---- validation ----

    @Test
    void waypointValidationRejectsDisorderOutOfHorizonAndNonsenseLevels() {
        assertThatThrownBy(() -> gbm(30, 0.3, 1, 10, List.of(
                new ScenarioSpec.Waypoint(12, 1.0), new ScenarioSpec.Waypoint(5, 1.1))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ordered");
        assertThatThrownBy(() -> gbm(30, 0.3, 1, 10, List.of(
                new ScenarioSpec.Waypoint(9, 1.0), new ScenarioSpec.Waypoint(9, 1.1))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ordered");
        assertThatThrownBy(() -> gbm(30, 0.3, 1, 10, List.of(new ScenarioSpec.Waypoint(31, 1.0))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("beyond the scenario horizon");
        assertThatThrownBy(() -> new ScenarioSpec.Waypoint(0, 1.0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dayIndex");
        assertThatThrownBy(() -> new ScenarioSpec.Waypoint(5, 0.0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
        assertThatThrownBy(() -> new ScenarioSpec.Waypoint(5, -1.2))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
        assertThatThrownBy(() -> new ScenarioSpec.Waypoint(5, 1.0, -0.01))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tolerance");
    }

    @Test
    void sanePreservesWaypointsAndOldConstructorShapeMeansNoPins() {
        var pins = List.of(new ScenarioSpec.Waypoint(10, 0.95, 0.01), new ScenarioSpec.Waypoint(20, 1.05));
        var spec = gbm(30, 0.3, 7, 100, pins).sane();
        assertThat(spec.waypoints()).containsExactlyElementsOf(pins);
        var plain = new ScenarioSpec(ScenarioSpec.PathModel.GBM, ScenarioSpec.Shape.CHOP,
                30, 1, 0, 0.3, 0, 0, 0, 6, ScenarioSpec.Heston.fromVol(0.3), 7, 100);
        assertThat(plain.waypoints()).isEmpty();
        assertThat(plain.sane().waypoints()).isEmpty();
    }

    // ---- trading calendar ----

    @Test
    void calendarHorizonCountsRealSessionsNotCalendarDays() {
        // 2026-07-03 is the observed Independence Day holiday (July 4 falls on a Saturday):
        // (Wed 07-01, Wed 07-08] holds Thu 02, Mon 06, Tue 07, Wed 08 — four sessions, not five.
        assertThat(ScenarioSpec.calendarHorizonDays(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 8)))
                .isEqualTo(4);
        // A plain week with a weekend inside: (Fri 07-10, Fri 07-17] = 5 sessions over 7 calendar days.
        assertThat(ScenarioSpec.calendarHorizonDays(LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 17)))
                .isEqualTo(5);
        assertThat(ScenarioSpec.sessionDates(LocalDate.of(2026, 7, 1), 4)).containsExactly(
                LocalDate.of(2026, 7, 2), LocalDate.of(2026, 7, 6),
                LocalDate.of(2026, 7, 7), LocalDate.of(2026, 7, 8));
    }

    @Test
    void calendarDtUsesRealElapsedTimeWhileLegacyDtStaysFrozen() {
        var spec = gbm(4, 0.3, 1, 10, List.of());
        // Legacy dt() is fingerprint-load-bearing and must never move.
        assertThat(spec.dt()).isEqualTo(1.0 / 252.0);
        // Four sessions from Wed 2026-07-01 end on Wed 2026-07-08 (holiday + weekend inside):
        // seven real calendar days of clock time, not 4/252 years.
        double expected = (7.0 / 365.0) / 4.0;
        assertThat(spec.calendarDt(LocalDate.of(2026, 7, 1))).isCloseTo(expected, within(1e-12));
        assertThat(spec.calendarDt(LocalDate.of(2026, 7, 1))).isGreaterThan(spec.dt());
    }

    // ---- helpers ----

    private static double logStdevAt(double[][] paths, int step) {
        double mean = 0;
        for (double[] p : paths) mean += Math.log(p[step]);
        mean /= paths.length;
        double var = 0;
        for (double[] p : paths) {
            double d = Math.log(p[step]) - mean;
            var += d * d;
        }
        return Math.sqrt(var / paths.length);
    }
}
