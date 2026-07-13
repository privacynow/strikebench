package io.liftandshift.strikebench.backtest;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.db.StoredCandleStore;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.market.ports.HistoricalOptionsProvider;
import io.liftandshift.strikebench.market.ports.NewsFilingsProvider;
import io.liftandshift.strikebench.market.ports.RatesProvider;
import io.liftandshift.strikebench.market.providers.FixtureProvider;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Managed replay shares the same historical kernel and persistence as single-position backtests. */
class ManagedBacktestTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T15:30:00Z"),
            ZoneId.of("America/New_York"));
    private static final Set<String> EXIT_REASONS = Set.of(
            "PROFIT_TARGET", "STOP", "TIME", "EXPIRED", "WINDOW_END");

    private Db db;
    private MarketDataService market;
    private Backtester backtester;

    @BeforeEach
    void setUp() {
        db = TestDb.fresh();
        FixtureProvider fixture = new FixtureProvider(CLOCK);
        market = new MarketDataService(List.<MarketDataProvider>of(fixture),
                List.<NewsFilingsProvider>of(fixture), List.<RatesProvider>of(fixture));
        backtester = new Backtester(market, List.of(),
                new AppConfig(Map.of("FIXTURES_ONLY", "true")), db, CLOCK);
    }

    @AfterEach
    void closeDb() {
        if (db != null) db.close();
    }

    private static Backtester.PortfolioRequest req(String strategy) {
        return new Backtester.PortfolioRequest("AAPL", strategy, "2026-01-02", "2026-06-01",
                30, 5, 4, 1, 0.30, 0.05, 0.5, 0.8, 7, 100_000_00L);
    }

    @Test
    void runsConcurrentPositionsWithMechanicalExitsAndPersistsTheReport() {
        var report = backtester.runPortfolio(req("CREDIT_PUT_SPREAD"));

        assertThat(report.daysCovered()).isGreaterThan(0);
        assertThat(report.equityCurve()).isNotEmpty();
        assertThat(report.trades()).isNotEmpty();
        assertThat(report.concurrentPeak()).isGreaterThanOrEqualTo(1);
        assertThat(report.pricingMode()).isEqualTo("PAYOFF_ONLY");
        assertThat(report.demoUnderlying()).isTrue();
        assertThat(backtester.get(report.id()).get("strategy")).isEqualTo("CREDIT_PUT_SPREAD");

        for (var trade : report.trades()) {
            assertThat(trade.maxLossCents()).isGreaterThan(0);
            assertThat(trade.exitReason()).isIn(EXIT_REASONS);
            assertThat(trade.returnOnRisk()).isNotNull();
        }
        assertThat(report.trades()).anyMatch(t -> t.creditCents() > 0);
        if (report.sampleSize() > 0) {
            assertThat(report.winRate()).isBetween(0.0, 1.0);
            assertThat(report.avgReturnOnRisk()).isNotNull();
        }
    }

    @Test
    void isDeterministic() {
        var first = backtester.runPortfolio(req("CREDIT_PUT_SPREAD"));
        var second = backtester.runPortfolio(req("CREDIT_PUT_SPREAD"));
        assertThat(first.endingCents()).isEqualTo(second.endingCents());
        assertThat(first.sampleSize()).isEqualTo(second.sampleSize());
        assertThat(first.trades()).hasSameSizeAs(second.trades());
    }

    @Test
    void supportsTheDirectionalDebitFamilyAndConcurrencyCap() {
        var directional = backtester.runPortfolio(req("DEBIT_CALL_SPREAD"));
        assertThat(directional.strategy()).isEqualTo("DEBIT_CALL_SPREAD");
        assertThat(directional.trades()).isNotEmpty();
        assertThat(directional.trades()).anyMatch(t -> t.creditCents() < 0);

        var capped = backtester.runPortfolio(new Backtester.PortfolioRequest(
                "AAPL", "CREDIT_PUT_SPREAD", "2026-01-02", "2026-06-01",
                45, 3, 2, 1, 0.30, 0.05, 0.5, 0.8, 7, 100_000_00L));
        assertThat(capped.concurrentPeak()).isLessThanOrEqualTo(2);
    }

    @Test
    void historicalKernelUsesOppositeBookSidesForEntryAndExit() {
        LocalDate asOf = LocalDate.parse("2026-03-02");
        LocalDate expiration = LocalDate.parse("2026-04-17");
        db.exec("INSERT INTO option_bar(symbol,asof,expiration,strike,opt_type,bid,ask,mark,source,"
                        + "bid_ask_observed,iv_source,dataset_id) VALUES (?,?,?,?,?,?,?,?,?,1,'vendor','observed')",
                "AAPL", asOf, expiration, new BigDecimal("250"), "CALL",
                new BigDecimal("4.00"), new BigDecimal("6.00"), new BigDecimal("5.00"), "orats");

        HistoricalReplayKernel kernel = new HistoricalReplayKernel(market, db);
        var evidence = new HistoricalReplayKernel.Evidence();
        Leg longCall = Leg.option(LegAction.BUY, OptionType.CALL, new BigDecimal("250"),
                expiration, 1, BigDecimal.ZERO);
        Leg shortCall = Leg.option(LegAction.SELL, OptionType.CALL, new BigDecimal("250"),
                expiration, 1, BigDecimal.ZERO);

        assertThat(kernel.valueCents("AAPL", List.of(longCall), 1, 250, 0.30, asOf,
                AnalysisContext.OBSERVED, HistoricalReplayKernel.PriceIntent.ENTRY, false, evidence)).isEqualTo(60_000);
        assertThat(kernel.valueCents("AAPL", List.of(longCall), 1, 250, 0.30, asOf,
                AnalysisContext.OBSERVED, HistoricalReplayKernel.PriceIntent.EXIT, false, evidence)).isEqualTo(40_000);
        assertThat(kernel.valueCents("AAPL", List.of(longCall), 1, 250, 0.30, asOf,
                AnalysisContext.OBSERVED, HistoricalReplayKernel.PriceIntent.MARK, false, evidence)).isEqualTo(50_000);
        assertThat(kernel.valueCents("AAPL", List.of(shortCall), 1, 250, 0.30, asOf,
                AnalysisContext.OBSERVED, HistoricalReplayKernel.PriceIntent.ENTRY, false, evidence)).isEqualTo(-40_000);
        assertThat(kernel.valueCents("AAPL", List.of(shortCall), 1, 250, 0.30, asOf,
                AnalysisContext.OBSERVED, HistoricalReplayKernel.PriceIntent.EXIT, false, evidence)).isEqualTo(-60_000);
        assertThat(evidence.observedMarks()).isEqualTo(5);
        assertThat(evidence.totalMarks()).isEqualTo(5);

        db.exec("INSERT INTO option_bar(symbol,asof,expiration,strike,opt_type,bid,ask,mark,source,"
                        + "bid_ask_observed,iv_source,dataset_id) VALUES (?,?,?,?,?,?,?,?,?,1,'vendor','observed')",
                "AAPL", asOf, expiration, new BigDecimal("255"), "CALL",
                null, new BigDecimal("6.00"), new BigDecimal("5.00"), "orats");
        Leg missingBidShort = Leg.option(LegAction.SELL, OptionType.CALL, new BigDecimal("255"),
                expiration, 1, BigDecimal.ZERO);
        kernel.valueCents("AAPL", List.of(missingBidShort), 1, 250, 0.30, asOf,
                AnalysisContext.OBSERVED, HistoricalReplayKernel.PriceIntent.ENTRY, false, evidence);
        assertThat(evidence.observedMarks()).isEqualTo(5);
        assertThat(evidence.totalMarks()).isEqualTo(6);
    }

    @Test
    void listedContractSelectionIsOptionTypeSpecific() {
        LocalDate asOf = LocalDate.parse("2026-03-02");
        LocalDate expiration = LocalDate.parse("2026-04-17");
        for (String strike : List.of("240", "245")) {
            db.exec("INSERT INTO option_bar(symbol,asof,expiration,strike,opt_type,bid,ask,mark,source,"
                            + "bid_ask_observed,iv_source,dataset_id) VALUES (?,?,?,?,?,?,?,?,?,1,'vendor','observed')",
                    "AAPL", asOf, expiration, new BigDecimal(strike), "PUT",
                    new BigDecimal("4.00"), new BigDecimal("4.20"), new BigDecimal("4.10"), "orats");
        }
        HistoricalReplayKernel kernel = new HistoricalReplayKernel(market, db);
        assertThat(kernel.listedExpirationNear("AAPL", asOf, 45, OptionType.PUT, AnalysisContext.OBSERVED))
                .isEqualTo(expiration);
        assertThat(kernel.listedExpirationNear("AAPL", asOf, 45, OptionType.CALL, AnalysisContext.OBSERVED)).isNull();
        assertThat(kernel.listedStrikes("AAPL", asOf, expiration, OptionType.PUT, AnalysisContext.OBSERVED))
                .containsExactly(240.0, 245.0);
        assertThat(kernel.listedStrikes("AAPL", asOf, expiration, OptionType.CALL, AnalysisContext.OBSERVED)).isEmpty();
    }

    @Test
    void syntheticReplayNeverSplicesObservedOptionBooksOrStrikeGrids() {
        LocalDate asOf = LocalDate.parse("2026-03-02");
        LocalDate expiration = LocalDate.parse("2026-04-17");
        db.exec("INSERT INTO option_bar(symbol,asof,expiration,strike,opt_type,bid,ask,mark,source," +
                        "bid_ask_observed,iv_source,dataset_id) VALUES (?,?,?,?,?,?,?,?,?,1,'vendor','observed')",
                "AAPL", asOf, expiration, new BigDecimal("250"), "CALL",
                new BigDecimal("4.00"), new BigDecimal("6.00"), new BigDecimal("5.00"), "orats");
        AnalysisContext scenario = new AnalysisContext(null, "scenario-without-options");
        HistoricalReplayKernel kernel = new HistoricalReplayKernel(market, db);
        var evidence = new HistoricalReplayKernel.Evidence();
        Leg call = Leg.option(LegAction.BUY, OptionType.CALL, new BigDecimal("250"),
                expiration, 1, BigDecimal.ZERO);

        long modeled = kernel.valueCents("AAPL", List.of(call), 1, 250, 0.30, asOf,
                scenario, HistoricalReplayKernel.PriceIntent.ENTRY, false, evidence);

        assertThat(modeled).isNotEqualTo(60_000L);
        assertThat(evidence.observedMarks()).isZero();
        assertThat(evidence.totalMarks()).isEqualTo(1);
        assertThat(kernel.listedExpirationNear("AAPL", asOf, 45, OptionType.CALL, scenario)).isNull();
        assertThat(kernel.listedStrikes("AAPL", asOf, expiration, OptionType.CALL, scenario)).isEmpty();
    }

    @Test
    void syntheticBacktestNeverCallsObservedChainProvidersAndLabelsTheModeledRun() {
        String dataset = "scenario-replay";
        db.exec("INSERT INTO dataset(id,name,kind,symbol,spec) VALUES(?,?,?,?,'{}'::jsonb)",
                dataset, "Scenario replay", "SYNTHETIC_PURE", "AAPL");
        LocalDate day = LocalDate.parse("2026-01-02");
        int index = 0;
        while (!day.isAfter(LocalDate.parse("2026-06-01"))) {
            if (day.getDayOfWeek().getValue() <= 5) {
                BigDecimal close = BigDecimal.valueOf(240 + index * 0.25);
                db.exec("INSERT INTO underlying_bar(symbol,d,open,high,low,close,source,observed,dataset_id) " +
                                "VALUES(?,?,?,?,?,?,?,0,?)", "AAPL", day, close, close, close, close,
                        "scenario", dataset);
                index++;
            }
            day = day.plusDays(1);
        }
        AtomicInteger historicalCalls = new AtomicInteger();
        HistoricalOptionsProvider observedProvider = new HistoricalOptionsProvider() {
            @Override public String name() { return "observed-spy"; }
            @Override public java.util.Optional<io.liftandshift.strikebench.model.OptionChain> historicalChain(
                    String symbol, LocalDate asOf, LocalDate expiration) {
                historicalCalls.incrementAndGet();
                return java.util.Optional.empty();
            }
            @Override public List<LocalDate> historicalExpirations(String symbol, LocalDate asOf) {
                historicalCalls.incrementAndGet();
                return List.of(asOf.plusDays(30));
            }
        };
        MarketDataService scenarioMarket = new MarketDataService(List.of(), List.of(), List.of(),
                new StoredCandleStore(db));
        Backtester scenarioBacktester = new Backtester(scenarioMarket, List.of(observedProvider),
                new AppConfig(Map.of()), db, CLOCK);

        Backtester.BacktestReport report = scenarioBacktester.run(
                new Backtester.BacktestRequest("AAPL", "DEBIT_CALL_SPREAD", "2026-02-02", "2026-05-29",
                        30, 5, 1, 0.0, 10_000_000L), new AnalysisContext(null, dataset));

        assertThat(historicalCalls).hasValue(0);
        assertThat(report.pricingMode()).isEqualTo("MODELED_FROM_UNDERLYING");
        assertThat(report.confidence()).isEqualTo("modeled (generated underlying)");
        assertThat(report.notes()).anyMatch(note -> note.contains("generated analysis dataset " + dataset)
                && note.contains("not mixed"));
    }
}
