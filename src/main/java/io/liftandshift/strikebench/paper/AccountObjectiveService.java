package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.util.Ids;
import io.liftandshift.strikebench.util.OwnerScope;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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

    public record Revision(String id, String accountId, int revisionNo, String objective,
                           String direction, Long targetExposureCents, String assignmentPreference,
                           String createdAt) {}

    private final Db db;
    private final Clock clock;

    public AccountObjectiveService(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public Revision declare(String userId, String accountId, String objective, String direction,
                            Long targetExposureCents, String assignmentPreference) {
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
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String id = Ids.newId("aobj");
        return db.tx(c -> {
            requireOwnedAccount(c, owner, accountId);
            long next = Db.queryOn(c, "SELECT COALESCE(MAX(revision_no),0)+1 seq FROM account_objective_revision " +
                            "WHERE portfolio_account_id=?", r -> r.lng("seq"), accountId).getFirst();
            Db.execOn(c, "INSERT INTO account_objective_revision(id,portfolio_account_id,revision_no,objective," +
                            "direction,target_exposure_cents,assignment_preference,created_at) VALUES(?,?,?,?,?,?,?,?)",
                    id, accountId, next, normalizedObjective, normalizedDirection,
                    targetExposureCents, normalizedAssignment, now);
            return new Revision(id, accountId, (int) next, normalizedObjective, normalizedDirection,
                    targetExposureCents, normalizedAssignment, now.toString());
        });
    }

    /** The revision in force — latest by number; null when the account never declared one. */
    public Revision latest(String userId, String accountId) {
        String owner = OwnerScope.id(userId);
        return db.with(c -> {
            requireOwnedAccount(c, owner, accountId);
            List<Revision> rows = Db.queryOn(c, "SELECT id,portfolio_account_id,revision_no,objective,direction," +
                            "target_exposure_cents,assignment_preference,created_at::text created_at " +
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
                            "target_exposure_cents,assignment_preference,created_at::text created_at " +
                            "FROM account_objective_revision WHERE portfolio_account_id=? ORDER BY revision_no",
                    AccountObjectiveService::row, accountId);
        });
    }

    private static Revision row(Db.Row r) {
        return new Revision(r.str("id"), r.str("portfolio_account_id"), r.intv("revision_no"),
                r.str("objective"), r.str("direction"), r.lngOrNull("target_exposure_cents"),
                r.str("assignment_preference"), r.str("created_at"));
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
