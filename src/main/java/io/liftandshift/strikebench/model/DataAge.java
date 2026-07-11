package io.liftandshift.strikebench.model;

/** Recency of a value, independent from whether it was observed or generated. */
public enum DataAge {
    REALTIME,
    DELAYED,
    EOD,
    STALE,
    NOT_APPLICABLE,
    MISSING
}
