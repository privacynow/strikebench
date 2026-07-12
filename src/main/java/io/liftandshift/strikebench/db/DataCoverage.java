package io.liftandshift.strikebench.db;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Data Center's coverage matrix: for every symbol we hold history on, what underlying and
 * option data exists, over what date range, and whether it's observed (real) or demo. Answers
 * "what data do we have and how good is it?" — the honest inventory that decides which evidence
 * tier the backtester/research can reach.
 */
public final class DataCoverage {

    private final Db db;

    public DataCoverage(Db db) { this.db = db; }

    public record SymbolCoverage(String symbol, String underlyingFrom, String underlyingTo, long underlyingBars,
                                 boolean underlyingObserved, String underlyingSources, String underlyingBasis,
                                 String optionFrom, String optionTo, long optionRows, long optionDays,
                                 boolean optionObserved) {}

    public record CoverageSummary(long underlyingSymbols, long underlyingBars, long optionSymbols,
                                  long optionRows, long observedUnderlyingSymbols, long observedOptionSymbols) {}

    public List<SymbolCoverage> bySymbol() {
        Map<String, Object[]> u = new LinkedHashMap<>();
        db.query("SELECT symbol, min(d)::text f, max(d)::text t, count(DISTINCT d) bars, min(observed) obs, "
               + "string_agg(DISTINCT source, ',' ORDER BY source) srcs, "
               + "string_agg(DISTINCT bar_kind, ',' ORDER BY bar_kind) basis "
               // Coverage describes the OBSERVED world: synthetic scenario runs must not inflate
               // it, and 'observed' is true only when EVERY bar is observed (weakest link).
               + "FROM underlying_bar WHERE dataset_id='observed' GROUP BY symbol",
                r -> u.put(r.str("symbol"),
                        new Object[]{r.str("f"), r.str("t"), r.lng("bars"), r.lng("obs") == 1, r.str("srcs"), r.str("basis")}));
        Map<String, Object[]> o = new LinkedHashMap<>();
        db.query("SELECT symbol, min(asof)::text f, max(asof)::text t, count(*) rows, "
               + "count(DISTINCT asof) days, min(CASE WHEN bid_ask_observed=1 THEN 1 ELSE 0 END) obs "
               + "FROM option_bar WHERE dataset_id='observed' GROUP BY symbol",
                r -> o.put(r.str("symbol"),
                        new Object[]{r.str("f"), r.str("t"), r.lng("rows"), r.lng("days"), r.lng("obs") == 1}));

        java.util.Set<String> symbols = new java.util.TreeSet<>();
        symbols.addAll(u.keySet());
        symbols.addAll(o.keySet());
        List<SymbolCoverage> out = new ArrayList<>();
        for (String s : symbols) {
            Object[] ub = u.get(s);
            Object[] ob = o.get(s);
            out.add(new SymbolCoverage(s,
                    ub == null ? null : (String) ub[0], ub == null ? null : (String) ub[1],
                    ub == null ? 0 : (long) ub[2], ub != null && (boolean) ub[3],
                    ub == null ? null : (String) ub[4], ub == null ? null : (String) ub[5],
                    ob == null ? null : (String) ob[0], ob == null ? null : (String) ob[1],
                    ob == null ? 0 : (long) ob[2], ob == null ? 0 : (long) ob[3],
                    ob != null && (boolean) ob[4]));
        }
        return out;
    }

    public CoverageSummary summary() {
        // OBSERVED world only: synthetic scenario rows must never inflate the coverage story.
        long[] u = db.query("SELECT count(*) syms, coalesce(sum(bars),0) bars, "
                + "coalesce(sum(CASE WHEN obs=1 THEN 1 ELSE 0 END),0) obs FROM ("
                + "SELECT symbol,count(DISTINCT d) bars,min(observed) obs FROM underlying_bar "
                + "WHERE dataset_id='observed' GROUP BY symbol) x",
                r -> new long[]{r.lng("syms"), r.lng("bars"), r.lng("obs")}).getFirst();
        long[] o = db.query("SELECT count(DISTINCT symbol) syms, count(*) rows, "
                + "count(DISTINCT CASE WHEN bid_ask_observed=1 THEN symbol END) obs FROM option_bar "
                + "WHERE dataset_id='observed'",
                r -> new long[]{r.lng("syms"), r.lng("rows"), r.lng("obs")}).getFirst();
        return new CoverageSummary(u[0], u[1], o[0], o[1], u[2], o[2]);
    }
}
