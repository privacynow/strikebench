package io.liftandshift.strikebench.backtest;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.market.CandleSeries;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.pricing.BlackScholes;
import io.liftandshift.strikebench.pricing.HistoricalVol;
import io.liftandshift.strikebench.util.Ids;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Portfolio-level options backtester (Phase 4): unlike the single-position {@link Backtester}, this
 * holds CONCURRENT positions, selects strikes by DELTA, and manages each position with mechanical
 * rules that mirror the {@code ManagementPlan} — profit-target, stop, and a time/roll exit — settling
 * at intrinsic on expiry. No look-ahead: every day uses only candles up to and including that day
 * (HV, spot, expiry close). Pricing is modeled (Black-Scholes from realized vol); when a licensed
 * option history is loaded into {@code option_bar} it becomes the observed-pricing source (next step).
 *
 * Supported defined-risk families: CREDIT_PUT_SPREAD (income) and DEBIT_CALL_SPREAD (directional).
 */
public final class PortfolioBacktester {

    private static final int HV_WINDOW = 20;
    private static final double R = 0.04;                 // flat risk-free; short-dated spreads are insensitive
    private static final double DEFAULT_IV = 0.30;
    private static final String DISCLAIMER =
            "Modeled backtest — options priced by Black-Scholes from realized volatility, not traded prices. "
            + "Educational only; not a forecast and not investment advice.";

    private final MarketDataService market;
    private final AppConfig cfg;
    private final Clock clock;
    private final Db db;                 // optional: enables OBSERVED pricing from option_bar

    // Per-run state (a fresh instance per request, single-threaded per run).
    private String runSymbol;
    private boolean usedObserved;

    public PortfolioBacktester(MarketDataService market, AppConfig cfg, Clock clock) {
        this(market, cfg, clock, null);
    }

    public PortfolioBacktester(MarketDataService market, AppConfig cfg, Clock clock, Db db) {
        this.market = market;
        this.cfg = cfg;
        this.clock = clock;
        this.db = db;
    }

    public record PortfolioRequest(
            String symbol, String strategy, String from, String to,
            Integer targetDte, Integer entryEveryDays, Integer maxConcurrent, Integer qty,
            Double shortDelta, Double widthPct, Double profitTargetPct, Double stopFraction,
            Integer rollDte, Long startingCashCents) {}

    public record PortfolioTrade(String entryDate, String exitDate, String strategy,
                                 long creditCents, long pnlCents, long maxLossCents,
                                 Double returnOnRisk, String exitReason) {}

    public record PortfolioReport(
            String id, String symbol, String strategy, String from, String to,
            String pricingMode, String confidence, int daysCovered, int sampleSize, int concurrentPeak,
            Double winRate, Double avgReturnOnRisk, long startingCents, long endingCents,
            double maxDrawdownPct, List<PortfolioTrade> trades,
            List<Map<String, Object>> equityCurve, List<String> notes, String disclaimer) {}

    // A concrete leg of a modeled position.
    private record Leg(boolean call, boolean shortLeg, double strike) {}

    private static final class Position {
        LocalDate entryDate, expiration;
        List<Leg> legs;
        int qty;
        long entryValueCents;   // V_entry = Σ sign*price*100*qty (long +, short −)
        long creditCents;       // −entryValueCents (>0 credit, <0 debit)
        long maxProfitCents, maxLossCents;
    }

    public PortfolioReport run(PortfolioRequest req) {
        String symbol = req.symbol() == null ? "" : req.symbol().trim().toUpperCase(Locale.ROOT);
        if (symbol.isEmpty()) throw new IllegalArgumentException("symbol is required");
        this.runSymbol = symbol;
        this.usedObserved = false;
        Family family = Family.parse(req.strategy());
        LocalDate from = LocalDate.parse(req.from());
        LocalDate to = LocalDate.parse(req.to());
        if (!to.isAfter(from)) throw new IllegalArgumentException("'to' must be after 'from'");

        int targetDte = clamp(req.targetDte(), 30, 1, 365);
        int entryEvery = clamp(req.entryEveryDays(), 5, 1, 60);
        int maxConcurrent = clamp(req.maxConcurrent(), 4, 1, 20);
        int qty = clamp(req.qty(), 1, 1, 100);
        double shortDelta = clampD(req.shortDelta(), 0.30, 0.05, 0.6);
        double widthPct = clampD(req.widthPct(), 0.05, 0.01, 0.3);
        double profitTarget = clampD(req.profitTargetPct(), 0.5, 0.1, 1.0);
        double stopFraction = clampD(req.stopFraction(), 0.8, 0.2, 1.0);
        int rollDte = clamp(req.rollDte(), 7, 0, targetDte - 1);
        long startingCash = req.startingCashCents() == null ? 100_000_00L : req.startingCashCents();
        if (startingCash <= 0) throw new IllegalArgumentException("startingCashCents must be positive");

        List<String> notes = new ArrayList<>();
        CandleSeries series = market.candleSeries(symbol, from.minusDays(220), to);
        List<Candle> all = series.candles();
        boolean demo = series.isFixture() && !cfg.fixturesOnly();
        if (demo) notes.add("Underlying history is built-in DEMO DATA — add a Polygon/Alpha Vantage key for real candles.");
        List<Candle> window = all.stream().filter(c -> !c.date().isBefore(from) && !c.date().isAfter(to)).toList();

        List<PortfolioTrade> trades = new ArrayList<>();
        List<Map<String, Object>> equity = new ArrayList<>();
        if (window.isEmpty()) {
            notes.add("No underlying data for " + symbol + " in the window");
            return new PortfolioReport(Ids.backtest(), symbol, family.name(), from.toString(), to.toString(),
                    "PAYOFF_ONLY", "none", 0, 0, 0, null, null, startingCash, startingCash, 0,
                    trades, equity, notes, DISCLAIMER);
        }

        List<Position> openPositions = new ArrayList<>();
        long realized = 0;
        int concurrentPeak = 0;
        double peakEquity = startingCash, maxDd = 0;
        int dayIndex = 0;

        for (Candle day : window) {
            LocalDate date = day.date();
            double spot = day.close().doubleValue();
            List<Candle> known = all.stream().filter(c -> !c.date().isAfter(date)).toList();
            double hv = HistoricalVol.annualized(known, HV_WINDOW);
            double iv = Double.isNaN(hv) || hv <= 0 ? DEFAULT_IV : hv;

            // 1. Manage open positions (iterate a copy; remove on close).
            for (Position p : new ArrayList<>(openPositions)) {
                double tte = Math.max(0, ChronoUnit.DAYS.between(date, p.expiration)) / 365.0;
                if (!date.isBefore(p.expiration)) {
                    BigDecimal expClose = known.stream().filter(c -> !c.date().isAfter(p.expiration))
                            .reduce((a, b) -> b).map(Candle::close).orElse(day.close());
                    long pnl = intrinsicValueCents(p, expClose.doubleValue()) - p.entryValueCents;
                    realized += pnl;
                    trades.add(close(p, date, pnl, "EXPIRED"));
                    openPositions.remove(p);
                    continue;
                }
                long markValue = valueCents(p, spot, iv, tte, date);
                long unrealized = markValue - p.entryValueCents;
                String exit = null;
                if (p.maxProfitCents > 0 && unrealized >= profitTarget * p.maxProfitCents) exit = "PROFIT_TARGET";
                else if (p.maxLossCents > 0 && unrealized <= -stopFraction * p.maxLossCents) exit = "STOP";
                else if (ChronoUnit.DAYS.between(date, p.expiration) <= rollDte) exit = "TIME";
                if (exit != null) {
                    realized += unrealized;
                    trades.add(close(p, date, unrealized, exit));
                    openPositions.remove(p);
                }
            }

            // 2. Entry on cadence, capacity + capital permitting.
            if (dayIndex % entryEvery == 0 && openPositions.size() < maxConcurrent) {
                Position p = buildPosition(family, spot, iv, date, targetDte, qty, shortDelta, widthPct);
                if (p != null) {
                    long committed = openPositions.stream().mapToLong(x -> x.maxLossCents).sum();
                    if (committed + p.maxLossCents <= startingCash) openPositions.add(p);
                }
            }

            // 3. Equity = starting + realized + open unrealized.
            long openUnreal = 0;
            for (Position p : openPositions) {
                double tte = Math.max(0, ChronoUnit.DAYS.between(date, p.expiration)) / 365.0;
                openUnreal += valueCents(p, spot, iv, tte, date) - p.entryValueCents;
            }
            long eq = startingCash + realized + openUnreal;
            equity.add(Map.of("date", date.toString(), "equityCents", eq));
            concurrentPeak = Math.max(concurrentPeak, openPositions.size());
            peakEquity = Math.max(peakEquity, eq);
            if (peakEquity > 0) maxDd = Math.max(maxDd, (peakEquity - eq) / peakEquity);
            dayIndex++;
        }

        // Close survivors at model value on the last day (flagged as WINDOW_END, excluded from stats).
        Candle last = window.getLast();
        for (Position p : new ArrayList<>(openPositions)) {
            double tte = Math.max(0, ChronoUnit.DAYS.between(last.date(), p.expiration)) / 365.0;
            long unreal = valueCents(p, last.close().doubleValue(),
                    Math.max(DEFAULT_IV, hvOn(all, last.date())), tte, last.date()) - p.entryValueCents;
            realized += unreal;
            trades.add(close(p, last.date(), unreal, "WINDOW_END"));
        }

        List<PortfolioTrade> settled = trades.stream().filter(t -> !"WINDOW_END".equals(t.exitReason())).toList();
        int sample = settled.size();
        Double winRate = sample == 0 ? null
                : round((double) settled.stream().filter(t -> t.pnlCents() > 0).count() / sample);
        Double avgRoR = sample == 0 ? null
                : round(settled.stream().filter(t -> t.returnOnRisk() != null)
                        .mapToDouble(PortfolioTrade::returnOnRisk).average().orElse(0));
        long ending = startingCash + realized;

        String mode = demo ? "PAYOFF_ONLY" : usedObserved ? "OBSERVED_FROM_HISTORY" : "MODELED_FROM_UNDERLYING";
        String confidence = demo ? "none (demo data)" : usedObserved ? "observed" : "modeled";
        return new PortfolioReport(Ids.backtest(), symbol, family.name(), from.toString(), to.toString(),
                mode, confidence, window.size(), sample, concurrentPeak, winRate, avgRoR,
                startingCash, ending, round(maxDd * 100), trades, equity, notes, DISCLAIMER);
    }

    // ---- position construction (delta-based) ----

    private Position buildPosition(Family family, double spot, double iv, LocalDate date,
                                   int targetDte, int qty, double shortDelta, double widthPct) {
        LocalDate exp = date.plusDays(targetDte);
        double tte = targetDte / 365.0;
        double step = strikeStep(spot);
        double width = Math.max(step, Math.round(spot * widthPct / step) * step);

        List<Leg> legs = new ArrayList<>();
        if (family == Family.CREDIT_PUT_SPREAD) {
            double shortK = strikeForDelta(false, spot, tte, iv, -shortDelta, step); // short put ~ -shortDelta
            double longK = shortK - width;
            if (longK <= 0) return null;
            legs.add(new Leg(false, true, shortK));
            legs.add(new Leg(false, false, longK));
        } else { // DEBIT_CALL_SPREAD
            double longK = strikeForDelta(true, spot, tte, iv, 0.50, step);          // long call ~ ATM
            double shortK = longK + width;
            legs.add(new Leg(true, false, longK));
            legs.add(new Leg(true, true, shortK));
        }

        Position p = new Position();
        p.entryDate = date; p.expiration = exp; p.legs = legs; p.qty = qty;
        p.entryValueCents = valueCents(p, spot, iv, tte, date);
        p.creditCents = -p.entryValueCents;
        // Max profit/loss from the intrinsic payoff at extreme spots + the strikes.
        long best = Long.MIN_VALUE, worst = Long.MAX_VALUE;
        List<Double> probes = new ArrayList<>(List.of(0.01, spot * 3));
        for (Leg l : legs) probes.add(l.strike());
        for (double s : probes) {
            long pl = intrinsicValueCents(p, s) - p.entryValueCents;
            best = Math.max(best, pl); worst = Math.min(worst, pl);
        }
        p.maxProfitCents = Math.max(0, best);
        p.maxLossCents = Math.max(0, -worst);
        if (p.maxLossCents == 0) return null; // never accept a "riskless" modeled artifact
        return p;
    }

    /** The round strike whose modeled delta is closest to the target (call/put). */
    private static double strikeForDelta(boolean call, double spot, double tte, double iv,
                                         double targetDelta, double step) {
        double best = spot;
        double bestErr = Double.MAX_VALUE;
        for (double k = Math.max(step, spot * 0.6); k <= spot * 1.4; k += step) {
            double d = BlackScholes.delta(call, spot, k, tte, R, 0, iv);
            double err = Math.abs(d - targetDelta);
            if (err < bestErr) { bestErr = err; best = k; }
        }
        return best;
    }

    private static double strikeStep(double spot) {
        return spot < 25 ? 0.5 : spot < 100 ? 1.0 : spot < 300 ? 2.5 : 5.0;
    }

    // ---- valuation ----

    /**
     * Mark-to-market of a position. Each leg is priced from OBSERVED option_bar history when a Db is
     * configured and a matching bar exists for that date/expiration/strike; otherwise Black-Scholes
     * from realized vol. Setting {@link #usedObserved} lets the report label its pricing honestly.
     */
    private long valueCents(Position p, double spot, double iv, double tte, java.time.LocalDate date) {
        long v = 0;
        for (Leg l : p.legs) {
            Double observed = db == null ? null : observedMarkDollars(runSymbol, date, p.expiration, l.strike(), l.call());
            double price;
            if (observed != null) { price = observed; usedObserved = true; }
            else price = tte <= 0 ? intrinsic(l, spot) : BlackScholes.price(l.call(), spot, l.strike(), tte, R, 0, iv);
            v += (l.shortLeg() ? -1 : 1) * Math.round(price * 10_000) * p.qty; // price$/sh * 100sh * 100¢
        }
        return v;
    }

    /** The observed per-share mark (mark, else bid/ask midpoint) from option_bar, or null if none. */
    Double observedMarkDollars(String symbol, java.time.LocalDate date, java.time.LocalDate exp, double strike, boolean call) {
        var rows = db.query(
                "SELECT mark, bid, ask FROM option_bar WHERE symbol=? AND asof=? AND expiration=? AND strike=? "
              + "AND opt_type=? ORDER BY bid_ask_observed DESC LIMIT 1",
                r -> {
                    Double m = r.dblOrNull("mark");
                    if (m != null) return m;
                    Double b = r.dblOrNull("bid"), a = r.dblOrNull("ask");
                    return (b != null && a != null) ? (b + a) / 2.0 : null;
                },
                symbol, date, exp, java.math.BigDecimal.valueOf(strike), call ? "CALL" : "PUT");
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static long intrinsicValueCents(Position p, double spot) {
        long v = 0;
        for (Leg l : p.legs) v += (l.shortLeg() ? -1 : 1) * Math.round(intrinsic(l, spot) * 10_000) * p.qty;
        return v;
    }

    private static double intrinsic(Leg l, double spot) {
        return l.call() ? Math.max(0, spot - l.strike()) : Math.max(0, l.strike() - spot);
    }

    private static double hvOn(List<Candle> all, LocalDate date) {
        double hv = HistoricalVol.annualized(all.stream().filter(c -> !c.date().isAfter(date)).toList(), HV_WINDOW);
        return Double.isNaN(hv) || hv <= 0 ? DEFAULT_IV : hv;
    }

    private static PortfolioTrade close(Position p, LocalDate date, long pnl, String reason) {
        Double ror = p.maxLossCents > 0 ? round((double) pnl / p.maxLossCents) : null;
        return new PortfolioTrade(p.entryDate.toString(), date.toString(),
                p.legs.get(0).call() ? "DEBIT_CALL_SPREAD" : "CREDIT_PUT_SPREAD",
                p.creditCents, pnl, p.maxLossCents, ror, reason);
    }

    private enum Family {
        CREDIT_PUT_SPREAD, DEBIT_CALL_SPREAD;
        static Family parse(String s) {
            if (s == null) return CREDIT_PUT_SPREAD;
            String u = s.trim().toUpperCase(Locale.ROOT);
            if (u.contains("DEBIT") && u.contains("CALL")) return DEBIT_CALL_SPREAD;
            return CREDIT_PUT_SPREAD;
        }
    }

    private static int clamp(Integer v, int def, int lo, int hi) { return Math.clamp(v == null ? def : v, lo, hi); }
    private static double clampD(Double v, double def, double lo, double hi) { return Math.clamp(v == null ? def : v, lo, hi); }
    private static double round(double v) { return Math.round(v * 1000.0) / 1000.0; }
}
