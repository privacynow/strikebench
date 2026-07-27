package io.liftandshift.strikebench.market;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One request-scoped candle-acquisition receipt.
 *
 * <p>The returned {@link CandleSeries} is the data result. Conditions explain why a provider sent
 * no data without turning a local allowance decision or a pre-history range into a provider
 * outage. They travel with the request that produced them; durable scheduling and coverage facts
 * are owned by {@code DataSyncState}, never by process-global provider/service side channels.
 */
public record CandleAcquisition(CandleSeries series, List<Condition> conditions) {

    public enum Kind {
        RANGE_UNAVAILABLE,
        BUDGET_EXHAUSTED
    }

    public record Condition(Kind kind, String provider, LocalDate earliestAvailable,
                            Instant resumeAt, Integer dailyLimit) {
        public Condition {
            kind = Objects.requireNonNull(kind, "kind");
            if (provider == null || provider.isBlank()) {
                throw new IllegalArgumentException("condition provider is required");
            }
            provider = provider.trim().toLowerCase(Locale.ROOT);
            if (kind == Kind.RANGE_UNAVAILABLE && earliestAvailable == null) {
                throw new IllegalArgumentException("range-unavailable condition requires earliestAvailable");
            }
            if (kind == Kind.BUDGET_EXHAUSTED && dailyLimit != null && dailyLimit < 0) {
                throw new IllegalArgumentException("dailyLimit must be non-negative");
            }
        }

        public static Condition rangeUnavailable(String provider, LocalDate earliestAvailable) {
            return new Condition(Kind.RANGE_UNAVAILABLE, provider, earliestAvailable, null, null);
        }

        public static Condition budgetExhausted(String provider, Instant resumeAt, int dailyLimit) {
            return new Condition(Kind.BUDGET_EXHAUSTED, provider, null, resumeAt, dailyLimit);
        }

        /** User-safe condition text; it never describes a local denial as a failed source request. */
        public String summary() {
            return switch (kind) {
                case RANGE_UNAVAILABLE -> "No daily history exists before " + earliestAvailable
                        + " from " + provider + ".";
                case BUDGET_EXHAUSTED -> provider + " request allowance exhausted"
                        + (dailyLimit == null ? "" : " (" + dailyLimit + "/" + dailyLimit + ")")
                        + "; no external request sent"
                        + (resumeAt == null ? "." : "; resumes " + resumeAt + ".");
            };
        }
    }

    public CandleAcquisition {
        series = series == null ? CandleSeries.EMPTY : series;
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }

    public static CandleAcquisition data(CandleSeries series) {
        return new CandleAcquisition(series, List.of());
    }

    public static CandleAcquisition empty(List<Condition> conditions) {
        List<Condition> safe = conditions == null ? List.of() : List.copyOf(conditions);
        String reportingProvider = safe.isEmpty() ? null : safe.getFirst().provider();
        return new CandleAcquisition(CandleSeries.emptyFrom(reportingProvider), safe);
    }
}
