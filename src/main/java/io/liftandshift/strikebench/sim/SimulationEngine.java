package io.liftandshift.strikebench.sim;

import io.liftandshift.strikebench.db.DatasetService;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.model.Candle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a {@link ScenarioSpec} into a PERSISTED synthetic dataset: one concrete future (the seed's
 * path) written as daily bars under a fresh dataset_id, auto-saved in the dataset registry with its
 * full spec — so every run coexists with observed data (never overwrites it), can be re-selected as
 * the active analysis dataset, compared against others, and reproduced exactly from its seed. When
 * real history exists it also anchors the run: the block-bootstrap model resamples the symbol's own
 * observed returns, and the start price is the last real close.
 */
public final class SimulationEngine {

    private final MarketDataService market;
    private final DatasetService datasets;
    private final Db db;
    private final Clock clock;
    private final PathGenerator generator = new PathGenerator();

    public SimulationEngine(MarketDataService market, DatasetService datasets, Db db, Clock clock) {
        this.market = market;
        this.datasets = datasets;
        this.db = db;
        this.clock = clock;
    }

    public record DatasetRun(String datasetId, String name, String symbol, int bars, long seed,
                             double startPrice, double endPrice, List<String> notes) {}

    /** Generate + persist one synthetic future for a symbol. Returns the auto-saved dataset. */
    public DatasetRun runAndPersist(String symbolRaw, ScenarioSpec specRaw, String userId) {
        String symbol = symbolRaw == null ? "" : symbolRaw.trim().toUpperCase(Locale.ROOT);
        if (symbol.isEmpty()) throw new IllegalArgumentException("symbol is required");
        ScenarioSpec spec = specRaw.sane();

        // Anchor on reality where we can: start at the latest real price; feed the bootstrap real returns.
        double spot = market.quote(symbol).map(q -> q.mark() == null ? null : q.mark().doubleValue()).orElse(null) == null
                ? 100.0 : market.quote(symbol).map(q -> q.mark().doubleValue()).orElse(100.0);
        double[] hist = historicalLogReturns(symbol);

        double[] path = generator.generate(spec, spot, hist)[0]; // a dataset is ONE concrete future (the seed's)
        int spd = Math.max(1, spec.stepsPerDay());
        int days = spec.totalSteps() / spd;

        String name = symbol + " · " + pretty(spec.shape()) + " · " + days + "d · seed " + spec.seed();
        String id = datasets.create(name, "SYNTHETIC_PURE", symbol, spec.seed(), spec, userId);

        // Daily bars from the path: intraday steps give real O/H/L/C per day; daily steps give closes.
        List<Candle> bars = toDailyBars(path, spd, nextTradingDay(LocalDate.now(clock)));
        db.tx(c -> {
            for (Candle b : bars) {
                Db.execOn(c, "INSERT INTO underlying_bar (symbol, d, open, high, low, close, volume, source, observed, dataset_id) "
                        + "VALUES (?,?,?,?,?,?,?,?,0,?) ON CONFLICT (symbol, d, source, dataset_id) DO UPDATE SET "
                        + "open=excluded.open, high=excluded.high, low=excluded.low, close=excluded.close",
                        symbol, b.date(), b.open(), b.high(), b.low(), b.close(), b.volume(), "synthetic", id);
            }
            return null;
        });

        List<String> notes = new ArrayList<>();
        notes.add("Synthetic future — one concrete path from seed " + spec.seed() + ". Saved as its own dataset; observed data is untouched.");
        if (hist != null && spec.model() == ScenarioSpec.PathModel.BLOCK_BOOTSTRAP) {
            notes.add("Anchored on " + symbol + "'s own observed daily returns (block bootstrap).");
        }
        return new DatasetRun(id, name, symbol, bars.size(), spec.seed(), round2(path[0]), round2(path[path.length - 1]), notes);
    }

    public record PreviewBand(int day, double p10, double p50, double p90) {}

    public record Preview(String symbol, double spot, int paths, int horizonDays,
                          List<PreviewBand> bands, List<List<Double>> samples,
                          double endP10, double endP50, double endP90, List<String> notes) {}

    /**
     * The "show me N possible futures" fan — price bands per day + a few concrete sample paths for
     * the preview chart. Pure compute: nothing is persisted; the same seed reproduces it exactly.
     */
    public Preview preview(String symbolRaw, ScenarioSpec specRaw) {
        String symbol = symbolRaw == null ? "" : symbolRaw.trim().toUpperCase(Locale.ROOT);
        if (symbol.isEmpty()) throw new IllegalArgumentException("symbol is required");
        ScenarioSpec spec = specRaw.sane();
        double spot = market.quote(symbol).map(q -> q.mark() == null ? 100.0 : q.mark().doubleValue()).orElse(100.0);
        double[] hist = historicalLogReturns(symbol);
        double[][] paths = generator.generate(spec, spot, hist);
        int spd = Math.max(1, spec.stepsPerDay());
        int days = spec.totalSteps() / spd;

        List<PreviewBand> bands = new ArrayList<>();
        double[] tmp = new double[paths.length];
        for (int day = 0; day <= days; day++) {
            int i = Math.min(spec.totalSteps(), day * spd);
            for (int p = 0; p < paths.length; p++) tmp[p] = paths[p][i];
            double[] sorted = tmp.clone();
            java.util.Arrays.sort(sorted);
            bands.add(new PreviewBand(day, round2(q(sorted, 0.10)), round2(q(sorted, 0.50)), round2(q(sorted, 0.90))));
        }
        List<List<Double>> samples = new ArrayList<>();
        for (int p = 0; p < Math.min(3, paths.length); p++) {
            List<Double> sp = new ArrayList<>();
            for (int day = 0; day <= days; day++) sp.add(round2(paths[p][Math.min(spec.totalSteps(), day * spd)]));
            samples.add(sp);
        }
        PreviewBand end = bands.getLast();
        List<String> notes = new ArrayList<>();
        notes.add("Synthetic futures from seed " + spec.seed() + " — a model of what COULD happen, never a forecast.");
        if (hist == null && spec.model() == ScenarioSpec.PathModel.BLOCK_BOOTSTRAP) {
            notes.add("No observed history for " + symbol + " to bootstrap from — using a normal model instead.");
        }
        return new Preview(symbol, round2(spot), paths.length, days, bands, samples,
                end.p10(), end.p50(), end.p90(), notes);
    }

    private static double q(double[] sorted, double p) {
        return sorted[Math.max(0, Math.min(sorted.length - 1, (int) Math.floor(p * (sorted.length - 1))))];
    }

    /** Real mean-removed inputs for the bootstrap model, or null when no observed history exists. */
    public double[] historicalLogReturns(String symbol) {
        try {
            LocalDate to = LocalDate.now(clock);
            List<Candle> candles = market.candles(symbol, to.minusYears(2), to);
            if (candles.size() < 30) return null;
            double[] rs = new double[candles.size() - 1];
            for (int i = 1; i < candles.size(); i++) {
                rs[i - 1] = Math.log(candles.get(i).close().doubleValue() / candles.get(i - 1).close().doubleValue());
            }
            return rs;
        } catch (Exception e) { return null; }
    }

    private static List<Candle> toDailyBars(double[] path, int spd, LocalDate firstDay) {
        List<Candle> out = new ArrayList<>();
        LocalDate d = firstDay;
        int days = (path.length - 1) / spd;
        for (int day = 1; day <= days; day++) {
            int from = (day - 1) * spd, to = day * spd;
            double open = path[from], close = path[to], hi = open, lo = open;
            for (int i = from; i <= to; i++) { hi = Math.max(hi, path[i]); lo = Math.min(lo, path[i]); }
            out.add(new Candle(d, bd(open), bd(hi), bd(lo), bd(close), 0, false));
            d = nextTradingDay(d.plusDays(1));
        }
        return out;
    }

    private static LocalDate nextTradingDay(LocalDate d) {
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) d = d.plusDays(1);
        return d;
    }

    private static String pretty(ScenarioSpec.Shape s) {
        return s.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static BigDecimal bd(double v) { return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP); }
    private static double round2(double v) { return Math.round(v * 100) / 100.0; }

    public Map<String, Object> toJson(DatasetRun r) {
        return Map.of("datasetId", r.datasetId(), "name", r.name(), "symbol", r.symbol(), "bars", r.bars(),
                "seed", r.seed(), "startPrice", r.startPrice(), "endPrice", r.endPrice(), "notes", r.notes());
    }
}
