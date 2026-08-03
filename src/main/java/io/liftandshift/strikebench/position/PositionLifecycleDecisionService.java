package io.liftandshift.strikebench.position;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.paper.AccountObjectiveService;
import io.liftandshift.strikebench.paper.BookActionProjectionService;
import io.liftandshift.strikebench.paper.BookRiskService;
import io.liftandshift.strikebench.model.Symbol;
import io.liftandshift.strikebench.paper.PortfolioAccountingService;
import io.liftandshift.strikebench.paper.ProtocolEvaluator;
import io.liftandshift.strikebench.util.Ids;
import io.liftandshift.strikebench.util.Json;
import io.liftandshift.strikebench.util.Money;
import io.liftandshift.strikebench.util.OwnerScope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The one held-position policy layer. It composes lifecycle facts, hypothetical Book actions, and
 * the immutable account declaration; it never reprices a package or recalculates Book risk.
 * Surfaced results and personal decisions are append-only calibration evidence, not account
 * transactions and not inputs that can alter EV.
 */
public final class PositionLifecycleDecisionService {
    public static final String SCHEMA_VERSION = "position-lifecycle-decision-v2";
    private static final Set<String> ACTIONS = Set.of("HOLD", "CLOSE_ONE", "CLOSE_K", "CLOSE_ALL",
            "ASSIGNMENT", "CALL_AWAY", "ROLL", "NO_ACTION");

    /** NEEDS_EVIDENCE is NOT an actionable verdict — it is the honest "insufficient evidence to
     *  decide" state that must win precedence over any hold/defend/harvest recommendation. */
    public enum Verdict { KEEP, HARVEST, REDUCE, DEFEND, ACCEPT_ASSIGNMENT, NEEDS_EVIDENCE }

    /** The final evidence gate, after the policy's precedence has selected its decisive mode. */
    public enum EvidenceState {
        SUFFICIENT,
        PARTIAL,
        CURRENT_MARK_UNAVAILABLE,
        FORWARD_ECONOMICS_UNAVAILABLE,
        INSUFFICIENT
    }

    /**
     * The one named reason the final verdict/status was selected. Dimension reasons retain the
     * complete audit trail; this is the concise result a surface can render without rediscovering
     * policy precedence from those dimensions.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DecisionTrigger(String code, String label, String dimension, String status,
                                  String basis) {}

    /**
     * The owning plan's declared exit facts, supplied by the composer that knows the plan link.
     * The policy layer only READS these: the declaration lives on the plan, the entry price on
     * the trade, and the current price on the same preview that priced everything else — no
     * second market read and no second declaration store.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeclaredExitContext(String planId, String planIntent, Long declaredTargetCents,
                                      Integer horizonDays, Long entryUnderlyingCents,
                                      Long currentUnderlyingCents, OffsetDateTime openedAt,
                                      String basis) {}

    /**
     * Final surface semantics owned by the lifecycle policy. The browser may style and render this
     * record; it may not reinterpret missing evidence, invent a friendlier verdict, or maintain a
     * second urgency table.
     *
     * <p>{@code actionable} means that the policy reached a usable decision, including deliberate
     * KEEP/no-action. It is false only when the evidence gate withheld a verdict.
     */
    public record DecisionPresentation(EvidenceState evidenceState, boolean actionable,
                                       String userFacingVerdict, String userFacingStatus,
                                       String tone, DecisionTrigger trigger, int sortPriority) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Dimension(String name, String status, Verdict policySignal, List<String> reasons) {
        public Dimension { reasons = reasons == null ? List.of() : List.copyOf(reasons); }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LimitCheck(String scope, String key, long maxCents,
                             AccountObjectiveService.Enforcement enforcement,
                             long currentCents, boolean breached, Integer restoredByClosingQuantity,
                             String basis) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReductionProposal(int quantityToClose, int quantityRemaining, String action,
                                    String actionFingerprint, List<String> restoredLimits,
                                    String basis) {
        public ReductionProposal { restoredLimits = List.copyOf(restoredLimits); }
    }

    public record ActionAlternative(String action, int quantityAffected, int quantityRemaining,
                                    boolean available, String fingerprint, boolean restoresHardLimits,
                                    String basis) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DecisionAnalysis(
            String schemaVersion,
            String positionFingerprint,
            OffsetDateTime observedAt,
            Verdict verdict,
            String summary,
            DecisionPresentation presentation,
            ProtocolEvaluator.Policy policy,
            String policyFingerprint,
            String marketSnapshotFingerprint,
            String modelFingerprint,
            String objectiveRevisionId,
            String declarationFingerprint,
            String projectionFingerprint,
            List<Dimension> dimensions,
            List<LimitCheck> limits,
            ReductionProposal reduction,
            List<ActionAlternative> alternatives,
            String basis
    ) {
        public DecisionAnalysis {
            dimensions = List.copyOf(dimensions);
            limits = List.copyOf(limits);
            alternatives = List.copyOf(alternatives);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UserDecision(String id, String analysisId, Verdict decision,
                               String selectedAction, Integer quantity, String note,
                               OffsetDateTime decidedAt) {}

    public record DecisionInput(String analysisId, String decision, String selectedAction,
                                Integer quantity, String note) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LifecycleDecisionView(String analysisId, String analysisFingerprint,
                                  DecisionAnalysis analysis, UserDecision latestUserDecision) {}

    /** Exact frozen close facts for Scout's optional redeployment comparison. */
    public record ResolvedAction(String analysisId, String accountId, String symbol,
                                 String action, int quantity, Long executableCloseCostCents,
                                 Long capitalReleasedCents, Long closingPnlCents,
                                 BookActionProjectionService.BookSnapshot postActionBook,
                                 BookActionProjectionService.BasisEffect basisEffect,
                                 String authority, String basis) {}

    private record CapacityResult(Dimension dimension, boolean assignmentActive) {}
    private record VerdictSelection(Verdict verdict, Dimension decisiveDimension) {}

    private final Db db;
    private final Clock clock;

    public PositionLifecycleDecisionService(Db db, Clock clock) {
        this.db = db;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** Pure policy composition. No persistence and no market/account mutation. */
    public DecisionAnalysis analyze(PositionLifecycleAnalysis lifecycle,
                                    BookActionProjectionService.ProjectionSet projections,
                                    AccountObjectiveService.CapacityContext capacity,
                                    DeclaredExitContext declaredExitContext) {
        requireInputs(lifecycle, projections, capacity);
        var policy = capacity.accountPolicy() == null
                ? ProtocolEvaluator.Policy.standard()
                : capacity.accountPolicy().lifecyclePolicy();
        String policyFingerprint = fingerprint(policy, capacity.declarationFingerprint());
        var hold = action(projections, "HOLD");
        List<BookActionProjectionService.ActionProjection> closes = projections.actions().stream()
                .filter(item -> item.action().startsWith("CLOSE_"))
                .filter(BookActionProjectionService.ActionProjection::available)
                .sorted(Comparator.comparingInt(BookActionProjectionService.ActionProjection::quantityAffected))
                .toList();

        List<LimitCheck> limits = limitChecks(capacity.accountPolicy(), hold, closes);
        ReductionProposal reduction = reduction(limits, closes);

        Dimension mechanics = mechanics(lifecycle);
        Dimension protocol = mechanicalProtocol(lifecycle, policy);
        Dimension declaredExit = declaredExit(declaredExitContext, lifecycle);
        CapacityResult capacityResult = capacity(lifecycle, capacity, policy);
        Dimension accountLimits = accountLimits(limits, reduction);
        Dimension economics = economics(lifecycle);
        Dimension tailAndEvents = tailAndEvents(lifecycle, hold, policy);
        Dimension carry = carry(lifecycle, policy);
        Dimension history = history(lifecycle);
        List<Dimension> dimensions = List.of(mechanics, protocol, declaredExit,
                capacityResult.dimension(), accountLimits, economics, tailAndEvents, carry, history);

        VerdictSelection selection;
        if (mechanics.policySignal() != null) selection = select(mechanics.policySignal(), mechanics);
        // §6.4: DEFEND must name the trigger that fired. The stop-loss and the near-expiry time
        // rule are two of those named triggers, and they outrank every discretionary dimension.
        else if (protocol.policySignal() == Verdict.DEFEND) selection = select(Verdict.DEFEND, protocol);
        else if (capacityResult.dimension().policySignal() == Verdict.DEFEND) {
            selection = select(Verdict.DEFEND, capacityResult.dimension());
        }
        else if (accountLimits.policySignal() != null) {
            selection = select(accountLimits.policySignal(), accountLimits);
        }
        else if (capacityResult.assignmentActive()) {
            selection = select(Verdict.ACCEPT_ASSIGNMENT, capacityResult.dimension());
        }
        else if (protocol.policySignal() == Verdict.HARVEST) selection = select(Verdict.HARVEST, protocol);
        // The user's OWN declared exit rule outranks discretionary economics: when the underlying
        // crosses the plan's declared sell-at price, the position surfaces the crossing rather
        // than quietly re-litigating whether holding still has edge.
        else if (declaredExit.policySignal() == Verdict.HARVEST) {
            selection = select(Verdict.HARVEST, declaredExit);
        }
        else if (economics.policySignal() == Verdict.HARVEST) selection = select(Verdict.HARVEST, economics);
        else if (tailAndEvents.policySignal() != null) {
            selection = select(tailAndEvents.policySignal(), tailAndEvents);
        }
        else if (carry.policySignal() == Verdict.HARVEST) selection = select(Verdict.HARVEST, carry);
        // Minimum-evidence rule: an affirmative KEEP ("hold, no action") must rest on an evaluated
        // hold-vs-close economics. If forward economics is unavailable and nothing above produced an
        // action signal, there is no basis to affirm a hold — surface NEEDS_EVIDENCE (the missing input
        // is named in the FORWARD_ECONOMICS dimension) rather than a silent affirmative hold.
        else if (!lifecycle.currentChoice().holdVsClose().available()) {
            selection = select(Verdict.NEEDS_EVIDENCE, economics);
        }
        else selection = select(Verdict.KEEP, null);

        List<ActionAlternative> alternatives = alternatives(closes, limits);
        String projectionFingerprint = fingerprint(projections.actions().stream()
                .map(BookActionProjectionService.ActionProjection::fingerprint).toList());
        DecisionPresentation presentation = presentation(selection, dimensions);
        return new DecisionAnalysis(SCHEMA_VERSION, lifecycle.positionFingerprint(),
                lifecycle.evidence().observedAt(), selection.verdict(),
                summary(selection.verdict(), reduction), presentation, policy,
                policyFingerprint, lifecycle.evidence().marketSnapshotFingerprint(),
                lifecycle.evidence().modelFingerprint(), capacity.objectiveRevisionId(),
                capacity.declarationFingerprint(), projectionFingerprint, dimensions, limits,
                reduction, alternatives,
                "Precedence: executable mechanics; declared quantity capacity; hard account ceilings; "
                        + "the plan's own declared exit price; after-cost hold-vs-close economics; tail/event "
                        + "risk; carry compensation; history as context only. Existing evaluators, marks, "
                        + "accounting, and BookRiskService remain the calculation owners. This policy can "
                        + "change fit or action, never EV.");
    }

    /** Compose and append the exact result that was surfaced. Account holdings remain untouched. */
    public LifecycleDecisionView surface(String userId, String accountId,
                                   PositionLifecycleAnalysis lifecycle,
                                   BookActionProjectionService.ProjectionSet projections,
                                   AccountObjectiveService.CapacityContext capacity) {
        DecisionAnalysis analysis = analyze(lifecycle, projections, capacity, null);
        String analysisFingerprint = fingerprint(lifecycle, projections, capacity, analysis);
        String analysisId = Ids.newId("plda");
        OffsetDateTime surfacedAt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return db.tx(c -> {
            String owner = OwnerScope.ensure(c, userId);
            PortfolioAccountingService.requireOwned(c, owner, accountId);
            Db.execOn(c, "INSERT INTO position_lifecycle_analysis(" +
                            "id,user_id,portfolio_account_id,position_fingerprint,analysis_fingerprint," +
                            "policy_id,policy_fingerprint,market_snapshot_fingerprint,model_fingerprint," +
                            "account_objective_revision_id,declaration_fingerprint,verdict,lifecycle_json," +
                            "book_actions_json,capacity_json,decision_json,surfaced_at) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?)",
                    analysisId, owner, accountId, lifecycle.positionFingerprint(), analysisFingerprint,
                    analysis.policy().policyId(), analysis.policyFingerprint(),
                    analysis.marketSnapshotFingerprint(), analysis.modelFingerprint(),
                    analysis.objectiveRevisionId(), analysis.declarationFingerprint(), analysis.verdict().name(),
                    Json.stable(lifecycle), Json.stable(projections), Json.stable(capacity),
                    Json.stable(analysis), surfacedAt);
            return new LifecycleDecisionView(analysisId, analysisFingerprint, analysis,
                    latestForPosition(c, owner, accountId, lifecycle.positionFingerprint()));
        });
    }

    /** Append a personal decision beside the frozen engine result; never overwrite either. */
    public UserDecision recordUserDecision(String userId, String accountId, DecisionInput input) {
        if (input == null || input.analysisId() == null || input.analysisId().isBlank()) {
            throw new IllegalArgumentException("lifecycle result id is required");
        }
        Verdict decision;
        try { decision = Verdict.valueOf(clean(input.decision(), "decision")); }
        catch (RuntimeException e) {
            throw new IllegalArgumentException("decision must be one of "
                    + String.join(", ", java.util.Arrays.stream(Verdict.values()).map(Enum::name).toList()));
        }
        String selected = input.selectedAction() == null || input.selectedAction().isBlank()
                ? null : clean(input.selectedAction(), "selected action");
        if (selected != null && !ACTIONS.contains(selected)) {
            throw new IllegalArgumentException("selected action is not a lifecycle action");
        }
        if (input.quantity() != null && input.quantity() <= 0) {
            throw new IllegalArgumentException("decision quantity must be positive");
        }
        if (selected != null && selected.startsWith("CLOSE_") && input.quantity() == null) {
            throw new IllegalArgumentException("a close decision must record its quantity");
        }
        if ("CLOSE_ONE".equals(selected) && input.quantity() != 1) {
            throw new IllegalArgumentException("CLOSE_ONE quantity must equal 1");
        }
        String note = input.note() == null || input.note().isBlank() ? null : input.note().trim();
        if (note != null && note.length() > 2000) throw new IllegalArgumentException("decision note is too long");
        String id = Ids.newId("pldu");
        OffsetDateTime at = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return db.tx(c -> {
            String owner = OwnerScope.ensure(c, userId);
            PortfolioAccountingService.requireOwned(c, owner, accountId);
            List<String> frozenActions = Db.queryOn(c,
                    "SELECT book_actions_json::text actions FROM position_lifecycle_analysis " +
                            "WHERE id=? AND user_id=? AND portfolio_account_id=?",
                    r -> r.str("actions"), input.analysisId(), owner, accountId);
            if (frozenActions.isEmpty()) {
                throw new io.liftandshift.strikebench.util.ResourceNotFoundException(
                        "No lifecycle result " + input.analysisId() + " in this account");
            }
            if (selected != null && selected.startsWith("CLOSE_")) {
                boolean exactAction = java.util.stream.StreamSupport.stream(
                                Json.parse(frozenActions.getFirst()).withArray("actions").spliterator(), false)
                        .anyMatch(action -> selected.equals(action.path("action").asText())
                                && input.quantity() == action.path("quantityAffected").asInt()
                                && action.path("available").asBoolean());
                if (!exactAction) {
                    throw new IllegalArgumentException(
                            "selected close action and quantity are not available in the frozen result");
                }
            }
            Db.execOn(c, "INSERT INTO position_lifecycle_user_decision(" +
                            "id,analysis_id,user_id,decision,selected_action,quantity,note,decided_at) " +
                            "VALUES(?,?,?,?,?,?,?,?)",
                    id, input.analysisId(), owner, decision.name(), selected, input.quantity(), note, at);
            return new UserDecision(id, input.analysisId(), decision, selected, input.quantity(), note, at);
        });
    }

    /**
     * Resolves a close-to-redeploy source from the exact lifecycle result that crossed the wire.
     * Client-supplied costs, released capital, Book state, and tax basis are never trusted.
     */
    public ResolvedAction resolveAction(String userId, String accountId, String analysisId,
                                        String requestedAction, Integer requestedQuantity) {
        String action = clean(requestedAction, "redeployment action");
        if (!action.startsWith("CLOSE_")) {
            throw new IllegalArgumentException("redeployment must begin with a frozen close action");
        }
        if (requestedQuantity == null || requestedQuantity <= 0) {
            throw new IllegalArgumentException("redeployment close quantity must be positive");
        }
        return db.with(c -> {
            String owner = OwnerScope.ensure(c, userId);
            PortfolioAccountingService.requireOwned(c, owner, accountId);
            List<FrozenAnalysis> rows = Db.queryOn(c,
                    "SELECT lifecycle_json::text lifecycle,book_actions_json::text actions "
                            + "FROM position_lifecycle_analysis "
                            + "WHERE id=? AND user_id=? AND portfolio_account_id=?",
                    row -> new FrozenAnalysis(
                            Json.read(row.str("lifecycle"), PositionLifecycleAnalysis.class),
                            Json.read(row.str("actions"), BookActionProjectionService.ProjectionSet.class)),
                    analysisId, owner, accountId);
            if (rows.isEmpty()) {
                throw new io.liftandshift.strikebench.util.ResourceNotFoundException(
                        "No lifecycle result " + analysisId + " in this account");
            }
            FrozenAnalysis frozen = rows.getFirst();
            BookActionProjectionService.ActionProjection selected = frozen.actions().actions().stream()
                    .filter(candidate -> action.equals(candidate.action()))
                    .filter(candidate -> requestedQuantity == candidate.quantityAffected())
                    .filter(BookActionProjectionService.ActionProjection::available)
                    .findFirst().orElseThrow(() -> new IllegalArgumentException(
                            "redeployment action and quantity are not available in the frozen result"));
            BookActionProjectionService.ActionProjection hold = frozen.actions().actions().stream()
                    .filter(candidate -> "HOLD".equals(candidate.action()))
                    .findFirst().orElseThrow(() -> new IllegalStateException(
                            "frozen lifecycle result has no HOLD snapshot"));
            Long cost = selected.executableCost() == null
                    || selected.executableCost().signedNetCashCents() == null ? null
                    : Math.max(0, -selected.executableCost().signedNetCashCents());
            Long released = hold.snapshot() == null || selected.snapshot() == null
                    || hold.snapshot().encumbrance() == null || selected.snapshot().encumbrance() == null
                    || hold.snapshot().encumbrance().cents() == null
                    || selected.snapshot().encumbrance().cents() == null ? null
                    : Math.max(0, Math.subtractExact(hold.snapshot().encumbrance().cents(),
                            selected.snapshot().encumbrance().cents()));
            int totalQuantity = Math.addExact(selected.quantityAffected(), selected.quantityRemaining());
            Long pnl = frozen.lifecycle().history().netPnlIfClosedCents() == null ? null
                    : proportional(frozen.lifecycle().history().netPnlIfClosedCents(),
                            selected.quantityAffected(), totalQuantity);
            String authority = selected.executableCost() == null
                    || selected.executableCost().authority() == null ? "UNAVAILABLE"
                    : selected.executableCost().authority().name();
            return new ResolvedAction(analysisId, accountId, frozen.lifecycle().symbol(),
                    action, requestedQuantity, cost, released, pnl, selected.snapshot(),
                    selected.basisEffect(), authority,
                    "Resolved from immutable lifecycle_json and book_actions_json; no client financial amount is accepted.");
        });
    }

    private static Dimension mechanics(PositionLifecycleAnalysis lifecycle) {
        var close = lifecycle.currentChoice().close();
        return close.executable()
                ? dimension("MECHANICS", "PASS", null,
                "Executable close evidence is available at the opposite book side with fees.")
                : dimension("MECHANICS", "BLOCKED", Verdict.NEEDS_EVIDENCE,
                "The exact executable close is unavailable, so no action can be responsibly recommended: "
                        + close.unavailableReason());
    }

    /**
     * The named mechanical triggers §6.4 requires DEFEND to be able to point at — evaluated by the
     * ONE {@link ProtocolEvaluator} against this position's own option-only opening basis, its
     * P/L-if-closed, and the session clock. STOP_LOSS and a near-expiry time rule are DEFEND
     * triggers; TAKE_PROFIT is a HARVEST trigger; a time rule that is merely inside the decision
     * window is stated without forcing a verdict, because "decide whether to roll" is not a defense.
     */
    private static Dimension mechanicalProtocol(PositionLifecycleAnalysis lifecycle,
                                                ProtocolEvaluator.Policy policy) {
        var history = lifecycle.history();
        Long basis = history.signedOptionOpeningCashCents();
        List<String> reasons = new ArrayList<>();
        if (!history.available() || basis == null) {
            reasons.add("No recorded option-only opening basis, so the take-profit and stop lines "
                    + "cannot be placed: " + (history.unavailableReason() == null
                    ? "opening history is unavailable." : history.unavailableReason()));
            return new Dimension("MECHANICAL_PROTOCOL", "UNAVAILABLE", null, reasons);
        }
        Integer sessions = lifecycle.carryCollateral().tradingSessionsRemaining();
        var time = sessions == null ? null
                : io.liftandshift.strikebench.market.OptionTime.ofRecordedUnits(sessions,
                        lifecycle.carryCollateral().calendarDaysRemaining());
        var rules = ProtocolEvaluator.rules(policy, basis);
        for (ProtocolEvaluator.Rule rule : rules) {
            reasons.add(rule.rule() + " line of policy " + rule.policyId() + ": "
                    + (rule.triggerPnlCents() != null ? rule.triggerPnlCents() + " cents P/L"
                    : rule.triggerSessionsToExpiry() + " trading sessions to expiry")
                    + " — " + rule.summary() + ".");
        }
        List<ProtocolEvaluator.Trigger> fired = ProtocolEvaluator.evaluate(policy,
                new ProtocolEvaluator.Inputs(basis, history.netPnlIfClosedCents(), time));
        if (history.netPnlIfClosedCents() == null) {
            reasons.add("No net P/L-if-closed result, so the price rules stay silent rather than guess.");
        }
        Verdict signal = null;
        String status = "PASS";
        for (ProtocolEvaluator.Trigger trigger : fired) {
            reasons.add("FIRED " + trigger.rule() + ": " + trigger.summary() + ".");
            if (ProtocolEvaluator.STOP_LOSS.equals(trigger.rule())) {
                signal = Verdict.DEFEND;
                status = "STOP_LOSS_TRIGGER";
            } else if (ProtocolEvaluator.TIME_EXIT.equals(trigger.rule()) && signal == null
                    && sessions != null && sessions <= policy.nearExpirySessions()) {
                // Inside the near-expiry window the time rule IS a defense: rolling has stopped
                // being management and the position must be closed or settled deliberately.
                signal = Verdict.DEFEND;
                status = "EXPIRY_TIME_TRIGGER";
            } else if (ProtocolEvaluator.TAKE_PROFIT.equals(trigger.rule()) && signal == null) {
                signal = Verdict.HARVEST;
                status = "TAKE_PROFIT_TRIGGER";
            } else if (signal == null) {
                status = "TIME_DECISION_OPEN";
            }
        }
        return new Dimension("MECHANICAL_PROTOCOL", status, signal, reasons);
    }

    /**
     * The user's own declared exit, watched post-open. Two named triggers: the underlying
     * crossing the plan's declared exit price (HARVEST — the user's rule fired, not the
     * engine's), and the 80/50 pace rule — 80% of the declared move inside 50% of the declared
     * horizon — which surfaces "faster than the thesis assumed" without forcing a verdict.
     * Honest absence is stated per mode: no supplied plan context, no declared price, or no
     * current price each name themselves instead of silently reading as "no trigger".
     */
    private static Dimension declaredExit(DeclaredExitContext context,
                                          PositionLifecycleAnalysis lifecycle) {
        if (context == null) {
            return dimension("DECLARED_EXIT", "UNLINKED", null,
                    "No owning plan context was supplied to this analysis, so no declared exit "
                            + "price is watched here.");
        }
        List<String> reasons = new ArrayList<>();
        boolean exitIntent = "EXIT".equalsIgnoreCase(context.planIntent());
        String targetWord = exitIntent ? "sell-at" : "declared target";
        if (context.declaredTargetCents() == null) {
            reasons.add("The owning plan declares no exit price. Declare a "
                    + (exitIntent ? "sell-at" : "target")
                    + " price on the plan to arm this trigger.");
            return new Dimension("DECLARED_EXIT", "UNDECLARED", null, reasons);
        }
        long target = context.declaredTargetCents();
        if (context.currentUnderlyingCents() == null) {
            reasons.add("The " + Money.fmt(target) + " " + targetWord
                    + " price cannot be checked without a current underlying price.");
            return new Dimension("DECLARED_EXIT", "UNAVAILABLE", null, reasons);
        }
        long spot = context.currentUnderlyingCents();
        Long entry = context.entryUnderlyingCents();
        // Direction comes from the declared INTENT, not from where the entry price happened to
        // sit: an EXIT sell-at means "sell at or above" always, and a HEDGE protect-to means
        // "the floor is live at or below" always. Only an undeclared-intent target falls back to
        // the entry comparison.
        boolean upside = exitIntent
                || (!"HEDGE".equalsIgnoreCase(context.planIntent())
                        && (entry == null || target >= entry));
        boolean crossed = upside ? spot >= target : spot <= target;
        if (crossed) {
            if (upside) {
                reasons.add("The underlying at " + Money.fmt(spot) + " has crossed the "
                        + Money.fmt(target) + " " + targetWord + " price declared on the owning plan. "
                        + "Your own exit rule fired — review the executable close beside it.");
                return new Dimension("DECLARED_EXIT", "PRICE_TARGET_CROSSED", Verdict.HARVEST, reasons);
            }
            reasons.add("The underlying at " + Money.fmt(spot) + " has fallen through the "
                    + Money.fmt(target) + " protect-to level declared on the owning plan. The floor "
                    + "decision this position exists for is live; tail and defense rules stay the "
                    + "urgency owners.");
            // A breached floor is NOT an exit win: it gets its own named status so no surface
            // can dress it in take-profit grammar.
            return new Dimension("DECLARED_EXIT", "FLOOR_BREACHED", null, reasons);
        }
        if (upside && entry != null && entry != target && context.horizonDays() != null
                && context.horizonDays() > 0 && context.openedAt() != null
                && lifecycle.evidence().observedAt() != null) {
            double moveFraction = (double) (spot - entry) / (double) (target - entry);
            long elapsedDays = java.time.temporal.ChronoUnit.DAYS.between(
                    context.openedAt(), lifecycle.evidence().observedAt());
            double timeFraction = (double) elapsedDays / context.horizonDays();
            double movePct = 100.0 * (spot - entry) / entry;
            if (moveFraction >= 0.8 && timeFraction <= 0.5 && timeFraction >= 0) {
                reasons.add(String.format(Locale.ROOT,
                        "Underlying is %+.1f%% in %d day(s) — %.0f%% of the way to the %s "
                                + Money.fmt(target) + " in %.0f%% of the declared %d-day horizon "
                                + "(named 80/50 pace rule). This is faster than the thesis "
                                + "assumed; review whether to take the exit early.",
                        movePct, elapsedDays, moveFraction * 100, targetWord,
                        timeFraction * 100, context.horizonDays()));
                return new Dimension("DECLARED_EXIT", "RUN_UP", null, reasons);
            }
            reasons.add(String.format(Locale.ROOT,
                    "Underlying " + Money.fmt(spot) + " is %.0f%% of the way to the %s "
                            + Money.fmt(target) + " at %.0f%% of the declared %d-day horizon; "
                            + "the declared exit has not fired.",
                    Math.max(0, moveFraction * 100), targetWord,
                    Math.max(0, timeFraction * 100), context.horizonDays()));
            return new Dimension("DECLARED_EXIT", "WATCHING", null, reasons);
        }
        reasons.add("The " + Money.fmt(target) + " " + targetWord
                + " has not been crossed (underlying " + Money.fmt(spot) + ").");
        return new Dimension("DECLARED_EXIT", "WATCHING", null, reasons);
    }

    private static CapacityResult capacity(PositionLifecycleAnalysis lifecycle,
                                           AccountObjectiveService.CapacityContext context,
                                           ProtocolEvaluator.Policy policy) {
        long putShares = assignment(lifecycle, OptionType.PUT, true);
        long putDollars = assignment(lifecycle, OptionType.PUT, false);
        long callShares = assignment(lifecycle, OptionType.CALL, true);
        long callProceeds = assignment(lifecycle, OptionType.CALL, false);
        List<String> reasons = new ArrayList<>();
        boolean inadequate = false;
        var exact = context.packageCapacity();
        if (putShares > 0 && "AVOID".equals(context.assignmentPreference())) {
            inadequate = true;
            reasons.add("The account declares AVOID assignment while this package can require "
                    + putShares + " shares and " + putDollars + " cents at strike.");
        }
        if (exact == null) {
            reasons.add("No exact-fingerprint package capacity is declared; willingness is not inferred from a symbol.");
        } else {
            if (putShares > 0 && exact.acceptedAssignmentShares() != null
                    && exact.acceptedAssignmentShares() < putShares) {
                inadequate = true;
                reasons.add("Accepted assignment shares " + exact.acceptedAssignmentShares()
                        + " are below the package requirement " + putShares + ".");
            }
            if (putDollars > 0 && exact.acceptedAssignmentDollarsCents() != null
                    && exact.acceptedAssignmentDollarsCents() < putDollars) {
                inadequate = true;
                reasons.add("Accepted assignment dollars are below the package strike obligation.");
            }
            Long effective = lifecycle.assignmentExit().legs().stream()
                    .filter(leg -> leg.optionType() == OptionType.PUT)
                    .map(PositionLifecycleAnalysis.AssignmentLeg::freshEyesEffectivePricePerShareCents)
                    .filter(java.util.Objects::nonNull).max(Long::compareTo).orElse(null);
            if (effective != null && exact.acceptedEffectiveAcquisitionPricePerShareCents() != null
                    && effective > exact.acceptedEffectiveAcquisitionPricePerShareCents()) {
                inadequate = true;
                reasons.add("Fresh-eyes effective acquisition price exceeds the declared acceptable price.");
            }
            if (callShares > 0 && exact.acceptedCallAwayShares() != null
                    && exact.acceptedCallAwayShares() < callShares) {
                inadequate = true;
                reasons.add("Accepted call-away shares are below the package deliverable.");
            }
            if (callProceeds > 0 && exact.acceptedCallAwayProceedsCents() != null
                    && callProceeds < exact.acceptedCallAwayProceedsCents()) {
                inadequate = true;
                reasons.add("Modeled call-away proceeds are below the declared acceptable proceeds.");
            }
            if (!inadequate) reasons.add("The exact package declaration does not reject its physical quantities.");
        }
        // ONE clock: the assignment decision is measured in trading sessions like every other
        // policy threshold, so "5" means the same distance here as it does in the alert rail.
        Integer sessions = lifecycle.carryCollateral().tradingSessionsRemaining();
        boolean assignmentActive = !inadequate && exact != null && putShares > 0 && sessions != null
                && sessions <= policy.assignmentDecisionSessions()
                && exact.acceptedAssignmentShares() != null && exact.acceptedAssignmentShares() >= putShares
                && exact.acceptedAssignmentDollarsCents() != null
                && exact.acceptedAssignmentDollarsCents() >= putDollars;
        if (assignmentActive) reasons.add("Assignment is now an active near-expiry decision under the named "
                + policy.assignmentDecisionSessions() + "-session threshold of policy " + policy.policyId() + ".");
        return new CapacityResult(new Dimension("INTENT_CAPACITY",
                inadequate ? "INCOHERENT" : exact == null ? "UNDECLARED" : "COHERENT",
                inadequate ? Verdict.DEFEND : assignmentActive ? Verdict.ACCEPT_ASSIGNMENT : null,
                reasons), assignmentActive);
    }

    private static Dimension accountLimits(List<LimitCheck> checks, ReductionProposal reduction) {
        List<LimitCheck> hard = checks.stream().filter(LimitCheck::breached)
                .filter(check -> check.enforcement() == AccountObjectiveService.Enforcement.HARD).toList();
        List<LimitCheck> advisory = checks.stream().filter(LimitCheck::breached)
                .filter(check -> check.enforcement() == AccountObjectiveService.Enforcement.ADVISORY).toList();
        List<String> reasons = new ArrayList<>();
        hard.forEach(check -> reasons.add("Hard " + check.scope().toLowerCase(Locale.ROOT) + " ceiling "
                + check.key() + " is exceeded: " + check.currentCents() + " > " + check.maxCents() + " cents."));
        advisory.forEach(check -> reasons.add("Advisory " + check.scope().toLowerCase(Locale.ROOT) + " ceiling "
                + check.key() + " is exceeded: " + check.currentCents() + " > " + check.maxCents() + " cents."));
        if (checks.isEmpty()) reasons.add("No account capacity ceilings are declared.");
        else if (hard.isEmpty() && advisory.isEmpty()) reasons.add("Every declared account ceiling is within capacity.");
        Verdict signal = hard.isEmpty() ? null : reduction == null ? Verdict.DEFEND : Verdict.REDUCE;
        if (reduction != null) reasons.add(reduction.basis());
        return new Dimension("ACCOUNT_LIMITS", hard.isEmpty()
                ? advisory.isEmpty() ? "PASS" : "ADVISORY_BREACH"
                : reduction == null ? "HARD_BREACH_UNRESOLVED" : "HARD_BREACH_RESTORABLE",
                signal, reasons);
    }

    private static Dimension economics(PositionLifecycleAnalysis lifecycle) {
        var economics = lifecycle.currentChoice().holdVsClose();
        if (!economics.available()) return dimension("FORWARD_ECONOMICS", "UNAVAILABLE", null,
                economics.unavailableReason());
        Long realized = economics.realizedVolEvAfterCostsCents();
        Long market = economics.marketEvAfterCostsCents();
        long materiality = economics.materialityCents() == null ? 0 : economics.materialityCents();
        List<String> reasons = new ArrayList<>();
        reasons.add("Hold rather than close: realized-volatility EV after costs = " + realized
                + " cents; market-implied benchmark = " + market + " cents; materiality = "
                + materiality + " cents.");
        if (realized != null && realized < -materiality) {
            reasons.add("Holding is materially adverse versus paying the executable close now.");
            return new Dimension("FORWARD_ECONOMICS", "ADVERSE", Verdict.HARVEST, reasons);
        }
        if (realized != null && realized > materiality) {
            reasons.add("Holding has a material positive after-cost realistic-measure estimate; later risk dimensions remain visible.");
            return new Dimension("FORWARD_ECONOMICS", "POSITIVE", Verdict.KEEP, reasons);
        }
        reasons.add("No material after-cost edge is demonstrated for hold versus close.");
        return new Dimension("FORWARD_ECONOMICS", "MIXED", null, reasons);
    }

    private static Dimension tailAndEvents(PositionLifecycleAnalysis lifecycle,
                                           BookActionProjectionService.ActionProjection hold,
                                           ProtocolEvaluator.Policy policy) {
        List<String> reasons = new ArrayList<>();
        String eventStatus = lifecycle.assignmentExit().eventEvidenceStatus();
        boolean eventUnavailable = "UNAVAILABLE".equals(eventStatus);
        boolean confirmed = lifecycle.assignmentExit().eventCrossings().stream()
                .anyMatch(event -> "CONFIRMED".equals(event.status()));
        if (eventUnavailable) {
            reasons.add("Event crossing cannot be assessed because current event data is unavailable.");
            reasons.addAll(lifecycle.assignmentExit().limitations());
        } else if (confirmed) reasons.add("The package crosses confirmed issuer event evidence.");
        else if (!lifecycle.assignmentExit().eventCrossings().isEmpty()) {
            reasons.add("The package crosses estimated issuer event evidence; it is not promoted to confirmed.");
        } else reasons.add("The current event data shows no event crossing.");
        Long shortfall = lifecycle.currentChoice().expectedShortfallCents();
        boolean tailBreach = policy.expectedShortfallDefendCents() != null && shortfall != null
                && shortfall >= policy.expectedShortfallDefendCents();
        if (shortfall != null) reasons.add("Expected shortfall result: " + shortfall + " cents ("
                + lifecycle.currentChoice().expectedShortfallBasis() + ").");
        if (hold != null && hold.snapshot() != null && hold.snapshot().risk() != null) {
            var risk = hold.snapshot().risk();
            if (risk.themes().concentrationCallout() != null) reasons.add(risk.themes().concentrationCallout());
            if (risk.expiries().clusterNote() != null) reasons.add(risk.expiries().clusterNote());
            reasons.addAll(risk.collisions());
        }
        boolean eventBreach = confirmed && policy.defendConfirmedEvents();
        boolean defend = tailBreach || eventBreach;
        if (tailBreach) reasons.add("Expected shortfall crosses the named policy's defense threshold.");
        if (eventBreach) {
            reasons.add("This named policy requires defense for a confirmed event crossing.");
        }
        String status = tailBreach && eventBreach ? "TAIL_AND_EVENT_TRIGGER"
                : tailBreach ? "EXPECTED_SHORTFALL_TRIGGER"
                : eventBreach ? "CONFIRMED_EVENT_TRIGGER"
                : eventUnavailable ? "UNAVAILABLE" : confirmed ? "CAUTION" : "PASS";
        return new Dimension("TAIL_EVENT", status, defend ? Verdict.DEFEND : null, reasons);
    }

    private static Dimension carry(PositionLifecycleAnalysis lifecycle,
                                   ProtocolEvaluator.Policy policy) {
        var carry = lifecycle.carryCollateral();
        List<String> reasons = new ArrayList<>();
        if (carry.grossRemainingPremiumCents() != null) {
            reasons.add("Gross remaining premium if expiry is worthless: "
                    + carry.grossRemainingPremiumCents() + " cents; gross annualized rate: "
                    + carry.grossAnnualizedRemainingPremiumPct() + "%.");
        } else reasons.add("Remaining executable premium is unavailable.");
        reasons.add("Settlement income, option carry, and buying-power encumbrance remain separate facts.");
        boolean capturedTrigger = lifecycle.history().available()
                && lifecycle.history().grossPremiumCapturedPct() != null
                && lifecycle.history().grossPremiumCapturedPct() >= policy.harvestCapturedPremiumPct()
                && underRemainingThreshold(carry.grossRemainingPremiumCents(), policy);
        long assignmentDollars = assignment(lifecycle, OptionType.PUT, false);
        var close = lifecycle.currentChoice().close();
        long closeCost = close.price().afterFeeNetCents() == null ? Long.MAX_VALUE
                : Math.max(0, -close.price().afterFeeNetCents());
        Double closePct = assignmentDollars <= 0 || closeCost == Long.MAX_VALUE ? null
                : 100.0 * closeCost / assignmentDollars;
        boolean cheapRiskRemoval = assignmentDollars > 0 && closePct != null
                && closePct <= policy.cheapRiskRemovalMaxPctOfAssignment()
                && underRemainingThreshold(carry.grossRemainingPremiumCents(), policy)
                && lifecycle.currentChoice().expectedShortfallCents() != null
                && lifecycle.currentChoice().expectedShortfallCents() > 0;
        if (closePct != null) reasons.add("Executable close cost is "
                + Math.round(closePct * 10_000.0) / 10_000.0 + "% of strike assignment dollars.");
        if (capturedTrigger) reasons.add("Captured premium and residual premium cross this named harvest policy.");
        if (cheapRiskRemoval) reasons.add("The named cheap-risk-removal policy buys back assignment tail for a small fraction of strike dollars.");
        boolean harvest = capturedTrigger || cheapRiskRemoval;
        String status = capturedTrigger && cheapRiskRemoval ? "CAPTURED_AND_CHEAP_RISK_REMOVAL_TRIGGER"
                : capturedTrigger ? "CAPTURED_PREMIUM_TRIGGER"
                : cheapRiskRemoval ? "CHEAP_RISK_REMOVAL_TRIGGER" : "CONTEXT";
        return new Dimension("CARRY_COMPENSATION", status,
                harvest ? Verdict.HARVEST : null, reasons);
    }

    private static Dimension history(PositionLifecycleAnalysis lifecycle) {
        List<String> reasons = new ArrayList<>();
        if (lifecycle.history().available()) {
            reasons.add("Historical net P/L if closed: " + lifecycle.history().netPnlIfClosedCents() + " cents.");
            reasons.add("Historical P/L is displayed context and never contributes to KEEP.");
        } else reasons.add(lifecycle.history().unavailableReason());
        return new Dimension("HISTORY", lifecycle.history().available() ? "CONTEXT_ONLY" : "UNAVAILABLE",
                null, reasons);
    }

    private static List<LimitCheck> limitChecks(AccountObjectiveService.AccountCapacityPolicy policy,
                                                BookActionProjectionService.ActionProjection hold,
                                                List<BookActionProjectionService.ActionProjection> closes) {
        if (policy == null || hold == null || hold.snapshot() == null) return List.of();
        List<AccountObjectiveService.CapacityCheck> current =
                AccountObjectiveService.assessCapacity(policy, usage(hold.snapshot()));
        List<LimitCheck> out = new ArrayList<>();
        for (AccountObjectiveService.CapacityCheck check : current) {
            Integer restored = closes.stream().filter(close -> close.snapshot() != null)
                    .filter(close -> AccountObjectiveService.assessCapacity(policy, usage(close.snapshot())).stream()
                            .filter(next -> next.scope().equals(check.scope()) && next.key().equals(check.key()))
                            .noneMatch(AccountObjectiveService.CapacityCheck::breached))
                    .map(BookActionProjectionService.ActionProjection::quantityAffected)
                    .findFirst().orElse(null);
            out.add(new LimitCheck(check.scope(), check.key(), check.maxCents(), check.enforcement(),
                    check.currentCents() == null ? Long.MAX_VALUE : check.currentCents(),
                    check.breached(), restored, check.basis()));
        }
        return List.copyOf(out);
    }

    private static AccountObjectiveService.CapacityUsage usage(
            BookActionProjectionService.BookSnapshot snapshot) {
        Map<String, Long> symbols = new LinkedHashMap<>();
        Map<String, Long> themes = new LinkedHashMap<>();
        Map<String, Long> expiries = new LinkedHashMap<>();
        if (snapshot != null && snapshot.risk() != null) {
            snapshot.risk().themes().symbolNotionals().forEach(row ->
                    symbols.merge(Symbol.normalize(row.symbol()), row.notionalCents(), Math::addExact));
            snapshot.risk().themes().rows().forEach(row ->
                    themes.merge(row.label().toUpperCase(Locale.ROOT), row.notionalCents(), Math::addExact));
            snapshot.risk().expiries().rows().forEach(row ->
                    expiries.merge(row.date().toUpperCase(Locale.ROOT), row.notionalCents(), Math::addExact));
        }
        Long encumbrance = snapshot == null || snapshot.encumbrance() == null
                ? null : snapshot.encumbrance().cents();
        return new AccountObjectiveService.CapacityUsage(symbols, themes, expiries, encumbrance,
                "Values come from the Book risk and tracked-account hypothetical snapshot.");
    }

    private static long limitValue(String scope, String key,
                                   BookActionProjectionService.BookSnapshot snapshot) {
        if (snapshot == null) return Long.MAX_VALUE;
        return switch (scope) {
            case "SYMBOL" -> snapshot.risk().themes().symbolNotionals().stream()
                    .filter(row -> row.symbol().equalsIgnoreCase(key))
                    .mapToLong(BookRiskService.SymbolNotional::notionalCents).sum();
            case "THEME" -> snapshot.risk().themes().rows().stream()
                    .filter(row -> row.label().equalsIgnoreCase(key))
                    .mapToLong(BookRiskService.ThemeRow::notionalCents).sum();
            case "EXPIRY" -> snapshot.risk().expiries().rows().stream()
                    .filter(row -> row.date().equals(key))
                    .mapToLong(BookRiskService.ExpiryRow::notionalCents).sum();
            case "ENCUMBRANCE" -> snapshot.encumbrance().cents();
            default -> throw new IllegalArgumentException("unknown capacity scope " + scope);
        };
    }

    private static ReductionProposal reduction(List<LimitCheck> limits,
                                                List<BookActionProjectionService.ActionProjection> closes) {
        List<LimitCheck> hard = limits.stream().filter(LimitCheck::breached)
                .filter(check -> check.enforcement() == AccountObjectiveService.Enforcement.HARD).toList();
        if (hard.isEmpty()) return null;
        for (var close : closes) {
            if (close.snapshot() == null) continue;
            boolean restored = hard.stream().allMatch(check ->
                    limitValue(check.scope(), check.key(), close.snapshot()) <= check.maxCents());
            if (!restored) continue;
            return new ReductionProposal(close.quantityAffected(), close.quantityRemaining(), close.action(),
                    close.fingerprint(), hard.stream().map(check -> check.scope() + ":" + check.key()).toList(),
                    "Closing the minimum " + close.quantityAffected() + " package(s) restores every breached "
                            + "hard ceiling when the whole Book is recomputed.");
        }
        return null;
    }

    private static List<ActionAlternative> alternatives(
            List<BookActionProjectionService.ActionProjection> closes, List<LimitCheck> limits) {
        List<LimitCheck> hard = limits.stream().filter(LimitCheck::breached)
                .filter(check -> check.enforcement() == AccountObjectiveService.Enforcement.HARD).toList();
        return closes.stream().map(close -> {
            boolean restores = close.snapshot() != null && !hard.isEmpty() && hard.stream().allMatch(check ->
                    limitValue(check.scope(), check.key(), close.snapshot()) <= check.maxCents());
            return new ActionAlternative(close.action(), close.quantityAffected(), close.quantityRemaining(),
                    close.available(), close.fingerprint(), restores,
                    "Read-only whole-Book recomputation; selecting it still requires an explicit action preview.");
        }).toList();
    }

    private static long assignment(PositionLifecycleAnalysis lifecycle, OptionType type, boolean shares) {
        return lifecycle.assignmentExit().legs().stream().filter(leg -> leg.optionType() == type)
                .mapToLong(leg -> shares ? leg.shares() : leg.strikeDollarsCents()).sum();
    }

    private static boolean underRemainingThreshold(Long remaining,
                                                    ProtocolEvaluator.Policy policy) {
        return remaining != null && policy.harvestRemainingPremiumMaxCents() != null
                && remaining <= policy.harvestRemainingPremiumMaxCents();
    }

    private static long proportional(long amount, int quantity, int totalQuantity) {
        if (quantity == totalQuantity) return amount;
        return java.math.BigDecimal.valueOf(amount).multiply(java.math.BigDecimal.valueOf(quantity))
                .divide(java.math.BigDecimal.valueOf(totalQuantity), 0,
                        java.math.RoundingMode.HALF_UP).longValueExact();
    }

    private static Dimension dimension(String name, String status, Verdict signal, String reason) {
        return new Dimension(name, status, signal, List.of(reason));
    }

    private static VerdictSelection select(Verdict verdict, Dimension decisiveDimension) {
        return new VerdictSelection(verdict, decisiveDimension);
    }

    private static DecisionPresentation presentation(VerdictSelection selection,
                                                     List<Dimension> dimensions) {
        Verdict verdict = selection.verdict();
        Dimension decisive = selection.decisiveDimension();
        EvidenceState evidenceState = switch (verdict) {
            case NEEDS_EVIDENCE -> decisive != null && "MECHANICS".equals(decisive.name())
                    ? EvidenceState.CURRENT_MARK_UNAVAILABLE
                    : decisive != null && "FORWARD_ECONOMICS".equals(decisive.name())
                    ? EvidenceState.FORWARD_ECONOMICS_UNAVAILABLE
                    : EvidenceState.INSUFFICIENT;
            default -> dimensions.stream().anyMatch(dimension ->
                    "UNAVAILABLE".equals(dimension.status()))
                    ? EvidenceState.PARTIAL : EvidenceState.SUFFICIENT;
        };
        String userFacingVerdict = switch (verdict) {
            case KEEP -> "Keep";
            case HARVEST -> "Harvest";
            case REDUCE -> "Reduce";
            case DEFEND -> "Defend · action required";
            case ACCEPT_ASSIGNMENT -> "Accept assignment";
            case NEEDS_EVIDENCE -> switch (evidenceState) {
                case CURRENT_MARK_UNAVAILABLE -> "No verdict · executable close unavailable";
                case FORWARD_ECONOMICS_UNAVAILABLE -> "No verdict · forward economics unavailable";
                default -> "No verdict · evidence needed";
            };
        };
        String userFacingStatus = switch (verdict) {
            case KEEP -> "On plan";
            case HARVEST -> "Take profit";
            case REDUCE -> "Trim";
            case DEFEND -> "Action required";
            case ACCEPT_ASSIGNMENT -> "Assignment active";
            case NEEDS_EVIDENCE -> switch (evidenceState) {
                case CURRENT_MARK_UNAVAILABLE -> "Executable close unavailable";
                case FORWARD_ECONOMICS_UNAVAILABLE -> "Forward economics unavailable";
                default -> "Evidence needed";
            };
        };
        String tone = switch (verdict) {
            case DEFEND -> "CRITICAL";
            case NEEDS_EVIDENCE, REDUCE -> "WARNING";
            case HARVEST -> "OPPORTUNITY";
            case ACCEPT_ASSIGNMENT -> "ASSIGNMENT";
            case KEEP -> "POSITIVE";
        };
        int sortPriority = switch (verdict) {
            case DEFEND -> 0;
            case NEEDS_EVIDENCE -> 1;
            case REDUCE -> 2;
            case HARVEST -> 3;
            case ACCEPT_ASSIGNMENT -> 4;
            case KEEP -> 5;
        };
        return new DecisionPresentation(evidenceState, verdict != Verdict.NEEDS_EVIDENCE,
                userFacingVerdict, userFacingStatus, tone,
                trigger(verdict, decisive), sortPriority);
    }

    private static DecisionTrigger trigger(Verdict verdict, Dimension decisive) {
        if (decisive == null) return null;
        String key = decisive.name() + ":" + decisive.status();
        String code = switch (key) {
            case "MECHANICS:BLOCKED" -> "CURRENT_MARK_UNAVAILABLE";
            case "MECHANICAL_PROTOCOL:STOP_LOSS_TRIGGER" -> "STOP_LOSS";
            case "MECHANICAL_PROTOCOL:EXPIRY_TIME_TRIGGER" -> "EXPIRY_TIME_RULE";
            case "MECHANICAL_PROTOCOL:TAKE_PROFIT_TRIGGER" -> "TAKE_PROFIT";
            case "DECLARED_EXIT:PRICE_TARGET_CROSSED" -> "DECLARED_TARGET_CROSSED";
            case "INTENT_CAPACITY:INCOHERENT" -> "ASSIGNMENT_CAPACITY_CONFLICT";
            case "ACCOUNT_LIMITS:HARD_BREACH_UNRESOLVED" -> "HARD_ACCOUNT_LIMIT_UNRESOLVED";
            case "ACCOUNT_LIMITS:HARD_BREACH_RESTORABLE" -> "HARD_ACCOUNT_LIMIT_REDUCTION";
            case "FORWARD_ECONOMICS:ADVERSE" -> "ADVERSE_HOLD_VS_CLOSE_ECONOMICS";
            case "FORWARD_ECONOMICS:UNAVAILABLE" -> "FORWARD_ECONOMICS_UNAVAILABLE";
            case "TAIL_EVENT:EXPECTED_SHORTFALL_TRIGGER" -> "EXPECTED_SHORTFALL_LIMIT";
            case "TAIL_EVENT:CONFIRMED_EVENT_TRIGGER" -> "CONFIRMED_EVENT_RULE";
            case "TAIL_EVENT:TAIL_AND_EVENT_TRIGGER" -> "TAIL_AND_EVENT_RULES";
            case "CARRY_COMPENSATION:CAPTURED_PREMIUM_TRIGGER" -> "PROFIT_CAPTURE";
            case "CARRY_COMPENSATION:CHEAP_RISK_REMOVAL_TRIGGER" -> "CHEAP_RISK_REMOVAL";
            case "CARRY_COMPENSATION:CAPTURED_AND_CHEAP_RISK_REMOVAL_TRIGGER" ->
                    "PROFIT_CAPTURE_AND_CHEAP_RISK_REMOVAL";
            default -> verdict == Verdict.ACCEPT_ASSIGNMENT
                    ? "ASSIGNMENT_DECISION_ACTIVE" : "POLICY_TRIGGER";
        };
        String label = switch (key) {
            case "MECHANICS:BLOCKED" -> "Executable close unavailable";
            case "MECHANICAL_PROTOCOL:STOP_LOSS_TRIGGER" -> "Stop-loss line crossed";
            case "MECHANICAL_PROTOCOL:EXPIRY_TIME_TRIGGER" -> "Expiry time rule";
            case "MECHANICAL_PROTOCOL:TAKE_PROFIT_TRIGGER" -> "Take-profit line reached";
            case "DECLARED_EXIT:PRICE_TARGET_CROSSED" -> "Declared exit price crossed";
            case "INTENT_CAPACITY:INCOHERENT" -> "Assignment capacity conflicts with the declared intent";
            case "ACCOUNT_LIMITS:HARD_BREACH_UNRESOLVED" -> "Hard account limit cannot be restored";
            case "ACCOUNT_LIMITS:HARD_BREACH_RESTORABLE" -> "Hard account limit has a minimum reduction";
            case "FORWARD_ECONOMICS:ADVERSE" -> "Hold-vs-close economics favor closing";
            case "FORWARD_ECONOMICS:UNAVAILABLE" -> "Forward hold-vs-close economics unavailable";
            case "TAIL_EVENT:EXPECTED_SHORTFALL_TRIGGER" -> "Expected-shortfall limit crossed";
            case "TAIL_EVENT:CONFIRMED_EVENT_TRIGGER" -> "Confirmed issuer-event rule crossed";
            case "TAIL_EVENT:TAIL_AND_EVENT_TRIGGER" -> "Tail and confirmed-event rules crossed";
            case "CARRY_COMPENSATION:CAPTURED_PREMIUM_TRIGGER" -> "Profit-capture rule reached";
            case "CARRY_COMPENSATION:CHEAP_RISK_REMOVAL_TRIGGER" -> "Cheap risk-removal rule reached";
            case "CARRY_COMPENSATION:CAPTURED_AND_CHEAP_RISK_REMOVAL_TRIGGER" ->
                    "Profit-capture and cheap risk-removal rules reached";
            default -> verdict == Verdict.ACCEPT_ASSIGNMENT
                    ? "Assignment decision is active"
                    : "Named lifecycle policy trigger";
        };
        String basis = decisive.reasons().isEmpty() ? null : decisive.reasons().getLast();
        return new DecisionTrigger(code, label, decisive.name(), decisive.status(), basis);
    }

    private static BookActionProjectionService.ActionProjection action(
            BookActionProjectionService.ProjectionSet projections, String action) {
        return projections.actions().stream().filter(item -> action.equals(item.action()))
                .findFirst().orElse(null);
    }

    private static String summary(Verdict verdict, ReductionProposal reduction) {
        return switch (verdict) {
            case KEEP -> "KEEP under this named policy; economics, carry, event, and Book cautions remain separate.";
            case HARVEST -> "HARVEST under this named policy by paying the executable close cost; this is not a claim that collateral starts earning cash yield.";
            case REDUCE -> "REDUCE by " + (reduction == null ? "the policy-restoring quantity"
                    : reduction.quantityToClose() + " package(s)") + "; the full Book was recomputed at each quantity.";
            case DEFEND -> "DEFEND: resolve the named mechanical, capacity, hard-limit, or tail trigger before treating carry as permission to hold.";
            case ACCEPT_ASSIGNMENT -> "ACCEPT ASSIGNMENT is active near expiry and fits the exact declared share-and-dollar capacity; economics and Book risk remain visible.";
            case NEEDS_EVIDENCE -> "NO VERDICT: a required input for this decision is unavailable, so this position cannot be evaluated. Resolve the named missing input (see the dimension reasons) before any keep, defend, or harvest decision — no action is recommended.";
        };
    }

    private static void requireInputs(PositionLifecycleAnalysis lifecycle,
                                      BookActionProjectionService.ProjectionSet projections,
                                      AccountObjectiveService.CapacityContext capacity) {
        if (lifecycle == null || projections == null || capacity == null) {
            throw new IllegalArgumentException("lifecycle, Book actions, and capacity are required");
        }
        if (!lifecycle.positionFingerprint().equals(projections.positionFingerprint())) {
            throw new IllegalArgumentException("lifecycle and Book actions describe different packages");
        }
    }

    private static String clean(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        return value.trim().toUpperCase(Locale.ROOT);
    }


    private static UserDecision latestForPosition(Connection c, String owner, String accountId,
                                                  String positionFingerprint) throws java.sql.SQLException {
        List<UserDecision> rows = Db.queryOn(c, "SELECT d.id,d.analysis_id,d.decision,d.selected_action," +
                        "d.quantity,d.note,d.decided_at FROM position_lifecycle_user_decision d " +
                        "JOIN position_lifecycle_analysis r ON r.id=d.analysis_id " +
                        "WHERE r.user_id=? AND r.portfolio_account_id=? AND r.position_fingerprint=? " +
                        "ORDER BY d.decided_at DESC,d.id DESC LIMIT 1",
                row -> {
                    Long quantity = row.lngOrNull("quantity");
                    return new UserDecision(row.str("id"), row.str("analysis_id"),
                            Verdict.valueOf(row.str("decision")), row.str("selected_action"),
                            quantity == null ? null : Math.toIntExact(quantity),
                            row.str("note"), row.odt("decided_at"));
                },
                owner, accountId, positionFingerprint);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static String fingerprint(Object... values) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Json.stable(java.util.Arrays.asList(values))
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to fingerprint lifecycle decision result", e);
        }
    }

    private record FrozenAnalysis(PositionLifecycleAnalysis lifecycle,
                                 BookActionProjectionService.ProjectionSet actions) {}
}
