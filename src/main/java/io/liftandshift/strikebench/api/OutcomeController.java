package io.liftandshift.strikebench.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.pricing.PayoffCurve;
import io.liftandshift.strikebench.recommend.RecommendationEngine;
import io.liftandshift.strikebench.util.Json;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * One lane-aware forward-outcome boundary for paths, exact-position valuation, and comparisons.
 * All callers share the same captured market book and path-ensemble services.
 */
final class OutcomeController {
    private final AppConfig cfg;
    private final Clock clock;
    private final MarketDataService market;
    private final io.liftandshift.strikebench.sim.SimulationEngine simEngine;
    private final io.liftandshift.strikebench.sim.PathEnsembleService pathEnsembles;
    private final io.liftandshift.strikebench.sim.MarketVolatilityResolver marketVolatility;
    private final Function<Context, String> activeWorld;
    private final Function<Context, String> ownerId;
    private final Function<Context, AnalysisContext> analysisContext;
    private final BiFunction<Context, RecommendationEngine.Request, Object> decisionEvaluator;

    OutcomeController(AppConfig cfg, Clock clock, MarketDataService market,
                      io.liftandshift.strikebench.sim.SimulationEngine simEngine,
                      io.liftandshift.strikebench.sim.PathEnsembleService pathEnsembles,
                      io.liftandshift.strikebench.sim.MarketVolatilityResolver marketVolatility,
                      Function<Context, String> activeWorld,
                      Function<Context, String> ownerId,
                      Function<Context, AnalysisContext> analysisContext,
                      BiFunction<Context, RecommendationEngine.Request, Object> decisionEvaluator) {
        this.cfg = cfg;
        this.clock = clock;
        this.market = market;
        this.simEngine = simEngine;
        this.pathEnsembles = pathEnsembles;
        this.marketVolatility = marketVolatility;
        this.activeWorld = activeWorld;
        this.ownerId = ownerId;
        this.analysisContext = analysisContext;
        this.decisionEvaluator = decisionEvaluator;
    }

    void register(JavalinConfig config) {
        OutcomeRoutes.register(config, new OutcomeRoutes.Handlers(this::evaluate));
    }

    private static String worldParam(String world) {
        return world == null || "observed".equals(world) ? null : world;
    }

    // ---- Datasets & scenario simulation ----

    public record ScenarioRequest(String symbol, io.liftandshift.strikebench.sim.ScenarioSpec spec,
                                  List<io.liftandshift.strikebench.sim.SimulationEngine.DecisionLevel> levels) {}

    public record StrategySimRequest(String symbol,
                                     java.util.List<io.liftandshift.strikebench.sim.ScenarioSimulator.SimLeg> legs,
                                     Integer qty,
                                     io.liftandshift.strikebench.sim.ScenarioSpec spec,
                                     io.liftandshift.strikebench.sim.IvSpec iv,
                                     io.liftandshift.strikebench.sim.PathEnsembleService.Basis pathBasis,
                                     io.liftandshift.strikebench.research.ResearchQuestionEngine.RunRequest study,
                                     // Signed TOTAL opening value: debit positive, credit negative.
                                     // Builder/Review can preserve the exact displayed package price.
                                     Long entryCostCents,
                                     // Optional leg-aligned ISO expirations. When present, the
                                     // listed package is exact: no neighboring expiry/strike snap.
                                     java.util.List<String> contractExpirations) {}

    public record CompareStructure(String key,
                                   List<io.liftandshift.strikebench.sim.ScenarioSimulator.SimLeg> legs,
                                   Long entryCostCents,
                                   List<String> contractExpirations) {}
    public record CompareRequest(String symbol, io.liftandshift.strikebench.sim.ScenarioSpec spec,
                                 io.liftandshift.strikebench.sim.IvSpec iv, Integer qty,
                                 List<CompareStructure> structures,
                                 io.liftandshift.strikebench.sim.PathEnsembleService.Basis pathBasis,
                                 io.liftandshift.strikebench.research.ResearchQuestionEngine.RunRequest study) {}

    /**
     * The comparative-evidence engine: every requested structure priced on the SAME seeded path
     * set (one generation, one budget permit), entries resolved to exact listed contracts where a
     * chain matches, refusals reported by name. One COMPARE operation replaces N sequential
     * POSITION evaluations that would each regenerate identical paths.
     */
    private Object simCompareResult(Context ctx, CompareRequest b) {
        if (b.spec() == null) throw new IllegalArgumentException("spec is required");
        if (b.structures() == null || b.structures().isEmpty()) throw new IllegalArgumentException("structures are required");
        if (b.structures().size() > 30) throw new IllegalArgumentException("at most 30 structures");
        String sym = b.symbol() == null ? "" : b.symbol().trim().toUpperCase(Locale.ROOT);
        if (sym.isEmpty()) throw new IllegalArgumentException("symbol is required");
        String world = worldParam(activeWorld.apply(ctx));
        EntryBook book = new EntryBook(sym, world); // one captured entry book for every structure
        double spot = book.quote()
                .map(q -> q.mark()).filter(java.util.Objects::nonNull)
                .map(java.math.BigDecimal::doubleValue).filter(v -> v > 0)
                .orElseThrow(() -> new io.liftandshift.strikebench.util.DataUnavailableException(
                        "No price for " + sym + " — a simulation needs a real (or demo) quote to anchor on."));
        int qty = b.qty() == null ? 1 : Math.clamp(b.qty(), 1, 100);
        double r = market.riskFreeRateQuote(Math.max(1, b.spec().sane().horizonDays()), world).annualRate();
        io.liftandshift.strikebench.sim.ScenarioSpec spec = calibrateVol(sym, b.spec(), world);
        io.liftandshift.strikebench.sim.IvSpec iv = b.iv();
        if (iv == null) {
            Double atm = atmIv(sym, world);
            iv = io.liftandshift.strikebench.sim.IvSpec.flat(atm != null ? atm : spec.sane().volAnnual());
        }
        List<io.liftandshift.strikebench.sim.ScenarioSimulator.CompareItem> items = new ArrayList<>();
        List<Map<String, Object>> refusedEarly = new ArrayList<>();
        for (CompareStructure st : b.structures()) {
            String legProblem = validateSimLegs(st.legs());
            if (legProblem != null) {
                refusedEarly.add(Map.of("key", st.key() == null ? "?" : st.key(), "reason", legProblem));
                continue;
            }
            MarketEntry me = marketEntry(sym, st.legs(), qty, world, book,
                    st.contractExpirations());
            if (st.contractExpirations() != null && me == null) {
                refusedEarly.add(Map.of("key", st.key() == null ? "?" : st.key(),
                        "reason", "one of the exact listed contracts is unavailable at an executable price"));
                continue;
            }
            var legsToRun = me != null && me.resolvedLegs() != null ? me.resolvedLegs() : st.legs();
            items.add(new io.liftandshift.strikebench.sim.ScenarioSimulator.CompareItem(
                    st.key(), legsToRun,
                    st.entryCostCents() != null ? st.entryCostCents() : me == null ? null : me.entryCents(),
                    st.entryCostCents() != null ? "entry fixed to the supplied package price"
                            : me == null ? null : "entry at " + me.source() + " executable quotes",
                    scenarioRoundTripFees(legsToRun, qty)));
        }
        var pathBasis = b.pathBasis() == null
                ? io.liftandshift.strikebench.sim.PathEnsembleService.Basis.PARAMETRIC : b.pathBasis();
        var comparison = new io.liftandshift.strikebench.sim.ScenarioSimulator().compare(
                pathEnsembles,
                new io.liftandshift.strikebench.sim.PathEnsembleService.Scope(sym, world, analysisContext.apply(ctx)),
                pathBasis, spec, b.study(), spot, items, qty, iv, r);
        var report = comparison.report();
        var evStudy = comparison.ensemble().study();
        List<Map<String, Object>> refused = new ArrayList<>(refusedEarly);
        report.refused().forEach(x -> refused.add(Map.of("key", x.key(), "reason", x.reason())));
        // R9: fees ride each outcome so the ranking can be judged NET of round-trip commissions.
        List<Map<String, Object>> feeAware = new ArrayList<>();
        for (var oc : report.results()) {
            // ONE fee convention product-wide (ledger rule): OPTION contracts only, ratio-aware —
            // a 1x2 backspread pays for 3 contracts, a buy-write's stock leg pays none.
            long fees = items.stream().filter(it -> it.key().equals(oc.key()))
                    .findFirst().map(io.liftandshift.strikebench.sim.ScenarioSimulator.CompareItem::roundTripFeesCents)
                    .orElse(0L);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", oc.key());
            m.put("result", oc.result());
            m.put("feesCents", fees);
            feeAware.add(m);
        }
        Map<String, Object> outCmp = new LinkedHashMap<>();
        outCmp.put("results", feeAware);
        outCmp.put("refused", refused);
        outCmp.put("volAnnual", spec.sane().volAnnual());
        outCmp.put("pathModelVersion", comparison.ensemble().modelVersion());
        // The alternatives every structure must beat + the fairness contract, disclosed.
        outCmp.put("cashBaseline", Map.of("key", "CASH", "note",
                "Doing nothing: $0 expected, $0 at risk, zero costs — any structure below a coin flip after costs loses to this."));
        if (evStudy != null) {
            outCmp.put("pathSource", pathBasis.name());
            outCmp.put("studyKey", evStudy.studyKey());
            outCmp.put("analogEvents", evStudy.eventDates() == null ? 0 : evStudy.eventDates().size());
            outCmp.put("observed", evStudy.observed());
            outCmp.put("fairness", "one quote snapshot, ONE historical analog ensemble ("
                    + (evStudy.observed() ? "real past occurrences" : "demo/generated history — not real")
                    + ") — every structure judged on the same conditional sample");
        } else {
            outCmp.put("fairness", "one quote snapshot, one seeded path set — every structure judged on identical futures");
        }
        outCmp.put("snapshotAt", book.snapshotAt); // the ENFORCED shared book, identified
        return outCmp;
    }

    /** Uniform structural validation for simulation legs; null = fine, else the refusal reason. */
    String validateSimLegs(List<io.liftandshift.strikebench.sim.ScenarioSimulator.SimLeg> legs) {
        if (legs == null || legs.isEmpty()) return "no legs";
        if (legs.size() > 8) return "more than 8 legs";
        for (var l : legs) {
            if (l == null) return "null leg";
            if (l.ratio() < 1 || l.ratio() > 10) return "leg ratio out of 1..10";
            boolean stock = "STOCK".equalsIgnoreCase(l.type());
            if (!stock) {
                if (l.strike() <= 0) return "non-positive strike";
                if (l.expiryDay() < 0) return "negative expiry day";
                if (!"CALL".equalsIgnoreCase(l.type()) && !"PUT".equalsIgnoreCase(l.type())) return "unknown leg type";
            }
            if (!"BUY".equalsIgnoreCase(l.action()) && !"SELL".equalsIgnoreCase(l.action())) return "unknown leg action";
        }
        return null;
    }

    private long scenarioRoundTripFees(
            List<io.liftandshift.strikebench.sim.ScenarioSimulator.SimLeg> legs, int qty) {
        long contracts = (legs == null ? List.<io.liftandshift.strikebench.sim.ScenarioSimulator.SimLeg>of() : legs)
                .stream().filter(l -> !"STOCK".equalsIgnoreCase(l.type()))
                .mapToLong(l -> Math.max(1, l.ratio())).sum() * Math.max(1, qty);
        long orderFees = legs == null || legs.isEmpty() ? 0 : cfg.feePerOrderCents() * 2L;
        return contracts * cfg.feePerContractCents() * 2L + orderFees;
    }

    private io.liftandshift.strikebench.sim.SimulationEngine.PreviewRun simScenarioRun(Context ctx, ScenarioRequest b) {
        if (b.spec() == null) throw new IllegalArgumentException("spec is required");
        String world = worldParam(activeWorld.apply(ctx));
        int horizon = Math.max(1, b.spec().sane().horizonDays());
        var marketVol = marketVol(b.symbol(), world, horizon);
        Double marketIv = marketVol == null ? null : marketVol.atmIv();
        var calibrated = b.spec().volAnnual() > 0 || marketIv == null ? b.spec() : b.spec().withVol(marketIv);
        double rate = market.riskFreeRateQuote(horizon, world).annualRate();
        return simEngine.previewRun(b.symbol(), calibrated, world, analysisContext.apply(ctx), b.levels(), marketVol, rate);
    }

    /** volAnnual<=0 = "use market vol": the chain's ATM IV, so every symbol gets ITS OWN wildness. */
    private io.liftandshift.strikebench.sim.ScenarioSpec calibrateVol(String symbol,
            io.liftandshift.strikebench.sim.ScenarioSpec spec, String worldId) {
        if (spec.volAnnual() > 0) return spec;
        Double atm = atmIv(symbol, worldId, Math.max(1, spec.sane().horizonDays()));
        return atm != null ? spec.withVol(atm) : spec; // sane() falls back to its own default if truly nothing
    }

    Double atmIv(String symbol) { return atmIv(symbol, null); }

    Double atmIv(String symbol, String worldId) {
        return atmIv(symbol, worldId, 30);
    }

    private Double atmIv(String symbol, String worldId, int horizonDays) {
        return marketVolatility.atmIv(symbol, worldId, horizonDays);
    }

    io.liftandshift.strikebench.sim.SimulationEngine.MarketVolInput marketVol(
            String symbol, String worldId, int horizonSessions) {
        return marketVolatility.resolve(symbol, worldId, horizonSessions);
    }

    void generateDataset(Context ctx) {
        ScenarioRequest b = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, ScenarioRequest.class));
        if (b.spec() == null) throw new IllegalArgumentException("spec is required");
        io.liftandshift.strikebench.sim.ScenarioSpec spec = calibrateVol(b.symbol(), b.spec(), worldParam(activeWorld.apply(ctx))); // resolve ONCE
        ctx.json(simEngine.toJson(simEngine.runAndPersist(b.symbol(), spec, ownerId.apply(ctx),
                worldParam(activeWorld.apply(ctx)), analysisContext.apply(ctx))));
    }

    Object simStrategyResult(Context ctx, StrategySimRequest b) {
        return simStrategyResult(ctx, b, null);
    }

    Object simStrategyResult(Context ctx, StrategySimRequest b,
                                     io.liftandshift.strikebench.sim.PathEnsembleService.Ensemble fixedEnsemble) {
        if (b.spec() == null) throw new IllegalArgumentException("spec is required");
        if (b.legs() == null || b.legs().isEmpty()) throw new IllegalArgumentException("legs are required");
        String sym = b.symbol() == null ? "" : b.symbol().trim().toUpperCase(Locale.ROOT);
        if (sym.isEmpty()) throw new IllegalArgumentException("symbol is required");
        String world = worldParam(activeWorld.apply(ctx));
        EntryBook entryBook = new EntryBook(sym, world);
        // Loud refusal on a missing quote — a strategy simulated against an invented $100 stock
        // would be fixture-masquerade all over again.
        double spot = fixedEnsemble != null ? fixedEnsemble.spot() : entryBook.quote()
                    .map(q -> q.mark()).filter(java.util.Objects::nonNull)
                    .map(java.math.BigDecimal::doubleValue).filter(v -> v > 0)
                    .orElseThrow(() -> new io.liftandshift.strikebench.util.DataUnavailableException(
                            "No price for " + sym + " — a simulation needs a real (or demo) quote to anchor on. Check the ticker."));
        double r = market.riskFreeRateQuote(Math.max(1, b.spec().sane().horizonDays()), world).annualRate();
        // ACTIONABILITY: price the ENTRY from live market quotes (executable sides) when a chain
        // is available, and default the IV path to the chain's ATM IV when the caller didn't set
        // one. A model-priced entry simulated against the same model converges to a coin flip by
        // construction; a market-priced entry measures YOUR SCENARIO vs THE MARKET'S PRICE.
        int qty = b.qty() == null ? 1 : b.qty();
        // Guard the FULL work product: paths×steps are capped in ScenarioSpec, but legs/qty/ratio
        // multiply the pricing loop and the exposure — bound them here too.
        if (b.legs().size() > 8) throw new IllegalArgumentException("at most 8 legs");
        if (qty < 1 || qty > 100) throw new IllegalArgumentException("qty must be 1..100");
        for (var lg : b.legs()) {
            if (lg.ratio() > 10) throw new IllegalArgumentException("ratio must be 1..10");
        }
        if (b.entryCostCents() != null && Math.abs(b.entryCostCents()) > 1_000_000_000L) {
            throw new IllegalArgumentException("entry cost is outside the supported range");
        }
        if (b.contractExpirations() != null) {
            if (b.contractExpirations().size() != b.legs().size()) {
                throw new IllegalArgumentException("contract expirations must align with the legs");
            }
            for (String exp : b.contractExpirations()) {
                if (exp != null && !exp.isBlank()) java.time.LocalDate.parse(exp);
            }
        }
        MarketEntry me = marketEntry(sym, b.legs(), qty, world, entryBook,
                b.contractExpirations());
        if (b.contractExpirations() != null && me == null) {
            throw new IllegalArgumentException(
                    "One of the exact listed contracts is no longer available at an executable price; refresh the position first.");
        }
        // CALIBRATION: volAnnual<=0 is the "use market vol" sentinel — replace with the chain's
        // ATM IV so a caller with no view on wildness gets THIS symbol's, not a canned 25%.
        io.liftandshift.strikebench.sim.ScenarioSpec spec = fixedEnsemble != null ? fixedEnsemble.spec() : b.spec();
        if (fixedEnsemble == null && spec.volAnnual() <= 0 && me != null && me.atmIv() != null) spec = spec.withVol(me.atmIv());
        io.liftandshift.strikebench.sim.IvSpec iv = b.iv();
        boolean marketCalibratedIv = iv == null;
        double ivAnchor = me != null && me.atmIv() != null ? me.atmIv() : spec.sane().volAnnual();
        if (iv == null) {
            iv = spec.sane().shape() == io.liftandshift.strikebench.sim.ScenarioSpec.Shape.EVENT_JUMP
                    ? io.liftandshift.strikebench.sim.IvSpec.eventCrushAround(ivAnchor,
                        Math.max(1, Math.round(spec.sane().horizonDays() / 3.0f)))
                    : io.liftandshift.strikebench.sim.IvSpec.flat(ivAnchor);
        }
        // CONTRACT IDENTITY: when the entry was priced from listed contracts, SIMULATE THOSE
        // CONTRACTS — pricing one strike/expiry and simulating another silently compared two
        // different trades. The note names every snap so nothing shifts silently.
        var legsToRun = me != null && me.resolvedLegs() != null ? me.resolvedLegs() : b.legs();
        String entryNote = null;
        if (me != null) {
            String fresh = me.freshness() == null ? "" : me.freshness();
            String quality = "FIXTURE".equals(fresh) ? "built-in DEMO quotes"
                    : "DELAYED".equals(fresh) ? "delayed market quotes (~15 min)"
                    : "REALTIME".equals(fresh) ? "real-time market quotes"
                    : "market quotes" + (fresh.isEmpty() ? "" : " (" + fresh.toLowerCase(Locale.ROOT) + ")");
            entryNote = "Entry priced from " + me.source() + " " + quality + " at executable sides (buy at ask, sell at bid)"
                    + (me.snaps().isEmpty() ? "" : ". Snapped to listed contracts: " + String.join("; ", me.snaps()))
                    + ".";
        }
        Long entryCost = b.entryCostCents() != null ? b.entryCostCents()
                : (me == null ? null : me.entryCents());
        if (b.entryCostCents() != null) {
            entryNote = "Entry fixed to the exact package price already shown on this screen; "
                    + "path exits are modeled from the listed contracts.";
        }
        String ivBasis = marketCalibratedIv
                ? (me != null && me.atmIv() != null
                    ? "IV path anchored to the active lane's nearest-horizon ATM option volatility"
                    : "IV path anchored to the scenario volatility because no eligible ATM option volatility was available")
                : "IV path set explicitly by the scenario controls";
        entryNote = (entryNote == null || entryNote.isBlank() ? "" : entryNote + " ") + ivBasis + ".";
        var pathBasis = b.pathBasis() == null
                ? io.liftandshift.strikebench.sim.PathEnsembleService.Basis.PARAMETRIC : b.pathBasis();
        io.liftandshift.strikebench.sim.ScenarioSimulator.EnsembleRun evaluated;
        var simulator = new io.liftandshift.strikebench.sim.ScenarioSimulator();
        if (fixedEnsemble != null) {
            if (fixedEnsemble.basis() != pathBasis) {
                throw new IllegalArgumentException("the supplied position must use the stored ensemble's path basis");
            }
            var result = simulator.runOnPaths(fixedEnsemble.paths(), fixedEnsemble.spot(), legsToRun, qty,
                    fixedEnsemble.spec(), iv, r, entryCost, entryNote,
                    scenarioRoundTripFees(legsToRun, qty));
            evaluated = new io.liftandshift.strikebench.sim.ScenarioSimulator.EnsembleRun(fixedEnsemble, result);
        } else {
            evaluated = simulator.run(pathEnsembles,
                    new io.liftandshift.strikebench.sim.PathEnsembleService.Scope(sym, world, analysisContext.apply(ctx)),
                    pathBasis, spec, b.study(), spot, legsToRun, qty, iv, r, entryCost, entryNote,
                    scenarioRoundTripFees(legsToRun, qty));
        }
        var studyRes = evaluated.ensemble().study();
        String pathModelVersion = evaluated.ensemble().modelVersion();
        if (studyRes != null) {
            var eresult = evaluated.result();
            // The interpretation is DIFFERENT and must say so: conditional history, not a model.
            var out = (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(eresult);
            out.put("pathSource", pathBasis.name());
            out.put("pathModelVersion", pathModelVersion);
            out.put("ivStart", iv.sane().startIv());
            out.put("ivLongRun", iv.sane().longRunIv());
            out.put("ivBasis", ivBasis);
            out.put("studyKey", studyRes.studyKey());
            out.put("analogEvents", studyRes.eventDates() == null ? 0 : studyRes.eventDates().size());
            out.put("evidence", studyRes.evidence());
            out.put("observed", studyRes.observed());
            // HONEST BASIS (holistic review P0): "REAL past occurrences" is only true when the
            // candles behind the study are observed market history. Demo fixtures and synthetic
            // scenario datasets are labeled as exactly what they are — never as real history.
            String occurrences = studyRes.observed() ? "REAL past occurrences"
                    : "DEMO_FIXTURE".equals(studyRes.evidence())
                        ? "DEMO-data occurrences (built-in demo history, NOT real market history)"
                        : "GENERATED-scenario occurrences (synthetic dataset, NOT real market history)";
            out.put("sourceNote", pathBasis == io.liftandshift.strikebench.sim.PathEnsembleService.Basis.HISTORICAL_ANALOGS
                    ? "Priced over " + evaluated.ensemble().paths().length + " " + occurrences + " of this condition ("
                        + studyRes.from() + " to " + studyRes.to() + ") — conditional history, not a model's odds, and not a forecast."
                    : "Priced over " + evaluated.ensemble().paths().length + " whole-path resamples of " + studyRes.eventDates().size()
                        + " " + occurrences + " (conditional bootstrap) — empirical shape preserved; sampling uncertainty, not a model.");
            return out;
        }
        var out = (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(evaluated.result());
        out.put("pathModelVersion", pathModelVersion);
        out.put("ivStart", iv.sane().startIv());
        out.put("ivLongRun", iv.sane().longRunIv());
        out.put("ivBasis", ivBasis);
        return out;
    }

    private record MarketEntry(long entryCents, Double atmIv, Double averageIv,
                               String source, String freshness,
                               List<io.liftandshift.strikebench.sim.ScenarioSimulator.SimLeg> resolvedLegs,
                               List<Leg> pricedLegs, List<String> snaps) {}

    /**
     * Prices the position's entry from the live chain at EXECUTABLE sides. Returns null when any
     * option leg can't be matched to a real quote (unknown expiration/strike, one-sided book) —
     * the simulator then falls back to a model-priced entry, honestly labeled.
     */
    private static String trimNum(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /**
     * F5: ONE immutable quote/chain snapshot for a whole comparison. Without it every structure
     * refetched the book independently — in a RUNNING simulated (or live) market prices advance
     * between structures and "identical futures, identical entry book" was prose, not a property.
     * First access per expiration fills the book; every later structure reuses the same object.
     */
    final class EntryBook {
        final String symbol; final String worldId;
        final String snapshotAt;
        private final java.util.Map<java.time.LocalDate, java.util.Optional<io.liftandshift.strikebench.model.OptionChain>> chains
                = new java.util.HashMap<>();
        private java.util.Optional<io.liftandshift.strikebench.model.Quote> quote;
        private List<java.time.LocalDate> exps;
        EntryBook(String symbol, String worldId) {
            this.symbol = symbol; this.worldId = worldId;
            this.snapshotAt = market.simInstant(worldId).orElse(clock.instant()).toString();
        }
        synchronized List<java.time.LocalDate> expirations() {
            if (exps == null) exps = market.expirations(symbol, worldId);
            return exps;
        }
        synchronized java.util.Optional<io.liftandshift.strikebench.model.Quote> quote() {
            if (quote == null) quote = market.quote(symbol, worldId);
            return quote;
        }
        synchronized java.util.Optional<io.liftandshift.strikebench.model.OptionChain> chain(java.time.LocalDate exp) {
            return chains.computeIfAbsent(exp, e -> market.chain(symbol, e, worldId));
        }
    }

    private MarketEntry marketEntry(String symbol, List<io.liftandshift.strikebench.sim.ScenarioSimulator.SimLeg> legs,
                                    int qty, String worldId, EntryBook book, List<String> contractExpirations) {
        try {
            List<java.time.LocalDate> exps = book != null ? book.expirations() : market.expirations(symbol, worldId);
            if (exps.isEmpty()) return null;
            java.time.LocalDate today = market.simInstant(worldId)
                    .map(i -> java.time.LocalDate.ofInstant(i, io.liftandshift.strikebench.market.MarketHours.EASTERN))
                    .orElseGet(() -> java.time.LocalDate.now(clock));
            double entryPerUnit = 0;
            Double atmIv = null;
            String source = null;
            String freshness = null;
            java.math.BigDecimal spotBd = null;
            List<io.liftandshift.strikebench.sim.ScenarioSimulator.SimLeg> resolved = new ArrayList<>();
            List<Leg> priced = new ArrayList<>();
            List<Double> marketIvs = new ArrayList<>();
            List<String> snaps = new ArrayList<>();
            for (int legIndex = 0; legIndex < legs.size(); legIndex++) {
                var leg = legs.get(legIndex);
                if ("STOCK".equalsIgnoreCase(leg.type())) {
                    var q = (book != null ? book.quote() : market.quote(symbol, worldId)).orElse(null);
                    if (q == null || q.mark() == null) return null;
                    double sign = "SELL".equalsIgnoreCase(leg.action()) ? -1 : 1;
                    entryPerUnit += sign * Math.max(1, leg.ratio()) * 100 * q.mark().doubleValue();
                    resolved.add(leg);
                    priced.add(Leg.stock(io.liftandshift.strikebench.model.LegAction.valueOf(
                            leg.action().trim().toUpperCase(Locale.ROOT)), Math.max(1, leg.ratio()), q.mark()));
                    continue;
                }
                String exactRaw = contractExpirations != null ? contractExpirations.get(legIndex) : null;
                boolean exactContract = exactRaw != null && !exactRaw.isBlank();
                java.time.LocalDate exp;
                if (exactContract) {
                    exp = java.time.LocalDate.parse(exactRaw);
                    if (!exps.contains(exp)) return null;
                } else {
                    // Generic scenario: nearest listed expiration to the requested trading-session horizon.
                    java.time.LocalDate target = io.liftandshift.strikebench.market.MarketHours
                            .tradingDateAfter(today, leg.expiryDay());
                    exp = exps.stream()
                            .min(java.util.Comparator.comparingLong(e2 -> Math.abs(java.time.temporal.ChronoUnit.DAYS.between(e2, target))))
                            .orElse(null);
                }
                if (exp == null) return null;
                var chain = (book != null ? book.chain(exp) : market.chain(symbol, exp, worldId)).orElse(null);
                if (chain == null || chain.isEmpty()) return null;
                if (spotBd == null) spotBd = chain.underlyingPrice();
                boolean call = "CALL".equalsIgnoreCase(leg.type());
                var side = call ? chain.calls() : chain.puts();
                var quote = side.stream()
                        .min(java.util.Comparator.comparingDouble(o -> Math.abs(o.strike().doubleValue() - leg.strike())))
                        .orElse(null);
                // The nearest listed strike must be reasonably close, or this isn't the same trade.
                double strikeGap = quote == null ? Double.POSITIVE_INFINITY
                        : Math.abs(quote.strike().doubleValue() - leg.strike());
                if (quote == null || (exactContract ? strikeGap > 1e-9
                        : strikeGap > Math.max(2.5, leg.strike() * 0.03))) return null;
                boolean buy = !"SELL".equalsIgnoreCase(leg.action());
                java.math.BigDecimal px = buy ? quote.ask() : quote.bid(); // executable sides
                if (px == null || px.signum() <= 0) return null;
                if (source == null) source = chain.source();
                if (freshness == null && chain.freshness() != null) freshness = chain.freshness().name();
                if (quote.iv() != null && quote.iv() > 0.01) marketIvs.add(quote.iv());
                // ATM IV = the quote closest to spot (first leg's chain is fine for a default).
                if (atmIv == null && spotBd != null) {
                    final java.math.BigDecimal spotF = spotBd;
                    atmIv = side.stream()
                            .filter(o -> o.iv() != null && o.iv() > 0.01)
                            .min(java.util.Comparator.comparingDouble(o -> Math.abs(o.strike().subtract(spotF).doubleValue())))
                            .map(io.liftandshift.strikebench.model.OptionQuote::iv).orElse(null);
                }
                entryPerUnit += (buy ? 1 : -1) * Math.max(1, leg.ratio()) * 100 * px.doubleValue();
                // THE SIMULATED LEG IS THE PRICED LEG: exact listed strike + that expiration's
                // trading-day horizon. Anything that moved is named in the snap note.
                double listedStrike = quote.strike().doubleValue();
                int listedDays = Math.max(1, io.liftandshift.strikebench.market.MarketHours
                        .tradingDaysBetween(today, exp));
                resolved.add(new io.liftandshift.strikebench.sim.ScenarioSimulator.SimLeg(
                        leg.action(), leg.type(), listedStrike, listedDays, leg.ratio()));
                priced.add(Leg.option(io.liftandshift.strikebench.model.LegAction.valueOf(
                                leg.action().trim().toUpperCase(Locale.ROOT)),
                        io.liftandshift.strikebench.model.OptionType.valueOf(
                                leg.type().trim().toUpperCase(Locale.ROOT)),
                        quote.strike(), exp, Math.max(1, leg.ratio()), px));
                if (Math.abs(listedStrike - leg.strike()) > 1e-9 || Math.abs(listedDays - leg.expiryDay()) > 1) {
                    snaps.add(leg.type() + " " + trimNum(leg.strike()) + "\u2192" + trimNum(listedStrike) + " exp " + exp);
                }
            }
            Double averageIv = marketIvs.isEmpty() ? null
                    : marketIvs.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
            return new MarketEntry(Math.round(entryPerUnit * qty * 100), atmIv, averageIv,
                    source == null ? "live" : source, freshness, resolved, priced, snaps);
        } catch (Exception e) {
            return null; // no market pricing available — model entry, honestly labeled
        }
    }


    private void evaluate(Context ctx) {
        ctx.json(evaluateOutcomes(ctx, ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx,
                io.liftandshift.strikebench.outcomes.OutcomeContract.Request.class))));
    }


    /** One private cross-surface contract over the shared outcome kernels. */
    io.liftandshift.strikebench.outcomes.OutcomeContract.Response evaluateOutcomes(
            Context ctx, io.liftandshift.strikebench.outcomes.OutcomeContract.Request request) {
        if (request == null) throw new IllegalArgumentException("outcome request is required");
        if (request.operation() == null) throw new IllegalArgumentException("operation is required");
        var basis = request.basis() == null
                ? request.operation() == io.liftandshift.strikebench.outcomes.OutcomeContract.Operation.DECISION
                    ? io.liftandshift.strikebench.outcomes.OutcomeContract.Basis.DECISION_POLICY
                    : io.liftandshift.strikebench.outcomes.OutcomeContract.Basis.PARAMETRIC
                : request.basis();
        Map<String, Object> resolved = resolveOutcomeContext(ctx, request.context());
        String symbol = String.valueOf(resolved.get("symbol"));
        Object result;
        String interpretation;

        switch (request.operation()) {
            case DECISION -> {
                if (basis != io.liftandshift.strikebench.outcomes.OutcomeContract.Basis.DECISION_POLICY) {
                    throw new IllegalArgumentException("DECISION uses DECISION_POLICY basis");
                }
                if (request.decision() == null) throw new IllegalArgumentException("decision inputs are required");
                if (request.decision().symbol() == null
                        || !symbol.equalsIgnoreCase(request.decision().symbol())) {
                    throw new IllegalArgumentException("decision symbol must match context.symbol");
                }
                result = decisionEvaluator.apply(ctx, request.decision());
                interpretation = "One decision policy ranks mechanically eligible structures by after-cost economics, evidence and risk.";
            }
            case PATHS -> {
                if (basis != io.liftandshift.strikebench.outcomes.OutcomeContract.Basis.PARAMETRIC) {
                    throw new IllegalArgumentException("PATHS currently uses PARAMETRIC basis; historical paths come from a Research study");
                }
                List<io.liftandshift.strikebench.sim.SimulationEngine.DecisionLevel> levels = request.levels() == null
                        ? List.of() : request.levels().stream().map(l ->
                            new io.liftandshift.strikebench.sim.SimulationEngine.DecisionLevel(
                                    l.key(), l.price() == null ? Double.NaN : l.price().doubleValue())).toList();
                var run = simScenarioRun(ctx, new ScenarioRequest(symbol, requireOutcomeSpec(request.over()), levels));
                var pathResult = (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(run.preview());
                if (request.position() != null) {
                    var position = requireOutcomePosition(request.position());
                    var legs = toSimLegs(ctx, position.legs());
                    Object positionOutcome = simStrategyResult(ctx, new StrategySimRequest(symbol, legs,
                            position.qty(), run.ensemble().spec(), request.iv(),
                            io.liftandshift.strikebench.sim.PathEnsembleService.Basis.PARAMETRIC,
                            null, position.entryCostCents(), contractExpirations(position.legs())), run.ensemble());
                    pathResult.set("positionOutcome", Json.MAPPER.valueToTree(positionOutcome));
                    pathResult.put("positionEnsembleFingerprint", run.preview().receipt().fingerprint());
                }
                result = pathResult;
                interpretation = "Model-generated price paths: possible futures, never a forecast or historical frequency.";
            }
            case POSITION -> {
                var position = requireOutcomePosition(request.position());
                if (basis == io.liftandshift.strikebench.outcomes.OutcomeContract.Basis.RISK_NEUTRAL) {
                    result = riskNeutralPositionResult(ctx, symbol, position,
                            new EntryBook(symbol, worldParam(activeWorld.apply(ctx))));
                    interpretation = "Market-implied terminal odds from the exact listed package and executable entry; not a forecast.";
                    break;
                }
                var legs = toSimLegs(ctx, position.legs());
                result = simStrategyResult(ctx, new StrategySimRequest(symbol, legs,
                        position.qty(), requireOutcomeSpec(request.over()), request.iv(), pathBasis(basis),
                        request.study(), position.entryCostCents(), contractExpirations(position.legs())));
                interpretation = basis == io.liftandshift.strikebench.outcomes.OutcomeContract.Basis.PARAMETRIC
                        ? "The exact position is repriced over model-generated paths; probabilities are scenario-conditional, not a forecast."
                        : basis == io.liftandshift.strikebench.outcomes.OutcomeContract.Basis.HISTORICAL_ANALOGS
                            ? "The exact position is repriced over matching historical occurrences; this is conditional history, not model odds."
                            : "The exact position is repriced over whole-path resamples of matching history; this measures sampling uncertainty.";
            }
            case COMPARE -> {
                if (basis == io.liftandshift.strikebench.outcomes.OutcomeContract.Basis.RISK_NEUTRAL) {
                    result = riskNeutralComparisonResult(ctx, symbol, request.positions());
                    interpretation = "Every listed package is judged from one captured market book under the same risk-neutral convention.";
                    break;
                }
                if (request.positions() == null || request.positions().isEmpty()) {
                    throw new IllegalArgumentException("positions are required for COMPARE");
                }
                if (request.positions().size() > 30) throw new IllegalArgumentException("at most 30 positions");
                int qty = request.positions().getFirst().qty() == null ? 1 : request.positions().getFirst().qty();
                List<CompareStructure> structures = new ArrayList<>();
                for (var position : request.positions()) {
                    requireOutcomePosition(position);
                    int pq = position.qty() == null ? 1 : position.qty();
                    if (pq != qty) throw new IllegalArgumentException("COMPARE positions must use the same quantity");
                    structures.add(new CompareStructure(position.key(), toSimLegs(ctx, position.legs()),
                            position.entryCostCents(), contractExpirations(position.legs())));
                }
                result = simCompareResult(ctx, new CompareRequest(symbol, requireOutcomeSpec(request.over()),
                        request.iv(), qty, structures, pathBasis(basis), request.study()));
                interpretation = basis == io.liftandshift.strikebench.outcomes.OutcomeContract.Basis.PARAMETRIC
                        ? "Every position uses one quote snapshot and the same seeded model paths."
                        : "Every position uses one quote snapshot and the same conditional historical ensemble.";
            }
            default -> throw new IllegalArgumentException("Unknown outcome operation");
        }
        return new io.liftandshift.strikebench.outcomes.OutcomeContract.Response(
                request.operation(), basis, resolved, interpretation, result);
    }

    private Map<String, Object> riskNeutralComparisonResult(Context ctx, String symbol,
            List<io.liftandshift.strikebench.outcomes.OutcomeContract.Position> positions) {
        if (positions == null || positions.isEmpty()) {
            throw new IllegalArgumentException("positions are required for COMPARE");
        }
        if (positions.size() > 30) throw new IllegalArgumentException("at most 30 positions");
        EntryBook book = new EntryBook(symbol, worldParam(activeWorld.apply(ctx)));
        List<Map<String, Object>> results = new ArrayList<>();
        List<Map<String, Object>> refused = new ArrayList<>();
        for (var position : positions) {
            requireOutcomePosition(position);
            try {
                results.add(Map.of("key", position.key() == null ? "POSITION" : position.key(),
                        "result", riskNeutralPositionResult(ctx, symbol, position, book)));
            } catch (RuntimeException e) {
                refused.add(Map.of("key", position.key() == null ? "POSITION" : position.key(),
                        "reason", io.liftandshift.strikebench.sim.ScenarioSimulator.publicReason(e)));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("results", results);
        out.put("refused", refused);
        out.put("snapshotAt", book.snapshotAt);
        out.put("cashBaseline", Map.of("key", "CASH", "expectedValueCents", 0L,
                "maxLossCents", 0L, "note", "Doing nothing has no modeled market risk or execution cost."));
        out.put("fairness", "one captured quote/chain book and one risk-neutral convention for every listed package");
        return out;
    }

    private Map<String, Object> riskNeutralPositionResult(Context ctx, String symbol,
            io.liftandshift.strikebench.outcomes.OutcomeContract.Position position, EntryBook book) {
        List<String> expirations = contractExpirations(position.legs());
        if (expirations == null || position.legs().stream()
                .filter(l -> l != null && !"STOCK".equalsIgnoreCase(l.type()))
                .anyMatch(l -> l.expiration() == null || l.expiration().isBlank())) {
            throw new IllegalArgumentException("risk-neutral evaluation needs the exact listed expiration on every option leg");
        }
        var distinct = position.legs().stream()
                .filter(l -> l != null && !"STOCK".equalsIgnoreCase(l.type()))
                .map(io.liftandshift.strikebench.outcomes.OutcomeContract.Leg::expiration)
                .distinct().toList();
        if (distinct.size() != 1) {
            throw new IllegalArgumentException("risk-neutral terminal odds support one expiration; use path evaluation for calendars and diagonals");
        }
        int qty = position.qty() == null ? 1 : position.qty();
        List<io.liftandshift.strikebench.sim.ScenarioSimulator.SimLeg> simLegs = toSimLegs(ctx, position.legs());
        MarketEntry entry = marketEntry(symbol, simLegs, qty, worldParam(activeWorld.apply(ctx)), book, expirations);
        if (entry == null || entry.pricedLegs() == null || entry.pricedLegs().isEmpty()) {
            throw new IllegalArgumentException("the exact listed package is unavailable at executable prices");
        }
        double iv = entry.averageIv() != null && entry.averageIv() > 0 ? entry.averageIv()
                : entry.atmIv() != null && entry.atmIv() > 0 ? entry.atmIv() : Double.NaN;
        if (!(iv > 0)) throw new IllegalArgumentException("market IV is unavailable for this package");
        var baseCurve = PayoffCurve.of(entry.pricedLegs(), qty);
        long desiredNet = position.entryCostCents() == null ? -entry.entryCents() : -position.entryCostCents();
        long adjustment = desiredNet - baseCurve.entryNetPremiumCents();
        var curve = PayoffCurve.of(entry.pricedLegs(), qty, adjustment);
        java.time.LocalDate today = market.simInstant(worldParam(activeWorld.apply(ctx)))
                .map(i -> java.time.LocalDate.ofInstant(i, io.liftandshift.strikebench.market.MarketHours.EASTERN))
                .orElseGet(() -> java.time.LocalDate.now(clock));
        var time = io.liftandshift.strikebench.market.OptionTime.nearest(entry.pricedLegs(), today);
        String outcomeWorld = worldParam(activeWorld.apply(ctx));
        double rate = market.riskFreeRateQuote((int) Math.max(1, time.calendarDays()), outcomeWorld).annualRate();
        var shorts = entry.pricedLegs().stream()
                .filter(l -> !l.isStock() && l.action() == io.liftandshift.strikebench.model.LegAction.SELL)
                .map(Leg::strike).toList();
        var analyzed = io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.analyze(
                curve, book.quote().orElseThrow().mark().doubleValue(), iv, time.years(), rate, shorts);
        long contracts = entry.pricedLegs().stream().filter(l -> !l.isStock())
                .mapToLong(Leg::ratio).sum() * qty;
        long roundTripFees = contracts * cfg.feePerContractCents() * 2L
                + cfg.feePerOrderCents() * 2L;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("probabilityMap", analyzed.probabilityMap());
        out.put("expectedValueCents", analyzed.expectedValueCents());
        out.put("expectedValueAfterFeesCents", analyzed.expectedValueCents() - roundTripFees);
        out.put("evSensitivity", analyzed.sensitivity());
        out.put("entryCostCents", -desiredNet);
        out.put("roundTripFeesCents", roundTripFees);
        out.put("marketIv", iv);
        out.put("riskFreeRate", rate);
        out.put("time", time);
        out.put("theoreticalMaxProfitCents", curve.maxProfitUnbounded() ? null : curve.maxProfitCents());
        out.put("theoreticalMaxLossCents", curve.maxLossUnbounded() ? null : curve.maxLossCents());
        out.put("maxProfitUnbounded", curve.maxProfitUnbounded());
        out.put("maxLossUnbounded", curve.maxLossUnbounded());
        out.put("breakevens", curve.breakevens());
        out.put("payoff", curve.chartPoints(book.quote().orElseThrow().mark()));
        out.put("source", entry.source());
        out.put("freshness", entry.freshness());
        out.put("snapshotAt", book.snapshotAt);
        return out;
    }

    private io.liftandshift.strikebench.sim.ScenarioSpec requireOutcomeSpec(
            io.liftandshift.strikebench.sim.ScenarioSpec spec) {
        if (spec == null) throw new IllegalArgumentException("over (scenario specification) is required");
        return spec;
    }

    private static io.liftandshift.strikebench.sim.PathEnsembleService.Basis pathBasis(
            io.liftandshift.strikebench.outcomes.OutcomeContract.Basis basis) {
        return switch (basis) {
            case PARAMETRIC -> io.liftandshift.strikebench.sim.PathEnsembleService.Basis.PARAMETRIC;
            case HISTORICAL_ANALOGS -> io.liftandshift.strikebench.sim.PathEnsembleService.Basis.HISTORICAL_ANALOGS;
            case CONDITIONAL_BOOTSTRAP -> io.liftandshift.strikebench.sim.PathEnsembleService.Basis.CONDITIONAL_BOOTSTRAP;
            default -> throw new IllegalArgumentException(basis + " is not a path-ensemble basis");
        };
    }

    private io.liftandshift.strikebench.outcomes.OutcomeContract.Position requireOutcomePosition(
            io.liftandshift.strikebench.outcomes.OutcomeContract.Position position) {
        if (position == null || position.legs() == null || position.legs().isEmpty()) {
            throw new IllegalArgumentException("position with at least one leg is required");
        }
        if (position.qty() != null && (position.qty() < 1 || position.qty() > 100)) {
            throw new IllegalArgumentException("position quantity must be 1..100");
        }
        return position;
    }

    List<io.liftandshift.strikebench.sim.ScenarioSimulator.SimLeg> toSimLegs(
            Context ctx, List<io.liftandshift.strikebench.outcomes.OutcomeContract.Leg> legs) {
        List<io.liftandshift.strikebench.sim.ScenarioSimulator.SimLeg> out = new ArrayList<>();
        java.time.LocalDate laneToday = market.simInstant(worldParam(activeWorld.apply(ctx)))
                .map(i -> java.time.LocalDate.ofInstant(i, io.liftandshift.strikebench.market.MarketHours.EASTERN))
                .orElseGet(() -> java.time.LocalDate.now(clock));
        for (var leg : legs) {
            if (leg == null || leg.action() == null || leg.type() == null) {
                throw new IllegalArgumentException("each position leg needs action and type");
            }
            String type = leg.type().trim().toUpperCase(Locale.ROOT);
            int ratio = leg.ratio() == null ? 1 : leg.ratio();
            if ("STOCK".equals(type)) {
                out.add(new io.liftandshift.strikebench.sim.ScenarioSimulator.SimLeg(
                        leg.action().trim().toUpperCase(Locale.ROOT), "STOCK", 0, 0, ratio));
                continue;
            }
            if (leg.strike() == null || leg.strike().signum() <= 0) {
                throw new IllegalArgumentException("option legs need a positive strike");
            }
            int expiryDay;
            if (leg.expiryDay() != null) expiryDay = leg.expiryDay();
            else if (leg.expiration() != null && !leg.expiration().isBlank()) {
                expiryDay = outcomeExpiryDay(laneToday, java.time.LocalDate.parse(leg.expiration()));
            } else throw new IllegalArgumentException("option legs need expiration or expiryDay");
            out.add(new io.liftandshift.strikebench.sim.ScenarioSimulator.SimLeg(
                    leg.action().trim().toUpperCase(Locale.ROOT), type,
                    leg.strike().doubleValue(), expiryDay, ratio));
        }
        return out;
    }

    static int outcomeExpiryDay(java.time.LocalDate today, java.time.LocalDate expiration) {
        return Math.max(1, io.liftandshift.strikebench.market.MarketHours
                .tradingDaysBetween(today, expiration));
    }

    List<String> contractExpirations(
            List<io.liftandshift.strikebench.outcomes.OutcomeContract.Leg> legs) {
        boolean any = legs.stream().anyMatch(l -> l != null && l.expiration() != null && !l.expiration().isBlank());
        if (!any) return null;
        return legs.stream().map(l -> l == null ? null : l.expiration()).toList();
    }

    private Map<String, Object> resolveOutcomeContext(Context ctx,
            io.liftandshift.strikebench.outcomes.OutcomeContract.MarketContext requested) {
        if (requested == null || requested.symbol() == null || requested.symbol().isBlank()) {
            throw new IllegalArgumentException("context.symbol is required");
        }
        String symbol = requested.symbol().trim().toUpperCase(Locale.ROOT);
        String world = activeWorld.apply(ctx);
        var analysis = analysisContext.apply(ctx);
        String lane = io.liftandshift.strikebench.market.MarketLane
                .of(world, cfg.fixturesOnly(), analysis).name();
        if (requested.worldId() != null && !requested.worldId().isBlank()
                && !world.equals(requested.worldId())) {
            throw new IllegalStateException("Evaluation context changed: active market is " + world);
        }
        if (requested.datasetId() != null && !requested.datasetId().isBlank()
                && !analysis.datasetId().equals(requested.datasetId())) {
            throw new IllegalStateException("Evaluation context changed: active dataset is " + analysis.datasetId());
        }
        if (requested.marketLane() != null && !requested.marketLane().isBlank()
                && !lane.equalsIgnoreCase(requested.marketLane())) {
            throw new IllegalStateException("Evaluation context changed: active lane is " + lane);
        }
        var quote = market.quote(symbol, worldParam(world)).orElse(null);
        String asOf = quote == null || quote.asOfEpochMs() <= 0 ? null
                : java.time.Instant.ofEpochMilli(quote.asOfEpochMs()).toString();
        if (requested.asOf() != null && !requested.asOf().isBlank()) {
            java.time.Instant expected = java.time.Instant.parse(requested.asOf());
            if (asOf == null || !expected.equals(java.time.Instant.parse(asOf))) {
                throw new IllegalStateException("Evaluation context changed: the quote snapshot advanced; refresh before comparing");
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", symbol);
        out.put("marketLane", lane);
        out.put("worldId", world);
        out.put("datasetId", analysis.datasetId());
        if (asOf != null) out.put("asOf", asOf);
        out.put("serverTime", market.simInstant(worldParam(world)).orElse(clock.instant()).toString());
        return out;
    }

}
