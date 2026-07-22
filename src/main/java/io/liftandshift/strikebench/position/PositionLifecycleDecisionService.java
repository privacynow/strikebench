package io.liftandshift.strikebench.position;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.paper.AccountObjectiveService;
import io.liftandshift.strikebench.paper.BookActionProjectionService;
import io.liftandshift.strikebench.paper.BookRiskService;
import io.liftandshift.strikebench.util.Ids;
import io.liftandshift.strikebench.util.Json;
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
 * Surfaced receipts and personal decisions are append-only calibration evidence, not account
 * transactions and not inputs that can alter EV.
 */
public final class PositionLifecycleDecisionService {
    public static final String SCHEMA_VERSION = "position-lifecycle-decision-v1";
    private static final Set<String> ACTIONS = Set.of("HOLD", "CLOSE_ONE", "CLOSE_K", "CLOSE_ALL",
            "ASSIGNMENT", "CALL_AWAY", "ROLL", "NO_ACTION");

    public enum Verdict { KEEP, HARVEST, REDUCE, DEFEND, ACCEPT_ASSIGNMENT }

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
            AccountObjectiveService.LifecyclePolicy policy,
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
    public record UserDecision(String id, String receiptId, Verdict decision,
                               String selectedAction, Integer quantity, String note,
                               OffsetDateTime decidedAt) {}

    public record DecisionInput(String receiptId, String decision, String selectedAction,
                                Integer quantity, String note) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SurfacedReceipt(String receiptId, String receiptFingerprint,
                                  DecisionAnalysis analysis, UserDecision latestUserDecision) {}

    private record CapacityResult(Dimension dimension, boolean assignmentActive) {}

    private final Db db;
    private final Clock clock;

    public PositionLifecycleDecisionService(Db db, Clock clock) {
        this.db = db;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** Pure policy composition. No persistence and no market/account mutation. */
    public DecisionAnalysis analyze(PositionLifecycleReceipt lifecycle,
                                    BookActionProjectionService.ProjectionSet projections,
                                    AccountObjectiveService.CapacityContext capacity) {
        requireInputs(lifecycle, projections, capacity);
        var policy = capacity.accountPolicy() == null
                ? AccountObjectiveService.LifecyclePolicy.standard()
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
        CapacityResult capacityResult = capacity(lifecycle, capacity, policy);
        Dimension accountLimits = accountLimits(limits, reduction);
        Dimension economics = economics(lifecycle);
        Dimension tailAndEvents = tailAndEvents(lifecycle, hold, policy);
        Dimension carry = carry(lifecycle, policy);
        Dimension history = history(lifecycle);
        List<Dimension> dimensions = List.of(mechanics, capacityResult.dimension(), accountLimits,
                economics, tailAndEvents, carry, history);

        Verdict verdict;
        if (mechanics.policySignal() != null) verdict = mechanics.policySignal();
        else if (capacityResult.dimension().policySignal() == Verdict.DEFEND) verdict = Verdict.DEFEND;
        else if (accountLimits.policySignal() != null) verdict = accountLimits.policySignal();
        else if (capacityResult.assignmentActive()) verdict = Verdict.ACCEPT_ASSIGNMENT;
        else if (economics.policySignal() == Verdict.HARVEST) verdict = Verdict.HARVEST;
        else if (tailAndEvents.policySignal() != null) verdict = tailAndEvents.policySignal();
        else if (carry.policySignal() == Verdict.HARVEST) verdict = Verdict.HARVEST;
        else verdict = Verdict.KEEP;

        List<ActionAlternative> alternatives = alternatives(closes, limits);
        String projectionFingerprint = fingerprint(projections.actions().stream()
                .map(BookActionProjectionService.ActionProjection::fingerprint).toList());
        return new DecisionAnalysis(SCHEMA_VERSION, lifecycle.positionFingerprint(),
                lifecycle.evidence().observedAt(), verdict, summary(verdict, reduction), policy,
                policyFingerprint, lifecycle.evidence().marketSnapshotFingerprint(),
                lifecycle.evidence().modelFingerprint(), capacity.objectiveRevisionId(),
                capacity.declarationFingerprint(), projectionFingerprint, dimensions, limits,
                reduction, alternatives,
                "Precedence: executable mechanics; declared quantity capacity; hard account ceilings; "
                        + "after-cost hold-vs-close economics; tail/event risk; carry compensation; history as "
                        + "context only. Existing evaluators, marks, accounting, and BookRiskService remain the "
                        + "calculation owners. This policy can change fit or action, never EV.");
    }

    /** Compose and append the exact receipt that was surfaced. Account holdings remain untouched. */
    public SurfacedReceipt surface(String userId, String accountId,
                                   PositionLifecycleReceipt lifecycle,
                                   BookActionProjectionService.ProjectionSet projections,
                                   AccountObjectiveService.CapacityContext capacity) {
        DecisionAnalysis analysis = analyze(lifecycle, projections, capacity);
        String receiptFingerprint = fingerprint(lifecycle, projections, capacity, analysis);
        String receiptId = Ids.newId("pldr");
        OffsetDateTime surfacedAt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return db.tx(c -> {
            String owner = OwnerScope.ensure(c, userId);
            requireOwnedAccount(c, owner, accountId);
            Db.execOn(c, "INSERT INTO position_lifecycle_decision_receipt(" +
                            "id,user_id,portfolio_account_id,position_fingerprint,receipt_fingerprint," +
                            "policy_id,policy_fingerprint,market_snapshot_fingerprint,model_fingerprint," +
                            "account_objective_revision_id,declaration_fingerprint,verdict,lifecycle_json," +
                            "book_actions_json,capacity_json,decision_json,surfaced_at) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?)",
                    receiptId, owner, accountId, lifecycle.positionFingerprint(), receiptFingerprint,
                    analysis.policy().policyId(), analysis.policyFingerprint(),
                    analysis.marketSnapshotFingerprint(), analysis.modelFingerprint(),
                    analysis.objectiveRevisionId(), analysis.declarationFingerprint(), analysis.verdict().name(),
                    Json.canonical(lifecycle), Json.canonical(projections), Json.canonical(capacity),
                    Json.canonical(analysis), surfacedAt);
            return new SurfacedReceipt(receiptId, receiptFingerprint, analysis,
                    latestForPosition(c, owner, accountId, lifecycle.positionFingerprint()));
        });
    }

    /** Append a personal decision beside the frozen engine receipt; never overwrite either. */
    public UserDecision recordUserDecision(String userId, String accountId, DecisionInput input) {
        if (input == null || input.receiptId() == null || input.receiptId().isBlank()) {
            throw new IllegalArgumentException("lifecycle receipt id is required");
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
            requireOwnedAccount(c, owner, accountId);
            List<String> frozenActions = Db.queryOn(c,
                    "SELECT book_actions_json::text actions FROM position_lifecycle_decision_receipt " +
                            "WHERE id=? AND user_id=? AND portfolio_account_id=?",
                    r -> r.str("actions"), input.receiptId(), owner, accountId);
            if (frozenActions.isEmpty()) {
                throw new io.liftandshift.strikebench.util.ResourceNotFoundException(
                        "No lifecycle receipt " + input.receiptId() + " in this account");
            }
            if (selected != null && selected.startsWith("CLOSE_")) {
                boolean exactAction = java.util.stream.StreamSupport.stream(
                                Json.parse(frozenActions.getFirst()).withArray("actions").spliterator(), false)
                        .anyMatch(action -> selected.equals(action.path("action").asText())
                                && input.quantity() == action.path("quantityAffected").asInt()
                                && action.path("available").asBoolean());
                if (!exactAction) {
                    throw new IllegalArgumentException(
                            "selected close action and quantity are not available in the frozen receipt");
                }
            }
            Db.execOn(c, "INSERT INTO position_lifecycle_user_decision(" +
                            "id,receipt_id,user_id,decision,selected_action,quantity,note,decided_at) " +
                            "VALUES(?,?,?,?,?,?,?,?)",
                    id, input.receiptId(), owner, decision.name(), selected, input.quantity(), note, at);
            return new UserDecision(id, input.receiptId(), decision, selected, input.quantity(), note, at);
        });
    }

    private static Dimension mechanics(PositionLifecycleReceipt lifecycle) {
        var close = lifecycle.currentChoice().close();
        return close.executable()
                ? dimension("MECHANICS", "PASS", null,
                "Executable close evidence is available at the opposite book side with fees.")
                : dimension("MECHANICS", "BLOCKED", Verdict.DEFEND,
                "The position cannot be responsibly managed from the current book: "
                        + close.unavailableReason());
    }

    private static CapacityResult capacity(PositionLifecycleReceipt lifecycle,
                                           AccountObjectiveService.CapacityContext context,
                                           AccountObjectiveService.LifecyclePolicy policy) {
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
                    .map(PositionLifecycleReceipt.AssignmentLeg::freshEyesEffectivePricePerShareCents)
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
        Integer dte = lifecycle.carryCollateral().calendarDaysRemaining();
        boolean assignmentActive = !inadequate && exact != null && putShares > 0 && dte != null
                && dte <= policy.assignmentDecisionDte()
                && exact.acceptedAssignmentShares() != null && exact.acceptedAssignmentShares() >= putShares
                && exact.acceptedAssignmentDollarsCents() != null
                && exact.acceptedAssignmentDollarsCents() >= putDollars;
        if (assignmentActive) reasons.add("Assignment is now an active near-expiry decision under the named DTE threshold.");
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

    private static Dimension economics(PositionLifecycleReceipt lifecycle) {
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

    private static Dimension tailAndEvents(PositionLifecycleReceipt lifecycle,
                                           BookActionProjectionService.ActionProjection hold,
                                           AccountObjectiveService.LifecyclePolicy policy) {
        List<String> reasons = new ArrayList<>();
        boolean confirmed = lifecycle.assignmentExit().eventCrossings().stream()
                .anyMatch(event -> "CONFIRMED".equals(event.status()));
        if (confirmed) reasons.add("The package crosses confirmed issuer event evidence.");
        else if (!lifecycle.assignmentExit().eventCrossings().isEmpty()) {
            reasons.add("The package crosses estimated issuer event evidence; it is not promoted to confirmed.");
        } else reasons.add("No event crossing is present in the canonical event receipt.");
        Long shortfall = lifecycle.currentChoice().expectedShortfallCents();
        boolean tailBreach = policy.expectedShortfallDefendCents() != null && shortfall != null
                && shortfall >= policy.expectedShortfallDefendCents();
        if (shortfall != null) reasons.add("Expected shortfall receipt: " + shortfall + " cents ("
                + lifecycle.currentChoice().expectedShortfallBasis() + ").");
        if (hold != null && hold.snapshot() != null && hold.snapshot().risk() != null) {
            var risk = hold.snapshot().risk();
            if (risk.themes().concentrationCallout() != null) reasons.add(risk.themes().concentrationCallout());
            if (risk.expiries().clusterNote() != null) reasons.add(risk.expiries().clusterNote());
            reasons.addAll(risk.collisions());
        }
        boolean defend = tailBreach || (confirmed && policy.defendConfirmedEvents());
        if (tailBreach) reasons.add("Expected shortfall crosses the named policy's defense threshold.");
        if (confirmed && policy.defendConfirmedEvents()) {
            reasons.add("This named policy requires defense for a confirmed event crossing.");
        }
        return new Dimension("TAIL_EVENT", defend ? "DEFEND_TRIGGER" : confirmed ? "CAUTION" : "PASS",
                defend ? Verdict.DEFEND : null, reasons);
    }

    private static Dimension carry(PositionLifecycleReceipt lifecycle,
                                   AccountObjectiveService.LifecyclePolicy policy) {
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
        long closeCost = close.signedNetCloseCashCents() == null ? Long.MAX_VALUE
                : Math.max(0, -close.signedNetCloseCashCents());
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
        return new Dimension("CARRY_COMPENSATION", harvest ? "HARVEST_TRIGGER" : "CONTEXT",
                harvest ? Verdict.HARVEST : null, reasons);
    }

    private static Dimension history(PositionLifecycleReceipt lifecycle) {
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
        List<LimitCheck> out = new ArrayList<>();
        for (var ceiling : policy.symbolCeilings()) out.add(limit("SYMBOL", ceiling.key(),
                ceiling.maxCents(), ceiling.enforcement(), hold, closes));
        for (var ceiling : policy.themeCeilings()) out.add(limit("THEME", ceiling.key(),
                ceiling.maxCents(), ceiling.enforcement(), hold, closes));
        for (var ceiling : policy.expiryCeilings()) out.add(limit("EXPIRY", ceiling.key(),
                ceiling.maxCents(), ceiling.enforcement(), hold, closes));
        if (policy.encumbranceCeiling() != null) out.add(limit("ENCUMBRANCE", "ACCOUNT",
                policy.encumbranceCeiling().maxCents(), policy.encumbranceCeiling().enforcement(),
                hold, closes));
        return List.copyOf(out);
    }

    private static LimitCheck limit(String scope, String key, long max,
                                    AccountObjectiveService.Enforcement enforcement,
                                    BookActionProjectionService.ActionProjection hold,
                                    List<BookActionProjectionService.ActionProjection> closes) {
        long current = limitValue(scope, key, hold.snapshot());
        Integer restored = closes.stream().filter(close -> close.snapshot() != null)
                .filter(close -> limitValue(scope, key, close.snapshot()) <= max)
                .map(BookActionProjectionService.ActionProjection::quantityAffected).findFirst().orElse(null);
        return new LimitCheck(scope, key, max, enforcement, current, current > max, restored,
                "Values come from each canonical BookRiskService/PortfolioAccountingService hypothetical snapshot.");
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

    private static long assignment(PositionLifecycleReceipt lifecycle, OptionType type, boolean shares) {
        return lifecycle.assignmentExit().legs().stream().filter(leg -> leg.optionType() == type)
                .mapToLong(leg -> shares ? leg.shares() : leg.strikeDollarsCents()).sum();
    }

    private static boolean underRemainingThreshold(Long remaining,
                                                    AccountObjectiveService.LifecyclePolicy policy) {
        return remaining != null && policy.harvestRemainingPremiumMaxCents() != null
                && remaining <= policy.harvestRemainingPremiumMaxCents();
    }

    private static Dimension dimension(String name, String status, Verdict signal, String reason) {
        return new Dimension(name, status, signal, List.of(reason));
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
        };
    }

    private static void requireInputs(PositionLifecycleReceipt lifecycle,
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

    private static void requireOwnedAccount(Connection c, String owner, String accountId)
            throws java.sql.SQLException {
        if (Db.queryOn(c, "SELECT 1 ok FROM portfolio_account WHERE id=? AND user_id=?",
                r -> r.intv("ok"), accountId, owner).isEmpty()) {
            throw new io.liftandshift.strikebench.util.ResourceNotFoundException(
                    "No tracked portfolio account " + accountId);
        }
    }

    private static UserDecision latestForPosition(Connection c, String owner, String accountId,
                                                  String positionFingerprint) throws java.sql.SQLException {
        List<UserDecision> rows = Db.queryOn(c, "SELECT d.id,d.receipt_id,d.decision,d.selected_action," +
                        "d.quantity,d.note,d.decided_at FROM position_lifecycle_user_decision d " +
                        "JOIN position_lifecycle_decision_receipt r ON r.id=d.receipt_id " +
                        "WHERE r.user_id=? AND r.portfolio_account_id=? AND r.position_fingerprint=? " +
                        "ORDER BY d.decided_at DESC,d.id DESC LIMIT 1",
                row -> {
                    Long quantity = row.lngOrNull("quantity");
                    return new UserDecision(row.str("id"), row.str("receipt_id"),
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
                    .digest(Json.canonical(java.util.Arrays.asList(values))
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to fingerprint lifecycle decision receipt", e);
        }
    }
}
