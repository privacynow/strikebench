package io.liftandshift.strikebench.plan;

import io.liftandshift.strikebench.db.Db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import io.liftandshift.strikebench.util.ResourceNotFoundException;

/** Enforces the decision boundary for every service that persists Plan analysis. */
final class PlanWriteGuard {
    private PlanWriteGuard() {}

    static void requireMutable(Connection connection, String planId, String userId) throws SQLException {
        List<String> rows = Db.queryOn(connection,
                "SELECT p.status FROM plans p WHERE p.id=? AND " + ownerClause("p.user_id") + " FOR UPDATE OF p",
                row -> row.str("status"), planId, io.liftandshift.strikebench.util.OwnerScope.id(userId));
        if (rows.isEmpty()) throw new ResourceNotFoundException("no such Plan: " + planId);
        // A completed management action can reopen the Plan for a new decision cycle. Prior
        // receipts stay immutable; only the current ACTIVE cycle may create new analysis rows.
        String status = rows.getFirst();
        if ("ARCHIVED".equals(status) || "ABANDONED".equals(status)) {
            throw new IllegalStateException("This Plan is archived and read-only.");
        }
        if (!"ACTIVE".equals(status)) {
            throw new IllegalStateException(
                    "This Plan's decision is frozen. Start a linked Plan to revise it without rewriting history.");
        }
    }

    private static String ownerClause(String column) {
        return column + "=?::text";
    }
}
