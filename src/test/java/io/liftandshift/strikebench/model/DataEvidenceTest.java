package io.liftandshift.strikebench.model;

import io.liftandshift.strikebench.market.MarketLane;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DataEvidenceTest {

    @Test
    void provenanceAndAgeAreIndependentAndLaneOwned() {
        DataEvidence delayed = DataEvidence.of("cboe", Freshness.DELAYED);
        DataEvidence eod = DataEvidence.of("stored-observed", Freshness.EOD);
        DataEvidence stale = DataEvidence.of("cboe", Freshness.STALE);
        DataEvidence demo = DataEvidence.of("fixture", Freshness.FIXTURE);
        DataEvidence simulated = DataEvidence.of("simulated", Freshness.SIMULATED);

        assertThat(delayed.provenance()).isEqualTo(DataProvenance.OBSERVED);
        assertThat(delayed.age()).isEqualTo(DataAge.DELAYED);
        assertThat(delayed.executableIn(MarketLane.OBSERVED)).isTrue();
        assertThat(eod.usableIn(MarketLane.OBSERVED)).isTrue();
        assertThat(eod.executableIn(MarketLane.OBSERVED)).isFalse();
        assertThat(stale.usableIn(MarketLane.OBSERVED)).isTrue();
        assertThat(stale.executableIn(MarketLane.OBSERVED)).isFalse();
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

    @Test
    void observedAgeWithoutAnAttributableSourceFailsClosed() {
        DataEvidence unattributed = DataEvidence.of(null, Freshness.DELAYED);
        assertThat(unattributed.provenance()).isEqualTo(DataProvenance.MISSING);
        assertThat(unattributed.executableIn(MarketLane.OBSERVED)).isFalse();
    }

    @Test
    void sameProvenanceFromSeveralSourcesKeepsObservedButNamesMultipleInputs() {
        DataEvidence aggregate = DataEvidence.aggregate(List.of(
                DataEvidence.of("cboe", Freshness.DELAYED),
                DataEvidence.of("stored-observed", Freshness.EOD)));
        assertThat(aggregate.provenance()).isEqualTo(DataProvenance.OBSERVED);
        assertThat(aggregate.age()).isEqualTo(DataAge.EOD);
        assertThat(aggregate.source()).isEqualTo("multiple inputs");
    }

    @Test
    void previousCloseFallbackKeepsOriginButDowngradesTheDisplayedMark() {
        Quote observed = new Quote("AAPL", "Apple", null, null, null, new BigDecimal("100"),
                null, null, null, true, 1L, "cboe", Freshness.REALTIME);
        assertThat(observed.mark()).isEqualByComparingTo("100");
        assertThat(observed.markFreshness()).isEqualTo(Freshness.EOD);
        assertThat(observed.evidence().provenance()).isEqualTo(DataProvenance.OBSERVED);
        assertThat(observed.evidence().age()).isEqualTo(DataAge.EOD);
        assertThat(observed.evidence().executableIn(MarketLane.OBSERVED)).isFalse();

        Quote simulated = new Quote("AAPL", "Apple", null, null, null, new BigDecimal("100"),
                null, null, null, true, 1L, "simulated", Freshness.SIMULATED);
        assertThat(simulated.markFreshness()).isEqualTo(Freshness.STALE);
        assertThat(simulated.evidence().provenance()).isEqualTo(DataProvenance.SIMULATED);
        assertThat(simulated.evidence().age()).isEqualTo(DataAge.STALE);
        assertThat(simulated.evidence().executableIn(MarketLane.SIMULATED)).isFalse();
    }

    @Test
    void lastTradeOptionFallbackIsStaleInEveryLaneAndCrossedStockBookIsIgnored() {
        OptionQuote option = new OptionQuote("AAPL", "AAPL260821C00100000", OptionType.CALL,
                new BigDecimal("100"), LocalDate.of(2026, 8, 21), null, null, new BigDecimal("2.50"),
                10L, 20L, 0.25, 0.5, 0.01, -0.03, 0.10, 1L, "simulated", Freshness.SIMULATED);
        assertThat(option.mid()).isEqualByComparingTo("2.50");
        assertThat(option.markFreshness()).isEqualTo(Freshness.STALE);
        assertThat(option.evidence().provenance()).isEqualTo(DataProvenance.SIMULATED);
        assertThat(option.evidence().age()).isEqualTo(DataAge.STALE);

        Quote crossed = new Quote("AAPL", "Apple", new BigDecimal("99"), new BigDecimal("101"),
                new BigDecimal("100"), new BigDecimal("98"), null, null, null, true, 1L,
                "cboe", Freshness.DELAYED);
        assertThat(crossed.mark()).isEqualByComparingTo("99");
    }
}
