package io.liftandshift.strikebench.eval;

import java.util.List;

/**
 * The human case for a recommendation: why it wins, what would break it, and the honest caveats.
 * Structured so the competition UI can show "why this one · what could fail · the plan" uniformly.
 * Produced by {@code Explainer}.
 */
public record Explanation(
        String headline,          // one-line thesis fit
        String whySelected,       // why this beat the alternatives
        String bestCase,
        String biggestRisk,
        String wouldInvalidate,   // the observable that kills the thesis
        List<String> assumptions, // model assumptions the numbers rest on
        List<String> failureModes,// concrete ways it loses
        String plainLanguage      // beginner-friendly summary
) {
    public Explanation {
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        failureModes = failureModes == null ? List.of() : List.copyOf(failureModes);
    }
}
