package io.liftandshift.strikebench.api;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.liftandshift.strikebench.paper.CampaignService;

import java.util.function.Function;

/** HTTP controller for campaigns: an interpretation layer over the tracked book, never the
 *  accounting source. Membership is typed; auto-link results are proposals until confirmed. */
final class CampaignController {
    private final CampaignService campaigns;
    private final Function<Context, String> ownerId;

    CampaignController(CampaignService campaigns, Function<Context, String> ownerId) {
        this.campaigns = campaigns;
        this.ownerId = ownerId;
    }

    void register(JavalinConfig config) {
        CampaignRoutes.register(config, new CampaignRoutes.Handlers(
                ctx -> ctx.json(new ApiResponses.Campaigns<>(campaigns.list(ownerId.apply(ctx)))),
                this::create,
                ctx -> ctx.json(campaigns.view(ownerId.apply(ctx), ctx.pathParam("id"))),
                this::update,
                this::attach,
                ctx -> ctx.json(campaigns.detach(ownerId.apply(ctx), ctx.pathParam("id"),
                        ctx.pathParam("type"), ctx.pathParam("memberId"))),
                ctx -> ctx.json(new ApiResponses.Proposals<>(campaigns.propose(ownerId.apply(ctx),
                        ctx.pathParam("id"), ctx.queryParam("seedTransactionId"),
                        ctx.queryParam("seedStructureId"), ctx.queryParam("seedLotId"))))));
    }

    private void create(Context ctx) {
        var input = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, CampaignService.CreateInput.class));
        ctx.status(201).json(campaigns.create(ownerId.apply(ctx), input));
    }

    private void update(Context ctx) {
        var input = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, CampaignService.UpdateInput.class));
        ctx.json(campaigns.update(ownerId.apply(ctx), ctx.pathParam("id"), input));
    }

    private void attach(Context ctx) {
        var input = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, CampaignService.MemberInput.class));
        ctx.status(201).json(campaigns.attach(ownerId.apply(ctx), ctx.pathParam("id"), input));
    }
}
