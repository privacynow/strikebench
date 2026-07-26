package io.liftandshift.strikebench.api;

import io.liftandshift.strikebench.paper.OrderInstruction;
import io.liftandshift.strikebench.paper.PackagePriceReceipt;
import io.liftandshift.strikebench.paper.TradePreview;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The order dock publishes the preview's own §7.2 receipt. These two behaviours were pinned when
 * the dock re-derived its own valuation from the analytics map, and they survive the move onto the
 * shared receipt: an UNAVAILABLE execution never promotes a zero to a valuation, and an immediate
 * order and a resting one state visibly different bases for the money on screen.
 */
class PlanDecisionOrderDockTest {

    @Test void unavailableExecutionDoesNotPromoteThePreviewZeroSentinelToAValuation() {
        var order = order(OrderInstruction.market());
        var preview = preview(false,
                PackagePriceReceipt.unavailable(1, PackagePriceReceipt.FeeSide.OPENING,
                        "the complete package has no executable natural market"));

        var dock = PlanDecisionController.orderDock(order, preview);

        assertThat(dock.price().executability()).isEqualTo(OrderInstruction.Executability.UNAVAILABLE);
        assertThat(dock.price().executableNetCents()).isNull();
        assertThat(dock.price().valuedNetCents()).isNull();
        assertThat(dock.price().grossPackageNetCents()).isNull();
        assertThat(dock.price().valuationBasis())
                .isEqualTo(PackagePriceReceipt.ValuationBasis.UNAVAILABLE);
        assertThat(dock.price().unavailableReason()).isNotBlank();
    }

    @Test void immediateAndRestingInstructionsExposeDistinctValuationBases() {
        var immediate = PlanDecisionController.orderDock(order(OrderInstruction.market()),
                preview(true, priced(67_000, null, 67_000L,
                        OrderInstruction.Executability.IMMEDIATE,
                        PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK)));
        assertThat(immediate.price().valuedNetCents()).isEqualTo(67_000L);
        assertThat(immediate.price().valuationBasis())
                .isEqualTo(PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK);
        assertThat(immediate.price().restingLimitNetCents()).isNull();

        var resting = PlanDecisionController.orderDock(order(OrderInstruction.limit(69_000)),
                preview(false, priced(69_000, OrderInstruction.limit(69_000), 67_000L,
                        OrderInstruction.Executability.RESTING,
                        PackagePriceReceipt.ValuationBasis.RESTING_LIMIT)));
        assertThat(resting.price().valuedNetCents()).isEqualTo(69_000L);
        assertThat(resting.price().executableNetCents()).isEqualTo(67_000L);
        assertThat(resting.price().restingLimitNetCents()).isEqualTo(69_000L);
        assertThat(resting.price().valuationBasis())
                .isEqualTo(PackagePriceReceipt.ValuationBasis.RESTING_LIMIT);
    }

    @Test void theDockReportsTheFeeActuallyCharged() {
        // The old OrderSummary carried `feesOverrideCents`, an OVERRIDE that defaulted to 0, so the
        // dock printed $0 of fees on every order the customer had not overridden.
        var dock = PlanDecisionController.orderDock(order(OrderInstruction.market()),
                preview(true, priced(67_000, null, 67_000L,
                        OrderInstruction.Executability.IMMEDIATE,
                        PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK).withFees(130L)));

        assertThat(dock.price().openingFeesCents()).isEqualTo(130L);
        assertThat(dock.price().afterFeeNetCents()).isEqualTo(66_870L);
        assertThat(dock.price().valuedNetCents()).isEqualTo(66_870L);
    }

    private static PackagePriceReceipt priced(long gross, OrderInstruction instruction,
                                              Long executableNet,
                                              OrderInstruction.Executability executability,
                                              PackagePriceReceipt.ValuationBasis basis) {
        return PackagePriceReceipt.of(1, gross, gross, 0L, null, PackagePriceReceipt.FeeSide.OPENING,
                executableNet, instruction, executability, basis, "fixture", "MISSING", null, "fp");
    }

    private static TradeOpenRequest order(OrderInstruction instruction) {
        return new TradeOpenRequest("AMD", "CASH_SECURED_PUT", 1, List.of(), "neutral", "1d",
                "conservative", "INCOME", false, null, instruction.limitNetCents(), null,
                "PLAN", List.of(), null, "PROPOSED", instruction);
    }

    private static TradePreview preview(boolean ok, PackagePriceReceipt price) {
        // The two legacy package-price primitives are gone: `price` IS the preview's price, so a
        // fixture can no longer state one net beside the receipt's other one.
        return new TradePreview(ok, ok ? List.of() : List.of("Execution is unavailable."), List.of(),
                0, null, List.of(), null, null, 0,
                100_000, 100_000, 0, 0, 100_000, 100_000,
                // A preview with no price has no spot either; underlyingCents is nullable precisely
                // so this fixture cannot claim the underlying trades at $0.00 (§3.2).
                "MISSING", null, price.grossPackageNetCents() == null ? null : 12_345L,
                null, List.of(), List.of(), Map.of(), price);
    }
}
