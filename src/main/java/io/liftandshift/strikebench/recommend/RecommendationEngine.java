package io.liftandshift.strikebench.recommend;

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
import io.liftandshift.strikebench.pricing.BlackScholes;
import io.liftandshift.strikebench.pricing.PayoffCurve;
import io.liftandshift.strikebench.strategy.Guardrails;
import io.liftandshift.strikebench.strategy.IronCondorQuality;
import io.liftandshift.strikebench.strategy.StrategyBuilder;
import io.liftandshift.strikebench.strategy.StrategyFamily;
import io.liftandshift.strikebench.strategy.StrategyIntent;
import io.liftandshift.strikebench.strategy.Verdict;
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
    ) {}

    /**
     * Shares context. sharesOwned must be the FREE (unlocked) share count; costBasisCents is the
     * average per-share basis; targetPriceCents is the per-share price the user would happily
     * sell at (EXIT), buy at (ACQUIRE), or protect down to (HEDGE).
     */
    public record Holdings(Integer sharesOwned, Long costBasisCents, Long targetPriceCents) {}

    /** Hard candidate screens; a candidate failing one lands in rejected[] with the reason. */
    public record Filters(
            Double minPop,                 // 0..1
            Double maxAssignmentProb,      // 0..1
            Double minAnnualizedYieldPct,  // e.g. 12 = 12%/yr
            Long maxCostCents              // cap on cash paid at entry (debits)
    ) {}

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
        this.feePerContractCents = Math.max(0, perContractCents);
        this.feePerOrderCents = Math.max(0, perOrderCents);
        return this;
    }

    public LocalDate marketDate(String worldId) {
        return LocalDate.ofInstant(market.simInstant(worldId).orElseGet(clock::instant), MarketHours.EASTERN);
    }

    public Result recommend(Request req, long buyingPowerCents) {
        return recommend(req, buyingPowerCents, null);
    }

    /** World-aware: inside a SIMULATED session, recommendations price against THAT world —
     *  the whole point of a reviewer market. null = observed (the real-lane rule stands). */
    public Result recommend(Request req, long buyingPowerCents, String worldId) {
        String symbol = req.symbol() == null ? "" : req.symbol().trim().toUpperCase(Locale.ROOT);
        RiskMode mode = RiskMode.parse(req.riskMode());
        StrategyIntent intent = StrategyIntent.parse(req.intent());
        String horizon = effectiveHorizon(req.horizon(), intent);
        StrategyFamily.Thesis thesis = parseThesis(req.thesis());
        Holdings holdings = req.holdings();
        int freeShares = holdings != null && holdings.sharesOwned() != null ? Math.max(0, holdings.sharesOwned()) : 0;
        BigDecimal targetPrice = holdings != null && holdings.targetPriceCents() != null && holdings.targetPriceCents() > 0
                ? Money.priceFromCents(holdings.targetPriceCents()) : null;
        Filters filters = req.filters() == null ? new Filters(null, null, null, null) : req.filters();
        boolean allow0dte = Boolean.TRUE.equals(req.allow0dte());
        boolean avoidEarnings = req.avoidEarnings() == null || req.avoidEarnings();
        long budget = RiskBudgetPolicy.requestBudgetCents(
                mode, buyingPowerCents, req.maxRiskPctOfAccount(), req.maxLossCents());
        double minConfidence = req.minConfidence() == null ? 0 : req.minConfidence();

        List<String> notes = new ArrayList<>();
        if (req.horizon() == null || req.horizon().isBlank()) {
            notes.add(intent == StrategyIntent.INCOME
                    ? "No horizon was supplied to this internal engine call; using an explicit 30-session income cycle. Product decision routes still require the user-owned horizon to be persisted before ranking."
                    : "No horizon was supplied to this internal engine call; using the month analysis bucket. Product decision routes still require an explicit persisted horizon.");
        }
        // ONE CLOCK PER LANE: a simulated session's clock is always in-session while it runs —
        // the observed market being closed says nothing about THIS market (review P2).
        java.time.Instant laneNow = market.simInstant(worldId).orElseGet(clock::instant);
        if (worldId == null && !MarketHours.isRegularSession(laneNow)) {
            notes.add("The market is closed — prices and strikes here are anchored to the PRIOR CLOSE, "
                    + "not a live quote, and can shift at the next open.");
        }
        // Buying shares at a discount commits the full purchase price by design — a cash-secured
        // put reserves strike x 100. Capping that by a small risk-% would reject every candidate,
        // so the ACQUIRE flow caps by available cash instead (unless the user set explicit limits).
        if (StrategyIntent.parse(req.intent()) == StrategyIntent.ACQUIRE
                && req.maxRiskPctOfAccount() == null && req.maxLossCents() == null) {
            budget = buyingPowerCents;
            notes.add("Acquire flow: capital is capped by your buying power, not the risk-mode budget — "
                    + "a cash-secured put sets aside the full purchase price (that IS the design)");
        }
        List<Rejection> rejected = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();

        var lane = market.lane(worldId);
        Quote quote = market.quote(symbol, worldId).orElse(null);
        if (quote == null) {
            notes.add(missingMarketDataNote(lane, symbol));
            return new Result(symbol, thesis.name(), horizon, mode.name(), intent.name(), budget, null, List.of(), rejected, notes, DISCLAIMER);
        }
        if (!quote.evidence().usableIn(lane)) {
            notes.add("No " + lane + "-lane quote is available for " + symbol + "; refusing to substitute "
                    + quote.evidence().provenance() + " data from " + quote.evidence().source());
            return new Result(symbol, thesis.name(), horizon, mode.name(), intent.name(), budget,
                    null, List.of(), rejected, notes, DISCLAIMER);
        }
        List<LocalDate> expirations = market.expirations(symbol, worldId);
        if (!quote.optionable() || expirations.isEmpty()) {
            notes.add(symbol + " has no listed options (mutual funds and some securities cannot be traded with options)");
            return new Result(symbol, thesis.name(), horizon, mode.name(), intent.name(), budget, null, List.of(), rejected, notes, DISCLAIMER);
        }

        LocalDate today = LocalDate.ofInstant(laneNow, MarketHours.EASTERN);
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
        boolean holdBasedIntent = intent == StrategyIntent.EXIT || intent == StrategyIntent.HEDGE;
        boolean sharesHeld = freeShares >= 100 && (holdBasedIntent
                || (intent == StrategyIntent.INCOME && holdings != null));
        if (holdBasedIntent && freeShares < 100) {
            notes.add("You hold " + freeShares + " free shares of " + symbol
                    + " — options work in 100-share lots, so candidates below include buying the shares (buy-write style)");
        }
        if (intent == StrategyIntent.ACQUIRE && targetPrice != null && spot != null
                && targetPrice.compareTo(spot) > 0) {
            notes.add("Your target buy price is above today's price — you could simply buy the shares now; "
                    + "candidates below get paid to wait for a LOWER price");
        }
        StrategyBuilder.BuildHints hints = new StrategyBuilder.BuildHints(
                intent == StrategyIntent.DIRECTIONAL ? null : targetPrice, sharesHeld);

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
                if (family.needsStock() || family == StrategyFamily.CASH_SECURED_PUT) continue;
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
                        family.display() + " has undefined risk and is blocked by default; "
                                + "it remains available for payoff education, while automatic ideas use a defined-risk alternative.")));
                continue;
            }

            // DYNAMIC EXPIRY: build and screen this family across every liquid candidate expiration.
            // Do not collapse the packages here using raw EV/max-loss: that happens before costs,
            // realized-volatility evidence, objective fit, tail risk, and evidence quality exist.
            // The existing DecisionPolicy evaluates this complete set and chooses the family's
            // representative afterward. The first anchor-nearest rejection remains the teaching
            // reason only when no expiry produces a viable package.
            boolean builtOnHeldShares = sharesHeld && family.needsStock();
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
                    Verdict verdict = Guardrails.checkForAnalysis(new Guardrails.Proposal(
                            family, built.legs(), 1, built.quotes(), ctx.spot(), ctx.chain().freshness(), today,
                            buyingPowerCents, false, avoidEarnings && earningsSoon, false, coverSharesPerUnit));
                    if (verdict.blocked()) {
                        if (firstRejection == null) firstRejection = new Rejection(family.name(), family.display(), verdict.blockReasons());
                        continue;
                    }

                    CandidateProbe probe = new CandidateProbe();
                    Candidate candidate = toCandidate(family, built, verdict, ctx.spot(), today, budget, buyingPowerCents,
                            ctx.chain().freshness(), avoidEarnings, thesis, intent, holdings,
                            builtOnHeldShares ? coverSharesPerUnit : 0, builtOnHeldShares ? freeShares : 0,
                            quote, ctx.riskFreeRate(), probe);
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
                    String filterReason = failsFilter(candidate, filters);
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
        String symbol = req.symbol() == null ? "" : req.symbol().trim().toUpperCase(Locale.ROOT);
        List<String> notes = new ArrayList<>();
        java.time.Instant ladderNow = market.simInstant(worldId).orElseGet(clock::instant);
        if (worldId == null && !MarketHours.isRegularSession(ladderNow)) {
            notes.add("The market is closed — these rungs are measured off the PRIOR CLOSE and can shift at the open.");
        }
        Holdings holdings = req.holdings();
        Filters filters = req.filters() == null ? new Filters(null, null, null, null) : req.filters();
        int freeShares = holdings != null && holdings.sharesOwned() != null ? Math.max(0, holdings.sharesOwned()) : 0;
        boolean sharesHeld = freeShares >= 100 && intent != StrategyIntent.ACQUIRE;

        var lane = market.lane(worldId);
        Quote quote = market.quote(symbol, worldId).orElse(null);
        List<LocalDate> expirations = quote == null ? List.of() : market.expirations(symbol, worldId);
        if (quote == null || !quote.optionable() || expirations.isEmpty()) {
            notes.add(quote == null ? missingMarketDataNote(lane, symbol)
                    : symbol + " has no listed options");
            return new LadderResult(symbol, intent.name(), List.of(), notes, DISCLAIMER);
        }
        if (!quote.evidence().usableIn(lane)) {
            notes.add("No " + lane + "-lane quote is available for " + symbol);
            return new LadderResult(symbol, intent.name(), List.of(), notes, DISCLAIMER);
        }
        LocalDate today = LocalDate.ofInstant(ladderNow, MarketHours.EASTERN);
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
        // Use the same budget calculation as recommend(). ACQUIRE is the one explicit exception:
        // its cash-secured purchase commitment is the product and is disclosed as such. EXIT and
        // HEDGE never inflate the selected per-idea budget merely to manufacture a rung.
        RiskMode mode = RiskMode.parse(req.riskMode());
        long budget = intent == StrategyIntent.ACQUIRE && req.maxRiskPctOfAccount() == null && req.maxLossCents() == null
                ? buyingPowerCents
                : RiskBudgetPolicy.requestBudgetCents(
                        mode, buyingPowerCents, req.maxRiskPctOfAccount(), req.maxLossCents());

        // Rung strikes: EXIT climbs above spot, ACQUIRE/HEDGE step below it
        List<BigDecimal> strikes = new ArrayList<>();
        List<BigDecimal> all = chain.strikes();
        if (intent == StrategyIntent.EXIT) {
            for (BigDecimal k : all) if (k.compareTo(spot) >= 0 && strikes.size() < 6) strikes.add(k);
        } else {
            for (int i = all.size() - 1; i >= 0 && strikes.size() < 6; i--) {
                if (all.get(i).compareTo(spot) <= 0) strikes.add(all.get(i));
            }
        }
        List<Candidate> rungs = new ArrayList<>();
        List<String> filterExamples = new ArrayList<>();
        int filteredRungs = 0;
        for (BigDecimal k : strikes) {
            StrategyBuilder.Built built = StrategyBuilder.build(family, chain, null, spot,
                    new StrategyBuilder.BuildHints(k, sharesHeld));
            if (built == null) continue;
            // Only accept the rung whose short/long strike is EXACTLY k (target snapping can dedupe)
            boolean exact = built.legs().stream().anyMatch(l -> !l.isStock() && l.strike().compareTo(k) == 0);
            if (!exact) continue;
            long coverShares = sharesHeld
                    ? Math.max(0, io.liftandshift.strikebench.strategy.CoverageCheck.callCoverSharesNeeded(built.legs())) : 0;
            Candidate c = toCandidate(family, built, Verdict.of(List.of(), List.of()), spot, today, budget,
                    buyingPowerCents, chain.freshness(), true, StrategyFamily.Thesis.NEUTRAL,
                    intent, holdings, sharesHeld ? coverShares : 0, sharesHeld ? freeShares : 0,
                    quote, riskFreeRate, new CandidateProbe());
            if (c == null) continue;
            String filterReason = failsFilter(c, filters);
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
            if (filteredRungs > 0) {
                notes.add("No ladder rung passed every selected limit");
            } else if (!sharesHeld && (intent == StrategyIntent.HEDGE || intent == StrategyIntent.EXIT)) {
                notes.add("This goal starts from shares you own. No stock-plus-option package fits the selected "
                        + "per-idea risk limit; buy practice shares first or construct the full package in Structure.");
            } else {
                notes.add("No tradable strikes fit this ladder and its stated budget right now");
            }
        }
        if (sharesHeld) notes.add("Sized against your " + freeShares + " free shares");
        return new LadderResult(symbol, intent.name(), rungs, notes, DISCLAIMER);
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
                                  LocalDate today, long budget, long buyingPowerCents, Freshness freshness, boolean avoidEarnings,
                                  StrategyFamily.Thesis thesis, StrategyIntent intent, Holdings holdings,
                                  long coverSharesPerUnit, int freeShares, Quote underlyingQuote,
                                  double riskFreeRate, CandidateProbe probe) {
        // Re-price legs at the EXECUTABLE side (buys pay the ask, sells receive the bid) so
        // the numbers a learner sees here match what a fill would actually cost. Structures
        // whose legs have no executable side are not real opportunities.
        List<io.liftandshift.strikebench.model.Leg> executableLegs = new ArrayList<>(built.legs().size());
        for (int i = 0; i < built.legs().size(); i++) {
            io.liftandshift.strikebench.model.Leg leg = built.legs().get(i);
            if (leg.isStock()) {
                BigDecimal side = io.liftandshift.strikebench.market.ExecutablePrice.forAction(
                        underlyingQuote.bid(), underlyingQuote.ask(), leg.action());
                if (side == null) {
                    return candidateFailure(probe, "The stock leg has no executable two-sided quote");
                }
                executableLegs.add(Leg.stock(leg.action(), leg.ratio(), side));
                continue;
            }
            OptionQuote q = built.quotes().get(i);
            BigDecimal side = io.liftandshift.strikebench.market.ExecutablePrice.forAction(
                    q.bid(), q.ask(), leg.action());
            if (side == null) {
                return candidateFailure(probe, "An option leg has no executable two-sided quote");
            }
            executableLegs.add(new io.liftandshift.strikebench.model.Leg(leg.action(), leg.type(), leg.strike(),
                    leg.expiration(), leg.ratio(), side, leg.multiplier()));
        }
        built = new StrategyBuilder.Built(executableLegs, built.quotes(), built.label());
        PayoffCurve unitCurve = PayoffCurve.of(built.legs(), 1);
        long unitEntryNet = unitCurve.entryNetPremiumCents();
        boolean multiExp = family.multiExpiration();

        // Held-shares candidates carry option legs only; risk display and POP come from the
        // COMBINED position (legs + the held lot at today's price), while budget/reserve math
        // uses the trade's INCREMENTAL cash risk (a covered call adds none; a hedge costs its debit).
        boolean onHeldShares = freeShares > 0 && family.needsStock();
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
            unitMaxLoss = Math.max(0, -unitEntryNet); // incremental cash risk only
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
                && (family.needsStock() || family == StrategyFamily.CASH_SECURED_PUT);
        int qty;
        if (onHeldShares) {
            if (unitMaxLoss > budget) return candidateFailure(probe,
                    "One lot requires " + Money.fmt(unitMaxLoss) + " of incremental risk, above this Plan's "
                            + Money.fmt(budget) + " budget");
            int packagesAvailable = (int) (freeShares / displaySharesPerUnit);
            long byBudget = unitMaxLoss > 0 ? Math.max(1, budget / unitMaxLoss) : packagesAvailable;
            qty = (int) Math.clamp(Math.min((long) packagesAvailable, byBudget), 1, MAX_QTY);
        } else if (collateralBased) {
            long unitCapital = Math.max(unitMaxLoss, Math.max(0, -unitEntryNet)); // collateral or share cost per lot
            if (unitCapital > buyingPowerCents) return candidateFailure(probe,
                    "One lot needs " + Money.fmt(unitCapital) + " of capital (collateral or shares), above the account's "
                            + Money.fmt(buyingPowerCents) + " buying power");
            long lotsByPower = unitCapital > 0 ? Math.max(1, buyingPowerCents / unitCapital) : 1;
            int desiredLots = holdings != null && holdings.sharesOwned() != null && holdings.sharesOwned() > 0
                    ? Math.max(1, holdings.sharesOwned() / 100) : 1;
            qty = (int) Math.clamp(Math.min((long) desiredLots, lotsByPower), 1, MAX_QTY);
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
        // Debits also consume CASH — never suggest a position the account cannot pay for.
        long unitCashNeeded = Math.max(0, -unitEntryNet);
        if (unitCashNeeded > 0) {
            if (unitCashNeeded > buyingPowerCents) return candidateFailure(probe,
                    "One lot costs " + Money.fmt(unitCashNeeded) + ", above the account's "
                            + Money.fmt(buyingPowerCents) + " buying power");
            qty = (int) Math.clamp(Math.min((long) qty, buyingPowerCents / unitCashNeeded), 1, MAX_QTY);
        }

        PayoffCurve curve = PayoffCurve.of(onHeldShares ? unitDisplayLegs : built.legs(), qty);
        long entryNet = unitEntryNet * qty;
        long maxLoss = unitMaxLoss * qty;
        Long maxProfit = unitMaxProfit == null ? null : unitMaxProfit * qty;
        Long combinedMaxLoss = unitCombinedMaxLoss == null ? null : unitCombinedMaxLoss * qty;

        List<String> breakevens;
        Double pop;
        Long ev;
        boolean ivMissing = built.quotes().stream().filter(Objects::nonNull)
                .map(OptionQuote::iv).noneMatch(Objects::nonNull);
        if (multiExp) {
            breakevens = List.of();
            pop = null;
            ev = null;
        } else {
            breakevens = curve.breakevens().stream().map(b -> b.stripTrailingZeros().toPlainString()).toList();
            double ivAvg = built.quotes().stream().filter(Objects::nonNull).map(OptionQuote::iv)
                    .filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(0.30);
            double t = built.legs().stream().filter(l -> !l.isStock())
                    .mapToLong(l -> ChronoUnit.DAYS.between(today, l.expiration())).min().stream()
                    .mapToDouble(d -> Math.max(d, 0.5) / 365.0).findFirst().orElse(7 / 365.0);
            List<BigDecimal> shorts = built.legs().stream()
                    .filter(l -> !l.isStock() && l.action() == LegAction.SELL)
                    .map(Leg::strike).filter(Objects::nonNull).distinct().toList();
            var analyzed = io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.analyze(
                    curve, spot.doubleValue(), ivAvg, t, riskFreeRate, shorts);
            pop = analyzed.probabilityMap().pAnyProfit();
            ev = analyzed.expectedValueCents();
        }

        double liquidity = liquidityScore(built.quotes());
        boolean zeroDte = built.legs().stream().anyMatch(l -> !l.isStock() && l.expiration().equals(today));

        // ---- Intent metrics (assignment, income yield, effective share price) ----
        double ivFallback = built.quotes().stream().filter(Objects::nonNull).map(OptionQuote::iv)
                .filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(0.30);
        Double assignProb = assignmentProbability(built.legs(), built.quotes(), spot, today, ivFallback,
                riskFreeRate);
        int minDte = (int) built.legs().stream().filter(l -> !l.isStock())
                .mapToLong(l -> ChronoUnit.DAYS.between(today, l.expiration())).min().orElse(7);
        BigDecimal shortCallStrike = built.legs().stream()
                .filter(l -> !l.isStock() && l.action() == LegAction.SELL && l.type() == OptionType.CALL)
                .map(Leg::strike).findFirst().orElse(null);
        BigDecimal shortPutStrike = built.legs().stream()
                .filter(l -> !l.isStock() && l.action() == LegAction.SELL && l.type() == OptionType.PUT)
                .map(Leg::strike).findFirst().orElse(null);
        BigDecimal longPutStrike = built.legs().stream()
                .filter(l -> !l.isStock() && l.action() == LegAction.BUY && l.type() == OptionType.PUT)
                .map(Leg::strike).findFirst().orElse(null);
        List<Leg> optionLegs = built.legs().stream().filter(l -> !l.isStock()).toList();
        long packageShareUnitsPerUnit = built.legs().stream().filter(Leg::isStock)
                .mapToLong(leg -> Math.multiplyExact((long) leg.ratio(), leg.multiplier())).sum();
        if (packageShareUnitsPerUnit <= 0) {
            packageShareUnitsPerUnit = io.liftandshift.strikebench.strategy.CoverageCheck
                    .shareContextUnitsNeeded(built.legs());
        }
        if (packageShareUnitsPerUnit <= 0) packageShareUnitsPerUnit = Leg.SHARES_PER_CONTRACT;
        long optionNetCents = PayoffCurve.of(optionLegs, qty).entryNetPremiumCents();
        long optionContracts = 0;
        for (Leg optionLeg : optionLegs) optionContracts += (long) optionLeg.ratio() * qty;
        long openingFees = optionContracts * feePerContractCents
                + (optionLegs.isEmpty() ? 0 : feePerOrderCents);
        long netOptionIncomeCents = optionNetCents - openingFees;

        // Annualized yield is quoted ONLY for share-backed premium (covered calls, cash-secured
        // puts, collars) where "capital" is real: the shares or the strike cash. Annualizing a
        // narrow condor's max return-on-risk produces four-digit percentages that read as income
        // but are really low-probability best cases — that is R:R's job, not yield's.
        boolean shareBacked = family == StrategyFamily.COVERED_CALL
                || family == StrategyFamily.CASH_SECURED_PUT
                || family == StrategyFamily.PROTECTIVE_COLLAR;
        Long yieldCollateral = null;
        if (netOptionIncomeCents > 0 && shareBacked) {
            yieldCollateral = family == StrategyFamily.CASH_SECURED_PUT && shortPutStrike != null
                    ? Money.centsFromPrice(shortPutStrike, Math.multiplyExact(packageShareUnitsPerUnit, (long) qty))
                    : Money.centsFromPrice(spot, Math.multiplyExact(packageShareUnitsPerUnit, (long) qty));
        }
        Double annualYieldPct = null;
        if (yieldCollateral != null && yieldCollateral > 0) {
            annualYieldPct = round2(100.0 * (netOptionIncomeCents / (double) yieldCollateral)
                    * (365.0 / Math.max(minDte, 1)));
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
                ? (maxLoss > 0
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
                    + (collateralBased
                        ? "Sized by your buying power — it sets aside " + Money.fmt(maxLoss) + " of capital"
                            + (family == StrategyFamily.CASH_SECURED_PUT
                                ? " (the cash to buy the shares at the strike if you are assigned)"
                                : family.needsStock() ? " (the 100 shares the call is written against)" : "")
                            + ", within your " + Money.fmt(buyingPowerCents) + " account — this is collateral you hold, not a fee you lose."
                        : maxLoss > 0 ? "Sized to keep new cash at risk within your " + Money.fmt(budget) + " budget." : "");
        boolean includesStockLeg = built.legs().stream().anyMatch(Leg::isStock);
        String beginner = beginnerText(family, entryNet, optionNetCents, includesStockLeg);
        String intentNote = intentNote(intent, family, holdings, spot, qty, netOptionIncomeCents, minDte,
                effectivePrice, assignProb, annualYieldPct, shortCallStrike, shortPutStrike, longPutStrike,
                onHeldShares, packageShareUnitsPerUnit);

        List<String> candidateWarnings = new ArrayList<>(verdict.warnings());
        if (ivMissing && !multiExp) {
            candidateWarnings.add("No implied volatility available — POP/EV assume a 30% placeholder volatility");
        }
        return new Candidate(family.name(), family.display(), family.structureGroup(), built.label(),
                built.legs().stream().map(LegView::of).toList(), qty,
                entryNet, maxProfit, maxLoss, breakevens, pop, ev,
                round2(liquidity), freshness.name(), candidateWarnings,
                round2(confidence), why, upside, risk, invalidate, beginner,
                intent.name(), family.intents().stream().map(Enum::name).sorted().toList(),
                assignProb,
                annualYieldPct, effectivePrice, intentNote,
                onHeldShares ? Boolean.TRUE : null,
                onHeldShares ? Math.toIntExact(Math.multiplyExact(displaySharesPerUnit, (long) qty)) : null,
                combinedMaxLoss);
    }

    private static final class CandidateProbe {
        private String reason;
    }

    private static Candidate candidateFailure(CandidateProbe probe, String reason) {
        if (probe != null) probe.reason = reason;
        return null;
    }

    /**
     * Modeled chance that at least one short leg finishes in the money at ITS expiration —
     * risk-neutral N(d2)/N(-d2) at each leg's own IV and the lane's rate (q=0 assumption),
     * with no early-assignment model. At one expiration, nested same-side strikes collapse to the
     * outer event (lowest call / highest put); put and call tails are disjoint unless they overlap.
     * Different expirations are summed and capped, making the multi-expiration result a conservative
     * upper bound rather than a false claim of joint-path precision.
     */
    private static Double assignmentProbability(List<Leg> legs, List<OptionQuote> quotes,
                                                BigDecimal spot, LocalDate today, double ivFallback,
                                                double riskFreeRate) {
        List<Double> ivs = new ArrayList<>();
        for (int i = 0; i < legs.size(); i++) {
            OptionQuote q = quotes != null && i < quotes.size() ? quotes.get(i) : null;
            ivs.add(q == null ? null : q.iv());
        }
        return assignmentProbabilityFromIvs(legs, ivs, spot, today, ivFallback, riskFreeRate);
    }

    /**
     * N(d2)/N(-d2) over the UNION of short-ITM regions at each expiration -- shared with the
     * trade-preview path so the builder shows the same number the engine would.
     * {@code ivsAligned} is index-aligned with {@code legs} (null entries fall back).
     */
    public static Double assignmentProbabilityFromIvs(List<Leg> legs, List<Double> ivsAligned,
                                                      BigDecimal spot, LocalDate today, double ivFallback,
                                                      double riskFreeRate) {
        if (spot == null || spot.signum() <= 0) return null;
        java.util.Map<LocalDate, Integer> lowestCall = new java.util.LinkedHashMap<>();
        java.util.Map<LocalDate, Integer> highestPut = new java.util.LinkedHashMap<>();
        for (int i = 0; i < legs.size(); i++) {
            Leg l = legs.get(i);
            if (l.isStock() || l.action() != LegAction.SELL) continue;
            java.util.Map<LocalDate, Integer> target = l.type() == OptionType.CALL ? lowestCall : highestPut;
            Integer prior = target.get(l.expiration());
            if (prior == null || (l.type() == OptionType.CALL
                    ? l.strike().compareTo(legs.get(prior).strike()) < 0
                    : l.strike().compareTo(legs.get(prior).strike()) > 0)) {
                target.put(l.expiration(), i);
            }
        }
        java.util.Set<LocalDate> expirations = new java.util.LinkedHashSet<>(lowestCall.keySet());
        expirations.addAll(highestPut.keySet());
        if (expirations.isEmpty()) return null;

        double total = 0;
        for (LocalDate expiration : expirations) {
            Integer ci = lowestCall.get(expiration);
            Integer pi = highestPut.get(expiration);
            if (ci != null && pi != null
                    && legs.get(pi).strike().compareTo(legs.get(ci).strike()) >= 0) {
                total += 1.0; // S<put OR S>call covers the whole line when the regions overlap
                continue;
            }
            if (ci != null) total += finishItmProbability(legs.get(ci), alignedIv(ivsAligned, ci),
                    spot, today, ivFallback, riskFreeRate);
            if (pi != null) total += finishItmProbability(legs.get(pi), alignedIv(ivsAligned, pi),
                    spot, today, ivFallback, riskFreeRate);
        }
        return Math.min(1.0, total);
    }

    private static Double alignedIv(List<Double> ivs, int index) {
        return ivs != null && index < ivs.size() ? ivs.get(index) : null;
    }

    private static double finishItmProbability(Leg leg, Double iv, BigDecimal spot, LocalDate today,
                                               double ivFallback, double riskFreeRate) {
        double sigma = iv != null && iv > 0 ? iv : ivFallback;
        double t = Math.max(ChronoUnit.DAYS.between(today, leg.expiration()), 0.5) / 365.0;
        double d1 = BlackScholes.d1(spot.doubleValue(), leg.strike().doubleValue(), t,
                riskFreeRate, 0, sigma);
        double d2 = d1 - sigma * Math.sqrt(t);
        return leg.type() == OptionType.CALL ? BlackScholes.normCdf(d2) : BlackScholes.normCdf(-d2);
    }

    /** Human framing of the candidate against the user's goal, holdings and target price. */
    private static String intentNote(StrategyIntent intent, StrategyFamily family, Holdings holdings,
                                     BigDecimal spot, int qty, long netOptionPremium, int minDte,
                                     String effectivePrice, Double assignProb, Double annualYieldPct,
                                     BigDecimal shortCallStrike, BigDecimal shortPutStrike, BigDecimal longPutStrike,
                                     boolean onHeldShares, long displaySharesPerUnit) {
        long shares = Math.multiplyExact(Math.max(displaySharesPerUnit, 1), (long) qty);
        String assignPct = assignProb == null ? null : String.format("~%.0f%%", assignProb * 100);
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
                sb.append(". ").append(assignPct != null
                        ? "Chance that happens by expiration: " + assignPct + " (that's the goal — not a risk)."
                        : "");
                if (netOptionPremium > 0) sb.append(" Either way the premium leaves ")
                        .append(Money.fmt(netOptionPremium)).append(" after opening fees.");
                return sb.toString().trim();
            }
            case ACQUIRE -> {
                if (shortPutStrike == null) return null;
                StringBuilder sb = new StringBuilder();
                sb.append("If assigned you buy ").append(shares).append(" shares at $")
                        .append(shortPutStrike.stripTrailingZeros().toPlainString());
                if (effectivePrice != null) sb.append(" — effectively $").append(effectivePrice).append("/sh after the premium");
                if (spot != null && spot.signum() > 0 && effectivePrice != null) {
                    double disc = 100.0 * (spot.doubleValue() - Double.parseDouble(effectivePrice)) / spot.doubleValue();
                    sb.append(String.format(", %.1f%% below today's $%s", disc, spot.stripTrailingZeros().toPlainString()));
                }
                sb.append(". ").append(assignPct != null
                        ? "Chance you get the shares: " + assignPct + " (that's the goal)."
                        : "");
                if (netOptionPremium > 0 && annualYieldPct != null) {
                    sb.append(" If not, the premium leaves ").append(Money.fmt(netOptionPremium)).append(" after opening fees")
                            .append(String.format(" (~%.1f%%/yr while you wait).", annualYieldPct));
                }
                return sb.toString().trim();
            }
            case INCOME -> {
                if (netOptionPremium <= 0) return null;
                StringBuilder sb = new StringBuilder("Collect " + Money.fmt(netOptionPremium) + " net after opening fees");
                if (annualYieldPct != null) sb.append(String.format(" — ~%.1f%%/yr on the capital at risk over %d days", annualYieldPct, Math.max(minDte, 1)));
                sb.append(".");
                if (assignPct != null) sb.append(" Chance the short side finishes in the money: ").append(assignPct).append(".");
                if (onHeldShares) sb.append(" Written against shares you already hold.");
                return sb.toString();
            }
            case HEDGE -> {
                if (longPutStrike == null) return null;
                StringBuilder sb = new StringBuilder();
                sb.append("Guarantees a sale price of at least $")
                        .append(longPutStrike.stripTrailingZeros().toPlainString())
                        .append(" for ").append(shares).append(" shares until expiration");
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
    private static String failsFilter(Candidate c, Filters f) {
        if (f.minPop() != null) {
            if (c.pop() == null) return String.format("No modeled POP available, but you require at least %.0f%%", f.minPop() * 100);
            if (c.pop() < f.minPop()) return String.format("Modeled POP %.0f%% is below your minimum %.0f%%", c.pop() * 100, f.minPop() * 100);
        }
        if (f.maxAssignmentProb() != null && c.assignmentProb() != null && c.assignmentProb() > f.maxAssignmentProb()) {
            return String.format("Assignment probability %.0f%% exceeds your cap of %.0f%%", c.assignmentProb() * 100, f.maxAssignmentProb() * 100);
        }
        if (f.minAnnualizedYieldPct() != null) {
            if (c.annualizedYieldPct() == null) return "No share-backed premium yield to hold against your minimum (yield applies to covered calls, cash-secured puts and collars)";
            if (c.annualizedYieldPct() < f.minAnnualizedYieldPct()) {
                return String.format("Annualized yield %.1f%% is below your minimum %.1f%%", c.annualizedYieldPct(), f.minAnnualizedYieldPct());
            }
        }
        if (f.maxCostCents() != null && c.entryNetPremiumCents() < 0 && -c.entryNetPremiumCents() > f.maxCostCents()) {
            return "Entry cost " + Money.fmt(-c.entryNetPremiumCents()) + " exceeds your cap of " + Money.fmt(f.maxCostCents());
        }
        return null;
    }

    private static double liquidityScore(List<OptionQuote> quotes) {
        double worstSpread = quotes.stream().filter(Objects::nonNull)
                .mapToDouble(OptionQuote::spreadPct).filter(d -> !Double.isNaN(d)).max().orElse(0.05);
        return Math.clamp(1.0 - worstSpread / 0.15, 0, 1);
    }

    private static String beginnerText(StrategyFamily family, long entryNet, long optionEntryNet,
                                       boolean includesStockLeg) {
        String cash;
        if (family.needsStock()) {
            String optionCash = optionEntryNet > 0
                    ? "The option legs collect " + Money.fmt(optionEntryNet) + " net up front."
                    : optionEntryNet < 0
                        ? "The option legs cost " + Money.fmt(-optionEntryNet) + " net up front."
                        : "The option legs have no net entry premium.";
            if (includesStockLeg) {
                String packageCash = entryNet > 0
                        ? "The complete stock-plus-options package collects " + Money.fmt(entryNet) + " net up front."
                        : entryNet < 0
                            ? "The complete stock-plus-options package costs " + Money.fmt(-entryNet) + " up front."
                            : "The complete stock-plus-options package has no net entry payment.";
                cash = packageCash + " " + optionCash
                        + " The shares are part of that package value and keep their own upside and downside.";
            } else {
                cash = optionCash + " Your existing shares remain a separate source of gain or loss.";
            }
        } else {
            cash = entryNet >= 0
                    ? "You collect " + Money.fmt(entryNet) + " up front and keep it if the trade works out."
                    : "You pay " + Money.fmt(-entryNet)
                        + " up front — that payment is the most you can lose.";
        }
        return switch (family) {
            case LONG_CALL -> "Buying a call is a bet the stock rises above the strike before expiration. " + cash;
            case LONG_PUT -> "Buying a put is a bet the stock falls below the strike before expiration. " + cash;
            case DEBIT_CALL_SPREAD -> "You buy one call and sell a higher one to cut the cost; profit is capped but so is loss. " + cash;
            case DEBIT_PUT_SPREAD -> "You buy one put and sell a lower one to cut the cost; profit is capped but so is loss. " + cash;
            case CREDIT_CALL_SPREAD -> "You sell a call above the price and buy a higher one as insurance; you win if the stock stays below the short strike. " + cash;
            case CREDIT_PUT_SPREAD -> "You sell a put below the price and buy a lower one as insurance; you win if the stock stays above the short strike. " + cash;
            case IRON_CONDOR -> "Two credit spreads at once: you win if the stock stays inside a range. " + cash;
            case IRON_BUTTERFLY -> "Like an iron condor but centered exactly at the money — bigger credit, narrower sweet spot. " + cash;
            case LONG_CALL_BUTTERFLY, LONG_PUT_BUTTERFLY -> "A pinned bet that the stock finishes near the middle strike. Cheap to buy, capped both ways. " + cash;
            case CALENDAR_CALL, CALENDAR_PUT -> "You sell a near-term option and buy a longer one at the same strike, hoping time decays the near one faster. " + cash;
            case DIAGONAL_CALL, DIAGONAL_PUT -> "Like a calendar but the strikes differ, adding a directional lean. " + cash;
            case COVERED_CALL -> (includesStockLeg
                    ? "You buy shares and rent them out by selling a call; income now, upside capped. "
                    : "You own shares and rent them out by selling a call; income now, upside capped. ") + cash;
            case COVERED_STRANGLE -> "A covered call plus a cash-secured put: you collect two premiums, cap the upside, and stand ready to buy 100 more shares at the put strike. " + cash;
            case COVERED_CALL_PUT_SPREAD -> "You own shares, rent out the upside with a call, and use the premium to buy a put spread - a protected shelf that absorbs losses down to the lower put strike. " + cash;
            case COVERED_CALL_CALL_OVERLAY -> "A covered call plus a farther long call: income now, and if the stock runs past the overlay strike your upside participation resumes. " + cash;
            case CASH_SECURED_PUT -> "You sell a put with the cash set aside to buy the shares if assigned — getting paid to bid below the market. " + cash;
            case PROTECTIVE_COLLAR -> "You own shares, buy a put as a floor, and sell a call to pay for it. " + cash;
            case PROTECTIVE_PUT -> "You own shares and buy a put as insurance: a guaranteed minimum sale price until expiration. " + cash;
            default -> cash;
        };
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
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
     *   - INCOME must COLLECT premium: a single-expiration, pure-option income structure that pays a
     *     net debit is a "pay-to-earn-income" contradiction. Stock-backed families (covered call /
     *     cash-secured put / collar) are excluded from the debit test — their entry nets the share
     *     cost, while the income is the option credit — and multi-expiration calendars are exempt
     *     because their income is theta collected over rolls, not an entry credit.
     * Returns a human reason to reject, or null when the structure is coherent with the intent.
     */
    private static String intentIncoherence(StrategyIntent intent, StrategyFamily family, Candidate c) {
        if (c.maxProfitCents() != null && c.maxProfitCents() <= 0) {
            return "At executable prices this structure cannot profit under any outcome (max profit "
                    + Money.fmt(c.maxProfitCents()) + ") — not a usable trade.";
        }
        if (intent == StrategyIntent.INCOME && !family.multiExpiration() && !family.needsStock()
                && c.entryNetPremiumCents() <= 0) {
            return "Earning income means COLLECTING premium, but this structure pays a net debit of "
                    + Money.fmt(-c.entryNetPremiumCents())
                    + " at entry — it costs money to hold rather than paying you to wait.";
        }
        return null;
    }

    /** Structural viability only; the complete economics/evidence judgment remains downstream. */
    static String packageViability(StrategyFamily family, Candidate c) {
        if (family != StrategyFamily.IRON_CONDOR) return null;
        long credit = c.entryNetPremiumCents();
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
