package io.liftandshift.strikebench.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.http.Context;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.OptionQuote;
import io.liftandshift.strikebench.paper.TradeRecord;
import io.liftandshift.strikebench.paper.TradeService;
import io.liftandshift.strikebench.plan.PlanDecisionService;
import io.liftandshift.strikebench.plan.PlanManagementService;
import io.liftandshift.strikebench.plan.PlanRehearsalService;
import io.liftandshift.strikebench.plan.PlanService;
import io.liftandshift.strikebench.recommend.LegView;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Owns Plan rehearsals, frozen decisions, and linked-position management. */
final class PlanDecisionController {
    private final PlanController root;
    private final Clock clock;
    private final Db db;
    private final MarketDataService market;
    private final TradeService trades;
    private final PlanService planSvc;
    private final PlanRehearsalService planRehearsals;
    private final PlanDecisionService planDecisions;
    private final PlanManagementService planManagement;
    private final TradeController tradeController;

    PlanDecisionController(PlanController root, Clock clock, Db db, MarketDataService market,
                           TradeService trades, PlanService planSvc,
                           PlanRehearsalService planRehearsals, PlanDecisionService planDecisions,
                           PlanManagementService planManagement, TradeController tradeController) {
        this.root = root;
        this.clock = clock;
        this.db = db;
        this.market = market;
        this.trades = trades;
        this.planSvc = planSvc;
        this.planRehearsals = planRehearsals;
        this.planDecisions = planDecisions;
        this.planManagement = planManagement;
        this.tradeController = tradeController;
    }

    public record PlanDecisionRequest(Long expectedVersion, Integer qty, Long proposedNetCents,
                                      Long feesOverrideCents, List<String> acknowledgedRisks,
                                      String ackToken, String note) {}
    public record PlanManageRequest(Long expectedVersion, Boolean confirm) {}

    void planRehearsalsList(Context ctx) {
        ctx.json(planRehearsals.list(root.ownerId(ctx), ctx.pathParam("id")));
    }

    void planRehearsalCreate(Context ctx) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, PlanRehearsalService.Request.class));
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan);
        var created = planRehearsals.create(root.ownerId(ctx), plan, body, root.analysisCtx(ctx));
        ctx.status(201).json(new ApiResponses.PlanRehearsal<>(
                created, planSvc.get(root.ownerId(ctx), plan.id())));
    }

    void planDecisionLatest(Context ctx) {
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan);
        ctx.json(new ApiResponses.PlanDecisionState<>(plan, root.selectedCandidate(ctx, plan, false),
                planDecisions.latest(root.ownerId(ctx), plan.id())));
    }

    void planDecisionPreview(Context ctx) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, PlanDecisionRequest.class));
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan);
        PlanController.requirePlanVersion(plan, body.expectedVersion());
        ObjectNode candidate = root.selectedCandidate(ctx, plan, true);
        TradeOpenRequest order = planDecisionOrder(plan, candidate, body, false);
        ApiResponses.TradePreviewResponse payload = tradeController.previewPayload(ctx, order);
        var first = payload.preview();
        if (order.proposedNetCents() == null) {
            body = new PlanDecisionRequest(body.expectedVersion(), body.qty(), first.entryNetPremiumCents(),
                    body.feesOverrideCents(), body.acknowledgedRisks(), body.ackToken(), body.note());
            order = planDecisionOrder(plan, candidate, body, false);
            payload = tradeController.previewPayload(ctx, order);
        }
        ctx.json(new ApiResponses.PlanDecisionPreview<>(payload.preview(), payload.evaluation(),
                payload.guardrails(), payload.requiredAcks(), payload.ackToken(), payload.accountFit(),
                plan, candidate, new ApiResponses.OrderSummary(order.qty(), order.proposedNetCents(),
                order.feesOverrideCents() == null ? 0L : order.feesOverrideCents())));
    }

    void planDecisionTrade(Context ctx) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, PlanDecisionRequest.class));
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan);
        PlanController.requirePlanVersion(plan, body.expectedVersion());
        ObjectNode candidate = root.selectedCandidate(ctx, plan, true);
        TradeOpenRequest order = planDecisionOrder(plan, candidate, body, false);
        ApiResponses.TradePreviewResponse payload = tradeController.previewPayload(ctx, order);
        var prepared = planDecisions.prepareTrade(planDecisionInput(ctx, plan, body, candidate, payload));
        TradeController.CreatedTrade created = tradeController.execute(ctx, order, prepared.hook());
        var updated = planSvc.get(root.ownerId(ctx), plan.id());
        ctx.status(201).json(new ApiResponses.PlanPlacedTrade<>(updated, TradeView.of(created.trade()),
                planDecisions.latest(root.ownerId(ctx), plan.id()), created.verdict().warnings()));
    }

    void planDecisionCash(Context ctx) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, PlanDecisionRequest.class));
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan);
        PlanController.requirePlanVersion(plan, body.expectedVersion());
        ObjectNode candidate = root.selectedCandidate(ctx, plan, true);
        // CASH freezes the selected package as analyzed. It is not an order and therefore may
        // preserve proposed per-leg prices without claiming that those prices were executable.
        TradeOpenRequest order = planDecisionOrder(plan, candidate, body, true);
        ApiResponses.TradePreviewResponse payload = tradeController.previewPayload(ctx, order);
        ObjectNode decision = planDecisions.chooseCash(planDecisionInput(ctx, plan, body, candidate, payload));
        var updated = planSvc.get(root.ownerId(ctx), plan.id());
        ctx.status(201).json(new ApiResponses.PlanDecision<>(updated, decision));
    }

    private TradeOpenRequest planDecisionOrder(io.liftandshift.strikebench.plan.Plan.View plan,
                                               ObjectNode candidate, PlanDecisionRequest body,
                                               boolean freezeAnalyzedPackage) {
        String strategy = requiredCandidateText(candidate, "strategy");
        List<LegView> legs = new ArrayList<>();
        for (JsonNode leg : candidate.withArray("legs")) {
            legs.add(new LegView(leg.path("action").asText(), leg.path("type").asText(),
                    leg.path("strike").isMissingNode() || leg.path("strike").isNull() ? null : leg.path("strike").asText(),
                    leg.path("expiration").isMissingNode() || leg.path("expiration").isNull() ? null : leg.path("expiration").asText(),
                    leg.path("ratio").asInt(), leg.path("entryPrice").isMissingNode() ? null : leg.path("entryPrice").asText(null),
                    leg.path("multiplier").asInt(), leg.path("positionEffect").asText()));
        }
        int candidateQty = candidate.path("qty").asInt();
        if (candidateQty < 1) throw new IllegalStateException("selected candidate qty must be positive");
        int qty = body.qty() == null ? candidateQty : body.qty();
        return new TradeOpenRequest(plan.symbol(), strategy, qty, legs,
                plan.context().thesis(), (plan.context().horizonDays() == null
                        ? io.liftandshift.strikebench.model.Horizon.MONTH.tradingSessions()
                        : plan.context().horizonDays()) + "d",
                plan.context().riskMode(), plan.intent(), candidate.path("usesHeldShares").asBoolean(false),
                candidate.path("recommendationId").asText(null), body.proposedNetCents(), body.feesOverrideCents(),
                freezeAnalyzedPackage ? "ANALYZE" : "PLAN",
                body.acknowledgedRisks(), body.ackToken(), "PROPOSED");
    }

    private static String requiredCandidateText(ObjectNode candidate, String field) {
        String value = candidate.path(field).asText();
        if (value.isBlank()) throw new IllegalStateException("selected candidate " + field + " is required");
        return value;
    }

    private PlanDecisionService.Input planDecisionInput(
            Context ctx, io.liftandshift.strikebench.plan.Plan.View plan, PlanDecisionRequest body,
            ObjectNode candidate, ApiResponses.TradePreviewResponse payload) {
        return new PlanDecisionService.Input(root.ownerId(ctx), plan, body.expectedVersion(),
                candidate.path("id").asText(), root.currentAccount(ctx), payload.preview(),
                payload.evaluation().assessment().economics(),
                io.liftandshift.strikebench.paper.AccountRiskContext.load(db, root.ownerId(ctx)),
                body.qty() == null ? candidate.path("qty").asInt() : body.qty(),
                body.acknowledgedRisks() == null ? List.of() : body.acknowledgedRisks(), body.note(), root.analysisCtx(ctx));
    }

    void planManageGet(Context ctx) {
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan);
        ObjectNode management = planManagement.latest(root.ownerId(ctx), plan.id());
        String tradeId = management.path("activeTradeId").asText(null);
        if (tradeId == null) {
            for (JsonNode link : management.withArray("links")) {
                if (link.hasNonNull("tradeId")) tradeId = link.get("tradeId").asText();
            }
        }
        ctx.json(new ApiResponses.PlanWorkspace<>(plan, planDecisions.latest(root.ownerId(ctx), plan.id()),
                management, tradeId == null ? null : tradeController.detailData(tradeId)));
    }

    void plansPortfolio(Context ctx) {
        String world = PlanController.worldParam(root.activeWorld(ctx));
        var marketKind = root.activePlanMarket(ctx);
        String ownerId = root.ownerId(ctx);
        var plans = planSvc.list(ownerId, marketKind,
                marketKind == io.liftandshift.strikebench.plan.Plan.MarketKind.SIMULATED ? world : null, false);
        var portfolioDecisions = planDecisions.portfolioLatest(ownerId);
        Map<String, Map<String, TradeService.MarkView>> marksByAccount = new HashMap<>();
        for (var plan : plans) {
            if (plan.accountId() == null || marksByAccount.containsKey(plan.accountId())) continue;
            marksByAccount.put(plan.accountId(), trades.accountMarkSnapshot(plan.accountId()));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var plan : plans) {
            if (plan.status() == io.liftandshift.strikebench.plan.Plan.Status.ARCHIVED) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("plan", plan);
            var portfolioDecision = portfolioDecisions.get(plan.id());
            if (portfolioDecision != null) row.put("decision", portfolioDecision.decision());
            String tradeId = portfolioDecision == null ? null : portfolioDecision.activeTradeId();
            if (tradeId != null) {
                row.put("tradeId", tradeId);
                var mark = marksByAccount.getOrDefault(plan.accountId(), Map.of()).get(tradeId);
                if (mark != null) row.put("mark", mark);
                else row.put("markUnavailable", true);
            }
            rows.add(row);
        }
        ctx.json(new ApiResponses.PlanRows<>(rows, marketKind.name()));
    }

    void planManageRefresh(Context ctx) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, PlanManageRequest.class));
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan);
        PlanController.requirePlanVersion(plan, body.expectedVersion());
        String tradeId = requirePlanActiveTrade(ctx, plan.id());
        var mark = trades.refresh(tradeId);
        planManagement.recordMark(root.ownerId(ctx), plan.id(), body.expectedVersion(), tradeId, mark);
        ctx.json(new ApiResponses.PlanMark<>(planSvc.get(root.ownerId(ctx), plan.id()), mark,
                planManagement.latest(root.ownerId(ctx), plan.id())));
    }

    void planManageUnwind(Context ctx) { planManageClose(ctx, "CLOSE", false); }
    void planManageSettle(Context ctx) { planManageClose(ctx, "SETTLE", false); }
    void planManageRoll(Context ctx) { planManageClose(ctx, "ROLL", true); }

    private void planManageClose(Context ctx, String kind, boolean roll) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, PlanManageRequest.class));
        if (!Boolean.TRUE.equals(body.confirm())) throw new IllegalArgumentException("confirm=true is required");
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan);
        PlanController.requirePlanVersion(plan, body.expectedVersion());
        String tradeId = requirePlanActiveTrade(ctx, plan.id());
        var hook = planManagement.lifecycleHook(root.ownerId(ctx), plan.id(), body.expectedVersion(), kind, roll);
        TradeService.CloseResult result = "SETTLE".equals(kind)
                ? trades.settle(tradeId, true, hook) : trades.unwind(tradeId, true, hook);
        tradeController.resolveRecommendation(tradeId, "SETTLE".equals(kind) ? "SETTLED" : "CLOSED",
                TradeController.decisionPnl(result.trade(), result.realizedPnlCents()));
        ctx.json(new ApiResponses.PlanClosedTrade<>(planSvc.get(root.ownerId(ctx), plan.id()),
                TradeView.of(result.trade()), result.realizedPnlCents(),
                planManagement.latest(root.ownerId(ctx), plan.id()),
                roll ? rolledPosition(result.trade(), PlanController.worldParam(root.activeWorld(ctx))) : null));
    }

    void planManageVoid(Context ctx) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, PlanManageRequest.class));
        if (!Boolean.TRUE.equals(body.confirm())) throw new IllegalArgumentException("confirm=true is required");
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan);
        PlanController.requirePlanVersion(plan, body.expectedVersion());
        String tradeId = requirePlanActiveTrade(ctx, plan.id());
        var hook = planManagement.lifecycleHook(root.ownerId(ctx), plan.id(), body.expectedVersion(), "VOID", false);
        TradeRecord deleted = trades.delete(tradeId, true, hook);
        ctx.json(new ApiResponses.PlanTrade<>(planSvc.get(root.ownerId(ctx), plan.id()),
                TradeView.of(deleted), planManagement.latest(root.ownerId(ctx), plan.id())));
    }

    void planManageReview(Context ctx) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, PlanManageRequest.class));
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan);
        PlanController.requirePlanVersion(plan, body.expectedVersion());
        ObjectNode decision = planDecisions.latest(root.ownerId(ctx), plan.id());
        if (decision == null || !"CASH".equals(decision.path("action").asText())) {
            throw new IllegalStateException("This Plan does not have a cash decision to review.");
        }
        ObjectNode metrics = decision.with("metrics");
        int horizon = decision.path("reviewHorizonDays").asInt(30);
        java.time.Instant decidedAt = java.time.OffsetDateTime.parse(decision.path("createdAt").asText()).toInstant();
        java.time.Instant dueAt = decidedAt.plus(java.time.Duration.ofDays(horizon));
        String world = plan.marketKind() == io.liftandshift.strikebench.plan.Plan.MarketKind.SIMULATED
                ? plan.worldId() : plan.marketKind() == io.liftandshift.strikebench.plan.Plan.MarketKind.DEMO ? "demo" : "observed";
        java.time.Instant laneNow = market.simInstant(world).orElse(clock.instant());
        if (laneNow.isBefore(dueAt)) {
            throw new IllegalStateException("This opportunity review is scheduled for "
                    + LocalDate.ofInstant(dueAt, io.liftandshift.strikebench.market.MarketHours.EASTERN) + ".");
        }
        LocalDate dueDate = LocalDate.ofInstant(dueAt, io.liftandshift.strikebench.market.MarketHours.EASTERN);
        var series = market.candleSeries(plan.symbol(), dueDate.minusDays(14), dueDate, world,
                io.liftandshift.strikebench.db.AnalysisContext.OBSERVED);
        var dueBar = series.candles().stream().filter(c -> !c.date().isAfter(dueDate))
                .max(java.util.Comparator.comparing(io.liftandshift.strikebench.model.Candle::date))
                .orElseThrow(() -> new IllegalStateException("No lane-owned closing price is available for the review horizon."));
        long startUnderlying = metrics.path("underlyingCents").asLong(0);
        if (startUnderlying <= 0) throw new IllegalStateException("The frozen decision has no underlying anchor.");
        long endUnderlying = io.liftandshift.strikebench.util.Money.toCents(dueBar.close());
        long riskCapital = Math.max(startUnderlying, decision.path("maxLossCents").asLong(startUnderlying));
        long shares = Math.max(1, Math.min(10_000, riskCapital / startUnderlying));
        long stockPnl = Math.multiplyExact(endUnderlying - startUnderlying, shares);
        int qty = Math.max(1, (int) Math.round(metrics.path("decisionQty").asDouble(1)));
        double rate = metrics.path("riskFreeRateAnnual").asDouble(Double.NaN);
        if (!Double.isFinite(rate)) throw new IllegalStateException("The frozen decision has no pricing-rate snapshot.");
        long packageEnd = modeledRejectedPackageValue(decision.withArray("legs"), dueBar.close(), dueDate, rate, qty);
        long entry = decision.path("proposedNetCents").asLong();
        long fees = metrics.path("feesOpenCents").asLong(0);
        long rejectedPnl = entry + packageEnd - Math.multiplyExact(fees, 2L);
        ObjectNode management = planManagement.recordCashReview(root.ownerId(ctx), plan.id(), body.expectedVersion(),
                new PlanManagementService.CashReview(startUnderlying, endUnderlying, stockPnl, entry, packageEnd,
                        rejectedPnl, horizon, decision.hasNonNull("pop") ? decision.get("pop").asDouble() : null,
                        "Frozen-IV modeled value at the lane-owned horizon close; kept outside trade calibration"));
        ctx.json(new ApiResponses.PlanManagement<>(planSvc.get(root.ownerId(ctx), plan.id()), management));
    }

    private static long modeledRejectedPackageValue(ArrayNode legs, BigDecimal spot, LocalDate horizon,
                                                     double rate, int qty) {
        long value = 0;
        for (JsonNode leg : legs) {
            int ratio = leg.path("ratio").asInt();
            if (ratio < 1) throw new IllegalStateException("The frozen decision has an invalid leg ratio.");
            int multiplier = leg.path("multiplier").asInt();
            if (multiplier < 1 || multiplier > 10_000) {
                throw new IllegalStateException("The frozen decision has an invalid contract multiplier.");
            }
            long units = Math.multiplyExact((long) multiplier, Math.multiplyExact((long) ratio, qty));
            double price;
            if ("STOCK".equals(leg.path("type").asText())) {
                price = spot.doubleValue();
            } else {
                if (!leg.hasNonNull("strikePrice") || !leg.hasNonNull("expiration") || !leg.hasNonNull("iv")) {
                    throw new IllegalStateException("The rejected package lacks a frozen strike, expiration, or IV.");
                }
                double strike = leg.get("strikePrice").asDouble();
                LocalDate expiry = LocalDate.parse(leg.get("expiration").asText());
                double years = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(horizon, expiry) / 365.0);
                price = io.liftandshift.strikebench.pricing.BlackScholes.price(
                        "CALL".equals(leg.path("type").asText()), spot.doubleValue(), strike, years,
                        rate, 0, leg.get("iv").asDouble());
            }
            long cents = io.liftandshift.strikebench.util.Money.centsFromPrice(BigDecimal.valueOf(price), units);
            value += "BUY".equals(leg.path("action").asText()) ? cents : -cents;
        }
        return value;
    }

    private String requirePlanActiveTrade(Context ctx, String planId) {
        String tradeId = planManagement.activeTradeId(root.ownerId(ctx), planId);
        if (tradeId == null) throw new IllegalStateException("This Plan has no active linked position.");
        return tradeId;
    }

    private Map<String, Object> rolledPosition(TradeRecord trade, String world) {
        List<LocalDate> listed = market.expirations(trade.symbol(), world).stream().sorted().toList();
        List<Map<String, Object>> legs = new ArrayList<>();
        for (Leg leg : trade.legs()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("action", leg.action().name());
            row.put("type", leg.isStock() ? "STOCK" : leg.type().name());
            row.put("ratio", leg.ratio());
            row.put("multiplier", leg.multiplier());
            row.put("positionEffect", "OPEN");
            if (!leg.isStock()) {
                LocalDate target = leg.expiration().plusDays(28);
                LocalDate next = listed.stream().filter(date -> !date.isBefore(target)).findFirst()
                        .orElseGet(() -> listed.stream().filter(date -> date.isAfter(leg.expiration()))
                                .reduce((a, b) -> b).orElseThrow(() -> new IllegalStateException(
                                        "No later listed expiration is available for this roll.")));
                OptionChain chain = market.chain(trade.symbol(), next, world)
                        .orElseThrow(() -> new IllegalStateException("The later expiration has no option book."));
                List<OptionQuote> side = leg.type() == io.liftandshift.strikebench.model.OptionType.CALL
                        ? chain.calls() : chain.puts();
                BigDecimal strike = side.stream().map(OptionQuote::strike)
                        .min(java.util.Comparator.comparing(value -> value.subtract(leg.strike()).abs()))
                        .orElseThrow(() -> new IllegalStateException("The later expiration has no "
                                + leg.type().name().toLowerCase(Locale.ROOT) + " strikes."));
                row.put("strike", strike.toPlainString());
                row.put("expiration", next.toString());
            }
            legs.add(row);
        }
        if (trade.intent() == null || trade.intent().isBlank()) {
            throw new IllegalStateException("The current trade record has no strategy intent.");
        }
        return Map.of("symbol", trade.symbol(), "strategy", trade.strategy(), "qty", trade.qty(), "legs", legs,
                "intent", trade.intent(), "source", "PLAN", "fillNature", "PROPOSED");
    }
}
