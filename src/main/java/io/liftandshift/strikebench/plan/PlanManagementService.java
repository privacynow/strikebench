package io.liftandshift.strikebench.plan;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.paper.TradeRecord;
import io.liftandshift.strikebench.paper.TradeService;
import io.liftandshift.strikebench.util.Ids;
import io.liftandshift.strikebench.util.Json;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import io.liftandshift.strikebench.util.ResourceNotFoundException;
import java.util.Set;

/** Typed Plan ownership for marks, closes, rolls, voids, and separated review lanes. */
public final class PlanManagementService {
    public record CashReview(long underlyingStartCents, long underlyingEndCents, long stockPnlCents,
                             long rejectedEntryCents, long rejectedEndCents, long rejectedPnlCents,
                             int horizonDays, Double predictedPop, String note) {}
    private final Db db;
    private final Clock clock;

    public PlanManagementService(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public String activeTradeId(String userId, String planId) {
        return db.with(c -> {
            requireOwned(c, planId, userId, false);
            return Db.queryOn(c, "SELECT l.trade_id FROM plan_link l JOIN trades t ON t.id=l.trade_id " +
                            "WHERE l.plan_id=? AND l.trade_id IS NOT NULL AND t.status='ACTIVE' " +
                            "ORDER BY l.created_at DESC LIMIT 1", r -> r.str("trade_id"), planId)
                    .stream().findFirst().orElse(null);
        });
    }

    public void recordMark(String userId, String planId, long expectedVersion,
                           String tradeId, TradeService.MarkView mark) {
        db.tx(c -> {
            PlanRow plan = requireOwned(c, planId, userId, true);
            if (plan.version() != expectedVersion) throw new IllegalStateException("This Plan changed before the mark was saved.");
            requireLinked(c, planId, tradeId);
            Db.execOn(c, "INSERT INTO plan_management_action(id,plan_id,decision_id,trade_id,kind,action_at," +
                            "underlying_cents,position_value_cents,unrealized_cents,pop,note,created_at) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", Ids.newId("pmgt"), planId,
                    latestDecisionId(c, planId), tradeId, "MARK", requireMarkTime(mark.ts()), mark.underlyingCents(),
                    mark.closeCostCents(), mark.decisionUnrealizedCents() != null ? mark.decisionUnrealizedCents() : mark.unrealizedCents(),
                    mark.popNow(), "Plan mark refreshed from executable closing sides", now());
            return null;
        });
    }

    public TradeService.LifecycleHook lifecycleHook(String userId, String planId, long expectedVersion,
                                                    String kind, boolean prepareRoll) {
        return lifecycleHook(userId, planId, expectedVersion, kind, prepareRoll, null);
    }

    public TradeService.LifecycleHook lifecycleHook(String userId, String planId, long expectedVersion,
                                                    String kind, boolean prepareRoll, String receiptId) {
        String normalized = kind == null ? "CLOSE" : kind.trim().toUpperCase();
        if (!Set.of("CLOSE", "SETTLE", "ROLL", "VOID").contains(normalized)) {
            throw new IllegalArgumentException("management kind must be CLOSE, SETTLE, ROLL, or VOID");
        }
        return (connection, trade, actionRealized, realizedToDate) -> saveLifecycleOn(connection, userId, planId,
                expectedVersion, normalized, prepareRoll, receiptId, trade, actionRealized, realizedToDate);
    }

    public TradeService.RollHook rollLifecycleHook(String userId, String planId, long expectedVersion,
                                                   String receiptId) {
        if (receiptId == null || receiptId.isBlank()) {
            throw new IllegalArgumentException("a Plan roll requires its transformation receipt");
        }
        return (connection, closed, replacement, actionRealized, realizedToDate) -> saveRollOn(connection,
                userId, planId, expectedVersion, receiptId, closed, replacement, actionRealized, realizedToDate);
    }

    public TradeService.LifecycleHook partialCloseLifecycleHook(String userId, String planId,
                                                                long expectedVersion, String receiptId) {
        if (receiptId == null || receiptId.isBlank()) {
            throw new IllegalArgumentException("a Plan partial close requires its transformation receipt");
        }
        return (connection, survivor, actionRealized, realizedToDate) -> savePartialCloseOn(connection, userId, planId,
                expectedVersion, receiptId, survivor, actionRealized);
    }

    public TradeService.LifecycleHook adjustmentLifecycleHook(String userId, String planId,
                                                              long expectedVersion,
                                                              String transformationAction,
                                                              String receiptId) {
        if (receiptId == null || receiptId.isBlank()) {
            throw new IllegalArgumentException("a Plan adjustment requires its transformation receipt");
        }
        String action = transformationAction == null ? "" : transformationAction.trim().toUpperCase();
        if (!java.util.Set.of("LEG_CLOSE", "REMOVE_LEG", "ADD_LEG", "ADD_STOCK", "REMOVE_STOCK")
                .contains(action)) {
            throw new IllegalArgumentException("unsupported Plan position adjustment: " + transformationAction);
        }
        return (connection, survivor, actionRealized, realizedToDate) -> saveAdjustmentOn(connection,
                userId, planId, expectedVersion, receiptId, action, survivor, actionRealized);
    }

    public TradeService.LifecycleHook optionLifecycleHook(String userId, String planId,
                                                          long expectedVersion, String action,
                                                          boolean positionSurvives,
                                                          String receiptId) {
        String normalized = action == null ? "" : action.trim().toUpperCase();
        if (!Set.of("ASSIGNMENT", "EXERCISE", "EXPIRATION").contains(normalized)) {
            throw new IllegalArgumentException("unsupported option lifecycle action: " + action);
        }
        if (receiptId == null || receiptId.isBlank()) {
            throw new IllegalArgumentException("a Plan option lifecycle action requires its transformation receipt");
        }
        return (connection, changed, actionRealized, realizedToDate) -> saveOptionLifecycleOn(connection,
                userId, planId, expectedVersion, receiptId, normalized, positionSurvives,
                changed, actionRealized, realizedToDate);
    }

    public ObjectNode recordCashReview(String userId, String planId, long expectedVersion, CashReview review) {
        db.tx(c -> {
            PlanRow plan = requireOwned(c, planId, userId, true);
            if (plan.version() != expectedVersion) throw new IllegalStateException("This Plan changed before the review was recorded.");
            if (!"DECIDED_CASH".equals(plan.status())) {
                throw new IllegalStateException("Only an active frozen cash decision can record an opportunity review.");
            }
            String decisionId = latestDecisionId(c, planId);
            if (decisionId == null) throw new IllegalStateException("This Plan has no frozen decision to review.");
            String action = Db.queryOn(c, "SELECT action FROM plan_decision WHERE id=?", r -> r.str("action"), decisionId)
                    .stream().findFirst().orElse(null);
            if (!"CASH".equals(action)) throw new IllegalStateException("Only a cash decision uses the opportunity-review lane.");
            boolean exists = !Db.queryOn(c, "SELECT 1 ok FROM plan_review WHERE plan_id=? AND decision_id=? " +
                            "AND category='CASH_DECISION' LIMIT 1", r -> r.intv("ok"), planId, decisionId).isEmpty();
            if (exists) return null;
            OffsetDateTime now = now();
            insertReview(c, planId, decisionId, review.horizonDays(), "CASH", 0L, 0L, 0L,
                    null, false, "Cash benchmark: no market exposure and no interest assumption", now);
            insertReview(c, planId, decisionId, review.horizonDays(), "STOCK", review.underlyingStartCents(),
                    review.underlyingEndCents(), review.stockPnlCents(), null, review.stockPnlCents() > 0,
                    "Risk-matched whole-share benchmark", now);
            insertReview(c, planId, decisionId, review.horizonDays(), "REJECTED_STRATEGY", review.rejectedEntryCents(),
                    review.rejectedEndCents(), review.rejectedPnlCents(), review.predictedPop(), review.rejectedPnlCents() > 0,
                    review.note(), now);
            Db.execOn(c, "UPDATE plans SET version=version+1,updated_at=? WHERE id=?", now, planId);
            return null;
        });
        return latest(userId, planId);
    }

    public ObjectNode latest(String userId, String planId) {
        return db.with(c -> {
            requireOwned(c, planId, userId, false);
            ObjectNode out = Json.MAPPER.createObjectNode();
            put(out, "activeTradeId", Db.queryOn(c, "SELECT l.trade_id FROM plan_link l JOIN trades t ON t.id=l.trade_id " +
                            "WHERE l.plan_id=? AND t.status='ACTIVE' ORDER BY l.created_at DESC LIMIT 1",
                    r -> r.str("trade_id"), planId).stream().findFirst().orElse(null));
            ObjectNode currentPosition = latestPositionReceipt(c, planId);
            if (currentPosition != null) out.set("currentPosition", currentPosition);
            ObjectNode tracked = latestTrackedStructure(c, planId);
            if (tracked != null) out.set("trackedStructure", tracked);
            ArrayNode links = out.putArray("links");
            Db.queryOn(c, "SELECT role,trade_id,sim_session_id,related_plan_id,created_at::text created_at " +
                            "FROM plan_link WHERE plan_id=? ORDER BY created_at", r -> {
                        ObjectNode n = Json.MAPPER.createObjectNode(); put(n, "role", r.str("role"));
                        put(n, "tradeId", r.str("trade_id")); put(n, "simSessionId", r.str("sim_session_id"));
                        put(n, "relatedPlanId", r.str("related_plan_id")); put(n, "createdAt", r.str("created_at"));
                        return n;
                    }, planId).forEach(links::add);
            ArrayNode actions = out.putArray("actions");
            Db.queryOn(c, "SELECT kind,action_at::text action_at,underlying_cents,position_value_cents," +
                            "realized_cents,unrealized_cents,pop,mae_cents,mfe_cents,note FROM plan_management_action " +
                            "WHERE plan_id=? ORDER BY action_at DESC,CASE WHEN kind='MARK' THEN 1 ELSE 0 END,created_at DESC", r -> {
                        ObjectNode n = Json.MAPPER.createObjectNode(); put(n, "kind", r.str("kind"));
                        put(n, "at", r.str("action_at")); put(n, "underlyingCents", r.lngOrNull("underlying_cents"));
                        put(n, "positionValueCents", r.lngOrNull("position_value_cents"));
                        put(n, "realizedCents", r.lngOrNull("realized_cents")); put(n, "unrealizedCents", r.lngOrNull("unrealized_cents"));
                        put(n, "pop", r.dblOrNull("pop")); put(n, "maeCents", r.lngOrNull("mae_cents"));
                        put(n, "mfeCents", r.lngOrNull("mfe_cents")); put(n, "note", r.str("note")); return n;
                    }, planId).forEach(actions::add);
            ArrayNode reviews = out.putArray("reviews");
            Db.queryOn(c, "SELECT category,horizon_days,benchmark_kind,benchmark_start_cents,benchmark_end_cents," +
                            "realized_cents,predicted_pop,won,reviewed_at::text reviewed_at,note FROM plan_review " +
                            "WHERE plan_id=? ORDER BY reviewed_at DESC", r -> {
                        ObjectNode n = Json.MAPPER.createObjectNode(); put(n, "category", r.str("category"));
                        put(n, "horizonDays", r.intv("horizon_days")); put(n, "benchmarkKind", r.str("benchmark_kind"));
                        put(n, "benchmarkStartCents", r.lngOrNull("benchmark_start_cents"));
                        put(n, "benchmarkEndCents", r.lngOrNull("benchmark_end_cents"));
                        put(n, "realizedCents", r.lngOrNull("realized_cents")); put(n, "predictedPop", r.dblOrNull("predicted_pop"));
                        Long won = r.lngOrNull("won"); put(n, "won", won == null ? null : won == 1);
                        put(n, "reviewedAt", r.str("reviewed_at")); put(n, "note", r.str("note")); return n;
                    }, planId).forEach(reviews::add);
            return out;
        });
    }

    /**
     * The frozen transformation receipt is the current Plan package when an option lifecycle event
     * leaves shares but no active option trade. It is a read model over artifacts written by the
     * existing atomic mutation, not a second accounting path.
     */
    private static ObjectNode latestPositionReceipt(Connection c, String planId) throws SQLException {
        List<ObjectNode> rows = Db.queryOn(c, "SELECT id,position_state,transformation_action,practice_trade_id," +
                        "marks_as_of::text marks_as_of,evidence_level,created_at::text created_at " +
                        "FROM position_receipt WHERE plan_id=? AND execution_lane='PRACTICE' " +
                        "ORDER BY created_at DESC LIMIT 1", r -> {
                    ObjectNode n = Json.MAPPER.createObjectNode();
                    put(n, "receiptId", r.str("id"));
                    put(n, "positionState", r.str("position_state"));
                    put(n, "action", r.str("transformation_action"));
                    put(n, "practiceTradeId", r.str("practice_trade_id"));
                    put(n, "marksAsOf", r.str("marks_as_of"));
                    put(n, "evidenceLevel", r.str("evidence_level"));
                    put(n, "createdAt", r.str("created_at"));
                    return n;
                }, planId);
        if (rows.isEmpty()) return null;
        ObjectNode out = rows.getFirst();
        String receiptId = out.get("receiptId").asText();
        Db.queryOn(c, "SELECT value_text FROM position_receipt_metric " +
                        "WHERE receipt_id=? AND metric_key='after_identity'", r -> r.str("value_text"), receiptId)
                .stream().findFirst().ifPresent(value -> put(out, "identity", value));
        ArrayNode legs = out.putArray("legs");
        Db.queryOn(c, "SELECT leg_no,instrument_type,action,symbol,option_type,strike::text strike," +
                        "expiration::text expiration,quantity,multiplier,mid::text mid,price_authority " +
                        "FROM position_receipt_leg WHERE receipt_id=? AND position_phase='AFTER' ORDER BY leg_no", r -> {
                    ObjectNode leg = Json.MAPPER.createObjectNode();
                    put(leg, "legNo", r.intv("leg_no"));
                    put(leg, "instrumentType", r.str("instrument_type"));
                    put(leg, "action", r.str("action"));
                    put(leg, "symbol", r.str("symbol"));
                    put(leg, "optionType", r.str("option_type"));
                    put(leg, "strike", r.str("strike"));
                    put(leg, "expiration", r.str("expiration"));
                    put(leg, "quantity", r.lng("quantity"));
                    put(leg, "multiplier", r.intv("multiplier"));
                    put(leg, "price", r.str("mid"));
                    put(leg, "priceAuthority", r.str("price_authority"));
                    return leg;
                }, receiptId).forEach(legs::add);
        String tradeId = out.path("practiceTradeId").asText(null);
        if (tradeId != null) {
            Db.queryOn(c, "SELECT p.shares,p.avg_cost_cents FROM trades t " +
                            "JOIN positions p ON p.account_id=t.account_id AND p.symbol=t.symbol WHERE t.id=?",
                    r -> new long[]{r.lng("shares"), r.lng("avg_cost_cents")}, tradeId)
                    .stream().findFirst().ifPresent(holding -> {
                        put(out, "holdingShares", holding[0]);
                        put(out, "holdingAvgCostCents", holding[1]);
                    });
        }
        return out;
    }

    /**
     * The REAL-lane read model: a broker-recorded placement links this Plan to a tracked-book
     * structure through plan_portfolio_action. Same artifact-read principle as the Practice
     * receipt above — never a second accounting path.
     */
    private static ObjectNode latestTrackedStructure(Connection c, String planId) throws SQLException {
        List<ObjectNode> rows = Db.queryOn(c, "SELECT ppa.role,ppa.receipt_id,ppa.transaction_id," +
                        "ppa.created_at::text created_at,psr.position_state,ps.id structure_id,ps.label," +
                        "ps.symbol,ps.status,ps.portfolio_account_id,pa.name account_name," +
                        "pr.marks_as_of::text marks_as_of,pr.authority,pr.kind receipt_kind " +
                        "FROM plan_portfolio_action ppa " +
                        "JOIN portfolio_structure_revision psr ON psr.id=ppa.structure_revision_id " +
                        "JOIN portfolio_structure ps ON ps.id=psr.structure_id " +
                        "JOIN portfolio_account pa ON pa.id=ps.portfolio_account_id " +
                        "JOIN position_receipt pr ON pr.id=ppa.receipt_id " +
                        "WHERE ppa.plan_id=? ORDER BY ppa.created_at DESC LIMIT 1", r -> {
                    ObjectNode n = Json.MAPPER.createObjectNode();
                    put(n, "structureId", r.str("structure_id"));
                    put(n, "label", r.str("label"));
                    put(n, "symbol", r.str("symbol"));
                    put(n, "status", r.str("status"));
                    put(n, "positionState", r.str("position_state"));
                    put(n, "role", r.str("role"));
                    put(n, "receiptId", r.str("receipt_id"));
                    put(n, "receiptKind", r.str("receipt_kind"));
                    put(n, "authority", r.str("authority"));
                    put(n, "transactionId", r.str("transaction_id"));
                    put(n, "accountId", r.str("portfolio_account_id"));
                    put(n, "accountName", r.str("account_name"));
                    put(n, "marksAsOf", r.str("marks_as_of"));
                    put(n, "createdAt", r.str("created_at"));
                    return n;
                }, planId);
        if (rows.isEmpty()) return null;
        ObjectNode out = rows.getFirst();
        ArrayNode legs = out.putArray("legs");
        Db.queryOn(c, "SELECT leg_no,instrument_type,action,symbol,option_type,strike::text strike," +
                        "expiration::text expiration,quantity,multiplier,fill_price::text fill_price " +
                        "FROM position_receipt_leg WHERE receipt_id=? AND position_phase='AFTER' ORDER BY leg_no", r -> {
                    ObjectNode leg = Json.MAPPER.createObjectNode();
                    put(leg, "legNo", r.intv("leg_no"));
                    put(leg, "instrumentType", r.str("instrument_type"));
                    put(leg, "action", r.str("action"));
                    put(leg, "symbol", r.str("symbol"));
                    put(leg, "optionType", r.str("option_type"));
                    put(leg, "strike", r.str("strike"));
                    put(leg, "expiration", r.str("expiration"));
                    put(leg, "quantity", r.lng("quantity"));
                    put(leg, "multiplier", r.intv("multiplier"));
                    put(leg, "fillPrice", r.str("fill_price"));
                    return leg;
                }, out.get("receiptId").asText()).forEach(legs::add);
        long remaining = Db.queryOn(c, "SELECT COALESCE(SUM(pl.remaining_quantity),0) remaining " +
                        "FROM portfolio_structure ps JOIN portfolio_structure_member psm " +
                        "ON psm.revision_id=ps.current_revision_id " +
                        "JOIN portfolio_lot pl ON pl.id=psm.lot_id WHERE ps.id=?",
                r -> r.lng("remaining"), out.get("structureId").asText()).getFirst();
        out.put("openQuantityRemaining", remaining);
        return out;
    }

    private void saveLifecycleOn(Connection c, String userId, String planId, long expectedVersion,
                                 String kind, boolean prepareRoll, String receiptId,
                                 TradeRecord trade, Long actionRealized,
                                 Long realizedToDate) throws SQLException {
        PlanRow plan = requireOwned(c, planId, userId, true);
        if (plan.version() != expectedVersion) throw new IllegalStateException("This Plan changed before the management action completed.");
        requireLinked(c, planId, trade.id());
        OffsetDateTime now = now();
        String decisionId = latestDecisionId(c, planId);
        String actionKind = prepareRoll ? "ROLL" : switch (kind) {
            case "SETTLE" -> "SETTLE";
            case "VOID" -> "VOID";
            default -> "CLOSE";
        };
        Db.execOn(c, "INSERT INTO plan_management_action(id,plan_id,decision_id,trade_id,receipt_id,kind,action_at," +
                        "realized_cents,note,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)", Ids.newId("pmgt"), planId,
                decisionId, trade.id(), receiptId, actionKind, now, actionRealized,
                "VOID".equals(kind) ? "Practice trade voided and cash entries reversed" :
                        prepareRoll ? "Position closed; exact package prepared for a roll in this Plan" :
                                "SETTLE".equals(kind) ? "Position settled after expiration" : "Position unwound at executable closing sides", now);
        String linkRole = prepareRoll ? "ROLL" : "CLOSE";
        Db.execOn(c, "INSERT INTO plan_link(id,plan_id,decision_id,role,trade_id,created_at) VALUES(?,?,?,?,?,?)",
                Ids.newId("plink"), planId, decisionId, linkRole, trade.id(), now);
        if (!"VOID".equals(kind) && realizedToDate != null && decisionId != null) {
            List<DecisionReview> frozen = Db.queryOn(c,
                    "SELECT review_horizon_days,pop FROM plan_decision WHERE id=?",
                    r -> new DecisionReview(r.intv("review_horizon_days"), r.dblOrNull("pop")), decisionId);
            if (!frozen.isEmpty()) {
                DecisionReview decision = frozen.getFirst();
                Db.execOn(c, "INSERT INTO plan_review(id,plan_id,decision_id,category,horizon_days,benchmark_kind," +
                                "realized_cents,predicted_pop,won,reviewed_at,note,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                        Ids.newId("prev"), planId, decisionId, "TRADE_DECISION", decision.horizonDays(),
                        "PLAN_POSITION", realizedToDate, decision.pop(), realizedToDate > 0 ? 1 : 0, now,
                        prepareRoll ? "Realized result for this completed roll cycle; the Plan continues"
                                : "Realized result for the Plan position; market provenance remains on the Plan and trade",
                        now);
            }
        }
        Db.execOn(c, "UPDATE plans SET status=?,furthest_stage=?,version=version+1,updated_at=? WHERE id=?",
                prepareRoll ? "ACTIVE" : "CLOSED", prepareRoll ? "STRATEGY" : "MANAGE_REVIEW", now, planId);
    }

    private void saveRollOn(Connection c, String userId, String planId, long expectedVersion,
                            String receiptId, TradeRecord closed, TradeRecord replacement,
                            long actionRealized, long realizedToDate) throws SQLException {
        PlanRow plan = requireOwned(c, planId, userId, true);
        if (plan.version() != expectedVersion) {
            throw new IllegalStateException("This Plan changed before the roll completed.");
        }
        requireLinked(c, planId, closed.id());
        if (!closed.accountId().equals(replacement.accountId()) || !TradeRecord.ACTIVE.equals(replacement.status())) {
            throw new IllegalStateException("The roll replacement is not an active position in the same Practice account.");
        }
        OffsetDateTime at = now();
        String decisionId = latestDecisionId(c, planId);
        Db.execOn(c, "INSERT INTO plan_management_action(id,plan_id,decision_id,trade_id,receipt_id,kind,action_at," +
                        "realized_cents,note,created_at) VALUES(?,?,?,?,?,'ROLL',?,?,?,?)",
                Ids.newId("pmgt"), planId, decisionId, closed.id(), receiptId, at, actionRealized,
                "Closed the prior package and opened the reviewed replacement atomically; the closing loss or gain remains realized",
                at);
        Db.execOn(c, "INSERT INTO plan_link(id,plan_id,decision_id,role,trade_id,created_at) " +
                        "VALUES(?,?,?,'ROLL',?,?)",
                Ids.newId("plink"), planId, decisionId, replacement.id(), at);
        if (decisionId != null) {
            List<DecisionReview> frozen = Db.queryOn(c,
                    "SELECT review_horizon_days,pop FROM plan_decision WHERE id=?",
                    r -> new DecisionReview(r.intv("review_horizon_days"), r.dblOrNull("pop")), decisionId);
            if (!frozen.isEmpty()) {
                DecisionReview decision = frozen.getFirst();
                Db.execOn(c, "INSERT INTO plan_review(id,plan_id,decision_id,category,horizon_days,benchmark_kind," +
                                "realized_cents,predicted_pop,won,reviewed_at,note,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                        Ids.newId("prev"), planId, decisionId, "TRADE_DECISION", decision.horizonDays(),
                        "PLAN_POSITION", realizedToDate, decision.pop(), realizedToDate > 0 ? 1 : 0, at,
                        "Realized result of the closed roll leg; the reviewed replacement remains open", at);
            }
        }
        Db.execOn(c, "UPDATE plans SET status='POSITION_OPEN',furthest_stage='MANAGE_REVIEW'," +
                        "version=version+1,updated_at=? WHERE id=?", at, planId);
    }

    private void savePartialCloseOn(Connection c, String userId, String planId, long expectedVersion,
                                    String receiptId, TradeRecord survivor, long actionRealized)
            throws SQLException {
        PlanRow plan = requireOwned(c, planId, userId, true);
        if (plan.version() != expectedVersion) {
            throw new IllegalStateException("This Plan changed before the partial close completed.");
        }
        if (!"POSITION_OPEN".equals(plan.status())) {
            throw new IllegalStateException("Only a Plan with an open position can be partially closed.");
        }
        requireLinked(c, planId, survivor.id());
        if (!TradeRecord.ACTIVE.equals(survivor.status()) || survivor.qty() <= 0) {
            throw new IllegalStateException("The partial close did not leave an active position.");
        }
        OffsetDateTime at = now();
        String decisionId = latestDecisionId(c, planId);
        Db.execOn(c, "INSERT INTO plan_management_action(id,plan_id,decision_id,trade_id,receipt_id,kind,action_at," +
                        "realized_cents,note,created_at) VALUES(?,?,?,?,?,'PARTIAL_CLOSE',?,?,?,?)",
                Ids.newId("pmgt"), planId, decisionId, survivor.id(), receiptId, at, actionRealized,
                "Closed part of the exact package; the surviving quantity keeps its original entry basis",
                at);
        Db.execOn(c, "INSERT INTO plan_link(id,plan_id,decision_id,role,trade_id,created_at) " +
                        "VALUES(?,?,?,'PARTIAL_CLOSE',?,?)",
                Ids.newId("plink"), planId, decisionId, survivor.id(), at);
        Db.execOn(c, "UPDATE plans SET status='POSITION_OPEN',furthest_stage='MANAGE_REVIEW'," +
                        "version=version+1,updated_at=? WHERE id=?", at, planId);
    }

    private void saveAdjustmentOn(Connection c, String userId, String planId, long expectedVersion,
                                  String receiptId, String transformationAction,
                                  TradeRecord survivor, long actionRealized) throws SQLException {
        PlanRow plan = requireOwned(c, planId, userId, true);
        if (plan.version() != expectedVersion) {
            throw new IllegalStateException("This Plan changed before the position adjustment completed.");
        }
        if (!"POSITION_OPEN".equals(plan.status())) {
            throw new IllegalStateException("Only a Plan with an open position can adjust its legs or stock.");
        }
        requireLinked(c, planId, survivor.id());
        if (!TradeRecord.ACTIVE.equals(survivor.status())) {
            throw new IllegalStateException("The adjustment did not leave an active position.");
        }
        OffsetDateTime at = now();
        String decisionId = latestDecisionId(c, planId);
        Db.execOn(c, "INSERT INTO plan_management_action(id,plan_id,decision_id,trade_id,receipt_id,kind,action_at," +
                        "realized_cents,note,created_at) VALUES(?,?,?,?,?,'ADJUST',?,?,?,?)",
                Ids.newId("pmgt"), planId, decisionId, survivor.id(), receiptId, at, actionRealized,
                transformationAction + " changed the exact position composition; retained quantities kept their stored fills",
                at);
        Db.execOn(c, "INSERT INTO plan_link(id,plan_id,decision_id,role,trade_id,created_at) " +
                        "VALUES(?,?,?,'ADJUST',?,?)",
                Ids.newId("plink"), planId, decisionId, survivor.id(), at);
        Db.execOn(c, "UPDATE plans SET status='POSITION_OPEN',furthest_stage='MANAGE_REVIEW'," +
                        "version=version+1,updated_at=? WHERE id=?", at, planId);
    }

    private void saveOptionLifecycleOn(Connection c, String userId, String planId, long expectedVersion,
                                       String receiptId, String action, boolean positionSurvives,
                                       TradeRecord changed, long actionRealized, long realizedToDate)
            throws SQLException {
        PlanRow plan = requireOwned(c, planId, userId, true);
        if (plan.version() != expectedVersion) {
            throw new IllegalStateException("This Plan changed before the option lifecycle action completed.");
        }
        if (!"POSITION_OPEN".equals(plan.status())) {
            throw new IllegalStateException("Only a Plan with an open position can record assignment, exercise, or expiration.");
        }
        requireLinked(c, planId, changed.id());
        if (positionSurvives && !TradeRecord.ACTIVE.equals(changed.status())
                && !Set.of("ASSIGNMENT", "EXERCISE").contains(action)) {
            throw new IllegalStateException("The reviewed lifecycle action did not leave the expected position state.");
        }
        OffsetDateTime at = now();
        String decisionId = latestDecisionId(c, planId);
        String note = switch (action) {
            case "ASSIGNMENT" -> "Assigned option converted at its contract strike; option P/L and physical share cash remain separate";
            case "EXERCISE" -> "Exercised option converted at its contract strike; the resulting shares and surviving options remain visible";
            default -> "Expired option was removed using the Plan market's lane clock and disclosed settlement basis";
        };
        Db.execOn(c, "INSERT INTO plan_management_action(id,plan_id,decision_id,trade_id,receipt_id,kind,action_at," +
                        "realized_cents,note,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                Ids.newId("pmgt"), planId, decisionId, changed.id(), receiptId, action, at,
                actionRealized, note, at);
        Db.execOn(c, "INSERT INTO plan_link(id,plan_id,decision_id,role,trade_id,created_at) VALUES(?,?,?,?,?,?)",
                Ids.newId("plink"), planId, decisionId, action, changed.id(), at);
        if (!positionSurvives && decisionId != null) {
            List<DecisionReview> frozen = Db.queryOn(c,
                    "SELECT review_horizon_days,pop FROM plan_decision WHERE id=?",
                    r -> new DecisionReview(r.intv("review_horizon_days"), r.dblOrNull("pop")), decisionId);
            if (!frozen.isEmpty()) {
                DecisionReview decision = frozen.getFirst();
                Db.execOn(c, "INSERT INTO plan_review(id,plan_id,decision_id,category,horizon_days,benchmark_kind," +
                                "realized_cents,predicted_pop,won,reviewed_at,note,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                        Ids.newId("prev"), planId, decisionId, "TRADE_DECISION", decision.horizonDays(),
                        "PLAN_POSITION", realizedToDate, decision.pop(), realizedToDate > 0 ? 1 : 0, at,
                        "Realized option result after " + action.toLowerCase(java.util.Locale.ROOT)
                                + "; any physical share cash remains a separate accounting fact",
                        at);
            }
        }
        Db.execOn(c, "UPDATE plans SET status=?,furthest_stage='MANAGE_REVIEW',version=version+1,updated_at=? WHERE id=?",
                positionSurvives ? "POSITION_OPEN" : "CLOSED", at, planId);
    }

    private static PlanRow requireOwned(Connection c, String planId, String userId, boolean lock) throws SQLException {
        List<PlanRow> rows = Db.queryOn(c, "SELECT user_id,version,status FROM plans WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                r -> new PlanRow(r.str("user_id"), r.lng("version"), r.str("status")), planId);
        if (rows.isEmpty()) throw new ResourceNotFoundException("no such Plan: " + planId);
        PlanRow row = rows.getFirst();
        if (!io.liftandshift.strikebench.util.OwnerScope.id(userId).equals(row.userId())) {
            throw new ResourceNotFoundException("no such Plan: " + planId);
        }
        return row;
    }

    private static void requireLinked(Connection c, String planId, String tradeId) throws SQLException {
        if (Db.queryOn(c, "SELECT 1 ok FROM plan_link WHERE plan_id=? AND trade_id=? LIMIT 1", r -> r.intv("ok"), planId, tradeId).isEmpty()) {
            throw new IllegalStateException("The active trade is not linked to this Plan.");
        }
    }
    private static String latestDecisionId(Connection c, String planId) throws SQLException {
        return Db.queryOn(c, "SELECT id FROM plan_decision WHERE plan_id=? ORDER BY decision_seq DESC LIMIT 1",
                r -> r.str("id"), planId).stream().findFirst().orElse(null);
    }
    private static void insertReview(Connection c, String planId, String decisionId, int horizonDays,
                                     String benchmark, long start, long end, long realized, Double predictedPop,
                                     boolean won, String note, OffsetDateTime now) throws SQLException {
        Db.execOn(c, "INSERT INTO plan_review(id,plan_id,decision_id,category,horizon_days,benchmark_kind," +
                        "benchmark_start_cents,benchmark_end_cents,realized_cents,predicted_pop,won,reviewed_at,note,created_at) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)", Ids.newId("prev"), planId, decisionId,
                "CASH_DECISION", horizonDays, benchmark, start, end, realized, predictedPop, won ? 1 : 0, now, note, now);
    }
    private OffsetDateTime now() { return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    static OffsetDateTime requireMarkTime(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("The market mark has no timestamp; nothing was recorded.");
        }
        try { return OffsetDateTime.parse(raw); }
        catch (RuntimeException e) {
            throw new IllegalStateException("The market mark timestamp is invalid; nothing was recorded.", e);
        }
    }
    private static void put(ObjectNode node, String key, Object value) {
        if (value == null) return;
        if (value instanceof String s) node.put(key, s); else if (value instanceof Integer i) node.put(key, i);
        else if (value instanceof Long l) node.put(key, l); else if (value instanceof Double d) node.put(key, d);
        else if (value instanceof Boolean b) node.put(key, b); else node.set(key, Json.MAPPER.valueToTree(value));
    }
    private record PlanRow(String userId, long version, String status) {}
    private record DecisionReview(int horizonDays, Double pop) {}
}
