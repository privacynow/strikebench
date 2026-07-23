package io.liftandshift.strikebench.market;

import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.Freshness;

/** A pricing rate and its provenance. A modeled fallback is never presented as observed. */
public record RateQuote(double annualRate, DataEvidence evidence) {
    /** THE educational/modeled risk-free-rate fallback used when no observed rate is available.
     *  One value; every fallback site references this so a change lands in one place. */
    public static final double DEFAULT_MODELED_RATE = 0.04;

    public static RateQuote modeledDefault(double rate) {
        return new RateQuote(rate, DataEvidence.of("modeled-default", Freshness.MODELED));
    }
}
