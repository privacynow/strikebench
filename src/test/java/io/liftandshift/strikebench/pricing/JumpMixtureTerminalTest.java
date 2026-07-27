package io.liftandshift.strikebench.pricing;

import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The real-world / tail lane is a SEPARATE distribution from the risk-neutral lognormal, and it is
 * honestly gap-DIRECTIONAL: it lowers the probability of profit for a down-gap-EXPOSED credit
 * structure (a short put) and raises it for a down-gap-BENEFITING structure (a long put). It is not
 * a blanket haircut — it is the Merton jump-mixture the desk used to compute in the browser, now a
 * backend authority.
 */
class JumpMixtureTerminalTest {

    private static final LocalDate EXP = LocalDate.of(2026, 8, 21);
    private static final double SPOT = 100.0;
    private static final double IV = 0.40;
    private static final int DTE = 30;
    private static final double T_YEARS = DTE / 365.0;
    private static final double RATE = 0.04;
    // Horizon 1-sigma expected move in %, the SAME body vol both lanes see (IV*sqrt(T)).
    private static final double EM_PCT = IV * Math.sqrt(T_YEARS) * 100.0;
    // A gappy sector so the jump prior actually bites (verbatim desk "Semis": lam 0.09, gap 0.15).
    private static final String SECTOR = "Semiconductors, memory & storage";

    private static Leg opt(LegAction a, OptionType t, String strike, String prem) {
        return Leg.option(a, t, new BigDecimal(strike), EXP, 1, new BigDecimal(prem));
    }

    private static DoubleUnaryOperator payoffCents(PayoffCurve curve) {
        return s -> curve.profitAtCents(BigDecimal.valueOf(s));
    }

    private static double lognormalPop(PayoffCurve curve, List<BigDecimal> shortStrikes) {
        return ProbabilityMap.of(curve, SPOT, IV, T_YEARS, RATE, shortStrikes).pAnyProfit();
    }

    private static JumpMixtureTerminal.Tail jumpTail(PayoffCurve curve) {
        return JumpMixtureTerminal.tail(SPOT, SECTOR, 55.0, EM_PCT, false, null, true,
                curve.maxLossUnbounded(), curve.maxLossCents(), payoffCents(curve), null);
    }

    /** THE gate assertion: a down-gap-exposed short put has a strictly LOWER tail-aware POP than the
     *  risk-neutral lognormal POP over the exact same curve — the gap the smooth IV under-prices. */
    @Test void jumpMixturePopDropsForGapExposedShortPutVersusLognormal() {
        // Cash-secured short put: profits unless the underlying falls below the ~$88 breakeven.
        PayoffCurve shortPut = PayoffCurve.of(List.of(opt(LegAction.SELL, OptionType.PUT, "90", "2.00")), 1);

        double lognormalPop = lognormalPop(shortPut, List.of(new BigDecimal("90")));
        double jumpPop = jumpTail(shortPut).base().pop();

        assertThat(lognormalPop)
                .as("the lognormal already rates a 12%%-OTM short put a likely winner")
                .isGreaterThan(0.60);
        assertThat(jumpPop)
                .as("the down-gap tail eats into that win-rate — the whole point of the lane")
                .isLessThan(lognormalPop);
        assertThat(lognormalPop - jumpPop)
                .as("the drop is a MEANINGFUL correction, not rounding noise")
                .isGreaterThan(0.01);
        assertThat(jumpPop).isBetween(0.01, 0.99);
    }

    /** The lane is gap-DIRECTIONAL, not blanket pessimism: a down-gap HELPS a long put, so its
     *  tail-aware POP is strictly HIGHER than its lognormal POP over the same curve. */
    @Test void jumpMixtureRaisesPopForDownGapBenefitingLongPut() {
        PayoffCurve longPut = PayoffCurve.of(List.of(opt(LegAction.BUY, OptionType.PUT, "90", "2.00")), 1);

        double lognormalPop = lognormalPop(longPut, List.of());
        double jumpPop = jumpTail(longPut).base().pop();

        assertThat(jumpPop)
                .as("added down-gap mass can only increase a down-profiting structure's odds")
                .isGreaterThan(lognormalPop);
    }

    /** The calm/base/tense dial is monotone for a down-exposed structure: more fear -> lower POP. */
    @Test void gapStanceDialIsMonotoneForADownExposedStructure() {
        PayoffCurve shortPut = PayoffCurve.of(List.of(opt(LegAction.SELL, OptionType.PUT, "90", "2.00")), 1);
        JumpMixtureTerminal.Tail tail = jumpTail(shortPut);

        assertThat(tail.calm().pop())
                .as("calm (dial 0.5) is the most optimistic")
                .isGreaterThanOrEqualTo(tail.base().pop());
        assertThat(tail.base().pop())
                .as("tense (dial 1.9) is the most pessimistic")
                .isGreaterThanOrEqualTo(tail.tense().pop());
        assertThat(tail.calm().pop())
                .as("and the spread across the dial is real")
                .isGreaterThan(tail.tense().pop());
        assertThat(tail.calm().dial()).isEqualTo(0.5);
        assertThat(tail.base().dial()).isEqualTo(1.0);
        assertThat(tail.tense().dial()).isEqualTo(1.9);
    }

    /** The receipt is a complete, honest gap readout: direction, size, expected shortfall, provenance. */
    @Test void receiptCarriesTheHonestGapReadout() {
        PayoffCurve shortPut = PayoffCurve.of(List.of(opt(LegAction.SELL, OptionType.PUT, "90", "2.00")), 1);
        JumpMixtureTerminal.Tail tail = jumpTail(shortPut);
        JumpMixtureTerminal.Receipt base = tail.base();

        assertThat(tail.available()).isTrue();
        assertThat(tail.schemaVersion()).isEqualTo(JumpMixtureTerminal.SCHEMA);
        assertThat(tail.modelVersion()).isEqualTo(JumpMixtureTerminal.MODEL);
        assertThat(tail.headlineStance()).isEqualTo("BASE");
        assertThat(base.gapDir()).as("a short put is threatened by a DOWN gap").isEqualTo("-");
        assertThat(base.gapPct()).as("Semis characteristic gap ~15%").isBetween(14, 16);
        assertThat(base.expectedShortfallCents())
                .as("the worst-5% mean P/L is a real loss (cents)")
                .isNegative();
        assertThat(base.gapLossCents()).isNegative();
        assertThat(base.sector()).isEqualTo("Semis");
        assertThat(base.undefinedRisk()).isFalse();
        assertThat(base.intensityPct()).isPositive();
    }

    /** An unavailable curve (mixed-expiry / no anchor) is explicit, never a fabricated tail. */
    @Test void unavailableTailIsExplicitNotFabricated() {
        JumpMixtureTerminal.Tail tail = JumpMixtureTerminal.tail(0.0, null, 55.0, 0.0, false, null,
                false, false, 0L, null, "mixed-expiration package");

        assertThat(tail.available()).isFalse();
        assertThat(tail.base()).isNull();
        assertThat(tail.pop()).isNull();
        assertThat(tail.unavailableReason()).contains("mixed-expiration");
    }

    @Test void missingCalibrationEvidenceNeverFallsBackToBrowserFixtureDefaults() {
        PayoffCurve shortPut = PayoffCurve.of(
                List.of(opt(LegAction.SELL, OptionType.PUT, "90", "2.00")), 1);

        JumpMixtureTerminal.Tail missingMove = JumpMixtureTerminal.tail(
                SPOT, SECTOR, 55.0, 0.0, false, null, true,
                false, shortPut.maxLossCents(), payoffCents(shortPut), null);
        JumpMixtureTerminal.Tail missingRank = JumpMixtureTerminal.tail(
                SPOT, SECTOR, Double.NaN, EM_PCT, false, null, true,
                false, shortPut.maxLossCents(), payoffCents(shortPut), null);

        assertThat(missingMove.available()).isFalse();
        assertThat(missingMove.unavailableReason()).contains("expected move");
        assertThat(missingRank.available()).isFalse();
        assertThat(missingRank.unavailableReason()).contains("IV rank");
        assertThatThrownBy(() -> JumpMixtureTerminal.of(
                SECTOR, 55.0, EM_PCT, null, false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stance");
    }

    /** A defined-risk credit put spread is also down-gap exposed: its POP drops too. */
    @Test void jumpMixturePopDropsForDefinedRiskCreditPutSpread() {
        PayoffCurve creditSpread = PayoffCurve.of(List.of(
                opt(LegAction.SELL, OptionType.PUT, "95", "3.00"),
                opt(LegAction.BUY, OptionType.PUT, "90", "1.20")), 1);

        double lognormalPop = lognormalPop(creditSpread, List.of(new BigDecimal("95")));
        double jumpPop = jumpTail(creditSpread).base().pop();

        assertThat(jumpPop).isLessThan(lognormalPop);
        assertThat(jumpTail(creditSpread).base().undefinedRisk()).isFalse();
    }

    /** The sector-label -> prior-bucket mapping resolves the universe's real labels. */
    @Test void resolveKeyMapsUniverseLabelsToPriorBuckets() {
        assertThat(JumpMixtureTerminal.resolveKey("Semiconductors, memory & storage")).isEqualTo("Semis");
        assertThat(JumpMixtureTerminal.resolveKey("Healthcare")).isEqualTo("Health");
        assertThat(JumpMixtureTerminal.resolveKey("Index & macro ETFs")).isEqualTo("Index");
        assertThat(JumpMixtureTerminal.resolveKey("Technology")).isEqualTo("Tech");
        assertThat(JumpMixtureTerminal.resolveKey("Other / unclassified")).isEqualTo("default");
        assertThat(JumpMixtureTerminal.resolveKey(null)).isEqualTo("default");
    }
}
