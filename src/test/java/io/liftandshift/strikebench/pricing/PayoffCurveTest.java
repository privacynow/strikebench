package io.liftandshift.strikebench.pricing;

import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PayoffCurveTest {

    private static final LocalDate EXP = LocalDate.of(2026, 8, 21);

    private static Leg opt(LegAction a, OptionType t, String strike, int ratio, String prem) {
        return Leg.option(a, t, new BigDecimal(strike), EXP, ratio, new BigDecimal(prem));
    }

    @Test
    void longCall() {
        PayoffCurve c = PayoffCurve.of(List.of(opt(LegAction.BUY, OptionType.CALL, "100", 1, "2.50")), 1);
        assertThat(c.breakevens()).containsExactly(new BigDecimal("102.5000"));
        assertThat(c.maxLossUnbounded()).isFalse();
        assertThat(c.maxLossCents()).isEqualTo(25000);          // $250 premium
        assertThat(c.maxProfitUnbounded()).isTrue();
        assertThat(c.profitAtCents(new BigDecimal("110"))).isEqualTo(75000);
        assertThat(c.profitAtCents(new BigDecimal("90"))).isEqualTo(-25000);
        assertThat(c.entryNetPremiumCents()).isEqualTo(-25000); // debit
    }

    @Test
    void longPut() {
        PayoffCurve c = PayoffCurve.of(List.of(opt(LegAction.BUY, OptionType.PUT, "100", 1, "3.00")), 1);
        assertThat(c.breakevens()).containsExactly(new BigDecimal("97.0000"));
        assertThat(c.maxProfitUnbounded()).isFalse();
        assertThat(c.maxProfitCents()).isEqualTo(970000);       // (100-3)*100 at S=0
        assertThat(c.maxLossCents()).isEqualTo(30000);
    }

    @Test
    void creditPutSpread() {
        PayoffCurve c = PayoffCurve.of(List.of(
                opt(LegAction.SELL, OptionType.PUT, "100", 1, "3.00"),
                opt(LegAction.BUY, OptionType.PUT, "95", 1, "1.20")), 1);
        assertThat(c.entryNetPremiumCents()).isEqualTo(18000);  // $1.80 credit
        assertThat(c.maxProfitCents()).isEqualTo(18000);
        assertThat(c.maxLossCents()).isEqualTo(32000);          // width $5 - credit $1.80
        assertThat(c.breakevens()).containsExactly(new BigDecimal("98.2000"));
        assertThat(c.maxLossUnbounded()).isFalse();
        assertThat(c.maxProfitUnbounded()).isFalse();
    }

    @Test
    void ironCondor() {
        PayoffCurve c = PayoffCurve.of(List.of(
                opt(LegAction.BUY, OptionType.PUT, "90", 1, "0.70"),
                opt(LegAction.SELL, OptionType.PUT, "95", 1, "1.50"),
                opt(LegAction.SELL, OptionType.CALL, "105", 1, "1.40"),
                opt(LegAction.BUY, OptionType.CALL, "110", 1, "0.60")), 1);
        assertThat(c.entryNetPremiumCents()).isEqualTo(16000);  // $1.60 credit
        assertThat(c.maxProfitCents()).isEqualTo(16000);
        assertThat(c.maxLossCents()).isEqualTo(34000);
        assertThat(c.breakevens()).containsExactly(new BigDecimal("93.4000"), new BigDecimal("106.6000"));
        assertThat(c.profitAtCents(new BigDecimal("100"))).isEqualTo(16000);
        assertThat(c.profitAtCents(new BigDecimal("80"))).isEqualTo(-34000);
        assertThat(c.profitAtCents(new BigDecimal("120"))).isEqualTo(-34000);
    }

    @Test
    void longCallButterfly() {
        PayoffCurve c = PayoffCurve.of(List.of(
                opt(LegAction.BUY, OptionType.CALL, "95", 1, "6.00"),
                opt(LegAction.SELL, OptionType.CALL, "100", 2, "3.50"),
                opt(LegAction.BUY, OptionType.CALL, "105", 1, "1.50")), 1);
        assertThat(c.entryNetPremiumCents()).isEqualTo(-5000);  // $0.50 debit
        assertThat(c.maxLossCents()).isEqualTo(5000);
        assertThat(c.maxProfitCents()).isEqualTo(45000);        // peak at middle strike
        assertThat(c.breakevens()).containsExactly(new BigDecimal("95.5000"), new BigDecimal("104.5000"));
    }

    @Test
    void coveredCall() {
        PayoffCurve c = PayoffCurve.of(List.of(
                Leg.stock(LegAction.BUY, 1, new BigDecimal("100.00")),
                opt(LegAction.SELL, OptionType.CALL, "105", 1, "2.00")), 1);
        assertThat(c.maxProfitUnbounded()).isFalse();           // upside capped by short call
        assertThat(c.maxProfitCents()).isEqualTo(70000);        // (5 + 2) * 100
        assertThat(c.maxLossUnbounded()).isFalse();
        assertThat(c.maxLossCents()).isEqualTo(980000);         // stock to zero minus premium
        assertThat(c.breakevens()).containsExactly(new BigDecimal("98.0000"));
    }

    @Test
    void nakedCallHasUnboundedLoss() {
        PayoffCurve c = PayoffCurve.of(List.of(opt(LegAction.SELL, OptionType.CALL, "100", 1, "2.50")), 1);
        assertThat(c.maxLossUnbounded()).isTrue();
        assertThat(c.maxProfitUnbounded()).isFalse();
        assertThat(c.maxProfitCents()).isEqualTo(25000);
    }

    @Test
    void qtyScalesEverything() {
        PayoffCurve c = PayoffCurve.of(List.of(
                opt(LegAction.SELL, OptionType.PUT, "100", 1, "3.00"),
                opt(LegAction.BUY, OptionType.PUT, "95", 1, "1.20")), 3);
        assertThat(c.entryNetPremiumCents()).isEqualTo(54000);
        assertThat(c.maxLossCents()).isEqualTo(96000);
        assertThat(c.breakevens()).containsExactly(new BigDecimal("98.2000")); // breakevens unchanged
    }

    @Test
    void adjustedMultiplierScalesExactCashAndRisk() {
        Leg standard = Leg.option(LegAction.BUY, OptionType.CALL, new BigDecimal("100"), EXP,
                1, new BigDecimal("2.50"), 100);
        Leg adjusted = Leg.option(LegAction.BUY, OptionType.CALL, new BigDecimal("100"), EXP,
                1, new BigDecimal("2.50"), 10);

        PayoffCurve standardCurve = PayoffCurve.of(List.of(standard), 1);
        PayoffCurve adjustedCurve = PayoffCurve.of(List.of(adjusted), 1);

        assertThat(standardCurve.entryNetPremiumCents()).isEqualTo(-25_000);
        assertThat(adjustedCurve.entryNetPremiumCents()).isEqualTo(-2_500);
        assertThat(adjustedCurve.maxLossCents()).isEqualTo(2_500);
        assertThat(adjustedCurve.profitAtCents(new BigDecimal("110"))).isEqualTo(7_500);
        assertThat(adjustedCurve.breakevens()).containsExactly(new BigDecimal("102.5000"));
    }

    @Test
    void exactShareStockLegDoesNotBecomeAOneHundredShareLot() {
        PayoffCurve curve = PayoffCurve.of(List.of(
                Leg.stockShares(LegAction.BUY, 10, new BigDecimal("100"))), 1);

        assertThat(curve.entryNetPremiumCents()).isEqualTo(-100_000);
        assertThat(curve.profitAtCents(new BigDecimal("110"))).isEqualTo(10_000);
    }

    @Test
    void probProfitComplementarityAndBounds() {
        List<Leg> longCall = List.of(opt(LegAction.BUY, OptionType.CALL, "100", 1, "2.50"));
        List<Leg> shortCall = List.of(opt(LegAction.SELL, OptionType.CALL, "100", 1, "2.50"));
        double pLong = PayoffCurve.of(longCall, 1).probProfit(100, 0.25, 0.25, 0.0);
        double pShort = PayoffCurve.of(shortCall, 1).probProfit(100, 0.25, 0.25, 0.0);
        assertThat(pLong).isBetween(0.05, 0.5);                 // OTM-at-breakeven long call
        assertThat(pLong + pShort).isCloseTo(1.0, within(1e-6)); // complementary regions
    }

    @Test
    void probProfitDegenerateCases() {
        PayoffCurve c = PayoffCurve.of(List.of(opt(LegAction.BUY, OptionType.CALL, "100", 1, "2.50")), 1);
        assertThat(c.probProfit(110, 0.25, 0.0, 0.0)).isEqualTo(1.0); // expired ITM past breakeven
        assertThat(c.probProfit(100, 0.25, 0.0, 0.0)).isEqualTo(0.0); // expired at strike
    }

    @Test
    void expectedValueIsAntisymmetricAndNearFair() {
        // Terminal-dollar expectation includes carry; long/short remain exact opposites.
        double prem = BlackScholes.price(true, 100, 100, 0.25, 0.03, 0, 0.25);
        Leg buy = opt(LegAction.BUY, OptionType.CALL, "100", 1, new BigDecimal(prem).setScale(4, java.math.RoundingMode.HALF_UP).toPlainString());
        Leg sell = opt(LegAction.SELL, OptionType.CALL, "100", 1, new BigDecimal(prem).setScale(4, java.math.RoundingMode.HALF_UP).toPlainString());
        long evLong = PayoffCurve.of(List.of(buy), 1).expectedValueCents(100, 0.25, 0.25, 0.03);
        long evShort = PayoffCurve.of(List.of(sell), 1).expectedValueCents(100, 0.25, 0.25, 0.03);
        assertThat(evLong + evShort).isBetween(-2L, 2L);        // antisymmetric up to rounding
        assertThat(Math.abs(evLong)).isLessThan(1500);          // fair-priced -> small EV
    }

    @Test
    void riskNeutralExpectedValueDiscountsOnlyTheTerminalPayoff() {
        double t = 0.25, r = 0.03, sigma = 0.25;
        double fair = BlackScholes.price(true, 100, 100, t, r, 0, sigma);
        Leg buy = opt(LegAction.BUY, OptionType.CALL, "100", 1,
                new BigDecimal(fair).setScale(6, java.math.RoundingMode.HALF_UP).toPlainString());
        Leg sell = opt(LegAction.SELL, OptionType.CALL, "100", 1,
                new BigDecimal(fair).setScale(6, java.math.RoundingMode.HALF_UP).toPlainString());

        long longPv = PayoffCurve.of(List.of(buy), 1)
                .riskNeutralExpectedValueCents(100, sigma, t, r);
        long shortPv = PayoffCurve.of(List.of(sell), 1)
                .riskNeutralExpectedValueCents(100, sigma, t, r);

        assertThat(longPv).isBetween(-5L, 5L);
        assertThat(shortPv).isBetween(-5L, 5L);
        assertThat(longPv + shortPv).isBetween(-2L, 2L);
    }
}
