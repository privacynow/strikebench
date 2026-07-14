package io.liftandshift.strikebench.recommend;

import java.util.List;

/**
 * One risk-screened, data-backed educational candidate. Never a promise of profit —
 * every candidate carries its assumptions, risks, and invalidation conditions.
 *
 * Intent-flow fields: assignmentProb is the modeled chance the short legs finish in the
 * money (risk-neutral, at each leg's own IV; early assignment not modeled). For ACQUIRE
 * and EXIT intents assignment IS the goal, so present it as the chance of success there.
 * annualizedYieldPct is premium income over capital at risk, annualized by days to expiry.
 * usesHeldShares candidates carry option legs only; the trade layer locks sharesNeeded
 * held shares as coverage, so maxLossCents is the trade's INCREMENTAL cash risk while
 * combinedMaxLossCents is the worst case including the locked shares from today's price.
 */
public record Candidate(
        String strategy,
        String displayName,
        String structureGroup,        // presentation diversity only; never changes the ranked order
        String label,                 // short human summary, e.g. "SELL 555P / BUY 550P Aug 21"
        List<LegView> legs,
        int qty,
        long entryNetPremiumCents,    // credit > 0, debit < 0
        Long maxProfitCents,          // null = uncapped or model-dependent
        long maxLossCents,
        List<String> breakevens,
        Double pop,                   // probability of profit under lognormal model, null when model-dependent
        Long expectedValueCents,      // modeled EV, null when model-dependent
        double liquidityScore,        // 0..1
        String freshness,
        List<String> warnings,
        double confidence,            // 0..1
        String whyConsidered,
        String bestUpside,
        String biggestRisk,
        String wouldInvalidate,
        String beginnerExplanation,
        String intent,                // StrategyIntent this candidate was generated under
        List<String> intents,         // every intent the family serves (first = primary)
        Double assignmentProb,        // 0..1, null when the structure has no short legs
        Double annualizedYieldPct,    // net opening premium / actual share-or-strike collateral / year
        String effectivePrice,        // strike +/- option premium after opening fees, null when n/a
        String intentNote,            // human framing vs the holdings/target context
        Boolean usesHeldShares,
        Integer sharesNeeded,         // held shares this trade would lock, when usesHeldShares
        Long combinedMaxLossCents     // worst case incl. locked shares from today's price, when usesHeldShares
) {}
