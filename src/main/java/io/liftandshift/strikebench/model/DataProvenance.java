package io.liftandshift.strikebench.model;

/** Where a value came from. This is intentionally independent from how old it is. */
public enum DataProvenance {
    OBSERVED,
    BROKER,
    DEMO,
    SIMULATED,
    MODELED,
    MIXED,
    MISSING
}
