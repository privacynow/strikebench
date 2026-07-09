package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.Leg;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Current marks for revalidating and marking trades. Implemented over MarketDataService at
 * wiring time; stubbed in unit tests so the paper core stays independent of providers.
 */
public interface MarksSource {

    /**
     * bid/ask are the EXECUTABLE sides (null/zero = no market on that side); mid is the
     * display/marking price. Paper fills must use the executable side, never the mid.
     */
    record LegMark(BigDecimal bid, BigDecimal ask, BigDecimal mid, Double iv, Freshness freshness,
                   Double delta, Double gamma, Double theta, Double vega) {
        /** Convenience constructor without greeks (stubs, stock legs). */
        public LegMark(BigDecimal bid, BigDecimal ask, BigDecimal mid, Double iv, Freshness freshness) {
            this(bid, ask, mid, iv, freshness, null, null, null, null);
        }

        /**
         * Price at which this leg can actually be traded right now, or null.
         * A crossed book (bid > ask) is a stale-quote artifact — "buying the ask and selling
         * the higher bid" mints fictional money, so crossed books are not executable at all.
         */
        public BigDecimal executable(io.liftandshift.strikebench.model.LegAction action) {
            if (bid != null && ask != null && bid.signum() > 0 && ask.signum() > 0
                    && bid.compareTo(ask) > 0) {
                return null; // crossed book
            }
            BigDecimal side = action == io.liftandshift.strikebench.model.LegAction.BUY ? ask : bid;
            return side != null && side.signum() > 0 ? side : null;
        }
    }

    /** Current per-share mark of the underlying. */
    Optional<BigDecimal> underlyingMark(String symbol);

    /** Current per-share mid for the specific contract a leg references (or the stock). */
    Optional<LegMark> legMark(String symbol, Leg leg);

    /** Underlying close on a specific (past) date, for settling at expiration-day value. */
    default java.util.Optional<BigDecimal> closeOn(String symbol, java.time.LocalDate date) {
        return java.util.Optional.empty();
    }

    /** Annualized risk-free rate for POP/EV modeling. */
    default double riskFreeRate(int days) { return 0.04; }
}
