package io.liftandshift.strikebench.recommend;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.liftandshift.strikebench.paper.PackagePriceReceipt;
import io.liftandshift.strikebench.strategy.CapitalRequirement;
import io.liftandshift.strikebench.strategy.StrategyCatalog;
import io.liftandshift.strikebench.strategy.StrategyFamily;

import java.util.List;

/**
 * One risk-screened, data-backed educational candidate. Never a promise of profit —
 * every candidate carries its assumptions, risks, and invalidation conditions.
 *
 * Intent-flow fields: assignmentProb is the modeled chance the short legs finish in the
 * money (risk-neutral, at each leg's own IV; early assignment not modeled). For ACQUIRE
 * and EXIT intents assignment IS the goal, so present it as the chance of success there.
 * annualizedYieldPct is premium income over named strike-cash or share collateral (never
 * defined-risk ROC), annualized through the candidate's canonical {@code OptionTime.Measure}
 * calendar-time fraction.
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
        // THE canonical package-price receipt (§7.2) — the same object the preview, the order dock,
        // the review screen and a held close carry. It replaced the bare entryNetPremiumCents /
        // optionNetPremiumCents pair, which stated two amounts on an undisclosed basis at an
        // undisclosed time and so could never be reconciled against the dock's number (§3.3).
        io.liftandshift.strikebench.paper.PackagePriceReceipt price,
        Long maxProfitCents,          // null = uncapped or model-dependent
        long maxLossCents,
        List<String> breakevens,
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
        Long combinedMaxLossCents,    // worst case incl. locked shares from today's price, when usesHeldShares
        // The sole market-implied probability/EV authority for this exact priced package.
        io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.Receipt marketImpliedRisk
) {
    /**
     * Every candidate carries a §7.2 receipt — a missing one IS the unpriced state, so it is
     * normalized here rather than left as a null that each of the dozen downstream consumers would
     * have to remember to guard. Consumers ask {@code price().priced()} and get one answer.
     */
    public Candidate {
        if (qty < 1) {
            throw new IllegalArgumentException("candidate requires quantity >= 1");
        }
        if (price == null) {
            price = io.liftandshift.strikebench.paper.PackagePriceReceipt.unavailable(
                    qty, io.liftandshift.strikebench.paper.PackagePriceReceipt.FeeSide.OPENING,
                    "No package-price receipt was produced for this candidate.");
        }
        if (price.quantity() != qty) {
            throw new IllegalArgumentException(
                    "candidate quantity must match its package-price receipt quantity");
        }
        if (marketImpliedRisk == null) {
            marketImpliedRisk = io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.Receipt.unavailable(
                    "No fingerprinted market-implied evaluation was captured for this candidate.");
        } else if (marketImpliedRisk.available()) {
            if (!java.util.Objects.equals(price.fingerprint(), marketImpliedRisk.priceFingerprint())) {
                throw new IllegalArgumentException(
                        "candidate market-implied evaluation does not match its package-price fingerprint");
            }
        }
    }

    /** The one typed capital-use receipt consumed by filters, evaluation, scoring and Scout. */
    @JsonProperty("capital")
    public CapitalRequirement capital() {
        return capital(strategy, price, maxLossCents, combinedMaxLossCents,
                Boolean.TRUE.equals(usesHeldShares));
    }

    /** Same receipt composer for persisted candidate read models rebuilt from exact stored facts. */
    public static CapitalRequirement capital(String strategy, PackagePriceReceipt price,
                                             Long maxLossCents, Long combinedMaxLossCents,
                                             boolean usesHeldShares) {
        StrategyCatalog.PositionIdentity identity = null;
        if (strategy != null && !strategy.isBlank()) {
            try {
                identity = StrategyCatalog.identify(
                        StrategyFamily.valueOf(strategy.trim().toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // A custom/unknown family requires an exact-package assessment.
            }
        }
        return CapitalRequirement.of(identity, price, maxLossCents, combinedMaxLossCents,
                usesHeldShares);
    }
}
