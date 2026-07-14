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
                    latestDecisionId(c, planId), tradeId, "MARK", parseTime(mark.ts()), mark.underlyingCents(),
                    mark.closeCostCents(), mark.decisionUnrealizedCents() != null ? mark.decisionUnrealizedCents() : mark.unrealizedCents(),
                    mark.popNow(), "Plan mark refreshed from executable closing sides", now());
            return null;
        });
    }

    public TradeService.LifecycleHook lifecycleHook(String userId, String planId, long expectedVersion,
                                                    String kind, boolean prepareRoll) {
        String normalized = kind == null ? "CLOSE" : kind.trim().toUpperCase();
        if (!Set.of("CLOSE", "SETTLE", "ROLL", "VOID").contains(normalized)) {
            throw new IllegalArgumentException("management kind must be CLOSE, SETTLE, ROLL, or VOID");
        }
        return (connection, trade, realized) -> saveLifecycleOn(connection, userId, planId,
                expectedVersion, normalized, prepareRoll, trade, realized);
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

    private void saveLifecycleOn(Connection c, String userId, String planId, long expectedVersion,
                                 String kind, boolean prepareRoll, TradeRecord trade, Long realized) throws SQLException {
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
        Db.execOn(c, "INSERT INTO plan_management_action(id,plan_id,decision_id,trade_id,kind,action_at," +
                        "realized_cents,note,created_at) VALUES(?,?,?,?,?,?,?,?,?)", Ids.newId("pmgt"), planId,
                decisionId, trade.id(), actionKind, now, realized,
                "VOID".equals(kind) ? "Practice trade voided and cash entries reversed" :
                        prepareRoll ? "Position closed; exact package prepared for a roll in this Plan" :
                                "SETTLE".equals(kind) ? "Position settled after expiration" : "Position unwound at executable closing sides", now);
        String linkRole = prepareRoll ? "ROLL" : "CLOSE";
        Db.execOn(c, "INSERT INTO plan_link(id,plan_id,decision_id,role,trade_id,created_at) VALUES(?,?,?,?,?,?)",
                Ids.newId("plink"), planId, decisionId, linkRole, trade.id(), now);
        if (!"VOID".equals(kind) && realized != null && decisionId != null) {
            List<DecisionReview> frozen = Db.queryOn(c,
                    "SELECT review_horizon_days,pop FROM plan_decision WHERE id=?",
                    r -> new DecisionReview(r.intv("review_horizon_days"), r.dblOrNull("pop")), decisionId);
            if (!frozen.isEmpty()) {
                DecisionReview decision = frozen.getFirst();
                Db.execOn(c, "INSERT INTO plan_review(id,plan_id,decision_id,category,horizon_days,benchmark_kind," +
                                "realized_cents,predicted_pop,won,reviewed_at,note,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                        Ids.newId("prev"), planId, decisionId, "TRADE_DECISION", decision.horizonDays(),
                        "PLAN_POSITION", realized, decision.pop(), realized > 0 ? 1 : 0, now,
                        prepareRoll ? "Realized result for this completed roll cycle; the Plan continues"
                                : "Realized result for the Plan position; market provenance remains on the Plan and trade",
                        now);
            }
        }
        Db.execOn(c, "UPDATE plans SET status=?,active_stage=?,version=version+1,updated_at=? WHERE id=?",
                prepareRoll ? "ACTIVE" : "CLOSED", prepareRoll ? "STRATEGY" : "MANAGE_REVIEW", now, planId);
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
    private OffsetDateTime parseTime(String raw) { try { return OffsetDateTime.parse(raw); } catch (Exception e) { return now(); } }
    private static void put(ObjectNode node, String key, Object value) {
        if (value == null) return;
        if (value instanceof String s) node.put(key, s); else if (value instanceof Integer i) node.put(key, i);
        else if (value instanceof Long l) node.put(key, l); else if (value instanceof Double d) node.put(key, d);
        else if (value instanceof Boolean b) node.put(key, b); else node.set(key, Json.MAPPER.valueToTree(value));
    }
    private record PlanRow(String userId, long version, String status) {}
    private record DecisionReview(int horizonDays, Double pop) {}
}
