package io.liftandshift.strikebench.db;

import com.fasterxml.jackson.databind.JsonNode;
import io.liftandshift.strikebench.market.MarketLane;
import io.liftandshift.strikebench.market.Universes;
import io.liftandshift.strikebench.model.Symbol;
import io.liftandshift.strikebench.position.PositionDomain;
import io.liftandshift.strikebench.recommend.RecommendationEngine;
import io.liftandshift.strikebench.util.Json;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * THE one workspace context (audit §6, program §8.1). Home declarations, market focus, sector
 * scope, Decide state, Book state and route state were six independent frontend globals; that is
 * why Import Trade wiped goal/view/horizon/risk plus the selected world, and why Analyze-in-New-Idea
 * opened a blank declaration. One versioned, persisted record owns them now.
 *
 * <p>Three rules the shape enforces rather than documents:
 *
 * <ul>
 *   <li><b>Absent is absent (§3.5).</b> Every declaration is nullable and nothing here ever
 *       substitutes a default. An undeclared goal stays undeclared through every read, merge and
 *       world transition; only {@link Patch#clear()} un-declares, and only explicitly.</li>
 *   <li><b>The market owns its own artifacts.</b> {@link #MARKET_OWNED} fields name things that
 *       existed in one specific market (a symbol from that world's symbol set, an account, a
 *       package priced there). A world change clears them in the same commit that stamps the new
 *       world — see {@link #inWorld}. {@link #DECLARATIONS} are user intent and survive.</li>
 *   <li><b>Server facts are server-stamped.</b> {@code version}, {@code generation}, {@code world},
 *       {@code marketLane} and {@code accountId} are never taken from the request body. A browser
 *       cannot declare which market it is in, so a header cannot say Observed while the body says
 *       Demo.</li>
 * </ul>
 */
public record WorkspaceContext(
        int version,
        long generation,
        String world,
        String datasetId,
        String marketLane,
        String accountId,
        String scopeType,
        String sectorKey,
        String focusedSubject,
        String focusedSymbol,
        String focusedPositionId,
        String focusedIdeaId,
        String focusedEvaluationId,
        String goal,
        String view,
        Integer horizonDays,
        String riskPosture,
        Long targetCents,
        Long shareQuantity,
        String assignmentPreference,
        String routeState,
        Focus returnFocus) {

    /** The only shape this build can read or write. Anything else is refused with a reason. */
    public static final int CURRENT_VERSION = 1;

    /** What the workspace is scoped to. Program §8.1 {@code scopeType}; audit §6 "scope". */
    public enum Scope { BROAD_MARKET, ACTIVE_UNIVERSE, SECTOR, SYMBOL }

    /** What the desk is looking at. Audit §6 "focused subject". */
    public enum Subject { BOOK, POSITION, PACKAGE, MARKET }

    /** Demo and simulated worlds describe themselves as one sector (see MarketUniverseView). */
    public static final String WORLD_SECTOR_KEY = "world";

    private static final int MAX_ID = 128;
    private static final int MAX_ROUTE = 256;

    /**
     * Market-owned: cleared by a world change because the referent belonged to the market the user
     * left — that world's symbol set, that world's account, a package priced against it.
     */
    public static final List<String> MARKET_OWNED = List.of(
            "scopeType", "sectorKey", "focusedSubject", "focusedSymbol", "focusedPositionId",
            "focusedIdeaId", "focusedEvaluationId", "targetCents", "shareQuantity",
            "routeState", "returnFocus");

    /**
     * Declarations: user intent, valid in any market, and therefore preserved by every transition.
     * Destroying these is precisely the Import Trade defect the audit recorded (§6).
     */
    public static final List<String> DECLARATIONS = List.of(
            "goal", "view", "horizonDays", "riskPosture", "assignmentPreference");

    /** Every field a client may declare — and the only legal members of {@link Patch#clear()}. */
    public static final Set<String> CLIENT_FIELDS = clientFields();

    /** The caller's authoritative market. Resolved per request; never taken from a request body. */
    public record ActiveMarket(String world, String datasetId, String lane, String accountId) {
        /** Compatibility constructor for callers that explicitly mean the observed dataset. */
        public ActiveMarket(String world, String lane, String accountId) {
            this(world, DatasetService.OBSERVED, lane, accountId);
        }
    }

    /** Prior focus, so Back returns where the user came from instead of a default Home (audit §6). */
    public record Focus(String subject, String symbol, String positionId, String ideaId,
                        String evaluationId, String scopeType, String sectorKey,
                        Long targetCents, Long shareQuantity, String routeState) {
        /** Existing stored version-1 receipts omit target/quantity and remain readable. */
        public Focus(String subject, String symbol, String positionId, String ideaId,
                     String evaluationId, String scopeType, String sectorKey, String routeState) {
            this(subject, symbol, positionId, ideaId, evaluationId, scopeType, sectorKey,
                    null, null, routeState);
        }
    }

    /**
     * A partial write. Omitted fields RETAIN their stored value; only {@code clear} un-declares.
     * This is the same grammar as {@code Plan.ContextUpdateRequest}, deliberately — one merge
     * contract across the product.
     *
     * <p>The {@code expected*} members are optimistic guards, not declarations. The complete market
     * identity is required because a dataset or account can change while the world token stays the
     * same, and an undeclared workspace legitimately has revision zero on both sides of that change.
     * A late response from the prior identity must therefore be rejected even when {@code world}
     * and {@code expectedRev} happen to match.
     */
    public record Patch(Integer version, String world, Long expectedRev,
                        String expectedDatasetId, String expectedMarketLane,
                        String expectedAccountId, Long expectedGeneration,
                        String scopeType, String sectorKey,
                        String focusedSubject, String focusedSymbol, String focusedPositionId,
                        String focusedIdeaId, String focusedEvaluationId,
                        String goal, String view, Integer horizonDays, String riskPosture,
                        Long targetCents, Long shareQuantity, String assignmentPreference,
                        String routeState, Focus returnFocus, Set<String> clear) {}

    /** Why a stored blob was not read. Reported verbatim; the row is left intact. */
    public record Unreadable(Integer storedVersion, int supportedVersion, String reason) {}

    /** The result of reading a stored blob: exactly one of context / unreadable is present. */
    public record Stored(WorkspaceContext context, Unreadable unreadable) {
        public boolean readable() { return context != null; }
    }

    /** What a world change did, so the desk can say it out loud instead of losing state silently. */
    public record Transition(String fromWorld, String toWorld, List<String> cleared, String reason) {}

    /** A context already moved into the active market, plus the receipt (null when nothing moved). */
    public record WorldCommit(WorkspaceContext context, Transition transition) {}

    /** A context that declares nothing but the market it belongs to. */
    public static WorkspaceContext empty(ActiveMarket market) {
        requireMarket(market);
        return new WorkspaceContext(CURRENT_VERSION, 1L, market.world(), market.datasetId(), market.lane(),
                blankToNull(market.accountId()), null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    /**
     * Reads a stored blob. Either the whole context is understood, or nothing is taken from it and
     * the refusal states why — a version this build does not know is never partially read.
     */
    public static Stored read(String json) {
        if (json == null || json.isBlank()) {
            return new Stored(null, new Unreadable(null, CURRENT_VERSION,
                    "the stored workspace was empty; nothing was restored"));
        }
        JsonNode node;
        try {
            node = Json.parse(json);
        } catch (RuntimeException e) {
            return new Stored(null, new Unreadable(null, CURRENT_VERSION,
                    "the stored workspace is not readable JSON; it was not read"));
        }
        if (!node.isObject()) {
            return new Stored(null, new Unreadable(null, CURRENT_VERSION,
                    "the stored workspace is not a JSON object; it was not read"));
        }
        JsonNode versionNode = node.get("version");
        if (versionNode == null || !versionNode.isIntegralNumber()) {
            return new Stored(null, new Unreadable(null, CURRENT_VERSION,
                    "the stored workspace predates the versioned workspace context (it declares no "
                            + "version); it was not read, and nothing was taken from it"));
        }
        int storedVersion = versionNode.intValue();
        if (storedVersion != CURRENT_VERSION) {
            String direction = storedVersion > CURRENT_VERSION
                    ? "was written by a newer build than this one"
                    : "is older than this build and has no upgrade path";
            return new Stored(null, new Unreadable(storedVersion, CURRENT_VERSION,
                    "stored workspace context version " + storedVersion + " " + direction
                            + " (this build reads version " + CURRENT_VERSION + "); it was not read"));
        }
        try {
            return new Stored(Json.MAPPER.treeToValue(node, WorkspaceContext.class), null);
        } catch (Exception e) {
            return new Stored(null, new Unreadable(storedVersion, CURRENT_VERSION,
                    "stored workspace context version " + storedVersion
                            + " could not be read (" + e.getClass().getSimpleName() + "); it was not read"));
        }
    }

    public String toJson() {
        return Json.write(this);
    }

    /**
     * Applies a partial write. Every field the patch omits keeps its stored value; a field named in
     * {@code clear} becomes undeclared. Nothing else is touched — that is the Import Trade fix.
     */
    public WorkspaceContext merge(Patch patch) {
        if (patch == null) throw new IllegalArgumentException("a workspace patch body is required");
        requireVersion(patch.version());
        Set<String> clear = normalizedClear(patch.clear());
        return new WorkspaceContext(version, generation, world, datasetId, marketLane, accountId,
                merged("scopeType", patch.scopeType(), scopeType, clear),
                merged("sectorKey", patch.sectorKey(), sectorKey, clear),
                merged("focusedSubject", patch.focusedSubject(), focusedSubject, clear),
                merged("focusedSymbol", patch.focusedSymbol(), focusedSymbol, clear),
                merged("focusedPositionId", patch.focusedPositionId(), focusedPositionId, clear),
                merged("focusedIdeaId", patch.focusedIdeaId(), focusedIdeaId, clear),
                merged("focusedEvaluationId", patch.focusedEvaluationId(), focusedEvaluationId, clear),
                merged("goal", patch.goal(), goal, clear),
                merged("view", patch.view(), view, clear),
                merged("horizonDays", patch.horizonDays(), horizonDays, clear),
                merged("riskPosture", patch.riskPosture(), riskPosture, clear),
                merged("targetCents", patch.targetCents(), targetCents, clear),
                merged("shareQuantity", patch.shareQuantity(), shareQuantity, clear),
                merged("assignmentPreference", patch.assignmentPreference(), assignmentPreference, clear),
                merged("routeState", patch.routeState(), routeState, clear),
                merged("returnFocus", patch.returnFocus(), returnFocus, clear));
    }

    /**
     * A full replace: the request declares every client-owned field, so anything it omits becomes
     * undeclared. Server-stamped facts (version, generation, world, lane, account) are kept.
     */
    public WorkspaceContext replacedWith(WorkspaceContext requested) {
        if (requested == null) throw new IllegalArgumentException("a workspace context body is required");
        requireVersion(requested.version());
        return new WorkspaceContext(version, generation, world, datasetId, marketLane, accountId,
                requested.scopeType(), requested.sectorKey(), requested.focusedSubject(),
                requested.focusedSymbol(), requested.focusedPositionId(), requested.focusedIdeaId(),
                requested.focusedEvaluationId(), requested.goal(), requested.view(),
                requested.horizonDays(), requested.riskPosture(), requested.targetCents(),
                requested.shareQuantity(), requested.assignmentPreference(), requested.routeState(),
                requested.returnFocus());
    }

    /**
     * Moves this context into the caller's active market. When the world changed, every
     * market-owned field is cleared and the generation advances IN THE SAME VALUE that carries the
     * new world — a caller can never observe the new world beside the old world's focus, because
     * no such value exists. Declarations are carried across untouched.
     */
    public WorldCommit inWorld(ActiveMarket market) {
        requireMarket(market);
        String target = market.world();
        String dataset = blankToNull(market.datasetId());
        String lane = blankToNull(market.lane());
        String account = blankToNull(market.accountId());
        if (target.equals(world) && Objects.equals(dataset, datasetId)
                && Objects.equals(account, accountId)) {
            if (Objects.equals(lane, marketLane)) {
                return new WorldCommit(this, null);
            }
            return new WorldCommit(new WorkspaceContext(version, generation, world, dataset, lane, account,
                    scopeType, sectorKey, focusedSubject, focusedSymbol, focusedPositionId,
                    focusedIdeaId, focusedEvaluationId, goal, view, horizonDays, riskPosture,
                    targetCents, shareQuantity, assignmentPreference, routeState, returnFocus), null);
        }
        List<String> cleared = new ArrayList<>();
        if (scopeType != null) cleared.add("scopeType");
        if (sectorKey != null) cleared.add("sectorKey");
        if (focusedSubject != null) cleared.add("focusedSubject");
        if (focusedSymbol != null) cleared.add("focusedSymbol");
        if (focusedPositionId != null) cleared.add("focusedPositionId");
        if (focusedIdeaId != null) cleared.add("focusedIdeaId");
        if (focusedEvaluationId != null) cleared.add("focusedEvaluationId");
        if (targetCents != null) cleared.add("targetCents");
        if (shareQuantity != null) cleared.add("shareQuantity");
        if (routeState != null) cleared.add("routeState");
        if (returnFocus != null) cleared.add("returnFocus");
        WorkspaceContext moved = new WorkspaceContext(CURRENT_VERSION, generation + 1, target, dataset, lane,
                account, null, null, null, null, null, null, null,
                goal, view, horizonDays, riskPosture, null, null, assignmentPreference, null, null);
        return new WorldCommit(moved, new Transition(world, target, List.copyOf(cleared),
                transitionReason(world, datasetId, target, dataset, cleared)));
    }

    /**
     * Normalizes and checks every declared field, or refuses with a reason that names the field.
     * Coherence is checked too: a SECTOR scope without a sector, or a POSITION subject without a
     * position, is a half-declared context and is rejected rather than stored and later guessed at.
     */
    public WorkspaceContext validated() {
        requireVersion(version);
        if (blankToNull(world) == null) {
            throw new IllegalArgumentException("a workspace context must name the market world it belongs to");
        }
        String lane = blankToNull(marketLane);
        if (lane == null || Arrays.stream(MarketLane.values()).noneMatch(l -> l.name().equals(lane))) {
            throw new IllegalArgumentException("marketLane must be one of "
                    + names(Arrays.stream(MarketLane.values()).map(Enum::name).toList()));
        }
        String scope = token("scopeType", scopeType, names(Scope.class));
        String subject = token("focusedSubject", focusedSubject, names(Subject.class));
        String sector = sector(sectorKey);
        String symbol = symbol(focusedSymbol);
        String positionId = id("focusedPositionId", focusedPositionId);
        String ideaId = id("focusedIdeaId", focusedIdeaId);
        String evaluationId = id("focusedEvaluationId", focusedEvaluationId);
        String objective = token("goal", goal, names(PositionDomain.Objective.class));
        String direction = token("view", view, names(PositionDomain.Direction.class));
        String risk = token("riskPosture", riskPosture, names(RecommendationEngine.RiskMode.class));
        String assignment = token("assignmentPreference", assignmentPreference,
                names(PositionDomain.AssignmentPreference.class));
        if (horizonDays != null && horizonDays <= 0) {
            throw new IllegalArgumentException("horizonDays must be a positive number of days");
        }
        if (targetCents != null && targetCents <= 0) {
            throw new IllegalArgumentException("targetCents must be positive");
        }
        if (shareQuantity != null && shareQuantity < 0) {
            throw new IllegalArgumentException("shareQuantity cannot be negative");
        }
        String route = text("routeState", routeState, MAX_ROUTE);
        Focus back = focus(returnFocus);
        if (Scope.SECTOR.name().equals(scope) && sector == null) {
            throw new IllegalArgumentException(
                    "a SECTOR scope must name its sectorKey — clear scopeType, or declare the sector");
        }
        if (Scope.SYMBOL.name().equals(scope) && symbol == null) {
            throw new IllegalArgumentException(
                    "a SYMBOL scope must name focusedSymbol — clear scopeType, or declare the symbol");
        }
        if (Subject.POSITION.name().equals(subject) && positionId == null) {
            throw new IllegalArgumentException(
                    "a POSITION subject must name focusedPositionId — the held identity is the point");
        }
        if (Subject.PACKAGE.name().equals(subject) && ideaId == null && evaluationId == null) {
            // A Scout row is a proposed package before its Plan exists, so either identity will do
            // — but "a package" with no identity at all is how the exact package got lost (§8.2).
            throw new IllegalArgumentException("a PACKAGE subject must name the proposed package: "
                    + "focusedIdeaId (its Plan) or focusedEvaluationId (the exact scanned evaluation)");
        }
        return new WorkspaceContext(CURRENT_VERSION, generation, world.trim(),
                id("datasetId", datasetId), lane,
                id("accountId", accountId), scope, sector, subject, symbol, positionId, ideaId,
                evaluationId, objective, direction, horizonDays, risk, targetCents, shareQuantity,
                assignment, route, back);
    }

    private static String transitionReason(String from, String fromDataset,
                                           String to, String toDataset,
                                           List<String> cleared) {
        StringBuilder reason = new StringBuilder("the active market identity changed from ")
                .append(from == null ? "an unnamed market" : from)
                .append(" / ").append(fromDataset == null ? "an unnamed dataset" : fromDataset)
                .append(" to ").append(to).append(" / ").append(toDataset);
        if (cleared.isEmpty()) {
            return reason.append("; nothing market-owned was declared, so nothing was cleared").toString();
        }
        return reason.append("; focus that belonged to the previous market was cleared (")
                .append(String.join(", ", cleared))
                .append("). Declarations are not market facts and were kept.").toString();
    }

    private static <T> T merged(String key, T supplied, T current, Set<String> clear) {
        return clear.contains(key) ? null : supplied == null ? current : supplied;
    }

    private Set<String> normalizedClear(Set<String> raw) {
        if (raw == null || raw.isEmpty()) return Set.of();
        Set<String> clear = new LinkedHashSet<>();
        for (String key : raw) {
            String name = key == null ? "" : key.trim();
            if (!CLIENT_FIELDS.contains(name)) {
                throw new IllegalArgumentException("cannot clear unknown workspace field '" + name
                        + "'; clearable fields are " + names(List.copyOf(CLIENT_FIELDS)));
            }
            clear.add(name);
        }
        return clear;
    }

    private static void requireVersion(Integer declared) {
        if (declared == null || declared == 0) {
            throw new IllegalArgumentException("the workspace context must declare version "
                    + CURRENT_VERSION);
        }
        if (declared != CURRENT_VERSION) {
            throw new IllegalArgumentException("this build reads workspace context version "
                    + CURRENT_VERSION + "; the request declared version " + declared);
        }
    }

    private static void requireMarket(ActiveMarket market) {
        if (market == null || blankToNull(market.world()) == null
                || blankToNull(market.datasetId()) == null
                || blankToNull(market.lane()) == null
                || blankToNull(market.accountId()) == null) {
            throw new IllegalArgumentException(
                    "the caller's active market world, dataset, lane, and account are required");
        }
    }

    private Focus focus(Focus raw) {
        if (raw == null) return null;
        Focus back = new Focus(token("returnFocus.subject", raw.subject(), names(Subject.class)),
                symbol(raw.symbol()), id("returnFocus.positionId", raw.positionId()),
                id("returnFocus.ideaId", raw.ideaId()), id("returnFocus.evaluationId", raw.evaluationId()),
                token("returnFocus.scopeType", raw.scopeType(), names(Scope.class)),
                sector(raw.sectorKey()), raw.targetCents(), raw.shareQuantity(),
                text("returnFocus.routeState", raw.routeState(), MAX_ROUTE));
        if (back.targetCents() != null && back.targetCents() <= 0) {
            throw new IllegalArgumentException("returnFocus.targetCents must be positive");
        }
        if (back.shareQuantity() != null && back.shareQuantity() < 0) {
            throw new IllegalArgumentException("returnFocus.shareQuantity cannot be negative");
        }
        boolean empty = back.subject() == null && back.symbol() == null && back.positionId() == null
                && back.ideaId() == null && back.evaluationId() == null && back.scopeType() == null
                && back.sectorKey() == null && back.targetCents() == null
                && back.shareQuantity() == null && back.routeState() == null;
        return empty ? null : back;
    }

    private static String token(String field, String raw, List<String> allowed) {
        String value = blankToNull(raw);
        if (value == null) return null;
        String upper = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(upper)) {
            throw new IllegalArgumentException(field + " must be one of " + names(allowed)
                    + " (received '" + raw + "')");
        }
        return upper;
    }

    private static String sector(String raw) {
        String value = blankToNull(raw);
        if (value == null) return null;
        if (WORLD_SECTOR_KEY.equalsIgnoreCase(value.trim())) return WORLD_SECTOR_KEY;
        String upper = value.trim().toUpperCase(Locale.ROOT);
        if (!Universes.SECTORS.containsKey(upper)) {
            throw new IllegalArgumentException("sectorKey must be a canonical sector key — one of "
                    + names(List.copyOf(Universes.SECTORS.keySet())) + " or '" + WORLD_SECTOR_KEY
                    + "' inside a generated market (received '" + raw + "')");
        }
        return upper;
    }

    private static String symbol(String raw) {
        return Symbol.normalizeOptional(raw);
    }

    private static String id(String field, String raw) {
        String value = blankToNull(raw);
        if (value == null) return null;
        String trimmed = value.trim();
        if (!trimmed.matches("[A-Za-z0-9._:-]{1," + MAX_ID + "}")) {
            throw new IllegalArgumentException(field + " is not a valid identifier: " + raw);
        }
        return trimmed;
    }

    private static String text(String field, String raw, int max) {
        String value = blankToNull(raw);
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new IllegalArgumentException(field + " is longer than " + max + " characters");
        }
        if (trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " cannot contain control characters");
        }
        return trimmed;
    }

    private static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }

    private static List<String> names(Class<? extends Enum<?>> type) {
        return Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
    }

    private static String names(List<String> allowed) {
        return allowed.stream().collect(Collectors.joining(", "));
    }

    private static Set<String> clientFields() {
        Set<String> fields = new LinkedHashSet<>(MARKET_OWNED);
        fields.addAll(DECLARATIONS);
        // Declaration order is kept so the refusal message reads in the same order as the record.
        return java.util.Collections.unmodifiableSet(fields);
    }
}
