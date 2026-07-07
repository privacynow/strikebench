package io.liftandshift.paper;

import io.liftandshift.db.Db;
import io.liftandshift.model.Leg;
import io.liftandshift.model.LegAction;
import io.liftandshift.util.Ids;
import io.liftandshift.util.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Paper equity holdings. Shares are first-class: bought at the ask, sold at the bid, held at an
 * average per-share cost basis, and usable as coverage for short calls (the trade layer locks
 * them via trades.shares_locked while a covered trade is ACTIVE). Cash moves through the same
 * append-only ledger as options (STOCK_BUY / STOCK_SELL rows); equity commissions are $0.
 *
 * Basis convention: assigned shares from a cash-secured put enter at the STRIKE (the premium was
 * already booked as option income) — this differs from the tax convention of strike-minus-premium
 * and is documented in the UI. Realized stock P/L on sells is computed against the average basis.
 */
public final class PositionsService {

    public static final long MAX_SHARES_PER_ORDER = 100_000;

    private final Db db;
    private final MarksSource marks;
    private final AuditLog audit;
    private final Clock clock;

    public PositionsService(Db db, MarksSource marks, AuditLog audit, Clock clock) {
        this.db = db;
        this.marks = marks;
        this.audit = audit;
        this.clock = clock;
    }

    public record Position(String id, String accountId, String symbol, long shares, long avgCostCents,
                           String createdAt, String updatedAt) {}

    /** Wire-friendly view with lock state and mark-to-market context. */
    public record PositionView(String symbol, long shares, long freeShares, long lockedShares,
                               long avgCostCents, Long lastCents, Long marketValueCents,
                               Long unrealizedCents, Double gainPct) {}

    public record StockTradeResult(PositionView position, long sharesTraded, long pricePerShareCents,
                                   long totalCents, Long realizedPnlCents, List<String> warnings) {}

    // ---- Reads ----

    public List<PositionView> list(String accountId) {
        List<Position> rows = db.query("SELECT * FROM positions WHERE account_id=? ORDER BY symbol",
                PositionsService::map, accountId);
        List<PositionView> out = new ArrayList<>(rows.size());
        for (Position p : rows) {
            out.add(view(p));
        }
        return out;
    }

    public PositionView get(String accountId, String symbol) {
        Position p = db.with(c -> find(c, accountId, norm(symbol)));
        if (p == null) throw new java.util.NoSuchElementException("no position in " + norm(symbol));
        return view(p);
    }

    /** Shares of a symbol not pledged as coverage to an ACTIVE trade. 0 when nothing is held. */
    public long freeShares(String accountId, String symbol) {
        return db.with(c -> {
            Position p = find(c, accountId, norm(symbol));
            return p == null ? 0L : p.shares() - lockedShares(c, accountId, norm(symbol));
        });
    }

    private PositionView view(Position p) {
        long locked = db.with(c -> lockedShares(c, p.accountId(), p.symbol()));
        Long last = marks.underlyingMark(p.symbol()).map(Money::toCents).orElse(null); // per-share cents
        Long mv = last == null ? null : last * p.shares();
        Long unreal = last == null ? null : (last - p.avgCostCents()) * p.shares();
        Double gainPct = last == null || p.avgCostCents() <= 0 ? null
                : Math.round(10000.0 * (last - p.avgCostCents()) / p.avgCostCents()) / 100.0;
        return new PositionView(p.symbol(), p.shares(), p.shares() - locked, locked,
                p.avgCostCents(), last, mv, unreal, gainPct);
    }

    // ---- Buy / sell ----

    /** Buys shares at the executable ask. Blocks on missing/one-sided quotes or insufficient cash. */
    public StockTradeResult buy(String accountId, String symbol, long shares) {
        String sym = norm(symbol);
        List<String> warnings = validateOrder(shares);
        MarksSource.LegMark mark = stockMark(sym);
        BigDecimal ask = mark.executable(LegAction.BUY);
        if (ask == null) {
            throw new TradeRejectedException(List.of("No executable ask for " + sym
                    + " — the quote is one-sided, crossed, or missing; cannot buy"));
        }
        long cost = Money.centsFromPrice(ask, shares);
        long priceCents = Money.toCents(ask);
        Position updated = db.tx(c -> {
            Account acct = AccountService.get(c, accountId);
            long cash = acct.cashCents() - cost;
            if (cash - acct.reservedCents() < 0) {
                throw new TradeRejectedException(List.of("Insufficient buying power: " + shares + " sh of " + sym
                        + " costs " + Money.fmt(cost) + " but only " + Money.fmt(acct.buyingPowerCents()) + " is available"));
            }
            String now = now();
            ledgerRow(c, accountId, now, "STOCK_BUY", -cost, cash, acct.reservedCents(),
                    "BUY " + shares + " sh " + sym + " @ " + ask.toPlainString());
            Position p = find(c, accountId, sym);
            if (p == null) {
                // Basis derives from the EXACT ledger debit (total cost / shares), never from a
                // separately-rounded per-share price — realized/unrealized must reconcile with cash
                long basis = BigDecimal.valueOf(cost)
                        .divide(BigDecimal.valueOf(shares), 0, RoundingMode.HALF_UP).longValueExact();
                p = new Position(Ids.newId("pos"), accountId, sym, shares, basis, now, now);
                Db.execOn(c, "INSERT INTO positions(id,account_id,symbol,shares,avg_cost_cents,created_at,updated_at) VALUES (?,?,?,?,?,?,?)",
                        p.id(), accountId, sym, shares, basis, now, now);
            } else {
                long newShares = p.shares() + shares;
                // Weighted-average per-share basis: (old total cost + this order's total cost) / total shares
                long newBasis = BigDecimal.valueOf(p.avgCostCents()).multiply(BigDecimal.valueOf(p.shares()))
                        .add(BigDecimal.valueOf(cost))
                        .divide(BigDecimal.valueOf(newShares), 0, RoundingMode.HALF_UP).longValueExact();
                Db.execOn(c, "UPDATE positions SET shares=?, avg_cost_cents=?, updated_at=? WHERE id=?",
                        newShares, newBasis, now, p.id());
                p = new Position(p.id(), accountId, sym, newShares, newBasis, p.createdAt(), now);
            }
            Db.execOn(c, "UPDATE accounts SET cash_cents=?, has_traded=1, updated_at=? WHERE id=?", cash, now, accountId);
            return p;
        });
        auditSafe(accountId, "POSITION_BUY", Map.of("symbol", sym, "shares", shares, "costCents", cost));
        return new StockTradeResult(view(updated), shares, priceCents, cost, null, warnings);
    }

    /** Sells FREE shares at the executable bid; realized P/L is reported against the average basis. */
    public StockTradeResult sell(String accountId, String symbol, long shares) {
        String sym = norm(symbol);
        List<String> warnings = validateOrder(shares);
        MarksSource.LegMark mark = stockMark(sym);
        BigDecimal bid = mark.executable(LegAction.SELL);
        if (bid == null) {
            throw new TradeRejectedException(List.of("No executable bid for " + sym
                    + " — the quote is one-sided, crossed, or missing; cannot sell"));
        }
        long proceeds = Money.centsFromPrice(bid, shares);
        long priceCents = Money.toCents(bid);
        final long[] realized = new long[1];
        Position updated = db.tx(c -> {
            Account acct = AccountService.get(c, accountId);
            Position p = find(c, accountId, sym);
            if (p == null) throw new TradeRejectedException(List.of("You do not hold any shares of " + sym));
            long locked = lockedShares(c, accountId, sym);
            long free = p.shares() - locked;
            if (shares > free) {
                throw new TradeRejectedException(List.of("Only " + free + " of your " + p.shares() + " " + sym
                        + " shares are free — " + locked + " are locked as coverage for open covered-call/collar trades;"
                        + " close those trades first"));
            }
            String now = now();
            long cash = acct.cashCents() + proceeds;
            realized[0] = proceeds - p.avgCostCents() * shares;
            ledgerRow(c, accountId, now, "STOCK_SELL", proceeds, cash, acct.reservedCents(),
                    "SELL " + shares + " sh " + sym + " @ " + bid.toPlainString()
                            + " (basis " + Money.fmt(p.avgCostCents()) + "/sh, realized " + Money.fmt(realized[0]) + ")");
            long newShares = p.shares() - shares;
            if (newShares == 0) {
                Db.execOn(c, "DELETE FROM positions WHERE id=?", p.id());
            } else {
                Db.execOn(c, "UPDATE positions SET shares=?, updated_at=? WHERE id=?", newShares, now, p.id());
            }
            Db.execOn(c, "UPDATE accounts SET cash_cents=?, has_traded=1, updated_at=? WHERE id=?", cash, now, accountId);
            return new Position(p.id(), accountId, sym, newShares, p.avgCostCents(), p.createdAt(), now);
        });
        auditSafe(accountId, "POSITION_SELL", Map.of("symbol", sym, "shares", shares,
                "proceedsCents", proceeds, "realizedPnlCents", realized[0]));
        return new StockTradeResult(view(updated), shares, priceCents, proceeds, realized[0], warnings);
    }

    private List<String> validateOrder(long shares) {
        if (shares <= 0) throw new IllegalArgumentException("shares must be positive");
        if (shares > MAX_SHARES_PER_ORDER) {
            throw new IllegalArgumentException("shares exceeds the " + MAX_SHARES_PER_ORDER + " practice cap");
        }
        List<String> warnings = new ArrayList<>();
        if (!io.liftandshift.market.MarketHours.isRegularSession(clock.instant())) {
            warnings.add("Market is closed — quotes are leftovers from the last session and paper fills are simulated");
        }
        return warnings;
    }

    private MarksSource.LegMark stockMark(String sym) {
        return marks.legMark(sym, Leg.stock(LegAction.BUY, 1, BigDecimal.ONE))
                .orElseThrow(() -> new TradeRejectedException(List.of("No quote available for " + sym)));
    }

    // ---- Assignment hooks (called by TradeService inside ITS transaction) ----

    /** Removes shares at the strike as a covered call is assigned. Returns realized stock P/L vs basis. */
    static long removeAssigned(Connection c, String accountId, String symbol, long shares, long strikeCents, String now) throws SQLException {
        Position p = find(c, accountId, symbol);
        if (p == null || p.shares() < shares) {
            throw new IllegalStateException("assignment needs " + shares + " sh of " + symbol + " but the position holds "
                    + (p == null ? 0 : p.shares()));
        }
        long realized = (strikeCents - p.avgCostCents()) * shares;
        long newShares = p.shares() - shares;
        if (newShares == 0) Db.execOn(c, "DELETE FROM positions WHERE id=?", p.id());
        else Db.execOn(c, "UPDATE positions SET shares=?, updated_at=? WHERE id=?", newShares, now, p.id());
        return realized;
    }

    /** Adds shares at the strike as a cash-secured put is assigned (basis = strike; premium was option income). */
    static void addAssigned(Connection c, String accountId, String symbol, long shares, long strikeCents, String now) throws SQLException {
        Position p = find(c, accountId, symbol);
        if (p == null) {
            Db.execOn(c, "INSERT INTO positions(id,account_id,symbol,shares,avg_cost_cents,created_at,updated_at) VALUES (?,?,?,?,?,?,?)",
                    Ids.newId("pos"), accountId, symbol, shares, strikeCents, now, now);
        } else {
            long newShares = p.shares() + shares;
            long newBasis = BigDecimal.valueOf(p.avgCostCents()).multiply(BigDecimal.valueOf(p.shares()))
                    .add(BigDecimal.valueOf(strikeCents).multiply(BigDecimal.valueOf(shares)))
                    .divide(BigDecimal.valueOf(newShares), 0, RoundingMode.HALF_UP).longValueExact();
            Db.execOn(c, "UPDATE positions SET shares=?, avg_cost_cents=?, updated_at=? WHERE id=?",
                    newShares, newBasis, now, p.id());
        }
    }

    static Position find(Connection c, String accountId, String symbol) throws SQLException {
        List<Position> rows = Db.queryOn(c, "SELECT * FROM positions WHERE account_id=? AND symbol=?",
                PositionsService::map, accountId, symbol);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    static long heldShares(Connection c, String accountId, String symbol) throws SQLException {
        Position p = find(c, accountId, symbol);
        return p == null ? 0 : p.shares();
    }

    /** Shares pledged to ACTIVE trades as short-call coverage. */
    static long lockedShares(Connection c, String accountId, String symbol) throws SQLException {
        return Db.queryOn(c, "SELECT COALESCE(SUM(shares_locked),0) AS n FROM trades WHERE account_id=? AND symbol=? AND status='ACTIVE'",
                r -> r.lng("n"), accountId, symbol).getFirst();
    }

    static Position map(Db.Row r) {
        return new Position(r.str("id"), r.str("account_id"), r.str("symbol"), r.lng("shares"),
                r.lng("avg_cost_cents"), r.str("created_at"), r.str("updated_at"));
    }

    private static void ledgerRow(Connection c, String accountId, String ts, String type,
                                  long amount, long cashAfter, long reservedAfter, String memo) throws SQLException {
        Db.execOn(c, "INSERT INTO ledger(account_id,trade_id,ts,type,amount_cents,cash_after_cents,reserved_after_cents,memo) VALUES (?,NULL,?,?,?,?,?,?)",
                accountId, ts, type, amount, cashAfter, reservedAfter, memo);
    }

    private void auditSafe(String accountId, String action, Map<String, Object> detail) {
        try {
            audit.log(accountId, null, action, "INFO", detail);
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(PositionsService.class)
                    .warn("post-commit audit write failed for {}: {}", action, e.toString());
        }
    }

    private String norm(String symbol) {
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("symbol is required");
        return symbol.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private String now() {
        return Instant.now(clock).toString();
    }
}
