package io.liftandshift.strikebench.market;

/** Health of one provider within one data domain, for one {@link ReadCondition}. Plain types for
 *  direct JSON serialization. {@code condition} is null for domains reported at domain grain. */
public record ProviderStatusInfo(
        String provider,
        String domain,
        String condition,       // ReadCondition name, or null when the domain reports at domain grain
        String state,           // OK | ERROR | EMPTY | UNKNOWN | UNCONFIGURED | PRE_HISTORY | BUDGET_EXHAUSTED
        String detail,
        Long lastSuccessEpochMs,
        Long lastErrorEpochMs
) {
    public static ProviderStatusInfo unknown(String provider, String domain) {
        return unknown(provider, domain, null);
    }

    public static ProviderStatusInfo unknown(String provider, String domain, String condition) {
        return new ProviderStatusInfo(provider, domain, condition, "UNKNOWN", "not yet used", null, null);
    }
}
