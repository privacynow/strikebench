package io.liftandshift.strikebench.plan;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.eval.EvidenceLevel;
import io.liftandshift.strikebench.position.PositionArtifactStore;
import io.liftandshift.strikebench.position.PositionDomain;
import io.liftandshift.strikebench.util.OwnerScope;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Adopts an as-is tracked position into a Plan. The position already exists in the book —
 * adoption asserts nothing new about the market; it writes an ADOPTION receipt
 * (USER_ALLOCATED) over the EXISTING lots and spawns a Plan that starts mid-journey: the
 * live band shows the adopted structure immediately, while declaring a view on it remains
 * the user's next deliberate step.
 */
public final class PlanAdoptionService {
    public static final String MODEL_VERSION = "plan-adoption-1";

    public record Allocation(String lotId, Long quantity) {}
    public record Request(String clientRequestId, String portfolioAccountId, String symbol,
                          String label, List<Allocation> allocations) {}
    public record Result(Plan.View plan, PositionArtifactStore.ArtifactSet artifacts) {}

    private final Db db;
    private final Clock clock;
    private final PlanService plans;
    private final PositionArtifactStore artifacts;

    public PlanAdoptionService(Db db, Clock clock, PlanService plans, PositionArtifactStore artifacts) {
        this.db = db;
        this.clock = clock;
        this.plans = plans;
        this.artifacts = artifacts;
    }

    public Result adopt(String userId, Plan.MarketKind marketKind, String worldId, Request request) {
        if (request == null || request.portfolioAccountId() == null || request.portfolioAccountId().isBlank()) {
            throw new IllegalArgumentException("choose the tracked account that holds this position");
        }
        if (request.allocations() == null || request.allocations().isEmpty()) {
            throw new IllegalArgumentException("an adoption needs at least one lot to allocate");
        }
        if (marketKind == Plan.MarketKind.SIMULATED) {
            throw new IllegalStateException("A simulated world cannot adopt a real tracked position.");
        }
        String owner = OwnerScope.id(userId);

        // Pre-flight the lots BEFORE creating anything: owned, in the account, quantity honest.
        List<LotRow> lots = resolveLots(owner, request);

        // The receipt's transaction anchor is the newest event that shaped the position —
        // multi-lot positions may span several opening transactions; any of them satisfies
        // the store's account-scope check, and the newest is the most truthful anchor.
        LotRow anchor = lots.getFirst();
        for (LotRow lot : lots) if (lot.openedAt().compareTo(anchor.openedAt()) > 0) anchor = lot;

        Plan.View plan = plans.create(userId, marketKind, worldId, null, new Plan.CreateRequest(
                request.clientRequestId(), request.symbol(), null, null,
                trim(request.label()), null, null, null, null, null, null, null));
        try {
            List<PositionArtifactStore.Allocation> storeAllocations = new ArrayList<>(lots.size());
            for (int i = 0; i < lots.size(); i++) {
                LotRow lot = lots.get(i);
                storeAllocations.add(new PositionArtifactStore.Allocation(
                        lot.id(), request.allocations().get(i).quantity() == null
                                ? lot.remaining() : request.allocations().get(i).quantity(),
                        legRole(lot)));
            }
            String anchorTxn = anchor.openingTransactionId();
            OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            var result = db.tx(c -> {
                var set = artifacts.recordNewStructureAction(c, new PositionArtifactStore.NewStructureAction(
                        userId, plan.id(), plan.context().rev(), request.portfolioAccountId(), anchorTxn,
                        null, request.symbol(), trim(request.label()),
                        PositionDomain.PositionState.OPEN, PositionDomain.PlanActionRole.ENTRY,
                        PositionDomain.ReceiptKind.ADOPTION, PositionDomain.ReceiptAuthority.USER_ALLOCATED,
                        now, evidenceLevel(marketKind), MODEL_VERSION, storeAllocations, List.of()));
                // The adopted Plan is mid-journey: managing the live position is available NOW;
                // the declared view (still the next step) stays editable — status remains ACTIVE.
                Db.execOn(c, "UPDATE plans SET furthest_stage='MANAGE_REVIEW',version=version+1,updated_at=? WHERE id=?",
                        now, plan.id());
                return set;
            });
            return new Result(plans.get(userId, plan.id()), result);
        } catch (RuntimeException e) {
            // The plan was created in its own transaction; a failed adoption must not leave an
            // empty husk behind. Best-effort compensation — the pre-flight makes this rare.
            try { plans.deleteDraft(userId, plan.id(), plan.version()); } catch (RuntimeException ignored) { }
            throw e;
        }
    }

    private List<LotRow> resolveLots(String owner, Request request) {
        return db.with(c -> {
            List<LotRow> out = new ArrayList<>(request.allocations().size());
            for (Allocation allocation : request.allocations()) {
                if (allocation == null || allocation.lotId() == null || allocation.lotId().isBlank()) {
                    throw new IllegalArgumentException("every adoption allocation names a lot");
                }
                List<LotRow> rows = Db.queryOn(c, "SELECT pl.id,pl.side,pl.instrument_type,pl.option_type," +
                                "pl.remaining_quantity,pl.opening_transaction_id,pl.opened_at::text opened_at," +
                                "pl.symbol FROM portfolio_lot pl JOIN portfolio_account pa " +
                                "ON pa.id=pl.portfolio_account_id " +
                                "WHERE pl.id=? AND pl.portfolio_account_id=? AND pa.user_id=?",
                        r -> new LotRow(r.str("id"), r.str("side"), r.str("instrument_type"),
                                r.str("option_type"), r.lng("remaining_quantity"),
                                r.str("opening_transaction_id"), r.str("opened_at"), r.str("symbol")),
                        allocation.lotId(), request.portfolioAccountId(), owner);
                if (rows.isEmpty()) {
                    throw new IllegalArgumentException("lot " + allocation.lotId()
                            + " does not exist in this tracked account");
                }
                LotRow lot = rows.getFirst();
                long quantity = allocation.quantity() == null ? lot.remaining() : allocation.quantity();
                if (quantity < 1 || quantity > lot.remaining()) {
                    throw new IllegalArgumentException("lot " + allocation.lotId() + " has "
                            + lot.remaining() + " remaining; cannot adopt " + quantity);
                }
                out.add(lot);
            }
            return out;
        });
    }

    private static EvidenceLevel evidenceLevel(Plan.MarketKind marketKind) {
        return marketKind == Plan.MarketKind.DEMO ? EvidenceLevel.DEMO_FIXTURE : EvidenceLevel.OBSERVED_DELAYED;
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

    private static String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private record LotRow(String id, String side, String instrumentType, String optionType,
                          long remaining, String openingTransactionId, String openedAt, String symbol) {}
}
