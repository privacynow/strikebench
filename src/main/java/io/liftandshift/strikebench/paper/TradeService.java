package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.pricing.PayoffCurve;
import io.liftandshift.strikebench.util.Ids;
import io.liftandshift.strikebench.util.Json;
import io.liftandshift.strikebench.util.Money;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
     * broker integration, external-fill recording — produces exactly this typed package, and the
     * one evaluation pipeline consumes it. Package-level extras: {@code proposedNetCents} (signed:
     * + credit received / − debit paid; null = price at executable sides), {@code feesOverrideCents}
     * (null = platform default; a paper ticket treats this as the fee per side, while a recorded
     * fill treats it as the actual entry fee), {@code source} (RECOMMENDATION | BUILDER | TICKET |
     * IMPORT | BROKER).
     */
    public record OpenRequest(String accountId, String symbol, String strategy, int qty, List<Leg> legs,
                              String thesis, String horizon, String riskMode,
                              String intent, Boolean useHeldShares,
                              Long proposedNetCents, Long feesOverrideCents, String source) {
        /** Jackson and every internal caller bind one complete position contract. */
        @com.fasterxml.jackson.annotation.JsonCreator
        public OpenRequest {}
        public boolean heldShares() { return Boolean.TRUE.equals(useHeldShares); }
    }

    /** Position greeks: share-equivalent delta/gamma, $ per day theta, $ per vol-point vega. Model stats, not money. */
    public record PositionGreeks(Double deltaShares, Double gammaShares, Double thetaPerDay, Double vegaPerPoint, boolean complete) {}

    public record MarkView(String tradeId, String ts, Long underlyingCents, Long closeCostCents,
                           Long unrealizedCents, Long decisionUnrealizedCents, Double popNow, String freshness,
                           PositionGreeks greeks, List<Map<String, Object>> legGreeks) {}

    public record Page(List<TradeRecord> trades, long total, int page, int size) {}

    public record CloseResult(TradeRecord trade, long realizedPnlCents) {}

    /** Optional owner hook for workflows that must commit their identity beside the paper trade. */
    @FunctionalInterface
    public interface TransactionHook {
        void afterTradeCreated(Connection connection, TradeRecord trade) throws SQLException;
    }

    @FunctionalInterface
    public interface LifecycleHook {
        void afterMutation(Connection connection, TradeRecord trade, Long realizedPnlCents) throws SQLException;
    }

    // ---- Preview / create ----

    public TradePreview preview(OpenRequest req) {
        Account acct = db.with(c -> AccountService.get(c, req.accountId()));
        // Import/broker previews describe an actual fill that already happened. Ordinary ticket
        // previews describe a paper order and therefore cannot claim a favorable resting limit filled.
        Plan p = computePlan(req, isRecordedFillSource(req.source()));
        long cashAfter = acct.cashCents() + p.entryNet - p.fees;
        long reservedAfter = acct.reservedCents() + p.reserve;
        List<String> blocks = new ArrayList<>(p.blocks);
        if (p.blocks.isEmpty() && req.heldShares()) {
            long needed = Math.max(p.sharesToLock(), 100L * req.qty());
            long free = db.with(c -> PositionsService.heldShares(c, req.accountId(), req.symbol().toUpperCase(java.util.Locale.ROOT))
                    - PositionsService.lockedShares(c, req.accountId(), req.symbol().toUpperCase(java.util.Locale.ROOT)));
            if (free < needed) {
                blocks.add("Needs " + needed + " free shares of " + req.symbol().toUpperCase(java.util.Locale.ROOT)
                        + " but only " + Math.max(0, free) + " are free (held minus already locked)");
            }
        }
        if (p.blocks.isEmpty() && blocks.isEmpty() && cashAfter - reservedAfter < 0) {
            blocks.add("Insufficient buying power: needs " + Money.fmt(p.maxLoss + p.fees)
                    + " but only " + Money.fmt(acct.buyingPowerCents()) + " is available");
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
     * Records a REAL trade executed at a brokerage: identical evaluation (the plan runs at the
     * ACTUAL fill via proposedNetCents), identical marks/plans/resolution — but paper cash, the
     * ledger and reserves are NEVER touched. This is the learning loop's missing import path:
     * the MU condor left zero trace because only paper placements existed.
     */
    /** Execution identity for the real-fill lane: when it happened, where, and the order id. */
    public record ExternalMeta(String executedAt, String broker, String orderRef, boolean historical) {}

    public TradeRecord createExternal(OpenRequest req) {
        return createExternal(req, new ExternalMeta(null, null, null, false));
    }

    public TradeRecord createExternal(OpenRequest req, ExternalMeta meta) {
        Plan p;
        if (meta != null && meta.historical()) {
            // HISTORICAL fills: the contracts may be dead and the books gone — the user's own
            // per-leg fills ARE the record. No live validation is possible; evidence says so.
            p = planFromUserFills(req);
        } else {
            if (req.proposedNetCents() == null) {
                throw new IllegalArgumentException("recording a real trade requires proposedNetCents — the actual net fill");
            }
            p = computePlan(req, true);
            // A real fill is a FACT to record, not an order to risk-screen (CP-9/R6): entry-quality
            // blocks (undefined risk, too-good-to-be-true quotes) become loud warnings here — the
            // riskiest real trades are the ones the learning loop most needs to see. Structural
            // problems (unknown symbol, no market at all) still reject.
            List<String> hardBlocks = new ArrayList<>();
            List<String> softened = new ArrayList<>();
            for (String b : p.blocks()) {
                if (b.startsWith("Undefined (unlimited) risk") || b.startsWith("Computed max loss is $0.00")) {
                    softened.add("RECORDED WITH UNSCREENED RISK: " + b);
                } else {
                    hardBlocks.add(b);
                }
            }
            if (!softened.isEmpty() && hardBlocks.isEmpty()) {
                List<String> warns = new ArrayList<>(p.warnings());
                warns.addAll(softened);
                p = new Plan(p.filledLegs(), p.entryNet(), p.fees(), 0, p.maxLoss(), p.maxProfit(),
                        p.breakevens(), p.pop(), p.ev(), p.underlyingCents(), p.freshness(),
                        List.of(), warns, p.snapshotJson(), p.sharesToLock(), p.legDetails(),
                        p.assignmentProb(), p.payoff(), p.analytics());
            }
        }
        if (!p.blocks().isEmpty()) reject(req, p.blocks());
        String tradeId = Ids.trade();
        String now = now();
        java.time.OffsetDateTime executedAt = parseExecutedAt(meta == null ? null : meta.executedAt());
        db.exec("""
                INSERT INTO trades(id,account_id,symbol,strategy,status,qty,legs_json,thesis,horizon,risk_mode,
                  entry_underlying_cents,entry_net_premium_cents,max_loss_cents,max_profit_cents,breakevens_json,
                  pop_entry,fees_open_cents,fees_close_cents,realized_pnl_cents,close_reason,entry_snapshot_json,
                  is_live,created_at,closed_at,updated_at,intent,shares_locked,origin,
                  proposed_net_cents,executed_at,broker,order_ref,data_provenance,data_age,data_source)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,NULL,NULL,?,0,?,NULL,?,?,0,'EXTERNAL',?,?,?,?,?,?,?)""",
                tradeId, req.accountId(), req.symbol().toUpperCase(java.util.Locale.ROOT),
                req.strategy(), TradeRecord.ACTIVE, req.qty(), Json.write(p.filledLegs()),
                req.thesis(), req.horizon(), req.riskMode(),
                p.underlyingCents(), p.entryNet(), p.maxLoss(), p.maxProfit(), Json.write(p.breakevens()),
                p.pop(), p.fees(), p.snapshotJson(), now, now,
                req.intent() == null || req.intent().isBlank() ? null
                        : io.liftandshift.strikebench.strategy.StrategyIntent.parse(req.intent()).name(),
                req.proposedNetCents(), executedAt,
                meta == null ? null : meta.broker(), meta == null ? null : meta.orderRef(),
                "BROKER", io.liftandshift.strikebench.model.DataEvidence.of("broker", p.freshness()).age().name(),
                meta == null || meta.broker() == null ? "external fill" : meta.broker());
        auditSafe(req.accountId(), tradeId, "EXTERNAL_TRADE_RECORDED", "INFO", Map.of(
                "symbol", req.symbol(), "strategy", req.strategy(), "qty", req.qty(),
                "fillNetCents", p.entryNet(), "feesCents", p.fees()));
        return get(tradeId);
    }

    private static java.time.OffsetDateTime parseExecutedAt(String s) {
        if (s == null || s.isBlank()) return null;
        try { return java.time.OffsetDateTime.parse(s); } catch (RuntimeException ignored) { }
        try { return LocalDate.parse(s.trim()).atTime(16, 0).atOffset(java.time.ZoneOffset.UTC); }
        catch (RuntimeException e) { throw new IllegalArgumentException("executedAt must be an ISO date or datetime"); }
    }

    /**
     * A plan built ENTIRELY from user-supplied per-leg fills — the honest path for recording a
     * trade whose contracts have since expired. No live marks, no POP/EV (no vol), evidence
     * MISSING; undefined-risk reality is RECORDED with a loud warning, never blocked (it already
     * happened at the broker).
     */
    private Plan planFromUserFills(OpenRequest req) {
        List<String> blocks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (req.qty() < 1 || req.qty() > 100) blocks.add("Quantity must be 1..100");
        if (req.legs() == null || req.legs().isEmpty()) blocks.add("At least one leg is required");
        List<Leg> filled = new ArrayList<>();
        for (Leg leg : req.legs() == null ? List.<Leg>of() : req.legs()) {
            if (!leg.isStock() && (leg.entryPrice() == null || leg.entryPrice().signum() <= 0)) {
                blocks.add("Historical recording requires YOUR fill price on every option leg (" + legDesc(leg) + ")");
                continue;
            }
            filled.add(leg);
        }
        if (!blocks.isEmpty()) return new Plan(List.of(), 0, 0, 0, 0, null, List.of(), null, null, 0,
                Freshness.MISSING, blocks, warnings, "{}");
        long netAdjust = 0;
        PayoffCurve curve = PayoffCurve.of(filled, req.qty());
        long entryNet = curve.entryNetPremiumCents();
        if (req.proposedNetCents() != null && req.proposedNetCents() != entryNet) {
            netAdjust = req.proposedNetCents() - entryNet;
            curve = PayoffCurve.of(filled, req.qty(), netAdjust);
            entryNet = curve.entryNetPremiumCents();
            warnings.add("Package net " + Money.fmt(entryNet) + " taken from your stated total; per-leg fills sum to "
                    + Money.fmt(entryNet - netAdjust));
        }
        long maxLoss;
        Long maxProfit = curve.maxProfitUnbounded() ? null : curve.maxProfitCents();
        if (curve.maxLossUnbounded()) {
            maxLoss = 0;
            warnings.add("UNDEFINED RISK recorded as-is: this real position can lose more than any figure shown — "
                    + "max loss is stored as $0 because no cap exists");
        } else {
            maxLoss = curve.maxLossCents();
        }
        long fees = req.feesOverrideCents() != null ? Math.max(0, req.feesOverrideCents()) : feesFor(filled, req.qty());
        warnings.add("Recorded from YOUR fills — contracts were not validated against a live book (historical entry)");
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("freshness", Freshness.MISSING.name());
        snapshot.put("asOf", now());
        snapshot.put("basis", "user-supplied historical fills");
        return new Plan(filled, entryNet, fees, 0, maxLoss, maxProfit,
                curve.breakevens().stream().map(BigDecimal::toPlainString).toList(),
                null, null, 0, Freshness.MISSING, blocks, warnings, Json.write(snapshot));
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
            TradeRecord out = db.tx(c -> {
                Account acct = AccountService.getForUpdate(c, req.accountId());
                long cashAfter = acct.cashCents() + p.entryNet - p.fees;
                long reservedAfter = acct.reservedCents() + p.reserve;
                if (cashAfter - reservedAfter < 0) {
                    throw new TradeRejectedException(List.of("Insufficient buying power: needs "
                            + Money.fmt(p.maxLoss + p.fees) + " but only " + Money.fmt(acct.buyingPowerCents()) + " is available"));
                }
                String symbol = req.symbol().toUpperCase(java.util.Locale.ROOT);
                if (req.heldShares()) {
                    // Verified INSIDE the transaction so two covered trades cannot both claim
                    // the same lot; the lock lives on the trade row and dies with ACTIVE status.
                    long needed = Math.max(p.sharesToLock(), 100L * req.qty());
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
                        p.sharesToLock(), req.proposedNetCents(),
                        entryEvidence(acct.id(), p.freshness()).provenance().name(),
                        entryEvidence(acct.id(), p.freshness()).age().name(),
                        entryEvidence(acct.id(), p.freshness()).source());
                Db.execOn(c, "UPDATE accounts SET cash_cents=?, reserved_cents=?, has_traded=1, updated_at=? WHERE id=?",
                        cash, reserved, now, acct.id());
                TradeRecord created = getOn(c, tradeId);
                if (hook != null) hook.afterTradeCreated(c, created);
                return created;
            });
            auditSafe(req.accountId(), tradeId, "TRADE_OPENED", "INFO", Map.of(
                    "symbol", req.symbol(), "strategy", req.strategy(), "qty", req.qty(),
                    "entryNetPremiumCents", p.entryNet, "maxLossCents", p.maxLoss, "feesCents", p.fees));
            return out;
        } catch (TradeRejectedException e) {
            reject(req, e.reasons());
            throw e; // unreachable; reject throws
        }
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
        requireConfirm(confirm, "unwind");
        markMemo.invalidate(tradeId); // closing: never serve a pre-close mark
        accountSnapshot.invalidateAll(); // the book changed — no consumer may see the old snapshot
        CloseResult result = db.tx(c -> {
            LockedTrade locked = lockTradeAndAccount(c, tradeId, TradeRecord.ACTIVE);
            TradeRecord t = locked.trade();
            Account acct = locked.account();
            long closeValue = 0;
            Freshness worst = Freshness.REALTIME;
            var lane = laneFor(worldOf(t.accountId()));
            for (Leg leg : t.legs()) {
                MarksSource.LegMark mark = marks.legMark(t.symbol(), leg, worldOf(t.accountId()))
                        .orElseThrow(() -> new TradeRejectedException(List.of("No current mark for leg " + legDesc(leg) + "; cannot value the close")));
                if (!mark.evidence().executableIn(lane)) {
                    throw new TradeRejectedException(List.of("Cannot close " + legDesc(leg) + " in the " + lane
                            + " market using " + mark.evidence().provenance() + " data (" + mark.evidence().source()
                            + ", " + mark.evidence().age() + "). Refresh an executable quote or switch to the market that owns this data."));
                }
                worst = worse(worst, mark.freshness());
                LegAction closingAction = leg.action().opposite();
                BigDecimal px = mark.executable(closingAction);
                if (px == null) {
                    throw new TradeRejectedException(List.of("No executable " + (closingAction == LegAction.BUY ? "ask" : "bid")
                            + " to close " + legDesc(leg) + " — the book is one-sided or empty; try during market hours"));
                }
                closeValue += closeSign(leg) * Money.centsFromPrice(px, (long) Leg.SHARES_PER_CONTRACT * leg.ratio() * t.qty());
            }
            long feesClose = closeFeesFor(t);
            Long decisionUnderlying = marks.underlyingMark(t.symbol(), worldOf(t.accountId()))
                    .map(Money::toCents).orElse(null);
            CloseResult closed = closeOut(c, t, acct, "PREMIUM_CLOSE", closeValue, feesClose,
                    TradeRecord.CLOSED, "UNWIND", decisionUnderlying);
            if (hook != null) hook.afterMutation(c, closed.trade(), closed.realizedPnlCents());
            return closed;
        });
        auditSafe(result.trade().accountId(), tradeId, "TRADE_UNWOUND", "INFO",
                Map.of("realizedPnlCents", result.realizedPnlCents()));
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
                long legShares = (long) Leg.SHARES_PER_CONTRACT * leg.ratio() * t.qty();
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
            if (t.external()) {
                // Real-trade lane: cash-settle the OUTCOME onto the trade row only — the paper
                // ledger never held this money, and physical assignment belongs to the broker.
                long realizedX = t.entryNetPremiumCents() - t.feesOpenCents() + settleValue;
                Db.execOn(c, "UPDATE trades SET status=?, close_reason=?, fees_close_cents=0, realized_pnl_cents=?, decision_pnl_cents=?, closed_at=?, updated_at=? WHERE id=?",
                        TradeRecord.EXPIRED, "SETTLED (external)" + memoSuffix, realizedX, realizedX, nowTs, nowTs, t.id());
                CloseResult closed = new CloseResult(getOn(c, t.id()), realizedX);
                if (hook != null) hook.afterMutation(c, closed.trade(), closed.realizedPnlCents());
                return closed;
            }
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
            long realized = t.entryNetPremiumCents() - t.feesOpenCents() + settleValue;
            long decisionPnl = decisionPnlAtSettlement(t, closes.get(lastExpiry), realized);
            String closeReason = "SETTLED" + assignNote + memoSuffix;
            Db.execOn(c, "UPDATE trades SET status=?, close_reason=?, fees_close_cents=0, realized_pnl_cents=?, decision_pnl_cents=?, closed_at=?, updated_at=? WHERE id=?",
                    TradeRecord.EXPIRED, closeReason, realized, decisionPnl, nowTs, nowTs, t.id());
            Db.execOn(c, "UPDATE accounts SET cash_cents=?, reserved_cents=?, updated_at=? WHERE id=?", cash, reserved, nowTs, acct.id());
            CloseResult closed = new CloseResult(getOn(c, t.id()), realized);
            if (hook != null) hook.afterMutation(c, closed.trade(), closed.realizedPnlCents());
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
            if (hook != null) hook.afterMutation(c, deleted, null);
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
    private String worldOf(String accountId) {
        try {
            Account account = db.with(c -> AccountService.get(c, accountId));
            return "DEMO".equals(account.type()) ? "demo" : account.worldId();
        }
        catch (RuntimeException e) { return null; }
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
            for (TradeRecord t : list(id, TradeRecord.ACTIVE, 0, 200).trades()) {
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
        List<TradeRecord> all = list(accountId, TradeRecord.ACTIVE, 0, 200).trades();
        // EXTERNAL trades are excluded from paper-CASH arithmetic (their money lives at the
        // broker — same identity as openPositionsValue); they are counted separately so the
        // strip can label them instead of silently bending paper buying power (review P2).
        List<TradeRecord> active = all.stream().filter(t -> !t.external()).toList();
        long externalCount = all.size() - active.size();
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
                            (long) Leg.SHARES_PER_CONTRACT * l.ratio() * t.qty());
                }
            }
            long tradeReserve = reserveByTrade.getOrDefault(t.id(), 0L);
            if (cashSecuredPutAssignsPhysically(t, tradeReserve)) {
                long strikeCash = Money.centsFromPrice(t.legs().getFirst().strike(),
                        (long) Leg.SHARES_PER_CONTRACT * t.legs().getFirst().ratio() * t.qty());
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
        out.put("externalTrades", externalCount); // marked/judged elsewhere; never in paper cash math
        return out;
    }

    private static boolean cashSecuredPutAssignsPhysically(TradeRecord t, long tradeReserve) {
        if (!"CASH_SECURED_PUT".equalsIgnoreCase(t.strategy()) || t.legs().size() != 1) return false;
        Leg leg = t.legs().getFirst();
        if (leg.isStock() || leg.action() != LegAction.SELL
                || leg.type() != io.liftandshift.strikebench.model.OptionType.PUT) return false;
        long strikeCash = Money.centsFromPrice(leg.strike(),
                (long) Leg.SHARES_PER_CONTRACT * leg.ratio() * t.qty());
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
        for (Leg leg : t.legs()) {
            var mark = marks.legMark(t.symbol(), leg, world).orElse(null);
            if (mark == null) { complete = false; worst = Freshness.MISSING; break; }
            worst = worse(worst, mark.freshness());
            if (mark.iv() != null) ivs.add(mark.iv());
            // Value the close at the EXECUTABLE side (longs sell the bid, shorts pay the ask) —
            // the same price an unwind would actually get, so "unrealized" never overstates.
            BigDecimal px = mark.executable(leg.action().opposite());
            if (px == null) { complete = false; worst = Freshness.MISSING; break; }
            closeValue += closeSign(leg) * Money.centsFromPrice(px, (long) Leg.SHARES_PER_CONTRACT * leg.ratio() * t.qty());

            // Position greeks: sign x greek x 100 shares x ratio x qty (share-equiv / $-per-unit)
            double mult = closeSign(leg) * Leg.SHARES_PER_CONTRACT * (double) leg.ratio() * t.qty();
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
        PositionGreeks greeks = complete
                ? new PositionGreeks(round2(dDelta), round4(dGamma), round2(dTheta), round2(dVega), greeksComplete)
                : null;
        Long closeCost = complete ? closeValue : null;
        // Opening fees already left cash and belong in today's P/L. The only omitted cost is the
        // FUTURE close fee, which the UI labels explicitly as not yet included.
        Long unrealized = complete ? closeValue + t.entryNetPremiumCents() - t.feesOpenCents() : null;
        Long decisionUnrealized = unrealized;
        long heldContextShares = heldShareContextLots(t) * Leg.SHARES_PER_CONTRACT;
        if (decisionUnrealized != null && underlyingCents != null && heldContextShares > 0
                && t.entryUnderlyingCents() > 0) {
            decisionUnrealized += (underlyingCents - t.entryUnderlyingCents()) * heldContextShares;
        }
        boolean mixedExp = t.legs().stream().filter(l -> !l.isStock())
                .map(Leg::expiration).distinct().count() > 1;
        Double popNow = null;
        if (complete && underlyingCents != null && !mixedExp) {
            List<Leg> curveLegs = new ArrayList<>(t.legs());
            int lotsPerUnit = t.qty() > 0 ? (int) (t.sharesLocked() / ((long) Leg.SHARES_PER_CONTRACT * t.qty())) : 0;
            if (lotsPerUnit > 0) {
                // Held-share trades were risk-shaped from the ENTRY spot. Resetting the stock
                // basis to today's mark erases the move already earned/lost and makes POP jump
                // even when the package itself did not change.
                long entrySpot = t.entryUnderlyingCents() > 0 ? t.entryUnderlyingCents() : underlyingCents;
                curveLegs.add(Leg.stock(LegAction.BUY, lotsPerUnit, BigDecimal.valueOf(entrySpot, 2)));
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
    public Map<String, Object> openPositionsValue(String accountId) {
        // EXTERNAL trades are excluded from paper-money math: their cash lives at the broker,
        // so including their close value would break totalValue = cash + shares + open closes.
        List<TradeRecord> active = list(accountId, TradeRecord.ACTIVE, 0, 200).trades().stream()
                .filter(t -> !t.external()).toList();
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
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("openTradesCount", active.size());
        out.put("markedTradesCount", counted);
        out.put("valueCents", value);
        out.put("unrealizedCents", unrealized);
        out.put("complete", complete);
        out.put("freshness", worst.name());
        return out;
    }

    /** Aggregate greeks across all ACTIVE trades (Pro portfolio view). Never touches money. */    /** Aggregate greeks across all ACTIVE trades (Pro portfolio view). Never touches money. */
    public Map<String, Object> portfolioGreeks(String accountId) {
        List<TradeRecord> active = list(accountId, TradeRecord.ACTIVE, 0, 200).trades();
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

    // ---- Reads ----

    public TradeRecord get(String tradeId) {
        return db.with(c -> getOn(c, tradeId));
    }

    public Page list(String accountId, String status, int page, int size) {
        return list(accountId, status, null, null, page, size);
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

    private static boolean isRecordedFillSource(String source) {
        if (source == null) return false;
        return switch (source.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "IMPORT", "BROKER", "EXTERNAL" -> true;
            default -> false;
        };
    }

    /** Recorded broker fills are facts; paper orders may not claim a favorable limit filled. */
    private Plan computePlan(OpenRequest req, boolean recordedFill) {
        List<String> blocks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (req.qty() < 1) blocks.add("Quantity must be at least 1");
        if (req.qty() > 100) blocks.add("Quantity exceeds the 100-contract practice cap");
        if (req.legs() == null || req.legs().isEmpty()) blocks.add("At least one leg is required");
        if (!blocks.isEmpty()) return new Plan(List.of(), 0, 0, 0, 0, null, List.of(), null, null, 0, Freshness.MISSING, blocks, warnings, "{}");

        String world = worldOf(req.accountId());
        var lane = laneFor(world);
        BigDecimal underlying = marks.underlyingMark(req.symbol(), world).orElse(null);
        var underlyingEvidence = marks.underlyingEvidence(req.symbol(), world).orElse(null);
        if (underlying == null) blocks.add("No current price for " + req.symbol());
        if (underlyingEvidence != null && !underlyingEvidence.executableIn(lane)) {
            blocks.add("Cannot price " + req.symbol() + " in the " + lane + " market using "
                    + underlyingEvidence.provenance() + " underlying data (" + underlyingEvidence.source()
                    + ", " + underlyingEvidence.age() + "). Refresh an executable quote before placing the trade.");
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
        List<Map<String, Object>> snapshotLegs = new ArrayList<>();
        Freshness worst = Freshness.REALTIME;
        if (underlyingEvidence != null) worst = worse(worst, freshnessOf(underlyingEvidence));
        List<Double> ivs = new ArrayList<>();
        List<Double> legIvs = new ArrayList<>(); // index-aligned with filled (nulls kept)
        for (Leg leg : req.legs()) {
            var mark = marks.legMark(req.symbol(), leg, world).orElse(null);
            if (mark == null || mark.mid() == null) {
                blocks.add("No tradable mark for " + legDesc(leg) + " — symbol may have no listed options");
                continue;
            }
            if (!mark.evidence().executableIn(lane)) {
                blocks.add("Cannot fill " + legDesc(leg) + " in the " + lane + " market using "
                        + mark.evidence().provenance() + " data (" + mark.evidence().source() + "). "
                        + "Age: " + mark.evidence().age() + ". "
                        + "Use an observed executable quote, or explicitly enter Demo/Simulated market.");
                continue;
            }
            // Fill realism: buys pay the ask, sells receive the bid. A one-sided or empty
            // book is not executable — midpoints of dead books mint fictional money.
            BigDecimal fill = mark.executable(leg.action());
            if (fill == null) {
                blocks.add("No executable " + (leg.action() == LegAction.BUY ? "ask" : "bid") + " for "
                        + legDesc(leg) + " — the book is one-sided, empty, or crossed; this leg cannot actually be traded");
                continue;
            }
            // Quote integrity: an option marked below intrinsic value against the SAME feed's
            // underlying is impossible — it is a stale or expired quote, not an opportunity.
            if (!leg.isStock() && underlying != null) {
                BigDecimal intrinsic = leg.intrinsicPerShare(underlying);
                BigDecimal tolerance = intrinsic.multiply(new BigDecimal("0.02")).max(new BigDecimal("0.05"));
                if (mark.mid().compareTo(intrinsic.subtract(tolerance)) < 0) {
                    blocks.add("Quote integrity: " + legDesc(leg) + " is marked " + mark.mid().toPlainString()
                            + " but is worth at least " + intrinsic.toPlainString() + " intrinsically vs the underlying at "
                            + underlying.toPlainString() + " — impossible price, the quote is stale or the contract is dead");
                    continue;
                }
            }
            worst = worse(worst, mark.freshness());
            if (mark.iv() != null) ivs.add(mark.iv());
            legIvs.add(mark.iv());
            Leg filledLeg = new Leg(leg.action(), leg.type(), leg.strike(), leg.expiration(), leg.ratio(), fill);
            filled.add(filledLeg);
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("leg", legDesc(filledLeg));
            snap.put("action", leg.action().name());
            snap.put("type", leg.isStock() ? "STOCK" : leg.type().name());
            snap.put("strike", leg.isStock() || leg.strike() == null ? null : leg.strike().stripTrailingZeros().toPlainString());
            snap.put("expiration", leg.expiration() == null ? null : leg.expiration().toString());
            snap.put("ratio", leg.ratio());
            snap.put("fill", fill.toPlainString());
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

        if (worst == Freshness.DELAYED || worst == Freshness.EOD) {
            warnings.add("Marks are " + worst + " — fills use non-realtime prices");
        }

        // Held-shares coverage: the account's own shares stand in for a stock leg. Only short
        // CALLS can be covered this way; the create() transaction verifies and locks the shares.
        int coverLotsPerUnit = 0;
        if (req.heldShares()) {
            if (filled.stream().anyMatch(Leg::isStock)) {
                blocks.add("useHeldShares cannot be combined with stock legs — either buy shares inside the trade or write against shares you already hold");
                return new Plan(filled, 0, 0, 0, 0, null, List.of(), null, null, Money.toCents(underlying), worst, blocks, warnings, "{}");
            }
            coverLotsPerUnit = io.liftandshift.strikebench.strategy.CoverageCheck.callCoverLotsNeeded(filled);
            if (coverLotsPerUnit < 0) {
                blocks.add("Held shares can only cover short CALLS — this structure has uncovered short puts or short stock that shares cannot protect");
                return new Plan(filled, 0, 0, 0, 0, null, List.of(), null, null, Money.toCents(underlying), worst, blocks, warnings, "{}");
            }
        }
        long sharesToLock = (long) coverLotsPerUnit * Leg.SHARES_PER_CONTRACT * req.qty();

        PayoffCurve curve = PayoffCurve.of(filled, req.qty());
        long entryNet = curve.entryNetPremiumCents();
        long executableNet = entryNet;                       // what crossing the books pays, right now
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
            List<String> uncovered = io.liftandshift.strikebench.strategy.CoverageCheck.uncoveredShorts(filled, coverLotsPerUnit);
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
        boolean shareCovered = shareContext && coverLotsPerUnit > 0;
        int contextLots = shareContext ? Math.max(coverLotsPerUnit, 1) : 0;
        PayoffCurve riskCurve = curve;
        if (shareContext) {
            List<Leg> combined = new ArrayList<>(filled);
            combined.add(Leg.stock(LegAction.BUY, contextLots, underlying));
            riskCurve = PayoffCurve.of(combined, req.qty(), netAdjust); // YOUR price shifts this curve too
            warnings.add(shareCovered
                    ? "Covered by " + sharesToLock + " held shares (locked while this trade is open) — "
                        + "risk figures include those shares at today's price; they keep their own downside"
                    : "Risk figures include " + (100L * contextLots * req.qty()) + " held shares at today's price. "
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
        if (req.feesOverrideCents() != null && !isRecordedFillSource(req.source())) {
            snapshot.put("feeOverridePerSideCents", fees);
        }
        if (shareCovered) snapshot.put("coveredByHeldShares", sharesToLock);
        if (shareContext) snapshot.put("heldShareContextLots", (long) contextLots * req.qty());

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

    /** Signed package midpoint from the leg snapshots: SELL +, BUY −, per-share x100 x ratio x qty. */
    private static Long packageMidCents(List<Map<String, Object>> snaps, int qty) {
        long total = 0;
        for (Map<String, Object> snap : snaps) {
            Object midS = snap.get("mid");
            if (midS == null) return null;
            int sign = "SELL".equals(snap.get("action")) ? 1 : -1;
            int ratio = ((Number) snap.get("ratio")).intValue();
            long shares = "STOCK".equals(snap.get("type")) ? (long) Leg.SHARES_PER_CONTRACT * ratio
                    : (long) Leg.SHARES_PER_CONTRACT * ratio;
            total += sign * Money.centsFromPrice(new BigDecimal(midS.toString()), shares * qty);
        }
        return total;
    }

    /** Total quoted spread across the package (ask−bid summed, x100 x ratio x qty); null if any book is one-sided. */
    private static Long packageSpreadCents(List<Map<String, Object>> snaps, int qty) {
        long total = 0;
        for (Map<String, Object> snap : snaps) {
            if ("STOCK".equals(snap.get("type"))) continue;
            Object bidS = snap.get("bid"), askS = snap.get("ask");
            if (bidS == null || askS == null) return null;
            BigDecimal spread = new BigDecimal(askS.toString()).subtract(new BigDecimal(bidS.toString()));
            if (spread.signum() < 0) return null; // crossed book — not a meaningful spread
            int ratio = ((Number) snap.get("ratio")).intValue();
            total += Money.centsFromPrice(spread, (long) Leg.SHARES_PER_CONTRACT * ratio * qty);
        }
        return total;
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
        if (t.external()) {
            // A REAL trade recorded for the learning loop: the paper account never held its cash,
            // so closing writes the outcome to the trade row ONLY — no ledger, no reserve, no cash.
            long realizedX = t.entryNetPremiumCents() - t.feesOpenCents() + closeValue - feesClose;
            Db.execOn(c, "UPDATE trades SET status=?, close_reason=?, fees_close_cents=?, realized_pnl_cents=?, decision_pnl_cents=?, closed_at=?, updated_at=? WHERE id=?",
                    newStatus, closeReason, feesClose, realizedX, realizedX, now, now, t.id());
            return new CloseResult(getOn(c, t.id()), realizedX);
        }
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
        long realized = t.entryNetPremiumCents() - t.feesOpenCents() + closeValue - feesClose;
        long decisionPnl = decisionPnlAtMark(t, realized, decisionUnderlyingCents);
        Db.execOn(c, "UPDATE trades SET status=?, close_reason=?, fees_close_cents=?, realized_pnl_cents=?, decision_pnl_cents=?, closed_at=?, updated_at=? WHERE id=?",
                newStatus, closeReason, feesClose, realized, decisionPnl, now, now, t.id());
        Db.execOn(c, "UPDATE accounts SET cash_cents=?, reserved_cents=?, updated_at=? WHERE id=?", cash, reserved, now, acct.id());
        return new CloseResult(getOn(c, t.id()), realized);
    }

    private static long decisionPnlAtMark(TradeRecord t, long incrementalPnl, Long underlyingCents) {
        long shares = heldShareContextLots(t) * Leg.SHARES_PER_CONTRACT;
        if (shares <= 0 || underlyingCents == null || t.entryUnderlyingCents() <= 0) return incrementalPnl;
        return incrementalPnl + (underlyingCents - t.entryUnderlyingCents()) * shares;
    }

    private static long decisionPnlAtSettlement(TradeRecord t, BigDecimal underlyingClose, long incrementalPnl) {
        long expirations = t.legs().stream().filter(l -> !l.isStock())
                .map(Leg::expiration).distinct().count();
        if (underlyingClose == null || expirations > 1) return incrementalPnl;
        long totalLots = heldShareContextLots(t);
        List<Leg> combined = new ArrayList<>(t.legs());
        if (totalLots > 0 && t.qty() > 0 && totalLots % t.qty() == 0 && t.entryUnderlyingCents() > 0) {
            combined.add(Leg.stock(LegAction.BUY, (int) (totalLots / t.qty()),
                    BigDecimal.valueOf(t.entryUnderlyingCents(), 2)));
        }
        long tradedLegEntry = PayoffCurve.of(t.legs(), t.qty()).entryNetPremiumCents();
        long adjustment = t.entryNetPremiumCents() - tradedLegEntry;
        return PayoffCurve.of(combined, t.qty(), adjustment).profitAtCents(underlyingClose)
                - t.feesOpenCents();
    }

    private static long heldShareContextLots(TradeRecord t) {
        Long value = entrySnapshotLong(t, "heldShareContextLots");
        return value == null ? 0 : Math.max(0, value);
    }

    /** Paper what-if fees are explicitly per side; broker-import fees remain entry facts only. */
    private long closeFeesFor(TradeRecord t) {
        if (!t.external()) {
            Long override = entrySnapshotLong(t, "feeOverridePerSideCents");
            if (override != null) return Math.max(0, override);
        }
        return feesFor(t.legs(), t.qty());
    }

    private static Long entrySnapshotLong(TradeRecord t, String key) {
        if (t == null || t.entrySnapshotJson() == null || t.entrySnapshotJson().isBlank()) return null;
        try {
            Map<String, Object> snapshot = Json.read(t.entrySnapshotJson(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            Object value = snapshot.get(key);
            return value instanceof Number n ? n.longValue() : null;
        } catch (RuntimeException ignored) {
            return null; // legacy or malformed snapshot: never invent an override
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
        if (rows.isEmpty()) throw new java.util.NoSuchElementException("no such trade " + tradeId);
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
        if (rows.isEmpty()) throw new java.util.NoSuchElementException("no such trade " + tradeId);
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
                r.str("intent"), r.lng("shares_locked"), r.str("origin"),
                r.lngOrNull("proposed_net_cents"), r.str("executed_at"), r.str("broker"), r.str("order_ref"),
                r.str("data_provenance"), r.str("data_age"), r.str("data_source"));
    }

    static String legDesc(Leg leg) {
        if (leg.isStock()) return leg.action() + " " + leg.ratio() * Leg.SHARES_PER_CONTRACT + " shares";
        return leg.action() + " " + leg.ratio() + "x " + leg.strike().toPlainString() + " " + leg.type() + " " + leg.expiration();
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
