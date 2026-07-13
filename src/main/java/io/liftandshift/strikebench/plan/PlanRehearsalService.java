package io.liftandshift.strikebench.plan;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.market.MarketHours;
import io.liftandshift.strikebench.market.sim.SimulatedWorld;
import io.liftandshift.strikebench.market.sim.SimulationSessions;
import io.liftandshift.strikebench.paper.AccountService;
import io.liftandshift.strikebench.util.Ids;
import io.liftandshift.strikebench.util.Json;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Plan-owned bridge from a persisted path ensemble to the live simulated exchange. Selection,
 * replay identity, isolated account and Plan linkage are server-owned and transactionally durable.
 */
public final class PlanRehearsalService {
    public enum Selection { RANDOM, TYPICAL, FAVORABLE, ADVERSE, STRESS, SAMPLE }

    public record Request(Long expectedVersion, String ensembleId, String selection,
                          Integer pathIndex, Double speed) {}
    public record Created(String worldId, String accountId, String planId, String ensembleId,
                          String fingerprint, int pathIndex, String selection, String symbol,
                          String modelVersion, double rateAnnual, double stepSeconds, int knots) {}

    private final Db db;
    private final Clock clock;
    private final PlanOutcomeService outcomes;
    private final SimulationSessions sessions;
    private final AccountService accounts;

    public PlanRehearsalService(Db db, Clock clock, PlanOutcomeService outcomes,
                                SimulationSessions sessions, AccountService accounts) {
        this.db = db;
        this.clock = clock;
        this.outcomes = outcomes;
        this.sessions = sessions;
        this.accounts = accounts;
    }

    public Created create(String userId, Plan.View plan, Request raw, AnalysisContext analysis) {
        if (raw == null || raw.expectedVersion() == null || raw.ensembleId() == null || raw.ensembleId().isBlank()) {
            throw new IllegalArgumentException("expectedVersion and ensembleId are required");
        }
        if (plan.version() != raw.expectedVersion()) {
            throw new IllegalStateException("This Plan changed before the rehearsal was created. Reload it first.");
        }
        if (plan.status() != Plan.Status.ACTIVE) {
            throw new IllegalStateException("This Plan's decision is frozen. Start a linked Plan before creating another rehearsal.");
        }
        if (plan.marketKind() == Plan.MarketKind.SIMULATED) {
            throw new IllegalStateException("This Plan already lives inside one simulated path. Return to its source market before creating a separate rehearsal.");
        }
        PlanOutcomeService.StoredEnsemble stored = outcomes.loadCurrentEnsemble(userId, plan, raw.ensembleId(), analysis);
        if (!plan.symbol().equals(stored.ensemble().scope().symbol())) {
            throw new IllegalStateException("The stored ensemble belongs to a different symbol.");
        }
        Selection selection = parseSelection(raw.selection(), raw.pathIndex());
        int index = selectPath(stored.ensemble().paths(), selection, raw.pathIndex(), plan.context().thesis(),
                stored.fingerprint());
        double[] spotPath = stored.ensemble().paths()[index].clone();
        double[] ivPath = stored.iv().path(spotPath.length - 1, stored.ensemble().spec().dt(),
                stored.ensemble().spec().stepsPerDay());
        SimulatedWorld.ReplaySource replay = new SimulatedWorld.ReplaySource(plan.id(), stored.id(),
                stored.fingerprint(), index, selection.name(), plan.symbol(), stored.ensemble().modelVersion(),
                spotPath, ivPath, stored.stepSeconds(), stored.rateAnnual());
        double speed = raw.speed() == null ? 26.0 : raw.speed();
        SimulatedWorld.Config cfg = new SimulatedWorld.Config("pending",
                "Rehearse " + plan.symbol() + " · " + selectionLabel(selection), Map.of(plan.symbol(), 1.0),
                Map.of(plan.symbol(), spotPath[0]), "PLAN_REPLAY", stored.ensemble().spec().volAnnual(),
                stored.ensemble().spec().seed() ^ index, nextOpen().toString(), speed,
                Map.of(plan.symbol(), stored.ensemble().spec().volAnnual()), Map.of(plan.symbol(), ivPath[0]));
        Map<String, Object> anchor = new LinkedHashMap<>();
        anchor.put("symbol", plan.symbol()); anchor.put("price", spotPath[0]);
        anchor.put("source", "Plan ensemble " + stored.id()); anchor.put("sourceTimestamp", stored.asOf());
        anchor.put("basis", stored.basis()); anchor.put("freshness", stored.anchorFreshness());
        Map<String, Object> anchorDoc = new LinkedHashMap<>();
        anchorDoc.put("anchors", List.of(anchor)); anchorDoc.put("excluded", List.of());
        anchorDoc.put("pending", List.of());
        anchorDoc.put("note", "Exact Plan rehearsal · path " + index + " · receipt " + stored.fingerprint());

        SimulationSessions.SessionHook hook = (c, worldId, accountId) -> {
            PlanWriteGuard.requireMutable(c, plan.id(), userId);
            PlanRow current = requireOwned(c, plan.id(), userId, true);
            if (current.version() != raw.expectedVersion() || current.contextRev() != plan.context().rev()) {
                throw new IllegalStateException("This Plan changed while the rehearsal was being created.");
            }
            String datasetId = analysis != null && analysis.synthetic() ? analysis.datasetId() : null;
            if (Db.queryOn(c, "SELECT id FROM plan_ensemble WHERE id=? AND plan_id=? AND context_rev=? " +
                            "AND dataset_id IS NOT DISTINCT FROM ? AND state='CURRENT'",
                    r -> r.str("id"), stored.id(), plan.id(), plan.context().rev(), datasetId).isEmpty()) {
                throw new IllegalStateException("This path set changed before the rehearsal was created.");
            }
            OffsetDateTime now = now();
            Db.execOn(c, "INSERT INTO plan_link(id,plan_id,role,sim_session_id,created_at) VALUES(?,?,?,?,?)",
                    Ids.newId("plink"), plan.id(), "REHEARSAL", worldId, now);
            Db.execOn(c, "UPDATE plans SET version=version+1,updated_at=? WHERE id=?", now, plan.id());
        };
        SimulationSessions.Created created = sessions.createReplayAtomic(cfg, userId, Json.write(anchorDoc),
                plan.symbol() + " Plan rehearsal", accounts, replay, hook);
        return new Created(created.world().worldId(), created.accountId(), plan.id(), stored.id(),
                stored.fingerprint(), index, selection.name(), plan.symbol(), stored.ensemble().modelVersion(),
                stored.rateAnnual(), stored.stepSeconds(), spotPath.length);
    }

    public ObjectNode list(String userId, String planId) {
        requireOwned(planId, userId);
        ObjectNode out = Json.MAPPER.createObjectNode();
        ArrayNode rows = out.putArray("rehearsals");
        db.query("SELECT rs.sim_session_id,s.name,s.status,s.created_at::text created_at,s.finished_at::text finished_at," +
                        "rs.ensemble_id,rs.fingerprint,rs.path_index,rs.selection_kind,rs.symbol,rs.model_version," +
                        "rs.n_steps,rs.step_seconds,rs.rate_annual FROM sim_replay_source rs " +
                        "JOIN sim_session s ON s.id=rs.sim_session_id WHERE rs.plan_id=? ORDER BY s.created_at DESC",
                r -> {
                    ObjectNode n = Json.MAPPER.createObjectNode();
                    n.put("worldId", r.str("sim_session_id")); n.put("name", r.str("name"));
                    n.put("status", r.str("status")); n.put("ensembleId", r.str("ensemble_id"));
                    n.put("fingerprint", r.str("fingerprint")); n.put("pathIndex", r.intv("path_index"));
                    n.put("selection", r.str("selection_kind")); n.put("symbol", r.str("symbol"));
                    n.put("modelVersion", r.str("model_version")); n.put("steps", r.intv("n_steps"));
                    n.put("stepSeconds", r.dbl("step_seconds")); n.put("rateAnnual", r.dbl("rate_annual"));
                    n.put("createdAt", r.str("created_at"));
                    if (r.str("finished_at") != null) n.put("finishedAt", r.str("finished_at"));
                    return n;
                }, planId).forEach(rows::add);
        return out;
    }

    /** Finish hook used by the generic session endpoint. Rehearsals become Plan evidence but never
     * recommendation-calibration samples. */
    public SimulationSessions.FinishHook finishHook(String userId, String worldId) {
        String planId = db.query("SELECT plan_id FROM sim_replay_source WHERE sim_session_id=?",
                r -> r.str("plan_id"), worldId).stream().findFirst().orElse(null);
        if (planId == null) return null;
        return (c, id, world) -> recordFinish(c, userId, planId, id, world);
    }

    private void recordFinish(Connection c, String userId, String planId, String worldId,
                              SimulatedWorld world) throws SQLException {
        PlanRow plan = requireOwned(c, planId, userId, true);
        SimulatedWorld.ReplaySource replay = world.replaySource();
        if (replay == null) throw new IllegalStateException("Plan rehearsal lost its replay identity");
        OffsetDateTime now = now();
        String decisionId = Db.queryOn(c, "SELECT id FROM plan_decision WHERE plan_id=? ORDER BY decision_seq DESC LIMIT 1",
                r -> r.str("id"), planId).stream().findFirst().orElse(null);
        List<Long> realizedRows = Db.queryOn(c, "SELECT SUM(COALESCE(decision_pnl_cents,realized_pnl_cents)) total " +
                        "FROM trades t JOIN accounts a ON a.id=t.account_id WHERE a.world_id=? AND t.status<>'ACTIVE'",
                r -> r.lngOrNull("total"), worldId);
        Long realized = realizedRows.isEmpty() ? null : realizedRows.getFirst();
        Long[] excursion = Db.queryOn(c, "SELECT MIN(COALESCE(tm.decision_unrealized_cents,tm.unrealized_cents)) mae," +
                        "MAX(COALESCE(tm.decision_unrealized_cents,tm.unrealized_cents)) mfe FROM trade_marks tm " +
                        "JOIN trades t ON t.id=tm.trade_id JOIN accounts a ON a.id=t.account_id WHERE a.world_id=?",
                r -> new Long[]{r.lngOrNull("mae"), r.lngOrNull("mfe")}, worldId).stream()
                .findFirst().orElse(new Long[]{null, null});
        long start = Math.round(replay.spotAt(0) * 100);
        long end = Math.round(replay.spotAt(replay.durationSeconds()) * 100);
        String note = "Exact path " + (replay.pathIndex() + 1) + " (" + replay.selection().toLowerCase(Locale.ROOT)
                + ") from Plan ensemble " + replay.ensembleId() + " · receipt " + replay.fingerprint();
        Db.execOn(c, "INSERT INTO plan_management_action(id,plan_id,decision_id,sim_session_id,kind,action_at," +
                        "underlying_cents,realized_cents,mae_cents,mfe_cents,note,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                Ids.newId("pmgt"), planId, decisionId, worldId, "REHEARSAL_RESULT", now, end, realized,
                excursion[0], excursion[1], note, now);
        int horizon = Db.queryOn(c, "SELECT horizon_days FROM plan_context_revision WHERE plan_id=? AND rev=?",
                r -> r.intv("horizon_days"), planId, plan.contextRev()).stream().findFirst().orElse(1);
        Double pop = decisionId == null ? null : Db.queryOn(c, "SELECT pop FROM plan_decision WHERE id=?",
                r -> r.dblOrNull("pop"), decisionId).stream().findFirst().orElse(null);
        Db.execOn(c, "INSERT INTO plan_review(id,plan_id,decision_id,category,horizon_days,benchmark_kind," +
                        "benchmark_start_cents,benchmark_end_cents,realized_cents,predicted_pop,won,reviewed_at,note,created_at) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)", Ids.newId("prev"), planId, decisionId,
                "SIM_REHEARSAL", Math.max(1, horizon), "PLAN_POSITION", start, end, realized, pop,
                realized == null ? null : realized > 0 ? 1 : 0, now, note, now);
        Db.execOn(c, "UPDATE plans SET active_stage='MANAGE_REVIEW',version=version+1,updated_at=? WHERE id=?",
                now, planId);
    }

    private static Selection parseSelection(String raw, Integer pathIndex) {
        if (pathIndex != null) return Selection.SAMPLE;
        try { return Selection.valueOf(raw == null ? "TYPICAL" : raw.trim().toUpperCase(Locale.ROOT)); }
        catch (Exception e) { throw new IllegalArgumentException("selection must be RANDOM, TYPICAL, FAVORABLE, ADVERSE, STRESS, or SAMPLE"); }
    }

    static int selectPath(double[][] paths, Selection selection, Integer requested,
                          String thesis, String fingerprint) {
        if (paths == null || paths.length == 0) throw new IllegalArgumentException("ensemble has no paths");
        if (selection == Selection.SAMPLE) {
            if (requested == null || requested < 0 || requested >= paths.length) {
                throw new IllegalArgumentException("sample path index is outside this ensemble");
            }
            return requested;
        }
        if (selection == Selection.RANDOM) return Math.floorMod(fingerprint.hashCode(), paths.length);
        double start = paths[0][0];
        double[] terminal = new double[paths.length];
        for (int i = 0; i < paths.length; i++) terminal[i] = paths[i][paths[i].length - 1];
        if (selection == Selection.TYPICAL) {
            double[] copy = terminal.clone(); java.util.Arrays.sort(copy);
            double median = copy[copy.length / 2]; return nearest(terminal, median);
        }
        if (selection == Selection.STRESS) {
            int best = 0; double worstDrawdown = 0;
            for (int i = 0; i < paths.length; i++) {
                double peak = paths[i][0], drawdown = 0;
                for (double value : paths[i]) { peak = Math.max(peak, value); drawdown = Math.min(drawdown, value / peak - 1); }
                if (drawdown < worstDrawdown) { worstDrawdown = drawdown; best = i; }
            }
            return best;
        }
        String t = thesis == null ? "" : thesis.toLowerCase(Locale.ROOT);
        boolean bearish = t.contains("bear") || t.contains("down") || t.contains("fall") || t.contains("drop");
        boolean neutral = t.contains("neutral") || t.contains("flat") || t.contains("range") || t.contains("chop");
        if (neutral) {
            int nearest = nearest(terminal, start), farthest = 0; double distance = -1;
            for (int i = 0; i < terminal.length; i++) if (Math.abs(terminal[i] - start) > distance) {
                distance = Math.abs(terminal[i] - start); farthest = i;
            }
            return selection == Selection.FAVORABLE ? nearest : farthest;
        }
        int min = 0, max = 0;
        for (int i = 1; i < terminal.length; i++) { if (terminal[i] < terminal[min]) min = i; if (terminal[i] > terminal[max]) max = i; }
        boolean favorableUp = !bearish;
        return selection == Selection.FAVORABLE ? (favorableUp ? max : min) : (favorableUp ? min : max);
    }

    private static int nearest(double[] values, double target) {
        int best = 0; double distance = Math.abs(values[0] - target);
        for (int i = 1; i < values.length; i++) { double d = Math.abs(values[i] - target); if (d < distance) { best = i; distance = d; } }
        return best;
    }

    private static String selectionLabel(Selection selection) {
        return switch (selection) {
            case TYPICAL -> "typical path"; case FAVORABLE -> "view-favorable path";
            case ADVERSE -> "view-adverse path"; case STRESS -> "drawdown stress path";
            case SAMPLE -> "selected sample"; case RANDOM -> "repeatable random path";
        };
    }

    private LocalDateTime nextOpen() {
        var now = clock.instant().atZone(MarketHours.EASTERN);
        LocalDate day = now.toLocalDate();
        if (MarketHours.isTradingDay(day) && !now.toLocalTime().isBefore(LocalTime.of(16, 0))) day = day.plusDays(1);
        while (!MarketHours.isTradingDay(day)) day = day.plusDays(1);
        return LocalDateTime.of(day, LocalTime.of(9, 30));
    }

    private void requireOwned(String planId, String userId) {
        db.with(c -> { requireOwned(c, planId, userId, false); return null; });
    }

    private static PlanRow requireOwned(Connection c, String planId, String userId, boolean lock) throws SQLException {
        List<PlanRow> rows = Db.queryOn(c, "SELECT user_id,version,active_context_rev FROM plans WHERE id=? " +
                        "AND (user_id=?::text OR (?::text IS NULL AND user_id IS NULL))" + (lock ? " FOR UPDATE" : ""),
                r -> new PlanRow(r.str("user_id"), r.lng("version"), r.intv("active_context_rev")),
                planId, userId, userId);
        if (rows.isEmpty()) throw new NoSuchElementException("no such Plan: " + planId);
        return rows.getFirst();
    }

    private OffsetDateTime now() { return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private record PlanRow(String userId, long version, int contextRev) {}
}
