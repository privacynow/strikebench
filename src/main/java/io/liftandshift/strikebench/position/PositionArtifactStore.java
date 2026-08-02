package io.liftandshift.strikebench.position;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.eval.EvidenceLevel;
import io.liftandshift.strikebench.model.Symbol;
import io.liftandshift.strikebench.paper.AccountObjectiveService;
import io.liftandshift.strikebench.util.Ids;
import io.liftandshift.strikebench.util.OwnerScope;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Atomic writer for the artifact cardinality required by a Plan-managed tracked action. */
public final class PositionArtifactStore {

    /** Writes the four-artifact set inside a caller-owned transaction (a Plan promotion commits
     *  the frozen decision, the ledger row, and these artifacts together or not at all). */
    public ArtifactSet recordNewStructureAction(Connection c, NewStructureAction input) throws SQLException {
        if (c == null || input == null) throw new IllegalArgumentException("position action is required");
        return writeNewStructureAction(c, OwnerScope.id(input.userId()), input);
    }

    /**
     * Freezes a pending-import resolution inside the caller's ledger transaction. The pending
     * package total, exact ledger cash, authority and result are one database fact: a failure
     * in any row rolls the entire resolution back.
     */
    public String recordImportResolution(Connection c, ImportResolutionAction input) throws SQLException {
        if (c == null || input == null) throw new IllegalArgumentException("import resolution is required");
        String userId = OwnerScope.id(input.userId());
        requireExists(c, "SELECT 1 ok FROM portfolio_import_pending WHERE id=? AND user_id=? "
                        + (input.authority() == PositionDomain.ArtifactSource.BROKER_REPORTED
                        ? "AND status IN ('PENDING','PROVISIONAL') " : "AND status='PENDING' ")
                        + "AND destination_portfolio_account_id=?",
                "pending import", input.pendingId(), userId, input.portfolioAccountId());
        requireExists(c, "SELECT 1 ok FROM portfolio_account WHERE id=? AND user_id=? AND status='ACTIVE'",
                "active tracked account", input.portfolioAccountId(), userId);
        if (input.authority() == PositionDomain.ArtifactSource.BROKER_REPORTED) {
            requireExists(c, "SELECT 1 ok FROM portfolio_transaction WHERE id=? AND portfolio_account_id=?",
                    "tracked transaction", input.transactionId(), input.portfolioAccountId());
        } else if (input.transactionId() != null) {
            throw new IllegalArgumentException("a provisional allocation cannot reference a tracked transaction");
        }
        if (input.packageTotalCents() != input.allocatedTotalCents()) {
            throw new IllegalArgumentException("allocated leg cash must equal the pending package total to the cent");
        }
        String artifactId = Ids.newId("part");
        String resolutionId = Ids.newId("pires");
        String taxBasis = input.authority() == PositionDomain.ArtifactSource.BROKER_REPORTED
                ? "AUTHORITATIVE" : "PROVISIONAL";
        Db.execOn(c, "INSERT INTO position_artifact(id,user_id,artifact_type,artifact_source,book_type,position_state,"
                        + "portfolio_account_id,transaction_id,marks_as_of,evidence_level,model_version) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                artifactId, userId, PositionDomain.ArtifactType.RESOLUTION.name(), input.authority().name(),
                PositionDomain.BookType.TRACKED.name(),
                input.authority() == PositionDomain.ArtifactSource.USER_ALLOCATED
                        ? PositionDomain.PositionState.PENDING.name() : input.positionState().name(),
                input.portfolioAccountId(), input.transactionId(), input.marksAsOf(),
                input.evidenceLevel().name(), input.modelVersion());
        if (input.artifactLegs() != null) for (ArtifactLeg leg : input.artifactLegs()) {
            Db.execOn(c, "INSERT INTO position_artifact_leg(artifact_id,position_phase,leg_no,instrument_type,action,symbol,"
                            + "option_type,strike,expiration,quantity,multiplier,bid,ask,mid,fill_price,price_authority) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    artifactId, "AFTER", leg.legNo(), leg.instrumentType(), leg.action(), symbol(leg.symbol()),
                    leg.optionType(), leg.strike(), leg.expiration(), leg.quantity(), leg.multiplier(), leg.bid(),
                    leg.ask(), leg.mid(), leg.fillPrice(), leg.priceAuthority().name());
        }
        insertCentsMetric(c, artifactId, "package_total", input.packageTotalCents());
        insertCentsMetric(c, artifactId, "allocated_total", input.allocatedTotalCents());
        insertTextMetric(c, artifactId, "pending_import", input.pendingId());
        insertTextMetric(c, artifactId, "source_system", input.sourceSystem());
        insertTextMetric(c, artifactId, "tax_basis_status", taxBasis);
        String nextStatus = input.authority() == PositionDomain.ArtifactSource.BROKER_REPORTED
                ? "RESOLVED" : "PROVISIONAL";
        int updated = Db.execOn(c, "UPDATE portfolio_import_pending SET status=?,resolved_at=? "
                        + "WHERE id=? AND user_id=? AND "
                        + (input.authority() == PositionDomain.ArtifactSource.BROKER_REPORTED
                        ? "status IN ('PENDING','PROVISIONAL')" : "status='PENDING'"),
                nextStatus, input.marksAsOf(), input.pendingId(), userId);
        if (updated != 1) throw new IllegalStateException("pending import changed while it was being resolved");
        Db.execOn(c, "INSERT INTO portfolio_import_resolution(id,pending_id,portfolio_account_id,transaction_id,"
                        + "artifact_id,authority,tax_basis_status,package_total_cents,allocated_total_cents,resolver_user_id,resolved_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                resolutionId, input.pendingId(), input.portfolioAccountId(), input.transactionId(), artifactId,
                input.authority().name(), taxBasis, input.packageTotalCents(), input.allocatedTotalCents(),
                userId, input.marksAsOf());
        return artifactId;
    }

    private ArtifactSet writeNewStructureAction(Connection c, String userId, NewStructureAction input)
            throws SQLException {
        requireExists(c, "SELECT 1 ok FROM plans WHERE id=? AND user_id=?", "Plan", input.planId(), userId);
        requireExists(c, "SELECT 1 ok FROM plan_context_revision WHERE plan_id=? AND rev=?",
                "Plan context revision", input.planId(), input.contextRevision());
        requireExists(c, "SELECT 1 ok FROM portfolio_account WHERE id=? AND user_id=? AND status='ACTIVE'",
                "active tracked account", input.portfolioAccountId(), userId);
        requireExists(c, "SELECT 1 ok FROM portfolio_transaction WHERE id=? AND portfolio_account_id=?",
                "tracked transaction", input.transactionId(), input.portfolioAccountId());
        if (input.allocations() == null || input.allocations().isEmpty()) {
            throw new IllegalArgumentException("a tracked structure requires at least one lot allocation");
        }

        String structureId = Ids.newId("pstr");
        String revisionId = Ids.newId("psr");
        String artifactId = Ids.newId("part");
        String actionId = Ids.newId("ppa");
        if ((input.accountObjectiveRevisionId() == null)
                != (input.accountObjectiveDeclarationFingerprint() == null)) {
            throw new IllegalArgumentException(
                    "objective revision id and declaration fingerprint must be supplied together");
        }
        if (input.accountObjectiveRevisionId() != null) {
            var objective = AccountObjectiveService.revisionOn(c, userId,
                    input.portfolioAccountId(), input.accountObjectiveRevisionId());
            if (!objective.declarationFingerprint()
                    .equals(input.accountObjectiveDeclarationFingerprint())) {
                throw new IllegalArgumentException(
                        "account objective declaration fingerprint does not match the reviewed revision");
            }
        }
        Db.execOn(c, "INSERT INTO portfolio_structure(id,user_id,portfolio_account_id,symbol,label,status) "
                        + "VALUES(?,?,?,?,?,'OPEN')",
                structureId, userId, input.portfolioAccountId(), symbol(input.symbol()), trim(input.label()));
        Db.execOn(c, "INSERT INTO portfolio_structure_revision(id,structure_id,revision_no,position_state,"
                        + "action_role,transaction_id) VALUES(?,?,1,?,?,?)",
                revisionId, structureId, input.positionState().name(), input.role().name(), input.transactionId());
        int legNo = 0;
        for (Allocation allocation : input.allocations()) {
            if (allocation == null || allocation.quantity() <= 0) {
                throw new IllegalArgumentException("lot allocations must be positive");
            }
            requireExists(c, "SELECT 1 ok FROM portfolio_lot WHERE id=? AND portfolio_account_id=?",
                    "tracked lot", allocation.lotId(), input.portfolioAccountId());
            Db.execOn(c, "INSERT INTO portfolio_structure_member(revision_id,leg_no,lot_id,allocated_quantity,leg_role) "
                            + "VALUES(?,?,?,?,?)", revisionId, legNo++, allocation.lotId(), allocation.quantity(),
                    allocation.legRole());
        }
        Db.execOn(c, "UPDATE portfolio_structure SET current_revision_id=?,updated_at=now() WHERE id=?",
                revisionId, structureId);

        Db.execOn(c, "INSERT INTO position_artifact(id,user_id,artifact_type,artifact_source,book_type,position_state,"
                        + "plan_id,plan_context_rev,account_objective_revision_id,portfolio_account_id,"
                        + "structure_revision_id,decision_id,transaction_id,marks_as_of,evidence_level,model_version) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                artifactId, userId, input.artifactType().name(), input.artifactSource().name(),
                PositionDomain.BookType.TRACKED.name(), input.positionState().name(), input.planId(),
                input.contextRevision(), input.accountObjectiveRevisionId(), input.portfolioAccountId(),
                revisionId, input.decisionId(),
                input.transactionId(), input.marksAsOf(), input.evidenceLevel().name(), input.modelVersion());
        if (input.artifactLegs() != null) for (ArtifactLeg leg : input.artifactLegs()) {
            Db.execOn(c, "INSERT INTO position_artifact_leg(artifact_id,position_phase,leg_no,instrument_type,action,symbol,"
                            + "option_type,strike,expiration,quantity,multiplier,bid,ask,mid,fill_price,price_authority) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    artifactId, leg.positionPhase(), leg.legNo(), leg.instrumentType(), leg.action(), symbol(leg.symbol()),
                    leg.optionType(), leg.strike(), leg.expiration(), leg.quantity(), leg.multiplier(), leg.bid(),
                    leg.ask(), leg.mid(), leg.fillPrice(), leg.priceAuthority().name());
        }
        insertTextMetric(c, artifactId, "account_objective_declaration_fingerprint",
                input.accountObjectiveDeclarationFingerprint());
        Db.execOn(c, "INSERT INTO plan_portfolio_action(id,plan_id,structure_revision_id,transaction_id,artifact_id,role) "
                        + "VALUES(?,?,?,?,?,?)", actionId, input.planId(), revisionId, input.transactionId(),
                artifactId, input.role().name());
        return new ArtifactSet(structureId, revisionId, artifactId, actionId);
    }

    /** Writes the frozen before/after artifact inside the same transaction as a Practice mutation. */
    public String recordPracticeTransformation(Connection c, PracticeTransformationAction input)
            throws SQLException {
        if (c == null || input == null) throw new IllegalArgumentException("practice transformation is required");
        String userId = OwnerScope.id(input.userId());
        requireExists(c, "SELECT 1 ok FROM trades t JOIN accounts a ON a.id=t.account_id "
                        + "WHERE t.id=? AND a.user_id=?", "owned Practice trade", input.practiceTradeId(), userId);
        if (input.planId() != null) {
            requireExists(c, "SELECT 1 ok FROM plans WHERE id=? AND user_id=?", "Plan", input.planId(), userId);
            requireExists(c, "SELECT 1 ok FROM plan_context_revision WHERE plan_id=? AND rev=?",
                    "Plan context revision", input.planId(), input.contextRevision());
        }
        PositionTransformation.Preview preview = input.preview();
        Db.execOn(c, "INSERT INTO position_artifact(id,user_id,artifact_type,artifact_source,book_type,position_state,"
                        + "plan_id,plan_context_rev,practice_trade_id,marks_as_of,evidence_level,model_version,"
                        + "transformation_action,preview_fingerprint) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                input.artifactId(), userId, PositionDomain.ArtifactType.TRANSFORMATION.name(),
                PositionDomain.ArtifactSource.SYSTEM_ANALYSIS.name(), PositionDomain.BookType.PRACTICE.name(),
                input.positionState().name(), input.planId(), input.planId() == null ? null : input.contextRevision(),
                input.practiceTradeId(), input.marksAsOf(), input.evidenceLevel().name(), input.modelVersion(),
                preview.action().name(), preview.fingerprint());
        insertPackageLegs(c, input.artifactId(), "BEFORE", input.before());
        insertPackageLegs(c, input.artifactId(), "AFTER", input.after());
        insertTextMetric(c, input.artifactId(), "before_identity", preview.beforeIdentity().label());
        insertTextMetric(c, input.artifactId(), "after_identity", preview.afterIdentity().label());
        insertCentsMetric(c, input.artifactId(), "before_max_loss", preview.beforeRisk().maxLossCents());
        Long afterMaxLoss = preview.afterRisk() == null
                ? Long.valueOf(0L) : preview.afterRisk().maxLossCents();
        insertCentsMetric(c, input.artifactId(), "after_max_loss", afterMaxLoss);
        insertCentsMetric(c, input.artifactId(), "before_reserve", preview.beforeRisk().reserveCents());
        Long afterReserve = preview.afterRisk() == null
                ? Long.valueOf(0L) : preview.afterRisk().reserveCents();
        insertCentsMetric(c, input.artifactId(), "after_reserve", afterReserve);
        insertCentsMetric(c, input.artifactId(), "before_assignment_cash",
                preview.beforeObligations().putAssignmentCashCents());
        insertCentsMetric(c, input.artifactId(), "after_assignment_cash",
                preview.afterObligations().putAssignmentCashCents());
        insertNumberMetric(c, input.artifactId(), "before_call_delivery_shares",
                preview.beforeObligations().callDeliveryShares());
        insertNumberMetric(c, input.artifactId(), "after_call_delivery_shares",
                preview.afterObligations().callDeliveryShares());
        insertCentsMetric(c, input.artifactId(), "realized_closing",
                input.realizedCents() == null ? preview.realizedClosingCents() : input.realizedCents());
        return input.artifactId();
    }

    private static void insertPackageLegs(Connection c, String artifactId, String phase, PositionPackage position)
            throws SQLException {
        if (position == null) return;
        for (PositionPackage.Leg leg : position.legs()) {
            Db.execOn(c, "INSERT INTO position_artifact_leg(artifact_id,position_phase,leg_no,instrument_type,action,"
                            + "symbol,option_type,strike,expiration,quantity,multiplier,mid,fill_price,price_authority) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    artifactId, phase, leg.index(), leg.instrumentType(), leg.action(), symbol(leg.symbol()),
                    leg.optionType(), leg.strike(), leg.expiration(), leg.quantity(), leg.multiplier(), leg.price(),
                    leg.priceAuthority() == PositionDomain.PriceAuthority.BROKER_REPORTED
                            || leg.priceAuthority() == PositionDomain.PriceAuthority.USER_REPORTED ? leg.price() : null,
                    leg.priceAuthority().name());
        }
    }

    private static void insertCentsMetric(Connection c, String artifactId, String key, Long value)
            throws SQLException {
        if (value != null) Db.execOn(c, "INSERT INTO position_artifact_metric(artifact_id,metric_key,value_cents) VALUES(?,?,?)",
                artifactId, key, value);
    }

    private static void insertNumberMetric(Connection c, String artifactId, String key, long value)
            throws SQLException {
        Db.execOn(c, "INSERT INTO position_artifact_metric(artifact_id,metric_key,value_number) VALUES(?,?,?)",
                artifactId, key, (double) value);
    }

    private static void insertTextMetric(Connection c, String artifactId, String key, String value)
            throws SQLException {
        if (value != null) Db.execOn(c, "INSERT INTO position_artifact_metric(artifact_id,metric_key,value_text) VALUES(?,?,?)",
                artifactId, key, value);
    }

    private static void requireExists(Connection c, String sql, String label, Object... params) throws SQLException {
        if (Db.queryOn(c, sql, row -> row.intv("ok"), params).isEmpty()) {
            throw new IllegalArgumentException(label + " does not exist in this owner/account scope");
        }
    }

    private static String symbol(String value) {
        return Symbol.normalize(value);
    }

    private static String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public record NewStructureAction(String userId, String planId, int contextRevision,
                                     String portfolioAccountId, String transactionId, String decisionId,
                                     String accountObjectiveRevisionId,
                                     String accountObjectiveDeclarationFingerprint,
                                     String symbol, String label, PositionDomain.PositionState positionState,
                                     PositionDomain.PlanActionRole role, PositionDomain.ArtifactType artifactType,
                                     PositionDomain.ArtifactSource artifactSource, OffsetDateTime marksAsOf,
                                     EvidenceLevel evidenceLevel, String modelVersion,
                                     List<Allocation> allocations, List<ArtifactLeg> artifactLegs) {
        public NewStructureAction {
            allocations = allocations == null ? List.of() : List.copyOf(allocations);
            artifactLegs = artifactLegs == null ? List.of() : List.copyOf(artifactLegs);
            if (contextRevision <= 0 || positionState == null || role == null || artifactType == null
                    || artifactSource == null || marksAsOf == null || evidenceLevel == null
                    || modelVersion == null || modelVersion.isBlank()) {
                throw new IllegalArgumentException("complete action provenance is required");
            }
            if ((accountObjectiveRevisionId == null)
                    != (accountObjectiveDeclarationFingerprint == null)) {
                throw new IllegalArgumentException(
                        "objective revision id and declaration fingerprint must be supplied together");
            }
        }
    }

    public record Allocation(String lotId, long quantity, String legRole) {}
    public record ArtifactLeg(String positionPhase, int legNo, String instrumentType, String action, String symbol,
                             String optionType, BigDecimal strike, LocalDate expiration,
                             long quantity, int multiplier, BigDecimal bid, BigDecimal ask,
                             BigDecimal mid, BigDecimal fillPrice,
                             PositionDomain.PriceAuthority priceAuthority) {}
    public record ImportResolutionAction(String userId, String pendingId, String portfolioAccountId,
                                         String transactionId, PositionDomain.ArtifactSource authority,
                                         PositionDomain.PositionState positionState,
                                         OffsetDateTime marksAsOf, EvidenceLevel evidenceLevel,
                                         String modelVersion, String sourceSystem,
                                         long packageTotalCents, long allocatedTotalCents,
                                         List<ArtifactLeg> artifactLegs) {
        public ImportResolutionAction {
            artifactLegs = artifactLegs == null ? List.of() : List.copyOf(artifactLegs);
            if (pendingId == null || pendingId.isBlank() || portfolioAccountId == null
                    || portfolioAccountId.isBlank()
                    || authority == null || authority == PositionDomain.ArtifactSource.SYSTEM_ANALYSIS
                    || positionState == null || marksAsOf == null || evidenceLevel == null
                    || modelVersion == null || modelVersion.isBlank() || sourceSystem == null
                    || sourceSystem.isBlank() || artifactLegs.isEmpty()) {
                throw new IllegalArgumentException("complete import-resolution provenance is required");
            }
            if (authority == PositionDomain.ArtifactSource.BROKER_REPORTED
                    && (transactionId == null || transactionId.isBlank())) {
                throw new IllegalArgumentException("broker-reported resolution needs its tracked transaction");
            }
            if (authority == PositionDomain.ArtifactSource.USER_ALLOCATED && transactionId != null) {
                throw new IllegalArgumentException("user allocation remains quarantined and cannot name a transaction");
            }
        }
    }
    public record PracticeTransformationAction(String userId, String artifactId, String planId,
                                               Integer contextRevision, String practiceTradeId,
                                               PositionDomain.PositionState positionState,
                                               OffsetDateTime marksAsOf, EvidenceLevel evidenceLevel,
                                               String modelVersion, PositionPackage before,
                                               PositionPackage after, PositionTransformation.Preview preview,
                                               Long realizedCents) {
        public PracticeTransformationAction {
            if (artifactId == null || artifactId.isBlank() || practiceTradeId == null || practiceTradeId.isBlank()
                    || positionState == null || marksAsOf == null || evidenceLevel == null
                    || modelVersion == null || modelVersion.isBlank() || before == null || preview == null
                    || planId != null && (contextRevision == null || contextRevision <= 0)) {
                throw new IllegalArgumentException("complete Practice transformation provenance is required");
            }
        }
    }
    public record ArtifactSet(String structureId, String revisionId, String artifactId, String actionId) {}
}
