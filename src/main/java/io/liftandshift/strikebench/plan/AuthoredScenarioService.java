package io.liftandshift.strikebench.plan;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.sim.PathGenerator;
import io.liftandshift.strikebench.sim.ScenarioSpec;
import io.liftandshift.strikebench.util.Ids;
import io.liftandshift.strikebench.util.OwnerScope;
import io.liftandshift.strikebench.util.ResourceNotFoundException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Persistence for the scenario canvas's authored paths. An authored scenario is never a fresh
 * world: it authors FROM a stored Plan fan ({@code base_ensemble_id} → {@code plan_ensemble}),
 * so "author the path" opens the SAME simulation spine every band quotes. Each row freezes the
 * authored spec (waypoints included), the model-honesty label for how those waypoints are filled
 * (standing decision 9: non-Gaussian fills are GUIDED_INTERPOLATION, never presented as exact
 * conditional sampling), and an immutable fingerprint receipt.
 */
public final class AuthoredScenarioService {

    public record Authored(String id, String planId, int contextRev, String baseEnsembleId,
                           ScenarioSpec spec, String waypointFill, String fingerprint,
                           String createdAt, String title) {
        /** Pre-title constructor shape kept for existing callers and tests. */
        public Authored(String id, String planId, int contextRev, String baseEnsembleId,
                        ScenarioSpec spec, String waypointFill, String fingerprint, String createdAt) {
            this(id, planId, contextRev, baseEnsembleId, spec, waypointFill, fingerprint, createdAt, null);
        }
    }

    private final Db db;
    private final Clock clock;

    public AuthoredScenarioService(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    /**
     * Freeze an authored scenario against the stored fan it was authored from. The base ensemble
     * must belong to the same Plan and the context revision on screen — authoring against a stale
     * fan is refused, not silently re-based.
     */
    public Authored save(String userId, Plan.View plan, String baseEnsembleId, ScenarioSpec rawSpec) {
        return save(userId, plan, baseEnsembleId, rawSpec, null);
    }

    /** Same freeze with the author's own name for the scenario ("Earnings dip, then grind back"). */
    public Authored save(String userId, Plan.View plan, String baseEnsembleId, ScenarioSpec rawSpec,
                         String rawTitle) {
        if (plan == null) throw new IllegalArgumentException("plan is required");
        String title = rawTitle == null || rawTitle.isBlank() ? null : rawTitle.trim();
        if (title != null && title.length() > 120) title = title.substring(0, 120);
        final String scenarioTitle = title;
        if (baseEnsembleId == null || baseEnsembleId.isBlank()) {
            throw new IllegalArgumentException("an authored scenario authors FROM a stored fan — base ensemble id is required");
        }
        if (rawSpec == null) throw new IllegalArgumentException("scenario specification is required");
        ScenarioSpec spec = rawSpec.sane();
        String fill = PathGenerator.waypointFill(spec).name();
        String id = Ids.newId("auth");
        return db.tx(c -> {
            PlanWriteGuard.requireMutable(c, plan.id(), userId);
            List<Long> currentRev = Db.queryOn(c, "SELECT active_context_rev FROM plans WHERE id=?",
                    r -> r.lng("active_context_rev"), plan.id());
            if (currentRev.isEmpty() || currentRev.getFirst() != plan.context().rev()) {
                throw new IllegalStateException("The Plan assumptions changed while the scenario was being authored.");
            }
            List<String> baseRows = Db.queryOn(c, "SELECT pe.fingerprint FROM plan_ensemble pe " +
                            "WHERE pe.id=? AND pe.plan_id=? AND pe.context_rev=?",
                    r -> r.str("fingerprint"), baseEnsembleId, plan.id(), plan.context().rev());
            if (baseRows.isEmpty()) {
                throw new IllegalStateException("The path set this scenario authors from does not belong " +
                        "to the current Plan assumptions. Run Outcomes again, then author.");
            }
            String fingerprint = fingerprint(baseRows.getFirst(), spec, fill);
            ScenarioSpec.Heston h = spec.heston();
            Db.execOn(c, "INSERT INTO authored_scenario(id,plan_id,context_rev,base_ensemble_id," +
                            "spec_model,spec_shape,spec_horizon_days,spec_steps_per_day,spec_drift_annual," +
                            "spec_vol_annual,spec_jumps_per_year,spec_jump_mean,spec_jump_vol,spec_tail_nu," +
                            "spec_seed,spec_paths,heston_kappa,heston_theta,heston_xi,heston_rho,heston_v0," +
                            "waypoint_fill,fingerprint,title,created_at) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, plan.id(), plan.context().rev(), baseEnsembleId,
                    spec.model().name(), spec.shape().name(), spec.horizonDays(), spec.stepsPerDay(),
                    spec.driftAnnual(), spec.volAnnual(), spec.jumpsPerYear(), spec.jumpMean(),
                    spec.jumpVol(), spec.tailNu(), spec.seed(), spec.paths(),
                    h == null ? null : h.kappa(), h == null ? null : h.theta(),
                    h == null ? null : h.xi(), h == null ? null : h.rho(), h == null ? null : h.v0(),
                    fill, fingerprint, scenarioTitle,
                    java.time.OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC));
            for (ScenarioSpec.Waypoint w : spec.waypoints()) {
                Db.execOn(c, "INSERT INTO authored_scenario_waypoint(scenario_id,day_index,price_ratio,tolerance) " +
                        "VALUES(?,?,?,?)", id, w.dayIndex(), w.priceRatio(), w.tolerance());
            }
            return row(c, plan.id(), id);
        });
    }

    /** Authored scenarios for the Plan context on screen, newest first. */
    public List<Authored> list(String userId, String planId, int contextRev) {
        return db.with(c -> {
            requireOwnedPlan(c, planId, userId);
            List<Authored> heads = Db.queryOn(c, SELECT + "WHERE a.plan_id=? AND a.context_rev=? " +
                            "ORDER BY a.created_at DESC, a.id DESC",
                    AuthoredScenarioService::head, planId, contextRev);
            List<Authored> out = new ArrayList<>(heads.size());
            for (Authored head : heads) out.add(withWaypoints(c, head));
            return out;
        });
    }

    /**
     * Every authored scenario the Plan owns, newest first, ANY context revision — the studio
     * shows older-assumption scenarios with an explicit staleness explanation instead of
     * silently dropping them when the Plan context moves on.
     */
    public List<Authored> listAll(String userId, String planId) {
        return db.with(c -> {
            requireOwnedPlan(c, planId, userId);
            List<Authored> heads = Db.queryOn(c, SELECT + "WHERE a.plan_id=? " +
                            "ORDER BY a.created_at DESC, a.id DESC",
                    AuthoredScenarioService::head, planId);
            List<Authored> out = new ArrayList<>(heads.size());
            for (Authored head : heads) out.add(withWaypoints(c, head));
            return out;
        });
    }

    /** One authored scenario, waypoints included, exactly as frozen. */
    public Authored load(String userId, String planId, String scenarioId) {
        return db.with(c -> {
            requireOwnedPlan(c, planId, userId);
            Authored found = row(c, planId, scenarioId);
            if (found == null) throw new ResourceNotFoundException("no such authored scenario: " + scenarioId);
            return found;
        });
    }

    // ---- internals ----

    private static final String SELECT = "SELECT a.id,a.plan_id,a.context_rev,a.base_ensemble_id," +
            "a.spec_model,a.spec_shape,a.spec_horizon_days,a.spec_steps_per_day,a.spec_drift_annual," +
            "a.spec_vol_annual,a.spec_jumps_per_year,a.spec_jump_mean,a.spec_jump_vol,a.spec_tail_nu," +
            "a.spec_seed,a.spec_paths,a.heston_kappa,a.heston_theta,a.heston_xi,a.heston_rho,a.heston_v0," +
            "a.waypoint_fill,a.fingerprint,a.title,a.created_at::text created_at FROM authored_scenario a ";

    private static Authored row(Connection c, String planId, String scenarioId) throws SQLException {
        List<Authored> rows = Db.queryOn(c, SELECT + "WHERE a.id=? AND a.plan_id=?",
                AuthoredScenarioService::head, scenarioId, planId);
        return rows.isEmpty() ? null : withWaypoints(c, rows.getFirst());
    }

    private static Authored head(Db.Row r) {
        Double kappa = r.dblOrNull("heston_kappa");
        ScenarioSpec.Heston h = kappa == null ? null : new ScenarioSpec.Heston(kappa,
                r.dbl("heston_theta"), r.dbl("heston_xi"), r.dbl("heston_rho"), r.dbl("heston_v0"));
        ScenarioSpec spec = new ScenarioSpec(
                ScenarioSpec.PathModel.valueOf(r.str("spec_model")),
                ScenarioSpec.Shape.valueOf(r.str("spec_shape")),
                r.intv("spec_horizon_days"), r.intv("spec_steps_per_day"),
                r.dbl("spec_drift_annual"), r.dbl("spec_vol_annual"), r.dbl("spec_jumps_per_year"),
                r.dbl("spec_jump_mean"), r.dbl("spec_jump_vol"), r.dbl("spec_tail_nu"), h,
                r.lng("spec_seed"), r.intv("spec_paths"));
        return new Authored(r.str("id"), r.str("plan_id"), r.intv("context_rev"),
                r.str("base_ensemble_id"), spec, r.str("waypoint_fill"), r.str("fingerprint"),
                r.str("created_at"), r.str("title"));
    }

    private static Authored withWaypoints(Connection c, Authored head) throws SQLException {
        List<ScenarioSpec.Waypoint> pins = Db.queryOn(c,
                "SELECT day_index,price_ratio,tolerance FROM authored_scenario_waypoint " +
                        "WHERE scenario_id=? ORDER BY day_index",
                r -> new ScenarioSpec.Waypoint(r.intv("day_index"), r.dbl("price_ratio"),
                        r.dblOrNull("tolerance")), head.id());
        if (pins.isEmpty()) return head;
        return new Authored(head.id(), head.planId(), head.contextRev(), head.baseEnsembleId(),
                head.spec().withWaypoints(pins), head.waypointFill(), head.fingerprint(),
                head.createdAt(), head.title());
    }

    private static void requireOwnedPlan(Connection c, String planId, String userId) throws SQLException {
        if (Db.queryOn(c, "SELECT id FROM plans WHERE id=? AND user_id=?::text",
                r -> r.str("id"), planId, OwnerScope.id(userId)).isEmpty()) {
            throw new ResourceNotFoundException("no such Plan: " + planId);
        }
    }

    /**
     * The immutable receipt identity, same recipe family as {@code PlanOutcomeService.saveEnsemble}:
     * SHA-256 over the base fan's fingerprint (the SAME-fan lineage), the generator model version,
     * the full sane spec (waypoints included via the record's canonical text), and the fill label.
     */
    private static String fingerprint(String baseFingerprint, ScenarioSpec spec, String fill) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((baseFingerprint + '|' + PathGenerator.MODEL_VERSION + '|' + spec + '|' + fill)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("Could not identify the authored scenario", e);
        }
    }
}
