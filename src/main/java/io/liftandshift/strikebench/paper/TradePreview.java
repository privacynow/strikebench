package io.liftandshift.strikebench.paper;

import java.util.List;
import java.util.Map;

/** Dry-run of a trade: validation verdict plus exact before/after balances. Never mutates. */
public record TradePreview(
        boolean ok,
        List<String> blockReasons,
        List<String> warnings,
        long entryNetPremiumCents,     // credit > 0, debit < 0
        long feesOpenCents,
        long maxLossCents,
        Long maxProfitCents,           // null = unbounded upside OR model-dependent for multi-expiration structures
        List<String> breakevens,
        Double popEntry,
        Long expectedValueCents,
        long reserveCents,             // gross reserve held (future liability not already paid)
        long cashBeforeCents,
        long cashAfterCents,
        long reservedBeforeCents,
        long reservedAfterCents,
        long buyingPowerBeforeCents,
        long buyingPowerAfterCents,
        String freshness,
        io.liftandshift.strikebench.model.DataEvidence evidence,
        long underlyingCents,          // spot used for fills/curve (0 when unavailable)
        Double assignmentProb,         // chance ANY short strike finishes ITM; null if no shorts
        List<Map<String, Object>> legs,    // per-leg fills: action/type/strike/expiration/ratio/fill/bid/ask/mid/iv/greeks/freshness
        List<Map<String, Object>> payoff,  // expiration P/L samples {price, profitCents}; empty for multi-expiration
        Map<String, Object> analytics,     // probabilityMap / evSensitivity / managementPlan / verdict
        // THE canonical package-price receipt (§7.2). entryNetPremiumCents/feesOpenCents above are
        // this receipt's grossPackageNetCents/openingFeesCents; the receipt adds the option-only
        // net, the stock cash flow, the after-fee net, the executable vs resting distinction, the
        // basis, the quantity and the observation stamp — everything a surface needs to reconcile
        // its number against the candidate rail's instead of guessing (§3.3).
        PackagePriceReceipt price
) {}
