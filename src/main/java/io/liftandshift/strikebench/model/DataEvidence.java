package io.liftandshift.strikebench.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.liftandshift.strikebench.market.MarketMode;

import java.util.Collection;
import java.util.Locale;

/** The single market-evidence value: origin, age, and named source stay together. */
public record DataEvidence(DataProvenance provenance, DataAge age, String source) {

    public DataEvidence {
        provenance = provenance == null ? DataProvenance.MISSING : provenance;
        age = age == null ? DataAge.MISSING : age;
    }

    public static DataEvidence observed(String source, DataAge age) {
        DataAge resolved = age == null ? DataAge.MISSING : age;
        return new DataEvidence(DataProvenance.OBSERVED, resolved, source);
    }

    public static DataEvidence broker(String source, DataAge age) {
        DataAge resolved = age == null ? DataAge.MISSING : age;
        return new DataEvidence(DataProvenance.BROKER, resolved, source);
    }

    public static DataEvidence demo(String source) {
        return new DataEvidence(DataProvenance.DEMO, DataAge.NOT_APPLICABLE, source);
    }

    public static DataEvidence simulated(String source) {
        return new DataEvidence(DataProvenance.SIMULATED, DataAge.NOT_APPLICABLE, source);
    }

    public static DataEvidence modeled(String source) {
        return new DataEvidence(DataProvenance.MODELED, DataAge.NOT_APPLICABLE, source);
    }

    /** Parse the one public evidence label used in stored artifacts and compact wire views. */
    public static DataEvidence fromLabel(String source, String label) {
        String value = label == null ? "MISSING" : label.trim().toUpperCase(Locale.ROOT);
        String s = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "REALTIME" -> (s.contains("etrade") || s.contains("broker"))
                    ? broker(source, DataAge.REALTIME) : observed(source, DataAge.REALTIME);
            case "DELAYED" -> (s.contains("etrade") || s.contains("broker"))
                    ? broker(source, DataAge.DELAYED) : observed(source, DataAge.DELAYED);
            case "EOD" -> observed(source, DataAge.EOD);
            case "STALE" -> new DataEvidence(
                    s.contains("etrade") || s.contains("broker") ? DataProvenance.BROKER
                            : s.isBlank() ? DataProvenance.MISSING : DataProvenance.OBSERVED,
                    DataAge.STALE, source);
            case "FIXTURE", "DEMO" -> demo(source);
            case "SIMULATED" -> simulated(source);
            case "MODELED" -> modeled(source);
            default -> missing(source);
        };
    }

    public static DataEvidence missing(String source) {
        return new DataEvidence(DataProvenance.MISSING, DataAge.MISSING, source);
    }

    /** Stable compact label for existing API fields and persisted analysis artifacts. */
    public String label() {
        return switch (age) {
            case REALTIME -> "REALTIME";
            case DELAYED -> "DELAYED";
            case EOD -> "EOD";
            case STALE -> "STALE";
            case MISSING -> "MISSING";
            case NOT_APPLICABLE -> switch (provenance == null
                    ? DataProvenance.MISSING : provenance) {
                case DEMO -> "FIXTURE";
                case SIMULATED -> "SIMULATED";
                case MODELED -> "MODELED";
                default -> "MISSING";
            };
        };
    }

    public DataEvidence withAge(DataAge replacement) {
        return new DataEvidence(provenance, replacement, source);
    }

    @JsonIgnore
    public boolean isObservedLive() {
        return (provenance == DataProvenance.OBSERVED || provenance == DataProvenance.BROKER)
                && (age == DataAge.REALTIME || age == DataAge.DELAYED);
    }

    @JsonIgnore
    public boolean isStaleOrMissing() {
        return age == DataAge.STALE || age == DataAge.MISSING
                || provenance == DataProvenance.MISSING;
    }

    /** Page/report rollup: provenance and age remain independent; mixed origins stay MIXED. */
    public static DataEvidence aggregate(Collection<DataEvidence> values) {
        if (values == null || values.isEmpty()) return missing("none");
        java.util.List<DataEvidence> usable = values.stream().filter(java.util.Objects::nonNull).toList();
        if (usable.isEmpty()) return missing("none");
        boolean anyMissing = usable.stream().anyMatch(e -> e.provenance == DataProvenance.MISSING);
        java.util.Set<DataProvenance> origins = new java.util.LinkedHashSet<>();
        for (DataEvidence e : usable) if (e.provenance != DataProvenance.MISSING) origins.add(e.provenance);
        java.util.Set<String> sources = new java.util.LinkedHashSet<>();
        for (DataEvidence e : usable) {
            if (e.source != null && !e.source.isBlank()) sources.add(e.source);
        }
        DataProvenance p = anyMissing ? DataProvenance.MISSING
                : origins.size() == 1 ? origins.iterator().next() : DataProvenance.MIXED;
        DataAge age = usable.stream().map(DataEvidence::age)
                .max(java.util.Comparator.comparingInt(DataEvidence::ageRank)).orElse(DataAge.MISSING);
        String source = !anyMissing && sources.size() == 1 ? sources.iterator().next()
                : sources.isEmpty() ? "none" : "multiple inputs";
        return new DataEvidence(p, age, source);
    }

    private static int ageRank(DataAge age) {
        return switch (age == null ? DataAge.MISSING : age) {
            case REALTIME -> 0;
            case DELAYED -> 1;
            case EOD -> 2;
            case STALE -> 3;
            case NOT_APPLICABLE -> 4;
            case MISSING -> 5;
        };
    }

    /** A value may be executable only inside the market that owns its provenance. */
    public boolean executableIn(MarketMode mode) {
        if (mode == null) return false;
        return switch (mode) {
            case OBSERVED -> (provenance == DataProvenance.OBSERVED || provenance == DataProvenance.BROKER)
                    && (age == DataAge.REALTIME || age == DataAge.DELAYED);
            case DEMO -> provenance == DataProvenance.DEMO && age != DataAge.STALE && age != DataAge.MISSING;
            case SIMULATED -> provenance == DataProvenance.SIMULATED && age != DataAge.STALE && age != DataAge.MISSING;
            case SCENARIO -> false; // a scenario is analysis, never an executable exchange
        };
    }

    /** Whether analysis may consume the value without crossing market modes. */
    public boolean usableIn(MarketMode mode) {
        if (mode == null) return false;
        return switch (mode) {
            case OBSERVED -> provenance == DataProvenance.OBSERVED || provenance == DataProvenance.BROKER;
            case DEMO -> provenance == DataProvenance.DEMO;
            case SIMULATED -> provenance == DataProvenance.SIMULATED;
            case SCENARIO -> provenance == DataProvenance.MODELED || provenance == DataProvenance.OBSERVED;
        };
    }
}
