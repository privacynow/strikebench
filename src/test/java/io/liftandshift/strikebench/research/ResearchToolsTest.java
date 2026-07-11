package io.liftandshift.strikebench.research;

import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.market.ports.NewsFilingsProvider;
import io.liftandshift.strikebench.market.ports.RatesProvider;
import io.liftandshift.strikebench.market.providers.FixtureProvider;
import io.liftandshift.strikebench.model.DataProvenance;
import io.liftandshift.strikebench.support.ObservedFixtureProvider;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Research tools: hypothesis tester + ETF (delta-1) replicator. */
class ResearchToolsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T15:30:00Z"), ZoneId.of("America/New_York"));

    private MarketDataService market() {
        FixtureProvider f = new FixtureProvider(CLOCK);
        return new MarketDataService(List.<MarketDataProvider>of(f), List.<NewsFilingsProvider>of(f), List.<RatesProvider>of(f));
    }

    private MarketDataService observedMarketWithNoWorlds() {
        var observed = new ObservedFixtureProvider(CLOCK);
        var market = new MarketDataService(List.<MarketDataProvider>of(observed), List.of(), List.of());
        market.setWorldResolver(id -> java.util.Optional.empty());
        return market;
    }

    @Test void hypothesisTestReportsAnHonestVerdictAndIsDeterministic() {
        var req = new HypothesisTester.HypothesisRequest("AAPL", "2026-01-02", "2026-06-01", 20, 0.0, 10);
        var r = new HypothesisTester(market()).test(req);

        assertThat(r.hypothesis()).contains("AAPL");
        assertThat(r.winRate()).isBetween(0.0, 1.0);
        assertThat(r.expectedByChance()).isBetween(0.0, 1.0);
        assertThat(Double.isFinite(r.zScore())).isTrue();
        assertThat(r.verdict()).isNotBlank();
        assertThat(r.evidence().provenance()).isEqualTo(DataProvenance.DEMO);
        assertThat(r.notes()).anyMatch(n -> n.toLowerCase().contains("not a forecast"));

        var r2 = new HypothesisTester(market()).test(req);
        assertThat(r2.sample()).isEqualTo(r.sample());
        assertThat(r2.wins()).isEqualTo(r.wins());

        var shared = new ResearchQuestionEngine(market()).run(new ResearchQuestionEngine.RunRequest(
                "momentum", "AAPL", "2026-01-02", "2026-06-01",
                java.util.Map.of("lookback", 20, "thresholdPct", 0.0, "forward", 10)));
        assertThat(r.sample()).isEqualTo(shared.conditioned().sample());
        assertThat(r.zScore()).isEqualTo(shared.zScore());
        assertThat(r.expectedByChance()).isCloseTo(shared.baseline().winRatePct() / 100.0,
                org.assertj.core.api.Assertions.within(0.001));
        assertThat(r.notes()).anyMatch(n -> n.contains("shared Research event-study engine"));
    }

    @Test void tooFewSignalsIsCalledOutHonestly() {
        // A very high momentum threshold rarely triggers -> too few samples to conclude.
        var r = new HypothesisTester(market()).test(
                new HypothesisTester.HypothesisRequest("AAPL", "2026-05-01", "2026-06-01", 20, 500.0, 10));
        assertThat(r.significant()).isFalse();
        assertThat(r.verdict()).containsIgnoringCase("too few");
    }

    @Test void etfReplicationSizesASyntheticExposure() {
        var rep = new ETFReplicator(market()).replicate(
                new ETFReplicator.ReplicationRequest("AAPL", 10_000_000L, true)); // ~$100k long

        assertThat(rep.underlyingCents()).isPositive();
        assertThat(rep.contracts()).isGreaterThanOrEqualTo(1);
        assertThat(rep.structure()).contains("Synthetic LONG");
        assertThat(rep.deltaExposureCents()).isPositive();
        assertThat(rep.shareCostCents()).isPositive();
        assertThat(rep.notes()).anyMatch(n -> n.contains("Delta-1"));
        assertThat(rep.evidence().provenance()).isEqualTo(DataProvenance.DEMO);
    }

    @Test void bearishReplicationIsAShortSynthetic() {
        var rep = new ETFReplicator(market()).replicate(
                new ETFReplicator.ReplicationRequest("AAPL", 10_000_000L, false));
        assertThat(rep.structure()).contains("Synthetic SHORT");
        assertThat(rep.deltaExposureCents()).isNegative();
    }

    @Test void explicitUnknownWorldNeverFallsThroughToObservedResearchInputs() {
        var market = observedMarketWithNoWorlds();
        var study = new HypothesisTester(market).test(
                new HypothesisTester.HypothesisRequest("AAPL", "2026-01-02", "2026-06-01", 20, 0.0, 10),
                io.liftandshift.strikebench.db.AnalysisContext.OBSERVED, "missing-world");
        var replication = new ETFReplicator(market).replicate(
                new ETFReplicator.ReplicationRequest("AAPL", 10_000_000L, true), "missing-world");

        assertThat(study.sample()).isZero();
        assertThat(study.evidence().provenance()).isEqualTo(DataProvenance.MISSING);
        assertThat(study.verdict()).containsIgnoringCase("unavailable");
        assertThat(replication.contracts()).isZero();
        assertThat(replication.evidence().provenance()).isEqualTo(DataProvenance.MISSING);
    }
}
