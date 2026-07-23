package io.liftandshift.strikebench.api;
import io.liftandshift.strikebench.market.MarketLane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.http.Context;
import io.liftandshift.strikebench.backtest.Backtester;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.plan.PlanEvidenceService;
import io.liftandshift.strikebench.plan.PlanManagementService;
import io.liftandshift.strikebench.plan.PlanOutcomeService;
import io.liftandshift.strikebench.plan.PlanService;
import io.liftandshift.strikebench.plan.PlanStrategyService;
import io.liftandshift.strikebench.sim.PathEnsembleService;
import io.liftandshift.strikebench.sim.SimulationEngine;
import io.liftandshift.strikebench.util.Json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Owns Plan outcome ensembles, exact-position valuation, comparisons, and historical replays. */
final class PlanOutcomeController {
    private final PlanController root;
    private final AppConfig cfg;
    private final MarketDataService market;
    private final Backtester backtester;
    private final PlanService planSvc;
    private final PlanEvidenceService planEvidence;
    private final PlanStrategyService planStrategy;
    private final PlanManagementService planManagement;
    private final PlanOutcomeService planOutcomes;
    private final PathEnsembleService pathEnsembles;
    private final SimulationEngine simEngine;
    private final OutcomeController outcomeController;
    private final io.liftandshift.strikebench.plan.AuthoredScenarioService authoredScenarios;
    private final io.liftandshift.strikebench.sim.ScenarioCanvasTemplateService canvasTemplates;
    private final io.liftandshift.strikebench.position.ScenarioPositionScopeService canvasPositions;
    private final io.liftandshift.strikebench.sim.ScenarioCanvasValuator canvasValuator =
            new io.liftandshift.strikebench.sim.ScenarioCanvasValuator();

    PlanOutcomeController(PlanController root, AppConfig cfg,
                          MarketDataService market, Backtester backtester,
                          PlanService planSvc, PlanEvidenceService planEvidence,
                          PlanStrategyService planStrategy, PlanManagementService planManagement,
                          PlanOutcomeService planOutcomes,
                          PathEnsembleService pathEnsembles, SimulationEngine simEngine,
                          OutcomeController outcomeController,
                          io.liftandshift.strikebench.plan.AuthoredScenarioService authoredScenarios,
                          io.liftandshift.strikebench.sim.ScenarioCanvasTemplateService canvasTemplates,
                          io.liftandshift.strikebench.position.ScenarioPositionScopeService canvasPositions) {
        this.root = root;
        this.cfg = cfg;
        this.market = market;
        this.backtester = backtester;
        this.planSvc = planSvc;
        this.planEvidence = planEvidence;
        this.planStrategy = planStrategy;
        this.planManagement = planManagement;
        this.planOutcomes = planOutcomes;
        this.pathEnsembles = pathEnsembles;
        this.simEngine = simEngine;
        this.outcomeController = outcomeController;
        this.authoredScenarios = authoredScenarios;
        this.canvasTemplates = canvasTemplates;
        this.canvasPositions = canvasPositions;
    }

    public record PlanEnsembleRequest(Long expectedVersion,
                                      io.liftandshift.strikebench.sim.ScenarioSpec over,
                                      io.liftandshift.strikebench.sim.IvSpec iv,
                                      List<io.liftandshift.strikebench.sim.SimulationEngine.DecisionLevel> levels,
                                      io.liftandshift.strikebench.sim.ScenarioCanvasSpec canvas,
                                      io.liftandshift.strikebench.sim.ScenarioCanvasTemplateService.Request template,
                                      String researchReceiptId, String expectedFingerprint) {}
    public record PlanOutcomeRunRequest(Long expectedVersion, String basis, String ensembleId,
                                       io.liftandshift.strikebench.sim.ScenarioSpec over,
                                       io.liftandshift.strikebench.sim.IvSpec iv) {}
    public record PlanOutcomeCompareRequest(Long expectedVersion, String basis, String ensembleId,
                                            List<String> candidateIds,
                                            io.liftandshift.strikebench.sim.ScenarioSpec over,
                                            io.liftandshift.strikebench.sim.IvSpec iv) {}
    /**
     * Non-mutating animation query; inline pins are evaluated against an already-stored fan.
     * When {@code focusPositionKey} is present, it must name a package already exposed by the
     * same-symbol Scenario Canvas scope (or its stock/proposal baseline), and only that package is
     * repriced into checkpoints. The stored path artifact is never regenerated.
     */
    public record PlanScenarioPathsRequest(String ensembleId, String scenarioId,
                                           List<io.liftandshift.strikebench.sim.ScenarioSpec.Waypoint> waypoints,
                                           List<io.liftandshift.strikebench.sim.PathEnsembleService.DisplayWaypoint>
                                                   pathWaypoints,
                                           io.liftandshift.strikebench.sim.IvSpec iv,
                                           io.liftandshift.strikebench.sim.ScenarioCanvasSpec canvas,
                                           Integer limit,
                                           String focusPositionKey) {}
    public record PlanBacktestRequest(Long expectedVersion, String engine, String from, String to,
                                      Integer targetDte, Integer entryEveryDays, Integer maxConcurrent,
                                      Integer qty, Double slippagePct, Long startingCashCents,
                                      Double shortDelta, Double widthPct, Double profitTargetPct,
                                      Double stopFraction, Integer rollDte) {}
    void planOutcomesLatest(Context ctx) {
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        ObjectNode out = planOutcomes.latest(root.ownerId(ctx), plan, root.analysisCtx(ctx));
        JsonNode selected = planStrategy.selectedCandidate(root.ownerId(ctx), plan.id());
        JsonNode prior = selected == null
                ? planStrategy.priorSelectedCandidate(root.ownerId(ctx), plan.id()) : null;
        ctx.json(new ApiResponses.PlanOutcomesLatest<>(out.path("outcomes"), out.path("comparisons"),
                out.path("backtests"), selected, selected != null ? "CURRENT" : prior != null ? "STALE" : "NONE",
                prior));
    }

    /**
     * Bounded animation data for the Desk.  This projects the exact stored fan; an authored
     * scenario selects paths closest to its frozen waypoints and never invokes a second simulator.
     */
    void planScenarioPaths(Context ctx) {
        boolean typedAnimationRequest = ctx.method() == io.javalin.http.HandlerType.POST;
        PlanScenarioPathsRequest body = typedAnimationRequest
                ? ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, PlanScenarioPathsRequest.class))
                : null;
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        if (typedAnimationRequest) root.requireActivePlanMarket(ctx, plan);
        String requestedEnsembleId = body == null ? ctx.queryParam("ensembleId") : body.ensembleId();
        String scenarioId = body == null ? ctx.queryParam("scenarioId") : body.scenarioId();
        List<io.liftandshift.strikebench.sim.ScenarioSpec.Waypoint> inlineWaypoints =
                body == null || body.waypoints() == null ? List.of() : List.copyOf(body.waypoints());
        List<io.liftandshift.strikebench.sim.PathEnsembleService.DisplayWaypoint> inlinePathWaypoints =
                body == null || body.pathWaypoints() == null
                        ? List.of() : List.copyOf(body.pathWaypoints());
        if (!inlineWaypoints.isEmpty() && !inlinePathWaypoints.isEmpty()) {
            throw new IllegalArgumentException(
                    "waypoints and pathWaypoints are alternative conditioning sources; provide exactly one");
        }
        if (scenarioId != null && !scenarioId.isBlank()
                && (!inlineWaypoints.isEmpty() || !inlinePathWaypoints.isEmpty())) {
            throw new IllegalArgumentException("scenarioId and inline waypoints are alternative scenario sources");
        }
        int limit = body == null || body.limit() == null
                ? parseDisplayPathLimit(ctx.queryParam("limit")) : body.limit();
        io.liftandshift.strikebench.plan.PlanOutcomeService.StoredEnsemble stored;
        ApiResponses.ScenarioPathRef scenarioRef = null;
        io.liftandshift.strikebench.sim.ScenarioSpec scenarioSpec = null;
        if (scenarioId != null && !scenarioId.isBlank()) {
            var authored = authoredScenarios.load(root.ownerId(ctx), plan.id(), scenarioId);
            if (requestedEnsembleId != null && !requestedEnsembleId.isBlank()
                    && !requestedEnsembleId.equals(authored.baseEnsembleId())) {
                throw new IllegalArgumentException("The named scenario can only display paths from its stored base fan.");
            }
            stored = typedAnimationRequest
                    ? planOutcomes.loadCurrentEnsemble(root.ownerId(ctx), plan, authored.baseEnsembleId(),
                        root.analysisCtx(ctx))
                    : planOutcomes.loadEnsemble(root.ownerId(ctx), plan.id(), authored.baseEnsembleId());
            scenarioSpec = authored.spec();
            scenarioRef = new ApiResponses.ScenarioPathRef(authored.id(), authored.fingerprint(), authored.title(),
                    authored.contextRev() == plan.context().rev(), authored.waypointFill(), authored.baseEnsembleId());
        } else if (requestedEnsembleId != null && !requestedEnsembleId.isBlank()) {
            stored = planOutcomes.loadCurrentEnsemble(root.ownerId(ctx), plan, requestedEnsembleId,
                    root.analysisCtx(ctx));
        } else {
            stored = planOutcomes.latestEnsemble(root.ownerId(ctx), plan,
                    PathEnsembleService.Basis.PARAMETRIC.name(), root.analysisCtx(ctx));
            if (stored == null) {
                throw new io.liftandshift.strikebench.util.ResourceNotFoundException(
                        "Run the possible-futures fan before requesting display paths.");
            }
        }
        if (!inlineWaypoints.isEmpty()) {
            scenarioSpec = stored.ensemble().spec().withWaypoints(inlineWaypoints).sane();
        }
        if (!inlinePathWaypoints.isEmpty()
                && inlinePathWaypoints.getLast().sessionProgress()
                    > stored.ensemble().spec().horizonDays()) {
            throw new IllegalArgumentException("The final path waypoint lies beyond the stored ensemble horizon.");
        }
        if (typedAnimationRequest
                && !java.util.Objects.equals(root.activeWorld(ctx), stored.ensemble().scope().worldId())) {
            throw new IllegalStateException("This path set belongs to another market world. Open its market before animating it.");
        }
        var projection = inlinePathWaypoints.isEmpty()
                ? pathEnsembles.displayPaths(stored.ensemble(), scenarioSpec, limit)
                : pathEnsembles.displayPathsAtProgress(stored.ensemble(), inlinePathWaypoints, limit);
        var ensembleRef = new ApiResponses.EnsembleRef(stored.id(), stored.fingerprint(), stored.basis(),
                stored.ensemble().waypointFill().name());
        if (!typedAnimationRequest) {
            ctx.json(new ApiResponses.PlanScenarioPaths<>(plan, ensembleRef, scenarioRef, projection));
            return;
        }

        String focusPositionKey = normalizeFocusPositionKey(body.focusPositionKey());
        ObjectNode selected = focusPositionKey == null ? root.selectedCandidate(ctx, plan, true) : null;
        int focusSourcePathIndex = projection.receipt().focusSourcePathIndex();
        var displayPathSelections = canvasDisplaySelections(projection);
        var effectiveIv = body.iv() == null ? stored.iv() : body.iv().sane();
        var effectiveCanvas = (body.canvas() == null
                ? stored.canvas() == null
                    ? io.liftandshift.strikebench.sim.ScenarioCanvasSpec.defaults() : stored.canvas()
                : body.canvas()).sane(stored.ensemble().spec().horizonDays());
        ObjectNode checkpointHolder = Json.MAPPER.createObjectNode();
        ObjectNode checkpoints = decorateCanvasValuation(ctx, checkpointHolder, plan, stored,
                focusSourcePathIndex, effectiveIv, effectiveCanvas, focusPositionKey,
                displayPathSelections, projection.selection());
        String selectedCandidateId = selected == null ? null : selected.path("id").asText();
        String requiredPositionKey = focusPositionKey == null
                ? "PROPOSED:" + selectedCandidateId : focusPositionKey;
        boolean requiredPositionPresent = false;
        for (JsonNode position : checkpoints.path("positions")) {
            if (requiredPositionKey.equals(position.path("key").asText())) {
                requiredPositionPresent = true;
                break;
            }
        }
        if (!requiredPositionPresent) {
            throw new IllegalStateException(focusPositionKey == null
                    ? "The selected package could not be repriced on this stored ensemble; inspect the named canvas refusal."
                    : "The focused position could not be repriced on this stored ensemble; inspect the named canvas refusal.");
        }
        String valuationFingerprint = checkpoints.at("/modelReceipt/valuationFingerprint").asText();
        JsonNode focusedPackageNode = checkpoints.at("/modelReceipt/focusedPackageProvenance");
        ApiResponses.FocusedPackageProvenance focusedPackageProvenance =
                focusedPackageNode.isObject()
                        ? Json.MAPPER.convertValue(focusedPackageNode,
                            ApiResponses.FocusedPackageProvenance.class)
                        : null;
        String focusedPackageFingerprint = checkpoints.at("/modelReceipt/focusedPackageFingerprint")
                .asText(null);
        var receipt = new ApiResponses.ScenarioAnimationReceipt(
                "scenario-animation-1", stored.id(), stored.fingerprint(), stored.basis(),
                stored.ensemble().modelVersion(), stored.ensemble().scope().symbol(),
                stored.ensemble().scope().worldId(), stored.ensemble().scope().analysis().datasetId(),
                stored.contextRev(), stored.state(), stored.ensemble().spot(),
                stored.ensemble().anchorDate().toString(), stored.anchorSource(), stored.anchorFreshness(),
                stored.asOf(), stored.stepSeconds(), stored.ensemble().paths().length,
                stored.ensemble().spec().totalSteps(), stored.ensemble().waypointFill().name(),
                stored.ensemble().spec(), scenarioSpec,
                inlinePathWaypoints.isEmpty()
                        ? scenarioSpec == null ? List.of() : scenarioSpec.waypoints().stream()
                            .map(pin -> new io.liftandshift.strikebench.sim.PathEnsembleService.DisplayWaypoint(
                                    pin.dayIndex(), pin.priceRatio(), pin.tolerance()))
                            .toList()
                        : inlinePathWaypoints,
                effectiveIv, effectiveCanvas, stored.rateAnnual(),
                selectedCandidateId, focusPositionKey, focusedPackageFingerprint,
                focusedPackageProvenance, valuationFingerprint);
        ctx.json(new ApiResponses.PlanScenarioPaths<>(plan, ensembleRef, scenarioRef, projection,
                receipt, checkpoints));
    }

    private static int parseDisplayPathLimit(String raw) {
        if (raw == null || raw.isBlank()) return 8;
        try { return Integer.parseInt(raw); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("limit must be a whole number"); }
    }

    private static String normalizeFocusPositionKey(String raw) {
        if (raw == null) return null;
        String key = raw.trim();
        if (key.isEmpty()) throw new IllegalArgumentException("focusPositionKey cannot be blank");
        if (key.length() > 200) throw new IllegalArgumentException("focusPositionKey is too long");
        return key;
    }

    /** Evidence owns path generation; Outcomes later values the exact selected package on this artifact. */
    void planEnsembleRun(Context ctx) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, PlanEnsembleRequest.class));
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan);
        PlanController.requirePlanVersion(plan, body.expectedVersion());
        if (body.researchReceiptId() != null && !body.researchReceiptId().isBlank()) {
            if (body.over() != null || body.iv() != null || body.canvas() != null || body.template() != null
                    || body.levels() != null && !body.levels().isEmpty()) {
                throw new IllegalArgumentException(
                        "researchReceiptId adopts an exact fan; generation inputs cannot be mixed into the same request");
            }
            promoteResearchEnsemble(ctx, plan, body.researchReceiptId(), body.expectedFingerprint());
            return;
        }
        if (body.expectedFingerprint() != null && !body.expectedFingerprint().isBlank()) {
            throw new IllegalArgumentException("expectedFingerprint requires researchReceiptId");
        }
        boolean calibrateFromMarket = requestsMarketVol(body.over());
        var spec = planScenarioSpec(plan, body.over());
        var world = MarketLane.worldParam(root.activeWorld(ctx));
        var marketVol = outcomeController.marketVol(plan.symbol(), world, spec.horizonDays());
        var canvas = body.canvas() == null
                ? io.liftandshift.strikebench.sim.ScenarioCanvasSpec.defaults()
                : body.canvas().sane(spec.horizonDays());
        if (body.template() != null) {
            double spot = pathEnsembles.anchorSpot(new io.liftandshift.strikebench.sim.PathEnsembleService.Scope(
                    plan.symbol(), world, root.analysisCtx(ctx)));
            double atm = marketVol == null ? spec.sane().volAnnual() : marketVol.atmIv();
            var seed = canvasTemplates.apply(plan.symbol(), world, root.analysisCtx(ctx), spot, atm,
                    spec, canvas, body.template());
            spec = planScenarioSpec(plan, seed.spec());
            canvas = seed.canvas().sane(spec.horizonDays());
            marketVol = outcomeController.marketVol(plan.symbol(), world, spec.horizonDays());
            // Templates receive the resolved ATM IV above and may deliberately scale it. Their
            // returned spec is therefore explicit; do not flatten that authored transformation.
            calibrateFromMarket = false;
        }
        var calibrated = calibrateFromMarket && marketVol != null && marketVol.atmIv() > 0
                ? spec.withVol(marketVol.atmIv()).sane() : spec;
        double rate = market.riskFreeRateQuote(Math.max(1, calibrated.horizonDays()), world).annualRate();
        var run = simEngine.previewRun(plan.symbol(), calibrated, world, root.analysisCtx(ctx),
                body.levels() == null ? List.of() : body.levels(), marketVol, rate);
        var iv = body.iv() == null ? defaultPlanIv(run.ensemble().spec(), marketVol) : body.iv();
        JsonNode input = Json.MAPPER.valueToTree(body);
        var stored = planOutcomes.saveEnsemble(root.ownerId(ctx), plan, run.ensemble(), iv, canvas,
                rate, run.preview(), input);
        ObjectNode preview = Json.MAPPER.valueToTree(run.preview());
        preview.put("planEnsembleId", stored.id());
        preview.put("planEnsembleFingerprint", stored.fingerprint());
        if (preview.path("receipt") instanceof ObjectNode receipt) receipt.put("fingerprint", stored.fingerprint());
        decorateScenarioCanvas(preview, run.ensemble());
        decorateCanvasValuation(ctx, preview, plan, stored);
        ctx.json(new ApiResponses.PlanEnsemble<>(plan,
                new ApiResponses.EnsembleRef(stored.id(), stored.fingerprint(), stored.basis(),
                        run.ensemble().waypointFill().name()), preview));
    }

    /** Exact-receipt branch of the one canonical ensemble command; deliberately no generator call. */
    private void promoteResearchEnsemble(Context ctx, io.liftandshift.strikebench.plan.Plan.View plan,
                                         String researchReceiptId, String expectedFingerprint) {
        var analysis = root.analysisCtx(ctx);
        String world = root.activeWorld(ctx);
        String lane = io.liftandshift.strikebench.market.MarketLane
                .of(world, cfg.fixturesOnly(), analysis).name();
        var promoted = planOutcomes.promoteResearchEnsemble(root.ownerId(ctx), plan,
                researchReceiptId, expectedFingerprint,
                new io.liftandshift.strikebench.plan.PlanOutcomeService.ResearchContext(
                        lane, world, analysis.datasetId()));
        var stored = promoted.ensemble();
        ObjectNode preview = promoted.preview() instanceof ObjectNode object
                ? object.deepCopy() : Json.MAPPER.createObjectNode();
        preview.put("planEnsembleId", stored.id());
        preview.put("planEnsembleFingerprint", stored.fingerprint());
        if (preview.path("receipt") instanceof ObjectNode receipt) {
            receipt.put("fingerprint", stored.fingerprint());
        }
        decorateScenarioCanvas(preview, stored.ensemble());
        decorateCanvasValuation(ctx, preview, plan, stored);
        ctx.json(new ApiResponses.PlanEnsemble<>(plan,
                new ApiResponses.EnsembleRef(stored.id(), stored.fingerprint(), stored.basis(),
                        stored.ensemble().waypointFill().name()), preview));
    }

    /** Repaint the stored fan for the Plan's current view — same wire shape as a fresh run. */
    void planEnsembleLatest(Context ctx) {
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        var stored = planOutcomes.latestEnsemble(root.ownerId(ctx), plan,
                io.liftandshift.strikebench.sim.PathEnsembleService.Basis.PARAMETRIC.name(), root.analysisCtx(ctx));
        if (stored == null) {
            throw new io.liftandshift.strikebench.util.ResourceNotFoundException(
                    "No current stored simulation for this Plan's view — run the scenario to create one.");
        }
        String world = MarketLane.worldParam(stored.ensemble().scope().worldId());
        int horizon = stored.ensemble().spec().horizonDays();
        var marketVol = outcomeController.marketVol(plan.symbol(), world, horizon);
        double rate = market.riskFreeRateQuote(Math.max(1, horizon), world).annualRate();
        ObjectNode preview = Json.MAPPER.valueToTree(simEngine.previewFromStored(
                stored, planOutcomes.levelOdds(stored.id()), marketVol, rate));
        preview.put("planEnsembleId", stored.id());
        preview.put("planEnsembleFingerprint", stored.fingerprint());
        decorateScenarioCanvas(preview, stored.ensemble());
        decorateCanvasValuation(ctx, preview, plan, stored);
        ctx.json(new ApiResponses.PlanEnsemble<>(plan,
                new ApiResponses.EnsembleRef(stored.id(), stored.fingerprint(), stored.basis(),
                        stored.ensemble().waypointFill().name()), preview));
    }

    void planOutcomeRun(Context ctx) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, PlanOutcomeRunRequest.class));
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan);
        PlanController.requirePlanVersion(plan, body.expectedVersion());
        ObjectNode candidate = root.selectedCandidate(ctx, plan, true);
        var position = planOutcomePosition(candidate);
        String basisName = body.basis() == null ? "PARAMETRIC" : body.basis().trim().toUpperCase(Locale.ROOT);
        JsonNode input = Json.MAPPER.valueToTree(body);
        if ("RISK_NEUTRAL".equals(basisName)) {
            var request = new io.liftandshift.strikebench.outcomes.OutcomeContract.Request(
                    io.liftandshift.strikebench.outcomes.OutcomeContract.Operation.POSITION,
                    io.liftandshift.strikebench.outcomes.OutcomeContract.Basis.RISK_NEUTRAL,
                    planOutcomeContext(ctx, plan), position, null, null, null, null, null, null);
            var evaluated = outcomeController.evaluateOutcomes(ctx, request);
            JsonNode result = Json.MAPPER.valueToTree(evaluated.result());
            var saved = planOutcomes.saveRiskNeutral(root.ownerId(ctx), plan, body.expectedVersion(),
                    candidate.path("id").asText(), result, input, evaluated.interpretation(), root.analysisCtx(ctx));
            ctx.json(new ApiResponses.PlanOutcome<>(plan, saved));
            return;
        }
        io.liftandshift.strikebench.sim.PathEnsembleService.Basis basis;
        try { basis = io.liftandshift.strikebench.sim.PathEnsembleService.Basis.valueOf(basisName); }
        catch (Exception e) { throw new IllegalArgumentException("basis must be RISK_NEUTRAL, PARAMETRIC, HISTORICAL_ANALOGS, or CONDITIONAL_BOOTSTRAP"); }
        if (basis == io.liftandshift.strikebench.sim.PathEnsembleService.Basis.JOINT_ALIGNED_BOOTSTRAP) {
            throw new IllegalArgumentException("JOINT_ALIGNED_BOOTSTRAP is a Book-owned artifact, not a Plan outcome basis");
        }
        var stored = resolvePlanEnsemble(ctx, plan, body, basis);
        var pathPosition = outcomeController.toPathPosition(ctx, position.legs(),
                stored.ensemble().anchorDate());
        var simRequest = new OutcomeController.StrategySimRequest(plan.symbol(), pathPosition, position.qty(),
                stored.ensemble().spec(), stored.iv(), basis, null, position.entryCostCents(),
                outcomeController.contractExpirations(position.legs()));
        JsonNode result = Json.MAPPER.valueToTree(
                outcomeController.simStrategyResult(ctx, simRequest, stored.ensemble(), stored.canvas()));
        String interpretation = switch (basis) {
            case PARAMETRIC -> "The exact selected package is repriced on the same stored model ensemble shown in Evidence.";
            case HISTORICAL_ANALOGS -> "The exact selected package is repriced over the Plan's stored matching historical occurrences.";
            case CONDITIONAL_BOOTSTRAP -> "The exact selected package is repriced over whole-path resamples of the Plan's stored analog sample.";
            case JOINT_ALIGNED_BOOTSTRAP -> throw new IllegalStateException("joint Book basis cannot enter a Plan outcome");
        };
        var saved = planOutcomes.savePathOutcome(root.ownerId(ctx), plan, body.expectedVersion(),
                candidate.path("id").asText(), stored, result, input, interpretation);
        ctx.json(new ApiResponses.PlanOutcomeWithEnsemble<>(plan, saved,
                new ApiResponses.EnsembleRef(stored.id(), stored.fingerprint(), stored.basis(),
                        stored.ensemble().waypointFill().name())));
    }

    /** Compare the Plan's current proposals on one exact stored path artifact. */
    void planOutcomeCompare(Context ctx) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, PlanOutcomeCompareRequest.class));
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan);
        PlanController.requirePlanVersion(plan, body.expectedVersion());
        String basisName = body.basis() == null ? "PARAMETRIC" : body.basis().trim().toUpperCase(Locale.ROOT);
        io.liftandshift.strikebench.sim.PathEnsembleService.Basis basis;
        try { basis = io.liftandshift.strikebench.sim.PathEnsembleService.Basis.valueOf(basisName); }
        catch (Exception e) {
            throw new IllegalArgumentException("comparison basis must be PARAMETRIC, HISTORICAL_ANALOGS, or CONDITIONAL_BOOTSTRAP");
        }
        var stored = resolvePlanEnsemble(ctx, plan,
                new PlanOutcomeRunRequest(body.expectedVersion(), basisName, body.ensembleId(), body.over(), body.iv()), basis);

        java.util.Set<String> wanted = body.candidateIds() == null || body.candidateIds().isEmpty()
                ? null : body.candidateIds().stream().filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        LinkedHashMap<String, ObjectNode> field = new LinkedHashMap<>();
        var competition = planStrategy.latestCompetition(root.ownerId(ctx), plan.id());
        if (competition != null) for (JsonNode node : competition.result().path("candidates")) {
            if (!(node instanceof ObjectNode candidate)) continue;
            String id = candidate.path("id").asText();
            if (!id.isBlank() && (wanted == null || wanted.contains(id))) field.put(id, candidate);
        }
        JsonNode selectedNode = planStrategy.selectedCandidate(root.ownerId(ctx), plan.id());
        if (selectedNode instanceof ObjectNode selected) {
            String id = selected.path("id").asText();
            if (!id.isBlank() && (wanted == null || wanted.contains(id))) field.putIfAbsent(id, selected);
        }
        if (field.isEmpty()) throw new IllegalStateException("Run the Strategy comparison before comparing proposal outcomes.");
        if (field.size() > 32) throw new IllegalArgumentException("at most 32 current Plan proposals can be compared together");

        List<io.liftandshift.strikebench.sim.ScenarioSimulator.CompareItem> simItems = new ArrayList<>();
        LinkedHashMap<String, PlanComparisonMeta> metadata = new LinkedHashMap<>();
        LinkedHashMap<String, String> earlyRefusals = new LinkedHashMap<>();
        for (ObjectNode candidate : field.values()) {
            String id = candidate.path("id").asText();
            int qty = Math.clamp(candidate.path("qty").asInt(1), 1, 100);
            var position = planOutcomePosition(candidate);
            JsonNode evaluation = candidate.path("evaluation");
            JsonNode assessment = evaluation.path("assessment");
            JsonNode economics = assessment.path("economics");
            long fees;
            if (economics.path("estimatedRoundTripFeesCents").isNumber()) {
                fees = economics.path("estimatedRoundTripFeesCents").longValue();
            } else {
                long contracts = position.legs().stream().filter(leg -> !"STOCK".equalsIgnoreCase(leg.type()))
                        .mapToLong(leg -> Math.max(1, leg.ratio())).sum() * qty;
                // THE one fee formula (no option order fee on a stock-only package).
                fees = io.liftandshift.strikebench.util.Fees.roundTripCents(
                        contracts, cfg.feePerContractCents(), cfg.feePerOrderCents());
            }
            metadata.put(id, new PlanComparisonMeta(candidate, position, qty, fees));
            try {
                var pathPosition = outcomeController.toPathPosition(ctx, position.legs(),
                        stored.ensemble().anchorDate());
                simItems.add(new io.liftandshift.strikebench.sim.ScenarioSimulator.CompareItem(
                        id, pathPosition, position.entryCostCents(),
                        "entry fixed to the Plan proposal's captured executable package", fees, qty));
            } catch (RuntimeException e) {
                earlyRefusals.put(id, io.liftandshift.strikebench.sim.ScenarioSimulator.publicReason(e));
            }
        }

        LinkedHashMap<String, io.liftandshift.strikebench.sim.ScenarioSimulator.SimResult> results = new LinkedHashMap<>();
        LinkedHashMap<String, String> refusals = new LinkedHashMap<>(earlyRefusals);
        if (!simItems.isEmpty()) {
            var compared = new io.liftandshift.strikebench.sim.ScenarioSimulator().compare(
                    stored.ensemble(), simItems, 1, stored.iv(), stored.canvas(), stored.rateAnnual());
            for (var outcome : compared.report().results()) results.put(outcome.key(), outcome.result());
            for (var refusal : compared.report().refused()) refusals.put(refusal.key(), refusal.reason());
        }

        List<io.liftandshift.strikebench.plan.PlanOutcomeService.ComparisonItem> items = new ArrayList<>();
        for (var entry : metadata.entrySet()) {
            String id = entry.getKey();
            PlanComparisonMeta meta = entry.getValue();
            ObjectNode candidate = meta.candidate();
            var result = results.get(id);
            Long p5 = result == null ? null : result.p5Cents();
            Long expected = result == null ? null : result.expectedPnlCents();
            Double tailScore = expected == null || p5 == null ? null
                    : expected.doubleValue() / Math.max(100.0, Math.max(0.0, -p5.doubleValue()));
            String display = candidate.path("displayName").asText(candidate.path("strategy").asText("Structure"));
            JsonNode evaluation = candidate.path("evaluation");
            JsonNode assessment = evaluation.path("assessment");
            JsonNode economics = assessment.path("economics");
            JsonNode mechanics = assessment.path("mechanics");
            items.add(new io.liftandshift.strikebench.plan.PlanOutcomeService.ComparisonItem(
                    id, id, 0, candidate.path("strategy").asText("CUSTOM"), display, meta.qty(),
                    meta.position().entryCostCents(), candidate.hasNonNull("maxLossCents")
                            ? candidate.path("maxLossCents").longValue() : null,
                    result == null ? null : result.winRatePct(), expected, p5,
                    result == null ? null : result.p50Cents(), result == null ? null : result.p95Cents(),
                    tailScore, meta.roundTripFees(), economics.path("verdict").asText(null),
                    economics.path("placement").asText(null),
                    mechanics.hasNonNull("eligible") ? mechanics.path("eligible").asBoolean() : null,
                    evaluation.hasNonNull("decisionScore") ? evaluation.path("decisionScore").doubleValue() : null,
                    candidate.path("selected").asBoolean(false), refusals.get(id)));
        }
        items.add(new io.liftandshift.strikebench.plan.PlanOutcomeService.ComparisonItem(
                "CASH", null, 0, "CASH", "Keep cash", 0, 0L, 0L,
                null, 0L, 0L, 0L, 0L, 0.0, 0L, null, "BASELINE", true, null, false, null));
        items.sort((a, b) -> {
            if ((a.refusalReason() != null) != (b.refusalReason() != null)) return a.refusalReason() == null ? -1 : 1;
            double as = a.tailReturnScore() == null ? Double.NEGATIVE_INFINITY : a.tailReturnScore();
            double bs = b.tailReturnScore() == null ? Double.NEGATIVE_INFINITY : b.tailReturnScore();
            int score = Double.compare(bs, as);
            if (score != 0) return score;
            return a.displayName().compareToIgnoreCase(b.displayName());
        });
        List<io.liftandshift.strikebench.plan.PlanOutcomeService.ComparisonItem> ranked = new ArrayList<>();
        int rank = 0;
        for (var item : items) ranked.add(new io.liftandshift.strikebench.plan.PlanOutcomeService.ComparisonItem(
                item.key(), item.candidateId(), ++rank, item.strategy(), item.displayName(), item.qty(),
                item.entryCostCents(), item.maxLossCents(), item.winRatePct(), item.expectedPnlCents(),
                item.p5Cents(), item.p50Cents(), item.p95Cents(), item.tailReturnScore(),
                item.roundTripFeesCents(), item.economicVerdict(), item.economicPlacement(),
                item.mechanicallyEligible(), item.decisionScore(), item.selected(), item.refusalReason()));

        String interpretation = switch (basis) {
            case PARAMETRIC -> "Every current Plan proposal repriced on the exact stored model futures.";
            case HISTORICAL_ANALOGS -> "Every current Plan proposal repriced on the exact matching historical occurrences.";
            case CONDITIONAL_BOOTSTRAP -> "Every current Plan proposal repriced on whole-path resamples of the same analog sample.";
            case JOINT_ALIGNED_BOOTSTRAP -> throw new IllegalStateException("joint Book basis cannot enter a Plan comparison");
        };
        String fairness = "Same ensemble " + stored.fingerprint() + ", captured proposal entries, quantities, and after-cost convention; cash is the zero-risk baseline.";
        var saved = planOutcomes.saveComparison(root.ownerId(ctx), plan, body.expectedVersion(), stored, ranked,
                Json.MAPPER.valueToTree(body), interpretation, fairness);
        ctx.json(new ApiResponses.PlanComparison<>(plan, saved,
                new ApiResponses.EnsembleRef(stored.id(), stored.fingerprint(), stored.basis(),
                        stored.ensemble().waypointFill().name())));
    }

    private record PlanComparisonMeta(ObjectNode candidate,
                                      io.liftandshift.strikebench.outcomes.OutcomeContract.Position position,
                                      int qty, long roundTripFees) {}

    // ---- Authored scenarios (the scenario canvas's save/list/load surface) ----

    public record PlanScenarioSaveRequest(Long expectedVersion, String baseEnsembleId, String title,
                                          io.liftandshift.strikebench.sim.ScenarioSpec over) {}

    /** Freeze the authored path (waypoints + spec) over the stored fan it was drawn on. */
    void planScenarioSave(Context ctx) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, PlanScenarioSaveRequest.class));
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan);
        PlanController.requirePlanVersion(plan, body.expectedVersion());
        if (body.over() == null || body.over().waypoints().isEmpty()) {
            throw new IllegalArgumentException("An authored scenario needs at least one waypoint — "
                    + "click the fan to pin “the price touches this level around this session” first.");
        }
        var spec = planScenarioSpec(plan, body.over());
        String baseId = body.baseEnsembleId();
        if (baseId == null || baseId.isBlank()) {
            var latest = planOutcomes.latestEnsemble(root.ownerId(ctx), plan,
                    io.liftandshift.strikebench.sim.PathEnsembleService.Basis.PARAMETRIC.name(),
                    root.analysisCtx(ctx));
            if (latest == null) {
                throw new IllegalStateException("Run the possible-futures fan first — an authored "
                        + "scenario freezes ON that stored fan, never on thin air.");
            }
            baseId = latest.id();
        }
        var saved = authoredScenarios.save(root.ownerId(ctx), plan, baseId, spec, body.title());
        ctx.status(201).json(new ApiResponses.PlanScenario<>(plan, scenarioView(ctx, plan, saved)));
    }

    /** Every authored scenario this Plan owns; stale-context rows carry an explicit explanation. */
    void planScenariosList(Context ctx) {
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        List<ObjectNode> views = new ArrayList<>();
        for (var authored : authoredScenarios.listAll(root.ownerId(ctx), plan.id())) {
            views.add(scenarioView(ctx, plan, authored));
        }
        ctx.json(new ApiResponses.PlanScenarios<>(plan, views));
    }

    /** One authored scenario with waypoints, fill label, and lineage to its base fan. */
    void planScenarioGet(Context ctx) {
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        var found = authoredScenarios.load(root.ownerId(ctx), plan.id(), ctx.pathParam("scenarioId"));
        ctx.json(new ApiResponses.PlanScenario<>(plan, scenarioView(ctx, plan, found)));
    }

    private ObjectNode scenarioView(Context ctx, io.liftandshift.strikebench.plan.Plan.View plan,
                                    io.liftandshift.strikebench.plan.AuthoredScenarioService.Authored authored) {
        ObjectNode out = Json.MAPPER.createObjectNode();
        out.put("id", authored.id());
        if (authored.title() != null) out.put("title", authored.title());
        out.put("createdAt", authored.createdAt());
        out.put("contextRev", authored.contextRev());
        boolean current = authored.contextRev() == plan.context().rev();
        out.put("currentContext", current);
        if (!current) {
            out.put("staleness", "The Plan assumptions changed after this scenario was authored"
                    + " (horizon, view, or price context moved on). Loading re-applies its waypoints"
                    + " to the current fan; run the scenario again to re-anchor it.");
        }
        out.put("waypointFill", authored.waypointFill());
        out.put("fingerprint", authored.fingerprint());
        out.put("baseEnsembleId", authored.baseEnsembleId());
        String baseFingerprint = planOutcomes.ensembleFingerprint(root.ownerId(ctx), plan.id(),
                authored.baseEnsembleId());
        if (baseFingerprint != null) out.put("baseEnsembleFingerprint", baseFingerprint);
        out.put("waypointCount", authored.spec().waypoints().size());
        var pins = out.putArray("waypoints");
        for (var w : authored.spec().waypoints()) {
            ObjectNode pin = pins.addObject();
            pin.put("dayIndex", w.dayIndex());
            pin.put("priceRatio", w.priceRatio());
            if (w.tolerance() != null) pin.put("tolerance", w.tolerance());
        }
        out.set("spec", Json.MAPPER.valueToTree(authored.spec()));
        out.put("horizonDays", authored.spec().horizonDays());
        var canvas = planOutcomes.canvasSpec(root.ownerId(ctx), plan.id(), authored.baseEnsembleId(),
                authored.spec().horizonDays());
        if (canvas != null) {
            out.set("canvas", Json.MAPPER.valueToTree(canvas));
            ObjectNode receipt = out.putObject("modelReceipt");
            receipt.put("fingerprint", authored.fingerprint());
            receipt.put("baseEnsembleFingerprint", baseFingerprint);
            receipt.put("canvasModelVersion", io.liftandshift.strikebench.sim.ScenarioCanvasSpec.MODEL_VERSION);
            receipt.put("calendar", canvas.calendar());
            receipt.put("surfaceDynamics", canvas.surfaceDynamics().name());
            receipt.put("settlementPolicy", canvas.settlementPolicy().name());
            receipt.put("exercisePolicy", canvas.exercisePolicy().name());
            receipt.put("authoredPathMeaning", "USER_HYPOTHESIS_NOT_FORECAST");
            if (canvas.template() != null) receipt.set("template", Json.MAPPER.valueToTree(canvas.template()));
        }
        return out;
    }

    void planBacktestRun(Context ctx) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, PlanBacktestRequest.class));
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan);
        PlanController.requirePlanVersion(plan, body.expectedVersion());
        ObjectNode candidate = root.selectedCandidate(ctx, plan, true);
        String family = candidate.path("strategy").asText();
        if (family.isBlank() || "CUSTOM".equals(family)) {
            throw new IllegalArgumentException("Historical replay needs a named strategy rule; model futures still test the exact custom package.");
        }
        String engineKind = body.engine() == null ? "single" : body.engine().trim().toLowerCase(Locale.ROOT);
        String world = MarketLane.worldParam(root.activeWorld(ctx));
        Object report;
        if ("portfolio".equals(engineKind)) {
            report = backtester.runPortfolio(new Backtester.PortfolioRequest(plan.symbol(), family, body.from(), body.to(),
                    body.targetDte() == null ? plan.context().horizonDays() : body.targetDte(), body.entryEveryDays(),
                    body.maxConcurrent(), body.qty(), body.shortDelta(), body.widthPct(), body.profitTargetPct(),
                    body.stopFraction(), body.rollDte(), body.startingCashCents()), root.analysisCtx(ctx),
                    root.ownerId(ctx), world);
        } else if ("single".equals(engineKind)) {
            report = backtester.run(new Backtester.BacktestRequest(plan.symbol(), family, body.from(), body.to(),
                    body.targetDte() == null ? plan.context().horizonDays() : body.targetDte(), body.entryEveryDays(),
                    body.qty(), body.slippagePct(), body.startingCashCents()), root.analysisCtx(ctx),
                    root.ownerId(ctx), world);
        } else throw new IllegalArgumentException("engine must be single or portfolio");
        JsonNode reportJson = Json.MAPPER.valueToTree(report);
        var saved = planOutcomes.saveBacktest(root.ownerId(ctx), plan, body.expectedVersion(),
                candidate.path("id").asText(), engineKind, reportJson, Json.MAPPER.valueToTree(body), root.analysisCtx(ctx));
        ctx.json(new ApiResponses.PlanBacktest<>(plan, saved, report));
    }

    void planBacktestGet(Context ctx) {
        String planId = ctx.pathParam("id");
        String backtestId = ctx.pathParam("backtestId");
        planOutcomes.requireBacktest(root.ownerId(ctx), planId, backtestId);
        ctx.json(backtester.get(backtestId));
    }


    private io.liftandshift.strikebench.plan.PlanOutcomeService.StoredEnsemble resolvePlanEnsemble(
            Context ctx, io.liftandshift.strikebench.plan.Plan.View plan, PlanOutcomeRunRequest body,
            io.liftandshift.strikebench.sim.PathEnsembleService.Basis basis) {
        if (body.ensembleId() != null && !body.ensembleId().isBlank()) {
            var stored = planOutcomes.loadCurrentEnsemble(root.ownerId(ctx), plan, body.ensembleId(), root.analysisCtx(ctx));
            if (!basis.name().equals(stored.basis())) throw new IllegalArgumentException("Stored ensemble basis does not match the requested basis");
            return stored;
        }
        var existing = planOutcomes.latestEnsemble(root.ownerId(ctx), plan, basis.name(), root.analysisCtx(ctx));
        if (existing != null && basis == io.liftandshift.strikebench.sim.PathEnsembleService.Basis.PARAMETRIC
                && body.over() == null && body.iv() == null) return existing;
        boolean calibrateFromMarket = requestsMarketVol(body.over());
        var spec = planScenarioSpec(plan, body.over());
        String world = MarketLane.worldParam(root.activeWorld(ctx));
        double spot = pathEnsembles.anchorSpot(new io.liftandshift.strikebench.sim.PathEnsembleService.Scope(
                plan.symbol(), world, root.analysisCtx(ctx)));
        io.liftandshift.strikebench.sim.PathEnsembleService.Ensemble ensemble;
        if (basis == io.liftandshift.strikebench.sim.PathEnsembleService.Basis.PARAMETRIC) {
            var marketVol = outcomeController.marketVol(plan.symbol(), world, spec.horizonDays());
            if (calibrateFromMarket && marketVol != null && marketVol.atmIv() > 0) {
                spec = spec.withVol(marketVol.atmIv()).sane();
            }
            ensemble = pathEnsembles.build(new io.liftandshift.strikebench.sim.PathEnsembleService.Scope(
                    plan.symbol(), world, root.analysisCtx(ctx)), basis, spec, null, spot);
        } else {
            var evidence = planEvidence.latest(root.ownerId(ctx), plan.id(), root.analysisCtx(ctx));
            if (evidence == null) throw new IllegalStateException("Run Past evidence in this Plan before using historical analog outcomes.");
            ensemble = pathEnsembles.fromStudy(new io.liftandshift.strikebench.sim.PathEnsembleService.Scope(
                    plan.symbol(), world, root.analysisCtx(ctx)), basis, spec, evidence.result(), spot);
        }
        double rate = market.riskFreeRateQuote(Math.max(1, ensemble.spec().horizonDays()), world).annualRate();
        var marketVol = outcomeController.marketVol(plan.symbol(), world, ensemble.spec().horizonDays());
        var iv = body.iv() == null ? defaultPlanIv(ensemble.spec(), marketVol) : body.iv();
        return planOutcomes.saveEnsemble(root.ownerId(ctx), plan, ensemble, iv, rate, null, Json.MAPPER.valueToTree(body));
    }

    private static io.liftandshift.strikebench.outcomes.OutcomeContract.Position planOutcomePosition(JsonNode candidate) {
        List<io.liftandshift.strikebench.outcomes.OutcomeContract.Leg> legs = new ArrayList<>();
        for (JsonNode leg : candidate.path("legs")) {
            String type = leg.path("type").asText();
            legs.add(new io.liftandshift.strikebench.outcomes.OutcomeContract.Leg(
                    leg.path("action").asText(), type,
                    "STOCK".equalsIgnoreCase(type) ? BigDecimal.ZERO : new BigDecimal(leg.path("strike").asText()),
                    leg.path("expiration").asText(null), null, leg.path("ratio").asInt(),
                    leg.path("multiplier").asInt()));
        }
        Long entryNet = candidate.hasNonNull("entryNetPremiumCents") ? candidate.path("entryNetPremiumCents").longValue() : null;
        return new io.liftandshift.strikebench.outcomes.OutcomeContract.Position(candidate.path("id").asText(), legs,
                candidate.path("qty").asInt(), entryNet == null ? null : -entryNet);
    }

    private io.liftandshift.strikebench.outcomes.OutcomeContract.MarketContext planOutcomeContext(
            Context ctx, io.liftandshift.strikebench.plan.Plan.View plan) {
        var analysis = root.analysisCtx(ctx);
        return new io.liftandshift.strikebench.outcomes.OutcomeContract.MarketContext(plan.symbol(),
                root.activePlanMarket(ctx).name(), root.activeWorld(ctx), analysis.datasetId(), null);
    }

    private static io.liftandshift.strikebench.sim.ScenarioSpec planScenarioSpec(
            io.liftandshift.strikebench.plan.Plan.View plan,
            io.liftandshift.strikebench.sim.ScenarioSpec raw) {
        int days = plan.context().horizonDays() == null
                ? io.liftandshift.strikebench.model.Horizon.MONTH.tradingSessions()
                : plan.context().horizonDays();
        var base = raw == null
                ? io.liftandshift.strikebench.sim.ScenarioSpec.preset(
                    io.liftandshift.strikebench.sim.ScenarioSpec.Shape.CHOP, days, 0, 4242L, 500)
                    .withStepsPerDay(defaultPlanStepsPerDay(days))
                : raw;
        // The canonical constructor carries authored waypoints through to generation and
        // validates each pin against the Plan-owned horizon with a units-bearing message.
        return new io.liftandshift.strikebench.sim.ScenarioSpec(base.model(), base.shape(), days,
                base.stepsPerDay(), base.driftAnnual(), base.volAnnual(), base.jumpsPerYear(),
                base.jumpMean(), base.jumpVol(), base.tailNu(), base.heston(), base.seed(), base.paths(),
                base.waypoints()).sane();
    }

    /** Null/nonpositive volatility means “calibrate from the active option market,” before sane() applies its fallback. */
    private static boolean requestsMarketVol(io.liftandshift.strikebench.sim.ScenarioSpec raw) {
        return raw == null || raw.volAnnual() <= 0;
    }

    /** Short decision horizons need a genuine stochastic journey, not only open/end points. */
    private static int defaultPlanStepsPerDay(int horizonDays) {
        if (horizonDays <= 2) return 12;
        if (horizonDays <= 7) return 6;
        if (horizonDays <= 45) return 3;
        return 1;
    }

    /**
     * Scenario-canvas facts on every fan payload: the waypoint-fill honesty label (derived from
     * the spec so it can never disagree with the paths) and the REAL NYSE session dates each
     * trading-day step lands on — the studio shows "session 12 — Tue Aug 4", never a bare index.
     */
    private static void decorateScenarioCanvas(ObjectNode preview,
            io.liftandshift.strikebench.sim.PathEnsembleService.Ensemble ensemble) {
        preview.put("waypointFill", ensemble.waypointFill().name());
        var pins = preview.putArray("waypoints");
        for (var w : ensemble.spec().waypoints()) {
            ObjectNode pin = pins.addObject();
            pin.put("dayIndex", w.dayIndex());
            pin.put("priceRatio", w.priceRatio());
            if (w.tolerance() != null) pin.put("tolerance", w.tolerance());
        }
        java.time.LocalDate anchor = ensemble.anchorDate();
        var sessions = preview.putArray("sessionDates");
        for (java.time.LocalDate d : io.liftandshift.strikebench.sim.ScenarioSpec.sessionDates(
                anchor, ensemble.spec().horizonDays())) {
            sessions.add(d.toString());
        }
        preview.put("anchorSessionDate", anchor.toString());
    }

    /**
     * One same-symbol canvas: current Practice/Tracked packages, selected proposal, and stock
     * baseline all consume the exact stored ensemble. Refused packages stay named instead of
     * disappearing or contaminating the rest of the comparison.
     */
    private void decorateCanvasValuation(Context ctx, ObjectNode preview,
            io.liftandshift.strikebench.plan.Plan.View plan,
            io.liftandshift.strikebench.plan.PlanOutcomeService.StoredEnsemble stored) {
        var projection = pathEnsembles.displayPaths(stored.ensemble(), null, 48);
        decorateCanvasValuation(ctx, preview, plan, stored, null, stored.iv(), stored.canvas(), null,
                canvasDisplaySelections(projection), projection.selection());
    }

    private ObjectNode decorateCanvasValuation(Context ctx, ObjectNode preview,
            io.liftandshift.strikebench.plan.Plan.View plan,
            io.liftandshift.strikebench.plan.PlanOutcomeService.StoredEnsemble stored,
            Integer focusSourcePathIndex,
            io.liftandshift.strikebench.sim.IvSpec valuationIv,
            io.liftandshift.strikebench.sim.ScenarioCanvasSpec valuationCanvas,
            String focusPositionKey,
            List<io.liftandshift.strikebench.sim.ScenarioCanvasValuator.DisplayPathSelection>
                    displayPathSelections,
            String displayPathRule) {
        var canvas = valuationCanvas == null
                ? io.liftandshift.strikebench.sim.ScenarioCanvasSpec.defaults()
                : valuationCanvas.sane(stored.ensemble().spec().horizonDays());
        var iv = valuationIv == null ? stored.iv() : valuationIv.sane();
        List<io.liftandshift.strikebench.sim.ScenarioCanvasValuator.PositionInput> inputs = new ArrayList<>();
        var refused = Json.MAPPER.createArrayNode();
        java.time.LocalDate anchor = stored.ensemble().anchorDate();
        io.liftandshift.strikebench.position.ScenarioPositionScopeService.Scoped focusedScoped = null;
        boolean exactBookFocus = focusPositionKey != null
                && !focusPositionKey.startsWith("PROPOSED:")
                && !focusPositionKey.equals("STOCK:" + plan.symbol());
        if (exactBookFocus) {
            String activePlanTradeId = planManagement.activeTradeId(root.ownerId(ctx), plan.id());
            focusedScoped = canvasPositions.focused(root.ownerId(ctx), plan.accountId(),
                    plan.symbol(), anchor, focusPositionKey, activePlanTradeId);
            addCanvasPosition(inputs, refused, focusedScoped, anchor);
        } else if (focusPositionKey == null) {
            try {
                for (var scoped : canvasPositions.list(root.ownerId(ctx), root.currentAccount(ctx).id(),
                        plan.symbol(), anchor)) {
                    addCanvasPosition(inputs, refused, scoped, anchor);
                }
            } catch (RuntimeException e) {
                ObjectNode row = refused.addObject(); row.put("key", "POSITION_SCOPE");
                row.put("label", "Same-symbol Book positions"); row.put("reason", e.getMessage());
            }
        }
        ObjectNode candidate = focusPositionKey == null || focusPositionKey.startsWith("PROPOSED:")
                ? root.selectedCandidate(ctx, plan, false) : null;
        if (candidate != null) {
            String candidateKey = "PROPOSED:" + candidate.path("id").asText();
            if (focusPositionKey != null && !focusPositionKey.equals(candidateKey)) candidate = null;
        }
        if (candidate != null) {
            try {
                var position = planOutcomePosition(candidate);
                inputs.add(new io.liftandshift.strikebench.sim.ScenarioCanvasValuator.PositionInput(
                        "PROPOSED:" + candidate.path("id").asText(),
                        candidate.path("displayName").asText(candidate.path("strategy").asText("Proposed structure")),
                        "HYPOTHETICAL", "PLAN_PROPOSAL", outcomeController.toPathPosition(
                                ctx, position.legs(), anchor),
                        position.qty(), position.entryCostCents(), true));
            } catch (RuntimeException e) {
                ObjectNode row = refused.addObject();
                row.put("key", "PROPOSED:" + candidate.path("id").asText());
                row.put("label", "Selected Plan proposal");
                row.put("reason", io.liftandshift.strikebench.sim.ScenarioSimulator.publicReason(e));
            }
        }
        String stockKey = "STOCK:" + plan.symbol();
        if (focusPositionKey == null || focusPositionKey.equals(stockKey)) {
            try {
                int shares = 100;
                var stock = new io.liftandshift.strikebench.sim.PathPosition(anchor, List.of(
                        io.liftandshift.strikebench.model.Leg.stockShares(
                                io.liftandshift.strikebench.model.LegAction.BUY, shares,
                                BigDecimal.valueOf(stored.ensemble().spot()))));
                inputs.add(new io.liftandshift.strikebench.sim.ScenarioCanvasValuator.PositionInput(
                        stockKey, "Buy and hold 100 shares", "BASELINE", "STOCK_BASELINE",
                        stock, 1, Math.round(stored.ensemble().spot() * shares * 100), false));
            } catch (RuntimeException e) {
                ObjectNode row = refused.addObject(); row.put("key", stockKey);
                row.put("label", "Buy and hold"); row.put("reason", e.getMessage());
            }
        }
        if (focusPositionKey != null && inputs.isEmpty()) {
            JsonNode matchingRefusal = null;
            for (JsonNode row : refused) {
                if (focusPositionKey.equals(row.path("key").asText())) {
                    matchingRefusal = row;
                    break;
                }
            }
            if (matchingRefusal != null) {
                throw new IllegalStateException("The focused position '" + focusPositionKey
                        + "' is in the Scenario Canvas scope but could not be repriced: "
                        + matchingRefusal.path("reason").asText("valuation unavailable"));
            }
            throw new IllegalArgumentException("focusPositionKey '" + focusPositionKey
                    + "' does not name a same-symbol position in the current Scenario Canvas scope");
        }
        ObjectNode canvasJson;
        try {
            var report = focusSourcePathIndex == null
                    ? canvasValuator.value(stored.ensemble(), iv, canvas,
                        stored.rateAnnual(), inputs, displayPathSelections)
                    : canvasValuator.value(stored.ensemble(), iv, canvas,
                        stored.rateAnnual(), inputs, focusSourcePathIndex, displayPathSelections);
            canvasJson = Json.MAPPER.valueToTree(report);
        } catch (IllegalArgumentException | IllegalStateException e) {
            canvasJson = Json.MAPPER.createObjectNode();
            canvasJson.putArray("underlying");
            canvasJson.putArray("underlyingSteps");
            canvasJson.putArray("positions");
            canvasJson.putArray("comparison");
            canvasJson.putArray("notes").add("The stored fan remains available, but its same-symbol "
                    + "position comparison could not run: "
                    + io.liftandshift.strikebench.sim.ScenarioSimulator.publicReason(e));
            ObjectNode row = refused.addObject();
            row.put("key", "CANVAS_VALUATION");
            row.put("label", "Same-symbol position comparison");
            row.put("reason", io.liftandshift.strikebench.sim.ScenarioSimulator.publicReason(e));
        }
        canvasJson.put("displayPathRule", displayPathRule == null ? "TERMINAL_QUANTILES" : displayPathRule);
        canvasJson.put("displayPathCount", displayPathSelections == null ? 0 : displayPathSelections.size());
        canvasJson.set("displayPathSourceIndices", Json.MAPPER.valueToTree(
                displayPathSelections == null ? List.of() : displayPathSelections.stream()
                        .map(io.liftandshift.strikebench.sim.ScenarioCanvasValuator.DisplayPathSelection::sourcePathIndex)
                        .toList()));
        canvasJson.set("refused", refused);
        io.liftandshift.strikebench.position.PositionPackageFingerprint.FocusedIdentity
                focusedPackageIdentity = focusedScoped == null
                ? null : focusedPackageIdentity(focusedScoped);
        String focusedPackageFingerprint = focusedPackageIdentity == null
                ? null : io.liftandshift.strikebench.position.PositionPackageFingerprint
                    .fingerprint(focusedPackageIdentity);
        ApiResponses.FocusedPackageProvenance focusedPackageProvenance = focusedScoped == null
                ? null : focusedPackageProvenance(focusedScoped);
        ObjectNode receipt = canvasJson.putObject("modelReceipt");
        receipt.put("fingerprint", stored.fingerprint());
        receipt.put("ensembleFingerprint", stored.fingerprint());
        receipt.put("canvasModelVersion", io.liftandshift.strikebench.sim.ScenarioCanvasSpec.MODEL_VERSION);
        receipt.put("pathModelVersion", stored.ensemble().modelVersion());
        receipt.put("calendar", canvas.calendar());
        receipt.put("rateAnnual", stored.rateAnnual());
        if (canvas.dividendYieldAnnual() == null) receipt.putNull("dividendYieldAnnual");
        else receipt.put("dividendYieldAnnual", canvas.dividendYieldAnnual());
        receipt.put("dividendBasis", canvas.dividendBasis());
        receipt.put("skewVolPerLogMoneyness", canvas.skewVolPerLogMoneyness());
        receipt.put("termVolPerSqrtYear", canvas.termVolPerSqrtYear());
        receipt.put("surfaceDynamics", canvas.surfaceDynamics().name());
        receipt.put("settlementPolicy", canvas.settlementPolicy().name());
        receipt.put("exercisePolicy", canvas.exercisePolicy().name());
        receipt.set("ivAssumptions", Json.MAPPER.valueToTree(iv));
        receipt.set("ivNodes", Json.MAPPER.valueToTree(canvas.ivNodes()));
        if (canvas.template() != null) receipt.set("template", Json.MAPPER.valueToTree(canvas.template()));
        receipt.put("anchorDate", anchor.toString());
        receipt.put("authoredPathMeaning", "USER_HYPOTHESIS_NOT_FORECAST");
        receipt.put("positionScopeCount", canvasJson.path("positions").size());
        receipt.put("positionScopeAttempted", inputs.size());
        receipt.put("displayPathRule", displayPathRule == null ? "TERMINAL_QUANTILES" : displayPathRule);
        receipt.put("displayPathCount", displayPathSelections == null ? 0 : displayPathSelections.size());
        int actualFocusPath = canvasJson.path("focusSourcePathIndex").asInt(
                focusSourcePathIndex == null ? -1 : focusSourcePathIndex);
        receipt.put("focusSourcePathIndex", actualFocusPath);
        if (focusPositionKey != null) receipt.put("focusPositionKey", focusPositionKey);
        if (focusedPackageFingerprint != null) {
            receipt.put("focusedPackageFingerprint", focusedPackageFingerprint);
            receipt.set("focusedPackageProvenance",
                    Json.MAPPER.valueToTree(focusedPackageProvenance));
        }
        String selectedCandidateId = candidate == null ? null : candidate.path("id").asText(null);
        if (selectedCandidateId != null) {
            receipt.put("selectedCandidateId", selectedCandidateId);
            receipt.put("selectedCandidateFingerprint", sha256(candidate));
        }
        ObjectNode valuationIdentity = Json.MAPPER.createObjectNode();
        valuationIdentity.put("contractVersion", "scenario-animation-valuation-1");
        valuationIdentity.put("ensembleFingerprint", stored.fingerprint());
        valuationIdentity.put("canvasModelVersion",
                io.liftandshift.strikebench.sim.ScenarioCanvasSpec.MODEL_VERSION);
        valuationIdentity.put("pathModelVersion", stored.ensemble().modelVersion());
        valuationIdentity.put("focusSourcePathIndex", actualFocusPath);
        valuationIdentity.put("rateAnnual", stored.rateAnnual());
        valuationIdentity.set("ivAssumptions", Json.MAPPER.valueToTree(iv));
        valuationIdentity.set("canvasAssumptions", Json.MAPPER.valueToTree(canvas));
        valuationIdentity.set("positionPackages", Json.MAPPER.valueToTree(inputs));
        valuationIdentity.put("displayPathRule",
                displayPathRule == null ? "TERMINAL_QUANTILES" : displayPathRule);
        valuationIdentity.set("displayPathSelections", Json.MAPPER.valueToTree(
                displayPathSelections == null ? List.of() : displayPathSelections));
        if (focusPositionKey != null) valuationIdentity.put("focusPositionKey", focusPositionKey);
        if (focusedPackageIdentity != null) {
            valuationIdentity.set("focusedPackage", Json.MAPPER.valueToTree(focusedPackageIdentity));
        }
        if (selectedCandidateId != null) {
            valuationIdentity.put("selectedCandidateId", selectedCandidateId);
            valuationIdentity.put("selectedCandidateFingerprint", sha256(candidate));
        }
        receipt.put("valuationContractVersion", "scenario-animation-valuation-1");
        receipt.put("valuationFingerprint", sha256(valuationIdentity));
        preview.set("canvas", canvasJson);
        preview.set("canvasModel", Json.MAPPER.valueToTree(canvas));
        return canvasJson;
    }

    private static List<io.liftandshift.strikebench.sim.ScenarioCanvasValuator.DisplayPathSelection>
            canvasDisplaySelections(
                    io.liftandshift.strikebench.sim.PathEnsembleService.DisplayProjection projection) {
        if (projection == null || projection.paths().isEmpty()) return List.of();
        return projection.paths().stream()
                .map(path -> new io.liftandshift.strikebench.sim.ScenarioCanvasValuator.DisplayPathSelection(
                        path.sourcePathIndex(), path.role()))
                .toList();
    }

    private static void addCanvasPosition(
            List<io.liftandshift.strikebench.sim.ScenarioCanvasValuator.PositionInput> inputs,
            com.fasterxml.jackson.databind.node.ArrayNode refused,
            io.liftandshift.strikebench.position.ScenarioPositionScopeService.Scoped scoped,
            java.time.LocalDate anchor) {
        try {
            var path = pathPosition(scoped.packageView(), anchor);
            inputs.add(new io.liftandshift.strikebench.sim.ScenarioCanvasValuator.PositionInput(
                    scoped.packageView().id(), scoped.label() + " · " + scoped.accountName(),
                    scoped.packageView().lane().name(), scoped.packageView().source().name(),
                    path, 1, scoped.entryCostCents(), false));
        } catch (RuntimeException e) {
            ObjectNode row = refused.addObject();
            row.put("key", scoped.packageView().id());
            row.put("label", scoped.label());
            row.put("reason", io.liftandshift.strikebench.sim.ScenarioSimulator.publicReason(e));
        }
    }

    private static io.liftandshift.strikebench.position.PositionPackageFingerprint.FocusedIdentity
            focusedPackageIdentity(
            io.liftandshift.strikebench.position.ScenarioPositionScopeService.Scoped scoped) {
        return io.liftandshift.strikebench.position.PositionPackageFingerprint.focusedIdentity(
                scoped.packageView(), scoped.entryCostCents(), scoped.entryProvenance());
    }

    private static ApiResponses.FocusedPackageProvenance focusedPackageProvenance(
            io.liftandshift.strikebench.position.ScenarioPositionScopeService.Scoped scoped) {
        var p = scoped.packageView();
        List<String> authorities = p.legs().stream()
                .map(io.liftandshift.strikebench.position.PositionPackage.Leg::priceAuthority)
                .filter(java.util.Objects::nonNull)
                .map(Enum::name)
                .distinct()
                .sorted()
                .toList();
        var entry = scoped.entryProvenance();
        return new ApiResponses.FocusedPackageProvenance(
                io.liftandshift.strikebench.position.PositionPackageFingerprint.CONTRACT_VERSION,
                p.id(), p.source().name(), p.lane().name(),
                p.symbol(), p.packageQuantity(), p.legs().size(), p.exactPackageCashCents(),
                scoped.entryCostCents(), p.asOf().toString(),
                entry == null ? null : entry.createdAt(),
                entry == null ? null : entry.dataProvenance(),
                entry == null ? null : entry.dataAge(),
                entry == null ? null : entry.dataSource(),
                entry == null ? null : entry.entrySnapshotFingerprint(), authorities,
                entry == null ? null : entry.sourceIdentity());
    }

    private static String sha256(JsonNode node) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(Json.canonical(node).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static io.liftandshift.strikebench.sim.PathPosition pathPosition(
            io.liftandshift.strikebench.position.PositionPackage packageView,
            java.time.LocalDate anchor) {
        List<io.liftandshift.strikebench.model.Leg> legs = new ArrayList<>();
        for (var leg : packageView.legs()) {
            long rawQty = leg.quantity();
            if (rawQty > 100_000) throw new IllegalArgumentException("position leg quantity exceeds 100,000 units");
            var action = io.liftandshift.strikebench.model.LegAction.valueOf(leg.action().toUpperCase(Locale.ROOT));
            BigDecimal price = leg.price() == null ? BigDecimal.ZERO : leg.price();
            if ("STOCK".equalsIgnoreCase(leg.instrumentType())) {
                legs.add(new io.liftandshift.strikebench.model.Leg(action, null, null, null,
                        Math.toIntExact(rawQty), price, leg.multiplier()));
            } else {
                legs.add(new io.liftandshift.strikebench.model.Leg(action,
                        io.liftandshift.strikebench.model.OptionType.valueOf(leg.optionType()),
                        leg.strike(), leg.expiration(), Math.toIntExact(rawQty), price, leg.multiplier()));
            }
        }
        return new io.liftandshift.strikebench.sim.PathPosition(anchor, legs);
    }

    private static io.liftandshift.strikebench.sim.IvSpec defaultPlanIv(
            io.liftandshift.strikebench.sim.ScenarioSpec spec,
            io.liftandshift.strikebench.sim.SimulationEngine.MarketVolInput marketVol) {
        double anchor = marketVol != null && marketVol.atmIv() > 0 ? marketVol.atmIv() : spec.sane().volAnnual();
        return spec.sane().shape() == io.liftandshift.strikebench.sim.ScenarioSpec.Shape.EVENT_JUMP
                ? io.liftandshift.strikebench.sim.IvSpec.eventCrushAround(anchor,
                    Math.max(1, Math.round(spec.sane().horizonDays() / 3.0f)))
                : io.liftandshift.strikebench.sim.IvSpec.flat(anchor);
    }

}
