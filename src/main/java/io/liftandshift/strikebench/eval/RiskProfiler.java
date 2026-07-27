package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.market.Universes;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.ScenarioStory;
import io.liftandshift.strikebench.pricing.JumpMixtureTerminal;
import io.liftandshift.strikebench.pricing.PayoffCurve;
import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.recommend.LegView;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the real payoff-vs-underlying grid (via {@link PayoffCurve}) plus the stressed tail loss.
 * For share-backed candidates the locked shares are folded in as a synthetic stock leg valued at
 * today's price, so the scenarios reflect the COMBINED position the trader actually holds.
 */
public final class RiskProfiler {

    /**
     * The product's NAMED scenarios ("Market crash", "Gap down", "Orderly pullback", "Choppy",
     * "Flat", "Grind higher", "Strong rally", "Melt-up"). The desk shows one tile per story, so the
     * checkpoint set must BE that set: when the two disagreed, the browser filled the five missing
     * moves by interpolating between checkpoints and displayed the result as a priced outcome.
     * Every tile is now a real valuation at a move the engine actually priced.
     */
    private static final double TAIL_MOVE = 0.20;
    private static final String TERMINAL_PAYOFF_SCHEMA = RiskProfile.TerminalPayoff.SCHEMA;
    private static final String TERMINAL_PAYOFF_MODEL = RiskProfile.TerminalPayoff.MODEL;

    public RiskProfile profile(Candidate c, EvalContext ctx) {
        long maxLoss = Math.max(0, c.combinedMaxLossCents() != null
                ? c.combinedMaxLossCents() : c.maxLossCents());
        Long maxProfit = c.maxProfitCents();

        List<RiskProfile.Scenario> scenarios = new ArrayList<>();
        RiskProfile.TerminalPayoff terminalPayoff;
        // The real-world / tail lane rides the SAME single-expiration curve as the terminal payoff.
        JumpMixtureTerminal.Tail jumpTail;
        long worstPnl = 0;
        boolean have = false;
        boolean exactLossBounded = false;
        long distinctExpirations = c.legs() == null ? 0 : c.legs().stream()
                .filter(l -> l.expiration() != null && !l.expiration().isBlank())
                .map(LegView::expiration).distinct().count();
        String expiration = c.legs() == null ? null : c.legs().stream()
                .filter(l -> l.expiration() != null && !l.expiration().isBlank())
                .map(LegView::expiration).findFirst().orElse(null);
        // §3.2: a P/L curve needs an ENTRY. When the §7.2 receipt states no package price there is
        // nothing to subtract, and the leg marks alone are exactly what was missing — building the
        // curve anyway would publish a payoff for a package whose cost is unknown, and would price
        // an unmarked leg at $0 as if it were free.
        String unpriced = unpricedReason(c);
        if (unpriced != null) {
            terminalPayoff = unavailableTerminalPayoff(unpriced);
            jumpTail = unavailableJumpTail(unpriced);
        } else if (distinctExpirations > 1) {
            terminalPayoff = unavailableTerminalPayoff(
                    "A mixed-expiration package requires supplied-path valuation; no single-expiration payoff was substituted.");
            jumpTail = unavailableJumpTail(
                    "A mixed-expiration package requires supplied-path valuation; no single-expiration jump-mixture tail was substituted.");
        } else try {
            PayoffCurve pc = payoffCurve(c, ctx);
            exactLossBounded = !pc.maxLossUnbounded();
            long marketAnchorCents = marketAnchorCents(c, ctx);
            BigDecimal spot = cents(marketAnchorCents);
            double spotD = spot.doubleValue();
            // Scenario mass is consumed from the candidate's ONE fingerprinted market-implied
            // receipt. This profiler prices the story payoffs but never rebuilds the distribution.
            var marketImpliedRisk = c.marketImpliedRisk();
            java.util.Map<ScenarioStory, Double> probabilityByStory =
                    marketImpliedRisk != null && marketImpliedRisk.available()
                            ? marketImpliedRisk.scenarioMasses().stream().collect(
                                    java.util.stream.Collectors.toUnmodifiableMap(
                                            io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer
                                                    .ScenarioMass::story,
                                            io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer
                                                    .ScenarioMass::probability))
                            : java.util.Map.of();
            ScenarioStory[] stories = ScenarioStory.values();
            for (int i = 0; i < stories.length; i++) {
                ScenarioStory story = stories[i];
                double m = story.underlyingMoveFraction();
                long pnl = pc.profitAtStoryCents(spot, story);
                Double prob = probabilityByStory.get(story);
                scenarios.add(new RiskProfile.Scenario(story, m, pnl, prob));
                worstPnl = have ? Math.min(worstPnl, pnl) : pnl;
                have = true;
            }
            // A single-expiration package has one exact terminal curve. Time spreads require the
            // supplied-ensemble valuation model instead, so they deliberately expose no false
            // single-date polyline here.
            var points = pc.chartPoints(spot).stream()
                    .map(p -> new RiskProfile.PayoffPoint(p.price(), p.profitCents()))
                    .toList();
            terminalPayoff = new RiskProfile.TerminalPayoff(TERMINAL_PAYOFF_SCHEMA,
                    TERMINAL_PAYOFF_MODEL, !points.isEmpty(), marketAnchorCents,
                    pc.profitAtCents(spot), expiration,
                    "EXPIRATION_INTRINSIC", "CAPTURED_CANDIDATE_NET", false, points,
                    points.isEmpty() ? "The captured evaluation has no positive underlying anchor." : null);
            // Real-world / tail lane over the same curve: sector prior from the symbol, IV-rank and
            // event proximity from the regime, body vol from the horizon expected move (IV*sqrt(T)).
            // The desk's former client Merton tail, now a backend receipt — one per calm/base/tense.
            String sectorLabel = ctx.symbol() == null || ctx.symbol().isBlank()
                    ? null : Universes.allocationSectorLabel(ctx.symbol());
            String tailEvidenceGap = jumpTailEvidenceGap(ctx);
            if (tailEvidenceGap != null) {
                jumpTail = unavailableJumpTail(tailEvidenceGap);
            } else {
                Double expectedMovePct = io.liftandshift.strikebench.pricing.ExpectedMove
                        .percent(ctx.atmIv(), ctx.timeToExpiry());
                jumpTail = JumpMixtureTerminal.tail(spotD, sectorLabel,
                        ctx.regime().ivRankPct(), expectedMovePct,
                        ctx.regime().eventSoon(), null, !points.isEmpty(),
                        pc.maxLossUnbounded(), maxLoss,
                        s -> pc.profitAtCents(BigDecimal.valueOf(s)),
                        points.isEmpty()
                                ? "The captured evaluation has no positive underlying anchor."
                                : null);
            }
        } catch (RuntimeException e) {
            // Degrade to extremes-only rather than fail the whole evaluation.
            terminalPayoff = unavailableTerminalPayoff(
                    "The exact terminal payoff could not be produced from the captured candidate.");
            jumpTail = unavailableJumpTail(
                    "The jump-mixture tail could not be produced from the captured candidate.");
        }
        // The visible +/-20% scenarios are checkpoints, not the complete tail envelope. For an
        // exact bounded curve the tail receipt is the pre-known maximum loss even when a distant
        // wing lies outside that scenario grid. Undefined-risk structures retain the modeled
        // stress loss. This keeps the headline risk from understating the same max-loss receipt.
        long tailLoss = exactLossBounded
                ? maxLoss
                : have ? Math.max(0, -worstPnl) : maxLoss;
        // The HISTORICAL-VOL SCENARIO lane: the same EV integral at REALIZED volatility (zero
        // drift). When implied >> realized, market-implied EV of short premium is negative while
        // this scenario EV is positive — that gap IS the volatility risk premium, and the two
        // numbers must never be blended into one.
        Long evHistVol = null;
        double marketRate = c.marketImpliedRisk() != null && c.marketImpliedRisk().available()
                ? c.marketImpliedRisk().riskFreeRate() : ctx.riskFreeRate();
        String basisNote = c.marketImpliedRisk() != null && c.marketImpliedRisk().available()
                ? String.format("market EV = present-value risk-neutral approximation "
                        + "(captured market IV, r=%.2f%%, q=0 assumed); pre-commission",
                        marketRate * 100)
                : "market EV unavailable: "
                        + (c.marketImpliedRisk() == null
                            ? "no fingerprinted market-implied receipt was captured"
                            : c.marketImpliedRisk().unavailableReason());
        if (unpriced != null) {
            basisNote = "Both EV lanes are unavailable because this package has no entry price: " + unpriced;
        } else if (distinctExpirations <= 1
                && ctx.realizedVol30() != null && ctx.realizedVol30() > 0 && ctx.underlyingCents() > 0
                && ctx.hasModelTime() && c.legs() != null && !c.legs().isEmpty()) {
            try {
                PayoffCurve ppc = payoffCurve(c, ctx);
                double t = ctx.yearsToExpiry();
                evHistVol = ppc.expectedValueCents(ctx.underlyingCents() / 100.0, ctx.realizedVol30(), t, 0);
                basisNote += "; history EV = realized-vol " + Math.round(ctx.realizedVol30() * 100)
                        + "% zero-drift scenario (not a physical-measure forecast). Both are pre-commission.";
            } catch (RuntimeException ignored) { /* lane stays honestly null */ }
        } else if (distinctExpirations > 1) {
            basisNote = "EV lanes are unavailable for multi-expiration structures in the single-terminal model; use the strategy simulator's two-expiry path valuation.";
        }
        RiskProfile.WorstScenario worstScenario = worstScenario(scenarios, maxLoss);
        var marketImpliedRisk = c.marketImpliedRisk() == null
                ? io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.Receipt.unavailable(
                        "No fingerprinted market-implied evaluation was captured for this candidate.")
                : c.marketImpliedRisk();
        return new RiskProfile(maxLoss, maxProfit, tailLoss,
                TAIL_MOVE, scenarios, terminalPayoff, evHistVol, basisNote, jumpTail,
                worstScenario, marketImpliedRisk);
    }

    static RiskProfile.WorstScenario worstScenario(
            List<RiskProfile.Scenario> scenarios, long maximumLossCents) {
        if (scenarios == null || scenarios.isEmpty()) {
            return new RiskProfile.WorstScenario(false, null, null, null,
                    maximumLossCents > 0 ? maximumLossCents : null,
                    maximumLossCents > 0 ? "EXACT_MAXIMUM_LOSS" : null,
                    null, null,
                    "Worst of the named scenario checkpoints, compared with the exact maximum "
                            + "loss for this package.",
                    "No named scenario checkpoint was priced for this package.");
        }
        RiskProfile.Scenario worst = scenarios.stream()
                .min(java.util.Comparator.comparingLong(RiskProfile.Scenario::pnlCents))
                .orElseThrow();
        long loss = Math.max(0L, -worst.pnlCents());
        if (maximumLossCents <= 0) {
            return new RiskProfile.WorstScenario(false, worst.underlyingMovePct(),
                    worst.pnlCents(), loss, null, null, null, null,
                    "Worst of the named scenario checkpoints.",
                    "The package has no positive exact maximum-loss denominator, so scenario "
                            + "severity cannot be measured.");
        }
        double sharePct = loss * 100.0 / maximumLossCents;
        RiskProfile.ScenarioSeverity severity = sharePct >= 90.0
                ? RiskProfile.ScenarioSeverity.SEVERE
                : sharePct >= 50.0
                    ? RiskProfile.ScenarioSeverity.MATERIAL
                    : RiskProfile.ScenarioSeverity.CONTAINED;
        return new RiskProfile.WorstScenario(true, worst.underlyingMovePct(), worst.pnlCents(),
                loss, maximumLossCents, "EXACT_MAXIMUM_LOSS",
                io.liftandshift.strikebench.util.Numbers.round2(sharePct), severity,
                "Worst of the named scenario checkpoints, compared with the exact maximum "
                        + "loss for this package.", null);
    }

    private static RiskProfile.TerminalPayoff unavailableTerminalPayoff(String reason) {
        return new RiskProfile.TerminalPayoff(TERMINAL_PAYOFF_SCHEMA, TERMINAL_PAYOFF_MODEL,
                false, null, null, null, null, null, false, List.of(), reason);
    }

    private static JumpMixtureTerminal.Tail unavailableJumpTail(String reason) {
        return JumpMixtureTerminal.unavailable(reason);
    }

    /**
     * Names the first missing observation required by the physical tail lane. Null means the lane
     * is supported. In particular, an unknown event calendar is not equivalent to "no event."
     */
    static String jumpTailEvidenceGap(EvalContext ctx) {
        if (ctx.regime() == null) {
            return "Jump-tail probability is unavailable because no market-regime receipt was captured.";
        }
        if (ctx.regime().ivRankPct() == null
                || !Double.isFinite(ctx.regime().ivRankPct())
                || ctx.regime().ivRankPct() < 0.0
                || ctx.regime().ivRankPct() > 100.0) {
            return "Jump-tail probability is unavailable because observed IV rank is missing.";
        }
        if (ctx.atmIv() == null || !Double.isFinite(ctx.atmIv()) || ctx.atmIv() <= 0.0) {
            return "Jump-tail probability is unavailable because an ATM option IV is missing.";
        }
        if (!ctx.hasModelTime()) {
            return "Jump-tail probability is unavailable because option time to expiry is missing.";
        }
        if (ctx.regime().eventSoon() == null) {
            String eventBasis = ctx.regime().eventBasis();
            return eventBasis == null || eventBasis.isBlank()
                    ? "Jump-tail probability is unavailable because event proximity is unknown."
                    : "Jump-tail probability is unavailable because event proximity is unknown: "
                            + eventBasis;
        }
        return null;
    }

    /**
     * THE one question every payoff consumer asks first: is this package priced, and if not, why?
     * Null means priced. Every lane that needs an entry (the terminal curve, the tail, both EV
     * lanes, participation capture) branches on this ONE answer so they cannot disagree about
     * whether a payoff exists (§3.2, §3.8).
     */
    static String unpricedReason(Candidate c) {
        if (c == null) return "No candidate was supplied, so there is no package to price.";
        var price = c.price();
        if (price != null && price.priced()) return null;
        String reason = price == null ? null : price.unavailableReason();
        return reason == null || reason.isBlank()
                ? "This package has no price receipt, so no entry basis exists." : reason;
    }

    /**
     * Builds the exact package curve once for every risk lane. The package-level entry is
     * authoritative (a user's limit/fill need not equal the sum of executable leg marks), while
     * held-share candidates add one stock lot PER package unit. {@code sharesNeeded} is the total
     * across quantity, so using it directly as a leg ratio would multiply quantity twice.
     *
     * <p>Returns NULL when {@link #unpricedReason} is non-null. Callers must branch and publish
     * that reason; there is no zero-entry curve to fall back on (§3.2).</p>
     */
    static PayoffCurve payoffCurve(Candidate c, EvalContext ctx) {
        if (unpricedReason(c) != null) return null;
        return PayoffCurve.of(combinedLegs(c, ctx), Math.max(1, c.qty()),
                entryAdjustmentCents(c, ctx));
    }

    /**
     * The package's legs, including the synthetic stock lot a held-share candidate locks. Geometry
     * only — deliberately independent of the package PRICE, so Greeks and exposure survive a
     * package the market could not mark.
     */
    static List<Leg> combinedLegs(Candidate c, EvalContext ctx) {
        int qty = Math.max(1, c.qty());
        List<Leg> combined = new ArrayList<>(c.legs().stream().map(LegView::toLeg).toList());
        if (Boolean.TRUE.equals(c.usesHeldShares()) && c.sharesNeeded() != null && c.sharesNeeded() > 0) {
            int sharesPerUnit = Math.max(1, c.sharesNeeded() / qty);
            combined.add(Leg.stockShares(LegAction.BUY, sharesPerUnit,
                    cents(marketAnchorCents(c, ctx))));
        }
        return combined;
    }

    private static long marketAnchorCents(Candidate candidate, EvalContext context) {
        var receipt = candidate == null ? null : candidate.marketImpliedRisk();
        return receipt != null && receipt.available()
                ? receipt.underlyingCents() : context.underlyingCents();
    }

    /** The authoritative package net less what the leg marks alone add up to. Priced packages only. */
    private static long entryAdjustmentCents(Candidate c, EvalContext ctx) {
        int qty = Math.max(1, c.qty());
        long markedEntry = PayoffCurve.of(
                c.legs().stream().map(LegView::toLeg).toList(), qty).entryNetPremiumCents();
        return c.price().grossPackageNetCents() - markedEntry;
    }

    private static BigDecimal cents(long c) { return BigDecimal.valueOf(c).movePointLeft(2); }
}
