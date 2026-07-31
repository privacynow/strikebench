package io.liftandshift.strikebench.api;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.liftandshift.strikebench.paper.BrokerImportService;

import java.util.function.Function;

/** One canonical HTTP journey for broker statement preview, confirmation and pending resolution. */
final class BrokerImportController {
    private final BrokerImportService imports;
    private final Function<Context, String> ownerId;

    BrokerImportController(BrokerImportService imports, Function<Context, String> ownerId) {
        this.imports = imports;
        this.ownerId = ownerId;
    }

    void register(JavalinConfig config) {
        config.routes.post("/api/portfolio/broker-imports/preview", this::preview);
        config.routes.post("/api/portfolio/broker-imports/confirm", this::confirm);
        config.routes.get("/api/portfolio/broker-imports", this::list);
        config.routes.get("/api/portfolio/broker-imports/{id}", this::get);
        config.routes.post("/api/portfolio/broker-imports/{id}/commands", this::command);
    }

    private void preview(Context ctx) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, BrokerImportService.PreviewRequest.class));
        ctx.json(imports.preview(ownerId.apply(ctx), body));
    }

    private void confirm(Context ctx) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, BrokerImportService.ConfirmRequest.class));
        ctx.status(201).json(imports.confirm(ownerId.apply(ctx), body));
    }

    private void list(Context ctx) {
        ctx.json(imports.list(ownerId.apply(ctx), ctx.queryParam("status"),
                intQuery(ctx, "limit", 100), intQuery(ctx, "offset", 0),
                ctx.queryParam("portfolioAccountId")));
    }

    private void get(Context ctx) {
        ctx.json(imports.get(ownerId.apply(ctx), ctx.pathParam("id")));
    }

    private void command(Context ctx) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, BrokerImportService.CommandRequest.class));
        ctx.json(imports.command(ownerId.apply(ctx), ctx.pathParam("id"), body));
    }

    private static int intQuery(Context ctx, String name, int fallback) {
        String raw = ctx.queryParam(name);
        if (raw == null || raw.isBlank()) return fallback;
        try { return Integer.parseInt(raw); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(name + " must be an integer"); }
    }
}
