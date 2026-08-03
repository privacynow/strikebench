package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.model.BroadBasedIndexOptions;
import io.liftandshift.strikebench.model.DataAge;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.DataProvenance;
import io.liftandshift.strikebench.model.GreeksView;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.model.Symbol;
import io.liftandshift.strikebench.position.PositionDomain;
import io.liftandshift.strikebench.position.PositionPackage;
import io.liftandshift.strikebench.position.PositionTransformation;
import io.liftandshift.strikebench.pricing.PayoffCurve;
import io.liftandshift.strikebench.recommend.LegView;
import io.liftandshift.strikebench.sim.SimulationEngine.MarketImpliedRange;
import io.liftandshift.strikebench.util.Fees;
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
    /**
     * The named mechanical management policy the ticket renders (§7.5). It is a SELECTION, never
     * universal financial truth: configurable per deployment, defaulting to the shipped
     * STANDARD_V1. {@link ProtocolEvaluator} owns the numbers; this class only names one.
     */
    private volatile ProtocolEvaluator.Policy managementPolicy = ProtocolEvaluator.Policy.standard();

    public TradeService(Db db, AppConfig cfg, MarksSource marks, AuditLog audit, Clock clock) {
        this.db = db;
        this.cfg = cfg;
        this.marks = marks;
        this.audit = audit;
        this.clock = clock;
    }

    /** Selects the named management policy every ticket preview renders. */
    public void setManagementPolicy(ProtocolEvaluator.Policy policy) {
        if (policy != null) this.managementPolicy = policy;
    }

    /** The named management policy in force — surfaces render it, they never restate it. */
    public ProtocolEvaluator.Policy managementPolicy() {
        return managementPolicy;
    }

    /**
     * THE normalized OrderPackage: every entry path — recommendations, builder, guided ticket,
     * broker integration and tracked-book imports — produces exactly this typed package, and the
     * one evaluation pipeline consumes it. Package-level extras: {@code orderInstruction} carries
     * MARKET/LIMIT and its signed package limit (+ credit received / − debit paid);
     * {@code feesOverrideCents}
     * (null = platform default; a Practice ticket treats this as the fee per side),
     * {@code source} (RECOMMENDATION | BUILDER | TICKET |
     * IMPORT | BROKER).
     */
    public record OpenRequest(String accountId, String symbol, String strategy, int qty, List<Leg> legs,
                              String thesis, String horizon, String riskMode,
                              String intent, Boolean useHeldShares,
                              Long feesOverrideCents, String source,
                              String fillNature, OrderInstruction orderInstruction,
                              io.liftandshift.strikebench.recommend.HoldingsEvidence.Provenance
                                      holdingsProvenance) {
        /** Jackson and every internal caller bind one complete position request. */
        @com.fasterxml.jackson.annotation.JsonCreator
        public OpenRequest {
            symbol = Symbol.normalize(symbol);
            if (feesOverrideCents != null && feesOverrideCents < 0) {
                throw new IllegalArgumentException("feesOverrideCents cannot be negative");
            }
            boolean recordedFill = "EXECUTED".equalsIgnoreCase(fillNature);
            if (recordedFill && orderInstruction != null) {
                throw new IllegalArgumentException(
                        "recorded fills are exact package-price evidence, not order instructions");
            }
            if (!recordedFill && orderInstruction != null) {
                PackageLimitTickPolicy.requirePackageLimit(orderInstruction, legs, qty);
            }
            if (!Boolean.TRUE.equals(useHeldShares) && holdingsProvenance != null) {
                throw new IllegalArgumentException(
                        "holdings provenance applies only when useHeldShares is true");
            }
            if (Boolean.TRUE.equals(useHeldShares) && holdingsProvenance == null) {
                throw new IllegalArgumentException(
                        "useHeldShares requires explicit destination-account holdings provenance");
            }
        }
        public boolean heldShares() { return Boolean.TRUE.equals(useHeldShares); }
        public boolean explicitFillMeaning() {
            return "PROPOSED".equalsIgnoreCase(fillNature) || "EXECUTED".equalsIgnoreCase(fillNature);
        }
        public boolean executedFill() {
            return "EXECUTED".equalsIgnoreCase(fillNature);
        }
    }

    /**
     * §5.3: one leg's contribution to a position's greeks, plus the per-share mark that produced it.
     *
     * <p>{@code greeks} is THE normalized greeks view — the same record, the same field names and the
     * same units a position, an idea candidate and the scenario canvas publish — already scaled by
     * sign x deliverable x ratio x quantity, so no surface multiplies anything. The per-share values
     * carry their unit in the name and are the raw quote, never a position figure. A mark component
     * the provider did not supply is an ABSENT field, never a 0 (§3.2).
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    /**
     * Per-leg market evidence plus that leg's contribution in the same normalized Greek units as
     * the package. Raw provider Greeks are inputs to {@link GreeksAggregator}, not a second public
     * shape with different scale and money units.
     */
    public record LegGreekRow(String leg, String bid, String ask, Double iv, GreeksView greeks) {}

    /**
     * Availability of the four independent current-market facts on a held package. Entry facts
     * stay on TradeRecord; a missing current quote, executable close, POP input, or Greek input is
     * named here and is never replaced by an entry value or numeric zero.
     */
    public record CurrentMarketAvailability(
            boolean quoteAvailable, String quoteUnavailableReason,
            boolean closeAvailable, String closeUnavailableReason,
            boolean decisionPnlAvailable, String decisionPnlUnavailableReason,
            boolean popAvailable, String popUnavailableReason,
            boolean greeksAvailable, String greeksUnavailableReason) {
        public CurrentMarketAvailability {
            verifyAvailability("quote", quoteAvailable, quoteUnavailableReason);
            verifyAvailability("close", closeAvailable, closeUnavailableReason);
            verifyAvailability("position P/L", decisionPnlAvailable, decisionPnlUnavailableReason);
            verifyAvailability("probability of profit", popAvailable, popUnavailableReason);
            verifyAvailability("Greeks", greeksAvailable, greeksUnavailableReason);
        }

        private static void verifyAvailability(String fact, boolean available, String reason) {
            boolean reasonPresent = reason != null && !reason.isBlank();
            if (available == reasonPresent) {
                throw new IllegalArgumentException("Current " + fact
                        + " must carry exactly one of a value or an unavailability reason.");
            }
        }

        /**
         * A current-mark operation failed before any component result could be produced. Keep that
         * failure typed on every component instead of leaving null values whose meaning a surface
         * would have to guess.
         */
        public static CurrentMarketAvailability unavailable(String reason) {
            String named = reason == null || reason.isBlank()
                    ? "The current market result for this position could not be produced."
                    : reason;
            return new CurrentMarketAvailability(
                    false, named, false, named, false, named, false, named, false, named);
        }

        /**
         * Preserve an independently obtained underlying quote when package marking failed. Quote
         * availability is a separate fact; it must not inherit the option-package failure.
         */
        public CurrentMarketAvailability withQuote(boolean available, String reason) {
            return new CurrentMarketAvailability(
                    available,
                    available ? null : (reason == null || reason.isBlank()
                            ? "No current underlying quote is available." : reason),
                    closeAvailable, closeUnavailableReason,
                    decisionPnlAvailable, decisionPnlUnavailableReason,
                    popAvailable, popUnavailableReason,
                    greeksAvailable, greeksUnavailableReason);
        }
    }

    /** Dollar-delta exposure for a mode-aware before/after assessment. */
    public record DollarDeltaExposure(long grossCents, long netCents, long focusSymbolGrossCents,
                                      boolean complete, String basis) {
        /** THE mapping to the evaluation layer's exposure context (adds the book type). */
        public io.liftandshift.strikebench.eval.PortfolioExposureContext toContext(
                io.liftandshift.strikebench.position.PositionDomain.BookType mode) {
            return new io.liftandshift.strikebench.eval.PortfolioExposureContext(
                    mode, grossCents, netCents, focusSymbolGrossCents, complete, basis);
        }
    }

    /**
     * One pass over the Practice book, retaining every symbol subtotal for cross-symbol Scout and
     * every per-trade signed delta, so any subset (a box-selected pool, one position) can be added
     * up in the ONE unit that is additive across underlyings without re-deriving the math.
     */
    public record DollarDeltaBook(long grossCents, long netCents,
                                  Map<String, Long> symbolGrossCents, Map<String, Long> tradeNetCents,
                                  boolean complete, String basis) {
        public DollarDeltaBook {
            symbolGrossCents = symbolGrossCents == null ? Map.of() : Map.copyOf(symbolGrossCents);
            tradeNetCents = tradeNetCents == null ? Map.of() : Map.copyOf(tradeNetCents);
        }
        public DollarDeltaExposure focus(String symbol) {
            String normalized = Symbol.normalizeOptional(symbol);
            return new DollarDeltaExposure(grossCents, netCents,
                    normalized == null ? 0L : symbolGrossCents.getOrDefault(normalized, 0L),
                    complete, basis);
        }
    }

    /**
     * One mark snapshot of a held package.
     *
     * <p>§5.3: {@code greeks} is THE normalized greeks view (deltaShares, gammaSharesPerDollar,
     * thetaCentsPerDay, vegaCentsPerPoint). There is no second greeks shape anywhere in this
     * codebase or on the wire — every surface reads these four names in these units. It is null,
     * never a partial sum, when any option leg's mark lacks greeks, so an exposure figure that
     * silently omits a leg can no longer be published (§3.2).
     */
    public record MarkView(String schemaVersion, String fingerprint,
                           String tradeId, String ts, Long underlyingCents,
                           Long unrealizedCents, Long decisionUnrealizedCents,
                           PackagePrice currentClosePrice,
                           Long indicativeUnrealizedCents,
                           Long indicativeDecisionUnrealizedCents,
                           Double popNow, String freshness,
                           GreeksView greeks,
                           List<LegGreekRow> legGreeks,
                           CurrentMarketAvailability availability,
                           io.liftandshift.strikebench.model.Quote underlyingQuote,
                           io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.RiskNeutralAnalysis
                                   marketImpliedRisk) {
        public static final String SCHEMA_VERSION = "current-position-mark-v1";

        public MarkView {
            if (!SCHEMA_VERSION.equals(schemaVersion)) {
                throw new IllegalArgumentException("unsupported current-position mark schema");
            }
            if (currentClosePrice == null) {
                throw new IllegalArgumentException(
                        "current-position mark requires its closing-price result");
            }
            if (!currentClosePrice.priced()
                    && (indicativeUnrealizedCents != null
                        || indicativeDecisionUnrealizedCents != null)) {
                throw new IllegalArgumentException(
                        "an unavailable current closing-price result cannot carry indicative P/L");
            }
            if (marketImpliedRisk == null) {
                throw new IllegalArgumentException(
                        "current-position mark requires its market-implied result");
            }
            if (marketImpliedRisk.available()
                    && !java.util.Objects.equals(popNow, marketImpliedRisk.pop())) {
                throw new IllegalArgumentException(
                        "held-position POP does not match its market-implied result");
            }
            String expected = markFingerprint(tradeId, ts, underlyingCents, unrealizedCents,
                    decisionUnrealizedCents, currentClosePrice, indicativeUnrealizedCents,
                    indicativeDecisionUnrealizedCents, popNow, freshness, greeks, legGreeks,
                    availability, underlyingQuote, marketImpliedRisk);
            if (fingerprint == null || fingerprint.isBlank()) {
                throw new IllegalArgumentException(
                        "current-position mark requires its fingerprint");
            }
            if (!expected.equals(fingerprint)) {
                throw new IllegalArgumentException(
                        "current-position mark fingerprint does not match its facts");
            }
        }

        public static MarkView create(
                String tradeId, String ts, Long underlyingCents,
                Long unrealizedCents, Long decisionUnrealizedCents,
                PackagePrice currentClosePrice, Long indicativeUnrealizedCents,
                Long indicativeDecisionUnrealizedCents, Double popNow, String freshness,
                GreeksView greeks, List<LegGreekRow> legGreeks,
                CurrentMarketAvailability availability,
                io.liftandshift.strikebench.model.Quote underlyingQuote,
                io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.RiskNeutralAnalysis
                        marketImpliedRisk) {
            return new MarkView(SCHEMA_VERSION,
                    markFingerprint(tradeId, ts, underlyingCents, unrealizedCents,
                            decisionUnrealizedCents, currentClosePrice,
                            indicativeUnrealizedCents, indicativeDecisionUnrealizedCents,
                            popNow, freshness, greeks, legGreeks, availability,
                            underlyingQuote, marketImpliedRisk),
                    tradeId, ts, underlyingCents, unrealizedCents, decisionUnrealizedCents,
                    currentClosePrice, indicativeUnrealizedCents,
                    indicativeDecisionUnrealizedCents, popNow, freshness, greeks, legGreeks,
                    availability, underlyingQuote, marketImpliedRisk);
        }

        private static String markFingerprint(
                String tradeId, String ts, Long underlyingCents, Long unrealizedCents,
                Long decisionUnrealizedCents, PackagePrice currentClosePrice,
                Long indicativeUnrealizedCents, Long indicativeDecisionUnrealizedCents,
                Double popNow, String freshness, GreeksView greeks,
                List<LegGreekRow> legGreeks, CurrentMarketAvailability availability,
                io.liftandshift.strikebench.model.Quote underlyingQuote,
                io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.RiskNeutralAnalysis
                        marketImpliedRisk) {
            try {
                Map<String, Object> stable = new LinkedHashMap<>();
                stable.put("schemaVersion", SCHEMA_VERSION);
                stable.put("tradeId", tradeId);
                stable.put("ts", ts);
                stable.put("underlyingCents", underlyingCents);
                stable.put("unrealizedCents", unrealizedCents);
                stable.put("decisionUnrealizedCents", decisionUnrealizedCents);
                stable.put("currentClosePrice", currentClosePrice);
                stable.put("indicativeUnrealizedCents", indicativeUnrealizedCents);
                stable.put("indicativeDecisionUnrealizedCents",
                        indicativeDecisionUnrealizedCents);
                stable.put("popNow", popNow);
                stable.put("freshness", freshness);
                stable.put("greeks", greeks);
                stable.put("legGreeks", legGreeks == null ? List.of() : List.copyOf(legGreeks));
                stable.put("availability", availability);
                stable.put("underlyingQuote", underlyingQuote);
                stable.put("marketImpliedRisk", marketImpliedRisk);
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                        Json.stable(stable).getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                throw new IllegalStateException(
                        "cannot fingerprint current-position mark", e);
            }
        }
    }

    /** Worst and best executable-mark excursions recorded while a trade was open. */
    public record Excursion(Long adverseCents, Long favorableCents) {}

    public record Page(List<TradeRecord> trades, long total, int page, int size) {}

    public record CloseResult(TradeRecord trade, long realizedPnlCents,
                              long actionRealizedPnlCents) {}

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
                                       long reserveBeforeCents, Long reserveAfterCents,
                                       long sharesLockedAfter,
                                       long projectedCashAfterCents, Long projectedReservedAfterCents,
                                       long placementCashBeforeCents, long placementReservedBeforeCents,
                                       List<String> basisNotes) {}

    public record AdjustmentResult(TradeRecord trade, long actionRealizedPnlCents,
                                   long realizedPnlCents) {}

    /**
     * One option lifecycle event projected against the account's own mode clock. The option
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
                                      Long reserveAfterCents, long heldShareContextAfter,
                                      long sharesLockedAfter, long projectedCashAfterCents,
                                      Long projectedReservedAfterCents, List<String> basisNotes,
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

    /** Complete inputs for one Practice position analysis; balances are never loaded implicitly. */
    public record PositionAnalysisRequest(
            String packageId,
            PositionDomain.PackageSource source,
            PositionDomain.BookType bookType,
            OpenRequest position,
            long cashBeforeCents,
            long reservedBeforeCents,
            long releasedShares) {}

    /** Executable close cash and realized result paired with the same fresh-eyes position assessment. */
    public record UnwindAssessment(PositionAssessment current, long closingCashCents,
                                   long closingFeesCents, long actionRealizedPnlCents,
                                   long realizedPnlToDateCents) {}

    /** Optional owner hook for workflows that must commit their identity beside the paper trade. */
    @FunctionalInterface
    public interface TransactionHook {
        void afterTradeCreated(Connection connection, TradeRecord trade,
                               TradePreview executionPreview) throws SQLException;
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

    /**
     * Prices one package against a projected Practice balance. A package without an order
     * instruction is analysis-only; adding an explicit MARKET or signed LIMIT instruction also
     * evaluates execution readiness. Transformations use this after valuing the close so the
     * replacement is judged with the cash, reserve, and held shares the atomic close releases.
     */
    public TradePreview preview(OpenRequest req, long cashBeforeCents, long reservedBeforeCents,
                                long releasedShares) {
        // Import/broker previews describe an actual fill that already happened. Ordinary ticket
        // previews describe a paper order and therefore cannot claim a favorable resting limit filled.
        boolean analysisOnly = !req.executedFill() && req.orderInstruction() == null;
        Plan p = computePlan(req, req.executedFill(), null, false, null, analysisOnly);
        return previewFromPlan(req, p, cashBeforeCents, reservedBeforeCents, releasedShares);
    }

    private TradePreview previewFromPlan(OpenRequest req, Plan p, long cashBeforeCents,
                                         long reservedBeforeCents, long releasedShares) {
        return db.with(c -> previewFromPlanOn(c, req, p, cashBeforeCents,
                reservedBeforeCents, releasedShares));
    }

    /** Builds the normalized preview from an already-priced Plan on the caller's transaction. */
    private TradePreview previewFromPlanOn(Connection c, OpenRequest req, Plan p,
                                           long cashBeforeCents, long reservedBeforeCents,
                                           long releasedShares) throws SQLException {
        if (cashBeforeCents < 0 || reservedBeforeCents < 0 || releasedShares < 0) {
            throw new IllegalArgumentException("projected Practice balances cannot be negative");
        }
        long buyingPowerBefore = Math.subtractExact(cashBeforeCents, reservedBeforeCents);
        long cashAfter = Math.subtractExact(Math.addExact(cashBeforeCents, p.entryNet), p.fees);
        List<String> blocks = new ArrayList<>(p.blocks);
        long planReserve = p.blocks.isEmpty() ? requiredRiskFact(p.reserve, "reserve") : 0L;
        long planMaxLoss = p.blocks.isEmpty() ? requiredRiskFact(p.maxLoss, "maximum loss") : 0L;
        long reservedAfter = p.blocks.isEmpty()
                ? Math.addExact(reservedBeforeCents, planReserve) : reservedBeforeCents;
        if (p.blocks.isEmpty() && req.heldShares()) {
            long needed = Math.max(p.sharesToLock(), Math.multiplyExact(heldShareUnitsPerPackage(req.legs()), req.qty()));
            long free = Math.addExact(availableCoverShares(c, req.accountId(), req.symbol()), releasedShares);
            if (free < needed) {
                blocks.add("Needs " + needed + " free shares of " + req.symbol()
                        + " but only " + Math.max(0, free)
                        + " are free in the destination Practice account");
            }
        }
        if (p.blocks.isEmpty() && blocks.isEmpty() && cashAfter - reservedAfter < 0) {
            blocks.add("Insufficient buying power: needs " + Money.fmt(planMaxLoss + p.fees)
                    + " but only " + Money.fmt(buyingPowerBefore) + " is available");
        }
        return new TradePreview(blocks.isEmpty(), blocks, p.warnings,
                p.maxLoss, p.maxProfit, p.breakevens, p.reserve,
                cashBeforeCents, blocks.isEmpty() ? cashAfter : cashBeforeCents,
                reservedBeforeCents, blocks.isEmpty() ? reservedAfter : reservedBeforeCents,
                buyingPowerBefore, blocks.isEmpty() ? cashAfter - reservedAfter : buyingPowerBefore,
                p.evidence.label(), entryEvidence(req.accountId(), p.evidence), p.underlyingCents,
                p.shortSideExpirationItmProb(), p.legDetails(), p.payoff(), p.analytics(), p.price(),
                p.marketImpliedRange(), p.marketImpliedRisk());
    }

    /**
     * Cover availability belongs to the ONE destination account. Shares in another tracked or
     * retirement account may be analyzed in that Book mode, but cannot collateralize a Practice
     * order.
     */
    private static long availableCoverShares(Connection c, String accountId, String symbol)
            throws SQLException {
        return PositionsService.heldShares(c, accountId, symbol)
                - PositionsService.lockedShares(c, accountId, symbol);
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
        List<String> blocks = new ArrayList<>(p.blocks);
        long planReserve = p.blocks.isEmpty() ? requiredRiskFact(p.reserve, "reserve") : 0L;
        long planMaxLoss = p.blocks.isEmpty() ? requiredRiskFact(p.maxLoss, "maximum loss") : 0L;
        long reservedAfter = p.blocks.isEmpty()
                ? Math.addExact(acct.reservedCents(), planReserve) : acct.reservedCents();
        if (p.blocks.isEmpty() && req.heldShares()) {
            long needed = Math.max(p.sharesToLock(), Math.multiplyExact(heldShareUnitsPerPackage(req.legs()), req.qty()));
            long free = db.with(c -> availableCoverShares(c, req.accountId(), req.symbol()));
            if (free < needed) {
                blocks.add("Needs " + needed + " free shares of " + req.symbol()
                        + " but only " + Math.max(0, free)
                        + " are free in the destination Practice account");
            }
        }
        if (p.blocks.isEmpty() && blocks.isEmpty() && cashAfter - reservedAfter < 0) {
            blocks.add("This Practice account has " + Money.fmt(acct.buyingPowerCents())
                    + " buying power; the analyzed package needs " + Money.fmt(planMaxLoss + p.fees)
                    + ". Analysis remains visible, but the account cannot fund it as entered.");
        }
        return new TradePreview(blocks.isEmpty(), blocks, p.warnings,
                p.maxLoss, p.maxProfit, p.breakevens, p.reserve,
                acct.cashCents(), blocks.isEmpty() ? cashAfter : acct.cashCents(),
                acct.reservedCents(), blocks.isEmpty() ? reservedAfter : acct.reservedCents(),
                acct.buyingPowerCents(), blocks.isEmpty() ? cashAfter - reservedAfter : acct.buyingPowerCents(),
                p.evidence.label(), entryEvidence(req.accountId(), p.evidence), p.underlyingCents,
                p.shortSideExpirationItmProb(), p.legDetails(), p.payoff(), p.analytics(), p.price(),
                p.marketImpliedRange(), p.marketImpliedRisk());
    }

    /**
     * Reprices an existing Practice position as one fresh-eyes package without pretending it is a
     * second order or charging its full reserve against the account again. All fills, risk, POP,
     * and evidence still come from the single {@code computePlan} path.
     */
    public PositionAssessment analyzeActivePosition(String tradeId) {
        TradeRecord trade = get(tradeId);
        if (!TradeRecord.ACTIVE.equals(trade.status())) {
            throw new IllegalStateException("trade is " + trade.status() + "; only ACTIVE trades can be transformed");
        }
        OpenRequest request = activePositionRequest(trade, trade.qty(), trade.sharesLocked());
        Account account = db.with(c -> AccountService.get(c, request.accountId()));
        PositionAssessment assessed = analyzePositionPackage(new PositionAnalysisRequest(
                trade.id(), PositionDomain.PackageSource.PRACTICE_TRADE,
                PositionDomain.BookType.PRACTICE, request, account.cashCents(),
                account.reservedCents(), 0));
        long outstandingReserve = db.with(c -> Ledger.outstandingReserve(c, trade.id()));
        PositionTransformation.RiskSnapshot bookRisk = new PositionTransformation.RiskSnapshot(
                trade.maxLossCents(), outstandingReserve, trade.maxProfitCents(), true, List.of(),
                assessed.risk().evidenceBasis());
        return new PositionAssessment(assessed.position(), assessed.preview(), bookRisk);
    }

    /**
     * The exact current package request used by every Practice fresh-eyes and transformation read.
     * Lifecycle composition consumes this request instead of rebuilding persisted trade geometry.
     */
    public OpenRequest activePositionRequest(String tradeId) {
        TradeRecord trade = get(tradeId);
        if (!TradeRecord.ACTIVE.equals(trade.status())) {
            throw new IllegalStateException("trade is " + trade.status()
                    + "; only ACTIVE trades have a current position request");
        }
        return activePositionRequest(trade, trade.qty(), trade.sharesLocked());
    }

    /** Analyzes one exact package through the shared Practice pricing path. */
    public PositionAssessment analyzePositionPackage(PositionAnalysisRequest input) {
        java.util.Objects.requireNonNull(input, "position analysis input");
        String packageId = input.packageId();
        PositionDomain.PackageSource source = input.source();
        PositionDomain.BookType mode = input.bookType();
        OpenRequest request = input.position();
        if (mode != PositionDomain.BookType.PRACTICE) {
            throw new IllegalArgumentException("TradeService position analysis owns the Practice mode only");
        }
        if (packageId == null || packageId.isBlank() || source == null || request == null) {
            throw new IllegalArgumentException("complete position-package identity is required");
        }
        // A held-position assessment is analysis, never placement approval. Preserve complete
        // labeled quote rows and risk geometry when mode-owned evidence is stale/EOD, while the
        // normalized package-price result still withholds executableNetCents. This prevents a
        // non-executable quote from erasing the exact contract identity needed to explain why
        // lifecycle advice is unavailable.
        Plan plan = computePlan(request, false, null, false, null, true);
        TradePreview preview = previewFromPlan(request, plan, input.cashBeforeCents(),
                input.reservedBeforeCents(), input.releasedShares());
        return assessmentFromPlan(packageId, source, mode, request, plan, preview);
    }

    private PositionAssessment assessmentFromPlan(String packageId, PositionDomain.PackageSource source,
                                                  PositionDomain.BookType mode, OpenRequest request,
                                                  Plan plan, TradePreview preview) {
        List<Leg> assessedLegs = plan.filledLegs().isEmpty() ? request.legs() : plan.filledLegs();
        PositionDomain.PriceAuthority authority = plan.evidence().provenance() == DataProvenance.OBSERVED
                || plan.evidence().provenance() == DataProvenance.BROKER
                ? PositionDomain.PriceAuthority.OBSERVED : PositionDomain.PriceAuthority.MODELED;
        List<PositionPackage.Leg> packageLegs = new ArrayList<>();
        for (int i = 0; i < assessedLegs.size(); i++) {
            Leg leg = assessedLegs.get(i);
            packageLegs.add(new PositionPackage.Leg(i, leg.action().name(), leg.isStock() ? "STOCK" : "OPTION",
                    request.symbol(), leg.isStock() ? null : leg.type().name(), leg.strike(), leg.expiration(),
                    Math.multiplyExact(request.qty(), (long) leg.ratio()), leg.multiplier(), leg.entryPrice(), authority));
        }
        PositionPackage position = new PositionPackage(packageId, source, mode, request.symbol(), request.qty(), plan.entryNet(),
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
        long outstanding = db.with(c -> Ledger.outstandingReserve(c, trade.id()));
        long reserveRelease = allocatedPrefix(outstanding, trade.qty(), closeQuantity);
        long survivingReserve = Math.subtractExact(outstanding, reserveRelease);
        long survivingShares = Math.subtractExact(trade.sharesLocked(),
                allocatedPrefix(trade.sharesLocked(), trade.qty(), closeQuantity));
        Account account = db.with(c -> AccountService.get(c, trade.accountId()));
        long projectedCash = Math.subtractExact(Math.addExact(account.cashCents(), close.cashCents()),
                close.feesCents());
        long projectedReservedWithoutPosition = Math.subtractExact(account.reservedCents(), outstanding);
        OpenRequest survivorRequest = activePositionRequest(trade, survivingQuantity, survivingShares);
        PositionAssessment survivor = analyzePositionPackage(new PositionAnalysisRequest(trade.id(),
                PositionDomain.PackageSource.PRACTICE_TRADE, PositionDomain.BookType.PRACTICE,
                survivorRequest, projectedCash, projectedReservedWithoutPosition,
                trade.sharesLocked()));
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
        long openingFees = filledAdded.isEmpty() ? 0 : feesFor(normalizedLegs(filledAdded), normalizedQuantity(filledAdded));
        long closingFees = reconciled.removed().isEmpty() ? 0 : closeFeesForLots(trade, reconciled.removed());
        long closingCash = reconciled.removed().isEmpty() ? 0 : executableCloseLots(trade, reconciled.removed());
        long openingCash = cashForLots(filledAdded);
        long actionRealized = Math.subtractExact(Math.addExact(
                Math.subtractExact(allocatedEntryBasis, allocatedOpenFees), closingCash), closingFees);
        long realizedToDate = Math.addExact(trade.realizedPnlCents() == null ? 0 : trade.realizedPnlCents(),
                actionRealized);

        int exactQuantity = normalizedQuantity(exactLots);
        List<Leg> exactLegs = normalizedLegs(exactLots);
        long exactEntry = Math.addExact(cashForLots(exactLots), remainingPackageAdjustment);
        long exactOpenFees = Math.addExact(Math.subtractExact(trade.feesOpenCents(), allocatedOpenFees), openingFees);
        OpenRequest exactAfter = new OpenRequest(trade.accountId(), trade.symbol(), desiredAfter.strategy(),
                exactQuantity, exactLegs, trade.thesis(), trade.horizon(), trade.riskMode(), trade.intent(),
                trade.sharesLocked() > 0, exactOpenFees,
                "POSITION_TRANSFORMATION", "EXECUTED", null,
                trade.sharesLocked() > 0
                        ? io.liftandshift.strikebench.recommend.HoldingsEvidence.Provenance.ACCOUNT_BACKED
                        : null);

        String world = worldOf(trade.accountId());
        Plan exactPlan = computePlan(exactAfter, true, world, true, marketModeFor(world), true);
        Account account = db.with(c -> AccountService.get(c, trade.accountId()));
        long reserveBefore = db.with(c -> Ledger.outstandingReserve(c, trade.id()));
        long projectedCash = Math.subtractExact(Math.addExact(
                Math.addExact(account.cashCents(), closingCash), openingCash),
                Math.addExact(closingFees, openingFees));
        long placementReservedBefore = Math.subtractExact(account.reservedCents(), reserveBefore);
        Long projectedReserved = exactPlan.reserve() == null ? null
                : Math.addExact(placementReservedBefore, exactPlan.reserve());
        long placementCashBefore = Math.addExact(Math.subtractExact(projectedCash, exactPlan.entryNet()),
                exactPlan.fees());
        TradePreview exactPreview = previewFromPlan(exactAfter, exactPlan, placementCashBefore,
                placementReservedBefore, trade.sharesLocked());
        PositionAssessment survivor = assessmentFromPlan(trade.id(), PositionDomain.PackageSource.PRACTICE_TRADE,
                PositionDomain.BookType.PRACTICE, exactAfter, exactPlan, exactPreview);

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

    /** Projects one assignment, exercise, or expiry through the same mode clock and pricing spine as settlement. */
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
        Instant marketNow = nowFor(world);
        SettlementReference reference = settlementReference(trade, selected, action, world, marketNow);
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
                    + " has no intrinsic value at the mode's current price; physical conversion would be uneconomic."));
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
        long reserveBefore = Ledger.outstandingReserve(c, trade.id());
        long projectedCash = Math.addExact(Math.addExact(account.cashCents(), optionSettlementCash), stockCash);
        long reservedWithoutCurrent = Math.subtractExact(account.reservedCents(), reserveBefore);
        OpenRequest exactAfter = null;
        Plan exactAfterPlan = null;
        Long reserveAfter = 0L;
        long sharesLockedAfter = 0;
        if (!retained.isEmpty()) {
            int quantity = normalizedQuantity(retained);
            String strategyAfter = lifecycleStrategy(trade.symbol(), retained, heldContextAfter,
                    projectedHolding.afterBasisCents());
            String intentAfter = lifecycleIntent(strategyAfter, trade.intent());
            exactAfter = new OpenRequest(trade.accountId(), trade.symbol(), strategyAfter, quantity,
                    normalizedLegs(retained), trade.thesis(), trade.horizon(), trade.riskMode(), intentAfter,
                    heldContextAfter > 0, remainingOpenFees,
                    "POSITION_TRANSFORMATION", "EXECUTED", null,
                    heldContextAfter > 0
                            ? io.liftandshift.strikebench.recommend.HoldingsEvidence.Provenance.ACCOUNT_BACKED
                            : null);
            exactAfterPlan = computePlan(exactAfter, true, world, true, marketModeFor(world), true);
            reserveAfter = exactAfterPlan.reserve();
            sharesLockedAfter = exactAfterPlan.sharesToLock();
        }
        Long projectedReserved = reserveAfter == null ? null
                : Math.addExact(reservedWithoutCurrent, reserveAfter);

        PositionAssessment current = lifecycleAssessment(c, trade, allLots, heldContextBefore,
                holding == null ? trade.entryUnderlyingCents() : holding.avgCostCents(),
                packageAdjustment, trade.feesOpenCents(), account.cashCents(), reservedWithoutCurrent,
                trade.maxLossCents(), reserveBefore, world);
        PositionAssessment survivor = null;
        if (!retained.isEmpty() || heldContextAfter > 0) {
            survivor = lifecycleAssessment(c, trade, retained, heldContextAfter,
                    projectedHolding.afterBasisCents(), remainingPackageAdjustment, remainingOpenFees,
                    projectedCash, reservedWithoutCurrent,
                    exactAfterPlan == null ? null : exactAfterPlan.maxLoss(), reserveAfter, world);
            List<String> blocks = new ArrayList<>(survivor.risk().blockReasons());
            if (exactAfterPlan != null) blocks.addAll(exactAfterPlan.blocks());
            if (action == PositionTransformation.Action.EXERCISE && projectedReserved != null
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
                && !io.liftandshift.strikebench.market.MarketHours.contractDead(selected.expiration(), marketNow)) {
            notes.add("Early exercise gives up any remaining extrinsic value. Compare an executable option sale before applying this event.");
        } else if (action == PositionTransformation.Action.ASSIGNMENT
                && !io.liftandshift.strikebench.market.MarketHours.contractDead(selected.expiration(), marketNow)) {
            notes.add("Early assignment is an event you are recording, not a prediction from the current mark.");
        }
        if (!reference.exact()) notes.add(reference.basis());
        if (action != PositionTransformation.Action.EXPIRATION
                && !io.liftandshift.strikebench.market.MarketHours.contractDead(selected.expiration(), marketNow)) {
            notes.add("This is an early " + action.name().toLowerCase(java.util.Locale.ROOT)
                    + " event against the active mode mark, not an expiration forecast.");
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
                                                   long reservedWithoutCurrent, Long exactMaxLoss,
                                                   Long exactReserve,
                                                   String world) throws SQLException {
        List<LegLot> combined = new ArrayList<>(optionLots);
        if (contextShares > 0) {
            combined.add(new LegLot(Leg.stockShares(LegAction.BUY, 1,
                    BigDecimal.valueOf(stockBasisCents, 2)), contextShares));
        }
        if (combined.isEmpty()) throw new IllegalArgumentException("a lifecycle assessment needs a surviving position");
        int quantity = normalizedQuantity(combined);
        List<Leg> legs = normalizedLegs(combined);
        OpenRequest request = new OpenRequest(trade.accountId(), trade.symbol(), trade.strategy(), quantity,
                legs, trade.thesis(), trade.horizon(), trade.riskMode(), trade.intent(), false,
                optionFees, "POSITION_TRANSFORMATION", "EXECUTED", null, null);
        Plan plan = computePlan(request, true, world, true, marketModeFor(world), true);
        long placementCash = Math.addExact(Math.subtractExact(projectedCash, plan.entryNet()), plan.fees());
        TradePreview preview = previewFromPlan(request, plan, placementCash, reservedWithoutCurrent, 0);
        PositionAssessment assessment = assessmentFromPlan(trade.id(), PositionDomain.PackageSource.PRACTICE_TRADE,
                PositionDomain.BookType.PRACTICE, request, plan, preview);
        Long reviewedMaxLoss = exactMaxLoss != null ? exactMaxLoss
                : exactReserve != null ? plan.maxLoss() : null;
        var risk = new PositionTransformation.RiskSnapshot(reviewedMaxLoss, exactReserve, plan.maxProfit(),
                plan.blocks().isEmpty(), plan.blocks(), assessment.risk().evidenceBasis());
        return new PositionAssessment(assessment.position(), assessment.preview(), risk);
    }

    private static OpenRequest activePositionRequest(TradeRecord trade, int quantity, long sharesLocked) {
        List<Leg> unpriced = trade.legs().stream().map(leg -> new Leg(leg.action(), leg.type(), leg.strike(),
                leg.expiration(), leg.ratio(), BigDecimal.ZERO, leg.multiplier())).toList();
        return new OpenRequest(trade.accountId(), trade.symbol(), trade.strategy(), quantity,
                unpriced, trade.thesis(), trade.horizon(), trade.riskMode(), trade.intent(),
                sharesLocked > 0, null, "POSITION_TRANSFORMATION", "PROPOSED", null,
                sharesLocked > 0
                        ? io.liftandshift.strikebench.recommend.HoldingsEvidence.Provenance.ACCOUNT_BACKED
                        : null);
    }

    /**
     * Read-only analysis for an owner-scoped tracked account. Production tracked books are valued
     * from the observed mode and use their own cash, never the Practice account or its selected
     * world. A fixture-only build keeps its explicitly labeled DEMO mode so its teaching analysis
     * cannot pretend that built-in prices are observed.
     */
    public TradePreview previewTracked(OpenRequest req, long trackedCashCents) {
        Plan p = computePlan(req, req.executedFill(), "observed", true,
                marketModeFor("observed"), true);
        long cashAfter = trackedCashCents + p.entryNet - p.fees;
        List<String> blocks = new ArrayList<>(p.blocks);
        long planReserve = p.blocks.isEmpty() ? requiredRiskFact(p.reserve, "reserve") : 0L;
        long planMaxLoss = p.blocks.isEmpty() ? requiredRiskFact(p.maxLoss, "maximum loss") : 0L;
        long reservedAfter = planReserve;
        if (p.blocks.isEmpty() && cashAfter - reservedAfter < 0) {
            blocks.add("This tracked account has " + Money.fmt(trackedCashCents)
                    + " cash; the analyzed package needs " + Money.fmt(planMaxLoss + p.fees)
                    + ". Analysis remains visible, but the account cannot fund it as entered.");
        }
        return new TradePreview(blocks.isEmpty(), blocks, p.warnings,
                p.maxLoss, p.maxProfit, p.breakevens, p.reserve,
                trackedCashCents, blocks.isEmpty() ? cashAfter : trackedCashCents,
                0, blocks.isEmpty() ? reservedAfter : 0,
                trackedCashCents, blocks.isEmpty() ? cashAfter - reservedAfter : trackedCashCents,
                p.evidence.label(), trackedAnalysisEvidence(p.evidence), p.underlyingCents,
                p.shortSideExpirationItmProb(), p.legDetails(), p.payoff(), p.analytics(), p.price(),
                p.marketImpliedRange(), p.marketImpliedRisk());
    }

    private static DataEvidence trackedAnalysisEvidence(DataEvidence evidence) {
        DataEvidence resolved = evidence == null ? DataEvidence.missing("tracked-account analysis") : evidence;
        String source = switch (resolved.provenance()) {
            case DEMO -> "built-in demo evidence used for tracked-account analysis only";
            case SIMULATED -> "simulated evidence used for tracked-account analysis only";
            case MODELED -> "modeled evidence used for tracked-account analysis only";
            case OBSERVED, BROKER -> "observed tracked-account analysis";
            case MISSING, MIXED -> "tracked-account analysis evidence unavailable";
        };
        return new DataEvidence(resolved.provenance(), resolved.age(), source);
    }

    /** Opens the trade and an owning workflow record in one database transaction. */
    public TradeRecord create(OpenRequest req, TransactionHook hook) {
        requirePlacementInstruction(req);
        if (req.heldShares() && !io.liftandshift.strikebench.recommend.HoldingsEvidence
                .forProvenance(req.holdingsProvenance(), null, null,
                        req.accountId(), "PRACTICE", null)
                .isAccountBacked()) {
            reject(req, List.of("Held-share placement requires destination-account-backed "
                    + "holdings evidence; this package remains analysis-only."));
        }
        // A proposed Practice order must clear the current executable package. An explicitly
        // recorded broker/import fill is instead valued from its exact leg fills and published as
        // RECORDED_FILL evidence; it is not silently reinterpreted as a MARKET order on create.
        Plan p = computePlan(req, req.executedFill(), null, false, null, false);
        if (!p.blocks.isEmpty()) {
            reject(req, p.blocks);
        }
        // A resting preview is valid analysis, not a risk failure. Mutation is nevertheless
        // authorized only by an immediate executable package (or an explicitly recorded fill),
        // so the ledger can never turn a resting instruction into a fictional fill.
        if (!req.executedFill() && (p.price() == null
                || p.price().executability() != OrderInstruction.Executability.IMMEDIATE)) {
            String reason = p.price() != null
                    && p.price().executability() == OrderInstruction.Executability.RESTING
                    ? "The signed package limit is resting and has not filled. Reprice it to the current "
                        + "executable book or record the broker fill after it occurs."
                    : "The complete package has no immediately executable market.";
            reject(req, List.of(reason));
        }
        requiredRiskFact(p.maxLoss, "maximum loss");
        requiredRiskFact(p.reserve, "reserve");
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
        TradePreview executionPreview = previewFromPlanOn(c, req, p,
                acct.cashCents(), acct.reservedCents(), 0);
        if (!executionPreview.ok()) {
            throw new TradeRejectedException(executionPreview.blockReasons());
        }
        long planMaxLoss = requiredRiskFact(p.maxLoss, "maximum loss");
        long planReserve = requiredRiskFact(p.reserve, "reserve");
        long cashAfter = acct.cashCents() + p.entryNet - p.fees;
        long reservedAfter = acct.reservedCents() + planReserve;
        if (cashAfter - reservedAfter < 0) {
            throw new TradeRejectedException(List.of("Insufficient buying power: needs "
                    + Money.fmt(planMaxLoss + p.fees) + " but only " + Money.fmt(acct.buyingPowerCents()) + " is available"));
        }
        String symbol = req.symbol();
        if (req.heldShares()) {
            long needed = Math.max(p.sharesToLock(), Math.multiplyExact(heldShareUnitsPerPackage(req.legs()), req.qty()));
            long free = availableCoverShares(c, acct.id(), symbol);
            if (free < needed) {
                throw new TradeRejectedException(List.of("Needs " + needed + " free shares of "
                        + symbol + " but only " + Math.max(0, free)
                        + " are free in the destination Practice account"));
            }
        }
        // entry_underlying_cents is the anchor every later P/L, review benchmark and settlement
        // fallback measures against. open() already rejects a blocked plan, so this is unreachable
        // today — it exists so a future path cannot persist "unknown spot" as 0 and have every
        // downstream number silently measure from a $0.00 stock (§3.2).
        if (p.underlyingCents() == null) {
            throw new TradeRejectedException(List.of("No underlying price is available to anchor this position's entry"));
        }
        String now = now();
        long cash = acct.cashCents(), reserved = acct.reservedCents();
        cash += p.entryNet;
        Ledger.append(c, acct.id(), tradeId, now, "PREMIUM_OPEN", p.entryNet, cash, reserved,
                req.strategy() + " x" + req.qty() + " open");
        if (p.fees != 0) {
            cash -= p.fees;
            Ledger.append(c, acct.id(), tradeId, now, "FEE", -p.fees, cash, reserved, "open commissions");
        }
        if (planReserve != 0) {
            reserved += planReserve;
            Ledger.append(c, acct.id(), tradeId, now, "RESERVE_HOLD", planReserve, cash, reserved, "max-loss reserve");
        }
        var evidence = entryEvidence(acct.id(), p.evidence());
        Db.execOn(c, """
                INSERT INTO trades(id,account_id,symbol,strategy,status,qty,legs_json,thesis,horizon,risk_mode,
                  entry_underlying_cents,entry_net_premium_cents,max_loss_cents,max_profit_cents,breakevens_json,
                  pop_entry,fees_open_cents,fees_close_cents,realized_pnl_cents,close_reason,entry_snapshot_json,
                  is_live,created_at,closed_at,updated_at,intent,shares_locked,order_instruction_json,
                  data_provenance,data_age,data_source)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,NULL,NULL,?,0,?,NULL,?,?,?,?,?,?,?)""",
                tradeId, acct.id(), symbol, req.strategy(), TradeRecord.ACTIVE,
                req.qty(), Json.write(p.filledLegs), req.thesis(), req.horizon(), req.riskMode(),
                p.underlyingCents, p.entryNet, planMaxLoss, p.maxProfit, Json.write(p.breakevens),
                p.marketImpliedRisk.pop(), p.fees, p.snapshotJson, now, now,
                req.intent() == null || req.intent().isBlank() ? null
                        : io.liftandshift.strikebench.strategy.StrategyIntent.parse(req.intent()).name(),
                p.sharesToLock(), req.orderInstruction() == null ? null : Json.write(req.orderInstruction()),
                evidence.provenance().name(), evidence.age().name(),
                evidence.source());
        Db.execOn(c, "UPDATE accounts SET cash_cents=?, reserved_cents=?, has_traded=1, updated_at=? WHERE id=?",
                cash, reserved, now, acct.id());
        TradeRecord created = getOn(c, tradeId);
        if (hook != null) hook.afterTradeCreated(c, created, executionPreview);
        return created;
    }

    private void reject(OpenRequest req, List<String> reasons) {
        audit.log(req.accountId(), null, "TRADE_REJECTED", "BLOCK", Map.of(
                "symbol", req.symbol(), "strategy", req.strategy(), "qty", req.qty(), "reasons", reasons));
        throw new TradeRejectedException(reasons);
    }

    // ---- Lifecycle ----

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
            Long decisionUnderlying = marks.underlyingQuote(t.symbol(), worldOf(t.accountId()))
                    .map(Quote::mark).map(Money::toCents).orElse(null);
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
     * realized. Ledger rows, reserve release, the survivor row, and the owning result hook share
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
            long outstanding = Ledger.outstandingReserve(c, trade.id());
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
            Ledger.append(c, account.id(), trade.id(), at, "PREMIUM_CLOSE", close.cashCents(), cash,
                    account.reservedCents(), trade.strategy() + " x" + closeQuantity + " partial close");
            if (close.feesCents() != 0) {
                cash = Math.subtractExact(cash, close.feesCents());
                Ledger.append(c, account.id(), trade.id(), at, "FEE", -close.feesCents(), cash,
                        account.reservedCents(), "partial-close commissions");
            }
            long reserved = account.reservedCents();
            if (reserveRelease != 0) {
                reserved = Math.subtractExact(reserved, reserveRelease);
                Ledger.append(c, account.id(), trade.id(), at, "RESERVE_RELEASE", -reserveRelease, cash,
                        reserved, "reserve released for " + closeQuantity + " closed package"
                                + (closeQuantity == 1 ? "" : "s"));
            }

            Long underlying = marks.underlyingQuote(trade.symbol(), worldOf(trade.accountId()))
                    .map(Quote::mark).map(Money::toCents).orElse(null);
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
            OrderInstruction survivingOrderInstruction = remainingOrderInstruction(
                    trade.orderInstruction(), trade.qty(), closeQuantity);
            long closeFeesToDate = Math.addExact(trade.feesCloseCents(), close.feesCents());
            String snapshot = survivorEntrySnapshot(trade, survivingQuantity, survivingShares);
            Db.execOn(c, "UPDATE trades SET qty=?,entry_net_premium_cents=?,max_loss_cents=?," +
                            "max_profit_cents=?,fees_open_cents=?,fees_close_cents=?,realized_pnl_cents=?," +
                            "decision_pnl_cents=?,shares_locked=?,order_instruction_json=?::jsonb,entry_snapshot_json=?::jsonb," +
                            "updated_at=? WHERE id=?",
                    survivingQuantity, survivingEntry, survivingMaxLoss, survivingMaxProfit,
                    survivingOpenFees, closeFeesToDate, totalRealized, totalDecision, survivingShares,
                    survivingOrderInstruction == null ? null : Json.write(survivingOrderInstruction),
                    snapshot, at, trade.id());
            AccountService.applyBalances(c, account.id(), cash, reserved, at);
            TradeRecord survivor = getOn(c, trade.id());
            if (Ledger.outstandingReserve(c, trade.id()) != survivingReserve) {
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
            long reserveBefore = Ledger.outstandingReserve(c, trade.id());
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
            long openingFees = added.isEmpty() ? 0 : feesFor(normalizedLegs(added), normalizedQuantity(added));

            long currentLegCash = cashForLots(lotsFromTrade(trade));
            long packageAdjustment = Math.subtractExact(trade.entryNetPremiumCents(), currentLegCash);
            long allocatedPackageAdjustment = allocatedPrefix(packageAdjustment, totalBasisUnits, removedBasisUnits);
            long remainingPackageAdjustment = Math.subtractExact(packageAdjustment, allocatedPackageAdjustment);
            List<LegLot> currentLots = new ArrayList<>(reconciled.retained());
            currentLots.addAll(added);
            int currentQuantity = normalizedQuantity(currentLots);
            long currentEntry = Math.addExact(cashForLots(currentLots), remainingPackageAdjustment);
            long currentOpenFees = Math.addExact(
                    Math.subtractExact(trade.feesOpenCents(), allocatedOpenFees), openingFees);
            OpenRequest currentAfter = new OpenRequest(trade.accountId(), trade.symbol(), exactAfter.strategy(),
                    currentQuantity, normalizedLegs(currentLots), trade.thesis(), trade.horizon(), trade.riskMode(),
                    trade.intent(), trade.sharesLocked() > 0, currentOpenFees,
                    "POSITION_TRANSFORMATION", "EXECUTED", null,
                    trade.sharesLocked() > 0
                            ? io.liftandshift.strikebench.recommend.HoldingsEvidence.Provenance.ACCOUNT_BACKED
                            : null);

            String world = worldOf(trade.accountId());
            Plan exactPlan = computePlan(currentAfter, true, world, true, marketModeFor(world), true);
            if (!exactPlan.blocks().isEmpty()) {
                throw new TradeRejectedException(exactPlan.blocks());
            }
            long exactReserve = requiredRiskFact(exactPlan.reserve(), "reserve");
            long exactMaxLoss = requiredRiskFact(exactPlan.maxLoss(), "maximum loss");
            long sharesAfter = exactPlan.sharesToLock();
            if (sharesAfter > 0) {
                long freeIncludingCurrent = Math.addExact(
                        availableCoverShares(c, trade.accountId(), trade.symbol()),
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
                Ledger.append(c, account.id(), trade.id(), at, adjustmentCloseRowType(action, reconciled.removed()),
                        closingCash, cash, reserved, action + " executable close");
            }
            if (closingFees != 0) {
                cash = Math.subtractExact(cash, closingFees);
                Ledger.append(c, account.id(), trade.id(), at, "FEE", -closingFees, cash, reserved,
                        action + " close commissions");
            }
            if (!added.isEmpty()) {
                cash = Math.addExact(cash, openingCash);
                Ledger.append(c, account.id(), trade.id(), at, adjustmentOpenRowType(action, added),
                        openingCash, cash, reserved, action + " executable open");
            }
            if (openingFees != 0) {
                cash = Math.subtractExact(cash, openingFees);
                Ledger.append(c, account.id(), trade.id(), at, "FEE", -openingFees, cash, reserved,
                        action + " open commissions");
            }
            long reserveDelta = Math.subtractExact(exactReserve, reserveBefore);
            if (reserveDelta < 0) {
                reserved = Math.addExact(reserved, reserveDelta);
                Ledger.append(c, account.id(), trade.id(), at, "RESERVE_RELEASE", reserveDelta, cash, reserved,
                        action + " reserve reduction");
            } else if (reserveDelta > 0) {
                reserved = Math.addExact(reserved, reserveDelta);
                Ledger.append(c, account.id(), trade.id(), at, "RESERVE_HOLD", reserveDelta, cash, reserved,
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
                            "shares_locked=?,order_instruction_json=?::jsonb,entry_snapshot_json=?::jsonb," +
                            "data_provenance=?,data_age=?,data_source=?,updated_at=? WHERE id=?",
                    currentAfter.strategy(), currentAfter.qty(), Json.write(exactPlan.filledLegs()), exactPlan.entryNet(),
                    exactMaxLoss, exactPlan.maxProfit(), Json.write(exactPlan.breakevens()),
                    exactPlan.marketImpliedRisk().pop(),
                    exactPlan.fees(), closeFeesToDate, totalRealized, decisionToDate, sharesAfter,
                    currentAfter.orderInstruction() == null ? null : Json.write(currentAfter.orderInstruction()),
                    snapshot, entryEvidence(trade.accountId(), exactPlan.evidence()).provenance().name(),
                    entryEvidence(trade.accountId(), exactPlan.evidence()).age().name(),
                    entryEvidence(trade.accountId(), exactPlan.evidence()).source(), at, trade.id());
            AccountService.applyBalances(c, account.id(), cash, reserved, at);
            TradeRecord survivor = getOn(c, trade.id());
            if (Ledger.outstandingReserve(c, trade.id()) != exactReserve) {
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
            long reserveBefore = Ledger.outstandingReserve(c, trade.id());
            requireExpectedPosition(trade, reserveBefore, expectedPosition);
            LifecycleAssessment projected = projectLifecycleConversion(c, trade, action, legIndex);
            requireExpectedLifecycle(projected, expectedLifecycle);
            if (projected.survivor() != null && !projected.survivor().risk().mechanicallyEligible()) {
                throw new TradeRejectedException(projected.survivor().risk().blockReasons());
            }
            long projectedReserve = projected.survivor() == null
                    ? 0L : projected.survivor().risk().requiredReserveCents();
            if (!Objects.equals(projected.reserveAfterCents(), projectedReserve)) {
                throw new TradeRejectedException(List.of(
                        "The surviving lifecycle reserve is unavailable or changed; review the conversion again."));
            }

            Account account = locked.account();
            long cash = Math.addExact(account.cashCents(), projected.optionSettlementCashCents());
            long reserved = account.reservedCents();
            String at = now();
            Ledger.append(c, account.id(), trade.id(), at, "SETTLEMENT", projected.optionSettlementCashCents(), cash, reserved,
                    action + " of " + projected.contract() + " at " + projected.settlementPriceBasis());
            if (projected.sharesDelta() > 0) {
                PositionsService.addAssigned(c, account.id(), trade.symbol(), projected.sharesDelta(),
                        strikePerShareCents(trade, legIndex), at);
                cash = Math.addExact(cash, projected.stockCashCents());
                Ledger.append(c, account.id(), trade.id(), at, "STOCK_BUY", projected.stockCashCents(), cash, reserved,
                        action + ": acquired " + projected.sharesDelta() + " sh " + trade.symbol()
                                + " at the contract strike; option premium remains separate");
            } else if (projected.sharesDelta() < 0) {
                long shares = -projected.sharesDelta();
                long stockRealized = PositionsService.removeAssigned(c, account.id(), trade.symbol(), shares,
                        strikePerShareCents(trade, legIndex), at);
                cash = Math.addExact(cash, projected.stockCashCents());
                Ledger.append(c, account.id(), trade.id(), at, "STOCK_SELL", projected.stockCashCents(), cash, reserved,
                        action + ": delivered " + shares + " sh " + trade.symbol()
                                + " at the contract strike (stock P/L vs basis " + Money.fmt(stockRealized) + ")");
            }

            long reserveDelta = Math.subtractExact(projectedReserve, reserveBefore);
            if (reserveDelta < 0) {
                reserved = Math.addExact(reserved, reserveDelta);
                Ledger.append(c, account.id(), trade.id(), at, "RESERVE_RELEASE", reserveDelta, cash, reserved,
                        action + " reserve reduction");
            } else if (reserveDelta > 0) {
                reserved = Math.addExact(reserved, reserveDelta);
                Ledger.append(c, account.id(), trade.id(), at, "RESERVE_HOLD", reserveDelta, cash, reserved,
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
                Plan exactPlan = computePlan(exactAfter, true, world, true, marketModeFor(world), true);
                if (!exactPlan.blocks().isEmpty()) throw new TradeRejectedException(exactPlan.blocks());
                long exactMaxLoss = requiredRiskFact(exactPlan.maxLoss(), "maximum loss");
                long exactReserve = requiredRiskFact(exactPlan.reserve(), "reserve");
                if (exactReserve != projectedReserve) {
                    throw new TradeRejectedException(List.of(
                            "The surviving lifecycle reserve changed after preview. Review the conversion again."));
                }
                String snapshot = lifecycleEntrySnapshot(trade, exactPlan, action, at,
                        projected.allocatedEntryBasisCents(), projected.allocatedOpenFeesCents(),
                        projected.heldShareContextAfter());
                Db.execOn(c, "UPDATE trades SET strategy=?,intent=?,qty=?,legs_json=?::jsonb,entry_net_premium_cents=?,"
                                + "max_loss_cents=?,max_profit_cents=?,breakevens_json=?::jsonb,pop_entry=?,"
                                + "fees_open_cents=?,realized_pnl_cents=?,decision_pnl_cents=?,shares_locked=?,"
                                + "order_instruction_json=?::jsonb,entry_snapshot_json=?::jsonb,data_provenance=?,data_age=?,"
                                + "data_source=?,updated_at=? WHERE id=?",
                        exactAfter.strategy(), exactAfter.intent(), exactAfter.qty(), Json.write(exactPlan.filledLegs()), exactPlan.entryNet(),
                        exactMaxLoss, exactPlan.maxProfit(), Json.write(exactPlan.breakevens()),
                        exactPlan.marketImpliedRisk().pop(),
                        exactPlan.fees(), totalRealized, totalDecision, exactPlan.sharesToLock(),
                        exactAfter.orderInstruction() == null ? null : Json.write(exactAfter.orderInstruction()),
                        snapshot, entryEvidence(trade.accountId(), exactPlan.evidence()).provenance().name(),
                        entryEvidence(trade.accountId(), exactPlan.evidence()).age().name(),
                        entryEvidence(trade.accountId(), exactPlan.evidence()).source(), at, trade.id());
            }
            AccountService.applyBalances(c, account.id(), cash, reserved, at);
            TradeRecord changed = getOn(c, trade.id());
            if (Ledger.outstandingReserve(c, trade.id()) != projectedReserve) {
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
        Plan replacementPlan = computePlan(replacement, false, null, false, null, false);
        if (!replacementPlan.blocks().isEmpty()) reject(replacement, replacementPlan.blocks());
        requiredRiskFact(replacementPlan.maxLoss(), "maximum loss");
        requiredRiskFact(replacementPlan.reserve(), "reserve");
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
            Long decisionUnderlying = marks.underlyingQuote(current.symbol(), worldOf(current.accountId()))
                    .map(Quote::mark).map(Money::toCents).orElse(null);
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
    public CloseResult settle(String tradeId, boolean confirm, LifecycleHook hook) {
        requireConfirm(confirm, "settle");
        markMemo.invalidate(tradeId); // closing: never serve a pre-close mark
        accountSnapshot.invalidateAll(); // the book changed — no consumer may see the old snapshot
        CloseResult result = db.tx(c -> {
            LockedTrade locked = lockTradeAndAccount(c, tradeId, TradeRecord.ACTIVE);
            TradeRecord t = locked.trade();
            // ONE CLOCK PER MARKET: a sim-world trade dies at the SIM bell and settles at the sim
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
                BigDecimal fallback = marks.underlyingQuote(t.symbol(), worldOf(t.accountId()))
                        .map(Quote::mark)
                        .orElseThrow(() -> new TradeRejectedException(
                                List.of("No underlying price available to settle against")));
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
            long tradeReserve = Ledger.outstandingReserve(c, t.id());
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
            Ledger.append(c, acct.id(), t.id(), nowTs, "SETTLEMENT", settleValue, cash, reserved,
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
                    Ledger.append(c, acct.id(), t.id(), nowTs, "STOCK_SELL", strikeTotal, cash, reserved,
                            "assignment: " + shares + " sh " + t.symbol() + " called away @ " + leg.strike().toPlainString()
                                    + " (stock P/L vs basis " + Money.fmt(stockRealized) + ")");
                    assignNote.append(" (assigned: ").append(shares).append(" sh called away at ")
                            .append(leg.strike().toPlainString()).append(")");
                } else {
                    PositionsService.addAssigned(c, acct.id(), t.symbol(), shares, strikePerShare, nowTs);
                    cash -= strikeTotal;
                    Ledger.append(c, acct.id(), t.id(), nowTs, "STOCK_BUY", -strikeTotal, cash, reserved,
                            "assignment: bought " + shares + " sh " + t.symbol() + " @ " + leg.strike().toPlainString()
                                    + " via short put (basis = strike; premium was option income)");
                    assignNote.append(" (assigned: bought ").append(shares).append(" sh at ")
                            .append(leg.strike().toPlainString()).append(")");
                }
            }
            long reserve = Ledger.outstandingReserve(c, t.id());
            if (reserve != 0) {
                reserved -= reserve;
                Ledger.append(c, acct.id(), t.id(), nowTs, "RESERVE_RELEASE", -reserve, cash, reserved, "reserve released on settle");
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
            AccountService.applyBalances(c, acct.id(), cash, reserved, nowTs);
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

            long reserve = Ledger.outstandingReserve(c, tradeId);
            if (reserve != 0) {
                reserved -= reserve;
                Ledger.append(c, acct.id(), tradeId, now, "RESERVE_RELEASE", -reserve, cash, reserved, "released by delete");
            }
            List<LedgerEntry> cashRows = Db.queryOn(c,
                    "SELECT * FROM ledger WHERE trade_id=? AND type IN ('PREMIUM_OPEN','PREMIUM_CLOSE','SETTLEMENT','FEE') ORDER BY id",
                    Ledger::map, tradeId);
            for (LedgerEntry row : cashRows) {
                cash -= row.amountCents();
                Ledger.append(c, acct.id(), tradeId, now, "ADJUSTMENT", -row.amountCents(), cash, reserved,
                        "reversal of ledger #" + row.id() + " (" + row.type() + ")");
            }
            Db.execOn(c, "UPDATE trades SET status=?, close_reason='DELETED_BY_USER', closed_at=?, updated_at=? WHERE id=?",
                    TradeRecord.DELETED, now, now, tradeId);
            AccountService.applyBalances(c, acct.id(), cash, reserved, now);
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

    /** The explicit market-world token used to read and value this account. */
    String worldOf(String accountId) {
        return db.with(c -> AccountService.get(c, accountId)).marketWorld();
    }

    private io.liftandshift.strikebench.market.MarketMode marketModeFor(String worldId) {
        return io.liftandshift.strikebench.market.MarketMode.of(worldId, cfg.fixturesOnly(),
                io.liftandshift.strikebench.db.AnalysisContext.OBSERVED);
    }

    private DataEvidence entryEvidence(String accountId, DataEvidence evidence) {
        var mode = marketModeFor(worldOf(accountId));
        return switch (mode) {
            case DEMO -> DataEvidence.demo("built-in demo");
            case SIMULATED -> DataEvidence.simulated("simulated market");
            case SCENARIO -> DataEvidence.modeled("scenario");
            case OBSERVED -> {
                DataEvidence resolved = evidence == null ? DataEvidence.missing("observed market") : evidence;
                yield new DataEvidence(resolved.provenance(), resolved.age(), "observed market");
            }
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
        db.exec("INSERT INTO trade_marks(trade_id,ts,current_mark_json) VALUES (?,?,?::jsonb)",
                tradeId, view.ts(), Json.write(view));
        return view;
    }

    /** Same computation as refresh, but persists nothing — used by the detail view. */
    public MarkView currentMark(String tradeId) {
        return memoizedMark(get(tradeId));
    }

    /** The position mode's one current underlying result, including for historical/closed detail. */
    public java.util.Optional<io.liftandshift.strikebench.model.Quote> currentUnderlyingQuote(
            String tradeId) {
        TradeRecord trade = get(tradeId);
        return marks.underlyingQuote(trade.symbol(), worldOf(trade.accountId()));
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
        return loadAccountMarkSnapshot(accountId, activeTrades(accountId));
    }

    private Map<String, MarkView> loadAccountMarkSnapshot(String accountId,
                                                         List<TradeRecord> activeTrades) {
        return accountSnapshot.get(accountId, id -> {
            Map<String, MarkView> out = new LinkedHashMap<>();
            for (TradeRecord t : activeTrades) {
                try { out.put(t.id(), memoizedMark(t)); } catch (RuntimeException ignored) { /* partial */ }
            }
            return Map.copyOf(out);
        });
    }

    /**
     * The current Practice book's non-scenario financial read model. Every served Practice-book
     * projection consumes this one typed object, so liquidation value, heat, dollar delta and
     * Greeks are derived from the same active-position roster and the same mark map. It is a
     * compositor over the existing mark/risk authorities, not a second calculator.
     */
    public record PracticeBookSnapshot(
            String schemaVersion,
            String snapshotId,
            String accountId,
            List<TradeRecord> activeTrades,
            Map<String, MarkView> marksByTrade,
            PortfolioHeat heat,
            OpenPositionsValue openPositions,
            DollarDeltaBook dollarDelta,
            BookGreeks greeks,
            String asOf) {
        public static final String SCHEMA_VERSION = "practice-book-snapshot-v1";

        public PracticeBookSnapshot {
            if (!SCHEMA_VERSION.equals(schemaVersion)) {
                throw new IllegalArgumentException("unsupported Practice-book snapshot schema");
            }
            if (snapshotId == null || snapshotId.isBlank()) {
                throw new IllegalArgumentException("Practice-book snapshot id is required");
            }
            if (accountId == null || accountId.isBlank()) {
                throw new IllegalArgumentException("Practice-book account id is required");
            }
            activeTrades = activeTrades == null ? List.of() : List.copyOf(activeTrades);
            marksByTrade = marksByTrade == null ? Map.of() : Map.copyOf(marksByTrade);
            if (heat == null || openPositions == null || dollarDelta == null || greeks == null) {
                throw new IllegalArgumentException(
                        "Practice-book snapshot requires heat, value, dollar-delta, and Greeks results");
            }
            if (asOf == null || asOf.isBlank()) {
                throw new IllegalArgumentException("Practice-book snapshot timestamp is required");
            }
            if (activeTrades.stream().anyMatch(trade -> !accountId.equals(trade.accountId()))) {
                throw new IllegalArgumentException(
                        "Practice-book snapshot cannot mix accounts");
            }
            java.util.Set<String> activeIds = activeTrades.stream()
                    .map(TradeRecord::id).collect(java.util.stream.Collectors.toSet());
            if (!activeIds.containsAll(marksByTrade.keySet())) {
                throw new IllegalArgumentException(
                        "Practice-book marks must belong to an active snapshot position");
            }
            if (heat.activeTrades() != activeTrades.size()
                    || openPositions.openTradesCount() != activeTrades.size()
                    || greeks.activeTrades() != activeTrades.size()) {
                throw new IllegalArgumentException(
                        "Practice-book component counts must describe the same active-position roster");
            }
        }
    }

    /** Typed heat result carried only by the versioned Practice Book snapshot. */
    public record PortfolioHeat(
            int activeTrades,
            long totalMaxLossCents,
            long reservedCents,
            int shortVolTrades,
            Map<String, Long> bySymbolMaxLossCents,
            long concentrationPct,
            long earlyAssignmentLiquidityCents,
            long physicalAssignmentCashCents,
            long assignmentReserveReleasedCents,
            long postPhysicalAssignmentBuyingPowerCents) {
        public PortfolioHeat {
            bySymbolMaxLossCents = bySymbolMaxLossCents == null
                    ? Map.of() : Map.copyOf(bySymbolMaxLossCents);
        }

    }

    public PracticeBookSnapshot practiceBookSnapshot(String accountId) {
        List<TradeRecord> active = activeTrades(accountId);
        Map<String, MarkView> snapshot = loadAccountMarkSnapshot(accountId, active);
        Account account = db.with(c -> AccountService.get(c, accountId));
        PortfolioHeat heat = portfolioHeatFacts(accountId, active, account);
        OpenPositionsValue open = openPositionsValue(active, snapshot);
        DollarDeltaBook dollarDelta = aggregateDollarDelta(active, snapshot);
        BookGreeks greeks = portfolioGreeks(active, snapshot, dollarDelta);
        return new PracticeBookSnapshot(PracticeBookSnapshot.SCHEMA_VERSION,
                Ids.newId("pbs"), accountId, active, snapshot, heat, open, dollarDelta,
                greeks, now());
    }

    /**
     * Portfolio heat: what the whole book is exposed to, not just per-trade risk — total worst
     * case, per-symbol concentration, short-volatility count, temporary early-assignment
     * liquidity, and the terminal cash-secured-put delivery picture. Those are deliberately
     * separate: a put spread can demand gross strike cash briefly without having that terminal
     * loss or becoming a stock position in StrikeBench's settlement model.
     */
    /** Gross strike obligation across active short puts; the same normalized fact used by heat. */
    public long theoreticalShortPutObligationCents(String accountId) {
        return practiceBookSnapshot(accountId).heat().earlyAssignmentLiquidityCents();
    }

    private PortfolioHeat portfolioHeatFacts(String accountId, List<TradeRecord> active, Account acct) {
        Map<String, Long> reserveByTrade = new java.util.HashMap<>();
        for (Map.Entry<String, Long> e : db.query(
                "SELECT trade_id, COALESCE(SUM(amount_cents),0) AS amount FROM ledger "
                        + "WHERE account_id=? AND trade_id IS NOT NULL "
                        + "AND type IN " + Ledger.RESERVE_TYPES + " GROUP BY trade_id",
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
        long concentrationPct = totalMaxLoss > 0
                ? Math.round(100.0 * worstSymbol / totalMaxLoss) : 0;
        return new PortfolioHeat(active.size(), totalMaxLoss, acct.reservedCents(), shortVol,
                Map.copyOf(bySymbol), concentrationPct, earlyAssignmentLiquidity,
                physicalAssignmentCash, assignmentReserveReleased,
                acct.buyingPowerCents() - physicalAssignmentCash + assignmentReserveReleased);
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
        io.liftandshift.strikebench.market.MarketMode mode = marketModeFor(world);
        io.liftandshift.strikebench.model.Quote underlyingQuote =
                marks.underlyingQuote(t.symbol(), world).orElse(null);
        Long underlyingCents = underlyingQuote == null || underlyingQuote.mark() == null
                ? null : Money.toCents(underlyingQuote.mark());
        String quoteUnavailableReason = underlyingCents == null
                ? "No current underlying quote is available for " + t.symbol()
                    + " in this position's market mode."
                : null;
        boolean underlyingAnalyticsCurrent = underlyingQuote != null
                && underlyingQuote.mark() != null
                && underlyingQuote.evidence().executableIn(mode);

        boolean greeksComplete = true;
        String closeUnavailableReason = null;
        String greeksUnavailableReason = null;
        DataEvidence worst = underlyingCents == null
                ? DataEvidence.missing("underlying quote") : underlyingQuote.evidence();
        List<Double> ivs = new ArrayList<>();
        int optionLegs = 0;
        boolean ivComplete = true;
        String ivUnavailableReason = null;
        List<GreeksAggregator.LegExposure> greekExposures = new ArrayList<>();
        List<LegGreekRow> legGreeks = new ArrayList<>();
        List<ExecutablePackagePricer.LegBook> closingBooks = new ArrayList<>(t.legs().size());
        long heldContextShares = heldShareContextShares(t);
        for (Leg leg : t.legs()) {
            var mark = leg.isStock() && underlyingQuote != null
                    ? MarksSource.LegMark.fromUnderlying(underlyingQuote)
                    : marks.legMark(t.symbol(), leg, world).orElse(null);
            Leg closingLeg = new Leg(leg.action().opposite(), leg.type(), leg.strike(),
                    leg.expiration(), leg.ratio(), leg.entryPrice(), leg.multiplier());
            closingBooks.add(ExecutablePackagePricer.LegBook.fromLegMark(closingLeg, mark));
            if (!leg.isStock()) optionLegs++;
            if (mark == null) {
                if (closeUnavailableReason == null) {
                    closeUnavailableReason = "No current market mark is available for "
                            + legDesc(leg) + ".";
                }
                worst = worse(worst, DataEvidence.missing("position leg mark"));
                if (leg.isStock()) {
                    // A share's delta is structural even when its current price is missing.
                    var exposure = new GreeksAggregator.LegExposure(true, closeSign(leg),
                            leg.multiplier(), leg.ratio(), t.qty(), null, null, null, null);
                    greekExposures.add(exposure);
                    legGreeks.add(new LegGreekRow(legDesc(leg), null, null, null,
                            GreeksAggregator.aggregate(List.of(exposure), 0)));
                } else {
                    greeksComplete = false;
                    ivComplete = false;
                    if (greeksUnavailableReason == null) {
                        greeksUnavailableReason = "No current Greeks mark is available for "
                                + legDesc(leg) + ".";
                    }
                    if (ivUnavailableReason == null) {
                        ivUnavailableReason = "No current implied volatility is available for "
                                + legDesc(leg) + ".";
                    }
                }
                continue;
            }
            worst = worse(worst, mark.evidence());
            if (!leg.isStock() && !mark.evidence().executableIn(mode)) {
                String evidence = mark.evidence().provenance() + " / "
                        + mark.evidence().age() + " evidence"
                        + (mark.evidence().source() == null
                                || mark.evidence().source().isBlank()
                            ? "" : " from " + mark.evidence().source());
                greeksComplete = false;
                ivComplete = false;
                if (greeksUnavailableReason == null) {
                    greeksUnavailableReason = "Current Greeks require live or delayed "
                            + "mode-owned option evidence; " + legDesc(leg) + " has "
                            + evidence + " and remains indicative only.";
                }
                if (ivUnavailableReason == null) {
                    ivUnavailableReason = "Current probability of profit requires live or "
                            + "delayed mode-owned option evidence; " + legDesc(leg) + " has "
                            + evidence + " and remains indicative only.";
                }
                legGreeks.add(new LegGreekRow(legDesc(leg),
                        mark.bid() == null ? null : mark.bid().toPlainString(),
                        mark.ask() == null ? null : mark.ask().toPlainString(),
                        mark.iv(), null));
                continue;
            }
            if (!leg.isStock()) {
                if (mark.iv() != null && mark.iv() > 0) {
                    ivs.add(mark.iv());
                } else {
                    ivComplete = false;
                    if (ivUnavailableReason == null) {
                        ivUnavailableReason = "No positive current implied volatility is available for "
                                + legDesc(leg) + ".";
                    }
                }
                if (mark.delta() == null || mark.gamma() == null
                        || mark.theta() == null || mark.vega() == null) {
                    greeksComplete = false;
                    if (greeksUnavailableReason == null) {
                        greeksUnavailableReason = "The current market mark for " + legDesc(leg)
                                + " does not include a complete Delta/Gamma/Theta/Vega set.";
                    }
                }
            }
            var exposure = new GreeksAggregator.LegExposure(leg.isStock(), closeSign(leg),
                    leg.multiplier(), leg.ratio(), t.qty(), mark.delta(), mark.gamma(),
                    mark.theta(), mark.vega());
            greekExposures.add(exposure);
            legGreeks.add(new LegGreekRow(legDesc(leg),
                    mark.bid() == null ? null : mark.bid().toPlainString(),
                    mark.ask() == null ? null : mark.ask().toPlainString(),
                    mark.iv(),
                    GreeksAggregator.aggregate(List.of(exposure), 0)));
        }
        if (heldContextShares > 0) {
            legGreeks.add(new LegGreekRow(heldContextShares + " held shares", null, null, null,
                    GreeksAggregator.aggregate(List.of(), heldContextShares)));
        }
        if (optionLegs > 0 && !underlyingAnalyticsCurrent) {
            greeksComplete = false;
            if (greeksUnavailableReason == null) {
                greeksUnavailableReason = underlyingCents == null
                        ? quoteUnavailableReason
                        : "Current Greeks require a live or delayed mode-owned underlying "
                            + "result; the available " + underlyingQuote.markFreshness()
                            + " quote remains indicative only.";
            }
        }
        // §3.2: an incomplete greeks strip is ABSENT, not a partial sum. A leg whose mark carried no
        // greeks used to be skipped while the remaining legs were still published as the position's
        // delta/gamma/theta/vega — a fabricated exposure that read as complete. Same rule the idea
        // path already applies in packageGreeks. Units are normalized: theta/vega in CENTS.
        GreeksView greeks = greeksComplete
                ? GreeksAggregator.aggregate(greekExposures, heldContextShares) : null;
        if (greeks == null && greeksUnavailableReason == null) {
            greeksComplete = false;
            greeksUnavailableReason = "This position has no complete current Greeks result.";
        }
        // THE one current closing-price result. The analysis policy retains a mode-owned stale
        // or EOD mark as an explicitly labeled indicative valuation, while executableNetCents is
        // populated only when every opposite-side book passes the same executableIn(mode)
        // authority used by tickets and lifecycle analysis.
        ExecutablePackagePricer.Book closingBook = ExecutablePackagePricer.price(
                closingBooks, mode, ExecutablePackagePricer.Policy.ANALYSIS);
        PackagePrice currentClosePrice = closingBook.packagePrice(t.qty(),
                feeScheduleFor(t.legs(), t.qty()), PackagePrice.FeeSide.CLOSING,
                OrderInstruction.market());
        Long closeCost = currentClosePrice.executableNetCents();
        if (closeCost == null) {
            closeUnavailableReason = closeUnavailableReason(
                    t.legs(), closingBook, currentClosePrice, mode, closeUnavailableReason);
        } else {
            closeUnavailableReason = null;
        }
        // Opening fees already left cash and belong in today's P/L. The only omitted cost is the
        // FUTURE close fee, which the UI labels explicitly as not yet included.
        Long unrealized = closeCost == null ? null
                : closeCost + t.entryNetPremiumCents() - t.feesOpenCents();
        Long indicativeUnrealized = currentClosePrice.priced()
                ? currentClosePrice.grossPackageNetCents()
                    + t.entryNetPremiumCents() - t.feesOpenCents()
                : null;
        Long decisionUnrealized = unrealized;
        Long indicativeDecisionUnrealized = indicativeUnrealized;
        String decisionPnlUnavailableReason = closeUnavailableReason;
        if (heldContextShares > 0) {
            if (decisionUnrealized != null && underlyingCents != null
                    && t.entryUnderlyingCents() > 0) {
                decisionUnrealized +=
                        (underlyingCents - t.entryUnderlyingCents()) * heldContextShares;
            } else {
                decisionUnrealized = null;
                if (decisionPnlUnavailableReason == null) {
                    decisionPnlUnavailableReason = underlyingCents == null
                            ? "Current position P/L includes held shares, but no current underlying "
                                + "quote is available."
                            : "Current position P/L includes held shares, but their recorded entry "
                                + "anchor is unavailable.";
                }
            }
            if (indicativeDecisionUnrealized != null && underlyingCents != null
                    && t.entryUnderlyingCents() > 0) {
                indicativeDecisionUnrealized +=
                        (underlyingCents - t.entryUnderlyingCents()) * heldContextShares;
            } else {
                indicativeDecisionUnrealized = null;
            }
        }
        boolean mixedExp = t.legs().stream().filter(l -> !l.isStock())
                .map(Leg::expiration).distinct().count() > 1;
        Double popNow = null;
        io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.RiskNeutralAnalysis marketImpliedRisk = null;
        String popUnavailableReason = null;
        if (optionLegs == 0) {
            popUnavailableReason = "A share-only position has no option probability-of-profit result.";
        } else if (underlyingCents == null) {
            popUnavailableReason = quoteUnavailableReason;
        } else if (!underlyingAnalyticsCurrent) {
            popUnavailableReason = "Current probability of profit requires a live or delayed "
                    + "mode-owned underlying result; the available "
                    + underlyingQuote.markFreshness() + " quote remains indicative only.";
        } else if (mixedExp) {
            popUnavailableReason = "A mixed-expiration package requires supplied-path valuation; "
                    + "no single-expiration probability was substituted.";
        } else if (!ivComplete) {
            popUnavailableReason = ivUnavailableReason;
        } else if (heldContextShares > 0 && t.entryUnderlyingCents() <= 0) {
            popUnavailableReason = "The held-share payoff has no recorded entry-price anchor, so "
                    + "current probability of profit is unavailable.";
        } else {
            PayoffCurve curve = heldPayoffCurve(t);
            double ivAvg = ivs.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
            io.liftandshift.strikebench.market.OptionTime.Measure mtte =
                    io.liftandshift.strikebench.market.OptionTime.nearest(t.legs(), nowFor(world));
            List<BigDecimal> shortStrikes = t.legs().stream()
                    .filter(l -> !l.isStock() && l.action() == LegAction.SELL)
                    .map(Leg::strike).filter(java.util.Objects::nonNull).distinct().toList();
            if (mtte.hasModelTime()) {
                try {
                    marketImpliedRisk =
                            io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.analyze(
                            curve, recordedEntryPrice(t), underlyingCents, ivAvg, mtte,
                            marks.riskFreeRate((int) Math.max(1, mtte.calendarDays()), world),
                            shortStrikes);
                    popNow = marketImpliedRisk.pop();
                } catch (RuntimeException e) {
                    popUnavailableReason = e.getMessage() == null || e.getMessage().isBlank()
                            ? "The current probability model could not value this exact package."
                            : e.getMessage();
                }
            } else {
                popUnavailableReason = "This package has no live option model clock ("
                        + mtte.state() + "), so current probability of profit is unavailable.";
            }
        }
        if (popNow == null && popUnavailableReason == null) {
            popUnavailableReason =
                    "The current probability model did not return a value for this exact package.";
        }
        if (marketImpliedRisk == null) {
            marketImpliedRisk =
                    io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.RiskNeutralAnalysis.unavailable(
                            popUnavailableReason);
        }
        CurrentMarketAvailability availability = new CurrentMarketAvailability(
                underlyingCents != null, quoteUnavailableReason,
                closeCost != null, closeCost == null ? closeUnavailableReason : null,
                decisionUnrealized != null,
                decisionUnrealized == null ? decisionPnlUnavailableReason : null,
                popNow != null, popNow == null ? popUnavailableReason : null,
                greeks != null, greeks == null ? greeksUnavailableReason : null);
        return MarkView.create(t.id(), now, underlyingCents, unrealized,
                decisionUnrealized, currentClosePrice, indicativeUnrealized,
                indicativeDecisionUnrealized, popNow, worst.label(), greeks,
                List.copyOf(legGreeks), availability, underlyingQuote, marketImpliedRisk);
    }

    private static String closeUnavailableReason(
            List<Leg> openedLegs,
            ExecutablePackagePricer.Book closingBook,
            PackagePrice closingPrice,
            io.liftandshift.strikebench.market.MarketMode mode,
            String priorReason) {
        if (priorReason != null && !priorReason.isBlank()) return priorReason;
        if (closingBook != null) {
            for (int i = 0; i < closingBook.legPrices().size(); i++) {
                ExecutablePackagePricer.LegPrice legPrice = closingBook.legPrices().get(i);
                if (legPrice.executable() != null) continue;
                Leg opened = i < openedLegs.size() ? openedLegs.get(i) : legPrice.requested();
                var evidence = legPrice.evidence();
                if (evidence != null && !evidence.executableIn(mode)) {
                    return "Executable close unavailable for " + legDesc(opened) + ": "
                            + evidence.provenance() + " / " + evidence.age() + " evidence"
                            + (evidence.source() == null || evidence.source().isBlank()
                                ? "" : " from " + evidence.source())
                            + " is not executable in the " + mode + " market. The quote remains "
                            + "available only as a labeled indicative valuation.";
                }
                return "Executable close unavailable for " + legDesc(opened) + ": no valid "
                        + (opened.action() == LegAction.BUY ? "bid" : "ask")
                        + " exists on the opposite side of this contract.";
            }
        }
        if (closingPrice != null && closingPrice.unavailableReason() != null) {
            return closingPrice.unavailableReason();
        }
        return "The exact package has no executable current closing-price result.";
    }

    private static Double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static Double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }

    /** Liquidation view of all ACTIVE trades: what unwinding everything now would pay
     *  (executable sides, BEFORE close fees). Sums computeMark per trade; incomplete marks
     *  make the whole answer honest-partial rather than silently wrong. */
    public record OpenPositionsValue(int openTradesCount, int markedTradesCount, long valueCents,
                                     long unrealizedCents, boolean complete, String freshness) {}

    private static OpenPositionsValue openPositionsValue(List<TradeRecord> active,
                                                         Map<String, MarkView> snap) {
        long value = 0, unrealized = 0;
        int counted = 0;
        boolean complete = true;
        DataEvidence worst = DataEvidence.observed("position marks", DataAge.REALTIME);
        for (TradeRecord t : active) {
            MarkView view = snap.get(t.id());
            if (view == null) { complete = false; continue; }
            Long closePrice = view.currentClosePrice().executableNetCents();
            if (closePrice == null) { complete = false; continue; }
            value += closePrice;
            unrealized += view.unrealizedCents() == null ? 0 : view.unrealizedCents();
            worst = worse(worst, DataEvidence.fromLabel("position mark", view.freshness()));
            counted++;
        }
        return new OpenPositionsValue(active.size(), counted, value, unrealized, complete, worst.label());
    }

    /** Why the book refuses to state a share-equivalent figure. Named, never a 0 and never a sum (§3.2). */
    public static final String SHARE_GREEKS_NOT_ADDITIVE =
            "Share-equivalent delta and gamma are per-underlying quantities and are not additive across "
                    + "underlyings — 40 share-deltas of AAPL plus 40 of NVDA are not 80 of anything. The book "
                    + "states dollar delta, which is additive; the share pair stays on each position row, where "
                    + "one underlying gives it meaning.";

    /**
     * One position's greeks row: share delta/gamma mean something here, because the underlying is
     * single. {@code netDollarDeltaCents} is the same position in the additive unit, so a surface
     * pooling several rows adds THAT and never the share figures.
     */
    public record PositionGreekRow(String id, String symbol, String strategy, int qty,
                                   GreeksView greeks,
                                   Long netDollarDeltaCents, Long unrealizedCents) {}

    /**
     * Book-scope greeks. The per-share pair is deliberately absent with a stated reason (see
     * {@link #SHARE_GREEKS_NOT_ADDITIVE}); the additive dollar delta from
     * {@link #portfolioDollarDeltaBook} carries the book's directional exposure instead. Theta and
     * vega DO add in money terms, so they ride the normalized cent units of
     * {@link GreeksView} — the unit is in the
     * field name so nothing sits next to cent fields wearing a bare dollar name (§7.6).
     */
    public record BookGreeks(long netDollarDeltaCents, long grossDollarDeltaCents,
                             Map<String, Long> grossDollarDeltaBySymbolCents, boolean dollarDeltaComplete,
                             Double thetaCentsPerDay, Double vegaCentsPerPoint,
                             boolean perShareAvailable, String perShareUnavailableReason,
                             int activeTrades, int measuredTrades, boolean complete,
                             List<PositionGreekRow> positions, String basis) {
        public BookGreeks {
            grossDollarDeltaBySymbolCents = grossDollarDeltaBySymbolCents == null
                    ? Map.of() : Map.copyOf(grossDollarDeltaBySymbolCents);
            positions = positions == null ? List.of() : List.copyOf(positions);
            if (!perShareAvailable && (perShareUnavailableReason == null || perShareUnavailableReason.isBlank())) {
                throw new IllegalArgumentException("absent per-share greeks need a stated reason");
            }
        }
    }

    /** Aggregate greeks across all ACTIVE trades (Pro portfolio view). Exposure and model stats, never P&L. */
    private static BookGreeks portfolioGreeks(List<TradeRecord> active,
                                              Map<String, MarkView> snap,
                                              DollarDeltaBook dollarDelta) {
        double thetaCents = 0, vegaCents = 0;
        boolean complete = true;
        int measured = 0;
        List<PositionGreekRow> positions = new ArrayList<>();
        for (TradeRecord t : active) {
            MarkView view = snap.get(t.id());
            if (view == null) { complete = false; continue; }
            var normalized = view.greeks();
            if (normalized == null) {
                complete = false; // a missing component is disclosed, never carried into the sum as 0
            } else {
                thetaCents += normalized.thetaCentsPerDay();
                vegaCents += normalized.vegaCentsPerPoint();
                measured++;
            }
            positions.add(new PositionGreekRow(t.id(), t.symbol(), t.strategy(), t.qty(),
                    normalized, dollarDelta.tradeNetCents().get(t.id()), view.unrealizedCents()));
        }
        // An empty book honestly decays by zero; a book with nothing measurable has no decay to state.
        boolean statable = active.isEmpty() || measured > 0;
        return new BookGreeks(dollarDelta.netCents(), dollarDelta.grossCents(),
                dollarDelta.symbolGrossCents(), dollarDelta.complete(),
                statable ? round2(thetaCents) : null, statable ? round2(vegaCents) : null,
                false, SHARE_GREEKS_NOT_ADDITIVE,
                active.size(), measured, complete, positions,
                "Model statistics from the current mark snapshot: dollar delta in cents, theta in cents "
                        + "per day, vega in cents per vol point. " + dollarDelta.basis());
    }

    /** Current Practice exposure with one position omitted, used for before/after transformations. */
    public DollarDeltaExposure portfolioDollarDelta(String accountId, String focusSymbol,
                                                     String excludedTradeId) {
        return portfolioDollarDeltaBook(accountId, excludedTradeId).focus(focusSymbol);
    }

    /** Current Practice exposure in one normalized mark pass, optionally omitting one trade. */
    public DollarDeltaBook portfolioDollarDeltaBook(String accountId, String excludedTradeId) {
        PracticeBookSnapshot snapshot = practiceBookSnapshot(accountId);
        if (excludedTradeId == null) return snapshot.dollarDelta();
        List<TradeRecord> active = snapshot.activeTrades().stream()
                .filter(trade -> !excludedTradeId.equals(trade.id())).toList();
        return aggregateDollarDelta(active, snapshot.marksByTrade());
    }

    private static DollarDeltaBook aggregateDollarDelta(
            List<TradeRecord> active, Map<String, MarkView> marksByTrade) {
        long gross = 0, net = 0;
        Map<String, Long> bySymbol = new LinkedHashMap<>();
        Map<String, Long> byTrade = new LinkedHashMap<>();
        boolean complete = true;
        for (TradeRecord trade : active) {
            MarkView mark = marksByTrade.get(trade.id());
            if (mark == null || mark.underlyingCents() == null || mark.greeks() == null) {
                complete = false;
                continue;
            }
            Long delta = GreeksAggregator.dollarDeltaCents(
                    mark.greeks(), mark.underlyingCents());
            if (delta == null) {
                complete = false;
                continue;
            }
            long magnitude = safeAbsolute(delta);
            gross = Math.addExact(gross, magnitude);
            net = Math.addExact(net, delta);
            bySymbol.merge(Symbol.normalize(trade.symbol()), magnitude, Math::addExact);
            byTrade.put(trade.id(), delta);
        }
        return new DollarDeltaBook(gross, net, Map.copyOf(bySymbol), Map.copyOf(byTrade), complete,
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
            params.add(Symbol.normalize(symbol));
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
        return db.query("SELECT ts::text ts,current_mark_json::text current_mark_json "
                        + "FROM trade_marks WHERE trade_id=? ORDER BY id DESC LIMIT ?",
                r -> historicalMark(tradeId, r.str("ts"), r.str("current_mark_json")),
                tradeId, limit);
    }

    private static MarkView historicalMark(String tradeId, String ts, String currentMarkJson) {
        MarkView exact = Json.read(currentMarkJson, MarkView.class);
        if (!Objects.equals(tradeId, exact.tradeId()) || !Objects.equals(ts, exact.ts())) {
            throw new IllegalStateException(
                    "stored current result identity does not match its mark row");
        }
        return exact;
    }

    public Excursion excursion(String tradeId) {
        return db.query("SELECT MIN(COALESCE((current_mark_json->>'decisionUnrealizedCents')::bigint, "
                        + "(current_mark_json->>'unrealizedCents')::bigint)) adverse, "
                        + "MAX(COALESCE((current_mark_json->>'decisionUnrealizedCents')::bigint, "
                        + "(current_mark_json->>'unrealizedCents')::bigint)) favorable "
                        + "FROM trade_marks WHERE trade_id=?",
                row -> new Excursion(row.lngOrNull("adverse"), row.lngOrNull("favorable")), tradeId)
                .stream().findFirst().orElse(new Excursion(null, null));
    }

    // ---- Plan computation ----

    // underlyingCents is a NULLABLE Long, not a primitive: a plan refused before a mode-owned
    // underlying mark existed has no spot, and 0 would be a $0.00 stock (§3.2/§3.3). Every priced
    // path below passes Money.toCents(underlying) on a non-null underlying; only the pre-pricing
    // refusals pass null, and their reason is already in `blocks`.
    private record Plan(List<Leg> filledLegs, long entryNet, long fees, Long reserve, Long maxLoss,
                        Long maxProfit, List<String> breakevens,
                        io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.RiskNeutralAnalysis marketImpliedRisk,
                        MarketImpliedRange marketImpliedRange,
                        Long underlyingCents,
                        DataEvidence evidence, List<String> blocks, List<String> warnings, String snapshotJson,
                        long sharesToLock, List<LegView> legDetails, Double shortSideExpirationItmProb,
                        List<Map<String, Object>> payoff, Map<String, Object> analytics,
                        PackagePrice price) {
        Plan {
            Objects.requireNonNull(marketImpliedRisk, "market-implied risk result is required");
            boolean maxLossKnown = maxLoss != null;
            boolean reserveKnown = reserve != null;
            if (maxLossKnown != reserveKnown) {
                throw new IllegalArgumentException(
                        "plan maximum loss and reserve must share availability");
            }
            if (maxLossKnown && (maxLoss < 0 || reserve < 0)) {
                throw new IllegalArgumentException("plan maximum loss and reserve cannot be negative");
            }
            if ((blocks == null || blocks.isEmpty()) && !maxLossKnown) {
                throw new IllegalArgumentException(
                        "an otherwise eligible plan requires maximum-loss and reserve results");
            }
        }
    }

    private static io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.RiskNeutralAnalysis
    unavailableMarketRisk(String reason) {
        return io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.RiskNeutralAnalysis.unavailable(reason);
    }

    /**
     * Accepted placement and funded-analysis paths require a complete risk result. Refused
     * previews are allowed to carry null; reaching this gate with null and no block would mean a
     * calculator silently lost a required fact, so fail closed rather than converting it to zero.
     */
    private static long requiredRiskFact(Long value, String fact) {
        if (value == null) {
            throw new IllegalStateException("An otherwise eligible package is missing its " + fact
                    + " result; placement is refused.");
        }
        if (value < 0) {
            throw new IllegalStateException("A package " + fact + " result cannot be negative.");
        }
        return value;
    }

    /** Recorded broker fills are facts; paper orders may not claim a favorable limit filled. */
    private Plan computePlan(OpenRequest req, boolean recordedFill,
                             String selectedWorld, boolean worldWasSupplied,
                             io.liftandshift.strikebench.market.MarketMode requiredMarketMode,
                             boolean analysisOnly) {
        List<String> blocks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (req.qty() < 1) blocks.add("Quantity must be at least 1");
        if (req.qty() > 1_000_000) blocks.add("Quantity exceeds the analysis arithmetic limit");
        else if (req.qty() > 100 && !analysisOnly) blocks.add("Quantity exceeds the 100-contract practice cap");
        if (!recordedFill && !analysisOnly && req.orderInstruction() == null) {
            blocks.add("An explicit MARKET or signed LIMIT instruction is required for placement");
        }
        if (req.legs() == null || req.legs().isEmpty()) blocks.add("At least one leg is required");
        if (!blocks.isEmpty()) {
            // Refused before any market was consulted: there is no spot to report, so report none.
            return new Plan(List.of(), 0, 0, null, null, null, List.of(),
                    unavailableMarketRisk("Market evidence was not consulted for this refused package."),
                    null, null, DataEvidence.missing("market evidence not consulted"), blocks, warnings,
                    "{}", 0, List.of(), null, List.of(), Map.of(),
                    unpricedPackage(req, blocks));
        }

        String world = worldWasSupplied ? selectedWorld : worldOf(req.accountId());
        var mode = requiredMarketMode == null ? marketModeFor(world) : requiredMarketMode;
        Quote underlyingQuote = marks.underlyingQuote(req.symbol(), world).orElse(null);
        BigDecimal underlying = underlyingQuote == null ? null : underlyingQuote.mark();
        var underlyingEvidence = underlyingQuote == null ? null : underlyingQuote.evidence();
        Long underlyingObservedAt = underlyingQuote == null || underlyingQuote.asOfEpochMs() <= 0
                ? null : underlyingQuote.asOfEpochMs();
        if (underlying == null) blocks.add("No current price for " + req.symbol());
        if (underlyingEvidence != null && !underlyingEvidence.executableIn(mode)) {
            String unavailable = "This is not a live " + mode + " execution for " + req.symbol()
                    + ": the underlying data is " + underlyingEvidence.provenance() + " ("
                    + underlyingEvidence.source() + ", " + underlyingEvidence.age() + ").";
            if (underlying != null) {
                // Advice and PAPER placement both run on available data. A closed or stale market
                // warns and stamps — the fill uses the last captured book, the entry carries this
                // exact provenance and age, and the position re-tests against fresh data on the
                // next market update. Only genuine absence (no price at all) still blocks above.
                warnings.add(unavailable + (analysisOnly
                        ? " ANALYZE uses it as labeled, non-executable evidence."
                        : " The paper fill uses the last captured book; the entry is stamped with"
                                + " this provenance and re-tested at the next market update."));
            } else {
                blocks.add(unavailable + " No usable price is available at all.");
            }
        }

        // ONE CLOCK PER MARKET: a world trade's expiry gates, session warnings and DTE all run on
        // the SIM clock (the clock that priced the chain) — never the JVM's (adversarial review P0).
        java.time.Instant nowInstant = nowFor(world);
        for (Leg leg : req.legs()) {
            if (!leg.isStock() && io.liftandshift.strikebench.market.MarketHours.contractDead(leg.expiration(), nowInstant)) {
                blocks.add("Leg " + legDesc(leg) + " is already expired — contracts die at 4:00pm ET on their expiration day; "
                        + "you cannot open a position in a dead contract");
            }
        }
        if (!blocks.isEmpty()) {
            // A dead leg or a non-executable mode refuses the package even when the spot IS known;
            // report the spot we actually have and null only when there truly is none.
            return new Plan(List.of(), 0, 0, null, null, null, List.of(),
                    unavailableMarketRisk("The package was refused before market-implied analysis."),
                    null, underlying == null ? null : Money.toCents(underlying),
                    DataEvidence.missing("package market evidence"), blocks, warnings,
                    "{}", 0, List.of(), null, List.of(), Map.of(),
                    unpricedPackage(req, blocks));
        }
        if (io.liftandshift.strikebench.market.MarketMode.isObservedWorld(world)
                && !io.liftandshift.strikebench.market.MarketHours.isRegularSession(nowInstant)) {
            warnings.add("Market is closed — quotes are leftovers from the last session and paper fills are simulated");
        }

        // Capture every leg mark once, then let the one package pricer choose executable sides,
        // analysis-only midpoints, aggregate evidence, and the package's natural result. The
        // remaining code owns only the genuinely different customer-price policies (recorded
        // fills and resting limits); it no longer carries another bid/ask calculator.
        List<MarksSource.LegMark> capturedMarks = new ArrayList<>(req.legs().size());
        List<ExecutablePackagePricer.LegBook> priceInputs = new ArrayList<>(req.legs().size());
        for (Leg leg : req.legs()) {
            MarksSource.LegMark mark = marks.legMark(req.symbol(), leg, world).orElse(null);
            capturedMarks.add(mark);
            priceInputs.add(ExecutablePackagePricer.LegBook.fromLegMark(leg, mark));
        }
        ExecutablePackagePricer.Book currentBook = ExecutablePackagePricer.price(priceInputs, mode,
                analysisOnly ? ExecutablePackagePricer.Policy.ANALYSIS
                        : ExecutablePackagePricer.Policy.EXECUTABLE_ONLY);

        List<Leg> filled = new ArrayList<>();
        List<Leg> marketFilled = new ArrayList<>();
        List<LegView> snapshotLegs = new ArrayList<>();
        boolean executableBook = true;
        DataEvidence worst = underlyingEvidence == null
                ? DataEvidence.missing("underlying quote") : underlyingEvidence;
        List<Double> ivs = new ArrayList<>();
        List<Double> legIvs = new ArrayList<>(); // index-aligned with filled (nulls kept)
        int suppliedEntryPrices = 0;
        int modeledEntryPrices = 0;
        // Legs whose price IS the midpoint of a one-sided/dead book. Counted separately from
        // modeledEntryPrices (which also counts perfectly two-sided marks that simply are not
        // executable in this mode) because it is the fact that decides MID_MARKET vs MODELED.
        int midPricedLegs = 0;
        for (int legIndex = 0; legIndex < req.legs().size(); legIndex++) {
            Leg leg = req.legs().get(legIndex);
            boolean suppliedEntryPrice = req.explicitFillMeaning()
                    && leg.entryPrice() != null && leg.entryPrice().signum() > 0;
            MarksSource.LegMark mark = capturedMarks.get(legIndex);
            ExecutablePackagePricer.LegPrice legPrice = currentBook.legPrices().get(legIndex);
            if (mark == null || (legPrice.selected() == null && !suppliedEntryPrice)) {
                executableBook = false;
                if (suppliedEntryPrice) {
                    suppliedEntryPrices++;
                    Leg filledLeg = new Leg(leg.action(), leg.type(), leg.strike(), leg.expiration(),
                            leg.ratio(), leg.entryPrice(), leg.multiplier());
                    filled.add(filledLeg);
                    legIvs.add(null);
                    worst = worse(worst, DataEvidence.missing("entered leg price has no current mark"));
                    snapshotLegs.add(new LegView(leg.action().name(),
                            leg.isStock() ? "STOCK" : leg.type().name(),
                            leg.isStock() || leg.strike() == null ? null
                                    : leg.strike().stripTrailingZeros().toPlainString(),
                            leg.expiration() == null ? null : leg.expiration().toString(),
                            leg.ratio(), leg.entryPrice().toPlainString(), leg.multiplier(), "OPEN",
                            null, null, null, "entered leg price",
                            DataEvidence.missing("entered leg price").label(), null, null, null,
                            req.executedFill() ? "USER_EXECUTED" : "USER_PROPOSED",
                            req.executedFill() ? "BROKER" : "USER_INPUT", "UNKNOWN",
                            null, null, null));
                    warnings.add("No current mark for " + legDesc(leg) + "; its entered "
                            + (req.executedFill() ? "executed fill" : "proposed price")
                            + " drives entry economics while volatility-dependent outputs use explicitly incomplete evidence.");
                    continue;
                }
                blocks.add((legPrice.unavailableReason() == null
                                ? "No market or model mark for " + legDesc(leg)
                                : legPrice.unavailableReason())
                        + " Enter a proposed/executed leg price or choose a contract with available evidence.");
                continue;
            }
            if (!mark.evidence().executableIn(mode)) {
                executableBook = false;
                String unavailable = "Leg " + legDesc(leg) + " is not live in the " + mode
                        + " market: its book is " + mark.evidence().provenance() + " ("
                        + mark.evidence().source() + ", " + mark.evidence().age() + ").";
                // Same behavior as the underlying: warn and stamp, never stop a paper fill on a
                // book that exists. The result's valuation basis and the order's executability
                // already carry executableBook=false, so nothing downstream mistakes this for a
                // live execution. A leg with NO usable side still blocks below.
                warnings.add(unavailable + (analysisOnly
                        ? " ANALYZE uses the labeled midpoint/model only; it is not a fill claim."
                        : " The paper fill uses this last captured side and the entry is stamped"
                                + " with its provenance and age."));
            }
            // Fill realism and mode eligibility come from ExecutablePackagePricer. A one-sided,
            // crossed, stale, or wrong-mode book cannot acquire a local fill convention here.
            BigDecimal executableFill = legPrice.executable() == null
                    ? null : legPrice.executable().entryPrice();
            BigDecimal marketPrice = legPrice.selected() == null
                    ? null : legPrice.selected().entryPrice();
            if (marketPrice == null) {
                executableBook = false;
                if (!analysisOnly) {
                    blocks.add("No executable " + (leg.action() == LegAction.BUY ? "ask" : "bid") + " for "
                            + legDesc(leg) + " — the book is one-sided, empty, or crossed; this leg cannot actually be traded");
                    continue;
                }
            }
            if (analysisOnly && legPrice.selectedAtMidpoint()) {
                modeledEntryPrices++;
                if (!suppliedEntryPrice) midPricedLegs++;
                warnings.add("No executable " + (leg.action() == LegAction.BUY ? "ask" : "bid") + " for "
                        + legDesc(leg) + "; ANALYZE uses the labeled midpoint and does not claim a fill.");
            }
            // Quote integrity: an option marked below intrinsic value against the SAME feed's
            // underlying is impossible — it is a stale or expired quote, not an opportunity.
            if (!leg.isStock() && underlying != null) {
                BigDecimal intrinsic = leg.intrinsicPerShare(underlying);
                BigDecimal tolerance = intrinsic.multiply(new BigDecimal("0.02")).max(new BigDecimal("0.05"));
                if (mark.mid() != null && mark.mid().compareTo(intrinsic.subtract(tolerance)) < 0) {
                    String impossible = "Quote integrity: " + legDesc(leg) + " is marked " + mark.mid().toPlainString()
                            + " but is worth at least " + intrinsic.toPlainString() + " intrinsically vs the underlying at "
                            + underlying.toPlainString() + " — impossible price, the quote is stale or the contract is dead";
                    if (suppliedEntryPrice && req.executedFill()) warnings.add(impossible + "; preserving the entered broker fill as fact while withholding a live-execution claim.");
                    else { blocks.add(impossible); continue; }
                }
            }
            worst = worse(worst, mark.evidence());
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
            } else if (!mark.evidence().executableIn(mode) || executableFill == null) {
                modeledEntryPrices++;
            }
            Leg filledLeg = new Leg(leg.action(), leg.type(), leg.strike(), leg.expiration(),
                    leg.ratio(), fill, leg.multiplier());
            filled.add(filledLeg);
            if (legPrice.executable() != null) marketFilled.add(legPrice.executable());
            snapshotLegs.add(new LegView(leg.action().name(),
                    leg.isStock() ? "STOCK" : leg.type().name(),
                    leg.isStock() || leg.strike() == null ? null
                            : leg.strike().stripTrailingZeros().toPlainString(),
                    leg.expiration() == null ? null : leg.expiration().toString(),
                    leg.ratio(), fill.toPlainString(), leg.multiplier(), "OPEN",
                    mark.bid() == null ? null : mark.bid().toPlainString(),
                    mark.ask() == null ? null : mark.ask().toPlainString(),
                    mark.asOfEpochMs(), mark.evidence().source(), mark.evidence().label(),
                    mark.iv(), mark.delta(),
                    mark.mid() == null ? null : mark.mid().toPlainString(),
                    suppliedEntryPrice
                            ? (req.executedFill() ? "USER_EXECUTED" : "USER_PROPOSED")
                            : (mark.evidence().executableIn(mode) && executableFill != null
                                    ? "EXECUTABLE_BOOK" : "LABELED_MODEL_OR_MID"),
                    mark.evidence().provenance().name(), mark.evidence().age().name(),
                    mark.gamma(), mark.theta(), mark.vega()));
        }
        if (!blocks.isEmpty()) {
            // Leg-level refusal. `underlying` is non-null here (the null case returned above), so
            // the spot is a fact we have and must report rather than flatten to 0.
            return new Plan(List.of(), 0, 0, null, null, null, List.of(),
                    unavailableMarketRisk("A leg could not be priced, so market-implied analysis is unavailable."),
                    null, Money.toCents(underlying), worst, blocks, warnings,
                    "{}", 0, List.of(), null, List.of(), Map.of(),
                    unpricedPackage(req, blocks));
        }

        if (modeledEntryPrices > 0) {
            warnings.add(modeledEntryPrices + " leg" + (modeledEntryPrices == 1 ? "" : "s")
                    + " lack an executable side; these are analysis inputs, not executable fill claims.");
        }

        if (worst.age() == DataAge.DELAYED || worst.age() == DataAge.EOD) {
            warnings.add("Marks are " + worst.label() + " — fills use non-realtime prices");
        }

        // Held-shares coverage: the account's own shares stand in for a stock leg. Only short
        // CALLS can be covered this way; the create() transaction verifies and locks the shares.
        long coverSharesPerUnit = 0;
        if (req.heldShares()) {
            if (filled.stream().anyMatch(Leg::isStock)) {
                blocks.add("useHeldShares cannot be combined with stock legs — either buy shares inside the trade or write against shares you already hold");
                return new Plan(filled, 0, 0, null, null, null, List.of(),
                        unavailableMarketRisk("The held-share package declaration is invalid."),
                        null, Money.toCents(underlying), worst, blocks, warnings, "{}",
                        0, List.of(), null, List.of(), Map.of(), unpricedPackage(req, blocks));
            }
            coverSharesPerUnit = io.liftandshift.strikebench.strategy.CoverageCheck.callCoverSharesNeeded(filled);
            if (coverSharesPerUnit < 0) {
                blocks.add("Held shares can only cover short CALLS — this structure has uncovered short puts or short stock that shares cannot protect");
                return new Plan(filled, 0, 0, null, null, null, List.of(),
                        unavailableMarketRisk("Held shares do not cover this package."),
                        null, Money.toCents(underlying), worst, blocks, warnings, "{}",
                        0, List.of(), null, List.of(), Map.of(), unpricedPackage(req, blocks));
            }
        }
        long sharesToLock = Math.multiplyExact(coverSharesPerUnit, req.qty());

        PayoffCurve curve = PayoffCurve.of(filled, req.qty(), 0L);
        long entryNet = curve.entryNetPremiumCents();
        executableBook = executableBook && currentBook.executable()
                && marketFilled.size() == filled.size();
        Long naturalExecutableNet = currentBook.executableNetCents(req.qty());
        long executableNet = naturalExecutableNet == null ? entryNet : naturalExecutableNet;
        OrderInstruction.Executability executability = recordedFill
                ? OrderInstruction.Executability.IMMEDIATE
                : req.orderInstruction() == null
                    ? OrderInstruction.Executability.UNAVAILABLE
                    : req.orderInstruction().executability(naturalExecutableNet, executableBook);
        Long packageMid = currentBook.midpointNetCents(req.qty());

        // §7.2: the basis the package net is STATED on is decided exactly where the price is
        // decided — the branch below already knows whether it took the natural book, a recorded
        // fill or the customer's own price; until now it simply never said so, which is what let
        // the rail and the dock print different numbers with nothing to explain the difference.
        // A package priced off MARKS names its basis through the result's own rule, so this mode
        // and the scan rail cannot label the same evidence differently. It also stops calling a
        // midpoint "MODELED": the ANALYZE mode above genuinely prices one-sided legs at the
        // midpoint, which is a real, nameable basis (and left ValuationBasis.MID_MARKET assigned
        // nowhere in the product while the label on screen was wrong).
        PackagePrice.ValuationBasis valuationBasis = suppliedEntryPrices > 0
                ? (req.executedFill() ? PackagePrice.ValuationBasis.RECORDED_FILL
                        // Entered proposed prices are analysis inputs, never a fill claim.
                        : PackagePrice.ValuationBasis.MODELED)
                : PackagePrice.markBasis(executableBook, midPricedLegs > 0);

        // MARKET and marketable LIMIT orders transact at the current natural package, never at a
        // worse user bound or stale per-leg proposal. A resting limit is still useful analysis,
        // but remains blocked until the market can satisfy it. Recorded fills remain exact facts.
        long netAdjust = 0;
        boolean optionPackage = filled.stream().anyMatch(l -> !l.isStock());
        if (!recordedFill && !req.executedFill() && !analysisOnly
                && executability == OrderInstruction.Executability.IMMEDIATE
                && naturalExecutableNet != null) {
            filled = new ArrayList<>(marketFilled);
            curve = PayoffCurve.of(filled, req.qty(), 0L);
            entryNet = curve.entryNetPremiumCents();
            valuationBasis = PackagePrice.ValuationBasis.EXECUTABLE_BOOK;
            for (int i = 0; i < snapshotLegs.size() && i < filled.size(); i++) {
                String natural = filled.get(i).entryPrice().toPlainString();
                snapshotLegs.set(i, snapshotLegs.get(i).withEntryPrice(
                        natural, "EXECUTABLE_BOOK"));
            }
            if (req.orderInstruction().type() == OrderInstruction.Type.LIMIT) {
                warnings.add("Limit " + Money.fmt(req.orderInstruction().limitNetCents())
                        + " is marketable; the package is valued at the better natural executable net "
                        + Money.fmt(entryNet) + ".");
            }
        } else if (!recordedFill && req.orderInstruction() != null
                && req.orderInstruction().limitNetCents() != null && !optionPackage
                && executability == OrderInstruction.Executability.RESTING) {
            // Share orders are owned by PositionsService. Never reinterpret a resting stock LIMIT
            // as though it filled at the current ask/bid merely because this package path also
            // accepts STOCK legs for combined option structures.
            blocks.add("The stock limit " + Money.fmt(req.orderInstruction().limitNetCents())
                    + " is not presently executable. StrikeBench does not model resting share "
                    + "orders in the package mode; use the share-order flow or record the broker fill.");
        } else if (!recordedFill && req.orderInstruction() != null
                && req.orderInstruction().limitNetCents() != null && optionPackage) {
            // A nonmarketable limit is a proposed package price, not a fill. Reprice the analysis
            // without fabricating per-leg executions; recorded fills remain the exact submitted
            // leg prices and are identified only by the RECORDED_FILL result basis.
            long orderLimitNetCents = req.orderInstruction().limitNetCents();
            netAdjust = orderLimitNetCents - entryNet;
            if (netAdjust != 0) {
                curve = PayoffCurve.of(filled, req.qty(), netAdjust);
                entryNet = curve.entryNetPremiumCents();
            }
            valuationBasis = PackagePrice.ValuationBasis.RESTING_LIMIT;
            warnings.add("Analyzed at your resting limit "
                    + Money.fmt(orderLimitNetCents) + " — the executable sides right now say "
                    + (naturalExecutableNet == null ? "unavailable" : Money.fmt(naturalExecutableNet))
                    + (packageMid != null ? ", midpoint " + Money.fmt(packageMid) : ""));
            if (packageMid != null && orderLimitNetCents > packageMid) {
                warnings.add("Your price is MORE favorable than the midpoint — resting there may never fill");
            }
            if (executability == OrderInstruction.Executability.RESTING) {
                warnings.add("Your limit " + Money.fmt(orderLimitNetCents)
                        + " is more favorable than the executable market " + Money.fmt(executableNet)
                        + ". StrikeBench does not model resting limit orders, so it cannot claim this paper "
                        + "order filled. The order is RESTING and is not presently executable. "
                        + "Re-price at or below the executable net, or record the fill after it actually occurs.");
            }
        }
        if (suppliedEntryPrices > 0 && (analysisOnly || recordedFill
                || executability != OrderInstruction.Executability.IMMEDIATE)) {
            warnings.add(suppliedEntryPrices + " entered leg price" + (suppliedEntryPrices == 1 ? "" : "s")
                    + " preserved exactly; blank legs use separately labeled market/model evidence.");
        }
        if (!recordedFill && req.orderInstruction() != null
                && executability == OrderInstruction.Executability.UNAVAILABLE) {
            String message = req.orderInstruction().type() + " order is UNAVAILABLE because the complete "
                    + "package has no executable natural market.";
            if (analysisOnly) warnings.add(message + " The labeled analysis can still be inspected.");
            else blocks.add(message + " Refresh the quote before placing it.");
        }

        // The credit/debit BASIS the management protocol is measured on is option-only: a
        // buy-write's package net is negative because of the shares, and stopping at "~50% of the
        // debit paid" there would mean half the share purchase (§7.5). Computed AFTER the package
        // net is finalized, and carrying the package-level net adjustment — which only ever prices
        // option premium (the branch above requires an option package; stock fills are exact).
        long optionEntryNet = ProtocolEvaluator.optionEntryBasisCents(filled, req.qty(), entryNet)
                + (filled.stream().anyMatch(Leg::isStock) ? netAdjust : 0);
        // The stock side is measured from the stock legs themselves, NOT as "package net less the
        // option net". Share fills are exact — a customer net override only ever reprices option
        // premium (the override branch above requires an option package), so the shares are the one
        // part of a buy-write that never absorbs the difference. Measuring it independently is what
        // gives the result's additive identity something real to catch.
        long stockCashFlow = ProtocolEvaluator.stockEntryBasisCents(filled, req.qty());

        // Commission is part of a priced package, not a reward for passing the mechanical gates
        // below. A package can be perfectly priceable and still be refused for undefined risk,
        // stale mechanics, or a $0-loss impossibility. Computing the fee here keeps those exits
        // from publishing a priced result with an invented "unknown" commission while the
        // normal path publishes the same package with a known commission.
        Fees.Schedule feeSchedule = req.feesOverrideCents() != null
                ? new Fees.Schedule(req.feesOverrideCents(), req.feesOverrideCents(),
                        Math.multiplyExact(2L, req.feesOverrideCents()))
                : feeScheduleFor(filled, req.qty());
        long openingFees = feeSchedule.openingCents();

        // THE package-price result (§7.2). Everything it needs is settled above, so every exit
        // from here down publishes the same amounts on the same stated basis. `naturalExecutableNet`
        // — not `executableNet` — is what travels: the latter silently falls back to the recorded
        // net on a one-sided book, and a field named "executable" must never carry a price nobody
        // can trade on. The opening commission is known once the filled package and quantity are
        // known, regardless of whether a later risk gate allows placement.
        Long packageObservedAt = packageObservedAt(snapshotLegs, underlyingObservedAt);
        Long currentBookNet = currentBook.grossNetCents(req.qty());
        boolean currentBookOwnsFinalPrice = netAdjust == 0
                && currentBookNet != null && currentBookNet == entryNet
                && currentBook.priced() && !recordedFill
                && (suppliedEntryPrices == 0
                    || valuationBasis == PackagePrice.ValuationBasis.EXECUTABLE_BOOK);
        PackagePrice price;
        if (currentBookOwnsFinalPrice) {
            price = currentBook.packagePrice(req.qty(), feeSchedule,
                    PackagePrice.FeeSide.OPENING, req.orderInstruction());
            if (price.valuationBasis() != valuationBasis
                    || price.executability() != executability
                    || !Objects.equals(price.executableNetCents(), naturalExecutableNet)) {
                throw new IllegalStateException(
                        "ticket package policy disagrees with the current executable package price");
            }
        } else {
            // Recorded fills and resting limits are not current-book prices. They keep their
            // distinct typed basis, while the executable comparison inside the result still
            // comes from the shared book above.
            price = PackagePrice.of(req.qty(), entryNet, optionEntryNet,
                    stockCashFlow,
                    openingFees, feeSchedule.roundTripCents(),
                    PackagePrice.FeeSide.OPENING, naturalExecutableNet,
                    req.orderInstruction(), executability, valuationBasis,
                    packageSource(snapshotLegs), worst.label(), packageObservedAt,
                    PackagePrice.fingerprintOf(filled, req.qty(), entryNet, valuationBasis,
                            packageObservedAt));
        }

        io.liftandshift.strikebench.market.OptionTime.Measure tte =
                io.liftandshift.strikebench.market.OptionTime.nearest(filled, nowInstant);
        int rateDays = (int) Math.max(1, tte.calendarDays());
        double rfr = marks.riskFreeRate(rateDays, world);
        io.liftandshift.strikebench.model.DataEvidence rateEvidence =
                marks.riskFreeRateEvidence(rateDays, world);

        // Short-side expiration-ITM odds belong to the same captured-IV, mode-clock risk-neutral
        // owner as every other option probability. This is not early-assignment probability.
        // Missing selected-leg IV stays unavailable.
        Double shortSideExpirationItmProb =
                io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.shortSideExpirationItmProbability(
                        filled, legIvs, Money.toCents(underlying), nowInstant, rfr);

        // Calendars/diagonals: the expiration payoff curve is meaningless across mixed
        // expirations. Debit versions risk exactly the debit; credit versions can carry
        // undefined risk once the near leg expires, so they are blocked.
        boolean mixedExpirations = filled.stream().filter(l -> !l.isStock())
                .map(Leg::expiration).distinct().count() > 1;
        if (mixedExpirations) {
            if (entryNet >= 0) {
                blocks.add("Multi-expiration credit positions can carry undefined risk after the near leg expires; blocked");
                return new Plan(filled, entryNet, openingFees, null, null, null, List.of(),
                        unavailableMarketRisk("Multi-expiration credit risk is undefined."),
                        null, Money.toCents(underlying), worst, blocks, warnings, "{}",
                        0, List.of(), null, List.of(), Map.of(), price);
            }
            // A net debit does NOT prove defined risk: a short leg that outlives (or out-strikes)
            // its cover has unlimited downside the single-expiration curve cannot see.
            List<String> uncovered = io.liftandshift.strikebench.strategy.CoverageCheck
                    .uncoveredShortsWithHeldShares(filled, coverSharesPerUnit);
            if (!uncovered.isEmpty()) {
                blocks.addAll(uncovered);
                return new Plan(filled, entryNet, openingFees, null, null, null, List.of(),
                        unavailableMarketRisk("The multi-expiration package has uncovered short risk."),
                        null, Money.toCents(underlying), worst, blocks, warnings, "{}",
                        0, List.of(), null, List.of(), Map.of(), price);
            }
            long maxLossCal = -entryNet;
            warnings.add("Calendar/diagonal position: max profit and probability of profit depend on future volatility and are not shown");
            Map<String, Object> snapCal = new LinkedHashMap<>();
            snapCal.put("underlying", underlying.toPlainString());
            snapCal.put("freshness", worst.label());
            snapCal.put("asOf", now());
            if (io.liftandshift.strikebench.market.MarketMode.isSimulatedWorld(world)) snapCal.put("marketTime", java.time.LocalDateTime.ofInstant(
                    nowInstant, io.liftandshift.strikebench.market.MarketHours.EASTERN).toString());
            snapCal.put("legs", snapshotLegs);
            return new Plan(filled, entryNet, openingFees, 0L, maxLossCal, null, List.of(),
                    unavailableMarketRisk("Multi-expiration probability and expected value require a future volatility surface."),
                    null, Money.toCents(underlying), worst, blocks, warnings, Json.write(snapCal), sharesToLock,
                    snapshotLegs, shortSideExpirationItmProb, List.of(), Map.of(), price);
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
        if (ivs.isEmpty()) {
            warnings.add("No implied volatility available — POP/EV are unavailable for this exact package");
        }
        double ivAvg = ivs.isEmpty() ? FALLBACK_IV : ivs.stream().mapToDouble(Double::doubleValue).average().orElse(FALLBACK_IV);
        // The package carries the SimulationEngine result itself. TradeService no longer
        // reconstructs the same lognormal range into an analytics map. FALLBACK_IV is useful for
        // explicitly modeled mechanics elsewhere, but it is not captured market evidence and
        // therefore cannot manufacture an options-implied range.
        MarketImpliedRange marketImpliedRange = !ivs.isEmpty() && tte.hasModelTime()
                && tte.expiration() != null
                ? MarketImpliedRange.forListedExpiry(spot, ivAvg, tte, rfr)
                : null;
        MarketImpliedRange oneSessionMarketImpliedRange =
                marketImpliedRange == null ? null
                        : MarketImpliedRange.forScenarioHorizon(spot, ivAvg,
                                new io.liftandshift.strikebench.pricing.ExpectedMove.ScenarioHorizon(1),
                                tte.expiration().toString(),
                                Math.toIntExact(tte.calendarDays()), rfr);

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
            // §3.1: the one-session expected move comes from the ONE owner of that fact, the
            // normalized risk-neutral range — not from a local spot·iv·√(1/252) linearization that
            // happened to sit next to it. Null when IV/spot cannot support a range, and then this
            // warning simply is not made rather than being made against a zero-width move.
            Double emOneSession = oneSessionMarketImpliedRange == null
                    ? null : oneSessionMarketImpliedRange.halfWidth();
            for (java.math.BigDecimal k : emOneSession == null ? List.<java.math.BigDecimal>of() : shortStrikes) {
                if (Math.abs(k.doubleValue() - spot) <= emOneSession) {
                    warnings.add("Pin/assignment risk: short strike " + k.stripTrailingZeros().toPlainString()
                            + " sits INSIDE the one-session expected move (~" + Money.fmt(Math.round(emOneSession * 100))
                            + ") — plan the exit before the final hour, and expect assignment mechanics if it finishes near the strike");
                    break;
                }
            }
        }

        io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.RiskNeutralAnalysis marketImpliedRisk;
        if (ivs.isEmpty()) {
            marketImpliedRisk = io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.RiskNeutralAnalysis.unavailable(
                    "No implied volatility was captured for this exact package.");
        } else if (!tte.hasModelTime()) {
            marketImpliedRisk = io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.RiskNeutralAnalysis.unavailable(
                    "This package has no live option model clock (" + tte.state()
                            + "); no probability or expected value was inferred.");
        } else {
            marketImpliedRisk = io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.analyze(
                    riskCurve, price, Money.toCents(underlying), ivAvg, tte, rfr, shortStrikes);
        }

        if (riskCurve.maxLossUnbounded()) {
            blocks.add("Undefined (unlimited) risk: this position can lose more than any amount reserved. Add a protective leg to cap the loss.");
            Map<String, Object> analyticsBlocked = buildAnalytics(riskCurve,
                    tte, marketImpliedRisk, shortStrikes,
                    snapshotLegs, req.qty(),
                    shareContext ? Math.multiplyExact(contextSharesPerUnit, req.qty()) : 0,
                    entryNet, optionEntryNet, packageMid,
                    feeSchedule.roundTripCents(), null, null, worst,
                    underlyingObservedAt,
                    rfr, rateEvidence);
            return new Plan(filled, entryNet, openingFees, null, null, null, List.of(),
                    marketImpliedRisk, marketImpliedRange,
                    Money.toCents(underlying),
                    worst, blocks, warnings, "{}", 0, snapshotLegs,
                    shortSideExpirationItmProb, payoff, analyticsBlocked, price);
        }
        long combinedMaxLoss = riskCurve.maxLossCents();
        if (combinedMaxLoss <= 0 && !shareContext) {
            blocks.add("Computed max loss is $0.00 — a risk-free position does not exist in real markets. "
                    + "The quotes feeding this trade are unreliable (stale, crossed, or expired book); refusing to fill.");
            return new Plan(filled, entryNet, openingFees, null, null, null, List.of(),
                    unavailableMarketRisk("The package's zero-loss result failed market-integrity checks."),
                    null, Money.toCents(underlying), worst, blocks, warnings, "{}", 0, snapshotLegs,
                    shortSideExpirationItmProb, payoff, Map.of(), price);
        }
        long heldSharePutObligation = shareContext
                ? io.liftandshift.strikebench.strategy.CapitalRequirement
                        .heldSharePutObligationCents(filled, req.qty()) : 0L;
        long maxLoss = shareContext
                ? Math.max(0L, Math.subtractExact(heldSharePutObligation, entryNet))
                : combinedMaxLoss;
        Long maxProfit = riskCurve.maxProfitUnbounded() ? null : riskCurve.maxProfitCents();
        long reserve = shareContext && heldSharePutObligation > 0
                ? heldSharePutObligation
                : io.liftandshift.strikebench.strategy.CapitalRequirement.reserveCents(
                        maxLoss, entryNet, shareContext);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("underlying", underlying.toPlainString());
        snapshot.put("freshness", worst.label());
        snapshot.put("rateEvidence", rateEvidence);
        snapshot.put("asOf", now());
        snapshot.put("executability", executability.name());
        // DUAL TIMESTAMPS for world trades: wall time above, the SIMULATED clock here — a session
        // report must place each decision on the mode's own clock (weekend-handoff M9).
        if (io.liftandshift.strikebench.market.MarketMode.isSimulatedWorld(world)) snapshot.put("marketTime", java.time.LocalDateTime.ofInstant(
                nowInstant, io.liftandshift.strikebench.market.MarketHours.EASTERN).toString());
        snapshot.put("legs", snapshotLegs);
        if (req.feesOverrideCents() != null && !req.executedFill()) {
            snapshot.put("feeOverridePerSideCents", openingFees);
        }
        if (shareCovered) snapshot.put("coveredByHeldShares", sharesToLock);
        if (shareContext) snapshot.put("heldShareContextShares",
                Math.multiplyExact(contextSharesPerUnit, req.qty()));

        Map<String, Object> analytics = buildAnalytics(riskCurve, tte,
                marketImpliedRisk, shortStrikes,
                snapshotLegs, req.qty(),
                shareContext ? Math.multiplyExact(contextSharesPerUnit, req.qty()) : 0,
                entryNet, optionEntryNet, packageMid,
                feeSchedule.roundTripCents(), maxLoss, maxProfit, worst,
                underlyingObservedAt,
                rfr, rateEvidence);
        if (shareContext) analytics.put("combinedMaxLossCents", combinedMaxLoss);
        return new Plan(filled, entryNet, openingFees, reserve, maxLoss, maxProfit,
                riskCurve.breakevens().stream().map(BigDecimal::toPlainString).toList(),
                marketImpliedRisk, marketImpliedRange, Money.toCents(underlying), worst, blocks, warnings,
                Json.write(snapshot), sharesToLock,
                snapshotLegs, shortSideExpirationItmProb, payoff, analytics, price);
    }

    private static void requirePlacementInstruction(OpenRequest request) {
        if (request != null && !request.executedFill() && request.orderInstruction() == null) {
            throw new IllegalArgumentException(
                    "An explicit MARKET or signed LIMIT instruction is required for placement");
        }
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

    // ---- Package price result (program §7.2) ----

    /**
     * Rehydrates the exact recorded opening basis for every held-position consumer. TradeRecord
     * remains the immutable ledger storage; this is the sole typed wire/computation projection.
     */
    public static PackagePrice recordedEntryPrice(TradeRecord trade) {
        if (trade == null) throw new IllegalArgumentException("recorded trade is required");
        long optionNet = ProtocolEvaluator.optionEntryBasisCents(
                trade.legs(), trade.qty(), trade.entryNetPremiumCents());
        long stockCash = ProtocolEvaluator.stockEntryBasisCents(trade.legs(), trade.qty());
        long roundTripFees = Math.addExact(trade.feesOpenCents(),
                Math.max(0L, trade.feesCloseCents()));
        var basis = PackagePrice.ValuationBasis.RECORDED_FILL;
        return PackagePrice.of(trade.qty(), trade.entryNetPremiumCents(), optionNet,
                stockCash, trade.feesOpenCents(), roundTripFees,
                PackagePrice.FeeSide.OPENING, trade.entryNetPremiumCents(),
                null, OrderInstruction.Executability.IMMEDIATE, basis,
                trade.dataSource(), trade.dataAge(), null,
                PackagePrice.fingerprintOf(trade.legs(), trade.qty(),
                        trade.entryNetPremiumCents(), basis, null));
    }

    /**
     * §3.2: a package refused before it could be priced publishes NO price. The first blocking
     * reason travels with the result so the surface can say what is missing instead of printing
     * a zero that looks like a free trade.
     */
    private static PackagePrice unpricedPackage(OpenRequest req, List<String> blocks) {
        return PackagePrice.unavailable(req.qty(), PackagePrice.FeeSide.OPENING,
                blocks == null || blocks.isEmpty()
                        ? "this package was refused before it could be priced" : blocks.getFirst());
    }

    /** Package source/observation reduced from the leg snapshots by the result's own reducers. */
    private static String packageSource(List<LegView> snaps) {
        return PackagePrice.sourceOf(snaps.stream()
                .map(LegView::quoteSource).toList());
    }

    private static Long packageObservedAt(List<LegView> snaps, Long underlyingAsOf) {
        Long oldest = PackagePrice.observedAtOf(snaps.stream()
                .map(LegView::quoteAsOfEpochMs).toList());
        return oldest != null ? oldest : underlyingAsOf;
    }

    // ---- Evaluation analytics (the one pipeline every entry path shares) ----

    /** Total quoted spread across the package (ask−bid summed by exact deliverable); null if any book is one-sided. */
    private static Long packageSpreadCents(List<LegView> snaps, int qty) {
        long total = 0;
        for (LegView snap : snaps) {
            if ("STOCK".equals(snap.type())) continue;
            if (snap.quoteBid() == null || snap.quoteAsk() == null) return null;
            BigDecimal spread = new BigDecimal(snap.quoteAsk()).subtract(new BigDecimal(snap.quoteBid()));
            if (spread.signum() < 0) return null; // crossed book — not a meaningful spread
            total += Money.centsFromPrice(spread,
                    Math.multiplyExact(Math.multiplyExact((long) snap.multiplier(), snap.ratio()), qty));
        }
        return total;
    }

    /**
     * The assembled non-market-risk judgment every Review consumer shares: execution quality
     * vs the books, a DTE-aware management plan, and a server-computed verdict. Market-implied
     * probabilities, EV, sensitivity, captured inputs, and scenario masses travel exactly once
     * in {@link TradePreview#marketImpliedRisk()}.
     */
    private Map<String, Object> buildAnalytics(PayoffCurve curve,
                                               io.liftandshift.strikebench.market.OptionTime.Measure tte,
                                               io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.RiskNeutralAnalysis
                                                       marketImpliedRisk,
                                               List<BigDecimal> shortStrikes,
                                               List<LegView> snaps, int qty,
                                               long heldShareContextShares,
                                               long entryNet, long optionEntryNet,
                                               Long packageMid,
                                               long roundTripFees, Long maxLoss, Long maxProfit,
                                               DataEvidence evidence, Long sourceAsOf, double rfr,
                                               io.liftandshift.strikebench.model.DataEvidence rateEvidence) {
        Map<String, Object> out = new LinkedHashMap<>();
        var riskNeutral = marketImpliedRisk == null
                ? io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.RiskNeutralAnalysis.unavailable(
                        "No fingerprinted market-implied evaluation was supplied.")
                : marketImpliedRisk;
        var map = riskNeutral.probabilityMap();

        // Execution QUALITY, not price. Package price, executability and order type live only on
        // the normalized price result. The midpoint remains solely as the concession benchmark.
        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("midNetCents", packageMid);
        Long spread = packageSpreadCents(snaps, qty);
        exec.put("packageSpreadCents", spread);
        if (spread != null) exec.put("exitSpreadEstimateCents", spread / 2);
        if (packageMid != null) {
            // The ONE package result's stated net is already `entryNet`: executable MARKET,
            // exact RECORDED_FILL, or the typed RESTING_LIMIT according to its declared basis.
            // Re-reading an order limit here used to make this quality metric a second price
            // authority and made exact recorded fills compare as though they occurred at market.
            long concession = packageMid - entryNet;   // dollars surrendered vs midpoint (signed net)
            exec.put("concessionVsMidCents", concession);
            if (packageMid != 0) exec.put("concessionPctOfMid", round4((double) concession / Math.abs(packageMid)));
            if (maxProfit != null && maxProfit > 0) exec.put("concessionPctOfMaxProfit", round4((double) concession / maxProfit));
            if (maxLoss != null && maxLoss > 0) exec.put("concessionPctOfMaxRisk", round4((double) concession / maxLoss));
        }
        out.put("executionQuality", exec);

        // The ONE mechanical policy owner renders this (§7.5): a typed, named, fingerprinted plan
        // on the OPTION-ONLY basis and the session clock. Nothing here restates a threshold in
        // prose, and no consumer sniffs the regime out of a string any more.
        out.put("managementPlan", ProtocolEvaluator.plan(managementPolicy, optionEntryNet, tte,
                !shortStrikes.isEmpty()));
        // B5: the exact trading-sessions/calendar-days-to-expiry result (MarketHours via OptionTime),
        // the SAME time convention the ticket and outcome use — so a preview and its order agree.
        out.put("time", tte);
        // B6: package greeks in the ONE normalized unit (deltaShares, gammaSharesPerDollar,
        // thetaCentsPerDay, vegaCentsPerPoint), aggregated from the same per-leg marks already priced.
        double heldDeltaShares = snaps.stream().anyMatch(snap ->
                "STOCK".equalsIgnoreCase(snap.type()))
                ? 0 : heldShareContextShares;
        var packageGreeks = packageGreeks(snaps, qty, heldDeltaShares);
        if (packageGreeks != null) out.put("greeks", packageGreeks);
        // R3: three clocks, separately — the data's own stamp, and when WE judged it. (fetchedAt
        // collapses into sourceAsOf for feeds without a distinct source stamp.)
        out.put("sourceAsOfEpochMs", sourceAsOf);
        out.put("evaluatedAtEpochMs", tte.asOf() == null ? null : tte.asOf().toEpochMilli());
        out.put("freshness", evidence.label());
        out.put("rate", Map.of(
                "annual", rfr,
                "evidence", rateEvidence == null
                        ? io.liftandshift.strikebench.model.DataEvidence.missing("rate assumption")
                        : rateEvidence));

        // The assembled verdict: worst-triggered tier wins; the reason names the single biggest problem.
        String verdict = "favorable"; String reason = "Model odds, payoff and execution costs look reasonable together.";
        double pAny = map == null ? Double.NaN : map.pAnyProfit();
        Object concessionPct = exec.get("concessionPctOfMid");
        boolean expensive = concessionPct instanceof Double d && Math.abs(d) > 0.10;
        // EV is a terminal payoff expectation. Judge it against the same estimated round-trip
        // commissions used by EconomicAssessment and the ticket acknowledgment, not merely the
        // opening commission. Otherwise Builder, Ideas and Decide show three different numbers
        // for the same package.
        Long marketEv = riskNeutral.expectedValueCents();
        Long evAfterFees = marketEv == null ? null : Math.subtractExact(marketEv, roundTripFees);
        if (curve.maxLossUnbounded()) {
            verdict = "unfavorable"; reason = "Risk is UNDEFINED — the stress loss below is a scenario, not a cap.";
        } else if (evAfterFees == null || map == null) {
            verdict = "mixed";
            reason = "Expected value is unavailable from the captured package evidence; no $0"
                    + " expectation was substituted.";
        } else if (evAfterFees < 0 && pAny < 0.45) {
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
        } else if (evAfterFees < 0) {
            verdict = "mixed";
            reason = "Expected value is slightly negative after fees (" + Money.fmt(evAfterFees) + ") at the market's own volatility.";
        }
        out.put("verdict", verdict);
        out.put("verdictReason", reason);
        return out;
    }

    /**
     * Package greeks in the ONE normalized unit, aggregated from the per-leg marks already priced
     * for this preview (sign x per-share greek x deliverable x ratio x qty). This is the same
     * sign/deliverable convention {@link #computeMark} uses for held positions, so an idea and the
     * position it becomes report greeks identically. Never a re-pricing; null when any option leg
     * lacks a mark, so the strip stays honestly absent rather than understated.
     */
    static GreeksView packageGreeks(
            List<LegView> snaps, int qty, double heldDeltaShares) {
        if (snaps == null || snaps.isEmpty() || qty < 1) return null;
        List<GreeksAggregator.LegExposure> exposures = new ArrayList<>();
        for (LegView snap : snaps) {
            String type = snap.type();
            String action = snap.action();
            if (!"BUY".equalsIgnoreCase(action) && !"SELL".equalsIgnoreCase(action)) return null;
            int sign = "SELL".equalsIgnoreCase(action) ? -1 : 1;
            boolean stock = "STOCK".equalsIgnoreCase(type);
            exposures.add(new GreeksAggregator.LegExposure(stock, sign,
                    snap.multiplier(), snap.ratio(), qty,
                    snap.quoteDelta(), snap.quoteGamma(), snap.quoteTheta(), snap.quoteVega()));
        }
        return GreeksAggregator.aggregate(exposures, heldDeltaShares);
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
        Ledger.append(c, acct.id(), t.id(), now, cashRowType, closeValue, cash, reserved,
                t.strategy() + " x" + t.qty() + " " + closeReason.toLowerCase(java.util.Locale.ROOT));
        if (feesClose != 0) {
            cash -= feesClose;
            Ledger.append(c, acct.id(), t.id(), now, "FEE", -feesClose, cash, reserved, "close commissions");
        }
        long reserve = Ledger.outstandingReserve(c, t.id());
        if (reserve != 0) {
            reserved -= reserve;
            Ledger.append(c, acct.id(), t.id(), now, "RESERVE_RELEASE", -reserve, cash, reserved, "reserve released on close");
        }
        Db.execOn(c, "UPDATE trades SET status=?, close_reason=?, fees_close_cents=?, realized_pnl_cents=?, decision_pnl_cents=?, closed_at=?, updated_at=? WHERE id=?",
                newStatus, closeReason, closeFeesToDate, realizedToDate, decisionPnlToDate, now, now, t.id());
        AccountService.applyBalances(c, acct.id(), cash, reserved, now);
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
        return heldPayoffCurve(t).profitAtCents(underlyingClose) - t.feesOpenCents();
    }

    /**
     * The one exact held-position terminal payoff. Every trade leg is expanded to its total
     * position quantity, then the exact held-share context is added as one total-share lot. This
     * avoids the old {@code heldShares / packageQty} truncation: an imported or partially closed
     * position with a non-divisible share count keeps every share in its payoff, POP, scenario,
     * settlement, and display results.
     */
    public static PayoffCurve heldPayoffCurve(TradeRecord trade) {
        if (trade == null || trade.qty() < 1) {
            throw new IllegalArgumentException("a positive-quantity trade is required");
        }
        List<Leg> totalLegs = new ArrayList<>(trade.legs().size() + 1);
        for (Leg leg : trade.legs()) {
            totalLegs.add(new Leg(leg.action(), leg.type(), leg.strike(), leg.expiration(),
                    Math.multiplyExact(leg.ratio(), trade.qty()), leg.entryPrice(),
                    leg.multiplier()));
        }
        long totalShares = heldShareContextShares(trade);
        if (totalShares > 0) {
            if (trade.entryUnderlyingCents() <= 0) {
                throw new IllegalStateException(
                        "The held-share payoff has no recorded entry-price anchor.");
            }
            totalLegs.add(Leg.stockShares(LegAction.BUY, Math.toIntExact(totalShares),
                    BigDecimal.valueOf(trade.entryUnderlyingCents(), 2)));
        }
        long tradedLegEntry = PayoffCurve.of(trade.legs(), trade.qty(), 0L).entryNetPremiumCents();
        long adjustment = trade.entryNetPremiumCents() - tradedLegEntry;
        return PayoffCurve.of(totalLegs, 1, adjustment);
    }

    /** Exact held-share decision context for every current result. */
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
        var mode = marketModeFor(worldOf(trade.accountId()));
        for (Leg leg : trade.legs()) {
            MarksSource.LegMark mark = marks.legMark(trade.symbol(), leg, worldOf(trade.accountId()))
                    .orElseThrow(() -> new TradeRejectedException(List.of(
                            "No current mark for leg " + legDesc(leg) + "; cannot value the close")));
            if (!mark.evidence().executableIn(mode)) {
                throw new TradeRejectedException(List.of("Cannot close " + legDesc(leg) + " in the " + mode
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
                                                    String world, Instant marketNow) {
        boolean dead = io.liftandshift.strikebench.market.MarketHours.contractDead(leg.expiration(), marketNow);
        if (action == PositionTransformation.Action.EXPIRATION && !dead) {
            throw new TradeRejectedException(List.of("This contract is still alive in its market mode until 4:00pm ET on "
                    + leg.expiration() + "."));
        }
        if (!dead) {
            var mode = marketModeFor(world);
            Quote quote = marks.underlyingQuote(trade.symbol(), world).orElse(null);
            var evidence = quote == null ? null : quote.evidence();
            if (evidence != null && !evidence.executableIn(mode)) {
                throw new TradeRejectedException(List.of("Cannot apply an early "
                        + action.name().toLowerCase(java.util.Locale.ROOT) + " in the " + mode
                        + " market using " + evidence.provenance() + " underlying data ("
                        + evidence.source() + ", " + evidence.age() + "). Refresh the mode-owned quote first."));
            }
            BigDecimal mark = quote == null ? null : quote.mark();
            if (mark == null) {
                throw new TradeRejectedException(List.of(
                        "No mode-owned underlying mark is available for this early lifecycle event."));
            }
            return new SettlementReference(mark,
                    "current " + marketModeFor(world) + " underlying mark for an early " + action.name().toLowerCase(java.util.Locale.ROOT),
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
        LocalDate today = LocalDate.ofInstant(marketNow,
                io.liftandshift.strikebench.market.MarketHours.EASTERN);
        if (!today.isAfter(leg.expiration())) {
            throw new TradeRejectedException(List.of("The expiration-day closing price is not available yet — retry after the next session"
                    + " or configure a candle source for exact settlement."));
        }
        BigDecimal fallback = marks.underlyingQuote(trade.symbol(), world)
                .map(Quote::mark)
                .orElseThrow(() -> new TradeRejectedException(List.of(
                        "No underlying price is available for the disclosed settlement fallback.")));
        return new SettlementReference(fallback,
                "expiration close unavailable — current mode mark used as a labeled fallback; value may differ from true settlement",
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
        var identity = io.liftandshift.strikebench.strategy.StrategyCatalog.identify(
                io.liftandshift.strikebench.strategy.StrategyCatalog.ClassificationRequest.draft(
                        null, symbol, normalizedQuantity(identityLots),
                        normalizedLegs(identityLots), false));
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
                    Json.stable(stable).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("cannot fingerprint option lifecycle conversion", e);
        }
    }

    static void requireExpectedLifecycle(LifecycleAssessment actual, ExpectedLifecycle expected) {
        if (actual.settlementUnderlyingCents() != expected.settlementUnderlyingCents()
                || actual.optionSettlementCashCents() != expected.optionSettlementCashCents()
                || actual.stockCashCents() != expected.stockCashCents()
                || actual.sharesDelta() != expected.sharesDelta()
                || actual.allocatedEntryBasisCents() != expected.allocatedEntryBasisCents()
                || actual.allocatedOpenFeesCents() != expected.allocatedOpenFeesCents()
                || !Objects.equals(actual.reserveAfterCents(), Long.valueOf(expected.reserveAfterCents()))
                || actual.heldShareContextAfter() != expected.heldShareContextAfter()
                || actual.sharesLockedAfter() != expected.sharesLockedAfter()
                || !Objects.equals(actual.exactStateFingerprint(), expected.exactStateFingerprint())) {
            throw new TradeRejectedException(List.of(
                    "The mode mark, option state, stock delivery, or surviving collateral changed after preview. Review the lifecycle conversion again."));
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
        int quantity = normalizedQuantity(unpriced);
        OpenRequest addedRequest = new OpenRequest(trade.accountId(), trade.symbol(), "CUSTOM", quantity,
                normalizedLegs(unpriced), trade.thesis(), trade.horizon(), trade.riskMode(), trade.intent(),
                false, null, "POSITION_TRANSFORMATION", "PROPOSED", OrderInstruction.market(), null);
        String world = worldOf(trade.accountId());
        Plan priced = computePlan(addedRequest, false, world, true, marketModeFor(world), false);
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

    private static int normalizedQuantity(List<LegLot> lots) {
        if (lots.isEmpty()) throw new IllegalArgumentException("a surviving position needs at least one leg");
        long value = 0;
        for (LegLot lot : lots) value = gcd(value, lot.quantity());
        return Math.toIntExact(value);
    }

    private static List<Leg> normalizedLegs(List<LegLot> lots) {
        int quantity = normalizedQuantity(lots);
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
        var mode = marketModeFor(world);
        for (LegLot lot : removed) {
            Leg leg = lot.leg();
            MarksSource.LegMark mark = marks.legMark(trade.symbol(), leg, world)
                    .orElseThrow(() -> new TradeRejectedException(List.of(
                            "No current mark for leg " + legDesc(leg) + "; cannot value the close")));
            if (!mark.evidence().executableIn(mode)) {
                throw new TradeRejectedException(List.of("Cannot close " + legDesc(leg) + " in the " + mode
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
        return feesFor(normalizedLegs(removed), normalizedQuantity(removed));
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
            stable.put("orderInstruction", request.orderInstruction());
            stable.put("feesOpenCents", request.feesOverrideCents());
            return HexFormat.of().formatHex(digest.digest(
                    Json.stable(stable).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("cannot fingerprint exact position", e);
        }
    }

    private static void requireExpectedAdjustment(ExpectedAdjustment expected,
                                                  long closingCash, long openingCash,
                                                  long closingFees, long openingFees,
                                                  Plan plan, long sharesAfter,
                                                  OpenRequest exactAfter) {
        long reserve = requiredRiskFact(plan.reserve(), "reserve");
        long maxLoss = requiredRiskFact(plan.maxLoss(), "maximum loss");
        if (closingCash != expected.closingCashCents()
                || openingCash != expected.openingCashCents()
                || closingFees != expected.closingFeesCents()
                || openingFees != expected.openingFeesCents()
                || plan.entryNet() != expected.entryNetCents()
                || plan.fees() != expected.feesOpenCents()
                || reserve != expected.reserveAfterCents()
                || maxLoss != expected.maxLossCents()
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

    private static OrderInstruction remainingOrderInstruction(OrderInstruction instruction,
                                                              int packageQuantity,
                                                              int removedQuantity) {
        if (instruction == null || instruction.type() == OrderInstruction.Type.MARKET) return instruction;
        return new OrderInstruction(OrderInstruction.Type.LIMIT,
                remainingAllocation(instruction.limitNetCents(), packageQuantity, removedQuantity),
                instruction.timeInForce());
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
        long reserve = requiredRiskFact(actual.reserve(), "reserve");
        long maxLoss = requiredRiskFact(actual.maxLoss(), "maximum loss");
        if (actual.entryNet() != expected.entryNetCents() || actual.fees() != expected.feesCents()
                || reserve != expected.reserveCents() || maxLoss != expected.maxLossCents()
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
            throw new IllegalStateException("Trade " + t.id() + " has an invalid current entry result.", e);
        }
    }

    /** Opening commission for this package under the app's configured fee schedule. */
    private long feesFor(List<Leg> legs, int qty) {
        return feeScheduleFor(legs, qty).openingCents();
    }

    /** One captured open/close commission schedule for an exact package and quantity. */
    private Fees.Schedule feeScheduleFor(List<Leg> legs, int qty) {
        return Fees.schedule(Fees.optionContracts(legs, qty),
                cfg.feePerContractCents(), cfg.feePerOrderCents());
    }

    /** Sign of the cash flow when CLOSING a leg: long legs are sold (+), short legs bought back (-). */
    private static int closeSign(Leg leg) {
        return leg.action() == LegAction.BUY ? 1 : -1;
    }


    /** The mode's effective clock: inside a simulated world, the WORLD's sim instant — every
     *  gate, warning, DTE and analytic for a world trade must run on the clock that priced it. */
    private java.time.Instant nowFor(String worldId) {
        return marks.simNow(worldId, clock);
    }

    private LocalDate todayFor(String worldId) {
        return LocalDate.ofInstant(nowFor(worldId), io.liftandshift.strikebench.market.MarketHours.EASTERN);
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
                r.str("intent"), r.lng("shares_locked"),
                r.str("order_instruction_json") == null ? null
                        : Json.read(r.str("order_instruction_json"), OrderInstruction.class),
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

    private static DataEvidence worse(DataEvidence a, DataEvidence b) {
        return DataEvidence.aggregate(List.of(
                a == null ? DataEvidence.missing("first market input") : a,
                b == null ? DataEvidence.missing("second market input") : b));
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
