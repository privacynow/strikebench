package io.liftandshift.strikebench.eval;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Per-input evidence receipt carried by every analysis; missing inputs never disappear. */
public record DataCoverageReceipt(
        Map<String, InputCoverage> inputs,
        String pricingModel,
        List<String> limitations
) {
    public DataCoverageReceipt {
        inputs = inputs == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        if (pricingModel == null || pricingModel.isBlank()) {
            throw new IllegalArgumentException("pricing model disclosure is required");
        }
    }

    public record InputCoverage(EvidenceLevel level, String detail) {
        public InputCoverage {
            level = level == null ? EvidenceLevel.UNKNOWN : level;
            if (detail == null || detail.isBlank()) detail = level.label();
        }
    }
}
