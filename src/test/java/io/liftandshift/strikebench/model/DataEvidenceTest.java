package io.liftandshift.strikebench.model;

import io.liftandshift.strikebench.market.MarketLane;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DataEvidenceTest {

    @Test
    void provenanceAndAgeAreIndependentAndLaneOwned() {
        DataEvidence delayed = DataEvidence.of("cboe", Freshness.DELAYED);
        DataEvidence demo = DataEvidence.of("fixture", Freshness.FIXTURE);
        DataEvidence simulated = DataEvidence.of("simulated", Freshness.SIMULATED);

        assertThat(delayed.provenance()).isEqualTo(DataProvenance.OBSERVED);
        assertThat(delayed.age()).isEqualTo(DataAge.DELAYED);
        assertThat(delayed.executableIn(MarketLane.OBSERVED)).isTrue();
        assertThat(demo.executableIn(MarketLane.OBSERVED)).isFalse();
        assertThat(demo.executableIn(MarketLane.DEMO)).isTrue();
        assertThat(simulated.executableIn(MarketLane.SIMULATED)).isTrue();
        assertThat(simulated.executableIn(MarketLane.DEMO)).isFalse();
    }

    @Test
    void mixedInputsNeverRollUpAsObserved() {
        DataEvidence mixed = DataEvidence.aggregate(List.of(
                DataEvidence.of("cboe", Freshness.DELAYED),
                DataEvidence.of("fixture", Freshness.FIXTURE)));

        assertThat(mixed.provenance()).isEqualTo(DataProvenance.MIXED);
        assertThat(mixed.usableIn(MarketLane.OBSERVED)).isFalse();
    }

    @Test
    void simulatedWorldWinsOverFixtureBuildDefault() {
        assertThat(MarketLane.of("world-123", true)).isEqualTo(MarketLane.SIMULATED);
        assertThat(MarketLane.of("demo", false)).isEqualTo(MarketLane.DEMO);
        assertThat(MarketLane.of("observed", true)).isEqualTo(MarketLane.DEMO);
    }
}
