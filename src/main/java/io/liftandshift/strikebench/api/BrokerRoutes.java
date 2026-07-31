package io.liftandshift.strikebench.api;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Handler;

/** Canonical HTTP surface for the optional broker adapter. */
public final class BrokerRoutes {
    public record Handlers(
            Handler status,
            Handler startConnect,
            Handler verifyConnect,
            Handler accounts,
            Handler balance,
            Handler positions,
            Handler orders,
            Handler previewOrder,
            Handler placeOrder,
            Handler cancelOrder
    ) {}

    private BrokerRoutes() {}

    public static void register(JavalinConfig config, Handlers h) {
        config.routes.get("/api/broker/status", h.status());
        config.routes.post("/api/broker/connect/start", h.startConnect());
        config.routes.post("/api/broker/connect/verify", h.verifyConnect());
        config.routes.get("/api/broker/accounts", h.accounts());
        config.routes.get("/api/broker/accounts/{k}/balance", h.balance());
        config.routes.get("/api/broker/accounts/{k}/positions", h.positions());
        config.routes.get("/api/broker/orders", h.orders());
        config.routes.post("/api/broker/orders/preview", h.previewOrder());
        config.routes.post("/api/broker/orders/place", h.placeOrder());
        config.routes.put("/api/broker/orders/{id}/cancel", h.cancelOrder());
    }
}
