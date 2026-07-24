package io.liftandshift.strikebench.sim;

import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.market.EventService;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.MarketHours;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.DataProvenance;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Seeds the canvas from lane-owned inputs without creating a second scenario endpoint or engine. */
public final class ScenarioCanvasTemplateService {
    public record Request(ScenarioCanvasSpec.TemplateKind kind, Long targetPriceCents,
                          String sectorSymbol, String historicalFrom, String historicalTo) {}
    public record Seed(ScenarioSpec spec, ScenarioCanvasSpec canvas) {}

    private final MarketDataService market;
    private final EventService events;
    private final Clock clock;

    public ScenarioCanvasTemplateService(MarketDataService market, EventService events, Clock clock) {
        this.market = market;
        this.events = events;
        this.clock = clock;
    }

    public Seed apply(String symbol, String worldId, AnalysisContext analysis, double spot,
                      double atmIv, ScenarioSpec rawSpec, ScenarioCanvasSpec rawCanvas, Request request) {
        if (request == null || request.kind() == null) throw new IllegalArgumentException("canvas template kind is required");
        ScenarioSpec spec = rawSpec.sane();
        ScenarioCanvasSpec canvas = (rawCanvas == null ? ScenarioCanvasSpec.defaults() : rawCanvas)
                .sane(spec.horizonDays());
        String world = worldId == null || worldId.isBlank() ? "observed" : worldId;
        LocalDate anchor = market.laneToday(world, clock);
        return switch (request.kind()) {
            case EARNINGS_GAP_UP -> earnings(symbol, world, analysis, spot, atmIv, spec, canvas, anchor, true);
            case EARNINGS_GAP_DOWN -> earnings(symbol, world, analysis, spot, atmIv, spec, canvas, anchor, false);
            case SECTOR_DRAWDOWN -> sector(symbol, request.sectorSymbol(), world, analysis, spec, canvas, anchor);
            case DRIFT_TO_TARGET -> target(symbol, request.targetPriceCents(), spot, spec, canvas, anchor);
            case HISTORICAL_REPLAY -> replay(symbol, world, analysis, request.historicalFrom(),
                    request.historicalTo(), spec, canvas, anchor);
        };
    }

    private Seed earnings(String symbol, String world, AnalysisContext analysis, double spot, double atmIv,
                          ScenarioSpec spec, ScenarioCanvasSpec canvas, LocalDate anchor, boolean up) {
        if (!"observed".equalsIgnoreCase(world)) {
            throw new IllegalArgumentException("The SEC filing-cadence earnings template is available only in the Observed market; observed issuer events are never borrowed into Demo or simulated worlds.");
        }
        EventService.EventEvidence event = events.earnings(symbol);
        if (!event.available()) {
            throw new IllegalArgumentException("No canonical earnings evidence is available for "
                    + symbol + "; this template will not invent an event date.");
        }
        int eventDay = MarketHours.tradingDaysBetween(anchor, event.date());
        if (eventDay < 1 || eventDay > spec.horizonDays()) {
            throw new IllegalArgumentException("The " + event.status().name().toLowerCase(java.util.Locale.ROOT)
                    + " earnings date near " + event.date()
                    + " is outside this " + spec.horizonDays()
                    + "-session canvas; extend the Plan horizon instead of moving the event into view.");
        }
        var series = market.candleSeries(symbol, anchor.minusYears(2), anchor, world, analysis);
        if (!observed(series.evidence().provenance())) {
            throw new IllegalArgumentException("Observed daily history is unavailable for the earnings template; Demo or modeled gaps are never substituted for real event analogs.");
        }
        List<Double> gaps = new ArrayList<>();
        List<Candle> candles = series.candles();
        List<LocalDate> reportDates = events.quarterlyReportDates(symbol);
        for (LocalDate filing : reportDates) {
            // Earnings releases typically precede the quarterly filing. With no confirmed
            // release calendar, use the largest close→open move in the ten calendar days before
            // each issuer filing and say that this is a proxy—not an observed release timestamp.
            double analog = -1;
            for (int i = 1; i < candles.size(); i++) {
                LocalDate date = candles.get(i).date();
                if (date.isBefore(filing.minusDays(10)) || date.isAfter(filing)) continue;
                double prior = candles.get(i - 1).close().doubleValue();
                double open = candles.get(i).open().doubleValue();
                if (prior > 0 && open > 0) analog = Math.max(analog, Math.abs(open / prior - 1.0));
            }
            if (analog >= 0) gaps.add(analog);
        }
        if (gaps.size() < 2) {
            throw new IllegalArgumentException("At least two SEC-filing-window price analogs are required; this template will not replace them with ordinary trading-day gaps.");
        }
        gaps.sort(Double::compareTo);
        double gap = Math.clamp(gaps.get((int) Math.floor(.75 * (gaps.size() - 1))), .02, .20);
        double ratio = 1 + (up ? gap : -gap);
        List<ScenarioSpec.Waypoint> pins = new ArrayList<>(spec.waypoints());
        putPin(pins, new ScenarioSpec.Waypoint(eventDay, ratio));
        ScenarioSpec seeded = new ScenarioSpec(ScenarioSpec.PathModel.JUMP_DIFFUSION,
                ScenarioSpec.Shape.EVENT_JUMP, spec.horizonDays(), spec.stepsPerDay(),
                spec.driftAnnual(), spec.volAnnual(), Math.max(2, spec.jumpsPerYear()),
                up ? gap : -gap, Math.max(.01, gap * .35), spec.tailNu(), spec.heston(),
                spec.seed(), spec.paths(), pins).sane();
        double iv = Math.clamp(atmIv > 0 ? atmIv : spec.volAnnual(), .03, 4);
        List<ScenarioCanvasSpec.IvNode> nodes = new ArrayList<>(canvas.ivNodes());
        putIv(nodes, new ScenarioCanvasSpec.IvNode(0, Math.min(4, iv * 1.25)));
        putIv(nodes, new ScenarioCanvasSpec.IvNode(eventDay, Math.min(4, iv * 1.25)));
        if (eventDay < spec.horizonDays()) putIv(nodes,
                new ScenarioCanvasSpec.IvNode(eventDay + 1, Math.max(.03, iv * .75)));
        var receipt = receipt(up ? ScenarioCanvasSpec.TemplateKind.EARNINGS_GAP_UP
                        : ScenarioCanvasSpec.TemplateKind.EARNINGS_GAP_DOWN,
                "SEC EDGAR filing dates + " + (series.source() == null ? "daily history unavailable" : series.source()),
                series.evidence().provenance().name(), anchor, candles.isEmpty() ? null : candles.getFirst().date(),
                candles.isEmpty() ? null : candles.getLast().date(), gaps.size(), observed(series.evidence().provenance()),
                "Underlying gap magnitude is the 75th percentile of " + gaps.size()
                        + " filing-window analogs (largest close-to-open move in the 10 calendar days before each 10-Q/10-K); these are proxies because the filing is not a confirmed release timestamp. Event date is "
                        + event.status().name() + " near "
                        + event.date() + (event.confirmed() ? " " + event.session().name().toLowerCase(java.util.Locale.ROOT)
                            : " (±" + event.windowDays() + " days)") + " from " + event.basis()
                        + ". IV crush is an authored assumption anchored to current ATM IV, not an observed forecast.");
        return new Seed(seeded, with(canvas, nodes, receipt));
    }

    private Seed sector(String symbol, String rawSector, String world, AnalysisContext analysis,
                        ScenarioSpec spec, ScenarioCanvasSpec canvas, LocalDate anchor) {
        String sector = rawSector == null ? "" : rawSector.trim().toUpperCase();
        if (sector.isBlank()) throw new IllegalArgumentException("Choose the sector ETF whose observed drawdown should seed the path");
        var series = market.candleSeries(sector, anchor.minusYears(2), anchor, world, analysis);
        if (!observed(series.evidence().provenance())) {
            throw new IllegalArgumentException("Observed sector ETF history is unavailable in this lane; Demo or simulated drawdowns are never presented as real-input templates.");
        }
        List<Candle> candles = series.candles();
        if (candles.size() < 30) throw new IllegalArgumentException("At least 30 sector ETF bars are required for a drawdown template");
        double worst = 0; int worstAt = -1; int window = Math.min(10, candles.size() - 1);
        for (int i = window; i < candles.size(); i++) {
            double prior = candles.get(i - window).close().doubleValue();
            double now = candles.get(i).close().doubleValue();
            double ret = prior > 0 ? now / prior - 1 : 0;
            if (ret < worst) { worst = ret; worstAt = i; }
        }
        if (worstAt < 0) throw new IllegalArgumentException("No drawdown was found in the selected sector window");
        int shockDay = Math.max(1, Math.min(spec.horizonDays(), Math.max(2, spec.horizonDays() / 2)));
        List<ScenarioSpec.Waypoint> pins = new ArrayList<>(spec.waypoints());
        putPin(pins, new ScenarioSpec.Waypoint(shockDay, Math.max(.25, 1 + worst)));
        ScenarioSpec seeded = new ScenarioSpec(spec.model(), ScenarioSpec.Shape.SELLOFF_REBOUND,
                spec.horizonDays(), spec.stepsPerDay(), spec.driftAnnual(), spec.volAnnual(),
                spec.jumpsPerYear(), spec.jumpMean(), spec.jumpVol(), spec.tailNu(), spec.heston(),
                spec.seed(), spec.paths(), pins).sane();
        var receipt = receipt(ScenarioCanvasSpec.TemplateKind.SECTOR_DRAWDOWN,
                series.source() == null ? sector + " history unavailable" : series.source() + " / " + sector,
                series.evidence().provenance().name(), anchor, candles.getFirst().date(), candles.getLast().date(),
                candles.size() - window, observed(series.evidence().provenance()),
                "Applies the worst observed " + window + "-session " + sector + " return ("
                        + Math.round(worst * 1000) / 10.0 + "%) in the two-year window to " + symbol
                        + ". This is a stress transfer, not a correlation claim or forecast.");
        return new Seed(seeded, with(canvas, canvas.ivNodes(), receipt));
    }

    private Seed target(String symbol, Long targetCents, double spot, ScenarioSpec spec,
                        ScenarioCanvasSpec canvas, LocalDate anchor) {
        if (targetCents == null || targetCents <= 0) throw new IllegalArgumentException("A positive target price is required");
        double target = targetCents / 100.0;
        List<ScenarioSpec.Waypoint> pins = new ArrayList<>(spec.waypoints());
        putPin(pins, new ScenarioSpec.Waypoint(spec.horizonDays(), target / spot));
        ScenarioSpec seeded = new ScenarioSpec(ScenarioSpec.PathModel.BROWNIAN_BRIDGE,
                target >= spot ? ScenarioSpec.Shape.GRIND_UP : ScenarioSpec.Shape.GRIND_DOWN,
                spec.horizonDays(), spec.stepsPerDay(), spec.driftAnnual(), spec.volAnnual(),
                spec.jumpsPerYear(), spec.jumpMean(), spec.jumpVol(), spec.tailNu(), spec.heston(),
                spec.seed(), spec.paths(), pins).sane();
        var receipt = receipt(ScenarioCanvasSpec.TemplateKind.DRIFT_TO_TARGET, "Plan/user target",
                "SCENARIO", anchor, anchor, anchor, 1, false,
                "Pins the final NYSE session to the declared $" + String.format(java.util.Locale.ROOT, "%.2f", target)
                        + " target. It is the user's hypothesis, not market evidence or a forecast.");
        return new Seed(seeded, with(canvas, canvas.ivNodes(), receipt));
    }

    private Seed replay(String symbol, String world, AnalysisContext analysis, String rawFrom, String rawTo,
                        ScenarioSpec spec, ScenarioCanvasSpec canvas, LocalDate anchor) {
        if (rawFrom == null || rawTo == null) throw new IllegalArgumentException("Historical replay needs from and to dates");
        LocalDate from = LocalDate.parse(rawFrom), to = LocalDate.parse(rawTo);
        if (!from.isBefore(to)) throw new IllegalArgumentException("Historical replay from date must precede to date");
        if (!to.isBefore(anchor)) throw new IllegalArgumentException("Historical replay must end before the canvas anchor; future or same-day data would introduce hindsight");
        var series = market.candleSeries(symbol, from, to, world, analysis);
        if (!observed(series.evidence().provenance())) {
            throw new IllegalArgumentException("The selected window is not Observed or broker-owned history; an actual historical replay never borrows Demo, modeled, or simulated closes.");
        }
        List<Candle> candles = series.candles().stream().filter(c -> !c.date().isBefore(from) && !c.date().isAfter(to))
                .sorted(Comparator.comparing(Candle::date)).toList();
        if (candles.size() < 2) throw new IllegalArgumentException("The selected historical window has fewer than two eligible closes");
        if (candles.size() - 1 > spec.horizonDays()) {
            throw new IllegalArgumentException("This replay has " + (candles.size() - 1)
                    + " sessions but the Plan canvas has " + spec.horizonDays() + "; extend the Plan horizon or shorten the window");
        }
        double base = candles.getFirst().close().doubleValue();
        List<ScenarioSpec.Waypoint> pins = new ArrayList<>();
        for (int i = 1; i < candles.size(); i++) pins.add(new ScenarioSpec.Waypoint(i,
                candles.get(i).close().doubleValue() / base));
        ScenarioSpec seeded = new ScenarioSpec(ScenarioSpec.PathModel.BROWNIAN_BRIDGE,
                ScenarioSpec.Shape.CHOP, spec.horizonDays(), spec.stepsPerDay(), spec.driftAnnual(),
                spec.volAnnual(), spec.jumpsPerYear(), spec.jumpMean(), spec.jumpVol(), spec.tailNu(),
                spec.heston(), spec.seed(), spec.paths(), pins).sane();
        var receipt = receipt(ScenarioCanvasSpec.TemplateKind.HISTORICAL_REPLAY,
                series.source() == null ? "history unavailable" : series.source(),
                series.evidence().provenance().name(), anchor, candles.getFirst().date(), candles.getLast().date(),
                candles.size(), observed(series.evidence().provenance()),
                "Replays the exact close path available in " + from + ".." + to
                        + ". Underlying closes retain their lane provenance; option values are MODELED day by day from the declared surface because no contemporaneous option series is claimed."
                        + (candles.size() - 1 < spec.horizonDays()
                            ? " The exact replay fills sessions 1–" + (candles.size() - 1)
                                + "; later canvas sessions resume the stored conditional path model from the final observed pin and are not historical observations."
                            : " The selected window fills the complete canvas horizon."));
        return new Seed(seeded, with(canvas, canvas.ivNodes(), receipt));
    }

    private static ScenarioCanvasSpec.TemplateReceipt receipt(ScenarioCanvasSpec.TemplateKind kind,
            String source, String provenance, LocalDate inputAsOf, LocalDate from, LocalDate to,
            int observations, boolean observed, String note) {
        return new ScenarioCanvasSpec.TemplateReceipt(kind, source, provenance, inputAsOf, from, to,
                observations, observed, true,
                "Underlying path uses " + provenance + " " + source
                        + "; option prices and Greeks are MODELED from only the declared canvas assumptions.",
                note, "").signed();
    }

    private static ScenarioCanvasSpec with(ScenarioCanvasSpec base, List<ScenarioCanvasSpec.IvNode> nodes,
                                           ScenarioCanvasSpec.TemplateReceipt receipt) {
        return new ScenarioCanvasSpec(base.calendar(), base.dividendYieldAnnual(), base.dividendBasis(),
                base.skewVolPerLogMoneyness(), base.termVolPerSqrtYear(), base.surfaceDynamics(),
                base.settlementPolicy(), base.exercisePolicy(), nodes, receipt).sane(756);
    }

    private static void putPin(List<ScenarioSpec.Waypoint> pins, ScenarioSpec.Waypoint pin) {
        pins.removeIf(p -> p.dayIndex() == pin.dayIndex()); pins.add(pin);
        pins.sort(Comparator.comparingInt(ScenarioSpec.Waypoint::dayIndex));
    }
    private static void putIv(List<ScenarioCanvasSpec.IvNode> nodes, ScenarioCanvasSpec.IvNode node) {
        nodes.removeIf(p -> p.dayIndex() == node.dayIndex()); nodes.add(node);
        nodes.sort(Comparator.comparingInt(ScenarioCanvasSpec.IvNode::dayIndex));
    }
    private static boolean observed(DataProvenance p) {
        return p == DataProvenance.OBSERVED || p == DataProvenance.BROKER;
    }
}
