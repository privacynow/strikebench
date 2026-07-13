package io.liftandshift.strikebench.plan;

import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.research.ResearchQuestionEngine;
import io.liftandshift.strikebench.util.Ids;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/** Durable, plan-owned historical evidence. Every rendered result is reconstructed from rows. */
public final class PlanEvidenceService {
    public record SavedStudy(String evidenceId, String state,
                             ResearchQuestionEngine.RunRequest request,
                             ResearchQuestionEngine.QuestionResult result, String basis, String datasetId,
                             String createdAt) {}

    private final Db db;
    private final ResearchQuestionEngine research;
    private final Clock clock;

    public PlanEvidenceService(Db db, ResearchQuestionEngine research, Clock clock) {
        this.db = db;
        this.research = research;
        this.clock = clock;
    }

    public SavedStudy run(String userId, Plan.View plan, ResearchQuestionEngine.RunRequest raw,
                          AnalysisContext analysis, String worldId) {
        if (raw == null) throw new IllegalArgumentException("study request is required");
        if (raw.symbol() != null && !raw.symbol().isBlank()
                && !plan.symbol().equalsIgnoreCase(raw.symbol())) {
            throw new IllegalArgumentException("study symbol must match the plan");
        }
        Map<String, Object> params = new LinkedHashMap<>(raw.params() == null ? Map.of() : raw.params());
        if (plan.context().horizonDays() != null) params.put("forward", plan.context().horizonDays());
        ResearchQuestionEngine.RunRequest request = new ResearchQuestionEngine.RunRequest(
                raw.key(), plan.symbol(), raw.from(), raw.to(), Map.copyOf(params));
        ResearchQuestionEngine.QuestionResult result = research.run(request, analysis, worldId);
        String id = Ids.newId("pe");
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String basis = basis(plan, analysis);
        String datasetId = analysis == null || !analysis.synthetic() ? null : analysis.datasetId();

        String state = db.tx(c -> {
            CurrentPlan current = ownedPlanOn(c, plan.id(), userId, true);
            boolean stillCurrent = current.contextRev() == plan.context().rev();
            String resultState = stillCurrent ? "CURRENT" : "STALE";
            if (stillCurrent) {
                Db.execOn(c, "UPDATE plan_evidence SET state='STALE' WHERE plan_id=? AND basis=? " +
                                "AND dataset_id IS NOT DISTINCT FROM ? AND state='CURRENT'",
                        plan.id(), basis, datasetId);
            }
            Db.execOn(c, "INSERT INTO plan_evidence(id,plan_id,context_rev,basis,dataset_id,question_key,study_key," +
                            "from_date,to_date,as_of,engine_version,input_hash,evidence_provenance,sample_size,state,created_at) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, plan.id(), plan.context().rev(), basis, datasetId, result.key(), result.studyKey(),
                    LocalDate.parse(result.from()), LocalDate.parse(result.to()), now,
                    ResearchQuestionEngine.MODEL_VERSION, result.studyKey(),
                    result.evidence(), result.conditioned().sample(), resultState, now);
            persistParams(c, id, request.params());
            persistResult(c, id, result);
            return resultState;
        });
        return new SavedStudy(id, state, request, result, basis, datasetId, now.toString());
    }

    public SavedStudy latest(String userId, String planId, AnalysisContext analysis) {
        return db.with(c -> {
            CurrentPlan plan = ownedPlanOn(c, planId, userId, false);
            String basis = basis(plan, analysis);
            String datasetId = analysis == null || !analysis.synthetic() ? null : analysis.datasetId();
            List<EvidenceRow> rows = Db.queryOn(c,
                    "SELECT e.id,e.question_key,e.study_key,e.evidence_provenance,e.basis,e.dataset_id," +
                            "e.from_date::text from_date,e.to_date::text to_date," +
                            "e.state,e.created_at::text created_at FROM plan_evidence e " +
                            "WHERE e.plan_id=? AND e.context_rev=? AND e.state='CURRENT' " +
                            "AND e.basis=? AND e.dataset_id IS NOT DISTINCT FROM ? " +
                            "ORDER BY e.created_at DESC LIMIT 1",
                    r -> new EvidenceRow(r.str("id"), r.str("question_key"), r.str("study_key"),
                            r.str("evidence_provenance"), r.str("basis"), r.str("dataset_id"), r.str("from_date"),
                            r.str("to_date"), r.str("state"), r.str("created_at")),
                    planId, plan.contextRev(), basis, datasetId);
            if (rows.isEmpty()) return null;
            EvidenceRow row = rows.getFirst();
            Map<String, Object> params = loadParams(c, row.id());
            ResearchQuestionEngine.RunRequest request = new ResearchQuestionEngine.RunRequest(
                    row.questionKey(), plan.symbol(), row.from(), row.to(), params);
            return new SavedStudy(row.id(), row.state(), request, loadResult(c, row, plan.symbol()),
                    row.basis(), row.datasetId(), row.createdAt());
        });
    }

    private static void persistParams(java.sql.Connection c, String id, Map<String, Object> params)
            throws java.sql.SQLException {
        for (var entry : params.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Boolean b) {
                Db.execOn(c, "INSERT INTO plan_evidence_param(evidence_id,param_key,value_boolean) VALUES(?,?,?)",
                        id, entry.getKey(), b ? 1 : 0);
            } else if (value instanceof Number n) {
                Db.execOn(c, "INSERT INTO plan_evidence_param(evidence_id,param_key,value_number) VALUES(?,?,?)",
                        id, entry.getKey(), n.doubleValue());
            } else if (value != null) {
                Db.execOn(c, "INSERT INTO plan_evidence_param(evidence_id,param_key,value_text) VALUES(?,?,?)",
                        id, entry.getKey(), String.valueOf(value));
            }
        }
    }

    private static void persistResult(java.sql.Connection c, String id,
                                      ResearchQuestionEngine.QuestionResult r) throws java.sql.SQLException {
        stat(c, id, "BASELINE", r.baseline());
        stat(c, id, "CONDITIONED", r.conditioned());
        metric(c, id, "question", r.question());
        metric(c, id, "forwardDays", r.forwardDays());
        metric(c, id, "winRateEdgePct", r.winRateEdgePct());
        metric(c, id, "meanEdgePct", r.meanEdgePct());
        metric(c, id, "zScore", r.zScore());
        metric(c, id, "significant", r.significant() ? 1 : 0);
        metric(c, id, "ciLowPct", r.ciLowPct());
        metric(c, id, "ciHighPct", r.ciHighPct());
        metric(c, id, "observed", r.observed() ? 1 : 0);
        metric(c, id, "verdict", r.verdict());
        if (r.effectSize() != null) metric(c, id, "effectSize", r.effectSize());
        if (r.holdout() != null) metric(c, id, "holdout", r.holdout());
        var p = r.protocol();
        metric(c, id, "protocol.baseline", p.baseline());
        metric(c, id, "protocol.regime", p.regime());
        metric(c, id, "protocol.eventSpacingDays", p.eventSpacingDays());
        metric(c, id, "protocol.effectiveEventBlock", p.effectiveEventBlock());
        metric(c, id, "protocol.minSample", p.minSample());
        metric(c, id, "protocol.confidencePct", p.confidencePct());
        metric(c, id, "protocol.bootstrapSamples", p.bootstrapSamples());
        metric(c, id, "protocol.multiplicity", p.multiplicity());
        metric(c, id, "protocol.splitHalfCheck", p.splitHalfCheck() ? 1 : 0);
        metric(c, id, "protocol.criticalZ", p.criticalZ());
        for (int i = 0; i < r.distribution().size(); i++) {
            var b = r.distribution().get(i);
            Db.execOn(c, "INSERT INTO plan_evidence_distribution(evidence_id,bucket_index,from_pct,to_pct,sample_count) VALUES(?,?,?,?,?)",
                    id, i, b.fromPct(), b.toPct(), b.count());
        }
        persistDates(c, "plan_evidence_example", "example_index", id, r.exampleDates());
        persistDates(c, "plan_evidence_event", "event_index", id, r.eventDates());
        persistNotes(c, id, r.notes());
        List<List<Double>> analogPaths = r.analogPaths() == null ? List.of() : r.analogPaths();
        for (int path = 0; path < analogPaths.size(); path++) {
            List<Double> values = analogPaths.get(path);
            for (int step = 0; step < values.size(); step++) {
                Db.execOn(c, "INSERT INTO plan_evidence_analog(evidence_id,path_index,step_index,relative_price) VALUES(?,?,?,?)",
                        id, path, step, values.get(step));
            }
        }
    }

    private static void stat(java.sql.Connection c, String id, String kind,
                             ResearchQuestionEngine.Stat s) throws java.sql.SQLException {
        Db.execOn(c, "INSERT INTO plan_evidence_stat(evidence_id,sample_kind,sample_size,win_rate_pct," +
                        "mean_return_pct,median_return_pct,worst_pct,best_pct) VALUES(?,?,?,?,?,?,?,?)",
                id, kind, s.sample(), s.winRatePct(), s.meanReturnPct(), s.medianReturnPct(),
                s.worstPct(), s.bestPct());
    }

    private static void metric(java.sql.Connection c, String id, String key, Number value)
            throws java.sql.SQLException {
        Db.execOn(c, "INSERT INTO plan_evidence_metric(evidence_id,metric_key,value_number) VALUES(?,?,?)",
                id, key, value.doubleValue());
    }

    private static void metric(java.sql.Connection c, String id, String key, String value)
            throws java.sql.SQLException {
        Db.execOn(c, "INSERT INTO plan_evidence_metric(evidence_id,metric_key,value_text) VALUES(?,?,?)",
                id, key, value);
    }

    private static void persistDates(java.sql.Connection c, String table, String indexColumn,
                                     String id, List<String> values)
            throws java.sql.SQLException {
        if (values == null) return;
        for (int i = 0; i < values.size(); i++) {
            Db.execOn(c, "INSERT INTO " + table + "(evidence_id," + indexColumn + ",event_date) VALUES(?,?,?)",
                    id, i, LocalDate.parse(values.get(i)));
        }
    }

    private static void persistNotes(java.sql.Connection c, String id, List<String> notes)
            throws java.sql.SQLException {
        if (notes == null) return;
        for (int i = 0; i < notes.size(); i++) {
            Db.execOn(c, "INSERT INTO plan_evidence_note(evidence_id,note_index,note) VALUES(?,?,?)",
                    id, i, notes.get(i));
        }
    }

    private static ResearchQuestionEngine.QuestionResult loadResult(java.sql.Connection c, EvidenceRow row,
                                                                    String symbol)
            throws java.sql.SQLException {
        Map<String, Metric> m = Db.queryOn(c,
                "SELECT metric_key,value_number,value_text FROM plan_evidence_metric WHERE evidence_id=?",
                r -> new Metric(r.str("metric_key"), r.dblOrNull("value_number"), r.str("value_text")), row.id())
                .stream().collect(java.util.stream.Collectors.toMap(Metric::key, x -> x));
        ResearchQuestionEngine.Stat baseline = loadStat(c, row.id(), "BASELINE");
        ResearchQuestionEngine.Stat conditioned = loadStat(c, row.id(), "CONDITIONED");
        List<ResearchQuestionEngine.Bucket> distribution = Db.queryOn(c,
                "SELECT from_pct,to_pct,sample_count FROM plan_evidence_distribution WHERE evidence_id=? ORDER BY bucket_index",
                r -> new ResearchQuestionEngine.Bucket(r.dbl("from_pct"), r.dbl("to_pct"), r.intv("sample_count")), row.id());
        List<String> examples = loadStrings(c, "plan_evidence_example", "example_index", "event_date::text", row.id());
        List<String> notes = loadStrings(c, "plan_evidence_note", "note_index", "note", row.id());
        List<String> events = loadStrings(c, "plan_evidence_event", "event_index", "event_date::text", row.id());
        List<List<Double>> analogs = loadAnalogs(c, row.id());
        ResearchQuestionEngine.Protocol protocol = new ResearchQuestionEngine.Protocol(
                text(m, "protocol.baseline"), text(m, "protocol.regime"), integer(m, "protocol.eventSpacingDays"),
                integer(m, "protocol.effectiveEventBlock"), integer(m, "protocol.minSample"),
                integer(m, "protocol.confidencePct"), integer(m, "protocol.bootstrapSamples"),
                text(m, "protocol.multiplicity"), number(m, "protocol.splitHalfCheck") != 0,
                number(m, "protocol.criticalZ"));
        return new ResearchQuestionEngine.QuestionResult(row.questionKey(), symbol, text(m, "question"),
                row.from(), row.to(), integer(m, "forwardDays"), baseline, conditioned,
                number(m, "winRateEdgePct"), number(m, "meanEdgePct"), number(m, "zScore"),
                number(m, "significant") != 0, number(m, "ciLowPct"), number(m, "ciHighPct"),
                distribution, examples, row.evidence(), number(m, "observed") != 0, text(m, "verdict"), notes,
                optionalNumber(m, "effectSize"), optionalText(m, "holdout"), analogs, events,
                row.studyKey(), protocol);
    }

    private static ResearchQuestionEngine.Stat loadStat(java.sql.Connection c, String id, String kind)
            throws java.sql.SQLException {
        return Db.queryOn(c, "SELECT sample_size,win_rate_pct,mean_return_pct,median_return_pct,worst_pct,best_pct " +
                        "FROM plan_evidence_stat WHERE evidence_id=? AND sample_kind=?",
                r -> new ResearchQuestionEngine.Stat(r.intv("sample_size"), r.dbl("win_rate_pct"),
                        r.dbl("mean_return_pct"), r.dbl("median_return_pct"), r.dbl("worst_pct"), r.dbl("best_pct")),
                id, kind).stream().findFirst().orElseThrow(() -> new IllegalStateException("incomplete plan evidence"));
    }

    private static List<List<Double>> loadAnalogs(java.sql.Connection c, String id) throws java.sql.SQLException {
        record Point(int path, int step, double value) {}
        List<Point> points = Db.queryOn(c, "SELECT path_index,step_index,relative_price FROM plan_evidence_analog " +
                        "WHERE evidence_id=? ORDER BY path_index,step_index",
                r -> new Point(r.intv("path_index"), r.intv("step_index"), r.dbl("relative_price")), id);
        List<List<Double>> out = new ArrayList<>();
        for (Point point : points) {
            while (out.size() <= point.path()) out.add(new ArrayList<>());
            out.get(point.path()).add(point.value());
        }
        return out;
    }

    private static List<String> loadStrings(java.sql.Connection c, String table, String order,
                                            String value, String id) throws java.sql.SQLException {
        return Db.queryOn(c, "SELECT " + value + " value FROM " + table + " WHERE evidence_id=? ORDER BY " + order,
                r -> r.str("value"), id);
    }

    private static Map<String, Object> loadParams(java.sql.Connection c, String id) throws java.sql.SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        Db.queryOn(c, "SELECT param_key,value_number,value_text,value_boolean FROM plan_evidence_param WHERE evidence_id=?",
                r -> new ParamRow(r.str("param_key"), r.dblOrNull("value_number"), r.str("value_text"),
                        r.lngOrNull("value_boolean")), id).forEach(p -> out.put(p.key(),
                        p.number() != null ? p.number() : p.text() != null ? p.text() : p.bool() != null && p.bool() == 1));
        return out;
    }

    private static CurrentPlan ownedPlanOn(java.sql.Connection c, String id, String userId, boolean lock)
            throws java.sql.SQLException {
        List<CurrentPlan> rows = Db.queryOn(c,
                "SELECT symbol,market_kind,active_context_rev FROM plans WHERE id=? AND " + ownerClause("user_id")
                        + (lock ? " FOR UPDATE" : ""),
                r -> new CurrentPlan(r.str("symbol"), Plan.MarketKind.valueOf(r.str("market_kind")),
                        r.intv("active_context_rev")), id, userId, userId);
        if (rows.isEmpty()) throw new NoSuchElementException("no such plan: " + id);
        return rows.getFirst();
    }

    private static String basis(Plan.View plan, AnalysisContext analysis) {
        if (analysis != null && analysis.synthetic()) return "SCENARIO_DATASET";
        return switch (plan.marketKind()) {
            case OBSERVED -> "OBSERVED_HISTORY";
            case DEMO -> "DEMO_HISTORY";
            case SIMULATED -> "SIMULATED_HISTORY";
        };
    }

    private static String basis(CurrentPlan plan, AnalysisContext analysis) {
        if (analysis != null && analysis.synthetic()) return "SCENARIO_DATASET";
        return switch (plan.marketKind()) {
            case OBSERVED -> "OBSERVED_HISTORY";
            case DEMO -> "DEMO_HISTORY";
            case SIMULATED -> "SIMULATED_HISTORY";
        };
    }

    private static String ownerClause(String column) {
        return "(" + column + "=?::text OR (?::text IS NULL AND " + column + " IS NULL))";
    }

    private static double number(Map<String, Metric> m, String key) {
        Metric value = Objects.requireNonNull(m.get(key), "missing evidence metric " + key);
        return Objects.requireNonNull(value.number(), "non-numeric evidence metric " + key);
    }
    private static int integer(Map<String, Metric> m, String key) { return (int) Math.round(number(m, key)); }
    private static String text(Map<String, Metric> m, String key) {
        return Objects.requireNonNull(Objects.requireNonNull(m.get(key), "missing evidence metric " + key).text());
    }
    private static Double optionalNumber(Map<String, Metric> m, String key) {
        return m.containsKey(key) ? m.get(key).number() : null;
    }
    private static String optionalText(Map<String, Metric> m, String key) {
        return m.containsKey(key) ? m.get(key).text() : null;
    }

    private record CurrentPlan(String symbol, Plan.MarketKind marketKind, int contextRev) {}
    private record EvidenceRow(String id, String questionKey, String studyKey, String evidence,
                               String basis, String datasetId,
                               String from, String to, String state, String createdAt) {}
    private record Metric(String key, Double number, String text) {}
    private record ParamRow(String key, Double number, String text, Long bool) {}
}
