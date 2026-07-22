package io.liftandshift.strikebench.paper;

import com.fasterxml.jackson.core.type.TypeReference;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.util.Ids;
import io.liftandshift.strikebench.util.Json;
import io.liftandshift.strikebench.util.OwnerScope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The account-scope declared objective (folded Phase 9, §3.7): an immutable revision series —
 * declaring again writes revision N+1 and applies PROSPECTIVELY; history is never rewritten.
 * The coherence diagnostic and assignment-preference reweighting read the latest revision.
 */
public final class AccountObjectiveService {
    private static final Set<String> OBJECTIVES =
            Set.of("INCOME", "ACCUMULATE", "HEDGE", "DIRECTIONAL", "CAPITAL_PRESERVATION");
    private static final Set<String> DIRECTIONS = Set.of("BULLISH", "BEARISH", "NEUTRAL", "NON_DIRECTIONAL");
    private static final Set<String> ASSIGNMENT = Set.of("AVOID", "ACCEPT", "PREFER_BELOW_BASIS", "SEEK");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SYMBOL = Pattern.compile("[A-Z0-9][A-Z0-9.\\-]{0,19}");

    public enum Enforcement { HARD, ADVISORY }

    /** A package-specific statement of acceptable physical settlement, never an EV input. */
    public record PackageCapacity(
            String positionFingerprint,
            String symbol,
            Long acceptedAssignmentShares,
            Long acceptedAssignmentDollarsCents,
            Long acceptedEffectiveAcquisitionPricePerShareCents,
            Long acceptedCallAwayShares,
            Long acceptedCallAwayProceedsCents
    ) {
        public PackageCapacity {
            positionFingerprint = clean(positionFingerprint).toLowerCase(Locale.ROOT);
            symbol = clean(symbol).toUpperCase(Locale.ROOT);
            if (!SHA256.matcher(positionFingerprint).matches()) {
                throw new IllegalArgumentException("position fingerprint must be a 64-character SHA-256 value");
            }
            if (!SYMBOL.matcher(symbol).matches()) {
                throw new IllegalArgumentException("package capacity symbol is invalid");
            }
            nonNegative(acceptedAssignmentShares, "accepted assignment shares");
            nonNegative(acceptedAssignmentDollarsCents, "accepted assignment dollars");
            nonNegative(acceptedEffectiveAcquisitionPricePerShareCents,
                    "accepted effective acquisition price");
            nonNegative(acceptedCallAwayShares, "accepted call-away shares");
            nonNegative(acceptedCallAwayProceedsCents, "accepted call-away proceeds");
            if (acceptedAssignmentShares == null && acceptedAssignmentDollarsCents == null
                    && acceptedEffectiveAcquisitionPricePerShareCents == null
                    && acceptedCallAwayShares == null && acceptedCallAwayProceedsCents == null) {
                throw new IllegalArgumentException("package capacity must declare at least one quantity or amount");
            }
        }
    }

    /** A monetary ceiling with explicit enforcement strength. Zero is a valid hard prohibition. */
    public record CapacityCeiling(long maxCents, Enforcement enforcement) {
        public CapacityCeiling {
            if (maxCents < 0) throw new IllegalArgumentException("capacity ceiling cannot be negative");
            if (enforcement == null) throw new IllegalArgumentException("capacity enforcement is required");
        }
    }

    /** Symbol, theme, and expiry ceilings are keyed rather than hidden in prose. */
    public record ScopedCeiling(String key, long maxCents, Enforcement enforcement) {
        public ScopedCeiling {
            key = clean(key).toUpperCase(Locale.ROOT);
            if (maxCents < 0) throw new IllegalArgumentException("capacity ceiling cannot be negative");
            if (enforcement == null) throw new IllegalArgumentException("capacity enforcement is required");
        }
    }

    /** Account-wide capacity policy stored on the same immutable objective revision. */
    public record AccountCapacityPolicy(
            List<ScopedCeiling> symbolCeilings,
            List<ScopedCeiling> themeCeilings,
            List<ScopedCeiling> expiryCeilings,
            CapacityCeiling encumbranceCeiling
    ) {
        public AccountCapacityPolicy {
            symbolCeilings = normalizeScoped(symbolCeilings, "symbol");
            themeCeilings = normalizeScoped(themeCeilings, "theme");
            expiryCeilings = normalizeScoped(expiryCeilings, "expiry");
        }

        public static AccountCapacityPolicy empty() {
            return new AccountCapacityPolicy(List.of(), List.of(), List.of(), null);
        }

    }

    public record Revision(String id, String accountId, int revisionNo, String objective,
                           String direction, Long targetExposureCents, String assignmentPreference,
                           List<PackageCapacity> packageCapacities,
                           AccountCapacityPolicy capacityPolicy,
                           String declarationFingerprint,
                           String createdAt) {}

    /** Exact package match projected into analysis; absent declarations stay explicit. */
    public record CapacityContext(
            boolean objectiveDeclared,
            String objectiveRevisionId,
            Integer objectiveRevisionNo,
            String declarationFingerprint,
            String assignmentPreference,
            PackageCapacity packageCapacity,
            AccountCapacityPolicy accountPolicy,
            String packageMatchStatus,
            String basis
    ) {}

    private final Db db;
    private final Clock clock;

    public AccountObjectiveService(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public Revision declare(String userId, String accountId, String objective, String direction,
                            Long targetExposureCents, String assignmentPreference) {
        return declare(userId, accountId, objective, direction, targetExposureCents,
                assignmentPreference, List.of(), AccountCapacityPolicy.empty());
    }

    public Revision declare(String userId, String accountId, String objective, String direction,
                            Long targetExposureCents, String assignmentPreference,
                            List<PackageCapacity> packageCapacities,
                            AccountCapacityPolicy capacityPolicy) {
        String owner = OwnerScope.id(userId);
        String normalizedObjective = requireOneOf(objective, OBJECTIVES, "objective");
        String normalizedDirection = direction == null || direction.isBlank() ? null
                : requireOneOf(direction, DIRECTIONS, "direction");
        String normalizedAssignment = requireOneOf(
                assignmentPreference == null || assignmentPreference.isBlank() ? "ACCEPT" : assignmentPreference,
                ASSIGNMENT, "assignment preference");
        if (targetExposureCents != null && targetExposureCents < 0) {
            throw new IllegalArgumentException("target exposure cannot be negative");
        }
        List<PackageCapacity> normalizedPackages = normalizePackages(packageCapacities);
        AccountCapacityPolicy normalizedPolicy = capacityPolicy == null
                ? AccountCapacityPolicy.empty() : capacityPolicy;
        String fingerprint = fingerprint(normalizedObjective, normalizedDirection, targetExposureCents,
                normalizedAssignment, normalizedPackages, normalizedPolicy);
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String id = Ids.newId("aobj");
        return db.tx(c -> {
            requireOwnedAccount(c, owner, accountId);
            long next = Db.queryOn(c, "SELECT COALESCE(MAX(revision_no),0)+1 seq FROM account_objective_revision " +
                            "WHERE portfolio_account_id=?", r -> r.lng("seq"), accountId).getFirst();
            Db.execOn(c, "INSERT INTO account_objective_revision(id,portfolio_account_id,revision_no,objective," +
                            "direction,target_exposure_cents,assignment_preference,package_capacities," +
                            "capacity_policy,created_at) VALUES(?,?,?,?,?,?,?,?::jsonb,?::jsonb,?)",
                    id, accountId, next, normalizedObjective, normalizedDirection,
                    targetExposureCents, normalizedAssignment, Json.canonical(normalizedPackages),
                    Json.canonical(normalizedPolicy), now);
            return new Revision(id, accountId, (int) next, normalizedObjective, normalizedDirection,
                    targetExposureCents, normalizedAssignment, normalizedPackages, normalizedPolicy,
                    fingerprint, now.toString());
        });
    }

    /** The revision in force — latest by number; null when the account never declared one. */
    public Revision latest(String userId, String accountId) {
        String owner = OwnerScope.id(userId);
        return db.with(c -> {
            requireOwnedAccount(c, owner, accountId);
            List<Revision> rows = Db.queryOn(c, "SELECT id,portfolio_account_id,revision_no,objective,direction," +
                            "target_exposure_cents,assignment_preference,package_capacities::text package_capacities," +
                            "capacity_policy::text capacity_policy,created_at::text created_at " +
                            "FROM account_objective_revision WHERE portfolio_account_id=? " +
                            "ORDER BY revision_no DESC LIMIT 1",
                    AccountObjectiveService::row, accountId);
            return rows.isEmpty() ? null : rows.getFirst();
        });
    }

    /** Every revision, oldest first — the declaration history is a first-class record. */
    public List<Revision> history(String userId, String accountId) {
        String owner = OwnerScope.id(userId);
        return db.with(c -> {
            requireOwnedAccount(c, owner, accountId);
            return Db.queryOn(c, "SELECT id,portfolio_account_id,revision_no,objective,direction," +
                            "target_exposure_cents,assignment_preference,package_capacities::text package_capacities," +
                            "capacity_policy::text capacity_policy,created_at::text created_at " +
                            "FROM account_objective_revision WHERE portfolio_account_id=? ORDER BY revision_no",
                    AccountObjectiveService::row, accountId);
        });
    }

    private static Revision row(Db.Row r) {
        List<PackageCapacity> packages = normalizePackages(Json.read(r.str("package_capacities"),
                new TypeReference<List<PackageCapacity>>() {}));
        AccountCapacityPolicy policy = Json.read(r.str("capacity_policy"), AccountCapacityPolicy.class);
        String objective = r.str("objective");
        String direction = r.str("direction");
        Long target = r.lngOrNull("target_exposure_cents");
        String assignment = r.str("assignment_preference");
        return new Revision(r.str("id"), r.str("portfolio_account_id"), r.intv("revision_no"),
                objective, direction, target, assignment, packages, policy,
                fingerprint(objective, direction, target, assignment, packages, policy), r.str("created_at"));
    }

    public static CapacityContext capacityContext(Revision revision, String positionFingerprint) {
        if (revision == null) {
            return new CapacityContext(false, null, null, null, null, null,
                    AccountCapacityPolicy.empty(), "NO_OBJECTIVE_REVISION",
                    "No account objective revision is declared; capacity is unavailable and is not inferred.");
        }
        PackageCapacity match = revision.packageCapacities().stream()
                .filter(item -> item.positionFingerprint().equalsIgnoreCase(clean(positionFingerprint)))
                .findFirst().orElse(null);
        return new CapacityContext(true, revision.id(), revision.revisionNo(),
                revision.declarationFingerprint(), revision.assignmentPreference(), match,
                revision.capacityPolicy(), match == null ? "NO_EXACT_PACKAGE_DECLARATION" : "EXACT_FINGERPRINT",
                "Capacity comes from immutable account objective revision " + revision.revisionNo()
                        + "; it affects fit and policy only, never valuation or EV.");
    }

    private static List<PackageCapacity> normalizePackages(List<PackageCapacity> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        ArrayList<PackageCapacity> out = new ArrayList<>(raw.size());
        Set<String> seen = new HashSet<>();
        for (PackageCapacity item : raw) {
            if (item == null) throw new IllegalArgumentException("package capacity cannot be null");
            if (!seen.add(item.positionFingerprint())) {
                throw new IllegalArgumentException("duplicate package capacity fingerprint "
                        + item.positionFingerprint());
            }
            out.add(item);
        }
        out.sort(Comparator.comparing(PackageCapacity::positionFingerprint));
        return List.copyOf(out);
    }

    private static List<ScopedCeiling> normalizeScoped(List<ScopedCeiling> raw, String scope) {
        if (raw == null || raw.isEmpty()) return List.of();
        ArrayList<ScopedCeiling> out = new ArrayList<>(raw.size());
        Set<String> seen = new HashSet<>();
        for (ScopedCeiling item : raw) {
            if (item == null) throw new IllegalArgumentException(scope + " ceiling cannot be null");
            if ("symbol".equals(scope) && !SYMBOL.matcher(item.key()).matches()) {
                throw new IllegalArgumentException("symbol ceiling key is invalid");
            }
            if ("expiry".equals(scope)) {
                try { LocalDate.parse(item.key()); }
                catch (RuntimeException e) {
                    throw new IllegalArgumentException("expiry ceiling key must be an ISO date");
                }
            }
            if (!seen.add(item.key())) throw new IllegalArgumentException(
                    "duplicate " + scope + " ceiling " + item.key());
            out.add(item);
        }
        out.sort(Comparator.comparing(ScopedCeiling::key));
        return List.copyOf(out);
    }

    private static String fingerprint(String objective, String direction, Long target,
                                      String assignment, List<PackageCapacity> packages,
                                      AccountCapacityPolicy policy) {
        Map<String, Object> stable = new LinkedHashMap<>();
        stable.put("objective", objective);
        stable.put("direction", direction);
        stable.put("targetExposureCents", target);
        stable.put("assignmentPreference", assignment);
        stable.put("packageCapacities", packages);
        stable.put("capacityPolicy", policy);
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Json.canonical(stable).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to fingerprint account objective declaration", e);
        }
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("capacity key is required");
        return value.trim();
    }

    private static void nonNegative(Long value, String label) {
        if (value != null && value < 0) throw new IllegalArgumentException(label + " cannot be negative");
    }

    private static void requireOwnedAccount(java.sql.Connection c, String owner, String accountId)
            throws java.sql.SQLException {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("portfolio account id is required");
        }
        if (Db.queryOn(c, "SELECT 1 ok FROM portfolio_account WHERE id=? AND user_id=?",
                r -> r.intv("ok"), accountId, owner).isEmpty()) {
            throw new io.liftandshift.strikebench.util.ResourceNotFoundException(
                    "No tracked portfolio account " + accountId);
        }
    }

    private static String requireOneOf(String raw, Set<String> allowed, String label) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException(label + " must be one of " + String.join(", ", allowed));
        }
        return value;
    }
}
