package io.liftandshift.strikebench.backtest;

import io.liftandshift.strikebench.config.AppConfig;
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
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Portfolio backtester: concurrent positions, delta strikes, mechanical exits, no look-ahead. */
class PortfolioBacktesterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T15:30:00Z"), ZoneId.of("America/New_York"));
    private static final Set<String> EXIT_REASONS = Set.of("PROFIT_TARGET", "STOP", "TIME", "EXPIRED", "WINDOW_END");

    private PortfolioBacktester backtester() {
        FixtureProvider fixture = new FixtureProvider(CLOCK);
        MarketDataService market = new MarketDataService(
                List.<MarketDataProvider>of(fixture), List.<NewsFilingsProvider>of(fixture), List.<RatesProvider>of(fixture));
        return new PortfolioBacktester(market, new AppConfig(Map.of("FIXTURES_ONLY", "true")), CLOCK);
    }

    private PortfolioBacktester.PortfolioRequest req(String strategy) {
        return new PortfolioBacktester.PortfolioRequest("AAPL", strategy, "2026-01-02", "2026-06-01",
                30, 5, 4, 1, 0.30, 0.05, 0.5, 0.8, 7, 100_000_00L);
    }

    @Test void runsAPortfolioWithConcurrentPositionsAndMechanicalExits() {
        var report = backtester().run(req("CREDIT_PUT_SPREAD"));

        assertThat(report.daysCovered()).isGreaterThan(0);
        assertThat(report.equityCurve()).isNotEmpty();
        assertThat(report.trades()).isNotEmpty();
        assertThat(report.concurrentPeak()).isGreaterThanOrEqualTo(1);
        assertThat(report.pricingMode()).isEqualTo("MODELED_FROM_UNDERLYING"); // fixture-only -> modeled, not demo

        // Every trade is a real defined-risk result with a recognized exit reason.
        for (var t : report.trades()) {
            assertThat(t.maxLossCents()).isGreaterThan(0);
            assertThat(t.exitReason()).isIn(EXIT_REASONS);
            assertThat(t.returnOnRisk()).isNotNull();
        }
        // Credit spreads collect a credit at entry.
        assertThat(report.trades()).anyMatch(t -> t.creditCents() > 0);
        // Stats computed over settled (non-WINDOW_END) trades.
        if (report.sampleSize() > 0) {
            assertThat(report.winRate()).isBetween(0.0, 1.0);
            assertThat(report.avgReturnOnRisk()).isNotNull();
        }
    }

    @Test void isDeterministic() {
        var a = backtester().run(req("CREDIT_PUT_SPREAD"));
        var b = backtester().run(req("CREDIT_PUT_SPREAD"));
        assertThat(a.endingCents()).isEqualTo(b.endingCents());
        assertThat(a.sampleSize()).isEqualTo(b.sampleSize());
        assertThat(a.trades()).hasSameSizeAs(b.trades());
    }

    @Test void supportsTheDirectionalDebitFamily() {
        var report = backtester().run(req("DEBIT_CALL_SPREAD"));
        assertThat(report.strategy()).isEqualTo("DEBIT_CALL_SPREAD");
        assertThat(report.trades()).isNotEmpty();
        // Debit spreads pay a debit at entry (creditCents < 0 for at least one).
        assertThat(report.trades()).anyMatch(t -> t.creditCents() < 0);
    }

    @Test void concurrentPeakRespectsTheCap() {
        var capped = backtester().run(new PortfolioBacktester.PortfolioRequest("AAPL", "CREDIT_PUT_SPREAD",
                "2026-01-02", "2026-06-01", 45, 3, 2, 1, 0.30, 0.05, 0.5, 0.8, 7, 100_000_00L));
        assertThat(capped.concurrentPeak()).isLessThanOrEqualTo(2);
    }
}
