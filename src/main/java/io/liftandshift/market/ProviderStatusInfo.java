package io.liftandshift.market;

/** Health of one provider within one data domain. Plain types for direct JSON serialization. */
public record ProviderStatusInfo(
        String provider,
        String domain,
        String state,           // OK | ERROR | EMPTY | UNKNOWN | UNCONFIGURED
        String detail,
        Long lastSuccessEpochMs,
        Long lastErrorEpochMs
) {
    public static ProviderStatusInfo unknown(String provider, String domain) {
        return new ProviderStatusInfo(provider, domain, "UNKNOWN", "not yet used", null, null);
    }
}
