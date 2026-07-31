package io.liftandshift.strikebench.api;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.liftandshift.strikebench.broker.BrokerService;

import java.util.Map;
import java.util.function.Consumer;

/** HTTP controller for the optional live-broker adapter. */
final class BrokerController {
    record VerifyRequest(String code) {}
    record OrderRequest(String accountIdKey, Map<String, Object> order,
                        String previewId, String clientOrderId, String confirmText) {}

    private final BrokerService broker;
    private final Consumer<Context> requireAdmin;

    BrokerController(BrokerService broker, Consumer<Context> requireAdmin) {
        this.broker = broker;
        this.requireAdmin = requireAdmin;
    }

    void register(JavalinConfig config) {
        BrokerRoutes.register(config, new BrokerRoutes.Handlers(
                this::status,
                this::startConnect,
                this::verify,
                this::accounts,
                this::balance,
                this::positions,
                this::orders,
                this::preview, this::place, this::cancel));
    }

    private void status(Context ctx) {
        adminWhenEnabled(ctx);
        ctx.json(broker.status());
    }

    private void startConnect(Context ctx) {
        adminWhenEnabled(ctx);
        ctx.json(new ApiResponses.AuthorizeUrl(broker.startConnect()));
    }

    private void verify(Context ctx) {
        adminWhenEnabled(ctx);
        VerifyRequest request = ApiRequest.requireBody(
                ApiRequest.bodyOrNull(ctx, VerifyRequest.class));
        broker.verifyConnect(request.code());
        ctx.json(broker.status());
    }

    private void accounts(Context ctx) {
        adminWhenEnabled(ctx);
        ctx.json(new ApiResponses.Accounts<>(broker.accounts()));
    }

    private void balance(Context ctx) {
        adminWhenEnabled(ctx);
        ctx.json(broker.balance(ctx.pathParam("k")));
    }

    private void positions(Context ctx) {
        adminWhenEnabled(ctx);
        ctx.json(new ApiResponses.Positions<>(broker.positions(ctx.pathParam("k"))));
    }

    private void orders(Context ctx) {
        adminWhenEnabled(ctx);
        String accountId = ctx.queryParam("accountIdKey");
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountIdKey is required");
        }
        ctx.json(new ApiResponses.Orders<>(broker.orders(accountId)));
    }

    private void preview(Context ctx) {
        adminWhenEnabled(ctx);
        OrderRequest request = ApiRequest.requireBody(
                ApiRequest.bodyOrNull(ctx, OrderRequest.class));
        BrokerService.PreviewOutcome outcome = broker.preview(
                request.accountIdKey(), request.order());
        ctx.json(new ApiResponses.BrokerPreview<>(
                outcome.localId(), outcome.preview(), BrokerService.CONFIRM_TEXT));
    }

    private void place(Context ctx) {
        adminWhenEnabled(ctx);
        OrderRequest request = ApiRequest.requireBody(
                ApiRequest.bodyOrNull(ctx, OrderRequest.class));
        ctx.json(broker.place(request.accountIdKey(), request.order(), request.previewId(),
                request.clientOrderId(), request.confirmText()));
    }

    private void cancel(Context ctx) {
        adminWhenEnabled(ctx);
        String accountId = ctx.queryParam("accountIdKey");
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountIdKey is required");
        }
        broker.cancel(accountId, ctx.pathParam("id"));
        ctx.json(new ApiResponses.CancelRequested(true,
                "Cancels are asynchronous and can lose the race to a fill — confirm via the orders list"));
    }

    private void adminWhenEnabled(Context ctx) {
        if (broker.liveEnabled()) requireAdmin.accept(ctx);
    }
}
