package io.liftandshift.strikebench.strategy;

import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.util.Money;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Sizes the Builder's synthetic-long/short structure to a requested delta-dollar exposure. */
public final class ExposureSizer {
    private final MarketDataService market;

    public ExposureSizer(MarketDataService market) {
        this.market = market;
    }

    public record Request(String symbol, Long targetExposureCents, Boolean bullish) {}

    public record Result(String symbol, long targetExposureCents, long underlyingCents,
                         int contracts, String structure, long deltaExposureCents,
                         long shareCostCents, DataEvidence evidence,
                         List<String> notes) {}

    public Result size(Request request, String worldId) {
        String symbol = request.symbol() == null ? "" : request.symbol().trim().toUpperCase(Locale.ROOT);
        if (symbol.isEmpty()) throw new IllegalArgumentException("symbol is required");
        long target = request.targetExposureCents() == null ? 0 : request.targetExposureCents();
        if (target <= 0) throw new IllegalArgumentException("targetExposureCents must be positive");
        boolean bullish = request.bullish() == null || request.bullish();

        Quote quote = market.quote(symbol, worldId).orElse(null);
        DataEvidence evidence = quote == null ? DataEvidence.missing("no quote") : quote.evidence();
        BigDecimal spot = quote == null || !evidence.usableIn(market.lane(worldId)) ? null : quote.mark();
        List<String> notes = new ArrayList<>();
        if (spot == null || spot.signum() <= 0) {
            notes.add("No price is available for " + symbol + " in the selected market — exposure cannot be sized.");
            return new Result(symbol, target, 0, 0, "n/a", 0, 0, evidence, notes);
        }
        long underlyingCents = Money.toCents(spot);
        long perContractExposure = underlyingCents * 100;
        long requestedContracts = perContractExposure <= 0 ? 0
                : Math.round((double) target / perContractExposure);
        int contracts = (int) Math.min(100, Math.max(0, requestedContracts));
        if (contracts < 1 && target > 0) contracts = 1;

        String structure = bullish
                ? "Synthetic long: buy the ATM call + sell the ATM put (about +100 delta per contract)"
                : "Synthetic short: buy the ATM put + sell the ATM call (about -100 delta per contract)";
        long deltaExposure = (long) contracts * perContractExposure * (bullish ? 1 : -1);
        long shareCost = (long) contracts * perContractExposure;

        notes.add("Delta-1 approximation only: it tracks price moves, not dividends or exact ETF composition.");
        notes.add("The Builder below remains the authority for reserve, max loss, buying power and assignment risk.");
        if (requestedContracts > 100) {
            notes.add("The requested exposure needs more than the 100-contract safety cap; quantity was capped at 100.");
        }
        if (!bullish) {
            notes.add("UNDEFINED RISK: the synthetic short's sold call is uncovered. The Builder shows the "
                    + "unlimited-loss payoff and blocks placement.");
        }
        return new Result(symbol, target, underlyingCents, contracts, structure,
                deltaExposure, shareCost, evidence, notes);
    }
}
