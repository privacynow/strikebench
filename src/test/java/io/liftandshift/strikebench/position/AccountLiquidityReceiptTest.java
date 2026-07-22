package io.liftandshift.strikebench.position;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AccountLiquidityReceiptTest {

    @Test
    void brokerReportedVanguardShapeReconcilesToTheCentWithoutCallingCollateralIdleCash() {
        var receipt = AccountLiquidityReceipt.tracked("synthetic-vanguard", 102_011_128L,
                101_650_000L, new AccountLiquidityReceipt.BrokerEvidence("pval-synthetic",
                        OffsetDateTime.parse("2026-07-22T16:00:00Z"),
                        102_011_128L, 361_099L, 101_650_000L, 29L, 5.10, null));

        assertThat(receipt.settlementBalance().cents()).isEqualTo(102_011_128L);
        assertThat(receipt.pendingActivity().cents()).isEqualTo(361_099L);
        assertThat(receipt.recordedOrReportedReserve().cents()).isEqualTo(101_650_000L);
        assertThat(receipt.theoreticalShortPutObligation().cents()).isEqualTo(101_650_000L);
        assertThat(receipt.genuinelyFreeBuyingPower().cents()).isEqualTo(29L);
        assertThat(receipt.reconciliationDifference().cents()).isZero();
        assertThat(receipt.reconciliationStatus()).isEqualTo("RECONCILED");
        assertThat(receipt.concurrentCollateralIncome().annualRatePct()).isEqualTo(5.10);
        assertThat(receipt.concurrentCollateralIncome().basis()).contains("separate from option carry");
    }

    @Test
    void trackedAccountWithoutBrokerReserveRefusesToInventFreeBuyingPower() {
        var receipt = AccountLiquidityReceipt.tracked("margin-book", -25_000L,
                4_500_000L, null);

        assertThat(receipt.settlementBalance().cents()).isEqualTo(-25_000L);
        assertThat(receipt.recordedOrReportedReserve().authority())
                .isEqualTo(PositionDomain.FactAuthority.UNAVAILABLE);
        assertThat(receipt.genuinelyFreeBuyingPower().authority())
                .isEqualTo(PositionDomain.FactAuthority.UNAVAILABLE);
        assertThat(receipt.reconciliationStatus()).isEqualTo("UNAVAILABLE");
        assertThat(receipt.reconciliationReason()).contains("cannot establish broker buying power");
    }

    @Test
    void practiceLedgerReconcilesExactRecordedReserveAndKeepsGrossPutObligationSeparate() {
        var receipt = AccountLiquidityReceipt.practice("paper", 10_000_000L,
                1_800_000L, 8_200_000L, 2_000_000L,
                OffsetDateTime.parse("2026-07-22T16:00:00Z"));

        assertThat(receipt.reconciliationStatus()).isEqualTo("RECONCILED");
        assertThat(receipt.reconciliationDifference().cents()).isZero();
        assertThat(receipt.recordedOrReportedReserve().cents()).isEqualTo(1_800_000L);
        assertThat(receipt.theoreticalShortPutObligation().cents()).isEqualTo(2_000_000L);
        assertThat(receipt.concurrentCollateralIncome().authority())
                .isEqualTo(PositionDomain.FactAuthority.UNAVAILABLE);
    }
}
