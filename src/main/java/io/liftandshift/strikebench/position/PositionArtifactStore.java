package io.liftandshift.strikebench.position;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.eval.EvidenceLevel;
import io.liftandshift.strikebench.util.Ids;
import io.liftandshift.strikebench.util.OwnerScope;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Atomic writer for the artifact cardinality required by a Plan-managed tracked action. */
public final class PositionArtifactStore {
    private final Db db;

    public PositionArtifactStore(Db db) { this.db = db; }

    public ArtifactSet recordNewStructureAction(NewStructureAction input) {
        if (input == null) throw new IllegalArgumentException("position action is required");
        String userId = OwnerScope.id(input.userId());
        return db.tx(c -> recordNewStructureAction(c, userId, input));
    }

    private ArtifactSet recordNewStructureAction(Connection c, String userId, NewStructureAction input)
            throws SQLException {
        requireExists(c, "SELECT 1 ok FROM plans WHERE id=? AND user_id=?", "Plan", input.planId(), userId);
        requireExists(c, "SELECT 1 ok FROM plan_context_revision WHERE plan_id=? AND rev=?",
                "Plan context revision", input.planId(), input.contextRevision());
        requireExists(c, "SELECT 1 ok FROM portfolio_account WHERE id=? AND user_id=? AND status='ACTIVE'",
                "active tracked account", input.portfolioAccountId(), userId);
        requireExists(c, "SELECT 1 ok FROM portfolio_transaction WHERE id=? AND portfolio_account_id=?",
                "tracked transaction", input.transactionId(), input.portfolioAccountId());
        if (input.allocations() == null || input.allocations().isEmpty()) {
            throw new IllegalArgumentException("a tracked structure requires at least one lot allocation");
        }

        String structureId = Ids.newId("pstr");
        String revisionId = Ids.newId("psr");
        String receiptId = Ids.newId("prec");
        String actionId = Ids.newId("ppa");
        Db.execOn(c, "INSERT INTO portfolio_structure(id,user_id,portfolio_account_id,symbol,label,status) "
                        + "VALUES(?,?,?,?,?,'OPEN')",
                structureId, userId, input.portfolioAccountId(), symbol(input.symbol()), trim(input.label()));
        Db.execOn(c, "INSERT INTO portfolio_structure_revision(id,structure_id,revision_no,position_state,"
                        + "action_role,transaction_id) VALUES(?,?,1,?,?,?)",
                revisionId, structureId, input.positionState().name(), input.role().name(), input.transactionId());
        int legNo = 0;
        for (Allocation allocation : input.allocations()) {
            if (allocation == null || allocation.quantity() <= 0) {
                throw new IllegalArgumentException("lot allocations must be positive");
            }
            requireExists(c, "SELECT 1 ok FROM portfolio_lot WHERE id=? AND portfolio_account_id=?",
                    "tracked lot", allocation.lotId(), input.portfolioAccountId());
            Db.execOn(c, "INSERT INTO portfolio_structure_member(revision_id,leg_no,lot_id,allocated_quantity,leg_role) "
                            + "VALUES(?,?,?,?,?)", revisionId, legNo++, allocation.lotId(), allocation.quantity(),
                    allocation.legRole());
        }
        Db.execOn(c, "UPDATE portfolio_structure SET current_revision_id=?,updated_at=now() WHERE id=?",
                revisionId, structureId);

        Db.execOn(c, "INSERT INTO position_receipt(id,user_id,kind,authority,execution_lane,position_state,"
                        + "plan_id,plan_context_rev,portfolio_account_id,structure_revision_id,decision_id,transaction_id,"
                        + "marks_as_of,evidence_level,model_version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                receiptId, userId, input.receiptKind().name(), input.receiptAuthority().name(),
                PositionDomain.ExecutionLane.REAL.name(), input.positionState().name(), input.planId(),
                input.contextRevision(), input.portfolioAccountId(), revisionId, input.decisionId(),
                input.transactionId(), input.marksAsOf(), input.evidenceLevel().name(), input.modelVersion());
        if (input.receiptLegs() != null) for (ReceiptLeg leg : input.receiptLegs()) {
            Db.execOn(c, "INSERT INTO position_receipt_leg(receipt_id,leg_no,instrument_type,action,symbol,"
                            + "option_type,strike,expiration,quantity,multiplier,bid,ask,mid,fill_price,price_authority) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    receiptId, leg.legNo(), leg.instrumentType(), leg.action(), symbol(leg.symbol()),
                    leg.optionType(), leg.strike(), leg.expiration(), leg.quantity(), leg.multiplier(), leg.bid(),
                    leg.ask(), leg.mid(), leg.fillPrice(), leg.priceAuthority().name());
        }
        Db.execOn(c, "INSERT INTO plan_portfolio_action(id,plan_id,structure_revision_id,transaction_id,receipt_id,role) "
                        + "VALUES(?,?,?,?,?,?)", actionId, input.planId(), revisionId, input.transactionId(),
                receiptId, input.role().name());
        return new ArtifactSet(structureId, revisionId, receiptId, actionId);
    }

    private static void requireExists(Connection c, String sql, String label, Object... params) throws SQLException {
        if (Db.queryOn(c, sql, row -> row.intv("ok"), params).isEmpty()) {
            throw new IllegalArgumentException(label + " does not exist in this owner/account scope");
        }
    }

    private static String symbol(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("symbol is required");
        return value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public record NewStructureAction(String userId, String planId, int contextRevision,
                                     String portfolioAccountId, String transactionId, String decisionId,
                                     String symbol, String label, PositionDomain.PositionState positionState,
                                     PositionDomain.PlanActionRole role, PositionDomain.ReceiptKind receiptKind,
                                     PositionDomain.ReceiptAuthority receiptAuthority, OffsetDateTime marksAsOf,
                                     EvidenceLevel evidenceLevel, String modelVersion,
                                     List<Allocation> allocations, List<ReceiptLeg> receiptLegs) {
        public NewStructureAction {
            allocations = allocations == null ? List.of() : List.copyOf(allocations);
            receiptLegs = receiptLegs == null ? List.of() : List.copyOf(receiptLegs);
            if (contextRevision <= 0 || positionState == null || role == null || receiptKind == null
                    || receiptAuthority == null || marksAsOf == null || evidenceLevel == null
                    || modelVersion == null || modelVersion.isBlank()) {
                throw new IllegalArgumentException("complete action provenance is required");
            }
        }
    }

    public record Allocation(String lotId, long quantity, String legRole) {}
    public record ReceiptLeg(int legNo, String instrumentType, String action, String symbol,
                             String optionType, BigDecimal strike, LocalDate expiration,
                             long quantity, int multiplier, BigDecimal bid, BigDecimal ask,
                             BigDecimal mid, BigDecimal fillPrice,
                             PositionDomain.PriceAuthority priceAuthority) {}
    public record ArtifactSet(String structureId, String revisionId, String receiptId, String actionId) {}
}
