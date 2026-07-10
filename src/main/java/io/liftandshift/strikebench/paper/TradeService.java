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
import java.time.temporal.ChronoUnit;
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
     * (null = platform default), {@code source} (RECOMMENDATION | BUILDER | TICKET | IMPORT | BROKER).
     */
    public record OpenRequest(String accountId, String symbol, String strategy, int qty, List<Leg> legs,
                              String thesis, String horizon, String riskMode,
                              String intent, Boolean useHeldShares,
                              Long proposedNetCents, Long feesOverrideCents, String source) {
        /** Pre-package 10-field shape (no proposed price / fees / source). */
        public OpenRequest(String accountId, String symbol, String strategy, int qty, List<Leg> legs,
                           String thesis, String horizon, String riskMode,
                           String intent, Boolean useHeldShares) {
            this(accountId, symbol, strategy, qty, legs, thesis, horizon, riskMode, intent, useHeldShares,
                    null, null, null);
        }
        /** Historical 8-field shape (no intent, buys any stock legs explicitly). */
        public OpenRequest(String accountId, String symbol, String strategy, int qty, List<Leg> legs,
                           String thesis, String horizon, String riskMode) {
            this(accountId, symbol, strategy, qty, legs, thesis, horizon, riskMode, null, null, null, null, null);
        }
        public boolean heldShares() { return Boolean.TRUE.equals(useHeldShares); }
    }

    /** Position greeks: share-equivalent delta/gamma, $ per day theta, $ per vol-point vega. Model stats, not money. */
    public record PositionGreeks(Double deltaShares, Double gammaShares, Double thetaPerDay, Double vegaPerPoint, boolean complete) {}

    public record MarkView(String tradeId, String ts, Long underlyingCents, Long closeCostCents,
                           Long unrealizedCents, Double popNow, String freshness,
                           PositionGreeks greeks, List<Map<String, Object>> legGreeks) {}

    public record Page(List<TradeRecord> trades, long total, int page, int size) {}

    public record CloseResult(TradeRecord trade, long realizedPnlCents) {}

    // ---- Preview / create ----

    public TradePreview preview(OpenRequest req) {
        Account acct = db.with(c -> AccountService.get(c, req.accountId()));
        Plan p = computePlan(req);
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
                p.freshness.name(), p.underlyingCents, p.assignmentProb(), p.legDetails(), p.payoff());
    }

    /** Opens the trade or throws TradeRejectedException (audit row only, zero mutation). */
    public TradeRecord create(OpenRequest req) {
        Plan p = computePlan(req);
        if (!p.blocks.isEmpty()) {
            reject(req, p.blocks);
        }
        String tradeId = Ids.trade();
        try {
            TradeRecord out = db.tx(c -> {
                Account acct = AccountService.get(c, req.accountId());
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
                          is_live,created_at,closed_at,updated_at,intent,shares_locked)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,NULL,NULL,?,0,?,NULL,?,?,?)""",
                        tradeId, acct.id(), symbol, req.strategy(), TradeRecord.ACTIVE,
                        req.qty(), Json.write(p.filledLegs), req.thesis(), req.horizon(), req.riskMode(),
                        p.underlyingCents, p.entryNet, p.maxLoss, p.maxProfit, Json.write(p.breakevens),
                        p.pop, p.fees, p.snapshotJson, now, now,
                        req.intent() == null || req.intent().isBlank() ? null
                                : io.liftandshift.strikebench.strategy.StrategyIntent.parse(req.intent()).name(),
                        p.sharesToLock());
                Db.execOn(c, "UPDATE accounts SET cash_cents=?, reserved_cents=?, has_traded=1, updated_at=? WHERE id=?",
                        cash, reserved, now, acct.id());
                return getOn(c, tradeId);
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
        requireConfirm(confirm, "unwind");
        markMemo.invalidate(tradeId); // closing: never serve a pre-close mark
        CloseResult result = db.tx(c -> {
            TradeRecord t = requireStatus(c, tradeId, TradeRecord.ACTIVE);
            Account acct = AccountService.get(c, t.accountId());
            long closeValue = 0;
            Freshness worst = Freshness.FIXTURE;
            for (Leg leg : t.legs()) {
                MarksSource.LegMark mark = marks.legMark(t.symbol(), leg)
                        .orElseThrow(() -> new TradeRejectedException(List.of("No current mark for leg " + legDesc(leg) + "; cannot value the close")));
                if (!mark.freshness().tradable() && mark.freshness() != Freshness.EOD) {
                    throw new TradeRejectedException(List.of("Marks are " + mark.freshness() + " for " + legDesc(leg) + "; refresh data before closing"));
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
            long feesClose = feesFor(t.legs(), t.qty());
            return closeOut(c, t, acct, "PREMIUM_CLOSE", closeValue, feesClose, TradeRecord.CLOSED, "UNWIND");
        });
        auditSafe(result.trade().accountId(), tradeId, "TRADE_UNWOUND", "INFO",
                Map.of("realizedPnlCents", result.realizedPnlCents()));
        return result;
    }

    /** Settles an expired position at cash-equivalent intrinsic value. */
    public CloseResult settle(String tradeId, boolean confirm) {
        requireConfirm(confirm, "settle");
        markMemo.invalidate(tradeId); // closing: never serve a pre-close mark
        CloseResult result = db.tx(c -> {
            TradeRecord t = requireStatus(c, tradeId, TradeRecord.ACTIVE);
            java.time.Instant now = clock.instant();
            LocalDate today = LocalDate.now(clock);
            // A contract is settleable only after its 16:00 ET final bell — a bare date check
            // would let expiry-day positions cash out at intrinsic hours before they die.
            boolean anyAlive = t.legs().stream()
                    .anyMatch(l -> !l.isStock() && !io.liftandshift.strikebench.market.MarketHours.contractDead(l.expiration(), now));
            if (anyAlive) {
                throw new TradeRejectedException(List.of("Legs are still alive (contracts die at 4:00pm ET on expiration day); unwind instead of settling"));
            }
            Account acct = AccountService.get(c, t.accountId());
            LocalDate lastExpiry = t.legs().stream().filter(l -> !l.isStock())
                    .map(Leg::expiration).max(LocalDate::compareTo).orElse(today);
            // Each leg settles at the underlying close ON ITS OWN expiration date — valuing a
            // near leg at the far leg's expiry erases the whole inter-expiry move.
            java.util.Map<LocalDate, BigDecimal> closes = new java.util.HashMap<>();
            boolean anyCloseMissing = false;
            for (Leg leg : t.legs()) {
                LocalDate exp = leg.isStock() ? lastExpiry : leg.expiration();
                if (!closes.containsKey(exp)) {
                    closes.put(exp, marks.closeOn(t.symbol(), exp).orElse(null));
                }
                if (closes.get(exp) == null) anyCloseMissing = true;
            }
            String memoSuffix = "";
            if (anyCloseMissing) {
                // No expiration-day close available (e.g. keyless live mode has no candle
                // source). Never fabricate from an intraday gap: allow a clearly-labeled
                // fallback to the current quote only once the expiry is at least a full day old.
                if (!today.isAfter(lastExpiry)) {
                    throw new TradeRejectedException(List.of("The expiration-day closing price is not available yet — retry after the next session"
                            + " (or configure a candle source for exact settlement)"));
                }
                BigDecimal fallback = marks.underlyingMark(t.symbol())
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
            boolean cspPhysical = "CASH_SECURED_PUT".equalsIgnoreCase(t.strategy())
                    && t.legs().size() == 1
                    && t.legs().getFirst().action() == LegAction.SELL
                    && t.legs().getFirst().type() == io.liftandshift.strikebench.model.OptionType.PUT
                    && tradeReserve >= Money.centsFromPrice(t.legs().getFirst().strike(),
                            (long) Leg.SHARES_PER_CONTRACT * t.legs().getFirst().ratio() * t.qty());
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
            String closeReason = "SETTLED" + assignNote + memoSuffix;
            Db.execOn(c, "UPDATE trades SET status=?, close_reason=?, fees_close_cents=0, realized_pnl_cents=?, closed_at=?, updated_at=? WHERE id=?",
                    TradeRecord.EXPIRED, closeReason, realized, nowTs, nowTs, t.id());
            Db.execOn(c, "UPDATE accounts SET cash_cents=?, reserved_cents=?, updated_at=? WHERE id=?", cash, reserved, nowTs, acct.id());
            return new CloseResult(getOn(c, t.id()), realized);
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
        requireConfirm(confirm, "delete");
        markMemo.invalidate(tradeId); // closing: never serve a pre-close mark
        TradeRecord out = db.tx(c -> {
            TradeRecord t = requireStatus(c, tradeId, TradeRecord.ACTIVE);
            Account acct = AccountService.get(c, t.accountId());
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
            return getOn(c, tradeId);
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

    /** Recomputes marks and writes a trade_marks row. NEVER touches cash or the reserve. */
    public MarkView refresh(String tradeId) {
        TradeRecord t = get(tradeId);
        if (!TradeRecord.ACTIVE.equals(t.status())) {
            throw new IllegalStateException("trade is " + t.status() + "; only ACTIVE trades can be refreshed");
        }
        MarkView view = computeMark(t);
        markMemo.put(tradeId, view);
        db.exec("INSERT INTO trade_marks(trade_id,ts,underlying_px_cents,close_cost_cents,unrealized_cents,pop_now,freshness,detail_json) VALUES (?,?,?,?,?,?,?,?)",
                tradeId, view.ts(), view.underlyingCents(), view.closeCostCents(), view.unrealizedCents(),
                view.popNow(), view.freshness(), null);
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

    private MarkView computeMark(TradeRecord t) {
        String now = now();
        Long underlyingCents = marks.underlyingMark(t.symbol()).map(Money::toCents).orElse(null);

        long closeValue = 0;
        boolean complete = true;
        Freshness worst = Freshness.FIXTURE;
        List<Double> ivs = new ArrayList<>();
        double dDelta = 0, dGamma = 0, dTheta = 0, dVega = 0;
        boolean greeksComplete = true;
        List<Map<String, Object>> legGreeks = new ArrayList<>();
        for (Leg leg : t.legs()) {
            var mark = marks.legMark(t.symbol(), leg).orElse(null);
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
        Long unrealized = complete ? closeValue + t.entryNetPremiumCents() : null;
        boolean mixedExp = t.legs().stream().filter(l -> !l.isStock())
                .map(Leg::expiration).distinct().count() > 1;
        Double popNow = null;
        if (complete && underlyingCents != null && !mixedExp) {
            List<Leg> curveLegs = t.legs();
            int lotsPerUnit = t.qty() > 0 ? (int) (t.sharesLocked() / ((long) Leg.SHARES_PER_CONTRACT * t.qty())) : 0;
            if (lotsPerUnit > 0) {
                // Covered trades were risk-shaped WITH their locked shares at entry; marks keep
                // the same combined framing (at today's price) so POP stays comparable.
                curveLegs = new ArrayList<>(t.legs());
                curveLegs.add(Leg.stock(LegAction.BUY, lotsPerUnit, BigDecimal.valueOf(underlyingCents, 2)));
            }
            PayoffCurve curve = PayoffCurve.of(curveLegs, t.qty());
            double ivAvg = ivs.isEmpty() ? FALLBACK_IV : ivs.stream().mapToDouble(Double::doubleValue).average().orElse(FALLBACK_IV);
            popNow = curve.probProfit(underlyingCents / 100.0, ivAvg, yearsToNearestExpiry(t.legs()), 0);
        }
        return new MarkView(t.id(), now, underlyingCents, closeCost, unrealized, popNow, worst.name(),
                greeks, complete ? legGreeks : List.of());
    }

    private static Double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static Double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }

    /** Liquidation view of all ACTIVE trades: what unwinding everything now would pay
     *  (executable sides, BEFORE close fees). Sums computeMark per trade; incomplete marks
     *  make the whole answer honest-partial rather than silently wrong. */
    public Map<String, Object> openPositionsValue(String accountId) {
        List<TradeRecord> active = list(accountId, TradeRecord.ACTIVE, 0, 200).trades();
        long value = 0, unrealized = 0;
        int counted = 0;
        boolean complete = true;
        Freshness worst = Freshness.FIXTURE;
        for (TradeRecord t : active) {
            MarkView view;
            try { view = memoizedMark(t); } catch (RuntimeException e) { complete = false; continue; }
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
        double delta = 0, gamma = 0, theta = 0, vega = 0;
        boolean complete = true;
        List<Map<String, Object>> positions = new ArrayList<>();
        for (TradeRecord t : active) {
            MarkView view;
            try { view = memoizedMark(t); } catch (RuntimeException e) { complete = false; continue; }
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
                        r.lngOrNull("unrealized_cents"), r.dblOrNull("pop_now"), r.str("freshness"), null, List.of()), tradeId, limit);
    }

    // ---- Plan computation ----

    private record Plan(List<Leg> filledLegs, long entryNet, long fees, long reserve, long maxLoss,
                        Long maxProfit, List<String> breakevens, Double pop, Long ev, long underlyingCents,
                        Freshness freshness, List<String> blocks, List<String> warnings, String snapshotJson,
                        long sharesToLock, List<Map<String, Object>> legDetails, Double assignmentProb,
                        List<Map<String, Object>> payoff) {
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
        List<String> blocks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (req.qty() < 1) blocks.add("Quantity must be at least 1");
        if (req.qty() > 100) blocks.add("Quantity exceeds the 100-contract practice cap");
        if (req.legs() == null || req.legs().isEmpty()) blocks.add("At least one leg is required");
        if (!blocks.isEmpty()) return new Plan(List.of(), 0, 0, 0, 0, null, List.of(), null, null, 0, Freshness.MISSING, blocks, warnings, "{}");

        BigDecimal underlying = marks.underlyingMark(req.symbol()).orElse(null);
        if (underlying == null) blocks.add("No current price for " + req.symbol());

        java.time.Instant nowInstant = clock.instant();
        for (Leg leg : req.legs()) {
            if (!leg.isStock() && io.liftandshift.strikebench.market.MarketHours.contractDead(leg.expiration(), nowInstant)) {
                blocks.add("Leg " + legDesc(leg) + " is already expired — contracts die at 4:00pm ET on their expiration day; "
                        + "you cannot open a position in a dead contract");
            }
        }
        if (!blocks.isEmpty()) return new Plan(List.of(), 0, 0, 0, 0, null, List.of(), null, null, 0, Freshness.MISSING, blocks, warnings, "{}");
        if (!io.liftandshift.strikebench.market.MarketHours.isRegularSession(nowInstant)) {
            warnings.add("Market is closed — quotes are leftovers from the last session and paper fills are simulated");
        }

        List<Leg> filled = new ArrayList<>();
        List<Map<String, Object>> snapshotLegs = new ArrayList<>();
        Freshness worst = Freshness.FIXTURE;
        List<Double> ivs = new ArrayList<>();
        List<Double> legIvs = new ArrayList<>(); // index-aligned with filled (nulls kept)
        for (Leg leg : req.legs()) {
            var mark = marks.legMark(req.symbol(), leg).orElse(null);
            if (mark == null || mark.mid() == null) {
                blocks.add("No tradable mark for " + legDesc(leg) + " — symbol may have no listed options");
                continue;
            }
            if (!mark.freshness().tradable()) {
                blocks.add("Mark for " + legDesc(leg) + " is " + mark.freshness() + "; refusing to fill against it");
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

        // Same math the recommendation engine shows on candidates — the builder's live
        // panel must not disagree with the Ideas screen about the same structure.
        Double assignProb = io.liftandshift.strikebench.recommend.RecommendationEngine.assignmentProbabilityFromIvs(
                filled, legIvs, underlying, java.time.LocalDate.ofInstant(nowInstant, io.liftandshift.strikebench.market.MarketHours.EASTERN),
                FALLBACK_IV);

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
            riskCurve = PayoffCurve.of(combined, req.qty());
            warnings.add(shareCovered
                    ? "Covered by " + sharesToLock + " held shares (locked while this trade is open) — "
                        + "risk figures include those shares at today's price; they keep their own downside"
                    : "Risk figures include " + (100L * contextLots * req.qty()) + " held shares at today's price. "
                        + "Long options do not lock shares — selling the shares later turns this into a plain option position");
        }
        // Chart-ready payoff samples: computed even for blocked plans so the builder can
        // SHOW the cliff (an uncapped short call's curve teaches more than the block text).
        List<Map<String, Object>> payoff = chartPointMaps(riskCurve, underlying);
        if (riskCurve.maxLossUnbounded()) {
            blocks.add("Undefined (unlimited) risk: this position can lose more than any amount reserved. Add a protective leg to cap the loss.");
            return new Plan(filled, entryNet, 0, 0, 0, null, List.of(), null, null, Money.toCents(underlying),
                    worst, blocks, warnings, "{}", 0, snapshotLegs, assignProb, payoff);
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
        long fees = feesFor(filled, req.qty());
        long reserve = shareContext ? 0 : Math.max(0, maxLoss + entryNet);

        double spot = underlying.doubleValue();
        double t = yearsToNearestExpiry(filled);
        if (ivs.isEmpty()) {
            warnings.add("No implied volatility available — POP/EV assume a 30% placeholder volatility");
        }
        double ivAvg = ivs.isEmpty() ? FALLBACK_IV : ivs.stream().mapToDouble(Double::doubleValue).average().orElse(FALLBACK_IV);
        Double pop = riskCurve.probProfit(spot, ivAvg, t, 0);
        Long ev = riskCurve.expectedValueCents(spot, ivAvg, t, 0);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("underlying", underlying.toPlainString());
        snapshot.put("freshness", worst.name());
        snapshot.put("asOf", now());
        snapshot.put("legs", snapshotLegs);
        if (shareCovered) snapshot.put("coveredByHeldShares", sharesToLock);
        if (shareContext) snapshot.put("heldShareContextLots", (long) contextLots * req.qty());

        return new Plan(filled, entryNet, fees, reserve, maxLoss, maxProfit,
                riskCurve.breakevens().stream().map(BigDecimal::toPlainString).toList(),
                pop, ev, Money.toCents(underlying), worst, blocks, warnings, Json.write(snapshot), sharesToLock,
                snapshotLegs, assignProb, payoff);
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

    // ---- Shared helpers ----

    private CloseResult closeOut(Connection c, TradeRecord t, Account acct, String cashRowType,
                                 long closeValue, long feesClose, String newStatus, String closeReason) throws SQLException {
        String now = now();
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
        Db.execOn(c, "UPDATE trades SET status=?, close_reason=?, fees_close_cents=?, realized_pnl_cents=?, closed_at=?, updated_at=? WHERE id=?",
                newStatus, closeReason, feesClose, realized, now, now, t.id());
        Db.execOn(c, "UPDATE accounts SET cash_cents=?, reserved_cents=?, updated_at=? WHERE id=?", cash, reserved, now, acct.id());
        return new CloseResult(getOn(c, t.id()), realized);
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

    private double yearsToNearestExpiry(List<Leg> legs) {
        LocalDate today = LocalDate.now(clock);
        return legs.stream().filter(l -> !l.isStock())
                .mapToLong(l -> ChronoUnit.DAYS.between(today, l.expiration()))
                .min().stream()
                .mapToDouble(d -> Math.max(d, 0.5) / 365.0)
                .findFirst().orElse(0.5 / 365.0);
    }

    private TradeRecord requireStatus(Connection c, String tradeId, String expected) throws SQLException {
        TradeRecord t = getOn(c, tradeId);
        if (!expected.equals(t.status())) {
            throw new IllegalStateException("trade " + tradeId + " is " + t.status() + ", expected " + expected);
        }
        return t;
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
                r.lngOrNull("realized_pnl_cents"), r.str("close_reason"), r.str("entry_snapshot_json"),
                r.bool("is_live"), r.str("created_at"), r.str("closed_at"), r.str("updated_at"),
                r.str("intent"), r.lng("shares_locked"));
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
                    .warn("post-commit audit write failed for {} {}: {}", action, tradeId, e.toString());
        }
    }

    private String now() {
        return Instant.now(clock).toString();
    }
}
