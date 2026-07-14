package io.liftandshift.strikebench.api;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Handler;

/** Canonical HTTP surface for market-world selection and simulated sessions. */
public final class WorldRoutes {
    public record Handlers(
            Handler current,
            Handler transition,
            Handler listSessions,
            Handler anchors,
            Handler createSession,
            Handler startSession,
            Handler pauseSession,
            Handler stepSession,
            Handler setSpeed,
            Handler injectEvent,
            Handler finishSession,
            Handler report
    ) {}

    private WorldRoutes() {}

    public static void register(JavalinConfig config, Handlers h) {
        config.routes.get("/api/world", h.current());
        config.routes.put("/api/world", h.transition());
        config.routes.get("/api/sim/market", h.listSessions());
        config.routes.get("/api/sim/market/{id}/anchors", h.anchors());
        config.routes.post("/api/sim/market", h.createSession());
        config.routes.post("/api/sim/market/{id}/start", h.startSession());
        config.routes.post("/api/sim/market/{id}/pause", h.pauseSession());
        config.routes.post("/api/sim/market/{id}/step", h.stepSession());
        config.routes.post("/api/sim/market/{id}/speed", h.setSpeed());
        config.routes.post("/api/sim/market/{id}/event", h.injectEvent());
        config.routes.delete("/api/sim/market/{id}", h.finishSession());
        config.routes.get("/api/sim/market/{id}/report", h.report());
    }
}
