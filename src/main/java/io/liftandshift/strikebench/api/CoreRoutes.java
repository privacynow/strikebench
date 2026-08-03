package io.liftandshift.strikebench.api;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Handler;
import io.javalin.http.sse.SseClient;

import java.util.function.Consumer;

/** Normalized HTTP surface for app bootstrap, market reads, and workspace continuity. */
public final class CoreRoutes {
    public record Handlers(
            Handler metrics,
            Handler status,
            Handler config,
            Handler health,
            Handler universe,
            Handler selectUniverse,
            Handler quotes,
            Handler sparklines,
            Handler marketEngine,
            Consumer<SseClient> marketStream,
            Consumer<SseClient> eventStream,
            Handler workspace,
            Handler replaceWorkspace,
            Handler updateWorkspace,
            Handler account,
            Handler resetAccount
    ) {}

    private CoreRoutes() {}

    public static void register(JavalinConfig config, Handlers h) {
        config.routes.get("/api/metrics", h.metrics());
        config.routes.get("/api/status", h.status());
        config.routes.get("/api/config", h.config());
        config.routes.get("/api/health", h.health());
        config.routes.get("/api/universe", h.universe());
        config.routes.put("/api/universe", h.selectUniverse());
        config.routes.get("/api/quotes", h.quotes());
        config.routes.get("/api/sparklines", h.sparklines());
        config.routes.get("/api/market/engine", h.marketEngine());
        config.routes.sse("/api/market/stream", h.marketStream());
        config.routes.sse("/api/events", h.eventStream());
        // ONE workspace context (audit §6): GET reconciles it to the caller's market, PUT replaces
        // the whole declaration, PATCH changes only what it names and preserves everything else.
        config.routes.get("/api/workspace", h.workspace());
        config.routes.put("/api/workspace", h.replaceWorkspace());
        config.routes.patch("/api/workspace", h.updateWorkspace());
        config.routes.get("/api/account", h.account());
        config.routes.post("/api/account/reset", h.resetAccount());
    }
}
