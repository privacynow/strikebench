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
 * One read-only analysis owner for exact held packages. Tracked and Practice adapters preserve
 * their own canonical pricing, balance, exposure, and transformation authorities while sharing
 * the evaluator, lifecycle fact composer, and governed decision policy. It never writes lots,
 * accounting basis, campaign membership, Practice cash, or a trade decision.
 */
final class TrackedPackageAnalysisService {
    private final PortfolioAccountingService books;
    private final AccountService practiceAccounts;
    private final TradeService trades;
    private final EvaluationService evaluations;
    private final AccountObjectiveService objectives;
    private final HeldPositionEconomicsService lifecycle;
    private final BookActionProjectionService bookActions;
    private final PositionLifecycleDecisionService decisions;

    TrackedPackageAnalysisService(PortfolioAccountingService books, AccountService practiceAccounts,
                                  TradeService trades,
                                  EvaluationService evaluations, AccountObjectiveService objectives,
                                  HeldPositionEconomicsService lifecycle,
                                  BookActionProjectionService bookActions,
                                  PositionLifecycleDecisionService decisions) {
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
     * The Practice lane enters the same lifecycle fact composer and policy layer as tracked
     * packages, while retaining Practice's own canonical pricing, balances, and transformations.
     * Unlike a tracked analysis it is not persisted into tracked-account decision tables.
     */
    ApiResponses.PracticePositionAnalysis analyzePractice(String tradeId) {
        var trade = trades.get(tradeId);
        var account = practiceAccounts.get(trade.accountId());
        var request = trades.activePositionRequest(tradeId);
        var assessed = trades.analyzeActivePosition(tradeId);
        var preview = assessed.preview();
        var candidate = TradeController.exactPreviewCandidate(request, preview);
        var exposure = trades.portfolioDollarDelta(account.id(), request.symbol(), tradeId)
                .toContext(PositionDomain.ExecutionLane.PRACTICE);
        long availableAfterClose = Math.addExact(account.buyingPowerCents(), assessed.risk().reserveCents());
        String world = "DEMO".equals(account.type()) ? "demo" : account.worldId();
        var evaluation = evaluations.assessExact(request.symbol(), candidate, availableAfterClose,
                AnalysisContext.OBSERVED, world, preview.ok(), preview.blockReasons(),
                Math.multiplyExact(preview.feesOpenCents(), 2L), exposure);
        var lifecycleReceipt = lifecycle.compose(request, preview, evaluation);
        var currentMark = safeCurrentMark(tradeId);
        var opening = trade.legs().stream().map(leg ->
                new HeldPositionEconomicsService.OpeningLeg(leg.action(),
                        leg.isStock() ? "STOCK" : "OPTION",
                        Math.multiplyExact((long) leg.ratio(), trade.qty()), leg.multiplier(),
                        leg.entryPrice(), "practiceTrade:" + trade.id())).toList();
        lifecycleReceipt = lifecycle.withHistory(lifecycleReceipt,
                new HeldPositionEconomicsService.HistoryContext(opening, trade.feesOpenCents(),
                        trade.realizedPnlCents(), currentMark == null ? null : currentMark.unrealizedCents(),
                        AuthorityFacts.MoneyFact.unavailable(
                                "Practice trades do not claim tracked tax-lot basis."),
                        AuthorityFacts.MoneyFact.unavailable(
                                "No explicitly linked campaign-adjusted basis is attached to this Practice trade."),
                        "Persisted Practice fills and opening fees supply history; tracked tax basis and campaign interpretation remain separate.",
                        java.util.List.of("practiceTrade:" + trade.id())));
        var actionProjections = bookActions.projectPractice(tradeId, lifecycleReceipt);
        var capacity = AccountObjectiveService.capacityContext(null,
                lifecycleReceipt.positionFingerprint());
        var decision = decisions.analyze(lifecycleReceipt, actionProjections, capacity);
        var identity = StrategyCatalog.identify(request.symbol(), request.qty(), request.legs());
        return new ApiResponses.PracticePositionAnalysis(
                ApiResponses.EvaluationReceipt.of(evaluation), identity,
                account.id(), account.name(), account.buyingPowerCents(), analysisLane(
                        evaluation.evidence().perDimension().get("pricing")),
                "Read-only Practice lifecycle analysis reuses the current trade's exact pricing, "
                        + "existing transformation previews, and the shared held-position policy. It places no order and changes no account state.",
                lifecycleReceipt, actionProjections, capacity, decision);
    }

    private TradeService.MarkView safeCurrentMark(String tradeId) {
        try { return trades.currentMark(tradeId); }
        catch (RuntimeException unavailable) { return null; }
    }

    ApiResponses.TrackedPackageAnalysis analyze(String ownerId, String accountId,
                                                TradeService.OpenRequest request) {
        var account = books.account(ownerId, accountId);
        var summary = books.summary(ownerId, accountId);
        var preview = trades.previewTracked(request, summary.bookCashCents());
        var candidate = TradeController.exactPreviewCandidate(request, preview);
        AccountObjectiveService.Revision objectiveRevision = objectives.latest(ownerId, accountId);
        var exposure = books.portfolioDollarDelta(ownerId, accountId, request.symbol())
                .toContext(PositionDomain.ExecutionLane.REAL);
        var evaluation = evaluations.assessExact(request.symbol(), candidate, summary.bookCashCents(),
                AnalysisContext.OBSERVED, null, preview.ok(), preview.blockReasons(),
                Math.multiplyExact(preview.feesOpenCents(), 2L), exposure,
                declaredAccountObjective(objectiveRevision));
        String lane = analysisLane(evaluation.evidence().perDimension().get("pricing"));
        var identity = StrategyCatalog.identify(request.symbol(), request.qty(), request.legs());
        var lifecycleReceipt = lifecycle.compose(request, preview, evaluation);
        var actionProjections = bookActions.project(ownerId, accountId, request, lifecycleReceipt, summary);
        var capacity = AccountObjectiveService.capacityContext(objectiveRevision,
                lifecycleReceipt.positionFingerprint());
        return new ApiResponses.TrackedPackageAnalysis(preview,
                ApiResponses.EvaluationReceipt.of(evaluation),
                identity,
                accountId, account.name(), summary.bookCashCents(), lane,
                "Read-only analysis uses " + lane.toLowerCase(java.util.Locale.ROOT)
                        + " evidence and this tracked account's cash. It never changes tracked lots,"
                        + " tracked tax basis, campaign accounting, or the Practice account.",
                lifecycleReceipt,
                actionProjections, capacity, null);
    }

    /** Freeze only the analysis that will actually cross the wire; callers may enrich history first. */
    ApiResponses.TrackedPackageAnalysis surface(String ownerId,
                                                ApiResponses.TrackedPackageAnalysis analysis) {
        if (analysis == null || analysis.lifecycle() == null || analysis.bookActions() == null
                || analysis.capacity() == null) return analysis;
        return new ApiResponses.TrackedPackageAnalysis(analysis.preview(), analysis.evaluation(),
                analysis.identity(), analysis.accountId(), analysis.accountName(),
                analysis.availableCashCents(), analysis.marketLane(), analysis.note(),
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

    private static String analysisLane(EvidenceLevel pricing) {
        if (pricing == null) return "UNKNOWN";
        return switch (pricing) {
            case OBSERVED_LIVE, OBSERVED_DELAYED, OBSERVED_EOD -> "OBSERVED";
            case DEMO_FIXTURE -> "DEMO";
            case SIMULATED -> "SIMULATED";
            case MODELED -> "MODELED";
            case UNKNOWN -> "UNKNOWN";
        };
    }
}
