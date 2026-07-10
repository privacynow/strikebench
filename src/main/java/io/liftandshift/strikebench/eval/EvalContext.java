package io.liftandshift.strikebench.eval;

import java.util.List;

/**
 * The live market context an evaluation is computed against, assembled once by
 * {@code StrategyEvaluator} and shared by every producer so they agree on the same snapshot.
 */
public record EvalContext(
        String symbol,
        long underlyingCents,     // current underlying price, cents
        int daysToExpiry,
        Double atmIv,             // at-the-money implied vol from the chain, null if none
        Double realizedVol30,     // 30-day realized (annualized), null if no candles
        List<Double> ivHistory,   // trailing ATM-IV observations for rank/percentile (may be empty)
        long buyingPowerCents,    // for the capital gate
        boolean marketOpen,
        long feePerContractCents  // so EV can be judged NET of commissions
) {
    public EvalContext {
        ivHistory = ivHistory == null ? List.of() : List.copyOf(ivHistory);
    }

    /** Pre-fee shape (tests, older call sites): default commission. */
    public EvalContext(String symbol, long underlyingCents, int daysToExpiry, Double atmIv,
                       Double realizedVol30, List<Double> ivHistory, long buyingPowerCents, boolean marketOpen) {
        this(symbol, underlyingCents, daysToExpiry, atmIv, realizedVol30, ivHistory, buyingPowerCents, marketOpen, 65);
    }
}
