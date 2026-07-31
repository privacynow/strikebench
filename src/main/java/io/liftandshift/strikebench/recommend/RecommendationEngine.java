package io.liftandshift.strikebench.recommend;
import static io.liftandshift.strikebench.util.Numbers.round2;

import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.MarketHours;
import io.liftandshift.strikebench.market.EventService;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.OptionQuote;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.model.ScenarioStory;
import io.liftandshift.strikebench.model.Symbol;
import io.liftandshift.strikebench.paper.ExecutablePackagePricer;
import io.liftandshift.strikebench.paper.OrderInstruction;
import io.liftandshift.strikebench.paper.PackagePriceReceipt;
import io.liftandshift.strikebench.pricing.PayoffCurve;
import io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer;
import io.liftandshift.strikebench.strategy.Guardrails;
import io.liftandshift.strikebench.strategy.IronCondorQuality;
import io.liftandshift.strikebench.strategy.StrategyBuilder;
import io.liftandshift.strikebench.strategy.StrategyFamily;
import io.liftandshift.strikebench.strategy.StrategyIntent;
import io.liftandshift.strikebench.strategy.Verdict;
import io.liftandshift.strikebench.util.Fees;
import io.liftandshift.strikebench.util.Money;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Produces risk-screened, data-backed EDUCATIONAL candidates — never advice, never a promise.
 * Ranks by a composite of finite-risk validity, freshness, liquidity, risk:reward, POP,
 * capital efficiency, and event risk — never max profit alone. Defaults are conservative:
 * defined risk only, no 0DTE, small fraction of the account at risk.
 */
public final class RecommendationEngine {

    public static final String DISCLAIMER =
            "Educational tool only, not financial advice. These are risk-screened teaching examples based on "
            + "current (possibly delayed or simulated) data. Options involve substantial risk; you can lose the "
            + "entire amount at risk and, in undefined-risk strategies, more. Nothing here promises any profit. "
            + "POP and market EV use a present-value risk-neutral lognormal approximation at the lane's "
            + "risk-free rate (q=0 dividend-yield assumption); market EV is a price/cost benchmark, not an "
            + "independent edge forecast; breakevens are payoff geometry. Raw model outputs "
            + "exclude commissions; any EV labeled after costs subtracts the disclosed estimated round-trip commissions.";

    /** Structural shape group — delegates to the catalog's explicit metadata (presentation only). */
    static String structuralGroup(String family) {
        if (family == null) return "other";
        try { return StrategyFamily.valueOf(family).structureGroup(); }
        catch (IllegalArgumentException e) { return "other"; }
    }
    private static final int MAX_QTY = 5;

    /**
     * Risk mode is a CAPITAL BUDGET, nothing else: a per-idea percent of buying power. It never
     * gates which strategies exist (the position's own math and the DecisionPolicy do that).
     */
    public enum RiskMode {
        CONSERVATIVE(0.01), BALANCED(0.02), AGGRESSIVE(0.05);
        final double defaultRiskPct;
        RiskMode(double defaultRiskPct) { this.defaultRiskPct = defaultRiskPct; }

        public double defaultRiskPct() { return defaultRiskPct; }

        public static RiskMode parse(String s) {
            if (s == null) return CONSERVATIVE;
            String v = s.trim().toUpperCase(Locale.ROOT);
            try { return valueOf(v); }
            catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("riskMode must be conservative, balanced, or aggressive");
            }
        }
    }

    public record Request(
            String symbol,
            String thesis,               // bullish | bearish | neutral | volatile
            String horizon,              // 0DTE | week | month | quarter
            String riskMode,             // conservative | balanced | aggressive
            Long maxLossCents,           // absolute per-trade budget, optional
            Double maxRiskPctOfAccount,  // optional, defaults by risk mode
            Double minConfidence,        // 0..1, optional
            List<String> allowedStrategies, // optional whitelist
            Boolean avoidEarnings,
            Boolean allow0dte,
            String intent,               // explicit StrategyIntent at every product/API decision boundary
            Holdings holdings,           // shares context for EXIT/HEDGE/ACQUIRE flows, optional
            Filters filters              // hard screens on candidate metrics, optional
    ) {
        /** A copy with the risk-capital-capped per-trade budget; every other field unchanged. */
        public Request withMaxLossCents(Long cappedMaxLossCents) {
            return new Request(symbol, thesis, horizon, riskMode, cappedMaxLossCents, maxRiskPctOfAccount,
                    minConfidence, allowedStrategies, avoidEarnings, allow0dte, intent, holdings, filters);
        }

        /** A copy carrying the account's real share position; every other field unchanged. */
        public Request withHoldings(Holdings resolvedHoldings) {
            return new Request(symbol, thesis, horizon, riskMode, maxLossCents, maxRiskPctOfAccount,
                    minConfidence, allowedStrategies, avoidEarnings, allow0dte, intent, resolvedHoldings, filters);
        }
    }

    /**
     * Shares context. sharesOwned must be the FREE (unlocked) share count; costBasisCents is the
     * average per-share basis; targetPriceCents is the per-share price the user would happily
     * sell at (EXIT), buy at (ACQUIRE), or protect down to (HEDGE).
     */
    /** {@code assignmentPreference} mirrors plan-context values (AVOID/ACCEPT/PREFER_BELOW_BASIS/
     *  SEEK); null = undeclared. It is the consent switch for deliberate in-the-money shorts. */
    public record Holdings(Integer sharesOwned, Long costBasisCents, Long targetPriceCents,
                           String assignmentPreference,
                           HoldingsEvidence.Provenance provenance,
                           String destinationAccountId,
                           String custodyLane,
                           Long observedAtEpochMs) {
        public Holdings(Integer sharesOwned, Long costBasisCents, Long targetPriceCents) {
            this(sharesOwned, costBasisCents, targetPriceCents, null, null,
                    null, null, null);
        }
        public Holdings(Integer sharesOwned, Long costBasisCents, Long targetPriceCents,
                        String assignmentPreference) {
            this(sharesOwned, costBasisCents, targetPriceCents, assignmentPreference, null,
                    null, null, null);
        }
        public Holdings(Integer sharesOwned, Long costBasisCents, Long targetPriceCents,
                        String assignmentPreference, HoldingsEvidence.Provenance provenance) {
            this(sharesOwned, costBasisCents, targetPriceCents, assignmentPreference, provenance,
                    null, null, null);
        }
        public Holdings {
            if (sharesOwned != null && sharesOwned < 0) {
                throw new IllegalArgumentException("sharesOwned cannot be negative");
            }
            if (costBasisCents != null && costBasisCents < 0) {
                throw new IllegalArgumentException("costBasisCents cannot be negative");
            }
            // Compatibility callers that have not yet named provenance remain useful for analysis,
            // but they must never gain account-backed authority by omission.
            if (sharesOwned != null && provenance == null) {
                provenance = HoldingsEvidence.Provenance.HYPOTHETICAL_HOLDINGS;
            }
        }
        public HoldingsEvidence evidence() {
            if (provenance == null) return null;
            return HoldingsEvidence.forProvenance(provenance, sharesOwned, costBasisCents,
                    destinationAccountId, custodyLane, observedAtEpochMs);
        }
    }

    /** Hard candidate screens; a candidate failing one lands in rejected[] with the reason. */
    public record Filters(
            Double minPop,                 // 0..1
            Double maxShortSideExpirationItmProb,      // 0..1
            Double minAnnualizedOpeningPremiumRatePct,  // e.g. 12 = 12%/yr
            Long maxCostCents,              // cap on cash paid at entry (debits)
            Long maxCapitalRequiredCents,   // exact catalog-basis capital/collateral encumbrance
            Long maxMarketCrashLossCents    // loss magnitude at ScenarioStory.MARKET_CRASH
    ) {
        /** Compatibility for existing callers that declare only the original four screens. */
        public Filters(Double minPop, Double maxShortSideExpirationItmProb,
                       Double minAnnualizedOpeningPremiumRatePct, Long maxCostCents) {
            this(minPop, maxShortSideExpirationItmProb, minAnnualizedOpeningPremiumRatePct, maxCostCents, null, null);
        }

        public Filters {
            if (maxCapitalRequiredCents != null && maxCapitalRequiredCents < 0) {
                throw new IllegalArgumentException("maxCapitalRequiredCents cannot be negative");
            }
            if (maxMarketCrashLossCents != null && maxMarketCrashLossCents < 0) {
                throw new IllegalArgumentException("maxMarketCrashLossCents cannot be negative");
            }
        }
    }

    public record Result(
            String symbol,
            String thesis,
            String horizon,
            String riskMode,
            String intent,
            long riskBudgetCents,
            /** The underlying price every candidate was priced against, for payoff axes; null when no chain loaded. */
            Long spotCents,
            List<Candidate> candidates,
            List<Rejection> rejected,
            List<String> notes,
            String disclaimer
    ) {}

    private final MarketDataService market;
    private final Clock clock;
    private final EventService events;
    private long feePerContractCents;
    private long feePerOrderCents;

    public RecommendationEngine(MarketDataService market, Clock clock) {
        this(market, clock, new EventService(market, clock));
    }

    public RecommendationEngine(MarketDataService market, Clock clock, EventService events) {
        this.market = market;
        this.clock = clock;
        this.events = java.util.Objects.requireNonNull(events, "events");
    }

    /** Candidate income/effective-price metrics use the same opening commission as the ticket. */
    public RecommendationEngine withFees(long perContractCents, long perOrderCents) {
        if (perContractCents < 0 || perOrderCents < 0) {
            throw new IllegalArgumentException("configured commissions cannot be negative");
        }
        this.feePerContractCents = perContractCents;
        this.feePerOrderCents = perOrderCents;
        return this;
    }

    public LocalDate marketDate(String worldId) {
        return market.laneToday(worldId, clock);
    }

    public Result recommend(Request req, long buyingPowerCents) {
        return recommend(req, buyingPowerCents, null);
    }

    /** World-aware: inside a SIMULATED session, recommendations price against THAT world —
     *  the whole point of a reviewer market. null = observed (the real-lane rule stands). */
    public Result recommend(Request req, long buyingPowerCents, String worldId) {
        String symbol = Symbol.normalize(req.symbol());
        RiskMode mode = RiskMode.parse(req.riskMode());
        StrategyIntent intent = StrategyIntent.parse(req.intent());
        String horizon = effectiveHorizon(req.horizon(), intent);
        StrategyFamily.Thesis thesis = parseThesis(req.thesis());
        Holdings holdings = req.holdings();
        int freeShares = holdings != null && holdings.sharesOwned() != null ? Math.max(0, holdings.sharesOwned()) : 0;
        BigDecimal targetPrice = holdings != null && holdings.targetPriceCents() != null && holdings.targetPriceCents() > 0
                ? Money.priceFromCents(holdings.targetPriceCents()) : null;
        Filters filters = req.filters() == null
                ? new Filters(null, null, null, null, null, null) : req.filters();
        boolean allow0dte = Boolean.TRUE.equals(req.allow0dte());
        boolean avoidEarnings = Boolean.TRUE.equals(req.avoidEarnings());
        long riskBudget = RiskBudgetPolicy.requestBudgetCents(
                mode, buyingPowerCents, req.maxRiskPctOfAccount(), req.maxLossCents());
        long budget = riskBudget;
        double minConfidence = req.minConfidence() == null ? 0 : req.minConfidence();

        List<String> notes = new ArrayList<>();
        if (req.horizon() == null || req.horizon().isBlank()) {
            notes.add(intent == StrategyIntent.INCOME
                    ? "No horizon was supplied to this internal engine call; using an explicit 30-session income cycle. Product decision routes still require the user-owned horizon to be persisted before ranking."
                    : "No horizon was supplied to this internal engine call; using the month analysis bucket. Product decision routes still require an explicit persisted horizon.");
        }
        // Buying shares at a discount commits the full purchase price by design — a cash-secured
        // put reserves strike x 100. Capping that by a small risk-% would reject every candidate,
        // so the ACQUIRE flow caps by available cash instead (unless the user set explicit limits).
        if (intent == StrategyIntent.ACQUIRE
                && req.maxRiskPctOfAccount() == null && req.maxLossCents() == null) {
            budget = buyingPowerCents;
            notes.add("Acquire flow: capital is capped by your buying power, not the risk-mode budget — "
                    + "a cash-secured put sets aside the full purchase price (that IS the design)");
        }
        List<Rejection> rejected = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();

        SymbolReady ready = preflightSymbol(symbol, worldId, notes);
        if (ready == null) {
            return new Result(symbol, thesis.name(), horizon, mode.name(), intent.name(), budget, null, List.of(), rejected, notes, DISCLAIMER);
        }
        var lane = ready.lane();
        Quote quote = ready.quote();
        List<LocalDate> expirations = ready.expirations();
        java.time.Instant laneNow = ready.laneNow();
        LocalDate today = ready.today();
        // DYNAMIC EXPIRY (engine remediation): the horizon is an ANCHOR, not a rigid bucket. Gather
        // the liquid, executable-chain expirations near it and let each family pick the one where it
        // is most profitable — instead of forcing one quantized date whose chain may be empty/thin,
        // which silently produced NOTHING for ~half of observed symbols.
        int anchorDays = horizonAnchorCalendarDays(horizon);
        if (anchorDays == 0 && !allow0dte) {
            notes.add("0DTE horizon requested but same-day expiration is disabled (allow0dte=false); using the nearest expiration instead");
        }
        List<ExpiryCtx> contexts = expiryContexts(symbol, worldId, expirations, anchorDays, today, laneNow, lane,
                allow0dte, MAX_EXPIRY_CANDIDATES);
        if (contexts.isEmpty()) {
            notes.add("The " + lane + " market has no analyzable same-lane option chain for " + symbol
                    + " near a " + anchorDays + "-day horizon — nearby expirations were empty,"
                    + " one-sided, or belonged to another market lane.");
            return new Result(symbol, thesis.name(), horizon, mode.name(), intent.name(), budget,
                    null, List.of(), rejected, notes, DISCLAIMER);
        }
        // The anchor context drives the expiry-independent setup (spot, teaching examples, notes).
        // Every viable package from the nearby expiry set survives construction. The existing
        // DecisionPolicy evaluates the complete field and only then chooses one package per family;
        // construction must not pre-empt that evidence- and after-cost-aware judgment.
        ExpiryCtx anchorCtx = contexts.get(0);
        OptionChain chain = anchorCtx.chain();
        OptionChain farChain = anchorCtx.farChain();
        double riskFreeRate = anchorCtx.riskFreeRate();
        LocalDate near = anchorCtx.near();
        BigDecimal spot = anchorCtx.spot();
        // One canonical event receipt owns candidate timing. Generated worlds never borrow it.
        EventService.EventEvidence eventEvidence = lane == io.liftandshift.strikebench.market.MarketLane.OBSERVED
                ? events.earnings(symbol) : events.unavailableForContext(symbol,
                    "simulated and Demo candidates do not borrow Observed issuer events");

        // Intent-flow context: hold-based intents can write against shares the user already owns.
        StrategyBuilder.AssignmentAppetite appetite = StrategyBuilder.AssignmentAppetite.parse(
                holdings == null ? null : holdings.assignmentPreference());
        boolean holdBasedIntent = intent == StrategyIntent.EXIT || intent == StrategyIntent.HEDGE;
        boolean sharesHeld = freeShares >= 100 && (holdBasedIntent
                || (intent == StrategyIntent.INCOME && holdings != null));
        if (holdBasedIntent && freeShares < 100) {
            notes.add("No eligible held shares of " + symbol + " — "
                    + (intent == StrategyIntent.EXIT ? "an exit" : "a hedge")
                    + " acts on a position you already own; candidates that would require buying shares are withheld.");
        }
        if (intent == StrategyIntent.EXIT && targetPrice != null && spot != null
                && targetPrice.compareTo(spot) < 0) {
            if (!appetite.allowsItm()) {
                notes.add("Your sell-at price is already below today's price — selling the shares "
                        + "honors it right now. A PAID EXIT (selling an in-the-money call at your "
                        + "level, harvesting its extrinsic on the way out) is withheld because this "
                        + (appetite == StrategyBuilder.AssignmentAppetite.UNDECLARED
                            ? "Plan has no assignment-consent declaration; choose accept, prefer, or seek to see it. "
                            : "Plan declares assignment: avoid; switch to accept, prefer, or seek to see it. ")
                        + "Standard above-market strikes are shown instead.");
            } else {
                notes.add("Your sell-at price is already below today's price, so a PAID EXIT is "
                        + "included: the call is sold IN the money at your declared level — "
                        + "assignment converts the shares at no worse than your floor plus the "
                        + "harvested extrinsic. Most of that premium is intrinsic (your own stock "
                        + "value coming back), and in-the-money short calls carry American-style "
                        + "early-assignment risk, highest near ex-dividend dates.");
            }
        }
        if (intent == StrategyIntent.ACQUIRE && targetPrice != null && spot != null
                && targetPrice.compareTo(spot) > 0) {
            if (!appetite.allowsItm()) {
                notes.add("Your target buy price is above today's price — you could simply buy the "
                        + "shares now. A PAID ENTRY (selling an in-the-money put at your level) is "
                        + "withheld because this "
                        + (appetite == StrategyBuilder.AssignmentAppetite.UNDECLARED
                            ? "Plan has no assignment-consent declaration; choose accept, prefer, or seek to see it. "
                            : "Plan declares assignment: avoid; switch to accept, prefer, or seek to see it. ")
                        + "Standard discounts are shown instead.");
            } else {
                notes.add("Your target buy price is above today's price, so a PAID ENTRY is "
                        + "included: the put is sold IN the money at your level — near-certain "
                        + "assignment delivers the shares at strike minus premium (below today's "
                        + "price by the harvested extrinsic); if the shares run away you keep the "
                        + "premium instead. Most of that premium is intrinsic (your own cash cycling "
                        + "back), and only the extrinsic is true harvest.");
            }
        }
        // The declared target carries its MEANING to the builders: sell-at for EXIT, buy-at for
        // ACQUIRE, protect-down-to for HEDGE. Every other intent passes no target at all — an
        // untyped price leaking into INCOME collapsed the covered-call/CSP delta ladder and, on
        // the wrong side of spot, sold in-the-money calls at the user's own buy/protect level.
        StrategyBuilder.TargetRole targetRole = switch (intent) {
            case EXIT -> StrategyBuilder.TargetRole.SELL_AT;
            case ACQUIRE -> StrategyBuilder.TargetRole.BUY_AT;
            case HEDGE -> StrategyBuilder.TargetRole.PROTECT_TO;
            default -> StrategyBuilder.TargetRole.NONE;
        };
        StrategyBuilder.BuildHints hints = new StrategyBuilder.BuildHints(
                targetRole == StrategyBuilder.TargetRole.NONE ? null : targetPrice, sharesHeld,
                intent == StrategyIntent.INCOME, targetRole, appetite);

        for (StrategyFamily family : StrategyFamily.values()) {
            if (intent == StrategyIntent.DIRECTIONAL) {
                // A directional bet IS a thesis, so the declared view hard-selects the structure side.
                if (!family.fits(thesis)) continue;
                // ...but a directional scan expresses a market VIEW with option structures. Share-backed
                // and cash-secured families (covered calls, cash-secured puts, protective puts/collars)
                // are about holdings, income, or protection — not a directional bet — and must never
                // surface here even when they fit the view (a protective collar is not a directional
                // idea; it was ranking as the top "directional" allocation). Pure-option view
                // expressions — spreads, condors, butterflies, calendars, diagonals — stay.
                if (family.requiresLongStock() || family == StrategyFamily.CASH_SECURED_PUT) continue;
            } else {
                // OBJECTIVE FLOWS (income / acquire / exit / hedge): pick families by PURPOSE. The
                // market view is a RANKING TILT and a per-candidate teaching note here, NEVER a
                // catalog gate — matching the product contract that the view is "only meaningful for
                // directional or hedging objectives" and "conditions ranking." Hard-filtering an
                // objective flow on an explicit view collapsed the educational fan to a single trade
                // (e.g. "earn income · bearish" surfaced ONLY a bear call spread, hiding the condor,
                // butterfly, calendars and the credit put spread). Offer every intent-serving family;
                // the coherence gate below keeps each offered structure honest to the objective.
                if (!family.servesIntent(intent) && !family.blockedByDefault()) continue;
                if (family.blockedByDefault() && !family.servesIntent(intent)) continue;
            }
            // HEDGE/EXIT act on an existing holding. With no eligible free shares, a share-backed
            // family would have to INVENT a 100-share purchase (a buy-write is neither a hedge nor an
            // exit) — withhold it and name the reason instead of silently fabricating the holding.
            if (holdBasedIntent && !sharesHeld && family.requiresLongStock()) {
                rejected.add(new Rejection(family.name(), family.display(), List.of(
                        "No eligible held shares of " + symbol + " to " + intent.name().toLowerCase()
                                + "; this structure would require buying shares, which a hedge or exit must not fabricate.")));
                continue;
            }
            // RISK MODE IS A BUDGET, NOT A COMPLEXITY LADDER (risk/experience decoupling): every
            // mode sees the SAME defined-risk catalog — the actual position's max loss, EV, tail,
            // liquidity and the DecisionPolicy decide what ranks. A diagonal is not automatically
            // riskier than a long call.
            if (req.allowedStrategies() != null && !req.allowedStrategies().isEmpty()
                    && req.allowedStrategies().stream().noneMatch(s -> s.equalsIgnoreCase(family.name()))) {
                continue;
            }

            // Complete catalog accounting: undefined-risk families are legitimate structures to
            // study, but they are never auto-recommendations. Name every applicable exclusion in
            // rejected[] instead of silently omitting families whose builder deliberately returns
            // null (short straddles/strangles). This lets clients distinguish "not suitable" from
            // "the engine forgot this strategy" without weakening the safety policy.
            if (family.blockedByDefault()) {
                rejected.add(new Rejection(family.name(), family.display(), List.of(
                        family.automaticBlockReason())));
                continue;
            }

            // DYNAMIC EXPIRY: build and screen this family across every liquid candidate expiration.
            // Do not collapse the packages here using raw EV/max-loss: that happens before costs,
            // realized-volatility evidence, objective fit, tail risk, and evidence quality exist.
            // The existing DecisionPolicy evaluates this complete set and chooses the family's
            // representative afterward. The first anchor-nearest rejection remains the teaching
            // reason only when no expiry produces a viable package.
            boolean builtOnHeldShares = sharesHeld && family.requiresLongStock();
            List<Candidate> familyCandidates = new ArrayList<>();
            Rejection firstRejection = null;
            for (ExpiryCtx ctx : contexts) {
                List<StrategyBuilder.Built> builtAlternatives = StrategyBuilder.buildAlternatives(
                        family, ctx.chain(), ctx.farChain(), ctx.spot(), hints);
                for (StrategyBuilder.Built built : builtAlternatives) {

                    // A structure whose computed worst case is <= $0 is a quote-integrity failure.
                    if (!family.multiExpiration()) {
                        PayoffCurve integrity = PayoffCurve.of(built.legs(), 1);
                        if (!integrity.maxLossUnbounded() && integrity.maxLossCents() <= 0) {
                            if (firstRejection == null) firstRejection = new Rejection(family.name(), family.display(),
                                    List.of("Priced as risk-free by the current quotes — impossible; stale or crossed data, skipped"));
                            continue;
                        }
                    }

                    long coverSharesPerUnit = builtOnHeldShares
                            ? Math.max(0, io.liftandshift.strikebench.strategy.CoverageCheck.callCoverSharesNeeded(built.legs()))
                            : 0;
                    LocalDate packageEnd = built.legs().stream().filter(leg -> !leg.isStock())
                            .map(Leg::expiration).max(LocalDate::compareTo).orElse(null);
                    boolean earningsSoon = eventEvidence.available() && packageEnd != null
                            && !eventEvidence.confidenceStart().isAfter(packageEnd)
                            && !eventEvidence.confidenceEnd().isBefore(today);
                    if (avoidEarnings && earningsSoon) {
                        if (firstRejection == null) {
                            firstRejection = new Rejection(family.name(), family.display(),
                                    List.of(earningsConstraintReason(eventEvidence, packageEnd)));
                        }
                        continue;
                    }
                    Verdict verdict = Guardrails.checkForAnalysis(new Guardrails.Proposal(
                            family, built.legs(), 1, built.quotes(), ctx.spot(), ctx.chain().freshness(), today,
                            buyingPowerCents, false, earningsSoon, false, coverSharesPerUnit));
                    if (verdict.blocked()) {
                        if (firstRejection == null) firstRejection = new Rejection(family.name(), family.display(), verdict.blockReasons());
                        continue;
                    }

                    CandidateProbe probe = new CandidateProbe();
                    // A direct acquisition may use the declared purchase capital. Capped-risk
                    // substitutes remain sized by the ordinary Plan risk budget; otherwise a
                    // three-lot share request could quietly scale a vertical to the whole account.
                    long familyBudget = intent == StrategyIntent.ACQUIRE
                            && !family.requiresLongStock() && family != StrategyFamily.CASH_SECURED_PUT
                            ? riskBudget : budget;
                    Candidate candidate = toCandidate(family, built, verdict, ctx.spot(), today, familyBudget, buyingPowerCents,
                            ctx.chain().freshness(), thesis, intent, holdings,
                            builtOnHeldShares ? coverSharesPerUnit : 0, builtOnHeldShares ? freeShares : 0,
                            quote, ctx.riskFreeRate(), laneNow, lane, probe);
                    if (candidate == null) {
                        if (firstRejection == null) firstRejection = new Rejection(family.name(), family.display(),
                                List.of(probe.reason != null ? probe.reason
                                        : "The current option book cannot produce an executable one-lot package"));
                        continue;
                    }
                    // OBJECTIVE-COHERENCE GATE: a structure whose economics contradict the declared intent
                    // is never a viable recommendation for it — the engine must not offer a "pay-to-earn-
                    // income" debit or a can't-profit package. (This is the offer-time enforcement of the
                    // same carry/coherence idea the eval layer already annotates on a selected position.)
                    String incoherence = intentIncoherence(intent, family, candidate);
                    if (incoherence != null) {
                        if (firstRejection == null) firstRejection = new Rejection(family.name(), family.display(), List.of(incoherence));
                        continue;
                    }
                    String viability = packageViability(family, candidate);
                    if (viability != null) {
                        if (firstRejection == null) firstRejection = new Rejection(
                                family.name(), family.display(), List.of(viability));
                        continue;
                    }
                    if (candidate.confidence() < minConfidence) {
                        if (firstRejection == null) firstRejection = new Rejection(family.name(), family.display(),
                                List.of(String.format("Confidence %.2f is below your minimum %.2f", candidate.confidence(), minConfidence)));
                        continue;
                    }
                    String filterReason = failsFilter(candidate, filters, probe.marketCrashLossCents);
                    if (filterReason != null) {
                        if (firstRejection == null) firstRejection = new Rejection(family.name(), family.display(), List.of(filterReason));
                        continue;
                    }
                    familyCandidates.add(candidate);
                }
            }
            if (!familyCandidates.isEmpty()) {
                candidates.addAll(familyCandidates);
            } else if (firstRejection != null) {
                rejected.add(firstRejection);
            } else {
                // A family can be valid in the catalog but impossible on this exact surface (for
                // example, no matching far expiration for a calendar or no executable protective
                // wing). Preserve that distinction in the response instead of making the family
                // disappear, which clients and users reasonably interpret as incomplete coverage.
                rejected.add(new Rejection(family.name(), family.display(), List.of(
                        "No canonical " + family.display().toLowerCase(Locale.ROOT)
                                + " package could be built from the available same-lane contracts near this horizon.")));
            }
        }

        // Always show a blocked undefined-risk example for education, even if not requested
        if (rejected.stream().noneMatch(r -> r.strategy().equals(StrategyFamily.NAKED_CALL.name()))) {
            StrategyBuilder.Built naked = StrategyBuilder.build(StrategyFamily.NAKED_CALL, chain, farChain, spot);
            if (naked != null) {
                Verdict v = Guardrails.checkForAnalysis(new Guardrails.Proposal(StrategyFamily.NAKED_CALL, naked.legs(), 1,
                        naked.quotes(), spot, chain.freshness(), today, buyingPowerCents, false, false, false));
                rejected.add(new Rejection(StrategyFamily.NAKED_CALL.name(), StrategyFamily.NAKED_CALL.display(),
                        v.blockReasons().isEmpty() ? List.of("Undefined risk — blocked by default") : v.blockReasons()));
            }
        }

        // Return the complete, bounded construction field. StrategyBuilder preserves materially
        // different short-boundary and wing-width strata; DecisionPolicy remains the sole owner of
        // recommendation ranking and endorsement across those exact packages.
        if (candidates.isEmpty()) notes.add("No strategy passed the risk screens for this combination — try a wider risk budget or different horizon");
        return new Result(symbol, thesis.name(), horizon, mode.name(), intent.name(), budget,
                spot.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValue(), candidates, rejected, notes, DISCLAIMER);
    }

    /**
     * A STRIKE LADDER for the hold-based intents — the intent-native view: several rungs of
     * the same structure at different strikes, so "buy at a discount" reads like naming your
     * price, "sell at a target" like picking your exit, "protect" like insurance quotes.
     * Every rung is a full Candidate (same executable pricing, same honesty metrics) and can
     * be sent straight to the ticket.
     */
    public record LadderResult(String symbol, String intent, List<Candidate> rungs,
                               List<String> notes, String disclaimer) {}

    public LadderResult ladder(Request req, long buyingPowerCents) {
        return ladder(req, buyingPowerCents, null);
    }

    /** World-aware twin of recommend(req, bp, worldId) — same CALL-scoped discipline. */
    public LadderResult ladder(Request req, long buyingPowerCents, String worldId) {
        StrategyIntent intent = StrategyIntent.parse(req.intent());
        StrategyFamily family = switch (intent) {
            case ACQUIRE -> StrategyFamily.CASH_SECURED_PUT;
            case EXIT -> StrategyFamily.COVERED_CALL;
            case HEDGE -> StrategyFamily.PROTECTIVE_PUT;
            default -> throw new IllegalArgumentException(
                    "Ladders exist for acquire, exit, and hedge — '" + req.intent() + "' has no strike ladder");
        };
        String symbol = Symbol.normalize(req.symbol());
        List<String> notes = new ArrayList<>();
        Holdings holdings = req.holdings();
        Filters filters = req.filters() == null ? new Filters(null, null, null, null) : req.filters();
        int freeShares = holdings != null && holdings.sharesOwned() != null ? Math.max(0, holdings.sharesOwned()) : 0;
        boolean sharesHeld = freeShares >= 100 && intent != StrategyIntent.ACQUIRE;

        // HEDGE and EXIT act on shares you already own. With fewer than 100 free shares a covered-call
        // / protective-put ladder could only be built by fabricating a 100-share stock purchase
        // (StrategyBuilder inserts Leg.stock(BUY) whenever sharesHeld is false). This is a SEMANTIC
        // rejection — it holds regardless of buying power, quote, or chain — so it runs BEFORE
        // preflightSymbol(): an impossible request must spend zero provider calls (no quote, no chain,
        // no history) and no external allowance. ACQUIRE (a cash-secured put) needs no held shares.
        boolean holdBasedIntent = intent == StrategyIntent.EXIT || intent == StrategyIntent.HEDGE;
        if (holdBasedIntent && freeShares < 100) {
            notes.add("This " + intent.name().toLowerCase() + " ladder starts from shares you own. With no "
                    + "eligible held shares of " + symbol + ", a strike ladder here would have to manufacture a "
                    + "100-share purchase, so it is withheld — buy practice shares first (Acquire), or build the "
                    + "full package in Structure.");
            return new LadderResult(symbol, intent.name(), List.of(), notes, DISCLAIMER);
        }

        SymbolReady ready = preflightSymbol(symbol, worldId, notes);
        if (ready == null) {
            return new LadderResult(symbol, intent.name(), List.of(), notes, DISCLAIMER);
        }
        var lane = ready.lane();
        Quote quote = ready.quote();
        List<LocalDate> expirations = ready.expirations();
        java.time.Instant ladderNow = ready.laneNow();
        LocalDate today = ready.today();
        String horizon = effectiveHorizon(req.horizon(), intent);
        if (req.horizon() == null || req.horizon().isBlank()) {
            notes.add(intent == StrategyIntent.INCOME
                    ? "No horizon was supplied; using a 30-session income cycle."
                    : "No horizon was supplied; using the month analysis bucket.");
        }
        LocalDate near = pickExpiration(expirations, horizon, today, false, ladderNow, notes);
        OptionChain chain = near == null ? null : market.chain(symbol, near, worldId).orElse(null);
        if (chain == null || chain.isEmpty()) {
            notes.add("Option chain unavailable for " + symbol);
            return new LadderResult(symbol, intent.name(), List.of(), notes, DISCLAIMER);
        }
        if (!chain.evidence().usableIn(lane)) {
            notes.add("The " + lane + " market has no same-lane option chain for " + symbol);
            return new LadderResult(symbol, intent.name(), List.of(), notes, DISCLAIMER);
        }
        double riskFreeRate = market.riskFreeRateQuote(
                (int) Math.max(1, ChronoUnit.DAYS.between(today, near)), worldId).annualRate();
        BigDecimal spot = chain.underlyingPrice();
        EventService.EventEvidence eventEvidence =
                lane == io.liftandshift.strikebench.market.MarketLane.OBSERVED
                    ? events.earnings(symbol)
                    : events.unavailableForContext(symbol,
                        "simulated and Demo ladders do not borrow Observed issuer events");
        boolean earningsSoon = eventEvidence.available()
                && !eventEvidence.confidenceStart().isAfter(near)
                && !eventEvidence.confidenceEnd().isBefore(today);
        if (Boolean.TRUE.equals(req.avoidEarnings()) && earningsSoon) {
            notes.add(earningsConstraintReason(eventEvidence, near));
            return new LadderResult(symbol, intent.name(), List.of(), notes, DISCLAIMER);
        }
        // Use the same budget calculation as recommend(). ACQUIRE is the one explicit exception:
        // its cash-secured purchase commitment is the product and is disclosed as such. EXIT and
        // HEDGE never inflate the selected per-idea budget merely to manufacture a rung.
        RiskMode mode = RiskMode.parse(req.riskMode());
        long budget = intent == StrategyIntent.ACQUIRE && req.maxRiskPctOfAccount() == null && req.maxLossCents() == null
                ? buyingPowerCents
                : RiskBudgetPolicy.requestBudgetCents(
                        mode, buyingPowerCents, req.maxRiskPctOfAccount(), req.maxLossCents());

        // Rung strikes: EXIT climbs above spot, ACQUIRE/HEDGE step below it. An explicitly
        // consenting assignment appetite additionally opens a few IN-the-money rungs on the
        // assignment side (paid exits below spot, paid entries above it) — deliberate, labeled,
        // never by leak.
        StrategyBuilder.AssignmentAppetite rungAppetite = StrategyBuilder.AssignmentAppetite.parse(
                req.holdings() == null ? null : req.holdings().assignmentPreference());
        List<BigDecimal> strikes = new ArrayList<>();
        List<BigDecimal> all = chain.strikes();
        if (intent == StrategyIntent.EXIT) {
            for (BigDecimal k : all) if (k.compareTo(spot) >= 0 && strikes.size() < 6) strikes.add(k);
            if (rungAppetite.allowsItm()) {
                int added = 0;
                for (int i = all.size() - 1; i >= 0 && added < 3; i--) {
                    if (all.get(i).compareTo(spot) < 0) { strikes.add(all.get(i)); added++; }
                }
            }
        } else {
            for (int i = all.size() - 1; i >= 0 && strikes.size() < 6; i--) {
                if (all.get(i).compareTo(spot) <= 0) strikes.add(all.get(i));
            }
            if (intent == StrategyIntent.ACQUIRE && rungAppetite.allowsItm()) {
                int added = 0;
                for (BigDecimal k : all) {
                    if (k.compareTo(spot) > 0 && added < 3) { strikes.add(k); added++; }
                }
            }
        }
        List<Candidate> rungs = new ArrayList<>();
        List<String> filterExamples = new ArrayList<>();
        int filteredRungs = 0;
        for (BigDecimal k : strikes) {
            StrategyBuilder.TargetRole rungRole = switch (intent) {
                case EXIT -> StrategyBuilder.TargetRole.SELL_AT;
                case ACQUIRE -> StrategyBuilder.TargetRole.BUY_AT;
                case HEDGE -> StrategyBuilder.TargetRole.PROTECT_TO;
                default -> StrategyBuilder.TargetRole.NONE;
            };
            StrategyBuilder.Built built = StrategyBuilder.build(family, chain, null, spot,
                    new StrategyBuilder.BuildHints(k, sharesHeld, false, rungRole, rungAppetite));
            if (built == null) continue;
            // Only accept the rung whose short/long strike is EXACTLY k (target snapping can dedupe)
            boolean exact = built.legs().stream().anyMatch(l -> !l.isStock() && l.strike().compareTo(k) == 0);
            if (!exact) continue;
            long coverShares = sharesHeld
                    ? Math.max(0, io.liftandshift.strikebench.strategy.CoverageCheck.callCoverSharesNeeded(built.legs())) : 0;
            Verdict verdict = Guardrails.checkForAnalysis(new Guardrails.Proposal(
                    family, built.legs(), 1, built.quotes(), spot, chain.freshness(), today,
                    buyingPowerCents, false, earningsSoon, false, coverShares));
            if (verdict.blocked()) {
                filteredRungs++;
                if (filterExamples.size() < 3) {
                    filterExamples.add("$" + k.stripTrailingZeros().toPlainString() + ": "
                            + String.join("; ", verdict.blockReasons()));
                }
                continue;
            }
            CandidateProbe probe = new CandidateProbe();
            Candidate c = toCandidate(family, built, verdict, spot, today, budget,
                    buyingPowerCents, chain.freshness(), StrategyFamily.Thesis.NEUTRAL,
                    intent, holdings, sharesHeld ? coverShares : 0, sharesHeld ? freeShares : 0,
                    quote, riskFreeRate, ladderNow, lane, probe);
            if (c == null) continue;
            String filterReason = failsFilter(c, filters, probe.marketCrashLossCents);
            if (filterReason != null) {
                filteredRungs++;
                if (filterExamples.size() < 3) {
                    filterExamples.add("$" + k.stripTrailingZeros().toPlainString() + ": " + filterReason);
                }
                continue;
            }
            if (rungs.stream().noneMatch(r -> r.label().equals(c.label()))) rungs.add(c);
        }
        if (filteredRungs > 0) {
            notes.add(filteredRungs + " ladder rung" + (filteredRungs == 1 ? " was" : "s were")
                    + " excluded by your selected limits");
            filterExamples.forEach(example -> notes.add("Excluded " + example));
        }
        if (rungs.isEmpty()) {
            // Hold-based intents without eligible shares are already rejected up front (semantic gate),
            // so an empty ladder here is only ever a filter or a strike/budget shortfall.
            if (filteredRungs > 0) {
                notes.add("No ladder rung passed every selected limit");
            } else {
                notes.add("No tradable strikes fit this ladder and its stated budget right now");
            }
        }
        if (sharesHeld) notes.add("Sized against your " + freeShares + " free shares");
        return new LadderResult(symbol, intent.name(), rungs, notes, DISCLAIMER);
    }

    /** The lane clock, a usable quote, its expirations, and today's lane date — the shared symbol
     *  readiness both recommend() and ladder() need. Null means "not tradable in this lane"; the
     *  reason is appended to {@code notes}. One determination, so the two surfaces cannot drift. */
    private record SymbolReady(java.time.Instant laneNow, LocalDate today,
                               io.liftandshift.strikebench.market.MarketLane lane, Quote quote,
                               List<LocalDate> expirations) {}

    private SymbolReady preflightSymbol(String symbol, String worldId, List<String> notes) {
        // ONE CLOCK PER LANE: a simulated session's clock is always in-session while it runs — the
        // observed market being closed says nothing about THIS market (review P2).
        java.time.Instant laneNow = market.laneNow(worldId, clock);
        if (worldId == null && !MarketHours.isRegularSession(laneNow)) {
            notes.add("The market is closed — prices and strikes here are anchored to the PRIOR CLOSE, "
                    + "not a live quote, and can shift at the next open.");
        }
        io.liftandshift.strikebench.market.MarketLane lane = market.lane(worldId);
        Quote quote = market.quote(symbol, worldId).orElse(null);
        if (quote == null) { notes.add(missingMarketDataNote(lane, symbol)); return null; }
        if (!quote.evidence().usableIn(lane)) {
            notes.add("No " + lane + "-lane quote is available for " + symbol + "; refusing to substitute "
                    + quote.evidence().provenance() + " data from " + quote.evidence().source());
            return null;
        }
        List<LocalDate> expirations = market.expirations(symbol, worldId);
        if (!quote.optionable() || expirations.isEmpty()) {
            notes.add(symbol + " has no listed options (mutual funds and some securities cannot be traded with options)");
            return null;
        }
        return new SymbolReady(laneNow, LocalDate.ofInstant(laneNow, MarketHours.EASTERN),
                lane, quote, expirations);
    }

    private static String missingMarketDataNote(io.liftandshift.strikebench.market.MarketLane lane,
                                                String symbol) {
        if (lane == io.liftandshift.strikebench.market.MarketLane.OBSERVED) {
            return "No market data is available for " + symbol + " in the OBSERVED-lane"
                    + "; DEMO and SIMULATED substitutes are disabled. Choose an explicit market lane or add data.";
        }
        return "No market data is available for " + symbol + " in the " + lane + " lane";
    }

    // ---- Scoring & explanation ----

    private Candidate toCandidate(StrategyFamily family, StrategyBuilder.Built built, Verdict verdict, BigDecimal spot,
                                  LocalDate today, long budget, long buyingPowerCents, Freshness freshness,
                                  StrategyFamily.Thesis thesis, StrategyIntent intent, Holdings holdings,
                                  long coverSharesPerUnit, int freeShares, Quote underlyingQuote,
                                  double riskFreeRate, java.time.Instant laneNow,
                                  io.liftandshift.strikebench.market.MarketLane lane,
                                  CandidateProbe probe) {
        // One captured book and one price owner. ANALYSIS retains honest EOD/model-mark ideas,
        // but the returned receipt withdraws every executable claim unless every leg's evidence
        // and bid/ask side are executable in this lane.
        List<ExecutablePackagePricer.LegBook> priceInputs = new ArrayList<>(built.legs().size());
        for (int i = 0; i < built.legs().size(); i++) {
            Leg leg = built.legs().get(i);
            if (leg.isStock()) {
                priceInputs.add(ExecutablePackagePricer.LegBook.from(leg, underlyingQuote));
            } else {
                OptionQuote quote = i < built.quotes().size() ? built.quotes().get(i) : null;
                priceInputs.add(ExecutablePackagePricer.LegBook.from(leg, quote));
            }
        }
        ExecutablePackagePricer.Book pricedBook = ExecutablePackagePricer.price(
                priceInputs, lane, ExecutablePackagePricer.Policy.ANALYSIS);
        if (!pricedBook.priced()) {
            return candidateFailure(probe, pricedBook.unavailableReason());
        }
        built = new StrategyBuilder.Built(pricedBook.pricedLegs(), built.quotes(), built.label());
        freshness = pricedBook.freshness();
        PayoffCurve unitCurve = PayoffCurve.of(built.legs(), 1);
        long unitEntryNet = unitCurve.entryNetPremiumCents();
        boolean multiExp = family.multiExpiration();

        // Held-shares candidates carry option legs only; risk display and POP come from the
        // COMBINED position (legs + the held lot at today's price), while budget/reserve math
        // uses the trade's INCREMENTAL cash risk (a covered call adds none; a hedge costs its debit).
        boolean onHeldShares = freeShares > 0 && family.requiresLongStock();
        long displaySharesPerUnit = onHeldShares
                ? Math.max(coverSharesPerUnit,
                        io.liftandshift.strikebench.strategy.CoverageCheck.shareContextUnitsNeeded(built.legs()))
                : 0;
        if (onHeldShares && displaySharesPerUnit <= 0) displaySharesPerUnit = 1;
        if (onHeldShares && (displaySharesPerUnit > Integer.MAX_VALUE || freeShares < displaySharesPerUnit)) {
            return candidateFailure(probe, "One package needs " + displaySharesPerUnit
                    + " free shares, but this account has " + freeShares);
        }
        List<Leg> unitDisplayLegs = built.legs();
        if (onHeldShares) {
            unitDisplayLegs = new ArrayList<>(built.legs());
            unitDisplayLegs.add(Leg.stockShares(LegAction.BUY, Math.toIntExact(displaySharesPerUnit), spot));
        }
        PayoffCurve unitDisplayCurve = onHeldShares ? PayoffCurve.of(unitDisplayLegs, 1) : unitCurve;
        long heldSharePutObligation = onHeldShares
                ? io.liftandshift.strikebench.strategy.CapitalRequirement
                        .heldSharePutObligationCents(built.legs(), 1)
                : 0L;

        long unitMaxLoss;
        Long unitMaxProfit;
        Long unitCombinedMaxLoss = null;
        if (multiExp) {
            if (unitEntryNet >= 0) return candidateFailure(probe,
                    "A multi-expiration credit cannot be bounded honestly");
            unitMaxLoss = -unitEntryNet;
            unitMaxProfit = null;
        } else if (onHeldShares) {
            if (unitDisplayCurve.maxLossUnbounded()) return candidateFailure(probe,
                    "The available shares do not bound this structure's risk");
            // Held shares cover short CALLS; they do not secure a short PUT. For a covered
            // strangle the incremental loss at a zero underlying is the put strike obligation
            // less the whole package credit. Treating every held-share package as debit-only
            // made this obligation disappear from max loss and buying power.
            unitMaxLoss = Math.max(0L,
                    Math.subtractExact(heldSharePutObligation, unitEntryNet));
            unitCombinedMaxLoss = unitDisplayCurve.maxLossCents();
            unitMaxProfit = unitDisplayCurve.maxProfitUnbounded() ? null : unitDisplayCurve.maxProfitCents();
        } else {
            if (unitCurve.maxLossUnbounded()) return candidateFailure(probe, "Theoretical loss is unlimited");
            unitMaxLoss = unitCurve.maxLossCents();
            unitMaxProfit = unitCurve.maxProfitUnbounded() ? null : unitCurve.maxProfitCents();
        }
        // CAPITAL is not RISK. A cash-secured put or a buy-write covered call deploys capital you must
        // HOLD — the strike cash you set aside to buy the shares, or the shares themselves — not a small
        // slice you are willing to LOSE. Gating these by the per-idea RISK budget is the wrong lens and
        // silently hid every one of them (a $500 name's cash-secured put needs ~$48k of collateral, far
        // above a $5k risk cap), so "sell puts to buy at a discount" and "take profit on holdings" never
        // appeared as income. Collateral-based structures are gated by BUYING POWER (what actually
        // constrains them), like the ACQUIRE flow, and default to a single lot so one idea never quietly
        // commits the whole account. Defined-risk structures (spreads, condors, butterflies) keep the
        // risk budget, where max loss genuinely IS the capital at risk.
        // Applies ONLY where deploying NEW capital is the point: INCOME (write a cash-secured put or a
        // buy-write covered call to earn premium) and ACQUIRE (set cash aside to buy at a discount).
        // EXIT and HEDGE operate on shares you ALREADY hold — a buy-write there is incoherent (you can't
        // "exit" shares you don't own), so without held shares those stay risk-budget-gated and drop out.
        // A DIRECTIONAL scan may also surface a cash-secured put (it fits a bullish view), but there it
        // is a directional bet and must respect the per-idea risk budget, not the whole account.
        boolean collateralBased = !onHeldShares
                && (intent == StrategyIntent.INCOME || intent == StrategyIntent.ACQUIRE)
                && (family.requiresLongStock() || family == StrategyFamily.CASH_SECURED_PUT);
        int qty;
        if (onHeldShares) {
            // A HEDGE is sized to the shares it must cover, never to the risk budget: its debit is
            // a premium PAID for the floor — a disclosed cost, capped only by the buying-power
            // reconciliation below — not a loss appetite. Budget-sizing protection is how a put
            // debit above the per-idea budget "rejected" a hedge the account could afford while
            // the shares it was meant to protect stayed uncovered.
            boolean protectionSizing = intent == StrategyIntent.HEDGE;
            if (!protectionSizing && heldSharePutObligation == 0 && unitMaxLoss > budget) {
                return candidateFailure(probe,
                        "One lot requires " + Money.fmt(unitMaxLoss) + " of incremental risk, above this Plan's "
                                + Money.fmt(budget) + " budget");
            }
            int packagesAvailable = (int) (freeShares / displaySharesPerUnit);
            long byBudget = protectionSizing || heldSharePutObligation > 0 ? packagesAvailable
                    : unitMaxLoss > 0 ? Math.max(1, budget / unitMaxLoss) : packagesAvailable;
            qty = (int) Math.clamp(Math.min((long) packagesAvailable, byBudget), 1, MAX_QTY);
        } else if (collateralBased) {
            int desiredLots = holdings != null && holdings.sharesOwned() != null && holdings.sharesOwned() > 0
                    ? Math.max(1, holdings.sharesOwned() / 100) : 1;
            qty = (int) Math.clamp(desiredLots, 1, MAX_QTY);
        } else {
            if (unitMaxLoss <= 0 || unitMaxLoss > budget) {
                if (unitMaxLoss > budget) return candidateFailure(probe,
                        "One lot risks " + Money.fmt(unitMaxLoss) + ", above this Plan's "
                                + Money.fmt(budget) + " budget");
                unitMaxLoss = Math.max(unitMaxLoss, 1);
            }
            qty = (int) Math.clamp(budget / unitMaxLoss, 1, MAX_QTY);
            if (intent == StrategyIntent.ACQUIRE) {
                // Size to the shares the user actually wants (holdings.sharesOwned doubles as
                // "shares I want to buy" here), defaulting to a single 100-share lot — never
                // silently commit the whole account to stock purchases.
                int desiredLots = holdings != null && holdings.sharesOwned() != null && holdings.sharesOwned() > 0
                        ? Math.max(1, holdings.sharesOwned() / 100) : 1;
                qty = Math.min(qty, Math.min(desiredLots, MAX_QTY));
            }
        }

        // Quantity is reconciled to the same reserve + after-fee buying-power identity published
        // on the final Candidate. This deliberately runs after the risk/intent quantity policy:
        // a flat order fee makes `buyingPower / gross unit cost` capable of sizing one lot too
        // many at a boundary, and a debit-only cash gate misses credit-spread reserve entirely.
        long oneLotBuyingPower = openingBuyingPowerRequired(
                unitMaxLoss, unitEntryNet, built.legs(), 1, onHeldShares);
        while (qty > 0 && openingBuyingPowerRequired(
                unitMaxLoss, unitEntryNet, built.legs(), qty, onHeldShares) > buyingPowerCents) {
            qty--;
        }
        if (qty == 0) {
            return candidateFailure(probe, "One lot needs " + Money.fmt(oneLotBuyingPower)
                    + " of exact opening buying power, above the account's "
                    + Money.fmt(buyingPowerCents) + " buying power");
        }

        PayoffCurve curve = PayoffCurve.of(onHeldShares ? unitDisplayLegs : built.legs(), qty);
        long entryNet = unitEntryNet * qty;
        long maxLoss = unitMaxLoss * qty;
        Long maxProfit = unitMaxProfit == null ? null : unitMaxProfit * qty;
        Long combinedMaxLoss = unitCombinedMaxLoss == null ? null : unitCombinedMaxLoss * qty;
        if (!onHeldShares && family.requiresLongStock()) {
            // The package already contains the purchased stock, so its exact maximum-loss curve is
            // the combined-position receipt. Publish that fact explicitly rather than asking the
            // capital consumer to guess from a missing combined field.
            combinedMaxLoss = maxLoss;
        }
        // A mixed-expiration package has no honest one-date intrinsic payoff. Every other package
        // is valued at the server-owned MARKET_CRASH terminal move through the canonical curve.
        if (probe != null) {
            probe.marketCrashLossCents = multiExp ? null
                    : curve.lossAtStoryCents(spot, ScenarioStory.MARKET_CRASH);
        }

        long optionContracts = Fees.optionContracts(built.legs(), qty);
        Fees.Schedule feeSchedule = Fees.schedule(optionContracts,
                feePerContractCents, feePerOrderCents);
        long openingFees = feeSchedule.openingCents();
        List<String> candidateWarnings = new ArrayList<>(verdict.warnings());
        List<LegView> legViews = new ArrayList<>(built.legs().size());
        for (int i = 0; i < built.legs().size(); i++) {
            OptionQuote quoteReceipt = i < built.quotes().size() ? built.quotes().get(i) : null;
            legViews.add(LegView.of(built.legs().get(i), quoteReceipt));
        }
        if (!pricedBook.executable()) {
            var packageEvidence = pricedBook.evidence();
            candidateWarnings.add("These prices come from " + packageEvidence.provenance() + " "
                    + packageEvidence.age() + " marks (" + packageEvidence.source() + "), which are not "
                    + "an executable book in the " + lane + " market. The package can be studied at this "
                    + "price; it cannot be traded at it until the market quotes it again.");
        }
        PackagePriceReceipt price = pricedBook.receipt(qty, feeSchedule,
                PackagePriceReceipt.FeeSide.OPENING, OrderInstruction.market());
        if (price.grossPackageNetCents() != entryNet) {
            throw new IllegalStateException("candidate payoff and package-price receipt disagree");
        }
        var exactCapital = io.liftandshift.strikebench.strategy.CapitalRequirement.of(
                io.liftandshift.strikebench.strategy.StrategyCatalog.identify(family),
                price, maxLoss, combinedMaxLoss, onHeldShares);
        if (!exactCapital.available()) {
            return candidateFailure(probe, exactCapital.unavailableReason());
        }
        long sizedBuyingPower = openingBuyingPowerRequired(
                unitMaxLoss, unitEntryNet, built.legs(), qty, onHeldShares);
        if (exactCapital.buyingPowerRequiredCents() != sizedBuyingPower) {
            throw new IllegalStateException(
                    "candidate sizing and canonical capital receipt disagree on opening buying power");
        }
        if (exactCapital.buyingPowerRequiredCents() > buyingPowerCents) {
            throw new IllegalStateException(
                    "candidate quantity exceeds buying power after canonical capital reconciliation");
        }
        long optionNetCents = price.optionNetPremiumCents();
        // The disclosure quotes the SAME after-fee cost the protection summary states — one
        // number for one fact, never a pre-fee twin beside an after-fee original.
        long afterFeeCost = price.afterFeeNetCents() == null ? 0
                : Math.max(0, -price.afterFeeNetCents());
        if (onHeldShares && intent == StrategyIntent.HEDGE && afterFeeCost > budget) {
            candidateWarnings.add("Protection is sized to the " + (displaySharesPerUnit * qty)
                    + " shares it covers. Its " + Money.fmt(afterFeeCost)
                    + " cost is a premium paid for the floor, capped by buying power rather than this Plan's "
                    + Money.fmt(budget) + " risk budget.");
        }

        List<String> breakevens;
        io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.Receipt marketImpliedRisk;
        List<Double> capturedIvs = built.quotes().stream()
                .map(quote -> quote == null ? null : quote.iv()).toList();
        io.liftandshift.strikebench.market.OptionTime.Measure packageTime =
                io.liftandshift.strikebench.market.OptionTime.nearest(built.legs(), laneNow);
        boolean ivMissing = built.quotes().stream().filter(Objects::nonNull)
                .map(OptionQuote::iv).noneMatch(Objects::nonNull);
        if (multiExp) {
            breakevens = List.of();
            marketImpliedRisk = io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.Receipt
                    .unavailable("A mixed-expiration package requires supplied-path valuation.");
        } else if (ivMissing) {
            breakevens = curve.breakevens().stream()
                    .map(b -> b.stripTrailingZeros().toPlainString()).toList();
            marketImpliedRisk = io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.Receipt
                    .unavailable("No implied volatility was captured for this exact package.");
        } else {
            breakevens = curve.breakevens().stream().map(b -> b.stripTrailingZeros().toPlainString()).toList();
            double ivAvg = built.quotes().stream().filter(Objects::nonNull).map(OptionQuote::iv)
                    .filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElseThrow();
            List<BigDecimal> shorts = built.legs().stream()
                    .filter(l -> !l.isStock() && l.action() == LegAction.SELL)
                    .map(Leg::strike).filter(Objects::nonNull).distinct().toList();
            marketImpliedRisk = io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.analyze(
                    curve, price, Money.toCents(spot), ivAvg,
                    packageTime,
                    riskFreeRate, shorts);
        }
        Double pop = marketImpliedRisk.pop();

        double liquidity = liquidityScore(built.quotes());
        boolean zeroDte = built.legs().stream().anyMatch(l -> !l.isStock() && l.expiration().equals(today));

        // ---- Intent metrics (short-side expiry-ITM odds, opening-premium rate, effective share price) ----
        Double shortSideExpirationItmProb = RiskNeutralAnalyzer.shortSideExpirationItmProbability(
                built.legs(), capturedIvs, Money.toCents(spot), laneNow, riskFreeRate);
        int minDte = Math.toIntExact(Math.max(0, packageTime.calendarDays()));
        BigDecimal shortCallStrike = built.legs().stream()
                .filter(l -> !l.isStock() && l.action() == LegAction.SELL && l.type() == OptionType.CALL)
                .map(Leg::strike).findFirst().orElse(null);
        BigDecimal shortPutStrike = built.legs().stream()
                .filter(l -> !l.isStock() && l.action() == LegAction.SELL && l.type() == OptionType.PUT)
                .map(Leg::strike).findFirst().orElse(null);
        BigDecimal longPutStrike = built.legs().stream()
                .filter(l -> !l.isStock() && l.action() == LegAction.BUY && l.type() == OptionType.PUT)
                .map(Leg::strike).findFirst().orElse(null);
        long packageShareUnitsPerUnit = built.legs().stream().filter(Leg::isStock)
                .mapToLong(leg -> Math.multiplyExact((long) leg.ratio(), leg.multiplier())).sum();
        if (packageShareUnitsPerUnit <= 0) {
            packageShareUnitsPerUnit = io.liftandshift.strikebench.strategy.CoverageCheck
                    .shareContextUnitsNeeded(built.legs());
        }
        if (packageShareUnitsPerUnit <= 0) packageShareUnitsPerUnit = Leg.SHARES_PER_CONTRACT;
        long netOptionIncomeCents = optionNetCents - openingFees;

        // In-the-money shorts: most of the collected premium is INTRINSIC — the user's own stock
        // value (short call below spot) or their own purchase cash (short put above spot) cycling
        // back. Only the extrinsic is harvest, so the annualized rate and the receipts are stated
        // on the extrinsic whenever intrinsic is present. Grading gross premium here is how a
        // deep-in-the-money sale would masquerade as spectacular income.
        long shortIntrinsicCents = 0;
        long totalShareUnits = Math.multiplyExact(packageShareUnitsPerUnit, (long) qty);
        if (shortCallStrike != null && shortCallStrike.compareTo(spot) < 0) {
            shortIntrinsicCents = Math.addExact(shortIntrinsicCents,
                    Money.centsFromPrice(spot.subtract(shortCallStrike), totalShareUnits));
        }
        if (shortPutStrike != null && shortPutStrike.compareTo(spot) > 0) {
            shortIntrinsicCents = Math.addExact(shortIntrinsicCents,
                    Money.centsFromPrice(shortPutStrike.subtract(spot), totalShareUnits));
        }
        long extrinsicIncomeCents = Math.subtractExact(netOptionIncomeCents, shortIntrinsicCents);
        if (shortIntrinsicCents > 0) {
            boolean itmCall = shortCallStrike != null && shortCallStrike.compareTo(spot) < 0;
            candidateWarnings.add("Assignment-seeking: the short "
                    + (itmCall ? "call is" : "put is") + " in the money. "
                    + Money.fmt(shortIntrinsicCents) + " of the premium is intrinsic — your own "
                    + (itmCall ? "stock value" : "purchase cash") + " returning to you — and only "
                    + Money.fmt(Math.max(0, extrinsicIncomeCents))
                    + " of extrinsic is true harvest. American-style early assignment can arrive "
                    + "any time it is in the money, most likely near ex-dividend dates.");
        }

        // An annualized opening-premium rate is stated ONLY for collateral-backed premium
        // (covered calls, cash-secured puts, collars) where the denominator is named shares or
        // strike cash. Annualizing a narrow condor's max return-on-risk produces four-digit
        // percentages that masquerade as income; defined-risk packages keep a period comparison.
        boolean shareBacked = family == StrategyFamily.COVERED_CALL
                || family == StrategyFamily.CASH_SECURED_PUT
                || family == StrategyFamily.PROTECTIVE_COLLAR;
        Long namedCollateralCents = null;
        if (netOptionIncomeCents > 0 && shareBacked) {
            namedCollateralCents = family == StrategyFamily.CASH_SECURED_PUT && shortPutStrike != null
                    ? Money.centsFromPrice(shortPutStrike, Math.multiplyExact(packageShareUnitsPerUnit, (long) qty))
                    : Money.centsFromPrice(spot, Math.multiplyExact(packageShareUnitsPerUnit, (long) qty));
        }
        Double annualizedOpeningPremiumRatePct = null;
        if (namedCollateralCents != null && namedCollateralCents > 0) {
            // the rate is a HARVEST rate: extrinsic only when the short is in the money
            long harvestCents = shortIntrinsicCents > 0
                    ? Math.max(0, extrinsicIncomeCents) : netOptionIncomeCents;
            Double annualized = packageTime.annualizedSimplePercent(
                    harvestCents, namedCollateralCents);
            annualizedOpeningPremiumRatePct = annualized == null ? null : round2(annualized);
        }
        // Effective share prices are strike +/- NET option premium per share after opening fees —
        // a buy-write's stock purchase must not leak into it (it made "effective sell $10/sh" nonsense once).
        BigDecimal perShareNet = BigDecimal.valueOf(netOptionIncomeCents)
                .divide(BigDecimal.valueOf(Math.multiplyExact(packageShareUnitsPerUnit, (long) qty)),
                        2, java.math.RoundingMode.HALF_UP)
                .movePointLeft(2); // cents -> dollars per share
        String effectivePrice = null;
        if ((family == StrategyFamily.COVERED_CALL || family == StrategyFamily.PROTECTIVE_COLLAR)
                && shortCallStrike != null) {
            effectivePrice = shortCallStrike.add(perShareNet).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
        } else if (family == StrategyFamily.CASH_SECURED_PUT && shortPutStrike != null) {
            effectivePrice = shortPutStrike.subtract(perShareNet).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
        }
        // PREFER_BELOW_BASIS is a declared entry DISCIPLINE: acquisitions whose effective price
        // (strike − premium) lands above the declared cost basis are refused by name, so
        // averaging down stays averaging DOWN. Only the bare entry structure is judged — spreads
        // hedge the commitment away and never claim an effective purchase price here.
        if (intent == StrategyIntent.ACQUIRE && family == StrategyFamily.CASH_SECURED_PUT
                && effectivePrice != null && holdings != null && holdings.costBasisCents() != null
                && StrategyBuilder.AssignmentAppetite.parse(holdings.assignmentPreference())
                        == StrategyBuilder.AssignmentAppetite.PREFER_BELOW_BASIS) {
            long effectiveCents = Money.toCents(new BigDecimal(effectivePrice));
            if (effectiveCents > holdings.costBasisCents()) {
                return candidateFailure(probe, "Below-basis rule: the effective purchase $"
                        + effectivePrice + " sits above your declared " + Money.fmt(holdings.costBasisCents())
                        + " basis — this Plan prefers entries that lower it.");
            }
        }

        double freshScore = switch (freshness) {
            case REALTIME -> 1.0;
            case DELAYED -> 0.85;
            case EOD -> 0.70;
            // FIXTURE is fabricated Demo data, never real-time. It is eligible only in the explicit
            // Demo lane; the haircut keeps educational confidence appropriately below observed data.
            case FIXTURE -> 0.45;
            default -> 0.40;
        };
        double modelConf = multiExp ? 0.4 : ivMissing ? 0.5 : (pop != null ? 0.9 : 0.6);
        double confidence = Math.clamp(0.40 * freshScore + 0.35 * liquidity + 0.25 * modelConf, 0, 1);

        String upside = maxProfit != null
                ? "Max profit " + Money.fmt(maxProfit) + (breakevens.isEmpty() ? "" : " beyond " + String.join(" / ", breakevens))
                : (multiExp ? "Profit depends on volatility staying favorable near the short strike; not a fixed number"
                             : "Uncapped upside past " + (breakevens.isEmpty() ? "the breakeven" : "$" + breakevens.getFirst()));
        String risk = onHeldShares
                ? (family == StrategyFamily.COVERED_STRANGLE
                    ? "Your held shares cover the short call, while the short put adds "
                        + Money.fmt(exactCapital.reserveCents())
                        + " of strike-cash obligation. The opening credit reduces but does not "
                        + "remove that obligation; combined downside from today's share price is "
                        + (combinedMaxLoss == null ? "unavailable"
                            : Money.fmt(combinedMaxLoss))
                        + ". Standard assignment can change shares and cash; the comparison payoff "
                        + "uses cash-equivalent expiry value."
                    : maxLoss > 0
                    ? "Costs " + Money.fmt(maxLoss) + " in premium; your " + (displaySharesPerUnit * qty)
                        + " shares keep their own downside" + (combinedMaxLoss != null
                            ? " — worst case incl. shares from today's price: " + Money.fmt(combinedMaxLoss) : "") + ". Fees come on top."
                    : "No new cash at risk — but your " + (displaySharesPerUnit * qty)
                        + " shares keep their downside, and gains above the short strike go to the call buyer. Fees come on top.")
                : "Max loss " + Money.fmt(maxLoss) + " (" + qty + "x) if the underlying "
                + switch (thesis) {
                    case BULLISH -> "falls and stays below " + (breakevens.isEmpty() ? "the strikes" : "$" + breakevens.getFirst());
                    case BEARISH -> "rises and stays above " + (breakevens.isEmpty() ? "the strikes" : "$" + breakevens.getLast());
                    case NEUTRAL -> "moves far beyond the breakeven range by expiration";
                    case VOLATILE -> "stays near today's price into expiration";
                } + ". Fees come on top.";
        String invalidate = switch (thesis) {
            case BULLISH -> "A close below " + (breakevens.isEmpty() ? "your support level" : "$" + breakevens.getFirst()) + " with time running out would invalidate the idea";
            case BEARISH -> "A close above " + (breakevens.isEmpty() ? "your resistance level" : "$" + breakevens.getLast()) + " with time running out would invalidate the idea";
            case NEUTRAL -> "A strong directional break outside " + (breakevens.size() >= 2 ? "$" + breakevens.getFirst() + "–$" + breakevens.getLast() : "the expected range") + " would invalidate the idea";
            case VOLATILE -> "The underlying pinning near " + spot.stripTrailingZeros().toPlainString() + " as expiration approaches would invalidate the idea";
        };
        String why = intent == StrategyIntent.DIRECTIONAL
                ? family.display() + " matches a " + thesis.name().toLowerCase(Locale.ROOT) + " view with "
                    + (family.definedRisk() ? "defined, pre-known risk" : "undefined risk") + ". "
                    + (pop != null ? String.format("Modeled probability of profit ~%.0f%%. ", pop * 100) : "")
                    + "Sized to keep worst case within your " + Money.fmt(budget) + " risk budget."
                : family.display() + " serves “" + intent.display() + "”: " + intent.blurb() + " "
                    + (pop != null ? String.format("Modeled probability of profit ~%.0f%%. ", pop * 100) : "")
                    // Collateral-based income (a cash-secured put or a buy-write covered call) deploys
                    // CAPITAL you set aside, not a slice of the risk budget — say so, so its five-figure
                    // "max loss" reads as the collateral it is, not a fee. Defined-risk ideas keep the
                    // risk-budget framing, where max loss genuinely IS the capital at risk.
                    + (onHeldShares && family == StrategyFamily.COVERED_STRANGLE
                        ? "Held shares back the call; the short put requires "
                            + Money.fmt(exactCapital.reserveCents())
                            + " of strike cash and "
                            + Money.fmt(exactCapital.buyingPowerRequiredCents())
                            + " of opening buying power after the captured credit. "
                        : collateralBased
                        ? "Sized by your buying power — this exact opening uses "
                            + Money.fmt(exactCapital.buyingPowerRequiredCents())
                            + " of buying power and carries "
                            + Money.fmt(exactCapital.economicExposureCents())
                            + " of named economic exposure, within your "
                            + Money.fmt(buyingPowerCents)
                            + " account. " + exactCapital.basis()
                        : maxLoss > 0 ? "Sized to keep new cash at risk within your " + Money.fmt(budget) + " budget." : "");
        boolean includesStockLeg = built.legs().stream().anyMatch(Leg::isStock);
        String beginner = beginnerText(family, price.afterFeeNetCents(),
                netOptionIncomeCents, includesStockLeg);
        String intentNote = intentNote(intent, family, holdings, spot, qty, netOptionIncomeCents, minDte,
                effectivePrice, shortSideExpirationItmProb, annualizedOpeningPremiumRatePct,
                shortCallStrike, shortPutStrike, longPutStrike,
                onHeldShares, packageShareUnitsPerUnit);

        if (ivMissing && !multiExp) {
            candidateWarnings.add("No implied volatility available — POP/EV are unavailable for this exact package");
        }

        return new Candidate(family.name(), family.display(), family.structureGroup(), built.label(),
                List.copyOf(legViews), qty,
                price, maxProfit, maxLoss, breakevens,
                round2(liquidity), freshness.name(), candidateWarnings,
                round2(confidence), why, upside, risk, invalidate, beginner,
                intent.name(), family.intents().stream().map(Enum::name).sorted().toList(),
                shortSideExpirationItmProb,
                annualizedOpeningPremiumRatePct, effectivePrice, intentNote,
                onHeldShares ? Boolean.TRUE : null,
                onHeldShares ? Math.toIntExact(Math.multiplyExact(displaySharesPerUnit, (long) qty)) : null,
                combinedMaxLoss, onHeldShares && holdings != null ? holdings.evidence() : null,
                marketImpliedRisk);
    }

    /** Fee-aware entry buying power for a quantity before the final immutable price is assembled. */
    private long openingBuyingPowerRequired(long unitMaximumLossCents,
                                            long unitGrossOpeningNetCents,
                                            List<Leg> legs, int qty,
                                            boolean heldShareContext) {
        long maximumLoss = Math.multiplyExact(unitMaximumLossCents, (long) qty);
        long grossNet = Math.multiplyExact(unitGrossOpeningNetCents, (long) qty);
        long heldSharePutObligation = heldShareContext
                ? io.liftandshift.strikebench.strategy.CapitalRequirement
                        .heldSharePutObligationCents(legs, qty) : 0L;
        long reserve = heldSharePutObligation > 0 ? heldSharePutObligation
                : io.liftandshift.strikebench.strategy.CapitalRequirement.reserveCents(
                        maximumLoss, grossNet, heldShareContext);
        long openingFees = Fees.openingCents(Fees.optionContracts(legs, qty),
                feePerContractCents, feePerOrderCents);
        long afterFeeNet = Math.subtractExact(grossNet, openingFees);
        return io.liftandshift.strikebench.strategy.CapitalRequirement
                .buyingPowerRequiredCents(reserve, afterFeeNet);
    }

    private static final class CandidateProbe {
        private String reason;
        private Long marketCrashLossCents;
    }

    private static Candidate candidateFailure(CandidateProbe probe, String reason) {
        if (probe != null) probe.reason = reason;
        return null;
    }

    /** Human framing of the candidate against the user's goal, holdings and target price. */
    private static String intentNote(StrategyIntent intent, StrategyFamily family, Holdings holdings,
                                     BigDecimal spot, int qty, long netOptionPremium, int minDte,
                                     String effectivePrice, Double shortSideExpirationItmProb,
                                     Double annualizedOpeningPremiumRatePct,
                                     BigDecimal shortCallStrike, BigDecimal shortPutStrike, BigDecimal longPutStrike,
                                     boolean onHeldShares, long displaySharesPerUnit) {
        long shares = Math.multiplyExact(Math.max(displaySharesPerUnit, 1), (long) qty);
        String expirationItmPct = shortSideExpirationItmProb == null ? null
                : String.format("~%.0f%%", shortSideExpirationItmProb * 100);
        Long basis = holdings == null ? null : holdings.costBasisCents();
        switch (intent) {
            case EXIT -> {
                if (shortCallStrike == null) return null;
                StringBuilder sb = new StringBuilder();
                sb.append("If assigned you sell ").append(shares).append(" shares at $")
                        .append(shortCallStrike.stripTrailingZeros().toPlainString());
                if (effectivePrice != null) sb.append(" — effectively $").append(effectivePrice).append("/sh with the premium");
                if (basis != null && basis > 0) {
                    double gain = 100.0 * (Double.parseDouble(effectivePrice == null
                            ? shortCallStrike.toPlainString() : effectivePrice) * 100 - basis) / basis;
                    sb.append(String.format(", %+.1f%% vs your $%s basis", gain, Money.fmt(basis).replace("$", "")));
                }
                sb.append(". ").append(expirationItmPct != null
                        ? "Modeled odds the short call finishes in the money at expiration: "
                            + expirationItmPct + ". That is a goal-fit proxy, not an assignment guarantee; "
                            + "early assignment can differ."
                        : "");
                if (netOptionPremium > 0) sb.append(" Either way the premium leaves ")
                        .append(Money.fmt(netOptionPremium)).append(" after opening fees.");
                return sb.toString().trim();
            }
            case ACQUIRE -> {
                if (family == StrategyFamily.CREDIT_PUT_SPREAD) {
                    return "Capped-risk alternative to a cash-secured put: the long put limits the "
                            + "downside while the short put earns premium. It expresses the desired-price "
                            + "view but normally settles as a spread; it is not a reliable way to receive shares.";
                }
                if (family == StrategyFamily.DEBIT_CALL_SPREAD) {
                    return "Capped-risk upside alternative while waiting for the desired stock price. "
                            + "It participates if the shares run away from your target, but it cannot deliver "
                            + "shares; the debit is the defined risk.";
                }
                if (family == StrategyFamily.CALENDAR_PUT) {
                    return "Put-calendar comparison around the desired acquisition price. The near "
                            + "short put can create a share-purchase obligation, but this debit-funded "
                            + "time-spread receipt does not establish the strike cash or margin needed "
                            + "for assignment and does not guarantee share delivery. Treat assignment, "
                            + "the farther-dated long put, and every roll as separate managed decisions.";
                }
                if (shortPutStrike == null) return null;
                StringBuilder sb = new StringBuilder();
                sb.append("If assigned you buy ").append(shares).append(" shares at $")
                        .append(shortPutStrike.stripTrailingZeros().toPlainString());
                if (effectivePrice != null) sb.append(" — effectively $").append(effectivePrice).append("/sh after the premium");
                if (spot != null && spot.signum() > 0 && effectivePrice != null) {
                    double disc = 100.0 * (spot.doubleValue() - Double.parseDouble(effectivePrice)) / spot.doubleValue();
                    sb.append(String.format(", %.1f%% below today's $%s", disc, spot.stripTrailingZeros().toPlainString()));
                }
                sb.append(". ").append(expirationItmPct != null
                        ? "Modeled odds the short put finishes in the money at expiration: "
                            + expirationItmPct + ". That is not a guarantee of share delivery or an "
                            + "early-assignment probability."
                        : "");
                if (netOptionPremium > 0 && annualizedOpeningPremiumRatePct != null) {
                    sb.append(" If not, the premium leaves ").append(Money.fmt(netOptionPremium)).append(" after opening fees")
                            .append(String.format(" (~%.1f%%/yr after-fee opening-premium rate on named collateral if repeatable; not expected return).", annualizedOpeningPremiumRatePct));
                }
                return sb.toString().trim();
            }
            case INCOME -> {
                if (netOptionPremium <= 0) return null;
                StringBuilder sb = new StringBuilder("Collect " + Money.fmt(netOptionPremium) + " net after opening fees");
                if (annualizedOpeningPremiumRatePct != null) sb.append(String.format(
                        " — ~%.1f%%/yr after-fee opening-premium rate on named collateral if repeatable "
                                + "over this %d-day package; not expected return",
                        annualizedOpeningPremiumRatePct, Math.max(minDte, 1)));
                sb.append(".");
                if (expirationItmPct != null) sb.append(" Modeled odds the short side finishes in the money at expiration: ")
                        .append(expirationItmPct)
                        .append(". This is not an early-assignment probability.");
                if (onHeldShares) sb.append(" Written against shares you already hold.");
                return sb.toString();
            }
            case HEDGE -> {
                if (longPutStrike == null) return null;
                StringBuilder sb = new StringBuilder();
                if (shortPutStrike != null && shortPutStrike.compareTo(longPutStrike) < 0) {
                    sb.append("Protects ").append(shares).append(" shares from $")
                            .append(longPutStrike.stripTrailingZeros().toPlainString())
                            .append(" down to $")
                            .append(shortPutStrike.stripTrailingZeros().toPlainString())
                            .append("; below the lower strike the put-spread protection is exhausted "
                                    + "and share downside reopens");
                } else {
                    sb.append("Creates an expiration sale floor at $")
                            .append(longPutStrike.stripTrailingZeros().toPlainString())
                            .append(" for ").append(shares).append(" shares");
                }
                if (netOptionPremium < 0) sb.append(" for a cost of ").append(Money.fmt(-netOptionPremium)).append(" after opening fees");
                else if (netOptionPremium > 0) sb.append(" and even leaves ").append(Money.fmt(netOptionPremium)).append(" after opening fees (the call cap funds the floor)");
                if (shortCallStrike != null) sb.append("; upside above $")
                        .append(shortCallStrike.stripTrailingZeros().toPlainString()).append(" is given up");
                sb.append(".");
                return sb.toString();
            }
            default -> { return null; }
        }
    }

    /** Returns a human-readable reason when the candidate fails a hard filter, else null. */
    private static String failsFilter(Candidate c, Filters f, Long marketCrashLossCents) {
        if (f.minPop() != null) {
            Double pop = c.marketImpliedRisk().pop();
            if (pop == null) return String.format("No modeled POP available, but you require at least %.0f%%", f.minPop() * 100);
            if (pop < f.minPop()) return String.format("Modeled POP %.0f%% is below your minimum %.0f%%", pop * 100, f.minPop() * 100);
        }
        boolean hasShortOption = c.legs().stream().anyMatch(leg ->
                "SELL".equalsIgnoreCase(leg.action()) && !"STOCK".equalsIgnoreCase(leg.type()));
        if (f.maxShortSideExpirationItmProb() != null && hasShortOption) {
            if (c.shortSideExpirationItmProb() == null) {
                return String.format("Short-side expiration-ITM odds are unavailable, so your cap "
                        + "of %.0f%% cannot be verified", f.maxShortSideExpirationItmProb() * 100);
            }
            if (c.shortSideExpirationItmProb() > f.maxShortSideExpirationItmProb()) {
                return String.format("Short-side expiration-ITM odds %.0f%% exceed your cap of %.0f%%; "
                                + "this is not an early-assignment probability",
                        c.shortSideExpirationItmProb() * 100, f.maxShortSideExpirationItmProb() * 100);
            }
        }
        if (f.minAnnualizedOpeningPremiumRatePct() != null) {
            if (c.annualizedOpeningPremiumRatePct() == null) return "No collateral-backed annualized opening-premium "
                    + "rate is available for this filter (it applies only to covered calls, "
                    + "cash-secured puts and collars; it is not expected return)";
            if (c.annualizedOpeningPremiumRatePct() < f.minAnnualizedOpeningPremiumRatePct()) {
                return String.format("After-fee annualized opening-premium rate %.1f%% is below your "
                                + "minimum %.1f%%; both are collateral-rate screens, not expected returns",
                        c.annualizedOpeningPremiumRatePct(), f.minAnnualizedOpeningPremiumRatePct());
            }
        }
        Long packageNet = c.price() == null ? null : c.price().afterFeeNetCents();
        if (packageNet == null) {
            // A filter is a promise about a number. With no package price the promise cannot be
            // kept, so the candidate is excluded WITH the reason — never admitted on an assumed
            // zero cost, which would slip an unpriced package past a max-cost cap (§3.2).
            return "This package has no price, so your entry-cost and premium filters cannot be applied to it";
        }
        if (f.maxCostCents() != null && packageNet < 0
                && Math.negateExact(packageNet) > f.maxCostCents()) {
            return "After-fee opening cost " + Money.fmt(Math.negateExact(packageNet))
                    + " exceeds your cap of " + Money.fmt(f.maxCostCents());
        }
        if (f.maxCapitalRequiredCents() != null) {
            Long required = c.capital().economicExposureCents();
            if (required == null) {
                return "This package has no exact economic-exposure receipt, so your "
                        + Money.fmt(f.maxCapitalRequiredCents()) + " capital cap cannot be applied";
            }
            if (required > f.maxCapitalRequiredCents()) {
                return "Economic exposure " + Money.fmt(required)
                        + " exceeds your cap of " + Money.fmt(f.maxCapitalRequiredCents());
            }
        }
        if (f.maxMarketCrashLossCents() != null) {
            if (marketCrashLossCents == null) {
                return "The " + storyMoveLabel(ScenarioStory.MARKET_CRASH)
                        + "% market-crash loss is unavailable for this package, so your "
                        + Money.fmt(f.maxMarketCrashLossCents()) + " crash-loss cap cannot be applied";
            }
            if (marketCrashLossCents > f.maxMarketCrashLossCents()) {
                return "Loss at the " + storyMoveLabel(ScenarioStory.MARKET_CRASH)
                        + "% market-crash scenario is " + Money.fmt(marketCrashLossCents)
                        + ", above your cap of " + Money.fmt(f.maxMarketCrashLossCents());
            }
        }
        return null;
    }

    private static String storyMoveLabel(ScenarioStory story) {
        return String.format(Locale.ROOT, "%.0f", story.movePct());
    }

    private static String earningsConstraintReason(EventService.EventEvidence event,
                                                   LocalDate packageEnd) {
        String authority = event.confirmed() ? "confirmed" : "estimated";
        String when = event.confirmed()
                ? event.date().toString()
                : event.confidenceStart() + " through " + event.confidenceEnd();
        return "Excluded by your Avoid earnings constraint: the " + authority
                + " earnings " + (event.confirmed() ? "date " : "window ")
                + when + " overlaps this package through " + packageEnd
                + " (" + event.source() + ").";
    }

    private static double liquidityScore(List<OptionQuote> quotes) {
        double worstSpread = quotes.stream().filter(Objects::nonNull)
                .mapToDouble(OptionQuote::spreadPct).filter(d -> !Double.isNaN(d)).max().orElse(0.05);
        return Math.clamp(1.0 - worstSpread / 0.15, 0, 1);
    }

    private static String beginnerText(StrategyFamily family, long afterFeePackageNet,
                                       long afterFeeOptionNet,
                                       boolean includesStockLeg) {
        String cash;
        if (family.requiresLongStock()) {
            String optionCash = afterFeeOptionNet > 0
                    ? "The option legs leave " + Money.fmt(afterFeeOptionNet)
                        + " after opening fees."
                    : afterFeeOptionNet < 0
                        ? "The option legs cost " + Money.fmt(-afterFeeOptionNet)
                            + " after opening fees."
                        : "The option legs leave no net cash after opening fees.";
            if (includesStockLeg) {
                String packageCash = afterFeePackageNet > 0
                        ? "The complete stock-plus-options package leaves "
                            + Money.fmt(afterFeePackageNet) + " after opening fees."
                        : afterFeePackageNet < 0
                            ? "The complete stock-plus-options package costs "
                                + Money.fmt(-afterFeePackageNet) + " after opening fees."
                            : "The complete stock-plus-options package leaves no net cash after opening fees.";
                cash = packageCash + " " + optionCash
                        + " The shares are part of that package value and keep their own upside and downside.";
            } else {
                cash = optionCash + " Your existing shares remain a separate source of gain or loss.";
            }
        } else {
            cash = afterFeePackageNet >= 0
                    ? "The package leaves " + Money.fmt(afterFeePackageNet)
                        + " after opening fees; that cash is not the expected result."
                    : "The package costs " + Money.fmt(-afterFeePackageNet)
                        + " after opening fees; consult the exact maximum-loss receipt rather than "
                        + "assuming every debit has identical risk.";
        }
        return switch (family) {
            case LONG_CALL -> "Buying a call is a bet the stock rises above the strike before expiration. " + cash;
            case LONG_PUT -> "Buying a put is a bet the stock falls below the strike before expiration. " + cash;
            case DEBIT_CALL_SPREAD -> "You buy one call and sell a higher one to cut the cost; profit is capped but so is loss. " + cash;
            case DEBIT_PUT_SPREAD -> "You buy one put and sell a lower one to cut the cost; profit is capped but so is loss. " + cash;
            case CREDIT_CALL_SPREAD -> "You sell a call above the price and buy a higher one to cap the loss; expiration profit requires the stock to remain below the breakeven. " + cash;
            case CREDIT_PUT_SPREAD -> "You sell a put below the price and buy a lower one to cap the loss; expiration profit requires the stock to remain above the breakeven. " + cash;
            case IRON_CONDOR -> "Two credit spreads define a range; expiration profit requires the stock to remain between the two breakevens. " + cash;
            case IRON_BUTTERFLY -> "Like an iron condor but centered exactly at the money — bigger credit, narrower sweet spot. " + cash;
            case LONG_CALL_BUTTERFLY, LONG_PUT_BUTTERFLY -> "A pinned bet that the stock finishes near the middle strike. Cheap to buy, capped both ways. " + cash;
            case CALENDAR_CALL, CALENDAR_PUT -> "You fund a longer-dated option and sell a nearer one "
                    + "at the same strike. This is a managed time-spread comparison: future decay "
                    + "and roll credits are not guaranteed income. " + cash;
            case DIAGONAL_CALL, DIAGONAL_PUT -> "You fund a longer-dated option and sell a nearer one "
                    + "at another strike. This is a managed campaign whose path, assignment, and "
                    + "future roll results are not captured by the opening premium alone. " + cash;
            case COVERED_CALL -> (includesStockLeg
                    ? "You buy shares and sell a call against them; the opening call credit caps upside and does not guarantee a profitable campaign. "
                    : "You own shares and sell a call against them; the opening call credit caps upside and does not guarantee a profitable campaign. ") + cash;
            case COVERED_STRANGLE -> "A covered call plus a short put: held shares back the call and "
                    + "strike cash backs the put economics. StrikeBench currently values the "
                    + "composite at cash-equivalent expiry and does not promise a second share-lot "
                    + "delivery, so it remains a comparison rather than an endorsement. " + cash;
            case COVERED_CALL_PUT_SPREAD -> "You own shares, sell a call that caps upside, and buy a put spread that limits part of the downside. The opening net credit or debit is not the campaign result. " + cash;
            case COVERED_CALL_CALL_OVERLAY -> "A covered call plus a farther long call: the opening credit caps near upside while the long call can restore participation beyond its strike. " + cash;
            case CASH_SECURED_PUT -> "You sell a put with strike cash reserved to buy the shares if assigned; the opening credit compensates you for accepting that downside obligation. " + cash;
            case PROTECTIVE_COLLAR -> "You own shares, buy a put as a floor, and sell a call to pay for it. " + cash;
            case PROTECTIVE_PUT -> "You own shares and buy a put as insurance: a guaranteed minimum sale price until expiration. " + cash;
            default -> cash;
        };
    }


    private static StrategyFamily.Thesis parseThesis(String s) {
        if (s == null) return StrategyFamily.Thesis.NEUTRAL;
        try { return StrategyFamily.Thesis.valueOf(s.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return StrategyFamily.Thesis.NEUTRAL; }
    }

    /** Internal callers historically relied on Horizon.parse(null) silently becoming month.
     * Keep compatibility explicit and make income's neutral starting cycle 30 trading sessions.
     * Public decision routes still reject an absent declaration before this engine is reached. */
    static String effectiveHorizon(String requested, StrategyIntent intent) {
        if (requested != null && !requested.isBlank()) return requested.trim();
        return intent == StrategyIntent.INCOME ? "30d" : "month";
    }

    /** How many liquid expirations the dynamic search evaluates per family, nearest-anchor first. */
    private static final int MAX_EXPIRY_CANDIDATES = 4;
    /** A tradeable chain needs at least this many two-sided strikes bracketing spot; below it, an
     *  expiration is a mirage that can never produce a fillable structure and is skipped. */
    private static final int MIN_LIQUID_STRIKES = 6;

    /** One expiration's fully-resolved build context for the dynamic-expiry search. */
    private record ExpiryCtx(LocalDate near, OptionChain chain, OptionChain farChain, BigDecimal spot,
                             double riskFreeRate) {}

    /** Exact horizon → calendar days. "Nd" sessions convert at 7/5; the named buckets fall back to
     *  their calendar span. This is only the ANCHOR — the search spans liquid expirations around it. */
    private static int horizonAnchorCalendarDays(String horizon) {
        return io.liftandshift.strikebench.model.Horizon.expiryCalendarDays(horizon);
    }

    /** The liquid, same-lane analysis expirations nearest the horizon anchor, nearest first, up to
     *  maxCount. A stale observed close may support labeled analysis but never execution; the
     *  decision/preview boundary owns that stricter gate. Each context carries its own spot, far
     *  chain (for calendars/diagonals) and risk-free rate. */
    private List<ExpiryCtx> expiryContexts(String symbol, String worldId, List<LocalDate> expirations,
            int anchorDays, LocalDate today, java.time.Instant now,
            io.liftandshift.strikebench.market.MarketLane lane, boolean allow0dte, int maxCount) {
        List<LocalDate> usable = expirations.stream()
                .filter(d -> !d.isBefore(today))
                .filter(d -> !MarketHours.contractDead(d, now))
                .filter(d -> allow0dte || !d.equals(today))
                .sorted(Comparator.comparingLong(d -> Math.abs(ChronoUnit.DAYS.between(today, d) - anchorDays)))
                .toList();
        List<ExpiryCtx> ctxs = new ArrayList<>();
        for (LocalDate exp : usable) {
            if (ctxs.size() >= maxCount) break;
            OptionChain chain = market.chain(symbol, exp, worldId).orElse(null);
            if (chain == null || chain.isEmpty() || !chain.evidence().usableIn(lane)) continue;
            BigDecimal spot = chain.underlyingPrice();
            if (spot == null || spot.signum() <= 0 || executableStrikesNearSpot(chain, spot) < MIN_LIQUID_STRIKES) continue;
            int idx = expirations.indexOf(exp);
            LocalDate far = idx >= 0 && idx + 4 < expirations.size() ? expirations.get(idx + 4)
                    : expirations.getLast().isAfter(exp) ? expirations.getLast() : null;
            OptionChain farChain = far == null ? null : market.chain(symbol, far, worldId).orElse(null);
            if (farChain != null && !farChain.evidence().usableIn(lane)) farChain = null;
            double rfr = market.riskFreeRateQuote((int) Math.max(1, ChronoUnit.DAYS.between(today, exp)), worldId).annualRate();
            ctxs.add(new ExpiryCtx(exp, chain, farChain, spot, rfr));
        }
        return ctxs;
    }

    /** Strikes within ±15% of spot with a two-sided CALL observation — a liquidity proxy. The
     *  enclosing evidence receipt determines whether those observations are fresh enough to execute. */
    private static int executableStrikesNearSpot(OptionChain chain, BigDecimal spot) {
        double s = spot.doubleValue(), lo = s * 0.85, hi = s * 1.15;
        return (int) chain.calls().stream()
                .filter(q -> q.strike() != null && q.strike().doubleValue() >= lo && q.strike().doubleValue() <= hi)
                .filter(q -> q.bid() != null && q.ask() != null && q.bid().signum() > 0 && q.ask().signum() > 0
                        && q.ask().compareTo(q.bid()) >= 0)
                .count();
    }

    /**
     * OBJECTIVE-COHERENCE GATE (offer time). Rejects any structure whose economics contradict the
     * declared intent, so the engine never presents an outcome incompatible with the objective:
     *   - DOMINATED (any intent): a bounded structure whose max profit is <= 0 can never make money.
     *   - INCOME must leave positive option cash after opening fees. Stock purchase/sale cash is
     *     never substituted for option compensation. Multi-expiration packages remain
     *     comparison-only because future short-option sales and rolls are new decisions, not
     *     opening income that this package can claim.
     * Returns a human reason to reject, or null when the structure is coherent with the intent.
     */
    public static String intentIncoherence(StrategyIntent intent, StrategyFamily family, Candidate c) {
        if (c.maxProfitCents() != null && c.maxProfitCents() <= 0) {
            return "At executable prices this structure cannot profit under any outcome (max profit "
                    + Money.fmt(c.maxProfitCents()) + ") — not a usable trade.";
        }
        Long optionNet = c.price() == null ? null : c.price().optionNetPremiumCents();
        Long openingFees = c.price() == null ? null : c.price().openingFeesCents();
        if (optionNet == null || openingFees == null) {
            return "This package has no complete option-side price and fee receipt, so whether it "
                    + "leaves positive opening option cash cannot be answered.";
        }
        long afterFeeOptionCash = Math.subtractExact(optionNet, openingFees);
        // GENERATION-time gate on opening cashflow — a single-expiration income structure must
        // collect a credit. Multi-expiration structures are kept for managed-carry comparison by
        // catalog policy; positive theta never gets relabeled as cash already earned.
        if (intent == StrategyIntent.INCOME && !family.multiExpiration()
                && afterFeeOptionCash <= 0) {
            return "This single-expiration Income package does not leave positive option cash after "
                    + "opening fees (" + Money.fmt(afterFeeOptionCash)
                    + "). Stock cash flow and modeled theta cannot substitute for opening compensation.";
        }
        return null;
    }

    /** Structural viability only; the complete economics/evidence judgment remains downstream. */
    public static String packageViability(StrategyFamily family, Candidate c) {
        if (family != StrategyFamily.IRON_CONDOR) return null;
        Long stated = c.price() == null ? null : c.price().grossPackageNetCents();
        if (stated == null) {
            // Every condor ratio below is credit-to-width. With no credit there is no ratio, and
            // a null read as $0 would report a perfectly good condor as paying nothing (§3.2).
            return "this package has no executable credit to measure its wings against";
        }
        long credit = stated;
        double grossWidth = (double) c.maxLossCents() + credit;
        double creditToWidth = grossWidth > 0 ? credit / grossWidth : 0.0;
        double wingBalance = condorWingBalance(c);
        IronCondorQuality.Assessment quality = IronCondorQuality.assessRatios(
                creditToWidth, wingBalance, credit > 0 && credit < grossWidth);
        if (quality.viable()) return null;

        List<String> reasons = new ArrayList<>();
        if (!quality.positiveBoundedCredit() || !quality.adequateCredit()) {
            reasons.add(String.format(Locale.ROOT,
                    "executable credit %s is %.1f%% of the widest wing (minimum %.0f%%)",
                    Money.fmt(Math.max(0, credit)), quality.creditToWidestWing() * 100.0,
                    IronCondorQuality.MIN_CREDIT_TO_WIDEST_WING * 100.0));
        }
        if (!quality.balancedWings()) {
            reasons.add(String.format(Locale.ROOT,
                    "the narrower protective wing is only %.1f%% of the wider wing (minimum %.0f%%); that is a broken-wing package, not a canonical range-income condor",
                    quality.narrowToWideWing() * 100.0,
                    IronCondorQuality.MIN_NARROW_TO_WIDE_WING * 100.0));
        }
        return "Iron condor quality screen: " + String.join("; ", reasons)
                + ". It remains analyzable as exact legs, but is not an automatic recommendation.";
    }

    private static double condorWingBalance(Candidate candidate) {
        if (candidate.legs() == null) return 0.0;
        BigDecimal longPut = null;
        BigDecimal shortPut = null;
        BigDecimal shortCall = null;
        BigDecimal longCall = null;
        for (LegView leg : candidate.legs()) {
            if (leg == null || leg.strike() == null) continue;
            BigDecimal strike;
            try { strike = new BigDecimal(leg.strike()); }
            catch (NumberFormatException e) { return 0.0; }
            if ("PUT".equalsIgnoreCase(leg.type()) && "BUY".equalsIgnoreCase(leg.action())) {
                if (longPut != null) return 0.0;
                longPut = strike;
            } else if ("PUT".equalsIgnoreCase(leg.type()) && "SELL".equalsIgnoreCase(leg.action())) {
                if (shortPut != null) return 0.0;
                shortPut = strike;
            } else if ("CALL".equalsIgnoreCase(leg.type()) && "SELL".equalsIgnoreCase(leg.action())) {
                if (shortCall != null) return 0.0;
                shortCall = strike;
            } else if ("CALL".equalsIgnoreCase(leg.type()) && "BUY".equalsIgnoreCase(leg.action())) {
                if (longCall != null) return 0.0;
                longCall = strike;
            }
        }
        if (longPut == null || shortPut == null || shortCall == null || longCall == null) return 0.0;
        BigDecimal putWidth = shortPut.subtract(longPut);
        BigDecimal callWidth = longCall.subtract(shortCall);
        if (putWidth.signum() <= 0 || callWidth.signum() <= 0) return 0.0;
        return putWidth.min(callWidth).doubleValue() / putWidth.max(callWidth).doubleValue();
    }

    private static LocalDate pickExpiration(List<LocalDate> expirations, String horizon, LocalDate today,
                                            boolean allow0dte, java.time.Instant now, List<String> notes) {
        int targetDays = io.liftandshift.strikebench.model.Horizon.expiryCalendarDays(horizon);
        List<LocalDate> usable = expirations.stream()
                .filter(d -> !d.isBefore(today))
                // A contract whose final bell (4pm ET on expiration day) has passed is DEAD — never
                // recommend it, matching the placement-time guard in TradeService.
                .filter(d -> !MarketHours.contractDead(d, now))
                .filter(d -> allow0dte || !d.equals(today))
                .toList();
        if (usable.isEmpty()) return null;
        if (targetDays == 0 && !allow0dte) {
            notes.add("0DTE horizon requested but same-day expiration is disabled (allow0dte=false); using the nearest expiration instead");
        }
        LocalDate best = usable.stream()
                .min(Comparator.comparingLong(d -> Math.abs(ChronoUnit.DAYS.between(today, d) - targetDays)))
                .orElse(null);
        if (best != null && targetDays >= 60 && ChronoUnit.DAYS.between(today, best) < 45) {
            notes.add("No expiration close to the requested quarter horizon is listed; using the furthest available");
        }
        return best;
    }
}
