package io.liftandshift.strikebench.support;

import io.liftandshift.strikebench.paper.OrderInstruction;
import io.liftandshift.strikebench.paper.PackagePriceReceipt;

/**
 * Fixture package prices for tests that need a §7.2 receipt but are not testing pricing.
 *
 * <p>It exists so the receipt's positional argument order lives in exactly ONE place across the
 * suite: two adjacent {@code long} amounts swapped by hand compile silently and produce a wrong
 * price, which is precisely the class of defect the receipt was introduced to prevent.</p>
 */
public final class TestPrices {

    private TestPrices() {}

    /** An option-only package priced at the executable book, quantity 1, no fee stated. */
    public static PackagePriceReceipt optionOnly(long grossPackageNetCents) {
        return optionOnly(1, grossPackageNetCents);
    }

    public static PackagePriceReceipt optionOnly(int quantity, long grossPackageNetCents) {
        return executable(quantity, grossPackageNetCents, grossPackageNetCents, null);
    }

    /** A stock-inclusive package (buy-write, collar): the option credit and the whole net differ. */
    public static PackagePriceReceipt stockInclusive(int quantity, long grossPackageNetCents,
                                                     long optionNetPremiumCents) {
        return executable(quantity, grossPackageNetCents, optionNetPremiumCents, null);
    }

    /** The same, with the opening commission stated so the after-fee net exists. */
    public static PackagePriceReceipt withFees(int quantity, long grossPackageNetCents,
                                               long optionNetPremiumCents, long openingFeesCents) {
        return executable(quantity, grossPackageNetCents, optionNetPremiumCents, openingFeesCents);
    }

    /** A CLOSING-side package price: what it costs to get out, on the executable book. */
    public static PackagePriceReceipt closing(int quantity, long grossCloseCashCents,
                                              long optionCloseCashCents, long closingFeesCents) {
        return PackagePriceReceipt.of(quantity, grossCloseCashCents, optionCloseCashCents,
                grossCloseCashCents - optionCloseCashCents,
                closingFeesCents, PackagePriceReceipt.FeeSide.CLOSING, grossCloseCashCents,
                null, OrderInstruction.Executability.IMMEDIATE,
                PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK, "fixture", "DELAYED",
                1_785_000_000_000L, "test-close-price");
    }

    // Fixtures state a package net and an option net and let the stock side be whatever balances
    // them — that is the ONE place the derivation is still legitimate, because these amounts are
    // invented for tests that are not testing pricing. Production producers MEASURE both sides.
    private static PackagePriceReceipt executable(int quantity, long grossPackageNetCents,
                                                  long optionNetPremiumCents, Long openingFeesCents) {
        return PackagePriceReceipt.of(quantity, grossPackageNetCents, optionNetPremiumCents,
                grossPackageNetCents - optionNetPremiumCents,
                openingFeesCents, PackagePriceReceipt.FeeSide.OPENING, grossPackageNetCents,
                OrderInstruction.market(), OrderInstruction.Executability.IMMEDIATE,
                PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK, "fixture", "DELAYED",
                1_785_000_000_000L, "test-package-price");
    }
}
