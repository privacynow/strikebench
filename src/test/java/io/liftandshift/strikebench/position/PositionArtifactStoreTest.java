package io.liftandshift.strikebench.position;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.eval.EvidenceLevel;
import io.liftandshift.strikebench.paper.PortfolioAccountingService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PositionArtifactStoreTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC);
    private Db db;
    private PortfolioAccountingService books;

    @BeforeEach void setUp() {
        db = TestDb.fresh();
        books = new PortfolioAccountingService(db, CLOCK);
    }

    @AfterEach void tearDown() { db.close(); }

    @Test
    void onePlanManagedActionCommitsTheFourArtifactsAtomicallyAndEnforcesLotAllocation() {
        var account = books.createAccount("local", new PortfolioAccountingService.AccountInput(
                "Tracked", "TAXABLE", "Broker", "FIFO", null, null, null, null, 10_000_000L));
        var opening = books.record("local", account.id(), new PortfolioAccountingService.TransactionInput(
                "2026-01-02T15:00:00Z", "TRADE", null, 0L, null, "BROKER", "shares-500", null,
                List.of(new PortfolioAccountingService.LegInput("STOCK", "BUY", "OPEN", "NVDA", null,
                        null, null, 500L, 1, new BigDecimal("100.00")))));
        String lotId = books.lots("local", account.id(), false).getFirst().id();
        createPlan("plan-one", "NVDA");
        PositionArtifactStore store = new PositionArtifactStore(db);

        var first = store.recordNewStructureAction(action("plan-one", account.id(), opening.id(), lotId, 300));
        var second = store.recordNewStructureAction(action("plan-one", account.id(), opening.id(), lotId, 200));

        assertThat(first.structureId()).isNotEqualTo(second.structureId());
        assertThat(db.query("SELECT COUNT(*) n FROM portfolio_structure", r -> r.lng("n")).getFirst()).isEqualTo(2);
        assertThat(db.query("SELECT COUNT(*) n FROM portfolio_structure_revision", r -> r.lng("n")).getFirst()).isEqualTo(2);
        assertThat(db.query("SELECT COUNT(*) n FROM position_receipt", r -> r.lng("n")).getFirst()).isEqualTo(2);
        assertThat(db.query("SELECT COUNT(*) n FROM plan_portfolio_action", r -> r.lng("n")).getFirst()).isEqualTo(2);

        assertThatThrownBy(() -> store.recordNewStructureAction(
                action("plan-one", account.id(), opening.id(), lotId, 1)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("current structure allocations exceed");
        assertThat(db.query("SELECT COUNT(*) n FROM portfolio_structure", r -> r.lng("n")).getFirst()).isEqualTo(2);
        assertThat(db.query("SELECT COUNT(*) n FROM position_receipt", r -> r.lng("n")).getFirst()).isEqualTo(2);
        assertThat(db.query("SELECT COUNT(*) n FROM plan_portfolio_action", r -> r.lng("n")).getFirst()).isEqualTo(2);
    }

    @Test
    void pendingImportIdentityIncludesTheNonReversibleAccountFingerprintAndStaysOutOfLedger() {
        long before = db.query("SELECT COUNT(*) n FROM portfolio_transaction", r -> r.lng("n")).getFirst();
        insertPending("pending-a", "fingerprint-a", "order-17");
        insertPending("pending-b", "fingerprint-b", "order-17");
        assertThat(db.query("SELECT COUNT(*) n FROM portfolio_import_pending", r -> r.lng("n")).getFirst())
                .isEqualTo(2);
        assertThat(db.query("SELECT COUNT(*) n FROM portfolio_transaction", r -> r.lng("n")).getFirst())
                .isEqualTo(before);

        assertThatThrownBy(() -> insertPending("pending-c", "fingerprint-a", "order-17"))
                .isInstanceOf(RuntimeException.class);

        db.exec("INSERT INTO campaign(id,user_id,symbol,title,status) VALUES(?,?,?,?,?)",
                "campaign-one", "local", "MU", "MU income campaign", "ACTIVE");
        db.exec("INSERT INTO campaign_pending_member(campaign_id,pending_id) VALUES(?,?)",
                "campaign-one", "pending-a");
        assertThat(db.query("SELECT p.package_net_cents FROM campaign_pending_member m "
                        + "JOIN portfolio_import_pending p ON p.id=m.pending_id WHERE m.campaign_id=?",
                r -> r.lng("package_net_cents"), "campaign-one")).containsExactly(221_200L);
    }

    @Test
    void trackedWriterRejectsAnOmittedMarketFillInsteadOfPersistingZero() {
        var account = books.createAccount("local", new PortfolioAccountingService.AccountInput(
                "Tracked", "ROTH_IRA", null, "FIFO", null, null, null, null, null));
        assertThatThrownBy(() -> books.record("local", account.id(),
                new PortfolioAccountingService.TransactionInput("2026-01-02T15:00:00Z", "TRADE", null,
                        0L, null, "MANUAL", null, null,
                        List.of(new PortfolioAccountingService.LegInput("OPTION", "BUY", "OPEN", "AAPL",
                                "CALL", new BigDecimal("250"), LocalDate.parse("2026-08-21"),
                                1L, 100, null)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact price");
        assertThat(db.query("SELECT COUNT(*) n FROM portfolio_transaction", r -> r.lng("n")).getFirst()).isZero();
    }

    @Test
    void pendingResolutionMustMatchThePendingCashTransactionAndFrozenReceipt() {
        var account = books.createAccount("local", new PortfolioAccountingService.AccountInput(
                "Resolution", "ROTH_IRA", null, "FIFO", null, null, null, null, null));
        var transaction = books.record("local", account.id(), new PortfolioAccountingService.TransactionInput(
                "2026-07-01T15:00:00Z", "TRADE", null, 0L, null, "BROKER", "resolved-order", null,
                List.of(new PortfolioAccountingService.LegInput("STOCK", "BUY", "OPEN", "MU", null,
                        null, null, 1L, 1, new BigDecimal("100.00")))));
        db.exec("INSERT INTO portfolio_import_pending(id,user_id,source_system,source_account_fingerprint,"
                        + "external_ref,occurred_at,package_net_cents,fees_cents) VALUES(?,?,?,?,?,?,?,?)",
                "pending-resolution", "local", "BROKER_CSV", "fingerprint-resolution", "resolved-order",
                OffsetDateTime.parse("2026-07-01T15:00:00Z"), -10_000L, 0L);
        db.exec("INSERT INTO position_receipt(id,user_id,kind,authority,execution_lane,position_state,"
                        + "portfolio_account_id,transaction_id,marks_as_of,evidence_level,model_version) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?)", "resolution-receipt", "local", "RESOLUTION",
                "BROKER_REPORTED", "REAL", "OPEN", account.id(), transaction.id(),
                OffsetDateTime.parse("2026-07-01T15:00:00Z"), "OBSERVED_DELAYED", "trader-own-1");

        assertThatThrownBy(() -> db.tx(c -> {
            Db.execOn(c, "UPDATE portfolio_import_pending SET status='RESOLVED',resolved_at=now() WHERE id=?",
                    "pending-resolution");
            Db.execOn(c, "INSERT INTO portfolio_import_resolution(pending_id,portfolio_account_id,transaction_id,"
                            + "receipt_id,authority,tax_basis_status,package_total_cents,allocated_total_cents,resolver_user_id) "
                            + "VALUES(?,?,?,?,?,?,?,?,?)", "pending-resolution", account.id(), transaction.id(),
                    "resolution-receipt", "BROKER_REPORTED", "AUTHORITATIVE", -9_999L, -9_999L, "local");
            return null;
        })).isInstanceOf(RuntimeException.class).hasMessageContaining("does not reconcile");
        assertThat(db.query("SELECT status FROM portfolio_import_pending WHERE id=?", r -> r.str("status"),
                "pending-resolution")).containsExactly("PENDING");

        db.tx(c -> {
            Db.execOn(c, "UPDATE portfolio_import_pending SET status='RESOLVED',resolved_at=now() WHERE id=?",
                    "pending-resolution");
            Db.execOn(c, "INSERT INTO portfolio_import_resolution(pending_id,portfolio_account_id,transaction_id,"
                            + "receipt_id,authority,tax_basis_status,package_total_cents,allocated_total_cents,resolver_user_id) "
                            + "VALUES(?,?,?,?,?,?,?,?,?)", "pending-resolution", account.id(), transaction.id(),
                    "resolution-receipt", "BROKER_REPORTED", "AUTHORITATIVE", -10_000L, -10_000L, "local");
            return null;
        });
        assertThat(db.query("SELECT tax_basis_status FROM portfolio_import_resolution WHERE pending_id=?",
                r -> r.str("tax_basis_status"), "pending-resolution")).containsExactly("AUTHORITATIVE");
    }

    private PositionArtifactStore.NewStructureAction action(String planId, String accountId,
                                                             String transactionId, String lotId, long quantity) {
        return new PositionArtifactStore.NewStructureAction("local", planId, 1, accountId, transactionId,
                null, "NVDA", "Covered stock", PositionDomain.PositionState.OPEN,
                PositionDomain.PlanActionRole.ENTRY, PositionDomain.ReceiptKind.ADOPTION,
                PositionDomain.ReceiptAuthority.BROKER_REPORTED,
                OffsetDateTime.parse("2026-07-15T12:00:00Z"), EvidenceLevel.OBSERVED_DELAYED,
                "trader-own-1", List.of(new PositionArtifactStore.Allocation(lotId, quantity, "UNDERLYING")),
                List.of());
    }

    private void createPlan(String id, String symbol) {
        db.exec("INSERT INTO plans(id,user_id,symbol,intent,market_kind,status,furthest_stage,version,is_open) "
                        + "VALUES(?,?,?,?,?,'ACTIVE','UNDERSTAND',1,1)",
                id, "local", symbol, "INCOME", "OBSERVED");
        db.exec("INSERT INTO plan_context_revision(id,plan_id,rev,horizon_days,input_hash,engine_version) "
                        + "VALUES(?,?,1,30,?,?)", id + "-ctx", id, "hash", "trader-own-1");
        db.exec("UPDATE plans SET active_context_rev=1 WHERE id=?", id);
    }

    private void insertPending(String id, String fingerprint, String ref) {
        db.exec("INSERT INTO portfolio_import_pending(id,user_id,source_system,source_account_fingerprint,"
                        + "external_ref,occurred_at,package_net_cents,fees_cents) VALUES(?,?,?,?,?,?,?,?)",
                id, "local", "VANGUARD_CSV", fingerprint, ref,
                OffsetDateTime.parse("2026-07-01T15:00:00Z"), 221_200L, 0L);
        db.exec("INSERT INTO portfolio_import_pending_leg(pending_id,leg_no,instrument_type,action,position_effect,"
                        + "symbol,option_type,strike,expiration,quantity,multiplier) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                id, 0, "OPTION", "SELL", "OPEN", "MU", "PUT", new BigDecimal("980"),
                LocalDate.parse("2026-07-17"), 1L, 100);
    }
}
