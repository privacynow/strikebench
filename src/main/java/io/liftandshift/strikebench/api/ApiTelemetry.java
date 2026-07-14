package io.liftandshift.strikebench.api;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.market.MarketDataEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Request accounting, latency classification, and bounded live-provider throttling. */
final class ApiTelemetry {
    private static final Logger log = LoggerFactory.getLogger(ApiTelemetry.class);

    private final AppConfig cfg;
    private final MarketDataEngine marketEngine;
    private final AtomicLong apiRequests = new AtomicLong();
    private final AtomicLong apiErrors = new AtomicLong();
    private final AtomicLong apiThrottled = new AtomicLong();
    private final Map<String, LatencyRing> latencyByClass = new ConcurrentHashMap<>();
    private final IpThrottle throttle = new IpThrottle(300, 50.0);

    ApiTelemetry(AppConfig cfg, MarketDataEngine marketEngine) {
        this.cfg = cfg;
        this.marketEngine = marketEngine;
    }

    void register(JavalinConfig config) {
        config.routes.after("/api/*", this::afterRequest);
        config.routes.before("/api/*", this::beforeRequest);
    }

    void recordError() {
        apiErrors.incrementAndGet();
    }

    void metrics(Context ctx) {
        Object engineStatus;
        try {
            engineStatus = marketEngine.status();
        } catch (Exception e) {
            log.debug("Market-engine metrics failure detail", e);
            engineStatus = new ApiResponses.ErrorOnly("Market engine status is temporarily unavailable");
        }
        ctx.json(new ApiResponses.Metrics<>(apiRequests.get(), latencyPercentiles(), apiErrors.get(),
                apiThrottled.get(), !cfg.fixturesOnly(), engineStatus));
    }

    private void beforeRequest(Context ctx) {
        ctx.attribute("reqStartNanos", System.nanoTime());
        apiRequests.incrementAndGet();
        if (cfg.fixturesOnly()) return;
        String path = ctx.path();
        if (path.equals("/api/health") || path.equals("/api/metrics")
                || path.equals("/api/events") || path.equals("/api/market/stream")) return;

        // X-Forwarded-For is client-controlled: honor it only for the configured or local proxy.
        String remote = ctx.ip();
        boolean trustedProxy = cfg.trustedProxy()
                || "127.0.0.1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote)
                || "::1".equals(remote);
        String forwarded = ctx.header("X-Forwarded-For");
        String ip = trustedProxy && forwarded != null ? forwarded.split(",")[0].trim() : remote;
        if (!throttle.tryAcquire(ip)) {
            apiThrottled.incrementAndGet();
            ctx.status(429).json(new ApiResponses.ErrorBody("rate limited",
                    "too many requests from this address — slow down and retry"));
            ctx.skipRemainingHandlers();
        }
    }

    private void afterRequest(Context ctx) {
        Object started = ctx.attribute("reqStartNanos");
        if (started instanceof Long nanos) {
            recordLatency(ctx.path(), (System.nanoTime() - nanos) / 1_000);
        }
    }

    private void recordLatency(String path, long micros) {
        latencyByClass.computeIfAbsent(latencyClass(path), ignored -> new LatencyRing()).record(micros);
    }

    private Map<String, Object> latencyPercentiles() {
        Map<String, Object> out = new LinkedHashMap<>();
        latencyByClass.forEach((key, value) -> out.put(key, value.percentiles()));
        return out;
    }

    static String latencyClass(String path) {
        if (path == null) return "other";
        if (path.contains("/chain")) return "chain";
        if (path.startsWith("/api/sim/market")) return "world";
        if (path.startsWith("/api/datasets") || path.startsWith("/api/data")) return "data-ops";
        if (path.startsWith("/api/broker")) return "broker";
        if (path.startsWith("/api/sparklines")) return "quotes";
        if (path.startsWith("/api/research/scout") || path.contains("/intent-ladder")
                || path.contains("/strategy/run") || path.contains("/strategy/fit")
                || path.startsWith("/api/evaluate") || path.startsWith("/api/opportunities")
                || path.startsWith("/api/optimize")) return "compute";
        if (path.startsWith("/api/research")) return "research";
        if (path.startsWith("/api/quotes") || path.startsWith("/api/market")) return "quotes";
        if (path.startsWith("/api/sim")) return "compute";
        if (path.startsWith("/api/trades") || path.startsWith("/api/portfolio")
                || path.startsWith("/api/positions")) return "trading";
        return "other";
    }

    static final class LatencyRing {
        private final long[] ring = new long[1024];
        private long count;

        synchronized void record(long micros) {
            ring[(int) (count++ % ring.length)] = micros;
        }

        synchronized Map<String, Object> percentiles() {
            long n = Math.min(count, ring.length);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("samples", n);
            if (n == 0) return out;
            long[] copy = java.util.Arrays.copyOf(ring, (int) n);
            java.util.Arrays.sort(copy);
            out.put("p50Micros", copy[(int) (n / 2)]);
            out.put("p95Micros", copy[(int) Math.min(n - 1,
                    (long) Math.ceil(n * 0.95) - 1)]);
            out.put("maxMicros", copy[(int) n - 1]);
            return out;
        }
    }

    /** Exact bounded LRU token buckets: cycling addresses cannot reset every active client. */
    static final class IpThrottle {
        private final int burst;
        private final double perSecond;
        private final int maximumEntries;
        private final LinkedHashMap<String, Bucket> buckets =
                new LinkedHashMap<>(128, 0.75f, true);

        IpThrottle(int burst, double perSecond) {
            this(burst, perSecond, 10_000);
        }

        IpThrottle(int burst, double perSecond, int maximumEntries) {
            this.burst = burst;
            this.perSecond = perSecond;
            this.maximumEntries = Math.max(1, maximumEntries);
        }

        boolean tryAcquire(String ip) {
            if (ip == null || ip.isBlank()) return true;
            synchronized (buckets) {
                Bucket bucket = buckets.get(ip);
                if (bucket == null) {
                    while (buckets.size() >= maximumEntries) {
                        var eldest = buckets.entrySet().iterator();
                        if (!eldest.hasNext()) break;
                        eldest.next();
                        eldest.remove();
                    }
                    bucket = new Bucket(burst);
                    buckets.put(ip, bucket);
                }
                long now = System.nanoTime();
                bucket.tokens = Math.min(burst,
                        bucket.tokens + (now - bucket.lastNs) / 1e9 * perSecond);
                bucket.lastNs = now;
                if (bucket.tokens < 1) return false;
                bucket.tokens -= 1;
                return true;
            }
        }

        long activeBuckets() {
            synchronized (buckets) {
                return buckets.size();
            }
        }

        private static final class Bucket {
            private double tokens;
            private long lastNs = System.nanoTime();

            private Bucket(int tokens) {
                this.tokens = tokens;
            }
        }
    }
}
