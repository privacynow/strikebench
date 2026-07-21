package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.model.DataEvidence;

import java.time.LocalDate;
import java.util.List;

/**
 * The live market context an evaluation is computed against, assembled once by
 * {@code StrategyEvaluator} and shared by every producer so they agree on the same snapshot.
 */
public record EvalContext(
        String symbol,
        long underlyingCents,     // current underlying price, cents
        LocalDate asOfDate,       // lane clock date; simulated worlds never borrow wall-clock DTE
        int daysToExpiry,
        Double atmIv,             // at-the-money implied vol from the chain, null if none
        Double realizedVol30,     // 30-day realized (annualized), null if no candles
        List<Double> ivHistory,   // trailing ATM-IV observations for rank/percentile (may be empty)
        long buyingPowerCents,    // for the capital gate
        boolean marketOpen,
        long feePerContractCents, // so EV can be judged NET of commissions
        long feePerOrderCents,    // flat fee, charged once on entry and once on close
        double riskFreeRate,      // annualized r used by the shared risk-neutral approximation
        DataEvidence rateEvidence,
        PortfolioExposureContext portfolioExposure,
        DeclaredObjective declared,   // what the user SAID this is for; null = undeclared
        RegimeSnapshot regime,        // the lane's trailing regime; null = not computed
        List<Double> trailingCloses,  // chronological lane closes for history-fit; empty = none
        DataEvidence historyEvidence  // exact provenance of the CandleSeries behind realized vol/history
) {
    public EvalContext {
        if (asOfDate == null) throw new IllegalArgumentException("evaluation date is required");
        ivHistory = ivHistory == null ? List.of() : List.copyOf(ivHistory);
        rateEvidence = rateEvidence == null ? DataEvidence.missing("rate input") : rateEvidence;
        trailingCloses = trailingCloses == null ? List.of() : List.copyOf(trailingCloses);
        historyEvidence = historyEvidence == null
                ? DataEvidence.missing("daily history provenance") : historyEvidence;
    }

    /**
     * Compatibility shape for callers that predate history provenance. A realized-volatility
     * number alone cannot prove where its bars came from, so the evidence stays explicitly
     * missing rather than being inferred from option pricing or the presence of a value.
     */
    public EvalContext(String symbol, long underlyingCents, LocalDate asOfDate, int daysToExpiry,
                       Double atmIv, Double realizedVol30, List<Double> ivHistory,
                       long buyingPowerCents, boolean marketOpen, long feePerContractCents,
                       long feePerOrderCents, double riskFreeRate, DataEvidence rateEvidence,
                       PortfolioExposureContext portfolioExposure, DeclaredObjective declared,
                       RegimeSnapshot regime, List<Double> trailingCloses) {
        this(symbol, underlyingCents, asOfDate, daysToExpiry, atmIv, realizedVol30, ivHistory,
                buyingPowerCents, marketOpen, feePerContractCents, feePerOrderCents, riskFreeRate,
                rateEvidence, portfolioExposure, declared, regime, trailingCloses,
                DataEvidence.missing("daily history provenance not supplied"));
    }

    /** Undeclared-context constructor: existing callers keep their shape. */
    public EvalContext(String symbol, long underlyingCents, LocalDate asOfDate, int daysToExpiry,
                       Double atmIv, Double realizedVol30, List<Double> ivHistory,
                       long buyingPowerCents, boolean marketOpen, long feePerContractCents,
                       long feePerOrderCents, double riskFreeRate, DataEvidence rateEvidence,
                       PortfolioExposureContext portfolioExposure) {
        this(symbol, underlyingCents, asOfDate, daysToExpiry, atmIv, realizedVol30, ivHistory,
                buyingPowerCents, marketOpen, feePerContractCents, feePerOrderCents, riskFreeRate,
                rateEvidence, portfolioExposure, null, null, null);
    }

    /** Declared-but-regimeless constructor: pre-regime callers keep their shape. */
    public EvalContext(String symbol, long underlyingCents, LocalDate asOfDate, int daysToExpiry,
                       Double atmIv, Double realizedVol30, List<Double> ivHistory,
                       long buyingPowerCents, boolean marketOpen, long feePerContractCents,
                       long feePerOrderCents, double riskFreeRate, DataEvidence rateEvidence,
                       PortfolioExposureContext portfolioExposure, DeclaredObjective declared) {
        this(symbol, underlyingCents, asOfDate, daysToExpiry, atmIv, realizedVol30, ivHistory,
                buyingPowerCents, marketOpen, feePerContractCents, feePerOrderCents, riskFreeRate,
                rateEvidence, portfolioExposure, declared, null, null);
    }
}
