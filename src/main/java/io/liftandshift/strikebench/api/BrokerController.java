package io.liftandshift.strikebench.api;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.liftandshift.strikebench.broker.BrokerService;

import java.util.Map;

/** HTTP controller for the optional live-broker adapter. */
final class BrokerController {
    record VerifyRequest(String code) {}
    record OrderRequest(String accountIdKey, Map<String, Object> order,
                        String previewId, String clientOrderId, String confirmText) {}

    private final BrokerService broker;

    BrokerController(BrokerService broker) {
        this.broker = broker;
    }

    void register(JavalinConfig config) {
        BrokerRoutes.register(config, new BrokerRoutes.Handlers(
                ctx -> ctx.json(broker.status()),
                ctx -> ctx.json(new ApiResponses.AuthorizeUrl(broker.startConnect())),
                this::verify,
                ctx -> ctx.json(new ApiResponses.Accounts<>(broker.accounts())),
                ctx -> ctx.json(broker.balance(ctx.pathParam("k"))),
                ctx -> ctx.json(new ApiResponses.Positions<>(broker.positions(ctx.pathParam("k")))),
                this::orders,
                this::preview, this::place, this::cancel));
    }

    private void verify(Context ctx) {
        VerifyRequest request = ApiRequest.requireBody(
                ApiRequest.bodyOrNull(ctx, VerifyRequest.class));
        broker.verifyConnect(request.code());
        ctx.json(broker.status());
    }

    private void orders(Context ctx) {
        String accountId = ctx.queryParam("accountIdKey");
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountIdKey is required");
        }
        ctx.json(new ApiResponses.Orders<>(broker.orders(accountId)));
    }

    private void preview(Context ctx) {
        OrderRequest request = ApiRequest.requireBody(
                ApiRequest.bodyOrNull(ctx, OrderRequest.class));
        BrokerService.PreviewOutcome outcome = broker.preview(
                request.accountIdKey(), request.order());
        ctx.json(new ApiResponses.BrokerPreview<>(
                outcome.localId(), outcome.preview(), BrokerService.CONFIRM_TEXT));
    }

    private void place(Context ctx) {
        OrderRequest request = ApiRequest.requireBody(
                ApiRequest.bodyOrNull(ctx, OrderRequest.class));
        ctx.json(broker.place(request.accountIdKey(), request.order(), request.previewId(),
                request.clientOrderId(), request.confirmText()));
    }

    private void cancel(Context ctx) {
        String accountId = ctx.queryParam("accountIdKey");
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountIdKey is required");
        }
        broker.cancel(accountId, ctx.pathParam("id"));
        ctx.json(new ApiResponses.CancelRequested(true,
                "Cancels are asynchronous and can lose the race to a fill — confirm via the orders list"));
    }
}
