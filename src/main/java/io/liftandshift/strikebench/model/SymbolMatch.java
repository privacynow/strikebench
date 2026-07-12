package io.liftandshift.strikebench.model;

/** A ticker lookup result. */
public record SymbolMatch(
        String symbol,
        String description,
        boolean optionable
) {}
