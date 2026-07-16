package io.liftandshift.strikebench.plan;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.eval.EvidenceLevel;
import io.liftandshift.strikebench.paper.PortfolioAccountingService;
import io.liftandshift.strikebench.position.PositionArtifactStore;
import io.liftandshift.strikebench.position.PositionDomain;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Records "I placed this with my broker": one database transaction writes the frozen BROKER
 * decision, the tracked-book ledger row with its lots, and the four position artifacts that
 * link the Plan to the tracked structure. Partial promotion is impossible by construction —
 * the plan_portfolio_action trigger additionally enforces receipt/action agreement.
 */
public final class PlanPromotionService {
    public static final String MODEL_VERSION = "plan-promotion-1";

    public record Order(String portfolioAccountId,
                        PortfolioAccountingService.TransactionInput transaction,
                        String structureLabel) {}

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
                prepared.hook().afterTradeCreated(c, null);
                PortfolioAccountingService.TransactionView txn =
                        books.recordOn(c, decision.userId(), order.portfolioAccountId(), order.transaction());
                List<LotRow> lots = Db.queryOn(c, "SELECT id,side,instrument_type,option_type,original_quantity "
                                + "FROM portfolio_lot WHERE opening_transaction_id=? ORDER BY opening_leg_no",
                        r -> new LotRow(r.str("id"), r.str("side"), r.str("instrument_type"),
                                r.str("option_type"), r.lng("original_quantity")), txn.id());
                if (lots.isEmpty()) {
                    throw new IllegalStateException("The broker record opened no tracked lots, so there is "
                            + "no new position to link. Record the opening placement, not a close.");
                }
                var allocations = lots.stream().map(lot ->
                        new PositionArtifactStore.Allocation(lot.id(), lot.quantity(), legRole(lot))).toList();
                var artifactSet = artifacts.recordNewStructureAction(c, new PositionArtifactStore.NewStructureAction(
                        decision.userId(), decision.plan().id(), decision.plan().context().rev(),
                        order.portfolioAccountId(), txn.id(), prepared.id(),
                        decision.plan().symbol(), order.structureLabel(),
                        PositionDomain.PositionState.OPEN, PositionDomain.PlanActionRole.ENTRY,
                        PositionDomain.ReceiptKind.DECISION, PositionDomain.ReceiptAuthority.BROKER_REPORTED,
                        OffsetDateTime.parse(txn.occurredAt()), evidenceLevel(decision.plan()),
                        MODEL_VERSION, allocations, receiptLegs(txn)));
                return new Result(prepared.id(), txn, artifactSet);
            });
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("transaction amounts exceed the supported range");
        }
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

    private static List<PositionArtifactStore.ReceiptLeg> receiptLegs(
            PortfolioAccountingService.TransactionView txn) {
        return txn.legs().stream().map(leg -> new PositionArtifactStore.ReceiptLeg(
                "AFTER", leg.legNo(), leg.instrumentType(), leg.action(), leg.symbol(),
                leg.optionType(), leg.strike(), leg.expiration(), leg.quantity(), leg.multiplier(),
                null, null, null, leg.price(), PositionDomain.PriceAuthority.BROKER_REPORTED)).toList();
    }

    private record LotRow(String id, String side, String instrumentType, String optionType, long quantity) {}
}
