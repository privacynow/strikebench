package io.liftandshift.strikebench.pricing;

import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;

/**
 * THE real-world (physical) / tail terminal distribution of {@code S_T} — a Merton jump-mixture: a
 * quiet lognormal diffusion BODY plus a rare, calibrated DOWN-GAP (a Poisson-Gaussian jump). This is
 * the SEPARATE, named tail lane that sits ALONGSIDE {@link LognormalTerminal}, never replacing it:
 *
 * <ul>
 *   <li>{@link LognormalTerminal} is the market-implied, RISK-NEUTRAL lane — the odds the options are
 *       priced with (owns {@code RiskProfile.pop}, the market-implied cone, EV).</li>
 *   <li>{@code JumpMixtureTerminal} is the REAL-WORLD / tail lane — it answers "will I actually
 *       profit?" honestly, so a tail-exposed credit trade can no longer hide a catastrophic gap
 *       behind a benign win-rate. The down-jumps are left UNCOMPENSATED, so the distribution carries
 *       an honest downside skew (a POP question, not a pricing question).</li>
 * </ul>
 *
 * <p>Ported VERBATIM from the desk's former client tail model (the browser
 * {@code tailParams / tailDens / popCalc / tailExpectedShortfall / tailStats} + the {@code GAP_STANCE}
 * calm/base/tense dial), so the backend is now the single authority for the number the desk used to
 * compute in the browser. General and per-symbol: priors are calibrated from IV (expected move),
 * IV-rank, sector and known events — NO AI, NO fit. The jump variance is EXTRA tail risk the smooth
 * IV under-prices; the body keeps the full IV-implied vol so the two lanes never double-count fear.
 */
public final class JumpMixtureTerminal {

    /** THE one jump-tail receipt schema, so an idea, the position it becomes, and the desk gap dial
     *  all read the same shape. */
    public static final String SCHEMA = "risk-jump-tail-1";
    public static final String MODEL = "merton-jump-mixture-1";

    private static final String BASIS =
            "Merton jump-mixture real-world (physical) tail: a lognormal diffusion body at the "
            + "IV-implied terminal vol plus a rare, calibrated down-gap (Poisson-Gaussian, k<=6). "
            + "Priors from sector, IV-rank and event proximity — no forecast, no AI. The down-jumps "
            + "are left uncompensated, so probability-of-profit answers 'will I actually profit?', "
            + "distinct from the risk-neutral lognormal (LognormalTerminal) market-implied lane.";

    /** The user's standing gap-risk assumption — the desk's calm/base/tense outlook dial. */
    public enum GapStance {
        CALM(0.5), BASE(1.0), TENSE(1.9);
        public final double dial;
        GapStance(double dial) { this.dial = dial; }
    }

    /** Per-sector priors (NOT a fit): jump intensity over the horizon + characteristic down-gap.
     *  {@code Tech} is intentionally identical to {@code default}. */
    private record SectorPrior(double lam, double gap) {}

    private static final Map<String, SectorPrior> SECTOR_PRIORS = Map.of(
            "Semis", new SectorPrior(0.09, 0.15),
            "Autos", new SectorPrior(0.09, 0.16),
            "Tech", new SectorPrior(0.05, 0.11),
            "Software", new SectorPrior(0.04, 0.10),
            "Index", new SectorPrior(0.025, 0.07),
            "Health", new SectorPrior(0.045, 0.10));
    private static final SectorPrior DEFAULT_PRIOR = new SectorPrior(0.05, 0.11);

    private static final double SQRT_2PI = 2.5066282746310002;   // matches the desk's literal exactly
    private static final int POISSON_TERMS = 6;                  // k = 0..6, verbatim
    private static final int GRID = 340;                         // integration steps, verbatim
    private static final double LO_X = Math.log(0.35);
    private static final double HI_X = Math.log(1.9);

    // ---- the terminal-log-return distribution params (mirror of tailParams' output) ----
    private final double intensity;   // L  — expected jumps over the horizon (Poisson mean)
    private final double jumpMean;    // muJ = ln(1 - gap), a DOWN gap
    private final double jumpSd;      // dJ  — per-jump log-return dispersion
    private final double bodyVar;     // vDiff — diffusion body variance over the horizon
    private final double bodySd;      // sigBody = sqrt(vDiff)
    private final double drift;       // -0.5*vDiff — martingale BODY only (jumps uncompensated)
    private final double gap;         // characteristic down-gap fraction
    private final String sectorKey;   // resolved prior bucket ("Semis"/"Health"/… or "—")
    private final boolean eventSoon;  // a scheduled catalyst inside the horizon fattened the jump
    private final String eventName;   // catalyst descriptor, null when none
    private final GapStance stance;

    private JumpMixtureTerminal(double intensity, double jumpMean, double jumpSd, double bodyVar,
                                double bodySd, double drift, double gap, String sectorKey,
                                boolean eventSoon, String eventName, GapStance stance) {
        this.intensity = intensity;
        this.jumpMean = jumpMean;
        this.jumpSd = jumpSd;
        this.bodyVar = bodyVar;
        this.bodySd = bodySd;
        this.drift = drift;
        this.gap = gap;
        this.sectorKey = sectorKey;
        this.eventSoon = eventSoon;
        this.eventName = eventName;
        this.stance = stance;
    }

    /**
     * Calibrate the distribution from supplied evidence. Missing regime, move, or stance inputs are
     * never replaced with the former browser fixture's 55/6/base defaults: those substitutions
     * published a precise tail probability even when the market evidence needed to support it did
     * not exist.
     */
    public static JumpMixtureTerminal of(String sectorLabel, double ivRankPct, double expectedMovePct,
                                         GapStance stance, boolean eventSoon, String eventName) {
        if (stance == null) {
            throw new IllegalArgumentException("jump-tail stance is required");
        }
        if (!Double.isFinite(ivRankPct) || ivRankPct < 0.0 || ivRankPct > 100.0) {
            throw new IllegalArgumentException("jump-tail IV rank must be within 0..100");
        }
        if (!Double.isFinite(expectedMovePct) || expectedMovePct <= 0.0) {
            throw new IllegalArgumentException("jump-tail expected move must be positive");
        }
        String key = resolveKey(sectorLabel);
        SectorPrior sec = SECTOR_PRIORS.getOrDefault(key, DEFAULT_PRIOR);
        double dial = stance.dial;
        double evMult = eventSoon ? 1.7 : 1.0;
        // jump intensity over the horizon; IV-rank scales it, a known catalyst fattens it, the dial tilts it
        double L = Math.clamp(sec.lam() * (0.7 + ivRankPct / 100.0 * 0.7) * evMult * dial, 0.015, 0.45);
        // characteristic down-gap size (a touch wider through an event)
        double gap = Math.clamp(sec.gap() * (1 + (ivRankPct - 50) / 100.0 * 0.3) * (eventSoon ? 1.1 : 1.0),
                0.05, 0.30);
        double muJ = Math.log(1 - gap);            // log-return jump: mean = the gap
        double dJ = 0.05;                          // small dispersion
        double vBody = Math.pow(expectedMovePct / 100.0, 2); // body keeps the full IV-implied vol
        double drift = -0.5 * vBody;               // martingale body; down-jumps left uncompensated
        return new JumpMixtureTerminal(L, muJ, dJ, vBody, Math.sqrt(vBody), drift, gap,
                "default".equals(key) ? "Broad market" : key, eventSoon, eventName, stance);
    }

    /**
     * Merton Poisson-Gaussian terminal density of the log-return {@code x} (mirror of {@code tailDens}):
     * a Poisson mixture of normal bodies shifted by k down-gaps.
     */
    public double logReturnDensity(double x) {
        double w = Math.exp(-intensity), sum = 0;
        for (int k = 0; k <= POISSON_TERMS; k++) {
            sum += w * normalPdf(x, drift + k * jumpMean, Math.sqrt(bodyVar + k * jumpSd * jumpSd));
            w *= intensity / (k + 1);
        }
        return sum;
    }

    private static double normalPdf(double x, double m, double s) {
        double z = (x - m) / s;
        return Math.exp(-0.5 * z * z) / (s * SQRT_2PI);
    }

    /**
     * Tail-aware probability of profit as an integer percent in [1,99] (mirror of {@code popCalc}):
     * the density mass where the payoff finishes positive. {@code payoffAtPrice} maps an underlying
     * price (dollars) to any monotone-in-sign P/L — only the sign is read here.
     */
    public int popPercent(double spot, DoubleUnaryOperator payoffAtPrice) {
        double dx = (HI_X - LO_X) / GRID, p = 0, tot = 0;
        for (int i = 0; i < GRID; i++) {
            double x = LO_X + dx * (i + 0.5), px = spot * Math.exp(x), d = logReturnDensity(x);
            tot += d;
            if (payoffAtPrice.applyAsDouble(px) > 0) p += d;
        }
        return (int) Math.clamp(Math.round(p / tot * 100), 1L, 99L);
    }

    /**
     * Terminal expected shortfall — the mean P/L in the worst 5% of the tail mass (mirror of
     * {@code tailExpectedShortfall}). {@code payoffCentsAtPrice} returns P/L in CENTS, so the result
     * is cents, the backend's money unit.
     */
    public long expectedShortfallCents(double spot, DoubleUnaryOperator payoffCentsAtPrice) {
        double dx = (HI_X - LO_X) / GRID;
        double[] pl = new double[GRID], w = new double[GRID];
        double tot = 0;
        for (int i = 0; i < GRID; i++) {
            double x = LO_X + dx * (i + 0.5);
            pl[i] = payoffCentsAtPrice.applyAsDouble(spot * Math.exp(x));
            w[i] = logReturnDensity(x);
            tot += w[i];
        }
        Integer[] idx = new Integer[GRID];
        for (int i = 0; i < GRID; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(pl[a], pl[b]));
        double target = Math.max(1e-9, tot * 0.05), used = 0, sum = 0;
        for (int j = 0; j < GRID && used < target; j++) {
            double take = Math.min(w[idx[j]], target - used);
            sum += pl[idx[j]] * take;
            used += take;
        }
        return Math.round(sum / Math.max(1e-9, used));
    }

    /**
     * The full per-stance receipt for this calibration (mirror of {@code tailStats} + POP + ES). The
     * gap statistic stresses whichever side HURTS: a down-gap is the usual equity crash, but an
     * upside-loss trade (short call / call spread) is threatened by an up-gap.
     */
    public Receipt receipt(double spot, boolean lossUnbounded, long maxLossCents,
                           DoubleUnaryOperator payoffCentsAtPrice) {
        double dn = payoffCentsAtPrice.applyAsDouble(spot * (1 - gap));
        double up = payoffCentsAtPrice.applyAsDouble(spot * (1 + gap));
        boolean downWorse = dn <= up;
        double worse = downWorse ? dn : up;
        int popPct = popPercent(spot, payoffCentsAtPrice);
        long es = expectedShortfallCents(spot, payoffCentsAtPrice);
        boolean atMaxLoss = !lossUnbounded && worse <= -maxLossCents * 0.98;
        return new Receipt(stance.name(), stance.dial, popPct / 100.0, es,
                (int) Math.round(gap * 100), Math.round(worse), downWorse ? "-" : "+",
                atMaxLoss, lossUnbounded, sectorKey, (int) Math.round(intensity * 100),
                eventSoon, eventName, round6(intensity), round6(jumpMean), round6(jumpSd),
                round6(bodySd), round6(drift), round6(gap));
    }

    /**
     * The gap-stance-parameterized tail bundle: one receipt per calm/base/tense stance, so the desk
     * gap dial reads a backend receipt for each stance instead of recomputing anything in the browser.
     * {@code base} is the headline. Unavailable (mixed-expiry / no positive anchor) mirrors the
     * terminal-payoff receipt's honesty rather than inventing a curve.
     */
    public static Tail tail(double spot, String sectorLabel, double ivRankPct, double expectedMovePct,
                            boolean eventSoon, String eventName, boolean available,
                            boolean lossUnbounded, long maxLossCents,
                            DoubleUnaryOperator payoffCentsAtPrice, String unavailableReason) {
        if (!available || spot <= 0 || payoffCentsAtPrice == null) {
            return unavailable(unavailableReason != null ? unavailableReason
                    : "No single-expiration curve / positive underlying anchor for the jump-mixture tail.");
        }
        if (!Double.isFinite(ivRankPct) || ivRankPct < 0.0 || ivRankPct > 100.0) {
            return unavailable("Jump-tail probability requires an observed IV rank within 0..100.");
        }
        if (!Double.isFinite(expectedMovePct) || expectedMovePct <= 0.0) {
            return unavailable("Jump-tail probability requires a positive horizon expected move from option IV.");
        }
        Receipt calm = of(sectorLabel, ivRankPct, expectedMovePct, GapStance.CALM, eventSoon, eventName)
                .receipt(spot, lossUnbounded, maxLossCents, payoffCentsAtPrice);
        Receipt base = of(sectorLabel, ivRankPct, expectedMovePct, GapStance.BASE, eventSoon, eventName)
                .receipt(spot, lossUnbounded, maxLossCents, payoffCentsAtPrice);
        Receipt tense = of(sectorLabel, ivRankPct, expectedMovePct, GapStance.TENSE, eventSoon, eventName)
                .receipt(spot, lossUnbounded, maxLossCents, payoffCentsAtPrice);
        return new Tail(SCHEMA, MODEL, true, GapStance.BASE.name(), base, calm, tense, BASIS, null);
    }

    /** An explicit unavailable receipt for callers that cannot support the tail distribution. */
    public static Tail unavailable(String reason) {
        String namedReason = reason == null || reason.isBlank()
                ? "The jump-mixture tail is unavailable because its required evidence was not supplied."
                : reason;
        return new Tail(SCHEMA, MODEL, false, GapStance.BASE.name(), null, null, null, BASIS,
                namedReason);
    }

    /**
     * One stance's honest tail readout. {@code pop} is a probability in [0.01,0.99] (the tail-aware
     * probability of profit); {@code expectedShortfallCents} and {@code gapLossCents} are cents.
     */
    public record Receipt(
            String stance,
            double dial,
            double pop,                    // tail-aware probability of profit, [0.01, 0.99]
            long expectedShortfallCents,   // mean P/L in the worst 5% of tail mass (cents)
            int gapPct,                    // characteristic gap size, whole percent
            long gapLossCents,             // deterministic P/L at the characteristic gap on the side that hurts
            String gapDir,                 // "-" down-gap, "+" up-gap (whichever is worse)
            boolean atMaxLoss,             // the characteristic gap already reaches ~max loss
            boolean undefinedRisk,         // the structure's loss is uncapped
            String sector,                 // resolved prior bucket ("Semis"/"Health"/… or "—")
            int intensityPct,              // expected jumps over the horizon, whole percent (lambda*100)
            boolean eventSoon,
            String eventName,
            double intensity,              // raw params, for transparency (6-dp)
            double jumpMeanLog,
            double jumpSd,
            double bodySd,
            double drift,
            double gap) {}

    /** The three-stance bundle that rides {@code evaluation.risk.jumpTail} and the held-line receipts. */
    public record Tail(
            String schemaVersion,
            String modelVersion,
            boolean available,
            String headlineStance,
            Receipt base,
            Receipt calm,
            Receipt tense,
            String basis,
            String unavailableReason) {

        /** The headline (base-stance) tail-aware POP, or null when unavailable. */
        public Double pop() { return base == null ? null : base.pop(); }
    }

    /** Map a universe/allocation sector label to its prior bucket. Pure string logic — no market dep. */
    static String resolveKey(String sectorLabel) {
        if (sectorLabel == null || sectorLabel.isBlank()) return "default";
        String s = sectorLabel.toLowerCase(Locale.ROOT);
        if (s.contains("semiconductor") || s.equals("semis")) return "Semis";
        if (s.contains("health")) return "Health";
        if (s.contains("index") || s.contains("macro") || s.contains("etf") || s.startsWith("core (")) return "Index";
        if (s.contains("software")) return "Software";
        if (s.contains("auto")) return "Autos";
        if (s.equals("tech") || s.equals("technology")) return "Tech";
        return "default";
    }

    private static double round6(double v) { return Math.round(v * 1e6) / 1e6; }
}
