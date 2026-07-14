package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.Leg;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Current marks for revalidating and marking trades. Implemented over MarketDataService at
 * wiring time; stubbed in unit tests so the paper core stays independent of providers.
 */
public interface MarksSource {

    /** The underlying quote's OWN timestamp (source stamp), when the feed provides one. */
        /** World-aware variants: a SIMULATION account's trades mark against ITS world. Defaults
     *  ignore the world (observed) so existing implementations stay correct. */
    default java.util.Optional<java.math.BigDecimal> underlyingMark(String symbol, String worldId) {
        return underlyingMark(symbol);
    }

    /** Provenance/age of the exact underlying value returned by underlyingMark. */
    default java.util.Optional<DataEvidence> underlyingEvidence(String symbol, String worldId) {
        return java.util.Optional.empty();
    }

    default java.util.Optional<LegMark> legMark(String symbol, io.liftandshift.strikebench.model.Leg leg, String worldId) {
        return legMark(symbol, leg);
    }

    default java.util.Optional<java.math.BigDecimal> closeOn(String symbol, java.time.LocalDate date, String worldId) {
        return closeOn(symbol, date);
    }

default java.util.Optional<Long> underlyingAsOfMs(String symbol) { return java.util.Optional.empty(); }

    /** The data's own stamp from the lane that actually prices the trade. */
    default java.util.Optional<Long> underlyingAsOfMs(String symbol, String worldId) {
        return underlyingAsOfMs(symbol);
    }

    /** The lane's effective clock: a simulated world's sim instant; empty = use the real clock. */
    default java.util.Optional<java.time.Instant> simNow(String worldId) { return java.util.Optional.empty(); }


    /**
     * bid/ask are the EXECUTABLE sides (null/zero = no market on that side); mid is the
     * display/marking price. Paper fills must use the executable side, never the mid.
     */
    record LegMark(BigDecimal bid, BigDecimal ask, BigDecimal mid, Double iv, Freshness freshness,
                   Double delta, Double gamma, Double theta, Double vega, DataEvidence evidence) {
        /** Convenience constructor without greeks (stubs, stock legs). */
        public LegMark(BigDecimal bid, BigDecimal ask, BigDecimal mid, Double iv, Freshness freshness) {
            this(bid, ask, mid, iv, freshness, null, null, null, null,
                    DataEvidence.of(null, freshness));
        }

        /**
         * Price at which this leg can actually be traded right now, or null.
         * A crossed book (bid > ask) is a stale-quote artifact — "buying the ask and selling
         * the higher bid" mints fictional money, so crossed books are not executable at all.
         */
        public BigDecimal executable(io.liftandshift.strikebench.model.LegAction action) {
            return io.liftandshift.strikebench.market.ExecutablePrice.forAction(bid, ask, action);
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

    /** Lane-aware rate value; generated markets must not silently borrow an observed input. */
    default double riskFreeRate(int days, String worldId) { return riskFreeRate(days); }

    /** Provenance of the rate assumption used by POP/EV modeling. */
    default DataEvidence riskFreeRateEvidence(int days, String worldId) {
        return DataEvidence.of("educational rate assumption", Freshness.MODELED);
    }
}
