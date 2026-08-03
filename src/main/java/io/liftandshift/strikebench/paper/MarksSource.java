package io.liftandshift.strikebench.paper;

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

    /** One atomic quote including value, provenance, freshness, and source timestamp. */
    Optional<Quote> underlyingQuote(String symbol, String worldId);

    /** The current per-share underlying mark in the named world. */
    default java.util.Optional<java.math.BigDecimal> underlyingMark(String symbol, String worldId) {
        return underlyingQuote(symbol, worldId).map(Quote::mark)
                .filter(mark -> mark != null && mark.signum() > 0);
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

    /** Current executable/mark data for the exact contract in the named world. */
    Optional<LegMark> legMark(String symbol, Leg leg, String worldId);

    /** Exact underlying close for settlement in the named world. */
    Optional<BigDecimal> closeOn(String symbol, java.time.LocalDate date, String worldId);

    /** The mode's effective clock: a simulated world's sim instant; empty = use the real clock. */
    Optional<java.time.Instant> simNow(String worldId);

    /** The mode's "now": the world's sim instant inside a simulated session, else the caller's clock. */
    default java.time.Instant simNow(String worldId, java.time.Clock clock) {
        return simNow(worldId).orElseGet(clock::instant);
    }


    /**
     * bid/ask are the EXECUTABLE sides (null/zero = no market on that side); mid is the
     * display/marking price. Paper fills must use the executable side, never the mid.
     */
    record LegMark(BigDecimal bid, BigDecimal ask, BigDecimal mid, Double iv,
                   Double delta, Double gamma, Double theta, Double vega, DataEvidence evidence,
                   Long asOfEpochMs) {
        public LegMark {
            evidence = evidence == null ? DataEvidence.missing("no leg evidence") : evidence;
        }

        public String freshness() { return evidence.code(); }

        /** Derive a stock leg from the exact underlying quote already owned by the mark snapshot. */
        public static LegMark fromUnderlying(Quote quote) {
            if (quote == null) return null;
            return new LegMark(quote.bid(), quote.ask(), quote.mark(), null,
                    1.0, 0.0, 0.0, 0.0,
                    quote.evidence(), quote.asOfEpochMs());
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

    /** Rate value in the named world; generated markets never borrow an observed input. */
    double riskFreeRate(int days, String worldId);

    /** Provenance of the rate assumption used by POP/EV modeling. */
    DataEvidence riskFreeRateEvidence(int days, String worldId);
}
