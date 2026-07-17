package io.liftandshift.strikebench.api;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.liftandshift.strikebench.paper.AlertCenterService;

import java.util.function.Function;

/**
 * The alert center's HTTP surface: one GET computing the user's current attention list
 * (alerts + counts by severity). Material changes ride the existing /api/events stream
 * as {@code alerts.updated} hints — there is no separate SSE channel.
 */
public final class AlertController {

    private final AlertCenterService alerts;
    private final Function<Context, String> ownerId;

    public AlertController(AlertCenterService alerts, Function<Context, String> ownerId) {
        this.alerts = alerts;
        this.ownerId = ownerId;
    }

    public void register(JavalinConfig config) {
        config.routes.get("/api/alerts", ctx -> ctx.json(alerts.compute(ownerId.apply(ctx))));
    }
}
