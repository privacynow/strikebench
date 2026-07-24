package io.liftandshift.strikebench.api;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Handler;

/** Canonical before/after position-mutation surface. */
final class PositionTransformationRoutes {
    record Handlers(Handler preview, Handler apply) {}

    private PositionTransformationRoutes() {}

    static void register(JavalinConfig config, Handlers handlers) {
        config.routes.post("/api/position-transformations/preview", handlers.preview());
        config.routes.post("/api/position-transformations/apply", handlers.apply());
    }
}
