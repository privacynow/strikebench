package io.liftandshift.strikebench.market.sim;

import io.liftandshift.strikebench.market.MarketHours;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.OptionQuote;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.pricing.BlackScholes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A LIVE SIMULATED MARKET: one deterministic world per session — a virtual exchange clock (sim ET
 * date/time, real session hours + the NYSE holiday calendar), a market/idiosyncratic factor model
 * stepping every symbol's spot, an evolving base IV, and a full simulated option exchange (listed
 * expirations on the sim calendar, BSM+smile pricing with intrinsic floors, spreads widening by
 * moneyness/DTE, synthesized OI/volume, greeks). Everything it emits is labeled
 * {@link Freshness#SIMULATED} with source "simulated"; identical (seed, config) reproduces the
 * identical world tick-for-tick.
 *
 * A tick-speed multiplier here advances SIM TIME, not just prices — theta, DTE and expirations
 * move with the clock, so the world stays mathematically coherent (junior's correction).
 */
public final class SimulatedWorld {

    /** Reproducible session configuration. betas: symbol -> market beta (index proxy uses 1.0). */
    public record Config(String worldId, String name, Map<String, Double> symbolBetas,
                         Map<String, Double> startSpots, String scenario, double volAnnual,
                         long seed, String startSimTime /* ISO LocalDateTime, ET */, double speed) {}

    private final Config cfg;
    private final Random rng;
    private final Map<String, Sym> syms = new ConcurrentHashMap<>();
    private volatile LocalDateTime simTime;   // ET wall time inside the world
    private volatile double speed;
    private volatile boolean running = false;
    private volatile long tickCount = 0;
    private volatile double ivShift = 0;      // injected vol shift (points, e.g. 0.10)

    private static final double DT_PER_TICK_SEC = 30; // one tick advances 30 sim-seconds × speed

    private static final class Sym {
        final double beta;
        volatile double spot;
        volatile double open, high, low;      // intraday accumulation for the daily bar
        final List<Candle> daily = new ArrayList<>(); // history + rolled sim days
        Sym(double beta, double spot) { this.beta = beta; this.spot = spot; resetDay(); }
        void resetDay() { open = spot; high = spot; low = spot; }
    }

    public SimulatedWorld(Config cfg) {
        this.cfg = cfg;
        this.rng = new Random(cfg.seed());
        this.speed = cfg.speed() <= 0 ? 1 : cfg.speed();
        this.simTime = LocalDateTime.parse(cfg.startSimTime());
        for (var e : cfg.symbolBetas().entrySet()) {
            String sym = e.getKey().toUpperCase(Locale.ROOT);
            double s0 = cfg.startSpots().getOrDefault(e.getKey(), 100.0);
            Sym st = new Sym(e.getValue(), s0);
            seedHistory(st, sym);
            syms.put(sym, st);
        }
    }

    /** ~250 daily bars of coherent history ENDING at the sim start, so charts/HV work day one. */
    private void seedHistory(Sym st, String sym) {
        Random h = new Random(cfg.seed() ^ sym.hashCode());
        double vol = cfg.volAnnual();
        double s = st.spot * Math.exp(-0.0); // walk BACKWARD deterministically then reverse
        double[] closes = new double[250];
        closes[249] = st.spot;
        for (int i = 248; i >= 0; i--) {
            double z = h.nextGaussian();
            closes[i] = closes[i + 1] / Math.exp((-vol * vol / 2) * (1.0 / 252) + vol * Math.sqrt(1.0 / 252) * z);
        }
        LocalDate d = simTime.toLocalDate();
        List<LocalDate> days = new ArrayList<>();
        while (days.size() < 250) {
            d = d.minusDays(1);
            if (MarketHours.isTradingDay(d)) days.add(d);
        }
        java.util.Collections.reverse(days);
        for (int i = 0; i < 250; i++) {
            double c = closes[i];
            double o = i == 0 ? c : closes[i - 1];
            st.daily.add(new Candle(days.get(i), bd(o), bd(Math.max(o, c) * 1.004), bd(Math.min(o, c) * 0.996),
                    bd(c), 1_000_000 + (long) (h.nextDouble() * 4_000_000), false));
        }
    }

    // ---- lifecycle ----
    public Config config() { return cfg; }
    public String worldId() { return cfg.worldId(); }
    public boolean running() { return running; }
    public void start() { running = true; }
    public void pause() { running = false; }
    public void setSpeed(double s) { this.speed = Math.clamp(s, 0.1, 600); }
    public double speed() { return speed; }
    public LocalDateTime simTime() { return simTime; }
    public long simMillis() { return simTime.toInstant(ZoneOffset.of("-05:00")).toEpochMilli(); }
    public long ticks() { return tickCount; }

    /** Advance the world by one tick (called by the session loop, or step() manually). */
    public synchronized void tick() {
        double simSeconds = DT_PER_TICK_SEC * speed;
        LocalDateTime next = simTime.plusSeconds((long) simSeconds);
        // Skip closed hours: the exchange clock jumps from close to the NEXT session's open —
        // prices only move while the sim market is open, exactly like the real one.
        LocalDate day = next.toLocalDate();
        LocalTime t = next.toLocalTime();
        if (!MarketHours.isTradingDay(day) || t.isAfter(LocalTime.of(16, 0))) {
            rollDay();
            do { day = day.plusDays(1); } while (!MarketHours.isTradingDay(day));
            next = LocalDateTime.of(day, LocalTime.of(9, 30));
        } else if (t.isBefore(LocalTime.of(9, 30))) {
            next = LocalDateTime.of(day, LocalTime.of(9, 30));
        }
        double dtYears = simSeconds / (252.0 * 6.5 * 3600.0); // trading-time diffusion
        double vol = currentVol();
        double drift = scenarioDrift();
        double zM = rng.nextGaussian(); // ONE market factor draw per tick — correlation source
        for (Sym st : syms.values()) {
            double b = Math.clamp(st.beta, -1.5, 1.5);
            double rho = Math.min(0.99, Math.abs(b) / 1.5);
            double z = rho * Math.signum(b) * zM + Math.sqrt(1 - rho * rho) * rng.nextGaussian();
            st.spot = st.spot * Math.exp((drift - vol * vol / 2) * dtYears + vol * Math.sqrt(dtYears) * z);
            st.high = Math.max(st.high, st.spot);
            st.low = Math.min(st.low, st.spot);
        }
        simTime = next;
        tickCount++;
    }

    private void rollDay() {
        LocalDate d = simTime.toLocalDate();
        for (Sym st : syms.values()) {
            st.daily.add(new Candle(d, bd(st.open), bd(st.high), bd(st.low), bd(st.spot),
                    1_000_000 + (tickCount % 3_000_000), false));
            st.resetDay();
        }
    }

    private double currentVol() { return Math.max(0.05, cfg.volAnnual() + ivShift); }

    /** Scenario shapes as LIVE drift bias (annualized) — dynamics, not a fixed outcome. */
    private double scenarioDrift() {
        String sc = cfg.scenario() == null ? "CHOP" : cfg.scenario().toUpperCase(Locale.ROOT);
        long phase = tickCount / 200; // regime alternation for round-trip shapes
        return switch (sc) {
            case "TREND_UP", "GRIND_UP" -> 0.35;
            case "TREND_DOWN", "SELLOFF" -> -0.5;
            case "SELLOFF_REBOUND" -> phase % 2 == 0 ? -0.8 : 0.8;
            case "RALLY_FADE" -> phase % 2 == 0 ? 0.8 : -0.8;
            case "VOL_EVENT", "NEWS_SHOCK" -> 0.0; // vol carries the story (use injectVolShift)
            default -> 0.0; // CHOP / CALM
        };
    }

    // ---- event injection (the reviewer-demo lever) ----
    public synchronized void injectMove(String symbol, double pct) {
        Sym st = syms.get(symbol.toUpperCase(Locale.ROOT));
        if (st == null) return;
        st.spot = st.spot * (1 + pct);
        st.high = Math.max(st.high, st.spot);
        st.low = Math.min(st.low, st.spot);
    }

    public synchronized void injectVolShift(double points) { ivShift += points; }

    // ---- the market data surface (everything labeled SIMULATED) ----
    public java.util.Set<String> symbols() { return syms.keySet(); }

    public java.util.Optional<Quote> quote(String symbol) {
        Sym st = syms.get(symbol.toUpperCase(Locale.ROOT));
        if (st == null) return java.util.Optional.empty();
        double spr = Math.max(0.01, st.spot * 0.0004);
        BigDecimal last = bd(st.spot);
        double prev = st.daily.isEmpty() ? st.spot : st.daily.getLast().close().doubleValue();
        return java.util.Optional.of(new Quote(symbol.toUpperCase(Locale.ROOT),
                cfg.name() + " (simulated)", last, bd(st.spot - spr / 2), bd(st.spot + spr / 2),
                bd(prev), null, null, 1_000_000L, true, simMillis(), "simulated", Freshness.SIMULATED));
    }

    /** Listed expirations on the SIM calendar: next 6 Fridays + the next 2 third-Fridays. */
    public List<LocalDate> expirations() {
        List<LocalDate> out = new ArrayList<>();
        LocalDate d = simTime.toLocalDate();
        LocalDate f = d;
        while (out.size() < 6) {
            f = f.plusDays(1);
            if (f.getDayOfWeek() == DayOfWeek.FRIDAY && MarketHours.isTradingDay(f)) out.add(f);
        }
        LocalDate m = d.withDayOfMonth(1).plusMonths(2);
        for (int k = 0; k < 2; k++) {
            LocalDate third = m.with(java.time.temporal.TemporalAdjusters.dayOfWeekInMonth(3, DayOfWeek.FRIDAY));
            if (!out.contains(third) && third.isAfter(d)) out.add(third);
            m = m.plusMonths(1);
        }
        out.sort(LocalDate::compareTo);
        return out;
    }

    public java.util.Optional<OptionChain> chain(String symbol, LocalDate exp) {
        Sym st = syms.get(symbol.toUpperCase(Locale.ROOT));
        if (st == null || exp == null || !exp.isAfter(simTime.toLocalDate().minusDays(1))) return java.util.Optional.empty();
        double spot = st.spot;
        double step = spot < 25 ? 0.5 : spot < 100 ? 1.0 : spot < 300 ? 2.5 : 5.0;
        int sessions = MarketHours.tradingDaysBetween(simTime.toLocalDate(), exp);
        long calDays = java.time.temporal.ChronoUnit.DAYS.between(simTime.toLocalDate(), exp);
        double tte = Math.max(calDays, 0.5) / 365.0;
        double baseIv = currentVol();
        List<OptionQuote> calls = new ArrayList<>(), puts = new ArrayList<>();
        double lo = Math.max(step, Math.floor(spot * 0.75 / step) * step);
        double hi = Math.ceil(spot * 1.25 / step) * step;
        Random liq = new Random(cfg.seed() ^ exp.hashCode() ^ symbol.hashCode());
        for (double k = lo; k <= hi + 1e-9; k += step) {
            double money = Math.log(k / spot);
            double iv = Math.max(0.05, baseIv * (1 + 0.35 * money * money * 8) - 0.06 * money); // smile + skew
            for (OptionType type : OptionType.values()) {
                boolean call = type == OptionType.CALL;
                double px = BlackScholes.price(call, spot, k, tte, 0.03, 0, iv);
                double intrinsic = Math.max(0, call ? spot - k : k - spot);
                px = Math.max(px, intrinsic + 0.01); // American-style floor: never below parity
                // spread widens with moneyness distance, shrinking DTE and injected stress
                double half = Math.max(0.01, px * (0.01 + 0.03 * Math.abs(money) + (sessions <= 3 ? 0.01 : 0)
                        + Math.abs(ivShift) * 0.05));
                long oi = Math.max(5, (long) (3000 * Math.exp(-8 * money * money) * (0.5 + liq.nextDouble())));
                var q = new OptionQuote(symbol.toUpperCase(Locale.ROOT),
                        occ(symbol, exp, call, k), type, bd(k), exp,
                        bd(Math.max(0.0, px - half)), bd(px + half), bd(px),
                        oi / 10, oi, iv,
                        BlackScholes.delta(call, spot, k, tte, 0.03, 0, iv),
                        BlackScholes.gamma(spot, k, tte, 0.03, 0, iv),
                        BlackScholes.theta(call, spot, k, tte, 0.03, 0, iv),
                        BlackScholes.vega(spot, k, tte, 0.03, 0, iv),
                        simMillis(), "simulated", Freshness.SIMULATED);
                (call ? calls : puts).add(q);
            }
        }
        return java.util.Optional.of(new OptionChain(symbol.toUpperCase(Locale.ROOT), exp, bd(spot),
                calls, puts, simMillis(), "simulated", Freshness.SIMULATED));
    }

    /** Daily bars (seeded history + rolled sim days), inclusive range. */
    public List<Candle> candles(String symbol, LocalDate from, LocalDate to) {
        Sym st = syms.get(symbol.toUpperCase(Locale.ROOT));
        if (st == null) return List.of();
        List<Candle> out = new ArrayList<>();
        synchronized (this) {
            for (Candle c : st.daily) {
                if (!c.date().isBefore(from) && !c.date().isAfter(to)) out.add(c);
            }
        }
        return out;
    }

    private static String occ(String sym, LocalDate exp, boolean call, double strike) {
        return sym.toUpperCase(Locale.ROOT) + exp.format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"))
                + (call ? "C" : "P") + String.format("%08d", Math.round(strike * 1000));
    }

    private static BigDecimal bd(double v) { return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP); }
}
