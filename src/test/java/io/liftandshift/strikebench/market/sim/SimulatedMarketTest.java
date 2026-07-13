package io.liftandshift.strikebench.market.sim;

import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.OptionQuote;
import io.liftandshift.strikebench.model.Freshness;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The simulated market must be a market a real trader cannot arbitrage in five minutes, and a
 * statistician can reproduce exactly: coherent books, intrinsic floors, a virtual exchange clock
 * that skips closed hours, and tick-for-tick determinism from the seed.
 */
class SimulatedMarketTest {

    private static SimulatedWorld world(long seed) {
        return new SimulatedWorld(new SimulatedWorld.Config(
                "w-test", "Test world", Map.of("ACME", 1.0, "BETA", 0.6),
                Map.of("ACME", 100.0, "BETA", 50.0), "CHOP", 0.30, seed,
                "2026-07-13T09:30:00", 300, null, null)); // 300x: one tick(=1 real second) = ten 30-sec quanta
    }

    @Test
    void identicalSeedsReproduceTheIdenticalWorld() {
        SimulatedWorld a = world(4242), b = world(4242);
        for (int i = 0; i < 200; i++) { a.tick(); b.tick(); }
        assertThat(a.quote("ACME").orElseThrow().last())
                .isEqualByComparingTo(b.quote("ACME").orElseThrow().last());
        assertThat(a.simTime()).isEqualTo(b.simTime());
        // ...and a DIFFERENT seed produces a different world.
        SimulatedWorld c = world(7);
        for (int i = 0; i < 200; i++) c.tick();
        assertThat(c.quote("ACME").orElseThrow().last())
                .isNotEqualByComparingTo(a.quote("ACME").orElseThrow().last());
    }

    @Test
    void everythingIsLabeledSimulatedAndBooksAreCoherent() {
        SimulatedWorld w = world(1);
        for (int i = 0; i < 50; i++) w.tick();
        var q = w.quote("ACME").orElseThrow();
        assertThat(q.freshness()).isEqualTo(Freshness.SIMULATED);
        assertThat(q.source()).isEqualTo("simulated");
        assertThat(q.bid()).isLessThan(q.ask()); // never crossed

        LocalDate exp = w.expirations().getFirst();
        OptionChain chain = w.chain("ACME", exp).orElseThrow();
        assertThat(chain.freshness()).isEqualTo(Freshness.SIMULATED);
        double spot = chain.underlyingPrice().doubleValue();
        double prevStrike = -1, prevCallMid = Double.MAX_VALUE;
        for (OptionQuote c : chain.calls()) {
            double k = c.strike().doubleValue();
            assertThat(k).isGreaterThan(prevStrike);                        // strike monotonicity
            assertThat(c.bid()).isLessThanOrEqualTo(c.ask());               // no crossed books
            double intrinsic = Math.max(0, spot - k);
            assertThat(c.mid().doubleValue()).isGreaterThanOrEqualTo(intrinsic); // intrinsic floor
            double mid = c.mid().doubleValue();
            assertThat(mid).isLessThanOrEqualTo(prevCallMid + 1e-9);        // call value convex-ish decreasing in K
            prevCallMid = mid; prevStrike = k;
        }
        for (OptionQuote p : chain.puts()) {
            double intrinsic = Math.max(0, p.strike().doubleValue() - spot);
            assertThat(p.mid().doubleValue()).isGreaterThanOrEqualTo(intrinsic);
        }
        // Greeks present, IV positive, OI synthesized.
        OptionQuote atm = chain.calls().get(chain.calls().size() / 2);
        assertThat(atm.iv()).isGreaterThan(0.05);
        assertThat(atm.delta()).isBetween(0.0, 1.0);
        assertThat(atm.openInterest()).isGreaterThan(0);
    }

    @Test
    void theVirtualClockSkipsClosedHoursAndRollsDailyBars() {
        SimulatedWorld w = world(9);
        // 300x speed = 300 sim-sec/tick; a 6.5h session (23,400 sim-sec) = 78 ticks.
        for (int i = 0; i < 100; i++) w.tick();
        // The clock is INSIDE a session (09:30..16:00 ET) on a TRADING day — never a weekend/night.
        var t = w.simTime();
        assertThat(io.liftandshift.strikebench.market.MarketHours.isTradingDay(t.toLocalDate())).isTrue();
        assertThat(t.toLocalTime()).isBetween(java.time.LocalTime.of(9, 30), java.time.LocalTime.of(16, 0));
        // Crossing at least one close rolled a NEW daily bar beyond the seeded history.
        var candles = w.candles("ACME", t.toLocalDate().minusDays(400), t.toLocalDate());
        assertThat(candles.size()).isGreaterThan(250);
        // History is coherent for HV/charts from day one.
        assertThat(candles.getFirst().close().signum()).isPositive();
    }

    @Test
    void eventInjectionMovesTheWorldImmediately() {
        SimulatedWorld w = world(5);
        double before = w.quote("ACME").orElseThrow().last().doubleValue();
        w.injectMove("ACME", -0.08);
        double after = w.quote("ACME").orElseThrow().last().doubleValue();
        assertThat(after).isCloseTo(before * 0.92, org.assertj.core.data.Offset.offset(0.01));
        // A vol shock widens option prices at the next chain build.
        LocalDate exp = w.expirations().getFirst();
        double atmBefore = w.chain("ACME", exp).orElseThrow().calls().stream()
                .min(java.util.Comparator.comparingDouble(c -> Math.abs(c.strike().doubleValue() - after)))
                .orElseThrow().mid().doubleValue();
        w.injectVolShift(0.30);
        double atmAfter = w.chain("ACME", exp).orElseThrow().calls().stream()
                .min(java.util.Comparator.comparingDouble(c -> Math.abs(c.strike().doubleValue() - after)))
                .orElseThrow().mid().doubleValue();
        assertThat(atmAfter).isGreaterThan(atmBefore);
    }

    @Test
    void betaCorrelatesComponentsWithTheMarketFactor() {
        // Over many ticks, a beta-1 symbol and a beta-0.6 symbol move with positive correlation.
        SimulatedWorld w = world(11);
        double prevA = 100, prevB = 50; int agree = 0, n = 0;
        for (int i = 0; i < 400; i++) {
            w.tick();
            double a = w.quote("ACME").orElseThrow().last().doubleValue();
            double b = w.quote("BETA").orElseThrow().last().doubleValue();
            if (i > 0) { n++; if ((a - prevA) * (b - prevB) > 0) agree++; }
            prevA = a; prevB = b;
        }
        assertThat((double) agree / n).isGreaterThan(0.55); // co-move more often than chance
    }

    @Test
    void planRehearsalFollowsTheStoredPathAtFixedKnotsAndCannotBeMutated() {
        var replay = new SimulatedWorld.ReplaySource("plan-1", "ensemble-1", "receipt-1", 7,
                "SAMPLE", "ACME", "paths-2", new double[]{100, 102, 98},
                new double[]{0.20, 0.30, 0.25}, 60, 0.041);
        var cfg = new SimulatedWorld.Config("w-replay", "Exact rehearsal", Map.of("ACME", 1.0),
                Map.of("ACME", 100.0), "PLAN_REPLAY", 0.25, 8,
                "2026-07-13T09:30:00", 26, Map.of("ACME", 0.25), Map.of("ACME", 0.20));
        SimulatedWorld world = new SimulatedWorld(cfg, replay);

        world.stepQuanta(1); // 30 seconds: halfway to the first stored knot
        assertThat(world.quote("ACME").orElseThrow().last().doubleValue()).isCloseTo(101.0,
                org.assertj.core.data.Offset.offset(0.0001));
        world.stepQuanta(1);
        assertThat(world.quote("ACME").orElseThrow().last().doubleValue()).isCloseTo(102.0,
                org.assertj.core.data.Offset.offset(0.0001));
        world.stepQuanta(2);
        assertThat(world.quote("ACME").orElseThrow().last().doubleValue()).isCloseTo(98.0,
                org.assertj.core.data.Offset.offset(0.0001));
        assertThat(world.replayComplete()).isTrue();
        assertThat(world.rateAnnual()).isEqualTo(0.041);
        world.stepQuanta(1);
        assertThat(world.ticks()).isEqualTo(4); // completion is terminal, not a clamped path with a moving clock
        assertThatThrownBy(() -> world.injectMove("ACME", -0.05)).hasMessageContaining("exact Plan rehearsal");
        assertThatThrownBy(() -> world.injectVolShift(0.10)).hasMessageContaining("exact Plan rehearsal");
    }
}
