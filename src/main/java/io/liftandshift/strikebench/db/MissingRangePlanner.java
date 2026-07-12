package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.market.MarketHours;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Plans only missing observed daily ranges, with a small overlap for vendor revisions. */
public final class MissingRangePlanner {

    public record Range(LocalDate from, LocalDate to, int missingSessions) {}
    public record Plan(String symbol, LocalDate requestedFrom, LocalDate requestedTo,
                       int existingSessions, int missingSessions, List<Range> ranges) {
        public boolean complete() { return missingSessions == 0; }
    }

    private static final int MAX_RANGES = 8;
    private final Db db;

    public MissingRangePlanner(Db db) { this.db = db; }

    public Plan plan(String symbol, LocalDate from, LocalDate to, String source) {
        String sym = normalize(symbol);
        if (from == null || to == null || from.isAfter(to)) throw new IllegalArgumentException("bad date range");
        String src = source == null || source.isBlank() || "auto".equalsIgnoreCase(source)
                ? null : source.trim().toLowerCase(Locale.ROOT);
        List<LocalDate> rows = src == null
                ? db.query("SELECT DISTINCT d::text d FROM underlying_bar WHERE symbol=? AND dataset_id='observed' "
                                + "AND observed=1 AND d BETWEEN ? AND ?",
                        r -> LocalDate.parse(r.str("d")), sym, from, to)
                : db.query("SELECT DISTINCT d::text d FROM underlying_bar WHERE symbol=? AND dataset_id='observed' "
                                + "AND observed=1 AND lower(source)=? AND d BETWEEN ? AND ?",
                        r -> LocalDate.parse(r.str("d")), sym, src, from, to);
        Set<LocalDate> existing = new HashSet<>(rows);
        List<LocalDate> missing = new ArrayList<>();
        int expected = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (!MarketHours.isTradingDay(d)) continue;
            expected++;
            if (!existing.contains(d)) missing.add(d);
        }
        if (missing.isEmpty()) return new Plan(sym, from, to, expected, 0, List.of());

        List<Range> grouped = group(missing);
        // Highly fragmented history is cheaper and kinder to retrieve as one bounded request than
        // as dozens of tiny calls. Existing rows are idempotent upserts.
        if (grouped.size() > MAX_RANGES) {
            grouped = List.of(new Range(missing.getFirst(), missing.getLast(), missing.size()));
        }
        // Re-fetch a short leading overlap so adjusted vendors may revise recent bars after a split.
        List<Range> withOverlap = grouped.stream().map(r -> new Range(
                previousTradingDay(r.from(), 3, from), r.to(), r.missingSessions())).toList();
        return new Plan(sym, from, to, Math.max(0, expected - missing.size()), missing.size(), withOverlap);
    }

    private static List<Range> group(List<LocalDate> missing) {
        List<Range> out = new ArrayList<>();
        LocalDate start = missing.getFirst(), previous = start;
        int count = 1;
        for (int i = 1; i < missing.size(); i++) {
            LocalDate current = missing.get(i);
            LocalDate expected = nextTradingDay(previous);
            if (!current.equals(expected)) {
                out.add(new Range(start, previous, count));
                start = current;
                count = 0;
            }
            count++;
            previous = current;
        }
        out.add(new Range(start, previous, count));
        return out;
    }

    private static LocalDate nextTradingDay(LocalDate d) {
        LocalDate x = d.plusDays(1);
        while (!MarketHours.isTradingDay(x)) x = x.plusDays(1);
        return x;
    }

    private static LocalDate previousTradingDay(LocalDate d, int count, LocalDate floor) {
        LocalDate x = d;
        int n = 0;
        while (n < count && x.isAfter(floor)) {
            x = x.minusDays(1);
            if (MarketHours.isTradingDay(x)) n++;
        }
        return x.isBefore(floor) ? floor : x;
    }

    private static String normalize(String symbol) {
        String s = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        if (s.isBlank()) throw new IllegalArgumentException("symbol is required");
        return s;
    }
}
