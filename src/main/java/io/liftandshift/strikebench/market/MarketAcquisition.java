package io.liftandshift.strikebench.market;

import java.util.Optional;

/**
 * One acquisition result for market observations. A retained stale value is usable evidence,
 * but it is not a successful refresh and must never advance freshness telemetry.
 */
public record MarketAcquisition<T>(
        Optional<T> value,
        Outcome outcome,
        long attemptedAtEpochMs,
        String detail) {

    public enum Outcome {
        FRESH,
        FALLBACK_STALE,
        FAILED
    }

    public MarketAcquisition {
        value = value == null ? Optional.empty() : value;
        if (outcome == null) throw new IllegalArgumentException("acquisition outcome is required");
        if (attemptedAtEpochMs <= 0) {
            throw new IllegalArgumentException("acquisition attempt time must be positive");
        }
        detail = detail == null ? "" : detail;
        if (outcome == Outcome.FAILED && value.isPresent()) {
            throw new IllegalArgumentException("a failed acquisition cannot carry an observation");
        }
        if (outcome != Outcome.FAILED && value.isEmpty()) {
            throw new IllegalArgumentException("a successful or fallback acquisition needs an observation");
        }
    }

    public boolean fresh() {
        return outcome == Outcome.FRESH;
    }
}
