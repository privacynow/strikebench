package io.liftandshift.strikebench.sim;

import io.liftandshift.strikebench.pricing.LognormalTerminal;
import io.liftandshift.strikebench.util.Numbers;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B4 expected-move receipt: the /api/research/{symbol}/expected-move endpoint serializes exactly
 * what MarketImpliedRange.of computes, and that computation is the ONE risk-neutral lognormal
 * terminal (no parallel expected-move math). Also pins the hide-on-invalid contract the cone relies
 * on (available=false rather than a fabricated band).
 */
class MarketImpliedRangeTest {

    @Test
    void ofProducesTheRiskNeutralRangeFromTheOneLognormalTerminal() {
        double spot = 100, iv = 0.4, rate = 0.04;
        int sessions = 21, calendarDays = 30;
        var r = SimulationEngine.MarketImpliedRange.of(spot, iv, sessions, "2026-02-20", calendarDays, rate);
        assertThat(r).isNotNull();

        // The bounds MUST equal the same LognormalTerminal the POP/range consolidation owns.
        var term = LognormalTerminal.of(spot, iv, sessions / 252.0, rate);
        double width = term.sd() * 0.994457883209753;
        assertThat(r.p16()).isEqualTo(Numbers.round2(Math.exp(term.mu() - width)));
        assertThat(r.p50()).isEqualTo(Numbers.round2(Math.exp(term.mu())));
        assertThat(r.p84()).isEqualTo(Numbers.round2(Math.exp(term.mu() + width)));

        assertThat(r.p16()).isLessThan(r.p50());
        assertThat(r.p50()).isLessThan(r.p84());
        assertThat(r.atmIv()).isEqualTo(iv);
        assertThat(r.horizonSessions()).isEqualTo(sessions);
        assertThat(r.expirationCalendarDays()).isEqualTo(calendarDays);
        assertThat(r.expiration()).isEqualTo("2026-02-20");
        assertThat(r.basis()).contains("Risk-neutral").contains("not a forecast");
    }

    @Test
    void ofHidesTheConeOnInvalidInputs() {
        assertThat(SimulationEngine.MarketImpliedRange.of(100, 0, 21, "e", 30, 0.04)).isNull();        // no IV
        assertThat(SimulationEngine.MarketImpliedRange.of(100, -0.1, 21, "e", 30, 0.04)).isNull();     // negative IV
        assertThat(SimulationEngine.MarketImpliedRange.of(100, Double.NaN, 21, "e", 30, 0.04)).isNull();
        assertThat(SimulationEngine.MarketImpliedRange.of(0, 0.4, 21, "e", 30, 0.04)).isNull();        // no spot
    }

    @Test
    void ofFloorsSessionsAtOne() {
        var r = SimulationEngine.MarketImpliedRange.of(100, 0.4, 0, "e", 1, 0.04);
        assertThat(r).isNotNull();
        assertThat(r.horizonSessions()).isEqualTo(1);
    }

    @Test
    void higherIvWidensTheRange() {
        var calm = SimulationEngine.MarketImpliedRange.of(100, 0.20, 30, "e", 45, 0.04);
        var tense = SimulationEngine.MarketImpliedRange.of(100, 0.80, 30, "e", 45, 0.04);
        assertThat(tense.p84() - tense.p16()).isGreaterThan(calm.p84() - calm.p16());
    }
}
