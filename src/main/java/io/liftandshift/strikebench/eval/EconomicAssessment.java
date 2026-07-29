package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.util.Money;

import java.util.ArrayList;
import java.util.List;

/**
 * The economic judgment that sits beside mechanical eligibility. A valid payoff is not
 * automatically a good opportunity: this record keeps unfavorable structures available for
 * learning while preventing payout size or capital efficiency from presenting them as ordinary
 * recommendations.
 */
public record EconomicAssessment(
        Verdict verdict,
        String placement,
        String label,
        String summary,
        Long marketEvAfterCostsCents,
        Long realizedVolEvAfterCostsCents,
        // §3.2: null when the exact package's §7.2 receipt states no commission. Every EV field
        // beside it is then null too — a round-trip cost that is not known must never be published
        // as a free one, because "EV after costs" would silently become "EV before costs".
        Long estimatedRoundTripFeesCents,
        Double marketEvPctOfRisk,
        Long realisticEvLowAfterCostsCents,
        Long realisticEvHighAfterCostsCents,
        long realisticEvMaterialityCents,
        String marketEvRole,
        String realisticEvBasis,
        boolean observedEvidence,
        List<String> reasons
) {
    public static final String DAILY_HISTORY_REASON =
            "The realized-volatility EV lane is unavailable because this market lacks enough eligible daily history.";

    public enum Verdict {
        FAVORABLE(3), MIXED(2), UNAVAILABLE(1), UNFAVORABLE(0);

        private final int rank;
        Verdict(int rank) { this.rank = rank; }
        public int rank() { return rank; }
    }

    public EconomicAssessment {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    /** Compatibility shape for persisted fixtures and callers that predate the scoped EV receipt. */
    public EconomicAssessment(Verdict verdict, String placement, String label, String summary,
                              Long marketEvAfterCostsCents, Long realizedVolEvAfterCostsCents,
                              Long estimatedRoundTripFeesCents, Double marketEvPctOfRisk,
                              boolean observedEvidence, List<String> reasons) {
        this(verdict, placement, label, summary, marketEvAfterCostsCents,
                realizedVolEvAfterCostsCents, estimatedRoundTripFeesCents, marketEvPctOfRisk,
                null, null, 0L, marketRole(), null, observedEvidence, reasons);
    }

    public boolean favorable() { return verdict == Verdict.FAVORABLE; }
    /** A favorable model result supported end-to-end by observed evidence, not a teaching case. */
    public boolean actionableFavorable() { return favorable() && observedEvidence; }
    public boolean teachingCase() { return verdict == Verdict.UNFAVORABLE; }

    public static EconomicAssessment assess(Candidate c, RiskProfile risk, EvidenceProfile evidence,
                                            ScoreBreakdown score, EvalContext ctx) {
        return assessExact(c, risk, evidence, ctx, score != null && score.gatePassed(),
                score == null ? List.of() : score.gateFailures(), roundTripFees(c));
    }

    /**
     * The one reason an exact assessment refuses to state economics: the package's own §7.2 receipt
     * carries no commission, so nothing here can be reported "after costs".
     */
    public static final String UNKNOWN_FEES_REASON =
            "This package's price receipt states no commission, so no expected value can be reported"
                    + " after costs. Nothing here is netted against a $0 round trip.";

    /**
     * Exact-ticket assessment: mechanical eligibility comes from the trade preview and the fees are
     * the package-price receipt's own commission
     * ({@code PackagePriceReceipt.estimatedRoundTripFeesCents()}), not a reconstructed ranking assumption.
     *
     * <p>§3.2: {@code roundTripFeesCents} is null when that receipt states no commission — an
     * unpriced or refused package. Callers used to hand this a substituted 0 read off
     * {@code TradePreview.feesOpenCents}, which published the package's GROSS expectation under the
     * name {@code marketEvAfterCostsCents}. There is no EV lane to state in that case, so this
     * states the absence and its reason instead.</p>
     */
    public static EconomicAssessment assessExact(Candidate c, RiskProfile risk, EvidenceProfile evidence,
                                                  EvalContext ctx, boolean mechanicallyEligible,
                                                  List<String> mechanicalFailures, Long roundTripFeesCents) {
        if (roundTripFeesCents == null) {
            List<String> reasons = new ArrayList<>();
            if (mechanicalFailures != null) reasons.addAll(mechanicalFailures);
            reasons.add(UNKNOWN_FEES_REASON);
            return new EconomicAssessment(Verdict.UNAVAILABLE,
                    mechanicallyEligible ? "MECHANICS_ONLY" : "MECHANICALLY_INELIGIBLE",
                    mechanicallyEligible ? "Economics unavailable" : "Cannot assess as a trade",
                    UNKNOWN_FEES_REASON, null, null, null, null,
                    evidence != null && evidence.observedFor("realizedVolEv"), reasons);
        }
        if (roundTripFeesCents < 0) {
            throw new IllegalArgumentException("round-trip fees cannot be negative");
        }
        return assess(c, risk, evidence, ctx, mechanicallyEligible, mechanicalFailures,
                roundTripFeesCents);
    }

    private static EconomicAssessment assess(Candidate c, RiskProfile risk, EvidenceProfile evidence,
                                              EvalContext ctx, boolean mechanicallyEligible,
                                              List<String> mechanicalFailures, long fees) {
        Long marketEv = risk == null || risk.marketImpliedRisk() == null
                ? null : risk.marketImpliedRisk().expectedValueCents();
        Long marketNet = marketEv == null ? null : marketEv - fees;
        Long realizedNet = risk == null || risk.evHistVolCents() == null
                ? null : risk.evHistVolCents() - fees;
        long maxLoss = risk == null ? 0 : Math.max(0, risk.maxLossCents());
        Double evPct = marketNet == null || maxLoss <= 0
                ? null : 100.0 * marketNet / maxLoss;
        // The holistic badge remains worst-of for disclosure, but an economic claim is judged by
        // the inputs it actually consumes. Missing IV-rank history, Greeks, or unrelated portfolio
        // decoration cannot veto an observed two-lane EV claim; missing daily history still can.
        boolean observed = evidence != null && evidence.observedFor("realizedVolEv");
        EvidenceLevel pricingEvidence = evidence == null ? EvidenceLevel.UNKNOWN
                : evidence.perDimension().getOrDefault("pricing", EvidenceLevel.UNKNOWN);
        boolean explicitTeachingMarket = pricingEvidence == EvidenceLevel.DEMO_FIXTURE
                || pricingEvidence == EvidenceLevel.SIMULATED;
        List<String> reasons = new ArrayList<>();

        if (!mechanicallyEligible) {
            if (mechanicalFailures != null) reasons.addAll(mechanicalFailures);
            return new EconomicAssessment(Verdict.UNAVAILABLE, "MECHANICALLY_INELIGIBLE",
                    "Cannot assess as a trade",
                    "The package does not pass the mechanical and account checks required for an economic comparison.",
                    marketNet, realizedNet, fees, evPct, observed, reasons);
        }

        if (marketNet == null && realizedNet == null) {
            reasons.add("Neither a market-implied nor a realized-volatility EV lane is available.");
            return new EconomicAssessment(Verdict.UNAVAILABLE, "MECHANICS_ONLY",
                    "Economics unavailable",
                    "You can study the payoff mechanics, but the available data cannot support an economic verdict.",
                    null, null, fees, null, observed, reasons);
        }

        long material = realisticMaterialityCents(c, risk, ctx);
        RealisticRange range = realisticRange(c, ctx, realizedNet, fees);
        Long realisticLow = range == null ? null : range.lowCents();
        Long realisticHigh = range == null ? null : range.highCents();
        boolean realizedPositive = realizedNet != null && realizedNet > material;
        boolean positiveSensitivityCrossesZero = realizedPositive
                && realisticLow != null && realisticLow <= 0;
        boolean realizedNegative = realizedNet != null && realizedNet < -material;
        boolean negativeSensitivityCrossesZero = realizedNegative
                && realisticHigh != null && realisticHigh >= 0;
        Double riskNeutralPop = risk == null || risk.marketImpliedRisk() == null
                ? null : risk.marketImpliedRisk().pop();
        boolean lowProbability = riskNeutralPop != null && riskNeutralPop < 0.30;

        if (marketNet != null) reasons.add("Market-implied EV after estimated round-trip fees: "
                + Money.fmt(marketNet) + ". " + marketRole());
        if (realizedNet != null) {
            reasons.add("Realized-volatility scenario EV after estimated round-trip fees: "
                    + Money.fmt(realizedNet) + ".");
        } else if (ctx.realizedVol30() == null) {
            reasons.add(DAILY_HISTORY_REASON);
        } else if (risk != null && risk.evBasisNote() != null
                && risk.evBasisNote().contains("multi-expiration")) {
            reasons.add("The realized-volatility EV lane is unavailable for this multi-expiration structure in the single-terminal model.");
        } else {
            reasons.add("The realized-volatility EV lane could not be computed for this payoff shape.");
        }
        if (range != null) {
            reasons.add(range.note());
        }
        reasons.add("A realistic-measure advantage must exceed " + Money.fmt(material)
                + " after costs for this exact package; the threshold scales with attainable payoff and with the capital at risk over this horizon.");
        if (lowProbability) reasons.add("The modeled chance of any profit is below 30%; low probability is not a rejection by itself.");
        if (!observed) {
            var claim = evidence == null ? null : evidence.claims().get("realizedVolEv");
            if (claim != null && !claim.nonObservedDimensions().isEmpty()) {
                reasons.add("The live-market endorsement is limited by non-observed inputs: "
                        + String.join(", ", claim.nonObservedDimensions()) + ".");
            } else {
                reasons.add("The evidence is generated or modeled, so it cannot establish a live-market edge.");
            }
        }

        // The market-implied lane is a price-consistency/cost disclosure. Under the same option
        // prices and risk-neutral measure it is normally the spread and fees with a minus sign; it
        // is not independent evidence of edge and therefore cannot structurally veto a positive
        // realistic-measure result. FAVORABLE is driven by a material positive after-cost point
        // estimate with the evidence used by that claim. The volatility range is a sensitivity
        // disclosure, not a second edge vote: crossing zero narrows confidence, but cannot make a
        // positive realistic-measure estimate structurally impossible to endorse.
        if (positiveSensitivityCrossesZero) {
            reasons.add("The realized-volatility point estimate clears the materiality threshold, but the disclosed volatility sensitivity crosses zero; treat the edge as model-sensitive and keep the full range beside it.");
        }
        if (negativeSensitivityCrossesZero) {
            reasons.add("The realized-volatility point estimate is materially adverse, but the disclosed volatility sensitivity crosses zero; treat the rejection as model-sensitive and keep the full range beside it.");
        }
        if (realizedPositive && (observed || explicitTeachingMarket)) {
            return new EconomicAssessment(Verdict.FAVORABLE, "WORTH_INVESTIGATING",
                    observed ? "Worth investigating" : "Favorable in this teaching market",
                    observed
                            ? positiveSensitivityCrossesZero
                                ? "The observed realized-volatility point estimate shows a material after-cost advantage, with a disclosed sensitivity range that crosses zero. The market-implied lane remains visible as a cost benchmark, not a second edge vote."
                                : "The observed realized-volatility scenario shows a material after-cost advantage. The market-implied lane remains visible as a cost benchmark, not a second edge vote."
                            : "In this explicitly generated market, the realized-volatility point estimate is positive after estimated costs. Use the disclosed sensitivity to learn the setup; it is not evidence of a live-market edge.",
                    marketNet, realizedNet, fees, evPct, realisticLow, realisticHigh, material,
                    marketRole(), range == null ? realisticBasis(ctx) : range.basis(), observed, reasons);
        }

        // Two-axis verdict: the ECONOMIC TIER (does the after-cost point estimate clear materiality)
        // is independent of the EVIDENCE BADGE (is that estimate backed end-to-end by observed data).
        // A positive-EV package on modeled/unknown inputs is FAVORABLE on economics with a modeled
        // evidence badge (observedEvidence=false) — NOT demoted to MIXED where it hides. Downstream
        // endorsement/allocation still gates on actionableFavorable() (favorable AND observed), so
        // modeled-favorable surfaces in the scan and ranks below observed-favorable, but never
        // auto-endorses. This is the single biggest fix for "income scans return no income."
        if (realizedPositive && !observed && !explicitTeachingMarket) {
            reasons.add("The economics are favorable after costs, but the edge rests on modeled or unknown inputs rather than end-to-end observed evidence.");
            return new EconomicAssessment(Verdict.FAVORABLE, "WORTH_INVESTIGATING",
                    "Favorable · modeled evidence",
                    "The realized-volatility point estimate clears the after-cost materiality threshold, so the economics are favorable. The evidence is modeled, not observed end-to-end, so treat it as a strong candidate to confirm rather than a live-market endorsement.",
                    marketNet, realizedNet, fees, evPct, realisticLow, realisticHigh, material,
                    marketRole(), range == null ? realisticBasis(ctx) : range.basis(), false, reasons);
        }

        // Materially negative after-cost economics stay visible, but in a teaching lane. A small
        // POP alone never triggers this classification; it must be accompanied by non-positive EV.
        if (realizedNegative
                || (lowProbability && realizedNet != null && realizedNet < 0
                    && (realisticHigh == null || realisticHigh <= 0))) {
            return new EconomicAssessment(Verdict.UNFAVORABLE, "LEARN_FROM",
                    "Unfavorable at these prices",
                    negativeSensitivityCrossesZero
                            ? "The package is mechanically valid, but its realized-volatility point estimate is materially adverse after costs. Its disclosed sensitivity crosses zero, so keep it as a model-sensitive counterexample or re-price it; do not mistake availability for endorsement."
                            : "The package is mechanically valid, but its modeled after-cost economics are adverse. Keep it as a counterexample or re-price it; do not mistake availability for endorsement.",
                    marketNet, realizedNet, fees, evPct, realisticLow, realisticHigh, material,
                    marketRole(), range == null ? realisticBasis(ctx) : range.basis(), observed, reasons);
        }

        return new EconomicAssessment(Verdict.MIXED, "COMPARE_CAREFULLY",
                "No demonstrated edge yet",
                observed
                        ? "The structure is plausible, but the available models do not show a robust after-cost advantage. Compare it with cash, stock, and alternatives."
                        : "This is useful for learning and comparison, but generated or incomplete evidence cannot support a real-market edge claim.",
                marketNet, realizedNet, fees, evPct, realisticLow, realisticHigh, material,
                marketRole(), range == null ? realisticBasis(ctx) : range.basis(), observed, reasons);
    }

    /** True only when the evaluation context lacked eligible realized-volatility history. */
    public boolean needsDailyHistory() {
        return reasons.contains(DAILY_HISTORY_REASON);
    }

    /** THE round-trip commission for a candidate: the commission frozen into its package-price
     * receipt, never a re-price under today's configuration. Reused by the ranker so the verdict,
     * decision score, and outcome comparison all consume the same captured cost. */
    static Long roundTripFees(Candidate c) {
        return c == null || c.price() == null ? null : c.price().estimatedRoundTripFeesCents();
    }

    /**
     * A material edge must be meaningful on both relevant scales. Three percent of the attainable
     * payoff prevents a spread's modeled edge from being judged against an unreachable tail. A
     * five-basis-point capital noise allowance plus a one-percent annualized hurdle prevents the
     * opposite error: calling a $10 point estimate on a $50k cash-secured or held-share exposure
     * actionable. At 45 days that capital hurdle is about 17.3 bps, so a real $90 edge on roughly
     * $50k can still clear it while a rounding-sized result cannot. All terms scale with quantity.
     */
    static long realisticMaterialityCents(Candidate c, RiskProfile risk, EvalContext ctx) {
        long loss = risk == null ? 0 : Math.max(0, risk.maxLossCents());
        long scale = attainablePayoffScaleCents(c, risk);
        long perPackageFloor = 1_000L * Math.max(1, c == null ? 1 : c.qty());
        long payoffFloor = Math.round(scale * 0.03);
        long exposure = Math.max(loss, risk == null ? 0 : Math.max(0, risk.tailLossCents()));
        int days = ctx == null ? 0 : Math.max(0, ctx.calendarDaysToExpiry());
        double exposureRate = 0.0005 + 0.01 * days / 365.0;
        long exposureFloor = (long) Math.ceil(exposure * exposureRate);
        return Math.max(perPackageFloor, Math.max(payoffFloor, exposureFloor));
    }

    /** Structure-appropriate scale reused by DecisionPolicy's within-tier EV component. */
    static long realisticPayoffScaleCents(Candidate c, RiskProfile risk, EvalContext ctx) {
        long attainable = attainablePayoffScaleCents(c, risk);
        long materiality = realisticMaterialityCents(c, risk, ctx);
        // Keep the score's neutral-to-positive movement consistent with the verdict hurdle: an EV
        // exactly at materiality moves the normalized component by about 1.5 percentage points.
        long materialityEquivalentScale = (long) Math.ceil(materiality / 0.03);
        return Math.max(1, Math.max(attainable, materialityEquivalentScale));
    }

    private static long attainablePayoffScaleCents(Candidate c, RiskProfile risk) {
        long loss = risk == null ? 0 : Math.max(0, risk.maxLossCents());
        long profit = risk == null || risk.maxProfitCents() == null
                ? 0 : Math.max(0, risk.maxProfitCents());
        if (loss > 0 && profit > 0) return Math.min(loss, profit);
        if (profit > 0) return profit;
        if (loss > 0) return loss;
        // Last resort: the package's own size. This is a DENOMINATOR floor, never a published
        // payoff — and an unpriced package contributes no size, exactly as a null candidate does.
        // It can only be reached with an EV lane present, which an unpriced package never has.
        Long packageNet = c == null || RiskProfiler.unpricedReason(c) != null
                ? null : c.price().grossPackageNetCents();
        return Math.max(1, packageNet == null ? 1 : Math.abs(packageNet));
    }

    private static RealisticRange realisticRange(Candidate c, EvalContext ctx, Long pointAfterCosts,
                                                   long fees) {
        if (pointAfterCosts == null || c == null || ctx == null || ctx.realizedVol30() == null
                || ctx.realizedVol30() <= 0 || !ctx.hasModelTime()
                || ctx.trailingCloses() == null || ctx.trailingCloses().size() < 20) {
            return null;
        }
        int validCloses = (int) ctx.trailingCloses().stream()
                .filter(value -> value != null && Double.isFinite(value) && value > 0).count();
        int returns = Math.min(30, validCloses - 1);
        if (returns < 19) return null;
        double relativeSe = 1.0 / Math.sqrt(2.0 * Math.max(1, returns - 1));
        double lowVol = Math.max(0.01, ctx.realizedVol30() * (1.0 - relativeSe));
        double highVol = ctx.realizedVol30() * (1.0 + relativeSe);
        try {
            var curve = RiskProfiler.payoffCurve(c, ctx);
            if (curve == null) return null; // unpriced package: no entry, so no sensitivity range
            double years = ctx.yearsToExpiry();
            long atLow = curve.expectedValueCents(ctx.underlyingCents() / 100.0, lowVol, years, 0) - fees;
            long atHigh = curve.expectedValueCents(ctx.underlyingCents() / 100.0, highVol, years, 0) - fees;
            long low = Math.min(pointAfterCosts, Math.min(atLow, atHigh));
            long high = Math.max(pointAfterCosts, Math.max(atLow, atHigh));
            String basis = String.format(java.util.Locale.ROOT,
                    "REALIZED_VOL_ZERO_DRIFT_SENSITIVITY: %.1f%%-%.1f%% around %.1f%% using %d returns",
                    lowVol * 100, highVol * 100, ctx.realizedVol30() * 100, returns);
            String note = "Realized-volatility sensitivity after costs: " + Money.fmt(low)
                    + " to " + Money.fmt(high) + " across "
                    + String.format(java.util.Locale.ROOT, "%.1f%%-%.1f%% volatility", lowVol * 100, highVol * 100)
                    + ". This is a one-standard-error input sensitivity, not a confidence guarantee.";
            return new RealisticRange(low, high, basis, note);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static String marketRole() {
        return "Risk-neutral price/cost benchmark; it discloses spread and fees and is not an independent edge test.";
    }

    private static String realisticBasis(EvalContext ctx) {
        if (ctx == null || ctx.realizedVol30() == null) return null;
        return String.format(java.util.Locale.ROOT,
                "REALIZED_VOL_ZERO_DRIFT_POINT: %.1f%%; not a physical-measure forecast",
                ctx.realizedVol30() * 100);
    }

    private record RealisticRange(long lowCents, long highCents, String basis, String note) {}
}
