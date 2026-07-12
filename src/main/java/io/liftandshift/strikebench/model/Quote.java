package io.liftandshift.strikebench.model;

import java.math.BigDecimal;

/** Normalized underlying quote. All prices are BigDecimal; null when unknown. */
public record Quote(
        String symbol,
        String description,
        BigDecimal last,
        BigDecimal bid,
        BigDecimal ask,
        BigDecimal prevClose,
        BigDecimal dayHigh,
        BigDecimal dayLow,
        Long volume,
        boolean optionable,
        long asOfEpochMs,
        String source,
        Freshness freshness
) {
    public DataEvidence rawEvidence() { return DataEvidence.of(source, freshness); }

    /** Evidence for the value mark() actually returns, including a previous-close fallback. */
    public DataEvidence evidence() {
        DataEvidence raw = rawEvidence();
        if (!usesPreviousCloseFallback()) return raw;
        DataAge age = raw.provenance() == DataProvenance.OBSERVED || raw.provenance() == DataProvenance.BROKER
                ? DataAge.EOD : DataAge.STALE;
        return new DataEvidence(raw.provenance(), age, source + " (previous-close fallback)");
    }

    public boolean usesPreviousCloseFallback() {
        return !hasSaneTwoSidedBook() && (last == null || last.signum() <= 0)
                && prevClose != null && prevClose.signum() > 0;
    }

    public Freshness markFreshness() {
        if (!usesPreviousCloseFallback()) return freshness;
        DataProvenance p = rawEvidence().provenance();
        return p == DataProvenance.OBSERVED || p == DataProvenance.BROKER ? Freshness.EOD : Freshness.STALE;
    }

    /** Best available mark: mid of bid/ask, else last, else prevClose. */
    public BigDecimal mark() {
        if (hasSaneTwoSidedBook()) {
            return bid.add(ask).divide(BigDecimal.valueOf(2), io.liftandshift.strikebench.util.Money.PRICE_SCALE, java.math.RoundingMode.HALF_UP);
        }
        if (last != null && last.signum() > 0) return last;
        return prevClose;
    }

    private boolean hasSaneTwoSidedBook() {
        return bid != null && ask != null && bid.signum() > 0 && ask.signum() > 0 && ask.compareTo(bid) >= 0;
    }
}
