package io.liftandshift.strikebench.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Freshness.isObservedLive() (the staleness gate in MarketDataService) and DataEvidence.executableIn's
 * observed-lane age branch (age == REALTIME || DELAYED) are two spellings of the same "live observed
 * tier" concept, kept deliberately separate. This pins them lock-step: adding a live tier that updates
 * one but not the other breaks here.
 */
class FreshnessObservedLiveTest {

    @Test
    void isObservedLiveAgreesWithDataEvidenceObservedAgeForEveryTier() {
        for (Freshness f : Freshness.values()) {
            DataEvidence ev = DataEvidence.of("polygon", f); // an observed-provenance source
            boolean ageIsLive = ev.age() == DataAge.REALTIME || ev.age() == DataAge.DELAYED;
            assertThat(f.isObservedLive())
                    .as("isObservedLive vs observed-age branch for %s", f)
                    .isEqualTo(ageIsLive);
        }
    }

    @Test
    void onlyRealtimeAndDelayedAreObservedLive() {
        assertThat(Freshness.REALTIME.isObservedLive()).isTrue();
        assertThat(Freshness.DELAYED.isObservedLive()).isTrue();
        assertThat(Freshness.EOD.isObservedLive()).isFalse();
        assertThat(Freshness.SIMULATED.isObservedLive()).isFalse();
        assertThat(Freshness.FIXTURE.isObservedLive()).isFalse();
        assertThat(Freshness.STALE.isObservedLive()).isFalse();
        assertThat(Freshness.MISSING.isObservedLive()).isFalse();
        assertThat(Freshness.MODELED.isObservedLive()).isFalse();
    }
}
