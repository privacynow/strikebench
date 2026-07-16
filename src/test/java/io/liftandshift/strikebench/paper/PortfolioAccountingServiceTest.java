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

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
                    assertThat(tx.eventType()).isEqualTo("OPENING_BALANCE");
                    assertThat(tx.cashEffectCents()).isEqualTo(1_000_000L);
                });
        assertThat(new AccountService(db, new AppConfig(Map.of("FIXTURES_ONLY", "true")), audit, CLOCK)
                .get(paper.id()).cashCents()).isEqualTo(paper.cashCents());
        assertThat(db.query("SELECT COUNT(*) n FROM ledger", r -> r.lng("n")).getFirst()).isEqualTo(1);
    }

    @Test
    void trackedAccountsAndEveryDerivedBookRemainOwnerScoped() {
        var account = books.createAccount("owner-a", account("Owner A", "TAXABLE", 1_000_00L));
        books.record("owner-a", account.id(), trade("2026-01-02", 0L,
                leg("STOCK", "BUY", "OPEN", "AAPL", null, null, null, 1, 1, "100")));

        assertThat(books.accounts("owner-b")).isEmpty();
        assertThatThrownBy(() -> books.account("owner-b", account.id()))
                .isInstanceOf(java.util.NoSuchElementException.class);
        assertThatThrownBy(() -> books.summary("owner-b", account.id()))
                .isInstanceOf(java.util.NoSuchElementException.class);
        assertThatThrownBy(() -> books.record("owner-b", account.id(), cash(
                "2026-01-03", "INTEREST", 1_00L, "cross-owner", null)))
                .isInstanceOf(java.util.NoSuchElementException.class);
        assertThat(books.transactions("owner-a", account.id(), 0, 20)).hasSize(2);
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
    void sameTimestampLotsUseAppendSequenceForDeterministicFifoAndLifo() {
        var fifo = books.createAccount("local", account("FIFO", "TAXABLE", null));
        books.record("local", fifo.id(), tx("2026-01-02", "TRADE", null, 0L, null, "BROKER", "fifo-first",
                List.of(leg("STOCK", "BUY", "OPEN", "AAPL", null, null, null, 1, 1, "100"))));
        books.record("local", fifo.id(), tx("2026-01-02", "TRADE", null, 0L, null, "BROKER", "fifo-second",
                List.of(leg("STOCK", "BUY", "OPEN", "AAPL", null, null, null, 1, 1, "200"))));
        books.record("local", fifo.id(), tx("2026-02-02", "TRADE", null, 0L, null, "BROKER", "fifo-close",
                List.of(leg("STOCK", "SELL", "CLOSE", "AAPL", null, null, null, 1, 1, "150"))));
        assertThat(books.realizedLots("local", fifo.id(), 2026).getFirst().realizedGainCents()).isEqualTo(50_00L);
        assertThat(books.lots("local", fifo.id(), false).getFirst().remainingOpenAmountCents()).isEqualTo(200_00L);
        assertThat(books.transactions("local", fifo.id(), 0, 10))
                .extracting(PortfolioAccountingService.TransactionView::externalRef)
                .containsExactly("fifo-close", "fifo-second", "fifo-first");

        var lifo = books.createAccount("local", new PortfolioAccountingService.AccountInput(
                "LIFO", "TAXABLE", null, "LIFO", null, null, null, null, null));
        books.record("local", lifo.id(), tx("2026-01-02", "TRADE", null, 0L, null, "BROKER", "lifo-first",
                List.of(leg("STOCK", "BUY", "OPEN", "AAPL", null, null, null, 1, 1, "100"))));
        books.record("local", lifo.id(), tx("2026-01-02", "TRADE", null, 0L, null, "BROKER", "lifo-second",
                List.of(leg("STOCK", "BUY", "OPEN", "AAPL", null, null, null, 1, 1, "200"))));
        books.record("local", lifo.id(), tx("2026-02-02", "TRADE", null, 0L, null, "BROKER", "lifo-close",
                List.of(leg("STOCK", "SELL", "CLOSE", "AAPL", null, null, null, 1, 1, "150"))));
        assertThat(books.realizedLots("local", lifo.id(), 2026).getFirst().realizedGainCents()).isEqualTo(-50_00L);
        assertThat(books.lots("local", lifo.id(), false).getFirst().remainingOpenAmountCents()).isEqualTo(100_00L);
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
    void rollRealizesTheCloseAndLinksTheReplacementLotWithExactPremiumCarryover() {
        var account = books.createAccount("local", account("Roll book", "TAXABLE", 100_000_00L));
        books.record("local", account.id(), tx("2026-01-02", "TRADE", null, 100L, null,
                "BROKER", "roll-open", List.of(
                        leg("OPTION", "BUY", "OPEN", "AAPL", "CALL", "250", "2026-08-21", 1, 100, "5.00"))));

        var rolled = books.record("local", account.id(), tx("2026-02-02", "ROLL", null, 200L, null,
                "BROKER", "roll-forward", List.of(
                        leg("OPTION", "SELL", "CLOSE", "AAPL", "CALL", "250", "2026-08-21", 1, 100, "7.00"),
                        leg("OPTION", "BUY", "OPEN", "AAPL", "CALL", "260", "2026-09-18", 1, 100, "9.00"))));

        assertThat(rolled.cashEffectCents()).isEqualTo(-20_200L);
        assertThat(rolled.roll()).isNotNull();
        assertThat(rolled.roll().quantity()).isEqualTo(1);
        assertThat(rolled.roll().closingPremiumCents()).isEqualTo(70_000L);
        assertThat(rolled.roll().openingPremiumCents()).isEqualTo(-90_000L);
        assertThat(rolled.roll().premiumCarryoverCents()).isEqualTo(-20_000L);
        assertThat(rolled.roll().realizedMatchIds()).singleElement();

        assertThat(books.realizedLots("local", account.id(), 2026)).singleElement().satisfies(match -> {
            assertThat(match.realizedGainCents()).isEqualTo(19_812L);
            assertThat(match.holdingTerm()).isEqualTo("SHORT_TERM");
        });
        assertThat(books.lots("local", account.id(), false)).singleElement().satisfies(lot -> {
            assertThat(lot.id()).isEqualTo(rolled.roll().replacementLotId());
            assertThat(lot.strike()).isEqualByComparingTo("260.0000");
            assertThat(lot.expiration()).isEqualTo(LocalDate.parse("2026-09-18"));
            assertThat(lot.remainingOpenAmountCents()).isEqualTo(90_112L);
        });
        assertThat(db.query("SELECT COUNT(*) n FROM portfolio_roll_match", r -> r.lng("n")).getFirst()).isEqualTo(1);
    }

    @Test
    void rollRejectsAReplacementThatChangesThePositionIdentityOrSide() {
        var account = books.createAccount("local", account("Bad roll", "ROTH_IRA", null));
        books.record("local", account.id(), trade("2026-01-02", 0L,
                leg("OPTION", "SELL", "OPEN", "AAPL", "PUT", "200", "2026-08-21", 1, 100, "3.00")));

        assertThatThrownBy(() -> books.record("local", account.id(), tx("2026-02-02", "ROLL", null, 0L,
                null, "MANUAL", null, List.of(
                        leg("OPTION", "BUY", "CLOSE", "AAPL", "PUT", "200", "2026-08-21", 1, 100, "2.00"),
                        leg("OPTION", "BUY", "OPEN", "AAPL", "PUT", "190", "2026-09-18", 1, 100, "4.00")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same long or short side");
        assertThat(books.transactions("local", account.id(), 0, 20)).hasSize(1);
        assertThat(books.lots("local", account.id(), false)).singleElement()
                .satisfies(lot -> assertThat(lot.remainingQuantity()).isEqualTo(1));
    }

    @Test
    void knownIndexRootCannotBeForcedOutOfSection1256AndRollKeepsItsClassification() {
        var account = books.createAccount("local", account("Known index classification", "TAXABLE", 100_000_00L));
        var original = new PortfolioAccountingService.LegInput("OPTION", "BUY", "OPEN", "SPX", "CALL",
                new BigDecimal("5000"), LocalDate.parse("2026-08-21"), 1L, 100,
                new BigDecimal("5.00"), false);
        books.record("local", account.id(), tx("2026-01-02", "TRADE", null, 0L, null,
                "BROKER", "explicit-non-1256-open", List.of(original)));
        var close = new PortfolioAccountingService.LegInput("OPTION", "SELL", "CLOSE", "SPX", "CALL",
                new BigDecimal("5000"), LocalDate.parse("2026-08-21"), 1L, 100,
                new BigDecimal("7.00"), false);
        var replacement = new PortfolioAccountingService.LegInput("OPTION", "BUY", "OPEN", "SPX", "CALL",
                new BigDecimal("5100"), LocalDate.parse("2026-09-18"), 1L, 100,
                new BigDecimal("8.00"), false);

        var rolled = books.record("local", account.id(), tx("2026-02-02", "ROLL", null, 0L, null,
                "BROKER", "explicit-non-1256-roll", List.of(close, replacement)));

        assertThat(rolled.roll()).isNotNull();
        assertThat(rolled.legs()).allMatch(PortfolioAccountingService.LegView::section1256);
        assertThat(books.lots("local", account.id(), false)).singleElement()
                .satisfies(lot -> assertThat(lot.section1256()).isTrue());
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
    void provisionalYearsAndRetirementWrappersNeverPretendToCalculateTaxOwed() {
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
        assertThat(tax.scenarioFederalTaxCents()).isNull();
        assertThat(tax.scenarioStateTaxCents()).isNull();
        assertThat(tax.rules().status()).isEqualTo(TaxRules.Status.PROVISIONAL);
        assertThat(tax.note()).contains("withheld").contains("provisional");

        var roth = books.createAccount("local", account("Roth", "ROTH_IRA", null));
        books.record("local", roth.id(), cash("2026-06-30", "INTEREST", 10_00L, "ri1", null));
        var rothTax = books.taxReport("local", roth.id(), 2026);
        assertThat(rothTax.scenarioTotalTaxCents()).isNull();
        assertThat(rothTax.note()).contains("No user-rate scenario").contains("separate rules");
    }

    @Test
    void reviewedYearUserRatesProduceAScenarioWithoutClaimingTaxOwed() {
        var taxable = books.createAccount("local", new PortfolioAccountingService.AccountInput(
                "Reviewed taxable", "TAXABLE", null, "FIFO", 3200, 1500, 2400, 500, null));
        books.record("local", taxable.id(), tx("2024-01-01", "TRADE", null, 0L, null, "MANUAL", "buy",
                List.of(leg("STOCK", "BUY", "OPEN", "MSFT", null, null, null, 10, 1, "100"))));
        books.record("local", taxable.id(), tx("2025-01-02", "TRADE", null, 0L, null, "MANUAL", "sell",
                List.of(leg("STOCK", "SELL", "CLOSE", "MSFT", null, null, null, 10, 1, "120"))));
        books.record("local", taxable.id(), cash("2025-06-30", "INTEREST", 10_00L, "reviewed-i1", null));

        var tax = books.taxReport("local", taxable.id(), 2025);
        assertThat(tax.rules().status()).isEqualTo(TaxRules.Status.REVIEWED);
        assertThat(tax.scenarioFederalTaxCents()).isEqualTo(32_40L);
        assertThat(tax.scenarioStateTaxCents()).isEqualTo(10_50L);
        assertThat(tax.scenarioTotalTaxCents()).isEqualTo(42_90L);
        assertThat(tax.note()).contains(TaxRules.RULESET_ID).contains("Reconcile every figure");
    }

    @Test
    void unsupportedYearsPreserveRecordedFactsButWithholdAutomation() {
        var taxable = books.createAccount("local", new PortfolioAccountingService.AccountInput(
                "Historical facts", "TAXABLE", null, "FIFO", 3200, 1500, 2400, 500, null));
        books.record("local", taxable.id(), tx("2023-01-02", "TRADE", null, 0L, null, "MANUAL", "old-buy",
                List.of(leg("STOCK", "BUY", "OPEN", "AAPL", null, null, null, 1, 1, "100"))));
        books.record("local", taxable.id(), tx("2024-02-02", "TRADE", null, 0L, null, "MANUAL", "old-sell",
                List.of(leg("STOCK", "SELL", "CLOSE", "AAPL", null, null, null, 1, 1, "120"))));

        var tax = books.taxReport("local", taxable.id(), 2024);
        assertThat(tax.longTermGainCents()).isEqualTo(20_00L);
        assertThat(tax.rules().status()).isEqualTo(TaxRules.Status.UNSUPPORTED);
        assertThat(tax.scenarioTotalTaxCents()).isNull();
        assertThat(tax.note()).contains("Recorded lots and income remain visible").contains("unsupported");
        assertThatThrownBy(() -> books.markSection1256YearEnd("local", taxable.id(), 2024))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unavailable for 2024");
    }

    @Test
    void brokerFormsReconcileAgainstTheBookWithoutRewritingIt() {
        var taxable = books.createAccount("local", account("Broker forms", "TAXABLE", null));
        books.record("local", taxable.id(), tx("2025-01-02", "TRADE", null, 0L, null, "BROKER", "recon-open",
                List.of(leg("STOCK", "BUY", "OPEN", "AAPL", null, null, null, 2, 1, "100"))));
        books.record("local", taxable.id(), tx("2025-02-02", "TRADE", null, 0L, null, "BROKER", "recon-close",
                List.of(leg("STOCK", "SELL", "CLOSE", "AAPL", null, null, null, 2, 1, "110"))));
        books.record("local", taxable.id(), cash("2025-06-30", "INTEREST", 5_00L, "recon-interest", null));

        var saved = books.saveTaxReconciliation("local", taxable.id(), 2025,
                new PortfolioAccountingService.TaxReconciliationInput("DRAFT", "1099-B original",
                        21_00L, null, 3_00L, null, 6_00L, null, null, null,
                        "Broker includes one adjustment not recorded in this book"));
        assertThat(saved.status()).isEqualTo("DRAFT");
        assertThat(saved.shortTermGain().strikeBenchCents()).isEqualTo(20_00L);
        assertThat(saved.shortTermGain().brokerCents()).isEqualTo(21_00L);
        assertThat(saved.shortTermGain().differenceCents()).isEqualTo(1_00L);
        assertThat(saved.interest().differenceCents()).isEqualTo(1_00L);
        assertThat(saved.longTermGain().brokerCents()).isNull();
        assertThat(books.taxReport("local", taxable.id(), 2025).reconciliation()).isEqualTo(saved);
        assertThat(books.realizedLots("local", taxable.id(), 2025)).singleElement()
                .satisfies(match -> assertThat(match.realizedGainCents()).isEqualTo(20_00L));

        var updated = books.saveTaxReconciliation("local", taxable.id(), 2025,
                new PortfolioAccountingService.TaxReconciliationInput("RECONCILED", "Corrected 1099-B",
                        20_00L, 0L, 0L, 0L, 5_00L, 0L, 0L, 0L, "Matched after review"));
        assertThat(updated.status()).isEqualTo("RECONCILED");
        assertThat(updated.shortTermGain().differenceCents()).isZero();
        assertThat(updated.formReference()).isEqualTo("Corrected 1099-B");

        books.clearTaxReconciliation("local", taxable.id(), 2025);
        assertThat(books.taxReport("local", taxable.id(), 2025).reconciliation()).isNull();
    }

    @Test
    void reconciliationRequiresATaxableActiveOwnedAccountAndAtLeastOneAmount() {
        var taxable = books.createAccount("local", account("Taxable reconcile", "TAXABLE", null));
        var empty = new PortfolioAccountingService.TaxReconciliationInput(
                "DRAFT", null, null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> books.saveTaxReconciliation("local", taxable.id(), 2025, empty))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one broker-form amount");
        assertThatThrownBy(() -> books.saveTaxReconciliation("someone-else", taxable.id(), 2025,
                new PortfolioAccountingService.TaxReconciliationInput("DRAFT", null, 1L, null, null,
                        null, null, null, null, null, null)))
                .isInstanceOf(io.liftandshift.strikebench.util.ResourceNotFoundException.class);

        var roth = books.createAccount("local", account("Roth reconcile", "ROTH_IRA", null));
        assertThatThrownBy(() -> books.saveTaxReconciliation("local", roth.id(), 2025,
                new PortfolioAccountingService.TaxReconciliationInput("DRAFT", null, 1L, null, null,
                        null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("only for taxable");

        books.setArchived("local", taxable.id(), true);
        assertThatThrownBy(() -> books.saveTaxReconciliation("local", taxable.id(), 2025,
                new PortfolioAccountingService.TaxReconciliationInput("DRAFT", null, 1L, null, null,
                        null, null, null, null, null, null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("archived");
    }

    @Test
    void reconciliationSaveRollsBackWhenTheReviewedTaxViewCannotBeBuilt() {
        var taxable = books.createAccount("local", account("Atomic reconciliation", "TAXABLE", null));
        books.record("local", taxable.id(), tx("2025-12-01", "TRADE", null, 0L, null, "BROKER", "open-rut",
                List.of(leg("OPTION", "BUY", "OPEN", "RUT", "PUT", "2000", "2026-03-20", 1, 100, "8"))));

        assertThatThrownBy(() -> books.saveTaxReconciliation("local", taxable.id(), 2025,
                new PortfolioAccountingService.TaxReconciliationInput("DRAFT", "1099-B", 1L, null,
                        null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("still need their observed 2025 year-end mark");
        assertThat(db.query("SELECT COUNT(*) n FROM portfolio_tax_reconciliation", r -> r.lng("n")).getFirst())
                .isZero();
    }

    @Test
    void section1256OpenIndexOptionMarksAtYearEndAndReceivesExactSixtyFortyCharacter() {
        var taxable = books.createAccount("local", new PortfolioAccountingService.AccountInput(
                "1256", "TAXABLE", null, "FIFO", 3200, 1500, 2400, 0, null));
        books.record("local", taxable.id(), tx("2025-12-01", "TRADE", null, 0L, null, "BROKER", "spx-open",
                List.of(leg("OPTION", "BUY", "OPEN", "SPX", "CALL", "5000", "2026-03-20", 1, 100, "10"))));
        db.exec("INSERT INTO option_bar(symbol,asof,expiration,strike,opt_type,bid,ask,mark,source,bid_ask_observed,dataset_id) "
                        + "VALUES ('SPX','2025-12-31','2026-03-20',5000,'CALL',11.9,12.1,12.0,'broker-year-end',1,'observed')");

        assertThat(books.markSection1256YearEnd("local", taxable.id(), 2025)).isEqualTo(1);
        var tax = books.taxReport("local", taxable.id(), 2025);

        assertThat(tax.section1256GainCents()).isEqualTo(200_00L);
        assertThat(tax.section1256ShortTermCents()).isEqualTo(80_00L);
        assertThat(tax.section1256LongTermCents()).isEqualTo(120_00L);
        assertThat(tax.shortTermGainCents()).isEqualTo(80_00L);
        assertThat(tax.longTermGainCents()).isEqualTo(120_00L);
        assertThat(books.realizedLots("local", taxable.id(), 2025)).singleElement().satisfies(match -> {
            assertThat(match.section1256()).isTrue();
            assertThat(match.holdingTerm()).isEqualTo("SECTION_1256");
            assertThat(match.realizedGainCents()).isEqualTo(200_00L);
            assertThat(match.economicRealizedGainCents()).isZero();
        });
        assertThat(books.lots("local", taxable.id(), false)).singleElement().satisfies(lot -> {
            assertThat(lot.section1256()).isTrue();
            assertThat(lot.remainingOpenAmountCents()).isEqualTo(1_200_00L);
            assertThat(lot.economicRemainingOpenAmountCents()).isEqualTo(1_000_00L);
        });
        assertThat(books.summary("local", taxable.id()).realizedPnlCents()).isZero();
        assertThat(books.transactions("local", taxable.id(), 0, 20).getFirst()).satisfies(tx -> {
            assertThat(tx.eventType()).isEqualTo("MARK_TO_MARKET");
            assertThat(tx.source()).isEqualTo("CALCULATED");
            assertThat(tx.cashEffectCents()).isZero();
        });
    }

    @Test
    void section1256YearEndKeepsLongAndShortLotsOfTheSameContractDistinct() {
        var taxable = books.createAccount("local", account("1256 opposing lots", "TAXABLE", null));
        books.record("local", taxable.id(), tx("2025-12-01", "TRADE", null, 0L, null,
                "BROKER", "spx-opposing-open", List.of(
                        leg("OPTION", "BUY", "OPEN", "SPX", "CALL", "5000", "2026-03-20", 1, 100, "10"),
                        leg("OPTION", "SELL", "OPEN", "SPX", "CALL", "5000", "2026-03-20", 1, 100, "10"))));
        db.exec("INSERT INTO option_bar(symbol,asof,expiration,strike,opt_type,bid,ask,mark,source,bid_ask_observed,dataset_id) "
                + "VALUES ('SPX','2025-12-31','2026-03-20',5000,'CALL',11.9,12.1,12.0,'broker-year-end',1,'observed')");

        assertThat(books.markSection1256YearEnd("local", taxable.id(), 2025)).isEqualTo(1);
        assertThat(books.realizedLots("local", taxable.id(), 2025))
                .hasSize(2)
                .allMatch(match -> match.economicRealizedGainCents() == 0)
                .extracting(PortfolioAccountingService.RealizedLotView::realizedGainCents)
                .containsExactlyInAnyOrder(200_00L, -200_00L);
        assertThat(books.lots("local", taxable.id(), false))
                .hasSize(2)
                .extracting(PortfolioAccountingService.LotView::side)
                .containsExactlyInAnyOrder("LONG", "SHORT");
    }

    @Test
    void xspVixAndSpxWeeklyAutoClassifyAndMarkAtYearEnd() {
        var taxable = books.createAccount("local", account("Expanded 1256 roots", "TAXABLE", null));
        books.record("local", taxable.id(), tx("2025-12-01", "TRADE", null, 0L, null,
                "BROKER", "expanded-1256-open", List.of(
                        leg("OPTION", "BUY", "OPEN", "XSP", "CALL", "600", "2026-03-20", 1, 100, "10"),
                        leg("OPTION", "BUY", "OPEN", "VIX", "CALL", "25", "2026-03-20", 1, 100, "5"),
                        leg("OPTION", "BUY", "OPEN", "SPXW", "PUT", "5000", "2026-03-20", 1, 100, "8"))));
        db.exec("INSERT INTO option_bar(symbol,asof,expiration,strike,opt_type,bid,ask,mark,source,bid_ask_observed,dataset_id) VALUES "
                + "('XSP','2025-12-31','2026-03-20',600,'CALL',11.9,12.1,12.0,'broker-year-end',1,'observed'),"
                + "('VIX','2025-12-31','2026-03-20',25,'CALL',6.9,7.1,7.0,'broker-year-end',1,'observed'),"
                + "('SPXW','2025-12-31','2026-03-20',5000,'PUT',9.9,10.1,10.0,'broker-year-end',1,'observed')");

        assertThat(books.markSection1256YearEnd("local", taxable.id(), 2025)).isEqualTo(1);
        var tax = books.taxReport("local", taxable.id(), 2025);

        assertThat(tax.section1256GainCents()).isEqualTo(600_00L);
        assertThat(tax.section1256ShortTermCents()).isEqualTo(240_00L);
        assertThat(tax.section1256LongTermCents()).isEqualTo(360_00L);
        assertThat(books.realizedLots("local", taxable.id(), 2025))
                .extracting(PortfolioAccountingService.RealizedLotView::symbol)
                .containsExactlyInAnyOrder("XSP", "VIX", "SPXW");
        assertThat(books.realizedLots("local", taxable.id(), 2025))
                .allMatch(PortfolioAccountingService.RealizedLotView::section1256);
        assertThat(books.lots("local", taxable.id(), false))
                .allMatch(PortfolioAccountingService.LotView::section1256);
    }

    @Test
    void lookAlikeEtfIsNotAutoClassifiedAsSection1256() {
        var taxable = books.createAccount("local", account("Look-alike root", "TAXABLE", null));
        books.record("local", taxable.id(), tx("2026-01-02", "TRADE", null, 0L, null,
                "BROKER", "vixy-open", List.of(
                        leg("OPTION", "BUY", "OPEN", "VIXY", "CALL", "50", "2026-08-21", 1, 100, "2"))));

        assertThat(books.lots("local", taxable.id(), false)).singleElement()
                .satisfies(lot -> assertThat(lot.section1256()).isFalse());
    }

    @Test
    void cashSettledIndexOptionCannotBeRecordedAsPhysicalShareDelivery() {
        var taxable = books.createAccount("local", account("Cash settlement guard", "TAXABLE", null));
        books.record("local", taxable.id(), tx("2026-01-02", "TRADE", null, 0L, null,
                "BROKER", "xsp-short-put", List.of(
                        leg("OPTION", "SELL", "OPEN", "XSP", "PUT", "600", "2026-02-20", 1, 100, "4"))));

        assertThatThrownBy(() -> books.record("local", taxable.id(), tx("2026-02-20", "ASSIGNMENT", null,
                0L, null, "BROKER", "bad-xsp-delivery", List.of(
                        leg("OPTION", "BUY", "CLOSE", "XSP", "PUT", "600", "2026-02-20", 1, 100, "0"),
                        leg("STOCK", "BUY", "OPEN", "XSP", null, null, null, 100, 1, "600")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cash-settled")
                .hasMessageContaining("closing option transaction");
        assertThat(books.transactions("local", taxable.id(), 0, 20))
                .extracting(PortfolioAccountingService.TransactionView::externalRef)
                .containsExactly("xsp-short-put");
    }

    @Test
    void section1256CharacterRoundsOnceOnTheAnnualNetResult() {
        var taxable = books.createAccount("local", account("1256 cent split", "TAXABLE", null));
        books.record("local", taxable.id(), tx("2026-01-02", "TRADE", null, 0L, null, "BROKER", "cent-opens",
                List.of(
                        leg("OPTION", "BUY", "OPEN", "SPX", "CALL", "5000", "2026-12-18", 1, 1, "1.00"),
                        leg("OPTION", "BUY", "OPEN", "SPX", "CALL", "5100", "2026-12-18", 1, 1, "1.00"))));
        books.record("local", taxable.id(), tx("2026-02-02", "TRADE", null, 0L, null, "BROKER", "cent-closes",
                List.of(
                        leg("OPTION", "SELL", "CLOSE", "SPX", "CALL", "5000", "2026-12-18", 1, 1, "1.01"),
                        leg("OPTION", "SELL", "CLOSE", "SPX", "CALL", "5100", "2026-12-18", 1, 1, "1.01"))));

        var tax = books.taxReport("local", taxable.id(), 2026);
        assertThat(tax.section1256GainCents()).isEqualTo(2L);
        assertThat(tax.section1256LongTermCents()).isEqualTo(1L);
        assertThat(tax.section1256ShortTermCents()).isEqualTo(1L);
    }

    @Test
    void section1256YearEndRefusesToInventAMark() {
        var taxable = books.createAccount("local", account("Missing 1256 mark", "TAXABLE", null));
        books.record("local", taxable.id(), tx("2025-12-01", "TRADE", null, 0L, null, "BROKER", "rut-open",
                List.of(leg("OPTION", "BUY", "OPEN", "RUT", "PUT", "2000", "2026-03-20", 1, 100, "8"))));

        assertThatThrownBy(() -> books.markSection1256YearEnd("local", taxable.id(), 2025))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no observed year-end option mark")
                .hasMessageContaining("Import the broker's year-end mark");
        assertThat(books.transactions("local", taxable.id(), 0, 20))
                .extracting(PortfolioAccountingService.TransactionView::eventType)
                .containsExactly("TRADE");
    }

    @Test
    void currentYearTaxReportDoesNotDemandAFutureSection1256Mark() {
        var taxable = books.createAccount("local", account("Current 1256", "TAXABLE", null));
        books.record("local", taxable.id(), tx("2026-07-01", "TRADE", null, 0L, null, "BROKER", "ndx-open",
                List.of(leg("OPTION", "BUY", "OPEN", "NDX", "CALL", "20000", "2027-03-19", 1, 100, "12"))));

        var tax = books.taxReport("local", taxable.id(), 2026);
        assertThat(tax.section1256GainCents()).isZero();
        assertThat(tax.realizedLots()).isEmpty();
    }

    @Test
    void taxYearAndHoldingPeriodUseTheNewYorkMarketCalendar() {
        var taxable = books.createAccount("local", new PortfolioAccountingService.AccountInput(
                "Taxable", "TAXABLE", null, "FIFO", 3000, 1500, 2400, null, null));
        books.record("local", taxable.id(), tx("2024-12-31T23:30:00-05:00", "TRADE", null, 0L,
                null, "BROKER", "late-open", List.of(
                        leg("STOCK", "BUY", "OPEN", "MSFT", null, null, null, 1, 1, "100"))));
        books.record("local", taxable.id(), tx("2025-01-02T10:00:00-05:00", "TRADE", null, 0L,
                null, "BROKER", "year-open", List.of(
                        leg("STOCK", "BUY", "OPEN", "AAPL", null, null, null, 1, 1, "100"))));
        books.record("local", taxable.id(), tx("2025-12-31T23:30:00-05:00", "TRADE", null, 0L,
                null, "BROKER", "year-close", List.of(
                        leg("STOCK", "SELL", "CLOSE", "AAPL", null, null, null, 1, 1, "110"))));
        books.record("local", taxable.id(), cash("2025-12-31T23:45:00-05:00", "INTEREST", 5_00L,
                "year-interest", null));
        books.record("local", taxable.id(), tx("2026-01-01T00:15:00-05:00", "TRADE", null, 0L,
                null, "BROKER", "late-close", List.of(
                        leg("STOCK", "SELL", "CLOSE", "MSFT", null, null, null, 1, 1, "120"))));

        var year2025 = books.taxReport("local", taxable.id(), 2025);
        assertThat(year2025.shortTermGainCents()).isEqualTo(10_00L);
        assertThat(year2025.ordinaryInterestCents()).isEqualTo(5_00L);
        var year2026 = books.taxReport("local", taxable.id(), 2026);
        assertThat(year2026.longTermGainCents()).isEqualTo(20_00L);
        assertThat(year2026.shortTermGainCents()).isZero();
    }

    @Test
    void leapYearHoldingTermUsesTheCalendarAnniversaryNotElapsedDays() {
        var taxable = books.createAccount("local", account("Leap boundary", "TAXABLE", null));
        books.record("local", taxable.id(), tx("2024-01-01", "TRADE", null, 0L, null, "BROKER", "leap-open",
                List.of(leg("STOCK", "BUY", "OPEN", "AAPL", null, null, null, 2, 1, "100"))));
        books.record("local", taxable.id(), tx("2025-01-01", "TRADE", null, 0L, null, "BROKER", "anniversary-close",
                List.of(leg("STOCK", "SELL", "CLOSE", "AAPL", null, null, null, 1, 1, "110"))));
        books.record("local", taxable.id(), tx("2025-01-02", "TRADE", null, 0L, null, "BROKER", "past-anniversary-close",
                List.of(leg("STOCK", "SELL", "CLOSE", "AAPL", null, null, null, 1, 1, "120"))));

        assertThat(books.realizedLots("local", taxable.id(), 2025))
                .extracting(PortfolioAccountingService.RealizedLotView::holdingTerm)
                .containsExactly("SHORT_TERM", "LONG_TERM");
    }

    @Test
    void realizedLossesDoNotBecomeAStandaloneTaxRefundEstimate() {
        var taxable = books.createAccount("local", new PortfolioAccountingService.AccountInput(
                "Taxable loss", "TAXABLE", null, "FIFO", 3200, 1500, 2400, 500, null));
        books.record("local", taxable.id(), tx("2025-01-02", "TRADE", null, 0L, null, "BROKER", "loss-buy",
                List.of(leg("STOCK", "BUY", "OPEN", "AAPL", null, null, null, 10, 1, "100"))));
        books.record("local", taxable.id(), tx("2025-02-02", "TRADE", null, 0L, null, "BROKER", "loss-sell",
                List.of(leg("STOCK", "SELL", "CLOSE", "AAPL", null, null, null, 10, 1, "80"))));

        var tax = books.taxReport("local", taxable.id(), 2025);
        assertThat(tax.shortTermGainCents()).isEqualTo(-200_00L);
        assertThat(tax.scenarioFederalTaxCents()).isNull();
        assertThat(tax.scenarioStateTaxCents()).isNull();
        assertThat(tax.scenarioTotalTaxCents()).isNull();
        assertThat(tax.note()).contains("loss netting").contains("carryovers");
    }

    @Test
    void taxableWashSaleDisallowsLossProRataAndCarriesBasisAndHoldingPeriod() {
        books = new PortfolioAccountingService(db, CLOCK, new MarksSource() {
            @Override public Optional<BigDecimal> underlyingMark(String symbol) { return Optional.empty(); }
            @Override public Optional<LegMark> legMark(String symbol, Leg leg) {
                return "AAPL".equals(symbol) ? Optional.of(mark("95.00", "95.10")) : Optional.empty();
            }
        });
        var taxable = books.createAccount("local", account("Wash sale", "TAXABLE", null));
        books.record("local", taxable.id(), tx("2025-01-02", "TRADE", null, 0L, null, "BROKER", "wash-open",
                List.of(leg("STOCK", "BUY", "OPEN", "AAPL", null, null, null, 10, 1, "100"))));
        books.record("local", taxable.id(), tx("2025-02-02", "TRADE", null, 0L, null, "BROKER", "wash-loss",
                List.of(leg("STOCK", "SELL", "CLOSE", "AAPL", null, null, null, 10, 1, "80"))));
        books.record("local", taxable.id(), tx("2025-02-15", "TRADE", null, 0L, null, "BROKER", "wash-rebuy",
                List.of(leg("STOCK", "BUY", "OPEN", "AAPL", null, null, null, 6, 1, "90"))));
        books.record("local", taxable.id(), tx("2025-03-10", "TRADE", null, 0L, null, "BROKER", "outside-window",
                List.of(leg("STOCK", "BUY", "OPEN", "AAPL", null, null, null, 4, 1, "90"))));

        var realized = books.realizedLots("local", taxable.id(), 2025).getFirst();
        assertThat(realized.realizedGainCents()).isEqualTo(-200_00L);
        assertThat(realized.economicRealizedGainCents()).isEqualTo(-200_00L);
        assertThat(realized.washSaleAdjustmentCents()).isEqualTo(120_00L);
        var open = books.lots("local", taxable.id(), false);
        assertThat(open).filteredOn(l -> l.remainingQuantity() == 6).singleElement().satisfies(lot -> {
            assertThat(lot.remainingOpenAmountCents()).isEqualTo(660_00L);
            assertThat(lot.economicRemainingOpenAmountCents()).isEqualTo(540_00L);
            assertThat(lot.openedAt()).startsWith("2025-01-02");
        });
        assertThat(open).filteredOn(l -> l.remainingQuantity() == 4).singleElement().satisfies(lot -> {
            assertThat(lot.remainingOpenAmountCents()).isEqualTo(360_00L);
            assertThat(lot.economicRemainingOpenAmountCents()).isEqualTo(360_00L);
            assertThat(lot.openedAt()).startsWith("2025-03-10");
        });
        var tax = books.taxReport("local", taxable.id(), 2025);
        assertThat(tax.shortTermGainCents()).isEqualTo(-80_00L);
        assertThat(tax.washSaleAdjustmentsCents()).isEqualTo(120_00L);
        var economic = books.summary("local", taxable.id());
        assertThat(economic.realizedPnlCents()).isEqualTo(-200_00L);
        assertThat(economic.unrealizedPnlCents()).isEqualTo(50_00L);
    }

    @Test
    void retirementWrapperSuppressesWashSaleAdjustmentAndBasisCarry() {
        var roth = books.createAccount("local", account("Roth wash", "ROTH_IRA", null));
        books.record("local", roth.id(), tx("2025-01-02", "TRADE", null, 0L, null, "BROKER", "roth-open",
                List.of(leg("STOCK", "BUY", "OPEN", "AAPL", null, null, null, 1, 1, "100"))));
        books.record("local", roth.id(), tx("2025-02-02", "TRADE", null, 0L, null, "BROKER", "roth-loss",
                List.of(leg("STOCK", "SELL", "CLOSE", "AAPL", null, null, null, 1, 1, "80"))));
        books.record("local", roth.id(), tx("2025-02-15", "TRADE", null, 0L, null, "BROKER", "roth-rebuy",
                List.of(leg("STOCK", "BUY", "OPEN", "AAPL", null, null, null, 1, 1, "90"))));

        assertThat(books.realizedLots("local", roth.id(), 2025).getFirst().washSaleAdjustmentCents()).isZero();
        assertThat(books.lots("local", roth.id(), false)).singleElement().satisfies(lot -> {
            assertThat(lot.remainingOpenAmountCents()).isEqualTo(90_00L);
            assertThat(lot.openedAt()).startsWith("2025-02-15");
        });
        assertThat(books.taxReport("local", roth.id(), 2025).washSaleAdjustmentsCents()).isZero();
    }

    @Test
    void optionWashReplacementRequiresTheSameRightStrikeAndExpiration() {
        var taxable = books.createAccount("local", account("Option wash", "TAXABLE", null));
        books.record("local", taxable.id(), tx("2025-01-02", "TRADE", null, 0L, null, "BROKER", "option-open",
                List.of(leg("OPTION", "BUY", "OPEN", "SPY", "CALL", "500", "2025-06-20", 1, 100, "10"))));
        books.record("local", taxable.id(), tx("2025-02-02", "TRADE", null, 0L, null, "BROKER", "option-loss",
                List.of(leg("OPTION", "SELL", "CLOSE", "SPY", "CALL", "500", "2025-06-20", 1, 100, "5"))));
        books.record("local", taxable.id(), tx("2025-02-10", "TRADE", null, 0L, null, "BROKER", "different-strike",
                List.of(leg("OPTION", "BUY", "OPEN", "SPY", "CALL", "505", "2025-06-20", 1, 100, "6"))));
        assertThat(books.realizedLots("local", taxable.id(), 2025).getFirst().washSaleAdjustmentCents()).isZero();
        books.record("local", taxable.id(), tx("2025-02-12", "TRADE", null, 0L, null, "BROKER", "exact-option",
                List.of(leg("OPTION", "BUY", "OPEN", "SPY", "CALL", "500", "2025-06-20", 1, 100, "6"))));

        assertThat(books.realizedLots("local", taxable.id(), 2025).getFirst().washSaleAdjustmentCents())
                .isEqualTo(500_00L);
    }

    @Test
    void unreviewedYearKeepsEconomicFactsButDoesNotMutateTaxBasisFromAWashModel() {
        var taxable = books.createAccount("local", account("Provisional wash", "TAXABLE", null));
        books.record("local", taxable.id(), tx("2026-01-02", "TRADE", null, 0L, null, "BROKER", "provisional-open",
                List.of(leg("STOCK", "BUY", "OPEN", "AAPL", null, null, null, 1, 1, "100"))));
        books.record("local", taxable.id(), tx("2026-02-02", "TRADE", null, 0L, null, "BROKER", "provisional-loss",
                List.of(leg("STOCK", "SELL", "CLOSE", "AAPL", null, null, null, 1, 1, "80"))));
        books.record("local", taxable.id(), tx("2026-02-15", "TRADE", null, 0L, null, "BROKER", "provisional-rebuy",
                List.of(leg("STOCK", "BUY", "OPEN", "AAPL", null, null, null, 1, 1, "90"))));

        assertThat(books.realizedLots("local", taxable.id(), 2026)).singleElement().satisfies(match -> {
            assertThat(match.economicRealizedGainCents()).isEqualTo(-20_00L);
            assertThat(match.washSaleAdjustmentCents()).isZero();
        });
        assertThat(books.lots("local", taxable.id(), false)).singleElement().satisfies(lot -> {
            assertThat(lot.remainingOpenAmountCents()).isEqualTo(90_00L);
            assertThat(lot.economicRemainingOpenAmountCents()).isEqualTo(90_00L);
            assertThat(lot.openedAt()).startsWith("2026-02-15");
        });
        assertThat(books.taxReport("local", taxable.id(), 2026).note())
                .contains("ruleset is provisional").contains("user-rate scenario is withheld");
    }

    @Test
    void accountTaxWrapperCannotBeRewrittenAfterCreation() {
        var taxable = books.createAccount("local", account("Taxable", "TAXABLE", 10_000_00L));

        assertThatThrownBy(() -> books.updateAccount("local", taxable.id(), account("Taxable", "ROTH_IRA", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fixed after creation");
        assertThat(books.account("local", taxable.id()).accountType()).isEqualTo("TAXABLE");
        assertThat(books.transactions("local", taxable.id(), 0, 20)).singleElement()
                .satisfies(tx -> assertThat(tx.eventType()).isEqualTo("OPENING_BALANCE"));
    }

    @Test
    void fullSettingsUpdateCanClearEstimateRatesAndBrokerWithoutTouchingLots() {
        var taxable = books.createAccount("local", new PortfolioAccountingService.AccountInput(
                "Taxable", "TAXABLE", "Example broker", "FIFO", 3200, 1500, 2400, 500, null));
        books.record("local", taxable.id(), trade("2026-01-02", 0L,
                leg("STOCK", "BUY", "OPEN", "AAPL", null, null, null, 1, 1, "100")));

        var cleared = books.updateAccount("local", taxable.id(), new PortfolioAccountingService.AccountInput(
                "Taxable", "TAXABLE", "", "FIFO", null, null, null, null, null));

        assertThat(cleared.broker()).isNull();
        assertThat(cleared.shortTermTaxRateBps()).isNull();
        assertThat(cleared.longTermTaxRateBps()).isNull();
        assertThat(cleared.ordinaryTaxRateBps()).isNull();
        assertThat(cleared.stateTaxRateBps()).isNull();
        assertThat(books.lots("local", taxable.id(), false)).singleElement();
    }

    @Test
    void modifiedDietzSeparatesInvestmentGainFromExternalFlows() {
        var account = books.createAccount("local", account("401k", "TRADITIONAL_401K", null));
        books.addValuation("local", account.id(), valuation("2026-01-01", 100_000_00L));
        books.record("local", account.id(), cash("2026-07-02", "DEPOSIT", 10_000_00L, "contribution", null));
        books.addValuation("local", account.id(), valuation("2026-07-13", 121_000_00L));

        var performance = books.performance("local", account.id());
        assertThat(performance.netExternalFlowCents()).isEqualTo(10_000_00L);
        assertThat(performance.investmentGainCents()).isEqualTo(11_000_00L);
        assertThat(performance.modifiedDietzReturn()).isCloseTo(0.10938, within(0.0002));
        assertThat(performance.note()).contains("Modified Dietz");
    }

    @Test
    void openingBalanceIsBookBaselineRatherThanAContributionInsideHistoricalPerformance() {
        var account = books.createAccount("local", account("Taxable", "TAXABLE", 100_000_00L));
        books.addValuation("local", account.id(), valuation("2026-06-01", 100_000_00L));
        books.addValuation("local", account.id(), valuation("2026-07-13", 101_000_00L));

        var performance = books.performance("local", account.id());
        assertThat(performance.netExternalFlowCents()).isZero();
        assertThat(performance.investmentGainCents()).isEqualTo(1_000_00L);
        assertThat(performance.modifiedDietzReturn()).isCloseTo(0.01, within(0.00001));
        assertThat(books.transactions("local", account.id(), 0, 20)).singleElement()
                .satisfies(tx -> assertThat(tx.eventType()).isEqualTo("OPENING_BALANCE"));
    }

    @Test
    void performanceSeparatesTwrXirrDrawdownAndObservedSpyBenchmark() {
        var account = books.createAccount("local", account("Performance book", "TAXABLE", null));
        books.addValuation("local", account.id(), valuationAt("2026-01-02T16:00:00Z", 100_000_00L));
        books.addValuation("local", account.id(), valuationAt("2026-04-01T14:00:00Z", 110_000_00L));
        books.record("local", account.id(), cash("2026-04-01T15:00:00Z", "DEPOSIT", 100_000_00L,
                "large-contribution", null));
        books.addValuation("local", account.id(), valuationAt("2026-04-01T16:00:00Z", 210_000_00L));
        books.addValuation("local", account.id(), valuationAt("2026-07-01T16:00:00Z", 189_000_00L));
        db.exec("INSERT INTO dataset(id,name,kind,user_id) VALUES ('performance-demo','Generated control','FIXTURE_DEMO','local')");
        db.exec("INSERT INTO underlying_bar(symbol,d,close,source,observed,dataset_id,adjusted,quality_rank) VALUES "
                + "('SPY','2026-01-02',100,'observed-feed',1,'observed',1,10),"
                + "('SPY','2026-04-01',105,'observed-feed',1,'observed',1,10),"
                + "('SPY','2026-07-01',110,'observed-feed',1,'observed',1,10),"
                + "('SPY','2026-01-02',1000,'fixture',0,'performance-demo',1,99),"
                + "('SPY','2026-07-01',1,'fixture',0,'performance-demo',1,99)");

        var performance = books.performance("local", account.id());

        assertThat(performance.timeWeightedReturn()).isCloseTo(-0.01, within(0.000001));
        assertThat(performance.moneyWeightedIrr()).isCloseTo(-0.1418, within(0.001));
        assertThat(performance.maxDrawdown()).isCloseTo(-0.10, within(0.000001));
        assertThat(performance.drawdownPeakAt()).isEqualTo("2026-04-01T14:00Z");
        assertThat(performance.drawdownTroughAt()).isEqualTo("2026-07-01T16:00Z");
        assertThat(performance.benchmark().symbol()).isEqualTo("SPY");
        assertThat(performance.benchmark().returnValue()).isCloseTo(0.10, within(0.000001));
        assertThat(performance.benchmark().points()).hasSize(4);
        assertThat(performance.benchmark().points().getLast().normalizedBenchmarkValueCents())
                .isEqualTo(110_000_00L);
        assertThat(performance.benchmark().source()).isEqualTo("observed-feed");
        assertThat(performance.note()).contains("TWR", "IRR", "Partial valuations never enter");
    }

    @Test
    void twrIsWithheldWhenAnExternalFlowHasNoSameDayAfterFlowValuation() {
        var account = books.createAccount("local", account("Incomplete TWR", "TAXABLE", null));
        books.addValuation("local", account.id(), valuationAt("2026-01-02T16:00:00Z", 100_000_00L));
        books.record("local", account.id(), cash("2026-03-01T16:00:00Z", "DEPOSIT", 10_000_00L,
                "unbounded-flow", null));
        books.addValuation("local", account.id(), valuationAt("2026-07-01T16:00:00Z", 121_000_00L));

        var performance = books.performance("local", account.id());
        assertThat(performance.timeWeightedReturn()).isNull();
        assertThat(performance.moneyWeightedIrr()).isNotNull();
        assertThat(performance.modifiedDietzReturn()).isNotNull();
    }

    @Test
    void calculatedValuationsBuildHistoryWithoutManualSnapshotsAndLabelPartialMarks() {
        AtomicReference<BigDecimal> aaplBid = new AtomicReference<>(new BigDecimal("109.90"));
        MarksSource observed = new MarksSource() {
            @Override public Optional<BigDecimal> underlyingMark(String symbol) { return Optional.empty(); }
            @Override public Optional<LegMark> legMark(String symbol, Leg leg) {
                if (!"AAPL".equals(symbol)) return Optional.empty();
                return Optional.of(mark(aaplBid.get().toPlainString(), aaplBid.get().add(new BigDecimal("0.10")).toPlainString()));
            }
        };
        books = new PortfolioAccountingService(db, CLOCK, observed);
        var complete = books.createAccount("owner-a", account("Complete", "TAXABLE", 10_000_00L));
        books.record("owner-a", complete.id(), trade("2026-01-02", 0L,
                leg("STOCK", "BUY", "OPEN", "AAPL", null, null, null, 10, 1, "100")));
        var partial = books.createAccount("owner-b", account("Partial", "ROTH_IRA", 10_000_00L));
        books.record("owner-b", partial.id(), trade("2026-01-02", 0L,
                leg("STOCK", "BUY", "OPEN", "NVDA", null, null, null, 2, 1, "200")));

        var first = books.recordCalculatedValuation("owner-a", complete.id(), Instant.parse("2026-07-13T15:00:00Z"));
        aaplBid.set(new BigDecimal("119.90"));
        var second = books.recordCalculatedValuation("owner-a", complete.id(), Instant.parse("2026-07-13T16:00:00Z"));
        var partialValue = books.recordCalculatedValuation("owner-b", partial.id(), Instant.parse("2026-07-13T16:00:00Z"));

        assertThat(first.source()).isEqualTo("CALCULATED");
        assertThat(first.complete()).isTrue();
        assertThat(first.totalValueCents()).isEqualTo(10_099_00L);
        assertThat(second.totalValueCents()).isEqualTo(10_199_00L);
        assertThat(books.performance("owner-a", complete.id()).valuations())
                .hasSize(2).allMatch(v -> "CALCULATED".equals(v.source()) && v.complete());
        assertThat(partialValue.complete()).isFalse();
        assertThat(partialValue.missingMarks()).containsExactly("NVDA shares");
        assertThat(partialValue.cashCents()).isEqualTo(9_600_00L);
        assertThat(partialValue.securitiesValueCents()).isZero();
        assertThat(partialValue.totalValueCents()).isEqualTo(9_600_00L);
        assertThat(partialValue.notes()).contains("Known subtotal only").contains("NVDA shares");

        var run = books.recordActiveCalculatedValuations();
        assertThat(run.accounts()).isEqualTo(2);
        assertThat(run.complete()).isEqualTo(1);
        assertThat(run.partial()).isEqualTo(1);
        assertThat(run.failed()).isZero();
    }

    @Test
    void futureActivityAndValuationsAreRejectedBeforeTheyCanDistortAccounting() {
        var account = books.createAccount("local", account("Taxable", "TAXABLE", null));

        books.record("local", account.id(), cash(
                "2026-07-13", "DEPOSIT", 5_00L, "same-day-cash", null));

        assertThatThrownBy(() -> books.record("local", account.id(), cash(
                "2026-07-14", "DEPOSIT", 10_00L, "future-cash", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be in the future");
        assertThatThrownBy(() -> books.addValuation("local", account.id(), valuation(
                "2026-07-14", 10_00L)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be in the future");

        assertThat(books.transactions("local", account.id(), 0, 20))
                .extracting(PortfolioAccountingService.TransactionView::externalRef)
                .containsExactly("same-day-cash");
        assertThat(books.performance("local", account.id()).valuations()).isEmpty();
    }

    @Test
    void zeroAndOverflowingMoneyFailBeforeAnyAccountingRowIsWritten() {
        var account = books.createAccount("local", account("Taxable", "TAXABLE", null));

        assertThatThrownBy(() -> books.record("local", account.id(), cash(
                "2026-07-13", "DEPOSIT", 0, "zero-cash", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be zero");
        assertThatThrownBy(() -> books.record("local", account.id(), cash(
                "2026-07-13", "DEPOSIT", Long.MIN_VALUE, "overflow-cash", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("supported range");
        assertThatThrownBy(() -> books.addValuation("local", account.id(),
                new PortfolioAccountingService.ValuationInput("2026-07-13", Long.MAX_VALUE,
                        Long.MAX_VALUE, 1L, "MANUAL", null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("supported range");

        assertThat(books.transactions("local", account.id(), 0, 20)).isEmpty();
        assertThat(books.performance("local", account.id()).valuations()).isEmpty();
    }

    @Test
    void archivedAccountBlocksSettingsTransactionsAndValuationsUntilRestore() {
        var account = books.createAccount("local", account("Taxable", "TAXABLE", 10_000_00L));
        books.setArchived("local", account.id(), true);

        assertThatThrownBy(() -> books.updateAccount("local", account.id(), account("Renamed", "TAXABLE", null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("archived and read-only");
        assertThatThrownBy(() -> books.record("local", account.id(), cash(
                "2026-07-13", "INTEREST", 5_00L, "archived-interest", null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("archived and read-only");
        assertThatThrownBy(() -> books.addValuation("local", account.id(), valuation(
                "2026-07-13", 10_005_00L)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("archived and read-only");

        books.setArchived("local", account.id(), false);
        books.addValuation("local", account.id(), valuation("2026-07-13", 10_000_00L));
        assertThat(books.performance("local", account.id()).valuations()).hasSize(1);
    }

    @Test
    void duplicateBrokerReferenceIsRejectedWithoutPartialLots() {
        var account = books.createAccount("local", account("Brokerage", "TAXABLE", null));
        var open = tx("2026-01-02", "TRADE", null, 0L, null, "BROKER", "ORDER-7",
                List.of(leg("STOCK", "BUY", "OPEN", "AMD", null, null, null, 5, 1, "100")));
        books.record("local", account.id(), open);
        assertThatThrownBy(() -> books.record("local", account.id(), open))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already recorded");
        assertThat(books.transactionsByReference("local", account.id(), "BROKER", "ORDER-7"))
                .singleElement().satisfies(tx -> {
                    assertThat(tx.externalRef()).isEqualTo("ORDER-7");
                    assertThat(tx.source()).isEqualTo("BROKER");
                });
        assertThat(books.transactions("local", account.id(), 0, 20)).hasSize(1);
        assertThat(books.lots("local", account.id(), false)).singleElement()
                .satisfies(lot -> assertThat(lot.remainingQuantity()).isEqualTo(5));
    }

    @Test
    void proposedOrUnclassifiedMarketActivityCannotEnterTheTrackedLedger() {
        var account = books.createAccount("local", account("Brokerage", "TAXABLE", null));
        var factualLegs = List.of(leg("STOCK", "BUY", "OPEN", "AMD", null,
                null, null, 5, 1, "100"));
        var proposed = new PortfolioAccountingService.TransactionInput("2026-01-02", "TRADE",
                null, 0L, null, "MANUAL", null, null, factualLegs, "PROPOSED");
        var missing = new PortfolioAccountingService.TransactionInput("2026-01-02", "TRADE",
                null, 0L, null, "MANUAL", null, null, factualLegs, null);
        var modeled = new PortfolioAccountingService.TransactionInput("2026-01-02", "TRADE",
                null, 0L, null, "MANUAL", null, null, factualLegs, "MODELED");

        assertThatThrownBy(() -> books.record("local", account.id(), proposed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fill nature must be one of EXECUTED, MODELED, NOT_APPLICABLE");
        assertThatThrownBy(() -> books.record("local", account.id(), missing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fill nature is required");
        assertThatThrownBy(() -> books.record("local", account.id(), modeled))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fillNature=EXECUTED");
        assertThat(books.transactions("local", account.id(), 0, 20)).isEmpty();
        assertThat(books.lots("local", account.id(), false)).isEmpty();
    }

    @Test
    void brokerSourceRequiresAStableReferenceForIdempotency() {
        var account = books.createAccount("local", account("Brokerage", "TAXABLE", null));
        var missingReference = tx("2026-01-02", "TRADE", null, 0L, null, "BROKER", null,
                List.of(leg("STOCK", "BUY", "OPEN", "AMD", null, null, null, 5, 1, "100")));

        assertThatThrownBy(() -> books.record("local", account.id(), missingReference))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stable order or statement reference");
        assertThat(books.transactions("local", account.id(), 0, 20)).isEmpty();
    }

    @Test
    void cashRemainsCapitalAllocationWithoutBecomingMarketExposure() {
        var account = books.createAccount("local", account("Cash only", "TAXABLE", 10_000_000L));

        var allocation = books.summary("local", account.id()).allocation();

        assertThat(allocation.longExposureCents()).isZero();
        assertThat(allocation.shortExposureCents()).isZero();
        assertThat(allocation.grossExposureCents()).isZero();
        assertThat(allocation.netExposureCents()).isZero();
        assertThat(allocation.byAssetClass()).singleElement().satisfies(row -> {
            assertThat(row.label()).isEqualTo("Cash");
            assertThat(row.netExposureCents()).isEqualTo(10_000_000L);
            assertThat(row.percentOfTotal()).isEqualTo(1.0);
        });
        assertThat(allocation.bySector()).isEmpty();
        assertThat(allocation.bySymbol()).isEmpty();
        assertThat(allocation.byDirection()).allSatisfy(row -> {
            assertThat(row.grossExposureCents()).isZero();
            assertThat(row.percentOfTotal()).isNull();
        });
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
        assertThat(summary.allocation().longExposureCents()).isEqualTo(109_900L);
        assertThat(summary.allocation().shortExposureCents()).isEqualTo(15_000L);
        assertThat(summary.allocation().grossExposureCents()).isEqualTo(124_900L);
        assertThat(summary.allocation().netExposureCents()).isEqualTo(94_900L);
        assertThat(summary.allocation().byAssetClass())
                .extracting(PortfolioAccountingService.ExposureRow::label,
                        PortfolioAccountingService.ExposureRow::longExposureCents,
                        PortfolioAccountingService.ExposureRow::shortExposureCents)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("Cash", 9_920_000L, 0L),
                        org.assertj.core.groups.Tuple.tuple("Stocks", 109_900L, 0L),
                        org.assertj.core.groups.Tuple.tuple("Options", 0L, 15_000L));
        assertThat(summary.allocation().bySector())
                .extracting(PortfolioAccountingService.ExposureRow::label,
                        PortfolioAccountingService.ExposureRow::netExposureCents)
                .contains(org.assertj.core.groups.Tuple.tuple("Technology", 109_900L),
                        org.assertj.core.groups.Tuple.tuple("Index & macro ETFs", -15_000L))
                .doesNotContain(org.assertj.core.groups.Tuple.tuple("Cash", 9_920_000L));
        assertThat(summary.allocation().byDirection())
                .extracting(PortfolioAccountingService.ExposureRow::label,
                        PortfolioAccountingService.ExposureRow::grossExposureCents,
                        PortfolioAccountingService.ExposureRow::netExposureCents)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Long", 109_900L, 109_900L),
                        org.assertj.core.groups.Tuple.tuple("Short", 15_000L, -15_000L));
        assertThat(summary.valuationBasis()).contains("executable closing sides");
    }

    @Test
    void summaryNeverMixesPreTradeLotsWithPostTradeCash() {
        AtomicBoolean injectOnce = new AtomicBoolean(true);
        AtomicReference<PortfolioAccountingService> service = new AtomicReference<>();
        AtomicReference<String> accountId = new AtomicReference<>();
        MarksSource marks = new MarksSource() {
            @Override public Optional<BigDecimal> underlyingMark(String symbol) { return Optional.empty(); }
            @Override public Optional<LegMark> legMark(String symbol, Leg leg) {
                if (injectOnce.compareAndSet(true, false)) {
                    service.get().record("local", accountId.get(), tx("2026-01-03", "TRADE", null, 0L,
                            null, "BROKER", "concurrent-second-fill", List.of(
                                    leg("STOCK", "BUY", "OPEN", "AAPL", null, null, null, 1, 1, "100"))));
                }
                return Optional.of(mark("109.90", "110.00"));
            }
        };
        books = new PortfolioAccountingService(db, CLOCK, marks);
        service.set(books);
        var account = books.createAccount("local", account("Taxable", "TAXABLE", 1_000_00L));
        accountId.set(account.id());
        books.record("local", account.id(), tx("2026-01-02", "TRADE", null, 0L,
                null, "BROKER", "first-fill", List.of(
                        leg("STOCK", "BUY", "OPEN", "AAPL", null, null, null, 1, 1, "100"))));

        var during = books.summary("local", account.id());
        assertThat(during.bookCashCents()).isEqualTo(90_000L);
        assertThat(during.positions()).singleElement()
                .satisfies(position -> assertThat(position.quantity()).isEqualTo(1));
        assertThat(during.totalValueCents()).isEqualTo(100_990L);

        var after = books.summary("local", account.id());
        assertThat(after.bookCashCents()).isEqualTo(80_000L);
        assertThat(after.positions()).singleElement()
                .satisfies(position -> assertThat(position.quantity()).isEqualTo(2));
        assertThat(after.totalValueCents()).isEqualTo(101_980L);
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
    void generatedMarksNeverValueAnExternalTrackedAccount() {
        MarksSource generated = new MarksSource() {
            @Override public Optional<BigDecimal> underlyingMark(String symbol) { return Optional.of(new BigDecimal("255.30")); }
            @Override public Optional<LegMark> legMark(String symbol, Leg leg) {
                return Optional.of(new LegMark(new BigDecimal("255.20"), new BigDecimal("255.30"),
                        new BigDecimal("255.25"), null, Freshness.FIXTURE, null, null, null, null,
                        DataEvidence.of("fixture", Freshness.FIXTURE)));
            }
        };
        books = new PortfolioAccountingService(db, CLOCK, generated);
        var account = books.createAccount("local", account("Taxable", "TAXABLE", 1_000_000L));
        books.record("local", account.id(), trade("2026-01-02", 0L,
                leg("STOCK", "BUY", "OPEN", "AAPL", null, null, null, 2, 1, "100")));

        var summary = books.summary("local", account.id());
        assertThat(summary.complete()).isFalse();
        assertThat(summary.totalValueCents()).isNull();
        assertThat(summary.positions()).singleElement()
                .satisfies(position -> assertThat(position.liquidationValueCents()).isNull());
        assertThat(summary.valuationBasis()).contains("Demo, simulated, and modeled marks are never applied");
    }

    @Test
    void staleObservedMarksAreNotPresentedAsExecutableLiquidationValue() {
        MarksSource stale = new MarksSource() {
            @Override public Optional<BigDecimal> underlyingMark(String symbol) { return Optional.of(new BigDecimal("110")); }
            @Override public Optional<LegMark> legMark(String symbol, Leg leg) {
                return Optional.of(new LegMark(new BigDecimal("109.90"), new BigDecimal("110.00"),
                        new BigDecimal("109.95"), null, Freshness.STALE, null, null, null, null,
                        DataEvidence.of("cboe", Freshness.STALE)));
            }
        };
        books = new PortfolioAccountingService(db, CLOCK, stale);
        var account = books.createAccount("local", account("Taxable", "TAXABLE", 1_000_000L));
        books.record("local", account.id(), trade("2026-01-02", 0L,
                leg("STOCK", "BUY", "OPEN", "AAPL", null, null, null, 2, 1, "100")));

        var summary = books.summary("local", account.id());
        assertThat(summary.complete()).isFalse();
        assertThat(summary.totalValueCents()).isNull();
        assertThat(summary.missingMarks()).containsExactly("AAPL shares");
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

    @Test
    void adjustedContractAssignmentUsesItsRecordedDeliverableMultiplier() {
        var account = books.createAccount("local", account("Adjusted assignment", "TAXABLE", null));
        books.record("local", account.id(), tx("2026-01-02", "TRADE", null, 0L, null, "BROKER", "adjusted-put-open",
                List.of(leg("OPTION", "SELL", "OPEN", "XYZ", "PUT", "20", "2026-02-20", 1, 50, "2"))));

        var assigned = books.record("local", account.id(), tx("2026-02-20", "ASSIGNMENT", null, 0L, null,
                "BROKER", "adjusted-put-assignment", List.of(
                        leg("OPTION", "BUY", "CLOSE", "XYZ", "PUT", "20", "2026-02-20", 1, 50, "0"),
                        leg("STOCK", "BUY", "OPEN", "XYZ", null, null, null, 50, 1, "20"))));

        assertThat(assigned.cashEffectCents()).isEqualTo(-100_000L);
        assertThat(books.lots("local", account.id(), false)).singleElement().satisfies(lot -> {
            assertThat(lot.instrumentType()).isEqualTo("STOCK");
            assertThat(lot.remainingQuantity()).isEqualTo(50);
            assertThat(lot.remainingOpenAmountCents()).isEqualTo(90_000L);
        });
    }

    @Test
    void assignmentAndExerciseCanCreateTheShortOrCoveringSharePositionActuallyDelivered() {
        var assigned = books.createAccount("local", account("Uncovered assignment", "TAXABLE", null));
        books.record("local", assigned.id(), tx("2026-01-02", "TRADE", null, 0L, null, "BROKER", "short-call",
                List.of(leg("OPTION", "SELL", "OPEN", "AAPL", "CALL", "100", "2026-02-20", 1, 100, "4"))));
        books.record("local", assigned.id(), tx("2026-02-20", "ASSIGNMENT", null, 0L, null, "BROKER", "short-call-assigned",
                List.of(
                        leg("OPTION", "BUY", "CLOSE", "AAPL", "CALL", "100", "2026-02-20", 1, 100, "0"),
                        leg("STOCK", "SELL", "OPEN", "AAPL", null, null, null, 100, 1, "100"))));
        assertThat(books.lots("local", assigned.id(), false)).singleElement().satisfies(lot -> {
            assertThat(lot.instrumentType()).isEqualTo("STOCK");
            assertThat(lot.side()).isEqualTo("SHORT");
            assertThat(lot.remainingOpenAmountCents()).isEqualTo(1_040_000L);
        });

        var exercised = books.createAccount("local", account("Put exercise", "TAXABLE", null));
        books.record("local", exercised.id(), tx("2026-01-02", "TRADE", null, 0L, null, "BROKER", "long-put",
                List.of(leg("OPTION", "BUY", "OPEN", "MSFT", "PUT", "300", "2026-02-20", 1, 100, "5"))));
        books.record("local", exercised.id(), tx("2026-02-20", "EXERCISE", null, 0L, null, "BROKER", "long-put-exercised",
                List.of(
                        leg("OPTION", "SELL", "CLOSE", "MSFT", "PUT", "300", "2026-02-20", 1, 100, "0"),
                        leg("STOCK", "SELL", "OPEN", "MSFT", null, null, null, 100, 1, "300"))));
        assertThat(books.lots("local", exercised.id(), false)).singleElement().satisfies(lot -> {
            assertThat(lot.side()).isEqualTo("SHORT");
            assertThat(lot.remainingOpenAmountCents()).isEqualTo(2_950_000L);
        });
    }

    @Test
    void batchHistoryIsAppliedChronologicallyAndLaterBackdatingIsRefused() {
        var account = books.createAccount("local", account("Imported history", "TAXABLE", null));
        var close = tx("2026-02-02", "TRADE", null, 0L, null, "IMPORT", "close",
                List.of(leg("STOCK", "SELL", "CLOSE", "AAPL", null, null, null, 1, 1, "120")));
        var open = tx("2026-01-02", "TRADE", null, 0L, null, "IMPORT", "open",
                List.of(leg("STOCK", "BUY", "OPEN", "AAPL", null, null, null, 1, 1, "100")));

        assertThat(books.recordBatch("local", account.id(), List.of(close, open)))
                .extracting(PortfolioAccountingService.TransactionView::externalRef)
                .containsExactly("open", "close");
        assertThat(books.realizedLots("local", account.id(), 2026)).singleElement()
                .satisfies(match -> assertThat(match.realizedGainCents()).isEqualTo(20_00L));

        assertThatThrownBy(() -> books.record("local", account.id(), tx("2026-01-15", "TRADE", null, 0L,
                null, "BROKER", "late-backfill", List.of(
                        leg("STOCK", "BUY", "OPEN", "MSFT", null, null, null, 1, 1, "200")))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("oldest to newest");
        assertThat(books.transactions("local", account.id(), 0, 20)).hasSize(2);
    }

    @Test
    void expirationAcceptsOnlyOptionsOnOrAfterTheirExpirationDate() {
        var account = books.createAccount("local", account("Expiration", "TAXABLE", null));
        books.record("local", account.id(), trade("2026-01-02", 0L,
                leg("OPTION", "BUY", "OPEN", "AAPL", "CALL", "100", "2026-02-20", 1, 100, "2")));

        assertThatThrownBy(() -> books.record("local", account.id(), tx("2026-02-01", "EXPIRATION", null, 0L,
                null, "BROKER", "early-expiry", List.of(
                        leg("OPTION", "SELL", "CLOSE", "AAPL", "CALL", "100", "2026-02-20", 1, 100, "0")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("before its expiration date");
        assertThatThrownBy(() -> books.record("local", account.id(), tx("2026-02-20", "EXPIRATION", null, 0L,
                null, "BROKER", "stock-expiry", List.of(
                        leg("STOCK", "SELL", "CLOSE", "AAPL", null, null, null, 1, 1, "0")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("option legs only");
    }

    @Test
    void zeroValueMultiLegExpirationAllocatesFeesAcrossEveryExpiredContract() {
        var account = books.createAccount("local", account("Expiration fees", "TAXABLE", null));
        books.record("local", account.id(), tx("2026-01-02", "TRADE", null, 0L, null, "BROKER", "open-strangle",
                List.of(
                        leg("OPTION", "BUY", "OPEN", "AAPL", "CALL", "110", "2026-02-20", 1, 100, "1"),
                        leg("OPTION", "BUY", "OPEN", "AAPL", "PUT", "90", "2026-02-20", 1, 100, "1"))));

        var expired = books.record("local", account.id(), tx("2026-02-20", "EXPIRATION", null, 200L,
                null, "BROKER", "expire-strangle", List.of(
                        leg("OPTION", "SELL", "CLOSE", "AAPL", "CALL", "110", "2026-02-20", 1, 100, "0"),
                        leg("OPTION", "SELL", "CLOSE", "AAPL", "PUT", "90", "2026-02-20", 1, 100, "0"))));

        assertThat(expired.legs()).extracting(PortfolioAccountingService.LegView::allocatedFeeCents)
                .containsExactly(100L, 100L);
        assertThat(books.realizedLots("local", account.id(), 2026))
                .extracting(PortfolioAccountingService.RealizedLotView::realizedGainCents)
                .containsExactly(-101_00L, -101_00L);
    }

    @Test
    void hifoUsesHighestBasisForLongLotsAndLowestProceedsForShortLots() {
        var account = books.createAccount("local", new PortfolioAccountingService.AccountInput(
                "HIFO", "TAXABLE", null, "HIFO", null, null, null, null, null));
        books.record("local", account.id(), tx("2026-01-02", "TRADE", null, 0L, null, "BROKER", "short-low",
                List.of(leg("OPTION", "SELL", "OPEN", "QQQ", "PUT", "450", "2026-08-21", 1, 100, "2"))));
        books.record("local", account.id(), tx("2026-01-03", "TRADE", null, 0L, null, "BROKER", "short-high",
                List.of(leg("OPTION", "SELL", "OPEN", "QQQ", "PUT", "450", "2026-08-21", 1, 100, "4"))));
        books.record("local", account.id(), tx("2026-02-02", "TRADE", null, 0L, null, "BROKER", "short-close",
                List.of(leg("OPTION", "BUY", "CLOSE", "QQQ", "PUT", "450", "2026-08-21", 1, 100, "3"))));

        assertThat(books.realizedLots("local", account.id(), 2026)).singleElement()
                .satisfies(match -> assertThat(match.realizedGainCents()).isEqualTo(-100_00L));
        assertThat(books.lots("local", account.id(), false)).singleElement()
                .satisfies(lot -> assertThat(lot.remainingOpenAmountCents()).isEqualTo(400_00L));
    }

    @Test
    void cashFeesAndTaxCategoriesCannotSilentlyMisclassifyIncome() {
        var account = books.createAccount("local", account("Taxable", "TAXABLE", null));

        assertThatThrownBy(() -> books.record("local", account.id(), tx("2026-01-02", "DEPOSIT", 100_00L,
                1_00L, null, "BROKER", "ambiguous-fee", List.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("record a FEE event");
        assertThatThrownBy(() -> books.record("local", account.id(), tx("2026-01-02", "TRADE", null,
                0L, "QUALIFIED_DIVIDEND", "BROKER", "trade-as-dividend",
                List.of(leg("STOCK", "BUY", "OPEN", "AAPL", null, null, null, 1, 1, "100")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("only to interest or dividend");
        assertThatThrownBy(() -> books.record("local", account.id(), cash("2026-01-02", "INTEREST",
                10_00L, "interest-as-dividend", "QUALIFIED_DIVIDEND")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tax category for interest");

        books.record("local", account.id(), cash("2026-01-02", "DIVIDEND",
                10_00L, "qualified-dividend", "QUALIFIED_DIVIDEND"));
        assertThat(books.transactions("local", account.id(), 0, 20)).singleElement()
                .satisfies(tx -> assertThat(tx.taxCategory()).isEqualTo("QUALIFIED_DIVIDEND"));
    }

    @Test
    void collateralPairsShortCallsWithTheStrongestRecordedProtectionFirst() {
        books = new PortfolioAccountingService(db, CLOCK, new EmptyMarks());
        var account = books.createAccount("local", account("Taxable", "TAXABLE", 100_000_00L));
        books.record("local", account.id(), tx("2026-01-02", "TRADE", null, 0L, null, "BROKER", "call-portfolio",
                List.of(
                        leg("OPTION", "BUY", "OPEN", "AAPL", "CALL", "90", "2026-08-21", 1, 100, "12"),
                        leg("OPTION", "SELL", "OPEN", "AAPL", "CALL", "100", "2026-08-21", 1, 100, "5"),
                        leg("OPTION", "BUY", "OPEN", "AAPL", "CALL", "110", "2026-08-21", 1, 100, "2"))));

        var collateral = books.summary("local", account.id()).collateral();
        assertThat(collateral.knownBlockedCashCents()).isZero();
        assertThat(collateral.definedRiskCallContracts()).isEqualTo(1);
        assertThat(collateral.uncoveredShortCallShares()).isZero();
        assertThat(collateral.complete()).isTrue();
    }

    @Test
    void goldenTrackedAccountingJourneyKeepsTaxPerformanceExposureAndImportExact() throws Exception {
        MarksSource observed = new MarksSource() {
            @Override public Optional<BigDecimal> underlyingMark(String symbol) { return Optional.empty(); }
            @Override public Optional<LegMark> legMark(String symbol, Leg leg) {
                if (leg.isStock() && "AAPL".equals(symbol)) return Optional.of(mark("110.00", "110.10"));
                if (leg.isStock() && "AMZN".equals(symbol)) return Optional.of(mark("55.00", "55.10"));
                if (!leg.isStock() && "SPX".equals(symbol)) return Optional.of(mark("13.00", "13.10"));
                if (!leg.isStock() && "QQQ".equals(symbol) && leg.type() == io.liftandshift.strikebench.model.OptionType.CALL) {
                    return Optional.of(mark("10.00", "10.10"));
                }
                if (!leg.isStock() && "QQQ".equals(symbol) && leg.type() == io.liftandshift.strikebench.model.OptionType.PUT) {
                    return Optional.of(mark("1.40", "1.50"));
                }
                return Optional.empty();
            }
        };
        books = new PortfolioAccountingService(db, CLOCK, observed);
        var account = books.createAccount("local", account("Golden accounting book", "TAXABLE", 100_000_00L));

        books.record("local", account.id(), tx("2024-01-01", "TRADE", null, 0L, null, "BROKER", "golden-leap-open",
                List.of(leg("STOCK", "BUY", "OPEN", "AAPL", null, null, null, 2, 1, "100"))));
        books.record("local", account.id(), tx("2025-01-01", "TRADE", null, 0L, null, "BROKER", "golden-leap-anniversary",
                List.of(leg("STOCK", "SELL", "CLOSE", "AAPL", null, null, null, 1, 1, "110"))));
        books.record("local", account.id(), tx("2025-01-02", "TRADE", null, 0L, null, "BROKER", "golden-leap-past",
                List.of(leg("STOCK", "SELL", "CLOSE", "AAPL", null, null, null, 1, 1, "120"))));

        books.record("local", account.id(), tx("2025-03-01", "TRADE", null, 0L, null, "BROKER", "golden-wash-open",
                List.of(leg("STOCK", "BUY", "OPEN", "AAPL", null, null, null, 10, 1, "100"))));
        books.record("local", account.id(), tx("2025-04-01T12:00:00Z", "TRADE", null, 0L, null, "BROKER", "golden-wash-loss",
                List.of(leg("STOCK", "SELL", "CLOSE", "AAPL", null, null, null, 10, 1, "80"))));
        books.record("local", account.id(), tx("2025-04-15", "TRADE", null, 0L, null, "BROKER", "golden-wash-rebuy",
                List.of(leg("STOCK", "BUY", "OPEN", "AAPL", null, null, null, 6, 1, "90"))));
        books.record("local", account.id(), tx("2025-05-05", "TRADE", null, 0L, null, "BROKER", "golden-wash-outside",
                List.of(leg("STOCK", "BUY", "OPEN", "AAPL", null, null, null, 4, 1, "90"))));

        books.record("local", account.id(), tx("2025-12-01", "TRADE", null, 0L, null, "BROKER", "golden-index-open",
                List.of(
                        leg("OPTION", "BUY", "OPEN", "SPX", "CALL", "5000", "2026-12-18", 1, 100, "10"),
                        leg("OPTION", "BUY", "OPEN", "XSP", "CALL", "600", "2026-12-18", 1, 100, "10"))));
        db.exec("INSERT INTO option_bar(symbol,asof,expiration,strike,opt_type,bid,ask,mark,source,bid_ask_observed,dataset_id) "
                + "VALUES ('SPX','2025-12-31','2026-12-18',5000,'CALL',11.9,12.1,12.0,'golden-observed',1,'observed'),"
                + "('XSP','2025-12-31','2026-12-18',600,'CALL',11.9,12.1,12.0,'golden-observed',1,'observed')");
        assertThat(books.markSection1256YearEnd("local", account.id(), 2025)).isEqualTo(1);

        books.record("local", account.id(), tx("2026-01-02", "TRADE", null, 0L, null, "BROKER", "golden-xsp-close",
                List.of(leg("OPTION", "SELL", "CLOSE", "XSP", "CALL", "600", "2026-12-18", 1, 100, "12"))));

        books.record("local", account.id(), tx("2026-01-10", "TRADE", null, 100L, null, "BROKER", "golden-roll-open",
                List.of(leg("OPTION", "BUY", "OPEN", "QQQ", "CALL", "500", "2026-08-21", 1, 100, "5"))));
        var roll = books.record("local", account.id(), tx("2026-02-02", "ROLL", null, 200L, null, "BROKER", "golden-roll",
                List.of(
                        leg("OPTION", "SELL", "CLOSE", "QQQ", "CALL", "500", "2026-08-21", 1, 100, "7"),
                        leg("OPTION", "BUY", "OPEN", "QQQ", "CALL", "510", "2026-09-18", 1, 100, "9"))));
        assertThat(roll.roll().premiumCarryoverCents()).isEqualTo(-20_000L);
        assertThat(roll.roll().realizedMatchIds()).singleElement();

        String csv = PortfolioCsvImport.TEMPLATE_HEADER + "\n"
                + "golden-amzn,2026-06-01,TRADE,,0,,,0,STOCK,BUY,OPEN,AMZN,,,,2,1,50,false\n"
                + "golden-bad,2026-06-01,TRADE,,0,,,0,STOCK,BUY,OPEN,NVDA,,,,oops,1,50,false\n"
                + "golden-short,2026-06-01T13:00:00Z,TRADE,,0,,,0,OPTION,SELL,OPEN,QQQ,PUT,450,2026-08-21,1,100,2.00,false\n"
                + "golden-interest,2026-06-02,INTEREST,1234,0,ORDINARY_INTEREST,Imported interest\n";
        var imported = PortfolioCsvImport.run(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                "local", account.id(), books);
        assertThat(imported.transactionsWritten()).isEqualTo(3);
        assertThat(imported.rejectedRows()).isEqualTo(1);
        assertThat(imported.quarantine()).singleElement().satisfies(reject -> {
            assertThat(reject.line()).isEqualTo(3);
            assertThat(reject.transactionRef()).isEqualTo("golden-bad");
            assertThat(reject.reason()).contains("quantity must be an integer");
        });

        books.addValuation("local", account.id(), valuationAt("2026-01-02T16:00:00Z", 100_000_00L));
        books.addValuation("local", account.id(), valuationAt("2026-04-01T14:00:00Z", 110_000_00L));
        books.record("local", account.id(), cash("2026-04-01T15:00:00Z", "DEPOSIT", 100_000_00L,
                "golden-performance-flow", null));
        books.addValuation("local", account.id(), valuationAt("2026-04-01T16:00:00Z", 210_000_00L));
        books.addValuation("local", account.id(), valuationAt("2026-07-01T16:00:00Z", 189_000_00L));
        db.exec("INSERT INTO underlying_bar(symbol,d,close,source,observed,dataset_id,adjusted,quality_rank) VALUES "
                + "('SPY','2026-01-02',100,'golden-observed',1,'observed',1,10),"
                + "('SPY','2026-04-01',105,'golden-observed',1,'observed',1,10),"
                + "('SPY','2026-07-01',110,'golden-observed',1,'observed',1,10)");

        var tax2025 = books.taxReport("local", account.id(), 2025);
        assertThat(tax2025.shortTermGainCents()).isEqualTo(90_00L);
        assertThat(tax2025.longTermGainCents()).isEqualTo(260_00L);
        assertThat(tax2025.section1256GainCents()).isEqualTo(400_00L);
        assertThat(tax2025.washSaleAdjustmentsCents()).isEqualTo(120_00L);
        assertThat(tax2025.realizedLots()).filteredOn(PortfolioAccountingService.RealizedLotView::section1256)
                .extracting(PortfolioAccountingService.RealizedLotView::symbol)
                .containsExactlyInAnyOrder("SPX", "XSP");
        var tax2026 = books.taxReport("local", account.id(), 2026);
        assertThat(tax2026.washSaleAdjustmentsCents()).isZero();
        assertThat(tax2026.shortTermGainCents()).isEqualTo(198_12L);

        var performance = books.performance("local", account.id());
        assertThat(performance.timeWeightedReturn()).isCloseTo(-0.01, within(0.000001));
        assertThat(performance.moneyWeightedIrr()).isCloseTo(-0.1418, within(0.001));
        assertThat(performance.maxDrawdown()).isCloseTo(-0.10, within(0.000001));
        assertThat(performance.benchmark().returnValue()).isCloseTo(0.10, within(0.000001));

        var summary = books.summary("local", account.id());
        assertThat(summary.complete()).isTrue();
        assertThat(summary.bookCashCents()).isEqualTo(19_753_934L);
        assertThat(summary.securitiesLiquidationValueCents()).isEqualTo(336_000L);
        assertThat(summary.totalValueCents()).isEqualTo(20_089_934L);
        assertThat(summary.allocation().longExposureCents()).isEqualTo(351_000L);
        assertThat(summary.allocation().shortExposureCents()).isEqualTo(15_000L);
        assertThat(summary.allocation().grossExposureCents()).isEqualTo(366_000L);
        assertThat(summary.allocation().netExposureCents()).isEqualTo(336_000L);
        assertThat(summary.allocation().byAssetClass()).extracting(PortfolioAccountingService.ExposureRow::label)
                .containsExactlyInAnyOrder("Cash", "Stocks", "Options");
        assertThat(summary.allocation().bySector()).extracting(PortfolioAccountingService.ExposureRow::label)
                .contains("Technology", "Consumer discretionary", "Index & macro ETFs", "Other / unclassified");
        assertThat(summary.positions()).allMatch(p -> "OBSERVED".equals(p.provenance()));

        var calculated = books.recordCalculatedValuation("local", account.id(), Instant.parse("2026-07-13T15:00:00Z"));
        assertThat(calculated.source()).isEqualTo("CALCULATED");
        assertThat(calculated.complete()).isTrue();
        assertThat(calculated.totalValueCents()).isEqualTo(20_089_934L);
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
        String fillNature = switch (event) {
            case "TRADE", "ROLL", "EXPIRATION", "ASSIGNMENT", "EXERCISE" -> "EXECUTED";
            case "MARK_TO_MARKET" -> "MODELED";
            default -> "NOT_APPLICABLE";
        };
        return new PortfolioAccountingService.TransactionInput(date, event, cash, fees, category, source, ref, null,
                legs, fillNature);
    }

    private static PortfolioAccountingService.LegInput leg(String instrument, String action, String effect,
                                                            String symbol, String optionType, String strike,
                                                            String expiration, long quantity, int multiplier,
                                                            String price) {
        return new PortfolioAccountingService.LegInput(instrument, action, effect, symbol, optionType,
                strike == null ? null : new BigDecimal(strike), expiration == null ? null : LocalDate.parse(expiration),
                quantity, multiplier, new BigDecimal(price), null);
    }

    private static PortfolioAccountingService.ValuationInput valuation(String date, long total) {
        return new PortfolioAccountingService.ValuationInput(date, null, null, total, "MANUAL", null, null);
    }

    private static PortfolioAccountingService.ValuationInput valuationAt(String time, long total) {
        return new PortfolioAccountingService.ValuationInput(time, null, null, total, "MANUAL", null, null);
    }

    private static org.assertj.core.data.Offset<Double> within(double amount) {
        return org.assertj.core.data.Offset.offset(amount);
    }
}
