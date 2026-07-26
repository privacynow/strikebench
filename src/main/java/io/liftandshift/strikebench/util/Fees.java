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
 * defaults to zero. Negative fee input is rejected: silently converting it to zero would publish
 * a free-trade receipt on malformed configuration.
 *
 * <p>{@link #optionContracts} counts a {@code model.Leg} package; surfaces on a different leg type
 * (e.g. {@code LegView}) still count their own contracts and pass the total in.
 */
public final class Fees {

    private Fees() {}

    /** One captured commission schedule for an exact package at an exact quantity. */
    public record Schedule(long openingCents, long closingCents, long roundTripCents) {
        public Schedule {
            if (openingCents < 0 || closingCents < 0 || roundTripCents < 0) {
                throw new IllegalArgumentException("commission schedule cannot be negative");
            }
            if (roundTripCents != Math.addExact(openingCents, closingCents)) {
                throw new IllegalArgumentException("round-trip commission must equal opening plus closing");
            }
        }
    }

    /** Total OPTION contracts across a {@code model.Leg} package at a given quantity (stock legs excluded). */
    public static long optionContracts(List<Leg> legs, int qty) {
        return legs.stream().filter(l -> !l.isStock()).mapToLong(l -> (long) l.ratio() * qty).sum();
    }

    /** Opening (one-way) commission, or 0 for a stock-only package (no option contracts). */
    public static long openingCents(long optionContracts, long feePerContractCents, long feePerOrderCents) {
        if (feePerContractCents < 0 || feePerOrderCents < 0) {
            throw new IllegalArgumentException("configured commissions cannot be negative");
        }
        if (optionContracts <= 0) return 0;
        return Math.addExact(Math.multiplyExact(optionContracts, feePerContractCents),
                feePerOrderCents);
    }

    /** Round-trip = open + close = {@code 2 ×} the opening commission. Stock-only packages carry none. */
    public static long roundTripCents(long optionContracts, long feePerContractCents, long feePerOrderCents) {
        return schedule(optionContracts, feePerContractCents, feePerOrderCents).roundTripCents();
    }

    /** THE complete commission schedule used by package-price producers. */
    public static Schedule schedule(long optionContracts, long feePerContractCents, long feePerOrderCents) {
        long opening = openingCents(optionContracts, feePerContractCents, feePerOrderCents);
        long closing = opening;
        return new Schedule(opening, closing, Math.addExact(opening, closing));
    }
}
