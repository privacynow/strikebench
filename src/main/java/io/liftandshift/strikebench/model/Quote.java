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
        DataEvidence rawEvidence
) {
    public Quote {
        rawEvidence = rawEvidence == null ? DataEvidence.missing("no quote evidence") : rawEvidence;
    }

    /** Compact wire fields are projections of the one evidence value, never separate state. */
    public String source() { return rawEvidence.source(); }
    public String freshness() { return rawEvidence.label(); }

    /** Evidence for the value mark() actually returns, including a previous-close fallback. */
    public DataEvidence evidence() {
        DataEvidence raw = rawEvidence();
        if (!usesPreviousCloseFallback()) return raw;
        DataAge age = raw.provenance() == DataProvenance.OBSERVED || raw.provenance() == DataProvenance.BROKER
                ? DataAge.EOD : DataAge.STALE;
        return new DataEvidence(raw.provenance(), age, source() + " (previous-close fallback)");
    }

    public boolean usesPreviousCloseFallback() {
        return markBasis() == MarkBasis.PREVIOUS_CLOSE;
    }

    public String markFreshness() { return evidence().label(); }

    /**
     * WHICH input {@link #mark()} is quoting. ONE owner for the choice, so a wire row, a research
     * result and a stream frame all name the same basis instead of each re-deciding it. UNAVAILABLE
     * means there is nothing honest to show — the caller reports it unavailable with a reason and
     * never substitutes 0 (§3.2).
     */
    public enum MarkBasis { MID, LAST, PREVIOUS_CLOSE, UNAVAILABLE }

    /** The basis mark() is quoting — the single branch every consumer reads. */
    public MarkBasis markBasis() {
        if (hasSaneTwoSidedBook()) return MarkBasis.MID;
        if (last != null && last.signum() > 0) return MarkBasis.LAST;
        if (prevClose != null && prevClose.signum() > 0) return MarkBasis.PREVIOUS_CLOSE;
        return MarkBasis.UNAVAILABLE;
    }

    /**
     * Best available mark: mid of a sane two-sided book, else last, else previous close. Null when
     * none of the three exists — a symbol with no honest price has NO price here. It never falls
     * through to a zero or a negative previous close that a surface would render as $0.00.
     */
    public BigDecimal mark() {
        return switch (markBasis()) {
            case MID -> bid.add(ask).divide(BigDecimal.valueOf(2),
                    io.liftandshift.strikebench.util.Money.PRICE_SCALE, java.math.RoundingMode.HALF_UP);
            case LAST -> last;
            case PREVIOUS_CLOSE -> prevClose;
            case UNAVAILABLE -> null;
        };
    }

    /**
     * Change of {@code mark()} against the previous close, in percent. ONE owner for this
     * arithmetic: every surface reads this result instead of recomputing (price/prevClose-1)*100
     * from whichever price it happened to have. Null when either side is unknown — an unknown
     * change is reported as unavailable, never as 0%.
     */
    public Double markChangePct() { return changePct(mark(), prevClose); }

    /** The same rule for callers holding a mark and a previous close without a full Quote. */
    public static Double changePct(BigDecimal mark, BigDecimal previousClose) {
        if (mark == null || previousClose == null || previousClose.signum() <= 0) return null;
        return mark.subtract(previousClose)
                .divide(previousClose, 8, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();
    }

    private boolean hasSaneTwoSidedBook() {
        return bid != null && ask != null && bid.signum() > 0 && ask.signum() > 0 && ask.compareTo(bid) >= 0;
    }
}
