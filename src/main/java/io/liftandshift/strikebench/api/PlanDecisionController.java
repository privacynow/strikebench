package io.liftandshift.strikebench.api;
import io.liftandshift.strikebench.market.MarketLane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.http.Context;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.paper.OrderInstruction;
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
    private final io.liftandshift.strikebench.plan.PlanPromotionService promotions;
    private final PlanAdoptionReviewService adoptionReviews;

    PlanDecisionController(PlanController root, Clock clock, Db db, MarketDataService market,
                           TradeService trades, PlanService planSvc,
                           PlanRehearsalService planRehearsals, PlanDecisionService planDecisions,
                           PlanManagementService planManagement, TradeController tradeController,
                           io.liftandshift.strikebench.plan.PlanPromotionService promotions,
                           PlanAdoptionReviewService adoptionReviews) {
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
        this.promotions = promotions;
        this.adoptionReviews = adoptionReviews;
    }

    public record PlanDecisionRequest(Long expectedVersion, Integer qty, Long proposedNetCents,
                                      Long feesOverrideCents, List<String> acknowledgedRisks,
                                      String ackToken, String note,
                                      OrderInstruction orderInstruction) {
        public PlanDecisionRequest(Long expectedVersion, Integer qty, Long proposedNetCents,
                                   Long feesOverrideCents, List<String> acknowledgedRisks,
                                   String ackToken, String note) {
            this(expectedVersion, qty, proposedNetCents, feesOverrideCents, acknowledgedRisks,
                    ackToken, note, null);
        }
    }
    public record PlanManageRequest(Long expectedVersion) {}
    public record BrokerFill(Integer legIndex, String fillPrice) {}
    public record PlanBrokerRequest(Long expectedVersion, Integer qty, Long proposedNetCents,
                                    String portfolioAccountId, String externalRef, String occurredAt,
                                    Long feesCents, List<BrokerFill> fills,
                                    List<String> acknowledgedRisks, String ackToken, String note) {}

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
        JsonNode selected = root.selectedCandidate(ctx, plan, false);
        JsonNode prior = selected == null
                ? root.priorSelectedCandidate(ctx, plan) : null;
        ctx.json(new ApiResponses.PlanDecisionState<>(plan, selected,
                planDecisions.latest(root.ownerId(ctx), plan.id()),
                selected != null ? "CURRENT" : prior != null ? "STALE" : "NONE", prior));
    }

    void planDecisionPreview(Context ctx) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, PlanDecisionRequest.class));
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan);
        PlanController.requirePlanVersion(plan, body.expectedVersion());
        ObjectNode candidate = root.selectedCandidate(ctx, plan, true);
        body = normalizeLegacyMarketRoundTrip(ctx, plan, candidate, body);
        TradeOpenRequest order = planDecisionOrder(plan, candidate, body, false);
        ApiResponses.TradePreviewResponse payload = tradeController.previewPayload(ctx, order);
        ctx.json(new ApiResponses.PlanDecisionPreview<>(payload.preview(), payload.evaluation(),
                payload.guardrails(), payload.requiredAcks(), payload.ackToken(), payload.accountFit(),
                plan, candidate, orderSummary(order, payload.preview())));
    }

    void planDecisionTrade(Context ctx) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, PlanDecisionRequest.class));
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan);
        PlanController.requirePlanVersion(plan, body.expectedVersion());
        ObjectNode candidate = root.selectedCandidate(ctx, plan, true);
        body = normalizeLegacyMarketRoundTrip(ctx, plan, candidate, body);
        TradeOpenRequest order = planDecisionOrder(plan, candidate, body, false);
        ApiResponses.TradePreviewResponse payload = tradeController.previewPayload(ctx, order);
        var prepared = planDecisions.prepareTrade(planDecisionInput(ctx, plan, body, candidate, payload, order));
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
        ObjectNode decision = planDecisions.chooseCash(planDecisionInput(ctx, plan, body, candidate, payload, null));
        var updated = planSvc.get(root.ownerId(ctx), plan.id());
        ctx.status(201).json(new ApiResponses.PlanDecision<>(updated, decision));
    }

    /** The third outcome: the exact selected structure was placed at the user's real broker.
     *  One transaction freezes the BROKER decision, records the fills in the chosen tracked
     *  account, and links Plan to structure through the four-artifact receipt set. */
    void planDecisionBroker(Context ctx) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, PlanBrokerRequest.class));
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan);
        PlanController.requirePlanVersion(plan, body.expectedVersion());
        // A simulated world is synthetic by construction — nothing in it can have happened at a
        // real broker. Demo/observed Plans may record one: the placement's reality comes from the
        // user's assertion and the book record; the receipt keeps the analysis lane's evidence level.
        if (plan.marketKind() == io.liftandshift.strikebench.plan.Plan.MarketKind.SIMULATED) {
            throw new IllegalStateException("A simulated-market Plan cannot record a real broker "
                    + "placement; finish the simulation in the Practice lane.");
        }
        ObjectNode candidate = root.selectedCandidate(ctx, plan, true);
        // The broker's actual total fees live in the book transaction only; the frozen analysis
        // keeps its own fee model, so feesOverrideCents stays null here (it is also bound into
        // the acknowledgment token, which the preview minted without an override).
        PlanDecisionRequest decisionBody = new PlanDecisionRequest(body.expectedVersion(), body.qty(),
                body.proposedNetCents(), null, body.acknowledgedRisks(), body.ackToken(), body.note());
        decisionBody = normalizeLegacyMarketRoundTrip(ctx, plan, candidate, decisionBody);
        TradeOpenRequest order = planDecisionOrder(plan, candidate, decisionBody, true);
        tradeController.requireRecordedPlacementApproval(ctx, order);
        ApiResponses.TradePreviewResponse payload = tradeController.previewPayload(ctx, order);
        var input = planDecisionInput(ctx, plan, decisionBody, candidate, payload, order);
        int qty = decisionBody.qty() == null ? candidate.path("qty").asInt() : decisionBody.qty();
        String label = candidate.path("displayName").asText(null);
        if (label == null || label.isBlank()) label = candidate.path("strategy").asText(null);
        var result = promotions.promote(input, new io.liftandshift.strikebench.plan.PlanPromotionService.Order(
                body.portfolioAccountId(), brokerTransactionInput(plan, candidate, qty, body), label));
        var updated = planSvc.get(root.ownerId(ctx), plan.id());
        ctx.status(201).json(new ApiResponses.PlanBrokerPlacement<>(updated,
                planDecisions.latest(root.ownerId(ctx), plan.id()), result.transaction(),
                result.artifacts().structureId(), result.artifacts().receiptId()));
    }

    /** Translates the frozen candidate's legs into exact tracked-book fills. Stock legs carry
     *  deliverable shares as quantity (the book requires multiplier=1 for stock). */
    private io.liftandshift.strikebench.paper.PortfolioAccountingService.TransactionInput brokerTransactionInput(
            io.liftandshift.strikebench.plan.Plan.View plan, ObjectNode candidate, int qty,
            PlanBrokerRequest body) {
        if (body.externalRef() == null || body.externalRef().isBlank()) {
            throw new IllegalArgumentException("a broker placement needs the broker's order or confirmation reference");
        }
        Map<Integer, BigDecimal> fills = new HashMap<>();
        if (body.fills() != null) for (BrokerFill fill : body.fills()) {
            if (fill == null || fill.legIndex() == null || fill.fillPrice() == null || fill.fillPrice().isBlank()) continue;
            try { fills.put(fill.legIndex(), new BigDecimal(fill.fillPrice().trim())); }
            catch (NumberFormatException e) {
                throw new IllegalArgumentException("leg " + (fill.legIndex() + 1) + " has an invalid fill price");
            }
        }
        List<io.liftandshift.strikebench.paper.PortfolioAccountingService.LegInput> legs = new ArrayList<>();
        int index = 0;
        for (JsonNode leg : candidate.withArray("legs")) {
            BigDecimal fill = fills.get(index);
            if (fill == null) {
                throw new IllegalArgumentException("every leg needs its exact broker fill price (leg "
                        + (index + 1) + " is missing one)");
            }
            String type = leg.path("type").asText();
            boolean stock = "STOCK".equals(type);
            int ratio = Math.max(1, leg.path("ratio").asInt(1));
            int multiplier = Math.max(1, leg.path("multiplier").asInt(stock ? 1 : 100));
            legs.add(new io.liftandshift.strikebench.paper.PortfolioAccountingService.LegInput(
                    stock ? "STOCK" : "OPTION", leg.path("action").asText(), "OPEN", plan.symbol(),
                    stock ? null : type,
                    stock || !leg.hasNonNull("strike") ? null : new BigDecimal(leg.path("strike").asText()),
                    stock || !leg.hasNonNull("expiration") ? null : LocalDate.parse(leg.path("expiration").asText()),
                    stock ? Math.multiplyExact((long) ratio, Math.multiplyExact((long) multiplier, (long) qty))
                            : Math.multiplyExact((long) ratio, (long) qty),
                    stock ? 1 : multiplier, fill, null));
            index++;
        }
        String occurredAt = body.occurredAt() == null || body.occurredAt().isBlank()
                ? java.time.OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC).toString()
                : body.occurredAt();
        return new io.liftandshift.strikebench.paper.PortfolioAccountingService.TransactionInput(
                occurredAt, "TRADE", null, body.feesCents(), null, "BROKER", body.externalRef(),
                body.note(), legs, "EXECUTED");
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
                body.acknowledgedRisks(), body.ackToken(), "PROPOSED", body.orderInstruction());
    }

    private PlanDecisionRequest normalizeLegacyMarketRoundTrip(
            Context ctx, io.liftandshift.strikebench.plan.Plan.View plan, ObjectNode candidate,
            PlanDecisionRequest body) {
        if (body.orderInstruction() != null || body.proposedNetCents() == null) return body;
        PlanDecisionRequest marketBody = new PlanDecisionRequest(body.expectedVersion(), body.qty(), null,
                body.feesOverrideCents(), body.acknowledgedRisks(), body.ackToken(), body.note(),
                OrderInstruction.market());
        TradeOpenRequest marketOrder = planDecisionOrder(plan, candidate, marketBody, false);
        var marketPreview = tradeController.previewPayload(ctx, marketOrder).preview();
        // Older Plan clients echoed the preview's natural net in proposedNetCents. Preserve that
        // round trip as MARKET without turning the server's former second-pass default into LIMIT.
        return body.proposedNetCents() == marketPreview.entryNetPremiumCents() ? marketBody : body;
    }

    static ApiResponses.OrderSummary orderSummary(TradeOpenRequest order,
                                                  io.liftandshift.strikebench.paper.TradePreview preview) {
        OrderInstruction instruction = order.orderInstruction() == null
                ? OrderInstruction.fromLegacy(order.proposedNetCents()) : order.orderInstruction();
        Map<?, ?> quality = preview.analytics() == null ? Map.of()
                : preview.analytics().get("executionQuality") instanceof Map<?, ?> map ? map : Map.of();
        OrderInstruction.Executability executability;
        try {
            Object rawExecutability = quality.get("executability");
            executability = OrderInstruction.Executability.valueOf(
                    rawExecutability == null ? "UNAVAILABLE" : String.valueOf(rawExecutability));
        } catch (IllegalArgumentException e) {
            executability = OrderInstruction.Executability.UNAVAILABLE;
        }
        Long executableNet = quality.get("executableNetCents") instanceof Number number
                ? number.longValue() : null;
        Long valuedNet = null;
        ApiResponses.OrderValuationBasis valuationBasis = ApiResponses.OrderValuationBasis.UNAVAILABLE;
        if (executability == OrderInstruction.Executability.IMMEDIATE && executableNet != null) {
            valuedNet = executableNet;
            valuationBasis = ApiResponses.OrderValuationBasis.EXECUTABLE_BOOK;
        } else if (executability == OrderInstruction.Executability.RESTING
                && instruction.limitNetCents() != null) {
            valuedNet = instruction.limitNetCents();
            valuationBasis = ApiResponses.OrderValuationBasis.RESTING_LIMIT;
        }
        return new ApiResponses.OrderSummary(order.qty(), preview.entryNetPremiumCents(),
                order.feesOverrideCents() == null ? 0L : order.feesOverrideCents(), instruction,
                executability, executability == OrderInstruction.Executability.IMMEDIATE, executableNet,
                valuedNet, valuationBasis);
    }

    private static String requiredCandidateText(ObjectNode candidate, String field) {
        String value = candidate.path(field).asText();
        if (value.isBlank()) throw new IllegalStateException("selected candidate " + field + " is required");
        return value;
    }

    private PlanDecisionService.Input planDecisionInput(
            Context ctx, io.liftandshift.strikebench.plan.Plan.View plan, PlanDecisionRequest body,
            ObjectNode candidate, ApiResponses.TradePreviewResponse payload, TradeOpenRequest order) {
        return new PlanDecisionService.Input(root.ownerId(ctx), plan, body.expectedVersion(),
                candidate.path("id").asText(), root.currentAccount(ctx), payload.preview(),
                payload.evaluation().assessment().economics(),
                io.liftandshift.strikebench.paper.AccountRiskContext.load(db, root.ownerId(ctx)),
                body.qty() == null ? candidate.path("qty").asInt() : body.qty(),
                body.acknowledgedRisks() == null ? List.of() : body.acknowledgedRisks(), body.note(),
                root.analysisCtx(ctx), order == null ? null
                        : TradeController.toOpenRequest(order, root.currentAccount(ctx).id()).orderInstruction());
    }

    void planManageGet(Context ctx) {
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan);
        ObjectNode management = planManagement.latest(root.ownerId(ctx), plan.id());
        String tradeId = management.path("activeTradeId").asText(null);
        boolean receiptPositionSurvives = management.path("currentPosition").path("legs").size() > 0;
        if (tradeId == null && !receiptPositionSurvives) {
            for (JsonNode link : management.withArray("links")) {
                if (link.hasNonNull("tradeId")) tradeId = link.get("tradeId").asText();
            }
        }
        ctx.json(new ApiResponses.PlanWorkspace<>(plan, planDecisions.latest(root.ownerId(ctx), plan.id()),
                management, tradeId == null ? null : tradeController.detailData(tradeId),
                adoptionReviews.reviews(root.ownerId(ctx), plan.id())));
    }

    void plansPortfolio(Context ctx) {
        String world = MarketLane.worldParam(root.activeWorld(ctx));
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

}
