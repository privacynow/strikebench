package io.liftandshift.strikebench.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.liftandshift.strikebench.eval.EconomicAssessment;
import io.liftandshift.strikebench.eval.StrategyEvaluation;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.paper.OrderInstruction;
import io.liftandshift.strikebench.paper.PackagePriceReceipt;
import io.liftandshift.strikebench.paper.TradePreview;
import io.liftandshift.strikebench.recommend.Rejection;

import java.math.BigDecimal;
import java.util.List;

/** Named wire contracts shared by small API envelopes. Domain services own richer response records. */
public final class ApiResponses {
    public static final String SCENARIO_ANIMATION_CONTRACT_VERSION = "scenario-animation-2";
    public static final String SCENARIO_ANIMATION_VALUATION_CONTRACT_VERSION =
            "scenario-animation-valuation-2";

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

    /**
     * THE quote row (§3.1, §5.5). One shape for every batch lane — observed engine snapshots,
     * the demo market and simulated worlds — and the same display authority the single-symbol
     * research receipt publishes, produced HERE from {@link Quote} so no surface can pick between
     * two price sources.
     *
     * <p>{@code displayPrice}/{@code displayChangePct}/{@code markBasis} are the served decision;
     * {@code last}/{@code bid}/{@code ask}/{@code prevClose} are raw evidence a surface may show but
     * must never re-derive a price from. A row is either priced with a stated basis, or unpriced
     * with a stated reason — never an invented 0 and never a silently substituted previous close
     * (§3.2).</p>
     */
    public record QuoteView(String symbol, String description,
                            BigDecimal displayPrice, Double displayChangePct, String markBasis,
                            boolean priceIsPreviousClose, boolean priced,
                            String quoteUnavailableReason,
                            BigDecimal last, BigDecimal bid, BigDecimal ask, BigDecimal prevClose,
                            boolean optionable, String freshness, String source,
                            DataEvidence evidence, Long asOf, boolean refreshing) {
        public QuoteView {
            if (symbol == null || symbol.isBlank()) {
                throw new IllegalArgumentException("a quote row needs a symbol");
            }
            // A stated price and a stated reason are mutually exclusive: an unpriced row may not
            // ship a number, and a priced row may not carry an excuse that hides it.
            if (priced == (displayPrice == null)) {
                throw new IllegalArgumentException("quote row for " + symbol
                        + " must either carry a display price or be unpriced, not both/neither");
            }
            if (priced == (quoteUnavailableReason != null)) {
                throw new IllegalArgumentException("quote row for " + symbol
                        + " must state exactly one of a display price or an unavailability reason");
            }
        }

        /** The served row for a quote the market actually has. */
        public static QuoteView of(Quote quote, boolean refreshing) {
            Quote.MarkBasis basis = quote.markBasis();
            if (basis == Quote.MarkBasis.UNAVAILABLE) {
                return unavailable(quote.symbol(),
                        quote.symbol() + " has no last trade, no two-sided book and no previous close"
                                + " in this market, so it has no price to show",
                        quote.description(), quote.optionable(), quote.markFreshness().name(),
                        quote.source(), quote.evidence(), quote.asOfEpochMs(), refreshing);
            }
            return new QuoteView(quote.symbol(), quote.description(),
                    quote.mark(), quote.markChangePct(), basis.name(),
                    quote.usesPreviousCloseFallback(), true, null,
                    quote.last(), quote.bid(), quote.ask(), quote.prevClose(),
                    quote.optionable(), quote.markFreshness().name(), quote.source(),
                    quote.evidence(), quote.asOfEpochMs(), refreshing);
        }

        /** The served row for a symbol the market cannot price, carrying WHY (§3.2). */
        public static QuoteView unavailable(String symbol, String reason) {
            return unavailable(symbol, reason, null, false, "UNAVAILABLE", null, null, null, false);
        }

        private static QuoteView unavailable(String symbol, String reason, String description,
                                             boolean optionable, String freshness, String source,
                                             DataEvidence evidence, Long asOf, boolean refreshing) {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("an unpriced quote row for " + symbol + " needs a reason");
            }
            return new QuoteView(symbol, description, null, null,
                    Quote.MarkBasis.UNAVAILABLE.name(), false, false, reason,
                    null, null, null, null, optionable, freshness, source, evidence, asOf, refreshing);
        }
    }
    /**
     * THE workspace receipt (audit §6, backend gap 8). Mode and context are ONE payload: the world,
     * lane and account that own the context sit beside the context itself, so a header cannot say
     * Observed while the body says Demo. {@code rev} is 0 when nothing is stored — that is an
     * undeclared workspace, not an empty default. {@code transition} states what a world change
     * cleared; {@code unreadable} states why a stored context was refused instead of half-read.
     */
    public record Workspace(long rev, String updatedAt, int supportedVersion, String world,
                            String datasetId, String marketLane, String accountId,
                            io.liftandshift.strikebench.db.WorkspaceContext context,
                            io.liftandshift.strikebench.db.WorkspaceContext.Transition transition,
                            io.liftandshift.strikebench.db.WorkspaceContext.Unreadable unreadable) {
        /** One serializer for both /api/workspace and the atomic /api/world transition receipt. */
        public static Workspace from(
                io.liftandshift.strikebench.db.WorkspaceService.ContextState state,
                io.liftandshift.strikebench.db.WorkspaceContext.ActiveMarket market) {
            var context = state.context();
            return new Workspace(state.rev(), state.updatedAt(),
                    io.liftandshift.strikebench.db.WorkspaceContext.CURRENT_VERSION,
                    context == null ? market.world() : context.world(),
                    context == null ? market.datasetId() : context.datasetId(),
                    context == null ? market.lane() : context.marketLane(),
                    context == null ? market.accountId() : context.accountId(),
                    context, state.transition(), state.unreadable());
        }
    }
    public record Plans<T>(T plans, String market, String world) {}
    public record PlanSymbolError(String error, String detail, String market) {}
    public record PlanStrategy<T, U>(T plan, U strategy) {}
    /** The exact scanned package a Plan adopted, with the row identity it was shown under (§8.2). */
    public record PlanStrategyAdoption<T, U, V>(T plan, U strategy, V identity, String evaluationId) {}
    public record PlanStrategyPreview<T, U, V>(T plan, U strategy, V preview,
                                                io.liftandshift.strikebench.strategy.StrategyCatalog.PositionIdentity identity) {}
    /** One server-owned answer to whether a persisted computed artifact may be reused now. */
    public record ArtifactCurrency(boolean current, String status, String reason) {}
    public record StrategyState<T, U>(T strategy, U selected, ArtifactCurrency currency) {}
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
    public record PlanEnsemble<T, U>(T plan, EnsembleRef ensemble, U preview,
                                     ArtifactCurrency currency) {}
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
    /** Identity and market evidence for a display/valuation projection of one stored path artifact. */
    public record ScenarioProjectionReceipt(
            String contractVersion,
            String basis,
            String sourceEnsembleId,
            String sourceEnsembleFingerprint,
            QuoteView anchorQuote,
            double anchorSpot,
            String anchorDate,
            int horizonSessions,
            String transform,
            String fingerprint) {}
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
            io.liftandshift.strikebench.sim.ScenarioCanvasTemplateService.Interaction
                    requestedInteraction,
            ScenarioProjectionReceipt projection,
            Long interactionTargetSpotCents,
            io.liftandshift.strikebench.sim.ScenarioCanvasTemplateService.Interaction
                    interaction,
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
    public record Account<T>(T account) {}
    public record AccountLedger<T, U>(T account, U ledger) {}
    public record Expirations<T>(String symbol, String asOfDate, T expirations,
                                 ExpirationSelection selection) {}
    /** One expiration with its distance in both units, so no consumer has to count days itself. */
    public record ExpirationDistance(String date, int tradingSessions, int calendarDays) {}
    /** Server-owned listed-contract choice; consumers render it and never re-select from rows. */
    public record ExpirationSelection(String date, Integer requestedHorizonSessions,
                                      Integer tradingSessions, Integer calendarDays, String basis) {}
    public record EvidenceSummary<T, U>(T summary, U inputs) {}
    public record Benchmark<T, U>(String symbol, T last, String freshness, U evidence) {}
    /**
     * The single-symbol research document. Quote facts exist only inside {@link #quote}; legacy
     * top-level copies of price/change/basis/freshness were removed so a consumer cannot route to a
     * stale alias while the canonical QuoteView says something else.
     */
    public record ResearchDetail<U, V, W>(String symbol, QuoteView quote, String marketLane,
                                              boolean optionable, Double ivAtm,
                                              boolean ivRankAvailable, Double ivRankPct,
                                              Double ivPercentilePct, int ivHistoryDays,
                                              int ivRankRequiredDays, String ivRankNote,
                                              io.liftandshift.strikebench.market.EventService.EventEvidence earningsEstimate,
                                              io.liftandshift.strikebench.market.EventService.EventEvidence exDividend, Double hv30,
                                              int hvHistoryDays, int hvRequiredDays,
                                              boolean historyDemo, String historyBarBasis,
                                              String historyPriceBasis, U evidence,
                                              V expirations, boolean planEligible,
                                              String planEligibility, W benchmarks,
                                              String asOfDate,
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
                                U evidence, Object coverage, HistoryOverlays overlays) {}
    /**
     * Chart overlay series derived from the SAME authoritative candles this response carries, one
     * value per candle (oldest-first, {@code null} until enough trailing history). Serving them
     * keeps realized-vol and moving-average overlays honest to one backend source instead of a
     * second client estimator. {@code rv20} is annualized realized volatility (a ratio).
     * {@code bandUp}/{@code bandDn} are the realized one-month ±1σ envelope
     * ({@code sma20 · exp(±rv20 · √(21/252))}), now server-computed so the client plots values
     * instead of estimating; {@code null} at any bar where {@code sma20} or {@code rv20} is null.
     */
    public record HistoryOverlays(List<Double> rv20, List<Double> sma20, List<Double> sma50,
                                  List<Double> bandUp, List<Double> bandDn) {}
    public record Sparklines<T>(String range, T sparklines, int totalRequested, String world) {}
    /** Existing Research news route, enriched by the one versioned deterministic scorer. */
    public record ResearchNews<T, U>(String symbol, String scorerVersion, T items, U aggregate,
                                     T eventRisk, String evidence, String note) {}
    public record Optimization<T, U>(T optimization, int scanned, U scanNotes,
                                     io.liftandshift.strikebench.recommend.RedeploymentFrontier.Result frontier) {
        public Optimization(T optimization, int scanned, U scanNotes) {
            this(optimization, scanned, scanNotes, null);
        }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DecisionBaseline(String key, Long maxLossCents, Long capitalCents,
                                   boolean viable, String marketLane,
                                   String asOfDate, Integer horizonDays, Double volatility,
                                   String volatilityBasis, DataEvidence rateEvidence,
                                   io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.BaselineReceipt
                                           marketImpliedRisk,
                                   String note) {
        @JsonInclude(JsonInclude.Include.ALWAYS)
        @com.fasterxml.jackson.annotation.JsonProperty("evCents")
        public Long evCents() {
            return marketImpliedRisk == null ? null : marketImpliedRisk.expectedValueCents();
        }

        @JsonInclude(JsonInclude.Include.ALWAYS)
        @com.fasterxml.jackson.annotation.JsonProperty("cvar95Cents")
        public Long cvar95Cents() {
            return marketImpliedRisk == null ? null : marketImpliedRisk.cvar95Cents();
        }

        @JsonInclude(JsonInclude.Include.ALWAYS)
        @com.fasterxml.jackson.annotation.JsonProperty("stressLossCents")
        public Long stressLossCents() {
            return marketImpliedRisk == null ? null : marketImpliedRisk.stressLossCents();
        }

        @JsonInclude(JsonInclude.Include.ALWAYS)
        @com.fasterxml.jackson.annotation.JsonProperty("pAnyProfit")
        public Double pAnyProfit() {
            return marketImpliedRisk == null ? null : marketImpliedRisk.pop();
        }
    }
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
            io.liftandshift.strikebench.eval.Explanation explanation,
            io.liftandshift.strikebench.eval.DecisionEndorsement endorsement
    ) {
        public static EvaluationReceipt of(StrategyEvaluation evaluation) {
            if (evaluation == null) throw new IllegalArgumentException("evaluation is required");
            return new EvaluationReceipt(true, null, evaluation.decisionScore(), evaluation.viable(),
                    evaluation.capital(), evaluation.volatility(), evaluation.risk(), evaluation.evidence(),
                    evaluation.management(), evaluation.score(), evaluation.assessment(), evaluation.stance(),
                    evaluation.participation(), evaluation.impliedStance(), evaluation.ivContext(),
                    evaluation.coverage(), evaluation.explanation(), evaluation.endorsement());
        }

        /** Attaches this receipt onto a candidate JSON node under "evaluation" — THE one place that
         *  serializes an evaluation receipt into a candidate, reused by every ranked surface. */
        public static void attachTo(com.fasterxml.jackson.databind.node.ObjectNode node,
                                    StrategyEvaluation evaluation) {
            node.set("evaluation", io.liftandshift.strikebench.util.Json.MAPPER.valueToTree(of(evaluation)));
        }

        /**
         * @param estimatedRoundTripFeesCents the §7.2 receipt's own round-trip commission, or NULL
         *        when the package states none. §3.2: an unknown commission stays null here rather
         *        than being clamped to 0, which advertised an unpriced package as free to trade.
         */
        public static EvaluationReceipt unavailable(String reason, boolean mechanicallyEligible,
                                                    List<String> mechanicalReasons,
                                                    Long estimatedRoundTripFeesCents) {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("an unavailable evaluation requires a reason");
            }
            if (estimatedRoundTripFeesCents != null && estimatedRoundTripFeesCents < 0) {
                throw new IllegalArgumentException("round-trip fees cannot be negative");
            }
            List<String> baseReasons = mechanicalReasons == null
                    ? List.of(reason) : java.util.stream.Stream.concat(mechanicalReasons.stream(),
                            java.util.stream.Stream.of(reason)).distinct().toList();
            List<String> reasons = estimatedRoundTripFeesCents == null
                    ? java.util.stream.Stream.concat(baseReasons.stream(),
                            java.util.stream.Stream.of(
                                    io.liftandshift.strikebench.eval.EconomicAssessment.UNKNOWN_FEES_REASON))
                            .distinct().toList()
                    : baseReasons;
            var economics = new io.liftandshift.strikebench.eval.EconomicAssessment(
                    io.liftandshift.strikebench.eval.EconomicAssessment.Verdict.UNAVAILABLE,
                    mechanicallyEligible ? "MECHANICS_ONLY" : "MECHANICALLY_INELIGIBLE",
                    mechanicallyEligible ? "Economics unavailable" : "Cannot assess as a trade",
                    reason, null, null,
                    estimatedRoundTripFeesCents,
                    null, false, reasons);
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
                    null, null, null, null, null, null, assessment, null, null, null, null, null, null,
                    new io.liftandshift.strikebench.eval.DecisionEndorsement(false,
                            io.liftandshift.strikebench.eval.DecisionEndorsement.COMPARISON,
                            null, List.of(reason),
                            "An unavailable evaluation cannot be promoted from comparison."));
        }
    }
    public record CreatedTrade<T, U>(T trade, U warnings) {}
    public record Guardrails(String level, List<String> blockReasons, List<String> warnings) {}
    public record RiskAcknowledgment(String id, String label) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CapitalUse(String fundingClass, String capitalBasis,
                             Long capCents, Long usedCents, Long remainingCents,
                             Long overageCents, Boolean withinCap,
                             String basis, String unavailableReason) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AccountFit(Double pctOfNlv, Double pctOfCashBp, Double pctOfMarginBp,
                             Double pctOfRiskCapital, Boolean overRiskCapital,
                             CapitalUse selectedCapital) {}
    /**
     * The server's final execution decision for an exact preview. The browser renders this
     * receipt; it does not re-run guardrails, account-fit policy, or package executability.
     */
    public record ExecutionDecision(boolean reviewAllowed, boolean confirmAllowed,
                                    List<String> reasons) {
        public ExecutionDecision {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            if (confirmAllowed && !reviewAllowed) {
                throw new IllegalArgumentException(
                        "confirmAllowed requires reviewAllowed; execution authorization fails closed");
            }
        }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TradePreviewResponse(TradePreview preview, EvaluationReceipt evaluation,
                                       Guardrails guardrails, List<RiskAcknowledgment> requiredAcks,
                                       String ackToken, AccountFit accountFit,
                                       io.liftandshift.strikebench.strategy.StrategyCatalog.PositionIdentity identity,
                                       io.liftandshift.strikebench.eval.DecisionEndorsement endorsement,
                                       ExecutionDecision execution) {}
    /**
     * The order dock owns only the user's instruction. The exact package price is serialized once,
     * at {@code PlanDecisionPreview.preview.price}; publishing it here too created two authorities
     * for the same observation and encouraged the browser to choose between duplicate scalar views.
     */
    public record OrderDock(OrderInstruction orderInstruction) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlanDecisionPreview<T, U>(TradePreview preview, EvaluationReceipt evaluation,
                                             Guardrails guardrails,
                                             List<RiskAcknowledgment> requiredAcks,
                                             String ackToken, AccountFit accountFit,
                                             T plan, U selected, OrderDock order,
                                             io.liftandshift.strikebench.eval.DecisionEndorsement endorsement,
                                             ExecutionDecision execution) {}
    public record TradePage<T>(T trades, long total, int page, int size) {}
    public record PositionBook<T>(T positions, String note) {}
    public record TrackedPackageAnalysis(TradePreview preview, EvaluationReceipt evaluation,
                                         io.liftandshift.strikebench.strategy.StrategyCatalog.PositionIdentity identity,
                                         String accountId,
                                         String accountName, long availableCashCents,
                                         String marketLane, String note,
                                         io.liftandshift.strikebench.position.PositionLifecycleReceipt lifecycle,
                                         io.liftandshift.strikebench.paper.BookActionProjectionService.ProjectionSet bookActions,
                                         io.liftandshift.strikebench.paper.AccountObjectiveService.CapacityContext capacity,
                                         io.liftandshift.strikebench.position.PositionLifecycleDecisionService.SurfacedReceipt decision) {}
    public record PracticePositionAnalysis(EvaluationReceipt evaluation,
                                           io.liftandshift.strikebench.strategy.StrategyCatalog.PositionIdentity identity,
                                           String accountId, String accountName, long availableCashCents,
                                           String marketLane, String note,
                                           io.liftandshift.strikebench.position.PositionLifecycleReceipt lifecycle,
                                           io.liftandshift.strikebench.paper.BookActionProjectionService.ProjectionSet bookActions,
                                           io.liftandshift.strikebench.paper.AccountObjectiveService.CapacityContext capacity,
                                           io.liftandshift.strikebench.position.PositionLifecycleDecisionService.DecisionAnalysis decision) {}
    /**
     * §5.4: THE backend answer to "what is this package worth if the price holds".
     *
     * <p>The held terminal payoff evaluated at ONE declared spot, on the same curve and the same
     * price/evidence snapshot that produced the served polyline — so the printed number and the
     * drawn line can never come from different engines. The browser may still interpolate the
     * polyline to place pixels; it may not originate this figure (§3.1).
     *
     * <p>{@code spotBasis} names the exact current-price receipt used (for example {@code MID} or
     * {@code PREVIOUS_CLOSE}) and {@code withinServedCurve} says whether the served polyline even
     * reaches that price. A recorded entry is never substituted for a missing current price.
     * When there is no answer, {@code unavailableReason} states why — never a substituted 0 (§3.2).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HeldSpotPnl(Long terminalPnlAtCurrentSpotCents, Long spotCents, String spotBasis,
                              String freshness, boolean withinServedCurve, String unavailableReason) {
        public static HeldSpotPnl unavailable(String reason) {
            return new HeldSpotPnl(null, null, null, null, false, reason);
        }
    }

    /**
     * Named held-position scenarios are an independent receipt. An empty successful list and a
     * producer failure are materially different states, so the latter may never collapse to
     * {@code []}: it carries the user-facing reason that prevented the receipt from being built.
     */
    public record HeldScenarioValue(
            io.liftandshift.strikebench.model.ScenarioStory story,
            double underlyingMovePct,
            long targetUnderlyingCents,
            long pnlCents,
            Double prob) {
        public HeldScenarioValue {
            if (story == null || !Double.isFinite(underlyingMovePct)
                    || targetUnderlyingCents <= 0) {
                throw new IllegalArgumentException(
                        "a held scenario requires a story, finite move, and positive target price");
            }
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HeldScenarios(
            boolean available,
            List<HeldScenarioValue> values,
            Long anchorSpotCents,
            String anchorBasis,
            String freshness,
            String source,
            Long observedAt,
            String unavailableReason) {
        public HeldScenarios {
            values = values == null ? List.of() : List.copyOf(values);
            if (available && unavailableReason != null) {
                throw new IllegalArgumentException(
                        "available held scenarios cannot have an unavailable reason");
            }
            if (!available && (unavailableReason == null || unavailableReason.isBlank())) {
                throw new IllegalArgumentException(
                        "unavailable held scenarios require a user-facing reason");
            }
            if (!available && !values.isEmpty()) {
                throw new IllegalArgumentException(
                        "unavailable held scenarios cannot carry partial values");
            }
            if (available && (anchorSpotCents == null || anchorSpotCents <= 0
                    || anchorBasis == null || anchorBasis.isBlank()
                    || freshness == null || freshness.isBlank()
                    || source == null || source.isBlank()
                    || observedAt == null || observedAt <= 0)) {
                throw new IllegalArgumentException(
                        "available held scenarios require an explicit current-price anchor receipt");
            }
            if (!available && (anchorSpotCents != null || anchorBasis != null
                    || freshness != null || source != null || observedAt != null)) {
                throw new IllegalArgumentException(
                        "unavailable held scenarios cannot carry a price anchor");
            }
        }

        public static HeldScenarios available(
                List<HeldScenarioValue> values,
                long anchorSpotCents, String anchorBasis, String freshness,
                String source, long observedAt) {
            return new HeldScenarios(true, values, anchorSpotCents, anchorBasis,
                    freshness, source, observedAt, null);
        }

        public static HeldScenarios unavailable(String reason) {
            return new HeldScenarios(false, List.of(), null, null, null, null, null,
                    reason == null || reason.isBlank()
                            ? "The held-position scenario receipt could not be produced."
                            : reason);
        }
    }

    /**
     * §5.4: the position detail carries exactly ONE held payoff — {@code trade.terminalPayoff}, the
     * shared {@code RiskProfile.TerminalPayoff} receipt an idea candidate also carries. The former
     * second copy on this envelope ({@code payoff}, a {@code price}/{@code profitCents} list) is
     * deleted: the browser used to consume one and then overwrite it with the other.
     */
    public record TradeDetail<T, U, V, W>(
            T trade,
            U current,
            QuoteView quote,
            String currentUnavailableReason,
            V marksHistory,
            W audit,
            PracticePositionAnalysis analysis) {}
    public record OptionLifecycleProjection(String action, int legIndex, String contract,
                                            String expiration, long settlementUnderlyingCents,
                                            String settlementPriceBasis,
                                            long optionSettlementCashCents, long stockCashCents,
                                            long sharesDelta, long reserveBeforeCents,
                                            @JsonInclude(JsonInclude.Include.ALWAYS)
                                            Long reserveAfterCents,
                                            long projectedCashAfterCents,
                                            @JsonInclude(JsonInclude.Include.ALWAYS)
                                            Long projectedReservedAfterCents,
                                            List<String> basisNotes) {}
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
                                   String freshness, String note,
                                   io.liftandshift.strikebench.position.AccountLiquidityReceipt liquidity) {}

    private ApiResponses() {}
}
