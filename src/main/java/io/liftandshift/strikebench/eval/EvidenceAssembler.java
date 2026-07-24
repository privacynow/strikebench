package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.recommend.Candidate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tags each evaluation dimension with its evidence level and rolls up to the worst. */
public final class EvidenceAssembler {

    public EvidenceProfile assemble(Candidate c, EvalContext ctx) {
        EvidenceLevel pricing = EvidenceLevel.fromFreshness(c.freshness());
        EvidenceLevel greeks = pricing; // greeks come off the same chain as the prices
        boolean placeholderIv = c.warnings() != null && c.warnings().stream()
                .map(String::toLowerCase)
                .anyMatch(warning -> warning.contains("iv") && warning.contains("placeholder"));
        EvidenceLevel currentVolatility = ctx.atmIv() == null ? EvidenceLevel.UNKNOWN
                : placeholderIv ? EvidenceLevel.MODELED.worseOf(pricing) : pricing;
        // This legacy dimension is the historical IV-rank lane. Keep it in the holistic badge,
        // but do not let missing rank history veto EV claims that consume current option IV.
        EvidenceLevel volatility = ctx.ivHistory().size() >= 10 && pricing.isObserved()
                ? pricing : EvidenceLevel.MODELED.worseOf(pricing);
        EvidenceLevel liquidity = c.liquidityScore() > 0 ? pricing : EvidenceLevel.UNKNOWN;
        // Daily-history provenance belongs to the CandleSeries that produced realizedVol30.
        // Deriving it from option pricing let an observed quote relabel synthetic/scenario bars
        // as observed and could turn a modeled realistic-measure result into a live endorsement.
        EvidenceLevel history = ctx.realizedVol30() == null ? EvidenceLevel.UNKNOWN
                : EvidenceLevel.fromEvidence(ctx.historyEvidence());
        EvidenceLevel rates = EvidenceLevel.fromEvidence(ctx.rateEvidence());

        Map<String, EvidenceLevel> dims = new LinkedHashMap<>();
        dims.put("pricing", pricing);
        dims.put("greeks", greeks);
        dims.put("currentVolatility", currentVolatility);
        dims.put("volatility", volatility);
        dims.put("liquidity", liquidity);
        dims.put("history", history);
        dims.put("rates", rates);

        String historyReceipt = "daily history is " + ctx.historyEvidence().provenance()
                + "/" + ctx.historyEvidence().age() + " from " + ctx.historyEvidence().source();
        String note = (pricing.isObserved()
                ? "pricing is " + c.freshness()
                    + "; volatility, history, and rates retain their own provenance; least-certain dimension sets the badge"
                : "generated pricing inputs — NOT observed market prices; least-certain dimension sets the badge")
                + "; " + historyReceipt;
        Map<String, EvidenceProfile.ClaimEvidence> claims = new LinkedHashMap<>();
        claims.put("marketEv", EvidenceProfile.project(dims,
                List.of("pricing", "currentVolatility", "rates"),
                "Market-implied EV uses executable pricing, current option volatility, and rates."));
        claims.put("realizedVolEv", EvidenceProfile.project(dims,
                List.of("pricing", "history"),
                "Realized-volatility EV uses executable pricing and eligible daily history."));
        claims.put("endorsement", EvidenceProfile.project(dims,
                List.of("pricing", "history"),
                "A live-market endorsement is driven by executable pricing and the observed-history realistic-measure lane. Current IV and rates remain scoped to the separate market-implied cost benchmark."));
        claims.put("ivRank", EvidenceProfile.project(dims,
                List.of("pricing", "volatility"),
                "IV rank is descriptive context and requires trailing IV observations."));
        claims.put("execution", EvidenceProfile.project(dims,
                List.of("pricing", "liquidity"),
                "Execution readiness uses the current executable book and liquidity receipt."));
        return EvidenceProfile.of(dims, note, claims);
    }
}
