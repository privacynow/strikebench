package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.recommend.Candidate;

import java.util.LinkedHashMap;
import java.util.Map;

/** Tags each evaluation dimension with its evidence level and rolls up to the worst. */
public final class EvidenceAssembler {

    public EvidenceProfile assemble(Candidate c, EvalContext ctx) {
        EvidenceLevel pricing = EvidenceLevel.fromFreshness(c.freshness());
        EvidenceLevel greeks = pricing; // greeks come off the same chain as the prices
        EvidenceLevel volatility = ctx.ivHistory().size() >= 10 && pricing.isObserved()
                ? pricing : EvidenceLevel.MODELED.worseOf(pricing);
        EvidenceLevel liquidity = c.liquidityScore() > 0 ? pricing : EvidenceLevel.UNKNOWN;
        EvidenceLevel history = ctx.realizedVol30() == null ? EvidenceLevel.UNKNOWN
                : pricing.isObserved() ? EvidenceLevel.OBSERVED_EOD : pricing;
        EvidenceLevel rates = EvidenceLevel.fromEvidence(ctx.rateEvidence());

        Map<String, EvidenceLevel> dims = new LinkedHashMap<>();
        dims.put("pricing", pricing);
        dims.put("greeks", greeks);
        dims.put("volatility", volatility);
        dims.put("liquidity", liquidity);
        dims.put("history", history);
        dims.put("rates", rates);

        String note = pricing.isObserved()
                ? "pricing is " + c.freshness()
                    + "; volatility, history, and rates retain their own provenance; least-certain dimension sets the badge"
                : "generated pricing inputs — NOT observed market prices; least-certain dimension sets the badge";
        return EvidenceProfile.of(dims, note);
    }
}
