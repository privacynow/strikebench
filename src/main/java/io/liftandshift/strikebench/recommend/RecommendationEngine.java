package io.liftandshift.strikebench.recommend;

import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.MarketHours;
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
            + "POP/EV/breakevens are model outputs computed before commissions with zero price drift.";

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
     * The historical 'learning' wire value maps to CONSERVATIVE at this boundary only.
     */
    public enum RiskMode {
        CONSERVATIVE(0.01), BALANCED(0.02), AGGRESSIVE(0.05);
        final double defaultRiskPct;
        RiskMode(double defaultRiskPct) { this.defaultRiskPct = defaultRiskPct; }

        public double defaultRiskPct() { return defaultRiskPct; }

        public static RiskMode parse(String s) {
            if (s == null) return CONSERVATIVE;
            String v = s.trim().toUpperCase(Locale.ROOT);
            if (v.equals("LEARNING")) return CONSERVATIVE; // legacy wire value, API compat only
            try { return valueOf(v); }
            catch (IllegalArgumentException e) { return CONSERVATIVE; }
        }
    }

    public record Request(
            String symbol,
            String thesis,               // bullish | bearish | neutral | volatile
            String horizon,              // 0DTE | week | month | quarter
            String riskMode,             // learning | conservative | balanced | aggressive
            Long maxLossCents,           // absolute per-trade budget, optional
            Double maxRiskPctOfAccount,  // optional, defaults by risk mode
            Double minConfidence,        // 0..1, optional
            List<String> allowedStrategies, // optional whitelist
            Boolean avoidEarnings,
            Boolean allow0dte,
            String intent,               // StrategyIntent name; null/blank = DIRECTIONAL (historic behavior)
            Holdings holdings,           // shares context for EXIT/HEDGE/ACQUIRE flows, optional
            Filters filters              // hard screens on candidate metrics, optional
    ) {
        /** Historical 10-field shape (directional flow, no holdings, no filters). */
        public Request(String symbol, String thesis, String horizon, String riskMode, Long maxLossCents,
                       Double maxRiskPctOfAccount, Double minConfidence, List<String> allowedStrategies,
                       Boolean avoidEarnings, Boolean allow0dte) {
            this(symbol, thesis, horizon, riskMode, maxLossCents, maxRiskPctOfAccount, minConfidence,
                    allowedStrategies, avoidEarnings, allow0dte, null, null, null);
        }
    }

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
            List<Candidate> candidates,
            List<Rejection> rejected,
            List<String> notes,
            String disclaimer
    ) {}

    private final MarketDataService market;
    private final Clock clock;

    public RecommendationEngine(MarketDataService market, Clock clock) {
        this.market = market;
        this.clock = clock;
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
        return recommendInner(req, buyingPowerCents, worldId);
    }

    private Result recommendInner(Request req, long buyingPowerCents, String worldId) {
        String symbol = req.symbol() == null ? "" : req.symbol().trim().toUpperCase(Locale.ROOT);
        RiskMode mode = RiskMode.parse(req.riskMode());
        StrategyIntent intent = StrategyIntent.parse(req.intent());
        boolean thesisExplicit = req.thesis() != null && !req.thesis().isBlank();
        StrategyFamily.Thesis thesis = parseThesis(req.thesis());
        Holdings holdings = req.holdings();
        int freeShares = holdings != null && holdings.sharesOwned() != null ? Math.max(0, holdings.sharesOwned()) : 0;
        BigDecimal targetPrice = holdings != null && holdings.targetPriceCents() != null && holdings.targetPriceCents() > 0
                ? Money.priceFromCents(holdings.targetPriceCents()) : null;
        Filters filters = req.filters() == null ? new Filters(null, null, null, null) : req.filters();
        boolean allow0dte = Boolean.TRUE.equals(req.allow0dte());
        boolean avoidEarnings = req.avoidEarnings() == null || req.avoidEarnings();
        double riskPct = req.maxRiskPctOfAccount() != null ? Math.clamp(req.maxRiskPctOfAccount(), 0.001, 0.5) : mode.defaultRiskPct;
        long budget = Math.round(buyingPowerCents * riskPct);
        if (req.maxLossCents() != null && req.maxLossCents() > 0) budget = Math.min(budget, req.maxLossCents());
        double minConfidence = req.minConfidence() == null ? 0 : req.minConfidence();

        List<String> notes = new ArrayList<>();
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
            notes.add("No market data available for " + symbol);
            return new Result(symbol, thesis.name(), req.horizon(), mode.name(), intent.name(), budget, List.of(), rejected, notes, DISCLAIMER);
        }
        if (!quote.evidence().usableIn(lane)) {
            notes.add("No " + lane + "-lane quote is available for " + symbol + "; refusing to substitute "
                    + quote.evidence().provenance() + " data from " + quote.evidence().source());
            return new Result(symbol, thesis.name(), req.horizon(), mode.name(), intent.name(), budget,
                    List.of(), rejected, notes, DISCLAIMER);
        }
        List<LocalDate> expirations = market.expirations(symbol, worldId);
        if (!quote.optionable() || expirations.isEmpty()) {
            notes.add(symbol + " has no listed options (mutual funds and some securities cannot be traded with options)");
            return new Result(symbol, thesis.name(), req.horizon(), mode.name(), intent.name(), budget, List.of(), rejected, notes, DISCLAIMER);
        }

        LocalDate today = LocalDate.ofInstant(laneNow, MarketHours.EASTERN);
        LocalDate near = pickExpiration(expirations, req.horizon(), today, allow0dte, laneNow, notes);
        if (near == null) {
            notes.add("No expiration matches the requested horizon");
            return new Result(symbol, thesis.name(), req.horizon(), mode.name(), intent.name(), budget, List.of(), rejected, notes, DISCLAIMER);
        }
        OptionChain chain = market.chain(symbol, near, worldId).orElse(null);
        if (chain == null || chain.isEmpty()) {
            notes.add("Option chain unavailable for " + symbol + " " + near);
            return new Result(symbol, thesis.name(), req.horizon(), mode.name(), intent.name(), budget, List.of(), rejected, notes, DISCLAIMER);
        }
        if (!chain.evidence().executableIn(lane)) {
            notes.add("The " + lane + " market has no executable option chain for " + symbol + " " + near
                    + "; refusing " + chain.evidence().provenance() + " data from " + chain.evidence().source());
            return new Result(symbol, thesis.name(), req.horizon(), mode.name(), intent.name(), budget,
                    List.of(), rejected, notes, DISCLAIMER);
        }
        // Far expiration for calendars/diagonals: ~4 weekly slots beyond the near one
        int nearIdx = expirations.indexOf(near);
        LocalDate far = nearIdx >= 0 && nearIdx + 4 < expirations.size() ? expirations.get(nearIdx + 4)
                : expirations.getLast().isAfter(near) ? expirations.getLast() : null;
        OptionChain farChain = far == null ? null : market.chain(symbol, far, worldId).orElse(null);
        if (farChain != null && !farChain.evidence().executableIn(lane)) farChain = null;

        BigDecimal spot = chain.underlyingPrice();
        boolean earningsSoon = market.news(symbol, worldId).stream().anyMatch(n -> {
            String h = n.headline() == null ? "" : n.headline().toLowerCase(Locale.ROOT);
            return h.contains("earnings") || h.contains("guidance") || h.contains("results");
        });

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
                if (!family.fits(thesis)) continue;
            } else {
                // Intent flows pick families by purpose; an explicit thesis narrows further.
                if (!family.servesIntent(intent) && !family.blockedByDefault()) continue;
                if (family.blockedByDefault() && !family.servesIntent(intent)) continue;
                if (thesisExplicit && !family.fits(thesis)) continue;
            }
            // RISK MODE IS A BUDGET, NOT A COMPLEXITY LADDER (risk/experience decoupling): every
            // mode sees the SAME defined-risk catalog — the actual position's max loss, EV, tail,
            // liquidity and the DecisionPolicy decide what ranks. A diagonal is not automatically
            // riskier than a long call.
            if (req.allowedStrategies() != null && !req.allowedStrategies().isEmpty()
                    && req.allowedStrategies().stream().noneMatch(s -> s.equalsIgnoreCase(family.name()))) {
                continue;
            }

            StrategyBuilder.Built built = StrategyBuilder.build(family, chain, farChain, spot, hints);
            if (built == null) continue;
            boolean builtOnHeldShares = sharesHeld && family.needsStock();

            // A structure whose computed worst case is <= $0 is a quote-integrity failure,
            // never an opportunity — surface it as rejected with the real reason.
            if (!family.multiExpiration()) {
                PayoffCurve integrity = PayoffCurve.of(built.legs(), 1);
                if (!integrity.maxLossUnbounded() && integrity.maxLossCents() <= 0) {
                    rejected.add(new Rejection(family.name(), family.display(),
                            List.of("Priced as risk-free by the current quotes — impossible; stale or crossed data, skipped")));
                    continue;
                }
            }

            int coverLotsPerUnit = builtOnHeldShares
                    ? Math.max(0, io.liftandshift.strikebench.strategy.CoverageCheck.callCoverLotsNeeded(built.legs()))
                    : 0;
            Verdict verdict = Guardrails.check(new Guardrails.Proposal(
                    family, built.legs(), 1, built.quotes(), spot, chain.freshness(), today,
                    buyingPowerCents, false, avoidEarnings && earningsSoon, false, coverLotsPerUnit));
            if (verdict.blocked()) {
                rejected.add(new Rejection(family.name(), family.display(), verdict.blockReasons()));
                continue;
            }

            Candidate candidate = toCandidate(family, built, verdict, spot, today, budget, buyingPowerCents,
                    chain.freshness(), avoidEarnings, thesis, intent, holdings,
                    builtOnHeldShares ? coverLotsPerUnit : 0, builtOnHeldShares ? freeShares : 0);
            if (candidate == null) {
                rejected.add(new Rejection(family.name(), family.display(),
                        List.of("Minimum position size exceeds the risk budget of " + Money.fmt(budget))));
                continue;
            }
            if (candidate.confidence() < minConfidence) {
                rejected.add(new Rejection(family.name(), family.display(),
                        List.of(String.format("Confidence %.2f is below your minimum %.2f", candidate.confidence(), minConfidence))));
                continue;
            }
            String filterReason = failsFilter(candidate, filters);
            if (filterReason != null) {
                rejected.add(new Rejection(family.name(), family.display(), List.of(filterReason)));
                continue;
            }
            candidates.add(candidate);
        }

        // Always show a blocked undefined-risk example for education, even if not requested
        if (rejected.stream().noneMatch(r -> r.strategy().equals(StrategyFamily.NAKED_CALL.name()))) {
            StrategyBuilder.Built naked = StrategyBuilder.build(StrategyFamily.NAKED_CALL, chain, farChain, spot);
            if (naked != null) {
                Verdict v = Guardrails.check(new Guardrails.Proposal(StrategyFamily.NAKED_CALL, naked.legs(), 1,
                        naked.quotes(), spot, chain.freshness(), today, buyingPowerCents, false, false, false));
                rejected.add(new Rejection(StrategyFamily.NAKED_CALL.name(), StrategyFamily.NAKED_CALL.display(),
                        v.blockReasons().isEmpty() ? List.of("Undefined risk — blocked by default") : v.blockReasons()));
            }
        }

        candidates.sort(Comparator.comparingDouble(Candidate::score).reversed());
        // RANKING TRUTH: the engine returns the COMPLETE score-sorted list. Structural diversity
        // is a PRESENTATION concern (the UI may summarize with representatives per shape group,
        // with a Show-all affordance) — it must never rewrite the engine's ranked truth.
        if (candidates.isEmpty()) notes.add("No strategy passed the risk screens for this combination — try a wider risk budget or different horizon");
        return new Result(symbol, thesis.name(), req.horizon(), mode.name(), intent.name(), budget, candidates, rejected, notes, DISCLAIMER);
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
        return ladderInner(req, buyingPowerCents, worldId);
    }

    private LadderResult ladderInner(Request req, long buyingPowerCents, String worldId) {
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
        if (!MarketHours.isRegularSession(clock.instant())) {
            notes.add("The market is closed — these rungs are measured off the PRIOR CLOSE and can shift at the open.");
        }
        Holdings holdings = req.holdings();
        int freeShares = holdings != null && holdings.sharesOwned() != null ? Math.max(0, holdings.sharesOwned()) : 0;
        boolean sharesHeld = freeShares >= 100 && intent != StrategyIntent.ACQUIRE;

        var lane = market.lane(worldId);
        Quote quote = market.quote(symbol, worldId).orElse(null);
        List<LocalDate> expirations = quote == null ? List.of() : market.expirations(symbol, worldId);
        if (quote == null || !quote.optionable() || expirations.isEmpty()) {
            notes.add(quote == null ? "No market data available for " + symbol
                    : symbol + " has no listed options");
            return new LadderResult(symbol, intent.name(), List.of(), notes, DISCLAIMER);
        }
        if (!quote.evidence().usableIn(lane)) {
            notes.add("No " + lane + "-lane quote is available for " + symbol);
            return new LadderResult(symbol, intent.name(), List.of(), notes, DISCLAIMER);
        }
        java.time.Instant ladderNow = market.simInstant(worldId).orElseGet(clock::instant);
        LocalDate today = LocalDate.ofInstant(ladderNow, MarketHours.EASTERN);
        LocalDate near = pickExpiration(expirations, req.horizon(), today, false, ladderNow, notes);
        OptionChain chain = near == null ? null : market.chain(symbol, near, worldId).orElse(null);
        if (chain == null || chain.isEmpty()) {
            notes.add("Option chain unavailable for " + symbol);
            return new LadderResult(symbol, intent.name(), List.of(), notes, DISCLAIMER);
        }
        if (!chain.evidence().executableIn(lane)) {
            notes.add("The " + lane + " market has no executable option chain for " + symbol);
            return new LadderResult(symbol, intent.name(), List.of(), notes, DISCLAIMER);
        }
        BigDecimal spot = chain.underlyingPrice();
        // ACQUIRE budget follows the recommend() rule: strike cash is the design, not a breach
        RiskMode mode = RiskMode.parse(req.riskMode());
        double riskPct = req.maxRiskPctOfAccount() != null ? Math.clamp(req.maxRiskPctOfAccount(), 0.001, 0.5) : mode.defaultRiskPct;
        long budget = intent == StrategyIntent.ACQUIRE && req.maxRiskPctOfAccount() == null && req.maxLossCents() == null
                ? buyingPowerCents
                : Math.min(Math.round(buyingPowerCents * riskPct) == 0 ? buyingPowerCents : Math.round(buyingPowerCents * riskPct),
                           req.maxLossCents() != null && req.maxLossCents() > 0 ? req.maxLossCents() : Long.MAX_VALUE);
        if (intent == StrategyIntent.HEDGE || intent == StrategyIntent.EXIT) budget = Math.max(budget, buyingPowerCents / 10);

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
        for (BigDecimal k : strikes) {
            StrategyBuilder.Built built = StrategyBuilder.build(family, chain, null, spot,
                    new StrategyBuilder.BuildHints(k, sharesHeld));
            if (built == null) continue;
            // Only accept the rung whose short/long strike is EXACTLY k (target snapping can dedupe)
            boolean exact = built.legs().stream().anyMatch(l -> !l.isStock() && l.strike().compareTo(k) == 0);
            if (!exact) continue;
            int coverLots = sharesHeld ? Math.max(0, io.liftandshift.strikebench.strategy.CoverageCheck.callCoverLotsNeeded(built.legs())) : 0;
            Candidate c = toCandidate(family, built, Verdict.of(List.of(), List.of()), spot, today, budget,
                    buyingPowerCents, chain.freshness(), true, StrategyFamily.Thesis.NEUTRAL,
                    intent, holdings, sharesHeld ? coverLots : 0, sharesHeld ? freeShares : 0);
            if (c == null) continue;
            if (rungs.stream().noneMatch(r -> r.label().equals(c.label()))) rungs.add(c);
        }
        if (rungs.isEmpty()) notes.add("No tradable strikes for this ladder right now");
        if (sharesHeld) notes.add("Sized against your " + freeShares + " free shares");
        return new LadderResult(symbol, intent.name(), rungs, notes, DISCLAIMER);
    }

    // ---- Scoring & explanation ----

    private Candidate toCandidate(StrategyFamily family, StrategyBuilder.Built built, Verdict verdict, BigDecimal spot,
                                  LocalDate today, long budget, long buyingPowerCents, Freshness freshness, boolean avoidEarnings,
                                  StrategyFamily.Thesis thesis, StrategyIntent intent, Holdings holdings,
                                  int coverLotsPerUnit, int freeShares) {
        // Re-price legs at the EXECUTABLE side (buys pay the ask, sells receive the bid) so
        // the numbers a learner sees here match what a fill would actually cost. Structures
        // whose legs have no executable side are not real opportunities.
        List<io.liftandshift.strikebench.model.Leg> executableLegs = new ArrayList<>(built.legs().size());
        for (int i = 0; i < built.legs().size(); i++) {
            io.liftandshift.strikebench.model.Leg leg = built.legs().get(i);
            if (leg.isStock()) { executableLegs.add(leg); continue; }
            OptionQuote q = built.quotes().get(i);
            BigDecimal side = leg.action() == io.liftandshift.strikebench.model.LegAction.BUY ? q.ask() : q.bid();
            if (side == null || side.signum() <= 0
                    || (q.bid() != null && q.ask() != null && q.bid().compareTo(q.ask()) > 0)) {
                return null; // one-sided, empty, or crossed book — unbuildable in reality
            }
            executableLegs.add(new io.liftandshift.strikebench.model.Leg(leg.action(), leg.type(), leg.strike(),
                    leg.expiration(), leg.ratio(), side));
        }
        built = new StrategyBuilder.Built(executableLegs, built.quotes(), built.label());
        PayoffCurve unitCurve = PayoffCurve.of(built.legs(), 1);
        long unitEntryNet = unitCurve.entryNetPremiumCents();
        boolean multiExp = family.multiExpiration();

        // Held-shares candidates carry option legs only; risk display and POP come from the
        // COMBINED position (legs + the held lot at today's price), while budget/reserve math
        // uses the trade's INCREMENTAL cash risk (a covered call adds none; a hedge costs its debit).
        boolean onHeldShares = freeShares > 0 && family.needsStock();
        int displayLotsPerUnit = onHeldShares ? Math.max(coverLotsPerUnit, 1) : 0;
        List<Leg> unitDisplayLegs = built.legs();
        if (onHeldShares) {
            unitDisplayLegs = new ArrayList<>(built.legs());
            unitDisplayLegs.add(Leg.stock(LegAction.BUY, displayLotsPerUnit, spot));
        }
        PayoffCurve unitDisplayCurve = onHeldShares ? PayoffCurve.of(unitDisplayLegs, 1) : unitCurve;

        long unitMaxLoss;
        Long unitMaxProfit;
        Long unitCombinedMaxLoss = null;
        if (multiExp) {
            if (unitEntryNet >= 0) return null; // credit calendars blocked upstream; be safe
            unitMaxLoss = -unitEntryNet;
            unitMaxProfit = null;
        } else if (onHeldShares) {
            if (unitDisplayCurve.maxLossUnbounded()) return null; // shares don't cover this shape
            unitMaxLoss = Math.max(0, -unitEntryNet); // incremental cash risk only
            unitCombinedMaxLoss = unitDisplayCurve.maxLossCents();
            unitMaxProfit = unitDisplayCurve.maxProfitUnbounded() ? null : unitDisplayCurve.maxProfitCents();
        } else {
            if (unitCurve.maxLossUnbounded()) return null;
            unitMaxLoss = unitCurve.maxLossCents();
            unitMaxProfit = unitCurve.maxProfitUnbounded() ? null : unitCurve.maxProfitCents();
        }
        int qty;
        if (onHeldShares) {
            if (unitMaxLoss > budget) return null;
            int lotsAvailable = Math.max(1, freeShares / (100 * displayLotsPerUnit));
            long byBudget = unitMaxLoss > 0 ? Math.max(1, budget / unitMaxLoss) : lotsAvailable;
            qty = (int) Math.clamp(Math.min((long) lotsAvailable, byBudget), 1, MAX_QTY);
        } else {
            if (unitMaxLoss <= 0 || unitMaxLoss > budget) {
                if (unitMaxLoss > budget) return null;
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
            if (unitCashNeeded > buyingPowerCents) return null;
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
            pop = curve.probProfit(spot.doubleValue(), ivAvg, t, 0);
            ev = curve.expectedValueCents(spot.doubleValue(), ivAvg, t, 0);
        }

        double liquidity = liquidityScore(built.quotes());
        boolean zeroDte = built.legs().stream().anyMatch(l -> !l.isStock() && l.expiration().equals(today));

        // ---- Intent metrics (assignment, income yield, effective share price) ----
        double ivFallback = built.quotes().stream().filter(Objects::nonNull).map(OptionQuote::iv)
                .filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(0.30);
        Double assignProb = assignmentProbability(built.legs(), built.quotes(), spot, today, ivFallback);
        int minDte = (int) built.legs().stream().filter(l -> !l.isStock())
                .mapToLong(l -> ChronoUnit.DAYS.between(today, l.expiration())).min().orElse(7);
        // Annualized yield is quoted ONLY for share-backed premium (covered calls, cash-secured
        // puts, collars) where "capital" is real: the shares or the strike cash. Annualizing a
        // narrow condor's max return-on-risk produces four-digit percentages that read as income
        // but are really low-probability best cases — that is R:R's job, not yield's.
        boolean shareBacked = family == StrategyFamily.COVERED_CALL
                || family == StrategyFamily.CASH_SECURED_PUT
                || family == StrategyFamily.PROTECTIVE_COLLAR;
        Long yieldCollateral = null;
        if (entryNet > 0 && shareBacked) {
            yieldCollateral = onHeldShares
                    ? Money.centsFromPrice(spot, 100L * displayLotsPerUnit * qty) // income on share capital
                    : maxLoss;                                                    // income on capital at risk
        }
        Double annualYieldPct = null;
        if (yieldCollateral != null && yieldCollateral > 0) {
            annualYieldPct = round2(100.0 * (entryNet / (double) yieldCollateral) * (365.0 / Math.max(minDte, 1)));
        }
        // Effective share prices are strike +/- the OPTION premium per share — a buy-write's
        // stock purchase must not leak into it (it made "effective sell $10/sh" nonsense once).
        long optionNetCents = PayoffCurve.of(built.legs().stream().filter(l -> !l.isStock()).toList(), qty)
                .entryNetPremiumCents();
        BigDecimal perShareNet = BigDecimal.valueOf(optionNetCents)
                .divide(BigDecimal.valueOf(100L * qty), 2, java.math.RoundingMode.HALF_UP)
                .movePointLeft(2); // cents -> dollars per share
        String effectivePrice = null;
        BigDecimal shortCallStrike = built.legs().stream()
                .filter(l -> !l.isStock() && l.action() == LegAction.SELL && l.type() == OptionType.CALL)
                .map(Leg::strike).findFirst().orElse(null);
        BigDecimal shortPutStrike = built.legs().stream()
                .filter(l -> !l.isStock() && l.action() == LegAction.SELL && l.type() == OptionType.PUT)
                .map(Leg::strike).findFirst().orElse(null);
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
            // FIXTURE is simulated data — NOT real-time. Scoring it 1.0 inflated the composite score
            // and confidence % on any live-mode demo-fallback candidate. In whole-app demo mode every
            // candidate is FIXTURE so relative ranking is unchanged; in live mode a fixture fallback is
            // now correctly penalized below real DELAYED data.
            case FIXTURE -> 0.45;
            default -> 0.40;
        };
        double rr = maxProfit == null ? 0.6 : Math.min(3.0, maxProfit / (double) Math.max(1, maxLoss)) / 3.0;
        double popScore = pop == null ? 0.5 : pop;
        double capEff = Math.clamp(1.0 - maxLoss / (double) Math.max(1, budget), 0, 1);
        double event = 1.0 - (zeroDte ? 0.4 : 0);
        double score = 100 * (0.15 * freshScore + 0.20 * liquidity + 0.15 * rr + 0.25 * popScore + 0.10 * capEff + 0.15 * event);
        // Intent-aware rank adjustments, kept small and explainable: income flows prefer richer
        // annualized yield with tolerable assignment odds; exit/acquire flows prefer candidates
        // that actually reach the user's target price.
        BigDecimal target = holdings != null && holdings.targetPriceCents() != null && holdings.targetPriceCents() > 0
                ? Money.priceFromCents(holdings.targetPriceCents()) : null;
        if (intent == StrategyIntent.INCOME && annualYieldPct != null) {
            score += Math.min(15, annualYieldPct / 2.0) - (assignProb == null ? 0 : assignProb * 10);
        } else if (intent == StrategyIntent.EXIT && target != null && shortCallStrike != null) {
            if (shortCallStrike.compareTo(target) >= 0) score += 10;
        } else if (intent == StrategyIntent.ACQUIRE && target != null && shortPutStrike != null) {
            if (shortPutStrike.compareTo(target) <= 0) score += 10;
        }
        score = Math.clamp(score, 0, 100);
        double modelConf = multiExp ? 0.4 : ivMissing ? 0.5 : (pop != null ? 0.9 : 0.6);
        double confidence = Math.clamp(0.40 * freshScore + 0.35 * liquidity + 0.25 * modelConf, 0, 1);

        String upside = maxProfit != null
                ? "Max profit " + Money.fmt(maxProfit) + (breakevens.isEmpty() ? "" : " beyond " + String.join(" / ", breakevens))
                : (multiExp ? "Profit depends on volatility staying favorable near the short strike; not a fixed number"
                             : "Uncapped upside past " + (breakevens.isEmpty() ? "the breakeven" : "$" + breakevens.getFirst()));
        String risk = onHeldShares
                ? (maxLoss > 0
                    ? "Costs " + Money.fmt(maxLoss) + " in premium; your " + (100 * displayLotsPerUnit * qty)
                        + " shares keep their own downside" + (combinedMaxLoss != null
                            ? " — worst case incl. shares from today's price: " + Money.fmt(combinedMaxLoss) : "") + ". Fees come on top."
                    : "No new cash at risk — but your " + (100 * displayLotsPerUnit * qty)
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
                    + (maxLoss > 0 ? "Sized to keep new cash at risk within your " + Money.fmt(budget) + " budget." : "");
        String beginner = beginnerText(family, entryNet);
        BigDecimal longPutStrike = built.legs().stream()
                .filter(l -> !l.isStock() && l.action() == LegAction.BUY && l.type() == OptionType.PUT)
                .map(Leg::strike).findFirst().orElse(null);
        String intentNote = intentNote(intent, family, holdings, spot, qty, entryNet, minDte,
                effectivePrice, assignProb, annualYieldPct, shortCallStrike, shortPutStrike, longPutStrike,
                onHeldShares, displayLotsPerUnit);

        List<String> candidateWarnings = new ArrayList<>(verdict.warnings());
        if (ivMissing && !multiExp) {
            candidateWarnings.add("No implied volatility available — POP/EV assume a 30% placeholder volatility");
        }
        return new Candidate(family.name(), family.display(), built.label(),
                built.legs().stream().map(LegView::of).toList(), qty,
                entryNet, maxProfit, maxLoss, breakevens, pop, ev,
                round2(liquidity), freshness.name(), candidateWarnings,
                round2(score), round2(confidence), why, upside, risk, invalidate, beginner,
                intent.name(), family.intents().stream().map(Enum::name).sorted().toList(),
                assignProb == null ? null : round2(assignProb),
                annualYieldPct, effectivePrice, intentNote,
                onHeldShares ? Boolean.TRUE : null,
                onHeldShares ? coverLotsPerUnit * 100 * qty : null,
                combinedMaxLoss);
    }

    /**
     * Modeled chance that at least one short leg finishes in the money at ITS expiration —
     * risk-neutral N(d2)/N(-d2) at each leg's own IV, zero drift, no early-assignment model.
     * Distinct short strikes are summed (disjoint regions for condor-like shapes), capped at 1.
     */
    private static Double assignmentProbability(List<Leg> legs, List<OptionQuote> quotes,
                                                BigDecimal spot, LocalDate today, double ivFallback) {
        List<Double> ivs = new ArrayList<>();
        for (int i = 0; i < legs.size(); i++) {
            OptionQuote q = quotes != null && i < quotes.size() ? quotes.get(i) : null;
            ivs.add(q == null ? null : q.iv());
        }
        return assignmentProbabilityFromIvs(legs, ivs, spot, today, ivFallback);
    }

    /**
     * N(d2)/N(-d2) per DISTINCT short strike, summed and capped at 1 -- shared with the
     * trade-preview path so the builder shows the same number the engine would.
     * {@code ivsAligned} is index-aligned with {@code legs} (null entries fall back).
     */
    public static Double assignmentProbabilityFromIvs(List<Leg> legs, List<Double> ivsAligned,
                                                      BigDecimal spot, LocalDate today, double ivFallback) {
        if (spot == null || spot.signum() <= 0) return null;
        java.util.Set<String> seen = new java.util.HashSet<>();
        double total = 0;
        boolean any = false;
        for (int i = 0; i < legs.size(); i++) {
            Leg l = legs.get(i);
            if (l.isStock() || l.action() != LegAction.SELL) continue;
            String key = l.type() + "|" + l.strike().stripTrailingZeros().toPlainString() + "|" + l.expiration();
            if (!seen.add(key)) continue; // ratio>1 on the same contract is one event
            any = true;
            Double iv = ivsAligned != null && i < ivsAligned.size() ? ivsAligned.get(i) : null;
            double sigma = iv != null && iv > 0 ? iv : ivFallback;
            double t = Math.max(ChronoUnit.DAYS.between(today, l.expiration()), 0.5) / 365.0;
            double d1 = BlackScholes.d1(spot.doubleValue(), l.strike().doubleValue(), t, 0, 0, sigma);
            double d2 = d1 - sigma * Math.sqrt(t);
            total += l.type() == OptionType.CALL ? BlackScholes.normCdf(d2) : BlackScholes.normCdf(-d2);
        }
        return any ? Math.min(1.0, total) : null;
    }

    /** Human framing of the candidate against the user's goal, holdings and target price. */
    private static String intentNote(StrategyIntent intent, StrategyFamily family, Holdings holdings,
                                     BigDecimal spot, int qty, long entryNet, int minDte,
                                     String effectivePrice, Double assignProb, Double annualYieldPct,
                                     BigDecimal shortCallStrike, BigDecimal shortPutStrike, BigDecimal longPutStrike,
                                     boolean onHeldShares, int displayLotsPerUnit) {
        int shares = 100 * Math.max(displayLotsPerUnit, 1) * qty;
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
                if (entryNet > 0) sb.append(" Either way you keep the ").append(Money.fmt(entryNet)).append(" premium.");
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
                if (entryNet > 0 && annualYieldPct != null) {
                    sb.append(" If not, you keep ").append(Money.fmt(entryNet))
                            .append(String.format(" (~%.1f%%/yr while you wait).", annualYieldPct));
                }
                return sb.toString().trim();
            }
            case INCOME -> {
                if (entryNet <= 0) return null;
                StringBuilder sb = new StringBuilder("Collect " + Money.fmt(entryNet) + " now");
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
                if (entryNet < 0) sb.append(" for a cost of ").append(Money.fmt(-entryNet));
                else if (entryNet > 0) sb.append(" and even pays ").append(Money.fmt(entryNet)).append(" (the call cap funds the floor)");
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

    private static String beginnerText(StrategyFamily family, long entryNet) {
        String cash = entryNet >= 0
                ? "You collect " + Money.fmt(entryNet) + " up front and keep it if the trade works out."
                : "You pay " + Money.fmt(-entryNet) + " up front — that payment is the most you can lose"
                    + (family.needsStock() ? " on the options portion" : family.multiExpiration() ? "" : "") + ".";
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
            case COVERED_CALL -> "You own 100 shares and rent them out by selling a call; income now, upside capped. " + cash;
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

    private static LocalDate pickExpiration(List<LocalDate> expirations, String horizon, LocalDate today,
                                            boolean allow0dte, java.time.Instant now, List<String> notes) {
        int targetDays = switch (horizon == null ? "month" : horizon.trim().toLowerCase(Locale.ROOT)) {
            case "0dte" -> 0;
            case "week" -> 7;
            case "quarter" -> 90;
            default -> 35;
        };
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
