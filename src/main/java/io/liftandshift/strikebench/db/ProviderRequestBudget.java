package io.liftandshift.strikebench.db;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;

/** Durable per-provider request allowance shared by screens, jobs, and schedulers. */
public final class ProviderRequestBudget {

    public static final class Exhausted extends IllegalStateException {
        private final String source;
        private final int limit;

        public Exhausted(String source, int limit) {
            super("The " + source + " request allowance is exhausted for today (" + limit + "). It resets tomorrow.");
            this.source = source;
            this.limit = limit;
        }

        public String source() { return source; }
        public int limit() { return limit; }
    }

    public record Usage(String source, LocalDate period, int used, int limit, int remaining) {}

    private final Db db;
    private final Clock clock;

    public ProviderRequestBudget(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    /**
     * Consumes one request before it is sent. A non-positive limit means the user's provider plan
     * governs usage and StrikeBench does not impose a guessed allowance.
     */
    public Usage acquire(String source, int dailyLimit) {
        String key = normalize(source);
        LocalDate day = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (dailyLimit <= 0) return new Usage(key, day, 0, 0, Integer.MAX_VALUE);
        return db.tx(c -> {
            Db.execOn(c, "INSERT INTO provider_request_budget(source_key,period_key,used_count,limit_count) "
                    + "VALUES (?,?,0,?) ON CONFLICT(source_key,period_key) DO UPDATE SET "
                    + "limit_count=excluded.limit_count, updated_at=now()", key, day, dailyLimit);
            var rows = Db.queryOn(c,
                    "SELECT used_count,limit_count FROM provider_request_budget "
                            + "WHERE source_key=? AND period_key=? FOR UPDATE",
                    r -> new int[]{r.intv("used_count"), r.intv("limit_count")}, key, day);
            int used = rows.getFirst()[0], limit = rows.getFirst()[1];
            if (used >= limit) throw new Exhausted(key, limit);
            Db.execOn(c, "UPDATE provider_request_budget SET used_count=used_count+1,updated_at=now() "
                    + "WHERE source_key=? AND period_key=?", key, day);
            return new Usage(key, day, used + 1, limit, Math.max(0, limit - used - 1));
        });
    }

    public Usage usage(String source, int configuredLimit) {
        String key = normalize(source);
        LocalDate day = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (configuredLimit <= 0) return new Usage(key, day, 0, 0, Integer.MAX_VALUE);
        return db.query("SELECT used_count,limit_count FROM provider_request_budget "
                        + "WHERE source_key=? AND period_key=?",
                r -> new Usage(key, day, r.intv("used_count"), r.intv("limit_count"),
                        Math.max(0, r.intv("limit_count") - r.intv("used_count"))), key, day)
                .stream().findFirst().orElse(new Usage(key, day, 0, configuredLimit, configuredLimit));
    }

    private static String normalize(String source) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("source is required");
        return source.trim().toLowerCase(Locale.ROOT);
    }
}
