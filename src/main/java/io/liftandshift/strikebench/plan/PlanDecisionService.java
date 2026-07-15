package io.liftandshift.strikebench.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.eval.EconomicAssessment;
import io.liftandshift.strikebench.paper.Account;
import io.liftandshift.strikebench.paper.AccountRiskContext;
import io.liftandshift.strikebench.paper.TradePreview;
import io.liftandshift.strikebench.paper.TradeRecord;
import io.liftandshift.strikebench.paper.TradeService;
import io.liftandshift.strikebench.util.Ids;
import io.liftandshift.strikebench.util.Json;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.liftandshift.strikebench.util.OwnerScope;
import io.liftandshift.strikebench.util.ResourceNotFoundException;

/** Freezes one Plan decision and links its execution atomically to the owning Plan. */
public final class PlanDecisionService {
    public static final String MODEL_VERSION = "plan-decision-1";

    public record Input(String userId, Plan.View plan, long expectedVersion, String candidateId,
                        Account account, TradePreview preview, EconomicAssessment economics,
                        AccountRiskContext riskContext, Integer requestedQty,
                        List<String> acknowledgedRisks, String note, AnalysisContext analysis) {}

    public record PreparedTradeDecision(String id, TradeService.TransactionHook hook) {}
    public record PortfolioDecision(ObjectNode decision, String activeTradeId) {}

    private final Db db;
    private final Clock clock;

    public PlanDecisionService(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public PreparedTradeDecision prepareTrade(Input input) {
        String id = Ids.newId("pdec");
        return new PreparedTradeDecision(id, (connection, trade) -> saveOn(connection, id, input, "TRADE", trade));
    }

    public ObjectNode chooseCash(Input input) {
        String id = Ids.newId("pdec");
        db.tx(connection -> {
            saveOn(connection, id, input, "CASH", null);
            return null;
        });
        return latest(input.userId(), input.plan().id());
    }

    public ObjectNode latest(String userId, String planId) {
        return db.with(connection -> {
            requireOwned(connection, planId, userId, false);
            List<ObjectNode> rows = Db.queryOn(connection, "SELECT d.id,d.context_rev,d.candidate_id,d.recommendation_id," +
                            "d.ensemble_id,d.account_id,d.action," +
                            "d.qty,d.proposed_net_cents,d.quote_as_of::text quote_as_of,d.account_nlv_cents," +
                            "d.buying_power_cents,d.risk_capital_cents,d.max_loss_cents,d.max_profit_cents,d.pop," +
                            "d.p_max_profit,d.p_max_loss,d.ev_market_cents,d.ev_histvol_cents,d.cvar_cents," +
                            "d.economic_verdict,d.evidence_provenance,d.model_version,d.study_key,d.review_horizon_days," +
                            "d.created_at::text created_at,(SELECT l.trade_id FROM plan_link l WHERE l.decision_id=d.id " +
                            "AND l.trade_id IS NOT NULL AND l.role IN ('ENTRY','ROLL','ADJUST') " +
                            "ORDER BY l.created_at LIMIT 1) trade_id FROM plan_decision d " +
                            "WHERE d.plan_id=? ORDER BY d.decision_seq DESC LIMIT 1",
                    row -> {
                        ObjectNode node = Json.MAPPER.createObjectNode();
                        put(node, "id", row.str("id")); put(node, "contextRev", row.intv("context_rev"));
                        put(node, "candidateId", row.str("candidate_id"));
                        put(node, "recommendationId", row.str("recommendation_id"));
                        put(node, "ensembleId", row.str("ensemble_id")); put(node, "accountId", row.str("account_id"));
                        put(node, "action", row.str("action")); put(node, "qty", intOrNull(row, "qty"));
                        put(node, "proposedNetCents", row.lngOrNull("proposed_net_cents"));
                        put(node, "quoteAsOf", row.str("quote_as_of")); put(node, "accountNlvCents", row.lngOrNull("account_nlv_cents"));
                        put(node, "buyingPowerCents", row.lngOrNull("buying_power_cents"));
                        put(node, "riskCapitalCents", row.lngOrNull("risk_capital_cents"));
                        put(node, "maxLossCents", row.lngOrNull("max_loss_cents")); put(node, "maxProfitCents", row.lngOrNull("max_profit_cents"));
                        put(node, "pop", row.dblOrNull("pop")); put(node, "pMaxProfit", row.dblOrNull("p_max_profit"));
                        put(node, "pMaxLoss", row.dblOrNull("p_max_loss")); put(node, "evMarketCents", row.lngOrNull("ev_market_cents"));
                        put(node, "evHistvolCents", row.lngOrNull("ev_histvol_cents")); put(node, "cvarCents", row.lngOrNull("cvar_cents"));
                        put(node, "economicVerdict", row.str("economic_verdict")); put(node, "evidenceProvenance", row.str("evidence_provenance"));
                        put(node, "modelVersion", row.str("model_version")); put(node, "studyKey", row.str("study_key"));
                        put(node, "reviewHorizonDays", row.intv("review_horizon_days")); put(node, "createdAt", row.str("created_at"));
                        put(node, "tradeId", row.str("trade_id"));
                        return node;
                    }, planId);
            if (rows.isEmpty()) return null;
            ObjectNode out = rows.getFirst();
            String id = out.path("id").asText();
            ArrayNode legs = out.putArray("legs");
            Db.queryOn(connection, "SELECT leg_index,action,instrument_type,strike_price,expiration::text expiration," +
                            "ratio,bid_price,ask_price,mid_price,fill_price,iv FROM plan_decision_leg " +
                            "WHERE decision_id=? ORDER BY leg_index", row -> {
                        ObjectNode leg = Json.MAPPER.createObjectNode();
                        put(leg, "index", row.intv("leg_index")); put(leg, "action", row.str("action"));
                        put(leg, "type", row.str("instrument_type")); putDecimal(leg, "strikePrice", row.bd("strike_price"));
                        put(leg, "expiration", row.str("expiration")); put(leg, "ratio", row.intv("ratio"));
                        putDecimal(leg, "bidPrice", row.bd("bid_price")); putDecimal(leg, "askPrice", row.bd("ask_price"));
                        putDecimal(leg, "midPrice", row.bd("mid_price")); putDecimal(leg, "fillPrice", row.bd("fill_price"));
                        put(leg, "iv", row.dblOrNull("iv"));
                        return leg;
                    }, id).forEach(legs::add);
            ArrayNode acknowledgments = out.putArray("acknowledgments");
            Db.queryOn(connection, "SELECT ack_key FROM plan_decision_ack WHERE decision_id=? ORDER BY ack_key",
                    row -> row.str("ack_key"), id).forEach(acknowledgments::add);
            ObjectNode metrics = out.putObject("metrics");
            Db.queryOn(connection, "SELECT metric_key,value_number,value_cents,value_text FROM plan_decision_metric " +
                            "WHERE decision_id=? ORDER BY metric_key", row -> new Metric(row.str("metric_key"),
                            row.dblOrNull("value_number"), row.lngOrNull("value_cents"), row.str("value_text")), id)
                    .forEach(metric -> {
                        if (metric.number() != null) metrics.put(metric.key(), metric.number());
                        else if (metric.cents() != null) metrics.put(metric.key(), metric.cents());
                        else metrics.put(metric.key(), metric.text());
                    });
            return out;
        });
    }

    /**
     * One owner-scoped read for the Plan library. The library needs only the latest decision
     * summary and the currently active linked trade; loading the full frozen receipt once per
     * Plan turned Home into an avoidable 2N+1 query path.
     */
    public Map<String, PortfolioDecision> portfolioLatest(String userId) {
        return db.with(connection -> {
            Map<String, PortfolioDecision> out = new LinkedHashMap<>();
            Db.queryOn(connection, "SELECT DISTINCT ON (d.plan_id) d.plan_id,d.id,d.context_rev," +
                            "d.action,d.economic_verdict,d.pop,d.created_at::text created_at," +
                            "(SELECT l.trade_id FROM plan_link l JOIN trades t ON t.id=l.trade_id " +
                            "WHERE l.plan_id=d.plan_id AND l.trade_id IS NOT NULL AND t.status='ACTIVE' " +
                            "ORDER BY l.created_at DESC LIMIT 1) active_trade_id " +
                            "FROM plan_decision d JOIN plans p ON p.id=d.plan_id " +
                            "WHERE p.user_id=? AND p.status<>'ARCHIVED' " +
                            "ORDER BY d.plan_id,d.decision_seq DESC",
                    row -> {
                        ObjectNode decision = Json.MAPPER.createObjectNode();
                        put(decision, "id", row.str("id"));
                        put(decision, "contextRev", row.intv("context_rev"));
                        put(decision, "action", row.str("action"));
                        put(decision, "economicVerdict", row.str("economic_verdict"));
                        put(decision, "pop", row.dblOrNull("pop"));
                        put(decision, "createdAt", row.str("created_at"));
                        return Map.entry(row.str("plan_id"),
                                new PortfolioDecision(decision, row.str("active_trade_id")));
                    }, OwnerScope.id(userId)).forEach(entry -> out.put(entry.getKey(), entry.getValue()));
            return out;
        });
    }

    private void saveOn(Connection connection, String id, Input input, String action, TradeRecord trade) throws SQLException {
        if (input == null || input.plan() == null || input.preview() == null || input.economics() == null) {
            throw new IllegalArgumentException("complete decision inputs are required");
        }
        PlanRow current = requireOwned(connection, input.plan().id(), input.userId(), true);
        if (current.version() != input.expectedVersion() || current.contextRev() != input.plan().context().rev()) {
            throw new IllegalStateException("This Plan changed while the decision was being recorded.");
        }
        if (!"ACTIVE".equals(current.status())) throw new IllegalStateException("This Plan is not ready for another decision.");
        TradePreview preview = input.preview();
        EconomicAssessment economics = input.economics();
        Account account = input.account();
        AccountRiskContext risk = input.riskContext();
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        DecisionReferences references = decisionReferences(connection, input.plan(), input.candidateId(), input.analysis());
        long decisionSeq = Db.queryOn(connection,
                "SELECT COALESCE(MAX(decision_seq),0)+1 seq FROM plan_decision WHERE plan_id=?",
                row -> row.lng("seq"), input.plan().id()).getFirst();
        Number pMaxProfit = nestedNumber(preview.analytics(), "probabilityMap", "pMaxProfit");
        Number pMaxLoss = nestedNumber(preview.analytics(), "probabilityMap", "pMaxLoss");
        Number cvar = nestedNumber(preview.analytics(), "probabilityMap", "cvar95Cents");
        Long actualEntry = trade == null ? preview.entryNetPremiumCents() : trade.entryNetPremiumCents();
        Integer qty = "TRADE".equals(action) ? (trade == null ? null : trade.qty()) : null;
        Db.execOn(connection, "INSERT INTO plan_decision(id,plan_id,decision_seq,context_rev,candidate_id,recommendation_id," +
                        "ensemble_id,account_id,action,qty," +
                        "proposed_net_cents,quote_as_of,account_nlv_cents,buying_power_cents,risk_capital_cents," +
                        "max_loss_cents,max_profit_cents,pop,p_max_profit,p_max_loss,ev_market_cents,ev_histvol_cents," +
                        "cvar_cents,economic_verdict,evidence_provenance,model_version,study_key,review_horizon_days,created_at) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, input.plan().id(), decisionSeq, input.plan().context().rev(), input.candidateId(),
                references.recommendationId(), references.ensembleId(), account.id(), action, qty,
                actualEntry, now, risk == null ? null : risk.nlvCents(), account.buyingPowerCents(),
                risk == null ? null : risk.riskCapitalCents(),
                trade == null ? preview.maxLossCents() : trade.maxLossCents(),
                trade == null ? preview.maxProfitCents() : trade.maxProfitCents(),
                trade == null ? preview.popEntry() : trade.popEntry(), pMaxProfit, pMaxLoss,
                economics.marketEvAfterCostsCents(), economics.realizedVolEvAfterCostsCents(), cvar,
                economics.verdict().name(), preview.evidence().provenance().name(), MODEL_VERSION, references.studyKey(),
                input.plan().context().horizonDays() == null
                        ? io.liftandshift.strikebench.model.Horizon.MONTH.tradingSessions()
                        : input.plan().context().horizonDays(), now);
        if (references.ensembleId() != null) {
            Db.execOn(connection, "UPDATE ensemble_artifact ea SET pinned=1 FROM plan_ensemble pe " +
                            "WHERE pe.id=? AND pe.fingerprint=ea.fingerprint",
                    references.ensembleId());
        }
        persistLegs(connection, id, preview.legs());
        if (input.acknowledgedRisks() != null) for (String key : input.acknowledgedRisks()) {
            if (key != null && !key.isBlank()) Db.execOn(connection,
                    "INSERT INTO plan_decision_ack(decision_id,ack_key,ack_at) VALUES(?,?,?)", id, key, now);
        }
        metric(connection, id, "entryNetPremiumCents", actualEntry, true);
        metric(connection, id, "feesOpenCents", preview.feesOpenCents(), true);
        metric(connection, id, "reserveCents", preview.reserveCents(), true);
        metric(connection, id, "buyingPowerAfterCents", preview.buyingPowerAfterCents(), true);
        metric(connection, id, "underlyingCents", preview.underlyingCents(), true);
        metricNumber(connection, id, "decisionQty", input.requestedQty() == null ? 1 : input.requestedQty());
        Number rate = nestedNumber(preview.analytics(), "rate", "annual");
        if (rate != null) metricNumber(connection, id, "riskFreeRateAnnual", rate);
        metric(connection, id, "economicPlacement", economics.placement(), false);
        metric(connection, id, "economicSummary", economics.summary(), false);
        if (input.note() != null && !input.note().isBlank()) metric(connection, id, "decisionNote", input.note().trim(), false);
        if (trade != null) {
            String role = nextTradeRole(connection, input.plan().id());
            Db.execOn(connection, "INSERT INTO plan_link(id,plan_id,decision_id,role,trade_id,created_at) VALUES(?,?,?,?,?,?)",
                    Ids.newId("plink"), input.plan().id(), id, role, trade.id(), now);
        }
        Db.execOn(connection, "UPDATE plans SET status=?,active_stage='MANAGE_REVIEW',version=version+1,updated_at=? WHERE id=?",
                trade == null ? "DECIDED_CASH" : "POSITION_OPEN", now, input.plan().id());
    }

    private static void persistLegs(Connection connection, String decisionId, List<Map<String, Object>> legs) throws SQLException {
        int index = 0;
        for (Map<String, Object> row : legs == null ? List.<Map<String, Object>>of() : legs) {
            Db.execOn(connection, "INSERT INTO plan_decision_leg(decision_id,leg_index,action,instrument_type," +
                            "strike_price,expiration,ratio,bid_price,ask_price,mid_price,fill_price,iv) " +
                            "VALUES(?,?,?,?,?,CAST(? AS DATE),?,?,?,?,?,?)", decisionId, index++, text(row.get("action")),
                    text(row.get("type")), price(row.get("strike")), text(row.get("expiration")), integer(row.get("ratio"), 1),
                    price(row.get("bid")), price(row.get("ask")), price(row.get("mid")), price(row.get("fill")), decimal(row.get("iv")));
        }
    }

    private static PlanRow requireOwned(Connection connection, String planId, String userId, boolean lock) throws SQLException {
        List<PlanRow> rows = Db.queryOn(connection, "SELECT version,active_context_rev,user_id,status FROM plans WHERE id=?" +
                        (lock ? " FOR UPDATE" : ""), row -> new PlanRow(row.lng("version"),
                        row.intv("active_context_rev"), row.str("user_id"), row.str("status")), planId);
        if (rows.isEmpty()) throw new ResourceNotFoundException("no such Plan: " + planId);
        PlanRow row = rows.getFirst();
        if (!io.liftandshift.strikebench.util.OwnerScope.id(userId).equals(row.userId())) {
            throw new ResourceNotFoundException("no such Plan: " + planId);
        }
        return row;
    }

    private static void metric(Connection c, String id, String key, Object value, boolean cents) throws SQLException {
        if (value == null) return;
        if (cents) Db.execOn(c, "INSERT INTO plan_decision_metric(decision_id,metric_key,value_cents) VALUES(?,?,?)", id, key, value);
        else Db.execOn(c, "INSERT INTO plan_decision_metric(decision_id,metric_key,value_text) VALUES(?,?,?)", id, key, String.valueOf(value));
    }

    private static void metricNumber(Connection c, String id, String key, Number value) throws SQLException {
        if (value != null) Db.execOn(c,
                "INSERT INTO plan_decision_metric(decision_id,metric_key,value_number) VALUES(?,?,?)",
                id, key, value.doubleValue());
    }

    private static String nextTradeRole(Connection c, String planId) throws SQLException {
        boolean prior = !Db.queryOn(c, "SELECT 1 ok FROM plan_link WHERE plan_id=? AND role IN ('ENTRY','ROLL','ADJUST') LIMIT 1",
                row -> row.intv("ok"), planId).isEmpty();
        if (!prior) return "ENTRY";
        String action = Db.queryOn(c, "SELECT kind FROM plan_management_action WHERE plan_id=? " +
                        "ORDER BY action_at DESC,CASE WHEN kind='MARK' THEN 1 ELSE 0 END,created_at DESC LIMIT 1",
                row -> row.str("kind"), planId).stream().findFirst().orElse("ADJUST");
        return "ROLL".equals(action) ? "ROLL" : "ADJUST";
    }

    /** Resolve receipt identities from server-owned rows. Client state can choose neither a
     * foreign candidate nor a convenient ensemble after the fact. Prefer the parametric ensemble
     * actually used for this position; fall back to the current structure-less Plan ensemble. */
    private static DecisionReferences decisionReferences(Connection c, Plan.View plan, String candidateId,
                                                         AnalysisContext analysis)
            throws SQLException {
        String datasetId = analysis != null && analysis.synthetic() ? analysis.datasetId() : null;
        List<String> recommendations = Db.queryOn(c, "SELECT recommendation_id FROM plan_candidate " +
                        "WHERE id=? AND plan_id=? AND context_rev=? AND underlying_symbol=? " +
                        "AND selected=1 AND state='CURRENT'",
                row -> row.str("recommendation_id"), candidateId, plan.id(), plan.context().rev(), plan.symbol());
        if (recommendations.isEmpty()) {
            throw new IllegalStateException("The selected structure is no longer current for this Plan.");
        }
        String ensembleId = Db.queryOn(c, "SELECT ensemble_id FROM plan_outcome_run WHERE plan_id=? " +
                        "AND context_rev=? AND candidate_id=? AND state='CURRENT' AND ensemble_id IS NOT NULL " +
                        "AND dataset_id IS NOT DISTINCT FROM ? " +
                        "ORDER BY CASE WHEN basis='PARAMETRIC' THEN 0 ELSE 1 END,created_at DESC LIMIT 1",
                row -> row.str("ensemble_id"), plan.id(), plan.context().rev(), candidateId, datasetId)
                .stream().findFirst().orElse(null);
        if (ensembleId == null) {
            ensembleId = Db.queryOn(c, "SELECT pe.id FROM plan_ensemble pe JOIN ensemble_artifact ea " +
                            "ON ea.fingerprint=pe.fingerprint WHERE pe.plan_id=? AND pe.context_rev=? " +
                            "AND pe.dataset_id IS NOT DISTINCT FROM ? AND pe.state='CURRENT' " +
                            "ORDER BY CASE WHEN ea.basis='PARAMETRIC' THEN 0 ELSE 1 END," +
                            "pe.created_at DESC LIMIT 1",
                    row -> row.str("id"), plan.id(), plan.context().rev(), datasetId)
                    .stream().findFirst().orElse(null);
        }
        String evidenceBasis = analysis != null && analysis.synthetic() ? "SCENARIO_DATASET"
                : switch (plan.marketKind()) {
                    case OBSERVED -> "OBSERVED_HISTORY";
                    case DEMO -> "DEMO_HISTORY";
                    case SIMULATED -> "SIMULATED_HISTORY";
                };
        String studyKey = Db.queryOn(c, "SELECT study_key FROM plan_evidence WHERE plan_id=? AND context_rev=? " +
                        "AND basis=? AND dataset_id IS NOT DISTINCT FROM ? AND state='CURRENT' " +
                        "ORDER BY created_at DESC LIMIT 1",
                row -> row.str("study_key"), plan.id(), plan.context().rev(), evidenceBasis, datasetId)
                .stream().findFirst().orElse(null);
        return new DecisionReferences(recommendations.getFirst(), ensembleId, studyKey);
    }

    private static Number nestedNumber(Map<String, Object> root, String parent, String child) {
        Object nested = root == null ? null : root.get(parent);
        return nested instanceof Map<?, ?> map && map.get(child) instanceof Number number ? number : null;
    }

    private static BigDecimal price(Object value) {
        if (value == null || String.valueOf(value).isBlank() || "null".equals(String.valueOf(value))) return null;
        try { return new BigDecimal(String.valueOf(value)); }
        catch (RuntimeException ignored) { return null; }
    }
    private static Double decimal(Object value) { try { return value == null ? null : Double.valueOf(String.valueOf(value)); } catch (RuntimeException e) { return null; } }
    private static int integer(Object value, int fallback) { return value instanceof Number n ? n.intValue() : fallback; }
    private static String text(Object value) { return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value); }
    private static Integer intOrNull(Db.Row row, String key) { Long value = row.lngOrNull(key); return value == null ? null : value.intValue(); }
    private static void put(ObjectNode node, String key, Object value) {
        if (value == null) return;
        if (value instanceof String s) node.put(key, s);
        else if (value instanceof Integer i) node.put(key, i);
        else if (value instanceof Long l) node.put(key, l);
        else if (value instanceof Double d) node.put(key, d);
        else if (value instanceof Boolean b) node.put(key, b);
        else node.set(key, Json.MAPPER.valueToTree(value));
    }
    private static void putDecimal(ObjectNode node, String key, BigDecimal value) {
        if (value != null) node.put(key, value.stripTrailingZeros().toPlainString());
    }

    private record PlanRow(long version, int contextRev, String userId, String status) {}
    private record Metric(String key, Double number, Long cents, String text) {}
    private record DecisionReferences(String recommendationId, String ensembleId, String studyKey) {}
}
