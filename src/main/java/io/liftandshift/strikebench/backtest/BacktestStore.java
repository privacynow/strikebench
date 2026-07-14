package io.liftandshift.strikebench.backtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.util.Json;
import io.liftandshift.strikebench.util.OwnerScope;
import io.liftandshift.strikebench.util.ResourceNotFoundException;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Typed persistence boundary for historical replay runs and their ordered results. */
public final class BacktestStore {
    private final Db db;
    private final Clock clock;

    BacktestStore(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    void save(Backtester.BacktestReport report, Backtester.BacktestRequest request, String userId) {
        save("SINGLE", Json.MAPPER.valueToTree(request), Json.MAPPER.valueToTree(report), userId);
    }

    void save(Backtester.PortfolioReport report, Backtester.PortfolioRequest request, String userId) {
        save("PORTFOLIO", Json.MAPPER.valueToTree(request), Json.MAPPER.valueToTree(report), userId);
    }

    private void save(String kind, JsonNode request, JsonNode report, String userId) {
        db.tx(c -> {
            OwnerScope.ensure(c, userId);
            persist(c, kind, request, report, OwnerScope.id(userId),
                    OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
            return null;
        });
    }

    /** Used only by the one-time SQLite cutover; it writes through the same typed schema. */
    public static void importLegacy(Connection c, String id, String userId, String createdAt,
                                    String requestJson, String reportJson) throws SQLException {
        JsonNode request = Json.parse(requestJson);
        ObjectNode report = ((ObjectNode) Json.parse(reportJson)).deepCopy();
        report.put("id", id);
        String kind = request.has("maxConcurrent") ? "PORTFOLIO" : "SINGLE";
        String owner = OwnerScope.ensure(c, userId);
        persist(c, kind, request, report, owner, parseCreatedAt(createdAt));
    }

    private static void persist(Connection c, String kind, JsonNode request, JsonNode report,
                                String owner, OffsetDateTime createdAt) throws SQLException {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("id", requiredText(report, "id"));
        values.put("user_id", owner);
        values.put("created_at", createdAt);
        values.put("run_kind", kind);
        values.put("symbol", requiredText(report, "symbol"));
        values.put("strategy", requiredText(report, "strategy"));
        values.put("from_date", java.time.LocalDate.parse(requiredText(report, "from")));
        values.put("to_date", java.time.LocalDate.parse(requiredText(report, "to")));
        values.put("target_dte", intOrNull(request, "targetDte"));
        values.put("entry_every_days", intOrNull(request, "entryEveryDays"));
        values.put("qty", intOrNull(request, "qty"));
        values.put("slippage_pct", doubleOrNull(request, "slippagePct"));
        values.put("starting_cash_cents", longOrNull(request, "startingCashCents"));
        values.put("max_concurrent", intOrNull(request, "maxConcurrent"));
        values.put("short_delta", doubleOrNull(request, "shortDelta"));
        values.put("width_pct", doubleOrNull(request, "widthPct"));
        values.put("profit_target_pct", doubleOrNull(request, "profitTargetPct"));
        values.put("stop_fraction", doubleOrNull(request, "stopFraction"));
        values.put("roll_dte", intOrNull(request, "rollDte"));
        values.put("pricing_mode", requiredText(report, "pricingMode"));
        values.put("confidence", requiredText(report, "confidence"));
        values.put("days_requested", intOrNull(report, "daysRequested"));
        values.put("days_covered", requiredInt(report, "daysCovered"));
        values.put("sample_size", requiredInt(report, "sampleSize"));
        values.put("concurrent_peak", intOrNull(report, "concurrentPeak"));
        values.put("win_rate", doubleOrNull(report, "winRate"));
        values.put("avg_return_on_risk", doubleOrNull(report, "avgReturnOnRisk"));
        values.put("starting_cents", requiredLong(report, "startingCents"));
        values.put("ending_cents", requiredLong(report, "endingCents"));
        values.put("max_drawdown_pct", requiredDouble(report, "maxDrawdownPct"));
        values.put("assignments", intOrNull(report, "assignments"));
        values.put("demo_underlying", report.path("demoUnderlying").asBoolean(false) ? 1 : 0);
        values.put("disclaimer", requiredText(report, "disclaimer"));
        String columns = String.join(",", values.keySet());
        String placeholders = String.join(",", java.util.Collections.nCopies(values.size(), "?"));
        Db.execOn(c, "INSERT INTO backtests(" + columns + ") VALUES(" + placeholders + ")",
                values.values().toArray());

        JsonNode worst = report.path("worstTrade");
        int i = 0;
        boolean worstStored = false;
        for (JsonNode trade : report.path("trades")) {
            boolean isWorst = !worstStored && !worst.isMissingNode() && trade.equals(worst);
            Db.execOn(c, "INSERT INTO backtest_trade(backtest_id,trade_index,entry_date,exit_date,label,strategy," +
                            "entry_net_premium_cents,credit_cents,exit_value_cents,fees_cents,pnl_cents,max_loss_cents," +
                            "return_on_risk,exit_reason,assigned,entry_underlying_cents,is_worst) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    requiredText(report, "id"), i++, java.time.LocalDate.parse(requiredText(trade, "entryDate")),
                    java.time.LocalDate.parse(requiredText(trade, "exitDate")), text(trade, "label"),
                    text(trade, "strategy"), longOrNull(trade, "entryNetPremiumCents"), longOrNull(trade, "creditCents"),
                    longOrNull(trade, "exitValueCents"), longOrNull(trade, "feesCents"), requiredLong(trade, "pnlCents"),
                    requiredLong(trade, "maxLossCents"), doubleOrNull(trade, "returnOnRisk"),
                    requiredText(trade, "exitReason"), boolIntOrNull(trade, "assigned"),
                    longOrNull(trade, "entryUnderlyingCents"), isWorst ? 1 : 0);
            worstStored |= isWorst;
        }
        i = 0;
        for (JsonNode skipped : report.path("skipped")) {
            Db.execOn(c, "INSERT INTO backtest_skip(backtest_id,skip_index,skip_date,reason) VALUES(?,?,?,?)",
                    requiredText(report, "id"), i++, java.time.LocalDate.parse(requiredText(skipped, "date")),
                    requiredText(skipped, "reason"));
        }
        i = 0;
        for (JsonNode point : report.path("equityCurve")) {
            Db.execOn(c, "INSERT INTO backtest_equity_point(backtest_id,point_index,point_date,equity_cents) VALUES(?,?,?,?)",
                    requiredText(report, "id"), i++, java.time.LocalDate.parse(requiredText(point, "date")),
                    requiredLong(point, "equityCents"));
        }
        i = 0;
        for (JsonNode note : report.path("notes")) {
            Db.execOn(c, "INSERT INTO backtest_note(backtest_id,note_index,note) VALUES(?,?,?)",
                    requiredText(report, "id"), i++, note.asText());
        }
        JsonNode assumptions = report.path("assumptions");
        if (assumptions.isObject()) {
            var fields = assumptions.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                Db.execOn(c, "INSERT INTO backtest_assumption(backtest_id,assumption_key,assumption_value) " +
                                "VALUES(?,?,?::jsonb)",
                        requiredText(report, "id"), entry.getKey(), Json.write(entry.getValue()));
            }
        }
    }

    List<Map<String, Object>> list(String userId) {
        return db.with(c -> Db.queryOn(c, baseSelect() + " WHERE user_id=? ORDER BY created_at DESC,id DESC",
                r -> {
                    StoredRun stored = mapBase(r);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("id", stored.report().path("id").asText());
                    out.put("createdAt", r.str("created_at"));
                    out.put("request", Json.MAPPER.convertValue(stored.request(), Map.class));
                    return out;
                }, OwnerScope.id(userId)));
    }

    Map<String, Object> get(String id) {
        return db.with(c -> {
            List<StoredRun> rows = Db.queryOn(c, baseSelect() + " WHERE id=?", BacktestStore::mapBase, id);
            if (rows.isEmpty()) throw new ResourceNotFoundException("no such backtest " + id);
            StoredRun stored = rows.getFirst();
            ObjectNode report = stored.report();
            ArrayNode trades = report.putArray("trades");
            ObjectNode[] worst = new ObjectNode[1];
            Db.queryOn(c, "SELECT entry_date::text entry_date,exit_date::text exit_date,label,strategy," +
                            "entry_net_premium_cents,credit_cents,exit_value_cents,fees_cents,pnl_cents,max_loss_cents," +
                            "return_on_risk,exit_reason,assigned,entry_underlying_cents,is_worst FROM backtest_trade " +
                    "WHERE backtest_id=? ORDER BY trade_index", r -> {
                        ObjectNode trade = Json.obj();
                        put(trade, "entryDate", r.str("entry_date")); put(trade, "exitDate", r.str("exit_date"));
                        if ("SINGLE".equals(stored.kind())) {
                            put(trade, "label", r.str("label"));
                            put(trade, "entryNetPremiumCents", r.lngOrNull("entry_net_premium_cents"));
                            put(trade, "exitValueCents", r.lngOrNull("exit_value_cents"));
                            put(trade, "feesCents", r.lngOrNull("fees_cents"));
                        } else {
                            put(trade, "strategy", r.str("strategy"));
                            put(trade, "creditCents", r.lngOrNull("credit_cents"));
                        }
                        put(trade, "pnlCents", r.lng("pnl_cents"));
                        put(trade, "maxLossCents", r.lng("max_loss_cents"));
                        put(trade, "returnOnRisk", r.dblOrNull("return_on_risk")); put(trade, "exitReason", r.str("exit_reason"));
                        if ("SINGLE".equals(stored.kind())) {
                            put(trade, "assigned", boolOrNull(r, "assigned"));
                            put(trade, "entryUnderlyingCents", r.lngOrNull("entry_underlying_cents"));
                        }
                        if (r.bool("is_worst")) worst[0] = trade.deepCopy();
                        return trade;
                    }, id).forEach(trades::add);
            if (worst[0] != null) report.set("worstTrade", worst[0]);
            else if ("SINGLE".equals(stored.kind())) report.putNull("worstTrade");

            ArrayNode equity = report.putArray("equityCurve");
            Db.queryOn(c, "SELECT point_date::text point_date,equity_cents FROM backtest_equity_point " +
                            "WHERE backtest_id=? ORDER BY point_index", r -> {
                        ObjectNode point = Json.obj(); point.put("date", r.str("point_date"));
                        point.put("equityCents", r.lng("equity_cents")); return point;
                    }, id).forEach(equity::add);
            ArrayNode notes = report.putArray("notes");
            Db.queryOn(c, "SELECT note FROM backtest_note WHERE backtest_id=? ORDER BY note_index",
                    r -> r.str("note"), id).forEach(notes::add);
            if ("SINGLE".equals(stored.kind())) {
                ArrayNode skipped = report.putArray("skipped");
                Db.queryOn(c, "SELECT skip_date::text skip_date,reason FROM backtest_skip WHERE backtest_id=? " +
                                "ORDER BY skip_index", r -> {
                            ObjectNode item = Json.obj(); item.put("date", r.str("skip_date"));
                            item.put("reason", r.str("reason")); return item;
                        }, id).forEach(skipped::add);
            }
            ObjectNode assumptions = report.putObject("assumptions");
            Db.queryOn(c, "SELECT assumption_key,assumption_value::text assumption_value FROM backtest_assumption " +
                            "WHERE backtest_id=? ORDER BY assumption_key",
                    r -> Map.entry(r.str("assumption_key"), Json.parse(r.str("assumption_value"))), id)
                    .forEach(entry -> assumptions.set(entry.getKey(), entry.getValue()));
            @SuppressWarnings("unchecked")
            Map<String, Object> out = Json.MAPPER.convertValue(report, Map.class);
            return out;
        });
    }

    private static String baseSelect() {
        return "SELECT id,created_at::text created_at,run_kind,symbol,strategy,from_date::text from_date,to_date::text to_date," +
                "target_dte,entry_every_days,qty,slippage_pct,starting_cash_cents,max_concurrent,short_delta,width_pct," +
                "profit_target_pct,stop_fraction,roll_dte,pricing_mode,confidence,days_requested,days_covered,sample_size," +
                "concurrent_peak,win_rate,avg_return_on_risk,starting_cents,ending_cents,max_drawdown_pct,assignments," +
                "demo_underlying,disclaimer FROM backtests";
    }

    private static StoredRun mapBase(Db.Row r) {
        String kind = r.str("run_kind");
        ObjectNode request = Json.obj();
        put(request, "symbol", r.str("symbol")); put(request, "strategy", r.str("strategy"));
        put(request, "from", r.str("from_date")); put(request, "to", r.str("to_date"));
        put(request, "targetDte", intOrNull(r, "target_dte"));
        put(request, "entryEveryDays", intOrNull(r, "entry_every_days")); put(request, "qty", intOrNull(r, "qty"));
        put(request, "startingCashCents", r.lngOrNull("starting_cash_cents"));
        if ("SINGLE".equals(kind)) put(request, "slippagePct", r.dblOrNull("slippage_pct"));
        else {
            put(request, "maxConcurrent", intOrNull(r, "max_concurrent")); put(request, "shortDelta", r.dblOrNull("short_delta"));
            put(request, "widthPct", r.dblOrNull("width_pct")); put(request, "profitTargetPct", r.dblOrNull("profit_target_pct"));
            put(request, "stopFraction", r.dblOrNull("stop_fraction")); put(request, "rollDte", intOrNull(r, "roll_dte"));
        }
        ObjectNode report = Json.obj();
        put(report, "id", r.str("id")); put(report, "symbol", r.str("symbol")); put(report, "strategy", r.str("strategy"));
        put(report, "from", r.str("from_date")); put(report, "to", r.str("to_date"));
        put(report, "pricingMode", r.str("pricing_mode")); put(report, "confidence", r.str("confidence"));
        if ("SINGLE".equals(kind)) put(report, "daysRequested", intOrNull(r, "days_requested"));
        put(report, "daysCovered", r.intv("days_covered")); put(report, "sampleSize", r.intv("sample_size"));
        if ("PORTFOLIO".equals(kind)) put(report, "concurrentPeak", intOrNull(r, "concurrent_peak"));
        put(report, "winRate", r.dblOrNull("win_rate")); put(report, "avgReturnOnRisk", r.dblOrNull("avg_return_on_risk"));
        put(report, "startingCents", r.lng("starting_cents")); put(report, "endingCents", r.lng("ending_cents"));
        put(report, "maxDrawdownPct", r.dbl("max_drawdown_pct"));
        if ("SINGLE".equals(kind)) put(report, "assignments", intOrNull(r, "assignments"));
        put(report, "demoUnderlying", r.bool("demo_underlying")); put(report, "disclaimer", r.str("disclaimer"));
        return new StoredRun(kind, request, report);
    }

    private record StoredRun(String kind, ObjectNode request, ObjectNode report) {}

    private static String text(JsonNode n, String key) {
        JsonNode v = n == null ? null : n.get(key); return v == null || v.isNull() ? null : v.asText();
    }
    private static String requiredText(JsonNode n, String key) {
        String value = text(n, key); if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }
    private static Long longOrNull(JsonNode n, String key) { JsonNode v=n.get(key); return v==null||v.isNull()?null:v.longValue(); }
    private static Integer intOrNull(JsonNode n, String key) { JsonNode v=n.get(key); return v==null||v.isNull()?null:v.intValue(); }
    private static Double doubleOrNull(JsonNode n, String key) { JsonNode v=n.get(key); return v==null||v.isNull()?null:v.doubleValue(); }
    private static long requiredLong(JsonNode n, String key) { Long v=longOrNull(n,key); if(v==null)throw new IllegalArgumentException(key+" is required"); return v; }
    private static int requiredInt(JsonNode n, String key) { Integer v=intOrNull(n,key); if(v==null)throw new IllegalArgumentException(key+" is required"); return v; }
    private static double requiredDouble(JsonNode n, String key) { Double v=doubleOrNull(n,key); if(v==null)throw new IllegalArgumentException(key+" is required"); return v; }
    private static Integer boolIntOrNull(JsonNode n,String key){JsonNode v=n.get(key);return v==null||v.isNull()?null:v.asBoolean()?1:0;}
    private static Integer intOrNull(Db.Row r,String key){return r.lngOrNull(key)==null?null:r.intv(key);}
    private static Boolean boolOrNull(Db.Row r,String key){return r.lngOrNull(key)==null?null:r.bool(key);}

    private static OffsetDateTime parseCreatedAt(String value) {
        try { return OffsetDateTime.parse(value); }
        catch (java.time.format.DateTimeParseException noOffset) {
            return java.time.LocalDateTime.parse(value).atOffset(ZoneOffset.UTC);
        }
    }

    private static void put(ObjectNode n, String key, Object value) {
        if (value == null) { n.putNull(key); return; }
        if (value instanceof String v) n.put(key,v);
        else if (value instanceof Integer v) n.put(key,v);
        else if (value instanceof Long v) n.put(key,v);
        else if (value instanceof Double v) n.put(key,v);
        else if (value instanceof Boolean v) n.put(key,v);
        else n.set(key,Json.MAPPER.valueToTree(value));
    }
}
