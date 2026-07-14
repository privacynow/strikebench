package io.liftandshift.strikebench.backtest;

import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.RateQuote;
import io.liftandshift.strikebench.model.DataEvidence;

import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit model inputs shared by every historical replay engine. */
public record BacktestModelInputs(
        double annualRate,
        DataEvidence rateEvidence,
        double fallbackVolatility
) {
    public static final double DEFAULT_FALLBACK_VOLATILITY = 0.30;

    public BacktestModelInputs {
        if (!Double.isFinite(annualRate)) throw new IllegalArgumentException("Replay rate must be finite");
        if (rateEvidence == null) throw new IllegalArgumentException("Replay rate evidence is required");
        if (!Double.isFinite(fallbackVolatility) || fallbackVolatility <= 0) {
            throw new IllegalArgumentException("Replay fallback volatility must be positive and finite");
        }
    }

    public static BacktestModelInputs resolve(MarketDataService market, int targetDays, String worldId) {
        RateQuote quote = market.riskFreeRateQuote(Math.max(1, targetDays), worldId);
        return new BacktestModelInputs(quote.annualRate(), quote.evidence(), DEFAULT_FALLBACK_VOLATILITY);
    }

    public Map<String, Object> disclosure() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("annualRate", annualRate);
        out.put("rateSource", rateEvidence.source());
        out.put("rateProvenance", rateEvidence.provenance().name());
        out.put("rateAge", rateEvidence.age().name());
        out.put("rateConvention", "current lane-owned rate held constant across replay dates; not a historical yield curve");
        out.put("fallbackVolatility", fallbackVolatility);
        out.put("volatilityConvention", "trailing 30-session realized volatility with a modeled fallback when history is unavailable");
        return out;
    }
}
