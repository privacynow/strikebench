package io.liftandshift.backtest;

import io.liftandshift.config.AppConfig;
import io.liftandshift.db.Db;
import io.liftandshift.market.MarketDataService;
import io.liftandshift.market.ports.HistoricalOptionsProvider;
import io.liftandshift.model.Candle;
import io.liftandshift.model.Freshness;
import io.liftandshift.model.Leg;
import io.liftandshift.model.LegAction;
import io.liftandshift.model.OptionChain;
import io.liftandshift.model.OptionQuote;
import io.liftandshift.model.OptionType;
import io.liftandshift.pricing.BlackScholes;
import io.liftandshift.pricing.HistoricalVol;
import io.liftandshift.pricing.PayoffCurve;
import io.liftandshift.pricing.VolSurface;
import io.liftandshift.strategy.StrategyBuilder;
import io.liftandshift.strategy.StrategyFamily;
import io.liftandshift.util.Ids;
import io.liftandshift.util.Json;
import io.liftandshift.util.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Educational daily-loop backtester with STRICT no-look-ahead: every decision on day D uses
 * only data dated <= D. Pricing tiers, best available first:
 *   HISTORICAL_CHAIN        — real recorded chains (Polygon)
 *   MODELED_FROM_UNDERLYING — BSM prices from underlying closes with an HV-derived smile
 *   PAYOFF_ONLY             — not even HV available; flat-IV entry + intrinsic settlement only
 * Modeled results are approximations of strategy mechanics, never real option performance,
 * and every report says so.
 */
public final class Backtester {

    public static final String DISCLAIMER =
            "Backtests are educational simulations. Modeled option prices are estimated from underlying data "
            + "and a parametric volatility model — they are NOT real historical option prices unless the pricing "
            + "mode says HISTORICAL_CHAIN, and even then fills, spreads, and liquidity are idealized. "
            + "Past results, real or modeled, do not predict future returns. Not financial advice.";

    private static final double RISK_FREE = 0.04;
    private static final double FLAT_IV = 0.30;
    private static final int HV_WINDOW = 30;

    private final MarketDataService market;
    private final List<HistoricalOptionsProvider> historical;
    private final AppConfig cfg;
    private final Db db;
    private final Clock clock;

    public Backtester(MarketDataService market, List<HistoricalOptionsProvider> historical,
                      AppConfig cfg, Db db, Clock clock) {
        this.market = market;
        this.historical = List.copyOf(historical);
        this.cfg = cfg;
        this.db = db;
        this.clock = clock;
    }

    public record BacktestRequest(
            String symbol,
            String strategy,          // StrategyFamily name
            String from,              // ISO date
            String to,                // ISO date
            Integer targetDte,        // default 30
            Integer entryEveryDays,   // enter every N trading days when flat; default 5
            Integer qty,              // default 1
            Double slippagePct,       // per-leg premium slippage; default 0.005
            Long startingCashCents    // default $100k notional
    ) {}

    public record TradeResult(String entryDate, String exitDate, String label,
                              long entryNetPremiumCents, long exitValueCents, long feesCents,
                              long pnlCents, long maxLossCents, Double returnOnRisk, String exitReason,
                              Boolean assigned) {}

    public record BacktestReport(
            String id, String symbol, String strategy, String from, String to,
            String pricingMode, String confidence,
            int daysRequested, int daysCovered,
            int sampleSize, Double winRate, Double avgReturnOnRisk,
            long startingCents, long endingCents,
            double maxDrawdownPct,
            TradeResult worstTrade,
            List<TradeResult> trades,
            List<Map<String, String>> skipped,
            Map<String, Object> assumptions,
            List<Map<String, Object>> equityCurve,
            List<String> notes,
            Integer assignments,
            String disclaimer
    ) {}

    public BacktestReport run(BacktestRequest req) {
        String symbol = require(req.symbol(), "symbol").trim().toUpperCase(Locale.ROOT);
        StrategyFamily family = parseFamily(require(req.strategy(), "strategy"));
        LocalDate from = LocalDate.parse(require(req.from(), "from"));
        LocalDate to = LocalDate.parse(require(req.to(), "to"));
        if (!to.isAfter(from)) throw new IllegalArgumentException("'to' must be after 'from'");
        if (family.blockedByDefault()) {
            throw new IllegalArgumentException(family.display() + " has undefined risk and cannot be backtested here");
        }
        if (family.multiExpiration()) {
            throw new IllegalArgumentException(family.display() + " is not supported by the backtester yet "
                    + "(multi-expiration strategies need a richer model)");
        }
        int targetDte = req.targetDte() == null ? 30 : Math.clamp(req.targetDte(), 1, 365);
        int entryEvery = req.entryEveryDays() == null ? 5 : Math.clamp(req.entryEveryDays(), 1, 60);
        int qty = req.qty() == null ? 1 : Math.clamp(req.qty(), 1, 100);
        double slippage = req.slippagePct() == null ? 0.005 : Math.clamp(req.slippagePct(), 0, 0.1);
        long startingCash = req.startingCashCents() == null ? 10_000_000L : req.startingCashCents();
        if (startingCash <= 0) throw new IllegalArgumentException("startingCashCents must be positive");

        List<String> notes = new ArrayList<>();
        List<Map<String, String>> skipped = new ArrayList<>();
        List<TradeResult> trades = new ArrayList<>();
        List<Map<String, Object>> equityCurve = new ArrayList<>();

        // Lookback padding so HV is available on day one — filtered per-day to <= that day
        io.liftandshift.market.CandleSeries series = market.candleSeries(symbol, from.minusDays(200), to);
        List<Candle> allCandles = series.candles();
        boolean demoUnderlying = series.isFixture() && !cfg.fixturesOnly();
        if (demoUnderlying) {
            notes.add("Underlying price history is built-in DEMO DATA (no live candle source is configured — "
                    + "add a Polygon or Alpha Vantage key). These results do not reflect the real market.");
        }
        List<Candle> window = allCandles.stream()
                .filter(c -> !c.date().isBefore(from) && !c.date().isAfter(to)).toList();
        int daysRequested = countWeekdays(from, to);
        int daysCovered = window.size();
        if (window.isEmpty()) {
            notes.add("No underlying data available for " + symbol + " in the requested window");
            return persist(new BacktestReport(Ids.backtest(), symbol, family.name(), from.toString(), to.toString(),
                    "PAYOFF_ONLY", "none", daysRequested, 0, 0, null, null, startingCash, startingCash,
                    0, null, trades, skipped, assumptions(slippage, notes, family), equityCurve, notes, 0, DISCLAIMER), req);
        }
        // Weekday count includes market holidays (~10/yr); only flag a real shortfall.
        if (daysCovered < daysRequested * 0.9) {
            notes.add("Data covers only " + daysCovered + " of " + daysRequested + " requested weekdays");
        }

        long cash = startingCash;
        long reserved = 0;
        OpenPosition open = null;
        String pricingMode = null;
        int daysSinceFlat = 0;

        for (Candle day : window) {
            LocalDate date = day.date();
            double close = day.close().doubleValue();
            List<Candle> known = allCandles.stream().filter(c -> !c.date().isAfter(date)).toList();
            double hv = HistoricalVol.annualized(known, HV_WINDOW);
            boolean hvKnown = !Double.isNaN(hv);
            double ivProxy = hvKnown ? hv : FLAT_IV;

            // Exit first: settle at intrinsic once the expiration date is reached — valued at
            // the close ON (or last before) the expiration date, never a later bar's price.
            if (open != null && !date.isBefore(open.expiration)) {
                final LocalDate exp = open.expiration;
                BigDecimal expiryClose = known.stream()
                        .filter(c -> !c.date().isAfter(exp))
                        .reduce((a, b) -> b).map(Candle::close).orElse(day.close());
                long exitValue = intrinsicValueCents(open.legs, open.qty, expiryClose);
                boolean assigned = open.legs.stream().anyMatch(l -> !l.isStock()
                        && l.action() == io.liftandshift.model.LegAction.SELL
                        && l.intrinsicPerShare(expiryClose).signum() > 0);
                cash += exitValue;
                reserved -= Math.max(0, open.maxLossCents + open.entryNetCents);
                trades.add(closeOut(open, date, exitValue, "EXPIRED", assigned));
                open = null;
                daysSinceFlat = 0;
            }

            // Entry: every N trading days while flat
            if (open == null) {
                if (daysSinceFlat % entryEvery == 0) {
                    EntryAttempt attempt = tryEnter(symbol, family, date, targetDte, qty, slippage, known, ivProxy, hvKnown);
                    if (attempt.position != null
                            && cash + attempt.position.entryNetCents - attempt.position.feesCents
                               - (reserved + Math.max(0, attempt.position.maxLossCents + attempt.position.entryNetCents)) < 0) {
                        skipped.add(Map.of("date", date.toString(), "reason", "insufficient buying power for entry"));
                        attempt = new EntryAttempt(null, attempt.mode, "insufficient buying power");
                    }
                    if (attempt.position != null) {
                        open = attempt.position;
                        reserved += Math.max(0, open.maxLossCents + open.entryNetCents);
                        cash += open.entryNetCents - open.feesCents;
                        // Report the WORST tier used anywhere in the run — one modeled entry
                        // means the whole report is (at best) partially modeled.
                        if (pricingMode == null || tierRank(attempt.mode) > tierRank(pricingMode)) pricingMode = attempt.mode;
                    } else {
                        skipped.add(Map.of("date", date.toString(), "reason", attempt.reason));
                    }
                }
                daysSinceFlat++;
            }

            long equity = cash + (open == null ? 0
                    : modelValueCents(open.legs, open.qty, close, ivProxy, date, !hvKnown));
            equityCurve.add(Map.of("date", date.toString(), "equityCents", equity));
        }

        // Window ended with an open position: close at model value, flagged
        if (open != null) {
            Candle last = window.getLast();
            List<Candle> known = allCandles;
            double hv = HistoricalVol.annualized(known, HV_WINDOW);
            boolean hvKnown = !Double.isNaN(hv);
            long exitValue = modelValueCents(open.legs, open.qty, last.close().doubleValue(),
                    hvKnown ? hv : FLAT_IV, last.date(), !hvKnown);
            long feesClose = feesFor(open.legs, open.qty);
            cash += exitValue - feesClose;
            reserved -= Math.max(0, open.maxLossCents + open.entryNetCents);
            open.feesCents += feesClose;
            trades.add(closeOut(open, last.date(), exitValue, "WINDOW_END", null));
            notes.add("Final trade closed at model value (with close fees) because the window ended before expiration; "
                    + "it is excluded from win-rate and return statistics");
        }

        if (pricingMode == null) pricingMode = "MODELED_FROM_UNDERLYING";
        String confidence = trades.isEmpty() ? "none (no trades entered)"
                : demoUnderlying ? "none (demo data)" : switch (pricingMode) {
            case "HISTORICAL_CHAIN" -> "high";
            case "MODELED_FROM_UNDERLYING" -> "medium";
            default -> "low";
        };
        notes.add("Pricing mode " + pricingMode + ": "
                + (pricingMode.equals("HISTORICAL_CHAIN")
                    ? "entries used recorded option chains"
                    : "option prices are Black-Scholes estimates from the underlying — not observed option prices"));

        // Performance stats cover completed (expired) trades only — a window-end model exit
        // is an artifact of the report boundary, not a trade outcome.
        List<TradeResult> completed = trades.stream().filter(t -> "EXPIRED".equals(t.exitReason())).toList();
        int n = completed.size();
        Double winRate = n == 0 ? null : completed.stream().filter(t -> t.pnlCents() > 0).count() / (double) n;
        Double avgRoR = n == 0 ? null : completed.stream()
                .filter(t -> t.maxLossCents() > 0)
                .mapToDouble(t -> t.pnlCents() / (double) t.maxLossCents()).average().orElse(0);
        TradeResult worst = completed.stream().min(java.util.Comparator.comparingLong(TradeResult::pnlCents)).orElse(null);
        int assignments = (int) completed.stream().filter(t -> Boolean.TRUE.equals(t.assigned())).count();
        if (family.needsStock() || family == StrategyFamily.CASH_SECURED_PUT) {
            notes.add(assignments + " of " + n + " expirations finished with the short strike in the money "
                    + "(assignment in the real world; settled here as cash at intrinsic — same P/L, different form)");
        }

        return persist(new BacktestReport(Ids.backtest(), symbol, family.name(), from.toString(), to.toString(),
                pricingMode, confidence, daysRequested, daysCovered, n, winRate, avgRoR,
                startingCash, cash, maxDrawdownPct(equityCurve), worst, trades, skipped,
                assumptions(slippage, notes, family), equityCurve, notes, assignments, DISCLAIMER), req);
    }

    // ---- Entry ----

    private static final class OpenPosition {
        List<Leg> legs;
        int qty;
        LocalDate entryDate;
        LocalDate expiration;
        long entryNetCents;
        long feesCents;
        long maxLossCents;
        String label;
    }

    private record EntryAttempt(OpenPosition position, String mode, String reason) {}

    private EntryAttempt tryEnter(String symbol, StrategyFamily family, LocalDate date, int targetDte,
                                  int qty, double slippage, List<Candle> known, double ivProxy, boolean hvKnown) {
        OptionChain chain = null;
        String mode = null;
        for (HistoricalOptionsProvider p : historical) {
            try {
                List<LocalDate> exps = p.historicalExpirations(symbol, date);
                LocalDate exp = pickExpiration(exps, date, targetDte);
                if (exp == null) continue;
                chain = p.historicalChain(symbol, date, exp).orElse(null);
                if (chain != null && !chain.isEmpty()) {
                    mode = "polygon".equals(chain.source()) ? "HISTORICAL_CHAIN" : "MODELED_FROM_UNDERLYING";
                    break;
                }
                chain = null;
            } catch (RuntimeException ignored) {
                chain = null;
            }
        }
        if (chain != null) {
            EntryAttempt fromHistory = attemptFromChain(chain, mode, family, date, qty, slippage);
            if (fromHistory.position != null) return fromHistory;
            // Historical chains can lack greeks or two-sided books entirely (Polygon daily
            // aggregates carry no bid/ask/delta) — fall back to the modeled tier instead of
            // silently skipping every day of a "historical" backtest.
            BigDecimal closeFallback = known.getLast().close();
            LocalDate expFallback = nextFridayAtLeast(date, targetDte);
            EntryAttempt modeled = attemptFromChain(
                    modeledChain(symbol, date, expFallback, closeFallback, ivProxy),
                    hvKnown ? "MODELED_FROM_UNDERLYING" : "PAYOFF_ONLY", family, date, qty, slippage);
            return modeled.position != null ? modeled : fromHistory;
        }
        BigDecimal close = known.getLast().close();
        LocalDate exp = nextFridayAtLeast(date, targetDte);
        chain = modeledChain(symbol, date, exp, close, ivProxy);
        mode = hvKnown ? "MODELED_FROM_UNDERLYING" : "PAYOFF_ONLY";
        return attemptFromChain(chain, mode, family, date, qty, slippage);
    }

    private EntryAttempt attemptFromChain(OptionChain chain, String mode, StrategyFamily family,
                                          LocalDate date, int qty, double slippage) {
        StrategyBuilder.Built built = StrategyBuilder.build(family, chain, null, chain.underlyingPrice());
        if (built == null) return new EntryAttempt(null, mode, "strikes not buildable from available chain");

        // Same fill rule as the live paper engine: buys pay the ask, sells receive the bid
        // (slippage remains as an extra haircut on top).
        List<Leg> sided = new ArrayList<>(built.legs().size());
        for (int i = 0; i < built.legs().size(); i++) {
            Leg leg = built.legs().get(i);
            if (leg.isStock()) { sided.add(leg); continue; }
            OptionQuote q = built.quotes().get(i);
            BigDecimal side = leg.action() == LegAction.BUY ? q.ask() : q.bid();
            if (side == null || side.signum() <= 0) {
                return new EntryAttempt(null, mode, "no executable book side for a leg");
            }
            sided.add(new Leg(leg.action(), leg.type(), leg.strike(), leg.expiration(), leg.ratio(), side));
        }
        List<Leg> legs = applySlippage(sided, slippage);
        PayoffCurve curve = PayoffCurve.of(legs, qty);
        if (curve.maxLossUnbounded()) return new EntryAttempt(null, mode, "undefined risk after construction");
        if (curve.maxLossCents() <= 0) return new EntryAttempt(null, mode, "quote integrity: priced as risk-free — skipped");
        long entryNet = curve.entryNetPremiumCents();
        if (entryNet == 0) return new EntryAttempt(null, mode, "zero premium — chain too coarse");

        OpenPosition pos = new OpenPosition();
        pos.legs = legs;
        pos.qty = qty;
        pos.entryDate = date;
        pos.expiration = legs.stream().filter(l -> !l.isStock()).map(Leg::expiration).findFirst().orElse(date);
        pos.entryNetCents = entryNet;
        pos.feesCents = feesFor(legs, qty);
        pos.maxLossCents = curve.maxLossCents();
        pos.label = built.label();
        return new EntryAttempt(pos, mode, null);
    }

    private static List<Leg> applySlippage(List<Leg> legs, double slippage) {
        List<Leg> out = new ArrayList<>(legs.size());
        for (Leg leg : legs) {
            BigDecimal factor = BigDecimal.valueOf(leg.action() == LegAction.BUY ? 1 + slippage : 1 - slippage);
            BigDecimal price = leg.entryPrice().multiply(factor).setScale(Money.PRICE_SCALE, RoundingMode.HALF_UP);
            out.add(new Leg(leg.action(), leg.type(), leg.strike(), leg.expiration(), leg.ratio(), price.max(BigDecimal.ZERO)));
        }
        return out;
    }

    private TradeResult closeOut(OpenPosition pos, LocalDate exitDate, long exitValue, String reason, Boolean assigned) {
        long pnl = pos.entryNetCents - pos.feesCents + exitValue;
        Double ror = pos.maxLossCents > 0 ? pnl / (double) pos.maxLossCents : null;
        return new TradeResult(pos.entryDate.toString(), exitDate.toString(), pos.label,
                pos.entryNetCents, exitValue, pos.feesCents, pnl, pos.maxLossCents, ror, reason, assigned);
    }

    // ---- Valuation ----

    /** Cash to close: long legs sold, short legs bought back. */
    private static long intrinsicValueCents(List<Leg> legs, int qty, BigDecimal spot) {
        long total = 0;
        for (Leg leg : legs) {
            long sign = leg.action() == LegAction.BUY ? 1 : -1;
            total += sign * Money.centsFromPrice(leg.intrinsicPerShare(spot), (long) Leg.SHARES_PER_CONTRACT * leg.ratio() * qty);
        }
        return total;
    }

    private static long modelValueCents(List<Leg> legs, int qty, double spot, double ivProxy, LocalDate asOf, boolean payoffOnly) {
        double total = 0;
        for (Leg leg : legs) {
            double value;
            if (leg.isStock()) {
                value = spot;
            } else {
                double t = ChronoUnit.DAYS.between(asOf, leg.expiration()) / 365.0;
                double k = leg.strike().doubleValue();
                if (payoffOnly || t <= 0) {
                    value = leg.type() == OptionType.CALL ? Math.max(spot - k, 0) : Math.max(k - spot, 0);
                } else {
                    double iv = VolSurface.smile(ivProxy, spot, k, t);
                    value = BlackScholes.price(leg.type() == OptionType.CALL, spot, k, t, RISK_FREE, 0, iv);
                }
            }
            double sign = leg.action() == LegAction.BUY ? 1 : -1;
            total += sign * value * Leg.SHARES_PER_CONTRACT * leg.ratio() * qty;
        }
        return Money.toCents(BigDecimal.valueOf(total));
    }

    /** Synthetic chain from the underlying: BSM mids over an HV smile. MODELED, never real. */
    private OptionChain modeledChain(String symbol, LocalDate asOf, LocalDate exp, BigDecimal close, double ivProxy) {
        double s = close.doubleValue();
        double t = Math.max(ChronoUnit.DAYS.between(asOf, exp), 1) / 365.0;
        BigDecimal step = strikeStep(s);
        BigDecimal atm = close.divide(step, 0, RoundingMode.HALF_UP).multiply(step);
        List<OptionQuote> calls = new ArrayList<>();
        List<OptionQuote> puts = new ArrayList<>();
        for (int i = -10; i <= 10; i++) {
            BigDecimal strike = atm.add(step.multiply(BigDecimal.valueOf(i)));
            if (strike.signum() <= 0) continue;
            double k = strike.doubleValue();
            double iv = VolSurface.smile(ivProxy, s, k, t);
            for (OptionType type : OptionType.values()) {
                boolean call = type == OptionType.CALL;
                double mid = BlackScholes.price(call, s, k, t, RISK_FREE, 0, iv);
                double half = Math.max(0.01, mid * 0.015);
                OptionQuote q = new OptionQuote(symbol, "", type, strike, exp,
                        bd(Math.max(0, mid - half)), bd(mid + half), bd(mid), null, null, iv,
                        BlackScholes.delta(call, s, k, t, RISK_FREE, 0, iv),
                        null, null, null, 0L, "backtest-model", Freshness.MODELED);
                (call ? calls : puts).add(q);
            }
        }
        return new OptionChain(symbol, exp, close, calls, puts, 0L, "backtest-model", Freshness.MODELED);
    }

    private static BigDecimal strikeStep(double spot) {
        if (spot < 50) return BigDecimal.ONE;
        if (spot < 200) return new BigDecimal("2.5");
        if (spot < 500) return new BigDecimal("5");
        return new BigDecimal("10");
    }

    // ---- Reporting helpers ----

    private long feesFor(List<Leg> legs, int qty) {
        long contracts = legs.stream().filter(l -> !l.isStock()).mapToLong(l -> (long) l.ratio() * qty).sum();
        return contracts * cfg.feePerContractCents() + cfg.feePerOrderCents();
    }

    private Map<String, Object> assumptions(double slippage, List<String> notes, StrategyFamily family) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("slippagePctPerLeg", slippage);
        out.put("feePerContractCents", cfg.feePerContractCents());
        out.put("feePerOrderCents", cfg.feePerOrderCents());
        out.put("ivModel", "trailing " + HV_WINDOW + "-day historical vol + parametric smile (fallback flat " + FLAT_IV + ")");
        out.put("rate", RISK_FREE);
        out.put("settlement", "cash at intrinsic value on expiration, no settlement fees");
        out.put("fills", "executable bid/ask sides +/- slippage haircut; liquidity depth and early assignment ignored");
        if (family.needsStock()) {
            out.put("stockLegs", "bought at the daily close +/- slippage, valued at each day's close; "
                    + "no dividends, borrow costs, or commissions on shares");
        }
        return out;
    }

    private static double maxDrawdownPct(List<Map<String, Object>> equityCurve) {
        double peak = Double.NEGATIVE_INFINITY, maxDd = 0;
        for (Map<String, Object> point : equityCurve) {
            double eq = ((Number) point.get("equityCents")).doubleValue();
            peak = Math.max(peak, eq);
            if (peak > 0) maxDd = Math.max(maxDd, (peak - eq) / peak);
        }
        return Math.round(maxDd * 10000.0) / 10000.0;
    }

    private static LocalDate pickExpiration(List<LocalDate> exps, LocalDate date, int targetDte) {
        return exps.stream().filter(e -> e.isAfter(date))
                .min(java.util.Comparator.comparingLong(e -> Math.abs(ChronoUnit.DAYS.between(date, e) - targetDte)))
                .orElse(null);
    }

    private static LocalDate nextFridayAtLeast(LocalDate date, int minDays) {
        LocalDate d = date.plusDays(minDays);
        while (d.getDayOfWeek() != DayOfWeek.FRIDAY) d = d.plusDays(1);
        return d;
    }

    private static int countWeekdays(LocalDate from, LocalDate to) {
        int n = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) n++;
        }
        return n;
    }

    private static int tierRank(String mode) {
        return switch (mode) {
            case "HISTORICAL_CHAIN" -> 0;
            case "MODELED_FROM_UNDERLYING" -> 1;
            default -> 2;
        };
    }

    private static StrategyFamily parseFamily(String s) {
        try { return StrategyFamily.valueOf(s.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Unknown strategy: " + s); }
    }

    private static String require(String v, String name) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException(name + " is required");
        return v;
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    // ---- Persistence ----

    private BacktestReport persist(BacktestReport report, BacktestRequest req) {
        db.exec("INSERT INTO backtests(id, created_at, request_json, report_json) VALUES (?,?,?,?)",
                report.id(), Instant.now(clock).toString(), Json.write(req), Json.write(report));
        return report;
    }

    public List<Map<String, Object>> list() {
        return db.query("SELECT id, created_at, request_json FROM backtests ORDER BY created_at DESC, id DESC", r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.str("id"));
            m.put("createdAt", r.str("created_at"));
            m.put("request", Json.read(r.str("request_json"), Map.class));
            return m;
        });
    }

    public Map<String, Object> get(String id) {
        List<Map<String, Object>> rows = db.query("SELECT report_json FROM backtests WHERE id=?",
                r -> Json.read(r.str("report_json"), Map.class), id);
        if (rows.isEmpty()) throw new java.util.NoSuchElementException("no such backtest " + id);
        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) rows.getFirst();
        return report;
    }
}
