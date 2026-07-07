package io.liftandshift.paper;

import java.util.List;

/** Dry-run of a trade: validation verdict plus exact before/after balances. Never mutates. */
public record TradePreview(
        boolean ok,
        List<String> blockReasons,
        List<String> warnings,
        long entryNetPremiumCents,     // credit > 0, debit < 0
        long feesOpenCents,
        long maxLossCents,
        Long maxProfitCents,           // null = unbounded upside
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
        String freshness
) {}
