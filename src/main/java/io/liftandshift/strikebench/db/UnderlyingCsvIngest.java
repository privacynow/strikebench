package io.liftandshift.strikebench.db;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Validates and imports user-owned daily price CSV without ever writing rejected rows. */
public final class UnderlyingCsvIngest {

    public enum Basis { AUTO, RAW, ADJUSTED }
    public record Result(int rowsWritten, int quarantined, int symbols, LocalDate from, LocalDate to,
                         String source, String barBasis, String note) {}

    private record Bar(String symbol, LocalDate date, BigDecimal open, BigDecimal high, BigDecimal low,
                       BigDecimal close, Long volume, boolean adjusted, String kind, String raw) {}

    private static final Map<String, List<String>> ALIASES = Map.of(
            "date", List.of("date", "day", "timestamp", "time"),
            "symbol", List.of("symbol", "ticker", "instrument"),
            "open", List.of("open", "1. open"),
            "high", List.of("high", "2. high"),
            "low", List.of("low", "3. low"),
            "close", List.of("close", "4. close", "price"),
            "adjclose", List.of("adj close", "adj_close", "adjusted close", "adjusted_close", "5. adjusted close"),
            "volume", List.of("volume", "vol", "6. volume"));

    private UnderlyingCsvIngest() {}

    public static Result run(InputStream input, String fileName, String fallbackSymbol, String sourceLabel,
                             Basis basis, Db db, DataSyncState state, Clock clock, String ownerId) throws IOException {
        if (input == null) throw new IllegalArgumentException("CSV file is required");
        String fallback = normalizeSymbol(fallbackSymbol);
        String sourceBase = "user-csv:" + safeLabel(sourceLabel == null || sourceLabel.isBlank() ? fileName : sourceLabel);
        String source = sourceBase;
        Basis chosen = basis == null ? Basis.AUTO : basis;
        Map<String, Bar> accepted = new LinkedHashMap<>();
        java.util.Set<String> conflictingKeys = new java.util.HashSet<>();
        int quarantined = 0;
        LocalDate today = LocalDate.now(clock);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) throw new IllegalArgumentException("CSV file is empty");
            Map<String, Integer> columns = mapColumns(Csv.split(stripBom(header)));
            if (!columns.containsKey("date") || !columns.containsKey("close")) {
                throw new IllegalArgumentException("CSV needs Date and Close columns; Symbol is optional when entered in the form");
            }
            if (!columns.containsKey("symbol") && fallback.isBlank()) {
                throw new IllegalArgumentException("Enter a symbol because this CSV has no Symbol column");
            }
            boolean hasAdjustedColumn = columns.containsKey("adjclose");
            boolean declaredAdjusted = chosen == Basis.ADJUSTED && !hasAdjustedColumn;
            boolean fileAdjusted = chosen == Basis.ADJUSTED
                    || (chosen == Basis.AUTO && hasAdjustedColumn);
            source = sourceBase + (declaredAdjusted ? ":adjusted-declared"
                    : fileAdjusted ? ":adjusted" : ":raw");
            String line;
            int row = 1;
            while ((line = reader.readLine()) != null) {
                row++;
                if (line.isBlank()) continue;
                if (row > 1_000_001) throw new IllegalArgumentException("CSV exceeds the one-million-row local import limit");
                try {
                    String[] values = Csv.split(line);
                    String symbol = columns.containsKey("symbol") ? normalizeSymbol(get(values, columns, "symbol")) : fallback;
                    if (symbol.isBlank()) throw new IllegalArgumentException("missing symbol");
                    LocalDate date = LocalDate.parse(get(values, columns, "date").trim());
                    if (date.isAfter(today)) throw new IllegalArgumentException("future date");
                    BigDecimal rawClose = positive(values, columns, "close", true);
                    BigDecimal adj = positive(values, columns, "adjclose", false);
                    BigDecimal factor = BigDecimal.ONE;
                    boolean adjusted = fileAdjusted;
                    if (chosen == Basis.AUTO && fileAdjusted && adj == null) {
                        throw new IllegalArgumentException("missing adjusted close in an adjusted file");
                    }
                    if (adjusted && adj != null) factor = adj.divide(rawClose, MathContext.DECIMAL64);
                    BigDecimal close = adjusted && adj != null ? adj : rawClose;
                    BigDecimal open = scaled(values, columns, "open", factor);
                    BigDecimal high = scaled(values, columns, "high", factor);
                    BigDecimal low = scaled(values, columns, "low", factor);
                    String kind;
                    if (open == null && high == null && low == null) kind = "CLOSE_ONLY";
                    else if (open != null && high != null && low != null) kind = columns.containsKey("volume") ? "OHLCV" : "OHLC";
                    else throw new IllegalArgumentException("open, high, and low must be supplied together");
                    Long volume = longValue(values, columns, "volume");
                    if (volume != null && volume < 0) throw new IllegalArgumentException("negative volume");
                    if (!"CLOSE_ONLY".equals(kind)) validateOhlc(open, high, low, close);
                    Bar bar = new Bar(symbol, date, open, high, low, close, volume, adjusted, kind, line);
                    String key = symbol + "|" + date;
                    if (conflictingKeys.contains(key)) throw new IllegalArgumentException("conflicting duplicate symbol/date");
                    Bar prior = accepted.putIfAbsent(key, bar);
                    if (prior != null && !same(prior, bar)) {
                        accepted.remove(key); // contradictory duplicates do not get an arbitrary winner
                        conflictingKeys.add(key);
                        throw new IllegalArgumentException("conflicting duplicate symbol/date");
                    }
                } catch (RuntimeException e) {
                    quarantined++;
                    state.quarantine(ownerId, null, source, fallback.isBlank() ? null : fallback, "row " + row,
                            publicReason(e), line);
                }
            }
        }
        if (accepted.isEmpty()) {
            return new Result(0, quarantined, 0, null, null, source, "NONE",
                    "No eligible rows were imported. Review the quarantine summary.");
        }

        String sourceForWrite = source;
        db.tx(c -> {
            for (Bar bar : accepted.values()) write(c, sourceForWrite, bar);
            return null;
        });
        LocalDate from = accepted.values().stream().map(Bar::date).min(LocalDate::compareTo).orElse(null);
        LocalDate to = accepted.values().stream().map(Bar::date).max(LocalDate::compareTo).orElse(null);
        int symbols = (int) accepted.values().stream().map(Bar::symbol).distinct().count();
        String barBasis = accepted.values().stream().map(Bar::kind).distinct().count() == 1
                ? accepted.values().iterator().next().kind() : "MIXED";
        for (String symbol : accepted.values().stream().map(Bar::symbol).distinct().toList()) {
            LocalDate symbolLast = accepted.values().stream().filter(b -> b.symbol().equals(symbol))
                    .map(Bar::date).max(LocalDate::compareTo).orElse(to);
            state.succeeded(ownerId, "user_csv", symbol, from, to, symbolLast,
                    accepted.values().stream().filter(b -> b.symbol().equals(symbol)).count(), true,
                    "Imported from " + source + " (" + barBasis + ").");
        }
        String basisNote = source.endsWith(":adjusted-declared")
                ? " Prices were accepted as user-declared adjusted because the file has no separate adjusted-close column." : "";
        return new Result(accepted.size(), quarantined, symbols, from, to, source, barBasis,
                "Imported " + accepted.size() + " observed daily row(s) for " + symbols + " symbol(s)"
                        + (quarantined > 0 ? "; quarantined " + quarantined + " invalid row(s)." : ".") + basisNote);
    }

    private static void write(Connection c, String source, Bar b) throws java.sql.SQLException {
        Db.execOn(c, "INSERT INTO underlying_bar(symbol,d,open,high,low,close,volume,source,observed,adjusted,quality_rank,bar_kind) "
                        + "VALUES (?,?,?,?,?,?,?,?,1,?,80,?) ON CONFLICT(symbol,d,source,dataset_id) DO UPDATE SET "
                        + "open=excluded.open,high=excluded.high,low=excluded.low,close=excluded.close,volume=excluded.volume,"
                        + "observed=1,adjusted=excluded.adjusted,quality_rank=80,bar_kind=excluded.bar_kind,created_at=now()",
                b.symbol(), b.date(), b.open(), b.high(), b.low(), b.close(), b.volume(), source, b.adjusted(), b.kind());
    }

    private static Map<String, Integer> mapColumns(String[] header) {
        Map<String, Integer> names = new java.util.HashMap<>();
        for (int i = 0; i < header.length; i++) names.put(header[i].trim().toLowerCase(Locale.ROOT), i);
        Map<String, Integer> out = new java.util.HashMap<>();
        ALIASES.forEach((canonical, aliases) -> aliases.stream().filter(names::containsKey).findFirst()
                .ifPresent(alias -> out.put(canonical, names.get(alias))));
        return out;
    }

    private static BigDecimal positive(String[] row, Map<String, Integer> cols, String key, boolean required) {
        String raw = get(row, cols, key).trim();
        if (raw.isEmpty()) {
            if (required) throw new IllegalArgumentException("missing " + key);
            return null;
        }
        BigDecimal value = new BigDecimal(raw);
        if (value.signum() <= 0) throw new IllegalArgumentException("non-positive " + key);
        return value;
    }

    private static BigDecimal scaled(String[] row, Map<String, Integer> cols, String key, BigDecimal factor) {
        BigDecimal value = positive(row, cols, key, false);
        return value == null ? null : value.multiply(factor, MathContext.DECIMAL64);
    }

    private static Long longValue(String[] row, Map<String, Integer> cols, String key) {
        String raw = get(row, cols, key).trim();
        return raw.isEmpty() ? null : (long) Double.parseDouble(raw);
    }

    private static String get(String[] row, Map<String, Integer> cols, String key) {
        Integer i = cols.get(key);
        return i == null || i >= row.length ? "" : row[i];
    }

    private static void validateOhlc(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
        if (low.compareTo(high) > 0) throw new IllegalArgumentException("low is above high");
        if (high.compareTo(open.max(close)) < 0) throw new IllegalArgumentException("high is below open or close");
        if (low.compareTo(open.min(close)) > 0) throw new IllegalArgumentException("low is above open or close");
    }

    private static boolean same(Bar a, Bar b) {
        return java.util.Objects.equals(a.open(), b.open()) && java.util.Objects.equals(a.high(), b.high())
                && java.util.Objects.equals(a.low(), b.low()) && java.util.Objects.equals(a.close(), b.close())
                && java.util.Objects.equals(a.volume(), b.volume()) && a.adjusted() == b.adjusted();
    }

    private static String normalizeSymbol(String symbol) {
        String s = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        if (!s.isEmpty() && !s.matches("[A-Z0-9.^_-]{1,20}")) throw new IllegalArgumentException("invalid symbol");
        return s;
    }

    private static String safeLabel(String value) {
        String s = value == null ? "import" : value.replaceAll("[^A-Za-z0-9._-]", "-");
        s = s.replaceAll("-+", "-");
        if (s.isBlank()) s = "import";
        return s.substring(0, Math.min(60, s.length())).toLowerCase(Locale.ROOT);
    }

    private static String publicReason(RuntimeException e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? "invalid row" : m.substring(0, Math.min(160, m.length()));
    }

    private static String stripBom(String s) { return s != null && s.startsWith("\uFEFF") ? s.substring(1) : s; }
}
