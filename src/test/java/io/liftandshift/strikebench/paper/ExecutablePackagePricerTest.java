package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.market.MarketLane;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.util.Fees;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutablePackagePricerTest {

    private static final LocalDate EXPIRATION = LocalDate.parse("2026-08-21");
    private static final DataEvidence DEMO = DataEvidence.of("fixture", Freshness.FIXTURE);

    @Test
    void stockAndOptionsUseTheSameActionSideMultiplierAndReceiptArithmetic() {
        Leg stock = Leg.stock(LegAction.BUY, 1, BigDecimal.ZERO);
        Leg call = Leg.option(LegAction.SELL, OptionType.CALL, new BigDecimal("105"),
                EXPIRATION, 1, BigDecimal.ZERO);

        ExecutablePackagePricer.Book book = ExecutablePackagePricer.price(List.of(
                new ExecutablePackagePricer.LegBook(stock, new BigDecimal("99"),
                        new BigDecimal("101"), new BigDecimal("100"), DEMO, 2_000L),
                new ExecutablePackagePricer.LegBook(call, new BigDecimal("2"),
                        new BigDecimal("3"), new BigDecimal("2.5"), DEMO, 1_000L)
        ), MarketLane.DEMO, ExecutablePackagePricer.Policy.EXECUTABLE_ONLY);
        PackagePriceReceipt receipt = book.receipt(2, new Fees.Schedule(65, 65, 130),
                PackagePriceReceipt.FeeSide.OPENING, OrderInstruction.market());

        assertThat(book.priced()).isTrue();
        assertThat(book.executable()).isTrue();
        assertThat(book.pricedLegs()).extracting(Leg::entryPrice)
                .containsExactly(new BigDecimal("101"), new BigDecimal("2"));
        assertThat(receipt.stockCashFlowCents()).isEqualTo(-2_020_000L);
        assertThat(receipt.optionNetPremiumCents()).isEqualTo(40_000L);
        assertThat(receipt.grossPackageNetCents()).isEqualTo(-1_980_000L);
        assertThat(receipt.executableNetCents()).isEqualTo(-1_980_000L);
        assertThat(receipt.afterFeeNetCents()).isEqualTo(-1_980_065L);
        assertThat(receipt.valuationBasis())
                .isEqualTo(PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK);
        assertThat(receipt.observedAt()).isEqualTo(1_000L);
        assertThat(receipt.source()).isEqualTo("fixture");
    }

    @Test
    void aMissingOrCrossedActionSideMakesTheWholeExecutablePackageUnavailable() {
        Leg stock = Leg.stock(LegAction.BUY, 1, BigDecimal.ZERO);
        Leg call = Leg.option(LegAction.SELL, OptionType.CALL, new BigDecimal("105"),
                EXPIRATION, 1, BigDecimal.ZERO);

        ExecutablePackagePricer.Book missingAsk = ExecutablePackagePricer.price(List.of(
                new ExecutablePackagePricer.LegBook(stock, new BigDecimal("99"), null,
                        new BigDecimal("99"), DEMO, 2_000L),
                new ExecutablePackagePricer.LegBook(call, new BigDecimal("2"),
                        new BigDecimal("3"), new BigDecimal("2.5"), DEMO, 1_000L)
        ), MarketLane.DEMO, ExecutablePackagePricer.Policy.EXECUTABLE_ONLY);
        ExecutablePackagePricer.Book crossedOption = ExecutablePackagePricer.price(List.of(
                new ExecutablePackagePricer.LegBook(stock, new BigDecimal("99"),
                        new BigDecimal("101"), new BigDecimal("100"), DEMO, 2_000L),
                new ExecutablePackagePricer.LegBook(call, new BigDecimal("3"),
                        new BigDecimal("2"), new BigDecimal("2.5"), DEMO, 1_000L)
        ), MarketLane.DEMO, ExecutablePackagePricer.Policy.EXECUTABLE_ONLY);

        for (ExecutablePackagePricer.Book book : List.of(missingAsk, crossedOption)) {
            assertThat(book.priced()).isFalse();
            assertThat(book.receipt(1, new Fees.Schedule(0, 0, 0),
                    PackagePriceReceipt.FeeSide.OPENING, OrderInstruction.market()).priced())
                    .isFalse();
        }
        assertThat(missingAsk.unavailableReason()).contains("ask");
        assertThat(crossedOption.unavailableReason()).containsIgnoringCase("crossed");
    }

    @Test
    void analysisMayCarryAnExplicitlyNonExecutableMarkWithoutInventingExecutability() {
        Leg put = Leg.option(LegAction.SELL, OptionType.PUT, new BigDecimal("95"),
                EXPIRATION, 1, BigDecimal.ZERO);
        DataEvidence eod = DataEvidence.of("stored-close", Freshness.EOD);

        ExecutablePackagePricer.Book book = ExecutablePackagePricer.price(List.of(
                // The display mark is a last/close fallback, not a two-sided midpoint.
                new ExecutablePackagePricer.LegBook(put, null, null,
                        new BigDecimal("1.35"), eod, 1_000L)
        ), MarketLane.OBSERVED, ExecutablePackagePricer.Policy.ANALYSIS);
        PackagePriceReceipt receipt = book.receipt(1, new Fees.Schedule(65, 65, 130),
                PackagePriceReceipt.FeeSide.OPENING, OrderInstruction.market());

        assertThat(book.priced()).isTrue();
        assertThat(book.executable()).isFalse();
        assertThat(book.usedMidpoint()).isFalse();
        assertThat(book.midpointNetCents(1)).isNull();
        assertThat(receipt.grossPackageNetCents()).isEqualTo(13_500L);
        assertThat(receipt.executableNetCents()).isNull();
        assertThat(receipt.executability()).isEqualTo(OrderInstruction.Executability.UNAVAILABLE);
        assertThat(receipt.valuationBasis()).isEqualTo(PackagePriceReceipt.ValuationBasis.MODELED);
        assertThat(receipt.freshness()).isEqualTo(Freshness.EOD.name());
    }
}
