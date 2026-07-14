package io.liftandshift.strikebench.market;

import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.util.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** One executable-book rule for recommendations, paper fills, and historical replay. */
public final class ExecutablePrice {
    private ExecutablePrice() {}

    /** Returns the requested executable side, or null for a missing/non-positive/crossed side. */
    public static BigDecimal forAction(BigDecimal bid, BigDecimal ask, LegAction action) {
        if (crossed(bid, ask)) return null;
        BigDecimal side = action == LegAction.BUY ? ask : bid;
        return side != null && side.signum() > 0 ? side : null;
    }

    /** Two-sided display midpoint. It is never a fill and is unavailable on crossed books. */
    public static BigDecimal midpoint(BigDecimal bid, BigDecimal ask) {
        if (bid == null || ask == null || bid.signum() < 0 || ask.signum() <= 0 || crossed(bid, ask)) {
            return null;
        }
        return bid.add(ask).divide(BigDecimal.valueOf(2), Money.PRICE_SCALE, RoundingMode.HALF_UP);
    }

    private static boolean crossed(BigDecimal bid, BigDecimal ask) {
        return bid != null && ask != null && bid.signum() > 0 && ask.signum() > 0
                && bid.compareTo(ask) > 0;
    }
}
