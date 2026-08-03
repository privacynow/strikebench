package io.liftandshift.strikebench.plan;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.eval.EvidenceLevel;
import io.liftandshift.strikebench.paper.PortfolioAccountingService;
import io.liftandshift.strikebench.position.PositionArtifactStore;
import io.liftandshift.strikebench.position.PositionDomain;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Records "I placed this with my broker": one database transaction writes the frozen BROKER
 * decision, the tracked-book ledger row with its lots, and the four position artifacts that
 * link the Plan to the tracked structure. Partial promotion is impossible by construction —
 * the plan_portfolio_action trigger additionally enforces result/action agreement.
 */
public final class PlanPromotionService {
    public static final String MODEL_VERSION = "plan-promotion-1";

    public record Order(String portfolioAccountId,
                        PortfolioAccountingService.TransactionInput transaction,
                        String structureLabel,
                        long heldSharesRequired,
                        String reviewedObjectiveRevisionId,
                        String reviewedObjectiveDeclarationFingerprint) {
        public Order {
            if (heldSharesRequired < 0) {
                throw new IllegalArgumentException("heldSharesRequired cannot be negative");
            }
            if ((reviewedObjectiveRevisionId == null)
                    != (reviewedObjectiveDeclarationFingerprint == null)) {
                throw new IllegalArgumentException(
                        "reviewed objective revision id and fingerprint must be supplied together");
            }
        }
    }

    public record Result(String decisionId,
                         PortfolioAccountingService.TransactionView transaction,
                         PositionArtifactStore.ArtifactSet artifacts) {}

    private final Db db;
    private final PlanDecisionService planDecisions;
    private final PortfolioAccountingService books;
    private final PositionArtifactStore artifacts;

    public PlanPromotionService(Db db, PlanDecisionService planDecisions,
                                PortfolioAccountingService books, PositionArtifactStore artifacts) {
        this.db = db;
        this.planDecisions = planDecisions;
        this.books = books;
        this.artifacts = artifacts;
    }

    public Result promote(PlanDecisionService.Input decision, Order order) {
        if (decision == null || order == null || order.transaction() == null) {
            throw new IllegalArgumentException("complete promotion inputs are required");
        }
        if (order.portfolioAccountId() == null || order.portfolioAccountId().isBlank()) {
            throw new IllegalArgumentException("choose the tracked account that received this placement");
        }
        var prepared = planDecisions.prepareBroker(decision);
        try {
            return db.tx(c -> {
                prepared.hook().afterTradeCreated(c, null, null);
                PortfolioAccountingService.TransactionView txn =
                        books.recordOn(c, decision.userId(), order.portfolioAccountId(), order.transaction());
                List<LotRow> lots = Db.queryOn(c, "SELECT id,side,instrument_type,option_type,remaining_quantity "
                                + "FROM portfolio_lot WHERE opening_transaction_id=? ORDER BY opening_leg_no",
                        r -> new LotRow(r.str("id"), r.str("side"), r.str("instrument_type"),
                                r.str("option_type"), r.lng("remaining_quantity")), txn.id());
                if (lots.isEmpty()) {
                    throw new IllegalStateException("The broker record opened no tracked lots, so there is "
                            + "no new position to link. Record the opening placement, not a close.");
                }
                List<PositionArtifactStore.Allocation> allocations = new ArrayList<>();
                lots.forEach(lot -> allocations.add(
                        new PositionArtifactStore.Allocation(lot.id(), lot.quantity(), legRole(lot))));
                if (order.heldSharesRequired() > 0) {
                    long remaining = order.heldSharesRequired();
                    for (AvailableStockLot lot : availableStockLots(c, order.portfolioAccountId(),
                            decision.plan().symbol())) {
                        if (remaining == 0) break;
                        long take = Math.min(remaining, lot.availableQuantity());
                        allocations.add(new PositionArtifactStore.Allocation(
                                lot.id(), take, "UNDERLYING"));
                        remaining -= take;
                    }
                    if (remaining > 0) {
                        throw new IllegalStateException("The tracked destination no longer has "
                                + order.heldSharesRequired() + " free shares available to back this package.");
                    }
                }
                var artifactSet = artifacts.recordNewStructureAction(c, new PositionArtifactStore.NewStructureAction(
                        decision.userId(), decision.plan().id(), decision.plan().context().rev(),
                        order.portfolioAccountId(), txn.id(), prepared.id(),
                        order.reviewedObjectiveRevisionId(),
                        order.reviewedObjectiveDeclarationFingerprint(),
                        decision.plan().symbol(), order.structureLabel(),
                        PositionDomain.PositionState.OPEN, PositionDomain.PlanActionRole.ENTRY,
                        PositionDomain.ArtifactType.DECISION, PositionDomain.ArtifactSource.BROKER_REPORTED,
                        OffsetDateTime.parse(txn.occurredAt()), evidenceLevel(decision.plan()),
                        MODEL_VERSION, allocations, artifactLegs(txn)));
                return new Result(prepared.id(), txn, artifactSet);
            });
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("transaction amounts exceed the supported range");
        }
    }

    private static List<AvailableStockLot> availableStockLots(
            java.sql.Connection c, String accountId, String symbol) throws java.sql.SQLException {
        return Db.queryOn(c, """
                SELECT l.id,
                       l.remaining_quantity - COALESCE(used.allocated_quantity,0) available_quantity
                FROM portfolio_lot l
                LEFT JOIN (
                    SELECT m.lot_id,SUM(m.allocated_quantity) allocated_quantity
                    FROM portfolio_structure_member m
                    JOIN portfolio_structure_revision r ON r.id=m.revision_id
                    JOIN portfolio_structure s ON s.current_revision_id=r.id AND s.status='OPEN'
                    GROUP BY m.lot_id
                ) used ON used.lot_id=l.id
                WHERE l.portfolio_account_id=? AND l.symbol=?
                  AND l.instrument_type='STOCK' AND l.side='LONG' AND l.status='OPEN'
                  AND l.remaining_quantity > COALESCE(used.allocated_quantity,0)
                ORDER BY l.opened_at,l.id
                FOR UPDATE OF l
                """, row -> new AvailableStockLot(
                        row.str("id"), row.lng("available_quantity")), accountId, symbol);
    }

    private static EvidenceLevel evidenceLevel(Plan.View plan) {
        return switch (plan.marketKind()) {
            case OBSERVED -> EvidenceLevel.OBSERVED_DELAYED;
            case DEMO -> EvidenceLevel.DEMO_FIXTURE;
            case SIMULATED -> EvidenceLevel.SIMULATED;
        };
    }

    private static String legRole(LotRow lot) {
        if ("STOCK".equals(lot.instrumentType())) return "UNDERLYING";
        boolean shortSide = "SHORT".equals(lot.side());
        return switch (lot.optionType() == null ? "" : lot.optionType()) {
            case "CALL" -> shortSide ? "SHORT_CALL" : "LONG_CALL";
            case "PUT" -> shortSide ? "SHORT_PUT" : "LONG_PUT";
            default -> "CUSTOM";
        };
    }

    private static List<PositionArtifactStore.ArtifactLeg> artifactLegs(
            PortfolioAccountingService.TransactionView txn) {
        return txn.legs().stream().map(leg -> new PositionArtifactStore.ArtifactLeg(
                "AFTER", leg.legNo(), leg.instrumentType(), leg.action(), leg.symbol(),
                leg.optionType(), leg.strike(), leg.expiration(), leg.quantity(), leg.multiplier(),
                null, null, null, leg.price(), PositionDomain.PriceAuthority.BROKER_REPORTED)).toList();
    }

    private record LotRow(String id, String side, String instrumentType, String optionType, long quantity) {}
    private record AvailableStockLot(String id, long availableQuantity) {}
}
