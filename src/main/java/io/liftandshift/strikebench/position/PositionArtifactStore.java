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
            Db.execOn(c, "INSERT INTO position_receipt_leg(receipt_id,position_phase,leg_no,instrument_type,action,symbol,"
                            + "option_type,strike,expiration,quantity,multiplier,bid,ask,mid,fill_price,price_authority) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    receiptId, leg.positionPhase(), leg.legNo(), leg.instrumentType(), leg.action(), symbol(leg.symbol()),
                    leg.optionType(), leg.strike(), leg.expiration(), leg.quantity(), leg.multiplier(), leg.bid(),
                    leg.ask(), leg.mid(), leg.fillPrice(), leg.priceAuthority().name());
        }
        Db.execOn(c, "INSERT INTO plan_portfolio_action(id,plan_id,structure_revision_id,transaction_id,receipt_id,role) "
                        + "VALUES(?,?,?,?,?,?)", actionId, input.planId(), revisionId, input.transactionId(),
                receiptId, input.role().name());
        return new ArtifactSet(structureId, revisionId, receiptId, actionId);
    }

    /** Writes the frozen before/after artifact inside the same transaction as a Practice mutation. */
    public String recordPracticeTransformation(Connection c, PracticeTransformationAction input)
            throws SQLException {
        if (c == null || input == null) throw new IllegalArgumentException("practice transformation is required");
        String userId = OwnerScope.id(input.userId());
        requireExists(c, "SELECT 1 ok FROM trades t JOIN accounts a ON a.id=t.account_id "
                        + "WHERE t.id=? AND a.user_id=?", "owned Practice trade", input.practiceTradeId(), userId);
        if (input.planId() != null) {
            requireExists(c, "SELECT 1 ok FROM plans WHERE id=? AND user_id=?", "Plan", input.planId(), userId);
            requireExists(c, "SELECT 1 ok FROM plan_context_revision WHERE plan_id=? AND rev=?",
                    "Plan context revision", input.planId(), input.contextRevision());
        }
        PositionTransformation.Preview preview = input.preview();
        Db.execOn(c, "INSERT INTO position_receipt(id,user_id,kind,authority,execution_lane,position_state,"
                        + "plan_id,plan_context_rev,practice_trade_id,marks_as_of,evidence_level,model_version,"
                        + "transformation_action,preview_fingerprint) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                input.receiptId(), userId, PositionDomain.ReceiptKind.TRANSFORMATION.name(),
                PositionDomain.ReceiptAuthority.SYSTEM_ANALYSIS.name(), PositionDomain.ExecutionLane.PRACTICE.name(),
                input.positionState().name(), input.planId(), input.planId() == null ? null : input.contextRevision(),
                input.practiceTradeId(), input.marksAsOf(), input.evidenceLevel().name(), input.modelVersion(),
                preview.action().name(), preview.fingerprint());
        insertPackageLegs(c, input.receiptId(), "BEFORE", input.before());
        insertPackageLegs(c, input.receiptId(), "AFTER", input.after());
        insertTextMetric(c, input.receiptId(), "before_identity", preview.beforeIdentity().label());
        insertTextMetric(c, input.receiptId(), "after_identity", preview.afterIdentity().label());
        insertCentsMetric(c, input.receiptId(), "before_max_loss", preview.beforeRisk().maxLossCents());
        insertCentsMetric(c, input.receiptId(), "after_max_loss",
                preview.afterRisk() == null ? 0L : preview.afterRisk().maxLossCents());
        insertCentsMetric(c, input.receiptId(), "before_reserve", preview.beforeRisk().reserveCents());
        insertCentsMetric(c, input.receiptId(), "after_reserve",
                preview.afterRisk() == null ? 0L : preview.afterRisk().reserveCents());
        insertCentsMetric(c, input.receiptId(), "before_assignment_cash",
                preview.beforeObligations().putAssignmentCashCents());
        insertCentsMetric(c, input.receiptId(), "after_assignment_cash",
                preview.afterObligations().putAssignmentCashCents());
        insertNumberMetric(c, input.receiptId(), "before_call_delivery_shares",
                preview.beforeObligations().callDeliveryShares());
        insertNumberMetric(c, input.receiptId(), "after_call_delivery_shares",
                preview.afterObligations().callDeliveryShares());
        insertCentsMetric(c, input.receiptId(), "realized_closing",
                input.realizedCents() == null ? preview.realizedClosingCents() : input.realizedCents());
        return input.receiptId();
    }

    private static void insertPackageLegs(Connection c, String receiptId, String phase, PositionPackage position)
            throws SQLException {
        if (position == null) return;
        for (PositionPackage.Leg leg : position.legs()) {
            Db.execOn(c, "INSERT INTO position_receipt_leg(receipt_id,position_phase,leg_no,instrument_type,action,"
                            + "symbol,option_type,strike,expiration,quantity,multiplier,mid,fill_price,price_authority) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    receiptId, phase, leg.index(), leg.instrumentType(), leg.action(), symbol(leg.symbol()),
                    leg.optionType(), leg.strike(), leg.expiration(), leg.quantity(), leg.multiplier(), leg.price(),
                    leg.priceAuthority() == PositionDomain.PriceAuthority.BROKER_REPORTED
                            || leg.priceAuthority() == PositionDomain.PriceAuthority.USER_REPORTED ? leg.price() : null,
                    leg.priceAuthority().name());
        }
    }

    private static void insertCentsMetric(Connection c, String receiptId, String key, Long value)
            throws SQLException {
        if (value != null) Db.execOn(c, "INSERT INTO position_receipt_metric(receipt_id,metric_key,value_cents) VALUES(?,?,?)",
                receiptId, key, value);
    }

    private static void insertNumberMetric(Connection c, String receiptId, String key, long value)
            throws SQLException {
        Db.execOn(c, "INSERT INTO position_receipt_metric(receipt_id,metric_key,value_number) VALUES(?,?,?)",
                receiptId, key, (double) value);
    }

    private static void insertTextMetric(Connection c, String receiptId, String key, String value)
            throws SQLException {
        if (value != null) Db.execOn(c, "INSERT INTO position_receipt_metric(receipt_id,metric_key,value_text) VALUES(?,?,?)",
                receiptId, key, value);
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
    public record ReceiptLeg(String positionPhase, int legNo, String instrumentType, String action, String symbol,
                             String optionType, BigDecimal strike, LocalDate expiration,
                             long quantity, int multiplier, BigDecimal bid, BigDecimal ask,
                             BigDecimal mid, BigDecimal fillPrice,
                             PositionDomain.PriceAuthority priceAuthority) {}
    public record PracticeTransformationAction(String userId, String receiptId, String planId,
                                               Integer contextRevision, String practiceTradeId,
                                               PositionDomain.PositionState positionState,
                                               OffsetDateTime marksAsOf, EvidenceLevel evidenceLevel,
                                               String modelVersion, PositionPackage before,
                                               PositionPackage after, PositionTransformation.Preview preview,
                                               Long realizedCents) {
        public PracticeTransformationAction {
            if (receiptId == null || receiptId.isBlank() || practiceTradeId == null || practiceTradeId.isBlank()
                    || positionState == null || marksAsOf == null || evidenceLevel == null
                    || modelVersion == null || modelVersion.isBlank() || before == null || preview == null
                    || planId != null && (contextRevision == null || contextRevision <= 0)) {
                throw new IllegalArgumentException("complete Practice transformation provenance is required");
            }
        }
    }
    public record ArtifactSet(String structureId, String revisionId, String receiptId, String actionId) {}
}
