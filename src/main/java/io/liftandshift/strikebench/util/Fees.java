package io.liftandshift.strikebench.util;

import io.liftandshift.strikebench.model.Leg;

import java.util.List;

/**
 * THE one commission formula. Every EV-after-fees consumer routes the final arithmetic through
 * here so a fee-policy change lands in one place and the verdict, the decision score, the outcome
 * projection and a backtest can never net different fees off the same package.
 *
 * <p>Policy, in ONE place: a stock-only package carries no commission at all (no per-contract fee
 * and no flat per-order fee); an option package pays {@code contracts × per-contract + per-order}
 * to open, doubled to close. TradeService and Backtester previously charged the flat per-order fee
 * even on a stock-only order — a divergence masked only because the configured per-order fee
 * defaults to zero. Negative configured fees are clamped to zero.
 *
 * <p>{@link #optionContracts} counts a {@code model.Leg} package; surfaces on a different leg type
 * (e.g. {@code LegView}) still count their own contracts and pass the total in.
 */
public final class Fees {

    private Fees() {}

    /** Total OPTION contracts across a {@code model.Leg} package at a given quantity (stock legs excluded). */
    public static long optionContracts(List<Leg> legs, int qty) {
        return legs.stream().filter(l -> !l.isStock()).mapToLong(l -> (long) l.ratio() * qty).sum();
    }

    /** Opening (one-way) commission, or 0 for a stock-only package (no option contracts). */
    public static long openingCents(long optionContracts, long feePerContractCents, long feePerOrderCents) {
        if (optionContracts <= 0) return 0;
        return optionContracts * Math.max(0, feePerContractCents) + Math.max(0, feePerOrderCents);
    }

    /** Round-trip = open + close = {@code 2 ×} the opening commission. Stock-only packages carry none. */
    public static long roundTripCents(long optionContracts, long feePerContractCents, long feePerOrderCents) {
        return 2 * openingCents(optionContracts, feePerContractCents, feePerOrderCents);
    }
}
