package io.liftandshift.strikebench.strategy;

import io.liftandshift.strikebench.support.TestPrices;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CapitalRequirementTest {

    @Test
    void definedRiskCreditKeepsLossReserveAndBuyingPowerDistinct() {
        var receipt = CapitalRequirement.of(
                StrategyCatalog.identify(StrategyFamily.CREDIT_PUT_SPREAD),
                TestPrices.withFees(1, 10_000L, 10_000L, 130L),
                40_000L, null, false);

        assertThat(receipt.maximumLossCents()).isEqualTo(40_000L);
        assertThat(receipt.reserveCents()).isEqualTo(50_000L);
        assertThat(receipt.buyingPowerRequiredCents()).isEqualTo(40_130L);
        assertThat(receipt.economicExposureCents()).isEqualTo(40_000L);
    }

    @Test
    void cashSecuredPutUsesStrikeCashAsCollateralButNetLossAsBuyingPowerUse() {
        var receipt = CapitalRequirement.of(
                StrategyCatalog.identify(StrategyFamily.CASH_SECURED_PUT),
                TestPrices.withFees(1, 35_000L, 35_000L, 65L),
                2_365_000L, null, false);

        assertThat(receipt.reserveCents()).isEqualTo(2_400_000L);
        assertThat(receipt.buyingPowerRequiredCents()).isEqualTo(2_365_065L);
        assertThat(receipt.economicExposureCents()).isEqualTo(2_400_000L);
    }

    @Test
    void debitPaysRiskUpFrontAndHeldSharesAreNotReservedTwice() {
        var debit = CapitalRequirement.of(
                StrategyCatalog.identify(StrategyFamily.DEBIT_CALL_SPREAD),
                TestPrices.withFees(1, -20_000L, -20_000L, 130L),
                20_000L, null, false);
        assertThat(debit.reserveCents()).isZero();
        assertThat(debit.buyingPowerRequiredCents()).isEqualTo(20_130L);

        var held = CapitalRequirement.of(
                StrategyCatalog.identify(StrategyFamily.COVERED_CALL),
                TestPrices.withFees(1, 35_000L, 35_000L, 65L),
                0L, 2_365_000L, true);
        assertThat(held.reserveCents()).isZero();
        assertThat(held.buyingPowerRequiredCents()).isZero();
        assertThat(held.economicExposureCents()).isEqualTo(2_365_000L);
    }

    @Test
    void heldShareCapitalFailsClosedWithoutCombinedPositionRisk() {
        var receipt = CapitalRequirement.of(
                StrategyCatalog.identify(StrategyFamily.COVERED_CALL),
                TestPrices.withFees(1, 35_000L, 35_000L, 65L),
                0L, null, true);

        assertThat(receipt.available()).isFalse();
        assertThat(receipt.economicExposureCents()).isNull();
        assertThat(receipt.unavailableReason()).contains("combined-position maximum-loss");
    }
}
