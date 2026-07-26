package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.Quote;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Current marks for revalidating and marking trades. Implemented over MarketDataService at
 * wiring time; stubbed in unit tests so the paper core stays independent of providers.
 */
public interface MarksSource {

    /**
     * One underlying quote receipt. A caller that needs price, provenance and timestamp must read
     * this object once instead of making three provider/cache traversals that can observe three
     * different market instants.
     *
     * <p>The compatibility default adapts older test/port implementations. Production market
     * implementations override it and return their native {@link Quote} atomically.</p>
     */
    default Optional<Quote> underlyingQuote(String symbol, String worldId) {
        Optional<BigDecimal> mark = underlyingMark(symbol, worldId);
        if (mark.isEmpty()) return Optional.empty();
        DataEvidence evidence = underlyingEvidence(symbol, worldId).orElse(null);
        Freshness freshness = freshnessOf(evidence);
        String source = evidence == null ? null : evidence.source();
        long asOf = underlyingAsOfMs(symbol, worldId).orElse(0L);
        return Optional.of(new Quote(symbol, null, mark.get(), null, null, null,
                null, null, null, true, asOf, source, freshness));
    }

    /** World-aware variants: a SIMULATION account's trades mark against ITS world. Defaults
     *  ignore the world (observed) so existing implementations stay correct. */
    default java.util.Optional<java.math.BigDecimal> underlyingMark(String symbol, String worldId) {
        return underlyingMark(symbol);
    }

    /** Batch counterpart for holdings/valuation screens. Implementations backed by a shared
     * market engine override this so N symbols do not become N provider/cache traversals. */
    default Map<String, BigDecimal> underlyingMarks(List<String> symbols, String worldId) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        if (symbols == null) return out;
        for (String symbol : symbols) {
            underlyingMark(symbol, worldId).ifPresent(mark -> out.put(symbol, mark));
        }
        return out;
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

    /** The underlying quote's OWN timestamp (source stamp), when the feed provides one. */
    default java.util.Optional<Long> underlyingAsOfMs(String symbol) {
        return java.util.Optional.empty();
    }

    /** The data's own stamp from the lane that actually prices the trade. */
    default java.util.Optional<Long> underlyingAsOfMs(String symbol, String worldId) {
        return underlyingAsOfMs(symbol);
    }

    /** The lane's effective clock: a simulated world's sim instant; empty = use the real clock. */
    default java.util.Optional<java.time.Instant> simNow(String worldId) { return java.util.Optional.empty(); }

    /** The lane's "now": the world's sim instant inside a simulated session, else the caller's clock. */
    default java.time.Instant simNow(String worldId, java.time.Clock clock) {
        return simNow(worldId).orElseGet(clock::instant);
    }


    /**
     * bid/ask are the EXECUTABLE sides (null/zero = no market on that side); mid is the
     * display/marking price. Paper fills must use the executable side, never the mid.
     */
    record LegMark(BigDecimal bid, BigDecimal ask, BigDecimal mid, Double iv, Freshness freshness,
                   Double delta, Double gamma, Double theta, Double vega, DataEvidence evidence,
                   Long asOfEpochMs) {
        /** Derive a stock leg from the exact underlying quote already owned by the mark snapshot. */
        public static LegMark fromUnderlying(Quote quote) {
            if (quote == null) return null;
            return new LegMark(quote.bid(), quote.ask(), quote.mark(), null,
                    quote.markFreshness(), 1.0, 0.0, 0.0, 0.0,
                    quote.evidence(), quote.asOfEpochMs());
        }

        /** Compatibility constructor for marks whose source timestamp is unavailable. */
        public LegMark(BigDecimal bid, BigDecimal ask, BigDecimal mid, Double iv, Freshness freshness,
                       Double delta, Double gamma, Double theta, Double vega, DataEvidence evidence) {
            this(bid, ask, mid, iv, freshness, delta, gamma, theta, vega, evidence, null);
        }

        /** Convenience constructor without greeks (stubs, stock legs). */
        public LegMark(BigDecimal bid, BigDecimal ask, BigDecimal mid, Double iv, Freshness freshness) {
            this(bid, ask, mid, iv, freshness, null, null, null, null,
                    DataEvidence.of(null, freshness), null);
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
    default double riskFreeRate(int days) { return io.liftandshift.strikebench.market.RateQuote.DEFAULT_MODELED_RATE; }

    /** Lane-aware rate value; generated markets must not silently borrow an observed input. */
    default double riskFreeRate(int days, String worldId) { return riskFreeRate(days); }

    /** Provenance of the rate assumption used by POP/EV modeling. */
    default DataEvidence riskFreeRateEvidence(int days, String worldId) {
        return DataEvidence.of("educational rate assumption", Freshness.MODELED);
    }

    /** Lossless mapping for the legacy adapter above; provenance resolves NOT_APPLICABLE ages. */
    private static Freshness freshnessOf(DataEvidence evidence) {
        if (evidence == null || evidence.age() == null) return Freshness.MISSING;
        return switch (evidence.age()) {
            case REALTIME -> Freshness.REALTIME;
            case DELAYED -> Freshness.DELAYED;
            case EOD -> Freshness.EOD;
            case STALE -> Freshness.STALE;
            case MISSING -> Freshness.MISSING;
            case NOT_APPLICABLE -> switch (evidence.provenance()) {
                case DEMO -> Freshness.FIXTURE;
                case SIMULATED -> Freshness.SIMULATED;
                case MODELED -> Freshness.MODELED;
                default -> Freshness.MISSING;
            };
        };
    }
}
