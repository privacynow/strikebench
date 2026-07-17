package io.liftandshift.strikebench.sim;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * The quantitative assumptions owned by the unified Scenario Canvas.  Price-path mechanics stay
 * in {@link ScenarioSpec}/{@link PathEnsembleService}; this record adds the assumptions needed to
 * reprice listed structures through that same path matrix: a day-indexed ATM-IV path, strike skew,
 * term structure, dividends, and the expiration transformation policy.
 *
 * <p>The record is deliberately typed rather than a bag of JSON.  It is normalized before use and
 * persisted beside the Plan ensemble, so a restored canvas can reproduce the exact surface it
 * showed.  An empty list of IV nodes means the existing {@link IvSpec} recipe remains authoritative.
 */
public record ScenarioCanvasSpec(
        String calendar,
        Double dividendYieldAnnual,
        String dividendBasis,
        double skewVolPerLogMoneyness,
        double termVolPerSqrtYear,
        SurfaceDynamics surfaceDynamics,
        SettlementPolicy settlementPolicy,
        ExercisePolicy exercisePolicy,
        List<IvNode> ivNodes,
        TemplateReceipt template) {

    public static final String MODEL_VERSION = "scenario-canvas-1";

    public ScenarioCanvasSpec {
        calendar = calendar == null || calendar.isBlank() ? "NYSE" : calendar.trim().toUpperCase();
        dividendBasis = dividendBasis == null || dividendBasis.isBlank()
                ? (dividendYieldAnnual == null
                    ? "Dividend source unavailable; pricing uses 0% and discloses that limitation."
                    : "User-authored annualized continuous dividend yield.")
                : dividendBasis.trim();
        surfaceDynamics = surfaceDynamics == null ? SurfaceDynamics.STICKY_MONEYNESS : surfaceDynamics;
        settlementPolicy = settlementPolicy == null ? SettlementPolicy.CASH_INTRINSIC : settlementPolicy;
        exercisePolicy = exercisePolicy == null ? ExercisePolicy.EXPIRATION_ONLY : exercisePolicy;
        ivNodes = ivNodes == null ? List.of() : List.copyOf(ivNodes);
    }

    public enum SurfaceDynamics { STICKY_MONEYNESS, STICKY_STRIKE }
    public enum SettlementPolicy { CASH_INTRINSIC, PHYSICAL_IF_ITM }
    public enum ExercisePolicy { EXPIRATION_ONLY, EXTRINSIC_THRESHOLD }
    public enum TemplateKind {
        EARNINGS_GAP_UP, EARNINGS_GAP_DOWN, SECTOR_DRAWDOWN, DRIFT_TO_TARGET,
        HISTORICAL_REPLAY
    }

    /** One exact ATM-IV assumption at a session close. Day zero is the anchor. */
    public record IvNode(int dayIndex, double atmIv) {
        public IvNode {
            if (dayIndex < 0) throw new IllegalArgumentException("IV dayIndex must be >= 0");
            if (!(atmIv >= 0.01 && atmIv <= 4.0) || !Double.isFinite(atmIv)) {
                throw new IllegalArgumentException("ATM IV must be 1%..400% in annualized units");
            }
        }
    }

    /**
     * Server-issued provenance for a template. {@code observed=false} is first-class for a
     * user-authored target; templates advertised as historical require Observed/broker-owned
     * inputs and refuse Demo or simulated substitution.
     */
    public record TemplateReceipt(
            TemplateKind kind,
            String source,
            String provenance,
            LocalDate inputAsOf,
            LocalDate windowFrom,
            LocalDate windowTo,
            int observations,
            boolean observed,
            boolean noHindsight,
            String legDayProvenance,
            String note,
            String fingerprint) {
        public TemplateReceipt {
            if (kind == null) throw new IllegalArgumentException("template kind is required");
            if (source == null || source.isBlank()) throw new IllegalArgumentException("template source is required");
            if (provenance == null || provenance.isBlank()) throw new IllegalArgumentException("template provenance is required");
            if (inputAsOf == null) throw new IllegalArgumentException("template inputAsOf is required");
            if (observations < 0) throw new IllegalArgumentException("template observations cannot be negative");
            legDayProvenance = legDayProvenance == null || legDayProvenance.isBlank()
                    ? "Underlying path uses the named source; option values are modeled from the declared canvas surface."
                    : legDayProvenance.trim();
            note = note == null ? "" : note.trim();
            fingerprint = fingerprint == null ? "" : fingerprint.trim().toLowerCase();
        }

        public TemplateReceipt signed() {
            return new TemplateReceipt(kind, source, provenance, inputAsOf, windowFrom, windowTo,
                    observations, observed, noHindsight, legDayProvenance, note, digest(material()));
        }

        public boolean validFingerprint() {
            return !fingerprint.isBlank() && fingerprint.equals(digest(material()));
        }

        private String material() {
            return kind + "|" + source + "|" + provenance + "|" + inputAsOf + "|" + windowFrom
                    + "|" + windowTo + "|" + observations + "|" + observed + "|" + noHindsight
                    + "|" + legDayProvenance + "|" + note;
        }
    }

    /** Strict, deterministic normalization at the Plan-owned horizon. */
    public ScenarioCanvasSpec sane(int horizonDays) {
        int horizon = Math.clamp(horizonDays, 1, 756);
        if (!"NYSE".equals(calendar)) throw new IllegalArgumentException("canvas calendar must be NYSE");
        Double dividend = dividendYieldAnnual;
        if (dividend != null && (!Double.isFinite(dividend) || dividend < -0.25 || dividend > 1.0)) {
            throw new IllegalArgumentException("annual dividend yield must be -25%..100% or unavailable");
        }
        if (!Double.isFinite(skewVolPerLogMoneyness) || Math.abs(skewVolPerLogMoneyness) > 3) {
            throw new IllegalArgumentException("IV skew must be within +/-3 vol units per log-moneyness");
        }
        if (!Double.isFinite(termVolPerSqrtYear) || Math.abs(termVolPerSqrtYear) > 3) {
            throw new IllegalArgumentException("IV term slope must be within +/-3 vol units per sqrt-year");
        }
        ArrayList<IvNode> nodes = new ArrayList<>(ivNodes);
        nodes.sort(Comparator.comparingInt(IvNode::dayIndex));
        int prior = -1;
        for (IvNode node : nodes) {
            if (node.dayIndex() > horizon) {
                throw new IllegalArgumentException("IV node day " + node.dayIndex()
                        + " lies beyond the canvas horizon of " + horizon + " sessions");
            }
            if (node.dayIndex() == prior) throw new IllegalArgumentException("only one IV node is allowed per session");
            prior = node.dayIndex();
        }
        TemplateReceipt receipt = template;
        if (receipt != null && !receipt.validFingerprint()) {
            throw new IllegalArgumentException("template provenance receipt changed; apply the template again");
        }
        return new ScenarioCanvasSpec("NYSE", dividend, dividendBasis,
                Math.clamp(skewVolPerLogMoneyness, -3, 3),
                Math.clamp(termVolPerSqrtYear, -3, 3), surfaceDynamics,
                settlementPolicy, exercisePolicy, nodes, receipt);
    }

    public static ScenarioCanvasSpec defaults() {
        return new ScenarioCanvasSpec("NYSE", null,
                "Dividend source unavailable; pricing uses 0% and discloses that limitation.",
                0, 0, SurfaceDynamics.STICKY_MONEYNESS,
                SettlementPolicy.CASH_INTRINSIC, ExercisePolicy.EXPIRATION_ONLY, List.of(), null);
    }

    /** ATM IV at a session close; explicit nodes interpolate linearly, otherwise legacy IV wins. */
    public double atmIv(int day, int horizon, double legacyIv) {
        if (ivNodes.isEmpty()) return clampIv(legacyIv);
        int d = Math.clamp(day, 0, Math.max(1, horizon));
        IvNode first = ivNodes.getFirst();
        if (d <= first.dayIndex()) return first.atmIv();
        IvNode last = ivNodes.getLast();
        if (d >= last.dayIndex()) return last.atmIv();
        for (int i = 1; i < ivNodes.size(); i++) {
            IvNode right = ivNodes.get(i);
            if (d > right.dayIndex()) continue;
            IvNode left = ivNodes.get(i - 1);
            double w = (double) (d - left.dayIndex()) / (right.dayIndex() - left.dayIndex());
            return clampIv(left.atmIv() + w * (right.atmIv() - left.atmIv()));
        }
        return last.atmIv();
    }

    /**
     * Evolve one strike/expiry point from the day ATM node.  Sticky-moneyness moves skew with the
     * underlying; sticky-strike keeps the anchor spot in the moneyness term.  Term slope is stated
     * in vol units per sqrt-year and is referenced to a 30-calendar-day point.
     */
    public double surfaceIv(int day, int horizon, double legacyIv, double anchorSpot,
                            double currentSpot, double strike, double yearsToExpiry) {
        double atm = atmIv(day, horizon, legacyIv);
        double referenceSpot = surfaceDynamics == SurfaceDynamics.STICKY_STRIKE ? anchorSpot : currentSpot;
        double moneyness = Math.log(Math.max(1e-9, strike) / Math.max(1e-9, referenceSpot));
        double term = Math.sqrt(Math.max(0, yearsToExpiry)) - Math.sqrt(30.0 / 365.0);
        return clampIv(atm + skewVolPerLogMoneyness * moneyness + termVolPerSqrtYear * term);
    }

    public double dividendYieldForPricing() { return dividendYieldAnnual == null ? 0 : dividendYieldAnnual; }

    private static double clampIv(double value) { return Math.clamp(value, 0.01, 4.0); }

    private static String digest(String material) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception impossible) {
            throw new IllegalStateException("Could not identify template provenance", impossible);
        }
    }
}
