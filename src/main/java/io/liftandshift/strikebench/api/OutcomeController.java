package io.liftandshift.strikebench.api;

import io.liftandshift.strikebench.model.Symbol;
import static io.liftandshift.strikebench.market.MarketLane.worldParam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.paper.ExecutablePackagePricer;
import io.liftandshift.strikebench.paper.OrderInstruction;
import io.liftandshift.strikebench.paper.PackagePriceReceipt;
import io.liftandshift.strikebench.pricing.PayoffCurve;
import io.liftandshift.strikebench.recommend.RecommendationEngine;
import io.liftandshift.strikebench.util.Json;

import java.math.BigDecimal;
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
    private final io.liftandshift.strikebench.plan.PlanOutcomeService planOutcomes;
    private final Function<Context, String> activeWorld;
    private final Function<Context, String> ownerId;
    private final Function<Context, AnalysisContext> analysisContext;
    private final BiFunction<Context, RecommendationEngine.Request, Object> decisionEvaluator;

    OutcomeController(AppConfig cfg, Clock clock, MarketDataService market,
                      io.liftandshift.strikebench.sim.SimulationEngine simEngine,
                      io.liftandshift.strikebench.sim.PathEnsembleService pathEnsembles,
                      io.liftandshift.strikebench.sim.MarketVolatilityResolver marketVolatility,
                      io.liftandshift.strikebench.plan.PlanOutcomeService planOutcomes,
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
        this.planOutcomes = planOutcomes;
        this.activeWorld = activeWorld;
        this.ownerId = ownerId;
        this.analysisContext = analysisContext;
        this.decisionEvaluator = decisionEvaluator;
    }

    void register(JavalinConfig config) {
        OutcomeRoutes.register(config, new OutcomeRoutes.Handlers(this::evaluate));
    }


    // ---- Datasets & scenario simulation ----

    public record ScenarioRequest(String symbol, io.liftandshift.strikebench.sim.ScenarioSpec spec,
                                  List<io.liftandshift.strikebench.sim.SimulationEngine.DecisionLevel> levels) {}

    public record StrategySimRequest(String symbol,
                                     io.liftandshift.strikebench.sim.PathPosition position,
                                     Integer qty,
                                     io.liftandshift.strikebench.sim.ScenarioSpec spec,
                                     io.liftandshift.strikebench.sim.IvSpec iv,
                                     io.liftandshift.strikebench.sim.PathEnsembleService.Basis pathBasis,
                                     io.liftandshift.strikebench.research.ResearchQuestionEngine.RunRequest study,
                                     // Null asks the server to price the current book. Otherwise
                                     // exact package cash, fees, basis and provenance stay together.
                                     PackagePriceReceipt entryPrice,
                                     // Optional leg-aligned ISO expirations. When present, the
                                     // listed package is exact: no neighboring expiry/strike snap.
                                     java.util.List<String> contractExpirations) {}

    public record CompareStructure(String key,
                                   io.liftandshift.strikebench.sim.PathPosition position,
                                   PackagePriceReceipt entryPrice,
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
        b.spec().validated();
        if (b.iv() != null) b.iv().validated(b.spec().horizonDays());
        if (b.structures() == null || b.structures().isEmpty()) throw new IllegalArgumentException("structures are required");
        if (b.structures().size() > 30) throw new IllegalArgumentException("at most 30 structures");
        String sym = Symbol.normalize(b.symbol());
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
        Map<String, PackagePriceReceipt> pricesByKey = new LinkedHashMap<>();
        List<Map<String, Object>> refusedEarly = new ArrayList<>();
        for (CompareStructure st : b.structures()) {
            if (st.position() == null) {
                refusedEarly.add(Map.of("key", st.key() == null ? "?" : st.key(), "reason", "no position"));
                continue;
            }
            PackagePriceReceipt capturedPrice;
            try {
                capturedPrice = requireOutcomeEntryPrice(st.entryPrice(), qty);
            } catch (IllegalArgumentException missingReceipt) {
                refusedEarly.add(Map.of("key", st.key() == null ? "?" : st.key(),
                        "reason", missingReceipt.getMessage()));
                continue;
            }
            validateContractExpirations(st.position(), st.contractExpirations());
            MarketEntry me = marketEntry(sym, st.position(), qty, world, book,
                    st.contractExpirations());
            if (st.contractExpirations() != null && me == null) {
                refusedEarly.add(Map.of("key", st.key() == null ? "?" : st.key(),
                        "reason", "one of the exact listed contracts is unavailable at an executable price"));
                continue;
            }
            var positionToRun = me != null && me.resolvedPosition() != null
                    ? me.resolvedPosition() : st.position();
            PackagePriceReceipt entryPrice = capturedPrice != null
                    ? capturedPrice : me == null ? null : me.price();
            long roundTripFees;
            try {
                roundTripFees = resolveOutcomeRoundTripFees(entryPrice,
                        () -> scenarioRoundTripFees(positionToRun, qty));
            } catch (IllegalArgumentException missingReceipt) {
                refusedEarly.add(Map.of("key", st.key() == null ? "?" : st.key(),
                        "reason", missingReceipt.getMessage()));
                continue;
            }
            items.add(new io.liftandshift.strikebench.sim.ScenarioSimulator.CompareItem(
                    st.key(), positionToRun,
                    entryPrice == null ? null : entryPrice.payoffEntryCostCents(),
                    entryPrice == null ? null : outcomeEntryNote(entryPrice, me),
                    roundTripFees));
            pricesByKey.put(st.key(), entryPrice);
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
        // The complete package-price receipt rides each outcome. A modeled fallback has no
        // captured price, so its fee is explicitly labeled modeled instead of being published as
        // a second loose fact beside a receipt.
        List<Map<String, Object>> feeAware = new ArrayList<>();
        for (var oc : report.results()) {
            // ONE fee convention product-wide (ledger rule): OPTION contracts only, ratio-aware —
            // a 1x2 backspread pays for 3 contracts, a buy-write's stock leg pays none.
            long fees = items.stream().filter(it -> it.key().equals(oc.key()))
                    .findFirst().map(io.liftandshift.strikebench.sim.ScenarioSimulator.CompareItem::roundTripFeesCents)
                    .orElseThrow(() -> new IllegalStateException(
                            "comparison result has no matching captured fee receipt: " + oc.key()));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", oc.key());
            ObjectNode result = (ObjectNode) Json.MAPPER.valueToTree(oc.result());
            PackagePriceReceipt price = pricesByKey.get(oc.key());
            publishOutcomeEntry(result, price);
            if (price == null) result.put("modeledRoundTripFeesCents", fees);
            m.put("result", result);
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

    private long scenarioRoundTripFees(
            io.liftandshift.strikebench.sim.PathPosition position, int qty) {
        // THE one fee formula (also correctly charges no option order fee on a stock-only package).
        return io.liftandshift.strikebench.util.Fees.roundTripCents(
                scenarioOptionContracts(position, qty),
                cfg.feePerContractCents(), cfg.feePerOrderCents());
    }

    static long scenarioOptionContracts(
            io.liftandshift.strikebench.sim.PathPosition position, int qty) {
        if (position == null) {
            throw new IllegalArgumentException("scenario fee calculation requires a position");
        }
        return io.liftandshift.strikebench.util.Fees.optionContracts(position.legs(), qty);
    }

    /**
     * A captured entry carries its own captured fee schedule. Current configuration is consulted
     * only when this request genuinely asks the server to model an uncaptured current entry.
     */
    static long resolveOutcomeRoundTripFees(PackagePriceReceipt entryPrice,
                                            java.util.function.LongSupplier currentFeeSchedule) {
        if (entryPrice == null) return currentFeeSchedule.getAsLong();
        requireOutcomeEntryPrice(entryPrice, entryPrice.quantity());
        return entryPrice.estimatedRoundTripFeesCents();
    }

    /**
     * Validate one supplied outcome-entry receipt without translating it into loose primitives.
     * Null means “price/model the current entry”; UNAVAILABLE remains unavailable and is never
     * silently replaced by a newer book.
     */
    static PackagePriceReceipt requireOutcomeEntryPrice(PackagePriceReceipt price, int quantity) {
        if (price == null) return null;
        if (price.quantity() != quantity) {
            throw new IllegalArgumentException(
                    "The captured package-price quantity does not match the outcome position.");
        }
        if (price.feeSide() != PackagePriceReceipt.FeeSide.OPENING) {
            throw new IllegalArgumentException(
                    "An outcome entry requires an OPENING package-price receipt.");
        }
        if (!price.priced()) {
            throw new IllegalArgumentException("The captured entry is unavailable: "
                    + price.unavailableReason());
        }
        if (price.estimatedRoundTripFeesCents() == null) {
            throw new IllegalArgumentException("The captured entry states no estimated round-trip "
                    + "commission, so an after-cost outcome cannot be reported.");
        }
        price.payoffEntryCostCents(); // validates the one opening-cost sign boundary
        return price;
    }

    private static String outcomeEntryNote(PackagePriceReceipt price, MarketEntry marketEntry) {
        if (price.valuationBasis() == PackagePriceReceipt.ValuationBasis.RECORDED_FILL) {
            return "entry fixed to the held position's recorded fill";
        }
        if (marketEntry != null && price == marketEntry.price()) {
            return "entry at " + marketEntry.source() + " executable quotes";
        }
        return "entry fixed to the proposal's captured package-price receipt ("
                + price.valuationBasis().name().toLowerCase(Locale.ROOT).replace('_', ' ') + ")";
    }

    /**
     * The simulator internally values a cost-convention scalar, but the public outcome never
     * republishes that inverse-sign primitive beside a captured price. Captured/current-book
     * entries expose the complete receipt; a genuinely modeled fallback is named as modeled.
     */
    private static void publishOutcomeEntry(ObjectNode outcome, PackagePriceReceipt price) {
        JsonNode modeled = outcome.remove("entryCostCents");
        if (price != null) {
            outcome.set("price", Json.MAPPER.valueToTree(price));
        } else if (modeled != null && modeled.isNumber()) {
            outcome.set("modeledEntryCostCents", modeled);
        }
    }

    private io.liftandshift.strikebench.sim.SimulationEngine.PreviewRun simScenarioRun(Context ctx, ScenarioRequest b) {
        if (b.spec() == null) throw new IllegalArgumentException("spec is required");
        b.spec().validated();
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
        if (atm == null || !(atm > 0)) {
            throw new io.liftandshift.strikebench.util.DataUnavailableException(
                    "Market-calibrated scenario volatility was requested, but eligible ATM IV is unavailable.");
        }
        return spec.withVol(atm);
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
        b.spec().validated();
        io.liftandshift.strikebench.sim.ScenarioSpec spec = calibrateVol(b.symbol(), b.spec(), worldParam(activeWorld.apply(ctx))); // resolve ONCE
        ctx.json(simEngine.toJson(simEngine.runAndPersist(b.symbol(), spec, ownerId.apply(ctx),
                worldParam(activeWorld.apply(ctx)), analysisContext.apply(ctx))));
    }

    Object simStrategyResult(Context ctx, StrategySimRequest b) {
        return simStrategyResult(ctx, b, null);
    }

    Object simStrategyResult(Context ctx, StrategySimRequest b,
                                     io.liftandshift.strikebench.sim.PathEnsembleService.Ensemble fixedEnsemble) {
        return simStrategyResult(ctx, b, fixedEnsemble, null);
    }

    Object simStrategyResult(Context ctx, StrategySimRequest b,
            io.liftandshift.strikebench.sim.PathEnsembleService.Ensemble fixedEnsemble,
            io.liftandshift.strikebench.sim.ScenarioCanvasSpec canvas) {
        if (b.spec() == null) throw new IllegalArgumentException("spec is required");
        if (b.position() == null) throw new IllegalArgumentException("position is required");
        b.spec().validated();
        if (b.iv() != null) b.iv().validated(b.spec().horizonDays());
        String sym = Symbol.normalize(b.symbol());
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
        if (qty < 1 || qty > 100) throw new IllegalArgumentException("qty must be 1..100");
        PackagePriceReceipt capturedPrice = requireOutcomeEntryPrice(b.entryPrice(), qty);
        Long capturedCost = capturedPrice == null ? null : capturedPrice.payoffEntryCostCents();
        if (capturedCost != null && Math.abs(capturedCost) > 1_000_000_000L) {
            throw new IllegalArgumentException("entry cost is outside the supported range");
        }
        validateContractExpirations(b.position(), b.contractExpirations());
        MarketEntry me = marketEntry(sym, b.position(), qty, world, entryBook,
                b.contractExpirations());
        if (b.contractExpirations() != null && me == null) {
            throw new IllegalArgumentException(
                    "One of the exact listed contracts is no longer available at an executable price; refresh the position first.");
        }
        // CALIBRATION: volAnnual<=0 is the "use market vol" sentinel — replace with the chain's
        // ATM IV so a caller with no view on wildness gets THIS symbol's, not a canned 25%.
        io.liftandshift.strikebench.sim.ScenarioSpec spec = fixedEnsemble != null ? fixedEnsemble.spec() : b.spec();
        if (fixedEnsemble == null && spec.volAnnual() <= 0) {
            if (me != null && me.atmIv() != null && me.atmIv() > 0) {
                spec = spec.withVol(me.atmIv());
            } else {
                throw new io.liftandshift.strikebench.util.DataUnavailableException(
                        io.liftandshift.strikebench.sim.ScenarioSpec.MISSING_VOLATILITY);
            }
        }
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
        var positionToRun = me != null && me.resolvedPosition() != null
                ? me.resolvedPosition() : b.position();
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
        PackagePriceReceipt entryPrice = capturedPrice != null
                ? capturedPrice : me == null ? null : me.price();
        Long entryCost = entryPrice == null ? null : entryPrice.payoffEntryCostCents();
        if (capturedPrice != null) {
            entryNote = (capturedPrice.valuationBasis() == PackagePriceReceipt.ValuationBasis.RECORDED_FILL
                    ? "Entry fixed to the held position's recorded fill"
                    : "Entry fixed to the proposal's captured package-price receipt")
                    + "; path exits are modeled from the listed contracts.";
        }
        String ivBasis = marketCalibratedIv
                ? (me != null && me.atmIv() != null
                    ? "IV path anchored to the active lane's nearest-horizon ATM option volatility"
                    : "IV path anchored to the scenario volatility because no eligible ATM option volatility was available")
                : "IV path set explicitly by the scenario controls";
        entryNote = (entryNote == null || entryNote.isBlank() ? "" : entryNote + " ") + ivBasis + ".";
        var pathBasis = b.pathBasis() == null
                ? io.liftandshift.strikebench.sim.PathEnsembleService.Basis.PARAMETRIC : b.pathBasis();
        long roundTripFees = resolveOutcomeRoundTripFees(entryPrice,
                () -> scenarioRoundTripFees(positionToRun, qty));
        io.liftandshift.strikebench.sim.ScenarioSimulator.EnsembleRun evaluated;
        var simulator = new io.liftandshift.strikebench.sim.ScenarioSimulator();
        if (fixedEnsemble != null) {
            if (fixedEnsemble.basis() != pathBasis) {
                throw new IllegalArgumentException("the supplied position must use the stored ensemble's path basis");
            }
            var result = simulator.runOnPaths(fixedEnsemble.paths(), positionToRun, qty,
                    fixedEnsemble.spec(), iv, canvas, r, entryCost, entryNote,
                    roundTripFees);
            evaluated = new io.liftandshift.strikebench.sim.ScenarioSimulator.EnsembleRun(fixedEnsemble, result);
        } else {
            evaluated = simulator.run(pathEnsembles,
                    new io.liftandshift.strikebench.sim.PathEnsembleService.Scope(sym, world, analysisContext.apply(ctx)),
                    pathBasis, spec, b.study(), spot, positionToRun, qty, iv, r, entryCost, entryNote,
                    roundTripFees);
        }
        var studyRes = evaluated.ensemble().study();
        String pathModelVersion = evaluated.ensemble().modelVersion();
        if (studyRes != null) {
            var eresult = evaluated.result();
            // The interpretation is DIFFERENT and must say so: conditional history, not a model.
            var out = (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(eresult);
            publishOutcomeEntry(out, entryPrice);
            if (entryPrice == null) out.put("modeledRoundTripFeesCents", roundTripFees);
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
        publishOutcomeEntry(out, entryPrice);
        if (entryPrice == null) out.put("modeledRoundTripFeesCents", roundTripFees);
        out.put("pathModelVersion", pathModelVersion);
        out.put("ivStart", iv.sane().startIv());
        out.put("ivLongRun", iv.sane().longRunIv());
        out.put("ivBasis", ivBasis);
        return out;
    }

    record MarketEntry(PackagePriceReceipt price, Double atmIv, Double averageIv,
                       String source, String freshness,
                       io.liftandshift.strikebench.sim.PathPosition resolvedPosition,
                       List<String> snaps) {
        List<Leg> pricedLegs() { return resolvedPosition == null ? List.of() : resolvedPosition.legs(); }
    }

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
            this.snapshotAt = market.laneNow(worldId, clock).toString();
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

    MarketEntry marketEntry(String symbol, io.liftandshift.strikebench.sim.PathPosition position,
                            int qty, String worldId, EntryBook book, List<String> contractExpirations) {
        List<java.time.LocalDate> exps = book != null ? book.expirations() : market.expirations(symbol, worldId);
        if (exps.isEmpty()) return null;
        java.time.Instant laneNow = market.laneNow(worldId, clock);
        java.time.LocalDate today = java.time.LocalDate.ofInstant(
                laneNow, io.liftandshift.strikebench.market.MarketHours.EASTERN);
        Double atmIv = null;
        java.math.BigDecimal spotBd = null;
        List<ExecutablePackagePricer.LegBook> priceInputs = new ArrayList<>();
        List<Double> marketIvs = new ArrayList<>();
        List<String> snaps = new ArrayList<>();
        for (int legIndex = 0; legIndex < position.legs().size(); legIndex++) {
            var leg = position.legs().get(legIndex);
            int multiplier = leg.multiplier();
            if (leg.isStock()) {
                var q = (book != null ? book.quote() : market.quote(symbol, worldId)).orElse(null);
                priceInputs.add(ExecutablePackagePricer.LegBook.from(
                        new Leg(leg.action(), null, null, null,
                                Math.max(1, leg.ratio()), BigDecimal.ZERO, multiplier), q));
                continue;
            }
            String exactRaw = contractExpirations != null ? contractExpirations.get(legIndex) : null;
            boolean exactContract = exactRaw != null && !exactRaw.isBlank();
            java.time.LocalDate exp;
            if (exactContract) {
                exp = java.time.LocalDate.parse(exactRaw);
                if (!exps.contains(exp)) return null;
            } else {
                // Generic scenario: the same server-owned listed-expiration selection receipt
                // consumed by Research. No controller or browser gets a private date policy.
                exp = io.liftandshift.strikebench.market.OptionTime.selectListedExpiration(
                        exps, laneNow, position.expiryDay(leg)).expiration();
            }
            if (exp == null) return null;
            var chain = (book != null ? book.chain(exp) : market.chain(symbol, exp, worldId)).orElse(null);
            if (chain == null || chain.isEmpty()) return null;
            if (spotBd == null) spotBd = chain.underlyingPrice();
            boolean call = leg.type() == io.liftandshift.strikebench.model.OptionType.CALL;
            var side = call ? chain.calls() : chain.puts();
            var quote = side.stream()
                    .min(java.util.Comparator.comparingDouble(o -> Math.abs(
                            o.strike().doubleValue() - leg.strike().doubleValue())))
                    .orElse(null);
            // The nearest listed strike must be reasonably close, or this isn't the same trade.
            double strikeGap = quote == null ? Double.POSITIVE_INFINITY
                    : Math.abs(quote.strike().doubleValue() - leg.strike().doubleValue());
            if (quote == null || (exactContract ? strikeGap > 1e-9
                    : strikeGap > Math.max(2.5, leg.strike().doubleValue() * 0.03))) return null;
            if (quote.iv() != null && quote.iv() > 0.01) marketIvs.add(quote.iv());
            // ATM IV = the quote closest to spot (first leg's chain is fine for a default).
            if (atmIv == null && spotBd != null) {
                final java.math.BigDecimal spotF = spotBd;
                atmIv = side.stream()
                        .filter(o -> o.iv() != null && o.iv() > 0.01)
                        .min(java.util.Comparator.comparingDouble(o -> Math.abs(o.strike().subtract(spotF).doubleValue())))
                        .map(io.liftandshift.strikebench.model.OptionQuote::iv).orElse(null);
            }
            // THE SIMULATED LEG IS THE PRICED LEG: exact listed strike + that expiration's
            // trading-day horizon. Anything that moved is named in the snap note.
            double listedStrike = quote.strike().doubleValue();
            int listedDays = io.liftandshift.strikebench.market.MarketHours
                    .tradingDaysBetween(today, exp);
            Leg resolved = Leg.option(leg.action(), leg.type(),
                    quote.strike(), exp, Math.max(1, leg.ratio()), BigDecimal.ZERO, multiplier);
            priceInputs.add(ExecutablePackagePricer.LegBook.from(resolved, quote));
            if (Math.abs(listedStrike - leg.strike().doubleValue()) > 1e-9
                    || Math.abs(listedDays - position.expiryDay(leg)) > 1) {
                snaps.add(leg.type() + " " + trimNum(leg.strike().doubleValue())
                        + "\u2192" + trimNum(listedStrike) + " exp " + exp);
            }
        }
        Double averageIv = marketIvs.isEmpty() ? null
                : marketIvs.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        ExecutablePackagePricer.Book pricedBook = ExecutablePackagePricer.price(priceInputs,
                market.lane(worldId), ExecutablePackagePricer.Policy.EXECUTABLE_ONLY);
        if (!pricedBook.priced() || !pricedBook.executable()) return null;
        var feeSchedule = io.liftandshift.strikebench.util.Fees.schedule(
                io.liftandshift.strikebench.util.Fees.optionContracts(pricedBook.pricedLegs(), qty),
                cfg.feePerContractCents(), cfg.feePerOrderCents());
        PackagePriceReceipt price = pricedBook.receipt(qty, feeSchedule,
                PackagePriceReceipt.FeeSide.OPENING, OrderInstruction.market());
        return new MarketEntry(price, atmIv, averageIv, price.source(), price.freshness(),
                new io.liftandshift.strikebench.sim.PathPosition(today, pricedBook.pricedLegs()), snaps);
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
                String outcomeWorld = activeWorld.apply(ctx);
                var analysis = analysisContext.apply(ctx);
                double rate = market.riskFreeRateQuote(
                        Math.max(1, run.ensemble().spec().horizonDays()), worldParam(outcomeWorld)).annualRate();
                var iv = request.iv() == null
                        ? io.liftandshift.strikebench.sim.IvSpec.flat(run.ensemble().spec().volAnnual())
                        : request.iv();
                var publicReceipt = planOutcomes.saveResearchEnsemble(ownerId.apply(ctx),
                        new io.liftandshift.strikebench.plan.PlanOutcomeService.ResearchContext(
                                String.valueOf(resolved.get("marketLane")),
                                String.valueOf(resolved.get("worldId")),
                                String.valueOf(resolved.get("datasetId"))),
                        run.ensemble(), iv, io.liftandshift.strikebench.sim.ScenarioCanvasSpec.defaults(),
                        rate, run.preview(), Json.MAPPER.valueToTree(request));
                var pathResult = ((com.fasterxml.jackson.databind.node.ObjectNode) publicReceipt.preview()).deepCopy();
                pathResult.set("ensemble", Json.MAPPER.valueToTree(new ApiResponses.EnsembleRef(
                        publicReceipt.id(), publicReceipt.fingerprint(), publicReceipt.basis(),
                        publicReceipt.waypointFill())));
                pathResult.put("researchReceiptExpiresAt", publicReceipt.expiresAt());
                if (request.position() != null) {
                    var position = requireOutcomePosition(request.position());
                    var pathPosition = toPathPosition(ctx, position.legs());
                    Object positionOutcome = simStrategyResult(ctx, new StrategySimRequest(symbol, pathPosition,
                            position.qty(), run.ensemble().spec(), request.iv(),
                            io.liftandshift.strikebench.sim.PathEnsembleService.Basis.PARAMETRIC,
                            null, position.price(),
                            contractExpirations(position.legs())), run.ensemble());
                    pathResult.set("positionOutcome", Json.MAPPER.valueToTree(positionOutcome));
                    pathResult.put("positionEnsembleFingerprint", publicReceipt.fingerprint());
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
                var pathPosition = toPathPosition(ctx, position.legs());
                result = simStrategyResult(ctx, new StrategySimRequest(symbol, pathPosition,
                        position.qty(), requireOutcomeSpec(request.over()), request.iv(), pathBasis(basis),
                        request.study(), position.price(),
                        contractExpirations(position.legs())));
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
                int qty = request.positions().getFirst().qty();
                List<CompareStructure> structures = new ArrayList<>();
                for (var position : request.positions()) {
                    requireOutcomePosition(position);
                    int pq = position.qty();
                    if (pq != qty) throw new IllegalArgumentException("COMPARE positions must use the same quantity");
                    structures.add(new CompareStructure(position.key(), toPathPosition(ctx, position.legs()),
                            position.price(),
                            contractExpirations(position.legs())));
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
        out.put("cashBaseline",
                io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.BaselineReceipt.cash());
        out.put("fairness", "one captured quote/chain book and one risk-neutral convention for every listed package");
        return out;
    }

    private Map<String, Object> riskNeutralPositionResult(Context ctx, String symbol,
            io.liftandshift.strikebench.outcomes.OutcomeContract.Position position, EntryBook book) {
        PackagePriceReceipt capturedPrice = requireOutcomeEntryPrice(position.price(), position.qty());
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
        var underlying = requireOutcomeQuote(book.quote(), symbol);
        int qty = position.qty();
        io.liftandshift.strikebench.sim.PathPosition pathPosition = toPathPosition(ctx, position.legs());
        MarketEntry entry = marketEntry(symbol, pathPosition, qty,
                worldParam(activeWorld.apply(ctx)), book, expirations);
        if (entry == null || entry.pricedLegs() == null || entry.pricedLegs().isEmpty()) {
            throw new IllegalArgumentException("the exact listed package is unavailable at executable prices");
        }
        double iv = entry.averageIv() != null && entry.averageIv() > 0 ? entry.averageIv()
                : entry.atmIv() != null && entry.atmIv() > 0 ? entry.atmIv() : Double.NaN;
        if (!(iv > 0)) throw new IllegalArgumentException("market IV is unavailable for this package");
        var baseCurve = PayoffCurve.of(entry.pricedLegs(), qty);
        PackagePriceReceipt entryPrice = capturedPrice == null ? entry.price() : capturedPrice;
        long desiredNet = entryPrice.grossPackageNetCents();
        long adjustment = Math.subtractExact(desiredNet, baseCurve.entryNetPremiumCents());
        var curve = PayoffCurve.of(entry.pricedLegs(), qty, adjustment);
        String outcomeWorld = worldParam(activeWorld.apply(ctx));
        var time = io.liftandshift.strikebench.market.OptionTime.nearest(entry.pricedLegs(),
                market.laneNow(outcomeWorld, clock));
        if (!time.hasModelTime()) {
            throw new IllegalArgumentException("risk-neutral evaluation has no live option-time"
                    + " fraction: " + time.state());
        }
        double rate = market.riskFreeRateQuote((int) Math.max(1, time.calendarDays()), outcomeWorld).annualRate();
        var shorts = entry.pricedLegs().stream()
                .filter(l -> !l.isStock() && l.action() == io.liftandshift.strikebench.model.LegAction.SELL)
                .map(Leg::strike).toList();
        var analyzed = io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.analyze(
                curve, entryPrice,
                io.liftandshift.strikebench.util.Money.toCents(underlying.mark()),
                iv, time, rate, shorts);
        long roundTripFees = resolveOutcomeRoundTripFees(entryPrice,
                () -> scenarioRoundTripFees(pathPosition, qty));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("marketImpliedRisk", analyzed);
        out.put("marketImpliedEvAfterCostsCents",
                Math.subtractExact(analyzed.expectedValueCents(), roundTripFees));
        out.put("price", entryPrice);
        out.put("theoreticalMaxProfitCents", curve.maxProfitUnbounded() ? null : curve.maxProfitCents());
        out.put("theoreticalMaxLossCents", curve.maxLossUnbounded() ? null : curve.maxLossCents());
        out.put("maxProfitUnbounded", curve.maxProfitUnbounded());
        out.put("maxLossUnbounded", curve.maxLossUnbounded());
        out.put("breakevens", curve.breakevens());
        out.put("payoff", curve.chartPoints(underlying.mark()));
        out.put("snapshotAt", book.snapshotAt);
        return out;
    }

    private io.liftandshift.strikebench.sim.ScenarioSpec requireOutcomeSpec(
            io.liftandshift.strikebench.sim.ScenarioSpec spec) {
        if (spec == null) throw new IllegalArgumentException("over (scenario specification) is required");
        return spec.validated();
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
        if (position.qty() < 1 || position.qty() > 100) {
            throw new IllegalArgumentException("position quantity must be 1..100");
        }
        return position;
    }

    io.liftandshift.strikebench.sim.PathPosition toPathPosition(
            Context ctx, List<io.liftandshift.strikebench.outcomes.OutcomeContract.Leg> legs) {
        java.time.LocalDate laneToday = market.laneToday(worldParam(activeWorld.apply(ctx)), clock);
        return toPathPosition(ctx, legs, laneToday);
    }

    /** Re-anchor exact contracts to the immutable date of a stored path ensemble. */
    io.liftandshift.strikebench.sim.PathPosition toPathPosition(
            Context ctx, List<io.liftandshift.strikebench.outcomes.OutcomeContract.Leg> legs,
            java.time.LocalDate valuationDate) {
        if (valuationDate == null) throw new IllegalArgumentException("valuation date is required");
        List<Leg> out = new ArrayList<>();
        for (var leg : legs) {
            if (leg == null || leg.action() == null || leg.type() == null) {
                throw new IllegalArgumentException("each position leg needs action and type");
            }
            String type = leg.type().trim().toUpperCase(Locale.ROOT);
            int ratio = leg.ratio();
            int multiplier = leg.multiplier();
            if (ratio < 1) throw new IllegalArgumentException("leg ratio must be >= 1");
            if (multiplier < 1 || multiplier > 10_000) {
                throw new IllegalArgumentException("leg multiplier must be 1..10,000");
            }
            io.liftandshift.strikebench.model.LegAction action;
            try {
                action = io.liftandshift.strikebench.model.LegAction.valueOf(
                        leg.action().trim().toUpperCase(Locale.ROOT));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("leg action must be BUY or SELL");
            }
            if ("STOCK".equals(type)) {
                out.add(new Leg(action, null, null, null, ratio, BigDecimal.ZERO, multiplier));
                continue;
            }
            if (leg.strike() == null || leg.strike().signum() <= 0) {
                throw new IllegalArgumentException("option legs need a positive strike");
            }
            io.liftandshift.strikebench.model.OptionType optionType;
            try {
                optionType = io.liftandshift.strikebench.model.OptionType.valueOf(type);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("leg type must be CALL, PUT, or STOCK");
            }
            java.time.LocalDate expiration;
            if (leg.expiration() != null && !leg.expiration().isBlank()) {
                expiration = java.time.LocalDate.parse(leg.expiration());
            } else if (leg.expiryDay() != null && leg.expiryDay() >= 0) {
                expiration = io.liftandshift.strikebench.market.MarketHours
                        .tradingDateAfter(valuationDate, leg.expiryDay());
            } else throw new IllegalArgumentException("option legs need expiration or a non-negative expiryDay");
            out.add(Leg.option(action, optionType, leg.strike(), expiration, ratio,
                    BigDecimal.ZERO, multiplier));
        }
        return new io.liftandshift.strikebench.sim.PathPosition(valuationDate, out);
    }

    List<String> contractExpirations(
            List<io.liftandshift.strikebench.outcomes.OutcomeContract.Leg> legs) {
        boolean any = legs.stream().anyMatch(l -> l != null && l.expiration() != null && !l.expiration().isBlank());
        if (!any) return null;
        return legs.stream().map(l -> l == null ? null : l.expiration()).toList();
    }

    static void validateContractExpirations(io.liftandshift.strikebench.sim.PathPosition position,
                                            List<String> expirations) {
        if (expirations == null) return;
        if (position == null || expirations.size() != position.legs().size()) {
            throw new IllegalArgumentException("contract expirations must align with the legs");
        }
        for (String expiration : expirations) {
            if (expiration != null && !expiration.isBlank()) java.time.LocalDate.parse(expiration);
        }
    }

    static io.liftandshift.strikebench.model.Quote requireOutcomeQuote(
            java.util.Optional<io.liftandshift.strikebench.model.Quote> quote, String symbol) {
        return quote.filter(q -> q.mark() != null && q.mark().signum() > 0)
                .orElseThrow(() -> new io.liftandshift.strikebench.util.DataUnavailableException(
                        "No price for " + symbol
                                + " — market-implied evaluation needs a lane-owned quote. Refresh market data and try again."));
    }

    private Map<String, Object> resolveOutcomeContext(Context ctx,
            io.liftandshift.strikebench.outcomes.OutcomeContract.MarketContext requested) {
        if (requested == null || requested.symbol() == null || requested.symbol().isBlank()) {
            throw new IllegalArgumentException("context.symbol is required");
        }
        String symbol = Symbol.normalize(requested.symbol());
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
        out.put("serverTime", market.laneNow(worldParam(world), clock).toString());
        return out;
    }

}
