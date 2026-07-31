package io.liftandshift.strikebench.market;

import io.liftandshift.strikebench.model.Quote;

import java.util.Optional;

/**
 * Typed result of one quote acquisition attempt. A retained last-known observation is useful data,
 * but it is not a successful refresh and must never advance refresh-success telemetry.
 */
public record QuoteAcquisition(Optional<Quote> quote, Outcome outcome,
                               long attemptedAtEpochMs, String detail) {
    public enum Outcome {
        ACQUIRED,
        RETAINED_LAST_KNOWN,
        UNAVAILABLE
    }

    public QuoteAcquisition {
        quote = quote == null ? Optional.empty() : quote;
        outcome = outcome == null ? Outcome.UNAVAILABLE : outcome;
        detail = detail == null ? "" : detail;
        if (outcome == Outcome.UNAVAILABLE && quote.isPresent()) {
            throw new IllegalArgumentException("an unavailable acquisition cannot carry a quote");
        }
        if (outcome != Outcome.UNAVAILABLE && quote.isEmpty()) {
            throw new IllegalArgumentException("a quote acquisition outcome requires a quote");
        }
    }

    public boolean acquired() { return outcome == Outcome.ACQUIRED; }
}
