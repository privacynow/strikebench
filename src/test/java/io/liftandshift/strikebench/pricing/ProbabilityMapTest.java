package io.liftandshift.strikebench.pricing;

import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The full probability picture must be internally consistent for every payoff shape. */
class ProbabilityMapTest {

    private static final LocalDate EXP = LocalDate.parse("2026-08-21");

    private static Leg leg(LegAction a, OptionType t, String strike, String price) {
        return Leg.option(a, t, new BigDecimal(strike), EXP, 1, new BigDecimal(price));
    }

    /** MU-geometry condor: tight shorts around spot, wide wings, fat credit. */
    private static PayoffCurve condor() {
        return PayoffCurve.of(List.of(
                leg(LegAction.SELL, OptionType.PUT, "980", "21.85"),
                leg(LegAction.BUY, OptionType.PUT, "950", "13.00"),
                leg(LegAction.SELL, OptionType.CALL, "1005", "20.65"),
                leg(LegAction.BUY, OptionType.CALL, "1035", "12.10")), 1);
    }

    @Test
    void probabilitiesPartitionAndMatchTheMuIncident() {
        // Spot 991.83, IV 75.6%, one trading session (1/252) — the exact regime of the real trade.
        var r = ProbabilityMap.of(condor(), 991.83, 0.756, 3.0 / 365.0, 0.0,
                List.of(new BigDecimal("980"), new BigDecimal("1005")));
        // Partition: any-profit + max-loss + partial-loss ≈ 1 (each outcome is exactly one of these).
        assertThat(r.pAnyProfit() + r.pMaxLoss() + r.pPartial()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.02));
        // The independently verified hand numbers: ~34% any profit, ~14% full credit, ~50-56% max loss.
        assertThat(r.pAnyProfit()).isBetween(0.30, 0.38);
        assertThat(r.pMaxProfit()).isBetween(0.10, 0.19);
        assertThat(r.pMaxLoss()).isBetween(0.45, 0.60);
        // Max-profit region is a SUBSET of the profit region.
        assertThat(r.pMaxProfit()).isLessThanOrEqualTo(r.pAnyProfit());
        // CVaR95 for a defined-risk condor is bounded by max loss and is a loss.
        assertThat(r.cvar95Cents()).isLessThan(0);
        assertThat(-r.cvar95Cents()).isLessThanOrEqualTo(condor().maxLossCents());
        // Touch odds are at least the expire-beyond odds and within [0,1].
        assertThat(r.touches()).hasSize(2);
        r.touches().forEach(t -> assertThat(t.probability()).isBetween(0.0, 1.0));
        // A zero-rate call is still explicit about its q=0 approximation.
        assertThat(r.basis()).containsIgnoringCase("zero-rate risk-neutral")
                .containsIgnoringCase("q=0");
        // ...and the r-variant IS risk-neutral, with the rate disclosed.
        var rr = ProbabilityMap.of(condor(), 991.83, 0.756, 3.0 / 365.0, 0.04,
                List.of(new BigDecimal("980"), new BigDecimal("1005")));
        assertThat(rr.basis()).containsIgnoringCase("risk-neutral");
        assertThat(rr.basis()).contains("r=4.0%");
        // With r>0 over 3 days the numbers barely move — same hand-verified regime.
        assertThat(rr.pAnyProfit()).isBetween(0.30, 0.38);
    }

    @Test
    void undefinedRiskShowsStressInsteadOfAFalseCap() {
        // Naked short call: no max loss exists; the stress figure must be a real negative scenario.
        PayoffCurve naked = PayoffCurve.of(List.of(
                leg(LegAction.SELL, OptionType.CALL, "100", "3.00")), 1);
        var r = ProbabilityMap.of(naked, 100, 0.4, 30 / 365.0, 0.0,
                List.of(new BigDecimal("100")));
        assertThat(naked.maxLossUnbounded()).isTrue();
        assertThat(r.pMaxLoss()).isZero();           // there IS no "max loss" to land on
        assertThat(r.stressLossCents()).isLessThan(0); // but the +20%/2σ stress is honest and large
        assertThat(r.stressLossCents()).isLessThan(-100_00); // worse than -$100 on a $100 stock +20% move
    }

    @Test
    void longCallMapIsCoherent() {
        PayoffCurve lc = PayoffCurve.of(List.of(
                leg(LegAction.BUY, OptionType.CALL, "100", "2.50")), 1);
        var r = ProbabilityMap.of(lc, 100, 0.3, 30 / 365.0, 0.0, List.of());
        // Debit single: max loss = the debit, hit whenever S <= K; pMaxLoss is large and real.
        assertThat(r.pMaxLoss()).isGreaterThan(0.3);
        assertThat(r.pAnyProfit()).isBetween(0.05, 0.6);
        // Uncapped upside: "max profit" plateau never registers.
        assertThat(r.pMaxProfit()).isZero();
        assertThat(r.touches()).isEmpty();
    }

    @Test
    void degenerateInputsRefuseHonestly() {
        var r = ProbabilityMap.of(condor(), 0, 0.5, 0.01, 0.0, List.of());
        assertThat(r.basis()).contains("undefined");
        assertThat(r.pAnyProfit()).isZero();
    }

    @org.junit.jupiter.api.Test
    void expectedValueFollowsTheProposedPackagePriceExactly() {
        // R-CONTRACTS: a package-level price adjustment must shift EVERY statistic — the EV
        // integral included. EV(adjusted) = EV(base) + adjust, to the cent (review P0).
        var legs = java.util.List.of(
                leg(io.liftandshift.strikebench.model.LegAction.SELL, io.liftandshift.strikebench.model.OptionType.PUT, "100", "2.00"),
                leg(io.liftandshift.strikebench.model.LegAction.BUY, io.liftandshift.strikebench.model.OptionType.PUT, "95", "0.80"));
        PayoffCurve base = PayoffCurve.of(legs, 1);
        PayoffCurve proposed = PayoffCurve.of(legs, 1, -3500L); // you accept $35 less credit
        long evBase = base.expectedValueCents(100, 0.3, 30 / 365.0, 0.04);
        long evProp = proposed.expectedValueCents(100, 0.3, 30 / 365.0, 0.04);
        org.assertj.core.api.Assertions.assertThat(evProp - evBase).isEqualTo(-3500L);
        // ...and probabilities move consistently with the shifted breakevens.
        var mBase = ProbabilityMap.of(base, 100, 0.3, 30 / 365.0, 0.04, java.util.List.of());
        var mProp = ProbabilityMap.of(proposed, 100, 0.3, 30 / 365.0, 0.04, java.util.List.of());
        org.assertj.core.api.Assertions.assertThat(mProp.pAnyProfit()).isLessThan(mBase.pAnyProfit());
    }
}
