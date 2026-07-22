package io.liftandshift.strikebench.position;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.paper.AccountObjectiveService;
import io.liftandshift.strikebench.paper.BookActionProjectionService;
import io.liftandshift.strikebench.paper.BookRiskService;
import io.liftandshift.strikebench.paper.MarksSource;
import io.liftandshift.strikebench.paper.PortfolioAccountingService;
import io.liftandshift.strikebench.paper.TradeService;
import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PositionLifecycleDecisionServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2031-07-22T16:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate EXPIRY = LocalDate.parse("2031-08-07");
    private static final String POSITION = "a".repeat(64);
    private Db db;
    private PortfolioAccountingService books;
    private AccountObjectiveService objectives;
    private BookActionProjectionService projections;
    private PositionLifecycleDecisionService decisions;

    @BeforeEach void setUp() {
        db = TestDb.fresh();
        MarksSource marks = new MarksSource() {
            @Override public Optional<BigDecimal> underlyingMark(String symbol) {
                return Optional.of(new BigDecimal("400"));
            }
            @Override public Optional<LegMark> legMark(String symbol, Leg leg) {
                BigDecimal bid = new BigDecimal("0.45");
                BigDecimal ask = new BigDecimal("0.50");
                return Optional.of(new LegMark(bid, ask, new BigDecimal("0.475"), null,
                        Freshness.DELAYED, -0.20, 0.01, -0.02, 0.05,
                        DataEvidence.of("observed-test", Freshness.DELAYED)));
            }
        };
        books = new PortfolioAccountingService(db, CLOCK, marks);
        objectives = new AccountObjectiveService(db, CLOCK);
        var risk = new BookRiskService(db, CLOCK, marks, books, objectives, null);
        projections = new BookActionProjectionService(books, risk, CLOCK);
        decisions = new PositionLifecycleDecisionService(db, CLOCK);
    }

    @AfterEach void close() { db.close(); }

    @Test
    void twoNamedPoliciesProduceKeepThenMinimumReductionAndPersonalDecisionStaysSeparate() {
        var account = books.createAccount("local", new PortfolioAccountingService.AccountInput(
                "Synthetic policy book", "TAXABLE", null, "FIFO", null, null, null, null,
                100_000_000L));
        books.record("local", account.id(), new PortfolioAccountingService.TransactionInput(
                "2031-07-01T16:00:00Z", "TRADE", null, 0L, null, "MANUAL", "policy-put", null,
                List.of(new PortfolioAccountingService.LegInput("OPTION", "SELL", "OPEN", "QQQ", "PUT",
                        new BigDecimal("450"), EXPIRY, 3L, 100, new BigDecimal("2.00"), null)),
                "EXECUTED"));
        long transactionsBefore = count("portfolio_transaction");
        long lotsBefore = count("portfolio_lot");
        var request = new TradeService.OpenRequest(account.id(), "QQQ", "CASH_SECURED_PUT", 3,
                List.of(Leg.option(LegAction.SELL, OptionType.PUT, new BigDecimal("450"),
                        EXPIRY, 1, BigDecimal.ZERO)), null, "16d", "DEFINED", "INCOME",
                false, null, null, "IMPORT", "EXECUTED");
        PositionLifecycleReceipt lifecycle = lifecycle();

        var fullPolicy = new AccountObjectiveService.LifecyclePolicy("FULL_ASSIGNMENT_CAPACITY",
                90, 1_000L, 0, 5, false, null);
        var fullCapacity = capacity(fullPolicy, List.of());
        var fullRevision = objectives.declare("local", account.id(), "INCOME", "NON_DIRECTIONAL",
                null, "ACCEPT", List.of(packageCapacity()), fullCapacity);
        var actionSet = projections.project("local", account.id(), request, lifecycle);
        var full = decisions.analyze(lifecycle, actionSet,
                AccountObjectiveService.capacityContext(fullRevision, POSITION));

        assertThat(full.verdict()).isEqualTo(PositionLifecycleDecisionService.Verdict.KEEP);
        assertThat(full.dimensions()).filteredOn(row -> row.name().equals("TAIL_EVENT"))
                .singleElement().satisfies(row -> {
                    assertThat(row.status()).isEqualTo("CAUTION");
                    assertThat(row.reasons()).anyMatch(reason -> reason.contains("confirmed issuer event"));
                });
        assertThat(full.alternatives()).extracting(PositionLifecycleDecisionService.ActionAlternative::quantityAffected)
                .containsExactly(1, 2, 3);

        var assignmentPolicy = new AccountObjectiveService.LifecyclePolicy("ASSIGNMENT_READY",
                90, 1_000L, 0, 20, false, null);
        var assignmentRevision = objectives.declare("local", account.id(), "ACCUMULATE", "BULLISH",
                null, "ACCEPT", List.of(packageCapacity()), capacity(assignmentPolicy, List.of()));
        assertThat(decisions.analyze(lifecycle, actionSet,
                AccountObjectiveService.capacityContext(assignmentRevision, POSITION)).verdict())
                .isEqualTo(PositionLifecycleDecisionService.Verdict.ACCEPT_ASSIGNMENT);

        var limitedPolicy = new AccountObjectiveService.LifecyclePolicy("CONCENTRATION_LIMITED",
                90, 1_000L, 0, 5, false, null);
        var limitedCapacity = capacity(limitedPolicy, List.of(new AccountObjectiveService.ScopedCeiling(
                "QQQ", 5_000_000L, AccountObjectiveService.Enforcement.HARD)));
        var limitedRevision = objectives.declare("local", account.id(), "INCOME", "NON_DIRECTIONAL",
                null, "ACCEPT", List.of(packageCapacity()), limitedCapacity);
        var limited = decisions.analyze(lifecycle, actionSet,
                AccountObjectiveService.capacityContext(limitedRevision, POSITION));

        assertThat(limited.verdict()).isEqualTo(PositionLifecycleDecisionService.Verdict.REDUCE);
        assertThat(limited.reduction().quantityToClose()).isEqualTo(2);
        assertThat(limited.reduction().quantityRemaining()).isEqualTo(1);
        assertThat(limited.limits()).singleElement().satisfies(limit -> {
            assertThat(limit.currentCents()).isEqualTo(13_500_000L);
            assertThat(limit.restoredByClosingQuantity()).isEqualTo(2);
        });

        var cheapPolicy = new AccountObjectiveService.LifecyclePolicy("CHEAP_RISK_REMOVAL",
                90, 20_000L, 1.0, 5, false, null);
        var cheapRevision = objectives.declare("local", account.id(), "INCOME", "NON_DIRECTIONAL",
                null, "ACCEPT", List.of(packageCapacity()), capacity(cheapPolicy, List.of()));
        var surfaced = decisions.surface("local", account.id(), lifecycle, actionSet,
                AccountObjectiveService.capacityContext(cheapRevision, POSITION));
        assertThat(surfaced.analysis().verdict())
                .isEqualTo(PositionLifecycleDecisionService.Verdict.HARVEST);
        var redeploy = decisions.resolveAction("local", account.id(), surfaced.receiptId(),
                "CLOSE_K", 2);
        assertThat(redeploy.symbol()).isEqualTo("QQQ");
        assertThat(redeploy.executableCloseCostCents()).isEqualTo(10_200L);
        assertThat(redeploy.capitalReleasedCents()).isEqualTo(9_000_000L);
        assertThat(redeploy.closingPnlCents()).isEqualTo(9_800L);
        assertThat(redeploy.postActionBook().risk().themes().symbolNotionals())
                .extracting(BookRiskService.SymbolNotional::notionalCents)
                .containsExactly(4_500_000L);
        assertThatThrownBy(() -> decisions.resolveAction("local", account.id(), surfaced.receiptId(),
                "CLOSE_K", 1)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not available in the frozen receipt");
        var personal = decisions.recordUserDecision("local", account.id(),
                new PositionLifecycleDecisionService.DecisionInput(surfaced.receiptId(), "KEEP",
                        "HOLD", null, "I accept the assigned shares and prefer to retain this premium."));
        assertThat(personal.decision()).isEqualTo(PositionLifecycleDecisionService.Verdict.KEEP);
        assertThatThrownBy(() -> decisions.recordUserDecision("local", account.id(),
                new PositionLifecycleDecisionService.DecisionInput(surfaced.receiptId(), "REDUCE",
                        "CLOSE_K", 1, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not available in the frozen receipt");
        var resurfaced = decisions.surface("local", account.id(), lifecycle, actionSet,
                AccountObjectiveService.capacityContext(cheapRevision, POSITION));
        assertThat(resurfaced.analysis().verdict())
                .isEqualTo(PositionLifecycleDecisionService.Verdict.HARVEST);
        assertThat(resurfaced.latestUserDecision().decision())
                .isEqualTo(PositionLifecycleDecisionService.Verdict.KEEP);
        assertThat(resurfaced.latestUserDecision().note()).contains("retain this premium");

        assertThat(count("position_lifecycle_decision_receipt")).isEqualTo(2);
        assertThat(count("position_lifecycle_user_decision")).isEqualTo(1);
        assertThat(count("portfolio_transaction")).isEqualTo(transactionsBefore);
        assertThat(count("portfolio_lot")).isEqualTo(lotsBefore);
    }

    private static AccountObjectiveService.AccountCapacityPolicy capacity(
            AccountObjectiveService.LifecyclePolicy policy,
            List<AccountObjectiveService.ScopedCeiling> symbolCeilings) {
        return new AccountObjectiveService.AccountCapacityPolicy(symbolCeilings, List.of(), List.of(),
                null, policy);
    }

    private static AccountObjectiveService.PackageCapacity packageCapacity() {
        return new AccountObjectiveService.PackageCapacity(POSITION, "QQQ", 300L, 13_500_000L,
                45_000L, null, null);
    }

    private static PositionLifecycleReceipt lifecycle() {
        var close = new PositionLifecycleReceipt.CloseQuote(true, -14_250L, -15_000L,
                -15_000L, 300L, -15_300L, PositionDomain.PriceAuthority.OBSERVED,
                "Observed ask plus fees.", null);
        var forward = new PositionLifecycleReceipt.ForwardEconomics(true, -300L, 500L,
                -100L, 900L, 50L, true, "Observed hold-vs-close economics.", null);
        return new PositionLifecycleReceipt(PositionLifecycleReceipt.SCHEMA_VERSION, "QQQ", POSITION,
                new PositionLifecycleReceipt.History(true, 60_000L, 60_000L, 0L, 60_000L,
                        60_000L, 30_000L, 50.0, 14_700L, null, null,
                        "Recorded opening fill and executable close.", null, List.of("tracked-lots")),
                new PositionLifecycleReceipt.CurrentChoice(close,
                        "Would you open the exact position you still own today, ignoring sunk campaign cash?",
                        PositionLifecycleReceipt.FRESH_EYES_ECONOMICS_REF, forward, 1_350_000L,
                        "Canonical probability-map CVaR95.", PositionLifecycleReceipt.STANCE_REF,
                        "Executable hold-vs-close choice.", List.of()),
                new PositionLifecycleReceipt.CarryCollateral(15_000L, 2.5347, 16,
                        new AuthorityFacts.MoneyFact(13_500_000L,
                                PositionDomain.FactAuthority.MODEL_DERIVED, "Strike obligation."),
                        AuthorityFacts.RateFact.unavailable("No broker settlement rate."),
                        new AuthorityFacts.MoneyFact(13_500_000L,
                                PositionDomain.FactAuthority.MODEL_DERIVED, "Theoretical encumbrance."),
                        new AuthorityFacts.MoneyFact(13_500_000L,
                                PositionDomain.FactAuthority.MODEL_DERIVED, "Theoretical release."),
                        0L, "Carry and collateral are separate.", List.of()),
                new PositionLifecycleReceipt.AssignmentExit(List.of(
                        new PositionLifecycleReceipt.AssignmentLeg(OptionType.PUT, EXPIRY, 45_000L,
                                300L, 13_500_000L, 44_950L, "BUY_SHARES", "Exact strike geometry.")),
                        AuthorityFacts.MoneyFact.unavailable("No linked tax-lot basis."),
                        AuthorityFacts.MoneyFact.unavailable("No linked campaign basis."),
                        List.of(new PositionLifecycleReceipt.EventCrossing("EARNINGS",
                                LocalDate.parse("2031-08-04"), "AFTER_CLOSE", "CONFIRMED",
                                "Issuer IR", "https://example.test/issuer", OffsetDateTime.now(CLOCK),
                                "e".repeat(64))), "CONFIRMED", "bookActions",
                        "Exact assignment and event evidence.", List.of()),
                new PositionLifecycleReceipt.Evidence(OffsetDateTime.now(CLOCK), "COMPLETE",
                        "m".repeat(64), "model-v1", "FACTS_ONLY", List.of("preview", "book"), List.of()));
    }

    private long count(String table) {
        return db.query("SELECT COUNT(*) n FROM " + table, row -> row.lng("n")).getFirst();
    }
}
