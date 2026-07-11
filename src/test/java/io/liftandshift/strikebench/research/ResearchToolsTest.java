package io.liftandshift.strikebench.research;

import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.market.ports.NewsFilingsProvider;
import io.liftandshift.strikebench.market.ports.RatesProvider;
import io.liftandshift.strikebench.market.providers.FixtureProvider;
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

    @Test void hypothesisTestReportsAnHonestVerdictAndIsDeterministic() {
        var req = new HypothesisTester.HypothesisRequest("AAPL", "2026-01-02", "2026-06-01", 20, 0.0, 10);
        var r = new HypothesisTester(market()).test(req);

        assertThat(r.hypothesis()).contains("AAPL");
        assertThat(r.winRate()).isBetween(0.0, 1.0);
        assertThat(r.expectedByChance()).isEqualTo(0.5);
        assertThat(Double.isFinite(r.zScore())).isTrue();
        assertThat(r.verdict()).isNotBlank();
        assertThat(r.notes()).anyMatch(n -> n.toLowerCase().contains("not a forecast"));

        var r2 = new HypothesisTester(market()).test(req);
        assertThat(r2.sample()).isEqualTo(r.sample());
        assertThat(r2.wins()).isEqualTo(r.wins());
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
    }

    @Test void bearishReplicationIsAShortSynthetic() {
        var rep = new ETFReplicator(market()).replicate(
                new ETFReplicator.ReplicationRequest("AAPL", 10_000_000L, false));
        assertThat(rep.structure()).contains("Synthetic SHORT");
        assertThat(rep.deltaExposureCents()).isNegative();
    }
}
