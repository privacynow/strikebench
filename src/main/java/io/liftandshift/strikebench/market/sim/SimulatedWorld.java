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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A LIVE SIMULATED MARKET: one deterministic world per session. It is a MODELED harness, not a real
 * exchange — every quote/chain it emits is labeled {@link Freshness#SIMULATED} and priced with a
 * European Black-Scholes kernel plus an intrinsic floor (no dividends, no early-exercise premium,
 * no real order book). Within those honest limits it is coherent and reproducible.
 *
 * <p>Determinism is STRUCTURAL, not incidental:
 * <ul>
 *   <li>The path lives on fixed <b>quanta</b> — each quantum is exactly {@code QUANTUM_SECONDS} of
 *       open-market time and applies exactly one diffusion step of that size. Playback {@code speed}
 *       only changes how many quanta advance per real tick, so the path at sim-time T is identical
 *       at 1× and 600× (junior: "speed must not change the path").</li>
 *   <li>Randomness comes from independent counter-based streams keyed by (seed, stream, symbol,
 *       quantum) — adding or removing a symbol never shifts another symbol's draws, and iteration
 *       order is irrelevant.</li>
 *   <li>Injected events and speed changes are recorded on an immutable log keyed by quantum, so
 *       {@code replayTo(q)} reconstructs the exact world after a restart.</li>
 * </ul>
 *
 * <p>The clock is the single source of time: option time-to-expiry is measured in <b>open-market
 * seconds to the expiration bell</b>, so theta/gamma converge continuously to intrinsic as the sim
 * clock runs through expiration day (junior: "0.5/365 floor is wrong at 3:59pm"). Beta is a real
 * factor loading (a β=1.5 name carries more market variance than a β=0.6 name), and implied vol is
 * a distinct process from realized diffusion vol.
 */
public final class SimulatedWorld {

    /** The model version stamped into reports; bump when dynamics change so old runs stay labeled. */
    public static final String MODEL_VERSION = "sim-2";

    /** One quantum of the path = 30 seconds of OPEN-MARKET time. Fixed forever (path identity). */
    private static final double QUANTUM_SECONDS = 30.0;
    private static final double SESSION_SECONDS = 6.5 * 3600.0;   // 09:30–16:00 ET
    private static final double YEAR_SECONDS = 252.0 * SESSION_SECONDS; // trading-time year
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final LocalTime OPEN = LocalTime.of(9, 30), CLOSE = LocalTime.of(16, 0);
    private static final int HISTORY_DAYS = 250;

    /** Reproducible session configuration. betas: symbol -> market beta (index proxy uses 1.0).
     *  symbolVols / symbolIvs: OPTIONAL per-symbol calibration (realized vol, base IV) — resolved
     *  from observed data at creation when available, else modeled defaults; the creator labels
     *  each basis. Appended fields keep old persisted configs deserializable (null = defaults). */
    public record Config(String worldId, String name, Map<String, Double> symbolBetas,
                         Map<String, Double> startSpots, String scenario, double volAnnual,
                         long seed, String startSimTime /* ISO LocalDateTime, ET */, double speed,
                         Map<String, Double> symbolVols, Map<String, Double> symbolIvs) {
        public Config(String worldId, String name, Map<String, Double> symbolBetas,
                      Map<String, Double> startSpots, String scenario, double volAnnual,
                      long seed, String startSimTime, double speed) {
            this(worldId, name, symbolBetas, startSpots, scenario, volAnnual, seed, startSimTime,
                    speed, null, null);
        }
        public Config {
            if (symbolBetas == null || symbolBetas.isEmpty())
                throw new IllegalArgumentException("a simulated world needs at least one symbol");
            symbolBetas.forEach((k, v) -> {
                if (v == null || !Double.isFinite(v) || Math.abs(v) > 3.0)
                    throw new IllegalArgumentException("beta for " + k + " must be finite and within ±3");
            });
            if (startSpots != null) startSpots.forEach((k, v) -> {
                if (v == null || !Double.isFinite(v) || v <= 0)
                    throw new IllegalArgumentException("start price for " + k + " must be positive");
            });
            if (!Double.isFinite(volAnnual) || volAnnual <= 0 || volAnnual > 5.0)
                throw new IllegalArgumentException("annual volatility must be in (0, 500%]");
            if (!Double.isFinite(speed) || speed <= 0 || speed > 2000)
                throw new IllegalArgumentException("speed (sim-time multiplier) must be in (0, 2000]");
            if (symbolVols != null) symbolVols.forEach((k, v) -> {
                if (v == null || !Double.isFinite(v) || v <= 0 || v > 5.0)
                    throw new IllegalArgumentException("calibrated vol for " + k + " must be in (0, 500%]");
            });
            if (symbolIvs != null) symbolIvs.forEach((k, v) -> {
                if (v == null || !Double.isFinite(v) || v <= 0 || v > 5.0)
                    throw new IllegalArgumentException("calibrated IV for " + k + " must be in (0, 500%]");
            });
            try { LocalDateTime.parse(startSimTime); }
            catch (RuntimeException e) { throw new IllegalArgumentException("startSimTime must be ISO LocalDateTime"); }
        }
    }

    /** An immutable path event — replayed at its quantum so a restored world is bit-identical. */
    public record WorldEvent(long quantum, String kind /* MOVE|VOL|SPEED */, String symbol, double value) {}

    // Counter-RNG stream ids (mixed into the seed so streams never collide).
    private static final long S_MARKET = 0x9E3779B97F4A7C15L;
    private static final long S_IDIO = 0xC2B2AE3D27D4EB4FL;

    private final Config cfg;
    private final double sigmaMarket;   // market-factor annual vol
    private final double sigmaIdio;     // idiosyncratic annual vol (modeled default)
    private final Map<String, Sym> syms = new ConcurrentHashMap<>();
    private final List<WorldEvent> events = new ArrayList<>(); // guarded by this

    private volatile LocalDateTime simTime;
    private volatile double speed;
    private volatile boolean running = false;
    private volatile long quantum = 0;   // total quanta elapsed — the path index
    private volatile double ivInjected = 0; // cumulative injected IV shifts (logged + replayed)

    private static final class Sym {
        final double beta;
        final double anchorSpot;              // fixes the strike grid so a big move can't delist a strike
        final double sigmaTotal;              // realized total vol (per-symbol calibrated when available)
        final double sigmaIdio;               // idiosyncratic component consistent with beta + total
        final double baseIv;                  // this symbol's OWN base implied vol (calibrated or modeled)
        final long key;                       // stable per-symbol RNG stream key (from the NAME, sorted-safe)
        volatile double spot;
        volatile double open, high, low;
        final List<Candle> daily = new ArrayList<>();
        Sym(String name, double beta, double spot, double sigmaTotal, double sigmaIdio, double baseIv) {
            this.beta = beta; this.anchorSpot = spot; this.sigmaTotal = sigmaTotal;
            this.sigmaIdio = sigmaIdio; this.baseIv = baseIv; this.spot = spot;
            this.key = mix(name.hashCode() * 0x9E3779B97F4A7C15L + name.length());
            resetDay();
        }
        void resetDay() { open = spot; high = spot; low = spot; }
    }

    public SimulatedWorld(Config cfg) {
        this.cfg = cfg;
        this.sigmaMarket = cfg.volAnnual();
        this.sigmaIdio = cfg.volAnnual() * 0.6; // modeled residual vol; labeled as such in the report
        this.speed = cfg.speed();
        this.simTime = snapToSession(LocalDateTime.parse(cfg.startSimTime()));

        for (var e : cfg.symbolBetas().entrySet()) {
            String sym = e.getKey().toUpperCase(Locale.ROOT);
            double s0 = cfg.startSpots() == null ? 100.0 : cfg.startSpots().getOrDefault(e.getKey(), 100.0);
            double beta = e.getValue();
            // PER-SYMBOL calibration (adversarial review M8): a calibrated total vol constrains the
            // idiosyncratic term (sigmaIdio^2 = sigmaTotal^2 - beta^2 sigmaM^2, floored so the factor
            // never exceeds the total); base IV is the symbol's own when calibrated.
            Double calVol = cfg.symbolVols() == null ? null : cfg.symbolVols().get(e.getKey());
            double sTot, sIdio;
            if (calVol != null) {
                sTot = calVol;
                sIdio = Math.sqrt(Math.max(0.0004, sTot * sTot - beta * beta * sigmaMarket * sigmaMarket));
            } else {
                sIdio = sigmaIdio;
                sTot = Math.sqrt(beta * beta * sigmaMarket * sigmaMarket + sIdio * sIdio);
            }
            Double calIv = cfg.symbolIvs() == null ? null : cfg.symbolIvs().get(e.getKey());
            double bIv = calIv != null ? calIv : cfg.volAnnual();
            Sym st = new Sym(sym, beta, s0, sTot, sIdio, bIv);
            seedHistory(st, sym);
            syms.put(sym, st);
        }
    }

    // ---- deterministic randomness: THE shared counter-based foundation (RandomStreams) ----
    private double gaussian(long stream, long key, long index) {
        return io.liftandshift.strikebench.sim.RandomStreams.gaussian(cfg.seed(), stream, key, index);
    }

    private static long mix(long z) {
        return io.liftandshift.strikebench.sim.RandomStreams.mix(z);
    }

    /** ~250 daily bars of coherent history ending at the sim start (vol-scaled ranges). */
    private void seedHistory(Sym st, String sym) {
        long symKey = mix(sym.hashCode());
        double[] closes = new double[HISTORY_DAYS];
        closes[HISTORY_DAYS - 1] = st.spot;
        double dt = 1.0 / 252;
        for (int i = HISTORY_DAYS - 2; i >= 0; i--) {
            double z = gaussian(0xA5A5A5A5L, symKey, i);
            closes[i] = closes[i + 1] / Math.exp((-st.sigmaTotal * st.sigmaTotal / 2) * dt
                    + st.sigmaTotal * Math.sqrt(dt) * z);
        }
        LocalDate d = simTime.toLocalDate();
        List<LocalDate> days = new ArrayList<>();
        while (days.size() < HISTORY_DAYS) {
            d = d.minusDays(1);
            if (MarketHours.isTradingDay(d)) days.add(d);
        }
        java.util.Collections.reverse(days);
        double dailySd = st.sigmaTotal * Math.sqrt(dt); // typical daily log-range scale
        for (int i = 0; i < HISTORY_DAYS; i++) {
            double c = closes[i];
            double o = i == 0 ? c : closes[i - 1];
            double rng = Math.abs(gaussian(0xB6B6B6B6L, symKey, i)) * dailySd; // vol-scaled range
            double hi = Math.max(o, c) * (1 + rng);
            double lo = Math.min(o, c) * (1 - rng);
            long vol = 800_000 + (long) (Math.abs(gaussian(0xC7C7C7C7L, symKey, i)) * 3_000_000);
            st.daily.add(new Candle(days.get(i), bd(o), bd(hi), bd(lo), bd(c), vol, false));
        }
    }

    // ---- lifecycle ----
    public Config config() { return cfg; }
    public String worldId() { return cfg.worldId(); }
    public boolean running() { return running; }
    public void start() { running = true; }
    public void pause() { running = false; }
    public double speed() { return speed; }
    public LocalDateTime simTime() { return simTime; }
    public long simMillis() { return simTime.atZone(ET).toInstant().toEpochMilli(); }
    public long ticks() { return quantum; }
    public String modelVersion() { return MODEL_VERSION; }
    public synchronized List<WorldEvent> eventLog() { return List.copyOf(events); }

    /** speed = the SIM-TIME MULTIPLIER: 1x means one simulated second per real second (a full
     *  6.5h session takes 6.5 real hours); 26x ~ a session in 15 minutes; 1560x ~ in 15 seconds.
     *  The adversarial review caught the old unit (quanta-per-tick) making every label false. */
    public static final double MAX_SPEED = 2000;

    public synchronized void setSpeed(double s) {
        this.speed = Math.clamp(s, 0.1, MAX_SPEED);
        events.add(new WorldEvent(quantum, "SPEED", null, this.speed));
    }

    /** Restore-only: adopt the checkpointed speed without logging a new event. */
    public synchronized void setSpeedSilently(double s) { this.speed = Math.clamp(s, 0.1, MAX_SPEED); }

    // Accumulate SIM-SECONDS, not quantum fractions: integral speeds sum exactly in doubles
    // (30 x 1.0 == 30.0), while 30 x (1/30) is 0.999… and real-time playback never stepped.
    private double pendingSimSeconds = 0; // pacing only, never the path

    /** One real second of playback: accumulate speed x 1s of sim time and run whole quanta.
     *  Pacing NEVER touches the path — the path is a pure function of the quantum index. */
    public synchronized void tick() {
        pendingSimSeconds += speed;
        while (pendingSimSeconds >= QUANTUM_SECONDS) { stepOneQuantum(); pendingSimSeconds -= QUANTUM_SECONDS; }
    }

    /** Exactly one quantum (30 sim-seconds), regardless of speed — the band's Step button
     *  must always move the world visibly (at 1x a bare tick() advances less than a quantum). */
    public synchronized void stepQuanta(int n) {
        for (int i = 0; i < Math.max(1, n); i++) stepOneQuantum();
    }

    /** The atomic path step: one fixed quantum of sim time and one diffusion step of that size. */
    private void stepOneQuantum() {
        long q = quantum; // draws for THIS quantum
        double dtYears = QUANTUM_SECONDS / YEAR_SECONDS;
        double drift = scenarioDrift();
        double zM = gaussian(S_MARKET, 0, q); // one market-factor draw per quantum
        for (String name : sortedSymbols()) {
            Sym st = syms.get(name);
            double zI = gaussian(S_IDIO, st.key, q);
            // r_i = (drift - 0.5 sigmaTotal^2) dt + beta*sigmaM*sqrt(dt)*zM + sigmaIdio_i*sqrt(dt)*zI
            double factor = st.beta * sigmaMarket * Math.sqrt(dtYears) * zM
                    + st.sigmaIdio * Math.sqrt(dtYears) * zI;
            st.spot = st.spot * Math.exp((drift - 0.5 * st.sigmaTotal * st.sigmaTotal) * dtYears + factor);
            st.high = Math.max(st.high, st.spot);
            st.low = Math.min(st.low, st.spot);
        }
        quantum = q + 1;
        // Advance the clock by one quantum of OPEN-MARKET time, rolling the daily bar at the bell.
        advanceClock();
    }

    private void advanceClock() {
        LocalDateTime next = simTime.plusSeconds((long) QUANTUM_SECONDS);
        if (!MarketHours.isTradingDay(next.toLocalDate())
                || next.toLocalTime().isAfter(CLOSE) || next.toLocalTime().equals(CLOSE)) {
            rollDay();
            LocalDate d = simTime.toLocalDate();
            do { d = d.plusDays(1); } while (!MarketHours.isTradingDay(d));
            simTime = LocalDateTime.of(d, OPEN);
        } else if (next.toLocalTime().isBefore(OPEN)) {
            simTime = LocalDateTime.of(next.toLocalDate(), OPEN);
        } else {
            simTime = next;
        }
    }

    private LocalDateTime snapToSession(LocalDateTime t) {
        LocalDate d = t.toLocalDate();
        while (!MarketHours.isTradingDay(d)) d = d.plusDays(1);
        LocalTime time = t.toLocalTime();
        if (d.equals(t.toLocalDate()) && time.isAfter(OPEN) && time.isBefore(CLOSE)) return t;
        return LocalDateTime.of(d, OPEN);
    }

    private void rollDay() {
        LocalDate d = simTime.toLocalDate();
        for (String name : sortedSymbols()) {
            Sym st = syms.get(name);
            long vol = 800_000 + Math.abs(mix(cfg.seed() ^ d.toEpochDay() ^ st.key)) % 3_000_000;
            st.daily.add(new Candle(d, bd(st.open), bd(st.high), bd(st.low), bd(st.spot), vol, false));
            st.resetDay();
        }
    }

    /** Realized diffusion drift for the active scenario, phased by SIM-TIME elapsed (speed-invariant). */
    private double scenarioDrift() {
        String sc = cfg.scenario() == null ? "CHOP" : cfg.scenario().toUpperCase(Locale.ROOT);
        // Elapsed sim TRADING days since start — a function of quanta, never of wall-clock ticks.
        double sessionsElapsed = quantum * QUANTUM_SECONDS / SESSION_SECONDS;
        long phase = (long) (sessionsElapsed / 3.0); // regime flips every ~3 sim sessions
        return switch (sc) {
            case "TREND_UP", "GRIND_UP" -> 0.35;
            case "TREND_DOWN", "SELLOFF" -> -0.5;
            case "SELLOFF_REBOUND" -> phase % 2 == 0 ? -0.8 : 0.8;
            case "RALLY_FADE" -> phase % 2 == 0 ? 0.8 : -0.8;
            case "VOL_EVENT", "NEWS_SHOCK" -> 0.0; // vol carries the story (injectVolShift)
            default -> 0.0; // CHOP / CALM
        };
    }

    // ---- event injection (recorded on the path log so restore is exact) ----
    public synchronized void injectMove(String symbol, double pct) {
        if (!Double.isFinite(pct) || pct <= -0.95 || pct > 5.0)
            throw new IllegalArgumentException("move must be a finite fraction in (-95%, +500%]");
        Sym st = syms.get(symbol.toUpperCase(Locale.ROOT));
        if (st == null) throw new IllegalArgumentException("no such symbol: " + symbol);
        st.spot = Math.max(0.01, st.spot * (1 + pct));
        st.high = Math.max(st.high, st.spot);
        st.low = Math.min(st.low, st.spot);
        events.add(new WorldEvent(quantum, "MOVE", symbol.toUpperCase(Locale.ROOT), pct));
    }

    public synchronized void injectVolShift(double points) {
        if (!Double.isFinite(points) || Math.abs(points) > 2.0)
            throw new IllegalArgumentException("vol shift must be finite and within \u00b1200 IV points");
        ivInjected = Math.clamp(ivInjected + points, -2.0, 2.0);
        events.add(new WorldEvent(quantum, "VOL", null, points));
    }

    /**
     * The IMPLIED-VOL level as a real, deterministic PROCESS (a pure function of the quantum, so
     * replay is exact): VOL_EVENT builds IV through the first session, breaks it (the crush) at
     * the second open, and mean-reverts over ~3 sessions — a volatility event that actually
     * happens, not a card that only zeroes drift (adversarial review). Injected shifts stack on top.
     */
    private double scenarioIvFactor() {
        String sc = cfg.scenario() == null ? "CHOP" : cfg.scenario().toUpperCase(Locale.ROOT);
        if (!"VOL_EVENT".equals(sc) && !"NEWS_SHOCK".equals(sc)) return 1.0;
        double sessions = quantum * QUANTUM_SECONDS / SESSION_SECONDS;
        if (sessions < 1.0) return 1.0 + 0.5 * sessions;          // anticipation build: 1.0 -> 1.5x
        double after = sessions - 1.0;                             // the event at the 2nd open
        if (after < 3.0) return 0.75 + (1.0 - 0.75) * (after / 3); // crush to 0.75x, revert over 3 sessions
        return 1.0;
    }

    /** Effective base IV for a symbol right now: its own calibrated level x the scenario arc + shifts. */
    private double effectiveIv(Sym st) {
        return Math.clamp(st.baseIv * scenarioIvFactor() + ivInjected, 0.05, 5.0);
    }

    /**
     * Deterministic restore: replay every quantum, applying each logged event BEFORE stepping its
     * quantum — a live injection at counter q lands before step q consumes index q, so replay must
     * do the same (the old step-then-apply ordering skewed OHLC and bell-crossing settlement for
     * injections near the close — adversarial-review release blocker #1). Events logged AT the
     * target quantum (after the final step) are applied at the end.
     */
    public synchronized void replayTo(long targetQuantum, List<WorldEvent> log) {
        java.util.Map<Long, List<WorldEvent>> byQuantum = new java.util.HashMap<>();
        for (WorldEvent e : log) byQuantum.computeIfAbsent(e.quantum(), k -> new ArrayList<>()).add(e);
        while (quantum < targetQuantum) {
            for (WorldEvent e : byQuantum.getOrDefault(quantum, List.of())) applyReplayEvent(e);
            stepOneQuantum();
        }
        for (WorldEvent e : byQuantum.getOrDefault(quantum, List.of())) applyReplayEvent(e);
        this.events.clear();
        this.events.addAll(log);
    }

    private void applyReplayEvent(WorldEvent e) {
        switch (e.kind()) {
            case "MOVE" -> { Sym st = syms.get(e.symbol()); if (st != null) {
                st.spot = Math.max(0.01, st.spot * (1 + e.value())); st.high = Math.max(st.high, st.spot); st.low = Math.min(st.low, st.spot); } }
            case "VOL" -> ivInjected = Math.clamp(ivInjected + e.value(), -2.0, 2.0);
            case "SPEED" -> speed = Math.clamp(e.value(), 0.1, MAX_SPEED);
            default -> { }
        }
    }

    private List<String> sortedSymbols() {
        List<String> names = new ArrayList<>(syms.keySet());
        java.util.Collections.sort(names);
        return names;
    }

    // ---- the market data surface (everything labeled SIMULATED) ----
    public java.util.Set<String> symbols() { return syms.keySet(); }

    public java.util.Optional<Quote> quote(String symbol) {
        Sym st = syms.get(symbol.toUpperCase(Locale.ROOT));
        if (st == null) return java.util.Optional.empty();
        double spr = Math.max(0.01, st.spot * 0.0004);
        double prev = st.daily.isEmpty() ? st.spot : st.daily.getLast().close().doubleValue();
        return java.util.Optional.of(new Quote(symbol.toUpperCase(Locale.ROOT),
                cfg.name() + " (simulated)", bd(st.spot), bd(st.spot - spr / 2), bd(st.spot + spr / 2),
                bd(prev), null, null, 1_000_000L, true, simMillis(), "simulated", Freshness.SIMULATED));
    }

    /** Listed expirations on the SIM calendar: the sim day's own expiry (if a Friday before the
     *  bell) plus the next Fridays and two monthlies — holiday Fridays roll to the prior session. */
    public List<LocalDate> expirations() {
        List<LocalDate> out = new ArrayList<>();
        LocalDate today = simTime.toLocalDate();
        boolean beforeBell = simTime.toLocalTime().isBefore(CLOSE);
        // 0DTE: the sim day itself if it's a listed expiration and the bell hasn't rung.
        if (beforeBell && today.getDayOfWeek() == DayOfWeek.FRIDAY && MarketHours.isTradingDay(today)) out.add(today);
        LocalDate f = today;
        while (out.size() < 7) {
            f = f.plusDays(1);
            if (f.getDayOfWeek() == DayOfWeek.FRIDAY) out.add(holidayAdjust(f));
        }
        LocalDate m = today.withDayOfMonth(1).plusMonths(2);
        for (int k = 0; k < 2; k++) {
            LocalDate third = holidayAdjust(m.with(java.time.temporal.TemporalAdjusters.dayOfWeekInMonth(3, DayOfWeek.FRIDAY)));
            if (!out.contains(third) && third.isAfter(today)) out.add(third);
            m = m.plusMonths(1);
        }
        out.sort(LocalDate::compareTo);
        return out.stream().distinct().toList();
    }

    /** A holiday expiration rolls to the prior trading session (real listed-option convention). */
    private static LocalDate holidayAdjust(LocalDate d) {
        while (!MarketHours.isTradingDay(d)) d = d.minusDays(1);
        return d;
    }

    public java.util.Optional<OptionChain> chain(String symbol, LocalDate exp) {
        Sym st = syms.get(symbol.toUpperCase(Locale.ROOT));
        if (st == null || exp == null || exp.isBefore(simTime.toLocalDate())) return java.util.Optional.empty();
        double spot = st.spot;
        double step = strikeStep(st.anchorSpot);
        double tte = timeToExpiryYears(exp);
        double baseIv = effectiveIv(st);
        List<OptionQuote> calls = new ArrayList<>(), puts = new ArrayList<>();
        // Strikes anchored to the START spot with a WIDE band that always covers the current spot,
        // so a large move (or an injected shock) can never delist an open position's strike.
        double center = st.anchorSpot;
        double lo = Math.max(step, Math.floor(Math.min(center, spot) * 0.5 / step) * step);
        double hi = Math.ceil(Math.max(center, spot) * 1.6 / step) * step;
        for (double k = lo; k <= hi + 1e-9; k += step) {
            double money = Math.log(k / spot);
            double iv = Math.max(0.05, baseIv * (1 + 0.35 * money * money * 8) - 0.06 * money); // smile + skew
            long expKey = mix(cfg.seed() ^ exp.toEpochDay() ^ mix((long) (k * 1000)) ^ st.key);
            for (OptionType type : OptionType.values()) {
                boolean call = type == OptionType.CALL;
                double px = BlackScholes.price(call, spot, k, tte, 0.03, 0, iv);
                double intrinsic = Math.max(0, call ? spot - k : k - spot);
                px = Math.max(px, intrinsic + 0.01);
                double half = Math.max(0.01, px * (0.01 + 0.03 * Math.abs(money) + (tte < 4.0 / 252 ? 0.01 : 0)));
                long oi = Math.max(5, (long) (3000 * Math.exp(-8 * money * money) * (0.5 + (Math.abs(expKey % 1000) / 1000.0))));
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

    /**
     * Time to expiry in YEARS measured in OPEN-MARKET seconds to the expiration bell — so option
     * time value converges continuously to intrinsic as the sim clock runs through expiration day.
     * At 3:59pm on expiry this is ~one minute of a year, not the old half-day floor.
     */
    public double timeToExpiryYears(LocalDate exp) {
        return openSecondsUntil(LocalDateTime.of(exp, CLOSE)) / YEAR_SECONDS;
    }

    /** Open-market seconds from now (sim) to a target datetime; 0 once at/after target. */
    private double openSecondsUntil(LocalDateTime target) {
        if (!target.isAfter(simTime)) return 0;
        LocalDate d = simTime.toLocalDate();
        double secs = 0;
        // remainder of today's session
        if (MarketHours.isTradingDay(d)) {
            LocalTime from = simTime.toLocalTime().isBefore(OPEN) ? OPEN : simTime.toLocalTime();
            LocalTime to = d.equals(target.toLocalDate())
                    ? (target.toLocalTime().isAfter(CLOSE) ? CLOSE : target.toLocalTime())
                    : CLOSE;
            if (to.isAfter(from)) secs += java.time.Duration.between(from, to).getSeconds();
        }
        if (d.equals(target.toLocalDate())) return secs;
        d = d.plusDays(1);
        while (d.isBefore(target.toLocalDate())) {
            if (MarketHours.isTradingDay(d)) secs += SESSION_SECONDS;
            d = d.plusDays(1);
        }
        // partial final day up to the target time
        if (d.equals(target.toLocalDate()) && MarketHours.isTradingDay(d)) {
            LocalTime to = target.toLocalTime().isAfter(CLOSE) ? CLOSE : target.toLocalTime();
            if (to.isAfter(OPEN)) secs += java.time.Duration.between(OPEN, to).getSeconds();
        }
        return secs;
    }

    private static double strikeStep(double ref) {
        return ref < 25 ? 0.5 : ref < 100 ? 1.0 : ref < 300 ? 2.5 : 5.0;
    }

    /** The sim close for a date: the rolled daily bar's close, else the current spot (today). */
    public java.util.Optional<BigDecimal> closeOn(String symbol, LocalDate date) {
        Sym st = syms.get(symbol.toUpperCase(Locale.ROOT));
        if (st == null) return java.util.Optional.empty();
        synchronized (this) {
            for (int i = st.daily.size() - 1; i >= 0; i--) {
                if (st.daily.get(i).date().equals(date)) return java.util.Optional.of(st.daily.get(i).close());
            }
            if (date.equals(simTime.toLocalDate())) return java.util.Optional.of(bd(st.spot));
        }
        return java.util.Optional.empty();
    }

    public List<Candle> candles(String symbol, LocalDate from, LocalDate to) {
        Sym st = syms.get(symbol.toUpperCase(Locale.ROOT));
        if (st == null) return List.of();
        List<Candle> out = new ArrayList<>();
        synchronized (this) {
            for (Candle c : st.daily) if (!c.date().isBefore(from) && !c.date().isAfter(to)) out.add(c);
        }
        return out;
    }

    /** Statistical self-check for validation reports: realized annualized vol of the history. */
    public double realizedVol(String symbol) {
        Sym st = syms.get(symbol.toUpperCase(Locale.ROOT));
        if (st == null || st.daily.size() < 3) return Double.NaN;
        List<Candle> c;
        synchronized (this) { c = new ArrayList<>(st.daily); }
        double mean = 0; double[] r = new double[c.size() - 1];
        for (int i = 1; i < c.size(); i++) { r[i - 1] = Math.log(c.get(i).close().doubleValue() / c.get(i - 1).close().doubleValue()); mean += r[i - 1]; }
        mean /= r.length;
        double var = 0; for (double x : r) var += (x - mean) * (x - mean);
        return Math.sqrt(var / (r.length - 1) * 252);
    }

    private static String occ(String sym, LocalDate exp, boolean call, double strike) {
        return sym.toUpperCase(Locale.ROOT) + exp.format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"))
                + (call ? "C" : "P") + String.format("%08d", Math.round(strike * 1000));
    }

    private static BigDecimal bd(double v) { return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP); }
}
