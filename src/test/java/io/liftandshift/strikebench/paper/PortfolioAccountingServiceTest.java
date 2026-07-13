package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.Leg;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortfolioAccountingServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-13T16:00:00Z"), ZoneOffset.UTC);
    private Db db;
    private PortfolioAccountingService books;

    @BeforeEach void setUp() {
        db = TestDb.fresh();
        books = new PortfolioAccountingService(db, CLOCK);
    }

    @AfterEach void close() { if (db != null) db.close(); }

    @Test
    void trackedAccountsAndOpeningCashNeverTouchPracticeMoney() {
        var audit = new AuditLog(db, CLOCK);
        var paper = new AccountService(db, new AppConfig(Map.of("FIXTURES_ONLY", "true")), audit, CLOCK)
                .getOrCreateDefault();
        var tracked = books.createAccount("local", account("Taxable brokerage", "TAXABLE", 1_000_000L));

        assertThat(tracked.accountType()).isEqualTo("TAXABLE");
        assertThat(books.transactions("local", tracked.id(), 0, 20))
                .singleElement().satisfies(tx -> {
                    assertThat(tx.eventType()).isEqualTo("DEPOSIT");
                    assertThat(tx.cashEffectCents()).isEqualTo(1_000_000L);
                });
        assertThat(new AccountService(db, new AppConfig(Map.of("FIXTURES_ONLY", "true")), audit, CLOCK)
                .get(paper.id()).cashCents()).isEqualTo(paper.cashCents());
        assertThat(db.query("SELECT COUNT(*) n FROM ledger", r -> r.lng("n")).getFirst()).isEqualTo(1);
    }

    @Test
    void fifoPartialStockCloseConservesBasisAndFeesExactly() {
        var account = books.createAccount("local", account("Brokerage", "TAXABLE", null));
        books.record("local", account.id(), trade("2025-01-02", 3L,
                leg("STOCK", "BUY", "OPEN", "AAPL", null, null, null, 3, 1, "10.00")));
        var close = books.record("local", account.id(), trade("2026-01-03", 2L,
                leg("STOCK", "SELL", "CLOSE", "AAPL", null, null, null, 2, 1, "12.00")));

        assertThat(close.cashEffectCents()).isEqualTo(23_98L);
        assertThat(close.occurredAt()).startsWith("2026-01-03T12:00");
        var lot = books.lots("local", account.id(), true).getFirst();
        assertThat(lot.originalOpenAmountCents()).isEqualTo(30_03L);
        assertThat(lot.remainingQuantity()).isEqualTo(1);
        assertThat(lot.remainingOpenAmountCents()).isEqualTo(10_01L);
        var realized = books.realizedLots("local", account.id(), 2026).getFirst();
        assertThat(realized.openAmountCents()).isEqualTo(20_02L);
        assertThat(realized.closeAmountCents()).isEqualTo(23_98L);
        assertThat(realized.realizedGainCents()).isEqualTo(3_96L);
    }

    @Test
    void shortOptionCloseUsesOpeningProceedsLessClosingCost() {
        var account = books.createAccount("local", account("IRA", "TRADITIONAL_IRA", null));
        books.record("local", account.id(), tx("2026-01-02", "TRADE", null, 200L, null, "BROKER", "open-1",
                List.of(leg("OPTION", "SELL", "OPEN", "QQQ", "PUT", "450", "2026-03-20", 2, 100, "2.00"))));
        books.record("local", account.id(), tx("2026-02-02", "TRADE", null, 100L, null, "BROKER", "close-1",
                List.of(leg("OPTION", "BUY", "CLOSE", "QQQ", "PUT", "450", "2026-03-20", 1, 100, "1.00"))));

        var lot = books.lots("local", account.id(), false).getFirst();
        assertThat(lot.side()).isEqualTo("SHORT");
        assertThat(lot.remainingQuantity()).isEqualTo(1);
        assertThat(lot.remainingOpenAmountCents()).isEqualTo(199_00L);
        var realized = books.realizedLots("local", account.id(), 2026).getFirst();
        assertThat(realized.openAmountCents()).isEqualTo(199_00L);
        assertThat(realized.closeAmountCents()).isEqualTo(101_00L);
        assertThat(realized.realizedGainCents()).isEqualTo(98_00L);
        assertThat(realized.holdingTerm()).isEqualTo("SHORT_TERM");
    }

    @Test
    void shortPutAssignmentTransfersPremiumIntoShareBasis() {
        var account = books.createAccount("local", account("Brokerage", "TAXABLE", null));
        books.record("local", account.id(), tx("2026-01-02", "TRADE", null, 0L, null, "BROKER", "put-open",
                List.of(leg("OPTION", "SELL", "OPEN", "AAPL", "PUT", "100", "2026-02-20", 1, 100, "3.00"))));
        var assigned = books.record("local", account.id(), tx("2026-02-20", "ASSIGNMENT", null, 0L, null, "BROKER", "assign-1",
                List.of(
                        leg("OPTION", "BUY", "CLOSE", "AAPL", "PUT", "100", "2026-02-20", 1, 100, "0"),
                        leg("STOCK", "BUY", "OPEN", "AAPL", null, null, null, 100, 1, "100"))));

        assertThat(assigned.cashEffectCents()).isEqualTo(-1_000_000L);
        var stockLot = books.lots("local", account.id(), true).stream()
                .filter(lot -> "STOCK".equals(lot.instrumentType())).findFirst().orElseThrow();
        assertThat(stockLot.remainingQuantity()).isEqualTo(100);
        assertThat(stockLot.remainingOpenAmountCents()).isEqualTo(970_000L);
        assertThat(books.realizedLots("local", account.id(), 2026))
                .singleElement().satisfies(match -> {
                    assertThat(match.holdingTerm()).isEqualTo("ROLLED");
                    assertThat(match.realizedGainCents()).isZero();
                });
    }

    @Test
    void exerciseAndCallAssignmentTransferOptionBasisWithoutInventingTaxableOptionGain() {
        var exercise = books.createAccount("local", account("Exercise", "TAXABLE", null));
        books.record("local", exercise.id(), tx("2025-01-02", "TRADE", null, 0L, null, "BROKER", "call-buy",
                List.of(leg("OPTION", "BUY", "OPEN", "AAPL", "CALL", "100", "2026-01-16", 1, 100, "5"))));
        books.record("local", exercise.id(), tx("2026-01-16", "EXERCISE", null, 0L, null, "BROKER", "call-exercise",
                List.of(
                        leg("OPTION", "SELL", "CLOSE", "AAPL", "CALL", "100", "2026-01-16", 1, 100, "0"),
                        leg("STOCK", "BUY", "OPEN", "AAPL", null, null, null, 100, 1, "100"))));
        var exercisedShares = books.lots("local", exercise.id(), false).stream()
                .filter(l -> "STOCK".equals(l.instrumentType())).findFirst().orElseThrow();
        assertThat(exercisedShares.remainingOpenAmountCents()).isEqualTo(1_050_000L);
        assertThat(books.realizedLots("local", exercise.id(), 2026).getFirst().holdingTerm()).isEqualTo("ROLLED");

        var assigned = books.createAccount("local", account("Assigned call", "TAXABLE", null));
        books.record("local", assigned.id(), tx("2025-01-02", "TRADE", null, 0L, null, "BROKER", "shares",
                List.of(leg("STOCK", "BUY", "OPEN", "AAPL", null, null, null, 100, 1, "80"))));
        books.record("local", assigned.id(), tx("2025-02-02", "TRADE", null, 0L, null, "BROKER", "call-sell",
                List.of(leg("OPTION", "SELL", "OPEN", "AAPL", "CALL", "100", "2026-01-16", 1, 100, "4"))));
        books.record("local", assigned.id(), tx("2026-01-16", "ASSIGNMENT", null, 0L, null, "BROKER", "call-assigned",
                List.of(
                        leg("OPTION", "BUY", "CLOSE", "AAPL", "CALL", "100", "2026-01-16", 1, 100, "0"),
                        leg("STOCK", "SELL", "CLOSE", "AAPL", null, null, null, 100, 1, "100"))));
        var realized = books.realizedLots("local", assigned.id(), 2026);
        assertThat(realized.stream().filter(m -> "STOCK".equals(m.instrumentType())).findFirst().orElseThrow()
                .realizedGainCents()).isEqualTo(240_000L);
        assertThat(realized.stream().filter(m -> "OPTION".equals(m.instrumentType())).findFirst().orElseThrow()
                .holdingTerm()).isEqualTo("ROLLED");
    }

    @Test
    void aCloseWithoutInventoryRollsBackTheWholeTransaction() {
        var account = books.createAccount("local", account("Brokerage", "TAXABLE", null));
        assertThatThrownBy(() -> books.record("local", account.id(), tx("2026-01-02", "TRADE", null, 0L,
                null, "BROKER", "bad-close", List.of(
                        leg("STOCK", "SELL", "CLOSE", "AAPL", null, null, null, 1, 1, "100")))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("only 0 matching long units");
        assertThat(books.transactions("local", account.id(), 0, 20)).isEmpty();
        assertThat(db.query("SELECT COUNT(*) n FROM portfolio_transaction_leg", r -> r.lng("n")).getFirst()).isZero();
    }

    @Test
    void taxableAndRetirementReportsNeverPretendToShareTaxTreatment() {
        var taxable = books.createAccount("local", new PortfolioAccountingService.AccountInput(
                "Taxable", "TAXABLE", null, "FIFO", 3200, 1500, 2400, 500, null));
        books.record("local", taxable.id(), tx("2024-01-01", "TRADE", null, 0L, null, "MANUAL", "buy",
                List.of(leg("STOCK", "BUY", "OPEN", "MSFT", null, null, null, 10, 1, "100"))));
        books.record("local", taxable.id(), tx("2026-01-02", "TRADE", null, 0L, null, "MANUAL", "sell",
                List.of(leg("STOCK", "SELL", "CLOSE", "MSFT", null, null, null, 10, 1, "120"))));
        books.record("local", taxable.id(), cash("2026-06-30", "INTEREST", 10_00L, "i1", null));
        var tax = books.taxReport("local", taxable.id(), 2026);
        assertThat(tax.longTermGainCents()).isEqualTo(200_00L);
        assertThat(tax.ordinaryInterestCents()).isEqualTo(10_00L);
        assertThat(tax.estimatedFederalTaxCents()).isEqualTo(32_40L);
        assertThat(tax.estimatedStateTaxCents()).isEqualTo(10_50L);

        var roth = books.createAccount("local", account("Roth", "ROTH_IRA", null));
        books.record("local", roth.id(), cash("2026-06-30", "INTEREST", 10_00L, "ri1", null));
        var rothTax = books.taxReport("local", roth.id(), 2026);
        assertThat(rothTax.estimatedTotalTaxCents()).isZero();
        assertThat(rothTax.note()).contains("no current per-trade");
    }

    @Test
    void modifiedDietzSeparatesInvestmentGainFromExternalFlows() {
        var account = books.createAccount("local", account("401k", "TRADITIONAL_401K", null));
        books.addValuation("local", account.id(), valuation("2026-01-01", 100_000_00L));
        books.record("local", account.id(), cash("2026-07-02", "DEPOSIT", 10_000_00L, "contribution", null));
        books.addValuation("local", account.id(), valuation("2027-01-01", 121_000_00L));

        var performance = books.performance("local", account.id());
        assertThat(performance.netExternalFlowCents()).isEqualTo(10_000_00L);
        assertThat(performance.investmentGainCents()).isEqualTo(11_000_00L);
        assertThat(performance.modifiedDietzReturn()).isCloseTo(0.10476, within(0.0002));
        assertThat(performance.note()).contains("Modified Dietz");
    }

    @Test
    void duplicateBrokerReferenceIsRejectedWithoutPartialLots() {
        var account = books.createAccount("local", account("Brokerage", "TAXABLE", null));
        var open = tx("2026-01-02", "TRADE", null, 0L, null, "BROKER", "ORDER-7",
                List.of(leg("STOCK", "BUY", "OPEN", "AMD", null, null, null, 5, 1, "100")));
        books.record("local", account.id(), open);
        assertThatThrownBy(() -> books.record("local", account.id(), open))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already recorded");
        assertThat(books.transactions("local", account.id(), 0, 20)).hasSize(1);
        assertThat(books.lots("local", account.id(), false)).singleElement()
                .satisfies(lot -> assertThat(lot.remainingQuantity()).isEqualTo(5));
    }

    @Test
    void summaryUsesExecutableLiquidationSidesAndKeepsTrackedCashExact() {
        MarksSource marks = new MarksSource() {
            @Override public Optional<BigDecimal> underlyingMark(String symbol) { return Optional.empty(); }
            @Override public Optional<LegMark> legMark(String symbol, Leg leg) {
                if (leg.isStock() && "AAPL".equals(symbol)) return Optional.of(mark("109.90", "110.00"));
                if (!leg.isStock() && "QQQ".equals(symbol)) return Optional.of(mark("1.40", "1.50"));
                return Optional.empty();
            }
        };
        books = new PortfolioAccountingService(db, CLOCK, marks);
        var account = books.createAccount("local", account("Taxable", "TAXABLE", 10_000_000L));
        books.record("local", account.id(), trade("2026-01-02", 0L,
                leg("STOCK", "BUY", "OPEN", "AAPL", null, null, null, 10, 1, "100")));
        books.record("local", account.id(), trade("2026-01-03", 0L,
                leg("OPTION", "SELL", "OPEN", "QQQ", "PUT", "450", "2026-08-21", 1, 100, "2")));

        var summary = books.summary("local", account.id());
        assertThat(summary.bookCashCents()).isEqualTo(9_920_000L);
        assertThat(summary.securitiesLiquidationValueCents()).isEqualTo(94_900L); // stock bid less short-put ask
        assertThat(summary.totalValueCents()).isEqualTo(10_014_900L);
        assertThat(summary.unrealizedPnlCents()).isEqualTo(14_900L);
        assertThat(summary.collateral().knownBlockedCashCents()).isEqualTo(4_500_000L);
        assertThat(summary.collateral().availableCashCents()).isEqualTo(5_420_000L);
        assertThat(summary.collateral().cashSecuredPutContracts()).isEqualTo(1);
        assertThat(summary.positions()).allMatch(PortfolioAccountingService.PositionView::complete);
        assertThat(summary.valuationBasis()).contains("executable closing sides");
    }

    @Test
    void missingMarkNeverBecomesZeroPortfolioValue() {
        var account = books.createAccount("local", account("Taxable", "TAXABLE", 1_000_000L));
        books.record("local", account.id(), trade("2026-01-02", 0L,
                leg("STOCK", "BUY", "OPEN", "NVDA", null, null, null, 2, 1, "200")));

        var summary = books.summary("local", account.id());
        assertThat(summary.complete()).isFalse();
        assertThat(summary.totalValueCents()).isNull();
        assertThat(summary.unrealizedPnlCents()).isNull();
        assertThat(summary.missingMarks()).containsExactly("NVDA shares");
        assertThat(summary.positions()).singleElement().satisfies(p -> {
            assertThat(p.liquidationValueCents()).isNull();
            assertThat(p.provenance()).isEqualTo("MISSING");
        });
    }

    @Test
    void protectivePutWingReducesCashBlockedToSpreadWidth() {
        books = new PortfolioAccountingService(db, CLOCK, new EmptyMarks());
        var account = books.createAccount("local", account("IRA", "TRADITIONAL_IRA", 5_000_000L));
        books.record("local", account.id(), tx("2026-01-02", "TRADE", null, 0L, null, "MANUAL", "spread",
                List.of(
                        leg("OPTION", "SELL", "OPEN", "QQQ", "PUT", "450", "2026-08-21", 1, 100, "2"),
                        leg("OPTION", "BUY", "OPEN", "QQQ", "PUT", "440", "2026-08-21", 1, 100, "1"))));

        var collateral = books.summary("local", account.id()).collateral();
        assertThat(collateral.knownBlockedCashCents()).isEqualTo(100_000L);
        assertThat(collateral.definedRiskPutContracts()).isEqualTo(1);
        assertThat(collateral.cashSecuredPutContracts()).isZero();
        assertThat(collateral.complete()).isTrue();
    }

    @Test
    void adjustedContractMultiplierIsPartOfLotIdentity() {
        var account = books.createAccount("local", account("Taxable", "TAXABLE", null));
        books.record("local", account.id(), trade("2026-01-02", 0L,
                leg("OPTION", "BUY", "OPEN", "XYZ", "CALL", "10", "2026-08-21", 1, 50, "1")));
        assertThatThrownBy(() -> books.record("local", account.id(), trade("2026-01-03", 0L,
                leg("OPTION", "SELL", "CLOSE", "XYZ", "CALL", "10", "2026-08-21", 1, 100, "2"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("only 0 matching long units");
    }

    private static MarksSource.LegMark mark(String bid, String ask) {
        return new MarksSource.LegMark(new BigDecimal(bid), new BigDecimal(ask),
                new BigDecimal(bid).add(new BigDecimal(ask)).divide(BigDecimal.TWO), null,
                Freshness.DELAYED, null, null, null, null,
                DataEvidence.of("observed-test", Freshness.DELAYED));
    }

    private static final class EmptyMarks implements MarksSource {
        @Override public Optional<BigDecimal> underlyingMark(String symbol) { return Optional.empty(); }
        @Override public Optional<LegMark> legMark(String symbol, Leg leg) { return Optional.empty(); }
    }

    private static PortfolioAccountingService.AccountInput account(String name, String type, Long cash) {
        return new PortfolioAccountingService.AccountInput(name, type, null, "FIFO", null, null, null, null, cash);
    }

    private static PortfolioAccountingService.TransactionInput trade(String date, Long fees,
                                                                      PortfolioAccountingService.LegInput leg) {
        return tx(date, "TRADE", null, fees, null, "MANUAL", null, List.of(leg));
    }

    private static PortfolioAccountingService.TransactionInput cash(String date, String event, long amount,
                                                                     String ref, String category) {
        return tx(date, event, amount, 0L, category, "MANUAL", ref, List.of());
    }

    private static PortfolioAccountingService.TransactionInput tx(String date, String event, Long cash,
                                                                   Long fees, String category, String source,
                                                                   String ref, List<PortfolioAccountingService.LegInput> legs) {
        return new PortfolioAccountingService.TransactionInput(date, event, cash, fees, category, source, ref, null, legs);
    }

    private static PortfolioAccountingService.LegInput leg(String instrument, String action, String effect,
                                                            String symbol, String optionType, String strike,
                                                            String expiration, long quantity, int multiplier,
                                                            String price) {
        return new PortfolioAccountingService.LegInput(instrument, action, effect, symbol, optionType,
                strike == null ? null : new BigDecimal(strike), expiration == null ? null : LocalDate.parse(expiration),
                quantity, multiplier, new BigDecimal(price));
    }

    private static PortfolioAccountingService.ValuationInput valuation(String date, long total) {
        return new PortfolioAccountingService.ValuationInput(date, null, null, total, "MANUAL", null, null);
    }

    private static org.assertj.core.data.Offset<Double> within(double amount) {
        return org.assertj.core.data.Offset.offset(amount);
    }
}
