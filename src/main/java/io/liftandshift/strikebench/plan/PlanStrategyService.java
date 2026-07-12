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
import java.util.NoSuchElementException;

/** Normalized, exact Strategy-stage competition persistence for one Plan context. */
public final class PlanStrategyService {
    public static final String ENGINE_VERSION = "plan-strategy-1";

    public record SavedRun(String runId, String state, JsonNode result, String createdAt) {}
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
            CurrentPlan current = ownedPlanOn(c, plan.id(), userId, true);
            boolean stillCurrent = current.contextRev() == plan.context().rev();
            String runState = stillCurrent ? "CURRENT" : "STALE";
            if (stillCurrent) {
                Db.execOn(c, "UPDATE plan_strategy_run SET state='STALE' WHERE plan_id=? AND run_kind='COMPETITION' AND state='CURRENT'",
                        plan.id());
                Db.execOn(c, "UPDATE plan_candidate SET state='STALE' WHERE plan_id=? AND state='CURRENT'", plan.id());
            }
            Db.execOn(c, "INSERT INTO plan_strategy_run(id,plan_id,context_rev,run_kind,scope_kind,thesis,horizon," +
                            "risk_mode,intent,risk_budget_cents,ranking_policy,economic_message,favorable_count,mixed_count," +
                            "unfavorable_count,unavailable_count,disclaimer,input_hash,engine_version,state,created_at) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    runId, plan.id(), plan.context().rev(), "COMPETITION", "PLAN",
                    text(result, "thesis"), text(result, "horizon"),
                    text(result, "riskMode"), text(result, "intent"), longOrNull(result, "riskBudgetCents"),
                    text(result, "ranking"), text(result, "economicMessage"), integer(result, "favorableCount"),
                    integer(result, "mixedCount"), integer(result, "unfavorableCount"),
                    integer(result, "unavailableCount"), text(result, "disclaimer"), inputHash,
                    ENGINE_VERSION, runState, now);
            persistParams(c, runId, "", request == null ? Json.MAPPER.createObjectNode() : request);
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
        return new SavedRun(runId, state, result, now.toString());
    }

    public SavedRun latestCompetition(String userId, String planId) {
        return db.with(c -> {
            CurrentPlan plan = ownedPlanOn(c, planId, userId, false);
            List<RunRow> runs = Db.queryOn(c, "SELECT id,thesis,horizon,risk_mode,intent,risk_budget_cents," +
                            "ranking_policy,economic_message,favorable_count,mixed_count,unfavorable_count," +
                            "unavailable_count,disclaimer,state,created_at::text created_at FROM plan_strategy_run " +
                            "WHERE plan_id=? AND context_rev=? AND run_kind='COMPETITION' AND state='CURRENT' " +
                            "ORDER BY created_at DESC LIMIT 1",
                    r -> new RunRow(r.str("id"), r.str("thesis"), r.str("horizon"), r.str("risk_mode"),
                            r.str("intent"), r.lngOrNull("risk_budget_cents"), r.str("ranking_policy"),
                            r.str("economic_message"), r.intv("favorable_count"), r.intv("mixed_count"),
                            r.intv("unfavorable_count"), r.intv("unavailable_count"), r.str("disclaimer"),
                            r.str("state"), r.str("created_at")), planId, plan.contextRev());
            if (runs.isEmpty()) return null;
            RunRow run = runs.getFirst();
            ObjectNode result = Json.MAPPER.createObjectNode();
            result.put("symbol", plan.symbol()); put(result, "thesis", run.thesis());
            put(result, "horizon", run.horizon()); put(result, "riskMode", run.riskMode());
            put(result, "intent", run.intent()); put(result, "riskBudgetCents", run.riskBudgetCents());
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
            return new SavedRun(run.id(), run.state(), result, run.createdAt());
        });
    }

    public Selection select(String userId, String planId, String candidateId, long expectedVersion) {
        return db.tx(c -> {
            CurrentPlan plan = ownedPlanOn(c, planId, userId, true);
            if (plan.version() != expectedVersion) {
                throw new IllegalStateException("This plan changed in another tab. Reload it before choosing a structure.");
            }
            List<String> candidate = Db.queryOn(c, "SELECT id FROM plan_candidate WHERE id=? AND plan_id=? " +
                            "AND context_rev=? AND state='CURRENT'",
                    r -> r.str("id"), candidateId, planId, plan.contextRev());
            if (candidate.isEmpty()) throw new NoSuchElementException("no current candidate " + candidateId);
            Db.execOn(c, "UPDATE plan_candidate SET selected=0 WHERE plan_id=? AND context_rev=?", planId, plan.contextRev());
            Db.execOn(c, "UPDATE plan_candidate SET selected=1 WHERE id=?", candidateId);
            Db.execOn(c, "UPDATE plans SET version=version+1,updated_at=now() WHERE id=?", planId);
            return new Selection(candidateId, plan.version() + 1);
        });
    }

    private static String persistCandidate(java.sql.Connection c, String runId, Plan.View plan, JsonNode n,
                                           int rank, String state, OffsetDateTime now) throws java.sql.SQLException {
        String id = Ids.newId("pcand");
        JsonNode economics = n.path("economics");
        String family = requiredText(n, "strategy");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", id); values.put("plan_id", plan.id()); values.put("context_rev", plan.context().rev());
        values.put("recommendation_id", text(n, "recommendationId"));
        values.put("evaluation_id", text(n, "evaluationId")); values.put("family", family);
        values.put("structure_group", text(n, "structureGroup")); values.put("rank_number", rank);
        values.put("decision_score", doubleOrNull(n, "decisionScore"));
        values.put("ev_market_cents", economicsLong(economics, "marketEvAfterCostsCents", longOrNull(n, "expectedValueCents")));
        values.put("ev_histvol_cents", longOrNull(economics, "realizedVolEvAfterCostsCents"));
        values.put("pop", doubleOrNull(n, "pop")); values.put("assignment_probability", doubleOrNull(n, "assignmentProb"));
        values.put("entry_net_cents", longOrNull(n, "entryNetPremiumCents"));
        values.put("max_loss_cents", longOrNull(n, "maxLossCents")); values.put("max_profit_cents", longOrNull(n, "maxProfitCents"));
        values.put("cvar_cents", longOrNull(n, "cvarCents")); values.put("economic_verdict", text(n, "economicVerdict"));
        values.put("evidence_provenance", text(n, "freshness")); values.put("input_hash", sha256(n));
        values.put("state", state); values.put("selected", 0); values.put("run_id", runId); values.put("source_kind", "RANKED");
        values.put("display_name", text(n, "displayName")); values.put("position_label", text(n, "label"));
        values.put("qty", integerOrNull(n, "qty")); values.put("expected_value_cents", longOrNull(n, "expectedValueCents"));
        values.put("liquidity_score", doubleOrNull(n, "liquidityScore")); values.put("freshness", text(n, "freshness"));
        values.put("screen_score", doubleOrNull(n, "score")); values.put("confidence", doubleOrNull(n, "confidence"));
        values.put("why_considered", text(n, "whyConsidered")); values.put("best_upside", text(n, "bestUpside"));
        values.put("biggest_risk", text(n, "biggestRisk")); values.put("would_invalidate", text(n, "wouldInvalidate"));
        values.put("beginner_explanation", text(n, "beginnerExplanation"));
        values.put("annualized_yield_pct", doubleOrNull(n, "annualizedYieldPct"));
        values.put("effective_price", text(n, "effectivePrice")); values.put("intent_note", text(n, "intentNote"));
        values.put("uses_held_shares", boolInt(n, "usesHeldShares")); values.put("shares_needed", integerOrNull(n, "sharesNeeded"));
        values.put("combined_max_loss_cents", longOrNull(n, "combinedMaxLossCents"));
        values.put("decision_viable", boolInt(n, "decisionViable"));
        values.put("structurally_eligible", boolInt(n, "structurallyEligible"));
        values.put("economic_placement", text(n, "economicPlacement")); values.put("economic_label", text(economics, "label"));
        values.put("economic_summary", text(economics, "summary"));
        values.put("estimated_roundtrip_fees_cents", longOrNull(economics, "estimatedRoundTripFeesCents"));
        values.put("market_ev_pct_of_risk", doubleOrNull(economics, "marketEvPctOfRisk"));
        values.put("observed_evidence", boolInt(economics, "observedEvidence"));
        values.put("created_at", now);
        String columns = String.join(",", values.keySet());
        String placeholders = String.join(",", java.util.Collections.nCopies(values.size(), "?"));
        Db.execOn(c, "INSERT INTO plan_candidate(" + columns + ") VALUES(" + placeholders + ")", values.values().toArray());
        persistLegs(c, id, n.path("legs"));
        persistIndexedNumbers(c, "plan_candidate_breakeven", "breakeven_index", "price", id, n.path("breakevens"));
        persistIndexedStrings(c, "plan_candidate_intent", "intent_index", "intent", id, n.path("intents"));
        persistMessages(c, id, "WARNING", n.path("warnings"));
        persistMessages(c, id, "ECONOMIC_REASON", economics.path("reasons"));
        return id;
    }

    private static ObjectNode loadCandidate(java.sql.Connection c, CandidateRow r) throws java.sql.SQLException {
        ObjectNode n = Json.MAPPER.createObjectNode();
        put(n, "id", r.id()); put(n, "strategy", r.family()); put(n, "displayName", r.displayName());
        put(n, "structureGroup", r.structureGroup()); put(n, "label", r.label()); put(n, "qty", r.qty());
        put(n, "entryNetPremiumCents", r.entryNet()); put(n, "maxProfitCents", r.maxProfit());
        put(n, "maxLossCents", r.maxLoss()); put(n, "pop", r.pop()); put(n, "expectedValueCents", r.expectedValue());
        put(n, "liquidityScore", r.liquidity()); put(n, "freshness", r.freshness()); put(n, "score", r.screenScore());
        put(n, "confidence", r.confidence()); put(n, "whyConsidered", r.why()); put(n, "bestUpside", r.upside());
        put(n, "biggestRisk", r.risk()); put(n, "wouldInvalidate", r.invalidate());
        put(n, "beginnerExplanation", r.beginner()); put(n, "intent", r.intent());
        put(n, "assignmentProb", r.assignment()); put(n, "annualizedYieldPct", r.annualized());
        put(n, "effectivePrice", r.effectivePrice()); put(n, "intentNote", r.intentNote());
        put(n, "usesHeldShares", r.usesHeld()); put(n, "sharesNeeded", r.sharesNeeded());
        put(n, "combinedMaxLossCents", r.combinedMaxLoss()); put(n, "decisionScore", r.decisionScore());
        put(n, "decisionViable", r.decisionViable()); put(n, "structurallyEligible", r.structurallyEligible());
        put(n, "economicVerdict", r.economicVerdict()); put(n, "economicPlacement", r.economicPlacement());
        n.put("selected", r.selected());
        n.set("legs", loadLegs(c, r.id()));
        n.set("breakevens", loadNumbers(c, "plan_candidate_breakeven", "breakeven_index", "price", r.id()));
        n.set("intents", loadStrings(c, "plan_candidate_intent", "intent_index", "intent", "candidate_id", r.id()));
        n.set("warnings", loadMessages(c, r.id(), "WARNING"));
        if (r.economicVerdict() != null || r.economicSummary() != null) {
            ObjectNode economics = n.putObject("economics");
            put(economics, "verdict", r.economicVerdict()); put(economics, "placement", r.economicPlacement());
            put(economics, "label", r.economicLabel()); put(economics, "summary", r.economicSummary());
            put(economics, "marketEvAfterCostsCents", r.evMarket());
            put(economics, "realizedVolEvAfterCostsCents", r.evHist());
            put(economics, "estimatedRoundTripFeesCents", r.roundTripFees());
            put(economics, "marketEvPctOfRisk", r.marketEvPct()); put(economics, "observedEvidence", r.observed());
            economics.set("reasons", loadMessages(c, r.id(), "ECONOMIC_REASON"));
        }
        return n;
    }

    private static String candidateSelect() {
        return "SELECT pc.id,pc.family,pc.display_name,pc.structure_group,pc.position_label,pc.qty," +
                "pc.entry_net_cents,pc.max_profit_cents,pc.max_loss_cents,pc.pop,pc.expected_value_cents," +
                "pc.liquidity_score,pc.freshness,pc.screen_score,pc.confidence,pc.why_considered,pc.best_upside," +
                "pc.biggest_risk,pc.would_invalidate,pc.beginner_explanation,pc.assignment_probability," +
                "pc.annualized_yield_pct,pc.effective_price,pc.intent_note,pc.uses_held_shares,pc.shares_needed," +
                "pc.combined_max_loss_cents,pc.decision_score,pc.decision_viable,pc.structurally_eligible," +
                "pc.economic_verdict,pc.economic_placement,pc.economic_label,pc.economic_summary," +
                "pc.ev_market_cents,pc.ev_histvol_cents,pc.estimated_roundtrip_fees_cents,pc.market_ev_pct_of_risk," +
                "pc.observed_evidence,pc.selected,psr.intent FROM plan_candidate pc " +
                "JOIN plan_strategy_run psr ON psr.id=pc.run_id";
    }

    private static CandidateRow candidateRow(Db.Row r) {
        return new CandidateRow(r.str("id"), r.str("family"), r.str("display_name"), r.str("structure_group"),
                r.str("position_label"), integerOrNull(r, "qty"), r.lngOrNull("entry_net_cents"),
                r.lngOrNull("max_profit_cents"), r.lngOrNull("max_loss_cents"), r.dblOrNull("pop"),
                r.lngOrNull("expected_value_cents"), r.dblOrNull("liquidity_score"), r.str("freshness"),
                r.dblOrNull("screen_score"), r.dblOrNull("confidence"), r.str("why_considered"),
                r.str("best_upside"), r.str("biggest_risk"), r.str("would_invalidate"),
                r.str("beginner_explanation"), r.str("intent"), r.dblOrNull("assignment_probability"),
                r.dblOrNull("annualized_yield_pct"), r.str("effective_price"), r.str("intent_note"),
                boolOrNull(r, "uses_held_shares"), integerOrNull(r, "shares_needed"),
                r.lngOrNull("combined_max_loss_cents"), r.dblOrNull("decision_score"),
                boolOrNull(r, "decision_viable"), boolOrNull(r, "structurally_eligible"),
                r.str("economic_verdict"), r.str("economic_placement"), r.str("economic_label"),
                r.str("economic_summary"), r.lngOrNull("ev_market_cents"), r.lngOrNull("ev_histvol_cents"),
                r.lngOrNull("estimated_roundtrip_fees_cents"), r.dblOrNull("market_ev_pct_of_risk"),
                boolOrNull(r, "observed_evidence"), r.bool("selected"));
    }

    private static void persistLegs(java.sql.Connection c, String id, JsonNode legs) throws java.sql.SQLException {
        int index = 0;
        for (JsonNode leg : legs) {
            String type = leg.path("stock").asBoolean(false) ? "STOCK" : requiredText(leg, "type").toUpperCase();
            Db.execOn(c, "INSERT INTO plan_candidate_leg(candidate_id,leg_index,action,instrument_type,strike_cents," +
                            "expiration,ratio,entry_price_cents) VALUES(?,?,?,?,?,?,?,?)", id, index++, requiredText(leg, "action").toUpperCase(),
                    type, "STOCK".equals(type) ? null : priceCents(leg.get("strike")),
                    "STOCK".equals(type) || text(leg, "expiration") == null ? null : java.time.LocalDate.parse(text(leg, "expiration")),
                    Math.max(1, leg.path("ratio").asInt(1)), priceCents(leg.get("entryPrice")));
        }
    }

    private static ArrayNode loadLegs(java.sql.Connection c, String id) throws java.sql.SQLException {
        ArrayNode out = Json.MAPPER.createArrayNode();
        Db.queryOn(c, "SELECT action,instrument_type,strike_cents,expiration::text expiration,ratio,entry_price_cents FROM " +
                        "plan_candidate_leg WHERE candidate_id=? ORDER BY leg_index",
                r -> new LegRow(r.str("action"), r.str("instrument_type"), r.lngOrNull("strike_cents"),
                        r.str("expiration"), r.intv("ratio"), r.lngOrNull("entry_price_cents")), id).forEach(leg -> {
            ObjectNode n = out.addObject(); n.put("action", leg.action()); n.put("type", leg.type());
            if (leg.strikeCents() != null) n.put("strike", decimalString(leg.strikeCents()));
            if (leg.expiration() != null) n.put("expiration", leg.expiration());
            n.put("ratio", leg.ratio());
            if (leg.entryPriceCents() != null) n.put("entryPrice", decimalString(leg.entryPriceCents()));
        });
        return out;
    }

    private static void persistParams(java.sql.Connection c, String runId, String prefix, JsonNode node)
            throws java.sql.SQLException {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                try { persistParams(c, runId, prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey(), entry.getValue()); }
                catch (java.sql.SQLException e) { throw new SqlRuntimeException(e); }
            });
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) persistParams(c, runId, prefix + "[" + i + "]", node.get(i));
            return;
        }
        if (prefix.isBlank()) return;
        if (node.isBoolean()) {
            Db.execOn(c, "INSERT INTO plan_strategy_param(run_id,param_key,value_boolean) VALUES(?,?,?)",
                    runId, prefix, node.asBoolean() ? 1 : 0);
        } else if (node.isNumber() && prefix.toLowerCase().endsWith("cents")) {
            Db.execOn(c, "INSERT INTO plan_strategy_param(run_id,param_key,value_cents) VALUES(?,?,?)",
                    runId, prefix, node.longValue());
        } else if (node.isNumber()) {
            Db.execOn(c, "INSERT INTO plan_strategy_param(run_id,param_key,value_number) VALUES(?,?,?)",
                    runId, prefix, node.doubleValue());
        } else {
            Db.execOn(c, "INSERT INTO plan_strategy_param(run_id,param_key,value_text) VALUES(?,?,?)",
                    runId, prefix, node.asText());
        }
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
        int i = 0; for (JsonNode value : values) Db.execOn(c, "INSERT INTO " + table +
                        "(candidate_id," + indexCol + "," + valueCol + ") VALUES(?,?,?)", id, i++, value.decimalValue());
    }

    private static void persistMessages(java.sql.Connection c, String id, String kind, JsonNode values)
            throws java.sql.SQLException {
        int i = 0; for (JsonNode value : values) Db.execOn(c, "INSERT INTO plan_candidate_message(candidate_id," +
                        "message_kind,message_index,message) VALUES(?,?,?,?)", id, kind, i++, value.asText());
    }

    private static ArrayNode loadMessages(java.sql.Connection c, String id, String kind) throws java.sql.SQLException {
        ArrayNode out = Json.MAPPER.createArrayNode();
        Db.queryOn(c, "SELECT message FROM plan_candidate_message WHERE candidate_id=? AND message_kind=? " +
                        "ORDER BY message_index", r -> r.str("message"), id, kind).forEach(out::add);
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
                id, userId, userId);
        if (rows.isEmpty()) throw new NoSuchElementException("no such plan: " + id);
        return rows.getFirst();
    }

    private static String sha256(JsonNode node) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Json.canonical(node).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("Could not identify strategy data", e); }
    }

    private static String ownerClause(String column) {
        return "(" + column + "=?::text OR (?::text IS NULL AND " + column + " IS NULL))";
    }

    private static String requiredText(JsonNode n, String key) {
        String value = text(n, key); if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }
    private static String text(JsonNode n, String key) {
        JsonNode value = n == null ? null : n.get(key); return value == null || value.isNull() ? null : value.asText();
    }
    private static Long longOrNull(JsonNode n, String key) {
        JsonNode value = n == null ? null : n.get(key); return value == null || value.isNull() ? null : value.longValue();
    }
    private static Long economicsLong(JsonNode n, String key, Long fallback) {
        Long value = longOrNull(n, key); return value == null ? fallback : value;
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
    private static Long priceCents(JsonNode n) {
        return n == null || n.isNull() ? null : new BigDecimal(n.asText()).movePointRight(2).longValueExact();
    }
    private static String decimalString(long cents) {
        return BigDecimal.valueOf(cents, 2).stripTrailingZeros().toPlainString();
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
                          Long riskBudgetCents, String ranking, String economicMessage, int favorable,
                          int mixed, int unfavorable, int unavailable, String disclaimer, String state,
                          String createdAt) {}
    private record LegRow(String action, String type, Long strikeCents, String expiration, int ratio,
                          Long entryPriceCents) {}
    private record CandidateRow(String id, String family, String displayName, String structureGroup, String label,
                                Integer qty, Long entryNet, Long maxProfit, Long maxLoss, Double pop,
                                Long expectedValue, Double liquidity, String freshness, Double screenScore,
                                Double confidence, String why, String upside, String risk, String invalidate,
                                String beginner, String intent, Double assignment, Double annualized,
                                String effectivePrice, String intentNote, Boolean usesHeld, Integer sharesNeeded,
                                Long combinedMaxLoss, Double decisionScore, Boolean decisionViable,
                                Boolean structurallyEligible, String economicVerdict, String economicPlacement,
                                String economicLabel, String economicSummary, Long evMarket, Long evHist,
                                Long roundTripFees, Double marketEvPct, Boolean observed, boolean selected) {}

    private static final class SqlRuntimeException extends RuntimeException {
        SqlRuntimeException(java.sql.SQLException cause) { super(cause); }
    }
}
