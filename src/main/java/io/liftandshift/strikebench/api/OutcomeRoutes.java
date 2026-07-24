package io.liftandshift.strikebench.api;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Handler;

/** Canonical HTTP surface for forward paths, position outcomes, and comparisons. */
public final class OutcomeRoutes {
    public record Handlers(Handler evaluate) {}

    private OutcomeRoutes() {}

    public static void register(JavalinConfig config, Handlers handlers) {
        config.routes.post("/api/evaluate", handlers.evaluate());
    }
}
