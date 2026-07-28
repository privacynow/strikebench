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
import io.liftandshift.strikebench.recommend.DecisionDeclarationPolicy;
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
                                           io.liftandshift.strikebench.sim.ScenarioCanvasTemplateService.Interaction
                                                   interaction,
                                           Integer limit,
                                           String focusPositionKey) {}
    public record PlanBacktestRequest(Long expectedVersion, String engine, String from, String to,
                                      Integer targetDte, Integer entryEveryDays, Integer maxConcurrent,
                                      Integer qty, Double slippagePct, Long startingCashCents,
                                      Double shortDelta, Double widthPct, Double takeProfitFraction,
                                      Double stopMultiple, Integer timeRuleSessions) {}
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
        var interaction = body == null ? null : body.interaction();
        if (!inlineWaypoints.isEmpty() && !inlinePathWaypoints.isEmpty()) {
            throw new IllegalArgumentException(
                    "waypoints and pathWaypoints are alternative conditioning sources; provide exactly one");
        }
        if (scenarioId != null && !scenarioId.isBlank()
                && (!inlineWaypoints.isEmpty() || !inlinePathWaypoints.isEmpty())) {
            throw new IllegalArgumentException("scenarioId and inline waypoints are alternative scenario sources");
        }
        if (interaction != null && (scenarioId != null && !scenarioId.isBlank()
                || !inlineWaypoints.isEmpty() || !inlinePathWaypoints.isEmpty())) {
            throw new IllegalArgumentException(
                    "interaction, scenarioId, and inline waypoints are alternative scenario sources");
        }
        if (interaction != null && body.canvas() != null) {
            throw new IllegalArgumentException(
                    "interaction resolves its canvas on the server; canvas cannot also be supplied");
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
        String focusPositionKey = body == null ? null
                : normalizeFocusPositionKey(body.focusPositionKey());
        ObjectNode selected = null;
        var projectionStored = stored;
        ApiResponses.QuoteView interactionAnchorQuote = null;
        Long interactionAnchorSpotCents = null;
        io.liftandshift.strikebench.sim.ScenarioCanvasTemplateService.ResolvedInteraction
                resolvedInteraction = null;
        Integer exactSourcePathIndex = null;
        Long interactionTargetSpotCents = null;
        boolean exactHeldPosition = focusPositionKey != null
                && !focusPositionKey.startsWith("PROPOSED:")
                && !focusPositionKey.equals("STOCK:" + plan.symbol());
        /*
         * A focused held package always uses one projection basis, at rest and after a story/path
         * click.  Replaying the stored return paths from the latest usable underlying observation
         * preserves the immutable source ensemble while preventing the fan from jumping back to
         * its entry-date spot until the first interaction.  If no underlying observation exists,
         * the recorded fan remains usable and its projection receipt says STORED_ENSEMBLE.
         */
        if (typedAnimationRequest && exactHeldPosition) {
            String world = MarketLane.worldParam(stored.ensemble().scope().worldId());
            var currentQuote = market.quote(plan.symbol(), world).orElse(null);
            if (currentQuote != null && currentQuote.mark() != null) {
                interactionAnchorQuote = ApiResponses.QuoteView.of(currentQuote, false);
                interactionAnchorSpotCents = Math.round(
                        currentQuote.mark().doubleValue() * 100.0);
                String activePlanTradeId = planManagement.activeTradeId(
                        root.ownerId(ctx), plan.id());
                var focused = canvasPositions.focused(root.ownerId(ctx), plan.accountId(),
                        plan.symbol(), stored.ensemble().anchorDate(), focusPositionKey,
                        activePlanTradeId);
                java.time.LocalDate currentDate = java.time.LocalDate.ofInstant(
                        java.time.Instant.ofEpochMilli(currentQuote.asOfEpochMs()),
                        io.liftandshift.strikebench.market.MarketHours.EASTERN);
                java.time.LocalDate finalExpiration = focused.packageView().legs().stream()
                        .map(io.liftandshift.strikebench.position.PositionPackage.Leg::expiration)
                        .filter(java.util.Objects::nonNull)
                        .max(java.time.LocalDate::compareTo)
                        .orElse(null);
                int remainingSessions = finalExpiration == null
                        ? stored.ensemble().spec().horizonDays()
                        : io.liftandshift.strikebench.market.MarketHours
                            .tradingDaysBetween(currentDate, finalExpiration);
                if (remainingSessions < 1) {
                    throw new IllegalStateException("This package has no remaining trading session "
                            + "to project before its final expiration.");
                }
                var projectionEnsemble = pathEnsembles.reanchoredProjection(
                        stored.ensemble(), currentQuote.mark().doubleValue(),
                        currentDate, remainingSessions);
                projectionStored = new io.liftandshift.strikebench.plan.PlanOutcomeService.StoredEnsemble(
                        stored.id(), stored.fingerprint(), stored.basis(), stored.contextRev(),
                        stored.datasetId(), stored.state(), projectionEnsemble, stored.iv(),
                        stored.canvas(), stored.rateAnnual(), stored.stepSeconds(),
                        currentQuote.source(), currentQuote.markFreshness().name(),
                        java.time.Instant.ofEpochMilli(currentQuote.asOfEpochMs()).toString());
            }
        }
        if (interaction != null) {
            var effectiveInteraction = interaction;
            if (interaction.sourcePathIndex() == null) {
                Double declaredMovePct = interaction.movePct();
                if (interactionAnchorSpotCents == null) {
                    interactionAnchorSpotCents = Math.round(
                            projectionStored.ensemble().spot() * 100.0);
                }
                if (declaredMovePct == null && exactHeldPosition) {
                    declaredMovePct = io.liftandshift.strikebench.sim.ScenarioCanvasTemplateService
                            .storyPolicy(interaction.story(),
                                    projectionStored.ensemble().spec().horizonDays()).movePct();
                }
                if (exactHeldPosition && declaredMovePct != null) {
                    double targetDollars = interactionAnchorSpotCents / 100.0
                            * (1.0 + declaredMovePct / 100.0);
                    interactionTargetSpotCents = Math.round(targetDollars * 100.0);
                }
                Integer elapsedSessions = interaction.elapsedSessions();
                /*
                 * A named tile is an expiration payoff checkpoint. Its first click therefore
                 * conditions the stored path at this exact package's terminal boundary, not at
                 * the story catalog's short teaching cadence (Flat previously landed at session
                 * 7 and then wandered to +20% before expiration). An explicit time edit remains
                 * an explicit intermediate hypothesis.
                 */
                if (elapsedSessions == null) {
                    if (selected == null && focusPositionKey == null) {
                        selected = root.selectedCandidate(ctx, plan, true);
                    }
                    elapsedSessions = interactionBoundarySessions(ctx, plan, projectionStored,
                            selected, focusPositionKey);
                }
                effectiveInteraction =
                        new io.liftandshift.strikebench.sim.ScenarioCanvasTemplateService.Interaction(
                                interaction.story(), declaredMovePct, interaction.ivShiftPoints(),
                                elapsedSessions, interaction.sourcePathIndex());
            }
            resolvedInteraction =
                    io.liftandshift.strikebench.sim.ScenarioCanvasTemplateService.resolveInteraction(
                            projectionStored.ensemble(), projectionStored.ensemble().spec(),
                            body.iv() == null ? stored.iv()
                                    : body.iv().validated(
                                            projectionStored.ensemble().spec().horizonDays()),
                            projectionStored.canvas(), effectiveInteraction);
            scenarioSpec = resolvedInteraction.scenario();
            inlinePathWaypoints = resolvedInteraction.pathWaypoints();
            exactSourcePathIndex = resolvedInteraction.sourcePathIndex();
        }
        ApiResponses.ScenarioProjectionReceipt scenarioProjection =
                scenarioProjection(stored, projectionStored, interactionAnchorQuote);
        if (!inlineWaypoints.isEmpty()) {
            scenarioSpec = projectionStored.ensemble().spec().withWaypoints(inlineWaypoints).sane();
        }
        if (!inlinePathWaypoints.isEmpty()
                && inlinePathWaypoints.getLast().sessionProgress()
                    > projectionStored.ensemble().spec().horizonDays()) {
            throw new IllegalArgumentException("The final path waypoint lies beyond the stored ensemble horizon.");
        }
        if (typedAnimationRequest
                && !java.util.Objects.equals(root.activeWorld(ctx), stored.ensemble().scope().worldId())) {
            throw new IllegalStateException("This path set belongs to another market world. Open its market before animating it.");
        }
        var projection = exactSourcePathIndex != null
                ? pathEnsembles.displayPathsFocusedOnSource(
                        projectionStored.ensemble(), exactSourcePathIndex, limit, null)
                : inlinePathWaypoints.isEmpty()
                    ? pathEnsembles.displayPaths(projectionStored.ensemble(), scenarioSpec, limit)
                    : pathEnsembles.displayPathsAtProgress(
                            projectionStored.ensemble(), inlinePathWaypoints, limit);
        var ensembleRef = new ApiResponses.EnsembleRef(stored.id(), stored.fingerprint(), stored.basis(),
                stored.ensemble().waypointFill().name());
        if (!typedAnimationRequest) {
            ctx.json(new ApiResponses.PlanScenarioPaths<>(plan, ensembleRef, scenarioRef, projection));
            return;
        }

        if (selected == null && focusPositionKey == null) {
            selected = root.selectedCandidate(ctx, plan, true);
        }
        int focusSourcePathIndex = projection.receipt().focusSourcePathIndex();
        var displayPathSelections = canvasDisplaySelections(projection);
        var effectiveIv = body.iv() == null ? stored.iv()
                : body.iv().validated(projectionStored.ensemble().spec().horizonDays());
        var effectiveCanvas = (resolvedInteraction != null
                ? resolvedInteraction.canvas()
                : body.canvas() == null
                ? stored.canvas() == null
                    ? io.liftandshift.strikebench.sim.ScenarioCanvasSpec.defaults() : stored.canvas()
                : body.canvas()).sane(projectionStored.ensemble().spec().horizonDays());
        ObjectNode checkpointHolder = Json.MAPPER.createObjectNode();
        ObjectNode checkpoints = decorateCanvasValuation(ctx, checkpointHolder, plan, projectionStored,
                focusSourcePathIndex, effectiveIv, effectiveCanvas, focusPositionKey,
                displayPathSelections, projection.selection(), scenarioProjection);
        decorateInteractionAnchor(checkpoints, interactionAnchorSpotCents);
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
        int[] sharedDisplaySteps = canvasDisplaySteps(
                checkpoints, projectionStored.ensemble().spec().totalSteps());
        var alignedProjection = exactSourcePathIndex != null
                ? pathEnsembles.displayPathsFocusedOnSource(
                        projectionStored.ensemble(), exactSourcePathIndex, limit, sharedDisplaySteps)
                : inlinePathWaypoints.isEmpty()
                    ? pathEnsembles.displayPaths(
                            projectionStored.ensemble(), scenarioSpec, limit, sharedDisplaySteps)
                    : pathEnsembles.displayPathsAtProgress(
                        projectionStored.ensemble(), inlinePathWaypoints, limit, sharedDisplaySteps);
        requireSameDisplaySelection(projection, alignedProjection);
        projection = alignedProjection;
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
                ApiResponses.SCENARIO_ANIMATION_CONTRACT_VERSION,
                stored.id(), stored.fingerprint(), stored.basis(),
                stored.ensemble().modelVersion(), stored.ensemble().scope().symbol(),
                stored.ensemble().scope().worldId(), stored.ensemble().scope().analysis().datasetId(),
                stored.contextRev(), stored.state(), projectionStored.ensemble().spot(),
                projectionStored.ensemble().anchorDate().toString(),
                projectionStored.anchorSource(), projectionStored.anchorFreshness(),
                projectionStored.asOf(), stored.stepSeconds(), stored.ensemble().paths().length,
                stored.ensemble().spec().totalSteps(), stored.ensemble().waypointFill().name(),
                stored.ensemble().spec(), scenarioSpec,
                inlinePathWaypoints.isEmpty()
                        ? scenarioSpec == null ? List.of() : scenarioSpec.waypoints().stream()
                            .map(pin -> new io.liftandshift.strikebench.sim.PathEnsembleService.DisplayWaypoint(
                                    pin.dayIndex(), pin.priceRatio(), pin.tolerance()))
                            .toList()
                        : inlinePathWaypoints,
                effectiveIv, effectiveCanvas, stored.rateAnnual(),
                interaction,
                scenarioProjection,
                interactionTargetSpotCents,
                resolvedInteraction == null ? null : resolvedInteraction.declaration(),
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

    private static ApiResponses.ScenarioProjectionReceipt scenarioProjection(
            io.liftandshift.strikebench.plan.PlanOutcomeService.StoredEnsemble source,
            io.liftandshift.strikebench.plan.PlanOutcomeService.StoredEnsemble projected,
            ApiResponses.QuoteView anchorQuote) {
        boolean identity = anchorQuote == null;
        String basis;
        if (identity) {
            basis = "STORED_ENSEMBLE";
        } else {
            String freshness = String.valueOf(anchorQuote.freshness()).toUpperCase(Locale.ROOT);
            boolean live = freshness.equals("REALTIME") || freshness.equals("DELAYED")
                    || freshness.equals("SIMULATED") || freshness.equals("FIXTURE");
            basis = live ? "CURRENT_QUOTE_REBASED_SOURCE_RETURNS"
                    : "LAST_OBSERVED_QUOTE_REBASED_SOURCE_RETURNS";
        }
        String transform = identity ? "IDENTITY"
                : "SCALE_EACH_SOURCE_PRICE_BY_PROJECTION_SPOT_OVER_SOURCE_SPOT_AND_TRUNCATE_V1";
        ObjectNode identityNode = Json.MAPPER.createObjectNode();
        identityNode.put("contractVersion", "scenario-projection-1");
        identityNode.put("basis", basis);
        identityNode.put("sourceEnsembleId", source.id());
        identityNode.put("sourceEnsembleFingerprint", source.fingerprint());
        identityNode.set("anchorQuote", Json.MAPPER.valueToTree(anchorQuote));
        identityNode.put("anchorSpot", projected.ensemble().spot());
        identityNode.put("anchorDate", projected.ensemble().anchorDate().toString());
        identityNode.put("horizonSessions", projected.ensemble().spec().horizonDays());
        identityNode.put("transform", transform);
        return new ApiResponses.ScenarioProjectionReceipt(
                "scenario-projection-1", basis, source.id(), source.fingerprint(), anchorQuote,
                projected.ensemble().spot(), projected.ensemble().anchorDate().toString(),
                projected.ensemble().spec().horizonDays(), transform, sha256(identityNode));
    }

    /**
     * A held story is declared relative to the current underlying receipt, which can differ
     * materially from the stored fan's anchor. Publish the per-frame move against that exact
     * current anchor so the browser never re-derives a displayed percentage from price pixels.
     */
    private static void decorateInteractionAnchor(
            ObjectNode checkpoints, Long interactionAnchorSpotCents) {
        if (checkpoints == null || interactionAnchorSpotCents == null
                || interactionAnchorSpotCents <= 0) return;
        double anchor = interactionAnchorSpotCents / 100.0;
        JsonNode steps = checkpoints.path("underlyingSteps");
        if (!steps.isArray()) return;
        for (JsonNode node : steps) {
            if (!(node instanceof ObjectNode step) || !step.path("focusPrice").isNumber()) continue;
            double price = step.path("focusPrice").asDouble();
            step.put("moveFromInteractionAnchorPct",
                    Math.round((price / anchor - 1.0) * 1_000_000.0) / 10_000.0);
        }
        ObjectNode receipt = checkpoints.with("modelReceipt");
        receipt.put("interactionAnchorSpotCents", interactionAnchorSpotCents);
    }

    private static String normalizeFocusPositionKey(String raw) {
        if (raw == null) return null;
        String key = raw.trim();
        if (key.isEmpty()) throw new IllegalArgumentException("focusPositionKey cannot be blank");
        if (key.length() > 200) throw new IllegalArgumentException("focusPositionKey is too long");
        return key;
    }

    private int interactionBoundarySessions(
            Context ctx,
            io.liftandshift.strikebench.plan.Plan.View plan,
            io.liftandshift.strikebench.plan.PlanOutcomeService.StoredEnsemble projected,
            ObjectNode selected,
            String focusPositionKey) {
        int horizon = projected.ensemble().spec().horizonDays();
        java.time.LocalDate expiration = selected == null
                ? null : finalExpiration(selected.path("legs"));
        if (expiration == null && focusPositionKey != null
                && !focusPositionKey.startsWith("PROPOSED:")
                && !focusPositionKey.startsWith("STOCK:")) {
            String activePlanTradeId = planManagement.activeTradeId(
                    root.ownerId(ctx), plan.id());
            var focused = canvasPositions.focused(root.ownerId(ctx), plan.accountId(),
                    plan.symbol(), projected.ensemble().anchorDate(), focusPositionKey,
                    activePlanTradeId);
            expiration = focused.packageView().legs().stream()
                    .map(io.liftandshift.strikebench.position.PositionPackage.Leg::expiration)
                    .filter(java.util.Objects::nonNull)
                    .max(java.time.LocalDate::compareTo)
                    .orElse(null);
        }
        if (expiration == null) return horizon;
        int sessions = io.liftandshift.strikebench.market.MarketHours.tradingDaysBetween(
                projected.ensemble().anchorDate(), expiration);
        if (sessions < 1) {
            throw new IllegalStateException(
                    "This package has no remaining trading session before its final expiration.");
        }
        return Math.min(horizon, sessions);
    }

    private static java.time.LocalDate finalExpiration(JsonNode legs) {
        if (legs == null || !legs.isArray()) return null;
        java.time.LocalDate latest = null;
        for (JsonNode leg : legs) {
            String raw = leg.path("expiration").asText(null);
            if (raw == null || raw.isBlank()) continue;
            java.time.LocalDate expiration;
            try {
                expiration = java.time.LocalDate.parse(raw);
            } catch (java.time.format.DateTimeParseException ignored) {
                continue;
            }
            if (latest == null || expiration.isAfter(latest)) latest = expiration;
        }
        return latest;
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
        var iv = body.iv() == null ? defaultPlanIv(run.ensemble().spec(), marketVol)
                : body.iv().validated(run.ensemble().spec().horizonDays());
        JsonNode input = Json.MAPPER.valueToTree(body);
        var stored = planOutcomes.saveEnsemble(root.ownerId(ctx), plan, run.ensemble(), iv, canvas,
                rate, run.preview(), input);
        ObjectNode preview = Json.MAPPER.valueToTree(run.preview());
        preview.put("planEnsembleId", stored.id());
        preview.put("planEnsembleFingerprint", stored.fingerprint());
        if (preview.path("receipt") instanceof ObjectNode receipt) receipt.put("fingerprint", stored.fingerprint());
        decorateScenarioCanvas(preview, run.ensemble());
        ObjectNode canvasJson = decorateCanvasValuation(ctx, preview, plan, stored);
        alignPreviewProjection(preview, stored.ensemble(), canvasJson);
        ctx.json(new ApiResponses.PlanEnsemble<>(plan,
                new ApiResponses.EnsembleRef(stored.id(), stored.fingerprint(), stored.basis(),
                        run.ensemble().waypointFill().name()), preview,
                currentBuildCurrency(stored, preview)));
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
        ObjectNode canvasJson = decorateCanvasValuation(ctx, preview, plan, stored);
        alignPreviewProjection(preview, stored.ensemble(), canvasJson);
        ctx.json(new ApiResponses.PlanEnsemble<>(plan,
                new ApiResponses.EnsembleRef(stored.id(), stored.fingerprint(), stored.basis(),
                        stored.ensemble().waypointFill().name()), preview,
                ensembleCurrency(plan, stored, preview, outcomeController.marketVol(
                        plan.symbol(), world, stored.ensemble().spec().horizonDays()),
                        market.riskFreeRateQuote(Math.max(1,
                                stored.ensemble().spec().horizonDays()), world).annualRate())));
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
        ObjectNode canvasJson = decorateCanvasValuation(ctx, preview, plan, stored);
        alignPreviewProjection(preview, stored.ensemble(), canvasJson);
        ctx.json(new ApiResponses.PlanEnsemble<>(plan,
                new ApiResponses.EnsembleRef(stored.id(), stored.fingerprint(), stored.basis(),
                        stored.ensemble().waypointFill().name()), preview,
                ensembleCurrency(plan, stored, preview, marketVol, rate)));
    }

    private static ApiResponses.ArtifactCurrency currentBuildCurrency(
            io.liftandshift.strikebench.plan.PlanOutcomeService.StoredEnsemble stored,
            ObjectNode preview) {
        if (!"CURRENT".equals(stored.state())) {
            return new ApiResponses.ArtifactCurrency(false, "STALE",
                    "The stored fan is not current for this Plan context.");
        }
        if (!displayReady(preview)) {
            return new ApiResponses.ArtifactCurrency(false, "INCOMPATIBLE",
                    "The stored fan does not contain a complete display projection.");
        }
        return new ApiResponses.ArtifactCurrency(true, "CURRENT",
                "The server just built this fan from the current Plan and market receipts.");
    }

    /**
     * One server-owned reuse decision for a stored fan. The browser must not compare timestamps,
     * prices, volatility inputs, rates, or display density and thereby create another currency
     * policy in JavaScript.
     */
    private ApiResponses.ArtifactCurrency ensembleCurrency(
            io.liftandshift.strikebench.plan.Plan.View plan,
            io.liftandshift.strikebench.plan.PlanOutcomeService.StoredEnsemble stored,
            ObjectNode preview,
            io.liftandshift.strikebench.sim.SimulationEngine.MarketVolInput marketVol,
            double rateAnnual) {
        ApiResponses.ArtifactCurrency structural = currentBuildCurrency(stored, preview);
        if (!structural.current()) return structural;
        if (stored.contextRev() != plan.context().rev()) {
            return new ApiResponses.ArtifactCurrency(false, "STALE",
                    "The Plan declarations changed after this fan was stored.");
        }
        String world = MarketLane.worldParam(stored.ensemble().scope().worldId());
        var quote = market.quote(plan.symbol(), world).orElse(null);
        if (quote == null || quote.mark() == null) {
            return new ApiResponses.ArtifactCurrency(false, "MARKET_UNAVAILABLE",
                    "A current underlying quote is unavailable; the stored fan remains historical evidence.");
        }
        String quoteAsOf = java.time.Instant.ofEpochMilli(quote.asOfEpochMs()).toString();
        String quoteFreshness = quote.markFreshness() == null
                ? "MISSING" : quote.markFreshness().name();
        boolean quoteMatches = Math.round(quote.mark().doubleValue() * 100)
                    == Math.round(stored.ensemble().spot() * 100)
                && java.util.Objects.equals(
                        io.liftandshift.strikebench.util.Timestamps.instant(stored.asOf()),
                        io.liftandshift.strikebench.util.Timestamps.instant(quoteAsOf))
                && String.valueOf(stored.anchorSource()).equalsIgnoreCase(
                        String.valueOf(quote.source()))
                && String.valueOf(stored.anchorFreshness()).equalsIgnoreCase(quoteFreshness);
        if (!quoteMatches) {
            return new ApiResponses.ArtifactCurrency(false, "MARKET_CHANGED",
                    "The underlying quote receipt changed after this fan was stored.");
        }
        if (marketVol != null && Math.abs(stored.ensemble().spec().volAnnual()
                - marketVol.atmIv()) > 0.000001) {
            return new ApiResponses.ArtifactCurrency(false, "MARKET_CHANGED",
                    "The market-volatility calibration changed after this fan was stored.");
        }
        if (Math.abs(stored.rateAnnual() - rateAnnual) > 0.0000001) {
            return new ApiResponses.ArtifactCurrency(false, "MARKET_CHANGED",
                    "The risk-free-rate receipt changed after this fan was stored.");
        }
        return new ApiResponses.ArtifactCurrency(true, "CURRENT",
                "Plan context, quote, volatility, rate, and display receipts still match.");
    }

    private static boolean displayReady(ObjectNode preview) {
        JsonNode samples = preview.path("samples");
        if (!samples.isArray() || samples.isEmpty()) return false;
        int horizon = preview.path("horizonDays").asInt();
        if (horizon > 2) return true;
        for (JsonNode sample : samples) {
            if (!sample.isArray() || sample.size() < 5) return false;
        }
        return true;
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
                stored.ensemble().spec(), stored.iv(), basis, null, position.price(),
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
            metadata.put(id, new PlanComparisonMeta(candidate, position, qty));
            io.liftandshift.strikebench.paper.PackagePriceReceipt price;
            try {
                price = OutcomeController.requireOutcomeEntryPrice(position.price(), qty);
            } catch (IllegalArgumentException unavailablePrice) {
                earlyRefusals.put(id, unavailablePrice.getMessage());
                continue;
            }
            try {
                var pathPosition = outcomeController.toPathPosition(ctx, position.legs(),
                        stored.ensemble().anchorDate());
                simItems.add(new io.liftandshift.strikebench.sim.ScenarioSimulator.CompareItem(
                        id, pathPosition, price.payoffEntryCostCents(),
                        price.valuationBasis()
                                == io.liftandshift.strikebench.paper.PackagePriceReceipt.ValuationBasis.RECORDED_FILL
                            ? "entry fixed to the held position's recorded fill"
                            : "entry fixed to the Plan proposal's captured package-price receipt",
                        price.estimatedRoundTripFeesCents(), qty));
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
                    meta.position().price().payoffEntryCostCents(), candidate.hasNonNull("maxLossCents")
                            ? candidate.path("maxLossCents").longValue() : null,
                    result == null ? null : result.winRatePct(), expected, p5,
                    result == null ? null : result.p50Cents(), result == null ? null : result.p95Cents(),
                    tailScore, meta.position().price().estimatedRoundTripFeesCents(),
                    economics.path("verdict").asText(null),
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
                                      int qty) {}

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
                    body.maxConcurrent(), body.qty(), body.shortDelta(), body.widthPct(), body.takeProfitFraction(),
                    body.stopMultiple(), body.timeRuleSessions(), body.startingCashCents()), root.analysisCtx(ctx),
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
        var iv = body.iv() == null ? defaultPlanIv(ensemble.spec(), marketVol)
                : body.iv().validated(ensemble.spec().horizonDays());
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
        // The complete captured price travels into Outcomes intact. Its valuation basis preserves
        // the distinction between a proposal snapshot and an actual RECORDED_FILL; no controller
        // negates a loose package net or pairs it with a separately parsed fee.
        io.liftandshift.strikebench.paper.PackagePriceReceipt price =
                capturedOutcomePrice(candidate);
        return new io.liftandshift.strikebench.outcomes.OutcomeContract.Position(candidate.path("id").asText(), legs,
                candidate.path("qty").asInt(), price);
    }

    static io.liftandshift.strikebench.paper.PackagePriceReceipt capturedOutcomePrice(
            JsonNode candidate) {
        if (candidate == null || !candidate.path("price").isObject()) {
            throw new IllegalArgumentException(
                    "The proposal has no captured package-price receipt.");
        }
        try {
            var price = Json.MAPPER.convertValue(candidate.path("price"),
                    io.liftandshift.strikebench.paper.PackagePriceReceipt.class);
            return OutcomeController.requireOutcomeEntryPrice(
                    price, Math.clamp(candidate.path("qty").asInt(1), 1, 100));
        } catch (IllegalArgumentException malformed) {
            if (malformed.getMessage() != null
                    && malformed.getMessage().startsWith("The captured entry")) {
                throw malformed;
            }
            throw new IllegalArgumentException(
                    "The proposal's captured package-price receipt is malformed.", malformed);
        }
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
        int days = DecisionDeclarationPolicy.requirePlanHorizon(
                "Plan outcome generation", plan.context().horizonDays());
        var base = raw == null
                ? io.liftandshift.strikebench.sim.ScenarioSpec.preset(
                    io.liftandshift.strikebench.sim.ScenarioSpec.Shape.CHOP, days, 0, 4242L, 500)
                    .withStepsPerDay(defaultPlanStepsPerDay(days))
                : raw.validated();
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
    private ObjectNode decorateCanvasValuation(Context ctx, ObjectNode preview,
            io.liftandshift.strikebench.plan.Plan.View plan,
            io.liftandshift.strikebench.plan.PlanOutcomeService.StoredEnsemble stored) {
        List<io.liftandshift.strikebench.sim.ScenarioCanvasValuator.DisplayPathSelection> selections =
                canvasDisplaySelections(preview);
        String displayPathRule = "TERMINAL_QUANTILES";
        if (selections.isEmpty()) {
            var projection = pathEnsembles.displayPaths(stored.ensemble(), null, 48);
            selections = canvasDisplaySelections(projection);
            displayPathRule = projection.selection();
        }
        return decorateCanvasValuation(ctx, preview, plan, stored, null, stored.iv(), stored.canvas(), null,
                selections, displayPathRule, null);
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
            String displayPathRule,
            ApiResponses.ScenarioProjectionReceipt scenarioProjection) {
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
                        position.qty(), position.price() == null
                                ? null : position.price().payoffEntryCostCents(), true));
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
            // No frames were valued, so there is no animation contract to publish. The desk must
            // say the story is not valued rather than scrub an absent track (§3.3).
            canvasJson.putNull("animation");
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
        receipt.put("anchorSpot", stored.ensemble().spot());
        receipt.put("anchorSource", stored.anchorSource());
        receipt.put("anchorFreshness", stored.anchorFreshness());
        receipt.put("anchorAsOf", stored.asOf());
        if (scenarioProjection != null) {
            receipt.set("scenarioProjection", Json.MAPPER.valueToTree(scenarioProjection));
        }
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
        int[] sharedDisplaySteps = canvasDisplaySteps(
                canvasJson, stored.ensemble().spec().totalSteps(), false);
        receipt.set("displaySteps", Json.MAPPER.valueToTree(
                java.util.Arrays.stream(sharedDisplaySteps).boxed().toList()));
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
        valuationIdentity.put("contractVersion",
                ApiResponses.SCENARIO_ANIMATION_VALUATION_CONTRACT_VERSION);
        valuationIdentity.put("ensembleFingerprint", stored.fingerprint());
        valuationIdentity.put("canvasModelVersion",
                io.liftandshift.strikebench.sim.ScenarioCanvasSpec.MODEL_VERSION);
        valuationIdentity.put("pathModelVersion", stored.ensemble().modelVersion());
        valuationIdentity.put("anchorSpot", stored.ensemble().spot());
        valuationIdentity.put("anchorDate", stored.ensemble().anchorDate().toString());
        valuationIdentity.put("anchorSource", stored.anchorSource());
        valuationIdentity.put("anchorFreshness", stored.anchorFreshness());
        valuationIdentity.put("anchorAsOf", stored.asOf());
        valuationIdentity.put("horizonSessions", stored.ensemble().spec().horizonDays());
        if (scenarioProjection != null) {
            valuationIdentity.set("scenarioProjection", Json.MAPPER.valueToTree(scenarioProjection));
        }
        valuationIdentity.put("focusSourcePathIndex", actualFocusPath);
        valuationIdentity.put("rateAnnual", stored.rateAnnual());
        valuationIdentity.set("ivAssumptions", Json.MAPPER.valueToTree(iv));
        valuationIdentity.set("canvasAssumptions", Json.MAPPER.valueToTree(canvas));
        valuationIdentity.set("positionPackages", Json.MAPPER.valueToTree(inputs));
        valuationIdentity.put("displayPathRule",
                displayPathRule == null ? "TERMINAL_QUANTILES" : displayPathRule);
        valuationIdentity.set("displayPathSelections", Json.MAPPER.valueToTree(
                displayPathSelections == null ? List.of() : displayPathSelections));
        valuationIdentity.set("displaySteps", Json.MAPPER.valueToTree(
                java.util.Arrays.stream(sharedDisplaySteps).boxed().toList()));
        if (focusPositionKey != null) valuationIdentity.put("focusPositionKey", focusPositionKey);
        if (focusedPackageIdentity != null) {
            valuationIdentity.set("focusedPackage", Json.MAPPER.valueToTree(focusedPackageIdentity));
        }
        if (selectedCandidateId != null) {
            valuationIdentity.put("selectedCandidateId", selectedCandidateId);
            valuationIdentity.put("selectedCandidateFingerprint", sha256(candidate));
        }
        receipt.put("valuationContractVersion",
                ApiResponses.SCENARIO_ANIMATION_VALUATION_CONTRACT_VERSION);
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

    private static List<io.liftandshift.strikebench.sim.ScenarioCanvasValuator.DisplayPathSelection>
            canvasDisplaySelections(ObjectNode preview) {
        if (preview == null || !preview.path("sampleSourcePathIndices").isArray()) return List.of();
        int focusIndex = preview.path("sampleFocusIndex").asInt(-1);
        List<io.liftandshift.strikebench.sim.ScenarioCanvasValuator.DisplayPathSelection> selections =
                new ArrayList<>();
        int index = 0;
        for (JsonNode sourcePathIndex : preview.path("sampleSourcePathIndices")) {
            selections.add(new io.liftandshift.strikebench.sim.ScenarioCanvasValuator.DisplayPathSelection(
                    sourcePathIndex.asInt(-1), index == focusIndex ? "FOCUS" : "CONTEXT"));
            index++;
        }
        return List.copyOf(selections);
    }

    private void alignPreviewProjection(
            ObjectNode preview,
            io.liftandshift.strikebench.sim.PathEnsembleService.Ensemble ensemble,
            ObjectNode canvasJson) {
        int[] displaySteps = canvasDisplaySteps(canvasJson, ensemble.spec().totalSteps(), false);
        if (displaySteps.length == 0) return;
        List<Integer> selectedSourcePathIndices = new ArrayList<>();
        for (JsonNode sourcePathIndex : preview.path("sampleSourcePathIndices")) {
            selectedSourcePathIndices.add(sourcePathIndex.asInt());
        }
        int sampleFocusIndex = preview.path("sampleFocusIndex").asInt(-1);
        var projection = io.liftandshift.strikebench.sim.SimulationEngine.projectPreview(
                ensemble, selectedSourcePathIndices, sampleFocusIndex, displaySteps);
        List<Integer> canvasSourcePathIndices = new ArrayList<>();
        for (JsonNode sourcePathIndex : canvasJson.path("displayPathSourceIndices")) {
            canvasSourcePathIndices.add(sourcePathIndex.asInt());
        }
        if (!projection.sampleSourcePathIndices().equals(canvasSourcePathIndices)
                || projection.sampleSourcePathIndices().get(projection.sampleFocusIndex())
                    != canvasJson.path("focusSourcePathIndex").asInt(-1)) {
            throw new IllegalStateException(
                    "Plan preview and Scenario Canvas selected different source paths; fan publication was refused.");
        }
        preview.set("stepBands", Json.MAPPER.valueToTree(projection.stepBands()));
        preview.set("samples", Json.MAPPER.valueToTree(projection.samples()));
        preview.set("sampleSourcePathIndices",
                Json.MAPPER.valueToTree(projection.sampleSourcePathIndices()));
        preview.put("sampleFocusIndex", projection.sampleFocusIndex());
        preview.set("displayProjectionReceipt", Json.MAPPER.valueToTree(projection.receipt()));
    }

    private static int[] canvasDisplaySteps(ObjectNode canvasJson, int totalSteps) {
        return canvasDisplaySteps(canvasJson, totalSteps, true);
    }

    private static int[] canvasDisplaySteps(ObjectNode canvasJson, int totalSteps, boolean required) {
        JsonNode underlyingSteps = canvasJson == null ? null : canvasJson.path("underlyingSteps");
        if (underlyingSteps == null || !underlyingSteps.isArray() || underlyingSteps.isEmpty()) {
            if (required) {
                throw new IllegalStateException(
                        "Scenario Canvas did not publish the source-step grid required for animation.");
            }
            return new int[0];
        }
        int[] steps = new int[underlyingSteps.size()];
        for (int index = 0; index < underlyingSteps.size(); index++) {
            steps[index] = underlyingSteps.get(index).path("step").asInt(-1);
        }
        return io.liftandshift.strikebench.sim.PathEnsembleService
                .exactDisplayStepIndices(totalSteps, steps);
    }

    private static void requireSameDisplaySelection(
            io.liftandshift.strikebench.sim.PathEnsembleService.DisplayProjection before,
            io.liftandshift.strikebench.sim.PathEnsembleService.DisplayProjection after) {
        List<String> beforeRows = before.paths().stream()
                .map(path -> path.sourcePathIndex() + ":" + path.role()).toList();
        List<String> afterRows = after.paths().stream()
                .map(path -> path.sourcePathIndex() + ":" + path.role()).toList();
        if (!beforeRows.equals(afterRows)
                || before.receipt().focusSourcePathIndex()
                    != after.receipt().focusSourcePathIndex()) {
            throw new IllegalStateException(
                    "Shared-grid projection changed the selected source paths; animation was refused.");
        }
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
