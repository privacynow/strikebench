package io.liftandshift.strikebench.paper;

import java.util.List;
import java.util.Map;
import io.liftandshift.strikebench.recommend.LegView;

/** Dry-run of a trade: validation verdict plus exact before/after balances. Never mutates. */
public record TradePreview(
        boolean ok,
        List<String> blockReasons,
        List<String> warnings,
        // A refused/unpriced package has no risk fact. Explicit null is materially different from
        // a valid zero-risk cash flow and must survive the API mapper's NON_NULL default.
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
        Long maxLossCents,
        Long maxProfitCents,           // null = unbounded upside OR model-dependent for multi-expiration structures
        List<String> breakevens,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
        Long reserveCents,             // gross reserve held (future liability not already paid)
        long cashBeforeCents,
        long cashAfterCents,
        long reservedBeforeCents,
        long reservedAfterCents,
        long buyingPowerBeforeCents,
        long buyingPowerAfterCents,
        String freshness,
        io.liftandshift.strikebench.model.DataEvidence evidence,
        // §3.2/§3.3: the spot used for fills/curve. NULL when no mode-owned underlying mark exists —
        // never a substituted 0, which a surface cannot tell apart from a $0.00 stock. The REASON
        // rides in blockReasons ("No current price for AAPL" / the mode-executability refusal), the
        // same channel that already explains why the package could not be priced; a preview whose
        // underlying is unknown is always a refused preview. Serialized ALWAYS so the browser sees
        // an explicit null instead of a vanished key (the shared mapper is NON_NULL by default and
        // a missing key is indistinguishable from a null one in JS).
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
        Long underlyingCents,
        Double shortSideExpirationItmProb, // chance ANY short strike finishes ITM at expiry; not assignment odds
        List<LegView> legs,                // exact per-leg geometry, price, book, greeks, and evidence
        List<Map<String, Object>> payoff,  // expiration P/L samples {price, profitCents}; empty for multi-expiration
        Map<String, Object> analytics,     // managementPlan / verdict / execution-quality results
        // THE normalized package-price result (§7.2) — and now the ONLY package price this preview
        // publishes. It carries the option-only net, the stock cash flow, the gross package net, the
        // commission, the after-fee net, the executable vs resting distinction, the valuation basis,
        // the quantity and the observation stamp: everything a surface needs to reconcile its number
        // against the candidate rail's instead of guessing (§3.3).
        //
        // Every consumer reads this result directly. An unavailable price remains unavailable
        // instead of being represented by primitive zero-valued fields.
        PackagePrice price,
        // The one normalized options-implied terminal range for this package's nearest expiry.
        // This is deliberately the SimulationEngine result itself—not a TradeService map rebuilt
        // from the same inputs. Null means the package had no positive captured IV/live option
        // clock from which that owner could state a range.
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
        io.liftandshift.strikebench.sim.SimulationEngine.MarketImpliedRange marketImpliedRange,
        io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.RiskNeutralAnalysis marketImpliedRisk
) {
    public TradePreview {
        boolean maxLossKnown = maxLossCents != null;
        boolean reserveKnown = reserveCents != null;
        if (maxLossKnown != reserveKnown) {
            throw new IllegalArgumentException(
                    "maximum loss and reserve must either both be known or both be unavailable");
        }
        if (maxLossKnown && (maxLossCents < 0 || reserveCents < 0)) {
            throw new IllegalArgumentException("maximum loss and reserve cannot be negative");
        }
        if (ok && !maxLossKnown) {
            throw new IllegalArgumentException(
                    "an executable preview requires maximum-loss and reserve results");
        }
        if (marketImpliedRisk == null) {
            marketImpliedRisk = io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.RiskNeutralAnalysis.unavailable(
                    "No fingerprinted market-implied evaluation was captured for this preview.");
        }
        if (marketImpliedRisk.available()) {
            if (price == null || !java.util.Objects.equals(
                    price.fingerprint(), marketImpliedRisk.priceFingerprint())) {
                throw new IllegalArgumentException(
                        "preview market-implied evaluation does not match its package-price fingerprint");
            }
        }
    }

    /** True only when the package has a complete finite-risk result. */
    public boolean hasRiskFacts() {
        return maxLossCents != null;
    }

    /**
     * Mutation and risk-ranking boundaries call this instead of auto-unboxing a nullable wire
     * fact. A refused preview may legitimately omit risk; an accepted action may not.
     */
    public long requiredMaxLossCents() {
        if (maxLossCents == null) {
            throw new IllegalStateException(
                    "This package has no maximum-loss result; the action cannot be approved.");
        }
        return maxLossCents;
    }

    /** See {@link #requiredMaxLossCents()}; reserve shares the same availability invariant. */
    public long requiredReserveCents() {
        if (reserveCents == null) {
            throw new IllegalStateException(
                    "This package has no reserve result; the action cannot be approved.");
        }
        return reserveCents;
    }
}
