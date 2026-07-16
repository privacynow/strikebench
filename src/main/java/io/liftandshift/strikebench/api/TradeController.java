package io.liftandshift.strikebench.api;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.eval.EconomicAssessment;
import io.liftandshift.strikebench.eval.EvaluationService;
import io.liftandshift.strikebench.market.EventService;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.MarketLane;
import io.liftandshift.strikebench.market.SnapshotService;
import io.liftandshift.strikebench.model.DataProvenance;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.OptionQuote;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.paper.Account;
import io.liftandshift.strikebench.paper.AccountRiskContext;
import io.liftandshift.strikebench.paper.AccountService;
import io.liftandshift.strikebench.paper.AuditLog;
import io.liftandshift.strikebench.paper.PositionsService;
import io.liftandshift.strikebench.paper.TradeRecord;
import io.liftandshift.strikebench.paper.TradeRejectedException;
import io.liftandshift.strikebench.paper.TradeService;
import io.liftandshift.strikebench.pricing.PayoffCurve;
import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.recommend.LegView;
import io.liftandshift.strikebench.recommend.RecommendationEngine;
import io.liftandshift.strikebench.recommend.RiskBudgetPolicy;
import io.liftandshift.strikebench.strategy.Guardrails;
import io.liftandshift.strikebench.strategy.StrategyFamily;
import io.liftandshift.strikebench.strategy.StrategyIntent;
import io.liftandshift.strikebench.strategy.Verdict;
import io.liftandshift.strikebench.util.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/** HTTP controller and shared request operations for paper trades and share positions. */
final class TradeController {
    private static final Logger log = LoggerFactory.getLogger(TradeController.class);

    private final AppConfig cfg;
    private final Clock clock;
    private final Db db;
    private final AccountService accounts;
    private final MarketDataService market;
    private final EventService eventCalendar;
    private final AuditLog audit;
    private final TradeService trades;
    private final PositionsService positions;
    private final EvaluationService evaluations;
    private final ExactAssessment exactAssessment;
    private final SnapshotService snapshots;
    private final io.liftandshift.strikebench.auth.AuthService auth;
    private final Function<Context, Account> currentAccount;
    private final Function<Context, String> ownerId;
    private final Function<Context, String> activeWorld;
    private final Function<Context, AnalysisContext> analysisContext;
    private final Consumer<Context> requireAdmin;
    private final byte[] acknowledgmentSecret = new byte[32];

    TradeController(AppConfig cfg, Clock clock, Db db, AccountService accounts, MarketDataService market,
                    EventService eventCalendar, AuditLog audit, TradeService trades,
                    PositionsService positions, EvaluationService evaluations,
                    SnapshotService snapshots,
                    io.liftandshift.strikebench.auth.AuthService auth,
                    Function<Context, Account> currentAccount,
                    Function<Context, String> ownerId,
                    Function<Context, String> activeWorld,
                    Function<Context, AnalysisContext> analysisContext,
                    Consumer<Context> requireAdmin,
                    ExactAssessment exactAssessment) {
        this.cfg = cfg;
        this.clock = clock;
        this.db = db;
        this.accounts = accounts;
        this.market = market;
        this.eventCalendar = eventCalendar;
        this.audit = audit;
        this.trades = trades;
        this.positions = positions;
        this.evaluations = evaluations;
        this.exactAssessment = exactAssessment == null ? evaluations::assessExact : exactAssessment;
        this.snapshots = snapshots;
        this.auth = auth;
        this.currentAccount = currentAccount;
        this.ownerId = ownerId;
        this.activeWorld = activeWorld;
        this.analysisContext = analysisContext;
        this.requireAdmin = requireAdmin;
        new java.security.SecureRandom().nextBytes(acknowledgmentSecret);
    }

    @FunctionalInterface
    interface ExactAssessment {
        io.liftandshift.strikebench.eval.StrategyEvaluation assess(
                String symbol, Candidate candidate, long buyingPowerCents,
                AnalysisContext analysisContext, String worldId,
                boolean mechanicallyEligible, List<String> mechanicalFailures,
                long roundTripFeesCents,
                io.liftandshift.strikebench.eval.PortfolioExposureContext portfolioExposure);
    }

    void register(JavalinConfig config) {
        TradeRoutes.register(config, new TradeRoutes.Handlers(
                this::preview,
                this::create,
                this::list,
                this::detail,
                this::refresh,
                this::snapshot,
                this::auditPage,
                this::listPositions,
                this::previewPosition,
                this::buyPosition,
                this::sellPosition));
    }

    record CreatedTrade(TradeRecord trade, Verdict verdict) {}
    record PlacementCheck(Account account, TradeService.OpenRequest request, Verdict verdict,
                          io.liftandshift.strikebench.paper.TradePreview preview,
                          long effectiveRiskBudgetCents,
                          List<ApiResponses.RiskAcknowledgment> requiredAcknowledgments) {}
    record PlacementProjection(long cashCents, long reservedCents, long releasedShares,
                               String excludedTradeId) {
        long buyingPowerCents() { return Math.subtractExact(cashCents, reservedCents); }
    }
    private record StockOrderRequest(String symbol, Long shares) {}
    private record StockOrderPreviewRequest(String side, String symbol, Long shares) {}

    private void preview(Context ctx) {
        ctx.json(previewPayload(ctx, ApiRequest.bodyOrNull(ctx, TradeOpenRequest.class)));
    }

    private void create(Context ctx) {
        CreatedTrade created = execute(ctx, ApiRequest.bodyOrNull(ctx, TradeOpenRequest.class), null);
        ctx.status(201).json(new ApiResponses.CreatedTrade<>(
                TradeView.of(created.trade()), created.verdict().warnings()));
    }

    private void list(Context ctx) {
        Account account = currentAccount.apply(ctx);
        int page = ApiRequest.intParam(ctx, "page", 0);
        int size = Math.clamp(ApiRequest.intParam(ctx, "size", 20), 1, 100);
        TradeService.Page result = trades.list(account.id(), ctx.queryParam("status"),
                ctx.queryParam("symbol"), ctx.queryParam("intent"), page, size);
        List<TradeView> rows = new ArrayList<>();
        for (TradeRecord trade : result.trades()) {
            TradeView row = TradeView.of(trade);
            if (TradeRecord.ACTIVE.equals(trade.status())) {
                try {
                    TradeService.MarkView mark = trades.currentMark(trade.id());
                    if (mark != null && mark.unrealizedCents() != null) {
                        row = row.withUnrealized(mark.unrealizedCents(),
                                mark.decisionUnrealizedCents() == null
                                        ? mark.unrealizedCents() : mark.decisionUnrealizedCents());
                    }
                } catch (Exception ignored) {
                    // A missing live mark leaves this optional list value unavailable.
                }
            }
            rows.add(row);
        }
        ctx.json(new ApiResponses.TradePage<>(rows, result.total(), result.page(), result.size()));
    }

    private void detail(Context ctx) {
        String id = ctx.pathParam("id");
        ensureOwnedTrade(ctx, id);
        ctx.json(detailData(id));
    }

    private void refresh(Context ctx) {
        ensureOwnedTrade(ctx, ctx.pathParam("id"));
        ctx.json(trades.refresh(ctx.pathParam("id")));
    }

    private void snapshot(Context ctx) {
        requireAdmin.accept(ctx);
        SnapshotService.SnapshotResult result = snapshots.snapshotActiveUniverse();
        ctx.json(new ApiResponses.Snapshot(result.asof().toString(), result.symbols(),
                result.underlyingRows(), result.optionRows(), result.errors(), result.elapsedMs()));
    }

    private void auditPage(Context ctx) {
        int page = ApiRequest.intParam(ctx, "page", 0);
        Object entries = auth.enabled()
                ? audit.pageForAccount(currentAccount.apply(ctx).id(), page, 50)
                : audit.page(page, 50);
        ctx.json(new ApiResponses.Entries<>(entries));
    }

    private void listPositions(Context ctx) {
        Account account = currentAccount.apply(ctx);
        ctx.json(new ApiResponses.PositionBook<>(positions.list(account.id()),
                "Shares locked as covered-call coverage cannot be sold until the covering trade closes"));
    }

    private void buyPosition(Context ctx) {
        StockOrderRequest request = ApiRequest.bodyOrNull(ctx, StockOrderRequest.class);
        validateStockOrder(request);
        ctx.status(201).json(positions.buy(currentAccount.apply(ctx).id(),
                request.symbol(), request.shares()));
    }

    private void previewPosition(Context ctx) {
        StockOrderPreviewRequest request = ApiRequest.bodyOrNull(ctx, StockOrderPreviewRequest.class);
        if (request == null) throw new IllegalArgumentException("request body is required");
        validateStockOrder(new StockOrderRequest(request.symbol(), request.shares()));
        if (request.side() == null || request.side().isBlank()) {
            throw new IllegalArgumentException("side is required");
        }
        ctx.json(positions.preview(currentAccount.apply(ctx).id(), request.side(),
                request.symbol(), request.shares()));
    }

    private void sellPosition(Context ctx) {
        StockOrderRequest request = ApiRequest.bodyOrNull(ctx, StockOrderRequest.class);
        validateStockOrder(request);
        ctx.json(positions.sell(currentAccount.apply(ctx).id(), request.symbol(), request.shares()));
    }

    private static void validateStockOrder(StockOrderRequest request) {
        if (request == null) throw new IllegalArgumentException("request body is required");
        if (request.symbol() == null || request.symbol().isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        if (request.shares() == null || request.shares() <= 0) {
            throw new IllegalArgumentException("shares must be a positive number");
        }
    }

    ApiResponses.TradePreviewResponse previewPayload(Context ctx, TradeOpenRequest body) {
        return previewPayload(ctx, body, null);
    }

    private ApiResponses.TradePreviewResponse previewPayload(Context ctx, TradeOpenRequest body,
                                                             PlacementProjection projection) {
        PlacementCheck check = placementCheck(ctx, body, projection);
        return reviewPayload(ctx, check.account(), check.request(), check.preview(), check.verdict(),
                check.requiredAcknowledgments(), projection == null ? null : projection.excludedTradeId());
    }

    private ApiResponses.TradePreviewResponse reviewPayload(Context ctx, Account account,
            TradeService.OpenRequest request,
            io.liftandshift.strikebench.paper.TradePreview preview,
            Verdict verdict, List<ApiResponses.RiskAcknowledgment> required,
            String excludedTradeId) {
        Candidate exact = exactPreviewCandidate(request, preview);
        ApiResponses.EvaluationReceipt evaluation;
        long roundTripFees = Math.multiplyExact(preview.feesOpenCents(), 2L);
        try {
            evaluation = ApiResponses.EvaluationReceipt.of(exactAssessment.assess(
                    request.symbol(), exact, preview.buyingPowerBeforeCents(),
                    analysisContext.apply(ctx), worldParam(activeWorld.apply(ctx)), preview.ok(),
                    preview.blockReasons(), roundTripFees, practiceExposure(account, request.symbol(),
                            excludedTradeId)));
        } catch (RuntimeException e) {
            log.warn("Exact-ticket assessment is unavailable for this preview", e);
            evaluation = ApiResponses.EvaluationReceipt.unavailable(
                    "The package mechanics were checked, but the broader decision assessment is unavailable because one or more market inputs could not be observed. No score or economic claim was substituted.",
                    preview.ok(), preview.blockReasons(), roundTripFees);
        }
        ApiResponses.Guardrails guardrails = new ApiResponses.Guardrails(
                verdict.level().name(), verdict.blockReasons(), verdict.warnings());
        AccountRiskContext riskContext = AccountRiskContext.load(db, ownerId.apply(ctx));
        String token = required.isEmpty() ? null : acknowledgmentToken(request);
        ApiResponses.AccountFit accountFit = null;
        if (!riskContext.isEmpty() && preview.maxLossCents() > 0) {
            Double pctOfNlv = percentage(preview.maxLossCents(), riskContext.nlvCents());
            Double pctOfCash = percentage(preview.maxLossCents(), riskContext.cashBpCents());
            Double pctOfMargin = percentage(preview.maxLossCents(), riskContext.marginBpCents());
            Double pctOfRiskCapital = percentage(preview.maxLossCents(), riskContext.riskCapitalCents());
            Boolean overRiskCapital = riskContext.riskCapitalCents() != null
                    && riskContext.riskCapitalCents() > 0
                    && preview.maxLossCents() > riskContext.riskCapitalCents() ? true : null;
            accountFit = new ApiResponses.AccountFit(pctOfNlv, pctOfCash, pctOfMargin,
                    pctOfRiskCapital, overRiskCapital);
        }
        return new ApiResponses.TradePreviewResponse(preview, evaluation, guardrails,
                required.isEmpty() ? null : required, token, accountFit,
                io.liftandshift.strikebench.strategy.StrategyCatalog.identify(
                        request.symbol(), request.qty(), request.legs()));
    }

    ApiResponses.TradePreviewResponse transformationPayload(Context ctx, String expectedAccountId,
            TradeService.OpenRequest exactRequest,
            io.liftandshift.strikebench.paper.TradePreview exactPreview,
            String excludedTradeId) {
        Account account = currentAccount.apply(ctx);
        if (!account.id().equals(expectedAccountId)) {
            throw new IllegalStateException("Open the Practice account that owns this position before changing it.");
        }
        long effectiveRiskBudget = RiskBudgetPolicy.compute(
                RecommendationEngine.RiskMode.parse(exactRequest.riskMode()),
                exactPreview.buyingPowerBeforeCents(), riskCapCents(ctx)).effectiveBudgetCents();
        List<ApiResponses.RiskAcknowledgment> required = requiredAcksFor(exactPreview, effectiveRiskBudget);
        Verdict verdict = Verdict.of(exactPreview.blockReasons(), exactPreview.warnings());
        return reviewPayload(ctx, account, exactRequest, exactPreview, verdict, required, excludedTradeId);
    }

    void requireTransformationApproval(Context ctx, TradeOpenRequest body,
                                       TradeService.OpenRequest exactRequest,
                                       io.liftandshift.strikebench.paper.TradePreview exactPreview) {
        long effectiveRiskBudget = RiskBudgetPolicy.compute(
                RecommendationEngine.RiskMode.parse(exactRequest.riskMode()),
                exactPreview.buyingPowerBeforeCents(), riskCapCents(ctx)).effectiveBudgetCents();
        List<ApiResponses.RiskAcknowledgment> required = requiredAcksFor(exactPreview, effectiveRiskBudget);
        if (required.isEmpty()) return;
        Set<String> acknowledged = body == null || body.acknowledgedRisks() == null
                ? Set.of() : new HashSet<>(body.acknowledgedRisks());
        List<String> missing = required.stream().map(ApiResponses.RiskAcknowledgment::id)
                .filter(id -> !acknowledged.contains(id)).toList();
        if (!missing.isEmpty() || body == null || !verifyAcknowledgmentToken(body.ackToken(), exactRequest)) {
            throw new TradeRejectedException(List.of(
                    "The adjusted position's material risks must be acknowledged from this exact preview."));
        }
    }

    /** Enforces the risk-acknowledgment contract for a decision executed outside the Practice
     *  ledger (a broker-recorded placement). Material risks only: Practice buying-power verdicts
     *  do not gate what already happened at the user's real broker. */
    void requireRecordedPlacementApproval(Context ctx, TradeOpenRequest body) {
        PlacementCheck check = placementCheck(ctx, body, null);
        if (check.requiredAcknowledgments().isEmpty()) return;
        Set<String> acknowledged = body.acknowledgedRisks() == null
                ? Set.of() : new HashSet<>(body.acknowledgedRisks());
        List<String> missing = check.requiredAcknowledgments().stream()
                .map(ApiResponses.RiskAcknowledgment::id)
                .filter(id -> !acknowledged.contains(id)).toList();
        if (!missing.isEmpty() || !verifyAcknowledgmentToken(body.ackToken(), check.request())) {
            throw new TradeRejectedException(List.of("This position's material risks must be "
                    + "acknowledged from this exact preview before the broker record is written."));
        }
    }

    ApiResponses.TradePreviewResponse previewPayloadForAccount(Context ctx, TradeOpenRequest body,
                                                                String expectedAccountId,
                                                                PlacementProjection projection) {
        if (!currentAccount.apply(ctx).id().equals(expectedAccountId)) {
            throw new IllegalStateException("Open the Practice account that owns this position before changing it.");
        }
        return previewPayload(ctx, body, projection);
    }

    private io.liftandshift.strikebench.eval.PortfolioExposureContext practiceExposure(
            Account account, String symbol, String excludedTradeId) {
        var exposure = trades.portfolioDollarDelta(account.id(), symbol, excludedTradeId);
        return new io.liftandshift.strikebench.eval.PortfolioExposureContext(
                io.liftandshift.strikebench.position.PositionDomain.ExecutionLane.PRACTICE,
                exposure.grossCents(), exposure.netCents(), exposure.focusSymbolGrossCents(),
                exposure.complete(), exposure.basis());
    }

    CreatedTrade execute(Context ctx, TradeOpenRequest body, TradeService.TransactionHook hook) {
        PlacementCheck check = placementCheck(ctx, body, null);
        Account account = check.account();
        TradeService.OpenRequest request = check.request();
        Verdict verdict = check.verdict();
        requirePlacementApproval(body, check);
        TradeRecord trade = trades.create(request, hook);
        if (body.recommendationId() != null && !body.recommendationId().isBlank()
                && account.worldId() == null) {
            try {
                evaluations.linkTrade(body.recommendationId(), trade.id());
            } catch (RuntimeException e) {
                log.warn("The paper trade could not be linked to its recommendation record");
                log.debug("Recommendation-link detail", e);
            }
        }
        return new CreatedTrade(trade, verdict);
    }

    TradeService.OpenRequest approvedTransformationRequest(Context ctx, TradeOpenRequest body,
                                                           String expectedAccountId,
                                                           PlacementProjection projection) {
        PlacementCheck check = placementCheck(ctx, body, projection);
        if (!check.account().id().equals(expectedAccountId)) {
            throw new IllegalStateException("The replacement must use the same active Practice account.");
        }
        requirePlacementApproval(body, check);
        return check.request();
    }

    PlacementProjection projectionAfterClose(Context ctx, TradeRecord trade,
                                             TradeService.UnwindAssessment unwind) {
        Account account = currentAccount.apply(ctx);
        if (!account.id().equals(trade.accountId())) {
            throw new IllegalStateException("Open the Practice account that owns this position before changing it.");
        }
        long cash = Math.subtractExact(Math.addExact(account.cashCents(), unwind.closingCashCents()),
                unwind.closingFeesCents());
        long reserved = Math.subtractExact(account.reservedCents(), unwind.current().risk().reserveCents());
        if (reserved < 0) {
            throw new IllegalStateException("The current position reserve does not reconcile with its Practice account.");
        }
        return new PlacementProjection(cash, reserved, trade.sharesLocked(), trade.id());
    }

    private PlacementCheck placementCheck(Context ctx, TradeOpenRequest body,
                                          PlacementProjection projection) {
        Account account = currentAccount.apply(ctx);
        TradeService.OpenRequest request = toOpenRequest(body, account);
        long cash = projection == null ? account.cashCents() : projection.cashCents();
        long reserved = projection == null ? account.reservedCents() : projection.reservedCents();
        long releasedShares = projection == null ? 0 : projection.releasedShares();
        long buyingPower = Math.subtractExact(cash, reserved);
        Verdict verdict = guardrailCheck(request, account, riskCapCents(ctx), buyingPower, releasedShares);
        io.liftandshift.strikebench.paper.TradePreview preview = trades.preview(request, cash, reserved, releasedShares);
        long effectiveRiskBudget = RiskBudgetPolicy.compute(
                RecommendationEngine.RiskMode.parse(request.riskMode()),
                buyingPower, riskCapCents(ctx)).effectiveBudgetCents();
        return new PlacementCheck(account, request, verdict, preview, effectiveRiskBudget,
                requiredAcksFor(preview, effectiveRiskBudget));
    }

    private void requirePlacementApproval(TradeOpenRequest body, PlacementCheck check) {
        if (check.verdict().blocked()) {
            audit.log(check.account().id(), null, "TRADE_REJECTED", "BLOCK",
                    Map.of("symbol", check.request().symbol(), "strategy", check.request().strategy(),
                            "reasons", check.verdict().blockReasons()));
            throw new TradeRejectedException(check.verdict().blockReasons());
        }
        if (check.requiredAcknowledgments().isEmpty()) return;
        Set<String> acknowledged = body.acknowledgedRisks() == null
                ? Set.of() : new HashSet<>(body.acknowledgedRisks());
        List<String> missing = check.requiredAcknowledgments().stream().map(ApiResponses.RiskAcknowledgment::id)
                .filter(id -> !acknowledged.contains(id)).toList();
        if (!missing.isEmpty()) {
            throw new TradeRejectedException(List.of("This trade carries material risks that must be "
                    + "acknowledged first: " + String.join(", ", missing)
                    + " — preview it to see them"));
        }
        if (!verifyAcknowledgmentToken(body.ackToken(), check.request())) {
            throw new TradeRejectedException(List.of(
                    "Acknowledgment token missing or stale — preview this exact package again"));
        }
    }

    ApiResponses.TradeDetail<TradeView, TradeService.MarkView, Object, Object> detailData(String id) {
        TradeRecord trade = trades.get(id);
        TradeService.MarkView current = null;
        if (TradeRecord.ACTIVE.equals(trade.status())) {
            try {
                current = trades.currentMark(id);
            } catch (Exception e) {
                log.warn("Current paper-trade mark is unavailable for {}", id);
                log.debug("Paper-trade mark detail for " + id, e);
            }
        }
        return new ApiResponses.TradeDetail<>(TradeView.of(trade), current,
                trades.marksHistory(id, 50), audit.forTrade(id, 50), payoffPoints(trade));
    }

    static long decisionPnl(TradeRecord trade, long packagePnlCents) {
        return trade.decisionPnlCents() == null ? packagePnlCents : trade.decisionPnlCents();
    }

    void resolveRecommendation(String tradeId, String status, Long pnlCents) {
        try {
            TradeRecord trade = trades.get(tradeId);
            Account lane = accounts.get(trade.accountId());
            if (lane.worldId() != null || "DEMO".equals(lane.type())
                    || "SIMULATION".equals(lane.type())) return;
            if (!("OBSERVED".equals(trade.dataProvenance())
                    || "BROKER".equals(trade.dataProvenance()))) return;
            evaluations.resolveByTrade(tradeId, status, pnlCents);
        } catch (RuntimeException e) {
            log.warn("Recommendation context is unavailable for trade {}", tradeId);
            log.debug("Recommendation-context detail for " + tradeId, e);
        }
    }

    TradeService.OpenRequest toOpenRequest(TradeOpenRequest body, Account account) {
        return toOpenRequest(body, account.id());
    }

    static TradeService.OpenRequest toOpenRequest(TradeOpenRequest body, String accountId) {
        return toOpenRequest(body, accountId, false);
    }

    static TradeService.OpenRequest toAnalysisOpenRequest(TradeOpenRequest body, String accountId) {
        return toOpenRequest(body, accountId, true);
    }

    private static TradeService.OpenRequest toOpenRequest(TradeOpenRequest body, String accountId,
                                                          boolean analysisOnly) {
        if (body == null) throw new IllegalArgumentException("request body is required");
        if (body.symbol() == null || body.symbol().isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        if (body.legs() == null || body.legs().isEmpty()) {
            throw new IllegalArgumentException("legs are required");
        }
        if (body.fillNature() == null || body.fillNature().isBlank()) {
            throw new IllegalArgumentException("fillNature is required and must be PROPOSED or EXECUTED");
        }
        if (!"PROPOSED".equalsIgnoreCase(body.fillNature())
                && !"EXECUTED".equalsIgnoreCase(body.fillNature())) {
            throw new IllegalArgumentException("fillNature must be PROPOSED or EXECUTED");
        }
        if (body.strategy() == null || body.strategy().isBlank()) {
            throw new IllegalArgumentException("strategy is required");
        }
        if (body.qty() == null || body.qty() < 1
                || analysisOnly && body.qty() > 1_000_000
                || !analysisOnly && body.qty() > 100) {
            throw new IllegalArgumentException(analysisOnly
                    ? "qty must be 1..1,000,000 for analysis"
                    : "qty must be 1..100 for Practice placement");
        }
        if (body.source() == null || body.source().isBlank()) {
            throw new IllegalArgumentException("source is required");
        }
        if (body.legs().stream().anyMatch(leg -> "CLOSE".equalsIgnoreCase(leg.positionEffect()))) {
            throw new IllegalArgumentException("Closing legs change an existing tracked position. Use the tracked position transformation preview so the before-and-after structure, realized result, and surviving lots stay visible.");
        }
        List<Leg> legs = body.legs().stream().map(LegView::toLeg).toList();
        if (body.intent() != null && !body.intent().isBlank()) StrategyIntent.parse(body.intent());
        String suppliedStrategy = body.strategy().trim().toUpperCase(Locale.ROOT);
        var identified = io.liftandshift.strikebench.strategy.StrategyCatalog.identify(
                body.symbol().trim().toUpperCase(Locale.ROOT), body.qty(), legs);
        String strategy = "CUSTOM".equals(suppliedStrategy) && identified.family() != null
                ? identified.family() : suppliedStrategy;
        return new TradeService.OpenRequest(accountId, body.symbol().trim().toUpperCase(Locale.ROOT), strategy,
                body.qty(), legs, body.thesis(), body.horizon(),
                body.riskMode(), body.intent(), body.useHeldShares(), body.proposedNetCents(),
                body.feesOverrideCents(),
                body.source(),
                body.fillNature());
    }

    Long riskCapCents(Context ctx) {
        AccountRiskContext context = AccountRiskContext.load(db, ownerId.apply(ctx));
        return context.riskCapitalCents() != null && context.riskCapitalCents() > 0
                ? context.riskCapitalCents() : null;
    }

    private Verdict guardrailCheck(TradeService.OpenRequest request, Account account,
                                   Long riskCapCents, long buyingPowerCents,
                                   long releasedShares) {
        StrategyFamily family = null;
        try {
            family = StrategyFamily.valueOf(request.strategy());
        } catch (IllegalArgumentException ignored) {
            // Custom positions are validated by their exact legs.
        }
        List<OptionQuote> quotes = new ArrayList<>();
        Freshness worst = Freshness.REALTIME;
        String accountWorld = "DEMO".equals(account.type()) ? "demo" : account.worldId();
        MarketLane lane = MarketLane.of(accountWorld, cfg.fixturesOnly());
        List<String> integrityBlocks = new ArrayList<>();
        LocalDate latestExpiration = request.legs().stream().filter(leg -> !leg.isStock())
                .map(Leg::expiration).filter(Objects::nonNull).max(LocalDate::compareTo).orElse(null);
        boolean inWorld = accountWorld != null;
        boolean earningsSoon = !inWorld && latestExpiration != null
                && eventCalendar.earningsLikelyBefore(request.symbol(), latestExpiration);
        boolean eventLikeNews = !inWorld && market.news(request.symbol()).stream().anyMatch(news -> {
            String headline = news.headline() == null ? "" : news.headline().toLowerCase(Locale.ROOT);
            return headline.contains("earnings") || headline.contains("guidance")
                    || headline.contains("results");
        });
        Quote underlyingQuote = market.quote(request.symbol(), accountWorld).orElse(null);
        if (underlyingQuote != null && !underlyingQuote.evidence().usableIn(lane)) {
            integrityBlocks.add("The " + lane + " market cannot use "
                    + underlyingQuote.evidence().provenance() + " underlying data from "
                    + underlyingQuote.evidence().source());
        }
        BigDecimal spot = underlyingQuote == null ? null : underlyingQuote.mark();
        List<Leg> priced = new ArrayList<>(request.legs().size());
        for (Leg leg : request.legs()) {
            if (leg.isStock()) {
                quotes.add(null);
                BigDecimal price = leg.entryPrice().signum() > 0
                        ? leg.entryPrice() : spot == null ? BigDecimal.ZERO : spot;
                priced.add(new Leg(leg.action(), null, null, null, leg.ratio(), price, leg.multiplier()));
                continue;
            }
            OptionChain chain = market.chain(request.symbol(), leg.expiration(), accountWorld).orElse(null);
            if (chain != null && !chain.evidence().executableIn(lane)) {
                integrityBlocks.add("The " + lane + " market cannot execute " + leg.strike()
                        + " " + leg.type() + " " + leg.expiration() + " from "
                        + chain.evidence().provenance() + " data (" + chain.evidence().source() + ")");
            }
            OptionQuote quote = chain == null ? null : chain.find(leg.type(), leg.strike()).orElse(null);
            quotes.add(quote);
            if (quote != null) worst = Freshness.worse(worst, quote.freshness());
            BigDecimal mid = leg.entryPrice().signum() > 0 ? leg.entryPrice()
                    : quote != null && quote.mid() != null ? quote.mid() : BigDecimal.ZERO;
            priced.add(new Leg(leg.action(), leg.type(), leg.strike(), leg.expiration(),
                    leg.ratio(), mid, leg.multiplier()));
        }
        long lockedShares = 0;
        String shareShortfall = null;
        if (request.heldShares()) {
            long coverSharesPerUnit = Math.max(0,
                    io.liftandshift.strikebench.strategy.CoverageCheck.callCoverSharesNeeded(priced));
            long contextSharesPerUnit = Math.max(coverSharesPerUnit,
                    io.liftandshift.strikebench.strategy.CoverageCheck.shareContextUnitsNeeded(priced));
            long neededShares = Math.multiplyExact(contextSharesPerUnit, request.qty());
            long freeShares = Math.addExact(positions.freeShares(account.id(), request.symbol()),
                    releasedShares);
            if (freeShares >= neededShares) {
                lockedShares = Math.multiplyExact(coverSharesPerUnit, request.qty());
            } else {
                shareShortfall = "Needs " + neededShares + " free shares of " + request.symbol()
                        + " but only " + Math.max(0, freeShares)
                        + " are free (held minus already locked)";
            }
        }
        LocalDate laneToday = market.simInstant(accountWorld)
                .map(instant -> LocalDate.ofInstant(instant,
                        io.liftandshift.strikebench.market.MarketHours.EASTERN))
                .orElseGet(() -> LocalDate.now(clock));
        Verdict verdict = Guardrails.check(new Guardrails.Proposal(family, priced, request.qty(),
                quotes, spot, worst, laneToday, buyingPowerCents, false,
                earningsSoon, false, lockedShares));
        if (!integrityBlocks.isEmpty()) {
            List<String> blocks = new ArrayList<>(verdict.blockReasons());
            blocks.addAll(0, integrityBlocks);
            verdict = Verdict.of(blocks, verdict.warnings());
        }
        if (shareShortfall != null) {
            List<String> blocks = new ArrayList<>(verdict.blockReasons());
            blocks.addFirst(shareShortfall);
            verdict = Verdict.of(blocks, verdict.warnings());
        }
        if (earningsSoon) {
            EventService.EarningsEstimate estimate = eventCalendar.nextEarnings(request.symbol()).orElse(null);
            if (estimate != null) {
                List<String> warnings = new ArrayList<>(verdict.warnings());
                warnings.add("Earnings ESTIMATED around " + estimate.estimated() + " ±"
                        + estimate.windowDays() + "d (" + estimate.basis()
                        + ") — not a confirmed date, but it lands inside this trade");
                verdict = Verdict.of(verdict.blockReasons(), warnings);
            }
        } else if (eventLikeNews) {
            List<String> warnings = new ArrayList<>(verdict.warnings());
            warnings.add("Event-like news in recent headlines (earnings/guidance keywords) — "
                    + "a news signal only; no earnings event is ESTIMATED before this trade's expiration");
            verdict = Verdict.of(verdict.blockReasons(), warnings);
        }
        try {
            Long adjustment = null;
            if (request.proposedNetCents() != null) {
                long pricedNet = 0;
                for (Leg leg : priced) {
                    long shares = (long) leg.multiplier() * leg.ratio() * request.qty();
                    long cents = io.liftandshift.strikebench.util.Money.centsFromPrice(
                            leg.entryPrice(), shares);
                    pricedNet += leg.action() == io.liftandshift.strikebench.model.LegAction.SELL
                            ? cents : -cents;
                }
                adjustment = request.proposedNetCents() - pricedNet;
            }
            PayoffCurve curve = PayoffCurve.of(priced, request.qty(), adjustment);
            if (!curve.maxLossUnbounded() && curve.maxLossCents() > 0) {
                RecommendationEngine.RiskMode mode =
                        RecommendationEngine.RiskMode.parse(request.riskMode());
                long budget = RiskBudgetPolicy.compute(mode, buyingPowerCents, riskCapCents)
                        .effectiveBudgetCents();
                if (curve.maxLossCents() > budget) {
                    List<String> warnings = new ArrayList<>(verdict.warnings());
                    warnings.add("Max loss "
                            + io.liftandshift.strikebench.util.Money.fmt(curve.maxLossCents())
                            + " exceeds your " + mode.name().toLowerCase(Locale.ROOT)
                            + " risk budget of "
                            + io.liftandshift.strikebench.util.Money.fmt(budget)
                            + " per trade — allowed, but oversized for your chosen risk mode");
                    verdict = Verdict.of(verdict.blockReasons(), warnings);
                }
            }
        } catch (RuntimeException ignored) {
            // Budget comparison is advisory; structural checks above remain authoritative.
        }
        return verdict;
    }

    static Candidate exactPreviewCandidate(TradeService.OpenRequest request,
            io.liftandshift.strikebench.paper.TradePreview preview) {
        StrategyFamily family = null;
        try {
            family = StrategyFamily.valueOf(request.strategy().trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ignored) {
            // Explicit custom package.
        }
        String display = family == null ? "Custom position" : family.display();
        String group = family == null ? "custom" : family.structureGroup();
        String intent = request.intent() == null || request.intent().isBlank()
                ? family == null ? StrategyIntent.DIRECTIONAL.name() : family.primaryIntent().name()
                : StrategyIntent.parse(request.intent()).name();
        List<String> intents = family == null ? List.of(intent)
                : family.intents().stream().map(Enum::name).sorted().toList();
        // A mechanically refused package can have no executable leg-detail rows (for example an
        // impossible user price). Its entered geometry still needs a complete assessment: use the
        // request facts and let the authoritative package net carry the price constraint.
        List<LegView> legs = preview.legs().isEmpty()
                ? request.legs().stream().map(LegView::of).toList()
                : preview.legs().stream().map(mark -> new LegView(
                    Objects.toString(mark.get("action"), null),
                    Objects.toString(mark.get("type"), null),
                    Objects.toString(mark.get("strike"), null),
                    Objects.toString(mark.get("expiration"), null),
                    requiredPositiveInteger(mark, "ratio"),
                    Objects.toString(mark.get("fill"), null),
                    requiredPositiveInteger(mark, "multiplier"),
                    "OPEN")).toList();
        boolean liquid = preview.legs().stream().filter(mark -> !"STOCK".equals(mark.get("type")))
                .allMatch(mark -> mark.get("bid") != null && mark.get("ask") != null);
        Long combinedMaxLoss = preview.analytics().get("combinedMaxLossCents") instanceof Number number
                ? number.longValue() : null;
        Integer sharesNeeded = null;
        if (request.heldShares()) {
            long units = io.liftandshift.strikebench.strategy.CoverageCheck
                    .shareContextUnitsNeeded(request.legs());
            sharesNeeded = Math.toIntExact(Math.multiplyExact(units, request.qty()));
        }
        return new Candidate(request.strategy(), display, group, display, legs, request.qty(),
                preview.entryNetPremiumCents(), preview.maxProfitCents(), preview.maxLossCents(),
                preview.breakevens(), preview.popEntry(), preview.expectedValueCents(),
                liquid ? 1.0 : 0.0, preview.freshness(), preview.warnings(), 1,
                "Exact ticket", "", "", "", "", intent, intents, preview.assignmentProb(),
                null, null, null, request.heldShares() ? Boolean.TRUE : null,
                sharesNeeded, combinedMaxLoss);
    }

    private List<ApiResponses.RiskAcknowledgment> requiredAcksFor(
            io.liftandshift.strikebench.paper.TradePreview preview,
            long effectiveRiskBudgetCents) {
        List<ApiResponses.RiskAcknowledgment> out = new ArrayList<>();
        if (preview == null || preview.analytics() == null) return out;
        Long afterCosts = preview.expectedValueCents() == null ? null
                : preview.expectedValueCents() - Math.multiplyExact(preview.feesOpenCents(), 2L);
        if (afterCosts != null && afterCosts < 0) {
            out.add(new ApiResponses.RiskAcknowledgment("ack-ev",
                    "The model expects this trade to LOSE "
                            + io.liftandshift.strikebench.util.Money.fmt(-afterCosts)
                            + " on average at the market's own volatility."));
        }
        Object execution = preview.analytics().get("executionQuality");
        if (execution instanceof Map<?, ?> quality
                && quality.get("concessionPctOfMid") instanceof Double concession
                && Math.abs(concession) > 0.10) {
            out.add(new ApiResponses.RiskAcknowledgment("ack-exec",
                    "Entering surrenders " + Math.round(Math.abs(concession) * 100)
                            + "% of the package midpoint to the bid/ask spread."));
        }
        Object management = preview.analytics().get("managementPlan");
        if (management instanceof Map<?, ?> plan
                && String.valueOf(plan.get("regime")).contains("near-expiry")) {
            out.add(new ApiResponses.RiskAcknowledgment("ack-dte", "Only " + plan.get("sessions")
                    + " trading session(s) remain — gamma, weekend gaps and pin risk dominate."));
        }
        if (effectiveRiskBudgetCents > 0 && preview.maxLossCents() > effectiveRiskBudgetCents) {
            out.add(new ApiResponses.RiskAcknowledgment("ack-capital", "The theoretical max loss "
                    + io.liftandshift.strikebench.util.Money.fmt(preview.maxLossCents())
                    + " exceeds your selected per-trade risk budget ("
                    + io.liftandshift.strikebench.util.Money.fmt(effectiveRiskBudgetCents) + ")."));
        }
        return out;
    }

    private String acknowledgmentToken(TradeService.OpenRequest request) {
        long timestamp = clock.millis();
        return timestamp + "." + acknowledgmentHmac(request, timestamp);
    }

    private boolean verifyAcknowledgmentToken(String token, TradeService.OpenRequest request) {
        if (token == null || !token.contains(".")) return false;
        try {
            long timestamp = Long.parseLong(token.substring(0, token.indexOf('.')));
            if (Math.abs(clock.millis() - timestamp) > 15 * 60_000L) return false;
            String expected = acknowledgmentHmac(request, timestamp);
            return java.security.MessageDigest.isEqual(
                    expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    token.substring(token.indexOf('.') + 1)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String acknowledgmentHmac(TradeService.OpenRequest request, long timestamp) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(acknowledgmentSecret, "HmacSHA256"));
            String payload = request.symbol() + "|" + request.strategy() + "|" + request.qty()
                    + "|" + io.liftandshift.strikebench.util.Json.canonical(request.legs())
                    + "|" + request.proposedNetCents() + "|" + request.feesOverrideCents()
                    + "|" + request.accountId() + "|" + timestamp;
            return java.util.HexFormat.of().formatHex(mac.doFinal(
                    payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    void ensureOwnedTrade(Context ctx, String tradeId) {
        TradeRecord trade = trades.get(tradeId);
        if (!trade.accountId().equals(currentAccount.apply(ctx).id())) {
            throw new ResourceNotFoundException("no such trade " + tradeId);
        }
    }

    static List<ApiResponses.PayoffPoint> payoffPoints(TradeRecord trade) {
        boolean mixedExpirations = trade.legs().stream().filter(leg -> !leg.isStock())
                .map(Leg::expiration).distinct().count() > 1;
        if (mixedExpirations) return List.of();
        BigDecimal spot = BigDecimal.valueOf(trade.entryUnderlyingCents()).movePointLeft(2);
        List<Leg> chartLegs = trade.legs();
        long heldShares = TradeService.heldShareContextSharesForDisplay(trade);
        long sharesPerUnit = trade.qty() > 0 ? heldShares / trade.qty() : 0;
        if (sharesPerUnit > 0 && sharesPerUnit * trade.qty() == heldShares) {
            chartLegs = new ArrayList<>(trade.legs());
            chartLegs.add(Leg.stockShares(io.liftandshift.strikebench.model.LegAction.BUY,
                    Math.toIntExact(sharesPerUnit), spot));
        }
        long tradedLegEntry = PayoffCurve.of(trade.legs(), trade.qty()).entryNetPremiumCents();
        long adjustment = trade.entryNetPremiumCents() - tradedLegEntry;
        PayoffCurve curve = PayoffCurve.of(chartLegs, trade.qty(), adjustment);
        return curve.chartPoints(spot).stream()
                .map(point -> new ApiResponses.PayoffPoint(
                        point.price().toPlainString(), point.profitCents()))
                .toList();
    }

    private static Double percentage(long numerator, Long denominator) {
        return denominator != null && denominator > 0
                ? Math.round(1000.0 * numerator / denominator) / 10.0 : null;
    }

    private static int requiredPositiveInteger(Map<String, Object> values, String key) {
        Object raw = values.get(key);
        if (!(raw instanceof Number number) || number.intValue() < 1) {
            throw new IllegalStateException("Exact preview leg is missing a valid " + key + ".");
        }
        return number.intValue();
    }

    private static String worldParam(String world) {
        return world == null || "observed".equals(world) ? null : world;
    }
}
