package io.liftandshift.strikebench.api;

import io.liftandshift.strikebench.model.Symbol;
import static io.liftandshift.strikebench.market.MarketLane.worldParam;

import com.fasterxml.jackson.databind.JsonNode;
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
import io.liftandshift.strikebench.paper.PackagePriceReceipt;
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
import io.liftandshift.strikebench.util.Json;
import io.liftandshift.strikebench.util.Money;
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
import java.util.function.Supplier;

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
    private final TrackedPackageAnalysisService lifecycleAnalyses;
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
                    ExactAssessment exactAssessment,
                    TrackedPackageAnalysisService lifecycleAnalyses) {
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
        this.lifecycleAnalyses = lifecycleAnalyses;
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
                Long roundTripFeesCents,
                io.liftandshift.strikebench.eval.PortfolioExposureContext portfolioExposure,
                io.liftandshift.strikebench.eval.DeclaredObjective declared);
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
    /**
     * One exact package approved through the same path as Practice placement, but not mutated into
     * the Practice ledger. The broker boundary may translate this package into provider protocol;
     * it may not price, size, screen, or authorize it again.
     */
    record ApprovedLiveOrder(Account account, TradeService.OpenRequest request,
                             ApiResponses.TradePreviewResponse receipt) {}
    record PlacementProjection(long cashCents, long reservedCents, long releasedShares,
                               String excludedTradeId) {
        long buyingPowerCents() { return Math.subtractExact(cashCents, reservedCents); }
    }
    record HeldReceipts(
            io.liftandshift.strikebench.eval.RiskProfile.TerminalPayoff terminalPayoff,
            ApiResponses.HeldScenarios scenarios,
            ApiResponses.HeldSpotPnl spotPnl) {}
    private record StockOrderRequest(String symbol, Long shares) {}
    private record StockOrderPreviewRequest(String side, String symbol, Long shares) {}

    private void preview(Context ctx) {
        rejectRemovedProposalAlias(ctx);
        ctx.json(previewPayload(ctx, ApiRequest.bodyOrNull(ctx, TradeOpenRequest.class)));
    }

    private void create(Context ctx) {
        rejectRemovedProposalAlias(ctx);
        CreatedTrade created = execute(ctx, ApiRequest.bodyOrNull(ctx, TradeOpenRequest.class), null);
        ctx.status(201).json(new ApiResponses.CreatedTrade<>(
                TradeView.of(created.trade()), created.verdict().warnings()));
    }

    private static void rejectRemovedProposalAlias(Context ctx) {
        JsonNode body = Json.parse(ctx.body());
        if (body.has("proposedNetCents")) {
            throw new IllegalArgumentException(
                    "proposedNetCents was removed; use orderInstruction.limitNetCents for a LIMIT order");
        }
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
                Quote positionQuote = null;
                try {
                    positionQuote = trades.currentUnderlyingQuote(trade.id()).orElse(null);
                } catch (RuntimeException failure) {
                    log.debug("Paper-trade list underlying quote unavailable for " + trade.id(),
                            failure);
                }
                // The held bloom/spectrum reads exact server receipts off the roster row. Each
                // receipt is composed independently: failure to produce one visualization must
                // never erase the valid siblings already owned by the engine. Current Greeks
                // remain exclusively on TradeDetail.current.
                HeldReceipts held = heldReceipts(trade, positionQuote, true);
                row = row.withHeldReceipts(held.terminalPayoff(), held.scenarios(), held.spotPnl());
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

    ApprovedLiveOrder approvedLiveOrder(Context ctx, TradeOpenRequest body,
                                        boolean proceedWithoutEndorsement) {
        if (body == null) throw new IllegalArgumentException("request body is required");
        if (!"PROPOSED".equalsIgnoreCase(body.fillNature())) {
            throw new IllegalArgumentException(
                    "A live order must be a PROPOSED package, never an imported EXECUTED fill.");
        }
        if (body.orderInstruction() == null
                || body.orderInstruction().type()
                != io.liftandshift.strikebench.paper.OrderInstruction.Type.LIMIT) {
            throw new IllegalArgumentException(
                    "Live option packages require an explicit signed LIMIT; MARKET is never the default.");
        }

        PlacementCheck check = placementCheck(ctx, body, null);
        requirePlacementApproval(body, check);
        ApiResponses.TradePreviewResponse receipt = reviewPayload(
                ctx, check.account(), check.request(), check.preview(), check.verdict(),
                check.requiredAcknowledgments(), null);
        if (receipt.evaluation() == null || !receipt.evaluation().available()) {
            throw new IllegalArgumentException(
                    "Live preview requires a complete canonical package evaluation.");
        }
        if (receipt.execution() == null || !receipt.execution().confirmAllowed()) {
            String reason = receipt.execution() == null
                    || receipt.execution().reasons() == null
                    || receipt.execution().reasons().isEmpty()
                    ? "The canonical execution receipt is not confirmable."
                    : String.join(" ", receipt.execution().reasons());
            throw new IllegalArgumentException(reason);
        }
        PackagePriceReceipt price = receipt.preview() == null
                ? null : receipt.preview().price();
        if (price == null || price.fingerprint() == null
                || price.fingerprint().isBlank()
                || price.executableNetCents() == null) {
            throw new IllegalArgumentException(
                    "The canonical package has no executable price receipt.");
        }
        if (!price.executableNetCents().equals(
                body.orderInstruction().limitNetCents())) {
            throw new IllegalArgumentException(
                    "The live LIMIT must equal the canonical executable package net; preview again.");
        }
        if ((receipt.endorsement() == null || !receipt.endorsement().endorsed())
                && !proceedWithoutEndorsement) {
            throw new IllegalArgumentException(
                    "This package is a comparison, not an endorsement. Explicitly acknowledge "
                            + "proceedWithoutEndorsement to request a live preview.");
        }
        return new ApprovedLiveOrder(check.account(), check.request(), receipt);
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
        ApiResponses.EvaluationReceipt evaluation;
        io.liftandshift.strikebench.eval.StrategyEvaluation exactEvaluation = null;
        // §3.1: the round-trip commission is the §7.2 receipt's own doubling, not a fourth copy of
        // `feesOpenCents * 2` — and §3.2: null when the package states no commission, so the
        // assessment reports "no EV after costs" instead of netting the gross EV against $0.
        Long roundTripFees = exactRoundTripFees(preview);
        Candidate screenedCandidate = null;
        if (!preview.hasRiskFacts()) {
            evaluation = unavailableRiskEvaluation(preview, "The exact package");
        } else {
            try {
                Candidate exact = exactPreviewCandidate(request, preview);
                screenedCandidate = exact;
                exactEvaluation = exactAssessment.assess(
                        request.symbol(), exact, preview.buyingPowerBeforeCents(),
                        analysisContext.apply(ctx), worldParam(activeWorld.apply(ctx)), preview.ok(),
                        preview.blockReasons(), roundTripFees, practiceExposure(account, request.symbol(),
                                excludedTradeId), declaredOrderObjective(request));
                evaluation = ApiResponses.EvaluationReceipt.of(exactEvaluation);
            } catch (RuntimeException e) {
                log.warn("Exact-ticket assessment is unavailable for this preview", e);
                evaluation = unavailableAssessmentEvaluation(preview);
            }
        }
        io.liftandshift.strikebench.strategy.StrategyCatalog.PositionIdentity screenedIdentity =
                positionIdentity(request);
        // The generation gates (income-cash coherence, condor-quality) previously screened only
        // ENGINE-built packages; a stepped/custom draft with an identical shape was never
        // re-screened and surfaced with engine labels and no refusal. The same gates now speak
        // on every exact preview — as named warnings, since an explicit user package is the
        // user's to place, but never silently.
        java.util.List<String> screenNotes = generationScreenNotes(request, screenedCandidate, screenedIdentity);
        ApiResponses.Guardrails guardrails = new ApiResponses.Guardrails(
                verdict.level().name(), verdict.blockReasons(),
                screenNotes.isEmpty() ? verdict.warnings()
                        : java.util.stream.Stream.concat(verdict.warnings().stream(), screenNotes.stream())
                                .distinct().toList());
        AccountRiskContext riskContext = AccountRiskContext.load(db, ownerId.apply(ctx));
        String token = required.isEmpty() ? null : acknowledgmentToken(request);
        io.liftandshift.strikebench.strategy.StrategyCatalog.PositionIdentity identity =
                positionIdentity(request);
        ApiResponses.AccountFit accountFit = null;
        if (preview.maxLossCents() != null) {
            long maxLoss = preview.requiredMaxLossCents();
            Double pctOfNlv = percentage(maxLoss, riskContext.nlvCents());
            Double pctOfCash = percentage(maxLoss, riskContext.cashBpCents());
            Double pctOfMargin = percentage(maxLoss, riskContext.marginBpCents());
            Double pctOfRiskCapital = percentage(maxLoss, riskContext.riskCapitalCents());
            Boolean overRiskCapital = riskContext.riskCapitalCents() != null
                    && riskContext.riskCapitalCents() > 0
                    && maxLoss > riskContext.riskCapitalCents() ? true : null;
            accountFit = new ApiResponses.AccountFit(pctOfNlv, pctOfCash, pctOfMargin,
                    pctOfRiskCapital, overRiskCapital,
                    selectedCapitalUse(request, preview, identity, riskContext.riskCapitalCents()));
        }
        var endorsement = exactEvaluation == null
                ? new io.liftandshift.strikebench.eval.DecisionEndorsement(false,
                    io.liftandshift.strikebench.eval.DecisionEndorsement.COMPARISON, null,
                    List.of(evaluation.unavailableReason() == null
                            ? "The exact package evaluation is unavailable."
                            : evaluation.unavailableReason()),
                    "The exact package remains a comparison until its backend evaluation is available.")
                : exactEvaluation.endorsement();
        var execution = executionDecision(preview, guardrails);
        return new ApiResponses.TradePreviewResponse(preview, evaluation, guardrails,
                required.isEmpty() ? null : required, token, accountFit, identity, endorsement,
                execution);
    }

    static ApiResponses.ExecutionDecision executionDecision(
            io.liftandshift.strikebench.paper.TradePreview preview,
            ApiResponses.Guardrails guardrails) {
        List<String> reasons = java.util.stream.Stream.concat(
                        preview.blockReasons().stream(), guardrails.blockReasons().stream())
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(reason -> !reason.isEmpty())
                .distinct()
                .toList();
        boolean reviewAllowed = preview.ok() && reasons.isEmpty()
                && !"BLOCK".equalsIgnoreCase(guardrails.level());
        var price = preview.price();
        boolean immediate = price != null
                && price.executability()
                == io.liftandshift.strikebench.paper.OrderInstruction.Executability.IMMEDIATE;
        boolean confirmAllowed = reviewAllowed && immediate;
        if (!reviewAllowed && reasons.isEmpty()) {
            reasons = List.of("This exact instruction is unavailable.");
        } else if (reviewAllowed && !immediate) {
            reasons = List.of("This exact instruction is not presently executable.");
        }
        return new ApiResponses.ExecutionDecision(reviewAllowed, confirmAllowed, reasons);
    }

    /**
     * Exact-leg identity is primary. A lone short put is intentionally ambiguous from legs alone;
     * only in that case may the declared CASH_SECURED_PUT/NAKED_PUT family supply the collateral
     * classification, because the exact preview separately enforces whether that declaration is
     * funded.
     */
    private static io.liftandshift.strikebench.strategy.StrategyCatalog.PositionIdentity positionIdentity(
            TradeService.OpenRequest request) {
        var exact = io.liftandshift.strikebench.strategy.StrategyCatalog.identify(
                request.symbol(), request.qty(), request.legs());
        StrategyFamily declared = null;
        try {
            declared = StrategyFamily.valueOf(request.strategy());
        } catch (IllegalArgumentException ignored) {
            // Custom labels stay exact-package assessments.
        }
        if (declared != null && Boolean.TRUE.equals(request.useHeldShares())
                && declared.requiresLongStock()) {
            // Held shares are an explicit package context, not a browser inference. Exact option
            // legs alone look uncovered; the placement preview has already proved the held-share
            // coverage before this identity is published.
            return io.liftandshift.strikebench.strategy.StrategyCatalog.identify(declared);
        }
        if (exact.fundingClass()
                != io.liftandshift.strikebench.strategy.StrategyCatalog.FundingClass.UNCLASSIFIED) {
            return exact;
        }
        if (declared != null) {
            if (declared == StrategyFamily.CASH_SECURED_PUT || declared == StrategyFamily.NAKED_PUT) {
                return io.liftandshift.strikebench.strategy.StrategyCatalog.identify(declared);
            }
        }
        return exact;
    }

    /**
     * One selected-package capital receipt. The numerator is chosen from StrategyCatalog's typed
     * funding class, while the cap comes from the already-shared RiskBudgetPolicy. The browser no
     * longer chooses between max loss and reserve or derives remaining/overage itself.
     */
    private ApiResponses.CapitalUse selectedCapitalUse(
            TradeService.OpenRequest request,
            io.liftandshift.strikebench.paper.TradePreview preview,
            io.liftandshift.strikebench.strategy.StrategyCatalog.PositionIdentity identity,
            Long declaredRiskCapitalCents) {
        var funding = identity.fundingClass();
        if (funding
                == io.liftandshift.strikebench.strategy.StrategyCatalog.FundingClass.UNDEFINED_RISK) {
            return new ApiResponses.CapitalUse(funding.name(), identity.capitalBasis().name(),
                    null, null, null, null, null, null,
                    "Undefined-risk packages have no finite account cap receipt.",
                    "This exact package has no finite maximum loss.");
        }
        var requirement = io.liftandshift.strikebench.strategy.CapitalRequirement.of(
                identity, preview.price(), preview.maxLossCents(),
                combinedMaximumLossCents(request, preview),
                Boolean.TRUE.equals(request.useHeldShares()));
        if (!requirement.available()) {
            return new ApiResponses.CapitalUse(funding.name(), identity.capitalBasis().name(),
                    null, null, null, null, null, null, requirement.basis(),
                    requirement.unavailableReason());
        }
        long used;
        long cap;
        String basis;
        if (funding
                == io.liftandshift.strikebench.strategy.StrategyCatalog.FundingClass.CASH_COLLATERAL) {
            used = requirement.reserveCents();
            cap = preview.buyingPowerBeforeCents();
            basis = "Cash-collateral use is the exact package reserve measured against the "
                    + "account's buying power before this order.";
        } else {
            used = requirement.economicExposureCents();
            var budget = RiskBudgetPolicy.compute(
                    RecommendationEngine.RiskMode.parse(request.riskMode()),
                    preview.buyingPowerBeforeCents(), declaredRiskCapitalCents);
            cap = budget.effectiveBudgetCents();
            basis = "Capital at risk is the exact package maximum loss measured against the "
                    + "shared " + budget.label() + " risk budget"
                    + (budget.capped() ? ", capped by declared risk capital." : ".");
        }
        long remaining = Math.max(0L, cap - used);
        long overage = Math.max(0L, used - cap);
        int maximumQuantity;
        if (used == 0L) {
            maximumQuantity = 100;
        } else if (cap <= 0L) {
            maximumQuantity = 0;
        } else {
            // CapitalRequirement is already quantity-scaled. Repeating the exact same package is
            // linear for the finite funding classes represented here, so derive the ceiling once
            // on the server and publish it with the account-fit receipt. The browser must not
            // recreate this risk arithmetic or fall back to its former generic 1..100 policy.
            java.math.BigInteger numerator = java.math.BigInteger.valueOf(cap)
                    .multiply(java.math.BigInteger.valueOf(request.qty()));
            maximumQuantity = numerator.divide(java.math.BigInteger.valueOf(used))
                    .min(java.math.BigInteger.valueOf(100L)).intValue();
        }
        return new ApiResponses.CapitalUse(funding.name(), identity.capitalBasis().name(),
                cap, used, remaining, overage, overage == 0, maximumQuantity, basis, null);
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

    /** The engine's generation screens, re-voiced for an exact package: which refusal the
     *  ranked field would have given this same shape. Unknown/custom identities stay silent —
     *  the browser never guesses a family. */
    private static java.util.List<String> generationScreenNotes(
            TradeService.OpenRequest request, Candidate exact,
            io.liftandshift.strikebench.strategy.StrategyCatalog.PositionIdentity identity) {
        if (exact == null || identity == null || identity.custom() || identity.family() == null) {
            return java.util.List.of();
        }
        io.liftandshift.strikebench.strategy.StrategyFamily family;
        try {
            family = io.liftandshift.strikebench.strategy.StrategyFamily.valueOf(identity.family());
        } catch (IllegalArgumentException unknownFamily) {
            return java.util.List.of();
        }
        java.util.List<String> notes = new java.util.ArrayList<>();
        if (request.intent() != null && !request.intent().isBlank()) {
            try {
                var intent = io.liftandshift.strikebench.strategy.StrategyIntent
                        .valueOf(request.intent().toUpperCase(java.util.Locale.ROOT));
                String incoherence = io.liftandshift.strikebench.recommend.RecommendationEngine
                        .intentIncoherence(intent, family, exact);
                if (incoherence != null) notes.add("Engine screen: " + incoherence);
            } catch (IllegalArgumentException unknownIntent) { /* undeclared — nothing to screen */ }
        }
        String viability = io.liftandshift.strikebench.recommend.RecommendationEngine
                .packageViability(family, exact);
        if (viability != null) notes.add("Engine screen: " + viability);
        return notes;
    }

    /** The order's own DECLARED side of the coherence diagnostic: every OpenRequest carries the
     *  intent, thesis and horizon it was built from (a Plan's context, the ticket form, or the
     *  position record on a transformation). Assignment preference is not part of the order
     *  contract, so the single lens keyed on it does not apply to exact tickets. */
    private static io.liftandshift.strikebench.eval.DeclaredObjective declaredOrderObjective(
            TradeService.OpenRequest request) {
        String horizon = request.horizon();
        Integer horizonSessions = horizon == null || horizon.isBlank() ? null
                : io.liftandshift.strikebench.model.Horizon.tradingSessions(horizon);
        if (request.intent() == null && request.thesis() == null && horizonSessions == null) {
            return null;
        }
        return new io.liftandshift.strikebench.eval.DeclaredObjective(request.intent(),
                request.thesis(), horizonSessions, null, "this order's declared view");
    }

    private io.liftandshift.strikebench.eval.PortfolioExposureContext practiceExposure(
            Account account, String symbol, String excludedTradeId) {
        return trades.portfolioDollarDelta(account.id(), symbol, excludedTradeId).toContext(
                io.liftandshift.strikebench.position.PositionDomain.ExecutionLane.PRACTICE);
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
        io.liftandshift.strikebench.paper.TradePreview preview = trades.preview(request, cash, reserved, releasedShares);
        Verdict verdict = placementVerdict(request, account, preview, riskCapCents(ctx), buyingPower);
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
        String currentUnavailableReason = null;
        if (TradeRecord.ACTIVE.equals(trade.status())) {
            try {
                current = trades.currentMark(id);
                if (current == null) {
                    currentUnavailableReason =
                            "The current market service returned no receipt for this position.";
                }
            } catch (Exception e) {
                currentUnavailableReason = e.getMessage() == null || e.getMessage().isBlank()
                        ? "The current market receipt for this position could not be produced."
                        : e.getMessage();
                log.warn("Current paper-trade mark is unavailable for {}", id);
                log.debug("Paper-trade mark detail for " + id, e);
            }
        }
        Quote positionQuote = null;
        String quoteEnrichmentFailure = null;
        try {
            positionQuote = current == null
                    ? trades.currentUnderlyingQuote(id).orElse(null)
                    : current.underlyingQuote();
        } catch (RuntimeException failure) {
            quoteEnrichmentFailure = failure.getMessage() == null || failure.getMessage().isBlank()
                    ? "The current underlying quote could not be produced for this position."
                    : failure.getMessage();
            log.warn("Current underlying quote is unavailable for {}", id);
            log.debug("Current underlying quote detail for " + id, failure);
        }
        ApiResponses.QuoteView quote = positionQuote != null
                ? ApiResponses.QuoteView.of(positionQuote, false)
                : ApiResponses.QuoteView.unavailable(trade.symbol(),
                    quoteEnrichmentFailure != null
                            ? quoteEnrichmentFailure
                            : current != null && current.availability() != null
                            && current.availability().quoteUnavailableReason() != null
                            ? current.availability().quoteUnavailableReason()
                            : "No current quote is available for " + trade.symbol()
                                + " in this position's market lane.");
        ApiResponses.PracticePositionAnalysis analysis = null;
        if (TradeRecord.ACTIVE.equals(trade.status()) && lifecycleAnalyses != null) {
            try {
                analysis = lifecycleAnalyses.analyzePractice(id, current);
            } catch (RuntimeException e) {
                log.warn("Practice lifecycle analysis is unavailable for {}", id);
                log.debug("Practice lifecycle analysis detail for " + id, e);
            }
        }
        // §5.4: ONE held payoff on this envelope. The terminal-payoff receipt is attached for every
        // status — a closed package still has an exact recorded curve — because the second
        // `payoff` list that used to carry it for non-active trades is deleted.
        boolean active = TradeRecord.ACTIVE.equals(trade.status());
        TradeView view = TradeView.of(trade);
        HeldReceipts held = heldReceipts(trade, positionQuote, active);
        view = view.withHeldReceipts(held.terminalPayoff(), held.scenarios(), held.spotPnl());
        return new ApiResponses.TradeDetail<>(view, current, quote, currentUnavailableReason,
                trades.marksHistory(id, 50), audit.forTrade(id, 50), analysis);
    }

    /**
     * Compose held-position display receipts without coupling their availability. Terminal payoff
     * and named scenarios are recorded-entry facts; spot P/L is a current-quote fact. Current
     * package marks, POP, Greeks, and close-price evidence belong only to TradeDetail.current.
     * A failure in any one producer is contained to that receipt and must not erase its siblings.
     */
    static HeldReceipts heldReceipts(
            TradeRecord trade, Quote positionQuote, boolean active) {
        return composeHeldReceipts(trade.id(),
                () -> heldTerminalPayoff(trade),
                active ? () -> heldScenarios(trade, positionQuote) : null,
                active ? () -> heldSpotPnl(trade, positionQuote) : () -> null);
    }

    /**
     * Supplier boundary kept package-private so the failure-isolation contract can be pinned
     * without corrupting a persisted trade fixture merely to force one producer to throw.
     */
    static HeldReceipts composeHeldReceipts(
            String tradeId,
            Supplier<io.liftandshift.strikebench.eval.RiskProfile.TerminalPayoff> payoffSupplier,
            Supplier<ApiResponses.HeldScenarios> scenariosSupplier,
            Supplier<ApiResponses.HeldSpotPnl> spotPnlSupplier) {
        io.liftandshift.strikebench.eval.RiskProfile.TerminalPayoff payoff;
        try {
            payoff = Objects.requireNonNull(payoffSupplier.get(),
                    "held terminal-payoff producer returned no receipt");
        } catch (RuntimeException failure) {
            String reason = heldReceiptFailure("terminal payoff", failure);
            payoff = unavailableHeldTerminalPayoff(reason);
            logHeldReceiptFailure(tradeId, "terminal payoff", failure);
        }

        ApiResponses.HeldScenarios scenarios;
        if (scenariosSupplier == null) {
            scenarios = ApiResponses.HeldScenarios.unavailable(
                    "Named future scenarios are available only while this position is open.");
        } else {
            try {
                scenarios = Objects.requireNonNull(scenariosSupplier.get(),
                        "held scenario producer returned no receipt");
            } catch (RuntimeException failure) {
                String reason = heldReceiptFailure("named scenarios", failure);
                scenarios = ApiResponses.HeldScenarios.unavailable(reason);
                logHeldReceiptFailure(tradeId, "named scenarios", failure);
            }
        }

        ApiResponses.HeldSpotPnl spotPnl;
        try {
            spotPnl = spotPnlSupplier.get();
        } catch (RuntimeException failure) {
            String reason = heldReceiptFailure("spot P/L", failure);
            spotPnl = ApiResponses.HeldSpotPnl.unavailable(reason);
            logHeldReceiptFailure(tradeId, "spot P/L", failure);
        }
        return new HeldReceipts(payoff, scenarios, spotPnl);
    }

    private static String heldReceiptFailure(String receipt, RuntimeException failure) {
        String detail = failure.getMessage();
        return "The held-position " + receipt + " receipt could not be produced"
                + (detail == null || detail.isBlank() ? "." : ": " + detail);
    }

    private static void logHeldReceiptFailure(
            String tradeId, String receipt, RuntimeException failure) {
        log.warn("Held paper-trade {} receipt is unavailable for {}", receipt, tradeId);
        log.debug("Held paper-trade " + receipt + " receipt detail for " + tradeId, failure);
    }

    private static io.liftandshift.strikebench.eval.RiskProfile.TerminalPayoff
    unavailableHeldTerminalPayoff(String reason) {
        return new io.liftandshift.strikebench.eval.RiskProfile.TerminalPayoff(
                io.liftandshift.strikebench.eval.RiskProfile.TerminalPayoff.SCHEMA,
                io.liftandshift.strikebench.eval.RiskProfile.TerminalPayoff.MODEL,
                false, null, null, null, null, null, false, List.of(), reason);
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
        String symbol = Symbol.normalize(body.symbol());
        var identified = io.liftandshift.strikebench.strategy.StrategyCatalog.identify(
                symbol, body.qty(), legs);
        String strategy = "CUSTOM".equals(suppliedStrategy) && identified.family() != null
                ? identified.family() : suppliedStrategy;
        return new TradeService.OpenRequest(accountId, symbol, strategy,
                body.qty(), legs, body.thesis(), body.horizon(),
                body.riskMode(), body.intent(), body.useHeldShares(), body.feesOverrideCents(),
                body.source(),
                body.fillNature(), body.orderInstruction());
    }

    Long riskCapCents(Context ctx) {
        AccountRiskContext context = AccountRiskContext.load(db, ownerId.apply(ctx));
        return context.riskCapitalCents() != null && context.riskCapitalCents() > 0
                ? context.riskCapitalCents() : null;
    }

    /**
     * Adds account policy and event context to the one canonical priced-risk preview.
     *
     * <p>This method deliberately does not fetch or reprice a quote, chain, leg, payoff, reserve,
     * or maximum loss. {@link TradeService#preview} owns those facts and already applies the exact
     * expiry, evidence, executability, coverage, and buying-power gates used by create. The former
     * controller-side guardrail pass repeated all of that against a second market snapshot, which
     * allowed preview and placement to disagree.</p>
     */
    private Verdict placementVerdict(TradeService.OpenRequest request, Account account,
                                     io.liftandshift.strikebench.paper.TradePreview preview,
                                     Long riskCapCents, long buyingPowerCents) {
        List<String> blocks = new ArrayList<>(preview.blockReasons());
        List<String> warnings = new ArrayList<>(preview.warnings());
        String accountWorld = "DEMO".equals(account.type()) ? "demo" : account.worldId();
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

        // Risk posture is an advisory account policy layered over the exact preview, not another
        // payoff calculation. Missing risk stays missing and therefore cannot create a warning
        // against an invented zero.
        if (preview.maxLossCents() != null) {
            RecommendationEngine.RiskMode mode =
                    RecommendationEngine.RiskMode.parse(request.riskMode());
            long budget = RiskBudgetPolicy.compute(mode, buyingPowerCents, riskCapCents)
                    .effectiveBudgetCents();
            if (preview.maxLossCents() > budget) {
                warnings.add("Max loss " + io.liftandshift.strikebench.util.Money.fmt(
                                preview.maxLossCents())
                        + " exceeds your " + mode.name().toLowerCase(Locale.ROOT)
                        + " risk budget of "
                        + io.liftandshift.strikebench.util.Money.fmt(budget)
                        + " per trade — allowed, but oversized for your chosen risk mode");
            }
        }

        if (earningsSoon) {
            EventService.EventEvidence event = eventCalendar.earnings(request.symbol());
            if (event.available()) {
                String timing = event.session() == EventService.EventSession.BEFORE_OPEN ? " before open"
                        : event.session() == EventService.EventSession.AFTER_CLOSE ? " after close" : "";
                warnings.add(event.status() == EventService.EvidenceStatus.CONFIRMED
                        ? "Earnings CONFIRMED " + event.date() + timing + " by " + event.source()
                            + " — it lands inside this trade"
                        : "Earnings ESTIMATED around " + event.date() + " ±" + event.windowDays()
                            + "d (" + event.basis() + ") — not a confirmed date, but it lands inside this trade");
            }
        } else if (eventLikeNews) {
            warnings.add("Event-like news in recent headlines (earnings/guidance keywords) — "
                    + "a news signal only; no earnings event is ESTIMATED before this trade's expiration");
        }
        return Verdict.of(List.copyOf(blocks), List.copyOf(warnings));
    }

    static Candidate exactPreviewCandidate(TradeService.OpenRequest request,
            io.liftandshift.strikebench.paper.TradePreview preview) {
        return exactPreviewFacts(request, preview).toCandidate();
    }

    /** One serialization shape for an exact package, whether risk is known or unavailable. */
    static com.fasterxml.jackson.databind.node.ObjectNode exactPreviewNode(
            TradeService.OpenRequest request,
            io.liftandshift.strikebench.paper.TradePreview preview) {
        return Json.MAPPER.valueToTree(exactPreviewFacts(request, preview));
    }

    private static ExactPreviewFacts exactPreviewFacts(
            TradeService.OpenRequest request,
            io.liftandshift.strikebench.paper.TradePreview preview) {
        if (request == null || request.qty() < 1) {
            throw new IllegalArgumentException("exact package preview requires quantity >= 1");
        }
        ExactPreviewDescription description = exactPreviewDescription(request);
        List<LegView> legs = exactPreviewLegs(request, preview);
        List<Map<String, Object>> markedLegs = preview.legs() == null ? List.of() : preview.legs();
        Long combinedMaxLoss = combinedMaximumLossCents(request, preview);
        Integer sharesNeeded = exactPreviewSharesNeeded(request);
        PackagePriceReceipt price = preview.price() == null
                ? PackagePriceReceipt.unavailable(request.qty(),
                        PackagePriceReceipt.FeeSide.OPENING,
                        "No package-price receipt was produced for this exact package.")
                : preview.price();
        if (price.quantity() != request.qty()) {
            throw new IllegalArgumentException(
                    "exact package quantity must match its package-price receipt quantity");
        }
        long optionLegCount = request.legs().stream().filter(leg -> !leg.isStock()).count();
        List<Map<String, Object>> optionMarks = markedLegs.stream()
                .filter(mark -> !"STOCK".equals(mark.get("type"))).toList();
        boolean completeBook = optionMarks.size() == optionLegCount
                && optionMarks.stream().allMatch(mark ->
                        mark.get("bid") != null && mark.get("ask") != null);
        Double liquidity = price.priced() && completeBook ? 1.0 : null;
        Double confidence = preview.hasRiskFacts() && price.priced() ? 1.0 : null;
        return new ExactPreviewFacts(request.strategy(), description.display(), description.group(),
                description.display(), legs, request.qty(),
                price, preview.maxProfitCents(), preview.maxLossCents(),
                preview.breakevens() == null ? List.of() : preview.breakevens(),
                preview.marketImpliedRisk(),
                liquidity, preview.freshness(),
                preview.warnings() == null ? List.of() : preview.warnings(), confidence,
                "Exact ticket", "", "", "", "", description.intent(), description.intents(),
                preview.shortSideExpirationItmProb(), null, null, null,
                request.heldShares() ? Boolean.TRUE : null, sharesNeeded, combinedMaxLoss);
    }

    /** One extraction point until the historical preview analytics map becomes a typed field. */
    private static Long combinedMaximumLossCents(
            TradeService.OpenRequest request,
            io.liftandshift.strikebench.paper.TradePreview preview) {
        Map<String, Object> analytics = preview == null || preview.analytics() == null
                ? Map.of() : preview.analytics();
        if (analytics.get("combinedMaxLossCents") instanceof Number number) {
            return number.longValue();
        }
        // When stock is part of the exact package (not supplied as pre-existing held context),
        // TradeService's maximum-loss curve already includes that stock leg. Name it explicitly
        // as the combined receipt; an option-only package is never allowed this projection.
        boolean stockIncluded = request != null && !Boolean.TRUE.equals(request.useHeldShares())
                && request.legs().stream().anyMatch(io.liftandshift.strikebench.model.Leg::isStock);
        return stockIncluded && preview != null ? preview.maxLossCents() : null;
    }

    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private record ExactPreviewFacts(
            String strategy, String displayName, String structureGroup, String label,
            List<LegView> legs, int qty, PackagePriceReceipt price, Long maxProfitCents,
            @com.fasterxml.jackson.annotation.JsonInclude(
                    com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
            Long maxLossCents,
            List<String> breakevens,
            io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.Receipt marketImpliedRisk,
            @com.fasterxml.jackson.annotation.JsonInclude(
                    com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
            Double liquidityScore,
            String freshness, List<String> warnings,
            @com.fasterxml.jackson.annotation.JsonInclude(
                    com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
            Double confidence,
            String whyConsidered, String bestUpside, String biggestRisk, String wouldInvalidate,
            String beginnerExplanation, String intent, List<String> intents,
            Double shortSideExpirationItmProb, Double annualizedOpeningPremiumRatePct, String effectivePrice,
            String intentNote, Boolean usesHeldShares, Integer sharesNeeded,
            Long combinedMaxLossCents
    ) {
        Candidate toCandidate() {
            if (maxLossCents == null) {
                throw new IllegalStateException(
                        "This exact package has no maximum-loss receipt and cannot become a risk-screened candidate.");
            }
            if (liquidityScore == null || confidence == null) {
                throw new IllegalStateException(
                        "This exact package has no complete executable-price receipt and cannot become an assessed candidate.");
            }
            return new Candidate(strategy, displayName, structureGroup, label, legs, qty, price,
                    maxProfitCents, maxLossCents, breakevens,
                    liquidityScore, freshness, warnings, confidence, whyConsidered, bestUpside,
                    biggestRisk, wouldInvalidate, beginnerExplanation, intent, intents,
                    shortSideExpirationItmProb, annualizedOpeningPremiumRatePct, effectivePrice, intentNote,
                    usesHeldShares, sharesNeeded, combinedMaxLossCents, marketImpliedRisk);
        }

    }

    static Long exactRoundTripFees(
            io.liftandshift.strikebench.paper.TradePreview preview) {
        return preview == null || preview.price() == null ? null
                : preview.price().estimatedRoundTripFeesCents();
    }

    static ApiResponses.EvaluationReceipt unavailableRiskEvaluation(
            io.liftandshift.strikebench.paper.TradePreview preview, String subject) {
        List<String> reasons = preview.blockReasons() == null ? List.of() : preview.blockReasons();
        String cause = reasons.isEmpty()
                ? "no complete finite-risk receipt is available"
                : reasons.getFirst();
        String label = subject == null || subject.isBlank() ? "The exact package" : subject.trim();
        return ApiResponses.EvaluationReceipt.unavailable(
                label + " cannot be financially evaluated because " + cause
                        + ". No maximum loss, reserve, score, stance, or forward economics was substituted.",
                preview.ok(), reasons, exactRoundTripFees(preview));
    }

    static ApiResponses.EvaluationReceipt unavailableAssessmentEvaluation(
            io.liftandshift.strikebench.paper.TradePreview preview) {
        return ApiResponses.EvaluationReceipt.unavailable(
                "The package mechanics were checked, but the broader decision assessment is unavailable "
                        + "because one or more market inputs could not be observed. No score, stance, "
                        + "or economic claim was substituted.",
                preview.ok(), preview.blockReasons(), exactRoundTripFees(preview));
    }

    private static ExactPreviewDescription exactPreviewDescription(
            TradeService.OpenRequest request) {
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
        return new ExactPreviewDescription(display, group, intent, intents);
    }

    private static List<LegView> exactPreviewLegs(TradeService.OpenRequest request,
            io.liftandshift.strikebench.paper.TradePreview preview) {
        // A mechanically refused package can have no executable leg-detail rows (for example an
        // impossible user price). Its entered geometry still needs a complete assessment: use the
        // request facts and let the authoritative package net carry the price constraint.
        List<Map<String, Object>> markedLegs = preview.legs() == null ? List.of() : preview.legs();
        return markedLegs.isEmpty()
                ? request.legs().stream().map(LegView::of).toList()
                : markedLegs.stream().map(mark -> new LegView(
                    Objects.toString(mark.get("action"), null),
                    Objects.toString(mark.get("type"), null),
                    Objects.toString(mark.get("strike"), null),
                    Objects.toString(mark.get("expiration"), null),
                    requiredPositiveInteger(mark, "ratio"),
                    Objects.toString(mark.get("fill"), null),
                    requiredPositiveInteger(mark, "multiplier"),
                    "OPEN",
                    optionalDecimalString(mark.get("bid")),
                    optionalDecimalString(mark.get("ask")),
                    mark.get("asOfEpochMs") instanceof Number timestamp ? timestamp.longValue() : null,
                    Objects.toString(mark.get("source"), null),
                    Objects.toString(mark.get("freshness"), null),
                    optionalDouble(mark.get("iv")),
                    optionalDouble(mark.get("delta")))).toList();
    }

    private static Integer exactPreviewSharesNeeded(TradeService.OpenRequest request) {
        if (request.heldShares()) {
            long units = io.liftandshift.strikebench.strategy.CoverageCheck
                    .shareContextUnitsNeeded(request.legs());
            return Math.toIntExact(Math.multiplyExact(units, request.qty()));
        }
        return null;
    }

    private record ExactPreviewDescription(String display, String group, String intent,
                                           List<String> intents) {}

    private List<ApiResponses.RiskAcknowledgment> requiredAcksFor(
            io.liftandshift.strikebench.paper.TradePreview preview,
            long effectiveRiskBudgetCents) {
        List<ApiResponses.RiskAcknowledgment> out = new ArrayList<>();
        if (preview == null || preview.analytics() == null) return out;
        // The EV-is-negative acknowledgment is only offered when BOTH the expectation and the
        // round-trip commission are known; a $0 substituted commission made this ack claim an
        // after-cost loss it had not costed (§3.2).
        Long ackRoundTrip = preview.price() == null ? null
                : preview.price().estimatedRoundTripFeesCents();
        Long marketEv = preview.marketImpliedRisk() == null
                ? null : preview.marketImpliedRisk().expectedValueCents();
        Long afterCosts = marketEv == null || ackRoundTrip == null ? null
                : marketEv - ackRoundTrip;
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
        // Typed regime, not prose: renaming a rule's wording must never silently drop a required
        // risk acknowledgment (§7.5).
        Object management = preview.analytics().get("managementPlan");
        if (management instanceof io.liftandshift.strikebench.paper.ProtocolEvaluator.Plan plan
                && plan.regime() == io.liftandshift.strikebench.paper.ProtocolEvaluator.Regime.NEAR_EXPIRY) {
            out.add(new ApiResponses.RiskAcknowledgment("ack-dte", "Only " + plan.sessionsToExpiry()
                    + " trading session(s) remain — gamma, weekend gaps and pin risk dominate."));
        }
        if (effectiveRiskBudgetCents > 0 && preview.maxLossCents() != null
                && preview.maxLossCents() > effectiveRiskBudgetCents) {
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
                    + "|" + request.feesOverrideCents()
                    + "|" + io.liftandshift.strikebench.util.Json.canonical(request.orderInstruction())
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

    /**
     * §5.4: the engine's own answer to "if price holds" — the SAME held curve that produced
     * {@link #heldTerminalPayoff}, evaluated at one declared spot. The desk used to interpolate the
     * served polyline in JavaScript and print the result as a financial fact; worse, a position that
     * had moved more than 30% from its entry anchor fell off the served domain and printed
     * "unavailable" for a number the engine could state exactly.
     *
     * <p>The spot must be a current market fact. The recorded entry remains the payoff's basis,
     * but it is never substituted as today's quote.
     */
    static ApiResponses.HeldSpotPnl heldSpotPnl(TradeRecord trade, Quote quote) {
        Long spotCents = quote == null || quote.mark() == null
                ? null : Money.toCents(quote.mark());
        return heldSpotPnlAtCurrentQuote(trade, spotCents,
                quote == null ? null : quote.markBasis().name(),
                quote == null ? null : quote.markFreshness().name());
    }

    private static ApiResponses.HeldSpotPnl heldSpotPnlAtCurrentQuote(
            TradeRecord trade, Long spotCents, String spotBasis, String freshness) {
        boolean mixedExpirations = trade.legs().stream().filter(leg -> !leg.isStock())
                .map(Leg::expiration).distinct().count() > 1;
        if (mixedExpirations) {
            return ApiResponses.HeldSpotPnl.unavailable("A mixed-expiration package requires "
                    + "supplied-path valuation; no single-expiration payoff was substituted.");
        }
        if (trade.entryUnderlyingCents() <= 0) {
            return ApiResponses.HeldSpotPnl.unavailable(
                    "No positive underlying anchor is recorded for this trade.");
        }
        if (spotCents == null || spotCents <= 0) {
            return ApiResponses.HeldSpotPnl.unavailable(
                    "No current underlying quote is available for this position; the recorded "
                            + "entry price was not substituted.");
        }
        BigDecimal anchor = BigDecimal.valueOf(trade.entryUnderlyingCents()).movePointLeft(2);
        BigDecimal spot = BigDecimal.valueOf(spotCents).movePointLeft(2);
        PayoffCurve curve = TradeService.heldPayoffCurve(trade);
        List<PayoffCurve.ChartPoint> served = curve.chartPoints(anchor);
        boolean within = !served.isEmpty()
                && spot.compareTo(served.getFirst().price()) >= 0
                && spot.compareTo(served.getLast().price()) <= 0;
        return new ApiResponses.HeldSpotPnl(curve.profitAtCents(spot), spotCents,
                spotBasis, freshness,
                within, null);
    }

    /**
     * The HELD line's scenario grid — one priced checkpoint per NAMED story move, the same set and
     * the same {@link io.liftandshift.strikebench.eval.RiskProfile.Scenario} shape an idea candidate
     * carries. Without it a held position has no per-move receipt at all, which is why the desk used
     * to price the eight stories in the browser from the legs. Valued on the server through the same
     * curve that owns the terminal payoff, so the tiles and the payoff hero cannot disagree.
     *
     * <p>Probability is deliberately null: it needs a live ATM IV and time-to-expiry the roster row
     * does not carry, and an invented probability is worse than an absent one. Mixed-expiration
     * packages return no grid for the same reason the terminal payoff refuses them.
     */
    static ApiResponses.HeldScenarios heldScenarios(TradeRecord trade, Quote quote) {
        boolean mixedExpirations = trade.legs().stream().filter(leg -> !leg.isStock())
                .map(Leg::expiration).distinct().count() > 1;
        if (mixedExpirations) {
            return ApiResponses.HeldScenarios.unavailable(
                    "A mixed-expiration package requires supplied-path valuation; "
                            + "no single-expiration scenario grid was substituted.");
        }
        if (trade.entryUnderlyingCents() <= 0) {
            return ApiResponses.HeldScenarios.unavailable(
                    "No positive underlying anchor is recorded for this trade.");
        }
        if (quote == null || quote.mark() == null) {
            return ApiResponses.HeldScenarios.unavailable(
                    "No current underlying quote is available for this position; "
                            + "the recorded entry price was not substituted.");
        }
        long spotCents = Money.toCents(quote.mark());
        BigDecimal spot = BigDecimal.valueOf(spotCents).movePointLeft(2);
        PayoffCurve curve = TradeService.heldPayoffCurve(trade);
        List<ApiResponses.HeldScenarioValue> out = new ArrayList<>();
        for (io.liftandshift.strikebench.model.ScenarioStory story
                : io.liftandshift.strikebench.model.ScenarioStory.values()) {
            double move = story.underlyingMoveFraction();
            BigDecimal price = spot.multiply(BigDecimal.valueOf(1.0 + move));
            if (price.signum() <= 0) continue;
            out.add(new ApiResponses.HeldScenarioValue(
                    story, move, Money.toCents(price), curve.profitAtCents(price), null));
        }
        return ApiResponses.HeldScenarios.available(List.copyOf(out), spotCents,
                quote.markBasis().name(), quote.markFreshness().name(),
                quote.source(), quote.asOfEpochMs());
    }

    /**
     * B2: the exact terminal-payoff receipt for a HELD line — the SAME schema/shape the idea
     * candidate carries (see {@code RiskProfiler}), so the held bloom/spectrum interpolates a
     * server-owned curve instead of reconstructing it from legs. Mixed-expiry packages are
     * explicitly unavailable (they need supplied-path valuation), never a false single-date curve.
     */
    static io.liftandshift.strikebench.eval.RiskProfile.TerminalPayoff heldTerminalPayoff(TradeRecord trade) {
        boolean mixedExpirations = trade.legs().stream().filter(leg -> !leg.isStock())
                .map(Leg::expiration).distinct().count() > 1;
        if (mixedExpirations) {
            return unavailableHeldTerminalPayoff(
                    "A mixed-expiration package requires supplied-path valuation; no single-expiration payoff was substituted.");
        }
        BigDecimal spot = BigDecimal.valueOf(trade.entryUnderlyingCents()).movePointLeft(2);
        String expiration = trade.legs().stream().filter(leg -> !leg.isStock())
                .map(Leg::expiration).filter(Objects::nonNull)
                .min(LocalDate::compareTo).map(LocalDate::toString).orElse(null);
        PayoffCurve curve = TradeService.heldPayoffCurve(trade);
        List<io.liftandshift.strikebench.eval.RiskProfile.PayoffPoint> points =
                curve.chartPoints(spot).stream()
                        .map(p -> new io.liftandshift.strikebench.eval.RiskProfile.PayoffPoint(
                                p.price(), p.profitCents()))
                        .toList();
        return new io.liftandshift.strikebench.eval.RiskProfile.TerminalPayoff(
                io.liftandshift.strikebench.eval.RiskProfile.TerminalPayoff.SCHEMA,
                io.liftandshift.strikebench.eval.RiskProfile.TerminalPayoff.MODEL,
                !points.isEmpty(), trade.entryUnderlyingCents(), curve.profitAtCents(spot), expiration,
                "EXPIRATION_INTRINSIC", "RECORDED_TRADE_NET", false, points,
                points.isEmpty() ? "No positive underlying anchor is recorded for this trade." : null);
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

    private static String optionalDecimalString(Object raw) {
        if (raw == null) return null;
        return new BigDecimal(raw.toString()).stripTrailingZeros().toPlainString();
    }

    private static Double optionalDouble(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number number) return number.doubleValue();
        return Double.valueOf(raw.toString());
    }

}
