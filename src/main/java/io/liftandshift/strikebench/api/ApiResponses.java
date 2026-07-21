package io.liftandshift.strikebench.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.liftandshift.strikebench.eval.EconomicAssessment;
import io.liftandshift.strikebench.eval.StrategyEvaluation;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.paper.OrderInstruction;
import io.liftandshift.strikebench.paper.TradePreview;
import io.liftandshift.strikebench.recommend.Rejection;

import java.math.BigDecimal;
import java.util.List;

/** Named wire contracts shared by small API envelopes. Domain services own richer response records. */
public final class ApiResponses {
    public record ErrorBody(String error, String detail) {}
    public record ErrorOnly(String error) {}
    public record AuthErrorBody(String error, String detail, String loginUrl) {}
    public record TradeRejectedBody(String error, String detail, List<String> reasons) {}
    public record PlanMarketMismatchBody(String error, String detail, String market, String targetWorld) {}
    public record Ok(boolean ok) {}
    public record Running(boolean ok, boolean running) {}
    public record Speed(boolean ok, double speed) {}
    public record Deleted(String deleted) {}
    public record Questions<T>(T questions) {}
    public record StrategyCatalog<T>(List<String> families, T catalog, T templates) {}
    public record Evaluations<T>(T evaluations) {}
    public record Sessions<T>(T sessions) {}
    public record Accounts<T>(T accounts) {}
    public record AccountObjective(Object latest, Object history) {}
    public record Lots<T>(T lots) {}
    public record Realized<T>(T realized) {}
    public record Transactions<T>(T transactions) {}
    public record TransactionsWritten(int transactionsWritten) {}
    public record Campaigns<T>(T campaigns) {}
    public record Proposals<T>(T proposals) {}
    public record AuthorizeUrl(String authorizeUrl) {}
    public record Positions<T>(T positions) {}
    public record Orders<T>(T orders) {}
    public record Notes<T>(T notes) {}
    public record Entries<T>(T entries) {}
    public record Matches<T>(T matches) {}
    public record Job<T, U>(T job, U items) {}
    public record Snapshot(String asof, int symbols, int underlyingRows, int optionRows,
                           List<String> errors, long elapsedMs) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Status<T>(boolean ok, String asOf, Boolean fixturesOnly, T domains, String error) {}
    public record Brand(String name, String tagline) {}
    public record Config<T>(int port, boolean fixturesOnly, boolean marketOpen, boolean authEnabled,
                            long feePerContractCents, long feePerOrderCents,
                            long defaultStartingCashCents, Brand brand,
                            T broadBasedIndexOptionSymbols, String disclaimer,
                            String activeDataset, String activeDatasetName, boolean scenarioMode,
                            String world, String marketLane) {}
    public record Metrics<T, U>(long requests, T latency, long errors, long throttled,
                                boolean throttleActive, U engine) {}
    public record Health(boolean ok, String startedAt, boolean jarChangedSinceBoot) {}
    public record Quotes<T>(T quotes, int requested, int considered, boolean truncated,
                            int limit, String marketLane) {}
    public record WorldQuotes<T>(T quotes, int requested, int considered, boolean truncated,
                                 int limit, String world, String marketLane) {}
    public record Revision(long rev) {}
    public record Workspace<T>(long rev, String updatedAt, T state) {}
    public record SavedRevision(boolean ok, long rev) {}
    public record Plans<T>(T plans, String market, String world) {}
    public record PlanSymbolError(String error, String detail, String market) {}
    public record PlanStrategy<T, U>(T plan, U strategy) {}
    public record PlanStrategyPreview<T, U, V>(T plan, U strategy, V preview,
                                                io.liftandshift.strikebench.strategy.StrategyCatalog.PositionIdentity identity) {}
    public record StrategyState<T, U>(T strategy, U selected) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlanStrategyFit<T, U, V>(T plan, U result, V candidate) {}
    public record Evidence<T>(T evidence) {}
    public record Scout<T>(T scout) {}
    public record PlanSelection<T, U>(T selection, U plan) {}
    public record PlanScout<T, U>(T plan, U scout) {}
    public record ScoutSpawn<T, U>(T origin, U plan, String role) {}
    /** waypointFill is the scenario canvas's honesty label (NONE / EXACT_CONDITIONAL / GUIDED_INTERPOLATION). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EnsembleRef(String id, String fingerprint, String basis, String waypointFill) {
        public EnsembleRef(String id, String fingerprint, String basis) {
            this(id, fingerprint, basis, null);
        }
    }
    public record PlanEnsemble<T, U>(T plan, EnsembleRef ensemble, U preview) {}
    public record PlanScenario<T, U>(T plan, U scenario) {}
    public record PlanScenarios<T, U>(T plan, U scenarios) {}
    /** A named scenario's immutable receipt alongside a display-only subset of its base fan. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ScenarioPathRef(String id, String fingerprint, String title, boolean currentContext,
                                  String waypointFill, String baseEnsembleId) {}
    /** Auditable identity for the exact Book package repriced by a focused animation. */
    public record FocusedPackageProvenance(
            String contractVersion,
            String key,
            String source,
            String lane,
            String symbol,
            long packageQuantity,
            int legCount,
            Long exactPackageCashCents,
            long entryBasisCents,
            String valuationAsOf,
            String entryCreatedAt,
            String dataProvenance,
            String dataAge,
            String dataSource,
            String entrySnapshotFingerprint,
            List<String> priceAuthorities,
            io.liftandshift.strikebench.position.PositionPackageFingerprint.SourceIdentity
                    sourceIdentity) {}
    /** Complete immutable lineage for a non-mutating scenario-animation projection. */
    public record ScenarioAnimationReceipt(
            String contractVersion,
            String ensembleId,
            String ensembleFingerprint,
            String basis,
            String pathModelVersion,
            String symbol,
            String worldId,
            String datasetId,
            int contextRev,
            String state,
            double anchorSpot,
            String anchorDate,
            String anchorSource,
            String anchorFreshness,
            String asOf,
            double stepSeconds,
            int sourcePathCount,
            int sourceStepCount,
            String waypointFill,
            io.liftandshift.strikebench.sim.ScenarioSpec pathAssumptions,
            io.liftandshift.strikebench.sim.ScenarioSpec conditioningAssumptions,
            List<io.liftandshift.strikebench.sim.PathEnsembleService.DisplayWaypoint>
                    conditioningPathWaypoints,
            io.liftandshift.strikebench.sim.IvSpec ivAssumptions,
            io.liftandshift.strikebench.sim.ScenarioCanvasSpec valuationAssumptions,
            double rateAnnual,
            String selectedCandidateId,
            String focusPositionKey,
            String focusedPackageFingerprint,
            FocusedPackageProvenance focusedPackageProvenance,
            String valuationFingerprint) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlanScenarioPaths<T, U, V, W>(T plan, EnsembleRef ensemble,
                                                ScenarioPathRef scenario, U paths,
                                                V receipt, W checkpoints) {
        public PlanScenarioPaths(T plan, EnsembleRef ensemble, ScenarioPathRef scenario, U paths) {
            this(plan, ensemble, scenario, paths, null, null);
        }
    }
    public record PlanOutcome<T, U>(T plan, U outcome) {}
    public record PlanOutcomeWithEnsemble<T, U>(T plan, U outcome, EnsembleRef ensemble) {}
    public record PlanComparison<T, U>(T plan, U comparison, EnsembleRef ensemble) {}
    public record PlanBacktest<T, U, V>(T plan, U backtest, V report) {}
    public record PlanRehearsal<T, U>(T rehearsal, U plan) {}
    public record PlanDecision<T, U>(T plan, U decision) {}
    public record PlanDecisionState<T, U, V, W>(T plan, U selected, V decision,
                                                String selectionState, W priorSelection) {}
    public record PlanPlacedTrade<T, U, V, W>(T plan, U trade, V decision, W warnings) {}
    public record PlanBrokerPlacement<T, U, V>(T plan, U decision, V transaction,
                                               String structureId, String receiptId) {}
    public record PlanAdopted<T>(T plan, String structureId, String receiptId) {}
    public record PlanManagement<T, U>(T plan, U management) {}
    public record PlanMark<T, U, V>(T plan, U mark, V management) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlanWorkspace<T, U, V, W, X>(T plan, U decision, V management, W trade,
                                               X adoptionReviews) {}
    public record PlanRows<T>(T plans, String market) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlanOutcomesLatest<T, U, V, W, X>(T outcomes, U comparisons, V backtests, W selected,
                                                    String selectionState, X priorSelection) {}
    public record Coverage<T, U>(T symbols, U summary) {}
    public record DataOverview<T, U, V, W>(T engine, U coverage, V jobs, boolean fixturesOnly,
                                           String marketLane, boolean marketOpen, W jobKinds,
                                           boolean admin) {}
    public record DataSource(String name, String covers, boolean enabled, String license, String hint) {}
    public record DataSources<T, U>(T feeds, U connectors, String recommendedCandleSource,
                                    boolean fixturesOnly) {}
    public record DataSync<T, U, V, W>(T connectors, String recommendedSource, U cursors,
                                       V schedule, V systemSchedule, W quarantine, String latestCompletedSession,
                                       String note) {}
    public record SyncSymbolPlan<T>(String symbol, int existingSessions, int missingSessions,
                                    int requests, boolean complete, T ranges) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DataSyncPlan<T, U>(T source, String requestedFrom, String effectiveFrom,
                                     String to, int symbols, int missingSessions,
                                     int estimatedRequests, U plans, String limitation,
                                     String dateNote) {}
    public record Jobs<T>(T jobs) {}
    public record DatasetActivation(boolean ok, String active, boolean scenarioMode) {}
    public record Account<T>(T account) {}
    public record AccountLedger<T, U>(T account, U ledger) {}
    public record Expirations<T>(String symbol, String asOfDate, T expirations) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResearchEvent(Boolean available, String date, Integer windowDays,
                                String basis, Boolean confirmed, String note) {}
    public record EvidenceSummary<T, U>(T summary, U inputs) {}
    public record Benchmark<T, U>(String symbol, T last, String freshness, U evidence) {}
    public record ResearchDetail<T, U, V, W>(String symbol, T quote, BigDecimal displayPrice,
                                              boolean priceIsPreviousClose, String marketLane,
                                              boolean optionable, Double ivAtm,
                                              boolean ivRankAvailable, Double ivRankPct,
                                              Double ivPercentilePct, int ivHistoryDays,
                                              int ivRankRequiredDays, String ivRankNote,
                                              ResearchEvent earningsEstimate,
                                              ResearchEvent exDividend, Double hv30,
                                              int hvHistoryDays, int hvRequiredDays,
                                              boolean historyDemo, String historyBarBasis,
                                              String historyPriceBasis, U evidence,
                                              V expirations, boolean planEligible,
                                              String planEligibility, W benchmarks,
                                              String freshness, String asOfDate,
                                              Regime regime) {}
    /** The lane's trailing regime as one wire object; headline pre-composed server-side. */
    public record Regime(String trend, Double trendReturnPct, Integer trendSessions,
                         Double drawdownPct, Double varianceRiskPremium, Double ivRankPct,
                         Boolean eventSoon, String eventBasis, String headline, String basis) {
        public static Regime of(io.liftandshift.strikebench.eval.RegimeSnapshot snapshot) {
            if (snapshot == null) return null;
            return new Regime(snapshot.trend() == null ? null : snapshot.trend().name(),
                    snapshot.trendReturnPct(), snapshot.trendSessions(), snapshot.drawdownPct(),
                    snapshot.varianceRiskPremium(), snapshot.ivRankPct(), snapshot.eventSoon(),
                    snapshot.eventBasis(),
                    snapshot.headline(), snapshot.basis());
        }
    }
    public record History<T, U>(String symbol, String range, T candles, String source,
                                String freshness, String barBasis, String priceBasis,
                                U evidence, Object coverage) {}
    public record Sparklines<T>(String range, T sparklines, int totalRequested, String world) {}
    /** Existing Research news route, enriched by the one versioned deterministic scorer. */
    public record ResearchNews<T, U>(String symbol, String scorerVersion, T items, U aggregate,
                                     T eventRisk, String evidence, String note) {}
    public record Optimization<T, U>(T optimization, int scanned, U scanNotes) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DecisionBaseline(String key, Long evCents, Long maxLossCents,
                                   Long cvar95Cents, Long stressLossCents, Long capitalCents,
                                   Double pAnyProfit, boolean viable, String marketLane,
                                   String asOfDate, Integer horizonDays, Double volatility,
                                   String volatilityBasis, DataEvidence rateEvidence, String note) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DecisionCompetition(String symbol, String intent,
                                      List<StrategyEvaluation> evaluations,
                                      List<Rejection> rejected,
                                      List<DecisionBaseline> baselines,
                                      String recommendationId, String calibrationNote) {}
    /** Candidate is carried by its parent row; every available decision fact has one canonical receipt.
     * A mechanical preview can remain usable when the broader decision assessment cannot be assembled.
     * In that case {@code available=false} and no score, verdict, or profile is fabricated. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EvaluationReceipt(
            boolean available,
            String unavailableReason,
            Double decisionScore,
            Boolean viable,
            io.liftandshift.strikebench.eval.CapitalProfile capital,
            io.liftandshift.strikebench.eval.VolatilityProfile volatility,
            io.liftandshift.strikebench.eval.RiskProfile risk,
            io.liftandshift.strikebench.eval.EvidenceProfile evidence,
            io.liftandshift.strikebench.eval.ManagementPlan management,
            io.liftandshift.strikebench.eval.ScoreBreakdown score,
            io.liftandshift.strikebench.eval.FourOutputAssessment assessment,
            io.liftandshift.strikebench.eval.StanceVector stance,
            io.liftandshift.strikebench.position.ParticipationProfile participation,
            io.liftandshift.strikebench.eval.ImpliedStance impliedStance,
            io.liftandshift.strikebench.eval.IvContext ivContext,
            io.liftandshift.strikebench.eval.DataCoverageReceipt coverage,
            io.liftandshift.strikebench.eval.Explanation explanation
    ) {
        public static EvaluationReceipt of(StrategyEvaluation evaluation) {
            if (evaluation == null) throw new IllegalArgumentException("evaluation is required");
            return new EvaluationReceipt(true, null, evaluation.decisionScore(), evaluation.viable(),
                    evaluation.capital(), evaluation.volatility(), evaluation.risk(), evaluation.evidence(),
                    evaluation.management(), evaluation.score(), evaluation.assessment(), evaluation.stance(),
                    evaluation.participation(), evaluation.impliedStance(), evaluation.ivContext(),
                    evaluation.coverage(), evaluation.explanation());
        }

        public static EvaluationReceipt unavailable(String reason, boolean mechanicallyEligible,
                                                    List<String> mechanicalReasons,
                                                    long estimatedRoundTripFeesCents) {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("an unavailable evaluation requires a reason");
            }
            List<String> reasons = mechanicalReasons == null
                    ? List.of(reason) : java.util.stream.Stream.concat(mechanicalReasons.stream(),
                            java.util.stream.Stream.of(reason)).distinct().toList();
            var economics = new io.liftandshift.strikebench.eval.EconomicAssessment(
                    io.liftandshift.strikebench.eval.EconomicAssessment.Verdict.UNAVAILABLE,
                    mechanicallyEligible ? "MECHANICS_ONLY" : "MECHANICALLY_INELIGIBLE",
                    mechanicallyEligible ? "Economics unavailable" : "Cannot assess as a trade",
                    reason, null, null, Math.max(0, estimatedRoundTripFeesCents), null, false, reasons);
            var assessment = new io.liftandshift.strikebench.eval.FourOutputAssessment(
                    new io.liftandshift.strikebench.eval.FourOutputAssessment.MechanicalAssessment(
                            mechanicallyEligible, mechanicalReasons),
                    economics,
                    new io.liftandshift.strikebench.eval.FourOutputAssessment.ObjectiveCoherence(
                            io.liftandshift.strikebench.eval.FourOutputAssessment.Coherence.UNAVAILABLE,
                            "Direction assessment unavailable", "Duration assessment unavailable",
                            List.of("Objective fit was not inferred while the decision assessment was unavailable.")),
                    new io.liftandshift.strikebench.eval.FourOutputAssessment.PortfolioImpacts(
                            null, null, List.of("Portfolio impact was not inferred from incomplete assessment data.")));
            return new EvaluationReceipt(false, reason, null, null,
                    null, null, null, null, null, null, assessment, null, null, null, null, null, null);
        }
    }
    public record CreatedTrade<T, U>(T trade, U warnings) {}
    public record Guardrails(String level, List<String> blockReasons, List<String> warnings) {}
    public record RiskAcknowledgment(String id, String label) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AccountFit(Double pctOfNlv, Double pctOfCashBp, Double pctOfMarginBp,
                             Double pctOfRiskCapital, Boolean overRiskCapital) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TradePreviewResponse(TradePreview preview, EvaluationReceipt evaluation,
                                       Guardrails guardrails, List<RiskAcknowledgment> requiredAcks,
                                       String ackToken, AccountFit accountFit,
                                       io.liftandshift.strikebench.strategy.StrategyCatalog.PositionIdentity identity) {}
    public enum OrderValuationBasis { EXECUTABLE_BOOK, RESTING_LIMIT, UNAVAILABLE }
    public record OrderSummary(int qty, long proposedNetCents, long feesOverrideCents,
                               OrderInstruction orderInstruction,
                               OrderInstruction.Executability executability,
                               boolean presentlyExecutable, Long executableNetCents,
                               Long valuedNetCents, OrderValuationBasis valuationBasis) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlanDecisionPreview<T, U>(TradePreview preview, EvaluationReceipt evaluation,
                                             Guardrails guardrails,
                                             List<RiskAcknowledgment> requiredAcks,
                                             String ackToken, AccountFit accountFit,
                                             T plan, U selected, OrderSummary order) {}
    public record TradePage<T>(T trades, long total, int page, int size) {}
    public record PositionBook<T>(T positions, String note) {}
    public record TrackedPackageAnalysis(TradePreview preview, EvaluationReceipt evaluation,
                                         io.liftandshift.strikebench.strategy.StrategyCatalog.PositionIdentity identity,
                                         String accountId,
                                         String accountName, long availableCashCents,
                                         String marketLane, String note) {}
    public record PayoffPoint(String price, long profitCents) {}
    public record TradeDetail<T, U, V, W>(T trade, U current, V marksHistory, W audit,
                                           List<PayoffPoint> payoff) {}
    public record OptionLifecycleProjection(String action, int legIndex, String contract,
                                            String expiration, long settlementUnderlyingCents,
                                            String settlementPriceBasis,
                                            long optionSettlementCashCents, long stockCashCents,
                                            long sharesDelta, long reserveBeforeCents,
                                            long reserveAfterCents, long projectedCashAfterCents,
                                            long projectedReservedAfterCents, List<String> basisNotes) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PositionTransformationPreview<T, U>(
            io.liftandshift.strikebench.position.PositionTransformation.Preview transformation,
            T before, U after, Long closingCashCents, Long closingFeesCents,
            Long openingCashCents, Long openingFeesCents,
            Long allocatedEntryBasisCents, Long allocatedOpenFeesCents,
            Long actionRealizedPnlCents, Long realizedPnlToDateCents,
            List<String> basisNotes, OptionLifecycleProjection lifecycle,
            String previewToken, String expiresAt) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PositionTransformationApplied<T, U, V>(
            String receiptId,
            io.liftandshift.strikebench.position.PositionTransformation.Preview transformation,
            T trade, U plan, V management, long actionRealizedPnlCents,
            long realizedPnlToDateCents) {}
    public record Trade<T>(T trade) {}
    public record BrokerPreview<T>(String localId, T preview, String confirmTextRequired) {}
    public record CancelRequested(boolean cancelRequested, String note) {}
    public record PopVsOutcome(int highPopTrades, Long highPopWinRate, int lowPopTrades,
                               Long lowPopWinRate, String note) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SimulationReport<T, U, V, W>(String worldId, T config, String simTime,
                                                long ticks, U trades, int resolved, Long winRate,
                                                long decisionPnlCents, PopVsOutcome popVsOutcome,
                                                String modelVersion, V events, W rehearsal,
                                                String note) {}
    public record RiskModeBudget(String mode, String label, double percent,
                                 long policyBudgetCents, long effectiveBudgetCents,
                                 boolean capped) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RiskBudget<T>(String basisType, long basisCents, String accountType,
                                Long explicitCapCents, String capSource, T modes,
                                String note, String acquireException) {}
    public record PortfolioSummary(long cashCents, long reservedCents, long buyingPowerCents,
                                   long startingCashCents, long sharesValueCents,
                                   int sharesPositions, int openTradesCount,
                                   long openTradesValueCents, long openTradesUnrealizedCents,
                                   long totalValueCents, long totalPnlCents, boolean complete,
                                   String freshness, String note) {}

    private ApiResponses() {}
}
