package io.liftandshift.strikebench.research;

import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.util.Money;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Phase-5 research lab: replicate a target dollar exposure to an underlying (an ETF or any symbol)
 * with a capital-efficient options structure instead of buying the shares outright. A synthetic
 * stock position (long ATM call + short ATM put, or the reverse) carries ~100 delta per contract,
 * so N contracts replicate the delta of 100·N shares while tying up far less than the share cost.
 * Honest about what it is: a delta-1 replication, not a dividend/tracking-exact clone.
 */
public final class ETFReplicator {

    private final MarketDataService market;

    public ETFReplicator(MarketDataService market) { this.market = market; }

    public record ReplicationRequest(String symbol, Long targetExposureCents, Boolean bullish) {}

    public record ReplicationResult(String symbol, long targetExposureCents, long underlyingCents,
                                    int contracts, String structure, long deltaExposureCents,
                                    long shareCostCents, long estMarginCents, List<String> notes) {}

    public ReplicationResult replicate(ReplicationRequest req) {
        String symbol = req.symbol() == null ? "" : req.symbol().trim().toUpperCase(Locale.ROOT);
        if (symbol.isEmpty()) throw new IllegalArgumentException("symbol is required");
        long target = req.targetExposureCents() == null ? 0 : Math.max(0, req.targetExposureCents());
        boolean bullish = req.bullish() == null || req.bullish();

        BigDecimal spot = market.quote(symbol).map(q -> q.mark()).orElse(null);
        List<String> notes = new ArrayList<>();
        if (spot == null || spot.signum() <= 0) {
            notes.add("No price available for " + symbol + " — cannot size a replication.");
            return new ReplicationResult(symbol, target, 0, 0, "n/a", 0, 0, 0, notes);
        }
        long underlyingCents = Money.toCents(spot);
        long per100 = underlyingCents * 100;                 // exposure of one contract (100 shares)
        int contracts = per100 <= 0 ? 0 : (int) Math.round((double) target / per100);
        if (contracts < 1 && target > 0) contracts = 1;      // at least one lot when any exposure is asked

        String structure = bullish
                ? "Synthetic LONG: buy the ATM call + sell the ATM put (≈ +100 delta / contract)"
                : "Synthetic SHORT: sell the ATM call + buy the ATM put (≈ −100 delta / contract)";
        long deltaExposure = (long) contracts * per100 * (bullish ? 1 : -1);
        long shareCost = (long) contracts * per100;          // what the shares would have cost
        long estMargin = (long) contracts * underlyingCents * 100 / 5; // ~20% reg-T-ish estimate on the short leg

        notes.add("Delta-1 replication only — it tracks price moves, not dividends or exact ETF composition.");
        notes.add("The short leg carries assignment risk; margin is an estimate, not a broker quote.");
        return new ReplicationResult(symbol, target, underlyingCents, contracts, structure,
                deltaExposure, shareCost, estMargin, notes);
    }
}
