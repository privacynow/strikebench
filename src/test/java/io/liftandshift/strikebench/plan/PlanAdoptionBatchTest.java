package io.liftandshift.strikebench.plan;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.paper.PortfolioAccountingService;
import io.liftandshift.strikebench.position.PositionArtifactStore;
import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanAdoptionBatchTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC);
    private Db db;
    private PortfolioAccountingService books;
    private PlanService plans;
    private PlanAdoptionService adoptions;
    private String accountId;

    @BeforeEach void setUp() {
        db = TestDb.fresh();
        books = new PortfolioAccountingService(db, CLOCK);
        plans = new PlanService(db, CLOCK);
        adoptions = new PlanAdoptionService(db, CLOCK, plans, new PositionArtifactStore(db));
        accountId = books.createAccount("local", new PortfolioAccountingService.AccountInput(
                "Imported IRA", "ROTH_IRA", "Broker", "FIFO", null, null, null, null, null)).id();
    }

    @AfterEach void tearDown() { if (db != null) db.close(); }

    @Test
    void overlappingSecondChoiceRollsBackTheCompleteConfirmedBatch() {
        String lot = openShares("MU", "mu-open", 100, "100");
        var first = item("ADOPT", "batch-a", "MU", lot, 100, null);
        var second = item("ADOPT", "batch-b", "MU", lot, 100, null);

        assertThatThrownBy(() -> adoptions.adoptBatch("local", Plan.MarketKind.OBSERVED, null,
                new PlanAdoptionService.BatchRequest(List.of(first, second))))
                .isInstanceOf(RuntimeException.class).hasMessageContaining("allocations exceed");

        assertThat(count("plans")).isZero();
        assertThat(count("portfolio_structure")).isZero();
        assertThat(count("position_receipt")).isZero();
        assertThat(count("plan_portfolio_action")).isZero();
    }

    @Test
    void adoptLinkAndSkipUseOneAdoptionOwnerAndCommitTogether() {
        String muLot = openShares("MU", "mu-open", 100, "100");
        String aaplLot = openShares("AAPL", "aapl-open", 50, "200");
        Plan.View existing = plans.create("local", Plan.MarketKind.OBSERVED, null, null,
                new Plan.CreateRequest("existing-aapl", "AAPL", null, null, "AAPL as-is", null,
                        null, null, null, null, null, null, null));

        var result = adoptions.adoptBatch("local", Plan.MarketKind.OBSERVED, null,
                new PlanAdoptionService.BatchRequest(List.of(
                        item("ADOPT", "batch-mu", "MU", muLot, 100, null),
                        item("LINK", "batch-aapl-link", "AAPL", aaplLot, 50, existing.id()),
                        new PlanAdoptionService.BatchItem("SKIP", null, null, null,
                                "Do not manage", List.of(), null))));

        assertThat(result.adopted()).isEqualTo(1);
        assertThat(result.linked()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.items()).extracting(PlanAdoptionService.BatchItemResult::action)
                .containsExactly("ADOPT", "LINK", "SKIP");
        assertThat(count("portfolio_structure")).isEqualTo(2);
        assertThat(db.query("SELECT kind || ':' || authority v FROM position_receipt ORDER BY created_at,id",
                r -> r.str("v"))).containsOnly("ADOPTION:USER_ALLOCATED");
        assertThat(plans.get("local", existing.id()).furthestStage()).isEqualTo(Plan.Stage.MANAGE_REVIEW);
    }

    @Test
    void exactBatchReplayReturnsTheSameArtifactsAndConflictingReplayIsRejected() {
        String lot = openShares("MU", "mu-replay", 100, "100");
        var request = new PlanAdoptionService.BatchRequest(List.of(
                item("ADOPT", "stable-replay", "MU", lot, 100, null)));

        var first = adoptions.adoptBatch("local", Plan.MarketKind.OBSERVED, null, request);
        var replay = adoptions.adoptBatch("local", Plan.MarketKind.OBSERVED, null, request);

        assertThat(replay.items().getFirst().plan().id()).isEqualTo(first.items().getFirst().plan().id());
        assertThat(replay.items().getFirst().artifacts()).isEqualTo(first.items().getFirst().artifacts());
        assertThat(replay.items().getFirst().replayed()).isTrue();
        assertThat(replay.adopted()).isZero();
        assertThat(replay.replayed()).isEqualTo(1);
        assertThat(count("plans")).isEqualTo(1);
        assertThat(count("portfolio_structure")).isEqualTo(1);
        assertThat(count("position_receipt")).isEqualTo(1);
        assertThat(count("plan_portfolio_action")).isEqualTo(1);
        assertThat(count("portfolio_adoption_request")).isEqualTo(1);

        var changed = new PlanAdoptionService.BatchRequest(List.of(
                item("ADOPT", "stable-replay", "MU", lot, 50, null)));
        assertThatThrownBy(() -> adoptions.adoptBatch("local", Plan.MarketKind.OBSERVED, null, changed))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("different adoption facts");
        assertThat(count("portfolio_structure")).isEqualTo(1);
    }

    @Test
    void sameSymbolDistinctLotsCreateDistinctPositionOwnedPlans() {
        String firstLot = openShares("MU", "mu-position-1", 100, "100");
        String secondLot = openShares("MU", "mu-position-2", 100, "105");

        var result = adoptions.adoptBatch("local", Plan.MarketKind.OBSERVED, null,
                new PlanAdoptionService.BatchRequest(List.of(
                        item("ADOPT", "mu-position-plan-1", "MU", firstLot, 100, null),
                        item("ADOPT", "mu-position-plan-2", "MU", secondLot, 100, null))));

        assertThat(result.items()).extracting(item -> item.plan().id()).doesNotHaveDuplicates();
        assertThat(count("plans")).isEqualTo(2);
        assertThat(db.query("SELECT COUNT(DISTINCT position_owner_key) n FROM plans",
                r -> r.lng("n"))).containsExactly(2L);
    }

    @Test
    void realTrackedAdoptionRejectsDemoAndCrossMarketPlanLinks() {
        String lot = openShares("MU", "mu-market-boundary", 100, "100");
        assertThatThrownBy(() -> adoptions.adoptBatch("local", Plan.MarketKind.DEMO, null,
                new PlanAdoptionService.BatchRequest(List.of(
                        item("ADOPT", "demo-real-position", "MU", lot, 100, null)))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Observed market");

        Plan.View demo = plans.create("local", Plan.MarketKind.DEMO, null, null,
                new Plan.CreateRequest("demo-plan", "MU", null, null, "Demo MU", null,
                        null, null, null, null, null, null, null));
        assertThatThrownBy(() -> adoptions.adoptBatch("local", Plan.MarketKind.OBSERVED, null,
                new PlanAdoptionService.BatchRequest(List.of(
                        item("LINK", "cross-market-link", "MU", lot, 100, demo.id())))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("another market");
        assertThat(count("portfolio_structure")).isZero();
    }

    @Test
    void onlyVerifiedBrokerPayloadCanBecomeBrokerReportedReceiptPrice() {
        String genericImportLot = openShares("MU", "generic-import", 50, "100", "IMPORT");
        String verifiedLot = openShares("AAPL", "verified-import", 50, "200", "IMPORT");
        db.exec("UPDATE portfolio_transaction SET import_payload_fingerprint='verified-payload' "
                + "WHERE id=(SELECT opening_transaction_id FROM portfolio_lot WHERE id=?)", verifiedLot);

        adoptions.adoptBatch("local", Plan.MarketKind.OBSERVED, null,
                new PlanAdoptionService.BatchRequest(List.of(
                        item("ADOPT", "generic-import-adopt", "MU", genericImportLot, 50, null),
                        item("ADOPT", "verified-import-adopt", "AAPL", verifiedLot, 50, null))));

        assertThat(db.query("SELECT l.symbol || ':' || l.price_authority v FROM position_receipt_leg l "
                        + "JOIN position_receipt r ON r.id=l.receipt_id WHERE r.kind='ADOPTION' ORDER BY l.symbol",
                r -> r.str("v"))).containsExactly("AAPL:BROKER_REPORTED", "MU:USER_REPORTED");
    }

    @Test
    void oneAdoptionCannotRepeatTheSameLotAsTwoSyntheticLegs() {
        String lot = openShares("MU", "duplicate-lot", 100, "100");
        var duplicate = new PlanAdoptionService.BatchItem("ADOPT", "duplicate-lot-adopt", accountId,
                "MU", "duplicate", List.of(new PlanAdoptionService.Allocation(lot, 50L),
                new PlanAdoptionService.Allocation(lot, 50L)), null);
        assertThatThrownBy(() -> adoptions.adoptBatch("local", Plan.MarketKind.OBSERVED, null,
                new PlanAdoptionService.BatchRequest(List.of(duplicate))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("more than once");
        assertThat(count("portfolio_structure")).isZero();
    }

    private PlanAdoptionService.BatchItem item(String action, String requestId, String symbol,
                                                String lotId, long quantity, String existingPlan) {
        return new PlanAdoptionService.BatchItem(action, requestId, accountId, symbol,
                symbol + " imported position", List.of(new PlanAdoptionService.Allocation(lotId, quantity)),
                existingPlan);
    }

    private String openShares(String symbol, String ref, long quantity, String price) {
        return openShares(symbol, ref, quantity, price, "BROKER");
    }

    private String openShares(String symbol, String ref, long quantity, String price, String source) {
        var transaction = books.record("local", accountId, new PortfolioAccountingService.TransactionInput(
                "2026-07-01T15:00:00Z", "TRADE", null, 0L, null, source, ref, null,
                List.of(new PortfolioAccountingService.LegInput("STOCK", "BUY", "OPEN", symbol,
                        null, null, null, quantity, 1, new BigDecimal(price), null)), "EXECUTED"));
        return db.query("SELECT id FROM portfolio_lot WHERE opening_transaction_id=?",
                r -> r.str("id"), transaction.id()).getFirst();
    }

    private long count(String table) {
        return db.query("SELECT COUNT(*) n FROM " + table, r -> r.lng("n")).getFirst();
    }
}
