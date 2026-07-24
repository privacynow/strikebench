package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.util.EventBus;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongConsumer;

/**
 * Persists provider breaker deadlines announced on the shared event bus and restores them at boot.
 * A process restart is not a valid way to evade an upstream provider's requested quiet period.
 */
public final class ProviderCooldownState {
    private ProviderCooldownState() {}

    /**
     * Restores every configured provider, then subscribes once for future breaker trips.
     * Invalid or expired stored values are harmless: each provider owns the final deadline check.
     */
    public static Runnable wire(SettingsStore settings, EventBus events,
                                Map<String, LongConsumer> providers) {
        Map<String, LongConsumer> normalized = new LinkedHashMap<>();
        if (providers != null) {
            providers.forEach((name, seed) -> {
                if (seed != null) normalized.put(providerName(name), seed);
            });
        }
        normalized.forEach((provider, seed) -> {
            try {
                settings.get(settingKey(provider)).ifPresent(value -> seed.accept(Long.parseLong(value)));
            } catch (RuntimeException ignored) {
                // Operational state is best effort; malformed local state must never block boot.
            }
        });
        if (normalized.isEmpty()) return () -> {};
        return events.subscribe(event -> {
            if (!"provider.cooldown".equals(event.type())) return;
            String provider;
            try {
                provider = providerName(String.valueOf(event.data().get("provider")));
            } catch (RuntimeException ignored) {
                return;
            }
            if (!normalized.containsKey(provider)) return;
            Long untilMs = deadline(event.data().get("untilMs"));
            if (untilMs == null) return;
            try {
                settings.put(settingKey(provider), String.valueOf(untilMs));
            } catch (RuntimeException ignored) {
                // Announcing a cooldown must not fail the provider request/error path.
            }
        });
    }

    static String settingKey(String provider) {
        return providerName(provider) + "_cooldown_until";
    }

    private static String providerName(String raw) {
        String provider = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (provider.isBlank() || !provider.matches("[a-z0-9_-]+")) {
            throw new IllegalArgumentException("invalid provider name");
        }
        return provider;
    }

    private static Long deadline(Object raw) {
        if (raw instanceof Number n) return n.longValue();
        try { return raw == null ? null : Long.parseLong(String.valueOf(raw)); }
        catch (NumberFormatException ignored) { return null; }
    }
}
