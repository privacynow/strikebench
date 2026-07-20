package io.liftandshift.strikebench.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.util.Ids;
import io.liftandshift.strikebench.util.Json;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.liftandshift.strikebench.util.ResourceNotFoundException;

/** Normalized, exact Strategy-stage competition persistence for one Plan context. */
public final class PlanStrategyService {
    public static final String ENGINE_VERSION = "plan-strategy-1";

    /** inputHash identifies the canonical server-side request snapshot that produced this run. */
    public record SavedRun(String runId, String state, String inputHash, JsonNode result, String createdAt) {}
    public record Selection(String candidateId, long planVersion) {}

    private final Db db;
    private final Clock clock;

    public PlanStrategyService(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public SavedRun saveCompetition(String userId, Plan.View plan, JsonNode request, JsonNode rawResult) {
        if (rawResult == null || !rawResult.isObject()) throw new IllegalArgumentException("strategy result is required");
        ObjectNode result = (ObjectNode) rawResult.deepCopy();
        String runId = Ids.newId("psr");
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String inputHash = sha256(request == null ? Json.MAPPER.createObjectNode() : request);
        String state = db.tx(c -> {
            PlanWriteGuard.requireMutable(c, plan.id(), userId);
            CurrentPlan current = ownedPlanOn(c, plan.id(), userId, true);
            boolean stillCurrent = current.contextRev() == plan.context().rev();
            String runState = stillCurrent ? "CURRENT" : "STALE";
            if (stillCurrent) {
                boolean independentSelection = !Db.queryOn(c,
                        "SELECT pc.id FROM plan_candidate pc JOIN plan_strategy_run psr ON psr.id=pc.run_id " +
                                "WHERE pc.plan_id=? AND pc.context_rev=? AND pc.state='CURRENT' AND pc.selected=1 " +
                                "AND psr.run_kind<>'COMPETITION' LIMIT 1",
                        r -> r.str("id"), plan.id(), plan.context().rev()).isEmpty();
                if (independentSelection) {
                    markStrategyComparisonStale(c, plan.id(), plan.context().rev());
                } else {
                    markStrategyFieldDependentsStale(c, plan.id(), plan.context().rev());
                }
                Db.execOn(c, "UPDATE plan_strategy_run SET state='STALE' WHERE plan_id=? AND run_kind='COMPETITION' AND state='CURRENT'",
                        plan.id());
                Db.execOn(c, "UPDATE plan_candidate pc SET state='STALE' FROM plan_strategy_run psr " +
                                "WHERE pc.run_id=psr.id AND pc.plan_id=? AND pc.context_rev=? " +
                                "AND pc.state='CURRENT' AND psr.run_kind='COMPETITION'",
                        plan.id(), plan.context().rev());
            }
            Db.execOn(c, "INSERT INTO plan_strategy_run(id,plan_id,context_rev,run_kind,scope_kind,thesis,horizon," +
                            "risk_mode,intent,risk_budget_cents,spot_cents,ranking_policy,economic_message,favorable_count,mixed_count," +
                            "unfavorable_count,unavailable_count,disclaimer,request_snapshot,input_hash,engine_version,state,created_at) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?)",
                    runId, plan.id(), plan.context().rev(), "COMPETITION", "PLAN",
                    text(result, "thesis"), text(result, "horizon"),
                    text(result, "riskMode"), text(result, "intent"), longOrNull(result, "riskBudgetCents"),
                    longOrNull(result, "spotCents"),
                    text(result, "ranking"), text(result, "economicMessage"), integer(result, "favorableCount"),
                    integer(result, "mixedCount"), integer(result, "unfavorableCount"),
                    integer(result, "unavailableCount"), text(result, "disclaimer"), requestSnapshot(request), inputHash,
                    ENGINE_VERSION, runState, now);
            persistNotes(c, runId, result.path("notes"));
            persistRejections(c, runId, result.path("rejected"));
            int rank = 0;
            for (JsonNode candidate : result.path("candidates")) {
                String candidateId = persistCandidate(c, runId, plan, candidate, ++rank, runState, now);
                if (candidate instanceof ObjectNode object) object.put("id", candidateId);
            }
            return runState;
        });
        result.put("strategyRunId", runId);
        result.put("strategyRunState", state);
        return new SavedRun(runId, state, inputHash, result, now.toString());
    }

    public SavedRun latestCompetition(String userId, String planId) {
        return db.with(c -> {
            CurrentPlan plan = ownedPlanOn(c, planId, userId, false);
            List<RunRow> runs = Db.queryOn(c, "SELECT id,thesis,horizon,risk_mode,intent,risk_budget_cents,spot_cents," +
                            "ranking_policy,economic_message,favorable_count,mixed_count,unfavorable_count," +
                            "unavailable_count,disclaimer,sentiment_scorer_version,input_hash,state,created_at::text created_at FROM plan_strategy_run " +
                            "WHERE plan_id=? AND context_rev=? AND run_kind='COMPETITION' AND state='CURRENT' " +
                            "ORDER BY created_at DESC LIMIT 1",
                    r -> new RunRow(r.str("id"), r.str("thesis"), r.str("horizon"), r.str("risk_mode"),
                            r.str("intent"), r.lngOrNull("risk_budget_cents"), r.lngOrNull("spot_cents"), r.str("ranking_policy"),
                            r.str("economic_message"), r.intv("favorable_count"), r.intv("mixed_count"),
                            r.intv("unfavorable_count"), r.intv("unavailable_count"), r.str("disclaimer"),
                            r.str("sentiment_scorer_version"), r.str("input_hash"), r.str("state"), r.str("created_at")),
                    planId, plan.contextRev());
            if (runs.isEmpty()) return null;
            RunRow run = runs.getFirst();
            ObjectNode result = Json.MAPPER.createObjectNode();
            result.put("symbol", plan.symbol()); put(result, "thesis", run.thesis());
            put(result, "horizon", run.horizon()); put(result, "riskMode", run.riskMode());
            put(result, "intent", run.intent()); put(result, "riskBudgetCents", run.riskBudgetCents());
            put(result, "spotCents", run.spotCents());
            put(result, "ranking", run.ranking()); put(result, "economicMessage", run.economicMessage());
            result.put("favorableCount", run.favorable()); result.put("mixedCount", run.mixed());
            result.put("unfavorableCount", run.unfavorable()); result.put("unavailableCount", run.unavailable());
            put(result, "disclaimer", run.disclaimer());
            result.set("notes", loadStrings(c, "plan_strategy_note", "note_index", "note", "run_id", run.id()));
            result.set("rejected", loadRejections(c, run.id()));
            ArrayNode candidates = result.putArray("candidates");
            List<CandidateRow> rows = Db.queryOn(c,
                    candidateSelect() + " WHERE pc.run_id=? ORDER BY pc.rank_number,pc.created_at",
                    PlanStrategyService::candidateRow, run.id());
            for (CandidateRow row : rows) candidates.add(loadCandidate(c, row));
            result.put("strategyRunId", run.id()); result.put("strategyRunState", run.state());
            return new SavedRun(run.id(), run.state(), run.inputHash(), result, run.createdAt());
        });
    }

    public SavedRun saveScout(String userId, Plan.View plan, String rawScope, JsonNode request, ObjectNode result) {
        String scope = normalizeScope(rawScope);
        if (result == null) throw new IllegalArgumentException("scout result is required");
        String sentimentScorerVersion = requiredText(result, "sentimentScorerVersion");
        String runId = Ids.newId("psr");
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String inputHash = sha256(request == null ? Json.MAPPER.createObjectNode() : request);
        String state = db.tx(c -> {
            PlanWriteGuard.requireMutable(c, plan.id(), userId);
            CurrentPlan current = ownedPlanOn(c, plan.id(), userId, true);
            boolean stillCurrent = current.contextRev() == plan.context().rev();
            String runState = stillCurrent ? "CURRENT" : "STALE";
            if (stillCurrent) {
                Db.execOn(c, "UPDATE plan_strategy_run SET state='STALE' WHERE plan_id=? AND run_kind='SCOUT' " +
                                "AND scope_kind=? AND state='CURRENT'", plan.id(), scope);
                Db.execOn(c, "UPDATE plan_candidate SET state='STALE' WHERE run_id IN " +
                                "(SELECT id FROM plan_strategy_run WHERE plan_id=? AND run_kind='SCOUT' AND scope_kind=? AND state='STALE')",
                        plan.id(), scope);
            }
            Db.execOn(c, "INSERT INTO plan_strategy_run(id,plan_id,context_rev,run_kind,scope_kind,thesis,horizon," +
                            "risk_mode,intent,risk_budget_cents,ranking_policy,economic_message,favorable_count,mixed_count," +
                            "unfavorable_count,unavailable_count,disclaimer,request_snapshot,input_hash,engine_version," +
                            "sentiment_scorer_version,state,created_at) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?)",
                    runId, plan.id(), plan.context().rev(), "SCOUT", scope,
                    text(result, "thesis"), text(result, "horizon"), text(result, "riskMode"),
                    text(result, "intent"), longOrNull(result, "riskBudgetCents"), "SCOUT_OPPORTUNITY",
                    text(result, "economicMessage"), integer(result, "favorableCount"), integer(result, "mixedCount"),
                    integer(result, "unfavorableCount"), integer(result, "unavailableCount"),
                    text(result, "disclaimer"), requestSnapshot(request), inputHash, ENGINE_VERSION,
                    sentimentScorerVersion, runState, now);
            persistNotes(c, runId, result.path("notes"));
            int rank = 0;
            for (JsonNode candidate : result.path("candidates")) {
                if (candidate instanceof ObjectNode object) {
                    object.put("sentimentScorerVersion", sentimentScorerVersion);
                }
                String id = persistCandidate(c, runId, plan, candidate, ++rank, runState, now, "SCOUT");
                if (candidate instanceof ObjectNode object) object.put("id", id);
            }
            return runState;
        });
        result.put("strategyRunId", runId);
        result.put("strategyRunState", state);
        return new SavedRun(runId, state, inputHash, result, now.toString());
    }

    public SavedRun latestScout(String userId, String planId, String rawScope) {
        String scope = normalizeScope(rawScope);
        return db.with(c -> {
            CurrentPlan plan = ownedPlanOn(c, planId, userId, false);
            List<RunRow> runs = Db.queryOn(c, "SELECT id,thesis,horizon,risk_mode,intent,risk_budget_cents,spot_cents," +
                            "ranking_policy,economic_message,favorable_count,mixed_count,unfavorable_count," +
                            "unavailable_count,disclaimer,sentiment_scorer_version,input_hash,state,created_at::text created_at FROM plan_strategy_run " +
                            "WHERE plan_id=? AND context_rev=? AND run_kind='SCOUT' AND scope_kind=? AND state='CURRENT' " +
                            "ORDER BY created_at DESC LIMIT 1",
                    r -> new RunRow(r.str("id"), r.str("thesis"), r.str("horizon"), r.str("risk_mode"),
                            r.str("intent"), r.lngOrNull("risk_budget_cents"), r.lngOrNull("spot_cents"), r.str("ranking_policy"),
                            r.str("economic_message"), r.intv("favorable_count"), r.intv("mixed_count"),
                            r.intv("unfavorable_count"), r.intv("unavailable_count"), r.str("disclaimer"),
                            r.str("sentiment_scorer_version"), r.str("input_hash"), r.str("state"), r.str("created_at")),
                    planId, plan.contextRev(), scope);
            if (runs.isEmpty()) return null;
            RunRow run = runs.getFirst();
            ObjectNode result = Json.MAPPER.createObjectNode();
            result.put("symbol", plan.symbol()); result.put("scope", scope);
            put(result, "thesis", run.thesis()); put(result, "horizon", run.horizon());
            put(result, "riskMode", run.riskMode()); put(result, "intent", run.intent());
            put(result, "riskBudgetCents", run.riskBudgetCents()); put(result, "economicMessage", run.economicMessage());
            result.put("favorableCount", run.favorable()); result.put("mixedCount", run.mixed());
            result.put("unfavorableCount", run.unfavorable()); result.put("unavailableCount", run.unavailable());
            put(result, "disclaimer", run.disclaimer());
            put(result, "sentimentScorerVersion", run.sentimentScorerVersion());
            result.set("notes", loadStrings(c, "plan_strategy_note", "note_index", "note", "run_id", run.id()));
            ArrayNode candidates = result.putArray("candidates");
            List<CandidateRow> rows = Db.queryOn(c,
                    candidateSelect() + " WHERE pc.run_id=? ORDER BY pc.rank_number,pc.created_at",
                    PlanStrategyService::candidateRow, run.id());
            for (CandidateRow row : rows) candidates.add(loadCandidate(c, row));
            result.put("strategyRunId", run.id()); result.put("strategyRunState", run.state());
            return new SavedRun(run.id(), run.state(), run.inputHash(), result, run.createdAt());
        });
    }

    public Selection select(String userId, String planId, String candidateId, long expectedVersion) {
        return db.tx(c -> {
            PlanWriteGuard.requireMutable(c, planId, userId);
            CurrentPlan plan = ownedPlanOn(c, planId, userId, true);
            if (plan.version() != expectedVersion) {
                throw new IllegalStateException("This plan changed in another tab. Reload it before choosing a structure.");
            }
            List<String> candidate = Db.queryOn(c, "SELECT id FROM plan_candidate WHERE id=? AND plan_id=? " +
                            "AND context_rev=? AND underlying_symbol=? AND state='CURRENT'",
                    r -> r.str("id"), candidateId, planId, plan.contextRev(), plan.symbol());
            if (candidate.isEmpty()) throw new ResourceNotFoundException("no current candidate " + candidateId);
            String prior = Db.queryOn(c, "SELECT id FROM plan_candidate WHERE plan_id=? AND context_rev=? " +
                            "AND state='CURRENT' AND selected=1 ORDER BY created_at DESC LIMIT 1",
                    r -> r.str("id"), planId, plan.contextRev()).stream().findFirst().orElse(null);
            if (candidateId.equals(prior)) return new Selection(candidateId, plan.version());
            markSelectedPositionDependentsStale(c, planId, plan.contextRev());
            Db.execOn(c, "UPDATE plan_candidate SET selected=0 WHERE plan_id=? AND context_rev=?", planId, plan.contextRev());
            Db.execOn(c, "UPDATE plan_candidate SET selected=1 WHERE id=?", candidateId);
            Db.execOn(c, "UPDATE plans SET furthest_stage='OUTCOMES',version=version+1,updated_at=now() WHERE id=?", planId);
            return new Selection(candidateId, plan.version() + 1);
        });
    }

    /** Clear the current structure while retaining the ranked field as Plan evidence. */
    public Selection clearSelection(String userId, String planId, long expectedVersion) {
        return db.tx(c -> {
            PlanWriteGuard.requireMutable(c, planId, userId);
            CurrentPlan plan = ownedPlanOn(c, planId, userId, true);
            if (plan.version() != expectedVersion) {
                throw new IllegalStateException("This plan changed in another tab. Reload it before clearing the structure.");
            }
            boolean selected = !Db.queryOn(c, "SELECT id FROM plan_candidate WHERE plan_id=? AND context_rev=? " +
                            "AND state='CURRENT' AND selected=1 LIMIT 1",
                    r -> r.str("id"), planId, plan.contextRev()).isEmpty();
            if (!selected) return new Selection(null, plan.version());
            markSelectedPositionDependentsStale(c, planId, plan.contextRev());
            Db.execOn(c, "UPDATE plan_candidate SET selected=0 WHERE plan_id=? AND context_rev=?",
                    planId, plan.contextRev());
            Db.execOn(c, "UPDATE plans SET furthest_stage='STRATEGY',version=version+1,updated_at=now() WHERE id=?", planId);
            return new Selection(null, plan.version() + 1);
        });
    }

    /** Persist an exact analysis; only a mechanically valid package may replace the selected structure. */
    public SavedRun saveCustom(String userId, Plan.View plan, JsonNode request, ObjectNode candidate,
                               long expectedVersion, boolean select) {
        if (candidate == null) throw new IllegalArgumentException("custom candidate is required");
        String runId = Ids.newId("psr");
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String inputHash = sha256(request == null ? Json.MAPPER.createObjectNode() : request);
        record PersistedCustom(String candidateId, boolean clearedSelection) {}
        PersistedCustom persisted = db.tx(c -> {
            PlanWriteGuard.requireMutable(c, plan.id(), userId);
            CurrentPlan current = ownedPlanOn(c, plan.id(), userId, true);
            if (current.version() != expectedVersion) {
                throw new IllegalStateException("This plan changed in another tab. Reload it before saving the structure.");
            }
            if (current.contextRev() != plan.context().rev()) {
                throw new IllegalStateException("This plan's assumptions changed. Reprice the structure before saving it.");
            }
            boolean hadSelection = !Db.queryOn(c,
                    "SELECT id FROM plan_candidate WHERE plan_id=? AND context_rev=? " +
                            "AND state='CURRENT' AND selected=1 LIMIT 1",
                    r -> r.str("id"), plan.id(), plan.context().rev()).isEmpty();
            if (select || hadSelection) markStrategyFieldDependentsStale(c, plan.id(), plan.context().rev());
            Db.execOn(c, "INSERT INTO plan_strategy_run(id,plan_id,context_rev,run_kind,scope_kind,thesis,horizon," +
                            "risk_mode,intent,risk_budget_cents,ranking_policy,economic_message,favorable_count,mixed_count," +
                            "unfavorable_count,unavailable_count,disclaimer,request_snapshot,input_hash,engine_version,state,created_at) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?)",
                    runId, plan.id(), plan.context().rev(), "CUSTOM", "PLAN",
                    plan.context().thesis(), horizonName(plan.context().horizonDays()), plan.context().riskMode(),
                    plan.intent(), null, "EXACT_PACKAGE", "Exact Builder package", 0, 0, 0, 0,
                    "Server-priced exact contracts", requestSnapshot(request), inputHash, ENGINE_VERSION, "CURRENT", now);
            if (select || hadSelection) {
                Db.execOn(c, "UPDATE plan_candidate SET selected=0 WHERE plan_id=? AND context_rev=?",
                        plan.id(), plan.context().rev());
            }
            String id = persistCandidate(c, runId, plan, candidate, 1, "CURRENT", now, "CUSTOM");
            if (select) {
                Db.execOn(c, "UPDATE plan_candidate SET selected=1 WHERE id=?", id);
                Db.execOn(c, "UPDATE plans SET furthest_stage='OUTCOMES',version=version+1,updated_at=now() WHERE id=?", plan.id());
            } else if (hadSelection) {
                Db.execOn(c, "UPDATE plans SET furthest_stage='STRATEGY',version=version+1,updated_at=now() WHERE id=?", plan.id());
            }
            return new PersistedCustom(id, hadSelection && !select);
        });
        candidate.put("id", persisted.candidateId());
        candidate.put("selected", select);
        candidate.put("selectionCleared", persisted.clearedSelection());
        ObjectNode result = Json.MAPPER.createObjectNode();
        result.set("candidate", candidate);
        result.put("strategyRunId", runId);
        result.put("strategyRunState", "CURRENT");
        return new SavedRun(runId, "CURRENT", inputHash, result, now.toString());
    }

    public JsonNode selectedCandidate(String userId, String planId) {
        return db.with(c -> {
            CurrentPlan plan = ownedPlanOn(c, planId, userId, false);
            List<CandidateRow> rows = Db.queryOn(c, candidateSelect() +
                            " WHERE pc.plan_id=? AND pc.context_rev=? AND pc.state='CURRENT' AND pc.selected=1 " +
                            "AND pc.underlying_symbol=? " +
                            "ORDER BY pc.created_at DESC LIMIT 1",
                    PlanStrategyService::candidateRow, planId, plan.contextRev(), plan.symbol());
            return rows.isEmpty() ? null : loadCandidate(c, rows.getFirst());
        });
    }

    /** Most recent selected package from an earlier context revision. It is returned only as a
     * reprice seed; callers must never treat it as the current Plan structure. */
    public JsonNode priorSelectedCandidate(String userId, String planId) {
        return db.with(c -> {
            CurrentPlan plan = ownedPlanOn(c, planId, userId, false);
            List<CandidateRow> rows = Db.queryOn(c, candidateSelect() +
                            " WHERE pc.plan_id=? AND pc.context_rev<>? AND pc.state='STALE' AND pc.selected=1 " +
                            "AND pc.underlying_symbol=? ORDER BY pc.context_rev DESC,pc.created_at DESC LIMIT 1",
                    PlanStrategyService::candidateRow, planId, plan.contextRev(), plan.symbol());
            return rows.isEmpty() ? null : loadCandidate(c, rows.getFirst());
        });
    }

    /** Copy an exact scouted package into its newly-created sibling Plan as the selected structure. */
    public SavedRun copyScoutSelection(String userId, String originPlanId, String candidateId, Plan.View child) {
        ObjectNode candidate = scoutedCandidate(userId, originPlanId, candidateId);
        if (!child.symbol().equalsIgnoreCase(text(candidate, "symbol"))) {
            throw new IllegalArgumentException("scouted candidate symbol does not match the sibling Plan");
        }
        return saveLinkedSelection(userId, child, candidate);
    }

    public ObjectNode scoutedCandidate(String userId, String originPlanId, String candidateId) {
        return (ObjectNode) db.with(c -> {
            ownedPlanOn(c, originPlanId, userId, false);
            List<CandidateRow> rows = Db.queryOn(c, candidateSelect() +
                            " WHERE pc.id=? AND pc.plan_id=? AND pc.source_kind='SCOUT' AND pc.state='CURRENT'",
                    PlanStrategyService::candidateRow, candidateId, originPlanId);
            if (rows.isEmpty()) throw new ResourceNotFoundException("no current scouted candidate " + candidateId);
            return loadCandidate(c, rows.getFirst());
        });
    }

    private SavedRun saveLinkedSelection(String userId, Plan.View plan, ObjectNode candidate) {
        String runId = Ids.newId("psr");
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String inputHash = sha256(candidate);
        String candidateId = db.tx(c -> {
            PlanWriteGuard.requireMutable(c, plan.id(), userId);
            CurrentPlan current = ownedPlanOn(c, plan.id(), userId, true);
            if (current.version() != plan.version()) throw new IllegalStateException("The sibling Plan changed before its structure was saved");
            Db.execOn(c, "INSERT INTO plan_strategy_run(id,plan_id,context_rev,run_kind,scope_kind,thesis,horizon," +
                            "risk_mode,intent,risk_budget_cents,ranking_policy,economic_message,favorable_count,mixed_count," +
                            "unfavorable_count,unavailable_count,disclaimer,request_snapshot,input_hash,engine_version," +
                            "sentiment_scorer_version,state,created_at) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?)",
                    runId, plan.id(), plan.context().rev(), "SCOUT", "PLAN", plan.context().thesis(),
                    horizonName(plan.context().horizonDays()), plan.context().riskMode(), plan.intent(), null,
                    "SCOUT_SELECTION", "Linked from " + text(candidate, "symbol"), 0, 0, 0, 0,
                    "Exact package selected from a linked Scout run", linkedRequestSnapshot(candidate), inputHash,
                    ENGINE_VERSION, requiredText(candidate, "sentimentScorerVersion"), "CURRENT", now);
            String id = persistCandidate(c, runId, plan, candidate, 1, "CURRENT", now, "SCOUT");
            Db.execOn(c, "UPDATE plan_candidate SET selected=1 WHERE id=?", id);
            Db.execOn(c, "UPDATE plans SET furthest_stage='OUTCOMES',version=version+1,updated_at=now() WHERE id=?", plan.id());
            return id;
        });
        candidate.put("id", candidateId); candidate.put("selected", true);
        ObjectNode result = Json.MAPPER.createObjectNode(); result.set("candidate", candidate);
        result.put("strategyRunId", runId); result.put("strategyRunState", "CURRENT");
        return new SavedRun(runId, "CURRENT", inputHash, result, now.toString());
    }

    private static String persistCandidate(java.sql.Connection c, String runId, Plan.View plan, JsonNode n,
                                           int rank, String state, OffsetDateTime now) throws java.sql.SQLException {
        return persistCandidate(c, runId, plan, n, rank, state, now, "RANKED");
    }

    private static String persistCandidate(java.sql.Connection c, String runId, Plan.View plan, JsonNode n,
                                           int rank, String state, OffsetDateTime now, String sourceKind)
            throws java.sql.SQLException {
        String id = Ids.newId("pcand");
        JsonNode evaluation = n.path("evaluation");
        requireCurrentEvaluationReceipt(n, evaluation);
        String family = requiredText(n, "strategy");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", id); values.put("plan_id", plan.id()); values.put("context_rev", plan.context().rev());
        values.put("underlying_symbol", text(n, "symbol") == null ? plan.symbol() : text(n, "symbol"));
        values.put("scout_thesis", text(n, "scoutThesis"));
        values.put("recommendation_id", text(n, "recommendationId"));
        values.put("family", family);
        values.put("structure_group", text(n, "structureGroup")); values.put("rank_number", rank);
        values.put("assignment_probability", doubleOrNull(n, "assignmentProb"));
        values.put("entry_net_cents", longOrNull(n, "entryNetPremiumCents"));
        values.put("max_loss_cents", longOrNull(n, "maxLossCents")); values.put("max_profit_cents", longOrNull(n, "maxProfitCents"));
        values.put("input_hash", sha256(n));
        values.put("state", state); values.put("selected", 0); values.put("run_id", runId); values.put("source_kind", sourceKind);
        values.put("display_name", text(n, "displayName")); values.put("position_label", text(n, "label"));
        values.put("qty", integerOrNull(n, "qty"));
        values.put("liquidity_score", doubleOrNull(n, "liquidityScore")); values.put("freshness", text(n, "freshness"));
        values.put("confidence", doubleOrNull(n, "confidence"));
        values.put("why_considered", text(n, "whyConsidered")); values.put("best_upside", text(n, "bestUpside"));
        values.put("biggest_risk", text(n, "biggestRisk")); values.put("would_invalidate", text(n, "wouldInvalidate"));
        values.put("beginner_explanation", text(n, "beginnerExplanation"));
        values.put("annualized_yield_pct", doubleOrNull(n, "annualizedYieldPct"));
        values.put("effective_price", text(n, "effectivePrice")); values.put("intent_note", text(n, "intentNote"));
        values.put("uses_held_shares", boolInt(n, "usesHeldShares")); values.put("shares_needed", integerOrNull(n, "sharesNeeded"));
        values.put("combined_max_loss_cents", longOrNull(n, "combinedMaxLossCents"));
        values.put("evaluation_snapshot", Json.write(evaluation));
        values.put("created_at", now);
        String columns = String.join(",", values.keySet());
        String placeholders = String.join(",", java.util.Collections.nCopies(values.size(), "?"));
        Db.execOn(c, "INSERT INTO plan_candidate(" + columns + ") VALUES(" + placeholders + ")", values.values().toArray());
        persistLegs(c, id, n.path("legs"));
        persistIndexedNumbers(c, "plan_candidate_breakeven", "breakeven_index", "price", id, n.path("breakevens"));
        persistIndexedStrings(c, "plan_candidate_intent", "intent_index", "intent", id, n.path("intents"));
        persistWarnings(c, id, n.path("warnings"));
        return id;
    }

    private static void requireCurrentEvaluationReceipt(JsonNode candidate, JsonNode evaluation) {
        if (!evaluation.isObject()) {
            throw new IllegalArgumentException("candidate evaluation receipt is required");
        }
        for (String obsolete : List.of("score", "economics", "economicVerdict", "economicPlacement",
                "decisionScore", "evaluationId")) {
            if (candidate.has(obsolete)) {
                throw new IllegalArgumentException("obsolete candidate field is not accepted: " + obsolete);
            }
        }
        if (evaluation.has("id") || evaluation.has("candidate") || evaluation.has("spec")) {
            throw new IllegalArgumentException("full StrategyEvaluation payloads are not accepted; send the current evaluation receipt");
        }
        if (!evaluation.path("available").isBoolean()) {
            throw new IllegalArgumentException("evaluation receipt requires an availability flag");
        }
        if (!evaluation.path("available").asBoolean()) {
            if (text(evaluation, "unavailableReason") == null) {
                throw new IllegalArgumentException("unavailable evaluation receipt requires a reason");
            }
            return;
        }
        if (!evaluation.path("decisionScore").isNumber() || !evaluation.path("viable").isBoolean()) {
            throw new IllegalArgumentException("available evaluation receipt requires decisionScore and viable");
        }
        for (String field : List.of("capital", "volatility", "risk", "evidence", "management", "score",
                "assessment", "stance", "participation", "impliedStance", "ivContext", "coverage", "explanation")) {
            if (!evaluation.path(field).isObject()) {
                throw new IllegalArgumentException("evaluation receipt requires object field " + field);
            }
        }
    }

    private static ObjectNode loadCandidate(java.sql.Connection c, CandidateRow r) throws java.sql.SQLException {
        ObjectNode n = Json.MAPPER.createObjectNode();
        put(n, "id", r.id()); put(n, "symbol", r.symbol()); put(n, "scoutThesis", r.scoutThesis());
        put(n, "recommendationId", r.recommendationId());
        put(n, "sourceKind", r.sourceKind());
        put(n, "sentimentScorerVersion", r.sentimentScorerVersion());
        put(n, "strategy", r.family()); put(n, "displayName", r.displayName());
        put(n, "structureGroup", r.structureGroup()); put(n, "label", r.label()); put(n, "qty", r.qty());
        put(n, "entryNetPremiumCents", r.entryNet()); put(n, "maxProfitCents", r.maxProfit());
        put(n, "maxLossCents", r.maxLoss());
        put(n, "liquidityScore", r.liquidity()); put(n, "freshness", r.freshness());
        put(n, "confidence", r.confidence()); put(n, "whyConsidered", r.why()); put(n, "bestUpside", r.upside());
        put(n, "biggestRisk", r.risk()); put(n, "wouldInvalidate", r.invalidate());
        put(n, "beginnerExplanation", r.beginner()); put(n, "intent", r.intent());
        put(n, "assignmentProb", r.assignment()); put(n, "annualizedYieldPct", r.annualized());
        put(n, "effectivePrice", r.effectivePrice()); put(n, "intentNote", r.intentNote());
        put(n, "usesHeldShares", r.usesHeld()); put(n, "sharesNeeded", r.sharesNeeded());
        put(n, "combinedMaxLossCents", r.combinedMaxLoss());
        n.set("evaluation", Json.parse(r.evaluationSnapshot()));
        n.put("selected", r.selected());
        ArrayNode legs = loadLegs(c, r.id());
        n.set("legs", legs);
        if (r.symbol() != null && r.qty() != null && r.qty() > 0 && !legs.isEmpty()) {
            n.set("identity", Json.MAPPER.valueToTree(
                    io.liftandshift.strikebench.strategy.StrategyCatalog.identify(
                            r.symbol(), r.qty(), identityLegs(legs))));
        }
        n.set("breakevens", loadNumbers(c, "plan_candidate_breakeven", "breakeven_index", "price", r.id()));
        n.set("intents", loadStrings(c, "plan_candidate_intent", "intent_index", "intent", "candidate_id", r.id()));
        n.set("warnings", loadWarnings(c, r.id()));
        return n;
    }

    private static String candidateSelect() {
        return "SELECT pc.id,pc.underlying_symbol,pc.scout_thesis,pc.recommendation_id,pc.source_kind,pc.family,pc.display_name,pc.structure_group,pc.position_label,pc.qty," +
                "pc.entry_net_cents,pc.max_profit_cents,pc.max_loss_cents," +
                "pc.liquidity_score,pc.freshness,pc.confidence,pc.why_considered,pc.best_upside," +
                "pc.biggest_risk,pc.would_invalidate,pc.beginner_explanation,pc.assignment_probability," +
                "pc.annualized_yield_pct,pc.effective_price,pc.intent_note,pc.uses_held_shares,pc.shares_needed," +
                "pc.combined_max_loss_cents,pc.evaluation_snapshot,pc.selected,psr.intent,psr.sentiment_scorer_version " +
                "FROM plan_candidate pc " +
                "JOIN plan_strategy_run psr ON psr.id=pc.run_id";
    }

    private static CandidateRow candidateRow(Db.Row r) {
        return new CandidateRow(r.str("id"), r.str("underlying_symbol"), r.str("scout_thesis"),
                r.str("recommendation_id"), r.str("source_kind"),
                r.str("family"), r.str("display_name"), r.str("structure_group"),
                r.str("position_label"), integerOrNull(r, "qty"), r.lngOrNull("entry_net_cents"),
                r.lngOrNull("max_profit_cents"), r.lngOrNull("max_loss_cents"),
                r.dblOrNull("liquidity_score"), r.str("freshness"),
                r.dblOrNull("confidence"), r.str("why_considered"),
                r.str("best_upside"), r.str("biggest_risk"), r.str("would_invalidate"),
                r.str("beginner_explanation"), r.str("intent"), r.dblOrNull("assignment_probability"),
                r.dblOrNull("annualized_yield_pct"), r.str("effective_price"), r.str("intent_note"),
                boolOrNull(r, "uses_held_shares"), integerOrNull(r, "shares_needed"),
                r.lngOrNull("combined_max_loss_cents"), r.str("evaluation_snapshot"), r.bool("selected"),
                r.str("sentiment_scorer_version"));
    }

    private static void persistLegs(java.sql.Connection c, String id, JsonNode legs) throws java.sql.SQLException {
        int index = 0;
        for (JsonNode leg : legs) {
            String type = leg.path("stock").asBoolean(false) ? "STOCK" : requiredText(leg, "type").toUpperCase();
            if (!"OPEN".equalsIgnoreCase(requiredText(leg, "positionEffect"))) {
                throw new IllegalArgumentException("Plan Strategy candidates require positionEffect=OPEN");
            }
            Db.execOn(c, "INSERT INTO plan_candidate_leg(candidate_id,leg_index,action,instrument_type,strike_price," +
                            "expiration,ratio,multiplier,entry_price) VALUES(?,?,?,?,?,?,?,?,?)", id, index++, requiredText(leg, "action").toUpperCase(),
                    type, "STOCK".equals(type) ? null : priceDecimal(leg.get("strike")),
                    "STOCK".equals(type) || text(leg, "expiration") == null ? null : java.time.LocalDate.parse(text(leg, "expiration")),
                    requiredPositiveInteger(leg, "ratio"),
                    requiredPositiveInteger(leg, "multiplier"), priceDecimal(leg.get("entryPrice")));
        }
    }

    private static ArrayNode loadLegs(java.sql.Connection c, String id) throws java.sql.SQLException {
        ArrayNode out = Json.MAPPER.createArrayNode();
        Db.queryOn(c, "SELECT action,instrument_type,strike_price,expiration::text expiration,ratio,multiplier,entry_price FROM " +
                        "plan_candidate_leg WHERE candidate_id=? ORDER BY leg_index",
                r -> new LegRow(r.str("action"), r.str("instrument_type"), r.bd("strike_price"),
                        r.str("expiration"), r.intv("ratio"), r.intv("multiplier"), r.bd("entry_price")), id).forEach(leg -> {
            ObjectNode n = out.addObject(); n.put("action", leg.action()); n.put("type", leg.type());
            if (leg.strikePrice() != null) n.put("strike", decimalString(leg.strikePrice()));
            if (leg.expiration() != null) n.put("expiration", leg.expiration());
            n.put("ratio", leg.ratio());
            n.put("multiplier", leg.multiplier());
            n.put("positionEffect", "OPEN");
            if (leg.entryPrice() != null) n.put("entryPrice", decimalString(leg.entryPrice()));
        });
        return out;
    }

    private static List<io.liftandshift.strikebench.model.Leg> identityLegs(ArrayNode legs) {
        List<io.liftandshift.strikebench.model.Leg> out = new ArrayList<>();
        for (JsonNode leg : legs) {
            String rawType = requiredText(leg, "type");
            boolean stock = "STOCK".equalsIgnoreCase(rawType);
            out.add(new io.liftandshift.strikebench.model.Leg(
                    io.liftandshift.strikebench.model.LegAction.valueOf(requiredText(leg, "action")),
                    stock ? null : io.liftandshift.strikebench.model.OptionType.valueOf(rawType),
                    stock ? null : new BigDecimal(requiredText(leg, "strike")),
                    stock ? null : java.time.LocalDate.parse(requiredText(leg, "expiration")),
                    requiredPositiveInteger(leg, "ratio"),
                    new BigDecimal(requiredText(leg, "entryPrice")),
                    requiredPositiveInteger(leg, "multiplier")));
        }
        return out;
    }

    private static String requestSnapshot(JsonNode request) {
        return Json.write(request == null || request.isNull() ? Json.MAPPER.createObjectNode() : request);
    }

    private static String linkedRequestSnapshot(ObjectNode candidate) {
        ObjectNode receipt = Json.MAPPER.createObjectNode();
        put(receipt, "sourceCandidateId", text(candidate, "id"));
        put(receipt, "sourceSymbol", text(candidate, "symbol"));
        receipt.put("kind", "SCOUT_SELECTION");
        return Json.write(receipt);
    }

    private static void persistNotes(java.sql.Connection c, String runId, JsonNode notes) throws java.sql.SQLException {
        int i = 0; for (JsonNode note : notes) Db.execOn(c,
                "INSERT INTO plan_strategy_note(run_id,note_index,note) VALUES(?,?,?)", runId, i++, note.asText());
    }

    private static void persistRejections(java.sql.Connection c, String runId, JsonNode rejected)
            throws java.sql.SQLException {
        int i = 0;
        for (JsonNode rejection : rejected) {
            JsonNode reasons = rejection.has("reasons") ? rejection.path("reasons") : rejection.path("blockReasons");
            if (!reasons.isArray() || reasons.isEmpty()) reasons = Json.MAPPER.createArrayNode().add(text(rejection, "reason"));
            int j = 0;
            for (JsonNode reason : reasons) Db.execOn(c, "INSERT INTO plan_strategy_rejection(run_id,rejection_index," +
                            "family,display_name,reason_index,reason) VALUES(?,?,?,?,?,?)", runId, i,
                    requiredText(rejection, "strategy"), text(rejection, "displayName"), j++, reason.asText());
            i++;
        }
    }

    private static ArrayNode loadRejections(java.sql.Connection c, String runId) throws java.sql.SQLException {
        record RejectionRow(int index, String family, String display, String reason) {}
        List<RejectionRow> rows = Db.queryOn(c, "SELECT rejection_index,family,display_name,reason FROM " +
                        "plan_strategy_rejection WHERE run_id=? ORDER BY rejection_index,reason_index",
                r -> new RejectionRow(r.intv("rejection_index"), r.str("family"), r.str("display_name"), r.str("reason")), runId);
        ArrayNode out = Json.MAPPER.createArrayNode(); int current = -1; ObjectNode item = null; ArrayNode reasons = null;
        for (RejectionRow row : rows) {
            if (row.index() != current) {
                current = row.index(); item = out.addObject(); item.put("strategy", row.family());
                put(item, "displayName", row.display()); reasons = item.putArray("reasons");
            }
            reasons.add(row.reason());
        }
        return out;
    }

    private static void persistIndexedStrings(java.sql.Connection c, String table, String indexCol, String valueCol,
                                              String id, JsonNode values) throws java.sql.SQLException {
        int i = 0; for (JsonNode value : values) Db.execOn(c, "INSERT INTO " + table +
                        "(candidate_id," + indexCol + "," + valueCol + ") VALUES(?,?,?)", id, i++, value.asText());
    }

    private static void persistIndexedNumbers(java.sql.Connection c, String table, String indexCol, String valueCol,
                                              String id, JsonNode values) throws java.sql.SQLException {
        int i = 0;
        for (JsonNode value : values) {
            BigDecimal decimal;
            if (value.isNumber()) {
                decimal = value.decimalValue();
            } else if (value.isTextual() && !value.asText().isBlank()) {
                try {
                    decimal = new BigDecimal(value.asText());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("invalid decimal value in " + valueCol + ": " + value.asText(), e);
                }
            } else {
                throw new IllegalArgumentException("invalid decimal value in " + valueCol);
            }
            Db.execOn(c, "INSERT INTO " + table + "(candidate_id," + indexCol + "," + valueCol +
                    ") VALUES(?,?,?)", id, i++, decimal);
        }
    }

    private static void persistWarnings(java.sql.Connection c, String id, JsonNode values)
            throws java.sql.SQLException {
        int i = 0; for (JsonNode value : values) Db.execOn(c, "INSERT INTO plan_candidate_warning(candidate_id," +
                        "warning_index,message) VALUES(?,?,?)", id, i++, value.asText());
    }

    private static ArrayNode loadWarnings(java.sql.Connection c, String id) throws java.sql.SQLException {
        ArrayNode out = Json.MAPPER.createArrayNode();
        Db.queryOn(c, "SELECT message FROM plan_candidate_warning WHERE candidate_id=? " +
                        "ORDER BY warning_index", r -> r.str("message"), id).forEach(out::add);
        return out;
    }

    private static ArrayNode loadNumbers(java.sql.Connection c, String table, String order, String value, String id)
            throws java.sql.SQLException {
        ArrayNode out = Json.MAPPER.createArrayNode();
        Db.queryOn(c, "SELECT " + value + "::text value FROM " + table + " WHERE candidate_id=? ORDER BY " + order,
                r -> r.str("value"), id).forEach(v -> out.add(new BigDecimal(v)));
        return out;
    }

    private static ArrayNode loadStrings(java.sql.Connection c, String table, String order, String value,
                                         String idColumn, String id) throws java.sql.SQLException {
        ArrayNode out = Json.MAPPER.createArrayNode();
        Db.queryOn(c, "SELECT " + value + " value FROM " + table + " WHERE " + idColumn + "=? ORDER BY " + order,
                r -> r.str("value"), id).forEach(out::add);
        return out;
    }

    private static CurrentPlan ownedPlanOn(java.sql.Connection c, String id, String userId, boolean lock)
            throws java.sql.SQLException {
        List<CurrentPlan> rows = Db.queryOn(c, "SELECT symbol,active_context_rev,version FROM plans WHERE id=? AND " +
                        ownerClause("user_id") + (lock ? " FOR UPDATE" : ""),
                r -> new CurrentPlan(r.str("symbol"), r.intv("active_context_rev"), r.lng("version")),
                id, io.liftandshift.strikebench.util.OwnerScope.id(userId));
        if (rows.isEmpty()) throw new ResourceNotFoundException("no such plan: " + id);
        return rows.getFirst();
    }

    private static void markSelectedPositionDependentsStale(java.sql.Connection c, String planId, int contextRev)
            throws java.sql.SQLException {
        Db.execOn(c, "UPDATE plan_outcome_run SET state='STALE' WHERE plan_id=? AND context_rev=? AND state='CURRENT'",
                planId, contextRev);
        Db.execOn(c, "UPDATE plan_backtest SET state='STALE' WHERE plan_id=? AND context_rev=? AND state='CURRENT'",
                planId, contextRev);
    }

    private static void markStrategyFieldDependentsStale(java.sql.Connection c, String planId, int contextRev)
            throws java.sql.SQLException {
        markSelectedPositionDependentsStale(c, planId, contextRev);
        markStrategyComparisonStale(c, planId, contextRev);
    }

    private static void markStrategyComparisonStale(java.sql.Connection c, String planId, int contextRev)
            throws java.sql.SQLException {
        Db.execOn(c, "UPDATE plan_outcome_comparison SET state='STALE' WHERE plan_id=? AND context_rev=? AND state='CURRENT'",
                planId, contextRev);
    }

    private static String sha256(JsonNode node) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Json.canonical(node).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("Could not identify strategy data", e); }
    }

    private static String ownerClause(String column) {
        return column + "=?::text";
    }

    private static String requiredText(JsonNode n, String key) {
        String value = text(n, key); if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }
    private static int requiredPositiveInteger(JsonNode n, String key) {
        JsonNode value = n == null ? null : n.get(key);
        if (value == null || !value.isIntegralNumber() || value.intValue() < 1) {
            throw new IllegalArgumentException(key + " must be a positive integer");
        }
        return value.intValue();
    }
    private static String text(JsonNode n, String key) {
        JsonNode value = n == null ? null : n.get(key); return value == null || value.isNull() ? null : value.asText();
    }
    private static Long longOrNull(JsonNode n, String key) {
        JsonNode value = n == null ? null : n.get(key); return value == null || value.isNull() ? null : value.longValue();
    }
    private static Double doubleOrNull(JsonNode n, String key) {
        JsonNode value = n == null ? null : n.get(key); return value == null || value.isNull() ? null : value.doubleValue();
    }
    private static Integer integerOrNull(JsonNode n, String key) {
        JsonNode value = n == null ? null : n.get(key); return value == null || value.isNull() ? null : value.intValue();
    }
    private static int integer(JsonNode n, String key) { Integer value = integerOrNull(n, key); return value == null ? 0 : value; }
    private static Integer boolInt(JsonNode n, String key) {
        JsonNode value = n == null ? null : n.get(key); return value == null || value.isNull() ? null : value.asBoolean() ? 1 : 0;
    }
    private static BigDecimal priceDecimal(JsonNode n) {
        return n == null || n.isNull() ? null : new BigDecimal(n.asText());
    }
    private static String decimalString(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
    private static String horizonName(Integer days) {
        return io.liftandshift.strikebench.model.Horizon.fromTradingSessions(days).key();
    }
    private static String normalizeScope(String raw) {
        String value = raw == null ? "PEERS" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("PEERS", "ALTERNATIVES", "HEDGES").contains(value)) {
            throw new IllegalArgumentException("scope must be PEERS, ALTERNATIVES, or HEDGES");
        }
        return value;
    }
    private static void put(ObjectNode n, String key, String value) { if (value != null) n.put(key, value); }
    private static void put(ObjectNode n, String key, Long value) { if (value != null) n.put(key, value); }
    private static void put(ObjectNode n, String key, Integer value) { if (value != null) n.put(key, value); }
    private static void put(ObjectNode n, String key, Double value) { if (value != null) n.put(key, value); }
    private static void put(ObjectNode n, String key, Boolean value) { if (value != null) n.put(key, value); }
    private static Integer integerOrNull(Db.Row r, String key) { Long value = r.lngOrNull(key); return value == null ? null : Math.toIntExact(value); }
    private static Boolean boolOrNull(Db.Row r, String key) { Long value = r.lngOrNull(key); return value == null ? null : value != 0; }

    private record CurrentPlan(String symbol, int contextRev, long version) {}
    private record RunRow(String id, String thesis, String horizon, String riskMode, String intent,
                          Long riskBudgetCents, Long spotCents, String ranking, String economicMessage, int favorable,
                          int mixed, int unfavorable, int unavailable, String disclaimer,
                          String sentimentScorerVersion, String inputHash, String state,
                          String createdAt) {}
    private record LegRow(String action, String type, BigDecimal strikePrice, String expiration, int ratio,
                          int multiplier,
                          BigDecimal entryPrice) {}
    private record CandidateRow(String id, String symbol, String scoutThesis, String recommendationId,
                                String sourceKind,
                                String family, String displayName, String structureGroup, String label,
                                Integer qty, Long entryNet, Long maxProfit, Long maxLoss,
                                Double liquidity, String freshness, Double confidence,
                                String why, String upside, String risk, String invalidate,
                                String beginner, String intent, Double assignment, Double annualized,
                                String effectivePrice, String intentNote, Boolean usesHeld, Integer sharesNeeded,
                                Long combinedMaxLoss, String evaluationSnapshot, boolean selected,
                                String sentimentScorerVersion) {}

}
