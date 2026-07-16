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
        PortfolioExposureContext portfolioExposure
) {
    public EvalContext {
        if (asOfDate == null) throw new IllegalArgumentException("evaluation date is required");
        ivHistory = ivHistory == null ? List.of() : List.copyOf(ivHistory);
        rateEvidence = rateEvidence == null ? DataEvidence.missing("rate input") : rateEvidence;
    }

}
