package io.liftandshift.strikebench.eval;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * THE single economic-readiness classifier over a ranked field of evaluations. Every ranked surface —
 * the Decision ranking, the Plan Scout, and a persisted Plan run reload — folds its candidates into
 * one {@link Tally} and reads one readiness label + counts, so no two surfaces can report a different
 * readiness for the same underlying verdicts (they used to: three sites classified this three ways in
 * two vocabularies).
 *
 * <p>The surface-appropriate {@code economicMessage} prose stays at the call site — a single-symbol
 * decision and a related-symbols scout legitimately phrase the same state differently — but the
 * readiness label and every count come from here.
 */
public record EconomicReadiness(String readiness, int favorable, int actionableFavorable, int mixed,
                                int unfavorable, int unavailable, boolean needsDailyHistory,
                                List<String> missingEvidence) {

    public static final String READY = "READY";
    public static final String NEEDS_DAILY_HISTORY = "NEEDS_DAILY_HISTORY";
    public static final String MECHANICALLY_BLOCKED = "MECHANICALLY_BLOCKED";
    public static final String EVIDENCE_INCOMPLETE = "EVIDENCE_INCOMPLETE";
    public static final String CHECKED_NO_FAVORABLE = "CHECKED_NO_FAVORABLE";

    public boolean ready() { return READY.equals(readiness); }

    public static Tally tally() { return new Tally(); }

    /** Accumulates one candidate at a time; both live-object and persisted-JSON surfaces feed it. */
    public static final class Tally {
        private int favorable, actionableFavorable, mixed, unfavorable, unavailable;
        private boolean needsDailyHistory, anyAssessment, anyComparable;
        private final LinkedHashSet<String> missing = new LinkedHashSet<>();

        /** A candidate with NO economic assessment (mechanically un-assessable) counts as unavailable. */
        public Tally addUnassessed() { unavailable++; return this; }

        /** The primitive both feeds route through. {@code verdict} is the {@link EconomicAssessment.Verdict} name. */
        public Tally addAssessment(String verdict, boolean observedEvidence, boolean mechanicallyIneligible,
                                   boolean needsHistory, Collection<String> missingDimensions) {
            anyAssessment = true;
            if (!mechanicallyIneligible) anyComparable = true;
            if (needsHistory) needsDailyHistory = true;
            switch (verdict == null ? "UNAVAILABLE" : verdict) {
                case "FAVORABLE" -> { favorable++; if (observedEvidence) actionableFavorable++; }
                case "MIXED" -> mixed++;
                case "UNFAVORABLE" -> unfavorable++;
                default -> unavailable++;
            }
            if (missingDimensions != null) {
                for (String d : missingDimensions) { missing.add(d); if ("history".equals(d)) needsDailyHistory = true; }
            }
            return this;
        }

        /** Convenience over a live {@link EconomicAssessment}; a null economics is un-assessable. */
        public Tally add(EconomicAssessment economics, Collection<String> missingDimensions) {
            if (economics == null) return addUnassessed();
            return addAssessment(economics.verdict().name(), economics.observedEvidence(),
                    "MECHANICALLY_INELIGIBLE".equals(economics.placement()),
                    economics.needsDailyHistory(), missingDimensions);
        }

        public EconomicReadiness summarize() {
            // Precedence: nothing comparable is a MECHANICAL block, not "incomplete evidence"; an
            // observed-endorsed favorable is the only thing that reads READY; missing daily history is
            // named before the residual "checked, none favorable".
            String readiness = anyAssessment && !anyComparable ? MECHANICALLY_BLOCKED
                    : actionableFavorable > 0 ? READY
                    : needsDailyHistory ? NEEDS_DAILY_HISTORY
                    : unavailable > 0 && mixed == 0 && unfavorable == 0 ? EVIDENCE_INCOMPLETE
                    : CHECKED_NO_FAVORABLE;
            return new EconomicReadiness(readiness, favorable, actionableFavorable, mixed, unfavorable,
                    unavailable, needsDailyHistory, List.copyOf(missing));
        }
    }
}
