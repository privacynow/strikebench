package io.liftandshift.strikebench.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One leg of a strategy. Option legs have type/strike/expiration; stock legs (covered calls,
 * collars) have null type/strike/expiration. Multiplier is the deliverable units represented
 * by one ratio unit: 100 for standard options and stock lots, but adjusted contracts
 * and exact-share position packages may carry another positive value.
 *
 * entryPrice is the per-share price at entry: option premium, or share price for stock legs.
 */
public record Leg(
        LegAction action,
        OptionType type,        // null => stock leg
        BigDecimal strike,      // null for stock legs
        LocalDate expiration,   // null for stock legs
        int ratio,              // contracts or stock units per 1 package, >= 1
        BigDecimal entryPrice,  // per-share, >= 0
        int multiplier          // deliverable units per ratio unit, explicitly supplied
) {
    public static final int SHARES_PER_CONTRACT = 100;

    public Leg {
        if (action == null) throw new IllegalArgumentException("leg action required");
        if (ratio < 1) throw new IllegalArgumentException("leg ratio must be >= 1");
        if (multiplier < 1 || multiplier > 10_000) {
            throw new IllegalArgumentException("leg multiplier must be 1..10,000");
        }
        if (entryPrice == null || entryPrice.signum() < 0) throw new IllegalArgumentException("entryPrice must be >= 0");
        if (type != null && (strike == null || strike.signum() <= 0)) throw new IllegalArgumentException("option leg needs a positive strike");
        if (type != null && expiration == null) throw new IllegalArgumentException("option leg needs an expiration");
    }

    public static Leg option(LegAction action, OptionType type, BigDecimal strike, LocalDate expiration, int ratio, BigDecimal entryPrice) {
        return new Leg(action, type, strike, expiration, ratio, entryPrice, SHARES_PER_CONTRACT);
    }

    public static Leg option(LegAction action, OptionType type, BigDecimal strike, LocalDate expiration,
                             int ratio, BigDecimal entryPrice, int multiplier) {
        return new Leg(action, type, strike, expiration, ratio, entryPrice, multiplier);
    }

    public static Leg stock(LegAction action, int lots, BigDecimal sharePrice) {
        return new Leg(action, null, null, null, lots, sharePrice, SHARES_PER_CONTRACT);
    }

    public static Leg stockShares(LegAction action, int shares, BigDecimal sharePrice) {
        return new Leg(action, null, null, null, shares, sharePrice, 1);
    }

    public boolean isStock() { return type == null; }

    /** Intrinsic (exercise) value per share at underlying price s on expiration day. */
    public BigDecimal intrinsicPerShare(BigDecimal s) {
        if (isStock()) return s;
        BigDecimal diff = type == OptionType.CALL ? s.subtract(strike) : strike.subtract(s);
        return diff.signum() > 0 ? diff : BigDecimal.ZERO;
    }

    /** Signed profit per share at expiration underlying s, including entry premium, excluding fees. */
    public BigDecimal profitPerShare(BigDecimal s) {
        BigDecimal edge = intrinsicPerShare(s).subtract(entryPrice);
        return action == LegAction.BUY ? edge : edge.negate();
    }
}
