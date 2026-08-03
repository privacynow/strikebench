package io.liftandshift.strikebench.api;

import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.eval.DeclaredObjective;
import io.liftandshift.strikebench.eval.EvaluationService;
import io.liftandshift.strikebench.eval.EvidenceLevel;
import io.liftandshift.strikebench.eval.PortfolioExposureContext;
import io.liftandshift.strikebench.paper.AccountObjectiveService;
import io.liftandshift.strikebench.paper.AccountService;
import io.liftandshift.strikebench.paper.BookActionProjectionService;
import io.liftandshift.strikebench.paper.PortfolioAccountingService;
import io.liftandshift.strikebench.paper.TradeService;
import io.liftandshift.strikebench.position.PositionDomain;
import io.liftandshift.strikebench.position.AuthorityFacts;
import io.liftandshift.strikebench.position.HeldPositionEconomicsService;
import io.liftandshift.strikebench.position.PositionLifecycleDecisionService;
import io.liftandshift.strikebench.strategy.StrategyCatalog;

/**
 * One read-only analysis owner for exact held packages. Tracked and Practice books preserve
 * their own normalized pricing, balance, exposure, and transformation authorities while sharing
 * the evaluator, lifecycle fact composer, and governed decision policy. It never writes lots,
 * accounting basis, campaign membership, Practice cash, or a trade decision.
 */
final class TrackedPackageAnalysisService {
    private final io.liftandshift.strikebench.plan.PlanService plans;
    private final PortfolioAccountingService books;
    private final AccountService practiceAccounts;
    private final TradeService trades;
    private final EvaluationService evaluations;
    private final AccountObjectiveService objectives;
    private final HeldPositionEconomicsService lifecycle;
    private final BookActionProjectionService bookActions;
    private final PositionLifecycleDecisionService decisions;

    TrackedPackageAnalysisService(io.liftandshift.strikebench.plan.PlanService plans,
                                  PortfolioAccountingService books, AccountService practiceAccounts,
                                  TradeService trades,
                                  EvaluationService evaluations, AccountObjectiveService objectives,
                                  HeldPositionEconomicsService lifecycle,
                                  BookActionProjectionService bookActions,
                                  PositionLifecycleDecisionService decisions) {
        this.plans = plans;
        this.books = books;
        this.practiceAccounts = practiceAccounts;
        this.trades = trades;
        this.evaluations = evaluations;
        this.objectives = objectives;
        this.lifecycle = lifecycle;
        this.bookActions = bookActions;
        this.decisions = decisions;
    }

    /**
     * Compose the lifecycle view from the exact current result already acquired by the owning
     * Position request. A null value means that acquisition failed; it is not permission to issue
     * a second market read and silently mix instants inside one response. The Practice mode uses
     * the same lifecycle composer and policy as tracked packages without writing tracked decisions.
     */
    ApiResponses.PracticePositionAnalysis analyzePractice(
            String tradeId, TradeService.MarkView currentMark) {
        var trade = trades.get(tradeId);
        var account = practiceAccounts.get(trade.accountId());
        var request = trades.activePositionRequest(tradeId);
        var assessed = trades.analyzeActivePosition(tradeId);
        var preview = assessed.preview();
        var exposure = trades.portfolioDollarDelta(account.id(), request.symbol(), tradeId)
                .toContext(PositionDomain.BookType.PRACTICE);
        long availableAfterClose = Math.addExact(account.buyingPowerCents(),
                assessed.risk().requiredReserveCents());
        String world = account.marketWorld();
        io.liftandshift.strikebench.eval.StrategyEvaluation evaluation = null;
        ApiResponses.EvaluationResult evaluationResult;
        if (preview.hasRiskFacts()) {
            try {
                var candidate = TradeController.exactPreviewCandidate(request, preview);
                evaluation = evaluations.assessExact(new EvaluationService.ExactAssessmentRequest(
                        request.symbol(), candidate, availableAfterClose, AnalysisContext.OBSERVED,
                        world, preview.ok(), preview.blockReasons(),
                        TradeController.exactRoundTripFees(preview), exposure, null));
                evaluationResult = ApiResponses.EvaluationResult.of(evaluation);
            } catch (RuntimeException unavailable) {
                evaluationResult = TradeController.unavailableAssessmentEvaluation(preview);
            }
        } else {
            evaluationResult = TradeController.unavailableRiskEvaluation(
                    preview, "The held Practice package");
        }
        var lifecycleAnalysis = lifecycle.compose(request, preview, evaluation,
                evaluations.optionTime(request.legs(), world), currentMark);
        var opening = trade.legs().stream().map(leg ->
                new HeldPositionEconomicsService.OpeningLeg(leg.action(),
                        leg.isStock() ? "STOCK" : "OPTION",
                        Math.multiplyExact((long) leg.ratio(), trade.qty()), leg.multiplier(),
                        leg.entryPrice(), "practiceTrade:" + trade.id())).toList();
        lifecycleAnalysis = lifecycle.withHistory(lifecycleAnalysis,
                new HeldPositionEconomicsService.HistoryContext(opening, trade.feesOpenCents(),
                        trade.realizedPnlCents(), currentMark == null ? null : currentMark.unrealizedCents(),
                        AuthorityFacts.MoneyFact.unavailable(
                                "Practice trades do not claim tracked tax-lot basis."),
                        AuthorityFacts.MoneyFact.unavailable(
                                "No explicitly linked campaign-adjusted basis is attached to this Practice trade."),
                        "Persisted Practice fills and opening fees supply history; tracked tax basis and campaign interpretation remain separate.",
                        java.util.List.of("practiceTrade:" + trade.id())));
        var actionProjections = bookActions.projectPractice(tradeId, lifecycleAnalysis);
        var capacity = AccountObjectiveService.capacityContext(null,
                lifecycleAnalysis.positionFingerprint());
        var decision = decisions.analyze(lifecycleAnalysis, actionProjections, capacity,
                declaredExitContext(tradeId, trade, preview.underlyingCents()));
        var identity = StrategyCatalog.identify(StrategyCatalog.ClassificationRequest.draft(
                request.strategy(), request.symbol(), request.qty(), request.legs(),
                Boolean.TRUE.equals(request.useHeldShares())));
        return new ApiResponses.PracticePositionAnalysis(
                evaluationResult, identity,
                account.id(), account.name(), account.buyingPowerCents(),
                evaluation == null
                        ? analysisMode(EvidenceLevel.fromEvidence(preview.evidence()))
                        : analysisMode(evaluation.evidence().perDimension().get("pricing")),
                "Read-only Practice lifecycle analysis reuses the current trade's exact pricing, "
                        + "existing transformation previews, and the shared held-position policy. It places no order and changes no account state.",
                lifecycleAnalysis, actionProjections, capacity,
                new PositionLifecycleDecisionService.LifecycleDecisionView(
                        null, null, decision, null));
    }

    /**
     * The owning plan's declared exit, read through {@link
     * io.liftandshift.strikebench.plan.PlanService#ownerOfTrade the one reverse plan-link read} —
     * no second declaration store and no private link SQL. Entry price comes from the trade
     * record and the current price from the same preview that priced this analysis, so the
     * declared-exit dimension never issues its own market read. A missing link, context, or
     * target flows through as an honest absence rather than failing the analysis.
     */
    private PositionLifecycleDecisionService.DeclaredExitContext declaredExitContext(
            String tradeId, io.liftandshift.strikebench.paper.TradeRecord trade,
            Long currentUnderlyingCents) {
        io.liftandshift.strikebench.plan.Plan.View owner;
        try { owner = plans.ownerOfTrade(tradeId); }
        catch (RuntimeException unavailable) { return null; }
        if (owner == null || owner.context() == null) return null;
        return new PositionLifecycleDecisionService.DeclaredExitContext(
                owner.id(), owner.intent(), owner.context().targetCents(),
                owner.context().horizonDays(),
                trade == null ? null : trade.entryUnderlyingCents(),
                currentUnderlyingCents, openedAt(trade),
                "Declared exit facts come from the owning plan's active context revision; the "
                        + "entry price from the trade record; the current price from the same "
                        + "preview that priced this analysis.");
    }

    private static java.time.OffsetDateTime openedAt(
            io.liftandshift.strikebench.paper.TradeRecord trade) {
        if (trade == null || trade.createdAt() == null) return null;
        try { return java.time.OffsetDateTime.parse(trade.createdAt()); }
        catch (RuntimeException invalid) { return null; }
    }

    ApiResponses.TrackedPackageAnalysis analyze(String ownerId, String accountId,
                                                TradeService.OpenRequest request) {
        var account = books.account(ownerId, accountId);
        var summary = books.summary(ownerId, accountId);
        var preview = trades.previewTracked(request, summary.bookCashCents());
        AccountObjectiveService.Revision objectiveRevision = objectives.latest(ownerId, accountId);
        var exposure = books.portfolioDollarDelta(ownerId, accountId, request.symbol())
                .toContext(PositionDomain.BookType.TRACKED);
        io.liftandshift.strikebench.eval.StrategyEvaluation evaluation = null;
        ApiResponses.EvaluationResult evaluationResult;
        if (preview.hasRiskFacts()) {
            try {
                var candidate = TradeController.exactPreviewCandidate(request, preview);
                evaluation = evaluations.assessExact(new EvaluationService.ExactAssessmentRequest(
                        request.symbol(), candidate, summary.bookCashCents(),
                        AnalysisContext.OBSERVED, "observed", preview.ok(), preview.blockReasons(),
                        TradeController.exactRoundTripFees(preview), exposure,
                        declaredAccountObjective(objectiveRevision)));
                evaluationResult = ApiResponses.EvaluationResult.of(evaluation);
            } catch (RuntimeException unavailable) {
                evaluationResult = TradeController.unavailableAssessmentEvaluation(preview);
            }
        } else {
            evaluationResult = TradeController.unavailableRiskEvaluation(
                    preview, "The tracked package");
        }
        String mode = evaluation == null
                ? analysisMode(EvidenceLevel.fromEvidence(preview.evidence()))
                : analysisMode(evaluation.evidence().perDimension().get("pricing"));
        var identity = StrategyCatalog.identify(StrategyCatalog.ClassificationRequest.draft(
                request.strategy(), request.symbol(), request.qty(), request.legs(),
                Boolean.TRUE.equals(request.useHeldShares())));
        var lifecycleAnalysis = lifecycle.compose(request, preview, evaluation,
                evaluations.optionTime(request.legs(), "observed"), null);
        var actionProjections = bookActions.project(ownerId, accountId, request, lifecycleAnalysis, summary);
        var capacity = AccountObjectiveService.capacityContext(objectiveRevision,
                lifecycleAnalysis.positionFingerprint());
        return new ApiResponses.TrackedPackageAnalysis(preview,
                evaluationResult,
                identity,
                accountId, account.name(), summary.bookCashCents(), mode,
                "Read-only analysis uses " + mode.toLowerCase(java.util.Locale.ROOT)
                        + " evidence and this tracked account's cash. It never changes tracked lots,"
                        + " tracked tax basis, campaign accounting, or the Practice account.",
                lifecycleAnalysis,
                actionProjections, capacity, null);
    }

    /** Freeze only the analysis that will actually cross the wire; callers may enrich history first. */
    ApiResponses.TrackedPackageAnalysis surface(String ownerId,
                                                ApiResponses.TrackedPackageAnalysis analysis) {
        if (analysis == null || analysis.lifecycle() == null || analysis.bookActions() == null
                || analysis.capacity() == null) return analysis;
        return new ApiResponses.TrackedPackageAnalysis(analysis.preview(), analysis.evaluation(),
                analysis.identity(), analysis.accountId(), analysis.accountName(),
                analysis.availableCashCents(), analysis.marketMode(), analysis.note(),
                analysis.lifecycle(), analysis.bookActions(), analysis.capacity(),
                decisions.surface(ownerId, analysis.accountId(), analysis.lifecycle(),
                        analysis.bookActions(), analysis.capacity()));
    }

    private DeclaredObjective declaredAccountObjective(AccountObjectiveService.Revision revision) {
        if (revision == null) return null;
        String thesis = revision.direction() == null || "NON_DIRECTIONAL".equals(revision.direction())
                ? null : revision.direction();
        return new DeclaredObjective(revision.objective(), thesis, null,
                revision.assignmentPreference(),
                "this account's declared objective (revision " + revision.revisionNo() + ")");
    }

    private static String analysisMode(EvidenceLevel pricing) {
        if (pricing == null) return "UNKNOWN";
        return switch (pricing) {
            case OBSERVED_LIVE, OBSERVED_DELAYED, OBSERVED_EOD, OBSERVED_STALE -> "OBSERVED";
            case DEMO_FIXTURE -> "DEMO";
            case SIMULATED -> "SIMULATED";
            case MODELED -> "MODELED";
            case UNKNOWN -> "UNKNOWN";
        };
    }
}
