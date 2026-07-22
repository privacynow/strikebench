package io.liftandshift.strikebench.api;

import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.eval.DeclaredObjective;
import io.liftandshift.strikebench.eval.EvaluationService;
import io.liftandshift.strikebench.eval.EvidenceLevel;
import io.liftandshift.strikebench.eval.PortfolioExposureContext;
import io.liftandshift.strikebench.paper.AccountObjectiveService;
import io.liftandshift.strikebench.paper.BookActionProjectionService;
import io.liftandshift.strikebench.paper.PortfolioAccountingService;
import io.liftandshift.strikebench.paper.TradeService;
import io.liftandshift.strikebench.position.PositionDomain;
import io.liftandshift.strikebench.position.HeldPositionEconomicsService;
import io.liftandshift.strikebench.position.PositionLifecycleDecisionService;
import io.liftandshift.strikebench.strategy.StrategyCatalog;

/**
 * One read-only analysis owner for exact packages in a tracked account. Both the Book editor and
 * an adopted Plan call this service, so "fresh eyes" cannot drift into a second evaluation path.
 * It prices from the Observed lane, uses the tracked account's own cash/exposure/objective, and
 * never writes lots, accounting basis, campaign membership, or Practice cash.
 */
final class TrackedPackageAnalysisService {
    private final PortfolioAccountingService books;
    private final TradeService trades;
    private final EvaluationService evaluations;
    private final AccountObjectiveService objectives;
    private final HeldPositionEconomicsService lifecycle;
    private final BookActionProjectionService bookActions;
    private final PositionLifecycleDecisionService decisions;

    TrackedPackageAnalysisService(PortfolioAccountingService books, TradeService trades,
                                  EvaluationService evaluations, AccountObjectiveService objectives,
                                  HeldPositionEconomicsService lifecycle,
                                  BookActionProjectionService bookActions,
                                  PositionLifecycleDecisionService decisions) {
        this.books = books;
        this.trades = trades;
        this.evaluations = evaluations;
        this.objectives = objectives;
        this.lifecycle = lifecycle;
        this.bookActions = bookActions;
        this.decisions = decisions;
    }

    ApiResponses.TrackedPackageAnalysis analyze(String ownerId, String accountId,
                                                TradeService.OpenRequest request) {
        var account = books.account(ownerId, accountId);
        var summary = books.summary(ownerId, accountId);
        var preview = trades.previewTracked(request, summary.bookCashCents());
        var candidate = TradeController.exactPreviewCandidate(request, preview);
        var current = books.portfolioDollarDelta(ownerId, accountId, request.symbol());
        AccountObjectiveService.Revision objectiveRevision = objectives.latest(ownerId, accountId);
        var exposure = new PortfolioExposureContext(PositionDomain.ExecutionLane.REAL,
                current.grossCents(), current.netCents(), current.focusSymbolGrossCents(),
                current.complete(), current.basis());
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
