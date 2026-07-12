package io.liftandshift.strikebench.model;

/** A news headline or filing reference for a symbol. */
public record NewsItem(
        String symbol,
        String headline,
        String source,
        String url,
        long publishedEpochMs
) {}
