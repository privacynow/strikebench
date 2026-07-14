package io.liftandshift.strikebench.api;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Handler;

/** Canonical HTTP surface for strategy discovery, ranking, and portfolio construction. */
public final class DiscoveryRoutes {
    public record Handlers(
            Handler teachingExample,
            Handler scout,
            Handler intentLadder,
            Handler opportunities,
            Handler optimize
    ) {}

    private DiscoveryRoutes() {}

    public static void register(JavalinConfig config, Handlers handlers) {
        config.routes.get("/api/welcome/teaching-example", handlers.teachingExample());
        config.routes.post("/api/research/scout", handlers.scout());
        config.routes.post("/api/research/{symbol}/intent-ladder", handlers.intentLadder());
        config.routes.post("/api/opportunities", handlers.opportunities());
        config.routes.post("/api/optimize", handlers.optimize());
    }
}
