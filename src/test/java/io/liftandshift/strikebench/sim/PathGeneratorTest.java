package io.liftandshift.strikebench.sim;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The simulation core's mathematical contract: reproducibility, model behavior, OHLC bounds, shapes. */
class PathGeneratorTest {

    private final PathGenerator gen = new PathGenerator();

    private ScenarioSpec gbm(long seed, int paths) {
        return new ScenarioSpec(ScenarioSpec.PathModel.GBM, ScenarioSpec.Shape.CHOP,
                60, 1, 0.0, 0.30, 0, 0, 0, 6, ScenarioSpec.Heston.fromVol(0.30), seed, paths);
    }

    @Test
    void sameSeedReproducesIdenticalPaths() {
        double[][] a = gen.generate(gbm(42, 50), 100, null);
        double[][] b = gen.generate(gbm(42, 50), 100, null);
        assertThat(a.length).isEqualTo(b.length);
        for (int p = 0; p < a.length; p++) assertThat(a[p]).containsExactly(b[p]);
        // a different seed changes the paths
        double[][] c = gen.generate(gbm(43, 50), 100, null);
        assertThat(c[0]).isNotEqualTo(a[0]);
    }

    @Test
    void pathsStartAtSpotAndStayPositive() {
        double[][] paths = gen.generate(gbm(7, 30), 250, null);
        for (double[] path : paths) {
            assertThat(path[0]).isEqualTo(250.0);
            for (double px : path) assertThat(px).isGreaterThan(0);
        }
    }

    @Test
    void gbmRealizedVolMatchesTheRequestedSigma() {
        // Over many paths the sample stdev of daily log-returns ≈ sigma*sqrt(dt).
        var spec = gbm(11, 400);
        double[][] paths = gen.generate(spec, 100, null);
        double dt = spec.dt();
        double target = 0.30 * Math.sqrt(dt);
        double sumSq = 0; long n = 0;
        for (double[] path : paths)
            for (int i = 1; i < path.length; i++) { double r = Math.log(path[i] / path[i - 1]); sumSq += r * r; n++; }
        double realized = Math.sqrt(sumSq / n);
        assertThat(realized).isBetween(target * 0.85, target * 1.15); // within 15%
    }

    @Test
    void studentTHasFatterTailsThanGbm() {
        double kurtT = excessKurtosis(gen.generate(new ScenarioSpec(
                ScenarioSpec.PathModel.STUDENT_T, ScenarioSpec.Shape.CHOP, 120, 1, 0, 0.3, 0, 0, 0, 3.0,
                ScenarioSpec.Heston.fromVol(0.3), 5, 300), 100, null));
        double kurtG = excessKurtosis(gen.generate(gbm(5, 300), 100, null));
        assertThat(kurtT).isGreaterThan(kurtG); // fatter tails ⇒ higher excess kurtosis
    }

    @Test
    void jumpDiffusionWidensTheOutcomeSpread() {
        // SAME base diffusion vol (0.30) as the GBM baseline, so the jumps are the only difference.
        var jump = new ScenarioSpec(ScenarioSpec.PathModel.JUMP_DIFFUSION, ScenarioSpec.Shape.CHOP,
                60, 1, 0, 0.30, 30, -0.03, 0.04, 6, ScenarioSpec.Heston.fromVol(0.30), 9, 400);
        double spreadJump = terminalSpread(gen.generate(jump, 100, null));
        double spreadGbm = terminalSpread(gen.generate(gbm(9, 400), 100, null));
        assertThat(spreadJump).isGreaterThan(spreadGbm);
    }

    @Test
    void hestonStaysFiniteAndPositive() {
        var heston = new ScenarioSpec(ScenarioSpec.PathModel.HESTON, ScenarioSpec.Shape.CHOP,
                90, 1, 0, 0.35, 0, 0, 0, 6, new ScenarioSpec.Heston(3, 0.1225, 0.4, -0.6, 0.1225), 3, 200);
        double[][] paths = gen.generate(heston, 100, null);
        for (double[] path : paths) for (double px : path) {
            assertThat(Double.isFinite(px)).isTrue();
            assertThat(px).isGreaterThan(0);
        }
    }

    @Test
    void brownianBridgePinsTheEndpointToTheDriftedTarget() {
        // With zero noise-at-the-ends, every path ends at exactly s0*exp(total drift).
        var bridge = new ScenarioSpec(ScenarioSpec.PathModel.BROWNIAN_BRIDGE, ScenarioSpec.Shape.GRIND_UP,
                20, 1, 0.20, 0.3, 0, 0, 0, 6, ScenarioSpec.Heston.fromVol(0.3), 1, 40);
        double t = 20 / 252.0, target = 100 * Math.exp(0.20 * t);
        double[][] paths = gen.generate(bridge, 100, null);
        for (double[] path : paths) assertThat(path[path.length - 1]).isCloseTo(target, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void valleyShapeDipsBelowTheStartThenRecovers() {
        var valley = new ScenarioSpec(ScenarioSpec.PathModel.BROWNIAN_BRIDGE, ScenarioSpec.Shape.SELLOFF_REBOUND,
                40, 1, 0.0, 0.10, 0, 0, 0, 6, ScenarioSpec.Heston.fromVol(0.10), 2, 1);
        double[] path = gen.generate(valley, 100, null)[0];
        double min = 100; for (double px : path) min = Math.min(min, px);
        assertThat(min).isLessThan(99); // a genuine dip below the $100 start
        assertThat(path[path.length - 1]).isCloseTo(100, org.assertj.core.data.Offset.offset(0.5)); // recovers to ~start
    }

    @Test
    void intradayBridgeRespectsOhlcBounds() {
        Rng rng = new Rng(123);
        double open = 100, high = 104, low = 98, close = 101;
        double[] path = gen.intradayBridge(open, high, low, close, 40, rng);
        assertThat(path[0]).isEqualTo(open);
        assertThat(path[path.length - 1]).isEqualTo(close);
        double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
        for (double px : path) { mn = Math.min(mn, px); mx = Math.max(mx, px); }
        assertThat(mx).isLessThanOrEqualTo(high + 1e-9); // never above the real high
        assertThat(mn).isGreaterThanOrEqualTo(low - 1e-9); // never below the real low
        assertThat(mx).isCloseTo(high, org.assertj.core.data.Offset.offset(1e-6)); // the high is touched
        assertThat(mn).isCloseTo(low, org.assertj.core.data.Offset.offset(1e-6));  // the low is touched
    }

    // ---- helpers ----

    private static double terminalSpread(double[][] paths) {
        double[] finals = new double[paths.length];
        for (int p = 0; p < paths.length; p++) finals[p] = paths[p][paths[p].length - 1];
        return stdev(finals);
    }

    private static double excessKurtosis(double[][] paths) {
        java.util.List<Double> rs = new java.util.ArrayList<>();
        for (double[] path : paths)
            for (int i = 1; i < path.length; i++) rs.add(Math.log(path[i] / path[i - 1]));
        double m = rs.stream().mapToDouble(x -> x).average().orElse(0);
        double var = rs.stream().mapToDouble(x -> (x - m) * (x - m)).average().orElse(1e-12);
        double m4 = rs.stream().mapToDouble(x -> Math.pow(x - m, 4)).average().orElse(0);
        return m4 / (var * var) - 3.0;
    }

    private static double stdev(double[] a) {
        double m = 0; for (double x : a) m += x; m /= a.length;
        double v = 0; for (double x : a) v += (x - m) * (x - m); return Math.sqrt(v / a.length);
    }
}
