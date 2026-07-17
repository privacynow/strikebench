package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.model.BroadBasedIndexOptions;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.position.PositionDomain;
import io.liftandshift.strikebench.position.PositionPackage;
import io.liftandshift.strikebench.position.PositionTransformation;
import io.liftandshift.strikebench.pricing.PayoffCurve;
import io.liftandshift.strikebench.util.Ids;
import io.liftandshift.strikebench.util.Json;
import io.liftandshift.strikebench.util.Money;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Paper trade lifecycle over the append-only ledger.
 *
 * Reserve semantics: the reserve holds the trade's remaining worst-case cash outflow —
 * max(0, maxLoss + netPremium). Debit trades reserve nothing extra (the premium already left
 * cash and caps the loss); credit trades reserve the gross width. Either way the net
 * buying-power impact at entry is exactly maxLoss + fees.
 *
 * Invariants (tested): sum of cash-moving ledger rows == account cash; sum of RESERVE_* rows ==
 * account reserve; fees are separate FEE rows; rejections write an audit row and nothing else.
 */
public final class TradeService {

    private static final double FALLBACK_IV = 0.30;

    private final Db db;
    private final AppConfig cfg;
    private final MarksSource marks;
    private final AuditLog audit;
    private final Clock clock;

    public TradeService(Db db, AppConfig cfg, MarksSource marks, AuditLog audit, Clock clock) {
        this.db = db;
        this.cfg = cfg;
        this.marks = marks;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * THE canonical OrderPackage: every entry path — recommendations, builder, guided ticket,
     * broker integration and tracked-book imports — produces exactly this typed package, and the
     * one evaluation pipeline consumes it. Package-level extras: {@code proposedNetCents} (signed:
     * + credit received / − debit paid; null = price at executable sides), {@code feesOverrideCents}
     * (null = platform default; a Practice ticket treats this as the fee per side),
     * {@code source} (RECOMMENDATION | BUILDER | TICKET |
     * IMPORT | BROKER).
     */
    public record OpenRequest(String accountId, String symbol, String strategy, int qty, List<Leg> legs,
                              String thesis, String horizon, String riskMode,
                              String intent, Boolean useHeldShares,
                              Long proposedNetCents, Long feesOverrideCents, String source,
                              String fillNature) {
        /** Jackson and every internal caller bind one complete position contract. */
        @com.fasterxml.jackson.annotation.JsonCreator
        public OpenRequest {}
        public boolean heldShares() { return Boolean.TRUE.equals(useHeldShares); }
        public boolean explicitFillMeaning() {
            return "PROPOSED".equalsIgnoreCase(fillNature) || "EXECUTED".equalsIgnoreCase(fillNature);
        }
        public boolean executedFill() {
            return "EXECUTED".equalsIgnoreCase(fillNature);
        }
    }

    /** Position greeks: share-equivalent delta/gamma, $ per day theta, $ per vol-point vega. Model stats, not money. */
    public record PositionGreeks(Double deltaShares, Double gammaShares, Double thetaPerDay, Double vegaPerPoint, boolean complete) {}

    /** Dollar-delta exposure for a lane-aware before/after assessment. */
    public record DollarDeltaExposure(long grossCents, long netCents, long focusSymbolGrossCents,
                                      boolean complete, String basis) {}

    public record MarkView(String tradeId, String ts, Long underlyingCents, Long closeCostCents,
                           Long unrealizedCents, Long decisionUnrealizedCents, Double popNow, String freshness,
                           PositionGreeks greeks, List<Map<String, Object>> legGreeks) {}

    /** Worst and best executable-mark excursions recorded while a trade was open. */
    public record Excursion(Long adverseCents, Long favorableCents) {}

    public record Page(List<TradeRecord> trades, long total, int page, int size) {}

    public record CloseResult(TradeRecord trade, long realizedPnlCents,
                              long actionRealizedPnlCents) {
        public CloseResult(TradeRecord trade, long realizedPnlCents) {
            this(trade, realizedPnlCents, realizedPnlCents);
        }
    }

    public record PartialCloseAssessment(PositionAssessment current, PositionAssessment survivor,
                                         int closeQuantity, long closingCashCents,
                                         long closingFeesCents, long reserveReleaseCents,
                                         long actionRealizedPnlCents) {}

    public record PartialCloseResult(TradeRecord trade, int closedQuantity,
                                     long actionRealizedPnlCents, long realizedPnlCents) {}

    /**
     * One exact leg/stock adjustment. Retained lots keep their persisted fills; removed lots use
     * executable closing sides and added lots use executable opening sides from the existing
     * pricing path. The package-level entry-price adjustment and opening fees are allocated as
     * accounting basis only, never written back as fabricated per-leg fills.
     */
    public record AdjustmentAssessment(PositionAssessment current, PositionAssessment survivor,
                                       OpenRequest exactAfterRequest,
                                       long closingCashCents, long openingCashCents,
                                       long closingFeesCents, long openingFeesCents,
                                       long allocatedEntryBasisCents, long allocatedOpenFeesCents,
                                       long actionRealizedPnlCents, long realizedPnlToDateCents,
                                       long reserveBeforeCents, long reserveAfterCents,
                                       long sharesLockedAfter,
                                       long projectedCashAfterCents, long projectedReservedAfterCents,
                                       long placementCashBeforeCents, long placementReservedBeforeCents,
                                       List<String> basisNotes) {}

    public record AdjustmentResult(TradeRecord trade, long actionRealizedPnlCents,
                                   long realizedPnlCents) {}

    /**
     * One option lifecycle event projected against the account's own lane clock. The option
     * accounting result and the physical stock cash are deliberately separate: assignment or
     * exercise is not a closing fill, and the strike cash is not option P/L.
     */
    public record LifecycleAssessment(PositionAssessment current, PositionAssessment survivor,
                                      OpenRequest exactSurvivorRequest,
                                      PositionTransformation.Action action, int legIndex,
                                      String contract, LocalDate expiration,
                                      long settlementUnderlyingCents, String settlementPriceBasis,
                                      long optionSettlementCashCents, long stockCashCents,
                                      long sharesDelta, long allocatedEntryBasisCents,
                                      long allocatedOpenFeesCents, long actionRealizedPnlCents,
                                      long decisionPnlDeltaCents, long realizedPnlToDateCents,
                                      long reserveBeforeCents,
                                      long reserveAfterCents, long heldShareContextAfter,
                                      long sharesLockedAfter, long projectedCashAfterCents,
                                      long projectedReservedAfterCents, List<String> basisNotes,
                                      String exactStateFingerprint) {}

    public record LifecycleResult(TradeRecord trade, long actionRealizedPnlCents,
                                  long realizedPnlCents, long sharesDelta,
                                  long stockCashCents) {}

    public record ExpectedLifecycle(long settlementUnderlyingCents,
                                    long optionSettlementCashCents,
                                    long stockCashCents, long sharesDelta,
                                    long allocatedEntryBasisCents,
                                    long allocatedOpenFeesCents,
                                    long reserveAfterCents, long heldShareContextAfter,
                                    long sharesLockedAfter, String exactStateFingerprint) {}

    public record ExpectedAdjustment(long closingCashCents, long openingCashCents,
                                     long closingFeesCents, long openingFeesCents,
                                     long entryNetCents, long feesOpenCents,
                                     long reserveAfterCents, long maxLossCents,
                                     Long maxProfitCents, long sharesLocked,
                                     String legsFingerprint) {}

    public record RollResult(TradeRecord closedTrade, TradeRecord replacementTrade,
                             long realizedClosingCents, long actionRealizedClosingCents) {}

    public record ExpectedOpen(long entryNetCents, long feesCents, long reserveCents,
                               long maxLossCents, Long maxProfitCents) {}

    public record ExpectedPositionState(int quantity, long entryNetCents, long feesOpenCents,
                                        long maxLossCents, Long maxProfitCents, long sharesLocked,
                                        long realizedPnlCents, long reserveCents) {}

    /** Fresh-eyes assessment of the current exact Practice position through the existing pricing path. */
    public record PositionAssessment(PositionPackage position, TradePreview preview,
                                     PositionTransformation.RiskSnapshot risk) {}

    /** Executable close cash and realized result paired with the same fresh-eyes position assessment. */
    public record UnwindAssessment(PositionAssessment current, long closingCashCents,
                                   long closingFeesCents, long actionRealizedPnlCents,
                                   long realizedPnlToDateCents) {}

    /** Optional owner hook for workflows that must commit their identity beside the paper trade. */
    @FunctionalInterface
    public interface TransactionHook {
        void afterTradeCreated(Connection connection, TradeRecord trade) throws SQLException;
    }

    @FunctionalInterface
    public interface LifecycleHook {
        void afterMutation(Connection connection, TradeRecord trade, Long actionRealizedPnlCents,
                           Long realizedPnlToDateCents) throws SQLException;
    }

    @FunctionalInterface
    public interface RollHook {
        void afterRoll(Connection connection, TradeRecord closedTrade, TradeRecord replacementTrade,
                       long actionRealizedClosingCents, long realizedClosingToDateCents) throws SQLException;
    }

    // ---- Preview / create ----

    public TradePreview preview(OpenRequest req) {
        Account acct = db.with(c -> AccountService.get(c, req.accountId()));
        return preview(req, acct.cashCents(), acct.reservedCents(), 0);
    }

    /**
     * Prices the same executable order against a projected Practice balance. Transformations use
     * this after valuing the close so the replacement is judged with the cash, reserve, and held
     * shares that the atomic close will actually release.
     */
    public TradePreview preview(OpenRequest req, long cashBeforeCents, long reservedBeforeCents,
                                long releasedShares) {
        // Import/broker previews describe an actual fill that already happened. Ordinary ticket
        // previews describe a paper order and therefore cannot claim a favorable resting limit filled.
        Plan p = computePlan(req, req.executedFill());
        return previewFromPlan(req, p, cashBeforeCents, reservedBeforeCents, releasedShares);
    }

    private TradePreview previewFromPlan(OpenRequest req, Plan p, long cashBeforeCents,
                                         long reservedBeforeCents, long releasedShares) {
        if (cashBeforeCents < 0 || reservedBeforeCents < 0 || releasedShares < 0) {
            throw new IllegalArgumentException("projected Practice balances cannot be negative");
        }
        long buyingPowerBefore = Math.subtractExact(cashBeforeCents, reservedBeforeCents);
        long cashAfter = Math.subtractExact(Math.addExact(cashBeforeCents, p.entryNet), p.fees);
        long reservedAfter = Math.addExact(reservedBeforeCents, p.reserve);
        List<String> blocks = new ArrayList<>(p.blocks);
        if (p.blocks.isEmpty() && req.heldShares()) {
            long needed = Math.max(p.sharesToLock(), Math.multiplyExact(heldShareUnitsPerPackage(req.legs()), req.qty()));
            long free = Math.addExact(db.with(c -> PositionsService.heldShares(c, req.accountId(),
                            req.symbol().toUpperCase(java.util.Locale.ROOT))
                    - PositionsService.lockedShares(c, req.accountId(),
                            req.symbol().toUpperCase(java.util.Locale.ROOT))), releasedShares);
            if (free < needed) {
                blocks.add("Needs " + needed + " free shares of " + req.symbol().toUpperCase(java.util.Locale.ROOT)
                        + " but only " + Math.max(0, free) + " are free (held minus already locked)");
            }
        }
        if (p.blocks.isEmpty() && blocks.isEmpty() && cashAfter - reservedAfter < 0) {
            blocks.add("Insufficient buying power: needs " + Money.fmt(p.maxLoss + p.fees)
                    + " but only " + Money.fmt(buyingPowerBefore) + " is available");
        }
        return new TradePreview(blocks.isEmpty(), blocks, p.warnings,
                p.entryNet, p.fees, p.maxLoss, p.maxProfit, p.breakevens, p.pop, p.ev, p.reserve,
                cashBeforeCents, blocks.isEmpty() ? cashAfter : cashBeforeCents,
                reservedBeforeCents, blocks.isEmpty() ? reservedAfter : reservedBeforeCents,
                buyingPowerBefore, blocks.isEmpty() ? cashAfter - reservedAfter : buyingPowerBefore,
                p.freshness.name(), entryEvidence(req.accountId(), p.freshness), p.underlyingCents,
                p.assignmentProb(), p.legDetails(), p.payoff(), p.analytics());
    }

    /**
     * Read-only analysis for a Practice-market package. Unlike {@link #preview(OpenRequest)},
     * this may use explicitly labeled non-executable evidence and can preserve entered fills
     * when a current contract mark is unavailable. It can never be reused as placement approval.
     */
    public TradePreview analyze(OpenRequest req) {
        Account acct = db.with(c -> AccountService.get(c, req.accountId()));
        Plan p = computePlan(req, req.executedFill(), null, false, null, true);
        long cashAfter = acct.cashCents() + p.entryNet - p.fees;
        long reservedAfter = acct.reservedCents() + p.reserve;
        List<String> blocks = new ArrayList<>(p.blocks);
        if (p.blocks.isEmpty() && req.heldShares()) {
            long needed = Math.max(p.sharesToLock(), Math.multiplyExact(heldShareUnitsPerPackage(req.legs()), req.qty()));
            long free = db.with(c -> PositionsService.heldShares(c, req.accountId(), req.symbol().toUpperCase(java.util.Locale.ROOT))
                    - PositionsService.lockedShares(c, req.accountId(), req.symbol().toUpperCase(java.util.Locale.ROOT)));
            if (free < needed) {
                blocks.add("Needs " + needed + " free shares of " + req.symbol().toUpperCase(java.util.Locale.ROOT)
                        + " but only " + Math.max(0, free) + " are free (held minus already locked)");
            }
        }
        if (p.blocks.isEmpty() && blocks.isEmpty() && cashAfter - reservedAfter < 0) {
            blocks.add("This Practice account has " + Money.fmt(acct.buyingPowerCents())
                    + " buying power; the analyzed package needs " + Money.fmt(p.maxLoss + p.fees)
                    + ". Analysis remains visible, but the account cannot fund it as entered.");
        }
        return new TradePreview(blocks.isEmpty(), blocks, p.warnings,
                p.entryNet, p.fees, p.maxLoss, p.maxProfit, p.breakevens, p.pop, p.ev, p.reserve,
                acct.cashCents(), blocks.isEmpty() ? cashAfter : acct.cashCents(),
                acct.reservedCents(), blocks.isEmpty() ? reservedAfter : acct.reservedCents(),
                acct.buyingPowerCents(), blocks.isEmpty() ? cashAfter - reservedAfter : acct.buyingPowerCents(),
                p.freshness.name(), entryEvidence(req.accountId(), p.freshness), p.underlyingCents,
                p.assignmentProb(), p.legDetails(), p.payoff(), p.analytics());
    }

    /**
     * Reprices an existing Practice position as one fresh-eyes package without pretending it is a
     * second order or charging its full reserve against the account again. All fills, risk, POP,
     * and evidence still come from {@link #computePlan(OpenRequest, boolean)}.
     */
    public PositionAssessment analyzeActivePosition(String tradeId) {
        TradeRecord trade = get(tradeId);
        if (!TradeRecord.ACTIVE.equals(trade.status())) {
            throw new IllegalStateException("trade is " + trade.status() + "; only ACTIVE trades can be transformed");
        }
        OpenRequest request = activePositionRequest(trade, trade.qty(), trade.sharesLocked());
        PositionAssessment assessed = analyzePositionPackage(trade.id(), PositionDomain.PackageSource.PRACTICE_TRADE,
                PositionDomain.ExecutionLane.PRACTICE, request);
        long outstandingReserve = db.with(c -> outstandingReserve(c, trade.id()));
        PositionTransformation.RiskSnapshot bookRisk = new PositionTransformation.RiskSnapshot(
                trade.maxLossCents(), outstandingReserve, trade.maxProfitCents(), true, List.of(),
                assessed.risk().evidenceBasis());
        return new PositionAssessment(assessed.position(), assessed.preview(), bookRisk);
    }

    /** Adapts an exact proposed package to the shared position contract through this service's one pricing path. */
    public PositionAssessment analyzePositionPackage(String packageId, PositionDomain.PackageSource source,
                                                     PositionDomain.ExecutionLane lane, OpenRequest request) {
        Account account = db.with(c -> AccountService.get(c, request.accountId()));
        return analyzePositionPackage(packageId, source, lane, request, account.cashCents(),
                account.reservedCents(), 0);
    }

    public PositionAssessment analyzePositionPackage(String packageId, PositionDomain.PackageSource source,
                                                     PositionDomain.ExecutionLane lane, OpenRequest request,
                                                     long cashBeforeCents, long reservedBeforeCents,
                                                     long releasedShares) {
        if (lane != PositionDomain.ExecutionLane.PRACTICE) {
            throw new IllegalArgumentException("TradeService position analysis owns the Practice lane only");
        }
        if (packageId == null || packageId.isBlank() || source == null || request == null) {
            throw new IllegalArgumentException("complete position-package identity is required");
        }
        Plan plan = computePlan(request, false);
        TradePreview preview = previewFromPlan(request, plan, cashBeforeCents, reservedBeforeCents, releasedShares);
        return assessmentFromPlan(packageId, source, lane, request, plan, preview);
    }

    private PositionAssessment assessmentFromPlan(String packageId, PositionDomain.PackageSource source,
                                                  PositionDomain.ExecutionLane lane, OpenRequest request,
                                                  Plan plan, TradePreview preview) {
        List<Leg> assessedLegs = plan.filledLegs().isEmpty() ? request.legs() : plan.filledLegs();
        PositionDomain.PriceAuthority authority = switch (plan.freshness()) {
            case REALTIME, DELAYED, EOD -> PositionDomain.PriceAuthority.OBSERVED;
            default -> PositionDomain.PriceAuthority.MODELED;
        };
        List<PositionPackage.Leg> packageLegs = new ArrayList<>();
        for (int i = 0; i < assessedLegs.size(); i++) {
            Leg leg = assessedLegs.get(i);
            packageLegs.add(new PositionPackage.Leg(i, leg.action().name(), leg.isStock() ? "STOCK" : "OPTION",
                    request.symbol(), leg.isStock() ? null : leg.type().name(), leg.strike(), leg.expiration(),
                    Math.multiplyExact(request.qty(), (long) leg.ratio()), leg.multiplier(), leg.entryPrice(), authority));
        }
        PositionPackage position = new PositionPackage(packageId, source, lane, request.symbol(), request.qty(), plan.entryNet(),
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), packageLegs);
        return new PositionAssessment(position, preview, riskSnapshot(preview));
    }

    private static PositionTransformation.RiskSnapshot riskSnapshot(TradePreview preview) {
        io.liftandshift.strikebench.eval.EvidenceLevel evidence =
                io.liftandshift.strikebench.eval.EvidenceLevel.fromEvidence(preview.evidence());
        return new PositionTransformation.RiskSnapshot(preview.maxLossCents(), preview.reserveCents(),
                preview.maxProfitCents(), preview.ok(), preview.blockReasons(), evidence.name());
    }

    public UnwindAssessment previewUnwind(String tradeId) {
        PositionAssessment current = analyzeActivePosition(tradeId);
        TradeRecord trade = get(tradeId);
        ExecutableClose close = executableClose(trade);
        long closingFees = close.feesCents();
        long actionRealized = Math.subtractExact(Math.addExact(
                Math.subtractExact(trade.entryNetPremiumCents(), trade.feesOpenCents()), close.cashCents()),
                closingFees);
        long realizedToDate = Math.addExact(trade.realizedPnlCents() == null ? 0 : trade.realizedPnlCents(),
                actionRealized);
        return new UnwindAssessment(current, close.cashCents(), closingFees, actionRealized, realizedToDate);
    }

    /**
     * Reviews closing whole packages while retaining the exact original basis of every survivor.
     * The after-position is repriced fresh-eyes, but no survivor is treated as a new fill.
     */
    public PartialCloseAssessment previewPartialClose(String tradeId, int closeQuantity) {
        TradeRecord trade = get(tradeId);
        requirePracticeTransformation(trade);
        requirePartialQuantity(trade, closeQuantity);
        PositionAssessment current = analyzeActivePosition(tradeId);
        ExecutableClose close = executableClose(trade, closeQuantity);
        long actionEntry = allocatedPrefix(trade.entryNetPremiumCents(), trade.qty(), closeQuantity);
        long actionOpenFees = allocatedPrefix(trade.feesOpenCents(), trade.qty(), closeQuantity);
        long actionRealized = Math.subtractExact(Math.addExact(
                Math.subtractExact(actionEntry, actionOpenFees), close.cashCents()), close.feesCents());
        int survivingQuantity = trade.qty() - closeQuantity;
        long outstanding = db.with(c -> outstandingReserve(c, trade.id()));
        long reserveRelease = allocatedPrefix(outstanding, trade.qty(), closeQuantity);
        long survivingReserve = Math.subtractExact(outstanding, reserveRelease);
        long survivingShares = Math.subtractExact(trade.sharesLocked(),
                allocatedPrefix(trade.sharesLocked(), trade.qty(), closeQuantity));
        Account account = db.with(c -> AccountService.get(c, trade.accountId()));
        long projectedCash = Math.subtractExact(Math.addExact(account.cashCents(), close.cashCents()),
                close.feesCents());
        long projectedReservedWithoutPosition = Math.subtractExact(account.reservedCents(), outstanding);
        OpenRequest survivorRequest = activePositionRequest(trade, survivingQuantity, survivingShares);
        PositionAssessment survivor = analyzePositionPackage(trade.id(),
                PositionDomain.PackageSource.PRACTICE_TRADE, PositionDomain.ExecutionLane.PRACTICE,
                survivorRequest, projectedCash, projectedReservedWithoutPosition, trade.sharesLocked());
        PositionTransformation.RiskSnapshot fresh = survivor.risk();
        PositionTransformation.RiskSnapshot bookRisk = new PositionTransformation.RiskSnapshot(
                remainingAllocation(trade.maxLossCents(), trade.qty(), closeQuantity), survivingReserve,
                trade.maxProfitCents() == null ? null
                        : remainingAllocation(trade.maxProfitCents(), trade.qty(), closeQuantity),
                fresh.mechanicallyEligible(), fresh.blockReasons(), fresh.evidenceBasis());
        survivor = new PositionAssessment(survivor.position(), survivor.preview(), bookRisk);
        return new PartialCloseAssessment(current, survivor, closeQuantity, close.cashCents(),
                close.feesCents(), reserveRelease, actionRealized);
    }

    /**
     * Reviews one exact composition change without closing and reopening retained lots. This is the
     * Practice implementation behind LEG_CLOSE/REMOVE_LEG/ADD_LEG/ADD_STOCK/REMOVE_STOCK.
     */
    public AdjustmentAssessment previewAdjustment(String tradeId, PositionTransformation.Action action,
                                                  OpenRequest desiredAfter) {
        TradeRecord trade = get(tradeId);
        requirePracticeTransformation(trade);
        if (!adjustmentAction(action)) {
            throw new IllegalArgumentException("action is not a leg or stock adjustment");
        }
        if (!TradeRecord.ACTIVE.equals(trade.status())) {
            throw new IllegalStateException("trade is " + trade.status() + "; only ACTIVE trades can be transformed");
        }
        if (desiredAfter == null || !trade.accountId().equals(desiredAfter.accountId())
                || !trade.symbol().equalsIgnoreCase(desiredAfter.symbol())) {
            throw new IllegalArgumentException("the surviving position must use the same Practice account and symbol");
        }
        if (desiredAfter.heldShares() != (trade.sharesLocked() > 0)) {
            throw new IllegalArgumentException("leg editing cannot silently add or remove held-share coverage");
        }

        PositionAssessment current = analyzeActivePosition(tradeId);
        ReconciledLots reconciled = reconcileLots(trade, desiredAfter);
        if (reconciled.removed().isEmpty() && reconciled.added().isEmpty()) {
            throw new IllegalArgumentException("the proposed action does not change the position");
        }

        List<LegLot> filledAdded = priceAddedLots(trade, reconciled.added());
        List<LegLot> exactLots = new ArrayList<>(reconciled.retained());
        exactLots.addAll(filledAdded);
        if (exactLots.isEmpty()) {
            throw new IllegalArgumentException("use CLOSE when no position survives");
        }

        long currentLegCash = cashForLots(lotsFromTrade(trade));
        long packageAdjustment = Math.subtractExact(trade.entryNetPremiumCents(), currentLegCash);
        long totalBasisUnits = basisUnits(lotsFromTrade(trade));
        long removedBasisUnits = basisUnits(reconciled.removed());
        long allocatedPackageAdjustment = allocatedPrefix(packageAdjustment, totalBasisUnits, removedBasisUnits);
        long allocatedEntryBasis = Math.addExact(cashForLots(reconciled.removed()), allocatedPackageAdjustment);
        long remainingPackageAdjustment = Math.subtractExact(packageAdjustment, allocatedPackageAdjustment);

        long allocatedOpenFees = allocatedPrefix(trade.feesOpenCents(), totalBasisUnits, removedBasisUnits);
        long openingFees = filledAdded.isEmpty() ? 0 : feesFor(canonicalLegs(filledAdded), canonicalQuantity(filledAdded));
        long closingFees = reconciled.removed().isEmpty() ? 0 : closeFeesForLots(trade, reconciled.removed());
        long closingCash = reconciled.removed().isEmpty() ? 0 : executableCloseLots(trade, reconciled.removed());
        long openingCash = cashForLots(filledAdded);
        long actionRealized = Math.subtractExact(Math.addExact(
                Math.subtractExact(allocatedEntryBasis, allocatedOpenFees), closingCash), closingFees);
        long realizedToDate = Math.addExact(trade.realizedPnlCents() == null ? 0 : trade.realizedPnlCents(),
                actionRealized);

        int exactQuantity = canonicalQuantity(exactLots);
        List<Leg> exactLegs = canonicalLegs(exactLots);
        long exactEntry = Math.addExact(cashForLots(exactLots), remainingPackageAdjustment);
        long exactOpenFees = Math.addExact(Math.subtractExact(trade.feesOpenCents(), allocatedOpenFees), openingFees);
        OpenRequest exactAfter = new OpenRequest(trade.accountId(), trade.symbol(), desiredAfter.strategy(),
                exactQuantity, exactLegs, trade.thesis(), trade.horizon(), trade.riskMode(), trade.intent(),
                trade.sharesLocked() > 0, exactEntry, exactOpenFees,
                "POSITION_TRANSFORMATION", "EXECUTED");

        String world = worldOf(trade.accountId());
        Plan exactPlan = computePlan(exactAfter, true, world, true, laneFor(world), true);
        Account account = db.with(c -> AccountService.get(c, trade.accountId()));
        long reserveBefore = db.with(c -> outstandingReserve(c, trade.id()));
        long projectedCash = Math.subtractExact(Math.addExact(
                Math.addExact(account.cashCents(), closingCash), openingCash),
                Math.addExact(closingFees, openingFees));
        long placementReservedBefore = Math.subtractExact(account.reservedCents(), reserveBefore);
        long projectedReserved = Math.addExact(placementReservedBefore, exactPlan.reserve());
        long placementCashBefore = Math.addExact(Math.subtractExact(projectedCash, exactPlan.entryNet()),
                exactPlan.fees());
        TradePreview exactPreview = previewFromPlan(exactAfter, exactPlan, placementCashBefore,
                placementReservedBefore, trade.sharesLocked());
        PositionAssessment survivor = assessmentFromPlan(trade.id(), PositionDomain.PackageSource.PRACTICE_TRADE,
                PositionDomain.ExecutionLane.PRACTICE, exactAfter, exactPlan, exactPreview);

        List<String> notes = new ArrayList<>();
        notes.add("Retained quantities keep their exact stored fills; only changed quantities trade at current executable sides.");
        if (allocatedPackageAdjustment != 0) {
            notes.add("The package-level entry-price adjustment contributes " + Money.fmt(allocatedPackageAdjustment)
                    + " to the removed basis by deterministic quantity allocation. It is accounting basis, not a fabricated leg fill.");
        }
        if (allocatedOpenFees != 0) {
            notes.add(Money.fmt(allocatedOpenFees)
                    + " of prior opening fees is assigned to the removed quantity; the remainder stays with the open position.");
        }
        return new AdjustmentAssessment(current, survivor, exactAfter, closingCash, openingCash,
                closingFees, openingFees, allocatedEntryBasis, allocatedOpenFees,
                actionRealized, realizedToDate, reserveBefore, exactPlan.reserve(), exactPlan.sharesToLock(),
                projectedCash, projectedReserved, placementCashBefore, placementReservedBefore,
                List.copyOf(notes));
    }

    /** Projects one assignment, exercise, or expiry through the same lane clock and pricing spine as settlement. */
    public LifecycleAssessment previewLifecycleConversion(String tradeId,
                                                          PositionTransformation.Action action,
                                                          int legIndex) {
        return db.with(c -> {
            TradeRecord trade = getOn(c, tradeId);
            requirePracticeTransformation(trade);
            if (!TradeRecord.ACTIVE.equals(trade.status())) {
                throw new IllegalStateException("trade is " + trade.status()
                        + "; only ACTIVE trades can convert an option lifecycle event");
            }
            return projectLifecycleConversion(c, trade, action, legIndex);
        });
    }

    private LifecycleAssessment projectLifecycleConversion(Connection c, TradeRecord trade,
                                                            PositionTransformation.Action action,
                                                            int legIndex) throws SQLException {
        if (!lifecycleAction(action)) {
            throw new IllegalArgumentException("action must be ASSIGNMENT, EXERCISE, or EXPIRATION");
        }
        if (legIndex < 0 || legIndex >= trade.legs().size()) {
            throw new IllegalArgumentException("legIndex must identify one current option leg");
        }
        Leg selected = trade.legs().get(legIndex);
        if (selected.isStock()) throw new IllegalArgumentException("option lifecycle actions require an option leg");
        boolean cashSettled = BroadBasedIndexOptions.isKnownRoot(trade.symbol());

        String world = worldOf(trade.accountId());
        Instant laneNow = nowFor(world);
        SettlementReference reference = settlementReference(trade, selected, action, world, laneNow);
        BigDecimal intrinsic = selected.intrinsicPerShare(reference.underlying());
        if (cashSettled && action != PositionTransformation.Action.EXPIRATION) {
            throw new TradeRejectedException(List.of(trade.symbol()
                    + " is a cash-settled broad-based index option. It cannot deliver shares through assignment or exercise; record its expiration settlement instead."));
        }
        if (!cashSettled && action == PositionTransformation.Action.EXPIRATION && intrinsic.signum() != 0) {
            throw new TradeRejectedException(List.of("This " + legDesc(selected)
                    + " finished in the money. Review assignment or exercise instead of treating it as worthless."));
        }
        if (action == PositionTransformation.Action.ASSIGNMENT && selected.action() != LegAction.SELL) {
            throw new IllegalArgumentException("ASSIGNMENT requires a short option leg");
        }
        if (action == PositionTransformation.Action.EXERCISE && selected.action() != LegAction.BUY) {
            throw new IllegalArgumentException("EXERCISE requires a long option leg");
        }
        if (action != PositionTransformation.Action.EXPIRATION && intrinsic.signum() <= 0) {
            throw new TradeRejectedException(List.of("This " + legDesc(selected)
                    + " has no intrinsic value at the lane's current price; physical conversion would be uneconomic."));
        }

        List<LegLot> allLots = lotsFromTrade(trade);
        LegLot removed = allLots.get(legIndex);
        List<LegLot> retained = new ArrayList<>(allLots);
        retained.remove(legIndex);
        long removedUnits = removed.quantity();
        long deliverableShares = Math.multiplyExact(removedUnits, (long) selected.multiplier());
        long sharesDelta = switch (action) {
            case ASSIGNMENT -> selected.type() == io.liftandshift.strikebench.model.OptionType.PUT
                    ? deliverableShares : -deliverableShares;
            case EXERCISE -> selected.type() == io.liftandshift.strikebench.model.OptionType.CALL
                    ? deliverableShares : -deliverableShares;
            case EXPIRATION -> 0;
            default -> throw new IllegalStateException("unreachable lifecycle action");
        };
        long optionSettlementCash = cashSettled
                ? closeSign(selected) * Money.centsFromPrice(intrinsic, deliverableShares) : 0;

        PositionsService.Position holding = PositionsService.find(c, trade.accountId(), trade.symbol());
        long heldShares = holding == null ? 0 : holding.shares();
        long lockedShares = PositionsService.lockedShares(c, trade.accountId(), trade.symbol());
        if (sharesDelta < 0) {
            long needed = -sharesDelta;
            if (action == PositionTransformation.Action.ASSIGNMENT) {
                if (trade.sharesLocked() < needed) {
                    throw new TradeRejectedException(List.of("This short call is not backed by " + needed
                            + " shares locked to this Practice position. StrikeBench will not disguise a short-stock assignment as cash settlement."));
                }
            } else {
                // Shares pledged to THIS package are exactly the shares its long put is entitled
                // to deliver. Only locks owned by other active positions reduce availability.
                long otherPositionLocks = Math.max(0, lockedShares - trade.sharesLocked());
                long free = heldShares - otherPositionLocks;
                if (free < needed) {
                    throw new TradeRejectedException(List.of("Exercising this put would deliver " + needed
                            + " shares, but only " + Math.max(0, free)
                            + " are free after other position locks. Close the conflicting obligation first."));
                }
            }
        }

        long strikePerShareCents = Money.toCents(selected.strike());
        long stockCash = sharesDelta == 0 ? 0
                : -Math.multiplyExact(sharesDelta, strikePerShareCents);
        ProjectedHolding projectedHolding = projectHolding(holding, sharesDelta, strikePerShareCents);
        long heldContextBefore = heldShareContextShares(trade);
        long heldContextAfter = Math.max(0, Math.addExact(heldContextBefore, sharesDelta));

        long totalBasisUnits = basisUnits(allLots);
        long currentLegCash = cashForLots(allLots);
        long packageAdjustment = Math.subtractExact(trade.entryNetPremiumCents(), currentLegCash);
        long allocatedPackageAdjustment = allocatedPrefix(packageAdjustment, totalBasisUnits, removedUnits);
        long allocatedEntryBasis = Math.addExact(cashForLots(List.of(removed)), allocatedPackageAdjustment);
        long allocatedOpenFees = allocatedPrefix(trade.feesOpenCents(), totalBasisUnits, removedUnits);
        long actionRealized = Math.addExact(
                Math.subtractExact(allocatedEntryBasis, allocatedOpenFees), optionSettlementCash);
        long realizedToDate = Math.addExact(trade.realizedPnlCents() == null ? 0 : trade.realizedPnlCents(),
                actionRealized);
        long remainingPackageAdjustment = Math.subtractExact(packageAdjustment, allocatedPackageAdjustment);
        long remainingOpenFees = Math.subtractExact(trade.feesOpenCents(), allocatedOpenFees);

        Account account = AccountService.get(c, trade.accountId());
        long reserveBefore = outstandingReserve(c, trade.id());
        long projectedCash = Math.addExact(Math.addExact(account.cashCents(), optionSettlementCash), stockCash);
        long reservedWithoutCurrent = Math.subtractExact(account.reservedCents(), reserveBefore);
        OpenRequest exactAfter = null;
        Plan exactAfterPlan = null;
        long reserveAfter = 0;
        long sharesLockedAfter = 0;
        if (!retained.isEmpty()) {
            int quantity = canonicalQuantity(retained);
            String strategyAfter = lifecycleStrategy(trade.symbol(), retained, heldContextAfter,
                    projectedHolding.afterBasisCents());
            String intentAfter = lifecycleIntent(strategyAfter, trade.intent());
            exactAfter = new OpenRequest(trade.accountId(), trade.symbol(), strategyAfter, quantity,
                    canonicalLegs(retained), trade.thesis(), trade.horizon(), trade.riskMode(), intentAfter,
                    heldContextAfter > 0, Math.addExact(cashForLots(retained), remainingPackageAdjustment),
                    remainingOpenFees, "POSITION_TRANSFORMATION", "EXECUTED");
            exactAfterPlan = computePlan(exactAfter, true, world, true, laneFor(world), true);
            reserveAfter = exactAfterPlan.reserve();
            sharesLockedAfter = exactAfterPlan.sharesToLock();
        }
        long projectedReserved = Math.addExact(reservedWithoutCurrent, reserveAfter);

        PositionAssessment current = lifecycleAssessment(c, trade, allLots, heldContextBefore,
                holding == null ? trade.entryUnderlyingCents() : holding.avgCostCents(),
                packageAdjustment, trade.feesOpenCents(), account.cashCents(), reservedWithoutCurrent,
                reserveBefore, world);
        PositionAssessment survivor = null;
        if (!retained.isEmpty() || heldContextAfter > 0) {
            survivor = lifecycleAssessment(c, trade, retained, heldContextAfter,
                    projectedHolding.afterBasisCents(), remainingPackageAdjustment, remainingOpenFees,
                    projectedCash, reservedWithoutCurrent, reserveAfter, world);
            List<String> blocks = new ArrayList<>(survivor.risk().blockReasons());
            if (exactAfterPlan != null) blocks.addAll(exactAfterPlan.blocks());
            if (action == PositionTransformation.Action.EXERCISE
                    && projectedCash - projectedReserved < 0) {
                blocks.add("Exercise needs " + Money.fmt(-stockCash)
                        + " of strike cash and would exceed current Practice buying power.");
            }
            var risk = new PositionTransformation.RiskSnapshot(survivor.risk().maxLossCents(),
                    reserveAfter, survivor.risk().maxProfitCents(), blocks.isEmpty(),
                    blocks.stream().distinct().toList(), survivor.risk().evidenceBasis());
            survivor = new PositionAssessment(survivor.position(), survivor.preview(), risk);
        }

        long optionIntrinsicCash = closeSign(selected)
                * Money.centsFromPrice(intrinsic, deliverableShares);
        long contextConverted = Math.min(heldContextBefore, Math.max(0, -sharesDelta));
        long decisionDelta = cashSettled ? actionRealized : Math.addExact(actionRealized, optionIntrinsicCash);
        if (contextConverted > 0 && trade.entryUnderlyingCents() > 0) {
            decisionDelta = Math.addExact(decisionDelta, Math.multiplyExact(
                    reference.underlyingCents() - trade.entryUnderlyingCents(), contextConverted));
        }
        List<String> notes = new ArrayList<>();
        notes.add(cashSettled
                ? "The option leg settles its intrinsic value in cash; no stock delivery or strike purchase is fabricated."
                : "The option leg converts at the contract strike; strike cash is shown separately from option P/L.");
        notes.add("Surviving option quantities keep their exact stored fills and opening-fee basis.");
        if (cashSettled) {
            notes.add("This broad-based index option settles intrinsic value in cash and never creates or delivers shares.");
        } else if (action == PositionTransformation.Action.EXERCISE
                && !io.liftandshift.strikebench.market.MarketHours.contractDead(selected.expiration(), laneNow)) {
            notes.add("Early exercise gives up any remaining extrinsic value. Compare an executable option sale before applying this event.");
        } else if (action == PositionTransformation.Action.ASSIGNMENT
                && !io.liftandshift.strikebench.market.MarketHours.contractDead(selected.expiration(), laneNow)) {
            notes.add("Early assignment is an event you are recording, not a prediction from the current mark.");
        }
        if (!reference.exact()) notes.add(reference.basis());
        if (action != PositionTransformation.Action.EXPIRATION
                && !io.liftandshift.strikebench.market.MarketHours.contractDead(selected.expiration(), laneNow)) {
            notes.add("This is an early " + action.name().toLowerCase(java.util.Locale.ROOT)
                    + " event against the active lane mark, not an expiration forecast.");
        }
        String fingerprint = lifecycleStateFingerprint(trade, action, legIndex,
                reference.underlyingCents(), optionSettlementCash, stockCash, heldContextAfter, exactAfter);
        return new LifecycleAssessment(current, survivor, exactAfter, action, legIndex,
                legDesc(selected), selected.expiration(), reference.underlyingCents(), reference.basis(),
                optionSettlementCash, stockCash, sharesDelta, allocatedEntryBasis, allocatedOpenFees,
                actionRealized, decisionDelta, realizedToDate, reserveBefore, reserveAfter, heldContextAfter,
                sharesLockedAfter, projectedCash, projectedReserved, List.copyOf(notes), fingerprint);
    }

    private PositionAssessment lifecycleAssessment(Connection c, TradeRecord trade,
                                                   List<LegLot> optionLots, long contextShares,
                                                   long stockBasisCents, long packageAdjustment,
                                                   long optionFees, long projectedCash,
                                                   long reservedWithoutCurrent, long exactReserve,
                                                   String world) throws SQLException {
        List<LegLot> combined = new ArrayList<>(optionLots);
        if (contextShares > 0) {
            combined.add(new LegLot(Leg.stockShares(LegAction.BUY, 1,
                    BigDecimal.valueOf(stockBasisCents, 2)), contextShares));
        }
        if (combined.isEmpty()) throw new IllegalArgumentException("a lifecycle assessment needs a surviving position");
        int quantity = canonicalQuantity(combined);
        List<Leg> legs = canonicalLegs(combined);
        OpenRequest request = new OpenRequest(trade.accountId(), trade.symbol(), trade.strategy(), quantity,
                legs, trade.thesis(), trade.horizon(), trade.riskMode(), trade.intent(), false,
                Math.addExact(cashForLots(combined), packageAdjustment), optionFees,
                "POSITION_TRANSFORMATION", "EXECUTED");
        Plan plan = computePlan(request, true, world, true, laneFor(world), true);
        long placementCash = Math.addExact(Math.subtractExact(projectedCash, plan.entryNet()), plan.fees());
        TradePreview preview = previewFromPlan(request, plan, placementCash, reservedWithoutCurrent, 0);
        PositionAssessment assessment = assessmentFromPlan(trade.id(), PositionDomain.PackageSource.PRACTICE_TRADE,
                PositionDomain.ExecutionLane.PRACTICE, request, plan, preview);
        var risk = new PositionTransformation.RiskSnapshot(plan.maxLoss(), exactReserve, plan.maxProfit(),
                plan.blocks().isEmpty(), plan.blocks(), assessment.risk().evidenceBasis());
        return new PositionAssessment(assessment.position(), assessment.preview(), risk);
    }

    private static OpenRequest activePositionRequest(TradeRecord trade, int quantity, long sharesLocked) {
        List<Leg> unpriced = trade.legs().stream().map(leg -> new Leg(leg.action(), leg.type(), leg.strike(),
                leg.expiration(), leg.ratio(), BigDecimal.ZERO, leg.multiplier())).toList();
        return new OpenRequest(trade.accountId(), trade.symbol(), trade.strategy(), quantity,
                unpriced, trade.thesis(), trade.horizon(), trade.riskMode(), trade.intent(),
                sharesLocked > 0, null, null, "POSITION_TRANSFORMATION", "PROPOSED");
    }

    /**
     * Read-only analysis for an owner-scoped tracked account. Tracked books are always valued from
     * the observed lane and use their own cash, never the Practice account or its selected world.
     */
    public TradePreview previewTracked(OpenRequest req, long trackedCashCents) {
        Plan p = computePlan(req, req.executedFill(), null, true,
                io.liftandshift.strikebench.market.MarketLane.OBSERVED, true);
        long cashAfter = trackedCashCents + p.entryNet - p.fees;
        long reservedAfter = p.reserve;
        List<String> blocks = new ArrayList<>(p.blocks);
        if (p.blocks.isEmpty() && cashAfter - reservedAfter < 0) {
            blocks.add("This tracked account has " + Money.fmt(trackedCashCents)
                    + " cash; the analyzed package needs " + Money.fmt(p.maxLoss + p.fees)
                    + ". Analysis remains visible, but the account cannot fund it as entered.");
        }
        return new TradePreview(blocks.isEmpty(), blocks, p.warnings,
                p.entryNet, p.fees, p.maxLoss, p.maxProfit, p.breakevens, p.pop, p.ev, p.reserve,
                trackedCashCents, blocks.isEmpty() ? cashAfter : trackedCashCents,
                0, blocks.isEmpty() ? reservedAfter : 0,
                trackedCashCents, blocks.isEmpty() ? cashAfter - reservedAfter : trackedCashCents,
                p.freshness.name(), trackedAnalysisEvidence(p.freshness), p.underlyingCents,
                p.assignmentProb(), p.legDetails(), p.payoff(), p.analytics());
    }

    private static io.liftandshift.strikebench.model.DataEvidence trackedAnalysisEvidence(Freshness freshness) {
        return switch (freshness == null ? Freshness.MISSING : freshness) {
            case FIXTURE -> io.liftandshift.strikebench.model.DataEvidence.of(
                    "built-in demo evidence used for tracked-account analysis only", Freshness.FIXTURE);
            case SIMULATED -> io.liftandshift.strikebench.model.DataEvidence.of(
                    "simulated evidence used for tracked-account analysis only", Freshness.SIMULATED);
            case MODELED -> io.liftandshift.strikebench.model.DataEvidence.of(
                    "modeled evidence used for tracked-account analysis only", Freshness.MODELED);
            default -> io.liftandshift.strikebench.model.DataEvidence.of(
                    "observed tracked-account analysis", freshness);
        };
    }

    /** Opens the trade or throws TradeRejectedException (audit row only, zero mutation). */
    public TradeRecord create(OpenRequest req) {
        return create(req, null);
    }

    /** Opens the trade and an owning workflow record in one database transaction. */
    public TradeRecord create(OpenRequest req, TransactionHook hook) {
        Plan p = computePlan(req);
        if (!p.blocks.isEmpty()) {
            reject(req, p.blocks);
        }
        String tradeId = Ids.trade();
        try {
            TradeRecord out = db.tx(c -> openOn(c, AccountService.getForUpdate(c, req.accountId()),
                    tradeId, req, p, hook));
            accountSnapshot.invalidate(req.accountId());
            auditSafe(req.accountId(), tradeId, "TRADE_OPENED", "INFO", Map.of(
                    "symbol", req.symbol(), "strategy", req.strategy(), "qty", req.qty(),
                    "entryNetPremiumCents", p.entryNet, "maxLossCents", p.maxLoss, "feesCents", p.fees));
            return out;
        } catch (TradeRejectedException e) {
            reject(req, e.reasons());
            throw e; // unreachable; reject throws
        }
    }

    private TradeRecord openOn(Connection c, Account acct, String tradeId, OpenRequest req, Plan p,
                               TransactionHook hook) throws SQLException {
        long cashAfter = acct.cashCents() + p.entryNet - p.fees;
        long reservedAfter = acct.reservedCents() + p.reserve;
        if (cashAfter - reservedAfter < 0) {
            throw new TradeRejectedException(List.of("Insufficient buying power: needs "
                    + Money.fmt(p.maxLoss + p.fees) + " but only " + Money.fmt(acct.buyingPowerCents()) + " is available"));
        }
        String symbol = req.symbol().toUpperCase(java.util.Locale.ROOT);
        if (req.heldShares()) {
            long needed = Math.max(p.sharesToLock(), Math.multiplyExact(heldShareUnitsPerPackage(req.legs()), req.qty()));
            long free = PositionsService.heldShares(c, acct.id(), symbol)
                    - PositionsService.lockedShares(c, acct.id(), symbol);
            if (free < needed) {
                throw new TradeRejectedException(List.of("Needs " + needed + " free shares of "
                        + symbol + " but only " + Math.max(0, free) + " are free"));
            }
        }
        String now = now();
        long cash = acct.cashCents(), reserved = acct.reservedCents();
        cash += p.entryNet;
        ledgerRow(c, acct.id(), tradeId, now, "PREMIUM_OPEN", p.entryNet, cash, reserved,
                req.strategy() + " x" + req.qty() + " open");
        if (p.fees != 0) {
            cash -= p.fees;
            ledgerRow(c, acct.id(), tradeId, now, "FEE", -p.fees, cash, reserved, "open commissions");
        }
        if (p.reserve != 0) {
            reserved += p.reserve;
            ledgerRow(c, acct.id(), tradeId, now, "RESERVE_HOLD", p.reserve, cash, reserved, "max-loss reserve");
        }
        var evidence = entryEvidence(acct.id(), p.freshness());
        Db.execOn(c, """
                INSERT INTO trades(id,account_id,symbol,strategy,status,qty,legs_json,thesis,horizon,risk_mode,
                  entry_underlying_cents,entry_net_premium_cents,max_loss_cents,max_profit_cents,breakevens_json,
                  pop_entry,fees_open_cents,fees_close_cents,realized_pnl_cents,close_reason,entry_snapshot_json,
                  is_live,created_at,closed_at,updated_at,intent,shares_locked,proposed_net_cents,
                  data_provenance,data_age,data_source)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,NULL,NULL,?,0,?,NULL,?,?,?,?,?,?,?)""",
                tradeId, acct.id(), symbol, req.strategy(), TradeRecord.ACTIVE,
                req.qty(), Json.write(p.filledLegs), req.thesis(), req.horizon(), req.riskMode(),
                p.underlyingCents, p.entryNet, p.maxLoss, p.maxProfit, Json.write(p.breakevens),
                p.pop, p.fees, p.snapshotJson, now, now,
                req.intent() == null || req.intent().isBlank() ? null
                        : io.liftandshift.strikebench.strategy.StrategyIntent.parse(req.intent()).name(),
                p.sharesToLock(), req.proposedNetCents(), evidence.provenance().name(), evidence.age().name(),
                evidence.source());
        Db.execOn(c, "UPDATE accounts SET cash_cents=?, reserved_cents=?, has_traded=1, updated_at=? WHERE id=?",
                cash, reserved, now, acct.id());
        TradeRecord created = getOn(c, tradeId);
        if (hook != null) hook.afterTradeCreated(c, created);
        return created;
    }

    private void reject(OpenRequest req, List<String> reasons) {
        audit.log(req.accountId(), null, "TRADE_REJECTED", "BLOCK", Map.of(
                "symbol", req.symbol(), "strategy", req.strategy(), "qty", req.qty(), "reasons", reasons));
        throw new TradeRejectedException(reasons);
    }

    // ---- Lifecycle ----

    /** Closes at current marks. Returns realized P/L net of all fees. */
    public CloseResult unwind(String tradeId, boolean confirm) {
        return unwind(tradeId, confirm, null);
    }

    public CloseResult unwind(String tradeId, boolean confirm, LifecycleHook hook) {
        return unwind(tradeId, confirm, hook, null, null);
    }

    /**
     * Closes only if the executable package still matches the signed transformation preview.
     * The comparison runs inside the same account/trade transaction as the ledger mutation.
     */
    public CloseResult unwind(String tradeId, boolean confirm, LifecycleHook hook,
                              Long expectedCloseValueCents, Long expectedCloseFeesCents) {
        requireConfirm(confirm, "unwind");
        markMemo.invalidate(tradeId); // closing: never serve a pre-close mark
        accountSnapshot.invalidateAll(); // the book changed — no consumer may see the old snapshot
        CloseResult result = db.tx(c -> {
            LockedTrade locked = lockTradeAndAccount(c, tradeId, TradeRecord.ACTIVE);
            TradeRecord t = locked.trade();
            Account acct = locked.account();
            ExecutableClose close = executableClose(t);
            requireExpectedClose(close, expectedCloseValueCents, expectedCloseFeesCents);
            Long decisionUnderlying = marks.underlyingMark(t.symbol(), worldOf(t.accountId()))
                    .map(Money::toCents).orElse(null);
            CloseResult closed = closeOut(c, t, acct, "PREMIUM_CLOSE", close.cashCents(), close.feesCents(),
                    TradeRecord.CLOSED, "UNWIND", decisionUnderlying);
            if (hook != null) hook.afterMutation(c, closed.trade(),
                    closed.actionRealizedPnlCents(), closed.realizedPnlCents());
            return closed;
        });
        auditSafe(result.trade().accountId(), tradeId, "TRADE_UNWOUND", "INFO",
                Map.of("realizedPnlCents", result.realizedPnlCents()));
        return result;
    }

    /**
     * Closes whole packages without selling and rebuying the survivors. Original entry basis,
     * fills, and open-fee basis remain on the surviving quantity; only the closed allocation is
     * realized. Ledger rows, reserve release, the survivor row, and the owning receipt hook share
     * one transaction.
     */
    public PartialCloseResult partialClose(String tradeId, int closeQuantity, boolean confirm,
                                           LifecycleHook hook, Long expectedCloseValueCents,
                                           Long expectedCloseFeesCents,
                                           ExpectedPositionState expectedPosition) {
        requireConfirm(confirm, "partial close");
        markMemo.invalidate(tradeId);
        accountSnapshot.invalidateAll();
        PartialCloseResult result = db.tx(c -> {
            LockedTrade locked = lockTradeAndAccount(c, tradeId, TradeRecord.ACTIVE);
            TradeRecord trade = locked.trade();
            requirePracticeTransformation(trade);
            requirePartialQuantity(trade, closeQuantity);
            long outstanding = outstandingReserve(c, trade.id());
            requireExpectedPosition(trade, outstanding, expectedPosition);
            ExecutableClose close = executableClose(trade, closeQuantity);
            requireExpectedClose(close, expectedCloseValueCents, expectedCloseFeesCents);

            long actionEntry = allocatedPrefix(trade.entryNetPremiumCents(), trade.qty(), closeQuantity);
            long actionOpenFees = allocatedPrefix(trade.feesOpenCents(), trade.qty(), closeQuantity);
            long actionRealized = Math.subtractExact(Math.addExact(
                    Math.subtractExact(actionEntry, actionOpenFees), close.cashCents()), close.feesCents());
            long totalRealized = Math.addExact(trade.realizedPnlCents() == null ? 0 : trade.realizedPnlCents(),
                    actionRealized);
            int survivingQuantity = trade.qty() - closeQuantity;
            long reserveRelease = allocatedPrefix(outstanding, trade.qty(), closeQuantity);
            long survivingReserve = Math.subtractExact(outstanding, reserveRelease);
            long survivingShares = remainingAllocation(trade.sharesLocked(), trade.qty(), closeQuantity);

            Account account = locked.account();
            String at = now();
            long cash = Math.addExact(account.cashCents(), close.cashCents());
            ledgerRow(c, account.id(), trade.id(), at, "PREMIUM_CLOSE", close.cashCents(), cash,
                    account.reservedCents(), trade.strategy() + " x" + closeQuantity + " partial close");
            if (close.feesCents() != 0) {
                cash = Math.subtractExact(cash, close.feesCents());
                ledgerRow(c, account.id(), trade.id(), at, "FEE", -close.feesCents(), cash,
                        account.reservedCents(), "partial-close commissions");
            }
            long reserved = account.reservedCents();
            if (reserveRelease != 0) {
                reserved = Math.subtractExact(reserved, reserveRelease);
                ledgerRow(c, account.id(), trade.id(), at, "RESERVE_RELEASE", -reserveRelease, cash,
                        reserved, "reserve released for " + closeQuantity + " closed package"
                                + (closeQuantity == 1 ? "" : "s"));
            }

            Long underlying = marks.underlyingMark(trade.symbol(), worldOf(trade.accountId()))
                    .map(Money::toCents).orElse(null);
            long closedContextShares = allocatedPrefix(heldShareContextShares(trade), trade.qty(), closeQuantity);
            long actionDecision = actionRealized;
            if (closedContextShares > 0 && underlying != null && trade.entryUnderlyingCents() > 0) {
                actionDecision = Math.addExact(actionDecision,
                        Math.multiplyExact(underlying - trade.entryUnderlyingCents(), closedContextShares));
            }
            long totalDecision = Math.addExact(trade.decisionPnlCents() == null ? 0 : trade.decisionPnlCents(),
                    actionDecision);
            long survivingEntry = remainingAllocation(trade.entryNetPremiumCents(), trade.qty(), closeQuantity);
            long survivingOpenFees = remainingAllocation(trade.feesOpenCents(), trade.qty(), closeQuantity);
            long survivingMaxLoss = remainingAllocation(trade.maxLossCents(), trade.qty(), closeQuantity);
            Long survivingMaxProfit = trade.maxProfitCents() == null ? null
                    : remainingAllocation(trade.maxProfitCents(), trade.qty(), closeQuantity);
            Long survivingProposed = trade.proposedNetCents() == null ? null
                    : remainingAllocation(trade.proposedNetCents(), trade.qty(), closeQuantity);
            long closeFeesToDate = Math.addExact(trade.feesCloseCents(), close.feesCents());
            String snapshot = survivorEntrySnapshot(trade, survivingQuantity, survivingShares);
            Db.execOn(c, "UPDATE trades SET qty=?,entry_net_premium_cents=?,max_loss_cents=?," +
                            "max_profit_cents=?,fees_open_cents=?,fees_close_cents=?,realized_pnl_cents=?," +
                            "decision_pnl_cents=?,shares_locked=?,proposed_net_cents=?,entry_snapshot_json=?::jsonb," +
                            "updated_at=? WHERE id=?",
                    survivingQuantity, survivingEntry, survivingMaxLoss, survivingMaxProfit,
                    survivingOpenFees, closeFeesToDate, totalRealized, totalDecision, survivingShares,
                    survivingProposed, snapshot, at, trade.id());
            Db.execOn(c, "UPDATE accounts SET cash_cents=?,reserved_cents=?,updated_at=? WHERE id=?",
                    cash, reserved, at, account.id());
            TradeRecord survivor = getOn(c, trade.id());
            if (outstandingReserve(c, trade.id()) != survivingReserve) {
                throw new IllegalStateException("Partial-close reserve allocation did not reconcile.");
            }
            if (hook != null) hook.afterMutation(c, survivor, actionRealized, totalRealized);
            return new PartialCloseResult(survivor, closeQuantity, actionRealized, totalRealized);
        });
        auditSafe(result.trade().accountId(), result.trade().id(), "TRADE_PARTIALLY_CLOSED", "INFO",
                Map.of("closedQuantity", result.closedQuantity(), "survivingQuantity", result.trade().qty(),
                        "actionRealizedPnlCents", result.actionRealizedPnlCents(),
                        "realizedPnlToDateCents", result.realizedPnlCents()));
        return result;
    }

    /** Applies a signed leg/stock adjustment while retaining the same Practice trade identity. */
    public AdjustmentResult adjustPosition(String tradeId, PositionTransformation.Action action,
                                           OpenRequest exactAfter, boolean confirm, LifecycleHook hook,
                                           ExpectedPositionState expectedPosition,
                                           ExpectedAdjustment expectedAdjustment) {
        requireConfirm(confirm, "position adjustment");
        if (!adjustmentAction(action) || exactAfter == null || expectedAdjustment == null) {
            throw new IllegalArgumentException("a reviewed leg or stock adjustment is required");
        }
        markMemo.invalidate(tradeId);
        accountSnapshot.invalidateAll();
        AdjustmentResult result = db.tx(c -> {
            LockedTrade locked = lockTradeAndAccount(c, tradeId, TradeRecord.ACTIVE);
            TradeRecord trade = locked.trade();
            requirePracticeTransformation(trade);
            long reserveBefore = outstandingReserve(c, trade.id());
            requireExpectedPosition(trade, reserveBefore, expectedPosition);
            if (!trade.accountId().equals(exactAfter.accountId())
                    || !trade.symbol().equalsIgnoreCase(exactAfter.symbol())) {
                throw new TradeRejectedException(List.of("The reviewed adjustment no longer belongs to this Practice position."));
            }

            ReconciledLots reconciled = reconcileLots(trade, exactAfter);
            List<LegLot> added = priceAddedLots(trade, reconciled.added());
            long closingCash = reconciled.removed().isEmpty() ? 0 : executableCloseLots(trade, reconciled.removed());
            long closingFees = reconciled.removed().isEmpty() ? 0 : closeFeesForLots(trade, reconciled.removed());
            long openingCash = cashForLots(added);
            long totalBasisUnits = basisUnits(lotsFromTrade(trade));
            long removedBasisUnits = basisUnits(reconciled.removed());
            long allocatedOpenFees = allocatedPrefix(trade.feesOpenCents(), totalBasisUnits, removedBasisUnits);
            long openingFees = added.isEmpty() ? 0 : feesFor(canonicalLegs(added), canonicalQuantity(added));

            long currentLegCash = cashForLots(lotsFromTrade(trade));
            long packageAdjustment = Math.subtractExact(trade.entryNetPremiumCents(), currentLegCash);
            long allocatedPackageAdjustment = allocatedPrefix(packageAdjustment, totalBasisUnits, removedBasisUnits);
            long remainingPackageAdjustment = Math.subtractExact(packageAdjustment, allocatedPackageAdjustment);
            List<LegLot> currentLots = new ArrayList<>(reconciled.retained());
            currentLots.addAll(added);
            int currentQuantity = canonicalQuantity(currentLots);
            long currentEntry = Math.addExact(cashForLots(currentLots), remainingPackageAdjustment);
            long currentOpenFees = Math.addExact(
                    Math.subtractExact(trade.feesOpenCents(), allocatedOpenFees), openingFees);
            OpenRequest currentAfter = new OpenRequest(trade.accountId(), trade.symbol(), exactAfter.strategy(),
                    currentQuantity, canonicalLegs(currentLots), trade.thesis(), trade.horizon(), trade.riskMode(),
                    trade.intent(), trade.sharesLocked() > 0, currentEntry, currentOpenFees,
                    "POSITION_TRANSFORMATION", "EXECUTED");

            String world = worldOf(trade.accountId());
            Plan exactPlan = computePlan(currentAfter, true, world, true, laneFor(world), true);
            if (!exactPlan.blocks().isEmpty()) {
                throw new TradeRejectedException(exactPlan.blocks());
            }
            long sharesAfter = exactPlan.sharesToLock();
            if (sharesAfter > 0) {
                long freeIncludingCurrent = Math.addExact(
                        PositionsService.heldShares(c, trade.accountId(), trade.symbol())
                                - PositionsService.lockedShares(c, trade.accountId(), trade.symbol()),
                        trade.sharesLocked());
                if (freeIncludingCurrent < sharesAfter) {
                    throw new TradeRejectedException(List.of("The adjusted position needs " + sharesAfter
                            + " free shares of " + trade.symbol() + " but only "
                            + Math.max(0, freeIncludingCurrent) + " are available."));
                }
            }
            requireExpectedAdjustment(expectedAdjustment, closingCash, openingCash, closingFees,
                    openingFees, exactPlan, sharesAfter, currentAfter);

            long allocatedEntryBasis = Math.addExact(cashForLots(reconciled.removed()), allocatedPackageAdjustment);
            long actionRealized = Math.subtractExact(Math.addExact(
                    Math.subtractExact(allocatedEntryBasis, allocatedOpenFees), closingCash), closingFees);
            long totalRealized = Math.addExact(trade.realizedPnlCents() == null ? 0 : trade.realizedPnlCents(),
                    actionRealized);

            Account account = locked.account();
            long cash = account.cashCents();
            long reserved = account.reservedCents();
            String at = now();
            if (!reconciled.removed().isEmpty()) {
                cash = Math.addExact(cash, closingCash);
                ledgerRow(c, account.id(), trade.id(), at, adjustmentCloseRowType(action, reconciled.removed()),
                        closingCash, cash, reserved, action + " executable close");
            }
            if (closingFees != 0) {
                cash = Math.subtractExact(cash, closingFees);
                ledgerRow(c, account.id(), trade.id(), at, "FEE", -closingFees, cash, reserved,
                        action + " close commissions");
            }
            if (!added.isEmpty()) {
                cash = Math.addExact(cash, openingCash);
                ledgerRow(c, account.id(), trade.id(), at, adjustmentOpenRowType(action, added),
                        openingCash, cash, reserved, action + " executable open");
            }
            if (openingFees != 0) {
                cash = Math.subtractExact(cash, openingFees);
                ledgerRow(c, account.id(), trade.id(), at, "FEE", -openingFees, cash, reserved,
                        action + " open commissions");
            }
            long reserveDelta = Math.subtractExact(exactPlan.reserve(), reserveBefore);
            if (reserveDelta < 0) {
                reserved = Math.addExact(reserved, reserveDelta);
                ledgerRow(c, account.id(), trade.id(), at, "RESERVE_RELEASE", reserveDelta, cash, reserved,
                        action + " reserve reduction");
            } else if (reserveDelta > 0) {
                reserved = Math.addExact(reserved, reserveDelta);
                ledgerRow(c, account.id(), trade.id(), at, "RESERVE_HOLD", reserveDelta, cash, reserved,
                        action + " reserve increase");
            }
            if (cash - reserved < 0) {
                throw new TradeRejectedException(List.of("The adjusted position exceeds current Practice buying power."));
            }

            long closeFeesToDate = Math.addExact(trade.feesCloseCents(), closingFees);
            long decisionToDate = Math.addExact(trade.decisionPnlCents() == null ? 0 : trade.decisionPnlCents(),
                    actionRealized);
            String snapshot = adjustedEntrySnapshot(trade, exactPlan, action, at,
                    allocatedPackageAdjustment, allocatedOpenFees);
            Db.execOn(c, "UPDATE trades SET strategy=?,qty=?,legs_json=?::jsonb,entry_net_premium_cents=?," +
                            "max_loss_cents=?,max_profit_cents=?,breakevens_json=?::jsonb,pop_entry=?," +
                            "fees_open_cents=?,fees_close_cents=?,realized_pnl_cents=?,decision_pnl_cents=?," +
                            "shares_locked=?,proposed_net_cents=?,entry_snapshot_json=?::jsonb," +
                            "data_provenance=?,data_age=?,data_source=?,updated_at=? WHERE id=?",
                    currentAfter.strategy(), currentAfter.qty(), Json.write(exactPlan.filledLegs()), exactPlan.entryNet(),
                    exactPlan.maxLoss(), exactPlan.maxProfit(), Json.write(exactPlan.breakevens()), exactPlan.pop(),
                    exactPlan.fees(), closeFeesToDate, totalRealized, decisionToDate, sharesAfter,
                    exactPlan.entryNet(), snapshot, entryEvidence(trade.accountId(), exactPlan.freshness()).provenance().name(),
                    entryEvidence(trade.accountId(), exactPlan.freshness()).age().name(),
                    entryEvidence(trade.accountId(), exactPlan.freshness()).source(), at, trade.id());
            Db.execOn(c, "UPDATE accounts SET cash_cents=?,reserved_cents=?,updated_at=? WHERE id=?",
                    cash, reserved, at, account.id());
            TradeRecord survivor = getOn(c, trade.id());
            if (outstandingReserve(c, trade.id()) != exactPlan.reserve()) {
                throw new IllegalStateException("Adjusted-position reserve did not reconcile.");
            }
            if (hook != null) hook.afterMutation(c, survivor, actionRealized, totalRealized);
            return new AdjustmentResult(survivor, actionRealized, totalRealized);
        });
        auditSafe(result.trade().accountId(), result.trade().id(), "TRADE_POSITION_ADJUSTED", "INFO",
                Map.of("action", action.name(), "actionRealizedPnlCents", result.actionRealizedPnlCents(),
                        "realizedPnlToDateCents", result.realizedPnlCents()));
        return result;
    }

    /** Applies one reviewed option lifecycle conversion and its physical share delivery atomically. */
    public LifecycleResult applyLifecycleConversion(String tradeId,
                                                     PositionTransformation.Action action,
                                                     int legIndex, boolean confirm,
                                                     LifecycleHook hook,
                                                     ExpectedPositionState expectedPosition,
                                                     ExpectedLifecycle expectedLifecycle) {
        requireConfirm(confirm, "option lifecycle conversion");
        if (!lifecycleAction(action) || expectedLifecycle == null) {
            throw new IllegalArgumentException("a reviewed assignment, exercise, or expiry is required");
        }
        markMemo.invalidate(tradeId);
        accountSnapshot.invalidateAll();
        LifecycleResult result = db.tx(c -> {
            LockedTrade locked = lockTradeAndAccount(c, tradeId, TradeRecord.ACTIVE);
            TradeRecord trade = locked.trade();
            requirePracticeTransformation(trade);
            long reserveBefore = outstandingReserve(c, trade.id());
            requireExpectedPosition(trade, reserveBefore, expectedPosition);
            LifecycleAssessment projected = projectLifecycleConversion(c, trade, action, legIndex);
            requireExpectedLifecycle(projected, expectedLifecycle);
            if (projected.survivor() != null && !projected.survivor().risk().mechanicallyEligible()) {
                throw new TradeRejectedException(projected.survivor().risk().blockReasons());
            }

            Account account = locked.account();
            long cash = Math.addExact(account.cashCents(), projected.optionSettlementCashCents());
            long reserved = account.reservedCents();
            String at = now();
            ledgerRow(c, account.id(), trade.id(), at, "SETTLEMENT", projected.optionSettlementCashCents(), cash, reserved,
                    action + " of " + projected.contract() + " at " + projected.settlementPriceBasis());
            if (projected.sharesDelta() > 0) {
                PositionsService.addAssigned(c, account.id(), trade.symbol(), projected.sharesDelta(),
                        strikePerShareCents(trade, legIndex), at);
                cash = Math.addExact(cash, projected.stockCashCents());
                ledgerRow(c, account.id(), trade.id(), at, "STOCK_BUY", projected.stockCashCents(), cash, reserved,
                        action + ": acquired " + projected.sharesDelta() + " sh " + trade.symbol()
                                + " at the contract strike; option premium remains separate");
            } else if (projected.sharesDelta() < 0) {
                long shares = -projected.sharesDelta();
                long stockRealized = PositionsService.removeAssigned(c, account.id(), trade.symbol(), shares,
                        strikePerShareCents(trade, legIndex), at);
                cash = Math.addExact(cash, projected.stockCashCents());
                ledgerRow(c, account.id(), trade.id(), at, "STOCK_SELL", projected.stockCashCents(), cash, reserved,
                        action + ": delivered " + shares + " sh " + trade.symbol()
                                + " at the contract strike (stock P/L vs basis " + Money.fmt(stockRealized) + ")");
            }

            long reserveDelta = Math.subtractExact(projected.reserveAfterCents(), reserveBefore);
            if (reserveDelta < 0) {
                reserved = Math.addExact(reserved, reserveDelta);
                ledgerRow(c, account.id(), trade.id(), at, "RESERVE_RELEASE", reserveDelta, cash, reserved,
                        action + " reserve reduction");
            } else if (reserveDelta > 0) {
                reserved = Math.addExact(reserved, reserveDelta);
                ledgerRow(c, account.id(), trade.id(), at, "RESERVE_HOLD", reserveDelta, cash, reserved,
                        action + " reserve increase");
            }
            if (action == PositionTransformation.Action.EXERCISE && cash - reserved < 0) {
                throw new TradeRejectedException(List.of("Exercise exceeds current Practice buying power."));
            }

            long totalRealized = Math.addExact(trade.realizedPnlCents() == null ? 0 : trade.realizedPnlCents(),
                    projected.actionRealizedPnlCents());
            long totalDecision = Math.addExact(trade.decisionPnlCents() == null ? 0 : trade.decisionPnlCents(),
                    projected.decisionPnlDeltaCents());
            if (projected.exactSurvivorRequest() == null) {
                Db.execOn(c, "UPDATE trades SET status=?,close_reason=?,realized_pnl_cents=?,decision_pnl_cents=?,"
                                + "shares_locked=0,closed_at=?,updated_at=? WHERE id=?",
                        TradeRecord.EXPIRED, action + " — option lifecycle complete",
                        totalRealized, totalDecision, at, at, trade.id());
            } else {
                OpenRequest exactAfter = projected.exactSurvivorRequest();
                String world = worldOf(trade.accountId());
                Plan exactPlan = computePlan(exactAfter, true, world, true, laneFor(world), true);
                if (!exactPlan.blocks().isEmpty()) throw new TradeRejectedException(exactPlan.blocks());
                String snapshot = lifecycleEntrySnapshot(trade, exactPlan, action, at,
                        projected.allocatedEntryBasisCents(), projected.allocatedOpenFeesCents(),
                        projected.heldShareContextAfter());
                Db.execOn(c, "UPDATE trades SET strategy=?,intent=?,qty=?,legs_json=?::jsonb,entry_net_premium_cents=?,"
                                + "max_loss_cents=?,max_profit_cents=?,breakevens_json=?::jsonb,pop_entry=?,"
                                + "fees_open_cents=?,realized_pnl_cents=?,decision_pnl_cents=?,shares_locked=?,"
                                + "proposed_net_cents=?,entry_snapshot_json=?::jsonb,data_provenance=?,data_age=?,"
                                + "data_source=?,updated_at=? WHERE id=?",
                        exactAfter.strategy(), exactAfter.intent(), exactAfter.qty(), Json.write(exactPlan.filledLegs()), exactPlan.entryNet(),
                        exactPlan.maxLoss(), exactPlan.maxProfit(), Json.write(exactPlan.breakevens()), exactPlan.pop(),
                        exactPlan.fees(), totalRealized, totalDecision, exactPlan.sharesToLock(), exactPlan.entryNet(),
                        snapshot, entryEvidence(trade.accountId(), exactPlan.freshness()).provenance().name(),
                        entryEvidence(trade.accountId(), exactPlan.freshness()).age().name(),
                        entryEvidence(trade.accountId(), exactPlan.freshness()).source(), at, trade.id());
            }
            Db.execOn(c, "UPDATE accounts SET cash_cents=?,reserved_cents=?,updated_at=? WHERE id=?",
                    cash, reserved, at, account.id());
            TradeRecord changed = getOn(c, trade.id());
            if (outstandingReserve(c, trade.id()) != projected.reserveAfterCents()) {
                throw new IllegalStateException("Lifecycle conversion reserve did not reconcile.");
            }
            if (hook != null) hook.afterMutation(c, changed, projected.actionRealizedPnlCents(), totalRealized);
            return new LifecycleResult(changed, projected.actionRealizedPnlCents(), totalRealized,
                    projected.sharesDelta(), projected.stockCashCents());
        });
        auditSafe(result.trade().accountId(), result.trade().id(), "TRADE_OPTION_LIFECYCLE", "INFO",
                Map.of("action", action.name(), "legIndex", legIndex,
                        "sharesDelta", result.sharesDelta(),
                        "actionRealizedPnlCents", result.actionRealizedPnlCents(),
                        "realizedPnlToDateCents", result.realizedPnlCents()));
        return result;
    }

    /**
     * One exact Practice roll: close the existing package and open its replacement under the
     * same account lock and database transaction. The existing close/open kernels remain the
     * only money paths; this method composes them and adds no pricing formula of its own.
     */
    public RollResult roll(String tradeId, OpenRequest replacement, boolean confirm, RollHook hook,
                           Long expectedCloseValueCents, Long expectedCloseFeesCents,
                           ExpectedOpen expectedOpen) {
        requireConfirm(confirm, "roll");
        if (replacement == null) throw new IllegalArgumentException("a roll requires the replacement position");
        Plan replacementPlan = computePlan(replacement);
        if (!replacementPlan.blocks().isEmpty()) reject(replacement, replacementPlan.blocks());
        requireExpectedOpen(replacementPlan, expectedOpen);
        String replacementTradeId = Ids.trade();
        markMemo.invalidate(tradeId);
        accountSnapshot.invalidateAll();
        RollResult result = db.tx(c -> {
            LockedTrade locked = lockTradeAndAccount(c, tradeId, TradeRecord.ACTIVE);
            TradeRecord current = locked.trade();
            if (!current.accountId().equals(replacement.accountId())) {
                throw new IllegalArgumentException("a roll cannot switch Practice accounts");
            }
            if (!current.symbol().equalsIgnoreCase(replacement.symbol())) {
                throw new IllegalArgumentException("a roll cannot switch the underlying symbol");
            }
            ExecutableClose close = executableClose(current);
            requireExpectedClose(close, expectedCloseValueCents, expectedCloseFeesCents);
            Long decisionUnderlying = marks.underlyingMark(current.symbol(), worldOf(current.accountId()))
                    .map(Money::toCents).orElse(null);
            CloseResult closed = closeOut(c, current, locked.account(), "PREMIUM_CLOSE",
                    close.cashCents(), close.feesCents(), TradeRecord.CLOSED, "ROLL_CLOSE", decisionUnderlying);
            Account afterClose = AccountService.getForUpdate(c, current.accountId());
            TradeRecord opened = openOn(c, afterClose, replacementTradeId, replacement, replacementPlan, null);
            if (hook != null) hook.afterRoll(c, closed.trade(), opened,
                    closed.actionRealizedPnlCents(), closed.realizedPnlCents());
            return new RollResult(closed.trade(), opened, closed.realizedPnlCents(),
                    closed.actionRealizedPnlCents());
        });
        auditSafe(result.closedTrade().accountId(), result.closedTrade().id(), "TRADE_ROLLED_CLOSE", "INFO",
                Map.of("realizedPnlCents", result.realizedClosingCents(),
                        "replacementTradeId", result.replacementTrade().id()));
        auditSafe(result.replacementTrade().accountId(), result.replacementTrade().id(), "TRADE_ROLLED_OPEN", "INFO",
                Map.of("priorTradeId", result.closedTrade().id(),
                        "entryNetPremiumCents", result.replacementTrade().entryNetPremiumCents()));
        return result;
    }

    /** Settles an expired position at cash-equivalent intrinsic value. */
    public CloseResult settle(String tradeId, boolean confirm) {
        return settle(tradeId, confirm, null);
    }

    public CloseResult settle(String tradeId, boolean confirm, LifecycleHook hook) {
        requireConfirm(confirm, "settle");
        markMemo.invalidate(tradeId); // closing: never serve a pre-close mark
        accountSnapshot.invalidateAll(); // the book changed — no consumer may see the old snapshot
        CloseResult result = db.tx(c -> {
            LockedTrade locked = lockTradeAndAccount(c, tradeId, TradeRecord.ACTIVE);
            TradeRecord t = locked.trade();
            // ONE CLOCK PER LANE: a sim-world trade dies at the SIM bell and settles at the sim
            // closes the moment the SIM calendar passes expiry — never the JVM's calendar.
            String settleWorld = worldOf(t.accountId());
            java.time.Instant now = nowFor(settleWorld);
            LocalDate today = LocalDate.ofInstant(now, io.liftandshift.strikebench.market.MarketHours.EASTERN);
            // A contract is settleable only after its 16:00 ET final bell — a bare date check
            // would let expiry-day positions cash out at intrinsic hours before they die.
            boolean anyAlive = t.legs().stream()
                    .anyMatch(l -> !l.isStock() && !io.liftandshift.strikebench.market.MarketHours.contractDead(l.expiration(), now));
            if (anyAlive) {
                throw new TradeRejectedException(List.of("Legs are still alive (contracts die at 4:00pm ET on expiration day); unwind instead of settling"));
            }
            Account acct = locked.account();
            LocalDate lastExpiry = t.legs().stream().filter(l -> !l.isStock())
                    .map(Leg::expiration).max(LocalDate::compareTo).orElse(today);
            // Each leg settles at the underlying close ON ITS OWN expiration date — valuing a
            // near leg at the far leg's expiry erases the whole inter-expiry move.
            java.util.Map<LocalDate, BigDecimal> closes = new java.util.HashMap<>();
            boolean anyCloseMissing = false;
            for (Leg leg : t.legs()) {
                LocalDate exp = leg.isStock() ? lastExpiry : leg.expiration();
                if (!closes.containsKey(exp)) {
                    closes.put(exp, marks.closeOn(t.symbol(), exp, worldOf(t.accountId())).orElse(null));
                }
                if (closes.get(exp) == null) anyCloseMissing = true;
            }
            String memoSuffix = "";
            if (anyCloseMissing) {
                long distinctOptionExpirations = t.legs().stream().filter(l -> !l.isStock())
                        .map(Leg::expiration).distinct().count();
                if (distinctOptionExpirations > 1) {
                    throw new TradeRejectedException(List.of(
                            "An expiration-day close is missing for this multi-expiration position. "
                                    + "Backfill each expiry close before settlement; one current price cannot value different dates honestly."));
                }
                // No expiration-day close available (e.g. keyless live mode has no candle
                // source). Never fabricate from an intraday gap: allow a clearly-labeled
                // fallback to the current quote only once the expiry is at least a full day old.
                if (!today.isAfter(lastExpiry)) {
                    throw new TradeRejectedException(List.of("The expiration-day closing price is not available yet — retry after the next session"
                            + " (or configure a candle source for exact settlement)"));
                }
                BigDecimal fallback = marks.underlyingMark(t.symbol(), worldOf(t.accountId()))
                        .orElseThrow(() -> new TradeRejectedException(List.of("No underlying price available to settle against")));
                for (LocalDate d : closes.keySet()) closes.putIfAbsent(d, null);
                closes.replaceAll((d, v) -> v == null ? fallback : v);
                memoSuffix = " [expiration close unavailable — settled at CURRENT market price; value may differ from true expiry settlement]";
            }
            // Physical delivery where it is the point of the strategy AND the money backs it:
            // short CALLS deliver only up to the shares actually LOCKED to this trade — any
            // short-call units beyond the lock cash-settle at intrinsic (their cover is a long
            // call, never another trade's collateral). Short PUT assignment requires the
            // STRUCTURAL cash-secured shape (a single short put whose outstanding reserve holds
            // the full strike cash) — the strategy label is user-supplied, and a relabeled
            // spread must never spend strike money the reserve never backed. Everything else
            // cash-settles at intrinsic; total equity is identical either way.
            long tradeReserve = outstandingReserve(c, t.id());
            boolean cspPhysical = cashSecuredPutAssignsPhysically(t, tradeReserve);
            long settleValue = 0;
            long lockRemaining = t.sharesLocked();
            List<Leg> physical = new ArrayList<>();
            List<Long> physicalShares = new ArrayList<>();
            for (Leg leg : t.legs()) {
                LocalDate exp = leg.isStock() ? lastExpiry : leg.expiration();
                BigDecimal close = closes.get(exp);
                boolean itm = !leg.isStock() && leg.intrinsicPerShare(close).signum() > 0;
                long legShares = (long) leg.multiplier() * leg.ratio() * t.qty();
                BigDecimal intrinsic = leg.intrinsicPerShare(close);
                if (itm && leg.action() == LegAction.SELL
                        && leg.type() == io.liftandshift.strikebench.model.OptionType.CALL && lockRemaining > 0) {
                    long deliver = Math.min(lockRemaining, legShares);
                    lockRemaining -= deliver;
                    physical.add(leg);
                    physicalShares.add(deliver);
                    long rest = legShares - deliver;
                    if (rest > 0) {
                        settleValue += closeSign(leg) * Money.centsFromPrice(intrinsic, rest);
                    }
                    continue;
                }
                if (itm && cspPhysical && leg.action() == LegAction.SELL
                        && leg.type() == io.liftandshift.strikebench.model.OptionType.PUT) {
                    physical.add(leg);
                    physicalShares.add(legShares);
                    continue;
                }
                settleValue += closeSign(leg) * Money.centsFromPrice(intrinsic, legShares);
            }

            String nowTs = now();
            long cash = acct.cashCents(), reserved = acct.reservedCents();
            cash += settleValue;
            ledgerRow(c, acct.id(), t.id(), nowTs, "SETTLEMENT", settleValue, cash, reserved,
                    t.strategy() + " x" + t.qty() + " settled" + memoSuffix);
            StringBuilder assignNote = new StringBuilder();
            for (int pi = 0; pi < physical.size(); pi++) {
                Leg leg = physical.get(pi);
                long shares = physicalShares.get(pi);
                long strikeTotal = Money.centsFromPrice(leg.strike(), shares);
                long strikePerShare = Money.toCents(leg.strike());
                if (leg.type() == io.liftandshift.strikebench.model.OptionType.CALL) {
                    long stockRealized = PositionsService.removeAssigned(c, acct.id(), t.symbol(), shares, strikePerShare, nowTs);
                    cash += strikeTotal;
                    ledgerRow(c, acct.id(), t.id(), nowTs, "STOCK_SELL", strikeTotal, cash, reserved,
                            "assignment: " + shares + " sh " + t.symbol() + " called away @ " + leg.strike().toPlainString()
                                    + " (stock P/L vs basis " + Money.fmt(stockRealized) + ")");
                    assignNote.append(" (assigned: ").append(shares).append(" sh called away at ")
                            .append(leg.strike().toPlainString()).append(")");
                } else {
                    PositionsService.addAssigned(c, acct.id(), t.symbol(), shares, strikePerShare, nowTs);
                    cash -= strikeTotal;
                    ledgerRow(c, acct.id(), t.id(), nowTs, "STOCK_BUY", -strikeTotal, cash, reserved,
                            "assignment: bought " + shares + " sh " + t.symbol() + " @ " + leg.strike().toPlainString()
                                    + " via short put (basis = strike; premium was option income)");
                    assignNote.append(" (assigned: bought ").append(shares).append(" sh at ")
                            .append(leg.strike().toPlainString()).append(")");
                }
            }
            long reserve = outstandingReserve(c, t.id());
            if (reserve != 0) {
                reserved -= reserve;
                ledgerRow(c, acct.id(), t.id(), nowTs, "RESERVE_RELEASE", -reserve, cash, reserved, "reserve released on settle");
            }
            long actionRealized = Math.addExact(
                    Math.subtractExact(t.entryNetPremiumCents(), t.feesOpenCents()), settleValue);
            long realizedToDate = Math.addExact(t.realizedPnlCents() == null ? 0 : t.realizedPnlCents(),
                    actionRealized);
            long actionDecisionPnl = decisionPnlAtSettlement(t, closes.get(lastExpiry), actionRealized);
            long decisionPnlToDate = Math.addExact(t.decisionPnlCents() == null ? 0 : t.decisionPnlCents(),
                    actionDecisionPnl);
            String closeReason = "SETTLED" + assignNote + memoSuffix;
            Db.execOn(c, "UPDATE trades SET status=?, close_reason=?, realized_pnl_cents=?, decision_pnl_cents=?, closed_at=?, updated_at=? WHERE id=?",
                    TradeRecord.EXPIRED, closeReason, realizedToDate, decisionPnlToDate, nowTs, nowTs, t.id());
            Db.execOn(c, "UPDATE accounts SET cash_cents=?, reserved_cents=?, updated_at=? WHERE id=?", cash, reserved, nowTs, acct.id());
            CloseResult closed = new CloseResult(getOn(c, t.id()), realizedToDate, actionRealized);
            if (hook != null) hook.afterMutation(c, closed.trade(),
                    closed.actionRealizedPnlCents(), closed.realizedPnlCents());
            return closed;
        });
        if (result.trade().closeReason() != null && result.trade().closeReason().contains("CURRENT market price")) {
            auditSafe(result.trade().accountId(), tradeId, "SETTLE_FALLBACK_PRICE", "WARN",
                    Map.of("note", "expiration-day close unavailable; settled at current market price"));
        }
        auditSafe(result.trade().accountId(), tradeId, "TRADE_SETTLED", "INFO",
                Map.of("realizedPnlCents", result.realizedPnlCents()));
        return result;
    }

    /**
     * Voids an ACTIVE trade as if it never happened: releases the reserve and reverses each
     * cash row with a mirror ADJUSTMENT. Append-only — nothing is erased.
     */
    public TradeRecord delete(String tradeId, boolean confirm) {
        return delete(tradeId, confirm, null);
    }

    public TradeRecord delete(String tradeId, boolean confirm, LifecycleHook hook) {
        requireConfirm(confirm, "delete");
        markMemo.invalidate(tradeId); // closing: never serve a pre-close mark
        accountSnapshot.invalidateAll(); // the book changed — no consumer may see the old snapshot
        TradeRecord out = db.tx(c -> {
            LockedTrade locked = lockTradeAndAccount(c, tradeId, TradeRecord.ACTIVE);
            TradeRecord t = locked.trade();
            Account acct = locked.account();
            String now = now();
            long cash = acct.cashCents(), reserved = acct.reservedCents();

            long reserve = outstandingReserve(c, tradeId);
            if (reserve != 0) {
                reserved -= reserve;
                ledgerRow(c, acct.id(), tradeId, now, "RESERVE_RELEASE", -reserve, cash, reserved, "released by delete");
            }
            List<LedgerEntry> cashRows = Db.queryOn(c,
                    "SELECT * FROM ledger WHERE trade_id=? AND type IN ('PREMIUM_OPEN','PREMIUM_CLOSE','SETTLEMENT','FEE') ORDER BY id",
                    AccountService::mapLedger, tradeId);
            for (LedgerEntry row : cashRows) {
                cash -= row.amountCents();
                ledgerRow(c, acct.id(), tradeId, now, "ADJUSTMENT", -row.amountCents(), cash, reserved,
                        "reversal of ledger #" + row.id() + " (" + row.type() + ")");
            }
            Db.execOn(c, "UPDATE trades SET status=?, close_reason='DELETED_BY_USER', closed_at=?, updated_at=? WHERE id=?",
                    TradeRecord.DELETED, now, now, tradeId);
            Db.execOn(c, "UPDATE accounts SET cash_cents=?, reserved_cents=?, updated_at=? WHERE id=?", cash, reserved, now, acct.id());
            TradeRecord deleted = getOn(c, tradeId);
            if (hook != null) hook.afterMutation(c, deleted, null, null);
            return deleted;
        });
        auditSafe(out.accountId(), tradeId, "TRADE_DELETED", "WARN", Map.of("note", "trade voided; entry cash reversed"));
        return out;
    }

    /**
     * ONE mark snapshot per trade per ~10s: the portfolio page asks for the same trade's mark from
     * the summary, the greeks strip, AND the enriched table row — three identical leg-by-leg
     * computations against 15s-cached quotes. Read paths share this memo; refresh() always
     * recomputes (it persists a row) and replaces the memo; closes invalidate.
     */
    private final com.github.benmanes.caffeine.cache.Cache<String, MarkView> markMemo =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .expireAfterWrite(java.time.Duration.ofSeconds(10)).maximumSize(500).build();

    /** The world a trade's marks live in: its ACCOUNT's binding (null = observed lanes). */
    String worldOf(String accountId) {
        Account account = db.with(c -> AccountService.get(c, accountId));
        return "DEMO".equals(account.type()) ? "demo" : account.worldId();
    }

    private io.liftandshift.strikebench.market.MarketLane laneFor(String worldId) {
        return io.liftandshift.strikebench.market.MarketLane.of(worldId, cfg.fixturesOnly());
    }

    private io.liftandshift.strikebench.model.DataEvidence entryEvidence(String accountId, Freshness freshness) {
        var lane = laneFor(worldOf(accountId));
        return switch (lane) {
            case DEMO -> io.liftandshift.strikebench.model.DataEvidence.of("built-in demo", Freshness.FIXTURE);
            case SIMULATED -> io.liftandshift.strikebench.model.DataEvidence.of("simulated market", Freshness.SIMULATED);
            case SCENARIO -> io.liftandshift.strikebench.model.DataEvidence.of("scenario", Freshness.MODELED);
            case OBSERVED -> io.liftandshift.strikebench.model.DataEvidence.of("observed market", freshness);
        };
    }

    /** Recomputes marks and writes a trade_marks row. NEVER touches cash or the reserve. */
    public MarkView refresh(String tradeId) {
        TradeRecord t = get(tradeId);
        if (!TradeRecord.ACTIVE.equals(t.status())) {
            throw new IllegalStateException("trade is " + t.status() + "; only ACTIVE trades can be refreshed");
        }
        MarkView view = computeMark(t);
        markMemo.put(tradeId, view);
        db.exec("INSERT INTO trade_marks(trade_id,ts,underlying_px_cents,close_cost_cents,unrealized_cents,decision_unrealized_cents,pop_now,freshness,detail_json) VALUES (?,?,?,?,?,?,?,?,?)",
                tradeId, view.ts(), view.underlyingCents(), view.closeCostCents(), view.unrealizedCents(),
                view.decisionUnrealizedCents(), view.popNow(), view.freshness(), null);
        return view;
    }

    /** Same computation as refresh, but persists nothing — used by the detail view. */
    public MarkView currentMark(String tradeId) {
        return memoizedMark(get(tradeId));
    }

    private MarkView memoizedMark(TradeRecord t) {
        MarkView cached = markMemo.getIfPresent(t.id());
        if (cached != null) return cached;
        MarkView view = computeMark(t);
        markMemo.put(t.id(), view);
        return view;
    }

    /**
     * ONE ATOMIC portfolio snapshot: every ACTIVE trade marked in a single pass, memoized per
     * account (~10s). Summary, greeks strip and table rows read the SAME map, so they can never
     * show three different answers computed seconds apart.
     */
    private final com.github.benmanes.caffeine.cache.Cache<String, Map<String, MarkView>> accountSnapshot =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .expireAfterWrite(java.time.Duration.ofSeconds(10)).maximumSize(50).build();

    public Map<String, MarkView> accountMarkSnapshot(String accountId) {
        return accountSnapshot.get(accountId, id -> {
            Map<String, MarkView> out = new LinkedHashMap<>();
            for (TradeRecord t : activeTrades(id)) {
                try { out.put(t.id(), memoizedMark(t)); } catch (RuntimeException ignored) { /* partial */ }
            }
            return out;
        });
    }

    /**
     * Portfolio heat: what the whole book is exposed to, not just per-trade risk — total worst
     * case, per-symbol concentration, short-volatility count, temporary early-assignment
     * liquidity, and the terminal cash-secured-put delivery picture. Those are deliberately
     * separate: a put spread can demand gross strike cash briefly without having that terminal
     * loss or becoming a stock position in StrikeBench's settlement model.
     */
    public Map<String, Object> portfolioHeat(String accountId) {
        List<TradeRecord> active = activeTrades(accountId);
        Account acct = db.with(c -> AccountService.get(c, accountId));
        Map<String, Long> reserveByTrade = new java.util.HashMap<>();
        for (Map.Entry<String, Long> e : db.query(
                "SELECT trade_id, COALESCE(SUM(amount_cents),0) AS amount FROM ledger "
                        + "WHERE account_id=? AND trade_id IS NOT NULL "
                        + "AND type IN ('RESERVE_HOLD','RESERVE_RELEASE') GROUP BY trade_id",
                r -> Map.entry(r.str("trade_id"), r.lng("amount")), accountId)) {
            reserveByTrade.put(e.getKey(), e.getValue());
        }
        long totalMaxLoss = 0, earlyAssignmentLiquidity = 0;
        long physicalAssignmentCash = 0, assignmentReserveReleased = 0;
        int shortVol = 0;
        Map<String, Long> bySymbol = new LinkedHashMap<>();
        for (TradeRecord t : active) {
            totalMaxLoss += t.maxLossCents();
            bySymbol.merge(t.symbol(), t.maxLossCents(), Long::sum);
            boolean hasShort = t.legs().stream().anyMatch(l -> !l.isStock() && l.action() == LegAction.SELL);
            if (hasShort && t.entryNetPremiumCents() > 0) shortVol++;
            for (Leg l : t.legs()) {
                if (!l.isStock() && l.action() == LegAction.SELL && l.type() == io.liftandshift.strikebench.model.OptionType.PUT) {
                    earlyAssignmentLiquidity += Money.centsFromPrice(l.strike(),
                            (long) l.multiplier() * l.ratio() * t.qty());
                }
            }
            long tradeReserve = reserveByTrade.getOrDefault(t.id(), 0L);
            if (cashSecuredPutAssignsPhysically(t, tradeReserve)) {
                Leg assignmentLeg = t.legs().getFirst();
                long strikeCash = Money.centsFromPrice(assignmentLeg.strike(),
                        (long) assignmentLeg.multiplier() * assignmentLeg.ratio() * t.qty());
                physicalAssignmentCash += strikeCash;
                assignmentReserveReleased += tradeReserve;
            }
        }
        long worstSymbol = bySymbol.values().stream().mapToLong(Long::longValue).max().orElse(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("activeTrades", active.size());
        out.put("totalMaxLossCents", totalMaxLoss);
        out.put("reservedCents", acct.reservedCents());
        out.put("shortVolTrades", shortVol);
        out.put("bySymbolMaxLossCents", bySymbol);
        out.put("concentrationPct", totalMaxLoss > 0 ? Math.round(100.0 * worstSymbol / totalMaxLoss) : 0);
        out.put("earlyAssignmentLiquidityCents", earlyAssignmentLiquidity);
        out.put("physicalAssignmentCashCents", physicalAssignmentCash);
        out.put("assignmentReserveReleasedCents", assignmentReserveReleased);
        out.put("postPhysicalAssignmentBuyingPowerCents",
                acct.buyingPowerCents() - physicalAssignmentCash + assignmentReserveReleased);
        return out;
    }

    private static boolean cashSecuredPutAssignsPhysically(TradeRecord t, long tradeReserve) {
        if (!"CASH_SECURED_PUT".equalsIgnoreCase(t.strategy()) || t.legs().size() != 1) return false;
        Leg leg = t.legs().getFirst();
        if (leg.isStock() || leg.action() != LegAction.SELL
                || leg.type() != io.liftandshift.strikebench.model.OptionType.PUT) return false;
        long strikeCash = Money.centsFromPrice(leg.strike(),
                (long) leg.multiplier() * leg.ratio() * t.qty());
        return tradeReserve >= strikeCash;
    }

    private MarkView computeMark(TradeRecord t) {
        String now = now();
        String world = worldOf(t.accountId());
        Long underlyingCents = marks.underlyingMark(t.symbol(), world).map(Money::toCents).orElse(null);

        long closeValue = 0;
        boolean complete = true;
        Freshness worst = Freshness.REALTIME;
        List<Double> ivs = new ArrayList<>();
        double dDelta = 0, dGamma = 0, dTheta = 0, dVega = 0;
        boolean greeksComplete = true;
        List<Map<String, Object>> legGreeks = new ArrayList<>();
        long heldContextShares = heldShareContextShares(t);
        for (Leg leg : t.legs()) {
            var mark = marks.legMark(t.symbol(), leg, world).orElse(null);
            if (mark == null) { complete = false; worst = Freshness.MISSING; break; }
            worst = worse(worst, mark.freshness());
            if (mark.iv() != null) ivs.add(mark.iv());
            // Value the close at the EXECUTABLE side (longs sell the bid, shorts pay the ask) —
            // the same price an unwind would actually get, so "unrealized" never overstates.
            BigDecimal px = mark.executable(leg.action().opposite());
            if (px == null) { complete = false; worst = Freshness.MISSING; break; }
            closeValue += closeSign(leg) * Money.centsFromPrice(px, (long) leg.multiplier() * leg.ratio() * t.qty());

            // Position greeks: sign x greek x exact deliverable x ratio x quantity.
            double mult = closeSign(leg) * leg.multiplier() * (double) leg.ratio() * t.qty();
            if (mark.delta() == null && !leg.isStock()) {
                greeksComplete = false;
            } else {
                dDelta += (mark.delta() == null ? 0 : mark.delta()) * mult;
                dGamma += (mark.gamma() == null ? 0 : mark.gamma()) * mult;
                dTheta += (mark.theta() == null ? 0 : mark.theta()) * mult;
                dVega += (mark.vega() == null ? 0 : mark.vega()) * mult;
            }
            Map<String, Object> lg = new LinkedHashMap<>();
            lg.put("leg", legDesc(leg));
            lg.put("bid", mark.bid() == null ? null : mark.bid().toPlainString());
            lg.put("ask", mark.ask() == null ? null : mark.ask().toPlainString());
            lg.put("delta", mark.delta());
            lg.put("gamma", mark.gamma());
            lg.put("theta", mark.theta());
            lg.put("vega", mark.vega());
            lg.put("iv", mark.iv());
            legGreeks.add(lg);
        }
        if (complete && heldContextShares > 0) {
            dDelta += heldContextShares;
            Map<String, Object> held = new LinkedHashMap<>();
            held.put("leg", heldContextShares + " held shares");
            held.put("bid", null);
            held.put("ask", null);
            held.put("delta", 1.0);
            held.put("gamma", 0.0);
            held.put("theta", 0.0);
            held.put("vega", 0.0);
            held.put("iv", null);
            legGreeks.add(held);
        }
        PositionGreeks greeks = complete
                ? new PositionGreeks(round2(dDelta), round4(dGamma), round2(dTheta), round2(dVega), greeksComplete)
                : null;
        Long closeCost = complete ? closeValue : null;
        // Opening fees already left cash and belong in today's P/L. The only omitted cost is the
        // FUTURE close fee, which the UI labels explicitly as not yet included.
        Long unrealized = complete ? closeValue + t.entryNetPremiumCents() - t.feesOpenCents() : null;
        Long decisionUnrealized = unrealized;
        if (decisionUnrealized != null && underlyingCents != null && heldContextShares > 0
                && t.entryUnderlyingCents() > 0) {
            decisionUnrealized += (underlyingCents - t.entryUnderlyingCents()) * heldContextShares;
        }
        boolean mixedExp = t.legs().stream().filter(l -> !l.isStock())
                .map(Leg::expiration).distinct().count() > 1;
        Double popNow = null;
        if (complete && underlyingCents != null && !mixedExp) {
            List<Leg> curveLegs = new ArrayList<>(t.legs());
            long sharesPerUnit = t.qty() > 0 ? heldContextShares / t.qty() : 0;
            if (sharesPerUnit > 0) {
                // Held-share trades were risk-shaped from the ENTRY spot. Resetting the stock
                // basis to today's mark erases the move already earned/lost and makes POP jump
                // even when the package itself did not change.
                long entrySpot = t.entryUnderlyingCents() > 0 ? t.entryUnderlyingCents() : underlyingCents;
                curveLegs.add(Leg.stockShares(LegAction.BUY, Math.toIntExact(sharesPerUnit),
                        BigDecimal.valueOf(entrySpot, 2)));
            }
            // The package fill is authoritative. A net limit/fill need not equal the sum of the
            // executable leg marks stored in legs_json, and held-share stock context is not an
            // entry cash flow. Reapply the exact package adjustment so POP-now starts at the
            // same payoff curve the ticket showed.
            long tradedLegEntry = PayoffCurve.of(t.legs(), t.qty()).entryNetPremiumCents();
            long entryAdjustment = t.entryNetPremiumCents() - tradedLegEntry;
            PayoffCurve curve = PayoffCurve.of(curveLegs, t.qty(), entryAdjustment);
            double ivAvg = ivs.isEmpty() ? FALLBACK_IV : ivs.stream().mapToDouble(Double::doubleValue).average().orElse(FALLBACK_IV);
            io.liftandshift.strikebench.market.OptionTime.Measure mtte =
                    io.liftandshift.strikebench.market.OptionTime.nearest(t.legs(), todayFor(world));
            List<BigDecimal> shortStrikes = t.legs().stream()
                    .filter(l -> !l.isStock() && l.action() == LegAction.SELL)
                    .map(Leg::strike).filter(java.util.Objects::nonNull).distinct().toList();
            popNow = io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.analyze(
                    curve, underlyingCents / 100.0, ivAvg, mtte.years(),
                    marks.riskFreeRate((int) Math.max(1, mtte.calendarDays()), world), shortStrikes)
                    .probabilityMap().pAnyProfit();
        }
        return new MarkView(t.id(), now, underlyingCents, closeCost, unrealized, decisionUnrealized, popNow, worst.name(),
                greeks, complete ? legGreeks : List.of());
    }

    private static Double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static Double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }

    /** Liquidation view of all ACTIVE trades: what unwinding everything now would pay
     *  (executable sides, BEFORE close fees). Sums computeMark per trade; incomplete marks
     *  make the whole answer honest-partial rather than silently wrong. */
    public record OpenPositionsValue(int openTradesCount, int markedTradesCount, long valueCents,
                                     long unrealizedCents, boolean complete, String freshness) {}

    public OpenPositionsValue openPositionsValue(String accountId) {
        List<TradeRecord> active = activeTrades(accountId);
        Map<String, MarkView> snap = accountMarkSnapshot(accountId); // ONE atomic snapshot for all consumers
        long value = 0, unrealized = 0;
        int counted = 0;
        boolean complete = true;
        Freshness worst = Freshness.REALTIME;
        for (TradeRecord t : active) {
            MarkView view = snap.get(t.id());
            if (view == null) { complete = false; continue; }
            if (view.closeCostCents() == null) { complete = false; continue; }
            value += view.closeCostCents();
            unrealized += view.unrealizedCents() == null ? 0 : view.unrealizedCents();
            worst = worse(worst, Freshness.valueOf(view.freshness()));
            counted++;
        }
        return new OpenPositionsValue(active.size(), counted, value, unrealized, complete, worst.name());
    }

    /** Aggregate greeks across all ACTIVE trades (Pro portfolio view). Never touches money. */
    public Map<String, Object> portfolioGreeks(String accountId) {
        List<TradeRecord> active = activeTrades(accountId);
        Map<String, MarkView> snap = accountMarkSnapshot(accountId); // same atomic snapshot as the summary
        double delta = 0, gamma = 0, theta = 0, vega = 0;
        boolean complete = true;
        List<Map<String, Object>> positions = new ArrayList<>();
        for (TradeRecord t : active) {
            MarkView view = snap.get(t.id());
            if (view == null) { complete = false; continue; }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", t.id());
            row.put("symbol", t.symbol());
            row.put("strategy", t.strategy());
            row.put("qty", t.qty());
            if (view.greeks() == null) {
                complete = false;
                row.put("greeks", null);
            } else {
                if (!view.greeks().complete()) complete = false;
                delta += view.greeks().deltaShares() == null ? 0 : view.greeks().deltaShares();
                gamma += view.greeks().gammaShares() == null ? 0 : view.greeks().gammaShares();
                theta += view.greeks().thetaPerDay() == null ? 0 : view.greeks().thetaPerDay();
                vega += view.greeks().vegaPerPoint() == null ? 0 : view.greeks().vegaPerPoint();
                row.put("greeks", view.greeks());
                row.put("unrealizedCents", view.unrealizedCents());
            }
            positions.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deltaShares", round2(delta));
        out.put("gammaShares", round4(gamma));
        out.put("thetaPerDay", round2(theta));
        out.put("vegaPerPoint", round2(vega));
        out.put("complete", complete);
        out.put("positions", positions);
        out.put("note", "Model statistics from current marks — share-equivalent delta/gamma, $/day theta, $/vol-point vega");
        return out;
    }

    public DollarDeltaExposure portfolioDollarDelta(String accountId, String focusSymbol) {
        return portfolioDollarDelta(accountId, focusSymbol, null);
    }

    /** Current Practice exposure with one position omitted, used for before/after transformations. */
    public DollarDeltaExposure portfolioDollarDelta(String accountId, String focusSymbol,
                                                     String excludedTradeId) {
        List<TradeRecord> active = activeTrades(accountId).stream()
                .filter(trade -> excludedTradeId == null || !excludedTradeId.equals(trade.id()))
                .toList();
        Map<String, MarkView> marksByTrade = accountMarkSnapshot(accountId);
        long gross = 0, net = 0, focusGross = 0;
        boolean complete = true;
        for (TradeRecord trade : active) {
            MarkView mark = marksByTrade.get(trade.id());
            if (mark == null || mark.underlyingCents() == null || mark.greeks() == null
                    || mark.greeks().deltaShares() == null || !mark.greeks().complete()) {
                complete = false;
                continue;
            }
            double raw = mark.greeks().deltaShares() * mark.underlyingCents();
            if (!Double.isFinite(raw) || raw > Long.MAX_VALUE || raw < Long.MIN_VALUE) {
                complete = false;
                continue;
            }
            long delta = Math.round(raw);
            long magnitude = safeAbsolute(delta);
            gross = Math.addExact(gross, magnitude);
            net = Math.addExact(net, delta);
            if (trade.symbol().equalsIgnoreCase(focusSymbol)) {
                focusGross = Math.addExact(focusGross, magnitude);
            }
        }
        return new DollarDeltaExposure(gross, net, focusGross, complete,
                "Current executable Practice marks in this account's market; dollar delta uses the disclosed option model."
                        + " This is exposure, not P&L or broker reserve.");
    }

    private static long safeAbsolute(long value) {
        if (value == Long.MIN_VALUE) throw new ArithmeticException("dollar delta overflow");
        return Math.abs(value);
    }

    // ---- Reads ----

    public TradeRecord get(String tradeId) {
        return db.with(c -> getOn(c, tradeId));
    }

    public Page list(String accountId, String status, int page, int size) {
        return list(accountId, status, null, null, page, size);
    }

    private List<TradeRecord> activeTrades(String accountId) {
        return db.query("SELECT * FROM trades WHERE account_id=? AND status=? "
                        + "ORDER BY created_at DESC,id DESC",
                TradeService::mapTrade, accountId, TradeRecord.ACTIVE);
    }

    public Page list(String accountId, String status, String symbol, String intent, int page, int size) {
        StringBuilder where = new StringBuilder("account_id=?");
        List<Object> params = new ArrayList<>();
        params.add(accountId);
        if (status != null && !status.isBlank()) {
            where.append(" AND status=?");
            params.add(status.toUpperCase(java.util.Locale.ROOT));
        }
        if (symbol != null && !symbol.isBlank()) {
            where.append(" AND symbol=?");
            params.add(symbol.trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (intent != null && !intent.isBlank()) {
            String norm = intent.trim().toUpperCase(java.util.Locale.ROOT);
            if ("DIRECTIONAL".equals(norm)) {
                // Pre-taxonomy trades and plain tickets have no stored intent — they ARE directional
                where.append(" AND (intent=? OR intent IS NULL)");
            } else {
                where.append(" AND intent=?");
            }
            params.add(norm);
        }
        Object[] countParams = params.toArray();
        long total = db.with(c -> Db.queryOn(c, "SELECT COUNT(*) AS n FROM trades WHERE " + where, r -> r.lng("n"), countParams).getFirst());
        params.add(size);
        params.add(Math.max(0, page) * size);
        List<TradeRecord> rows = db.query("SELECT * FROM trades WHERE " + where + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                TradeService::mapTrade, params.toArray());
        return new Page(rows, total, page, size);
    }

    public List<MarkView> marksHistory(String tradeId, int limit) {
        return db.query("SELECT * FROM trade_marks WHERE trade_id=? ORDER BY id DESC LIMIT ?", r ->
                new MarkView(tradeId, r.str("ts"), r.lngOrNull("underlying_px_cents"), r.lngOrNull("close_cost_cents"),
                        r.lngOrNull("unrealized_cents"), r.lngOrNull("decision_unrealized_cents"),
                        r.dblOrNull("pop_now"), r.str("freshness"), null, List.of()), tradeId, limit);
    }

    public Excursion excursion(String tradeId) {
        return db.query("SELECT MIN(COALESCE(decision_unrealized_cents, unrealized_cents)) adverse, "
                        + "MAX(COALESCE(decision_unrealized_cents, unrealized_cents)) favorable "
                        + "FROM trade_marks WHERE trade_id=?",
                row -> new Excursion(row.lngOrNull("adverse"), row.lngOrNull("favorable")), tradeId)
                .stream().findFirst().orElse(new Excursion(null, null));
    }

    // ---- Plan computation ----

    private record Plan(List<Leg> filledLegs, long entryNet, long fees, long reserve, long maxLoss,
                        Long maxProfit, List<String> breakevens, Double pop, Long ev, long underlyingCents,
                        Freshness freshness, List<String> blocks, List<String> warnings, String snapshotJson,
                        long sharesToLock, List<Map<String, Object>> legDetails, Double assignmentProb,
                        List<Map<String, Object>> payoff, Map<String, Object> analytics) {
        Plan(List<Leg> filledLegs, long entryNet, long fees, long reserve, long maxLoss,
             Long maxProfit, List<String> breakevens, Double pop, Long ev, long underlyingCents,
             Freshness freshness, List<String> blocks, List<String> warnings, String snapshotJson,
             long sharesToLock, List<Map<String, Object>> legDetails, Double assignmentProb,
             List<Map<String, Object>> payoff) {
            this(filledLegs, entryNet, fees, reserve, maxLoss, maxProfit, breakevens, pop, ev,
                    underlyingCents, freshness, blocks, warnings, snapshotJson, sharesToLock,
                    legDetails, assignmentProb, payoff, Map.of());
        }

        Plan(List<Leg> filledLegs, long entryNet, long fees, long reserve, long maxLoss,
             Long maxProfit, List<String> breakevens, Double pop, Long ev, long underlyingCents,
             Freshness freshness, List<String> blocks, List<String> warnings, String snapshotJson) {
            this(filledLegs, entryNet, fees, reserve, maxLoss, maxProfit, breakevens, pop, ev,
                    underlyingCents, freshness, blocks, warnings, snapshotJson, 0, List.of(), null, List.of());
        }

        Plan(List<Leg> filledLegs, long entryNet, long fees, long reserve, long maxLoss,
             Long maxProfit, List<String> breakevens, Double pop, Long ev, long underlyingCents,
             Freshness freshness, List<String> blocks, List<String> warnings, String snapshotJson,
             long sharesToLock) {
            this(filledLegs, entryNet, fees, reserve, maxLoss, maxProfit, breakevens, pop, ev,
                    underlyingCents, freshness, blocks, warnings, snapshotJson, sharesToLock, List.of(), null, List.of());
        }
    }

    private Plan computePlan(OpenRequest req) {
        return computePlan(req, false);
    }

    /** Recorded broker fills are facts; paper orders may not claim a favorable limit filled. */
    private Plan computePlan(OpenRequest req, boolean recordedFill) {
        return computePlan(req, recordedFill, null, false, null, false);
    }

    private Plan computePlan(OpenRequest req, boolean recordedFill,
                             String selectedWorld, boolean worldWasSupplied) {
        return computePlan(req, recordedFill, selectedWorld, worldWasSupplied, null, false);
    }

    private Plan computePlan(OpenRequest req, boolean recordedFill,
                             String selectedWorld, boolean worldWasSupplied,
                             io.liftandshift.strikebench.market.MarketLane requiredLane,
                             boolean analysisOnly) {
        List<String> blocks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (req.qty() < 1) blocks.add("Quantity must be at least 1");
        if (req.qty() > 1_000_000) blocks.add("Quantity exceeds the analysis arithmetic limit");
        else if (req.qty() > 100 && !analysisOnly) blocks.add("Quantity exceeds the 100-contract practice cap");
        if (req.legs() == null || req.legs().isEmpty()) blocks.add("At least one leg is required");
        if (!blocks.isEmpty()) return new Plan(List.of(), 0, 0, 0, 0, null, List.of(), null, null, 0, Freshness.MISSING, blocks, warnings, "{}");

        String world = worldWasSupplied ? selectedWorld : worldOf(req.accountId());
        var lane = requiredLane == null ? laneFor(world) : requiredLane;
        BigDecimal underlying = marks.underlyingMark(req.symbol(), world).orElse(null);
        var underlyingEvidence = marks.underlyingEvidence(req.symbol(), world).orElse(null);
        if (underlying == null) blocks.add("No current price for " + req.symbol());
        if (underlyingEvidence != null && !underlyingEvidence.executableIn(lane)) {
            String unavailable = "Cannot execute " + req.symbol() + " in the " + lane + " market using "
                    + underlyingEvidence.provenance() + " underlying data (" + underlyingEvidence.source()
                    + ", " + underlyingEvidence.age() + ").";
            if (analysisOnly && underlying != null) {
                warnings.add(unavailable + " ANALYZE may still use it as labeled, non-executable evidence; RECORD and placement may not.");
            } else {
                blocks.add(unavailable + " Refresh an executable quote before placing the trade.");
            }
        }

        // ONE CLOCK PER LANE: a world trade's expiry gates, session warnings and DTE all run on
        // the SIM clock (the clock that priced the chain) — never the JVM's (adversarial review P0).
        java.time.Instant nowInstant = nowFor(world);
        for (Leg leg : req.legs()) {
            if (!leg.isStock() && io.liftandshift.strikebench.market.MarketHours.contractDead(leg.expiration(), nowInstant)) {
                blocks.add("Leg " + legDesc(leg) + " is already expired — contracts die at 4:00pm ET on their expiration day; "
                        + "you cannot open a position in a dead contract");
            }
        }
        if (!blocks.isEmpty()) return new Plan(List.of(), 0, 0, 0, 0, null, List.of(), null, null, 0, Freshness.MISSING, blocks, warnings, "{}");
        if (world == null && !io.liftandshift.strikebench.market.MarketHours.isRegularSession(nowInstant)) {
            warnings.add("Market is closed — quotes are leftovers from the last session and paper fills are simulated");
        }

        List<Leg> filled = new ArrayList<>();
        List<Leg> marketFilled = new ArrayList<>();
        List<Map<String, Object>> snapshotLegs = new ArrayList<>();
        Freshness worst = Freshness.REALTIME;
        if (underlyingEvidence != null) worst = worse(worst, freshnessOf(underlyingEvidence));
        List<Double> ivs = new ArrayList<>();
        List<Double> legIvs = new ArrayList<>(); // index-aligned with filled (nulls kept)
        int suppliedEntryPrices = 0;
        int modeledEntryPrices = 0;
        for (Leg leg : req.legs()) {
            boolean suppliedEntryPrice = req.explicitFillMeaning()
                    && leg.entryPrice() != null && leg.entryPrice().signum() > 0;
            var mark = marks.legMark(req.symbol(), leg, world).orElse(null);
            if (mark == null || mark.mid() == null) {
                if (suppliedEntryPrice) {
                    suppliedEntryPrices++;
                    Leg filledLeg = new Leg(leg.action(), leg.type(), leg.strike(), leg.expiration(),
                            leg.ratio(), leg.entryPrice(), leg.multiplier());
                    filled.add(filledLeg);
                    legIvs.add(null);
                    worst = worse(worst, Freshness.MISSING);
                    Map<String, Object> snap = new LinkedHashMap<>();
                    snap.put("leg", legDesc(filledLeg));
                    snap.put("action", leg.action().name());
                    snap.put("type", leg.isStock() ? "STOCK" : leg.type().name());
                    snap.put("strike", leg.isStock() || leg.strike() == null ? null : leg.strike().stripTrailingZeros().toPlainString());
                    snap.put("expiration", leg.expiration() == null ? null : leg.expiration().toString());
                    snap.put("ratio", leg.ratio());
                    snap.put("multiplier", leg.multiplier());
                    snap.put("fill", leg.entryPrice().toPlainString());
                    snap.put("fillBasis", req.executedFill() ? "USER_EXECUTED" : "USER_PROPOSED");
                    snap.put("bid", null); snap.put("ask", null); snap.put("mid", null);
                    snap.put("iv", null); snap.put("freshness", Freshness.MISSING.name());
                    snap.put("provenance", req.executedFill() ? "BROKER" : "USER_INPUT");
                    snap.put("dataAge", "UNKNOWN"); snap.put("source", "entered leg price");
                    snapshotLegs.add(snap);
                    warnings.add("No current mark for " + legDesc(leg) + "; its entered "
                            + (req.executedFill() ? "executed fill" : "proposed price")
                            + " drives entry economics while volatility-dependent outputs use explicitly incomplete evidence.");
                    continue;
                }
                blocks.add("No market or model mark for " + legDesc(leg)
                        + " — enter a proposed/executed leg price or choose a contract with available evidence");
                continue;
            }
            if (!mark.evidence().executableIn(lane)) {
                String unavailable = "Cannot execute " + legDesc(leg) + " in the " + lane + " market using "
                        + mark.evidence().provenance() + " data (" + mark.evidence().source() + ", "
                        + mark.evidence().age() + ").";
                if (analysisOnly) {
                    warnings.add(unavailable + " ANALYZE uses the labeled midpoint/model only; it is not a fill claim.");
                } else {
                    blocks.add(unavailable + " Use an observed executable quote, or explicitly enter Demo/Simulated market.");
                    continue;
                }
            }
            // Fill realism: buys pay the ask, sells receive the bid. A one-sided or empty
            // book is not executable — midpoints of dead books mint fictional money.
            BigDecimal executableFill = mark.executable(leg.action());
            BigDecimal marketPrice = executableFill;
            if (marketPrice == null) {
                if (analysisOnly) {
                    marketPrice = mark.mid();
                    modeledEntryPrices++;
                    warnings.add("No executable " + (leg.action() == LegAction.BUY ? "ask" : "bid") + " for "
                            + legDesc(leg) + "; ANALYZE uses the labeled midpoint and does not claim a fill.");
                } else {
                    blocks.add("No executable " + (leg.action() == LegAction.BUY ? "ask" : "bid") + " for "
                            + legDesc(leg) + " — the book is one-sided, empty, or crossed; this leg cannot actually be traded");
                    continue;
                }
            }
            // Quote integrity: an option marked below intrinsic value against the SAME feed's
            // underlying is impossible — it is a stale or expired quote, not an opportunity.
            if (!leg.isStock() && underlying != null) {
                BigDecimal intrinsic = leg.intrinsicPerShare(underlying);
                BigDecimal tolerance = intrinsic.multiply(new BigDecimal("0.02")).max(new BigDecimal("0.05"));
                if (mark.mid().compareTo(intrinsic.subtract(tolerance)) < 0) {
                    String impossible = "Quote integrity: " + legDesc(leg) + " is marked " + mark.mid().toPlainString()
                            + " but is worth at least " + intrinsic.toPlainString() + " intrinsically vs the underlying at "
                            + underlying.toPlainString() + " — impossible price, the quote is stale or the contract is dead";
                    if (suppliedEntryPrice && req.executedFill()) warnings.add(impossible + "; preserving the entered broker fill as fact while withholding a live-execution claim.");
                    else { blocks.add(impossible); continue; }
                }
            }
            worst = worse(worst, mark.freshness());
            if (mark.iv() != null) ivs.add(mark.iv());
            legIvs.add(mark.iv());
            BigDecimal fill = suppliedEntryPrice ? leg.entryPrice() : marketPrice;
            if (suppliedEntryPrice) {
                suppliedEntryPrices++;
                if (!req.executedFill() && executableFill != null) {
                    boolean tooFavorable = leg.action() == LegAction.BUY
                            ? fill.compareTo(executableFill) < 0 : fill.compareTo(executableFill) > 0;
                    if (tooFavorable) {
                        warnings.add("Your proposed " + legDesc(leg) + " price " + fill.toPlainString()
                                + " is more favorable than the executable " + executableFill.toPlainString()
                                + ". It can be analyzed, but StrikeBench cannot claim it filled.");
                    }
                }
            } else if (!mark.evidence().executableIn(lane) || executableFill == null) {
                modeledEntryPrices++;
            }
            Leg filledLeg = new Leg(leg.action(), leg.type(), leg.strike(), leg.expiration(),
                    leg.ratio(), fill, leg.multiplier());
            filled.add(filledLeg);
            marketFilled.add(new Leg(leg.action(), leg.type(), leg.strike(), leg.expiration(),
                    leg.ratio(), marketPrice, leg.multiplier()));
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("leg", legDesc(filledLeg));
            snap.put("action", leg.action().name());
            snap.put("type", leg.isStock() ? "STOCK" : leg.type().name());
            snap.put("strike", leg.isStock() || leg.strike() == null ? null : leg.strike().stripTrailingZeros().toPlainString());
            snap.put("expiration", leg.expiration() == null ? null : leg.expiration().toString());
            snap.put("ratio", leg.ratio());
            snap.put("multiplier", leg.multiplier());
            snap.put("fill", fill.toPlainString());
            snap.put("fillBasis", suppliedEntryPrice
                    ? (req.executedFill() ? "USER_EXECUTED" : "USER_PROPOSED")
                    : (mark.evidence().executableIn(lane) && executableFill != null ? "EXECUTABLE_BOOK" : "LABELED_MODEL_OR_MID"));
            snap.put("bid", mark.bid() == null ? null : mark.bid().toPlainString());
            snap.put("ask", mark.ask() == null ? null : mark.ask().toPlainString());
            snap.put("mid", mark.mid().toPlainString());
            snap.put("iv", mark.iv());
            snap.put("delta", mark.delta());
            snap.put("gamma", mark.gamma());
            snap.put("theta", mark.theta());
            snap.put("vega", mark.vega());
            snap.put("freshness", mark.freshness().name());
            snap.put("provenance", mark.evidence().provenance().name());
            snap.put("dataAge", mark.evidence().age().name());
            snap.put("source", mark.evidence().source());
            snapshotLegs.add(snap);
        }
        if (!blocks.isEmpty()) return new Plan(List.of(), 0, 0, 0, 0, null, List.of(), null, null, 0, worst, blocks, warnings, "{}");

        if (suppliedEntryPrices > 0) {
            warnings.add(suppliedEntryPrices + " entered leg price" + (suppliedEntryPrices == 1 ? "" : "s")
                    + " preserved exactly; blank legs use separately labeled market/model evidence.");
        }
        if (modeledEntryPrices > 0) {
            warnings.add(modeledEntryPrices + " leg" + (modeledEntryPrices == 1 ? "" : "s")
                    + " lack an executable side; these are analysis inputs, not executable fill claims.");
        }

        if (worst == Freshness.DELAYED || worst == Freshness.EOD) {
            warnings.add("Marks are " + worst + " — fills use non-realtime prices");
        }

        // Held-shares coverage: the account's own shares stand in for a stock leg. Only short
        // CALLS can be covered this way; the create() transaction verifies and locks the shares.
        long coverSharesPerUnit = 0;
        if (req.heldShares()) {
            if (filled.stream().anyMatch(Leg::isStock)) {
                blocks.add("useHeldShares cannot be combined with stock legs — either buy shares inside the trade or write against shares you already hold");
                return new Plan(filled, 0, 0, 0, 0, null, List.of(), null, null, Money.toCents(underlying), worst, blocks, warnings, "{}");
            }
            coverSharesPerUnit = io.liftandshift.strikebench.strategy.CoverageCheck.callCoverSharesNeeded(filled);
            if (coverSharesPerUnit < 0) {
                blocks.add("Held shares can only cover short CALLS — this structure has uncovered short puts or short stock that shares cannot protect");
                return new Plan(filled, 0, 0, 0, 0, null, List.of(), null, null, Money.toCents(underlying), worst, blocks, warnings, "{}");
            }
        }
        long sharesToLock = Math.multiplyExact(coverSharesPerUnit, req.qty());

        PayoffCurve curve = PayoffCurve.of(filled, req.qty());
        long entryNet = curve.entryNetPremiumCents();
        long executableNet = marketFilled.size() == filled.size()
                ? PayoffCurve.of(marketFilled, req.qty()).entryNetPremiumCents()
                : entryNet;                                  // unavailable markets fall back only for comparison copy
        Long packageMid = packageMidCents(snapshotLegs, req.qty());
        // YOUR price, not the model's: a proposed limit (pre-trade) or an actual fill (evaluation)
        // reprices the whole package — max loss, breakevens, POP, EV all follow the real number.
        long netAdjust = 0;
        if (req.proposedNetCents() != null && filled.stream().anyMatch(l -> !l.isStock())) {
            // R5: judge the SAME legs at YOUR price via a package-level curve adjustment — legs
            // keep their REAL quotes (no fabricated per-leg fill ever persists).
            netAdjust = req.proposedNetCents() - entryNet;
            if (netAdjust != 0) {
                curve = PayoffCurve.of(filled, req.qty(), netAdjust);
                entryNet = curve.entryNetPremiumCents();
            }
            warnings.add("Priced at YOUR net price " + Money.fmt(req.proposedNetCents())
                    + " — the executable sides right now say " + Money.fmt(executableNet)
                    + (packageMid != null ? ", midpoint " + Money.fmt(packageMid) : ""));
            if (packageMid != null && req.proposedNetCents() > packageMid) {
                warnings.add("Your price is MORE favorable than the midpoint — resting there may never fill");
            }
            if (!recordedFill && req.proposedNetCents() > executableNet) {
                blocks.add("Your limit " + Money.fmt(req.proposedNetCents())
                        + " is more favorable than the executable market " + Money.fmt(executableNet)
                        + ". StrikeBench does not model resting limit orders, so it cannot claim this paper order filled. "
                        + "Re-price at or below the executable net, or record the fill after it actually occurs.");
            }
        }

        LocalDate laneToday = java.time.LocalDate.ofInstant(nowInstant,
                io.liftandshift.strikebench.market.MarketHours.EASTERN);
        io.liftandshift.strikebench.market.OptionTime.Measure tte =
                io.liftandshift.strikebench.market.OptionTime.nearest(filled, laneToday);
        int rateDays = (int) Math.max(1, tte.calendarDays());
        double rfr = marks.riskFreeRate(rateDays, world);
        io.liftandshift.strikebench.model.DataEvidence rateEvidence =
                marks.riskFreeRateEvidence(rateDays, world);

        // Same rate, IVs and finish-ITM convention the recommendation engine uses.
        Double assignProb = io.liftandshift.strikebench.recommend.RecommendationEngine.assignmentProbabilityFromIvs(
                filled, legIvs, underlying, laneToday, FALLBACK_IV, rfr);

        // Calendars/diagonals: the expiration payoff curve is meaningless across mixed
        // expirations. Debit versions risk exactly the debit; credit versions can carry
        // undefined risk once the near leg expires, so they are blocked.
        boolean mixedExpirations = filled.stream().filter(l -> !l.isStock())
                .map(Leg::expiration).distinct().count() > 1;
        if (mixedExpirations) {
            if (entryNet >= 0) {
                blocks.add("Multi-expiration credit positions can carry undefined risk after the near leg expires; blocked");
                return new Plan(filled, entryNet, 0, 0, 0, null, List.of(), null, null, Money.toCents(underlying), worst, blocks, warnings, "{}");
            }
            // A net debit does NOT prove defined risk: a short leg that outlives (or out-strikes)
            // its cover has unlimited downside the single-expiration curve cannot see.
            List<String> uncovered = io.liftandshift.strikebench.strategy.CoverageCheck
                    .uncoveredShortsWithHeldShares(filled, coverSharesPerUnit);
            if (!uncovered.isEmpty()) {
                blocks.addAll(uncovered);
                return new Plan(filled, entryNet, 0, 0, 0, null, List.of(), null, null, Money.toCents(underlying), worst, blocks, warnings, "{}");
            }
            long maxLossCal = -entryNet;
            long feesCal = feesFor(filled, req.qty());
            warnings.add("Calendar/diagonal position: max profit and probability of profit depend on future volatility and are not shown");
            Map<String, Object> snapCal = new LinkedHashMap<>();
            snapCal.put("underlying", underlying.toPlainString());
            snapCal.put("freshness", worst.name());
            snapCal.put("asOf", now());
            if (world != null) snapCal.put("laneTime", java.time.LocalDateTime.ofInstant(
                    nowInstant, io.liftandshift.strikebench.market.MarketHours.EASTERN).toString());
            snapCal.put("legs", snapshotLegs);
            return new Plan(filled, entryNet, feesCal, 0, maxLossCal, null, List.of(), null, null,
                    Money.toCents(underlying), worst, blocks, warnings, Json.write(snapCal), sharesToLock,
                    snapshotLegs, assignProb, List.of());
        }

        // Held-shares trades are risk-shaped as the COMBINED position (option legs + the held
        // lot at today's price): that curve drives POP/breakevens/max-profit display — for a
        // protective put too, so preview matches the Ideas candidate — while the ledger's
        // maxLoss/reserve track only NEW cash this trade can lose (a covered call adds none;
        // a hedge costs its debit). The held-lot count for display is max(lockedLots, 1).
        boolean shareContext = req.heldShares();
        boolean shareCovered = shareContext && coverSharesPerUnit > 0;
        long contextSharesPerUnit = shareContext ? Math.max(coverSharesPerUnit,
                io.liftandshift.strikebench.strategy.CoverageCheck.shareContextUnitsNeeded(filled)) : 0;
        PayoffCurve riskCurve = curve;
        if (shareContext) {
            List<Leg> combined = new ArrayList<>(filled);
            combined.add(Leg.stockShares(LegAction.BUY, Math.toIntExact(contextSharesPerUnit), underlying));
            riskCurve = PayoffCurve.of(combined, req.qty(), netAdjust); // YOUR price shifts this curve too
            warnings.add(shareCovered
                    ? "Covered by " + sharesToLock + " held shares (locked while this trade is open) — "
                        + "risk figures include those shares at today's price; they keep their own downside"
                    : "Risk figures include " + (contextSharesPerUnit * req.qty()) + " held shares at today's price. "
                        + "Long options do not lock shares — selling the shares later turns this into a plain option position");
        }
        // Chart-ready payoff samples: computed even for blocked plans so the builder can
        // SHOW the cliff (an uncapped short call's curve teaches more than the block text).
        List<Map<String, Object>> payoff = chartPointMaps(riskCurve, underlying);

        double spot = underlying.doubleValue();
        double t = tte.years();
        if (ivs.isEmpty()) {
            warnings.add("No implied volatility available — POP/EV assume a 30% placeholder volatility");
        }
        double ivAvg = ivs.isEmpty() ? FALLBACK_IV : ivs.stream().mapToDouble(Double::doubleValue).average().orElse(FALLBACK_IV);

        // SHORT-DURATION REGIME (1–5 sessions): gamma concentration, weekend gaps and pin risk are
        // the trade — literal 0DTE was the only timing warning before, and a Friday-sold Monday
        // condor sailed through unwarned (the MU incident).
        List<java.math.BigDecimal> shortStrikes = filled.stream()
                .filter(l -> !l.isStock() && l.action() == LegAction.SELL)
                .map(Leg::strike).filter(java.util.Objects::nonNull).distinct().toList();
        if (tte.sessions() <= 5 && tte.sessions() >= 0) {
            String weekend = tte.calendarDays() - tte.sessions() >= 2
                    ? " including a weekend/holiday gap the position is exposed to" : "";
            warnings.add("Near-expiry gamma: only " + tte.sessions() + " trading session"
                    + (tte.sessions() == 1 ? "" : "s") + " remain"
                    + (tte.sessions() == 1 ? "s" : "") + " to the nearest expiration ("
                    + tte.calendarDays() + " calendar days" + weekend
                    + ") — small moves swing the P/L hard and there is little time to be wrong");
            double emOneSession = spot * ivAvg * Math.sqrt(1.0 / 252.0);
            for (java.math.BigDecimal k : shortStrikes) {
                if (Math.abs(k.doubleValue() - spot) <= emOneSession) {
                    warnings.add("Pin/assignment risk: short strike " + k.stripTrailingZeros().toPlainString()
                            + " sits INSIDE the one-session expected move (~" + Money.fmt(Math.round(emOneSession * 100))
                            + ") — plan the exit before the final hour, and expect assignment mechanics if it finishes near the strike");
                    break;
                }
            }
        }

        if (riskCurve.maxLossUnbounded()) {
            blocks.add("Undefined (unlimited) risk: this position can lose more than any amount reserved. Add a protective leg to cap the loss.");
            Map<String, Object> analyticsBlocked = buildAnalytics(riskCurve, spot, ivAvg, t, tte, shortStrikes,
                    snapshotLegs, req.qty(), entryNet, executableNet, packageMid, req.proposedNetCents(),
                    0, null, null, null, worst, marks.underlyingAsOfMs(req.symbol(), world).orElse(null),
                    rfr, rateEvidence);
            return new Plan(filled, entryNet, 0, 0, 0, null, List.of(), null, null, Money.toCents(underlying),
                    worst, blocks, warnings, "{}", 0, snapshotLegs, assignProb, payoff, analyticsBlocked);
        }
        long combinedMaxLoss = riskCurve.maxLossCents();
        if (combinedMaxLoss <= 0 && !shareContext) {
            blocks.add("Computed max loss is $0.00 — a risk-free position does not exist in real markets. "
                    + "The quotes feeding this trade are unreliable (stale, crossed, or expired book); refusing to fill.");
            return new Plan(filled, entryNet, 0, 0, 0, null, List.of(), null, null, Money.toCents(underlying),
                    worst, blocks, warnings, "{}", 0, snapshotLegs, assignProb, payoff);
        }
        long maxLoss = shareContext ? Math.max(0, -entryNet) : combinedMaxLoss;
        Long maxProfit = riskCurve.maxProfitUnbounded() ? null : riskCurve.maxProfitCents();
        long fees = req.feesOverrideCents() != null ? Math.max(0, req.feesOverrideCents()) : feesFor(filled, req.qty());
        long reserve = shareContext ? 0 : Math.max(0, maxLoss + entryNet);

        var riskNeutral = io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.analyze(
                riskCurve, spot, ivAvg, t, rfr, shortStrikes);
        Double pop = riskNeutral.probabilityMap().pAnyProfit();
        Long ev = riskNeutral.expectedValueCents();

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("underlying", underlying.toPlainString());
        snapshot.put("freshness", worst.name());
        snapshot.put("rateEvidence", rateEvidence);
        snapshot.put("asOf", now());
        // DUAL TIMESTAMPS for world trades: wall time above, the SIMULATED clock here — a session
        // report must place each decision on the lane's own clock (weekend-handoff M9).
        if (world != null) snapshot.put("laneTime", java.time.LocalDateTime.ofInstant(
                nowInstant, io.liftandshift.strikebench.market.MarketHours.EASTERN).toString());
        snapshot.put("legs", snapshotLegs);
        if (req.feesOverrideCents() != null && !req.executedFill()) {
            snapshot.put("feeOverridePerSideCents", fees);
        }
        if (shareCovered) snapshot.put("coveredByHeldShares", sharesToLock);
        if (shareContext) snapshot.put("heldShareContextShares",
                Math.multiplyExact(contextSharesPerUnit, req.qty()));

        Map<String, Object> analytics = buildAnalytics(riskCurve, spot, ivAvg, t, tte, shortStrikes,
                snapshotLegs, req.qty(), entryNet, executableNet, packageMid, req.proposedNetCents(),
                fees, maxLoss, maxProfit, ev, worst, marks.underlyingAsOfMs(req.symbol(), world).orElse(null),
                rfr, rateEvidence);
        if (shareContext) analytics.put("combinedMaxLossCents", combinedMaxLoss);
        return new Plan(filled, entryNet, fees, reserve, maxLoss, maxProfit,
                riskCurve.breakevens().stream().map(BigDecimal::toPlainString).toList(),
                pop, ev, Money.toCents(underlying), worst, blocks, warnings, Json.write(snapshot), sharesToLock,
                snapshotLegs, assignProb, payoff, analytics);
    }

    private static List<Map<String, Object>> chartPointMaps(PayoffCurve curve, BigDecimal spot) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PayoffCurve.ChartPoint p : curve.chartPoints(spot)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("price", p.price().toPlainString());
            m.put("profitCents", p.profitCents());
            out.add(m);
        }
        return out;
    }

    // ---- Evaluation analytics (the one pipeline every entry path shares) ----

    /** Signed package midpoint from the leg snapshots: SELL +, BUY −, per-unit x multiplier x ratio x qty. */
    private static Long packageMidCents(List<Map<String, Object>> snaps, int qty) {
        long total = 0;
        for (Map<String, Object> snap : snaps) {
            Object midS = snap.get("mid");
            if (midS == null) return null;
            int sign = "SELL".equals(snap.get("action")) ? 1 : -1;
            int ratio = ((Number) snap.get("ratio")).intValue();
            int multiplier = requiredSnapshotInteger(snap, "multiplier");
            long shares = Math.multiplyExact((long) multiplier, ratio);
            total += sign * Money.centsFromPrice(new BigDecimal(midS.toString()), shares * qty);
        }
        return total;
    }

    /** Total quoted spread across the package (ask−bid summed by exact deliverable); null if any book is one-sided. */
    private static Long packageSpreadCents(List<Map<String, Object>> snaps, int qty) {
        long total = 0;
        for (Map<String, Object> snap : snaps) {
            if ("STOCK".equals(snap.get("type"))) continue;
            Object bidS = snap.get("bid"), askS = snap.get("ask");
            if (bidS == null || askS == null) return null;
            BigDecimal spread = new BigDecimal(askS.toString()).subtract(new BigDecimal(bidS.toString()));
            if (spread.signum() < 0) return null; // crossed book — not a meaningful spread
            int ratio = ((Number) snap.get("ratio")).intValue();
            int multiplier = requiredSnapshotInteger(snap, "multiplier");
            total += Money.centsFromPrice(spread,
                    Math.multiplyExact(Math.multiplyExact((long) multiplier, ratio), qty));
        }
        return total;
    }

    private static int requiredSnapshotInteger(Map<String, Object> snapshot, String key) {
        Object raw = snapshot.get(key);
        if (!(raw instanceof Number number) || number.intValue() < 1) {
            throw new IllegalStateException("Current leg receipt is missing a valid " + key + ".");
        }
        return number.intValue();
    }


    /**
     * The assembled judgment every Review consumer shares: full probability map (risk-neutral,
     * labeled), EV sensitivity to the vol input, execution quality vs the books, a DTE-aware
     * management plan, and a server-computed verdict — so Beginner and Expert read the SAME truth.
     */
    private Map<String, Object> buildAnalytics(PayoffCurve curve, double spot, double ivAvg, double t,
                                               io.liftandshift.strikebench.market.OptionTime.Measure tte,
                                               List<BigDecimal> shortStrikes,
                                               List<Map<String, Object>> snaps, int qty,
                                               long entryNet, long executableNet, Long packageMid,
                                               Long proposedNet, long fees, Long maxLoss, Long maxProfit,
                                               Long ev, Freshness freshness, Long sourceAsOf, double rfr,
                                               io.liftandshift.strikebench.model.DataEvidence rateEvidence) {
        Map<String, Object> out = new LinkedHashMap<>();
        var riskNeutral = io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer
                .analyze(curve, spot, ivAvg, t, rfr, shortStrikes);
        var map = riskNeutral.probabilityMap();
        Map<String, Object> prob = new LinkedHashMap<>();
        prob.put("pAnyProfit", map.pAnyProfit());
        prob.put("pMaxProfit", map.pMaxProfit());
        prob.put("pMaxLoss", map.pMaxLoss());
        prob.put("pPartial", map.pPartial());
        prob.put("cvar95Cents", map.cvar95Cents());
        prob.put("stressLossCents", map.stressLossCents());
        prob.put("touches", map.touches().stream().map(x -> Map.of(
                "strike", x.strike().stripTrailingZeros().toPlainString(),
                "probability", x.probability())).toList());
        prob.put("basis", map.basis());
        prob.put("timeBasis", tte.basis());
        out.put("probabilityMap", prob);

        // EV sensitivity: the same integral at ±20% of the vol input — one falsely precise number
        // never travels alone.
        List<Map<String, Object>> sens = riskNeutral.sensitivity().stream()
                .map(x -> Map.<String, Object>of("ivScale", x.ivScale(), "evCents", x.evCents()))
                .toList();
        out.put("evSensitivity", sens);

        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("executableNetCents", executableNet);
        exec.put("midNetCents", packageMid);
        if (proposedNet != null) exec.put("proposedNetCents", proposedNet);
        Long spread = packageSpreadCents(snaps, qty);
        exec.put("packageSpreadCents", spread);
        if (spread != null) exec.put("exitSpreadEstimateCents", spread / 2);
        if (packageMid != null) {
            long basisNet = proposedNet != null ? proposedNet : executableNet;
            long concession = packageMid - basisNet;   // dollars surrendered vs midpoint (signed net)
            exec.put("concessionVsMidCents", concession);
            if (packageMid != 0) exec.put("concessionPctOfMid", round4((double) concession / Math.abs(packageMid)));
            if (maxProfit != null && maxProfit > 0) exec.put("concessionPctOfMaxProfit", round4((double) concession / maxProfit));
            if (maxLoss != null && maxLoss > 0) exec.put("concessionPctOfMaxRisk", round4((double) concession / maxLoss));
        }
        out.put("executionQuality", exec);

        out.put("managementPlan", dtePlan(tte, entryNet >= 0));
        // The ±1σ expected move to the nearest expiry (same vol/time basis as the map) — the UI
        // draws it on the payoff chart so 'shorts inside the expected move' is VISIBLE, not prose.
        double sdMove = ivAvg * Math.sqrt(t);
        Map<String, Object> em = new LinkedHashMap<>();
        em.put("lowCents", Math.round(spot * Math.exp(-sdMove) * 100));
        em.put("highCents", Math.round(spot * Math.exp(sdMove) * 100));
        em.put("oneSessionCents", Math.round(spot * ivAvg * Math.sqrt(1.0 / 252.0) * 100));
        out.put("expectedMove", em);
        // R3: three clocks, separately — the data's own stamp, and when WE judged it. (fetchedAt
        // collapses into sourceAsOf for feeds without a distinct source stamp.)
        out.put("sourceAsOfEpochMs", sourceAsOf);
        out.put("evaluatedAtEpochMs", clock.millis());
        out.put("freshness", freshness.name());
        out.put("rate", Map.of(
                "annual", rfr,
                "evidence", rateEvidence == null
                        ? io.liftandshift.strikebench.model.DataEvidence.missing("rate assumption")
                        : rateEvidence));

        // The assembled verdict: worst-triggered tier wins; the reason names the single biggest problem.
        String verdict = "favorable"; String reason = "Model odds, payoff and execution costs look reasonable together.";
        double pAny = map.pAnyProfit();
        Object concessionPct = exec.get("concessionPctOfMid");
        boolean expensive = concessionPct instanceof Double d && Math.abs(d) > 0.10;
        // EV is a terminal payoff expectation. Judge it against the same estimated round-trip
        // commissions used by EconomicAssessment and the ticket acknowledgment, not merely the
        // opening commission. Otherwise Builder, Ideas and Decide show three different numbers
        // for the same package.
        long evAfterFees = (ev == null ? 0 : ev) - Math.multiplyExact(fees, 2L);
        if (curve.maxLossUnbounded()) {
            verdict = "unfavorable"; reason = "Risk is UNDEFINED — the stress loss below is a scenario, not a cap.";
        } else if (ev != null && evAfterFees < 0 && pAny < 0.45) {
            verdict = "unfavorable";
            reason = "Negative expected value (" + Money.fmt(evAfterFees) + " after fees) with the odds against it ("
                    + Math.round(pAny * 100) + "% chance of any profit, " + Math.round(map.pMaxLoss() * 100)
                    + "% chance of max loss) — the market is charging more than this position is worth by its own odds.";
        } else if (map.pMaxLoss() > 0.5) {
            verdict = "unfavorable";
            reason = "The single most likely outcome is FULL max loss (" + Math.round(map.pMaxLoss() * 100) + "%).";
        } else if (expensive) {
            verdict = "mixed";
            reason = "Execution is expensive: crossing these books surrenders "
                    + Math.round(Math.abs((Double) concessionPct) * 100) + "% of the package midpoint before the trade even starts.";
        } else if (tte.sessions() <= 5 && tte.sessions() >= 0 && !shortStrikes.isEmpty()) {
            verdict = "mixed";
            reason = "Only " + tte.sessions() + " trading session" + (tte.sessions() == 1 ? "" : "s")
                    + " remain — gamma and pin risk dominate; the plan matters more than the entry.";
        } else if (ev != null && evAfterFees < 0) {
            verdict = "mixed";
            reason = "Expected value is slightly negative after fees (" + Money.fmt(evAfterFees) + ") at the market's own volatility.";
        }
        out.put("verdict", verdict);
        out.put("verdictReason", reason);
        return out;
    }

    /** DTE-aware management plan: a 3-day trade must never be told to 'roll at 21 DTE'. */
    private static Map<String, Object> dtePlan(
            io.liftandshift.strikebench.market.OptionTime.Measure tte, boolean credit) {
        List<String> rules = new ArrayList<>();
        String regime;
        if (tte.sessions() <= 5) {
            regime = "near-expiry (" + tte.sessions() + " session" + (tte.sessions() == 1 ? "" : "s") + ")";
            rules.add("Take profits early — at 50% of the maximum, do not wait for the last dollar");
            rules.add("If price TOUCHES a short strike, close that side immediately; do not hold and hope");
            rules.add("Do not carry a short strike that is near the money into the final hour — pin and assignment mechanics take over");
            if (tte.calendarDays() - tte.sessions() >= 2) {
                rules.add("A weekend/holiday gap sits inside this trade — the market can reopen through your strikes with no chance to react");
            }
            rules.add("Rolling is NOT a plan at this distance — closing is");
        } else if (tte.sessions() <= 15) {
            regime = "short-dated (~" + tte.sessions() + " sessions)";
            rules.add("Take profit at 50% of the maximum" + (credit ? " credit" : ""));
            rules.add(credit ? "Stop if the loss reaches the credit received (1x) — being wrong fast is information"
                    : "Stop if the position loses half its cost");
            rules.add("In the final week, close or roll — do not let a winner decay into a coin flip at expiry");
        } else {
            regime = "standard (" + tte.calendarDays() + " days)";
            rules.add("Take profit at 50% of the maximum" + (credit ? " credit" : ""));
            rules.add(credit ? "Stop at 2x the credit received" : "Stop at half the debit paid");
            rules.add("Manage or roll around 21 days to expiration — gamma grows fast after that");
        }
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("regime", regime);
        plan.put("sessions", tte.sessions());
        plan.put("calendarDays", tte.calendarDays());
        plan.put("rules", rules);
        return plan;
    }

    // ---- Shared helpers ----

    private CloseResult closeOut(Connection c, TradeRecord t, Account acct, String cashRowType,
                                 long closeValue, long feesClose, String newStatus, String closeReason,
                                 Long decisionUnderlyingCents) throws SQLException {
        String now = now();
        long actionRealized = Math.subtractExact(Math.addExact(
                Math.subtractExact(t.entryNetPremiumCents(), t.feesOpenCents()), closeValue), feesClose);
        long realizedToDate = Math.addExact(t.realizedPnlCents() == null ? 0 : t.realizedPnlCents(),
                actionRealized);
        long actionDecisionPnl = decisionPnlAtMark(t, actionRealized, decisionUnderlyingCents);
        long decisionPnlToDate = Math.addExact(t.decisionPnlCents() == null ? 0 : t.decisionPnlCents(),
                actionDecisionPnl);
        long closeFeesToDate = Math.addExact(t.feesCloseCents(), feesClose);
        long cash = acct.cashCents(), reserved = acct.reservedCents();

        cash += closeValue;
        ledgerRow(c, acct.id(), t.id(), now, cashRowType, closeValue, cash, reserved,
                t.strategy() + " x" + t.qty() + " " + closeReason.toLowerCase(java.util.Locale.ROOT));
        if (feesClose != 0) {
            cash -= feesClose;
            ledgerRow(c, acct.id(), t.id(), now, "FEE", -feesClose, cash, reserved, "close commissions");
        }
        long reserve = outstandingReserve(c, t.id());
        if (reserve != 0) {
            reserved -= reserve;
            ledgerRow(c, acct.id(), t.id(), now, "RESERVE_RELEASE", -reserve, cash, reserved, "reserve released on close");
        }
        Db.execOn(c, "UPDATE trades SET status=?, close_reason=?, fees_close_cents=?, realized_pnl_cents=?, decision_pnl_cents=?, closed_at=?, updated_at=? WHERE id=?",
                newStatus, closeReason, closeFeesToDate, realizedToDate, decisionPnlToDate, now, now, t.id());
        Db.execOn(c, "UPDATE accounts SET cash_cents=?, reserved_cents=?, updated_at=? WHERE id=?", cash, reserved, now, acct.id());
        return new CloseResult(getOn(c, t.id()), realizedToDate, actionRealized);
    }

    private static long decisionPnlAtMark(TradeRecord t, long incrementalPnl, Long underlyingCents) {
        long shares = heldShareContextShares(t);
        if (shares <= 0 || underlyingCents == null || t.entryUnderlyingCents() <= 0) return incrementalPnl;
        return incrementalPnl + (underlyingCents - t.entryUnderlyingCents()) * shares;
    }

    private static long decisionPnlAtSettlement(TradeRecord t, BigDecimal underlyingClose, long incrementalPnl) {
        long expirations = t.legs().stream().filter(l -> !l.isStock())
                .map(Leg::expiration).distinct().count();
        if (underlyingClose == null || expirations > 1) return incrementalPnl;
        long totalShares = heldShareContextShares(t);
        List<Leg> combined = new ArrayList<>(t.legs());
        if (totalShares > 0 && t.qty() > 0 && totalShares % t.qty() == 0 && t.entryUnderlyingCents() > 0) {
            combined.add(Leg.stockShares(LegAction.BUY, Math.toIntExact(totalShares / t.qty()),
                    BigDecimal.valueOf(t.entryUnderlyingCents(), 2)));
        }
        long tradedLegEntry = PayoffCurve.of(t.legs(), t.qty()).entryNetPremiumCents();
        long adjustment = t.entryNetPremiumCents() - tradedLegEntry;
        return PayoffCurve.of(combined, t.qty(), adjustment).profitAtCents(underlyingClose)
                - t.feesOpenCents();
    }

    /** Exact held-share decision context for every current receipt. */
    private static long heldShareContextShares(TradeRecord t) {
        Long exact = entrySnapshotLong(t, "heldShareContextShares");
        return exact == null ? 0 : Math.max(0, exact);
    }

    /** Exact held-share decision context for read models such as the trade-detail payoff. */
    public static long heldShareContextSharesForDisplay(TradeRecord t) {
        return heldShareContextShares(t);
    }

    private static long heldShareUnitsPerPackage(List<Leg> legs) {
        return io.liftandshift.strikebench.strategy.CoverageCheck.shareContextUnitsNeeded(legs);
    }

    /** Paper what-if fees are explicitly per side; broker-import fees remain entry facts only. */
    private long closeFeesFor(TradeRecord t) {
        return closeFeesFor(t, t.qty());
    }

    private long closeFeesFor(TradeRecord t, int quantity) {
        Long override = entrySnapshotLong(t, "feeOverridePerSideCents");
        if (override != null) return Math.max(0, allocatedPrefix(override, t.qty(), quantity));
        return feesFor(t.legs(), quantity);
    }

    private ExecutableClose executableClose(TradeRecord trade) {
        return executableClose(trade, trade.qty());
    }

    private ExecutableClose executableClose(TradeRecord trade, int quantity) {
        if (quantity <= 0 || quantity > trade.qty()) {
            throw new IllegalArgumentException("closing quantity must be between 1 and " + trade.qty());
        }
        long closeValue = 0;
        var lane = laneFor(worldOf(trade.accountId()));
        for (Leg leg : trade.legs()) {
            MarksSource.LegMark mark = marks.legMark(trade.symbol(), leg, worldOf(trade.accountId()))
                    .orElseThrow(() -> new TradeRejectedException(List.of(
                            "No current mark for leg " + legDesc(leg) + "; cannot value the close")));
            if (!mark.evidence().executableIn(lane)) {
                throw new TradeRejectedException(List.of("Cannot close " + legDesc(leg) + " in the " + lane
                        + " market using " + mark.evidence().provenance() + " data (" + mark.evidence().source()
                        + ", " + mark.evidence().age() + "). Refresh an executable quote or switch to the market that owns this data."));
            }
            LegAction closingAction = leg.action().opposite();
            BigDecimal px = mark.executable(closingAction);
            if (px == null) {
                throw new TradeRejectedException(List.of("No executable "
                        + (closingAction == LegAction.BUY ? "ask" : "bid") + " to close " + legDesc(leg)
                        + " — the book is one-sided or empty; try during market hours"));
            }
            closeValue = Math.addExact(closeValue, Math.multiplyExact(closeSign(leg),
                    Money.centsFromPrice(px, (long) leg.multiplier() * leg.ratio() * quantity)));
        }
        return new ExecutableClose(closeValue, closeFeesFor(trade, quantity));
    }

    private static void requirePartialQuantity(TradeRecord trade, int closeQuantity) {
        if (!TradeRecord.ACTIVE.equals(trade.status())) {
            throw new IllegalStateException("trade is " + trade.status() + "; only ACTIVE trades can be transformed");
        }
        if (closeQuantity <= 0 || closeQuantity >= trade.qty()) {
            throw new IllegalArgumentException("partial close quantity must be between 1 and "
                    + (trade.qty() - 1));
        }
    }

    private static void requirePracticeTransformation(TradeRecord trade) {
        // This service owns Practice trades only; tracked broker positions use portfolio structures.
    }

    private SettlementReference settlementReference(TradeRecord trade, Leg leg,
                                                    PositionTransformation.Action action,
                                                    String world, Instant laneNow) {
        boolean dead = io.liftandshift.strikebench.market.MarketHours.contractDead(leg.expiration(), laneNow);
        if (action == PositionTransformation.Action.EXPIRATION && !dead) {
            throw new TradeRejectedException(List.of("This contract is still alive in its market lane until 4:00pm ET on "
                    + leg.expiration() + "."));
        }
        if (!dead) {
            var lane = laneFor(world);
            var evidence = marks.underlyingEvidence(trade.symbol(), world).orElse(null);
            if (evidence != null && !evidence.executableIn(lane)) {
                throw new TradeRejectedException(List.of("Cannot apply an early "
                        + action.name().toLowerCase(java.util.Locale.ROOT) + " in the " + lane
                        + " market using " + evidence.provenance() + " underlying data ("
                        + evidence.source() + ", " + evidence.age() + "). Refresh the lane-owned quote first."));
            }
            BigDecimal mark = marks.underlyingMark(trade.symbol(), world)
                    .orElseThrow(() -> new TradeRejectedException(List.of(
                            "No lane-owned underlying mark is available for this early lifecycle event.")));
            return new SettlementReference(mark,
                    "current " + laneFor(world) + " underlying mark for an early " + action.name().toLowerCase(java.util.Locale.ROOT),
                    true);
        }
        BigDecimal close = marks.closeOn(trade.symbol(), leg.expiration(), world).orElse(null);
        if (close != null) {
            return new SettlementReference(close, leg.expiration() + " expiration-day close", true);
        }
        long expirations = trade.legs().stream().filter(candidate -> !candidate.isStock())
                .map(Leg::expiration).distinct().count();
        if (expirations > 1) {
            throw new TradeRejectedException(List.of("The " + leg.expiration()
                    + " expiration-day close is missing for this multi-expiration position. "
                    + "Backfill that exact close before converting one leg; a current price cannot stand in for an earlier expiry."));
        }
        LocalDate today = LocalDate.ofInstant(laneNow,
                io.liftandshift.strikebench.market.MarketHours.EASTERN);
        if (!today.isAfter(leg.expiration())) {
            throw new TradeRejectedException(List.of("The expiration-day closing price is not available yet — retry after the next session"
                    + " or configure a candle source for exact settlement."));
        }
        BigDecimal fallback = marks.underlyingMark(trade.symbol(), world)
                .orElseThrow(() -> new TradeRejectedException(List.of(
                        "No underlying price is available for the disclosed settlement fallback.")));
        return new SettlementReference(fallback,
                "expiration close unavailable — current lane mark used as a labeled fallback; value may differ from true settlement",
                false);
    }

    private static ProjectedHolding projectHolding(PositionsService.Position holding, long sharesDelta,
                                                   long strikePerShareCents) {
        long beforeShares = holding == null ? 0 : holding.shares();
        long beforeBasis = holding == null ? 0 : holding.avgCostCents();
        long afterShares = Math.addExact(beforeShares, sharesDelta);
        if (afterShares < 0) throw new IllegalStateException("physical delivery cannot create an untracked short share position");
        if (afterShares == 0) return new ProjectedHolding(beforeShares, beforeBasis, 0, 0);
        if (sharesDelta <= 0) return new ProjectedHolding(beforeShares, beforeBasis, afterShares, beforeBasis);
        long oldCost = Math.multiplyExact(beforeBasis, beforeShares);
        long addedCost = Math.multiplyExact(strikePerShareCents, sharesDelta);
        long afterBasis = BigDecimal.valueOf(Math.addExact(oldCost, addedCost))
                .divide(BigDecimal.valueOf(afterShares), 0, java.math.RoundingMode.HALF_UP)
                .longValueExact();
        return new ProjectedHolding(beforeShares, beforeBasis, afterShares, afterBasis);
    }

    private static String lifecycleStrategy(String symbol, List<LegLot> optionLots,
                                            long contextShares, long stockBasisCents) {
        List<LegLot> identityLots = new ArrayList<>(optionLots);
        if (contextShares > 0) {
            identityLots.add(new LegLot(Leg.stockShares(LegAction.BUY, 1,
                    BigDecimal.valueOf(stockBasisCents, 2)), contextShares));
        }
        var identity = io.liftandshift.strikebench.strategy.StrategyCatalog.identify(symbol,
                canonicalQuantity(identityLots), canonicalLegs(identityLots));
        if (identity.family() != null) return identity.family();
        if (identity.template() != null) return identity.template();
        return "CUSTOM";
    }

    private static String lifecycleIntent(String strategy, String priorIntent) {
        if ("PROTECTIVE_PUT".equals(strategy) || "PROTECTIVE_COLLAR".equals(strategy)) return "HEDGE";
        if ("COVERED_CALL".equals(strategy)) return "EXIT".equals(priorIntent) ? "EXIT" : "INCOME";
        return priorIntent;
    }

    private static boolean lifecycleAction(PositionTransformation.Action action) {
        return action == PositionTransformation.Action.ASSIGNMENT
                || action == PositionTransformation.Action.EXERCISE
                || action == PositionTransformation.Action.EXPIRATION;
    }

    private static long strikePerShareCents(TradeRecord trade, int legIndex) {
        if (legIndex < 0 || legIndex >= trade.legs().size() || trade.legs().get(legIndex).isStock()) {
            throw new IllegalArgumentException("legIndex must identify one current option leg");
        }
        return Money.toCents(trade.legs().get(legIndex).strike());
    }

    private static String lifecycleStateFingerprint(TradeRecord trade,
                                                    PositionTransformation.Action action,
                                                    int legIndex, long settlementUnderlyingCents,
                                                    long optionSettlementCashCents,
                                                    long stockCashCents, long heldContextAfter,
                                                    OpenRequest exactAfter) {
        try {
            Map<String, Object> stable = new LinkedHashMap<>();
            stable.put("tradeId", trade.id());
            stable.put("action", action.name());
            stable.put("legIndex", legIndex);
            stable.put("selectedContract", contractKey(trade.legs().get(legIndex)));
            stable.put("settlementUnderlyingCents", settlementUnderlyingCents);
            stable.put("optionSettlementCashCents", optionSettlementCashCents);
            stable.put("stockCashCents", stockCashCents);
            stable.put("heldShareContextAfter", heldContextAfter);
            stable.put("survivor", exactAfter == null ? null : exactPositionFingerprint(exactAfter));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    Json.canonical(stable).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("cannot fingerprint option lifecycle conversion", e);
        }
    }

    private static void requireExpectedLifecycle(LifecycleAssessment actual, ExpectedLifecycle expected) {
        if (actual.settlementUnderlyingCents() != expected.settlementUnderlyingCents()
                || actual.optionSettlementCashCents() != expected.optionSettlementCashCents()
                || actual.stockCashCents() != expected.stockCashCents()
                || actual.sharesDelta() != expected.sharesDelta()
                || actual.allocatedEntryBasisCents() != expected.allocatedEntryBasisCents()
                || actual.allocatedOpenFeesCents() != expected.allocatedOpenFeesCents()
                || actual.reserveAfterCents() != expected.reserveAfterCents()
                || actual.heldShareContextAfter() != expected.heldShareContextAfter()
                || actual.sharesLockedAfter() != expected.sharesLockedAfter()
                || !Objects.equals(actual.exactStateFingerprint(), expected.exactStateFingerprint())) {
            throw new TradeRejectedException(List.of(
                    "The lane mark, option state, stock delivery, or surviving collateral changed after preview. Review the lifecycle conversion again."));
        }
    }

    private static boolean adjustmentAction(PositionTransformation.Action action) {
        return action == PositionTransformation.Action.LEG_CLOSE
                || action == PositionTransformation.Action.REMOVE_LEG
                || action == PositionTransformation.Action.ADD_LEG
                || action == PositionTransformation.Action.ADD_STOCK
                || action == PositionTransformation.Action.REMOVE_STOCK;
    }

    private List<LegLot> priceAddedLots(TradeRecord trade, List<LegLot> additions) {
        if (additions.isEmpty()) return List.of();
        List<LegLot> unpriced = additions.stream().map(lot -> new LegLot(new Leg(lot.leg().action(),
                lot.leg().type(), lot.leg().strike(), lot.leg().expiration(), lot.leg().ratio(),
                BigDecimal.ZERO, lot.leg().multiplier()), lot.quantity())).toList();
        int quantity = canonicalQuantity(unpriced);
        OpenRequest addedRequest = new OpenRequest(trade.accountId(), trade.symbol(), "CUSTOM", quantity,
                canonicalLegs(unpriced), trade.thesis(), trade.horizon(), trade.riskMode(), trade.intent(),
                false, null, null, "POSITION_TRANSFORMATION", "PROPOSED");
        String world = worldOf(trade.accountId());
        Plan priced = computePlan(addedRequest, false, world, true, laneFor(world), false);
        if (priced.filledLegs().size() != addedRequest.legs().size()) {
            List<String> reasons = priced.blocks().isEmpty()
                    ? List.of("The added quantity has no executable market price.") : priced.blocks();
            throw new TradeRejectedException(reasons);
        }
        return lotsFromLegs(priced.filledLegs(), addedRequest.qty());
    }

    private static ReconciledLots reconcileLots(TradeRecord trade, OpenRequest desiredAfter) {
        List<LegLot> current = lotsFromTrade(trade);
        List<LegLot> target = lotsFromLegs(desiredAfter.legs(), desiredAfter.qty());
        Map<String, Long> currentAmounts = amounts(current);
        Map<String, Long> targetAmounts = amounts(target);
        Map<String, Long> removeNeeded = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : currentAmounts.entrySet()) {
            removeNeeded.put(entry.getKey(), Math.max(0,
                    Math.subtractExact(entry.getValue(), targetAmounts.getOrDefault(entry.getKey(), 0L))));
        }
        List<LegLot> retained = new ArrayList<>();
        List<LegLot> removed = new ArrayList<>();
        for (LegLot lot : current) {
            String key = contractKey(lot.leg());
            long remove = Math.min(lot.quantity(), removeNeeded.getOrDefault(key, 0L));
            if (remove > 0) {
                removed.add(new LegLot(lot.leg(), remove));
                removeNeeded.put(key, Math.subtractExact(removeNeeded.get(key), remove));
            }
            long keep = Math.subtractExact(lot.quantity(), remove);
            if (keep > 0) retained.add(new LegLot(lot.leg(), keep));
        }
        Map<String, Leg> targetTemplates = new LinkedHashMap<>();
        for (LegLot lot : target) targetTemplates.putIfAbsent(contractKey(lot.leg()), lot.leg());
        List<LegLot> added = new ArrayList<>();
        for (Map.Entry<String, Long> entry : targetAmounts.entrySet()) {
            long increase = Math.max(0,
                    Math.subtractExact(entry.getValue(), currentAmounts.getOrDefault(entry.getKey(), 0L)));
            if (increase > 0) added.add(new LegLot(targetTemplates.get(entry.getKey()), increase));
        }
        return new ReconciledLots(List.copyOf(retained), List.copyOf(removed), List.copyOf(added));
    }

    private static List<LegLot> lotsFromTrade(TradeRecord trade) {
        return lotsFromLegs(trade.legs(), trade.qty());
    }

    private static List<LegLot> lotsFromLegs(List<Leg> legs, int packageQuantity) {
        List<LegLot> out = new ArrayList<>();
        for (Leg leg : legs) {
            out.add(new LegLot(leg, Math.multiplyExact(packageQuantity, (long) leg.ratio())));
        }
        return List.copyOf(out);
    }

    private static Map<String, Long> amounts(List<LegLot> lots) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (LegLot lot : lots) out.merge(contractKey(lot.leg()), lot.quantity(), Math::addExact);
        return out;
    }

    private static String contractKey(Leg leg) {
        return String.join("|", leg.action().name(), leg.isStock() ? "STOCK" : leg.type().name(),
                leg.isStock() ? "" : leg.strike().stripTrailingZeros().toPlainString(),
                leg.expiration() == null ? "" : leg.expiration().toString(), String.valueOf(leg.multiplier()));
    }

    private static long cashForLots(List<LegLot> lots) {
        long out = 0;
        for (LegLot lot : lots) {
            long amount = Money.centsFromPrice(lot.leg().entryPrice(),
                    Math.multiplyExact(lot.quantity(), (long) lot.leg().multiplier()));
            out = Math.addExact(out, lot.leg().action() == LegAction.SELL ? amount : -amount);
        }
        return out;
    }

    private static long basisUnits(List<LegLot> lots) {
        return lots.stream().mapToLong(LegLot::quantity).sum();
    }

    private static int canonicalQuantity(List<LegLot> lots) {
        if (lots.isEmpty()) throw new IllegalArgumentException("a surviving position needs at least one leg");
        long value = 0;
        for (LegLot lot : lots) value = gcd(value, lot.quantity());
        return Math.toIntExact(value);
    }

    private static List<Leg> canonicalLegs(List<LegLot> lots) {
        int quantity = canonicalQuantity(lots);
        return lots.stream().map(lot -> new Leg(lot.leg().action(), lot.leg().type(), lot.leg().strike(),
                lot.leg().expiration(), Math.toIntExact(lot.quantity() / quantity), lot.leg().entryPrice(),
                lot.leg().multiplier())).toList();
    }

    private static long gcd(long a, long b) {
        a = Math.abs(a); b = Math.abs(b);
        while (b != 0) { long next = a % b; a = b; b = next; }
        return a == 0 ? 1 : a;
    }

    private long executableCloseLots(TradeRecord trade, List<LegLot> removed) {
        long closeValue = 0;
        String world = worldOf(trade.accountId());
        var lane = laneFor(world);
        for (LegLot lot : removed) {
            Leg leg = lot.leg();
            MarksSource.LegMark mark = marks.legMark(trade.symbol(), leg, world)
                    .orElseThrow(() -> new TradeRejectedException(List.of(
                            "No current mark for leg " + legDesc(leg) + "; cannot value the close")));
            if (!mark.evidence().executableIn(lane)) {
                throw new TradeRejectedException(List.of("Cannot close " + legDesc(leg) + " in the " + lane
                        + " market using " + mark.evidence().provenance() + " data (" + mark.evidence().source()
                        + ", " + mark.evidence().age() + "). Refresh an executable quote or switch markets."));
            }
            LegAction closingAction = leg.action().opposite();
            BigDecimal px = mark.executable(closingAction);
            if (px == null) {
                throw new TradeRejectedException(List.of("No executable "
                        + (closingAction == LegAction.BUY ? "ask" : "bid") + " to close " + legDesc(leg)));
            }
            long amount = Money.centsFromPrice(px,
                    Math.multiplyExact(lot.quantity(), (long) leg.multiplier()));
            closeValue = Math.addExact(closeValue, closeSign(leg) > 0 ? amount : -amount);
        }
        return closeValue;
    }

    private long closeFeesForLots(TradeRecord trade, List<LegLot> removed) {
        Long override = entrySnapshotLong(trade, "feeOverridePerSideCents");
        if (override != null) {
            return Math.max(0, allocatedPrefix(override, basisUnits(lotsFromTrade(trade)), basisUnits(removed)));
        }
        return feesFor(canonicalLegs(removed), canonicalQuantity(removed));
    }

    private static String adjustmentCloseRowType(PositionTransformation.Action action, List<LegLot> removed) {
        if (action != PositionTransformation.Action.REMOVE_STOCK) return "PREMIUM_CLOSE";
        boolean positive = removed.stream().allMatch(lot -> lot.leg().action() == LegAction.BUY);
        boolean negative = removed.stream().allMatch(lot -> lot.leg().action() == LegAction.SELL);
        return positive ? "STOCK_SELL" : negative ? "STOCK_BUY" : "ADJUSTMENT";
    }

    private static String adjustmentOpenRowType(PositionTransformation.Action action, List<LegLot> added) {
        if (action != PositionTransformation.Action.ADD_STOCK) return "PREMIUM_OPEN";
        boolean debit = added.stream().allMatch(lot -> lot.leg().action() == LegAction.BUY);
        boolean credit = added.stream().allMatch(lot -> lot.leg().action() == LegAction.SELL);
        return debit ? "STOCK_BUY" : credit ? "STOCK_SELL" : "ADJUSTMENT";
    }

    private static String adjustedEntrySnapshot(TradeRecord trade, Plan exactPlan,
                                                PositionTransformation.Action action, String at,
                                                long allocatedPackageAdjustment,
                                                long allocatedOpenFees) {
        return transformedEntrySnapshot(trade, exactPlan, action, at,
                allocatedPackageAdjustment, allocatedOpenFees, null);
    }

    private static String lifecycleEntrySnapshot(TradeRecord trade, Plan exactPlan,
                                                 PositionTransformation.Action action, String at,
                                                 long allocatedPackageAdjustment,
                                                 long allocatedOpenFees,
                                                 long heldShareContextAfter) {
        return transformedEntrySnapshot(trade, exactPlan, action, at,
                allocatedPackageAdjustment, allocatedOpenFees, heldShareContextAfter);
    }

    @SuppressWarnings("unchecked")
    private static String transformedEntrySnapshot(TradeRecord trade, Plan exactPlan,
                                                   PositionTransformation.Action action, String at,
                                                   long allocatedPackageAdjustment,
                                                   long allocatedOpenFees,
                                                   Long heldShareContextAfter) {
        Map<String, Object> snapshot = new LinkedHashMap<>(Json.read(exactPlan.snapshotJson(), Map.class));
        Map<String, Object> prior = Json.read(trade.entrySnapshotJson(), Map.class);
        List<Map<String, Object>> history = new ArrayList<>();
        Object oldHistory = prior.get("transformationHistory");
        if (oldHistory instanceof List<?> rows) {
            for (Object row : rows) if (row instanceof Map<?, ?> map) history.add((Map<String, Object>) map);
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("action", action.name());
        event.put("at", at);
        event.put("priorQuantity", trade.qty());
        event.put("allocatedPackageAdjustmentCents", allocatedPackageAdjustment);
        event.put("allocatedOpenFeesCents", allocatedOpenFees);
        history.add(event);
        snapshot.put("transformationHistory", history);
        snapshot.put("basis", "retained exact fills plus executable transformation fills");
        snapshot.put("originalOpenedAt", prior.getOrDefault("originalOpenedAt", trade.createdAt()));
        if (heldShareContextAfter != null) {
            if (heldShareContextAfter > 0) snapshot.put("heldShareContextShares", heldShareContextAfter);
            else snapshot.remove("heldShareContextShares");
            if (exactPlan.sharesToLock() > 0) snapshot.put("coveredByHeldShares", exactPlan.sharesToLock());
            else snapshot.remove("coveredByHeldShares");
        }
        snapshot.remove("feeOverridePerSideCents");
        return Json.write(snapshot);
    }

    public static String exactPositionFingerprint(OpenRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Map<String, Object> stable = new LinkedHashMap<>();
            stable.put("symbol", request.symbol());
            stable.put("quantity", request.qty());
            stable.put("legs", request.legs());
            stable.put("entryNetCents", request.proposedNetCents());
            stable.put("feesOpenCents", request.feesOverrideCents());
            return HexFormat.of().formatHex(digest.digest(
                    Json.canonical(stable).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("cannot fingerprint exact position", e);
        }
    }

    private static void requireExpectedAdjustment(ExpectedAdjustment expected,
                                                  long closingCash, long openingCash,
                                                  long closingFees, long openingFees,
                                                  Plan plan, long sharesAfter,
                                                  OpenRequest exactAfter) {
        if (closingCash != expected.closingCashCents()
                || openingCash != expected.openingCashCents()
                || closingFees != expected.closingFeesCents()
                || openingFees != expected.openingFeesCents()
                || plan.entryNet() != expected.entryNetCents()
                || plan.fees() != expected.feesOpenCents()
                || plan.reserve() != expected.reserveAfterCents()
                || plan.maxLoss() != expected.maxLossCents()
                || !Objects.equals(plan.maxProfit(), expected.maxProfitCents())
                || sharesAfter != expected.sharesLocked()
                || !exactPositionFingerprint(exactAfter).equals(expected.legsFingerprint())) {
            throw new TradeRejectedException(List.of(
                    "The executable adjustment or surviving basis changed after preview. Review the exact before-and-after position again."));
        }
    }

    private record LegLot(Leg leg, long quantity) {
        LegLot {
            if (leg == null || quantity <= 0) throw new IllegalArgumentException("leg lot quantity must be positive");
        }
    }

    private record SettlementReference(BigDecimal underlying, String basis, boolean exact) {
        long underlyingCents() { return Money.toCents(underlying); }
    }

    private record ProjectedHolding(long beforeShares, long beforeBasisCents,
                                    long afterShares, long afterBasisCents) {}

    private record ReconciledLots(List<LegLot> retained, List<LegLot> removed, List<LegLot> added) {}

    /** Deterministic largest-remainder allocation to the first N identical packages. */
    static long allocatedPrefix(long total, long packageQuantity, long selectedQuantity) {
        if (packageQuantity <= 0 || selectedQuantity < 0 || selectedQuantity > packageQuantity) {
            throw new IllegalArgumentException("invalid package allocation");
        }
        long base = total / packageQuantity;
        long remainder = total % packageQuantity;
        long extraUnits = Math.min(selectedQuantity, Math.abs(remainder));
        long extra = remainder < 0 ? -extraUnits : extraUnits;
        return Math.addExact(Math.multiplyExact(base, selectedQuantity), extra);
    }

    static long remainingAllocation(long total, long packageQuantity, long removedQuantity) {
        return Math.subtractExact(total, allocatedPrefix(total, packageQuantity, removedQuantity));
    }

    @SuppressWarnings("unchecked")
    private static String survivorEntrySnapshot(TradeRecord trade, int survivingQuantity,
                                                long survivingShares) {
        Map<String, Object> snapshot = new LinkedHashMap<>(Json.read(trade.entrySnapshotJson(), Map.class));
        long removed = trade.qty() - survivingQuantity;
        snapshot.put("positionQuantity", survivingQuantity);
        if (snapshot.containsKey("coveredByHeldShares")) {
            if (survivingShares > 0) snapshot.put("coveredByHeldShares", survivingShares);
            else snapshot.remove("coveredByHeldShares");
        }
        Long contextShares = entrySnapshotLong(trade, "heldShareContextShares");
        if (contextShares != null) {
            long remaining = remainingAllocation(contextShares, trade.qty(), removed);
            if (remaining > 0) snapshot.put("heldShareContextShares", remaining);
            else snapshot.remove("heldShareContextShares");
        }
        Long override = entrySnapshotLong(trade, "feeOverridePerSideCents");
        if (override != null) {
            snapshot.put("feeOverridePerSideCents", remainingAllocation(override, trade.qty(), removed));
        }
        return Json.write(snapshot);
    }

    private static void requireExpectedClose(ExecutableClose actual, Long expectedCash, Long expectedFees) {
        if (expectedCash != null && actual.cashCents() != expectedCash
                || expectedFees != null && actual.feesCents() != expectedFees) {
            throw new TradeRejectedException(List.of(
                    "The executable closing book changed after the transformation preview. Review the updated before-and-after position before applying it."));
        }
    }

    private static void requireExpectedOpen(Plan actual, ExpectedOpen expected) {
        if (expected == null) return;
        if (actual.entryNet() != expected.entryNetCents() || actual.fees() != expected.feesCents()
                || actual.reserve() != expected.reserveCents() || actual.maxLoss() != expected.maxLossCents()
                || !Objects.equals(actual.maxProfit(), expected.maxProfitCents())) {
            throw new TradeRejectedException(List.of(
                    "The executable replacement book changed after the transformation preview. Review the updated roll before applying it."));
        }
    }

    private static void requireExpectedPosition(TradeRecord actual, long actualReserve,
                                                ExpectedPositionState expected) {
        if (expected == null) {
            throw new IllegalArgumentException("the reviewed current-position state is required");
        }
        long realized = actual.realizedPnlCents() == null ? 0 : actual.realizedPnlCents();
        if (actual.qty() != expected.quantity()
                || actual.entryNetPremiumCents() != expected.entryNetCents()
                || actual.feesOpenCents() != expected.feesOpenCents()
                || actual.maxLossCents() != expected.maxLossCents()
                || !Objects.equals(actual.maxProfitCents(), expected.maxProfitCents())
                || actual.sharesLocked() != expected.sharesLocked()
                || realized != expected.realizedPnlCents()
                || actualReserve != expected.reserveCents()) {
            throw new TradeRejectedException(List.of(
                    "The position changed after the transformation preview. Review its current quantity, basis, and collateral before applying it."));
        }
    }

    private record ExecutableClose(long cashCents, long feesCents) {}

    private static Long entrySnapshotLong(TradeRecord t, String key) {
        if (t == null || t.entrySnapshotJson() == null || t.entrySnapshotJson().isBlank()) return null;
        try {
            Map<String, Object> snapshot = Json.read(t.entrySnapshotJson(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            Object value = snapshot.get(key);
            return value instanceof Number n ? n.longValue() : null;
        } catch (RuntimeException e) {
            throw new IllegalStateException("Trade " + t.id() + " has an invalid current entry receipt.", e);
        }
    }

    /** Commission: per-contract fee on option legs only, plus a flat per-order fee. */
    private long feesFor(List<Leg> legs, int qty) {
        long contracts = legs.stream().filter(l -> !l.isStock()).mapToLong(l -> (long) l.ratio() * qty).sum();
        return contracts * cfg.feePerContractCents() + cfg.feePerOrderCents();
    }

    /** Sign of the cash flow when CLOSING a leg: long legs are sold (+), short legs bought back (-). */
    private static int closeSign(Leg leg) {
        return leg.action() == LegAction.BUY ? 1 : -1;
    }

    static long outstandingReserve(Connection c, String tradeId) throws SQLException {
        return Db.queryOn(c, "SELECT COALESCE(SUM(amount_cents),0) AS n FROM ledger WHERE trade_id=? AND type IN ('RESERVE_HOLD','RESERVE_RELEASE')",
                r -> r.lng("n"), tradeId).getFirst();
    }

    /** The lane's effective clock: inside a simulated world, the WORLD's sim instant — every
     *  gate, warning, DTE and analytic for a world trade must run on the clock that priced it. */
    private java.time.Instant nowFor(String worldId) {
        return marks.simNow(worldId).orElseGet(clock::instant);
    }

    private static Freshness freshnessOf(io.liftandshift.strikebench.model.DataEvidence evidence) {
        if (evidence == null || evidence.provenance() == null) return Freshness.MISSING;
        return switch (evidence.provenance()) {
            case DEMO -> Freshness.FIXTURE;
            case SIMULATED -> Freshness.SIMULATED;
            case MODELED -> Freshness.MODELED;
            case MISSING, MIXED -> Freshness.MISSING;
            case OBSERVED, BROKER -> switch (evidence.age() == null
                    ? io.liftandshift.strikebench.model.DataAge.MISSING : evidence.age()) {
                case REALTIME -> Freshness.REALTIME;
                case DELAYED -> Freshness.DELAYED;
                case EOD -> Freshness.EOD;
                case STALE -> Freshness.STALE;
                case NOT_APPLICABLE, MISSING -> Freshness.MISSING;
            };
        };
    }

    private LocalDate todayFor(String worldId) {
        return LocalDate.ofInstant(nowFor(worldId), io.liftandshift.strikebench.market.MarketHours.EASTERN);
    }

    private double yearsToNearestExpiry(List<Leg> legs) {
        return io.liftandshift.strikebench.market.OptionTime.nearest(legs, LocalDate.now(clock)).years();
    }

    private TradeRecord requireStatusForUpdate(Connection c, String tradeId, String expected) throws SQLException {
        List<TradeRecord> rows = Db.queryOn(c, "SELECT * FROM trades WHERE id=? FOR UPDATE", TradeService::mapTrade, tradeId);
        if (rows.isEmpty()) throw new io.liftandshift.strikebench.util.ResourceNotFoundException("no such trade " + tradeId);
        TradeRecord t = rows.getFirst();
        if (!expected.equals(t.status())) {
            throw new IllegalStateException("trade " + tradeId + " is " + t.status() + ", expected " + expected);
        }
        return t;
    }

    private record LockedTrade(TradeRecord trade, Account account) {}

    /** Lock order is account, then trade, everywhere; reset and position mutations use the same order. */
    private LockedTrade lockTradeAndAccount(Connection c, String tradeId, String expected) throws SQLException {
        TradeRecord initial = getOn(c, tradeId);
        Account account = AccountService.getForUpdate(c, initial.accountId());
        TradeRecord locked = requireStatusForUpdate(c, tradeId, expected);
        if (!account.id().equals(locked.accountId())) {
            throw new IllegalStateException("trade account changed while locking " + tradeId);
        }
        return new LockedTrade(locked, account);
    }

    private static void requireConfirm(boolean confirm, String action) {
        if (!confirm) throw new IllegalArgumentException(action + " requires confirm=true");
    }

    private static void ledgerRow(Connection c, String accountId, String tradeId, String ts, String type,
                                  long amount, long cashAfter, long reservedAfter, String memo) throws SQLException {
        Db.execOn(c, "INSERT INTO ledger(account_id,trade_id,ts,type,amount_cents,cash_after_cents,reserved_after_cents,memo) VALUES (?,?,?,?,?,?,?,?)",
                accountId, tradeId, ts, type, amount, cashAfter, reservedAfter, memo);
    }

    static TradeRecord getOn(Connection c, String tradeId) throws SQLException {
        List<TradeRecord> rows = Db.queryOn(c, "SELECT * FROM trades WHERE id=?", TradeService::mapTrade, tradeId);
        if (rows.isEmpty()) throw new io.liftandshift.strikebench.util.ResourceNotFoundException("no such trade " + tradeId);
        return rows.getFirst();
    }

    static TradeRecord mapTrade(Db.Row r) {
        return new TradeRecord(r.str("id"), r.str("account_id"), r.str("symbol"), r.str("strategy"), r.str("status"),
                r.intv("qty"), TradeRecord.legsFromJson(r.str("legs_json")), r.str("thesis"), r.str("horizon"), r.str("risk_mode"),
                r.lng("entry_underlying_cents"), r.lng("entry_net_premium_cents"), r.lng("max_loss_cents"),
                r.lngOrNull("max_profit_cents"), TradeRecord.breakevensFromJson(r.str("breakevens_json")),
                r.dblOrNull("pop_entry"), r.lng("fees_open_cents"), r.lng("fees_close_cents"),
                r.lngOrNull("realized_pnl_cents"), r.lngOrNull("decision_pnl_cents"),
                r.str("close_reason"), r.str("entry_snapshot_json"),
                r.bool("is_live"), r.str("created_at"), r.str("closed_at"), r.str("updated_at"),
                r.str("intent"), r.lng("shares_locked"), r.lngOrNull("proposed_net_cents"),
                r.str("data_provenance"), r.str("data_age"), r.str("data_source"));
    }

    static String legDesc(Leg leg) {
        if (leg.isStock()) {
            return leg.action() + " " + Math.multiplyExact(leg.ratio(), leg.multiplier()) + " shares";
        }
        String adjusted = leg.multiplier() == Leg.SHARES_PER_CONTRACT
                ? "" : " (" + leg.multiplier() + " units/contract)";
        return leg.action() + " " + leg.ratio() + "x " + leg.strike().toPlainString() + " "
                + leg.type() + " " + leg.expiration() + adjusted;
    }

    private static Freshness worse(Freshness a, Freshness b) {
        return Freshness.worse(a, b);
    }

    /** The money already moved and committed — a failed audit write must not fail the request. */
    private void auditSafe(String accountId, String tradeId, String action, String level, Map<String, Object> detail) {
        try {
            audit.log(accountId, tradeId, action, level, detail);
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(TradeService.class)
                    .warn("A completed paper-trade action could not be added to the activity record: {} {}", action, tradeId);
            org.slf4j.LoggerFactory.getLogger(TradeService.class).debug("Paper-trade audit detail for " + action + " " + tradeId, e);
        }
    }

    private String now() {
        return Instant.now(clock).toString();
    }
}
