package io.liftandshift.strikebench.model;

import io.liftandshift.strikebench.market.MarketLane;

import java.util.Collection;
import java.util.Locale;

/** Provenance + age + source: the three facts the old Freshness enum conflated. */
public record DataEvidence(DataProvenance provenance, DataAge age, String source) {

    public static DataEvidence of(String source, Freshness freshness) {
        Freshness f = freshness == null ? Freshness.MISSING : freshness;
        String s = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
        DataProvenance provenance;
        if (f == Freshness.FIXTURE || s.equals("fixture") || s.contains("demo")) provenance = DataProvenance.DEMO;
        else if (f == Freshness.SIMULATED || s.equals("simulated")) provenance = DataProvenance.SIMULATED;
        else if (f == Freshness.MODELED || s.equals("model") || s.equals("synthetic")) provenance = DataProvenance.MODELED;
        else if (f == Freshness.MISSING) provenance = DataProvenance.MISSING;
        else if (s.contains("etrade") || s.contains("broker")) provenance = DataProvenance.BROKER;
        else provenance = DataProvenance.OBSERVED;

        DataAge age = switch (f) {
            case REALTIME -> DataAge.REALTIME;
            case DELAYED -> DataAge.DELAYED;
            case EOD -> DataAge.EOD;
            case STALE -> DataAge.STALE;
            case MISSING -> DataAge.MISSING;
            case MODELED, SIMULATED, FIXTURE -> DataAge.NOT_APPLICABLE;
        };
        return new DataEvidence(provenance, age, source);
    }

    public static DataEvidence missing(String source) {
        return new DataEvidence(DataProvenance.MISSING, DataAge.MISSING, source);
    }

    /** Page/report rollup: provenance and age remain independent; mixed origins stay MIXED. */
    public static DataEvidence aggregate(Collection<DataEvidence> values) {
        if (values == null || values.isEmpty()) return missing("none");
        java.util.List<DataEvidence> usable = values.stream().filter(java.util.Objects::nonNull).toList();
        if (usable.isEmpty()) return missing("none");
        boolean anyMissing = usable.stream().anyMatch(e -> e.provenance == DataProvenance.MISSING);
        java.util.Set<DataProvenance> origins = new java.util.LinkedHashSet<>();
        for (DataEvidence e : usable) if (e.provenance != DataProvenance.MISSING) origins.add(e.provenance);
        DataProvenance p = anyMissing ? DataProvenance.MISSING
                : origins.size() == 1 ? origins.iterator().next() : DataProvenance.MIXED;
        DataAge age = usable.stream().map(DataEvidence::age)
                .max(java.util.Comparator.comparingInt(DataEvidence::ageRank)).orElse(DataAge.MISSING);
        return new DataEvidence(p, age, origins.size() <= 1 && !anyMissing
                ? usable.getFirst().source : "multiple inputs");
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
    public boolean executableIn(MarketLane lane) {
        if (lane == null) return false;
        return switch (lane) {
            case OBSERVED -> (provenance == DataProvenance.OBSERVED || provenance == DataProvenance.BROKER)
                    && (age == DataAge.REALTIME || age == DataAge.DELAYED);
            case DEMO -> provenance == DataProvenance.DEMO && age != DataAge.STALE && age != DataAge.MISSING;
            case SIMULATED -> provenance == DataProvenance.SIMULATED && age != DataAge.STALE && age != DataAge.MISSING;
            case SCENARIO -> false; // a scenario is analysis, never an executable exchange
        };
    }

    /** Whether analysis may consume the value without crossing market lanes. */
    public boolean usableIn(MarketLane lane) {
        if (lane == null) return false;
        return switch (lane) {
            case OBSERVED -> provenance == DataProvenance.OBSERVED || provenance == DataProvenance.BROKER;
            case DEMO -> provenance == DataProvenance.DEMO;
            case SIMULATED -> provenance == DataProvenance.SIMULATED;
            case SCENARIO -> provenance == DataProvenance.MODELED || provenance == DataProvenance.OBSERVED;
        };
    }
}
