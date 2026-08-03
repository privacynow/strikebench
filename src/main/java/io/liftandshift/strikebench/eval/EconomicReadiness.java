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
    /** Favorable economics exist but none is backed end-to-end by observed evidence — the
     *  modeled/teaching tiers. CHECKED_NO_FAVORABLE beside favorableCount&gt;0 contradicted
     *  the same JSON's own counts. */
    public static final String FAVORABLE_NOT_ACTIONABLE = "FAVORABLE_NOT_ACTIONABLE";

    public boolean ready() { return READY.equals(readiness); }

    public static Tally tally() { return new Tally(); }

    /** Accumulates one canonical economic assessment at a time. */
    public static final class Tally {
        private int favorable, actionableFavorable, mixed, unfavorable, unavailable;
        private boolean needsDailyHistory, anyAssessment, anyComparable;
        private final LinkedHashSet<String> missing = new LinkedHashSet<>();

        /** A candidate with NO economic assessment (mechanically un-assessable) counts as unavailable. */
        public Tally addUnassessed() { unavailable++; return this; }

        /** Adds the typed assessment used by both new and restored ranked fields. */
        public Tally add(EconomicAssessment economics, Collection<String> missingDimensions) {
            if (economics == null) return addUnassessed();
            anyAssessment = true;
            if (!"MECHANICALLY_INELIGIBLE".equals(economics.placement())) anyComparable = true;
            if (economics.needsDailyHistory()) needsDailyHistory = true;
            switch (economics.verdict()) {
                case FAVORABLE -> { favorable++; if (economics.observedEvidence()) actionableFavorable++; }
                case MIXED -> mixed++;
                case UNFAVORABLE -> unfavorable++;
                case UNAVAILABLE -> unavailable++;
            }
            if (missingDimensions != null) {
                for (String d : missingDimensions) { missing.add(d); if ("history".equals(d)) needsDailyHistory = true; }
            }
            return this;
        }

        public EconomicReadiness summarize() {
            // Precedence: nothing comparable is a MECHANICAL block, not "incomplete evidence"; an
            // observed-endorsed favorable is the only thing that reads READY; missing daily history is
            // named before the residual "checked, none favorable".
            String readiness = anyAssessment && !anyComparable ? MECHANICALLY_BLOCKED
                    : actionableFavorable > 0 ? READY
                    : favorable > 0 ? FAVORABLE_NOT_ACTIONABLE
                    : needsDailyHistory ? NEEDS_DAILY_HISTORY
                    : unavailable > 0 && mixed == 0 && unfavorable == 0 ? EVIDENCE_INCOMPLETE
                    : CHECKED_NO_FAVORABLE;
            return new EconomicReadiness(readiness, favorable, actionableFavorable, mixed, unfavorable,
                    unavailable, needsDailyHistory, List.copyOf(missing));
        }
    }
}
