package io.liftandshift.strikebench.market.providers;

import io.liftandshift.strikebench.market.Domain;
import io.liftandshift.strikebench.market.ports.HistoricalOptionsProvider;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.market.ports.NewsFilingsProvider;
import io.liftandshift.strikebench.market.ports.RatesProvider;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.NewsItem;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.OptionQuote;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.model.SymbolMatch;
import io.liftandshift.strikebench.pricing.BlackScholes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Random;
import java.util.Set;

/**
 * Deterministic demo data so the whole app works with zero API keys and zero network.
 * All values are functions of (symbol, date, strike) with seeded RNG for volume noise —
 * two instances with the same clock produce identical data.
 */
public final class FixtureProvider implements MarketDataProvider, HistoricalOptionsProvider, NewsFilingsProvider, RatesProvider {

    public static final String NAME = "fixture";
    private static final double RISK_FREE = io.liftandshift.strikebench.market.RateQuote.DEFAULT_MODELED_RATE;
    private static final int STRIKES_EACH_SIDE = 10;
    private static final int EXPIRATION_COUNT = 8;

    private record Spec(String name, BigDecimal price, double baseIv, BigDecimal strikeStep,
                        boolean optionable, boolean zeroDte, double dailyVol, double optionSpreadPct) {}

    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();
    static {
        // Bid/ask width is part of the teaching world, not decorative noise. Highly liquid ETFs
        // use tight books; single names and volatile TSLA stay progressively wider.
        SPECS.put("AAPL", new Spec("Apple Inc.", new BigDecimal("255.30"), 0.28, new BigDecimal("2.5"), true, false, 0.016, 0.010));
        SPECS.put("SPY", new Spec("SPDR S&P 500 ETF Trust", new BigDecimal("562.10"), 0.15, new BigDecimal("5"), true, true, 0.009, 0.004));
        SPECS.put("QQQ", new Spec("Invesco QQQ Trust", new BigDecimal("484.75"), 0.20, new BigDecimal("5"), true, true, 0.012, 0.006));
        SPECS.put("TSLA", new Spec("Tesla Inc.", new BigDecimal("321.50"), 0.55, new BigDecimal("5"), true, false, 0.032, 0.020));
        SPECS.put("VTSAX", new Spec("Vanguard Total Stock Market Index Fund", new BigDecimal("131.22"), 0.0, null, false, false, 0.009, 0.0));
    }

    private final Clock clock;

    public FixtureProvider() { this(Clock.systemDefaultZone()); }

    public FixtureProvider(Clock clock) { this.clock = clock; }

    @Override public String name() { return NAME; }

    @Override public Set<Domain> domains() {
        return Set.of(Domain.QUOTES, Domain.OPTIONS, Domain.CANDLES, Domain.NEWS, Domain.RATES, Domain.HISTORICAL_OPTIONS);
    }

    public Set<String> symbols() { return java.util.Collections.unmodifiableSet(SPECS.keySet()); }

    private LocalDate today() { return LocalDate.now(clock); }

    private long nowMs() { return clock.millis(); }

    // ---- Lookup / quotes ----

    @Override
    public List<SymbolMatch> lookup(String query) {
        String q = query == null ? "" : query.trim().toUpperCase(Locale.ROOT);
        if (q.isEmpty()) return List.of();
        List<SymbolMatch> out = new ArrayList<>();
        SPECS.forEach((sym, spec) -> {
            if (sym.startsWith(q) || spec.name().toUpperCase(Locale.ROOT).contains(q)) {
                out.add(new SymbolMatch(sym, spec.name(), spec.optionable()));
            }
        });
        return out;
    }

    @Override
    public Optional<Quote> quote(String symbol) {
        Spec spec = SPECS.get(norm(symbol));
        if (spec == null) return Optional.empty();
        BigDecimal last = spec.price();
        BigDecimal half = new BigDecimal("0.02");
        return Optional.of(new Quote(
                norm(symbol), spec.name(),
                last, last.subtract(half), last.add(half),
                last.multiply(new BigDecimal("0.995")).setScale(2, RoundingMode.HALF_UP),
                last.multiply(new BigDecimal("1.008")).setScale(2, RoundingMode.HALF_UP),
                last.multiply(new BigDecimal("0.991")).setScale(2, RoundingMode.HALF_UP),
                seededRng(norm(symbol), today(), 0).nextLong(1_000_000, 60_000_000),
                spec.optionable(), nowMs(), NAME, Freshness.FIXTURE));
    }

    // ---- Options ----

    @Override
    public List<LocalDate> expirations(String symbol) {
        Spec spec = SPECS.get(norm(symbol));
        if (spec == null || !spec.optionable()) return List.of();
        return expirationsAsOf(spec, today());
    }

    private static List<LocalDate> expirationsAsOf(Spec spec, LocalDate asOf) {
        List<LocalDate> out = new ArrayList<>();
        if (spec.zeroDte() && asOf.getDayOfWeek() != DayOfWeek.SATURDAY && asOf.getDayOfWeek() != DayOfWeek.SUNDAY) {
            out.add(asOf); // 0DTE for the big index ETFs
        }
        LocalDate d = asOf.plusDays(1);
        while (out.size() < EXPIRATION_COUNT) {
            if (d.getDayOfWeek() == DayOfWeek.FRIDAY) out.add(d);
            d = d.plusDays(1);
        }
        return out;
    }

    @Override
    public Optional<OptionChain> chain(String symbol, LocalDate expiration) {
        Spec spec = SPECS.get(norm(symbol));
        if (spec == null || !spec.optionable()) return Optional.empty();
        // Only listed expirations exist — fabricating a chain for an arbitrary date would let
        // callers trade contracts that were never listed (or already expired).
        if (!expirationsAsOf(spec, today()).contains(expiration)) return Optional.empty();
        return Optional.of(buildChain(norm(symbol), spec, spec.price(), today(), expiration, Freshness.FIXTURE));
    }

    private OptionChain buildChain(String symbol, Spec spec, BigDecimal spot, LocalDate asOf, LocalDate expiration, Freshness freshness) {
        double s = spot.doubleValue();
        long days = java.time.temporal.ChronoUnit.DAYS.between(asOf, expiration);
        double t = Math.max(days, 0.3) / 365.0; // 0DTE keeps a sliver of time value

        BigDecimal step = spec.strikeStep();
        BigDecimal atm = spot.divide(step, 0, RoundingMode.HALF_UP).multiply(step);
        List<OptionQuote> calls = new ArrayList<>();
        List<OptionQuote> puts = new ArrayList<>();
        for (int i = -STRIKES_EACH_SIDE; i <= STRIKES_EACH_SIDE; i++) {
            BigDecimal strike = atm.add(step.multiply(BigDecimal.valueOf(i)));
            if (strike.signum() <= 0) continue;
            double k = strike.doubleValue();
            double iv = smileIv(spec.baseIv(), s, k, t);
            calls.add(buildQuote(symbol, OptionType.CALL, strike, expiration, s, k, t, iv,
                    spec.optionSpreadPct(), freshness));
            puts.add(buildQuote(symbol, OptionType.PUT, strike, expiration, s, k, t, iv,
                    spec.optionSpreadPct(), freshness));
        }
        return new OptionChain(symbol, expiration, spot, calls, puts, nowMs(), NAME, freshness);
    }

    /** Volatility smile: put skew (higher IV below spot) plus symmetric wing curvature. */
    static double smileIv(double baseIv, double s, double k, double t) {
        return io.liftandshift.strikebench.pricing.VolSurface.smile(baseIv, s, k, t);
    }

    private OptionQuote buildQuote(String symbol, OptionType type, BigDecimal strike, LocalDate expiration,
                                   double s, double k, double t, double iv, double spreadPct, Freshness freshness) {
        boolean call = type == OptionType.CALL;
        double mid = BlackScholes.price(call, s, k, t, RISK_FREE, 0, iv);
        double spread = Math.max(0.02, mid * spreadPct);
        BigDecimal bid = bd(Math.max(0, mid - spread / 2));
        BigDecimal ask = bd(mid + spread / 2);
        Random rng = seededRng(symbol + type + strike.toPlainString(), expiration, 1);
        double atmness = Math.exp(-8 * Math.pow(Math.log(k / s), 2)); // 1 at ATM, decays at wings
        long volume = (long) (atmness * (2000 + rng.nextInt(4000)));
        long oi = (long) (atmness * (8000 + rng.nextInt(20000))) + 50;
        return new OptionQuote(symbol, occSymbol(symbol, expiration, type, strike), type, strike, expiration,
                bid, ask, bd(mid), volume, oi, iv,
                BlackScholes.delta(call, s, k, t, RISK_FREE, 0, iv),
                BlackScholes.gamma(s, k, t, RISK_FREE, 0, iv),
                BlackScholes.theta(call, s, k, t, RISK_FREE, 0, iv) / 365.0,
                BlackScholes.vega(s, k, t, RISK_FREE, 0, iv) / 100.0,
                nowMs(), NAME, freshness);
    }

    static String occSymbol(String symbol, LocalDate expiration, OptionType type, BigDecimal strike) {
        String padded = (symbol + "      ").substring(0, 6);
        String date = expiration.format(DateTimeFormatter.ofPattern("yyMMdd"));
        long strikeMils = strike.movePointRight(3).setScale(0, RoundingMode.HALF_UP).longValueExact();
        return padded + date + (type == OptionType.CALL ? "C" : "P") + String.format("%08d", strikeMils);
    }

    // ---- Candles ----

    @Override
    public List<Candle> candles(String symbol, LocalDate from, LocalDate to) {
        Spec spec = SPECS.get(norm(symbol));
        if (spec == null) return List.of();
        List<Candle> all = walkTo(norm(symbol), spec, today());
        return all.stream().filter(c -> !c.date().isBefore(from) && !c.date().isAfter(to)).toList();
    }

    /**
     * Deterministic ~3y random walk that ends exactly at the spec price on endDate.
     * Generated backwards from the anchor so the terminal price is pinned.
     */
    private List<Candle> walkTo(String symbol, Spec spec, LocalDate endDate) {
        int tradingDays = 756;
        Random rng = new Random(symbol.hashCode() * 31L + 7);
        double[] rets = new double[tradingDays];
        for (int i = 0; i < tradingDays; i++) rets[i] = spec.dailyVol() * rng.nextGaussian() + 0.0003;

        List<LocalDate> dates = new ArrayList<>();
        LocalDate d = endDate;
        while (dates.size() < tradingDays) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) dates.add(d);
            d = d.minusDays(1);
        }
        java.util.Collections.reverse(dates);

        double px = spec.price().doubleValue();
        double[] closes = new double[tradingDays];
        closes[tradingDays - 1] = px;
        for (int i = tradingDays - 2; i >= 0; i--) closes[i] = closes[i + 1] / Math.exp(rets[i + 1]);

        List<Candle> out = new ArrayList<>(tradingDays);
        for (int i = 0; i < tradingDays; i++) {
            double c = closes[i];
            double o = i == 0 ? c : closes[i - 1];
            Random dayRng = seededRng(symbol, dates.get(i), 2);
            double hi = Math.max(o, c) * (1 + Math.abs(dayRng.nextGaussian()) * spec.dailyVol() * 0.5);
            double lo = Math.min(o, c) * (1 - Math.abs(dayRng.nextGaussian()) * spec.dailyVol() * 0.5);
            long vol = 1_000_000 + dayRng.nextInt(50_000_000);
            out.add(new Candle(dates.get(i), bd(o), bd(hi), bd(lo), bd(c), vol, true));
        }
        return out;
    }

    // ---- News ----

    @Override
    public List<NewsItem> news(String symbol) {
        String sym = norm(symbol);
        Spec spec = SPECS.get(sym);
        if (spec == null) return List.of();
        long dayMs = 86_400_000L;
        List<NewsItem> out = new ArrayList<>(List.of(
                new NewsItem(sym, spec.name() + " schedules quarterly earnings call", "Fixture Wire", "https://example.com/news/1", nowMs() - dayMs),
                new NewsItem(sym, "Analysts weigh in on " + sym + " ahead of product cycle", "Fixture Wire", "https://example.com/news/2", nowMs() - 3 * dayMs),
                new NewsItem(sym, "Options activity in " + sym + " picks up as volatility shifts", "Fixture Wire", "https://example.com/news/3", nowMs() - 7 * dayMs)));
        // Deterministic sentiment variety so the auto-scout is demonstrable offline
        switch (sym) {
            case "AAPL" -> {
                out.add(new NewsItem(sym, "Apple beats expectations as services growth accelerates", "Fixture Wire", "https://example.com/news/4", nowMs() - 2 * dayMs));
                out.add(new NewsItem(sym, "Regulators open probe into App Store practices", "Fixture Wire", "https://example.com/news/5", nowMs() - 4 * dayMs));
            }
            case "SPY" -> out.add(new NewsItem(sym, "Stocks rally to record close as inflation data cools", "Fixture Wire", "https://example.com/news/4", nowMs() - 2 * dayMs));
            case "QQQ" -> out.add(new NewsItem(sym, "Tech shares surge on strong earnings from chipmakers", "Fixture Wire", "https://example.com/news/4", nowMs() - 2 * dayMs));
            case "TSLA" -> {
                out.add(new NewsItem(sym, "Tesla issues recall amid regulator scrutiny", "Fixture Wire", "https://example.com/news/4", nowMs() - 2 * dayMs));
                out.add(new NewsItem(sym, "Deliveries surge past estimates in a record quarter", "Fixture Wire", "https://example.com/news/5", nowMs() - 5 * dayMs));
            }
            default -> { }
        }
        return List.copyOf(out);
    }

    // ---- Rates ----

    @Override
    public OptionalDouble riskFreeRate(int days) {
        return OptionalDouble.of(RISK_FREE);
    }

    // ---- Historical options (for backtests, MODELED) ----

    @Override
    public Optional<OptionChain> historicalChain(String symbol, LocalDate asOf, LocalDate expiration) {
        Spec spec = SPECS.get(norm(symbol));
        if (spec == null || !spec.optionable()) return Optional.empty();
        BigDecimal close = closeOn(norm(symbol), spec, asOf);
        if (close == null) return Optional.empty();
        return Optional.of(buildChain(norm(symbol), spec, close, asOf, expiration, Freshness.MODELED));
    }

    @Override
    public List<LocalDate> historicalExpirations(String symbol, LocalDate asOf) {
        Spec spec = SPECS.get(norm(symbol));
        if (spec == null || !spec.optionable()) return List.of();
        return expirationsAsOf(spec, asOf);
    }

    private BigDecimal closeOn(String symbol, Spec spec, LocalDate date) {
        return walkTo(symbol, spec, today()).stream()
                .filter(c -> !c.date().isAfter(date))
                .reduce((a, b) -> b)
                .map(Candle::close)
                .orElse(null);
    }

    // ---- helpers ----

    private static String norm(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private static Random seededRng(String key, LocalDate date, int salt) {
        return new Random(key.hashCode() * 1_000_003L + date.toEpochDay() * 31L + salt);
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }
}
