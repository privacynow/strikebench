package io.liftandshift.strikebench.eval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-dimension evidence with a worst-of rollup. Dimensions: pricing (bid/ask), volatility,
 * greeks, liquidity, history, and rates. The {@link #rollup()} is the single badge the UI shows; the map is
 * the detail behind it. Produced by {@code EvidenceAssembler}.
 */
public record EvidenceProfile(EvidenceLevel rollup, Map<String, EvidenceLevel> perDimension, String note,
                              Map<String, ClaimEvidence> claims) {

    public record ClaimEvidence(EvidenceLevel rollup, List<String> dimensions,
                                List<String> missingDimensions, List<String> nonObservedDimensions,
                                boolean observed, String note) {
        public ClaimEvidence {
            dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
            missingDimensions = missingDimensions == null ? List.of() : List.copyOf(missingDimensions);
            nonObservedDimensions = nonObservedDimensions == null
                    ? List.of() : List.copyOf(nonObservedDimensions);
        }
    }

    public EvidenceProfile {
        perDimension = perDimension == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(perDimension));
        claims = claims == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(claims));
    }

    /** Compatibility constructor for callers that intentionally provide only a holistic profile. */
    public EvidenceProfile(EvidenceLevel rollup, Map<String, EvidenceLevel> perDimension, String note) {
        this(rollup, perDimension, note, Map.of());
    }

    /** Builds a profile whose rollup is the worst of the given dimensions. */
    public static EvidenceProfile of(Map<String, EvidenceLevel> dims, String note) {
        EvidenceLevel worst = EvidenceLevel.OBSERVED_LIVE;
        Map<String, EvidenceLevel> clean = new LinkedHashMap<>();
        for (var e : dims.entrySet()) {
            EvidenceLevel v = e.getValue() == null ? EvidenceLevel.UNKNOWN : e.getValue();
            clean.put(e.getKey(), v);
            worst = worst.worseOf(v);
        }
        if (clean.isEmpty()) worst = EvidenceLevel.UNKNOWN;
        return new EvidenceProfile(worst, clean, note, Map.of());
    }

    /** Builds the holistic disclosure and preserves the independently scoped claim receipts. */
    public static EvidenceProfile of(Map<String, EvidenceLevel> dims, String note,
                                     Map<String, ClaimEvidence> claims) {
        EvidenceProfile holistic = of(dims, note);
        return new EvidenceProfile(holistic.rollup(), holistic.perDimension(), note, claims);
    }

    public static ClaimEvidence project(Map<String, EvidenceLevel> dims, List<String> required,
                                        String note) {
        EvidenceLevel worst = EvidenceLevel.OBSERVED_LIVE;
        var missing = new java.util.ArrayList<String>();
        var nonObserved = new java.util.ArrayList<String>();
        for (String dimension : required) {
            EvidenceLevel level = dims.getOrDefault(dimension, EvidenceLevel.UNKNOWN);
            worst = worst.worseOf(level);
            if (level == EvidenceLevel.UNKNOWN) missing.add(dimension);
            if (!level.isObserved()) nonObserved.add(dimension);
        }
        if (required.isEmpty()) worst = EvidenceLevel.UNKNOWN;
        return new ClaimEvidence(worst, required, missing, nonObserved,
                !required.isEmpty() && nonObserved.isEmpty(), note);
    }

    /** Claim-scoped evidence when present; holistic rollup preserves compatibility otherwise. */
    public boolean observedFor(String claim) {
        ClaimEvidence projected = claims.get(claim);
        return projected == null ? rollup != null && rollup.isObserved() : projected.observed();
    }
}
