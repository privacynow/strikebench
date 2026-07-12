package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.recommend.Candidate;

/** Computes incremental vs economic capital + (labeled) return-on-capital. */
public final class CapitalProfiler {

    public CapitalProfile profile(Candidate c, EvalContext ctx) {
        long incremental = Math.max(0, c.maxLossCents());
        long economic = c.combinedMaxLossCents() != null ? c.combinedMaxLossCents() : incremental;
        if (economic < incremental) economic = incremental;

        Double roc = null;
        if (c.maxProfitCents() != null && economic > 0) {
            roc = 100.0 * c.maxProfitCents() / economic;
        }
        Double annRoc = (roc != null && ctx.daysToExpiry() > 0) ? roc * 365.0 / ctx.daysToExpiry() : null;

        String basis = c.combinedMaxLossCents() != null
                ? "economic capital includes the held/needed shares valued at today's price"
                : "defined-risk: economic capital equals the incremental buying power";
        return new CapitalProfile(incremental, economic, roc, annRoc, ctx.daysToExpiry(), basis);
    }
}
