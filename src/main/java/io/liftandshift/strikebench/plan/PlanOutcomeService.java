package io.liftandshift.strikebench.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.liftandshift.strikebench.backtest.Backtester;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.sim.IvSpec;
import io.liftandshift.strikebench.sim.PathEnsembleService;
import io.liftandshift.strikebench.sim.ScenarioSpec;
import io.liftandshift.strikebench.sim.SimulationEngine;
import io.liftandshift.strikebench.util.Ids;
import io.liftandshift.strikebench.util.Json;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
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
                                 PathEnsembleService.Ensemble ensemble, IvSpec iv,
                                 double rateAnnual, double stepSeconds, String anchorSource,
                                 String anchorFreshness, String asOf) {}

    public record SavedOutcome(String id, String basis, String state, String candidateId,
                               String ensembleId, JsonNode result, String createdAt) {}

    public record SavedBacktest(String id, String state, String backtestId,
                                String engineKind, JsonNode summary, String createdAt) {}

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
        if (ensemble == null) throw new IllegalArgumentException("ensemble is required");
        IvSpec iv = (rawIv == null ? IvSpec.flat(ensemble.spec().volAnnual()) : rawIv).sane();
        double[] ivPath = iv.path(ensemble.spec().totalSteps(), ensemble.spec().dt(),
                ensemble.spec().stepsPerDay());
        byte[] rawSpots = encodeMatrix(ensemble.paths());
        byte[] rawIvBytes = encodeVector(ivPath);
        byte[] spotBytes = deflate(rawSpots);
        byte[] ivBytes = deflate(rawIvBytes);
        String inputHash = sha256(input == null ? Json.MAPPER.createObjectNode() : input);
        String fingerprint = fingerprint(ensemble, rawSpots, rawIvBytes, rateAnnual, inputHash);
        String ensembleId = Ids.newId("pen");
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        ScenarioSpec spec = ensemble.spec().sane();
        ScenarioSpec.Heston h = spec.heston();
        String source = preview != null && preview.receipt() != null
                ? preview.receipt().anchorSource() : "plan ensemble";
        String freshness = preview != null && preview.receipt() != null
                ? preview.receipt().anchorFreshness() : "MODELED";
        String asOf = preview != null && preview.receipt() != null
                ? preview.receipt().asOf() : now.toString();

        db.tx(c -> {
            CurrentPlan current = ownedPlanOn(c, plan.id(), userId, true);
            if (current.contextRev() != plan.context().rev()) {
                throw new IllegalStateException("The Plan assumptions changed while the ensemble was running.");
            }
            Db.execOn(c, "INSERT INTO ensemble_artifact(fingerprint,model_version,basis,n_paths,n_steps,codec," +
                            "raw_bytes,spot_matrix,iv_path,rate_annual,step_seconds,source_content_hash,pinned) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,0) ON CONFLICT (fingerprint) DO NOTHING",
                    fingerprint, ensemble.modelVersion(), ensemble.basis().name(), ensemble.paths().length,
                    spec.totalSteps(), CODEC, (long) rawSpots.length + rawIvBytes.length,
                    spotBytes, ivBytes, rateAnnual, 23_400.0 / Math.max(1, spec.stepsPerDay()), inputHash);
            Db.execOn(c, "UPDATE plan_ensemble pe SET state='STALE' FROM ensemble_artifact ea " +
                            "WHERE pe.fingerprint=ea.fingerprint AND pe.plan_id=? AND pe.context_rev=? " +
                            "AND ea.basis=? AND pe.state='CURRENT'",
                    plan.id(), plan.context().rev(), ensemble.basis().name());
            Db.execOn(c, "INSERT INTO plan_ensemble(id,plan_id,context_rev,fingerprint,model_version," +
                            "anchor_spot_cents,anchor_source,anchor_freshness,dataset_id,as_of,input_hash,state," +
                            "spec_model,spec_shape,spec_horizon_days,spec_steps_per_day,spec_drift_annual,spec_vol_annual," +
                            "spec_jumps_per_year,spec_jump_mean,spec_jump_vol,spec_tail_nu,spec_seed,spec_paths," +
                            "iv_start,iv_longrun,iv_shape,heston_kappa,heston_theta,heston_xi,heston_rho,heston_v0," +
                            "iv_drift_per_year,iv_mean_revert_speed,iv_event_day,iv_event_shock_pct,iv_min,iv_max) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    ensembleId, plan.id(), plan.context().rev(), fingerprint, ensemble.modelVersion(),
                    Math.round(ensemble.spot() * 100), source, freshness,
                    ensemble.scope().analysis().synthetic() ? ensemble.scope().analysis().datasetId() : null,
                    OffsetDateTime.parse(asOf), inputHash, "CURRENT", spec.model().name(), spec.shape().name(),
                    spec.horizonDays(), spec.stepsPerDay(), spec.driftAnnual(), spec.volAnnual(),
                    spec.jumpsPerYear(), spec.jumpMean(), spec.jumpVol(), spec.tailNu(), spec.seed(), spec.paths(),
                    iv.startIv(), iv.longRunIv(), "EXPLICIT", h == null ? null : h.kappa(),
                    h == null ? null : h.theta(), h == null ? null : h.xi(), h == null ? null : h.rho(),
                    h == null ? null : h.v0(), iv.driftPerYear(), iv.meanRevertSpeed(), iv.eventDay(),
                    iv.eventShockPct(), iv.minIv(), iv.maxIv());
            persistQuantiles(c, ensembleId, ensemble.paths());
            if (preview != null && preview.decisionMap() != null) {
                for (SimulationEngine.LevelOdds level : preview.decisionMap().levels()) {
                    Db.execOn(c, "INSERT INTO plan_level_odds(ensemble_id,level_key,price_cents,direction," +
                                    "end_above_probability,end_below_probability,end_beyond_probability,touch_probability," +
                                    "touch_ci_low,touch_ci_high,median_first_touch_day) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                            ensembleId, level.key(), Math.round(level.price() * 100), level.direction(),
                            level.endAboveProbability(), level.endBelowProbability(), level.endBeyondProbability(),
                            level.touchProbability(), level.touchCiLow(), level.touchCiHigh(), level.medianFirstTouchDay());
                }
            }
            return null;
        });
        return new StoredEnsemble(ensembleId, fingerprint, ensemble.basis().name(), ensemble, iv,
                rateAnnual, 23_400.0 / Math.max(1, spec.stepsPerDay()), source, freshness, asOf);
    }

    /** Rehydrate the stored matrix, IV path recipe, rate, model identity, and exact context. */
    public StoredEnsemble loadEnsemble(String userId, String planId, String ensembleId) {
        return db.with(c -> {
            ownedPlanOn(c, planId, userId, false);
            List<EnsembleRow> rows = Db.queryOn(c, "SELECT pe.id,pe.fingerprint,ea.basis,pe.model_version," +
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
            if (rows.isEmpty()) throw new NoSuchElementException("no such Plan ensemble: " + ensembleId);
            EnsembleRow r = rows.getFirst();
            if (!CODEC.equals(r.codec())) throw new IllegalStateException("Unsupported ensemble codec " + r.codec());
            double[][] paths = decodeMatrix(inflate(r.spotMatrix()), r.nPaths(), r.nSteps() + 1);
            ScenarioSpec.Heston h = r.hestonKappa() == null ? null : new ScenarioSpec.Heston(
                    r.hestonKappa(), r.hestonTheta(), r.hestonXi(), r.hestonRho(), r.hestonV0());
            ScenarioSpec spec = new ScenarioSpec(ScenarioSpec.PathModel.valueOf(r.model()),
                    ScenarioSpec.Shape.valueOf(r.shape()), r.horizon(), r.stepsPerDay(), r.drift(), r.vol(),
                    r.jumps(), r.jumpMean(), r.jumpVol(), r.tailNu(), h, r.seed(), r.paths());
            IvSpec iv = new IvSpec(r.ivStart(), r.ivDrift(), r.ivMeanRevert(), r.ivLongRun(),
                    r.ivEventDay(), r.ivEventShock(), r.ivMin(), r.ivMax()).sane();
            double[] storedIv = decodeVector(inflate(r.ivPath()), r.nSteps() + 1);
            double[] derivedIv = iv.path(r.nSteps(), spec.dt(), spec.stepsPerDay());
            if (!Arrays.equals(storedIv, derivedIv)) {
                throw new IllegalStateException("Stored IV trajectory no longer matches its normalized recipe");
            }
            var scope = new PathEnsembleService.Scope(r.symbol(), r.worldId(), r.analysis());
            var ensemble = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.valueOf(r.basis()),
                    scope, r.anchorSpotCents() / 100.0, spec, paths, null, r.modelVersion());
            return new StoredEnsemble(r.id(), r.fingerprint(), r.basis(), ensemble, iv,
                    r.rate(), r.stepSeconds(), r.anchorSource(), r.anchorFreshness(), r.asOf());
        });
    }

    public StoredEnsemble latestEnsemble(String userId, Plan.View plan, String basis) {
        String id = db.with(c -> Db.queryOn(c, "SELECT pe.id FROM plan_ensemble pe " +
                        "JOIN ensemble_artifact ea ON ea.fingerprint=pe.fingerprint " +
                        "WHERE pe.plan_id=? AND pe.context_rev=? AND pe.state='CURRENT' AND ea.basis=? " +
                        "ORDER BY pe.created_at DESC LIMIT 1", r -> r.str("id"),
                plan.id(), plan.context().rev(), basis).stream().findFirst().orElse(null));
        return id == null ? null : loadEnsemble(userId, plan.id(), id);
    }

    public SavedOutcome savePathOutcome(String userId, Plan.View plan, long expectedVersion,
                                        String candidateId, StoredEnsemble stored,
                                        JsonNode result, JsonNode input, String interpretation) {
        if (stored == null || result == null) throw new IllegalArgumentException("outcome result is required");
        String id = Ids.newId("por");
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String inputHash = sha256(input == null ? Json.MAPPER.createObjectNode() : input);
        String state = db.tx(c -> {
            CurrentPlan current = ownedPlanOn(c, plan.id(), userId, true);
            if (current.version() != expectedVersion || current.contextRev() != plan.context().rev()) {
                throw new IllegalStateException("This Plan changed while Outcomes was running. Reload it before saving the result.");
            }
            requireSelectedCandidate(c, plan.id(), plan.context().rev(), candidateId);
            Db.execOn(c, "UPDATE plan_outcome_run SET state='STALE' WHERE plan_id=? AND context_rev=? " +
                    "AND basis=? AND state='CURRENT'", plan.id(), plan.context().rev(), stored.basis());
            Db.execOn(c, "INSERT INTO plan_outcome_run(id,plan_id,context_rev,candidate_id,ensemble_id,basis," +
                            "interpretation,entry_cost_cents,paths,horizon_days,p5_cents,p25_cents,p50_cents," +
                            "p75_cents,p95_cents,expected_pnl_cents,win_rate_pct,best_cents,worst_cents," +
                            "breach_probability_pct,input_hash,engine_version,state,created_at) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, plan.id(), plan.context().rev(), candidateId, stored.id(), stored.basis(), interpretation,
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
                                        String interpretation) {
        String id = Ids.newId("por");
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String inputHash = sha256(input == null ? Json.MAPPER.createObjectNode() : input);
        db.tx(c -> {
            CurrentPlan current = ownedPlanOn(c, plan.id(), userId, true);
            if (current.version() != expectedVersion || current.contextRev() != plan.context().rev()) {
                throw new IllegalStateException("This Plan changed while market odds were running.");
            }
            requireSelectedCandidate(c, plan.id(), plan.context().rev(), candidateId);
            Db.execOn(c, "UPDATE plan_outcome_run SET state='STALE' WHERE plan_id=? AND context_rev=? " +
                    "AND basis='RISK_NEUTRAL' AND state='CURRENT'", plan.id(), plan.context().rev());
            Db.execOn(c, "INSERT INTO plan_outcome_run(id,plan_id,context_rev,candidate_id,basis,interpretation," +
                            "entry_cost_cents,expected_pnl_cents,input_hash,engine_version,state,created_at) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", id, plan.id(), plan.context().rev(), candidateId,
                    "RISK_NEUTRAL", interpretation, longOrNull(result, "entryCostCents"),
                    longOrNull(result, "expectedValueAfterFeesCents"), inputHash, ENGINE_VERSION, "CURRENT", now);
            flattenMetrics(c, id, "", result);
            return null;
        });
        return new SavedOutcome(id, "RISK_NEUTRAL", "CURRENT", candidateId, null, result, now.toString());
    }

    public SavedBacktest saveBacktest(String userId, Plan.View plan, long expectedVersion,
                                      String candidateId, String engineKind, JsonNode report,
                                      JsonNode input) {
        String id = Ids.newId("pbt");
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String inputHash = sha256(input == null ? Json.MAPPER.createObjectNode() : input);
        db.tx(c -> {
            CurrentPlan current = ownedPlanOn(c, plan.id(), userId, true);
            if (current.version() != expectedVersion || current.contextRev() != plan.context().rev()) {
                throw new IllegalStateException("This Plan changed while the historical replay was running.");
            }
            requireSelectedCandidate(c, plan.id(), plan.context().rev(), candidateId);
            Db.execOn(c, "UPDATE plan_backtest SET state='STALE' WHERE plan_id=? AND context_rev=? AND state='CURRENT'",
                    plan.id(), plan.context().rev());
            Db.execOn(c, "INSERT INTO plan_backtest(id,plan_id,context_rev,backtest_id,candidate_id,basis,as_of," +
                            "sample_size,win_rate,total_pnl_cents,max_drawdown_cents,avg_return_on_risk," +
                            "evidence_provenance,input_hash,engine_version,state,engine_kind,pricing_mode,confidence," +
                            "starting_cents,ending_cents,max_drawdown_pct,demo_underlying,created_at) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, plan.id(), plan.context().rev(), text(report, "id"), candidateId, "HISTORICAL_REPLAY", now,
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
            if (!linked) throw new NoSuchElementException("no such Plan backtest: " + backtestId);
            return null;
        });
    }

    /** Latest current results for the Plan's current context, reconstructed from normalized rows. */
    public ObjectNode latest(String userId, Plan.View plan) {
        return db.with(c -> {
            ownedPlanOn(c, plan.id(), userId, false);
            ObjectNode out = Json.MAPPER.createObjectNode();
            ArrayNode runs = out.putArray("outcomes");
            List<OutcomeRow> rows = Db.queryOn(c, "SELECT id,basis,candidate_id,ensemble_id,interpretation," +
                            "entry_cost_cents,paths,horizon_days,p5_cents,p25_cents,p50_cents,p75_cents,p95_cents," +
                            "expected_pnl_cents,win_rate_pct,best_cents,worst_cents,breach_probability_pct," +
                            "created_at::text created_at FROM plan_outcome_run WHERE plan_id=? AND context_rev=? " +
                            "AND state='CURRENT' ORDER BY created_at",
                    PlanOutcomeService::outcomeRow, plan.id(), plan.context().rev());
            for (OutcomeRow row : rows) runs.add(loadOutcome(c, row));
            ArrayNode backtests = out.putArray("backtests");
            Db.queryOn(c, "SELECT id,context_rev,state,backtest_id,candidate_id,evidence_provenance,engine_kind,pricing_mode,confidence,sample_size," +
                            "win_rate,total_pnl_cents,avg_return_on_risk,starting_cents,ending_cents,max_drawdown_pct,demo_underlying," +
                            "created_at::text created_at FROM plan_backtest WHERE plan_id=? " +
                            "ORDER BY (context_rev=?) DESC,(state='CURRENT') DESC,created_at DESC,id DESC LIMIT 20",
                    r -> {
                        ObjectNode n = Json.MAPPER.createObjectNode();
                        put(n, "id", r.str("id")); put(n, "backtestId", r.str("backtest_id"));
                        put(n, "contextRev", intOrNull(r, "context_rev")); put(n, "state", r.str("state"));
                        n.put("currentContext", r.intv("context_rev") == plan.context().rev());
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
        put(n, "ensembleId", r.ensembleId()); put(n, "interpretation", r.interpretation());
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

    private static void persistQuantiles(java.sql.Connection c, String ensembleId, double[][] paths)
            throws java.sql.SQLException {
        double[] terminal = new double[paths.length];
        for (int i = 0; i < paths.length; i++) terminal[i] = paths[i][paths[i].length - 1];
        Arrays.sort(terminal);
        for (double p : new double[]{0.05, 0.16, 0.50, 0.84, 0.95}) {
            Db.execOn(c, "INSERT INTO plan_ensemble_quantile(ensemble_id,probability,value_cents) VALUES(?,?,?)",
                    ensembleId, p, Math.round(quantile(terminal, p) * 100));
        }
    }

    private static double quantile(double[] sorted, double p) {
        if (sorted.length == 1) return sorted[0];
        double pos = p * (sorted.length - 1); int lo = (int) Math.floor(pos); int hi = (int) Math.ceil(pos);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo);
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
            digest.update((ensemble.basis().name() + '|' + ensemble.modelVersion() + '|' + ensemble.spec()
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

    private static void requireSelectedCandidate(java.sql.Connection c, String planId, int contextRev,
                                                 String candidateId) throws java.sql.SQLException {
        if (Db.queryOn(c, "SELECT id FROM plan_candidate WHERE id=? AND plan_id=? AND context_rev=? " +
                        "AND selected=1 AND state='CURRENT'", r -> r.str("id"), candidateId, planId, contextRev).isEmpty()) {
            throw new IllegalStateException("Select a current structure before running Outcomes.");
        }
    }

    private static CurrentPlan ownedPlanOn(java.sql.Connection c, String id, String userId, boolean lock)
            throws java.sql.SQLException {
        List<CurrentPlan> rows = Db.queryOn(c, "SELECT p.symbol,p.market_kind,p.world_id,p.active_context_rev,p.version," +
                        "cr.input_hash FROM plans p JOIN plan_context_revision cr ON cr.plan_id=p.id AND cr.rev=p.active_context_rev " +
                        "WHERE p.id=? AND " + ownerClause("p.user_id") + (lock ? " FOR UPDATE OF p" : ""),
                r -> new CurrentPlan(r.str("symbol"), r.str("market_kind"), r.str("world_id"),
                        r.intv("active_context_rev"), r.lng("version"), r.str("input_hash")), id, userId, userId);
        if (rows.isEmpty()) throw new NoSuchElementException("no such Plan: " + id);
        return rows.getFirst();
    }

    private static EnsembleRow ensembleRow(Db.Row r) {
        String market = r.str("market_kind");
        String world = "SIMULATED".equals(market) ? r.str("world_id") : "DEMO".equals(market) ? "demo" : "observed";
        var analysis = new io.liftandshift.strikebench.db.AnalysisContext(r.str("user_id"), r.str("dataset_id"));
        return new EnsembleRow(r.str("id"), r.str("fingerprint"), r.str("basis"), r.str("model_version"),
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
    private static String ownerClause(String column) { return "(" + column + "=?::text OR (?::text IS NULL AND " + column + " IS NULL))"; }
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
    private record MetricRow(String key, Double number, Long cents, String text) {}
    private record OutcomeRow(String id,String basis,String candidateId,String ensembleId,String interpretation,Long entry,
                              Integer paths,Integer horizon,Long p5,Long p25,Long p50,Long p75,Long p95,Long expected,
                              Double winRate,Long best,Long worst,Double breach,String createdAt) {}
    private record EnsembleRow(String id,String fingerprint,String basis,String modelVersion,String symbol,String worldId,
                               io.liftandshift.strikebench.db.AnalysisContext analysis,long anchorSpotCents,
                               String anchorSource,String anchorFreshness,String asOf,String model,String shape,int horizon,
                               int stepsPerDay,double drift,double vol,double jumps,double jumpMean,double jumpVol,double tailNu,
                               long seed,int paths,Double hestonKappa,Double hestonTheta,Double hestonXi,Double hestonRho,
                               Double hestonV0,double ivStart,double ivLongRun,double ivDrift,double ivMeanRevert,
                               int ivEventDay,double ivEventShock,double ivMin,double ivMax,double rate,double stepSeconds,int nPaths,int nSteps,
                               String codec,byte[] spotMatrix,byte[] ivPath) {}
}
