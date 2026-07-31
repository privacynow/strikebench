package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.paper.OrderInstruction;
import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.strategy.StrategyCatalog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The one backend-owned answer to whether a ranked or exactly repriced package may be promoted
 * from a comparison to the Desk's automatically selected idea.
 *
 * <p>This is deliberately separate from rank. A package can remain useful and highly ranked while
 * failing mechanics, objective coherence, after-cost economics, price availability, or the exact
 * MARKET-order gate. The browser renders this receipt; it never recreates these tests.</p>
 */
public record DecisionEndorsement(boolean endorsed, String status, String candidateId,
                                  List<String> reasons, String basis) {
    public static final String ENDORSED = "ENDORSED";
    public static final String COMPARISON = "COMPARISON";

    public DecisionEndorsement {
        status = endorsed ? ENDORSED : COMPARISON;
        reasons = reasons == null ? List.of() : List.copyOf(new LinkedHashSet<>(reasons));
        basis = basis == null || basis.isBlank()
                ? "Backend decision policy: mechanics, executable package price, objective coherence, "
                    + "and realistic after-cost economics remain separate gates."
                : basis;
    }

    /** Bind the policy receipt to the persisted/selected candidate identity without recalculating it. */
    public DecisionEndorsement forCandidate(String id) {
        return new DecisionEndorsement(endorsed, status, id, reasons, basis);
    }

    /** Ranking-time receipt for the exact candidate evaluation already ordered by decisionScore. */
    public static DecisionEndorsement ranked(StrategyEvaluation evaluation) {
        if (evaluation == null) {
            return comparison(null, List.of("No strategy evaluation was available."));
        }
        Candidate candidate = evaluation.candidate();
        List<String> reasons = new ArrayList<>();
        StrategyCatalog.RecommendationDisposition disposition =
                StrategyCatalog.recommendationDisposition(candidate == null ? null : candidate.strategy());
        if (disposition == StrategyCatalog.RecommendationDisposition.COMPARISON_ONLY) {
            reasons.add("This strategy family is comparison-only until its path-dependent economics, "
                    + "capital obligations, and deliverables support an automatic endorsement.");
        } else if (disposition == StrategyCatalog.RecommendationDisposition.EDUCATION_ONLY) {
            reasons.add("This strategy family is educational only because required risk or execution "
                    + "evidence is unavailable.");
        }
        if (!evaluation.viable()) reasons.add("The package did not pass the canonical viability gate.");
        if (evaluation.assessment() == null || evaluation.assessment().mechanics() == null
                || !evaluation.assessment().mechanics().eligible()) {
            reasons.add("The package did not pass the mechanical assessment.");
        }
        if (candidate == null || candidate.price() == null || !candidate.price().priced()) {
            reasons.add(candidate != null && candidate.price() != null
                    && candidate.price().unavailableReason() != null
                    ? candidate.price().unavailableReason()
                    : "The package has no priced package receipt.");
        }
        // Executability is deliberately NOT an endorsement veto: advice is judged on available
        // data, and a closed or stale market must not silence a favorable, coherent, observed
        // read. Placement re-tests the live book and warns with stamped provenance instead.
        if (evaluation.assessment() == null || evaluation.assessment().coherence() == null
                || evaluation.assessment().coherence().verdict()
                    != FourOutputAssessment.Coherence.COHERENT) {
            reasons.add("The package is not a coherent fit for the declared objective and duration.");
        }
        EconomicAssessment economics = evaluation.assessment() == null
                ? null : evaluation.assessment().economics();
        if (economics == null || economics.verdict() != EconomicAssessment.Verdict.FAVORABLE) {
            reasons.add("Realistic after-cost economics are not favorable.");
        } else if (!economics.actionableFavorable()) {
            reasons.add("Favorable modeled economics are not backed end-to-end by observed evidence; "
                    + "the package remains a comparison.");
        }
        boolean incomeShortPremium = candidate != null
                && "INCOME".equalsIgnoreCase(candidate.intent())
                && candidate.price() != null
                && candidate.price().optionNetPremiumCents() != null
                && candidate.price().optionNetPremiumCents() > 0
                && candidate.legs().stream().anyMatch(leg ->
                        "SELL".equalsIgnoreCase(leg.action())
                                && !"STOCK".equalsIgnoreCase(leg.type()));
        var jumpTail = evaluation.risk() == null ? null : evaluation.risk().jumpTail();
        if (incomeShortPremium && (jumpTail == null || !jumpTail.available())) {
            String gap = jumpTail == null ? null : jumpTail.unavailableReason();
            reasons.add("Short-premium income needs a complete jump/event tail receipt before it "
                    + "can be endorsed." + (gap == null || gap.isBlank() ? ""
                    : " " + gap));
        } else if (incomeShortPremium && jumpTail.base() != null
                && jumpTail.base().eventSoon() && jumpTail.base().eventName() != null) {
            // Only an OBSERVED near event (it carries a name) demotes. An unknown calendar runs
            // the tail on a disclosed assumed-event posture — already the widest modeling — and
            // the economics gate judges the package on that widened tail instead of a veto here.
            reasons.add("A named issuer event falls inside the package horizon; event-gap risk "
                    + "remains a separate decision and this package is comparison-only.");
        }
        String id = evaluation.id();
        return reasons.isEmpty()
                ? new DecisionEndorsement(true, ENDORSED, id, List.of(),
                    "The backend evaluation confirms mechanics, an immediately executable package "
                        + "price, coherent objective fit, and observed favorable realistic "
                        + "after-cost economics.")
                : comparison(id, reasons);
    }

    /**
     * Reconciles the ranking receipt with the exact selected MARKET package. A resting LIMIT is an
     * instruction choice and therefore does not erase the ranked endorsement; an unavailable or
     * blocked MARKET package does.
     */
    public static DecisionEndorsement exact(DecisionEndorsement ranked,
                                            OrderInstruction instruction,
                                            OrderInstruction.Executability executability,
                                            boolean mechanicallyBlocked,
                                            List<String> blockReasons) {
        if (ranked == null || !ranked.endorsed()) {
            return ranked == null
                    ? comparison(null, List.of("No ranked endorsement was available."))
                    : ranked;
        }
        boolean market = instruction == null || instruction.type() == OrderInstruction.Type.MARKET;
        if (!market) return ranked;
        List<String> reasons = new ArrayList<>();
        // Executability no longer erases the ranked endorsement: the paper fill proceeds on the
        // last captured book with stamped provenance, and the surfaces warn. Only a mechanical
        // block (a package that genuinely cannot be composed) withdraws the endorsement.
        if (mechanicallyBlocked) {
            if (blockReasons != null) reasons.addAll(blockReasons);
            if (reasons.isEmpty()) reasons.add("The exact MARKET package is mechanically blocked.");
        }
        return reasons.isEmpty() ? ranked : comparison(ranked.candidateId(), reasons);
    }

    private static DecisionEndorsement comparison(String candidateId, List<String> reasons) {
        return new DecisionEndorsement(false, COMPARISON, candidateId, reasons,
                "The package remains an inspectable comparison; the backend did not promote it.");
    }
}
