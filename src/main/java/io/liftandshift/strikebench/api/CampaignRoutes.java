package io.liftandshift.strikebench.api;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Handler;

/** Canonical HTTP surface for the campaign interpretation layer (never the accounting source). */
public final class CampaignRoutes {
    public record Handlers(
            Handler list,
            Handler create,
            Handler view,
            Handler update,
            Handler attach,
            Handler detach,
            Handler propose
    ) {}

    private CampaignRoutes() {}

    public static void register(JavalinConfig config, Handlers h) {
        config.routes.get("/api/campaigns", h.list());
        config.routes.post("/api/campaigns", h.create());
        config.routes.get("/api/campaigns/{id}", h.view());
        config.routes.put("/api/campaigns/{id}", h.update());
        config.routes.post("/api/campaigns/{id}/members", h.attach());
        config.routes.delete("/api/campaigns/{id}/members/{type}/{memberId}", h.detach());
        config.routes.get("/api/campaigns/{id}/proposals", h.propose());
    }
}
