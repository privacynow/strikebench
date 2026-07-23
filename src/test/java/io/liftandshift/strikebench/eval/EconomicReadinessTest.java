package io.liftandshift.strikebench.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioral contract for THE one economic-readiness classifier now shared by the Decision ranking,
 * the Plan Scout, and a persisted Plan run reload. Before consolidation these were three separate
 * classifiers in two vocabularies, so the same verdicts could read differently on different tabs;
 * these tests pin the single precedence so that can't regress.
 */
class EconomicReadinessTest {

    private static EconomicReadiness.Tally tally() { return EconomicReadiness.tally(); }

    @Test
    void readyRequiresAnObservedEndorsedFavorable() {
        var ready = tally()
                .addAssessment("FAVORABLE", true, false, false, null)
                .summarize();
        assertThat(ready.readiness()).isEqualTo(EconomicReadiness.READY);
        assertThat(ready.ready()).isTrue();
        assertThat(ready.actionableFavorable()).isEqualTo(1);
        assertThat(ready.favorable()).isEqualTo(1);
    }

    @Test
    void aModeledFavorableIsFavorableButNotReady() {
        // FAVORABLE economics on modeled (non-observed) evidence must NOT read READY — that was the
        // whole point of the two-axis verdict: surface it, but never as an observed endorsement.
        var modeled = tally()
                .addAssessment("FAVORABLE", false, false, false, null)
                .summarize();
        assertThat(modeled.favorable()).isEqualTo(1);
        assertThat(modeled.actionableFavorable()).isZero();
        assertThat(modeled.ready()).isFalse();
        assertThat(modeled.readiness()).isEqualTo(EconomicReadiness.CHECKED_NO_FAVORABLE);
    }

    @Test
    void nothingComparableIsMechanicallyBlockedNotEvidenceIncomplete() {
        // Every candidate mechanically ineligible → a MECHANICAL block, ranked ahead of every other
        // state. (The Decision path used to call this EVIDENCE_INCOMPLETE while the Scout said
        // MECHANICALLY_BLOCKED — the divergence this consolidation removes.)
        var blocked = tally()
                .addAssessment("UNAVAILABLE", false, true, false, null)
                .addAssessment("UNAVAILABLE", false, true, false, null)
                .summarize();
        assertThat(blocked.readiness()).isEqualTo(EconomicReadiness.MECHANICALLY_BLOCKED);
    }

    @Test
    void missingDailyHistoryIsNamedAheadOfTheResidualState() {
        var needsHistory = tally()
                .addAssessment("MIXED", false, false, true, null)
                .summarize();
        assertThat(needsHistory.readiness()).isEqualTo(EconomicReadiness.NEEDS_DAILY_HISTORY);
        assertThat(needsHistory.needsDailyHistory()).isTrue();
    }

    @Test
    void aHistoryMissingDimensionAlsoForcesNeedsDailyHistory() {
        var needsHistory = tally()
                .addAssessment("MIXED", false, false, false, List.of("history", "greeks"))
                .summarize();
        assertThat(needsHistory.readiness()).isEqualTo(EconomicReadiness.NEEDS_DAILY_HISTORY);
        assertThat(needsHistory.missingEvidence()).contains("history", "greeks");
    }

    @Test
    void comparableButUnavailableEconomicsAreEvidenceIncomplete() {
        var incomplete = tally()
                .addAssessment("UNAVAILABLE", false, false, false, null)
                .summarize();
        assertThat(incomplete.readiness()).isEqualTo(EconomicReadiness.EVIDENCE_INCOMPLETE);
    }

    @Test
    void aMixedFieldWithNoFavorableReadsCheckedNoFavorable() {
        var checked = tally()
                .addAssessment("MIXED", false, false, false, null)
                .addAssessment("UNFAVORABLE", false, false, false, null)
                .summarize();
        assertThat(checked.readiness()).isEqualTo(EconomicReadiness.CHECKED_NO_FAVORABLE);
        assertThat(checked.mixed()).isEqualTo(1);
        assertThat(checked.unfavorable()).isEqualTo(1);
    }

    @Test
    void oneObservedFavorableWinsReadyEvenBesideWeakerStructures() {
        var ready = tally()
                .addAssessment("MIXED", false, false, false, null)
                .addAssessment("FAVORABLE", true, false, false, null)
                .addAssessment("UNAVAILABLE", false, true, false, null)
                .summarize();
        assertThat(ready.readiness()).isEqualTo(EconomicReadiness.READY);
        assertThat(ready.actionableFavorable()).isEqualTo(1);
        assertThat(ready.mixed()).isEqualTo(1);
    }

    @Test
    void anUnassessedCandidateCountsAsUnavailable() {
        var incomplete = tally().addUnassessed().summarize();
        assertThat(incomplete.unavailable()).isEqualTo(1);
        assertThat(incomplete.readiness()).isEqualTo(EconomicReadiness.EVIDENCE_INCOMPLETE);
    }
}
