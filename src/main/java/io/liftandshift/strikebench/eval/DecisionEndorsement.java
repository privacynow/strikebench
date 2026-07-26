package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.paper.OrderInstruction;
import io.liftandshift.strikebench.recommend.Candidate;

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
        } else if (candidate.price().executability() != OrderInstruction.Executability.IMMEDIATE) {
            reasons.add("The ranked package is not immediately executable on its captured book.");
        }
        if (evaluation.assessment() == null || evaluation.assessment().coherence() == null
                || evaluation.assessment().coherence().verdict()
                    != FourOutputAssessment.Coherence.COHERENT) {
            reasons.add("The package is not a coherent fit for the declared objective and duration.");
        }
        if (evaluation.economicVerdict() != EconomicAssessment.Verdict.FAVORABLE) {
            reasons.add("Realistic after-cost economics are not favorable.");
        }
        String id = evaluation.id();
        return reasons.isEmpty()
                ? new DecisionEndorsement(true, ENDORSED, id, List.of(),
                    "The backend evaluation confirms mechanics, an immediately executable package "
                        + "price, coherent objective fit, and favorable realistic after-cost economics.")
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
        if (executability != OrderInstruction.Executability.IMMEDIATE) {
            reasons.add(executability == OrderInstruction.Executability.UNAVAILABLE
                    ? "The exact MARKET package is not executable on the current book."
                    : "The exact MARKET package is not immediately executable.");
        }
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
