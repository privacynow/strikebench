package io.liftandshift.strikebench.backtest;

import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.market.CandleSeries;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.pricing.BlackScholes;
import io.liftandshift.strikebench.pricing.HistoricalVol;
import io.liftandshift.strikebench.pricing.VolSurface;
import io.liftandshift.strikebench.util.Money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Shared no-look-ahead data, pricing and evidence rules for every historical replay mode. */
public final class HistoricalReplayKernel {
    public enum PriceIntent { ENTRY, EXIT, MARK }

    public record Window(CandleSeries series, List<Candle> all, List<Candle> requested) {
        public boolean demo() { return series.isFixture(); }
    }

    /** Mutable only within one replay invocation; never retained on a service singleton. */
    public static final class Evidence {
        private long observedMarks;
        private long totalMarks;
        private int gridModeledEntries;
        public long observedMarks() { return observedMarks; }
        public long totalMarks() { return totalMarks; }
        public int gridModeledEntries() { return gridModeledEntries; }
        public void gridModeledEntry() { gridModeledEntries++; }
        public boolean mostlyObserved() { return totalMarks > 0 && observedMarks * 10 >= totalMarks * 9; }
    }

    private static final double RATE = 0.04;
    private final MarketDataService market;
    private final Db db;

    public HistoricalReplayKernel(MarketDataService market, Db db) {
        this.market = market;
        this.db = db;
    }

    public Window window(String symbol, LocalDate from, LocalDate to, int warmupDays,
                         AnalysisContext analysis) {
        CandleSeries series = market.candleSeries(symbol, from.minusDays(warmupDays), to, analysis);
        List<Candle> all = series.candles();
        List<Candle> requested = all.stream()
                .filter(c -> !c.date().isBefore(from) && !c.date().isAfter(to)).toList();
        return new Window(series, all, requested);
    }

    public static List<Candle> known(List<Candle> all, LocalDate date) {
        return all.stream().filter(c -> !c.date().isAfter(date)).toList();
    }

    public static double volatility(List<Candle> known, int window, double fallback) {
        double hv = HistoricalVol.annualized(known, window);
        return Double.isNaN(hv) || hv <= 0 ? fallback : hv;
    }

    public long valueCents(String symbol, List<Leg> legs, int qty, double spot, double iv,
                           LocalDate asOf, PriceIntent intent, boolean payoffOnly, Evidence evidence) {
        double total = 0;
        for (Leg leg : legs) {
            double value;
            if (leg.isStock()) {
                value = spot;
            } else {
                evidence.totalMarks++;
                Double observed = observedPrice(symbol, asOf, leg, intent);
                if (observed != null) {
                    evidence.observedMarks++;
                    value = observed;
                } else {
                    double t = ChronoUnit.DAYS.between(asOf, leg.expiration()) / 365.0;
                    double strike = leg.strike().doubleValue();
                    if (payoffOnly || t <= 0) {
                        value = intrinsicPerShare(leg, spot);
                    } else {
                        double smileIv = VolSurface.smile(iv, spot, strike, t);
                        value = BlackScholes.price(leg.type() == OptionType.CALL, spot, strike,
                                t, RATE, 0, smileIv);
                    }
                }
            }
            double sign = leg.action() == LegAction.BUY ? 1 : -1;
            total += sign * value * Leg.SHARES_PER_CONTRACT * leg.ratio() * qty;
        }
        return Money.toCents(BigDecimal.valueOf(total));
    }

    public static long intrinsicValueCents(List<Leg> legs, int qty, BigDecimal spot) {
        long total = 0;
        for (Leg leg : legs) {
            long sign = leg.action() == LegAction.BUY ? 1 : -1;
            total += sign * Money.centsFromPrice(leg.intrinsicPerShare(spot),
                    (long) Leg.SHARES_PER_CONTRACT * leg.ratio() * qty);
        }
        return total;
    }

    private Double observedPrice(String symbol, LocalDate date, Leg leg, PriceIntent intent) {
        if (db == null || leg.isStock()) return null;
        var rows = db.query(
                "SELECT mark, bid, ask FROM option_bar WHERE symbol=? AND asof=? AND expiration=? AND strike=? "
              + "AND opt_type=? AND dataset_id='observed' AND bid_ask_observed=1 LIMIT 1",
                r -> new Double[]{r.dblOrNull("mark"), r.dblOrNull("bid"), r.dblOrNull("ask")},
                symbol, date, leg.expiration(), leg.strike(), leg.type().name());
        if (rows.isEmpty()) return null;
        Double mark = rows.getFirst()[0], bid = rows.getFirst()[1], ask = rows.getFirst()[2];
        Double side = switch (intent) {
            case ENTRY -> leg.action() == LegAction.BUY ? ask : bid;
            case EXIT -> leg.action() == LegAction.BUY ? bid : ask;
            case MARK -> null;
        };
        if (intent != PriceIntent.MARK) {
            // An observed mark is not an executable fill. Missing historical bid/ask falls back to
            // the model and therefore lowers the run's observed-evidence ratio.
            return side != null && side >= 0 ? side : null;
        }
        if (mark != null) return mark;
        return bid != null && ask != null && ask >= bid ? (bid + ask) / 2.0 : null;
    }

    public List<Double> listedStrikes(String symbol, LocalDate date, LocalDate expiration,
                                      OptionType type) {
        if (db == null || expiration == null) return List.of();
        return db.query("SELECT DISTINCT strike FROM option_bar WHERE symbol=? AND asof=? AND expiration=? "
                        + "AND opt_type=? AND dataset_id='observed' ORDER BY strike",
                r -> r.bd("strike").doubleValue(), symbol, date, expiration, type.name());
    }

    public LocalDate listedExpirationNear(String symbol, LocalDate date, int targetDte,
                                          OptionType type) {
        if (db == null) return null;
        LocalDate target = date.plusDays(targetDte);
        List<LocalDate> rows = db.query(
                "SELECT DISTINCT expiration::text e FROM option_bar WHERE symbol=? AND asof=? "
              + "AND opt_type=? AND dataset_id='observed' AND expiration > ? "
              + "GROUP BY expiration HAVING COUNT(DISTINCT strike) >= 2",
                r -> LocalDate.parse(r.str("e")), symbol, date, type.name(), date);
        return rows.stream().min(java.util.Comparator.comparingLong(e ->
                Math.abs(ChronoUnit.DAYS.between(e, target)))).orElse(null);
    }

    public static double maxDrawdownPct(List<? extends java.util.Map<String, ?>> equity) {
        double peak = Double.NEGATIVE_INFINITY, max = 0;
        for (var point : equity) {
            double value = ((Number) point.get("equityCents")).doubleValue();
            peak = Math.max(peak, value);
            if (peak > 0) max = Math.max(max, (peak - value) / peak);
        }
        return Math.round(max * 10000.0) / 10000.0;
    }

    private static double intrinsicPerShare(Leg leg, double spot) {
        return leg.type() == OptionType.CALL
                ? Math.max(0, spot - leg.strike().doubleValue())
                : Math.max(0, leg.strike().doubleValue() - spot);
    }
}
