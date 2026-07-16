package io.liftandshift.strikebench.api;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.liftandshift.strikebench.eval.EvidenceLevel;
import io.liftandshift.strikebench.paper.TradeRecord;
import io.liftandshift.strikebench.paper.TradeService;
import io.liftandshift.strikebench.plan.Plan;
import io.liftandshift.strikebench.plan.PlanManagementService;
import io.liftandshift.strikebench.plan.PlanService;
import io.liftandshift.strikebench.position.PositionArtifactStore;
import io.liftandshift.strikebench.position.PositionDomain;
import io.liftandshift.strikebench.position.PositionTransformation;
import io.liftandshift.strikebench.util.Ids;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Signed preview/apply boundary around the existing pricing and Practice ledger services.
 * The server always loads the before-position; a client cannot substitute it.
 */
final class PositionTransformationController {
    private static final Duration PREVIEW_TTL = Duration.ofMinutes(2);

    private final Clock clock;
    private final TradeService trades;
    private final TradeController tradeController;
    private final PlanController planController;
    private final PlanService plans;
    private final PlanManagementService management;
    private final PositionArtifactStore artifacts;
    private final byte[] previewSecret = new byte[32];

    PositionTransformationController(Clock clock, TradeService trades, TradeController tradeController,
                                     PlanController planController, PlanService plans,
                                     PlanManagementService management, PositionArtifactStore artifacts) {
        this.clock = clock;
        this.trades = trades;
        this.tradeController = tradeController;
        this.planController = planController;
        this.plans = plans;
        this.management = management;
        this.artifacts = artifacts;
        new java.security.SecureRandom().nextBytes(previewSecret);
    }

    void register(JavalinConfig config) {
        PositionTransformationRoutes.register(config,
                new PositionTransformationRoutes.Handlers(this::preview, this::apply));
    }

    private void preview(Context ctx) {
        Request request = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, Request.class));
        Prepared prepared = prepare(ctx, request);
        long issuedAt = clock.instant().getEpochSecond();
        String token = token(ctx, request, prepared.preview(), issuedAt);
        ctx.json(response(prepared, token, Instant.ofEpochSecond(issuedAt).plus(PREVIEW_TTL).toString()));
    }

    private void apply(Context ctx) {
        Request request = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, Request.class));
        if (request.previewToken() == null || request.previewToken().isBlank()) {
            throw new IllegalArgumentException("previewToken is required; preview this exact transformation first");
        }
        Prepared prepared = prepare(ctx, request);
        verifyToken(ctx, request, prepared.preview());
        if (request.action() != PositionTransformation.Action.CLOSE) {
            throw new IllegalStateException("This transformation can be analyzed, but its atomic Practice apply path is not active yet.");
        }

        String ownerId = planController.ownerId(ctx);
        String receiptId = Ids.newId("prec");
        TradeService.LifecycleHook planHook = prepared.plan() == null ? null
                : management.lifecycleHook(ownerId, prepared.plan().id(), request.expectedPlanVersion(),
                        "CLOSE", false, receiptId);
        TradeService.LifecycleHook atomicArtifacts = (connection, closed, realized) -> {
            artifacts.recordPracticeTransformation(connection,
                    new PositionArtifactStore.PracticeTransformationAction(ownerId, receiptId,
                            prepared.plan() == null ? null : prepared.plan().id(),
                            prepared.plan() == null ? null : prepared.plan().context().rev(),
                            closed.id(), PositionDomain.PositionState.CLOSED,
                            prepared.before().position().asOf(),
                            EvidenceLevel.fromEvidence(prepared.before().preview().evidence()),
                            PositionTransformation.MODEL_VERSION, prepared.before().position(), null,
                            prepared.preview(), realized));
            if (planHook != null) planHook.afterMutation(connection, closed, realized);
        };
        TradeService.CloseResult result = trades.unwind(request.sourceId(), true, atomicArtifacts,
                prepared.unwind().closingCashCents(), prepared.unwind().closingFeesCents());
        long decisionPnl = TradeController.decisionPnl(result.trade(), result.realizedPnlCents());
        tradeController.resolveRecommendation(result.trade().id(), "CLOSED", decisionPnl);
        Plan.View currentPlan = prepared.plan() == null ? null : plans.get(ownerId, prepared.plan().id());
        Object currentManagement = currentPlan == null ? null : management.latest(ownerId, currentPlan.id());
        ctx.json(new ApiResponses.PositionTransformationApplied<>(receiptId, prepared.preview(),
                TradeView.of(result.trade()), currentPlan, currentManagement, result.realizedPnlCents()));
    }

    private Prepared prepare(Context ctx, Request request) {
        if (request.source() != PositionDomain.PackageSource.PRACTICE_TRADE) {
            throw new IllegalArgumentException("source must be PRACTICE_TRADE for this Practice transformation path");
        }
        if (request.sourceId() == null || request.sourceId().isBlank() || request.action() == null) {
            throw new IllegalArgumentException("sourceId and action are required");
        }
        tradeController.ensureOwnedTrade(ctx, request.sourceId());
        TradeRecord trade = trades.get(request.sourceId());
        Plan.View plan = requirePlanContext(ctx, request, trade);
        TradeService.PositionAssessment before = trades.analyzeActivePosition(trade.id());
        TradeService.UnwindAssessment unwind = switch (request.action()) {
            case CLOSE, ROLL -> trades.previewUnwind(trade.id());
            default -> null;
        };
        TradeService.PositionAssessment after = null;
        if (request.after() != null) {
            TradeService.OpenRequest afterRequest = TradeController.toAnalysisOpenRequest(request.after(), trade.accountId());
            after = trades.analyzePositionPackage(trade.id(), PositionDomain.PackageSource.PRACTICE_TRADE,
                    PositionDomain.ExecutionLane.PRACTICE, afterRequest);
        }
        if (request.action() == PositionTransformation.Action.ASSIGNMENT
                || request.action() == PositionTransformation.Action.EXERCISE
                || request.action() == PositionTransformation.Action.EXPIRATION) {
            throw new IllegalStateException("Lifecycle conversion preview requires the lane-clock settlement projection.");
        }
        PositionTransformation.Preview preview = PositionTransformation.preview(new PositionTransformation.Request(
                request.action(), before.position(), after == null ? null : after.position(),
                risk(before.preview()), after == null ? null : risk(after.preview()),
                unwind == null ? null : unwind.realizedPnlCents()));
        return new Prepared(trade, plan, before, after, unwind, preview);
    }

    private Plan.View requirePlanContext(Context ctx, Request request, TradeRecord trade) {
        if (request.planId() == null || request.planId().isBlank()) {
            if (request.expectedPlanVersion() != null) {
                throw new IllegalArgumentException("expectedPlanVersion requires planId");
            }
            return null;
        }
        Plan.View plan = plans.get(planController.ownerId(ctx), request.planId());
        planController.requireActivePlanMarket(ctx, plan);
        PlanController.requirePlanVersion(plan, request.expectedPlanVersion());
        if (!trade.accountId().equals(plan.accountId())
                || !trade.id().equals(management.activeTradeId(planController.ownerId(ctx), plan.id()))) {
            throw new IllegalStateException("The Practice position is not the active position owned by this Plan.");
        }
        return plan;
    }

    private static PositionTransformation.RiskSnapshot risk(io.liftandshift.strikebench.paper.TradePreview preview) {
        EvidenceLevel evidence = EvidenceLevel.fromEvidence(preview.evidence());
        return new PositionTransformation.RiskSnapshot(preview.maxLossCents(), preview.reserveCents(),
                preview.maxProfitCents(), preview.ok(), preview.blockReasons(), evidence.name());
    }

    private ApiResponses.PositionTransformationPreview<io.liftandshift.strikebench.paper.TradePreview> response(
            Prepared prepared, String token, String expiresAt) {
        return new ApiResponses.PositionTransformationPreview<>(prepared.preview(),
                prepared.before().preview(), prepared.after() == null ? null : prepared.after().preview(),
                prepared.unwind() == null ? null : prepared.unwind().closingCashCents(),
                prepared.unwind() == null ? null : prepared.unwind().closingFeesCents(), token, expiresAt);
    }

    private String token(Context ctx, Request request, PositionTransformation.Preview preview, long issuedAt) {
        return issuedAt + "." + hmac(planController.ownerId(ctx), request, preview.fingerprint(), issuedAt);
    }

    private void verifyToken(Context ctx, Request request, PositionTransformation.Preview preview) {
        try {
            int dot = request.previewToken().indexOf('.');
            if (dot <= 0) throw new IllegalArgumentException();
            long issuedAt = Long.parseLong(request.previewToken().substring(0, dot));
            long now = clock.instant().getEpochSecond();
            if (issuedAt > now + 5 || now - issuedAt > PREVIEW_TTL.toSeconds()) {
                throw new IllegalStateException("The transformation preview expired. Review the current position again.");
            }
            byte[] expected = HexFormat.of().parseHex(
                    hmac(planController.ownerId(ctx), request, preview.fingerprint(), issuedAt));
            byte[] actual = HexFormat.of().parseHex(request.previewToken().substring(dot + 1));
            if (!MessageDigest.isEqual(expected, actual)) throw new IllegalArgumentException();
        } catch (IllegalStateException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException("The transformation preview is stale or does not match this exact action.");
        }
    }

    private String hmac(String ownerId, Request request, String fingerprint, long issuedAt) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(previewSecret, "HmacSHA256"));
            String payload = ownerId + "|" + request.source() + "|" + request.sourceId() + "|"
                    + request.planId() + "|" + request.expectedPlanVersion() + "|" + request.action() + "|"
                    + fingerprint + "|" + issuedAt;
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("cannot sign transformation preview", e);
        }
    }

    record Request(PositionDomain.PackageSource source, String sourceId, String planId,
                   Long expectedPlanVersion, PositionTransformation.Action action,
                   TradeOpenRequest after, String previewToken) {}

    private record Prepared(TradeRecord trade, Plan.View plan,
                            TradeService.PositionAssessment before,
                            TradeService.PositionAssessment after,
                            TradeService.UnwindAssessment unwind,
                            PositionTransformation.Preview preview) {}
}
