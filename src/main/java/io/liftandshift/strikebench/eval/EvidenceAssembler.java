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
                ? pricing : EvidenceLevel.MODELED;
        EvidenceLevel liquidity = c.liquidityScore() > 0 ? pricing : EvidenceLevel.UNKNOWN;
        EvidenceLevel history = ctx.realizedVol30() != null ? EvidenceLevel.OBSERVED_EOD : EvidenceLevel.UNKNOWN;

        Map<String, EvidenceLevel> dims = new LinkedHashMap<>();
        dims.put("pricing", pricing);
        dims.put("greeks", greeks);
        dims.put("volatility", volatility);
        dims.put("liquidity", liquidity);
        dims.put("history", history);

        String note = pricing.isObserved()
                ? "priced from " + c.freshness() + " market data; least-certain dimension sets the badge"
                : "modeled / demo inputs — NOT observed market prices";
        return EvidenceProfile.of(dims, note);
    }
}
