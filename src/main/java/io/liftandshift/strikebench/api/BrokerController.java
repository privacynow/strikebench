package io.liftandshift.strikebench.api;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.liftandshift.strikebench.broker.BrokerService;
import io.liftandshift.strikebench.market.ports.BrokerageProvider;
import io.liftandshift.strikebench.paper.PackagePriceReceipt;
import io.liftandshift.strikebench.util.Json;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * HTTP boundary for the optional live-broker adapter.
 *
 * <p>No route accepts broker-native order JSON. Preview accepts the canonical TradeOpenRequest,
 * runs the same exact Practice receipt path, and freezes the resulting typed command. Placement
 * accepts only that local preview identity plus an idempotency key and exact confirmation.</p>
 */
final class BrokerController {
    record VerifyRequest(String code) {}
    record PreviewRequest(String brokerAccountIdKey, TradeOpenRequest trade,
                          Boolean proceedWithoutEndorsement) {}
    record PlaceRequest(String previewId, String clientOrderId, String confirmText) {}
    record ProviderPreviewReceipt(BrokerageProvider.OrderPreview providerPreview,
                                  String commandFingerprint, String status) {}

    private final BrokerService broker;
    private final TradeController trades;
    private final Function<Context, String> ownerId;
    private final Consumer<Context> requireAdmin;

    BrokerController(BrokerService broker, TradeController trades,
                     Function<Context, String> ownerId,
                     Consumer<Context> requireAdmin) {
        this.broker = broker;
        this.trades = trades;
        this.ownerId = ownerId;
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
                this::preview,
                this::place,
                this::cancel,
                this::reconcile));
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
        ctx.json(new ApiResponses.Orders<>(
                broker.orders(ownerId.apply(ctx), ctx.queryParam("accountIdKey"))));
    }

    private void preview(Context ctx) {
        adminWhenEnabled(ctx);
        PreviewRequest request = ApiRequest.requireBody(
                ApiRequest.bodyOrNull(ctx, PreviewRequest.class));
        boolean proceed = Boolean.TRUE.equals(request.proceedWithoutEndorsement());
        TradeController.ApprovedLiveOrder approved =
                trades.approvedLiveOrder(ctx, request.trade(), proceed);
        PackagePriceReceipt price = approved.receipt().preview().price();
        BrokerageProvider.OrderCommand command =
                BrokerService.command(approved.request(), price.fingerprint());
        var endorsement = approved.receipt().endorsement();
        var execution = approved.receipt().execution();
        var approval = new BrokerService.CanonicalApproval(
                Json.canonical(approved.receipt()),
                price.fingerprint(),
                price.executableNetCents(),
                approved.receipt().evaluation().available(),
                approved.receipt().guardrails().level(),
                endorsement == null ? "COMPARISON" : endorsement.status(),
                endorsement != null && endorsement.endorsed(),
                execution.reviewAllowed(),
                execution.confirmAllowed(),
                proceed,
                execution.reasons());
        BrokerService.PreviewOutcome outcome = broker.preview(
                ownerId.apply(ctx), approved.account().id(),
                request.brokerAccountIdKey(), command, approval);
        ctx.json(new ApiResponses.BrokerPreview<>(
                outcome.localId(), new ProviderPreviewReceipt(
                        outcome.providerPreview(), outcome.commandFingerprint(), outcome.status()),
                BrokerService.CONFIRM_TEXT));
    }

    private void place(Context ctx) {
        adminWhenEnabled(ctx);
        PlaceRequest request = ApiRequest.requireBody(
                ApiRequest.bodyOrNull(ctx, PlaceRequest.class));
        ctx.json(broker.place(ownerId.apply(ctx), request.previewId(),
                request.clientOrderId(), request.confirmText()));
    }

    private void cancel(Context ctx) {
        adminWhenEnabled(ctx);
        ctx.json(broker.cancel(ownerId.apply(ctx), ctx.pathParam("id")));
    }

    private void reconcile(Context ctx) {
        adminWhenEnabled(ctx);
        ctx.json(broker.reconcile(ownerId.apply(ctx), ctx.pathParam("id")));
    }

    private void adminWhenEnabled(Context ctx) {
        if (broker.liveEnabled()) requireAdmin.accept(ctx);
    }
}
