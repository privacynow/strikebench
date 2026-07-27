package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The §7.2 receipt's own laws. Everything else in the product reads these amounts, so the two
 * reconciliation identities and the §3.2 no-fabricated-value rule are pinned here rather than
 * re-asserted per surface.
 */
class PackagePriceReceiptTest {

    private static final LocalDate EXP = LocalDate.of(2026, 8, 21);

    private static Leg shortCall(String premium) {
        return Leg.option(LegAction.SELL, OptionType.CALL, new BigDecimal("200"), EXP, 1,
                new BigDecimal(premium), 100);
    }

    private static Leg hundredShares(String price) {
        return Leg.stockShares(LegAction.BUY, 100, new BigDecimal(price));
    }

    @Test
    void payoffEntryCostHasOneExactDebitCreditZeroAndUnavailableSignPolicy() {
        PackagePriceReceipt credit = PackagePriceReceipt.of(1, 42_000L, 42_000L, 0L,
                65L, 155L, PackagePriceReceipt.FeeSide.OPENING, 42_000L,
                OrderInstruction.market(), OrderInstruction.Executability.IMMEDIATE,
                PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK,
                "fixture", "REALTIME", 1L, "credit");
        PackagePriceReceipt debit = PackagePriceReceipt.of(1, -42_000L, -42_000L, 0L,
                65L, 155L, PackagePriceReceipt.FeeSide.OPENING, -42_000L,
                OrderInstruction.market(), OrderInstruction.Executability.IMMEDIATE,
                PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK,
                "fixture", "REALTIME", 1L, "debit");
        PackagePriceReceipt zero = PackagePriceReceipt.of(1, 0L, 0L, 0L,
                0L, 0L, PackagePriceReceipt.FeeSide.OPENING, 0L,
                OrderInstruction.market(), OrderInstruction.Executability.IMMEDIATE,
                PackagePriceReceipt.ValuationBasis.RECORDED_FILL,
                "broker", "RECORDED", 1L, "zero");

        assertThat(credit.payoffEntryCostCents()).isEqualTo(-42_000L);
        assertThat(debit.payoffEntryCostCents()).isEqualTo(42_000L);
        assertThat(zero.payoffEntryCostCents()).isZero();
        assertThat(PackagePriceReceipt.unavailable(1, PackagePriceReceipt.FeeSide.OPENING,
                "no executable book").payoffEntryCostCents()).isNull();

        PackagePriceReceipt close = PackagePriceReceipt.of(1, -100L, -100L, 0L,
                5L, null, PackagePriceReceipt.FeeSide.CLOSING, -100L, null,
                OrderInstruction.Executability.IMMEDIATE,
                PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK,
                "fixture", "REALTIME", 1L, "close");
        assertThatThrownBy(close::payoffEntryCostCents)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPENING");
    }

    @Test
    void optionOnlyPackageHasZeroStockCashFlowAndReconcilesThroughFees() {
        PackagePriceReceipt price = PackagePriceReceipt.ofLegs(List.of(shortCall("3.20")), 2,
                64_000L, 130L, 260L, PackagePriceReceipt.FeeSide.OPENING, 64_000L,
                OrderInstruction.market(), OrderInstruction.Executability.IMMEDIATE,
                PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK, "fixture", "REALTIME", 1L, "fp");

        assertThat(price.quantity()).isEqualTo(2);
        assertThat(price.optionNetPremiumCents()).isEqualTo(64_000L);
        assertThat(price.stockCashFlowCents()).isZero();
        assertThat(price.grossPackageNetCents()).isEqualTo(64_000L);
        assertThat(price.afterFeeNetCents()).isEqualTo(63_870L);
        assertThat(price.estimatedRoundTripFeesCents()).isEqualTo(260L);
        assertThat(price.restingLimitNetCents()).isNull();
        assertThat(price.priced()).isTrue();
    }

    @Test
    void buyWriteSplitsTheOptionCreditFromTheShareCashFlow() {
        // One buy-write: sell a $3.20 call (+$320) and buy 100 shares at $195 (−$19,500).
        List<Leg> legs = List.of(shortCall("3.20"), hundredShares("195"));
        long gross = -1_918_000L;

        PackagePriceReceipt price = PackagePriceReceipt.ofLegs(legs, 1, gross, 65L, 130L,
                PackagePriceReceipt.FeeSide.OPENING, gross, OrderInstruction.market(),
                OrderInstruction.Executability.IMMEDIATE,
                PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK, "fixture", "REALTIME", 1L, "fp");

        assertThat(price.optionNetPremiumCents()).isEqualTo(32_000L);      // the call sold: a credit
        assertThat(price.stockCashFlowCents()).isEqualTo(-1_950_000L);    // the shares bought
        assertThat(price.grossPackageNetCents()).isEqualTo(gross);
        assertThat(price.grossPackageNetCents())
                .isEqualTo(price.optionNetPremiumCents() + price.stockCashFlowCents());
        assertThat(price.afterFeeNetCents()).isEqualTo(gross - 65L);
    }

    /**
     * The additive identity has to be a CHECK, not a restatement. {@code ofLegs} takes the package
     * net as a fact and measures both sides from the legs; a package net struck on a basis the legs
     * do not reproduce therefore fails here.
     *
     * <p>While the factory derived the stock side as {@code gross - optionNet}, this call produced a
     * perfectly "reconciling" receipt in which the entire mismatch had been reassigned to the share
     * cash flow — a fabricated number (§3.2) that made the whole invariant decorative. That is not a
     * hypothetical: {@code TradeController.exactPreviewCandidate} used to take its option net from
     * the prices the customer SUBMITTED and its package net from the preview's FILLED legs.</p>
     */
    @Test
    void aPackageNetStruckOnADifferentBasisThanItsLegsIsRefusedNotAbsorbedByTheShares() {
        // Legs price the buy-write at +$320 option credit − $19,500 shares = −$19,180.
        List<Leg> legs = List.of(shortCall("3.20"), hundredShares("195"));

        assertThatThrownBy(() -> PackagePriceReceipt.ofLegs(legs, 1, -1_919_000L, 65L, 130L,
                PackagePriceReceipt.FeeSide.OPENING, -1_919_000L, OrderInstruction.market(),
                OrderInstruction.Executability.IMMEDIATE,
                PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK, "fixture", "REALTIME", 1L, "fp"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("option net plus stock cash flow")
                .hasMessageContaining("-1919000")   // the stated package net…
                .hasMessageContaining("32000")      // …against the two measured sides
                .hasMessageContaining("-1950000");

        // The honest package net on the same legs still builds, and its share cash flow is the
        // SHARES — measured — rather than whatever balances the books.
        PackagePriceReceipt honest = PackagePriceReceipt.ofLegs(legs, 1, -1_918_000L, 65L, 130L,
                PackagePriceReceipt.FeeSide.OPENING, -1_918_000L, OrderInstruction.market(),
                OrderInstruction.Executability.IMMEDIATE,
                PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK, "fixture", "REALTIME", 1L, "fp");
        assertThat(honest.stockCashFlowCents()).isEqualTo(-1_950_000L);
        assertThat(honest.optionNetPremiumCents()).isEqualTo(32_000L);
    }

    @Test
    void aRestingLimitStatesItsOwnBasisAndCarriesTheLimitPrice() {
        PackagePriceReceipt price = PackagePriceReceipt.of(1, 42_000L, 42_000L, 0L, 65L, 130L,
                PackagePriceReceipt.FeeSide.OPENING, 40_000L, OrderInstruction.limit(42_000L),
                OrderInstruction.Executability.RESTING,
                PackagePriceReceipt.ValuationBasis.RESTING_LIMIT, "fixture", "REALTIME", 1L, "fp");

        assertThat(price.restingLimitNetCents()).isEqualTo(42_000L);
        assertThat(price.executableNetCents()).isEqualTo(40_000L);
        assertThat(price.valuationBasis()).isEqualTo(PackagePriceReceipt.ValuationBasis.RESTING_LIMIT);
        assertThat(price.executability()).isEqualTo(OrderInstruction.Executability.RESTING);
        // The basis and the executability answer different questions and must not be collapsed.
        assertThat(price.priced()).isTrue();
    }

    @Test
    void aOneSidedBookLeavesExecutableNetNullRatherThanBorrowingTheRecordedNet() {
        PackagePriceReceipt price = PackagePriceReceipt.of(1, 42_000L, 42_000L, 0L, 65L, 130L,
                PackagePriceReceipt.FeeSide.OPENING, null, OrderInstruction.market(),
                OrderInstruction.Executability.UNAVAILABLE,
                PackagePriceReceipt.ValuationBasis.MODELED, "fixture", "EOD", 1L, "fp");

        assertThat(price.executableNetCents()).isNull();
        assertThat(price.grossPackageNetCents()).isEqualTo(42_000L);
        assertThat(price.executability()).isEqualTo(OrderInstruction.Executability.UNAVAILABLE);
    }

    @Test
    void anUnpricedPackageStatesTheReasonAndNoAmounts() {
        PackagePriceReceipt price = PackagePriceReceipt.unavailable(3,
                PackagePriceReceipt.FeeSide.OPENING, "the complete package has no executable market");

        assertThat(price.quantity()).isEqualTo(3);
        assertThat(price.grossPackageNetCents()).isNull();
        assertThat(price.optionNetPremiumCents()).isNull();
        assertThat(price.stockCashFlowCents()).isNull();
        assertThat(price.afterFeeNetCents()).isNull();
        assertThat(price.openingFeesCents()).isNull();
        assertThat(price.estimatedRoundTripFeesCents()).isNull();
        assertThat(price.priced()).isFalse();
        assertThat(price.unavailableReason()).contains("no executable market");
        assertThat(price.valuationBasis()).isEqualTo(PackagePriceReceipt.ValuationBasis.UNAVAILABLE);
    }

    @Test
    void closingFeesAreDisclosedAsSuchRatherThanMislabelledAsOpening() {
        PackagePriceReceipt close = PackagePriceReceipt.of(1, -21_500L, -21_500L, 0L, 65L, null,
                PackagePriceReceipt.FeeSide.CLOSING, -21_500L, null,
                OrderInstruction.Executability.IMMEDIATE,
                PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK, "fixture", "REALTIME", 1L, "fp");

        assertThat(close.feeSide()).isEqualTo(PackagePriceReceipt.FeeSide.CLOSING);
        assertThat(close.afterFeeNetCents()).isEqualTo(-21_565L);
        assertThat(close.estimatedRoundTripFeesCents()).isNull();
    }

    @Test
    void anAsymmetricRoundTripEstimateIsCapturedRatherThanReconstructed() {
        PackagePriceReceipt price = PackagePriceReceipt.of(1, 42_000L, 42_000L, 0L,
                65L, 155L, PackagePriceReceipt.FeeSide.OPENING, 42_000L,
                OrderInstruction.market(), OrderInstruction.Executability.IMMEDIATE,
                PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK,
                "fixture", "REALTIME", 1L, "fp");

        assertThat(price.openingFeesCents()).isEqualTo(65L);
        assertThat(price.estimatedRoundTripFeesCents()).isEqualTo(155L);
        assertThat(price.afterFeeNetCents()).isEqualTo(41_935L);
    }

    @Test
    void theAdditiveAndFeeIdentitiesAreEnforcedNotAssumed() {
        assertThatThrownBy(() -> new PackagePriceReceipt(1, 32_000L, -1_950_000L, 0L,
                null, null, null, null, null,
                PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK,
                OrderInstruction.Executability.IMMEDIATE, "s", "REALTIME", 1L, "fp",
                PackagePriceReceipt.FeeSide.OPENING, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("option net plus stock cash flow");

        assertThatThrownBy(() -> new PackagePriceReceipt(1, 64_000L, 0L, 64_000L,
                130L, 260L, 64_000L, null, null,
                PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK,
                OrderInstruction.Executability.IMMEDIATE, "s", "REALTIME", 1L, "fp",
                PackagePriceReceipt.FeeSide.OPENING, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("less fees");

        // A stated basis without a price, or a price without a basis, is the §3.2 fallthrough.
        assertThatThrownBy(() -> new PackagePriceReceipt(1, null, null, null,
                null, null, null, null, null,
                PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK,
                OrderInstruction.Executability.IMMEDIATE, "s", "REALTIME", 1L, "fp",
                PackagePriceReceipt.FeeSide.OPENING, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must occur together");

        // …and an unpriced receipt always has SOME reason: a blank one is filled, never dropped.
        assertThat(PackagePriceReceipt.unavailable(1, PackagePriceReceipt.FeeSide.OPENING, null)
                .unavailableReason()).isNotBlank();
    }

    @Test
    void theWireShapeIsExactlyTheCanonicalAmountsAndDisclosures() {
        // The frontend binds every price cell to THIS object, so its field set is a contract:
        // nothing may be dropped when null (an absent field and a null field must read alike) and
        // no derived convenience accessor may leak in as a fifteenth "amount" to choose among.
        var node = io.liftandshift.strikebench.util.Json.MAPPER.valueToTree(
                PackagePriceReceipt.unavailable(1, PackagePriceReceipt.FeeSide.OPENING, "no book"));
        List<String> fields = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder(
                "quantity", "optionNetPremiumCents", "stockCashFlowCents", "grossPackageNetCents",
                "openingFeesCents", "estimatedRoundTripFeesCents", "afterFeeNetCents",
                "executableNetCents", "restingLimitNetCents",
                "valuationBasis", "executability", "source", "freshness", "observedAt",
                "fingerprint", "feeSide", "unavailableReason");
    }

    /**
     * The constructor refuses a negative fee, but both factories used to clamp one to 0 before the
     * constructor ever saw it — so the rule could not fire and a caller bug would have shipped as
     * "this order carries no commission", with the after-fee net silently equal to the gross. A
     * clamp that outranks an invariant is a fabricated value (§3.2), not a safety net.
     */
    @Test
    void aNegativeFeeIsRefusedRatherThanSilentlyPublishedAsAFreeOrder() {
        assertThatThrownBy(() -> PackagePriceReceipt.of(1, 42_000L, 42_000L, 0L, -65L, -130L,
                PackagePriceReceipt.FeeSide.OPENING, 42_000L, OrderInstruction.market(),
                OrderInstruction.Executability.IMMEDIATE,
                PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK, "fixture", "REALTIME", 1L, "fp"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fees cannot be negative");

        PackagePriceReceipt priced = PackagePriceReceipt.of(1, 42_000L, 42_000L, 0L, null, null,
                PackagePriceReceipt.FeeSide.OPENING, 42_000L, OrderInstruction.market(),
                OrderInstruction.Executability.IMMEDIATE,
                PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK, "fixture", "REALTIME", 1L, "fp");
        assertThatThrownBy(() -> priced.withFees(-1L, -2L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fees cannot be negative");

        // A zero commission is a legitimate schedule (stock-only packages carry none) and still builds.
        assertThat(priced.withFees(0L, 0L).afterFeeNetCents()).isEqualTo(42_000L);

        assertThatThrownBy(() -> new TradeService.OpenRequest("acct", "AAPL", "CUSTOM", 1,
                List.of(shortCall("3.20")), null, "month", "balanced", null, null,
                -1L, "TICKET", "PROPOSED", OrderInstruction.market()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feesOverrideCents cannot be negative");
    }

    @Test
    void anUnavailableReceiptCannotSmuggleFinancialFactsIntoConsumers() {
        assertThatThrownBy(() -> new PackagePriceReceipt(1, 100L, null, null,
                null, null, null, null, null,
                PackagePriceReceipt.ValuationBasis.UNAVAILABLE,
                OrderInstruction.Executability.UNAVAILABLE, "legacy", "UNKNOWN", 1L, "fp",
                PackagePriceReceipt.FeeSide.OPENING, "legacy price unavailable"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot carry financial amounts");

        assertThatThrownBy(() -> new PackagePriceReceipt(1, null, null, null,
                null, null, null, null, null,
                PackagePriceReceipt.ValuationBasis.UNAVAILABLE,
                OrderInstruction.Executability.IMMEDIATE, "legacy", "UNKNOWN", 1L, "fp",
                PackagePriceReceipt.FeeSide.OPENING, "legacy price unavailable"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNAVAILABLE executability");

        assertThatThrownBy(() -> new PackagePriceReceipt(1, 100L, 0L, 100L,
                0L, 0L, 100L, 100L, null,
                PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK,
                OrderInstruction.Executability.IMMEDIATE, "book", "REALTIME", 1L, "fp",
                PackagePriceReceipt.FeeSide.OPENING, "contradictory unavailable reason"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("priced package cannot carry");
    }

    /**
     * ONE rule for the basis of a package priced off marks, so a scan rail and an order preview
     * reading the same book cannot label it differently — and so MID_MARKET, which was assigned
     * nowhere in the product, names the lane that genuinely prices at the midpoint.
     */
    @Test
    void theMarkBasisNamesWhatWasActuallyPricedIncludingTheMidpointLane() {
        assertThat(PackagePriceReceipt.markBasis(true, false))
                .isEqualTo(PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK);
        assertThat(PackagePriceReceipt.markBasis(false, true))
                .isEqualTo(PackagePriceReceipt.ValuationBasis.MID_MARKET);
        assertThat(PackagePriceReceipt.markBasis(false, false))
                .isEqualTo(PackagePriceReceipt.ValuationBasis.MODELED);
    }

    /**
     * A DIFFERENT fingerprint is a statement to the reader — "these were struck at different
     * moments" — so it must never be produced by two spellings of one price. The scan holds a
     * quote's {@code 255.3200}; the same package arriving back through the wire holds
     * {@code 255.32}, because the wire form strips trailing zeros on purpose. Both are the same
     * package at the same price, and hashing the Leg records directly said otherwise.
     */
    @Test
    void oneScaleOrOrderingOfTheSamePriceIsOnePackageIdentity() {
        List<Leg> asScanned = List.of(
                Leg.stockShares(LegAction.BUY, 100, new BigDecimal("255.3200")),
                Leg.option(LegAction.SELL, OptionType.CALL, new BigDecimal("270.00"), EXP, 1,
                        new BigDecimal("3.9100"), 100));
        List<Leg> afterTheWire = List.of(
                Leg.option(LegAction.SELL, OptionType.CALL, new BigDecimal("270"), EXP, 1,
                        new BigDecimal("3.91"), 100),
                Leg.stockShares(LegAction.BUY, 100, new BigDecimal("255.32")));

        assertThat(PackagePriceReceipt.fingerprintOf(afterTheWire, 1, -2_514_100L,
                PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK, 1_000L))
                .isEqualTo(PackagePriceReceipt.fingerprintOf(asScanned, 1, -2_514_100L,
                        PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK, 1_000L));

        // …while a genuinely different package still gets a different identity.
        List<Leg> differentStrike = List.of(
                Leg.stockShares(LegAction.BUY, 100, new BigDecimal("255.32")),
                Leg.option(LegAction.SELL, OptionType.CALL, new BigDecimal("275"), EXP, 1,
                        new BigDecimal("3.91"), 100));
        assertThat(PackagePriceReceipt.fingerprintOf(differentStrike, 1, -2_514_100L,
                PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK, 1_000L))
                .isNotEqualTo(PackagePriceReceipt.fingerprintOf(asScanned, 1, -2_514_100L,
                        PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK, 1_000L));
    }

    @Test
    void theFingerprintIdentifiesThePricedPackageNotJustItsLegs() {
        List<Leg> legs = List.of(shortCall("3.20"));
        String a = PackagePriceReceipt.fingerprintOf(legs, 1, 32_000L,
                PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK, 1_000L);
        String same = PackagePriceReceipt.fingerprintOf(legs, 1, 32_000L,
                PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK, 1_000L);
        String laterScan = PackagePriceReceipt.fingerprintOf(legs, 1, 32_000L,
                PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK, 2_000L);
        String biggerSize = PackagePriceReceipt.fingerprintOf(legs, 2, 64_000L,
                PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK, 1_000L);

        assertThat(a).isEqualTo(same).hasSize(64);
        assertThat(a).isNotEqualTo(laterScan).isNotEqualTo(biggerSize);
        assertThat(PackagePriceReceipt.fingerprintOf(List.of(), 1, 1L,
                PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK, 1L)).isNull();
    }
}
