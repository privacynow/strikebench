package io.liftandshift.strikebench.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One-time bulk ingest of LICENSED historical options data (a vendor CSV) into {@code option_bar}
 * — the "own the past" half of the evidence moat. Rows are tagged with the vendor {@code source}
 * and marked OBSERVED (bid_ask_observed=1, iv_source/greeks_source='vendor'), so once real history
 * is loaded, IV rank/percentile and backtests draw on observed prices instead of models.
 *
 * The parser is header-mapped and vendor-agnostic: it matches known column names (with common
 * aliases) case-insensitively, so ORATS/Cboe/Databento-style exports load without code changes.
 * Only the derived rows are stored — never redistribute the vendor file itself.
 *
 * Required columns: date, symbol, expiration, strike, type. Everything else is optional.
 */
public final class HistoricalOptionsIngest {

    private static final Logger log = LoggerFactory.getLogger(HistoricalOptionsIngest.class);

    private HistoricalOptionsIngest() {}

    public record IngestResult(int optionRows, int underlyingRows, int skipped, List<String> problems) {}

    /** Column aliases -> canonical name. First match wins. */
    private static final Map<String, List<String>> ALIASES = Map.ofEntries(
            Map.entry("date", List.of("date", "asof", "as_of", "quote_date", "trade_date", "dt")),
            Map.entry("symbol", List.of("symbol", "ticker", "root", "underlying_symbol", "act_symbol")),
            Map.entry("expiration", List.of("expiration", "expiry", "exp_date", "expirationdate", "expire_date")),
            Map.entry("strike", List.of("strike", "strike_price", "k")),
            Map.entry("type", List.of("type", "right", "cp", "call_put", "option_type", "putcall")),
            Map.entry("bid", List.of("bid", "bid_price", "best_bid")),
            Map.entry("ask", List.of("ask", "ask_price", "best_ask", "offer")),
            Map.entry("last", List.of("last", "last_price", "mark", "close")),
            Map.entry("iv", List.of("iv", "implied_vol", "impliedvol", "implied_volatility", "mid_iv")),
            Map.entry("delta", List.of("delta")),
            Map.entry("gamma", List.of("gamma")),
            Map.entry("theta", List.of("theta")),
            Map.entry("vega", List.of("vega")),
            Map.entry("open_interest", List.of("open_interest", "oi", "openinterest")),
            Map.entry("volume", List.of("volume", "vol", "trade_volume")),
            Map.entry("underlying", List.of("underlying", "underlying_price", "stock_price", "spot", "stkpx", "underlyingprice")));

    public static IngestResult runFromFile(String csvPath, String source, Db db) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(Path.of(csvPath), StandardCharsets.UTF_8)) {
            return run(r, source, db);
        }
    }

    public static IngestResult run(BufferedReader reader, String source, Db db) throws IOException {
        String headerLine = reader.readLine();
        if (headerLine == null) return new IngestResult(0, 0, 0, List.of("empty file"));
        Map<String, Integer> col = mapColumns(splitCsv(headerLine));
        for (String req : List.of("date", "symbol", "expiration", "strike", "type")) {
            if (!col.containsKey(req)) {
                return new IngestResult(0, 0, 0, List.of("missing required column: " + req + " (header: " + headerLine + ")"));
            }
        }

        List<String[]> rows = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.isBlank()) rows.add(splitCsv(line));
        }

        List<String> problems = new ArrayList<>();
        int[] counts = db.tx(c -> writeRows(c, rows, col, source, problems));
        log.info("ingested {} option bars + {} underlying bars from vendor '{}' ({} skipped)",
                counts[0], counts[1], source, counts[2]);
        return new IngestResult(counts[0], counts[1], counts[2], problems);
    }

    private static int[] writeRows(Connection c, List<String[]> rows, Map<String, Integer> col,
                                   String source, List<String> problems) throws java.sql.SQLException {
        String optSql = "INSERT INTO option_bar (symbol, asof, expiration, strike, opt_type, bid, ask, last, mark, "
                + "iv, delta, gamma, theta, vega, open_interest, volume, underlying, source, "
                + "bid_ask_observed, iv_source, greeks_source) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                + "ON CONFLICT (symbol, asof, expiration, strike, opt_type, source) DO UPDATE SET "
                + "bid=excluded.bid, ask=excluded.ask, last=excluded.last, mark=excluded.mark, iv=excluded.iv, "
                + "delta=excluded.delta, gamma=excluded.gamma, theta=excluded.theta, vega=excluded.vega, "
                + "open_interest=excluded.open_interest, volume=excluded.volume, underlying=excluded.underlying, "
                + "bid_ask_observed=excluded.bid_ask_observed, iv_source=excluded.iv_source, greeks_source=excluded.greeks_source";
        String undSql = "INSERT INTO underlying_bar (symbol, d, close, source, observed) VALUES (?,?,?,?,1) "
                + "ON CONFLICT (symbol, d, source) DO UPDATE SET close=excluded.close, observed=1";

        int opt = 0, skipped = 0;
        Map<String, BigDecimal> underlyings = new HashMap<>(); // symbol|date -> underlying close
        try (PreparedStatement ps = c.prepareStatement(optSql)) {
            for (String[] row : rows) {
                try {
                    String symbol = up(get(row, col, "symbol"));
                    LocalDate asof = LocalDate.parse(get(row, col, "date").trim());
                    LocalDate exp = LocalDate.parse(get(row, col, "expiration").trim());
                    BigDecimal strike = new BigDecimal(get(row, col, "strike").trim());
                    String type = optType(get(row, col, "type"));
                    if (symbol.isEmpty() || type == null) { skipped++; continue; }

                    BigDecimal bid = dec(row, col, "bid"), ask = dec(row, col, "ask");
                    Double iv = dbl(row, col, "iv");
                    Double delta = dbl(row, col, "delta"), gamma = dbl(row, col, "gamma"),
                            theta = dbl(row, col, "theta"), vega = dbl(row, col, "vega");
                    BigDecimal underlying = dec(row, col, "underlying");
                    boolean baObs = bid != null && ask != null;
                    boolean anyGreek = delta != null || gamma != null || theta != null || vega != null;

                    int i = 0;
                    ps.setObject(++i, symbol); ps.setObject(++i, asof); ps.setObject(++i, exp);
                    ps.setObject(++i, strike); ps.setObject(++i, type);
                    ps.setObject(++i, bid); ps.setObject(++i, ask); ps.setObject(++i, dec(row, col, "last"));
                    ps.setObject(++i, dec(row, col, "last")); // mark <- last when no dedicated mark
                    ps.setObject(++i, iv); ps.setObject(++i, delta); ps.setObject(++i, gamma);
                    ps.setObject(++i, theta); ps.setObject(++i, vega);
                    ps.setObject(++i, lng(row, col, "open_interest")); ps.setObject(++i, lng(row, col, "volume"));
                    ps.setObject(++i, underlying); ps.setObject(++i, source);
                    ps.setInt(++i, baObs ? 1 : 0);
                    ps.setObject(++i, iv != null ? "vendor" : null);
                    ps.setObject(++i, anyGreek ? "vendor" : null);
                    ps.addBatch();
                    opt++;
                    if (underlying != null) underlyings.put(symbol + "|" + asof, underlying);
                    if (opt % 1000 == 0) ps.executeBatch();
                } catch (RuntimeException e) {
                    skipped++;
                    if (problems.size() < 20) problems.add("row skipped: " + e.getClass().getSimpleName() + " " + e.getMessage());
                }
            }
            ps.executeBatch();
        }

        int und = 0;
        try (PreparedStatement ps = c.prepareStatement(undSql)) {
            for (Map.Entry<String, BigDecimal> e : underlyings.entrySet()) {
                String[] parts = e.getKey().split("\\|");
                ps.setObject(1, parts[0]); ps.setObject(2, LocalDate.parse(parts[1]));
                ps.setObject(3, e.getValue()); ps.setObject(4, source);
                ps.addBatch(); und++;
            }
            ps.executeBatch();
        }
        return new int[]{opt, und, skipped};
    }

    // ---- parsing helpers ----

    private static Map<String, Integer> mapColumns(String[] header) {
        Map<String, Integer> byName = new HashMap<>();
        for (int i = 0; i < header.length; i++) byName.put(header[i].trim().toLowerCase(Locale.ROOT), i);
        Map<String, Integer> out = new HashMap<>();
        for (var e : ALIASES.entrySet()) {
            for (String alias : e.getValue()) {
                if (byName.containsKey(alias)) { out.put(e.getKey(), byName.get(alias)); break; }
            }
        }
        return out;
    }

    private static String get(String[] row, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        return (i == null || i >= row.length) ? "" : row[i];
    }

    private static BigDecimal dec(String[] row, Map<String, Integer> col, String name) {
        String v = get(row, col, name).trim();
        if (v.isEmpty()) return null;
        try { return new BigDecimal(v); } catch (NumberFormatException e) { return null; }
    }

    private static Double dbl(String[] row, Map<String, Integer> col, String name) {
        String v = get(row, col, name).trim();
        if (v.isEmpty()) return null;
        try { return Double.parseDouble(v); } catch (NumberFormatException e) { return null; }
    }

    private static Long lng(String[] row, Map<String, Integer> col, String name) {
        String v = get(row, col, name).trim();
        if (v.isEmpty()) return null;
        try { return (long) Double.parseDouble(v); } catch (NumberFormatException e) { return null; }
    }

    private static String up(String s) { return s == null ? "" : s.trim().toUpperCase(Locale.ROOT); }

    private static String optType(String raw) {
        String t = up(raw);
        if (t.startsWith("C")) return "CALL";
        if (t.startsWith("P")) return "PUT";
        return null;
    }

    /** Minimal CSV split honoring double-quoted fields (vendor exports are otherwise clean). */
    private static String[] splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQ && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else inQ = !inQ;
            } else if (ch == ',' && !inQ) {
                out.add(cur.toString()); cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}
