package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.position.PositionDomain;
import io.liftandshift.strikebench.position.PositionLifecycleReceipt;
import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BookActionProjectionServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-22T16:00:00Z"), ZoneOffset.UTC);
    private Db db;
    private PortfolioAccountingService books;
    private BookActionProjectionService projections;

    @BeforeEach void setUp() {
        db = TestDb.fresh();
        MarksSource marks = new MarksSource() {
            @Override public Optional<BigDecimal> underlyingMark(String symbol) {
                return Optional.of(new BigDecimal("400"));
            }

            @Override public Optional<LegMark> legMark(String symbol, Leg leg) {
                if (leg.isStock()) return Optional.of(mark("399.90", "400.10", 1.0));
                return Optional.of(mark("0.45", "0.50", -0.20));
            }
        };
        books = new PortfolioAccountingService(db, CLOCK, marks);
        var objectives = new AccountObjectiveService(db, CLOCK);
        var risk = new BookRiskService(db, CLOCK, marks, books, objectives, null);
        projections = new BookActionProjectionService(books, risk, CLOCK);
    }

    @AfterEach void close() { if (db != null) db.close(); }

    @Test
    void closeCurveAssignmentAndRollStepsRecomputeOneCanonicalBookWithoutMutation() {
        var account = books.createAccount("local", new PortfolioAccountingService.AccountInput(
                "Synthetic wheel", "TAXABLE", null, "FIFO", null, null, null, null,
                100_000_000L));
        books.record("local", account.id(), trade(new PortfolioAccountingService.LegInput(
                "OPTION", "SELL", "OPEN", "QQQ", "PUT", new BigDecimal("450"),
                LocalDate.parse("2026-08-07"), 3L, 100, new BigDecimal("2.00"), null)));
        long transactionsBefore = count("portfolio_transaction");
        long lotsBefore = count("portfolio_lot");

        var request = new TradeService.OpenRequest(account.id(), "QQQ", "CASH_SECURED_PUT", 3,
                List.of(Leg.option(LegAction.SELL, OptionType.PUT, new BigDecimal("450"),
                        LocalDate.parse("2026-08-07"), 1, BigDecimal.ZERO)),
                null, "16d", "DEFINED", "INCOME", false,
                null, null, "IMPORT", "EXECUTED");
        var close = new PositionLifecycleReceipt.CloseQuote(true, -14_250L, -15_000L,
                -15_000L, 300L, -15_300L, PositionDomain.PriceAuthority.OBSERVED,
                "Observed executable ask plus explicit closing fees.", null);

        var result = projections.project("local", account.id(), request, close, "position-fingerprint");

        assertThat(result.actions()).hasSize(6);
        var hold = action(result, "HOLD", 0);
        assertThat(hold.snapshot().shortPutObligation().cents()).isEqualTo(13_500_000L);
        assertThat(hold.snapshot().risk().stress().obligationCents()).isEqualTo(13_500_000L);

        var closeOne = action(result, "CLOSE_ONE", 1);
        assertThat(closeOne.snapshot().shortPutObligation().cents()).isEqualTo(9_000_000L);
        assertThat(closeOne.snapshot().cash().cents()).isEqualTo(100_054_900L);
        assertThat(closeOne.executableCost().feesCents()).isEqualTo(100L);
        assertThat(closeOne.quantityRemaining()).isEqualTo(2);

        var closeAll = action(result, "CLOSE_ALL", 3);
        assertThat(closeAll.snapshot().shortPutObligation().cents()).isZero();
        assertThat(closeAll.snapshot().risk().expiries().rows()).isEmpty();
        assertThat(closeAll.basisEffect().optionTaxBasisRemovedCents()).isEqualTo(60_000L);

        var assignment = action(result, "ASSIGNMENT", 3);
        assertThat(assignment.snapshot().cash().cents()).isEqualTo(86_560_000L);
        assertThat(assignment.snapshot().sharesBySymbol()).containsEntry("QQQ", 300L);
        assertThat(assignment.snapshot().shortPutObligation().cents()).isZero();
        assertThat(assignment.basisEffect().stockTaxBasisAddedCents()).isEqualTo(13_440_000L);

        var roll = action(result, "ROLL", 3);
        assertThat(roll.available()).isFalse();
        assertThat(roll.steps()).extracting(BookActionProjectionService.ActionStep::action)
                .containsExactly("CLOSE_EXISTING", "OPEN_REPLACEMENT");
        assertThat(roll.steps().get(1).status()).isEqualTo("UNAVAILABLE");

        assertThat(count("portfolio_transaction")).isEqualTo(transactionsBefore);
        assertThat(count("portfolio_lot")).isEqualTo(lotsBefore);
        assertThat(books.summary("local", account.id()).bookCashCents()).isEqualTo(100_060_000L);
    }

    @Test
    void coveredCallCallAwayRemovesOnlyRecordedSharesAndKeepsBothBasisSystemsVisible() {
        var account = books.createAccount("local", new PortfolioAccountingService.AccountInput(
                "Synthetic covered call", "TAXABLE", null, "FIFO", null, null, null, null,
                10_000_000L));
        books.record("local", account.id(), new PortfolioAccountingService.TransactionInput(
                "2026-07-01T16:00:00Z", "TRADE", null, 0L, null, "MANUAL", "synthetic-covered-call",
                null, List.of(
                new PortfolioAccountingService.LegInput("STOCK", "BUY", "OPEN", "INTC", null,
                        null, null, 200L, 1, new BigDecimal("100"), null),
                new PortfolioAccountingService.LegInput("OPTION", "SELL", "OPEN", "INTC", "CALL",
                        new BigDecimal("130"), LocalDate.parse("2026-08-21"), 2L, 100,
                        new BigDecimal("1"), null)), "EXECUTED"));

        var request = new TradeService.OpenRequest(account.id(), "INTC", "COVERED_CALL", 2,
                List.of(Leg.option(LegAction.SELL, OptionType.CALL, new BigDecimal("130"),
                        LocalDate.parse("2026-08-21"), 1, BigDecimal.ZERO)),
                null, "30d", "DEFINED", "INCOME", true,
                null, null, "IMPORT", "EXECUTED");
        var close = new PositionLifecycleReceipt.CloseQuote(true, -30_000L, -32_000L,
                -32_000L, 200L, -32_200L, PositionDomain.PriceAuthority.OBSERVED,
                "Observed executable ask plus explicit closing fees.", null);

        var result = projections.project("local", account.id(), request, close, "call-fingerprint");
        var callAway = action(result, "CALL_AWAY", 2);

        assertThat(callAway.available()).isTrue();
        assertThat(callAway.snapshot().sharesBySymbol()).doesNotContainKey("INTC");
        assertThat(callAway.snapshot().cash().cents()).isEqualTo(10_620_000L);
        assertThat(callAway.basisEffect().stockTaxBasisRemovedCents()).isEqualTo(2_000_000L);
        assertThat(callAway.basisEffect().optionEconomicBasisRemovedCents()).isEqualTo(20_000L);
        assertThat(callAway.executableCost().signedNetCashCents()).isEqualTo(2_600_000L);
        assertThat(callAway.executableCost().basis()).contains("Broker assignment").contains("unavailable");
    }

    private static BookActionProjectionService.ActionProjection action(
            BookActionProjectionService.ProjectionSet set, String action, int quantity) {
        return set.actions().stream().filter(row -> action.equals(row.action())
                        && row.quantityAffected() == quantity).findFirst().orElseThrow();
    }

    private long count(String table) {
        return db.query("SELECT COUNT(*) n FROM " + table, row -> row.lng("n")).getFirst();
    }

    private static PortfolioAccountingService.TransactionInput trade(
            PortfolioAccountingService.LegInput leg) {
        return new PortfolioAccountingService.TransactionInput("2026-07-01T16:00:00Z", "TRADE",
                null, 0L, null, "MANUAL", "synthetic-put", null, List.of(leg), "EXECUTED");
    }

    private static MarksSource.LegMark mark(String bid, String ask, double delta) {
        BigDecimal b = new BigDecimal(bid);
        BigDecimal a = new BigDecimal(ask);
        return new MarksSource.LegMark(b, a, b.add(a).divide(BigDecimal.TWO), null,
                Freshness.DELAYED, delta, 0.01, -0.02, 0.05,
                DataEvidence.of("observed-test", Freshness.DELAYED));
    }
}
