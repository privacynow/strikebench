package io.liftandshift.strikebench.util;

/**
 * THE one round-trip commission formula. Every EV-after-fees consumer routes the final arithmetic
 * through here so a fee-policy change lands in one place and the verdict, the decision score, and
 * the outcome projection can never net different fees off the same EV.
 *
 * <p>Callers count their own option contracts (leg types differ across surfaces — {@code LegView}
 * vs the model {@code Leg}); this owns only the open+close doubling, the flat per-order fee, and the
 * negative-fee clamp.
 */
public final class Fees {

    private Fees() {}

    /**
     * Round-trip = open + close: {@code optionContracts × per-contract fee × 2}, plus the flat
     * per-order fee × 2 when there is at least one option contract. Stock-only packages carry no
     * option commission. Negative configured fees are clamped to zero.
     */
    public static long roundTripCents(long optionContracts, long feePerContractCents, long feePerOrderCents) {
        if (optionContracts <= 0) return 0;
        return optionContracts * Math.max(0, feePerContractCents) * 2
                + Math.max(0, feePerOrderCents) * 2;
    }
}
