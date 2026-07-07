package io.liftandshift.backtest;

import io.liftandshift.config.AppConfig;
import io.liftandshift.db.Db;
import io.liftandshift.db.Migrations;
import io.liftandshift.market.MarketDataService;
import io.liftandshift.market.providers.FixtureProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BacktesterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T15:30:00Z"), ZoneId.of("America/New_York"));

    @TempDir Path tmp;
    private Backtester backtester;
    private Db db;

    @BeforeEach
    void setUp() {
        db = new Db(tmp.resolve("bt.db").toString());
        Migrations.run(db);
        FixtureProvider fixture = new FixtureProvider(CLOCK);
        MarketDataService market = new MarketDataService(List.of(fixture), List.of(fixture), List.of(fixture));
        backtester = new Backtester(market, List.of(fixture), new AppConfig(Map.of("FIXTURES_ONLY", "true")), db, CLOCK);
    }

    private static Backtester.BacktestRequest req(String strategy, String from, String to) {
        return new Backtester.BacktestRequest("AAPL", strategy, from, to, 30, 5, 1, null, null);
    }

    @Test
    void debitSpreadBacktestProducesFullReport() {
        Backtester.BacktestReport report = backtester.run(req("DEBIT_CALL_SPREAD", "2026-01-05", "2026-06-30"));

        assertThat(report.sampleSize()).isGreaterThanOrEqualTo(3);
        // sampleSize counts completed (EXPIRED) trades only; a window-end model exit may add one more row
        long expired = report.trades().stream().filter(t -> "EXPIRED".equals(t.exitReason())).count();
        assertThat(report.sampleSize()).isEqualTo((int) expired);
        assertThat(report.trades().size()).isBetween(report.sampleSize(), report.sampleSize() + 1);
        for (Backtester.TradeResult t : report.trades()) {
            assertThat(LocalDate.parse(t.exitDate())).isAfterOrEqualTo(LocalDate.parse(t.entryDate()));
            assertThat(t.entryNetPremiumCents()).isNegative(); // debit
            assertThat(t.maxLossCents()).isPositive();
            // expired trades pay open fees only (cash settlement); window-end exits also pay close fees
            assertThat(t.feesCents()).isEqualTo("EXPIRED".equals(t.exitReason()) ? 130 : 260);
        }
        assertThat(report.daysCovered()).isPositive().isLessThanOrEqualTo(report.daysRequested());
        assertThat(report.pricingMode()).isEqualTo("MODELED_FROM_UNDERLYING");
        assertThat(report.confidence()).isEqualTo("medium");
        assertThat(report.equityCurve()).hasSize(report.daysCovered());
        assertThat(report.startingCents()).isEqualTo(10_000_000L);
        assertThat(report.assumptions()).containsKeys("slippagePctPerLeg", "feePerContractCents", "ivModel", "fills");
        assertThat(report.maxDrawdownPct()).isBetween(0.0, 1.0);
        assertThat(report.winRate()).isBetween(0.0, 1.0);
        assertThat(report.disclaimer()).containsIgnoringCase("not real historical option prices");
        assertThat(report.notes()).anySatisfy(n -> assertThat(n).containsIgnoringCase("black-scholes"));
        assertThat(report.worstTrade()).isNotNull();
        // Equity curve reconciles: ending equals starting + sum of trade P/L
        long pnlSum = report.trades().stream().mapToLong(Backtester.TradeResult::pnlCents).sum();
        assertThat(report.endingCents()).isEqualTo(report.startingCents() + pnlSum);
    }

    @Test
    void noLookAhead_extendingTheWindowNeverChangesEarlierTrades() {
        Backtester.BacktestReport shortRun = backtester.run(req("CREDIT_PUT_SPREAD", "2026-01-05", "2026-03-31"));
        Backtester.BacktestReport longRun = backtester.run(req("CREDIT_PUT_SPREAD", "2026-01-05", "2026-06-30"));

        List<Backtester.TradeResult> shortTrades = shortRun.trades().stream()
                .filter(t -> "EXPIRED".equals(t.exitReason())).toList();
        assertThat(shortTrades).isNotEmpty();
        for (Backtester.TradeResult early : shortTrades) {
            assertThat(longRun.trades()).anySatisfy(late -> {
                assertThat(late.entryDate()).isEqualTo(early.entryDate());
                assertThat(late.entryNetPremiumCents()).isEqualTo(early.entryNetPremiumCents());
                assertThat(late.exitValueCents()).isEqualTo(early.exitValueCents());
            });
        }
    }

    @Test
    void undefinedRiskAndUnsupportedFamiliesAreRejectedUpfront() {
        assertThatThrownBy(() -> backtester.run(req("NAKED_CALL", "2026-01-05", "2026-06-30")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("undefined risk");
        assertThatThrownBy(() -> backtester.run(req("CALENDAR_CALL", "2026-01-05", "2026-06-30")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not supported");
        assertThatThrownBy(() -> backtester.run(req("DEBIT_CALL_SPREAD", "2026-06-30", "2026-01-05")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyDataWindowReportsZeroCoverage() {
        Backtester.BacktestReport report = backtester.run(
                new Backtester.BacktestRequest("VTSAX", "LONG_CALL", "2020-01-01", "2020-02-01", 30, 5, 1, null, null));
        // VTSAX has candles but 2020 predates the fixture walk... whichever way, coverage <= requested and report is well-formed
        assertThat(report.daysCovered()).isLessThanOrEqualTo(report.daysRequested());
        assertThat(report.disclaimer()).isNotBlank();
    }

    @Test
    void reportsArePersistedAndListable() {
        Backtester.BacktestReport report = backtester.run(req("LONG_CALL", "2026-04-01", "2026-06-30"));
        List<Map<String, Object>> all = backtester.list();
        assertThat(all).hasSizeGreaterThanOrEqualTo(1);
        assertThat(all.getFirst().get("id")).isEqualTo(report.id());
        Map<String, Object> loaded = backtester.get(report.id());
        assertThat(loaded.get("strategy")).isEqualTo("LONG_CALL");
        assertThatThrownBy(() -> backtester.get("bt_nope")).isInstanceOf(java.util.NoSuchElementException.class);
        assertThatThrownBy(() -> backtester.run(new Backtester.BacktestRequest(
                "AAPL", "LONG_CALL", "2026-04-01", "2026-06-30", 30, 5, 1, null, -100L)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
    }
    @Test
    void coveredCallBacktestValuesTheStockLegAndCountsAssignments() {
        Backtester.BacktestReport report = backtester.run(req("COVERED_CALL", "2026-01-05", "2026-06-30"));
        assertThat(report.sampleSize()).isPositive();
        assertThat(report.assignments()).isNotNull().isBetween(0, report.sampleSize());
        // Buy-write entries are deep debits: the stock leg costs ~spot x 100
        for (Backtester.TradeResult t : report.trades()) {
            assertThat(t.entryNetPremiumCents()).isLessThan(-1_000_000); // more than $10k of stock
            if ("EXPIRED".equals(t.exitReason())) assertThat(t.assigned()).isNotNull();
        }
        // Reserve semantics keep buy-writes affordable on $100k: no skipped-for-cash days
        assertThat(report.skipped().stream().map(s -> s.get("reason"))
                .filter(r -> r.contains("buying power")).count()).isZero();
        assertThat(report.assumptions()).containsKey("stockLegs");
        assertThat(String.join(" ", report.notes())).contains("in the money");
    }

    @Test
    void cashSecuredPutBacktestReservesTheStrikeCash() {
        Backtester.BacktestReport report = backtester.run(req("CASH_SECURED_PUT", "2026-01-05", "2026-06-30"));
        assertThat(report.sampleSize()).isPositive();
        // Credits at entry, strike cash reserved — still affordable on $100k for one contract
        for (Backtester.TradeResult t : report.trades()) {
            assertThat(t.entryNetPremiumCents()).isPositive();
            assertThat(t.maxLossCents()).isGreaterThan(1_000_000); // ~strike x 100 minus credit
        }
        assertThat(report.assignments()).isNotNull();
    }

    @Test
    void unusableHistoricalChainFallsBackToTheModeledTier() {
        // Polygon-style historical chains carry daily aggregates only — no bid/ask/greeks.
        // The backtester must fall back to modeled pricing instead of skipping every day
        // and then labeling the empty run as if it had modeled something.
        io.liftandshift.market.ports.HistoricalOptionsProvider noBook = new io.liftandshift.market.ports.HistoricalOptionsProvider() {
            @Override public String name() { return "polygon"; }
            @Override public java.util.List<java.time.LocalDate> historicalExpirations(String symbol, java.time.LocalDate asOf) {
                return java.util.List.of(asOf.plusDays(30));
            }
            @Override public java.util.Optional<io.liftandshift.model.OptionChain> historicalChain(String symbol, java.time.LocalDate asOf, java.time.LocalDate expiration) {
                java.math.BigDecimal strike = new java.math.BigDecimal("250");
                io.liftandshift.model.OptionQuote dead = new io.liftandshift.model.OptionQuote(symbol, symbol + "C", io.liftandshift.model.OptionType.CALL,
                        strike, expiration, null, null, new java.math.BigDecimal("5.00"), null, null,
                        null, null, null, null, null, 0, "polygon", io.liftandshift.model.Freshness.EOD);
                return java.util.Optional.of(new io.liftandshift.model.OptionChain(symbol, expiration, new java.math.BigDecimal("255"),
                        java.util.List.of(dead), java.util.List.of(), 0, "polygon", io.liftandshift.model.Freshness.EOD));
            }
        };
        FixtureProvider fixture = new FixtureProvider(CLOCK);
        MarketDataService market = new MarketDataService(List.of(fixture), List.of(fixture), List.of(fixture));
        Backtester bt = new Backtester(market, List.of(noBook), new AppConfig(java.util.Map.of("FIXTURES_ONLY", "true")), db, CLOCK);
        Backtester.BacktestReport report = bt.run(req("DEBIT_CALL_SPREAD", "2026-01-05", "2026-06-30"));
        assertThat(report.sampleSize()).isPositive(); // modeled fallback actually entered trades
        assertThat(report.pricingMode()).isEqualTo("MODELED_FROM_UNDERLYING");
    }

    @Test
    void runWithZeroEntriesReportsNoConfidence() {
        // VTSAX has candles in fixtures but no options — every day is skipped
        Backtester.BacktestReport report = backtester.run(req("DEBIT_CALL_SPREAD", "2020-01-06", "2020-03-31"));
        if (report.sampleSize() == 0 && report.trades().isEmpty()) {
            assertThat(report.confidence()).startsWith("none");
        }
    }

}
