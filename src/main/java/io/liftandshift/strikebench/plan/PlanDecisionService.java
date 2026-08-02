package io.liftandshift.strikebench.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.eval.EconomicAssessment;
import io.liftandshift.strikebench.market.MarketHours;
import io.liftandshift.strikebench.paper.Account;
import io.liftandshift.strikebench.paper.AccountRiskContext;
import io.liftandshift.strikebench.paper.OrderInstruction;
import io.liftandshift.strikebench.paper.PackagePrice;
import io.liftandshift.strikebench.paper.TradePreview;
import io.liftandshift.strikebench.paper.TradeRecord;
import io.liftandshift.strikebench.paper.TradeService;
import io.liftandshift.strikebench.recommend.DecisionDeclarationPolicy;
import io.liftandshift.strikebench.recommend.LegView;
import io.liftandshift.strikebench.util.Ids;
import io.liftandshift.strikebench.util.Json;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.liftandshift.strikebench.util.OwnerScope;
import io.liftandshift.strikebench.util.ResourceNotFoundException;

/** Freezes one Plan decision and links its execution atomically to the owning Plan. */
public final class PlanDecisionService {
    public static final String MODEL_VERSION = "plan-decision-3";

    public record Input(String userId, Plan.View plan, long expectedVersion, String candidateId,
                        Account account, TradePreview preview, EconomicAssessment economics,
                        AccountRiskContext riskContext, Integer requestedQty,
                        List<String> acknowledgedRisks, String note, AnalysisContext analysis,
                        OrderInstruction orderInstruction) {}

    public record PreparedTradeDecision(String id, TradeService.TransactionHook hook) {}
    public record PortfolioDecision(ObjectNode decision, String activeTradeId) {}

    private final Db db;
    private final Clock clock;

    public PlanDecisionService(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    /**
     * The one place the frozen review horizon becomes a date. The result stores TRADING SESSIONS,
     * and a session count is not a calendar-day count: reading 21 sessions as 21 days scheduled a
     * monthly review more than a week early and benchmarked the cash decision against the wrong close.
     */
    public static LocalDate reviewDueDate(Instant decidedAt, int horizonSessions) {
        if (horizonSessions < 1) {
            throw new IllegalStateException("The frozen decision has no review horizon in trading sessions.");
        }
        return MarketHours.tradingDateAfter(LocalDate.ofInstant(decidedAt, MarketHours.EASTERN), horizonSessions);
    }

    public PreparedTradeDecision prepareTrade(Input input) {
        requirePracticeRisk(input == null ? null : input.preview());
        String id = Ids.newId("pdec");
        return new PreparedTradeDecision(id, (connection, trade, executionPreview) ->
                saveOn(connection, id, input, "TRADE", trade, executionPreview));
    }

    /** The third decision outcome: the user placed this exact structure at their real broker.
     *  The freeze is identical in shape to a trade decision but executes nothing here — the
     *  enclosing transaction (PlanPromotionService) writes the tracked-book ledger row and the
     *  position artifacts, so the decision and its real-mode consequences commit together. */
    public PreparedTradeDecision prepareBroker(Input input) {
        String id = Ids.newId("pdec");
        return new PreparedTradeDecision(id, (connection, trade, executionPreview) ->
                saveOn(connection, id, input, "BROKER", null, null));
    }

    public ObjectNode chooseCash(Input input) {
        String id = Ids.newId("pdec");
        db.tx(connection -> {
            saveOn(connection, id, input, "CASH", null, null);
            return null;
        });
        return latest(input.userId(), input.plan().id());
    }

    public ObjectNode latest(String userId, String planId) {
        return db.with(connection -> {
            requireOwned(connection, planId, userId, false);
            List<ObjectNode> rows = Db.queryOn(connection, "SELECT d.id,d.context_rev,d.candidate_id,d.recommendation_id," +
                            "d.ensemble_id,d.account_id,d.action," +
                            "d.qty,d.package_price::text package_price,d.order_instruction_json::text order_instruction_json," +
                            "d.quote_as_of::text quote_as_of,d.account_nlv_cents," +
                            "d.buying_power_cents,d.risk_capital_cents,d.max_loss_cents,d.max_profit_cents,d.pop," +
                            "d.p_max_profit,d.p_max_loss,d.ev_market_cents,d.ev_histvol_cents,d.cvar_cents," +
                            "d.economic_verdict,d.evidence_provenance,d.model_version,d.study_key,d.review_horizon_sessions," +
                            // NOT ::text: PostgreSQL renders timestamptz in the session's zone
                            // ("2026-07-13 07:30:00-07"), which is neither ISO-8601 nor stable across
                            // machines. The freeze instant is a result; Row.str emits it as UTC ISO.
                            "d.created_at,(SELECT l.trade_id FROM plan_link l WHERE l.decision_id=d.id " +
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
                        node.set("price", Json.parse(row.str("package_price")));
                        if (row.str("order_instruction_json") != null) {
                            node.set("orderInstruction", Json.parse(row.str("order_instruction_json")));
                        }
                        put(node, "quoteAsOf", row.str("quote_as_of")); put(node, "accountNlvCents", row.lngOrNull("account_nlv_cents"));
                        put(node, "buyingPowerCents", row.lngOrNull("buying_power_cents"));
                        put(node, "riskCapitalCents", row.lngOrNull("risk_capital_cents"));
                        putNullable(node, "maxLossCents", row.lngOrNull("max_loss_cents"));
                        put(node, "maxProfitCents", row.lngOrNull("max_profit_cents"));
                        put(node, "pop", row.dblOrNull("pop")); put(node, "pMaxProfit", row.dblOrNull("p_max_profit"));
                        put(node, "pMaxLoss", row.dblOrNull("p_max_loss")); put(node, "evMarketCents", row.lngOrNull("ev_market_cents"));
                        put(node, "evHistvolCents", row.lngOrNull("ev_histvol_cents")); put(node, "cvarCents", row.lngOrNull("cvar_cents"));
                        put(node, "economicVerdict", row.str("economic_verdict")); put(node, "evidenceProvenance", row.str("evidence_provenance"));
                        put(node, "modelVersion", row.str("model_version")); put(node, "studyKey", row.str("study_key"));
                        put(node, "reviewHorizonSessions", row.intv("review_horizon_sessions")); put(node, "createdAt", row.str("created_at"));
                        put(node, "tradeId", row.str("trade_id"));
                        return node;
                    }, planId);
            if (rows.isEmpty()) return null;
            ObjectNode out = rows.getFirst();
            String id = out.path("id").asText();
            ArrayNode legs = out.putArray("legs");
            Db.queryOn(connection, "SELECT leg_index,action,instrument_type,strike_price,expiration::text expiration," +
                            "ratio,multiplier,bid_price,ask_price,mid_price,fill_price,iv FROM plan_decision_leg " +
                            "WHERE decision_id=? ORDER BY leg_index", row -> {
                        ObjectNode leg = Json.MAPPER.createObjectNode();
                        put(leg, "index", row.intv("leg_index")); put(leg, "action", row.str("action"));
                        put(leg, "type", row.str("instrument_type")); putDecimal(leg, "strikePrice", row.bd("strike_price"));
                        put(leg, "expiration", row.str("expiration")); put(leg, "ratio", row.intv("ratio"));
                        put(leg, "multiplier", row.intv("multiplier"));
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
            if (!metrics.has("reserveCents")) metrics.putNull("reserveCents");
            return out;
        });
    }

    /**
     * One owner-scoped read for the Plan library. The library needs only the latest decision
     * summary and the currently active linked trade; loading the full frozen result once per
     * Plan turned Home into an avoidable 2N+1 query path.
     */
    public Map<String, PortfolioDecision> portfolioLatest(String userId) {
        return db.with(connection -> {
            Map<String, PortfolioDecision> out = new LinkedHashMap<>();
            Db.queryOn(connection, "SELECT DISTINCT ON (d.plan_id) d.plan_id,d.id,d.context_rev," +
                            "d.action,d.economic_verdict,d.pop,d.created_at," +
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

    private void saveOn(Connection connection, String id, Input input, String action, TradeRecord trade,
                        TradePreview executionPreview) throws SQLException {
        if (input == null || input.plan() == null || input.preview() == null || input.economics() == null) {
            throw new IllegalArgumentException("complete decision inputs are required");
        }
        PlanRow current = requireOwned(connection, input.plan().id(), input.userId(), true);
        if (current.version() != input.expectedVersion() || current.contextRev() != input.plan().context().rev()) {
            throw new IllegalStateException("This Plan changed while the decision was being recorded.");
        }
        if (!"ACTIVE".equals(current.status())) throw new IllegalStateException("This Plan is not ready for another decision.");
        TradePreview preview = frozenPreview(input.preview(), trade, executionPreview);
        if ("TRADE".equals(action)) requirePracticeRisk(preview);
        EconomicAssessment economics = input.economics();
        Account account = input.account();
        AccountRiskContext risk = input.riskContext();
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        DecisionReferences references = decisionReferences(connection, input.plan(), input.candidateId(), input.analysis());
        long decisionSeq = Db.queryOn(connection,
                "SELECT COALESCE(MAX(decision_seq),0)+1 seq FROM plan_decision WHERE plan_id=?",
                row -> row.lng("seq"), input.plan().id()).getFirst();
        var marketRisk = preview.marketImpliedRisk();
        var probability = marketRisk == null ? null : marketRisk.probabilityMap();
        Number pMaxProfit = probability == null ? null : probability.pMaxProfit();
        Number pMaxLoss = probability == null ? null : probability.pMaxLoss();
        Number cvar = probability == null ? null : probability.cvar95Cents();
        // §3.1: the frozen decision records the ONE §7.2 result that was reviewed. There is no
        // primitive package-net twin: that older column converted an unpriced preview into $0 and
        // gave campaign review a second price authority.
        PackagePrice price = java.util.Objects.requireNonNull(preview.price(),
                "a frozen decision requires the preview's package-price result");
        Integer qty = switch (action) {
            case "TRADE" -> trade == null ? null : trade.qty();
            case "BROKER" -> input.requestedQty();
            default -> null;
        };
        if ("BROKER".equals(action) && (qty == null || qty < 1)) {
            throw new IllegalArgumentException("a broker placement requires the executed quantity");
        }
        // The Plan context's horizon is a TRADING-SESSION count (Horizon.exactTradingSessions), so the
        // result freezes sessions. Only reviewDueDate() may turn them into a calendar date.
        int reviewHorizonSessions = DecisionDeclarationPolicy.requirePlanHorizon(
                "Plan decision review", input.plan().context().horizonDays());
        Long frozenMaxLossCents = trade == null
                ? preview.maxLossCents()
                : Long.valueOf(trade.maxLossCents());
        Db.execOn(connection, "INSERT INTO plan_decision(id,plan_id,decision_seq,context_rev,candidate_id,recommendation_id," +
                        "ensemble_id,account_id,action,qty,package_price,order_instruction_json," +
                        "quote_as_of,account_nlv_cents,buying_power_cents,risk_capital_cents," +
                        "max_loss_cents,max_profit_cents,pop,p_max_profit,p_max_loss,ev_market_cents,ev_histvol_cents," +
                        "cvar_cents,economic_verdict,evidence_provenance,model_version,study_key,review_horizon_sessions,created_at) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, input.plan().id(), decisionSeq, input.plan().context().rev(), input.candidateId(),
                references.recommendationId(), references.ensembleId(), account.id(), action, qty,
                Json.write(price), input.orderInstruction() == null ? null : Json.write(input.orderInstruction()),
                now, risk == null ? null : risk.nlvCents(), account.buyingPowerCents(),
                risk == null ? null : risk.riskCapitalCents(),
                frozenMaxLossCents,
                trade == null ? preview.maxProfitCents() : trade.maxProfitCents(),
                trade == null
                        ? (marketRisk == null ? null : marketRisk.pop())
                        : trade.popEntry(),
                pMaxProfit, pMaxLoss,
                economics.marketEvAfterCostsCents(), economics.realizedVolEvAfterCostsCents(), cvar,
                economics.verdict().name(), preview.evidence().provenance().name(), MODEL_VERSION, references.studyKey(),
                reviewHorizonSessions, now);
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
        Db.execOn(connection, "UPDATE plans SET status=?,furthest_stage='MANAGE_REVIEW',version=version+1,updated_at=? WHERE id=?",
                "CASH".equals(action) ? "DECIDED_CASH" : "POSITION_OPEN", now, input.plan().id());
    }

    private static void persistLegs(Connection connection, String decisionId, List<LegView> legs) throws SQLException {
        int index = 0;
        for (LegView row : legs == null ? List.<LegView>of() : legs) {
            Db.execOn(connection, "INSERT INTO plan_decision_leg(decision_id,leg_index,action,instrument_type," +
                            "strike_price,expiration,ratio,multiplier,bid_price,ask_price,mid_price,fill_price,iv) " +
                            "VALUES(?,?,?,?,?,CAST(? AS DATE),?,?,?,?,?,?,?)", decisionId, index++, row.action(),
                    row.type(), decisionPrice(row.strike()), row.expiration(), row.ratio(), row.multiplier(),
                    decisionPrice(row.quoteBid()), decisionPrice(row.quoteAsk()), decisionPrice(row.quoteMid()),
                    decisionPrice(row.entryPrice()), row.quoteIv());
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

    private static void requirePracticeRisk(TradePreview preview) {
        if (preview == null || !preview.hasRiskFacts()) {
            throw new IllegalStateException(
                    "A Practice trade decision requires reviewed maximum-loss and reserve results.");
        }
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

    /** Resolve result identities from server-owned rows. Client state can choose neither a
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

    static BigDecimal decisionPrice(Object value) {
        if (value == null || String.valueOf(value).isBlank() || "null".equals(String.valueOf(value))) return null;
        try { return new BigDecimal(String.valueOf(value)); }
        catch (RuntimeException e) {
            throw new IllegalStateException("A frozen decision leg contains an invalid price; no result was written.", e);
        }
    }

    /**
     * The create-time preview—not merely package net—is checked against the reviewed decision facts.
     * Underlying/rate/IV changes can alter POP, tails, Greeks, payoff and evidence while leaving an
     * offsetting package net unchanged. Any such difference therefore forces another review.
     *
     * <p>Do not compare the whole transport record here. {@code analytics.evaluatedAtEpochMs} is the
     * time StrikeBench performed the calculation, not a financial input; two otherwise identical
     * previews are necessarily evaluated at different instants. Likewise, future diagnostics do not
     * become decision gates merely because somebody adds a map entry. The typed projection below is
     * the explicit review model: adding a new decision-bearing fact requires adding it there.</p>
     */
    static TradePreview frozenPreview(TradePreview reviewed, TradeRecord trade,
                                      TradePreview execution) {
        TradePreview reviewedPreview = java.util.Objects.requireNonNull(reviewed,
                "a frozen decision requires the reviewed trade preview");
        if (trade == null) return reviewedPreview;
        TradePreview executionPreview = java.util.Objects.requireNonNull(execution,
                "a trade decision requires the create-time trade preview");
        PackagePrice price = executionPreview.price();
        if (!decisionFacts(executionPreview).equals(decisionFacts(reviewedPreview))
                || price == null
                || !price.priced()
                || !java.util.Objects.equals(price.grossPackageNetCents(),
                        trade.entryNetPremiumCents())
                || !java.util.Objects.equals(price.openingFeesCents(),
                        trade.feesOpenCents())) {
            throw new IllegalStateException(
                    "The executable package or its decision evidence changed after review; "
                            + "review the current book before placing it.");
        }
        return executionPreview;
    }

    /**
     * Exact facts that authorize a decision. Ephemeral render/diagnostic metadata is deliberately
     * absent; all economic, risk, evidence, execution, account-impact, payoff and leg facts are
     * explicit. Map-valued analytics are selected by name rather than accepted wholesale.
     */
    private static DecisionFacts decisionFacts(TradePreview p) {
        Map<String, Object> analytics = p.analytics() == null ? Map.of() : p.analytics();
        return new DecisionFacts(
                p.ok(), p.blockReasons(), p.warnings(), p.maxLossCents(), p.maxProfitCents(),
                p.breakevens(), stableMarketRisk(p.marketImpliedRisk()), p.reserveCents(),
                p.cashBeforeCents(), p.cashAfterCents(), p.reservedBeforeCents(),
                p.reservedAfterCents(), p.buyingPowerBeforeCents(), p.buyingPowerAfterCents(),
                p.freshness(), p.evidence(), p.underlyingCents(), p.shortSideExpirationItmProb(),
                stableLegs(p.legs()), p.payoff(), stablePackagePrice(p.price()),
                analytics.get("executionQuality"),
                analytics.get("managementPlan"),
                stableOptionTime(analytics.get("time")),
                analytics.get("greeks"),
                p.marketImpliedRange(),
                analytics.get("freshness"),
                analytics.get("rate"),
                analytics.get("verdict"),
                analytics.get("verdictReason"),
                analytics.get("combinedMaxLossCents"));
    }

    /**
     * Review authorization is an equality check over financial meaning, not over the instant at
     * which an otherwise identical provider response happened to be wrapped. Fixture and live
     * providers can stamp each read independently; those stamps flow into leg {@code asOfEpochMs},
     * the package-price fingerprint and the risk-neutral result fingerprint. Comparing those
     * transport identities made an unchanged executable book impossible to commit.
     *
     * <p>The projections below remove only that observation-clock identity. Every quoted amount,
     * side, source, freshness class, model input, model result, payoff point, Greek, account
     * consequence and execution-quality fact remains in {@link DecisionFacts}. A changed bid,
     * ask, fill, IV, underlying, rate, clock duration, probability, EV, fee, reserve or warning
     * still requires a new review.</p>
     */
    private static List<StableLeg> stableLegs(List<LegView> legs) {
        if (legs == null) return null;
        return legs.stream().map(leg -> new StableLeg(
                leg.action(), leg.type(), leg.strike(), leg.expiration(), leg.ratio(),
                leg.entryPrice(), leg.multiplier(), leg.positionEffect(), leg.quoteBid(),
                leg.quoteAsk(), leg.quoteSource(), leg.quoteFreshness(), leg.quoteIv(),
                leg.quoteDelta(), leg.quoteMid(), leg.fillBasis(), leg.quoteProvenance(),
                leg.quoteDataAge(), leg.quoteGamma(), leg.quoteTheta(), leg.quoteVega())).toList();
    }

    private static StablePackagePrice stablePackagePrice(PackagePrice p) {
        if (p == null) return null;
        return new StablePackagePrice(
                p.quantity(), p.optionNetPremiumCents(), p.stockCashFlowCents(),
                p.grossPackageNetCents(), p.openingFeesCents(),
                p.estimatedRoundTripFeesCents(), p.afterFeeNetCents(),
                p.executableNetCents(), p.restingLimitNetCents(), p.valuationBasis(),
                p.executability(), p.source(), p.freshness(), p.feeSide(),
                p.unavailableReason());
    }

    private static StableOptionTime stableOptionTime(Object value) {
        if (value == null) return null;
        io.liftandshift.strikebench.market.OptionTime.Measure time;
        if (value instanceof io.liftandshift.strikebench.market.OptionTime.Measure measure) {
            time = measure;
        } else {
            time = Json.MAPPER.convertValue(value,
                    io.liftandshift.strikebench.market.OptionTime.Measure.class);
        }
        return new StableOptionTime(time.state(), time.sessions(), time.calendarDays(),
                time.years(), time.expiration(), time.basis());
    }

    private static StableMarketRisk stableMarketRisk(
            io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.RiskNeutralAnalysis result) {
        if (result == null) return null;
        return new StableMarketRisk(
                result.schemaVersion(), result.modelVersion(), result.available(),
                result.unavailableReason(), result.underlyingCents(), result.marketIv(),
                result.riskFreeRate(), stableOptionTime(result.time()),
                result.probabilityMap(), result.expectedValueCents(),
                result.sensitivity(), result.scenarioMasses());
    }

    private record DecisionFacts(
            boolean ok,
            List<String> blockReasons,
            List<String> warnings,
            Long maxLossCents,
            Long maxProfitCents,
            List<String> breakevens,
            StableMarketRisk marketImpliedRisk,
            Long reserveCents,
            long cashBeforeCents,
            long cashAfterCents,
            long reservedBeforeCents,
            long reservedAfterCents,
            long buyingPowerBeforeCents,
            long buyingPowerAfterCents,
            String freshness,
            io.liftandshift.strikebench.model.DataEvidence evidence,
            Long underlyingCents,
            Double shortSideExpirationItmProb,
            List<StableLeg> legs,
            List<Map<String, Object>> payoff,
            StablePackagePrice price,
            Object executionQuality,
            Object managementPlan,
            StableOptionTime time,
            Object greeks,
            io.liftandshift.strikebench.sim.SimulationEngine.MarketImpliedRange marketImpliedRange,
            Object analyticsFreshness,
            Object rate,
            Object verdict,
            Object verdictReason,
            Object combinedMaxLossCents
    ) {}

    /** Decision identity excludes only the observation clock; every financial leg fact remains. */
    private record StableLeg(
            String action, String type, String strike, String expiration, int ratio,
            String entryPrice, int multiplier, String positionEffect, String quoteBid,
            String quoteAsk, String quoteSource, String quoteFreshness, Double quoteIv,
            Double quoteDelta, String quoteMid, String fillBasis, String quoteProvenance,
            String quoteDataAge, Double quoteGamma, Double quoteTheta, Double quoteVega
    ) {}

    private record StablePackagePrice(
            int quantity,
            Long optionNetPremiumCents,
            Long stockCashFlowCents,
            Long grossPackageNetCents,
            Long openingFeesCents,
            Long estimatedRoundTripFeesCents,
            Long afterFeeNetCents,
            Long executableNetCents,
            Long restingLimitNetCents,
            PackagePrice.ValuationBasis valuationBasis,
            OrderInstruction.Executability executability,
            String source,
            String freshness,
            PackagePrice.FeeSide feeSide,
            String unavailableReason
    ) {}

    private record StableOptionTime(
            io.liftandshift.strikebench.market.OptionTime.State state,
            int sessions,
            long calendarDays,
            Double years,
            LocalDate expiration,
            String basis
    ) {}

    private record StableMarketRisk(
            String schemaVersion,
            String modelVersion,
            boolean available,
            String unavailableReason,
            Long underlyingCents,
            Double marketIv,
            Double riskFreeRate,
            StableOptionTime time,
            io.liftandshift.strikebench.pricing.ProbabilityMap.Result probabilityMap,
            Long expectedValueCents,
            List<io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.Sensitivity> sensitivity,
            List<io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.ScenarioMass> scenarioMasses
    ) {}

    static Double decisionDecimal(Object value) {
        if (value == null || String.valueOf(value).isBlank() || "null".equals(String.valueOf(value))) return null;
        try {
            double parsed = Double.parseDouble(String.valueOf(value));
            if (!Double.isFinite(parsed)) throw new NumberFormatException("non-finite");
            return parsed;
        } catch (RuntimeException e) {
            throw new IllegalStateException("A frozen decision leg contains an invalid decimal; no result was written.", e);
        }
    }
    static int decisionInteger(Object value, String field) {
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalStateException("A frozen decision leg is missing its " + field + "; no result was written.");
        }
        try {
            int parsed = value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value));
            if (parsed < 1) throw new NumberFormatException("non-positive");
            return parsed;
        } catch (RuntimeException e) {
            throw new IllegalStateException("A frozen decision leg contains an invalid " + field + "; no result was written.", e);
        }
    }
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
    private static void putNullable(ObjectNode node, String key, Long value) {
        if (value == null) node.putNull(key);
        else node.put(key, value);
    }
    private static void putDecimal(ObjectNode node, String key, BigDecimal value) {
        if (value != null) node.put(key, value.stripTrailingZeros().toPlainString());
    }

    private record PlanRow(long version, int contextRev, String userId, String status) {}
    private record Metric(String key, Double number, Long cents, String text) {}
    private record DecisionReferences(String recommendationId, String ensembleId, String studyKey) {}
}
