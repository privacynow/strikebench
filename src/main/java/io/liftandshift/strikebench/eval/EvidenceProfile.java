package io.liftandshift.strikebench.eval;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-dimension evidence with a worst-of rollup. Dimensions: pricing (bid/ask), volatility,
 * greeks, liquidity, history, and rates. The {@link #rollup()} is the single badge the UI shows; the map is
 * the detail behind it. Produced by {@code EvidenceAssembler}.
 */
public record EvidenceProfile(EvidenceLevel rollup, Map<String, EvidenceLevel> perDimension, String note) {

    public EvidenceProfile {
        perDimension = perDimension == null ? Map.of() : Map.copyOf(perDimension);
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
        return new EvidenceProfile(worst, clean, note);
    }
}
