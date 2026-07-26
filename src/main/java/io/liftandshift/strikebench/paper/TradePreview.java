package io.liftandshift.strikebench.paper;

import java.util.List;
import java.util.Map;

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
        Double popEntry,
        Long expectedValueCents,
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
        // §3.2/§3.3: the spot used for fills/curve. NULL when no lane-owned underlying mark exists —
        // never a substituted 0, which a surface cannot tell apart from a $0.00 stock. The REASON
        // rides in blockReasons ("No current price for AAPL" / the lane-executability refusal), the
        // same channel that already explains why the package could not be priced; a preview whose
        // underlying is unknown is always a refused preview. Serialized ALWAYS so the browser sees
        // an explicit null instead of a vanished key (the shared mapper is NON_NULL by default and
        // a missing key is indistinguishable from a null one in JS).
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
        Long underlyingCents,
        Double assignmentProb,         // chance ANY short strike finishes ITM; null if no shorts
        List<Map<String, Object>> legs,    // per-leg fills: action/type/strike/expiration/ratio/fill/bid/ask/mid/iv/greeks/freshness
        List<Map<String, Object>> payoff,  // expiration P/L samples {price, profitCents}; empty for multi-expiration
        Map<String, Object> analytics,     // probabilityMap / evSensitivity / managementPlan / verdict
        // THE canonical package-price receipt (§7.2) — and now the ONLY package price this preview
        // publishes. It carries the option-only net, the stock cash flow, the gross package net, the
        // commission, the after-fee net, the executable vs resting distinction, the valuation basis,
        // the quantity and the observation stamp: everything a surface needs to reconcile its number
        // against the candidate rail's instead of guessing (§3.3).
        //
        // This record used to publish `entryNetPremiumCents` and `feesOpenCents` BESIDE the receipt,
        // holding the same two amounts as primitive longs. They were not a harmless alias: on every
        // refused package the receipt correctly said "no price, and here is why" while the two
        // primitives said 0 and $0.00 of commission — a substituted zero (§3.2) that a browser or a
        // Java consumer could not tell apart from a genuinely free trade, published under the name a
        // surface was most likely to read. One receipt is now not just published but consumed
        // (§3.1): every consumer reads `price`, and a consumer that cannot proceed without a price
        // says so rather than reading a zero.
        PackagePriceReceipt price
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
                    "an executable preview requires maximum-loss and reserve receipts");
        }
    }

    /** True only when the package has a complete finite-risk receipt. */
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
                    "This package has no maximum-loss receipt; the action cannot be approved.");
        }
        return maxLossCents;
    }

    /** See {@link #requiredMaxLossCents()}; reserve shares the same availability invariant. */
    public long requiredReserveCents() {
        if (reserveCents == null) {
            throw new IllegalStateException(
                    "This package has no reserve receipt; the action cannot be approved.");
        }
        return reserveCents;
    }
}
