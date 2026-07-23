package io.liftandshift.strikebench.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.liftandshift.strikebench.backtest.Backtester;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.sim.IvSpec;
import io.liftandshift.strikebench.sim.PathEnsembleService;
import io.liftandshift.strikebench.sim.ScenarioSpec;
import io.liftandshift.strikebench.sim.ScenarioCanvasSpec;
import io.liftandshift.strikebench.sim.SimulationEngine;
import io.liftandshift.strikebench.util.Ids;
import io.liftandshift.strikebench.util.Json;
import io.liftandshift.strikebench.util.Quantiles;
import io.liftandshift.strikebench.util.OwnerScope;
import io.liftandshift.strikebench.util.ResourceNotFoundException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Plan ownership for exact path artifacts, exact-position outcome runs, and historical replays.
 * Path generation and valuation stay in PathEnsembleService/ScenarioSimulator/Backtester.
 */
public final class PlanOutcomeService {
    public static final String ENGINE_VERSION = "plan-outcomes-1";
    private static final String CODEC = "DEFLATE_DOUBLE_BE_V1";

    public record StoredEnsemble(String id, String fingerprint, String basis,
                                 int contextRev, String datasetId, String state,
                                 PathEnsembleService.Ensemble ensemble, IvSpec iv,
                                 ScenarioCanvasSpec canvas,
                                 double rateAnnual, double stepSeconds, String anchorSource,
                                 String anchorFreshness, String asOf) {}

    public record SavedOutcome(String id, String basis, String state, String candidateId,
                               String ensembleId, JsonNode result, String createdAt) {}

    public record SavedBacktest(String id, String state, String backtestId,
                                String engineKind, JsonNode summary, String createdAt) {}

    public record ComparisonItem(String key, String candidateId, int rank, String strategy,
                                 String displayName, int qty, Long entryCostCents,
                                 Long maxLossCents, Double winRatePct, Long expectedPnlCents, Long p5Cents,
                                 Long p50Cents, Long p95Cents, Double tailReturnScore,
                                 long roundTripFeesCents, String economicVerdict,
                                 String economicPlacement, Boolean mechanicallyEligible,
                                 Double decisionScore, boolean selected, String refusalReason) {}

    public record SavedComparison(String id, String basis, String state, String ensembleId,
                                  String ensembleFingerprint, String interpretation, String fairness,
                                  List<ComparisonItem> items, String createdAt) {}

    /** Explicit public-workspace ownership for an exact Possible Futures fan. */
    public record ResearchContext(String marketLane, String worldId, String datasetId) {}

    /** Opaque, owner-scoped handle returned before a Plan exists. */
    public record ResearchEnsembleReceipt(String id, String fingerprint, String basis,
                                          String waypointFill, String expiresAt, JsonNode preview) {}

    /** Exact artifact after its one transactional promotion into Plan ownership. */
    public record PromotedResearchEnsemble(StoredEnsemble ensemble, JsonNode preview) {}

    private final Db db;
    private final Clock clock;

    public PlanOutcomeService(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    /** Persist the exact matrix and IV path shown in Evidence. Repeated content deduplicates. */
    public StoredEnsemble saveEnsemble(String userId, Plan.View plan,
                                       PathEnsembleService.Ensemble ensemble,
                                       IvSpec rawIv, double rateAnnual,
                                       SimulationEngine.Preview preview, JsonNode input) {
        return saveEnsemble(userId, plan, ensemble, rawIv, null, rateAnnual, preview, input);
    }

    /** Persist the exact matrix plus the Canvas's typed per-day/surface/settlement receipt. */
    public StoredEnsemble saveEnsemble(String userId, Plan.View plan,
                                       PathEnsembleService.Ensemble ensemble,
                                       IvSpec rawIv, ScenarioCanvasSpec rawCanvas, double rateAnnual,
                                       SimulationEngine.Preview preview, JsonNode input) {
        PreparedEnsemble prepared = prepareEnsemble(ensemble, rawIv, rawCanvas, rateAnnual, preview, input);
        IvSpec iv = prepared.iv();
        ScenarioCanvasSpec canvas = prepared.canvas();
        String inputHash = prepared.inputHash();
        String fingerprint = prepared.fingerprint();
        String proposedEnsembleId = Ids.newId("pen");
        ScenarioSpec spec = prepared.spec();
        String source = prepared.source();
        String freshness = prepared.freshness();
        String asOf = prepared.asOf();
        String datasetId = prepared.datasetId();

        String ensembleId = db.tx(c -> {
            PlanWriteGuard.requireMutable(c, plan.id(), userId);
            CurrentPlan current = ownedPlanOn(c, plan.id(), userId, true);
            if (current.contextRev() != plan.context().rev()) {
                throw new IllegalStateException("The Plan assumptions changed while the ensemble was running.");
            }
            insertArtifact(c, ensemble, prepared, rateAnnual);
            List<String> identical = Db.queryOn(c,
                    "SELECT pe.id FROM plan_ensemble pe JOIN ensemble_artifact ea " +
                            "ON ea.fingerprint=pe.fingerprint WHERE pe.plan_id=? AND pe.context_rev=? " +
                            "AND pe.fingerprint=? AND ea.basis=? AND pe.dataset_id IS NOT DISTINCT FROM ? " +
                            "AND pe.state='CURRENT' ORDER BY pe.created_at DESC LIMIT 1",
                    r -> r.str("id"), plan.id(), plan.context().rev(), fingerprint,
                    ensemble.basis().name(), datasetId);
            if (!identical.isEmpty()) return identical.getFirst();
            Db.execOn(c, "UPDATE plan_ensemble pe SET state='STALE' FROM ensemble_artifact ea " +
                            "WHERE pe.fingerprint=ea.fingerprint AND pe.plan_id=? AND pe.context_rev=? " +
                            "AND ea.basis=? AND pe.dataset_id IS NOT DISTINCT FROM ? AND pe.state='CURRENT'",
                    plan.id(), plan.context().rev(), ensemble.basis().name(), datasetId);
            persistPlanEnsemble(c, proposedEnsembleId, plan, ensemble, prepared, preview);
            return proposedEnsembleId;
        });
        return new StoredEnsemble(ensembleId, fingerprint, ensemble.basis().name(),
                plan.context().rev(), datasetId, "CURRENT", ensemble, iv, canvas,
                rateAnnual, 23_400.0 / Math.max(1, spec.stepsPerDay()), source, freshness, asOf);
    }

    /**
     * Persist the exact public Possible Futures fan before a Plan exists. The dense matrix remains
     * in the canonical ensemble_artifact store; this row is only an owner/lane-scoped promotion
     * capability. Repeating the identical request reuses the live receipt.
     */
    public ResearchEnsembleReceipt saveResearchEnsemble(
            String userId, ResearchContext context, PathEnsembleService.Ensemble ensemble,
            IvSpec rawIv, ScenarioCanvasSpec rawCanvas, double rateAnnual,
            SimulationEngine.Preview preview, JsonNode input) {
        if (context == null) throw new IllegalArgumentException("research ensemble context is required");
        if (ensemble == null) throw new IllegalArgumentException("research ensemble is required");
        String worldId = canonicalWorld(context.worldId());
        String datasetId = required(context.datasetId(), "datasetId");
        String lane = required(context.marketLane(), "marketLane").toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("OBSERVED", "DEMO", "SIMULATED", "SCENARIO").contains(lane)) {
            throw new IllegalArgumentException("marketLane is invalid");
        }
        if (!canonicalWorld(ensemble.scope().worldId()).equals(worldId)) {
            throw new IllegalStateException("The path fan belongs to a different market world.");
        }
        if (!ensemble.scope().analysis().datasetId().equals(datasetId)) {
            throw new IllegalStateException("The path fan belongs to a different analysis dataset.");
        }
        String owner = OwnerScope.id(userId);
        ScenarioCanvasSpec receiptCanvas = rawCanvas == null
                ? ScenarioCanvasSpec.defaults() : rawCanvas;
        PreparedEnsemble prepared = prepareEnsemble(ensemble, rawIv, receiptCanvas, rateAnnual, preview, input);
        ObjectNode previewJson = preview == null ? Json.MAPPER.createObjectNode()
                : Json.MAPPER.valueToTree(preview);
        if (previewJson.path("receipt") instanceof ObjectNode receipt) {
            receipt.put("fingerprint", prepared.fingerprint());
        }
        String previewHash = semanticSha256(previewJson);
        ObjectNode inputJson = requireObject(input, "outcome request");
        String receiptId = Ids.newId("rer");
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        OffsetDateTime expires = now.plus(Duration.ofDays(30));
        String marketKind = marketKindForContext(lane, worldId);

        ReceiptIdentity identity = db.tx(c -> {
            insertArtifact(c, ensemble, prepared, rateAnnual);
            List<ReceiptIdentity> existing = Db.queryOn(c,
                    "SELECT id,expires_at::text expires_at FROM research_ensemble_receipt " +
                            "WHERE user_id=? AND fingerprint=? AND preview_hash=? AND symbol=? AND market_lane=? " +
                            "AND world_id=? AND dataset_id=? AND state='AVAILABLE' AND expires_at>? " +
                            "ORDER BY created_at DESC LIMIT 1",
                    r -> new ReceiptIdentity(r.str("id"), r.str("expires_at")), owner,
                    prepared.fingerprint(), previewHash, ensemble.scope().symbol(), lane, worldId, datasetId, now);
            if (!existing.isEmpty()) return existing.getFirst();
            Db.execOn(c, "INSERT INTO research_ensemble_receipt(id,user_id,fingerprint,symbol,market_kind," +
                            "market_lane,world_id,dataset_id,model_version,anchor_spot_cents,anchor_date," +
                            "anchor_source,anchor_freshness,as_of,input_hash,preview_hash,spec,iv,canvas,input,preview,state," +
                            "expires_at,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb," +
                            "?::jsonb,?::jsonb,?::jsonb,'AVAILABLE',?,?)",
                    receiptId, owner, prepared.fingerprint(), ensemble.scope().symbol(), marketKind,
                    lane, worldId, datasetId, ensemble.modelVersion(), Math.round(ensemble.spot() * 100),
                    ensemble.anchorDate(), prepared.source(), prepared.freshness(),
                    OffsetDateTime.parse(prepared.asOf()), prepared.inputHash(), previewHash,
                    Json.canonical(prepared.spec()), Json.canonical(prepared.iv()),
                    Json.canonical(prepared.canvas()), Json.canonical(inputJson), Json.canonical(previewJson),
                    expires, now);
            return new ReceiptIdentity(receiptId, expires.toString());
        });
        return new ResearchEnsembleReceipt(identity.id(), prepared.fingerprint(), ensemble.basis().name(),
                ensemble.waypointFill().name(), identity.expiresAt(), previewJson);
    }

    /**
     * Atomically turns one public receipt into the Plan's CURRENT ensemble. This method never owns
     * a PathEnsembleService and therefore cannot regenerate: it validates, inflates, fingerprints,
     * and links the already persisted matrix.
     */
    public PromotedResearchEnsemble promoteResearchEnsemble(
            String userId, Plan.View plan, String receiptId, String expectedFingerprint,
            ResearchContext activeContext) {
        String owner = OwnerScope.id(userId);
        String requiredReceipt = required(receiptId, "researchReceiptId");
        String requiredFingerprint = required(expectedFingerprint, "expectedFingerprint");
        if (activeContext == null) throw new IllegalArgumentException("active research context is required");

        AdoptionResult adopted = db.tx(c -> {
            PlanWriteGuard.requireMutable(c, plan.id(), userId);
            CurrentPlan current = ownedPlanOn(c, plan.id(), userId, true);
            if (current.contextRev() != plan.context().rev()) {
                throw new IllegalStateException("The Plan assumptions changed before the exact fan could be adopted.");
            }
            List<ResearchRow> rows = Db.queryOn(c,
                    "SELECT rr.id,rr.fingerprint,rr.symbol,rr.market_kind,rr.market_lane,rr.world_id," +
                            "rr.dataset_id,rr.model_version,rr.anchor_spot_cents,rr.anchor_date," +
                            "rr.anchor_source,rr.anchor_freshness,rr.as_of::text as_of,rr.input_hash," +
                            "rr.spec::text spec,rr.iv::text iv,rr.canvas::text canvas,rr.input::text input," +
                            "rr.preview::text preview,rr.state,rr.adopted_plan_id,rr.adopted_ensemble_id," +
                            "rr.expires_at,ea.basis,ea.n_paths,ea.n_steps,ea.codec,ea.spot_matrix,ea.iv_path," +
                            "ea.rate_annual,ea.step_seconds,ea.source_content_hash " +
                            "FROM research_ensemble_receipt rr JOIN ensemble_artifact ea " +
                            "ON ea.fingerprint=rr.fingerprint WHERE rr.id=? AND rr.user_id=? FOR UPDATE OF rr",
                    PlanOutcomeService::researchRow, requiredReceipt, owner);
            if (rows.isEmpty()) throw new ResourceNotFoundException("no such Possible Futures receipt: " + requiredReceipt);
            ResearchRow row = rows.getFirst();
            if (!row.fingerprint().equals(requiredFingerprint)) {
                throw new IllegalStateException("The Possible Futures fingerprint changed before adoption.");
            }
            validateResearchOwnership(plan, current, row, activeContext);
            if ("ADOPTED".equals(row.state())) {
                if (plan.id().equals(row.adoptedPlanId()) && row.adoptedEnsembleId() != null) {
                    return new AdoptionResult(row.adoptedEnsembleId(), parseObject(row.preview(), "preview"));
                }
                throw new IllegalStateException("This Possible Futures receipt was already adopted by another Plan.");
            }
            if (!row.expiresAt().isAfter(OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))) {
                throw new IllegalStateException("This Possible Futures receipt expired. Analyze the scenario again to create a fresh exact fan.");
            }

            RehydratedResearch rehydrated = rehydrateResearch(row, activeContext, owner);
            if (!row.sourceContentHash().equals(row.inputHash())) {
                throw new IllegalStateException("The stored Possible Futures assumptions failed their immutable hash check.");
            }
            if (!rehydrated.prepared().fingerprint().equals(row.fingerprint())) {
                throw new IllegalStateException("The stored Possible Futures path matrix failed its immutable fingerprint check.");
            }
            String ensembleId = Ids.newId("pen");
            String planDatasetId = rehydrated.prepared().datasetId();
            Db.execOn(c, "UPDATE plan_ensemble pe SET state='STALE' FROM ensemble_artifact ea " +
                            "WHERE pe.fingerprint=ea.fingerprint AND pe.plan_id=? AND pe.context_rev=? " +
                            "AND ea.basis=? AND pe.dataset_id IS NOT DISTINCT FROM ? AND pe.state='CURRENT'",
                    plan.id(), plan.context().rev(), row.basis(), planDatasetId);
            persistPlanEnsemble(c, ensembleId, plan, rehydrated.ensemble(),
                    rehydrated.prepared(), rehydrated.preview());
            Db.execOn(c, "UPDATE research_ensemble_receipt SET state='ADOPTED',adopted_plan_id=?," +
                            "adopted_ensemble_id=? WHERE id=?",
                    plan.id(), ensembleId, row.id());
            return new AdoptionResult(ensembleId, Json.MAPPER.valueToTree(rehydrated.preview()));
        });
        StoredEnsemble stored = loadEnsemble(userId, plan.id(), adopted.ensembleId());
        return new PromotedResearchEnsemble(stored, adopted.preview());
    }

    /** Rehydrate the stored matrix, IV path recipe, rate, model identity, and exact context. */
    public StoredEnsemble loadEnsemble(String userId, String planId, String ensembleId) {
        return db.with(c -> {
            ownedPlanOn(c, planId, userId, false);
            List<EnsembleRow> rows = Db.queryOn(c, "SELECT pe.id,pe.fingerprint,ea.basis,pe.context_rev,pe.dataset_id,pe.state,pe.model_version," +
                            "p.symbol,p.market_kind,p.world_id,p.user_id,pe.dataset_id," +
                            "pe.anchor_spot_cents,pe.anchor_source,pe.anchor_freshness,pe.as_of::text as_of," +
                            "pe.spec_model,pe.spec_shape,pe.spec_horizon_days,pe.spec_steps_per_day," +
                            "pe.spec_drift_annual,pe.spec_vol_annual,pe.spec_jumps_per_year,pe.spec_jump_mean," +
                            "pe.spec_jump_vol,pe.spec_tail_nu,pe.spec_seed,pe.spec_paths,pe.heston_kappa," +
                            "pe.heston_theta,pe.heston_xi,pe.heston_rho,pe.heston_v0,pe.iv_start,pe.iv_longrun," +
                            "pe.iv_drift_per_year,pe.iv_mean_revert_speed,pe.iv_event_day,pe.iv_event_shock_pct," +
                            "pe.iv_min,pe.iv_max,ea.rate_annual,ea.step_seconds,ea.n_paths,ea.n_steps,ea.codec,ea.spot_matrix,ea.iv_path " +
                            "FROM plan_ensemble pe JOIN ensemble_artifact ea ON ea.fingerprint=pe.fingerprint " +
                            "JOIN plans p ON p.id=pe.plan_id " +
                            "WHERE pe.id=? AND pe.plan_id=?",
                    PlanOutcomeService::ensembleRow, ensembleId, planId);
            if (rows.isEmpty()) throw new ResourceNotFoundException("no such Plan ensemble: " + ensembleId);
            EnsembleRow r = rows.getFirst();
            if (!CODEC.equals(r.codec())) throw new IllegalStateException("Unsupported ensemble codec " + r.codec());
            double[][] paths = decodeMatrix(inflate(r.spotMatrix()), r.nPaths(), r.nSteps() + 1);
            ScenarioSpec.Heston h = r.hestonKappa() == null ? null : new ScenarioSpec.Heston(
                    r.hestonKappa(), r.hestonTheta(), r.hestonXi(), r.hestonRho(), r.hestonV0());
            ScenarioSpec spec = new ScenarioSpec(ScenarioSpec.PathModel.valueOf(r.model()),
                    ScenarioSpec.Shape.valueOf(r.shape()), r.horizon(), r.stepsPerDay(), r.drift(), r.vol(),
                    r.jumps(), r.jumpMean(), r.jumpVol(), r.tailNu(), h, r.seed(), r.paths());
            // Re-attach the authored pins so the derived waypoint-fill honesty label survives storage.
            List<ScenarioSpec.Waypoint> pins = Db.queryOn(c,
                    "SELECT day_index,price_ratio,tolerance FROM plan_ensemble_waypoint " +
                            "WHERE ensemble_id=? ORDER BY day_index",
                    x -> new ScenarioSpec.Waypoint(x.intv("day_index"), x.dbl("price_ratio"),
                            x.dblOrNull("tolerance")), r.id());
            if (!pins.isEmpty()) spec = spec.withWaypoints(pins);
            IvSpec iv = new IvSpec(r.ivStart(), r.ivDrift(), r.ivMeanRevert(), r.ivLongRun(),
                    r.ivEventDay(), r.ivEventShock(), r.ivMin(), r.ivMax()).sane();
            ScenarioCanvasSpec canvas = loadCanvas(c, r.id(), spec.horizonDays());
            java.time.LocalDate anchorDate = canvas == null ? null : Db.queryOn(c,
                    "SELECT anchor_date FROM plan_ensemble_canvas WHERE ensemble_id=?",
                    x -> x.date("anchor_date"), r.id()).stream().findFirst().orElse(null);
            if (anchorDate == null) {
                try {
                    anchorDate = java.time.LocalDate.ofInstant(parseAsOf(r.asOf()),
                            io.liftandshift.strikebench.market.MarketHours.EASTERN);
                } catch (RuntimeException e) {
                    anchorDate = java.time.LocalDate.of(1970, 1, 1);
                }
            }
            double[] storedIv = decodeVector(inflate(r.ivPath()), r.nSteps() + 1);
            double[] derivedIv = canvasIvPath(spec, iv, canvas, anchorDate);
            if (!Arrays.equals(storedIv, derivedIv)) {
                throw new IllegalStateException("Stored IV trajectory no longer matches its normalized recipe");
            }
            var scope = new PathEnsembleService.Scope(r.symbol(), r.worldId(), r.analysis());
            var ensemble = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.valueOf(r.basis()),
                    scope, r.anchorSpotCents() / 100.0, spec, paths, null, r.modelVersion(), anchorDate);
            return new StoredEnsemble(r.id(), r.fingerprint(), r.basis(), r.contextRev(),
                    r.datasetId(), r.state(), ensemble, iv, canvas,
                    r.rate(), r.stepSeconds(), r.anchorSource(), r.anchorFreshness(), r.asOf());
        });
    }

    /** The stored fan's fingerprint alone — lineage lookups must not inflate the whole matrix. */
    public String ensembleFingerprint(String userId, String planId, String ensembleId) {
        return db.with(c -> {
            ownedPlanOn(c, planId, userId, false);
            return Db.queryOn(c, "SELECT fingerprint FROM plan_ensemble WHERE id=? AND plan_id=?",
                    r -> r.str("fingerprint"), ensembleId, planId).stream().findFirst().orElse(null);
        });
    }

    /** Lightweight typed Canvas receipt lookup; does not inflate the stored path matrix. */
    public ScenarioCanvasSpec canvasSpec(String userId, String planId, String ensembleId, int horizonDays) {
        return db.with(c -> {
            ownedPlanOn(c, planId, userId, false);
            if (Db.queryOn(c, "SELECT id FROM plan_ensemble WHERE id=? AND plan_id=?",
                    r -> r.str("id"), ensembleId, planId).isEmpty()) return null;
            try { return loadCanvas(c, ensembleId, horizonDays); }
            catch (java.sql.SQLException e) { throw new io.liftandshift.strikebench.db.Db.DbException(e); }
        });
    }

    /** Load an artifact only when it still belongs to the Plan assumptions and analysis lane on screen. */
    public StoredEnsemble loadCurrentEnsemble(String userId, Plan.View plan, String ensembleId,
                                              io.liftandshift.strikebench.db.AnalysisContext analysis) {
        StoredEnsemble stored = loadEnsemble(userId, plan.id(), ensembleId);
        String datasetId = analysis != null && analysis.synthetic() ? analysis.datasetId() : null;
        if (stored.contextRev() != plan.context().rev() || !"CURRENT".equals(stored.state())) {
            throw new IllegalStateException("This path set belongs to earlier Plan assumptions. Run Outcomes again.");
        }
        if (!java.util.Objects.equals(stored.datasetId(), datasetId)) {
            throw new IllegalStateException("This path set belongs to a different analysis dataset. Run Outcomes in the data lane on screen.");
        }
        return stored;
    }

    public StoredEnsemble latestEnsemble(String userId, Plan.View plan, String basis,
                                         io.liftandshift.strikebench.db.AnalysisContext analysis) {
        String datasetId = analysis != null && analysis.synthetic() ? analysis.datasetId() : null;
        String id = db.with(c -> Db.queryOn(c, "SELECT pe.id FROM plan_ensemble pe " +
                        "JOIN ensemble_artifact ea ON ea.fingerprint=pe.fingerprint " +
                        "WHERE pe.plan_id=? AND pe.context_rev=? AND pe.state='CURRENT' AND ea.basis=? " +
                        "AND pe.dataset_id IS NOT DISTINCT FROM ? " +
                        "ORDER BY pe.created_at DESC LIMIT 1", r -> r.str("id"),
                plan.id(), plan.context().rev(), basis, datasetId).stream().findFirst().orElse(null));
        return id == null ? null : loadEnsemble(userId, plan.id(), id);
    }

    /** The decision levels captured with a stored fan, verbatim as persisted. */
    public List<SimulationEngine.LevelOdds> levelOdds(String ensembleId) {
        return db.with(c -> Db.queryOn(c, "SELECT level_key,price_cents,direction,end_above_probability," +
                        "end_below_probability,end_beyond_probability,touch_probability,touch_ci_low," +
                        "touch_ci_high,median_first_touch_day FROM plan_level_odds WHERE ensemble_id=? " +
                        "ORDER BY level_key",
                r -> new SimulationEngine.LevelOdds(r.str("level_key"), r.lng("price_cents") / 100.0,
                        r.str("direction"), r.dbl("end_above_probability"), r.dbl("end_below_probability"),
                        r.dbl("end_beyond_probability"), r.dbl("touch_probability"), r.dbl("touch_ci_low"),
                        r.dbl("touch_ci_high"), r.dblOrNull("median_first_touch_day")), ensembleId));
    }

    public SavedOutcome savePathOutcome(String userId, Plan.View plan, long expectedVersion,
                                        String candidateId, StoredEnsemble stored,
                                        JsonNode result, JsonNode input, String interpretation) {
        if (stored == null || result == null) throw new IllegalArgumentException("outcome result is required");
        String id = Ids.newId("por");
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String inputHash = sha256(input == null ? Json.MAPPER.createObjectNode() : input);
        String datasetId = stored.ensemble().scope().analysis().synthetic()
                ? stored.ensemble().scope().analysis().datasetId() : null;
        String state = db.tx(c -> {
            PlanWriteGuard.requireMutable(c, plan.id(), userId);
            CurrentPlan current = ownedPlanOn(c, plan.id(), userId, true);
            if (current.version() != expectedVersion || current.contextRev() != plan.context().rev()) {
                throw new IllegalStateException("This Plan changed while Outcomes was running. Reload it before saving the result.");
            }
            requireCurrentEnsembleOn(c, plan.id(), current.contextRev(), stored.id(), datasetId);
            requireSelectedCandidate(c, plan.id(), plan.context().rev(), candidateId);
            Db.execOn(c, "UPDATE plan_outcome_run SET state='STALE' WHERE plan_id=? AND context_rev=? " +
                    "AND basis=? AND dataset_id IS NOT DISTINCT FROM ? AND state='CURRENT'",
                    plan.id(), plan.context().rev(), stored.basis(), datasetId);
            Db.execOn(c, "INSERT INTO plan_outcome_run(id,plan_id,context_rev,candidate_id,ensemble_id,dataset_id,basis," +
                            "interpretation,entry_cost_cents,paths,horizon_days,p5_cents,p25_cents,p50_cents," +
                            "p75_cents,p95_cents,expected_pnl_cents,win_rate_pct,best_cents,worst_cents," +
                            "breach_probability_pct,input_hash,engine_version,state,created_at) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, plan.id(), plan.context().rev(), candidateId, stored.id(), datasetId, stored.basis(), interpretation,
                    longOrNull(result, "entryCostCents"), integerOrNull(result, "paths"),
                    integerOrNull(result, "horizonDays"), longOrNull(result, "p5Cents"),
                    longOrNull(result, "p25Cents"), longOrNull(result, "p50Cents"),
                    longOrNull(result, "p75Cents"), longOrNull(result, "p95Cents"),
                    longOrNull(result, "expectedPnlCents"), doubleOrNull(result, "winRatePct"),
                    longOrNull(result, "bestCents"), longOrNull(result, "worstCents"),
                    doubleOrNull(result, "breachProbPct"), inputHash, ENGINE_VERSION, "CURRENT", now);
            persistPathDetails(c, id, result);
            return "CURRENT";
        });
        return new SavedOutcome(id, stored.basis(), state, candidateId, stored.id(), result, now.toString());
    }

    public SavedOutcome saveRiskNeutral(String userId, Plan.View plan, long expectedVersion,
                                        String candidateId, JsonNode result, JsonNode input,
                                        String interpretation,
                                        io.liftandshift.strikebench.db.AnalysisContext analysis) {
        String id = Ids.newId("por");
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String inputHash = sha256(input == null ? Json.MAPPER.createObjectNode() : input);
        String datasetId = analysis != null && analysis.synthetic() ? analysis.datasetId() : null;
        db.tx(c -> {
            PlanWriteGuard.requireMutable(c, plan.id(), userId);
            CurrentPlan current = ownedPlanOn(c, plan.id(), userId, true);
            if (current.version() != expectedVersion || current.contextRev() != plan.context().rev()) {
                throw new IllegalStateException("This Plan changed while market odds were running.");
            }
            requireSelectedCandidate(c, plan.id(), plan.context().rev(), candidateId);
            Db.execOn(c, "UPDATE plan_outcome_run SET state='STALE' WHERE plan_id=? AND context_rev=? " +
                    "AND basis='RISK_NEUTRAL' AND dataset_id IS NOT DISTINCT FROM ? AND state='CURRENT'",
                    plan.id(), plan.context().rev(), datasetId);
            Db.execOn(c, "INSERT INTO plan_outcome_run(id,plan_id,context_rev,candidate_id,dataset_id,basis,interpretation," +
                            "entry_cost_cents,expected_pnl_cents,input_hash,engine_version,state,created_at) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", id, plan.id(), plan.context().rev(), candidateId,
                    datasetId, "RISK_NEUTRAL", interpretation, longOrNull(result, "entryCostCents"),
                    longOrNull(result, "expectedValueAfterFeesCents"), inputHash, ENGINE_VERSION, "CURRENT", now);
            flattenMetrics(c, id, "", result);
            return null;
        });
        return new SavedOutcome(id, "RISK_NEUTRAL", "CURRENT", candidateId, null, result, now.toString());
    }

    /** Persist a cross-structure comparison whose every item used the exact stored ensemble. */
    public SavedComparison saveComparison(String userId, Plan.View plan, long expectedVersion,
                                          StoredEnsemble stored, List<ComparisonItem> items,
                                          JsonNode input, String interpretation, String fairness) {
        if (stored == null) throw new IllegalArgumentException("comparison ensemble is required");
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("comparison items are required");
        String id = Ids.newId("poc");
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String inputHash = sha256(input == null ? Json.MAPPER.createObjectNode() : input);
        String datasetId = stored.ensemble().scope().analysis().synthetic()
                ? stored.ensemble().scope().analysis().datasetId() : null;
        db.tx(c -> {
            PlanWriteGuard.requireMutable(c, plan.id(), userId);
            CurrentPlan current = ownedPlanOn(c, plan.id(), userId, true);
            if (current.version() != expectedVersion || current.contextRev() != plan.context().rev()) {
                throw new IllegalStateException("This Plan changed while proposals were being compared.");
            }
            requireCurrentEnsembleOn(c, plan.id(), current.contextRev(), stored.id(), datasetId);
            for (ComparisonItem item : items) {
                if (item.candidateId() != null) requireCurrentCandidate(c, plan.id(), plan.context().rev(), item.candidateId());
            }
            Db.execOn(c, "UPDATE plan_outcome_comparison SET state='STALE' WHERE plan_id=? AND context_rev=? " +
                            "AND basis=? AND dataset_id IS NOT DISTINCT FROM ? AND state='CURRENT'",
                    plan.id(), plan.context().rev(), stored.basis(), datasetId);
            Db.execOn(c, "INSERT INTO plan_outcome_comparison(id,plan_id,context_rev,ensemble_id," +
                            "ensemble_fingerprint,dataset_id,basis,interpretation,fairness,input_hash," +
                            "engine_version,state,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, plan.id(), plan.context().rev(), stored.id(), stored.fingerprint(), datasetId,
                    stored.basis(), interpretation, fairness, inputHash, ENGINE_VERSION, "CURRENT", now);
            for (ComparisonItem item : items) {
                Db.execOn(c, "INSERT INTO plan_outcome_comparison_item(comparison_id,item_key,candidate_id," +
                                "rank_number,strategy,display_name,qty,entry_cost_cents,win_rate_pct," +
                                "max_loss_cents,expected_pnl_cents,p5_cents,p50_cents,p95_cents,tail_return_score," +
                                "round_trip_fees_cents,economic_verdict,economic_placement,mechanically_eligible," +
                                "decision_score,selected,refusal_reason) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        id, item.key(), item.candidateId(), item.rank(), item.strategy(), item.displayName(),
                        item.qty(), item.entryCostCents(), item.winRatePct(), item.maxLossCents(), item.expectedPnlCents(),
                        item.p5Cents(), item.p50Cents(), item.p95Cents(), item.tailReturnScore(),
                        item.roundTripFeesCents(), item.economicVerdict(), item.economicPlacement(),
                        item.mechanicallyEligible() == null ? null : item.mechanicallyEligible() ? 1 : 0,
                        item.decisionScore(), item.selected() ? 1 : 0, item.refusalReason());
            }
            return null;
        });
        return new SavedComparison(id, stored.basis(), "CURRENT", stored.id(), stored.fingerprint(),
                interpretation, fairness, List.copyOf(items), now.toString());
    }

    public SavedBacktest saveBacktest(String userId, Plan.View plan, long expectedVersion,
                                      String candidateId, String engineKind, JsonNode report,
                                      JsonNode input, io.liftandshift.strikebench.db.AnalysisContext analysis) {
        String id = Ids.newId("pbt");
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String inputHash = sha256(input == null ? Json.MAPPER.createObjectNode() : input);
        String datasetId = analysis != null && analysis.synthetic() ? analysis.datasetId() : null;
        db.tx(c -> {
            PlanWriteGuard.requireMutable(c, plan.id(), userId);
            CurrentPlan current = ownedPlanOn(c, plan.id(), userId, true);
            if (current.version() != expectedVersion || current.contextRev() != plan.context().rev()) {
                throw new IllegalStateException("This Plan changed while the historical replay was running.");
            }
            requireSelectedCandidate(c, plan.id(), plan.context().rev(), candidateId);
            Db.execOn(c, "UPDATE plan_backtest SET state='STALE' WHERE plan_id=? AND context_rev=? " +
                            "AND dataset_id IS NOT DISTINCT FROM ? AND state='CURRENT'",
                    plan.id(), plan.context().rev(), datasetId);
            Db.execOn(c, "INSERT INTO plan_backtest(id,plan_id,context_rev,backtest_id,candidate_id,dataset_id,basis,as_of," +
                            "sample_size,win_rate,total_pnl_cents,max_drawdown_cents,avg_return_on_risk," +
                            "evidence_provenance,input_hash,engine_version,state,engine_kind,pricing_mode,confidence," +
                            "starting_cents,ending_cents,max_drawdown_pct,demo_underlying,created_at) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, plan.id(), plan.context().rev(), text(report, "id"), candidateId, datasetId, "HISTORICAL_REPLAY", now,
                    integerOrNull(report, "sampleSize"), doubleOrNull(report, "winRate"),
                    endingDelta(report), maxDrawdownCents(report), doubleOrNull(report, "avgReturnOnRisk"),
                    backtestEvidence(report), inputHash, ENGINE_VERSION, "CURRENT", engineKind,
                    text(report, "pricingMode"), text(report, "confidence"), longOrNull(report, "startingCents"),
                    longOrNull(report, "endingCents"), doubleOrNull(report, "maxDrawdownPct"),
                    report.path("demoUnderlying").asBoolean(false) ? 1 : 0, now);
            return null;
        });
        ObjectNode summary = Json.MAPPER.createObjectNode();
        for (String key : List.of("id", "symbol", "strategy", "from", "to", "pricingMode", "confidence",
                "sampleSize", "winRate", "avgReturnOnRisk", "startingCents", "endingCents",
                "maxDrawdownPct", "demoUnderlying")) if (report.has(key)) summary.set(key, report.get(key));
        return new SavedBacktest(id, "CURRENT", text(report, "id"), engineKind, summary, now.toString());
    }

    /** Authorize a historical replay read through its owning Plan. */
    public void requireBacktest(String userId, String planId, String backtestId) {
        db.with(c -> {
            ownedPlanOn(c, planId, userId, false);
            boolean linked = !Db.queryOn(c, "SELECT id FROM plan_backtest WHERE plan_id=? AND backtest_id=? LIMIT 1",
                    r -> r.str("id"), planId, backtestId).isEmpty();
            if (!linked) throw new ResourceNotFoundException("no such Plan backtest: " + backtestId);
            return null;
        });
    }

    /** Latest current results for the Plan's current context, reconstructed from normalized rows. */
    public ObjectNode latest(String userId, Plan.View plan,
                             io.liftandshift.strikebench.db.AnalysisContext analysis) {
        String datasetId = analysis != null && analysis.synthetic() ? analysis.datasetId() : null;
        return db.with(c -> {
            ownedPlanOn(c, plan.id(), userId, false);
            ObjectNode out = Json.MAPPER.createObjectNode();
            ArrayNode runs = out.putArray("outcomes");
            List<OutcomeRow> rows = Db.queryOn(c, "SELECT id,basis,candidate_id,ensemble_id,interpretation," +
                            "(SELECT pe.fingerprint FROM plan_ensemble pe WHERE pe.id=ensemble_id) ensemble_fingerprint," +
                            "entry_cost_cents,paths,horizon_days,p5_cents,p25_cents,p50_cents,p75_cents,p95_cents," +
                            "expected_pnl_cents,win_rate_pct,best_cents,worst_cents,breach_probability_pct," +
                            "created_at::text created_at FROM plan_outcome_run WHERE plan_id=? AND context_rev=? " +
                            "AND dataset_id IS NOT DISTINCT FROM ? AND state='CURRENT' ORDER BY created_at",
                    PlanOutcomeService::outcomeRow, plan.id(), plan.context().rev(), datasetId);
            for (OutcomeRow row : rows) runs.add(loadOutcome(c, row));
            ArrayNode comparisons = out.putArray("comparisons");
            List<ComparisonRow> comparisonRows = Db.queryOn(c,
                    "SELECT id,basis,ensemble_id,ensemble_fingerprint,interpretation,fairness,state," +
                            "created_at::text created_at FROM plan_outcome_comparison WHERE plan_id=? " +
                            "AND context_rev=? AND dataset_id IS NOT DISTINCT FROM ? AND state='CURRENT' " +
                            "ORDER BY created_at",
                    r -> new ComparisonRow(r.str("id"), r.str("basis"), r.str("ensemble_id"),
                            r.str("ensemble_fingerprint"), r.str("interpretation"), r.str("fairness"),
                            r.str("state"), r.str("created_at")), plan.id(), plan.context().rev(), datasetId);
            for (ComparisonRow row : comparisonRows) comparisons.add(loadComparison(c, row));
            ArrayNode backtests = out.putArray("backtests");
            Db.queryOn(c, "SELECT id,context_rev,dataset_id,state,backtest_id,candidate_id,evidence_provenance,engine_kind,pricing_mode,confidence,sample_size," +
                            "win_rate,total_pnl_cents,avg_return_on_risk,starting_cents,ending_cents,max_drawdown_pct,demo_underlying," +
                            "created_at::text created_at FROM plan_backtest WHERE plan_id=? " +
                            "ORDER BY (context_rev=?) DESC,(state='CURRENT') DESC,created_at DESC,id DESC LIMIT 20",
                    r -> {
                        ObjectNode n = Json.MAPPER.createObjectNode();
                        put(n, "id", r.str("id")); put(n, "backtestId", r.str("backtest_id"));
                        put(n, "contextRev", intOrNull(r, "context_rev")); put(n, "state", r.str("state"));
                        put(n, "datasetId", r.str("dataset_id"));
                        boolean currentDataset = java.util.Objects.equals(r.str("dataset_id"), datasetId);
                        n.put("currentDataset", currentDataset);
                        n.put("currentContext", r.intv("context_rev") == plan.context().rev() && currentDataset);
                        put(n, "candidateId", r.str("candidate_id")); put(n, "engineKind", r.str("engine_kind"));
                        put(n, "evidenceProvenance", r.str("evidence_provenance"));
                        put(n, "pricingMode", r.str("pricing_mode")); put(n, "confidence", r.str("confidence"));
                        put(n, "sampleSize", intOrNull(r, "sample_size")); put(n, "winRate", r.dblOrNull("win_rate"));
                        put(n, "totalPnlCents", r.lngOrNull("total_pnl_cents"));
                        put(n, "avgReturnOnRisk", r.dblOrNull("avg_return_on_risk"));
                        put(n, "startingCents", r.lngOrNull("starting_cents")); put(n, "endingCents", r.lngOrNull("ending_cents"));
                        put(n, "maxDrawdownPct", r.dblOrNull("max_drawdown_pct"));
                        put(n, "demoUnderlying", boolOrNull(r, "demo_underlying")); put(n, "createdAt", r.str("created_at"));
                        return n;
                    }, plan.id(), plan.context().rev()).forEach(backtests::add);
            return out;
        });
    }

    private static ObjectNode loadOutcome(java.sql.Connection c, OutcomeRow r) throws java.sql.SQLException {
        ObjectNode n = Json.MAPPER.createObjectNode();
        put(n, "id", r.id()); put(n, "basis", r.basis()); put(n, "candidateId", r.candidateId());
        put(n, "ensembleId", r.ensembleId()); put(n, "ensembleFingerprint", r.ensembleFingerprint());
        // The scenario-canvas honesty label survives restoration: a result computed on a pinned
        // fan must say so every time it is shown, not only on the run that created it.
        if (r.ensembleId() != null) {
            Db.queryOn(c, "SELECT pe.spec_model, EXISTS(SELECT 1 FROM plan_ensemble_waypoint w " +
                            "WHERE w.ensemble_id=pe.id)::int pinned FROM plan_ensemble pe WHERE pe.id=?",
                    x -> io.liftandshift.strikebench.sim.PathGenerator.waypointFill(
                            ScenarioSpec.PathModel.valueOf(x.str("spec_model")), x.bool("pinned")).name(),
                    r.ensembleId()).stream().findFirst()
                    .ifPresent(fill -> n.put("waypointFill", fill));
        }
        put(n, "interpretation", r.interpretation());
        put(n, "entryCostCents", r.entry()); put(n, "paths", r.paths()); put(n, "horizonDays", r.horizon());
        put(n, "p5Cents", r.p5()); put(n, "p25Cents", r.p25()); put(n, "p50Cents", r.p50());
        put(n, "p75Cents", r.p75()); put(n, "p95Cents", r.p95()); put(n, "expectedPnlCents", r.expected());
        put(n, "winRatePct", r.winRate()); put(n, "bestCents", r.best()); put(n, "worstCents", r.worst());
        put(n, "breachProbPct", r.breach()); put(n, "createdAt", r.createdAt());
        ArrayNode bands = n.putArray("bands");
        Db.queryOn(c, "SELECT day_index,p10_cents,p50_cents,p90_cents FROM plan_outcome_band " +
                        "WHERE outcome_id=? ORDER BY day_index",
                x -> Map.of("day", x.intv("day_index"), "p10Cents", x.lng("p10_cents"),
                        "p50Cents", x.lng("p50_cents"), "p90Cents", x.lng("p90_cents")), r.id())
                .forEach(x -> bands.add(Json.MAPPER.valueToTree(x)));
        ArrayNode distribution = n.putArray("distribution");
        Db.queryOn(c, "SELECT from_cents,to_cents,sample_count FROM plan_outcome_bucket " +
                        "WHERE outcome_id=? ORDER BY bucket_index",
                x -> Map.of("fromCents", x.lng("from_cents"), "toCents", x.lng("to_cents"),
                        "count", x.intv("sample_count")), r.id())
                .forEach(x -> distribution.add(Json.MAPPER.valueToTree(x)));
        ArrayNode notes = n.putArray("notes");
        Db.queryOn(c, "SELECT note FROM plan_outcome_note WHERE outcome_id=? ORDER BY note_index",
                x -> x.str("note"), r.id()).forEach(notes::add);
        ObjectNode metrics = n.putObject("metrics");
        Db.queryOn(c, "SELECT metric_key,value_number,value_cents,value_text FROM plan_outcome_market_metric " +
                        "WHERE outcome_id=? ORDER BY metric_key",
                x -> new MetricRow(x.str("metric_key"), x.dblOrNull("value_number"),
                        x.lngOrNull("value_cents"), x.str("value_text")), r.id()).forEach(m -> {
            if (m.cents() != null) metrics.put(m.key(), m.cents());
            else if (m.number() != null) metrics.put(m.key(), m.number());
            else if (m.text() != null) metrics.put(m.key(), m.text());
        });
        return n;
    }

    private static ObjectNode loadComparison(java.sql.Connection c, ComparisonRow row)
            throws java.sql.SQLException {
        ObjectNode out = Json.MAPPER.createObjectNode();
        put(out, "id", row.id()); put(out, "basis", row.basis()); put(out, "state", row.state());
        put(out, "ensembleId", row.ensembleId()); put(out, "ensembleFingerprint", row.fingerprint());
        put(out, "interpretation", row.interpretation()); put(out, "fairness", row.fairness());
        put(out, "createdAt", row.createdAt());
        ArrayNode items = out.putArray("items");
        Db.queryOn(c, "SELECT item_key,candidate_id,rank_number,strategy,display_name,qty,entry_cost_cents,max_loss_cents," +
                        "win_rate_pct,expected_pnl_cents,p5_cents,p50_cents,p95_cents,tail_return_score," +
                        "round_trip_fees_cents,economic_verdict,economic_placement,mechanically_eligible," +
                        "decision_score,selected,refusal_reason FROM plan_outcome_comparison_item " +
                        "WHERE comparison_id=? ORDER BY rank_number,item_key",
                r -> new ComparisonItem(r.str("item_key"), r.str("candidate_id"), r.intv("rank_number"),
                        r.str("strategy"), r.str("display_name"), r.intv("qty"), r.lngOrNull("entry_cost_cents"),
                        r.lngOrNull("max_loss_cents"), r.dblOrNull("win_rate_pct"),
                        r.lngOrNull("expected_pnl_cents"), r.lngOrNull("p5_cents"),
                        r.lngOrNull("p50_cents"), r.lngOrNull("p95_cents"), r.dblOrNull("tail_return_score"),
                        r.lng("round_trip_fees_cents"), r.str("economic_verdict"), r.str("economic_placement"),
                        boolOrNull(r, "mechanically_eligible"), r.dblOrNull("decision_score"),
                        r.bool("selected"), r.str("refusal_reason")), row.id())
                .forEach(item -> items.add(Json.MAPPER.valueToTree(item)));
        return out;
    }

    private static void persistPathDetails(java.sql.Connection c, String id, JsonNode result)
            throws java.sql.SQLException {
        int i = 0;
        for (JsonNode band : result.path("bands")) Db.execOn(c,
                "INSERT INTO plan_outcome_band(outcome_id,day_index,p10_cents,p50_cents,p90_cents) VALUES(?,?,?,?,?)",
                id, band.path("day").asInt(i++), band.path("p10Cents").asLong(),
                band.path("p50Cents").asLong(), band.path("p90Cents").asLong());
        i = 0;
        for (JsonNode bucket : result.path("distribution")) Db.execOn(c,
                "INSERT INTO plan_outcome_bucket(outcome_id,bucket_index,from_cents,to_cents,sample_count) VALUES(?,?,?,?,?)",
                id, i++, bucket.path("fromCents").asLong(), bucket.path("toCents").asLong(), bucket.path("count").asInt());
        i = 0;
        for (JsonNode note : result.path("notes")) Db.execOn(c,
                "INSERT INTO plan_outcome_note(outcome_id,note_index,note) VALUES(?,?,?)", id, i++, note.asText());
    }

    private static String backtestEvidence(JsonNode report) {
        if (report.path("demoUnderlying").asBoolean(false)) return "DEMO_FIXTURE";
        return switch (String.valueOf(text(report, "pricingMode"))) {
            case "HISTORICAL_CHAIN" -> "OBSERVED_EOD";
            case "MODELED_FROM_UNDERLYING", "PAYOFF_ONLY" -> "MODELED";
            default -> "UNKNOWN";
        };
    }

    private static void flattenMetrics(java.sql.Connection c, String id, String prefix, JsonNode node)
            throws java.sql.SQLException {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                flattenMetrics(c, id, prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey(), entry.getValue());
            }
        } else if (!node.isArray() && !prefix.isBlank()) {
            if (node.isIntegralNumber() && prefix.toLowerCase().contains("cents")) {
                Db.execOn(c, "INSERT INTO plan_outcome_market_metric(outcome_id,metric_key,value_cents) VALUES(?,?,?)",
                        id, prefix, node.longValue());
            } else if (node.isNumber() || node.isBoolean()) {
                Db.execOn(c, "INSERT INTO plan_outcome_market_metric(outcome_id,metric_key,value_number) VALUES(?,?,?)",
                        id, prefix, node.isBoolean() ? (node.asBoolean() ? 1.0 : 0.0) : node.doubleValue());
            } else if (node.isTextual()) {
                Db.execOn(c, "INSERT INTO plan_outcome_market_metric(outcome_id,metric_key,value_text) VALUES(?,?,?)",
                        id, prefix, node.asText());
            }
        }
    }

    private PreparedEnsemble prepareEnsemble(PathEnsembleService.Ensemble ensemble,
                                             IvSpec rawIv, ScenarioCanvasSpec rawCanvas,
                                             double rateAnnual, SimulationEngine.Preview preview,
                                             JsonNode input) {
        if (ensemble == null) throw new IllegalArgumentException("ensemble is required");
        ScenarioSpec spec = ensemble.spec().sane();
        IvSpec iv = (rawIv == null ? IvSpec.flat(spec.volAnnual()) : rawIv).sane();
        ScenarioCanvasSpec canvas = rawCanvas == null ? null : rawCanvas.sane(spec.horizonDays());
        double[] ivPath = canvasIvPath(spec, iv, canvas, ensemble.anchorDate());
        byte[] rawSpots = encodeMatrix(ensemble.paths());
        byte[] rawIvBytes = encodeVector(ivPath);
        ObjectNode hashInput = Json.MAPPER.createObjectNode();
        hashInput.set("request", requireObject(input, "outcome request"));
        if (canvas != null) {
            hashInput.set("canvas", Json.MAPPER.valueToTree(canvas));
            hashInput.put("calendarAnchorDate", ensemble.anchorDate().toString());
        }
        String inputHash = semanticSha256(hashInput);
        String fingerprint = fingerprint(ensemble, rawSpots, rawIvBytes, rateAnnual, inputHash);
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String source = preview != null && preview.receipt() != null
                ? preview.receipt().anchorSource() : "plan ensemble";
        String freshness = preview != null && preview.receipt() != null
                ? preview.receipt().anchorFreshness() : "MODELED";
        String asOf = preview != null && preview.receipt() != null
                ? preview.receipt().asOf() : now.toString();
        String datasetId = ensemble.scope().analysis().synthetic()
                ? ensemble.scope().analysis().datasetId() : null;
        return new PreparedEnsemble(iv, canvas, spec, rawSpots, rawIvBytes,
                deflate(rawSpots), deflate(rawIvBytes), inputHash, fingerprint, datasetId,
                source, freshness, asOf);
    }

    private static void insertArtifact(java.sql.Connection c, PathEnsembleService.Ensemble ensemble,
                                       PreparedEnsemble prepared, double rateAnnual)
            throws java.sql.SQLException {
        Db.execOn(c, "INSERT INTO ensemble_artifact(fingerprint,model_version,basis,n_paths,n_steps,codec," +
                        "raw_bytes,spot_matrix,iv_path,rate_annual,step_seconds,source_content_hash,pinned) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,0) ON CONFLICT (fingerprint) DO NOTHING",
                prepared.fingerprint(), ensemble.modelVersion(), ensemble.basis().name(), ensemble.paths().length,
                prepared.spec().totalSteps(), CODEC,
                (long) prepared.rawSpots().length + prepared.rawIv().length,
                prepared.spotBytes(), prepared.ivBytes(), rateAnnual,
                23_400.0 / Math.max(1, prepared.spec().stepsPerDay()), prepared.inputHash());
    }

    /** One Plan persistence path for fresh and promoted ensembles alike. */
    private static void persistPlanEnsemble(java.sql.Connection c, String ensembleId, Plan.View plan,
                                            PathEnsembleService.Ensemble ensemble,
                                            PreparedEnsemble prepared,
                                            SimulationEngine.Preview preview)
            throws java.sql.SQLException {
        ScenarioSpec spec = prepared.spec();
        ScenarioSpec.Heston h = spec.heston();
        IvSpec iv = prepared.iv();
        Db.execOn(c, "INSERT INTO plan_ensemble(id,plan_id,context_rev,fingerprint,model_version," +
                        "anchor_spot_cents,anchor_source,anchor_freshness,dataset_id,as_of,input_hash,state," +
                        "spec_model,spec_shape,spec_horizon_days,spec_steps_per_day,spec_drift_annual,spec_vol_annual," +
                        "spec_jumps_per_year,spec_jump_mean,spec_jump_vol,spec_tail_nu,spec_seed,spec_paths," +
                        "iv_start,iv_longrun,iv_shape,heston_kappa,heston_theta,heston_xi,heston_rho,heston_v0," +
                        "iv_drift_per_year,iv_mean_revert_speed,iv_event_day,iv_event_shock_pct,iv_min,iv_max) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                ensembleId, plan.id(), plan.context().rev(), prepared.fingerprint(), ensemble.modelVersion(),
                Math.round(ensemble.spot() * 100), prepared.source(), prepared.freshness(),
                prepared.datasetId(), OffsetDateTime.parse(prepared.asOf()), prepared.inputHash(), "CURRENT",
                spec.model().name(), spec.shape().name(), spec.horizonDays(), spec.stepsPerDay(),
                spec.driftAnnual(), spec.volAnnual(), spec.jumpsPerYear(), spec.jumpMean(), spec.jumpVol(),
                spec.tailNu(), spec.seed(), spec.paths(), iv.startIv(), iv.longRunIv(), "EXPLICIT",
                h == null ? null : h.kappa(), h == null ? null : h.theta(), h == null ? null : h.xi(),
                h == null ? null : h.rho(), h == null ? null : h.v0(), iv.driftPerYear(),
                iv.meanRevertSpeed(), iv.eventDay(), iv.eventShockPct(), iv.minIv(), iv.maxIv());
        // Authored waypoints are part of the spec identity: without these rows a reloaded pinned
        // fan would repaint with no honesty label — a silent lie about how it was made.
        for (ScenarioSpec.Waypoint w : spec.waypoints()) {
            Db.execOn(c, "INSERT INTO plan_ensemble_waypoint(ensemble_id,day_index,price_ratio,tolerance) " +
                    "VALUES(?,?,?,?)", ensembleId, w.dayIndex(), w.priceRatio(), w.tolerance());
        }
        if (prepared.canvas() != null) {
            persistCanvas(c, ensembleId, ensemble.anchorDate(), prepared.canvas());
        }
        persistQuantiles(c, ensembleId, ensemble.paths());
        if (preview != null && preview.decisionMap() != null && preview.decisionMap().levels() != null) {
            for (SimulationEngine.LevelOdds level : preview.decisionMap().levels()) {
                Db.execOn(c, "INSERT INTO plan_level_odds(ensemble_id,level_key,price_cents,direction," +
                                "end_above_probability,end_below_probability,end_beyond_probability,touch_probability," +
                                "touch_ci_low,touch_ci_high,median_first_touch_day) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                        ensembleId, level.key(), Math.round(level.price() * 100), level.direction(),
                        level.endAboveProbability(), level.endBelowProbability(), level.endBeyondProbability(),
                        level.touchProbability(), level.touchCiLow(), level.touchCiHigh(), level.medianFirstTouchDay());
            }
        }
    }

    private static void validateResearchOwnership(Plan.View plan, CurrentPlan current,
                                                   ResearchRow row, ResearchContext active) {
        String activeWorld = canonicalWorld(active.worldId());
        String activeDataset = required(active.datasetId(), "datasetId");
        String activeLane = required(active.marketLane(), "marketLane").toUpperCase(java.util.Locale.ROOT);
        if (!plan.symbol().equalsIgnoreCase(row.symbol()) || !current.symbol().equalsIgnoreCase(row.symbol())) {
            throw new IllegalStateException("This Possible Futures receipt belongs to another symbol.");
        }
        if (!current.marketKind().equals(row.marketKind())) {
            throw new IllegalStateException("This Possible Futures receipt belongs to another market.");
        }
        String planWorld = switch (Plan.MarketKind.valueOf(current.marketKind())) {
            case OBSERVED -> "observed";
            case DEMO -> "demo";
            case SIMULATED -> canonicalWorld(current.worldId());
        };
        if (!row.worldId().equals(activeWorld) || !row.worldId().equals(planWorld)) {
            throw new IllegalStateException("This Possible Futures receipt belongs to another market world.");
        }
        if (!row.marketLane().equals(activeLane)) {
            throw new IllegalStateException("This Possible Futures receipt belongs to another market lane.");
        }
        if (!row.datasetId().equals(activeDataset)) {
            throw new IllegalStateException("This Possible Futures receipt belongs to another analysis dataset.");
        }
        Integer horizon = plan.context().horizonDays();
        ScenarioSpec spec = Json.read(row.spec(), ScenarioSpec.class).sane();
        if (horizon != null && horizon != spec.horizonDays()) {
            throw new IllegalStateException("This Possible Futures receipt belongs to a different Plan horizon.");
        }
    }

    private RehydratedResearch rehydrateResearch(ResearchRow row, ResearchContext active, String owner) {
        if (!CODEC.equals(row.codec())) throw new IllegalStateException("Unsupported ensemble codec " + row.codec());
        ScenarioSpec spec = Json.read(row.spec(), ScenarioSpec.class).sane();
        IvSpec iv = Json.read(row.iv(), IvSpec.class).sane();
        ScenarioCanvasSpec canvas = Json.read(row.canvas(), ScenarioCanvasSpec.class).sane(spec.horizonDays());
        ObjectNode input = parseObject(row.input(), "outcome request");
        SimulationEngine.Preview preview = Json.read(row.preview(), SimulationEngine.Preview.class);
        double[][] paths = decodeMatrix(inflate(row.spotMatrix()), row.nPaths(), row.nSteps() + 1);
        var analysis = new io.liftandshift.strikebench.db.AnalysisContext(owner, row.datasetId());
        String scopeWorld = "observed".equals(row.worldId()) ? null : row.worldId();
        var ensemble = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.valueOf(row.basis()),
                new PathEnsembleService.Scope(row.symbol(), scopeWorld, analysis),
                row.anchorSpotCents() / 100.0, spec, paths, null, row.modelVersion(), row.anchorDate());
        // A helper service reconstructed only from receipt facts; no market/path generator is involved.
        double[] rawIv = decodeVector(inflate(row.ivPath()), row.nSteps() + 1);
        double[] derivedIv = canvasIvPath(spec, iv, canvas, row.anchorDate());
        if (!Arrays.equals(rawIv, derivedIv)) {
            throw new IllegalStateException("Stored IV trajectory no longer matches its normalized recipe");
        }
        PreparedEnsemble rebuilt = prepareEnsemble(ensemble, iv, canvas, row.rateAnnual(), preview, input);
        return new RehydratedResearch(ensemble, preview, rebuilt);
    }

    private static ResearchRow researchRow(Db.Row r) {
        return new ResearchRow(r.str("id"), r.str("fingerprint"), r.str("symbol"), r.str("market_kind"),
                r.str("market_lane"), r.str("world_id"), r.str("dataset_id"), r.str("model_version"),
                r.lng("anchor_spot_cents"), r.date("anchor_date"), r.str("anchor_source"),
                r.str("anchor_freshness"), r.str("as_of"), r.str("input_hash"), r.str("spec"),
                r.str("iv"), r.str("canvas"), r.str("input"), r.str("preview"), r.str("state"),
                r.str("adopted_plan_id"), r.str("adopted_ensemble_id"), r.odt("expires_at"),
                r.str("basis"), r.intv("n_paths"), r.intv("n_steps"), r.str("codec"),
                r.bytes("spot_matrix"), r.bytes("iv_path"), r.dbl("rate_annual"),
                r.dbl("step_seconds"), r.str("source_content_hash"));
    }

    private static ObjectNode requireObject(JsonNode node, String label) {
        if (node == null || node.isNull()) return Json.MAPPER.createObjectNode();
        if (!(node instanceof ObjectNode object)) throw new IllegalArgumentException(label + " must be an object");
        return object;
    }

    private static ObjectNode parseObject(String raw, String label) {
        return requireObject(Json.parse(raw), label);
    }

    private static String canonicalWorld(String world) {
        return world == null || world.isBlank() || "observed".equalsIgnoreCase(world)
                ? "observed" : world.trim();
    }

    private static String marketKindForContext(String lane, String world) {
        if ("DEMO".equalsIgnoreCase(lane)) return Plan.MarketKind.DEMO.name();
        if ("SIMULATED".equalsIgnoreCase(lane)) return Plan.MarketKind.SIMULATED.name();
        // SCENARIO is the analysis dataset axis, not an execution market: its Plan remains
        // owned by the observed/demo world underneath it.
        if ("demo".equalsIgnoreCase(world)) return Plan.MarketKind.DEMO.name();
        if ("observed".equalsIgnoreCase(world)) return Plan.MarketKind.OBSERVED.name();
        return Plan.MarketKind.SIMULATED.name();
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        return value.trim();
    }

    private static void persistQuantiles(java.sql.Connection c, String ensembleId, double[][] paths)
            throws java.sql.SQLException {
        double[] terminal = new double[paths.length];
        for (int i = 0; i < paths.length; i++) terminal[i] = paths[i][paths[i].length - 1];
        Arrays.sort(terminal);
        for (double p : new double[]{0.05, 0.16, 0.50, 0.84, 0.95}) {
            Db.execOn(c, "INSERT INTO plan_ensemble_quantile(ensemble_id,probability,value_cents) VALUES(?,?,?)",
                    ensembleId, p, Math.round(Quantiles.of(terminal, p) * 100));
        }
    }

    private static void persistCanvas(java.sql.Connection c, String ensembleId,
                                      java.time.LocalDate anchorDate, ScenarioCanvasSpec canvas)
            throws java.sql.SQLException {
        ScenarioCanvasSpec.TemplateReceipt t = canvas.template();
        Db.execOn(c, "INSERT INTO plan_ensemble_canvas(ensemble_id,model_version,anchor_date,calendar,dividend_yield_annual,"
                        + "dividend_basis,skew_vol_per_log_moneyness,term_vol_per_sqrt_year,surface_dynamics,"
                        + "settlement_policy,exercise_policy,template_kind,template_source,template_provenance,"
                        + "template_input_as_of,template_window_from,template_window_to,template_observations,"
                        + "template_observed,template_no_hindsight,template_leg_day_provenance,template_note,"
                        + "template_fingerprint) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                ensembleId, ScenarioCanvasSpec.MODEL_VERSION, anchorDate, canvas.calendar(), canvas.dividendYieldAnnual(),
                canvas.dividendBasis(), canvas.skewVolPerLogMoneyness(), canvas.termVolPerSqrtYear(),
                canvas.surfaceDynamics().name(), canvas.settlementPolicy().name(), canvas.exercisePolicy().name(),
                t == null ? null : t.kind().name(), t == null ? null : t.source(),
                t == null ? null : t.provenance(), t == null ? null : t.inputAsOf(),
                t == null ? null : t.windowFrom(), t == null ? null : t.windowTo(),
                t == null ? null : t.observations(), t == null ? null : t.observed(),
                t == null ? null : t.noHindsight(), t == null ? null : t.legDayProvenance(),
                t == null ? null : t.note(), t == null ? null : t.fingerprint());
        for (ScenarioCanvasSpec.IvNode node : canvas.ivNodes()) {
            Db.execOn(c, "INSERT INTO plan_ensemble_canvas_iv_node(ensemble_id,day_index,atm_iv) VALUES(?,?,?)",
                    ensembleId, node.dayIndex(), node.atmIv());
        }
    }

    private static ScenarioCanvasSpec loadCanvas(java.sql.Connection c, String ensembleId, int horizon)
            throws java.sql.SQLException {
        List<ScenarioCanvasSpec.IvNode> nodes = Db.queryOn(c,
                "SELECT day_index,atm_iv FROM plan_ensemble_canvas_iv_node WHERE ensemble_id=? ORDER BY day_index",
                n -> new ScenarioCanvasSpec.IvNode(n.intv("day_index"), n.dbl("atm_iv")), ensembleId);
        List<ScenarioCanvasSpec> rows = Db.queryOn(c,
                "SELECT calendar,dividend_yield_annual,dividend_basis,skew_vol_per_log_moneyness,"
                        + "term_vol_per_sqrt_year,surface_dynamics,settlement_policy,exercise_policy,"
                        + "template_kind,template_source,template_provenance,template_input_as_of,"
                        + "template_window_from,template_window_to,template_observations,template_observed,"
                        + "template_no_hindsight,template_leg_day_provenance,template_note,template_fingerprint "
                        + "FROM plan_ensemble_canvas WHERE ensemble_id=?", r -> {
                    String kind = r.str("template_kind");
                    ScenarioCanvasSpec.TemplateReceipt template = kind == null ? null
                            : new ScenarioCanvasSpec.TemplateReceipt(
                                ScenarioCanvasSpec.TemplateKind.valueOf(kind), r.str("template_source"),
                                r.str("template_provenance"), r.date("template_input_as_of"),
                                r.date("template_window_from"), r.date("template_window_to"),
                                r.intv("template_observations"), r.bool("template_observed"),
                                r.bool("template_no_hindsight"), r.str("template_leg_day_provenance"),
                                r.str("template_note"), r.str("template_fingerprint"));
                    return new ScenarioCanvasSpec(r.str("calendar"), r.dblOrNull("dividend_yield_annual"),
                            r.str("dividend_basis"), r.dbl("skew_vol_per_log_moneyness"),
                            r.dbl("term_vol_per_sqrt_year"),
                            ScenarioCanvasSpec.SurfaceDynamics.valueOf(r.str("surface_dynamics")),
                            ScenarioCanvasSpec.SettlementPolicy.valueOf(r.str("settlement_policy")),
                            ScenarioCanvasSpec.ExercisePolicy.valueOf(r.str("exercise_policy")), nodes, template)
                            .sane(horizon);
                }, ensembleId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static double[] canvasIvPath(ScenarioSpec spec, IvSpec iv, ScenarioCanvasSpec canvas,
                                         java.time.LocalDate anchorDate) {
        double dt = spec.dt();
        if (canvas != null) {
            double[] stepYears = spec.calendarStepYears(anchorDate);
            double elapsed = 0;
            for (double step : stepYears) elapsed += step;
            dt = elapsed / stepYears.length;
        }
        double[] legacy = iv.path(spec.totalSteps(), dt, spec.stepsPerDay());
        if (canvas == null || canvas.ivNodes().isEmpty()) return legacy;
        double[] out = new double[legacy.length];
        int spd = Math.max(1, spec.stepsPerDay());
        for (int i = 0; i < out.length; i++) {
            out[i] = canvas.atmIv(i / spd, spec.horizonDays(), legacy[i]);
        }
        return out;
    }

    private static java.time.Instant parseAsOf(String raw) {
        try { return java.time.Instant.parse(raw); }
        catch (java.time.format.DateTimeParseException e) {
            String iso = raw.replace(' ', 'T');
            if (iso.matches(".*[+-]\\d{2}$")) iso += ":00";
            return java.time.OffsetDateTime.parse(iso).toInstant();
        }
    }

    private static byte[] encodeMatrix(double[][] matrix) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            for (double[] row : matrix) for (double value : row) out.writeDouble(value);
            out.flush(); return bytes.toByteArray();
        } catch (Exception e) { throw new IllegalStateException("Could not encode path ensemble", e); }
    }

    private static byte[] encodeVector(double[] vector) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            for (double value : vector) out.writeDouble(value);
            out.flush(); return bytes.toByteArray();
        } catch (Exception e) { throw new IllegalStateException("Could not encode IV trajectory", e); }
    }

    private static double[][] decodeMatrix(byte[] bytes, int rows, int columns) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            double[][] out = new double[rows][columns];
            for (int r = 0; r < rows; r++) for (int c = 0; c < columns; c++) out[r][c] = in.readDouble();
            if (in.available() != 0) throw new IllegalStateException("Unexpected trailing path bytes");
            return out;
        } catch (Exception e) { throw new IllegalStateException("Could not decode path ensemble", e); }
    }

    private static double[] decodeVector(byte[] bytes, int size) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            double[] out = new double[size]; for (int i = 0; i < size; i++) out[i] = in.readDouble();
            if (in.available() != 0) throw new IllegalStateException("Unexpected trailing IV bytes");
            return out;
        } catch (Exception e) { throw new IllegalStateException("Could not decode IV trajectory", e); }
    }

    private static byte[] deflate(byte[] raw) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (DeflaterOutputStream zip = new DeflaterOutputStream(out)) { zip.write(raw); }
            return out.toByteArray();
        } catch (Exception e) { throw new IllegalStateException("Could not compress outcome artifact", e); }
    }

    private static byte[] inflate(byte[] compressed) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
                in.transferTo(out);
            }
            return out.toByteArray();
        } catch (Exception e) { throw new IllegalStateException("Could not decompress outcome artifact", e); }
    }

    private static String fingerprint(PathEnsembleService.Ensemble ensemble, byte[] spots, byte[] iv,
                                      double rate, String inputHash) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String specIdentity = Json.canonical(normalizeJsonNumbers(
                    Json.MAPPER.valueToTree(ensemble.spec().sane())));
            digest.update((ensemble.basis().name() + '|' + ensemble.modelVersion() + '|' + specIdentity
                    + '|' + Double.toHexString(rate) + '|' + inputHash).getBytes(StandardCharsets.UTF_8));
            digest.update(spots); digest.update(iv);
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) { throw new IllegalStateException("Could not identify outcome artifact", e); }
    }

    private static String sha256(JsonNode node) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Json.canonical(node).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("Could not identify outcome inputs", e); }
    }

    /** JSONB may rewrite 0.0 as 0; identity treats equal numeric values as equal. */
    private static String semanticSha256(JsonNode node) {
        return sha256(normalizeJsonNumbers(node));
    }

    private static JsonNode normalizeJsonNumbers(JsonNode node) {
        if (node == null || node.isNull()) return com.fasterxml.jackson.databind.node.NullNode.instance;
        if (node.isNumber()) {
            java.math.BigDecimal value = node.decimalValue().stripTrailingZeros();
            if (value.signum() == 0) value = java.math.BigDecimal.ZERO;
            return Json.MAPPER.getNodeFactory().numberNode(value);
        }
        if (node.isObject()) {
            ObjectNode out = Json.MAPPER.createObjectNode();
            java.util.ArrayList<String> names = new java.util.ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            for (String name : names) out.set(name, normalizeJsonNumbers(node.get(name)));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = Json.MAPPER.createArrayNode();
            node.forEach(value -> out.add(normalizeJsonNumbers(value)));
            return out;
        }
        return node.deepCopy();
    }

    private static void requireSelectedCandidate(java.sql.Connection c, String planId, int contextRev,
                                                 String candidateId) throws java.sql.SQLException {
        if (Db.queryOn(c, "SELECT id FROM plan_candidate WHERE id=? AND plan_id=? AND context_rev=? " +
                        "AND selected=1 AND state='CURRENT'", r -> r.str("id"), candidateId, planId, contextRev).isEmpty()) {
            throw new IllegalStateException("Select a current structure before running Outcomes.");
        }
    }

    private static void requireCurrentCandidate(java.sql.Connection c, String planId, int contextRev,
                                                String candidateId) throws java.sql.SQLException {
        if (Db.queryOn(c, "SELECT id FROM plan_candidate WHERE id=? AND plan_id=? AND context_rev=? " +
                        "AND state='CURRENT'", r -> r.str("id"), candidateId, planId, contextRev).isEmpty()) {
            throw new IllegalStateException("Comparison contains a candidate outside the current Plan context.");
        }
    }

    private static void requireCurrentEnsembleOn(java.sql.Connection c, String planId, int contextRev,
                                                 String ensembleId, String datasetId) throws java.sql.SQLException {
        if (Db.queryOn(c, "SELECT id FROM plan_ensemble WHERE id=? AND plan_id=? AND context_rev=? " +
                        "AND dataset_id IS NOT DISTINCT FROM ? AND state='CURRENT'",
                r -> r.str("id"), ensembleId, planId, contextRev, datasetId).isEmpty()) {
            throw new IllegalStateException("This path set is no longer current for the Plan and data lane on screen.");
        }
    }

    private static CurrentPlan ownedPlanOn(java.sql.Connection c, String id, String userId, boolean lock)
            throws java.sql.SQLException {
        List<CurrentPlan> rows = Db.queryOn(c, "SELECT p.symbol,p.market_kind,p.world_id,p.active_context_rev,p.version," +
                        "cr.input_hash FROM plans p JOIN plan_context_revision cr ON cr.plan_id=p.id AND cr.rev=p.active_context_rev " +
                        "WHERE p.id=? AND " + ownerClause("p.user_id") + (lock ? " FOR UPDATE OF p" : ""),
                r -> new CurrentPlan(r.str("symbol"), r.str("market_kind"), r.str("world_id"),
                        r.intv("active_context_rev"), r.lng("version"), r.str("input_hash")), id,
                io.liftandshift.strikebench.util.OwnerScope.id(userId));
        if (rows.isEmpty()) throw new ResourceNotFoundException("no such Plan: " + id);
        return rows.getFirst();
    }

    private static EnsembleRow ensembleRow(Db.Row r) {
        String market = r.str("market_kind");
        String world = "SIMULATED".equals(market) ? r.str("world_id") : "DEMO".equals(market) ? "demo" : "observed";
        var analysis = new io.liftandshift.strikebench.db.AnalysisContext(r.str("user_id"), r.str("dataset_id"));
        return new EnsembleRow(r.str("id"), r.str("fingerprint"), r.str("basis"), r.intv("context_rev"),
                r.str("dataset_id"), r.str("state"), r.str("model_version"),
                r.str("symbol"), world, analysis, r.lng("anchor_spot_cents"), r.str("anchor_source"),
                r.str("anchor_freshness"), r.str("as_of"), r.str("spec_model"), r.str("spec_shape"),
                r.intv("spec_horizon_days"), r.intv("spec_steps_per_day"), r.dbl("spec_drift_annual"),
                r.dbl("spec_vol_annual"), r.dbl("spec_jumps_per_year"), r.dbl("spec_jump_mean"),
                r.dbl("spec_jump_vol"), r.dbl("spec_tail_nu"), r.lng("spec_seed"), r.intv("spec_paths"),
                r.dblOrNull("heston_kappa"), r.dblOrNull("heston_theta"), r.dblOrNull("heston_xi"),
                r.dblOrNull("heston_rho"), r.dblOrNull("heston_v0"), r.dbl("iv_start"),
                r.dbl("iv_longrun"), r.dbl("iv_drift_per_year"), r.dbl("iv_mean_revert_speed"),
                r.intv("iv_event_day"), r.dbl("iv_event_shock_pct"), r.dbl("iv_min"), r.dbl("iv_max"),
                r.dbl("rate_annual"), r.dbl("step_seconds"), r.intv("n_paths"), r.intv("n_steps"), r.str("codec"),
                r.bytes("spot_matrix"), r.bytes("iv_path"));
    }

    private static OutcomeRow outcomeRow(Db.Row r) {
        return new OutcomeRow(r.str("id"), r.str("basis"), r.str("candidate_id"), r.str("ensemble_id"),
                r.str("ensemble_fingerprint"),
                r.str("interpretation"), r.lngOrNull("entry_cost_cents"), intOrNull(r, "paths"),
                intOrNull(r, "horizon_days"), r.lngOrNull("p5_cents"), r.lngOrNull("p25_cents"),
                r.lngOrNull("p50_cents"), r.lngOrNull("p75_cents"), r.lngOrNull("p95_cents"),
                r.lngOrNull("expected_pnl_cents"), r.dblOrNull("win_rate_pct"), r.lngOrNull("best_cents"),
                r.lngOrNull("worst_cents"), r.dblOrNull("breach_probability_pct"), r.str("created_at"));
    }

    private static Long endingDelta(JsonNode r) {
        Long start = longOrNull(r, "startingCents"), end = longOrNull(r, "endingCents");
        return start == null || end == null ? null : end - start;
    }
    private static Long maxDrawdownCents(JsonNode r) { return null; }
    private static String ownerClause(String column) { return column + "=?::text"; }
    private static String text(JsonNode n, String k) { JsonNode v=n==null?null:n.get(k); return v==null||v.isNull()?null:v.asText(); }
    private static Long longOrNull(JsonNode n, String k) { JsonNode v=n==null?null:n.get(k); return v==null||v.isNull()?null:v.longValue(); }
    private static Integer integerOrNull(JsonNode n, String k) { JsonNode v=n==null?null:n.get(k); return v==null||v.isNull()?null:v.intValue(); }
    private static Double doubleOrNull(JsonNode n, String k) { JsonNode v=n==null?null:n.get(k); return v==null||v.isNull()?null:v.doubleValue(); }
    private static Integer intOrNull(Db.Row r, String k) { Long v=r.lngOrNull(k); return v==null?null:Math.toIntExact(v); }
    private static Boolean boolOrNull(Db.Row r, String k) { Long v=r.lngOrNull(k); return v==null?null:v!=0; }
    private static void put(ObjectNode n,String k,String v){if(v!=null)n.put(k,v);} private static void put(ObjectNode n,String k,Long v){if(v!=null)n.put(k,v);}
    private static void put(ObjectNode n,String k,Integer v){if(v!=null)n.put(k,v);} private static void put(ObjectNode n,String k,Double v){if(v!=null)n.put(k,v);}
    private static void put(ObjectNode n,String k,Boolean v){if(v!=null)n.put(k,v);}

    private record CurrentPlan(String symbol, String marketKind, String worldId, int contextRev, long version, String contextHash) {}
    private record PreparedEnsemble(IvSpec iv, ScenarioCanvasSpec canvas, ScenarioSpec spec,
                                    byte[] rawSpots, byte[] rawIv, byte[] spotBytes, byte[] ivBytes,
                                    String inputHash, String fingerprint, String datasetId,
                                    String source, String freshness, String asOf) {}
    private record ReceiptIdentity(String id, String expiresAt) {}
    private record AdoptionResult(String ensembleId, JsonNode preview) {}
    private record RehydratedResearch(PathEnsembleService.Ensemble ensemble,
                                      SimulationEngine.Preview preview,
                                      PreparedEnsemble prepared) {}
    private record ResearchRow(String id, String fingerprint, String symbol, String marketKind,
                               String marketLane, String worldId, String datasetId, String modelVersion,
                               long anchorSpotCents, LocalDate anchorDate, String anchorSource,
                               String anchorFreshness, String asOf, String inputHash, String spec,
                               String iv, String canvas, String input, String preview, String state,
                               String adoptedPlanId, String adoptedEnsembleId, OffsetDateTime expiresAt,
                               String basis, int nPaths, int nSteps, String codec, byte[] spotMatrix,
                               byte[] ivPath, double rateAnnual, double stepSeconds,
                               String sourceContentHash) {}
    private record MetricRow(String key, Double number, Long cents, String text) {}
    private record OutcomeRow(String id,String basis,String candidateId,String ensembleId,
                              String ensembleFingerprint,String interpretation,Long entry,
                              Integer paths,Integer horizon,Long p5,Long p25,Long p50,Long p75,Long p95,Long expected,
                              Double winRate,Long best,Long worst,Double breach,String createdAt) {}
    private record ComparisonRow(String id,String basis,String ensembleId,String fingerprint,
                                 String interpretation,String fairness,String state,String createdAt) {}
    private record EnsembleRow(String id,String fingerprint,String basis,int contextRev,String datasetId,String state,
                               String modelVersion,String symbol,String worldId,
                               io.liftandshift.strikebench.db.AnalysisContext analysis,long anchorSpotCents,
                               String anchorSource,String anchorFreshness,String asOf,String model,String shape,int horizon,
                               int stepsPerDay,double drift,double vol,double jumps,double jumpMean,double jumpVol,double tailNu,
                               long seed,int paths,Double hestonKappa,Double hestonTheta,Double hestonXi,Double hestonRho,
                               Double hestonV0,double ivStart,double ivLongRun,double ivDrift,double ivMeanRevert,
                               int ivEventDay,double ivEventShock,double ivMin,double ivMax,double rate,double stepSeconds,int nPaths,int nSteps,
                               String codec,byte[] spotMatrix,byte[] ivPath) {}
}
