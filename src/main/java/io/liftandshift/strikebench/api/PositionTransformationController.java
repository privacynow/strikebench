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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

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
        if (request.action() != PositionTransformation.Action.CLOSE
                && request.action() != PositionTransformation.Action.PARTIAL_CLOSE
                && request.action() != PositionTransformation.Action.ROLL
                && !adjustmentAction(request.action())
                && !lifecycleAction(request.action())) {
            throw new IllegalStateException("This transformation can be analyzed, but its atomic Practice apply path is not active yet.");
        }

        String ownerId = planController.ownerId(ctx);
        String receiptId = Ids.newId("prec");
        TradeRecord responseTrade;
        TradeRecord resolvedTrade = null;
        long actionRealized;
        long realizedToDate;
        if (request.action() == PositionTransformation.Action.CLOSE) {
            TradeService.LifecycleHook planHook = prepared.plan() == null ? null
                    : management.lifecycleHook(ownerId, prepared.plan().id(), request.expectedPlanVersion(),
                            "CLOSE", false, receiptId);
            TradeService.LifecycleHook atomicArtifacts = (connection, closed, actionDelta, lifetimeTotal) -> {
                recordPracticeArtifact(connection, ownerId, receiptId, prepared, closed.id(),
                        PositionDomain.PositionState.CLOSED, prepared.unwind().actionRealizedPnlCents());
                if (planHook != null) {
                    planHook.afterMutation(connection, closed, actionDelta, lifetimeTotal);
                }
            };
            TradeService.CloseResult result = trades.unwind(request.sourceId(), true, atomicArtifacts,
                    prepared.unwind().closingCashCents(), prepared.unwind().closingFeesCents());
            responseTrade = result.trade();
            resolvedTrade = result.trade();
            actionRealized = result.actionRealizedPnlCents();
            realizedToDate = result.realizedPnlCents();
        } else if (request.action() == PositionTransformation.Action.PARTIAL_CLOSE) {
            TradeService.LifecycleHook planHook = prepared.plan() == null ? null
                    : management.partialCloseLifecycleHook(ownerId, prepared.plan().id(),
                            request.expectedPlanVersion(), receiptId);
            TradeService.LifecycleHook atomicArtifacts = (connection, survivor, actionDelta, lifetimeTotal) -> {
                recordPracticeArtifact(connection, ownerId, receiptId, prepared, survivor.id(),
                        PositionDomain.PositionState.PARTIALLY_CLOSED, actionDelta);
                if (planHook != null) {
                    planHook.afterMutation(connection, survivor, actionDelta, lifetimeTotal);
                }
            };
            TradeService.PartialCloseResult result = trades.partialClose(request.sourceId(),
                    request.closeQuantity(), true, atomicArtifacts, prepared.partial().closingCashCents(),
                    prepared.partial().closingFeesCents(), expectedPosition(prepared));
            responseTrade = result.trade();
            actionRealized = result.actionRealizedPnlCents();
            realizedToDate = result.realizedPnlCents();
        } else if (lifecycleAction(request.action())) {
            if (prepared.lifecycle() == null || request.legIndex() == null) {
                throw new IllegalArgumentException("a lifecycle conversion requires its reviewed option leg");
            }
            PositionDomain.PositionState state = switch (request.action()) {
                case ASSIGNMENT -> PositionDomain.PositionState.ASSIGNED;
                case EXERCISE -> PositionDomain.PositionState.EXERCISED;
                case EXPIRATION -> PositionDomain.PositionState.EXPIRED;
                default -> throw new IllegalStateException("unsupported lifecycle action");
            };
            boolean positionSurvives = prepared.after() != null;
            TradeService.LifecycleHook planHook = prepared.plan() == null ? null
                    : management.optionLifecycleHook(ownerId, prepared.plan().id(),
                            request.expectedPlanVersion(), request.action().name(),
                            positionSurvives, receiptId);
            TradeService.LifecycleHook atomicArtifacts = (connection, changed, actionDelta, lifetimeTotal) -> {
                recordPracticeArtifact(connection, ownerId, receiptId, prepared, changed.id(), state, actionDelta);
                if (planHook != null) planHook.afterMutation(connection, changed, actionDelta, lifetimeTotal);
            };
            TradeService.LifecycleResult result = trades.applyLifecycleConversion(request.sourceId(),
                    request.action(), request.legIndex(), true, atomicArtifacts,
                    expectedPosition(prepared), expectedLifecycle(prepared.lifecycle()));
            responseTrade = result.trade();
            if (!positionSurvives) resolvedTrade = result.trade();
            actionRealized = result.actionRealizedPnlCents();
            realizedToDate = result.realizedPnlCents();
        } else if (adjustmentAction(request.action())) {
            if (prepared.adjustment() == null || prepared.after() == null || request.after() == null) {
                throw new IllegalArgumentException("a leg or stock adjustment requires the reviewed surviving position");
            }
            tradeController.requireTransformationApproval(ctx, request.after(),
                    prepared.adjustment().exactAfterRequest(), prepared.adjustment().survivor().preview());
            TradeService.LifecycleHook planHook = prepared.plan() == null ? null
                    : management.adjustmentLifecycleHook(ownerId, prepared.plan().id(),
                            request.expectedPlanVersion(), request.action().name(), receiptId);
            PositionDomain.PositionState state = request.action() == PositionTransformation.Action.LEG_CLOSE
                    || request.action() == PositionTransformation.Action.REMOVE_LEG
                    || request.action() == PositionTransformation.Action.REMOVE_STOCK
                    ? PositionDomain.PositionState.PARTIALLY_CLOSED : PositionDomain.PositionState.OPEN;
            TradeService.LifecycleHook atomicArtifacts = (connection, survivor, actionDelta, lifetimeTotal) -> {
                recordPracticeArtifact(connection, ownerId, receiptId, prepared, survivor.id(), state, actionDelta);
                if (planHook != null) planHook.afterMutation(connection, survivor, actionDelta, lifetimeTotal);
            };
            TradeService.AdjustmentAssessment adjustment = prepared.adjustment();
            TradeService.AdjustmentResult result = trades.adjustPosition(request.sourceId(), request.action(),
                    adjustment.exactAfterRequest(), true, atomicArtifacts, expectedPosition(prepared),
                    new TradeService.ExpectedAdjustment(adjustment.closingCashCents(),
                            adjustment.openingCashCents(), adjustment.closingFeesCents(),
                            adjustment.openingFeesCents(), adjustment.survivor().preview().entryNetPremiumCents(),
                            adjustment.survivor().preview().feesOpenCents(), adjustment.reserveAfterCents(),
                            adjustment.survivor().preview().maxLossCents(),
                            adjustment.survivor().preview().maxProfitCents(), adjustment.sharesLockedAfter(),
                            TradeService.exactPositionFingerprint(adjustment.exactAfterRequest())));
            responseTrade = result.trade();
            actionRealized = result.actionRealizedPnlCents();
            realizedToDate = result.realizedPnlCents();
        } else {
            if (prepared.after() == null || prepared.afterRequest() == null) {
                throw new IllegalArgumentException("a roll requires the reviewed replacement position");
            }
            TradeService.OpenRequest approvedAfter = tradeController.approvedTransformationRequest(
                    ctx, request.after(), prepared.trade().accountId(), prepared.projection());
            TradeService.RollHook planHook = prepared.plan() == null ? null
                    : management.rollLifecycleHook(ownerId, prepared.plan().id(), request.expectedPlanVersion(), receiptId);
            TradeService.RollHook atomicArtifacts = (connection, closed, replacement,
                                                     actionDelta, lifetimeTotal) -> {
                recordPracticeArtifact(connection, ownerId, receiptId, prepared, replacement.id(),
                        PositionDomain.PositionState.OPEN, prepared.unwind().actionRealizedPnlCents());
                if (planHook != null) {
                    planHook.afterRoll(connection, closed, replacement, actionDelta, lifetimeTotal);
                }
            };
            var afterPreview = prepared.after().preview();
            TradeService.RollResult result = trades.roll(request.sourceId(), approvedAfter, true,
                    atomicArtifacts, prepared.unwind().closingCashCents(), prepared.unwind().closingFeesCents(),
                    new TradeService.ExpectedOpen(afterPreview.entryNetPremiumCents(), afterPreview.feesOpenCents(),
                            afterPreview.reserveCents(), afterPreview.maxLossCents(), afterPreview.maxProfitCents()));
            responseTrade = result.replacementTrade();
            resolvedTrade = result.closedTrade();
            actionRealized = result.actionRealizedClosingCents();
            realizedToDate = result.realizedClosingCents();
        }
        if (resolvedTrade != null) {
            tradeController.resolveRecommendation(resolvedTrade.id(), "CLOSED",
                    TradeController.decisionPnl(resolvedTrade, realizedToDate));
        }
        Plan.View currentPlan = prepared.plan() == null ? null : plans.get(ownerId, prepared.plan().id());
        Object currentManagement = currentPlan == null ? null : management.latest(ownerId, currentPlan.id());
        ctx.json(new ApiResponses.PositionTransformationApplied<>(receiptId, prepared.preview(),
                TradeView.of(responseTrade), currentPlan, currentManagement, actionRealized, realizedToDate));
    }

    private void recordPracticeArtifact(java.sql.Connection connection, String ownerId, String receiptId,
                                        Prepared prepared, String practiceTradeId,
                                        PositionDomain.PositionState state, Long realized) throws java.sql.SQLException {
        EvidenceLevel evidence = EvidenceLevel.fromEvidence(prepared.before().preview().evidence());
        if (prepared.after() != null) {
            evidence = evidence.worseOf(EvidenceLevel.fromEvidence(prepared.after().preview().evidence()));
        }
        artifacts.recordPracticeTransformation(connection,
                new PositionArtifactStore.PracticeTransformationAction(ownerId, receiptId,
                        prepared.plan() == null ? null : prepared.plan().id(),
                        prepared.plan() == null ? null : prepared.plan().context().rev(),
                        practiceTradeId, state, prepared.before().position().asOf(), evidence,
                        PositionTransformation.MODEL_VERSION, prepared.before().position(),
                        prepared.after() == null ? null : prepared.after().position(),
                        prepared.preview(), realized));
    }

    private static TradeService.ExpectedPositionState expectedPosition(Prepared prepared) {
        TradeRecord trade = prepared.trade();
        return new TradeService.ExpectedPositionState(trade.qty(), trade.entryNetPremiumCents(),
                trade.feesOpenCents(), trade.maxLossCents(), trade.maxProfitCents(), trade.sharesLocked(),
                trade.realizedPnlCents() == null ? 0 : trade.realizedPnlCents(),
                prepared.before().risk().reserveCents());
    }

    private static TradeService.ExpectedLifecycle expectedLifecycle(TradeService.LifecycleAssessment lifecycle) {
        return new TradeService.ExpectedLifecycle(lifecycle.settlementUnderlyingCents(),
                lifecycle.optionSettlementCashCents(), lifecycle.stockCashCents(), lifecycle.sharesDelta(),
                lifecycle.allocatedEntryBasisCents(), lifecycle.allocatedOpenFeesCents(),
                lifecycle.reserveAfterCents(), lifecycle.heldShareContextAfter(),
                lifecycle.sharesLockedAfter(), lifecycle.exactStateFingerprint());
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
        if (trade.external()) {
            throw new IllegalArgumentException(
                    "This endpoint transforms Practice positions only; use the tracked-account position workflow for broker records");
        }
        Plan.View plan = requirePlanContext(ctx, request, trade);
        boolean lifecycleAction = lifecycleAction(request.action());
        if (request.action() == PositionTransformation.Action.PARTIAL_CLOSE) {
            if (request.closeQuantity() == null) {
                throw new IllegalArgumentException("closeQuantity is required for a partial close");
            }
            if (request.after() != null) {
                throw new IllegalArgumentException("the server derives the surviving position from closeQuantity");
            }
        } else if (request.closeQuantity() != null) {
            throw new IllegalArgumentException("closeQuantity applies only to a partial close");
        }
        if (lifecycleAction) {
            if (request.legIndex() == null) {
                throw new IllegalArgumentException("legIndex is required for assignment, exercise, or expiration");
            }
            if (request.after() != null) {
                throw new IllegalArgumentException("the server derives the lifecycle survivor from the selected option leg");
            }
        } else if (request.legIndex() != null) {
            throw new IllegalArgumentException("legIndex applies only to assignment, exercise, or expiration");
        }
        TradeService.PartialCloseAssessment partial = request.action() == PositionTransformation.Action.PARTIAL_CLOSE
                ? trades.previewPartialClose(trade.id(), request.closeQuantity()) : null;
        TradeService.LifecycleAssessment lifecycle = lifecycleAction
                ? trades.previewLifecycleConversion(trade.id(), request.action(), request.legIndex()) : null;
        TradeService.PositionAssessment before = lifecycle != null ? lifecycle.current()
                : partial == null ? trades.analyzeActivePosition(trade.id()) : partial.current();
        TradeService.UnwindAssessment unwind = switch (request.action()) {
            case CLOSE, ROLL -> trades.previewUnwind(trade.id());
            default -> null;
        };
        TradeService.PositionAssessment after = lifecycle != null ? lifecycle.survivor()
                : partial == null ? null : partial.survivor();
        TradeService.OpenRequest afterRequest = null;
        ApiResponses.TradePreviewResponse afterReview = null;
        TradeService.AdjustmentAssessment adjustment = null;
        TradeController.PlacementProjection projection = request.action() == PositionTransformation.Action.ROLL
                ? tradeController.projectionAfterClose(ctx, trade, unwind) : null;
        if (request.after() != null) {
            afterRequest = TradeController.toAnalysisOpenRequest(request.after(), trade.accountId());
            if (adjustmentAction(request.action())) {
                adjustment = trades.previewAdjustment(trade.id(), request.action(), afterRequest);
                before = adjustment.current();
                after = adjustment.survivor();
                afterRequest = adjustment.exactAfterRequest();
                afterReview = tradeController.transformationPayload(ctx, trade.accountId(), afterRequest,
                        after.preview(), trade.id());
            } else {
                afterReview = tradeController.previewPayloadForAccount(ctx, request.after(), trade.accountId(), projection);
                if (projection == null) {
                    after = trades.analyzePositionPackage(trade.id(), PositionDomain.PackageSource.PRACTICE_TRADE,
                            PositionDomain.ExecutionLane.PRACTICE, afterRequest);
                } else {
                    after = trades.analyzePositionPackage(trade.id(), PositionDomain.PackageSource.PRACTICE_TRADE,
                            PositionDomain.ExecutionLane.PRACTICE, afterRequest, projection.cashCents(),
                            projection.reservedCents(), projection.releasedShares());
                }
                List<String> reasons = new ArrayList<>(after.risk().blockReasons());
                reasons.addAll(afterReview.guardrails().blockReasons());
                boolean eligible = after.risk().mechanicallyEligible()
                        && !"BLOCK".equals(afterReview.guardrails().level());
                var risk = new PositionTransformation.RiskSnapshot(after.risk().maxLossCents(),
                        after.risk().reserveCents(), after.risk().maxProfitCents(), eligible,
                        reasons.stream().distinct().toList(), after.risk().evidenceBasis());
                after = new TradeService.PositionAssessment(after.position(), after.preview(), risk);
            }
        }
        PositionTransformation.Preview preview = PositionTransformation.preview(new PositionTransformation.Request(
                request.action(), before.position(), after == null ? null : after.position(),
                before.risk(), after == null ? null : after.risk(),
                request.action() == PositionTransformation.Action.CLOSE
                        ? unwind.realizedPnlToDateCents()
                        : request.action() == PositionTransformation.Action.ROLL
                            ? unwind.actionRealizedPnlCents()
                            : adjustment != null ? adjustment.actionRealizedPnlCents()
                            : lifecycle != null ? lifecycle.actionRealizedPnlCents()
                            : partial == null ? null : partial.actionRealizedPnlCents()));
        return new Prepared(trade, plan, before, after, afterRequest, afterReview, projection, unwind,
                partial, adjustment, lifecycle, preview);
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

    private ApiResponses.PositionTransformationPreview<io.liftandshift.strikebench.paper.TradePreview,
            ApiResponses.TradePreviewResponse> response(
            Prepared prepared, String token, String expiresAt) {
        Long closingCash = null;
        Long closingFees = null;
        Long actionRealized = null;
        if (prepared.adjustment() != null) {
            closingCash = prepared.adjustment().closingCashCents();
            closingFees = prepared.adjustment().closingFeesCents();
            actionRealized = prepared.adjustment().actionRealizedPnlCents();
        } else if (prepared.partial() != null) {
            closingCash = prepared.partial().closingCashCents();
            closingFees = prepared.partial().closingFeesCents();
            actionRealized = prepared.partial().actionRealizedPnlCents();
        } else if (prepared.unwind() != null) {
            closingCash = prepared.unwind().closingCashCents();
            closingFees = prepared.unwind().closingFeesCents();
            actionRealized = prepared.unwind().actionRealizedPnlCents();
        }
        Long openingCash = prepared.adjustment() == null ? null : prepared.adjustment().openingCashCents();
        Long openingFees = prepared.adjustment() == null ? null : prepared.adjustment().openingFeesCents();
        Long allocatedEntryBasis = null;
        Long allocatedOpenFees = null;
        if (prepared.adjustment() != null) {
            allocatedEntryBasis = prepared.adjustment().allocatedEntryBasisCents();
            allocatedOpenFees = prepared.adjustment().allocatedOpenFeesCents();
        } else if (prepared.lifecycle() != null) {
            allocatedEntryBasis = prepared.lifecycle().allocatedEntryBasisCents();
            allocatedOpenFees = prepared.lifecycle().allocatedOpenFeesCents();
        }
        if (prepared.lifecycle() != null) {
            actionRealized = prepared.lifecycle().actionRealizedPnlCents();
        }
        Long realizedToDate = actionRealized == null ? null
                : prepared.adjustment() != null ? prepared.adjustment().realizedPnlToDateCents()
                : prepared.lifecycle() != null ? prepared.lifecycle().realizedPnlToDateCents()
                : prepared.partial() == null
                    ? prepared.unwind().realizedPnlToDateCents()
                    : Math.addExact(prepared.trade().realizedPnlCents() == null
                            ? 0 : prepared.trade().realizedPnlCents(), actionRealized);
        List<String> basisNotes = prepared.adjustment() != null ? prepared.adjustment().basisNotes()
                : prepared.lifecycle() != null ? prepared.lifecycle().basisNotes() : null;
        ApiResponses.OptionLifecycleProjection lifecycle = prepared.lifecycle() == null ? null
                : new ApiResponses.OptionLifecycleProjection(prepared.lifecycle().action().name(),
                        prepared.lifecycle().legIndex(), prepared.lifecycle().contract(),
                        prepared.lifecycle().expiration().toString(),
                        prepared.lifecycle().settlementUnderlyingCents(),
                        prepared.lifecycle().settlementPriceBasis(),
                        prepared.lifecycle().optionSettlementCashCents(),
                        prepared.lifecycle().stockCashCents(), prepared.lifecycle().sharesDelta(),
                        prepared.lifecycle().reserveBeforeCents(), prepared.lifecycle().reserveAfterCents(),
                        prepared.lifecycle().projectedCashAfterCents(),
                        prepared.lifecycle().projectedReservedAfterCents(),
                        prepared.lifecycle().basisNotes());
        return new ApiResponses.PositionTransformationPreview<>(prepared.preview(),
                prepared.before().preview(), prepared.afterReview(),
                closingCash, closingFees, openingCash, openingFees,
                allocatedEntryBasis, allocatedOpenFees, actionRealized, realizedToDate,
                basisNotes, lifecycle, token, expiresAt);
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
                    + request.closeQuantity() + "|" + request.legIndex() + "|" + fingerprint + "|" + issuedAt;
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("cannot sign transformation preview", e);
        }
    }

    record Request(PositionDomain.PackageSource source, String sourceId, String planId,
                   Long expectedPlanVersion, PositionTransformation.Action action,
                   Integer closeQuantity, Integer legIndex,
                   TradeOpenRequest after, String previewToken) {}

    private record Prepared(TradeRecord trade, Plan.View plan,
                            TradeService.PositionAssessment before,
                            TradeService.PositionAssessment after,
                            TradeService.OpenRequest afterRequest,
                            ApiResponses.TradePreviewResponse afterReview,
                            TradeController.PlacementProjection projection,
                            TradeService.UnwindAssessment unwind,
                            TradeService.PartialCloseAssessment partial,
                            TradeService.AdjustmentAssessment adjustment,
                            TradeService.LifecycleAssessment lifecycle,
                            PositionTransformation.Preview preview) {}

    private static boolean adjustmentAction(PositionTransformation.Action action) {
        return action == PositionTransformation.Action.LEG_CLOSE
                || action == PositionTransformation.Action.REMOVE_LEG
                || action == PositionTransformation.Action.ADD_LEG
                || action == PositionTransformation.Action.ADD_STOCK
                || action == PositionTransformation.Action.REMOVE_STOCK;
    }

    private static boolean lifecycleAction(PositionTransformation.Action action) {
        return action == PositionTransformation.Action.ASSIGNMENT
                || action == PositionTransformation.Action.EXERCISE
                || action == PositionTransformation.Action.EXPIRATION;
    }
}
