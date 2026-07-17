package io.liftandshift.strikebench.plan;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.eval.EvidenceLevel;
import io.liftandshift.strikebench.position.PositionArtifactStore;
import io.liftandshift.strikebench.position.PositionDomain;
import io.liftandshift.strikebench.paper.MarksSource;
import io.liftandshift.strikebench.util.OwnerScope;
import io.liftandshift.strikebench.util.Json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    public record BatchItem(String action, String clientRequestId, String portfolioAccountId,
                            String symbol, String label, List<Allocation> allocations,
                            String existingPlanId) {}
    public record BatchRequest(List<BatchItem> items) {}
    public record BatchItemResult(String action, Plan.View plan,
                                  PositionArtifactStore.ArtifactSet artifacts, boolean replayed) {}
    public record BatchResult(List<BatchItemResult> items, int adopted, int linked, int skipped,
                              int replayed, String note) {}

    private final Db db;
    private final Clock clock;
    private final PlanService plans;
    private final PositionArtifactStore artifacts;
    private final MarksSource marks;

    public PlanAdoptionService(Db db, Clock clock, PlanService plans, PositionArtifactStore artifacts) {
        this(db, clock, plans, artifacts, null);
    }

    public PlanAdoptionService(Db db, Clock clock, PlanService plans, PositionArtifactStore artifacts,
                               MarksSource marks) {
        this.db = db;
        this.clock = clock;
        this.plans = plans;
        this.artifacts = artifacts;
        this.marks = marks;
    }

    public Result adopt(String userId, Plan.MarketKind marketKind, String worldId, Request request) {
        if (request == null || request.portfolioAccountId() == null || request.portfolioAccountId().isBlank()) {
            throw new IllegalArgumentException("choose the tracked account that holds this position");
        }
        if (request.allocations() == null || request.allocations().isEmpty()) {
            throw new IllegalArgumentException("an adoption needs at least one lot to allocate");
        }
        if (marketKind != Plan.MarketKind.OBSERVED) {
            throw new IllegalStateException("A real tracked position can be adopted only in the Observed market.");
        }
        BatchResult batch = adoptBatch(userId, marketKind, worldId, new BatchRequest(List.of(new BatchItem(
                "ADOPT", request.clientRequestId(), request.portfolioAccountId(), request.symbol(),
                request.label(), request.allocations(), null))));
        BatchItemResult item = batch.items().getFirst();
        return new Result(item.plan(), item.artifacts());
    }

    /**
     * One explicit batch confirmation over existing tracked lots. ADOPT creates a new Plan,
     * LINK attaches the position to a named existing Plan, and SKIP writes nothing. All
     * non-skipped items commit together with their ADOPTION receipts or the entire batch rolls back.
     */
    public BatchResult adoptBatch(String userId, Plan.MarketKind marketKind, String worldId,
                                  BatchRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new IllegalArgumentException("batch adoption needs at least one adopt, link, or skip choice");
        }
        if (request.items().size() > 250) throw new IllegalArgumentException("batch adoption supports at most 250 positions");
        boolean changesRealPlan = request.items().stream().filter(java.util.Objects::nonNull)
                .map(BatchItem::action).filter(java.util.Objects::nonNull)
                .anyMatch(action -> !"SKIP".equalsIgnoreCase(action.trim()));
        if (changesRealPlan && marketKind != Plan.MarketKind.OBSERVED) {
            throw new IllegalStateException("A real tracked position can be adopted only in the Observed market.");
        }
        String owner = OwnerScope.id(userId);
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<PendingBatchResult> committed = db.tx(c -> {
            List<PendingBatchItem> prepared = new ArrayList<>(request.items().size());
            for (BatchItem raw : request.items()) prepared.add(prepareBatchItem(c, owner, raw));
            List<PendingBatchResult> results = new ArrayList<>(prepared.size());
            for (int index = 0; index < prepared.size(); index++) {
                PendingBatchItem item = prepared.get(index);
                if ("SKIP".equals(item.action())) {
                    results.add(new PendingBatchResult("SKIP", null, null, false));
                    continue;
                }
                String requestId = item.raw().clientRequestId();
                Db.queryOn(c, "SELECT pg_advisory_xact_lock(hashtext(?),hashtext(?)),1 ok",
                        r -> r.intv("ok"), owner, "portfolio-adoption:" + requestId);
                ExistingAdoption replay = existingAdoption(c, owner, requestId);
                if (replay != null) {
                    if (!replay.inputHash().equals(item.inputHash())) {
                        throw new IllegalStateException("clientRequestId " + requestId
                                + " was already used for different adoption facts");
                    }
                    results.add(new PendingBatchResult(replay.action(), replay.planId(),
                            new PositionArtifactStore.ArtifactSet(replay.structureId(), replay.revisionId(),
                                    replay.receiptId(), replay.actionId()), true));
                    continue;
                }
                Plan.View plan;
                if ("ADOPT".equals(item.action())) {
                    plan = plans.createOn(c, owner, marketKind, worldId, null, new Plan.CreateRequest(
                            "adoption-plan:" + requestId, item.symbol(), null, null,
                            trim(item.raw().label()), null, null, null, null, null, null, null, null),
                            item.positionOwnerKey());
                } else {
                    plan = existingLinkPlan(c, owner, item.raw().existingPlanId(), item.symbol(),
                            marketKind, worldId);
                }
                LotRow anchor = item.lots().getFirst();
                for (LotRow lot : item.lots()) if (lot.openedAt().compareTo(anchor.openedAt()) > 0) anchor = lot;
                List<PositionArtifactStore.Allocation> storeAllocations = new ArrayList<>(item.lots().size());
                for (int i = 0; i < item.lots().size(); i++) {
                    LotRow lot = item.lots().get(i);
                    Long requested = item.raw().allocations().get(i).quantity();
                    storeAllocations.add(new PositionArtifactStore.Allocation(lot.id(),
                            requested == null ? lot.remaining() : requested, legRole(lot)));
                }
                var set = artifacts.recordNewStructureAction(c, new PositionArtifactStore.NewStructureAction(
                        owner, plan.id(), plan.context().rev(), item.raw().portfolioAccountId(),
                        anchor.openingTransactionId(), null, item.symbol(), trim(item.raw().label()),
                        PositionDomain.PositionState.OPEN, PositionDomain.PlanActionRole.ENTRY,
                        PositionDomain.ReceiptKind.ADOPTION, PositionDomain.ReceiptAuthority.USER_ALLOCATED,
                        now, evidenceLevel(marketKind), MODEL_VERSION, storeAllocations,
                        receiptLegs(item.lots(), item.raw().allocations())));
                Db.execOn(c, "UPDATE plans SET furthest_stage='MANAGE_REVIEW',version=version+1,updated_at=? WHERE id=?",
                        now, plan.id());
                Db.execOn(c, "INSERT INTO portfolio_adoption_request(user_id,client_request_id,input_hash,action,"
                                + "plan_id,structure_id,structure_revision_id,receipt_id,plan_action_id) "
                                + "VALUES(?,?,?,?,?,?,?,?,?)",
                        owner, requestId, item.inputHash(), item.action(), plan.id(), set.structureId(),
                        set.revisionId(), set.receiptId(), set.actionId());
                results.add(new PendingBatchResult(item.action(), plan.id(), set, false));
            }
            return List.copyOf(results);
        });
        List<BatchItemResult> out = new ArrayList<>(committed.size());
        List<Plan.View> changedPlans = new ArrayList<>();
        for (PendingBatchResult item : committed) {
            Plan.View plan = item.planId() == null ? null : plans.get(owner, item.planId());
            if (plan != null && !item.replayed()) changedPlans.add(plan);
            out.add(new BatchItemResult(item.action(), plan, item.artifacts(), item.replayed()));
        }
        plans.announceCommitted(changedPlans);
        int adopted = (int) out.stream().filter(i -> "ADOPT".equals(i.action()) && !i.replayed()).count();
        int linked = (int) out.stream().filter(i -> "LINK".equals(i.action()) && !i.replayed()).count();
        int skipped = (int) out.stream().filter(i -> "SKIP".equals(i.action())).count();
        int replayed = (int) out.stream().filter(BatchItemResult::replayed).count();
        return new BatchResult(List.copyOf(out), adopted, linked, skipped, replayed,
                "The confirmed batch is atomic: every selected Plan link and ADOPTION receipt committed together. Skipped positions were not changed.");
    }

    private PendingBatchItem prepareBatchItem(java.sql.Connection c, String owner, BatchItem raw)
            throws java.sql.SQLException {
        if (raw == null || raw.action() == null) throw new IllegalArgumentException("every batch item needs an action");
        String action = raw.action().trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("ADOPT", "LINK", "SKIP").contains(action)) {
            throw new IllegalArgumentException("batch action must be ADOPT, LINK, or SKIP");
        }
        if ("SKIP".equals(action)) return new PendingBatchItem(action, raw, null, List.of(), null, null);
        if (raw.clientRequestId() == null || raw.clientRequestId().isBlank()) {
            throw new IllegalArgumentException("every ADOPT or LINK choice needs a stable clientRequestId");
        }
        if (raw.portfolioAccountId() == null || raw.portfolioAccountId().isBlank()) {
            throw new IllegalArgumentException("every adopted or linked position needs its tracked account");
        }
        if (raw.allocations() == null || raw.allocations().isEmpty()) {
            throw new IllegalArgumentException("every adopted or linked position needs at least one lot");
        }
        String symbol = raw.symbol() == null ? null : raw.symbol().trim().toUpperCase(java.util.Locale.ROOT);
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("every adopted position needs a symbol");
        Request asRequest = new Request(raw.clientRequestId(), raw.portfolioAccountId(), symbol,
                raw.label(), raw.allocations());
        List<LotRow> lots = resolveLotsOn(c, owner, asRequest, true);
        if (lots.stream().anyMatch(lot -> !symbol.equals(lot.symbol()))) {
            throw new IllegalArgumentException("all lots in one batch position must match " + symbol);
        }
        if ("LINK".equals(action) && (raw.existingPlanId() == null || raw.existingPlanId().isBlank())) {
            throw new IllegalArgumentException("LINK needs the existing Plan id");
        }
        String inputHash = adoptionInputHash(action, raw, lots);
        String positionOwnerKey = "position-" + positionOwnerHash(raw, lots);
        return new PendingBatchItem(action, raw, symbol, lots, inputHash, positionOwnerKey);
    }

    private static ExistingAdoption existingAdoption(java.sql.Connection c, String owner, String requestId)
            throws java.sql.SQLException {
        List<ExistingAdoption> rows = Db.queryOn(c, "SELECT * FROM portfolio_adoption_request "
                        + "WHERE user_id=? AND client_request_id=?",
                r -> new ExistingAdoption(r.str("input_hash"), r.str("action"), r.str("plan_id"),
                        r.str("structure_id"), r.str("structure_revision_id"), r.str("receipt_id"),
                        r.str("plan_action_id")), owner, requestId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static String adoptionInputHash(String action, BatchItem raw, List<LotRow> lots) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("action", action);
        input.put("portfolioAccountId", raw.portfolioAccountId());
        input.put("symbol", raw.symbol() == null ? null : raw.symbol().trim().toUpperCase(java.util.Locale.ROOT));
        input.put("label", trim(raw.label()));
        input.put("existingPlanId", raw.existingPlanId());
        List<Map<String, Object>> allocations = new ArrayList<>();
        for (int i = 0; i < lots.size(); i++) {
            Long requested = raw.allocations().get(i).quantity();
            allocations.add(Map.of("lotId", lots.get(i).id(), "quantity",
                    requested == null ? lots.get(i).remaining() : requested));
        }
        input.put("allocations", allocations);
        return canonicalHash(input);
    }

    private static String positionOwnerHash(BatchItem raw, List<LotRow> lots) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("portfolioAccountId", raw.portfolioAccountId());
        identity.put("symbol", raw.symbol() == null ? null : raw.symbol().trim().toUpperCase(java.util.Locale.ROOT));
        List<Map<String, Object>> allocations = new ArrayList<>();
        for (int i = 0; i < lots.size(); i++) {
            Long requested = raw.allocations().get(i).quantity();
            Map<String, Object> allocation = new LinkedHashMap<>();
            allocation.put("lotId", lots.get(i).id());
            allocation.put("quantity", requested == null ? lots.get(i).remaining() : requested);
            allocations.add(allocation);
        }
        allocations.sort(java.util.Comparator.comparing(row -> (String) row.get("lotId")));
        identity.put("allocations", allocations);
        return canonicalHash(identity);
    }

    private static String canonicalHash(Object input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Json.canonical(input).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("could not fingerprint the adoption request", e);
        }
    }

    private Plan.View existingLinkPlan(java.sql.Connection c, String owner, String planId, String symbol,
                                       Plan.MarketKind marketKind, String worldId)
            throws java.sql.SQLException {
        List<String> ids = Db.queryOn(c, "SELECT id FROM plans WHERE id=? AND user_id=? AND status='ACTIVE' "
                        + "AND symbol=? AND market_kind=? AND world_id IS NOT DISTINCT FROM ? FOR UPDATE",
                r -> r.str("id"), planId, owner, symbol, marketKind.name(), worldId);
        if (ids.isEmpty()) throw new IllegalArgumentException(
                "the existing Plan is unavailable, read-only, for another symbol, or belongs to another market");
        // selectViewOn is intentionally private to PlanService; this read is safe because the
        // Plan existed before the batch and PlanService remains the owner of its shape.
        return plans.get(owner, ids.getFirst());
    }

    private List<LotRow> resolveLots(String owner, Request request) {
        return db.with(c -> {
            try { return resolveLotsOn(c, owner, request, false); }
            catch (java.sql.SQLException e) { throw new Db.DbException(e); }
        });
    }

    private List<LotRow> resolveLotsOn(java.sql.Connection c, String owner, Request request, boolean lock)
            throws java.sql.SQLException {
            List<LotRow> out = new ArrayList<>(request.allocations().size());
            Set<String> seen = new LinkedHashSet<>();
            for (Allocation allocation : request.allocations()) {
                if (allocation == null || allocation.lotId() == null || allocation.lotId().isBlank()) {
                    throw new IllegalArgumentException("every adoption allocation names a lot");
                }
                if (!seen.add(allocation.lotId())) {
                    throw new IllegalArgumentException("lot " + allocation.lotId()
                            + " appears more than once in the same adoption");
                }
                List<LotRow> rows = Db.queryOn(c, "SELECT pl.id,pl.side,pl.instrument_type,pl.option_type," +
                                "pl.remaining_quantity,pl.opening_transaction_id,pl.opened_at::text opened_at," +
                                "pl.symbol,pl.strike,pl.expiration,pl.multiplier,tl.price opening_price," +
                                "t.source opening_source,t.import_payload_fingerprint FROM portfolio_lot pl JOIN portfolio_account pa " +
                                "ON pa.id=pl.portfolio_account_id JOIN portfolio_transaction t " +
                                "ON t.id=pl.opening_transaction_id JOIN portfolio_transaction_leg tl " +
                                "ON tl.transaction_id=pl.opening_transaction_id AND tl.leg_no=pl.opening_leg_no " +
                                "WHERE pl.id=? AND pl.portfolio_account_id=? AND pa.user_id=?" + (lock ? " FOR UPDATE OF pl" : ""),
                        r -> new LotRow(r.str("id"), r.str("side"), r.str("instrument_type"),
                                r.str("option_type"), r.lng("remaining_quantity"),
                                r.str("opening_transaction_id"), r.str("opened_at"), r.str("symbol"),
                                r.bd("strike"), r.date("expiration"), r.intv("multiplier"),
                                r.bd("opening_price"), r.str("opening_source"),
                                r.str("import_payload_fingerprint")),
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

    private List<PositionArtifactStore.ReceiptLeg> receiptLegs(List<LotRow> lots,
                                                               List<Allocation> allocations) {
        List<PositionArtifactStore.ReceiptLeg> out = new ArrayList<>(lots.size());
        for (int i = 0; i < lots.size(); i++) {
            LotRow lot = lots.get(i);
            long quantity = allocations.get(i).quantity() == null ? lot.remaining() : allocations.get(i).quantity();
            java.math.BigDecimal bid = null, ask = null, mid = null;
            if (marks != null) try {
                if ("STOCK".equals(lot.instrumentType())) {
                    mid = marks.underlyingMark(lot.symbol()).orElse(null);
                } else {
                    var leg = new io.liftandshift.strikebench.model.Leg(
                            "LONG".equals(lot.side())
                                    ? io.liftandshift.strikebench.model.LegAction.BUY
                                    : io.liftandshift.strikebench.model.LegAction.SELL,
                            io.liftandshift.strikebench.model.OptionType.valueOf(lot.optionType()),
                            lot.strike(), lot.expiration(), 1, java.math.BigDecimal.ZERO, lot.multiplier());
                    MarksSource.LegMark mark = marks.legMark(lot.symbol(), leg).orElse(null);
                    if (mark != null) { bid = mark.bid(); ask = mark.ask(); mid = mark.mid(); }
                }
            } catch (RuntimeException ignored) { /* missing current mark stays explicitly null */ }
            out.add(new PositionArtifactStore.ReceiptLeg("AFTER", i, lot.instrumentType(),
                    "LONG".equals(lot.side()) ? "BUY" : "SELL", lot.symbol(), lot.optionType(),
                    lot.strike(), lot.expiration(), quantity, lot.multiplier(), bid, ask, mid,
                    lot.openingPrice(), lot.importPayloadFingerprint() != null
                            && !lot.importPayloadFingerprint().isBlank()
                    ? PositionDomain.PriceAuthority.BROKER_REPORTED
                    : PositionDomain.PriceAuthority.USER_REPORTED));
        }
        return List.copyOf(out);
    }

    private static String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private record LotRow(String id, String side, String instrumentType, String optionType,
                          long remaining, String openingTransactionId, String openedAt, String symbol,
                          java.math.BigDecimal strike, java.time.LocalDate expiration, int multiplier,
                          java.math.BigDecimal openingPrice, String openingSource,
                          String importPayloadFingerprint) {}
    private record PendingBatchItem(String action, BatchItem raw, String symbol, List<LotRow> lots,
                                    String inputHash, String positionOwnerKey) {}
    private record PendingBatchResult(String action, String planId,
                                      PositionArtifactStore.ArtifactSet artifacts, boolean replayed) {}
    private record ExistingAdoption(String inputHash, String action, String planId, String structureId,
                                    String revisionId, String receiptId, String actionId) {}
}
