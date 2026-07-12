package io.liftandshift.strikebench.market.sim;

import io.liftandshift.strikebench.model.OptionQuote;
import io.liftandshift.strikebench.pricing.BlackScholes;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The release gates the adversarial review demanded of the simulated market's MATH:
 * speed-invariant paths, replay identity, a real factor model, continuous expiry convergence,
 * option-surface no-arbitrage invariants, stable strike listings, and input validation.
 */
class SimWorldGateTest {

    private static SimulatedWorld.Config config(String worldId, String name, Map<String, Double> symbolBetas,
                                                Map<String, Double> startSpots, String scenario,
                                                double volAnnual, long seed, String startSimTime, double speed) {
        return new SimulatedWorld.Config(worldId, name, symbolBetas, startSpots, scenario, volAnnual,
                seed, startSimTime, speed, null, null);
    }

    private static SimulatedWorld world(long seed, double speed) {
        return new SimulatedWorld(config(
                "w-gate", "Gate world", Map.of("ACME", 1.0, "BETA", 0.3, "HIBETA", 1.5),
                Map.of("ACME", 100.0, "BETA", 50.0, "HIBETA", 200.0), "CHOP", 0.30, seed,
                "2026-07-13T09:30:00", speed));
    }

    @Test
    void theSpeedKnobNeverChangesThePath() {
        // Speed = SIM-SECONDS PER REAL SECOND: at 30x one tick(=1s) is one 30-sec quantum, at
        // 300x it is ten. Same seed + same reached sim-time => identical prices, any speed.
        SimulatedWorld slow = world(7, 30);
        SimulatedWorld fast = world(7, 300);
        for (int i = 0; i < 600; i++) slow.tick(); // 600 ticks x 1 quantum
        for (int i = 0; i < 60; i++) fast.tick();  // 60 ticks x 10 quanta
        assertThat(slow.ticks()).isEqualTo(fast.ticks());
        assertThat(slow.simTime()).isEqualTo(fast.simTime());
        assertThat(slow.quote("ACME").orElseThrow().last())
                .isEqualByComparingTo(fast.quote("ACME").orElseThrow().last());
        assertThat(slow.quote("HIBETA").orElseThrow().last())
                .isEqualByComparingTo(fast.quote("HIBETA").orElseThrow().last());
    }

    @Test
    void replayReconstructsTheExactWorldIncludingInjectedEvents() {
        SimulatedWorld live = world(11, 150);
        for (int i = 0; i < 40; i++) live.tick();
        live.injectMove("ACME", -0.06);
        live.injectVolShift(0.10);
        for (int i = 0; i < 40; i++) live.tick();
        live.setSpeed(600);
        for (int i = 0; i < 10; i++) live.tick();

        SimulatedWorld restored = world(11, 150);
        restored.replayTo(live.ticks(), live.eventLog());

        assertThat(restored.ticks()).isEqualTo(live.ticks());
        assertThat(restored.simTime()).isEqualTo(live.simTime());
        for (String sym : List.of("ACME", "BETA", "HIBETA")) {
            assertThat(restored.quote(sym).orElseThrow().last())
                    .isEqualByComparingTo(live.quote(sym).orElseThrow().last());
        }
        // The IV shock survives replay too: same chain, same ATM price.
        LocalDate exp = live.expirations().getFirst();
        assertThat(atmMid(restored, "ACME", exp)).isEqualTo(atmMid(live, "ACME", exp));
    }

    @Test
    void betaIsARealFactorLoadingNotJustCorrelation() {
        // A beta-1.5 name must carry visibly more variance than a beta-0.3 name over many quanta.
        SimulatedWorld w = world(23, 300);
        double vLo = 0, vHi = 0, prevLo = 50, prevHi = 200;
        int n = 0;
        for (int i = 0; i < 500; i++) {
            w.tick();
            double lo = w.quote("BETA").orElseThrow().last().doubleValue();
            double hi = w.quote("HIBETA").orElseThrow().last().doubleValue();
            if (i > 0) {
                double rLo = Math.log(lo / prevLo), rHi = Math.log(hi / prevHi);
                vLo += rLo * rLo; vHi += rHi * rHi; n++;
            }
            prevLo = lo; prevHi = hi;
        }
        double ratio = Math.sqrt(vHi / n) / Math.sqrt(vLo / n);
        // sigma(beta=1.5)^2 = 1.5^2*sigmaM^2 + sigmaIdio^2 vs 0.3^2*sigmaM^2 + sigmaIdio^2:
        // with sigmaIdio = 0.6*sigmaM the theoretical ratio is ~2.3; demand clearly > 1.5.
        assertThat(ratio).isGreaterThan(1.5);
    }

    @Test
    void optionValueConvergesContinuouslyToIntrinsicAtTheSimBell() {
        // Run the clock deep into expiration day and watch ATM time value die smoothly —
        // the old whole-day floor held half a day of premium at 3:59pm (junior P0).
        SimulatedWorld w = world(3, 30);
        LocalDate exp = w.expirations().getFirst();
        // Advance to expiration morning.
        while (w.simTime().toLocalDate().isBefore(exp)) w.tick();
        double tMorning = w.timeToExpiryYears(exp);
        // Advance to ~15:55 on expiration day.
        while (w.simTime().toLocalDate().equals(exp)
                && w.simTime().toLocalTime().isBefore(LocalTime.of(15, 55))) w.tick();
        double tClose = w.timeToExpiryYears(exp);
        assertThat(tClose).isLessThan(tMorning / 20); // minutes vs a session-plus
        var chain = w.chain("ACME", exp).orElseThrow();
        double spot = chain.underlyingPrice().doubleValue();
        OptionQuote atm = nearest(chain.calls(), spot);
        double intrinsic = Math.max(0, spot - atm.strike().doubleValue());
        // Minutes from the bell, ATM time value is pennies — not half a day of premium.
        assertThat(atm.mid().doubleValue() - intrinsic).isLessThan(0.60);
    }

    @Test
    void surfaceInvariants_parityConvexityCalendarVerticalsUncrossed() {
        SimulatedWorld w = world(31, 150);
        for (int i = 0; i < 30; i++) w.tick();
        List<LocalDate> exps = w.expirations();
        LocalDate near = exps.get(1), far = exps.get(exps.size() - 1);
        var cNear = w.chain("ACME", near).orElseThrow();
        var cFar = w.chain("ACME", far).orElseThrow();
        double spot = cNear.underlyingPrice().doubleValue();
        double tN = w.timeToExpiryYears(near);

        List<OptionQuote> calls = cNear.calls();
        for (int i = 0; i < calls.size(); i++) {
            OptionQuote c = calls.get(i);
            // no crossed books
            assertThat(c.bid()).isLessThanOrEqualTo(c.ask());
            // Put-call parity BAND: the intrinsic floor is early-exercise (American-style) value,
            // and American puts legitimately sit up to K(1 - e^{-rT}) above European parity —
            // so the honest no-arbitrage bound is that band plus the two 1c floors and rounding.
            OptionQuote p = cNear.puts().get(i);
            double lhs = c.mid().doubleValue() - p.mid().doubleValue();
            double rhs = spot - c.strike().doubleValue() * Math.exp(-0.03 * tN);
            double band = c.strike().doubleValue() * (1 - Math.exp(-0.03 * tN)) + 0.04;
            assertThat(Math.abs(lhs - rhs)).isLessThanOrEqualTo(band);
            // vertical bounds: 0 <= C(K1)-C(K2) <= K2-K1
            if (i + 1 < calls.size()) {
                double dC = c.mid().doubleValue() - calls.get(i + 1).mid().doubleValue();
                double dK = calls.get(i + 1).strike().doubleValue() - c.strike().doubleValue();
                assertThat(dC).isBetween(-1e-6, dK + 1e-6);
            }
            // strike convexity: C(K-) - 2C(K) + C(K+) >= -eps
            if (i > 0 && i + 1 < calls.size()) {
                double conv = calls.get(i - 1).mid().doubleValue() - 2 * c.mid().doubleValue()
                        + calls.get(i + 1).mid().doubleValue();
                assertThat(conv).isGreaterThan(-0.02);
            }
        }
        // calendar monotonicity: far-dated ATM call >= near-dated ATM call
        OptionQuote atmN = nearest(cNear.calls(), spot);
        OptionQuote atmF = nearest(cFar.calls(), atmN.strike().doubleValue());
        assertThat(atmF.mid().doubleValue()).isGreaterThanOrEqualTo(atmN.mid().doubleValue() - 0.02);
    }

    @Test
    void strikesNeverDelistWhenTheSpotMoves() {
        // The grid anchors at inception: a -30% crash (the demo lever!) must NOT delist the
        // protective put someone is holding — that was the "hedge vanishes when it pays" defect.
        SimulatedWorld w = world(41, 150);
        LocalDate exp = w.expirations().get(2);
        var before = w.chain("ACME", exp).orElseThrow();
        double strike100 = 100.0;
        assertThat(before.find(io.liftandshift.strikebench.model.OptionType.PUT,
                java.math.BigDecimal.valueOf(strike100))).isPresent();
        w.injectMove("ACME", -0.30);
        var after = w.chain("ACME", exp).orElseThrow();
        assertThat(after.find(io.liftandshift.strikebench.model.OptionType.PUT,
                java.math.BigDecimal.valueOf(strike100)))
                .as("the 100 put must stay listed after a 30%% crash")
                .isPresent();
    }

    @Test
    void inputsAreValidatedLoudly() {
        SimulatedWorld w = world(1, 1);
        assertThatThrownBy(() -> w.injectMove("ACME", -1.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> w.injectMove("NOPE", -0.05)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> w.injectVolShift(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config("x", "x", Map.of("A", 9.0), Map.of(),
                "CHOP", 0.3, 1, "2026-07-13T09:30:00", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config("x", "x", Map.of("A", 1.0), Map.of("A", -5.0),
                "CHOP", 0.3, 1, "2026-07-13T09:30:00", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config("x", "x", Map.of("A", 1.0), Map.of(),
                "CHOP", 9.9, 1, "2026-07-13T09:30:00", 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void realizedVolMatchesTheConfiguredVolAtEverySpeed() {
        // The variance-scaling defect made fast worlds ~25-40% wilder than their own quoted IV.
        // With fixed quanta the realized vol of the PATH is speed-independent by construction;
        // check the seeded history is also in family with the configured total vol.
        SimulatedWorld w = world(57, 300);
        double rv = w.realizedVol("ACME");
        // sigmaTotal for beta=1 with sigmaM=0.30, sigmaIdio=0.18 => sqrt(.09+.0324) ~ 0.35
        assertThat(rv).isBetween(0.24, 0.46);
    }

    @Test
    void zeroDteStaysListedUntilTheBellAndHolidayFridaysRollBack() {
        // A Friday-morning world lists ITS OWN day as the front expiry until 16:00.
        SimulatedWorld fri = new SimulatedWorld(config(
                "w-fri", "Friday", Map.of("ACME", 1.0), Map.of("ACME", 100.0), "CHOP", 0.3, 5,
                "2026-07-17T10:00:00", 1)); // 2026-07-17 is a Friday
        assertThat(fri.expirations().getFirst()).isEqualTo(LocalDate.parse("2026-07-17"));
        // Holiday Friday (2026-07-03 observed Independence Day) rolls the weekly BACK to Thursday.
        SimulatedWorld hol = new SimulatedWorld(config(
                "w-hol", "Holiday", Map.of("ACME", 1.0), Map.of("ACME", 100.0), "CHOP", 0.3, 5,
                "2026-06-29T10:00:00", 1));
        assertThat(hol.expirations()).contains(LocalDate.parse("2026-07-02"));
        assertThat(hol.expirations()).doesNotContain(LocalDate.parse("2026-07-03"));
    }

    @Test
    void speedIsASimTimeMultiplierAndStepMovesExactlyOneQuantum() {
        // 1x = REAL TIME: one tick (one real second) advances less than a quantum; 30 ticks = one.
        SimulatedWorld rt = world(71, 1);
        for (int i = 0; i < 29; i++) rt.tick();
        assertThat(rt.ticks()).isZero();
        rt.tick();
        assertThat(rt.ticks()).isEqualTo(1);
        // Fractions accumulate exactly: 26x (a session in ~15 min) over 30 ticks = 26 quanta.
        SimulatedWorld fast = world(71, 26);
        for (int i = 0; i < 30; i++) fast.tick();
        assertThat(fast.ticks()).isEqualTo(26);
        // The band's Step button contract: exactly one quantum, at any speed.
        SimulatedWorld stepped = world(71, 1);
        stepped.stepQuanta(1);
        assertThat(stepped.ticks()).isEqualTo(1);
        assertThat(stepped.simTime()).isEqualTo(java.time.LocalDateTime.parse("2026-07-13T09:30:30"));
    }

    @Test
    void volEventScenarioActuallyMovesImpliedVol() {
        // M7: a "vol event" must BE one — IV builds ~1.5x into the event, crushes to ~0.75x at
        // the second open, and mean-reverts. The old scenario only shaped the PRICE path.
        SimulatedWorld w = new SimulatedWorld(config(
                "w-vol", "Vol event", Map.of("ACME", 1.0), Map.of("ACME", 100.0), "VOL_EVENT", 0.30,
                13, "2026-07-13T09:30:00", 30));
        LocalDate exp = w.expirations().get(2); // far enough that intrinsic never dominates
        double ivStart = atmIv(w, "ACME", exp);
        for (int i = 0; i < 770; i++) w.tick();   // deep into session 1: anticipation build
        double ivPeak = atmIv(w, "ACME", exp);
        for (int i = 0; i < 100; i++) w.tick();   // across the 2nd open: the crush
        double ivCrushed = atmIv(w, "ACME", exp);
        assertThat(ivPeak).isGreaterThan(ivStart * 1.3);
        assertThat(ivCrushed).isLessThan(ivPeak * 0.65);
        assertThat(ivCrushed).isLessThan(ivStart);
        // And the arc REPLAYS: a restored world reaches the same IV at the same quantum.
        SimulatedWorld r = new SimulatedWorld(config(
                "w-vol", "Vol event", Map.of("ACME", 1.0), Map.of("ACME", 100.0), "VOL_EVENT", 0.30,
                13, "2026-07-13T09:30:00", 30));
        r.replayTo(w.ticks(), w.eventLog());
        assertThat(atmIv(r, "ACME", exp)).isEqualTo(ivCrushed);
    }

    @Test
    void perSymbolCalibrationDrivesVolAndIv() {
        // M8: a calibrated symbol moves at ITS vol and quotes ITS IV, not the session knob's.
        SimulatedWorld w = new SimulatedWorld(new SimulatedWorld.Config(
                "w-cal", "Calibrated", Map.of("TAME", 1.0, "WILD", 1.0),
                Map.of("TAME", 100.0, "WILD", 100.0), "CHOP", 0.30, 91, "2026-07-13T09:30:00", 300,
                Map.of("TAME", 0.15, "WILD", 0.60), Map.of("TAME", 0.16, "WILD", 0.62)));
        LocalDate exp = w.expirations().get(2);
        double ivTame = atmIv(w, "TAME", exp), ivWild = atmIv(w, "WILD", exp);
        assertThat(ivWild).isGreaterThan(ivTame * 2.5);
        double vT = 0, vW = 0, pT = 100, pW = 100;
        for (int i = 0; i < 400; i++) {
            w.tick();
            double t = w.quote("TAME").orElseThrow().last().doubleValue();
            double x = w.quote("WILD").orElseThrow().last().doubleValue();
            double rT = Math.log(t / pT), rW = Math.log(x / pW);
            vT += rT * rT; vW += rW * rW; pT = t; pW = x;
        }
        assertThat(Math.sqrt(vW / 400) / Math.sqrt(vT / 400))
                .as("realized vol ratio must reflect the calibrated 0.60 vs 0.15")
                .isGreaterThan(2.0);
    }

    @Test
    void optionGreeksUseTheDisplayedPerDayAndPerVolPointUnits() {
        SimulatedWorld w = world(83, 1);
        LocalDate exp = w.expirations().get(1);
        var chain = w.chain("ACME", exp).orElseThrow();
        var quote = nearest(chain.calls(), chain.underlyingPrice().doubleValue());
        double s = chain.underlyingPrice().doubleValue();
        double k = quote.strike().doubleValue();
        double t = w.timeToExpiryYears(exp);
        double iv = quote.iv();

        assertThat(quote.theta()).isCloseTo(
                io.liftandshift.strikebench.pricing.BlackScholes.theta(true, s, k, t, 0.03, 0, iv) / 365.0,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(quote.vega()).isCloseTo(
                io.liftandshift.strikebench.pricing.BlackScholes.vega(s, k, t, 0.03, 0, iv) / 100.0,
                org.assertj.core.data.Offset.offset(1e-12));
    }

    private static double atmIv(SimulatedWorld w, String sym, LocalDate exp) {
        var chain = w.chain(sym, exp).orElseThrow();
        return nearest(chain.calls(), chain.underlyingPrice().doubleValue()).iv();
    }

    private static OptionQuote nearest(List<OptionQuote> quotes, double strike) {
        return quotes.stream()
                .min(java.util.Comparator.comparingDouble(q -> Math.abs(q.strike().doubleValue() - strike)))
                .orElseThrow();
    }

    private static java.math.BigDecimal atmMid(SimulatedWorld w, String sym, LocalDate exp) {
        var chain = w.chain(sym, exp).orElseThrow();
        return nearest(chain.calls(), chain.underlyingPrice().doubleValue()).mid();
    }
}
