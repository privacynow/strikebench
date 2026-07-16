package io.liftandshift.strikebench.api;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Handler;

/** Canonical HTTP surface for paper trades, positions, and audit operations. */
public final class TradeRoutes {
    public record Handlers(
            Handler preview,
            Handler create,
            Handler list,
            Handler detail,
            Handler refresh,
            Handler snapshot,
            Handler audit,
            Handler listPositions,
            Handler previewPosition,
            Handler buyPosition,
            Handler sellPosition
    ) {}

    private TradeRoutes() {}

    public static void register(JavalinConfig config, Handlers h) {
        config.routes.post("/api/trades/preview", h.preview());
        config.routes.post("/api/trades", h.create());
        config.routes.get("/api/trades", h.list());
        config.routes.get("/api/trades/{id}", h.detail());
        config.routes.post("/api/trades/{id}/refresh", h.refresh());
        config.routes.post("/api/admin/snapshot", h.snapshot());
        config.routes.get("/api/audit", h.audit());
        config.routes.get("/api/positions", h.listPositions());
        config.routes.post("/api/positions/preview", h.previewPosition());
        config.routes.post("/api/positions/buy", h.buyPosition());
        config.routes.post("/api/positions/sell", h.sellPosition());
    }
}
