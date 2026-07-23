package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.util.Ids;
import io.liftandshift.strikebench.util.Money;

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
    private final boolean fixturesOnly;

    public PositionsService(Db db, MarksSource marks, AuditLog audit, Clock clock) {
        this(db, marks, audit, clock, true);
    }

    public PositionsService(Db db, MarksSource marks, AuditLog audit, Clock clock, boolean fixturesOnly) {
        this.db = db;
        this.marks = marks;
        this.audit = audit;
        this.clock = clock;
        this.fixturesOnly = fixturesOnly;
    }

    public record Position(String id, String accountId, String symbol, long shares, long avgCostCents,
                           String createdAt, String updatedAt) {}

    /** Wire-friendly view with lock state and mark-to-market context. */
    public record PositionView(String symbol, long shares, long freeShares, long lockedShares,
                               long avgCostCents, Long lastCents, Long marketValueCents,
                               Long unrealizedCents, Double gainPct) {}

    public record StockTradeResult(PositionView position, long sharesTraded, long pricePerShareCents,
                                   long totalCents, Long realizedPnlCents, List<String> warnings) {}

    public record StockTradePreview(boolean ok, List<String> blockReasons, String side, String symbol,
                                    long shares, long pricePerShareCents, long totalCents,
                                    long cashChangeCents, long buyingPowerBeforeCents,
                                    long buyingPowerAfterCents, Long availableShares,
                                    Long estimatedRealizedPnlCents, List<String> warnings,
                                    io.liftandshift.strikebench.model.DataEvidence evidence) {}

    private record PricedStockOrder(String symbol, long shares, BigDecimal price, long priceCents,
                                    long totalCents, List<String> warnings,
                                    io.liftandshift.strikebench.model.DataEvidence evidence) {}

    private record PositionRead(Position position, long lockedShares, String worldId) {}

    // ---- Reads ----

    /**
     * Exact persisted share inventory without a market-data read. Book scenario composition uses
     * this owner-level view so automatically opening the desk cannot trigger quote acquisition;
     * mark-to-market callers should continue to use {@link #list}.
     */
    public List<Position> records(String accountId) {
        return db.query("SELECT * FROM positions WHERE account_id=? ORDER BY symbol",
                PositionsService::map, accountId);
    }

    public List<PositionView> list(String accountId) {
        List<PositionRead> rows = db.query("SELECT p.*,COALESCE(l.locked,0) locked,a.type,a.world_id " +
                        "FROM positions p JOIN accounts a ON a.id=p.account_id LEFT JOIN (" +
                        "SELECT account_id,symbol,SUM(shares_locked) locked FROM trades " +
                        "WHERE status='ACTIVE' GROUP BY account_id,symbol) l " +
                        "ON l.account_id=p.account_id AND l.symbol=p.symbol " +
                        "WHERE p.account_id=? ORDER BY p.symbol",
                row -> new PositionRead(map(row), row.lng("locked"),
                        "DEMO".equals(row.str("type")) ? "demo" : row.str("world_id")), accountId);
        if (rows.isEmpty()) return List.of();
        String world = rows.getFirst().worldId();
        Map<String, BigDecimal> marksBySymbol = marks.underlyingMarks(
                rows.stream().map(row -> row.position().symbol()).toList(), world);
        List<PositionView> out = new ArrayList<>(rows.size());
        for (PositionRead row : rows) {
            BigDecimal mark = marksBySymbol.get(row.position().symbol());
            out.add(view(row.position(), row.lockedShares(), mark == null ? null : Money.toCents(mark)));
        }
        return out;
    }

    public PositionView get(String accountId, String symbol) {
        Position p = db.with(c -> find(c, accountId, norm(symbol)));
        if (p == null) throw new io.liftandshift.strikebench.util.ResourceNotFoundException("no position in " + norm(symbol));
        return view(p);
    }

    /** Shares of a symbol not pledged as coverage to an ACTIVE trade. 0 when nothing is held. */
    public long freeShares(String accountId, String symbol) {
        return db.with(c -> {
            Position p = find(c, accountId, norm(symbol));
            return p == null ? 0L : p.shares() - lockedShares(c, accountId, norm(symbol));
        });
    }

    /** The account's market lane: a SIMULATION account's shares price against ITS world. */
    private String worldOf(String accountId) {
        var rows = db.query("SELECT world_id,type FROM accounts WHERE id=?",
                r -> "DEMO".equals(r.str("type")) ? "demo" : r.str("world_id"), accountId);
        if (rows.isEmpty()) throw new IllegalArgumentException("no such account " + accountId);
        return rows.getFirst();
    }

    private PositionView view(Position p) {
        long locked = db.with(c -> lockedShares(c, p.accountId(), p.symbol()));
        Long last = marks.underlyingMark(p.symbol(), worldOf(p.accountId()))
                .map(Money::toCents).orElse(null); // per-share cents
        return view(p, locked, last);
    }

    private static PositionView view(Position p, long locked, Long last) {
        Long mv = last == null ? null : last * p.shares();
        Long unreal = last == null ? null : (last - p.avgCostCents()) * p.shares();
        Double gainPct = last == null || p.avgCostCents() <= 0 ? null
                : Math.round(10000.0 * (last - p.avgCostCents()) / p.avgCostCents()) / 100.0;
        return new PositionView(p.symbol(), p.shares(), p.shares() - locked, locked,
                p.avgCostCents(), last, mv, unreal, gainPct);
    }

    // ---- Buy / sell ----

    /** Read-only quote and account check. The actual mutation re-prices and re-checks atomically. */
    public StockTradePreview preview(String accountId, String sideRaw, String symbol, long shares) {
        LegAction side = "SELL".equalsIgnoreCase(sideRaw) ? LegAction.SELL
                : "BUY".equalsIgnoreCase(sideRaw) ? LegAction.BUY
                : throwBadSide(sideRaw);
        String world = worldOf(accountId);
        PricedStockOrder order = priceOrder(side, symbol, shares, world);
        Account acct = db.with(c -> AccountService.get(c, accountId));
        List<String> blocks = new ArrayList<>();
        Long available = null, realized = null;
        long cashChange;
        if (side == LegAction.BUY) {
            cashChange = -order.totalCents();
            if (order.totalCents() > acct.buyingPowerCents()) {
                blocks.add(shares + " sh of " + order.symbol() + " costs " + Money.fmt(order.totalCents())
                        + " but only " + Money.fmt(acct.buyingPowerCents()) + " is available");
            }
        } else {
            cashChange = order.totalCents();
            Position p = db.with(c -> find(c, accountId, order.symbol()));
            if (p == null) {
                available = 0L;
                blocks.add("You do not hold any shares of " + order.symbol());
            } else {
                long locked = db.with(c -> lockedShares(c, accountId, order.symbol()));
                available = Math.max(0, p.shares() - locked);
                if (shares > available) {
                    blocks.add("Only " + available + " of your " + p.shares() + " " + order.symbol()
                            + " shares are free; " + locked + " are locked as trade coverage");
                }
                realized = (order.priceCents() - p.avgCostCents()) * shares;
            }
        }
        long after = acct.buyingPowerCents() + cashChange;
        return new StockTradePreview(blocks.isEmpty(), blocks, side.name(), order.symbol(), shares,
                order.priceCents(), order.totalCents(), cashChange, acct.buyingPowerCents(), after,
                available, realized, order.warnings(), order.evidence());
    }

    private static LegAction throwBadSide(String side) {
        throw new IllegalArgumentException("side must be BUY or SELL, not " + side);
    }

    /** Buys shares at the executable ask. Blocks on missing/one-sided quotes or insufficient cash. */
    public StockTradeResult buy(String accountId, String symbol, long shares) {
        String world = worldOf(accountId);
        PricedStockOrder order = priceOrder(LegAction.BUY, symbol, shares, world);
        String sym = order.symbol();
        BigDecimal ask = order.price();
        long cost = order.totalCents();
        long priceCents = order.priceCents();
        Position updated = db.tx(c -> {
            Account acct = AccountService.getForUpdate(c, accountId);
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
        return new StockTradeResult(view(updated), shares, priceCents, cost, null, order.warnings());
    }

    /** Sells FREE shares at the executable bid; realized P/L is reported against the average basis. */
    public StockTradeResult sell(String accountId, String symbol, long shares) {
        String world = worldOf(accountId);
        PricedStockOrder order = priceOrder(LegAction.SELL, symbol, shares, world);
        String sym = order.symbol();
        BigDecimal bid = order.price();
        long proceeds = order.totalCents();
        long priceCents = order.priceCents();
        final long[] realized = new long[1];
        Position updated = db.tx(c -> {
            Account acct = AccountService.getForUpdate(c, accountId);
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
        return new StockTradeResult(view(updated), shares, priceCents, proceeds, realized[0], order.warnings());
    }

    private PricedStockOrder priceOrder(LegAction side, String symbol, long shares, String world) {
        String sym = norm(symbol);
        List<String> warnings = validateOrder(shares, world);
        MarksSource.LegMark mark = stockMark(sym, world);
        requireExecutableEvidence(mark, world, sym);
        BigDecimal price = mark.executable(side);
        if (price == null) {
            throw new TradeRejectedException(List.of("No executable " + (side == LegAction.BUY ? "ask" : "bid")
                    + " for " + sym + " — the quote is one-sided, crossed, or missing; cannot "
                    + side.name().toLowerCase(java.util.Locale.ROOT)));
        }
        return new PricedStockOrder(sym, shares, price, Money.toCents(price),
                Money.centsFromPrice(price, shares), warnings, mark.evidence());
    }

    private List<String> validateOrder(long shares, String world) {
        if (shares <= 0) throw new IllegalArgumentException("shares must be positive");
        if (shares > MAX_SHARES_PER_ORDER) {
            throw new IllegalArgumentException("shares exceeds the " + MAX_SHARES_PER_ORDER + " practice cap");
        }
        List<String> warnings = new ArrayList<>();
        // ONE CLOCK PER LANE: a running sim session is its own open market — the observed
        // market being closed is irrelevant (and saying otherwise was a false claim).
        java.time.Instant now = marks.simNow(world, clock);
        if (world == null && !io.liftandshift.strikebench.market.MarketHours.isRegularSession(now)) {
            warnings.add("Market is closed — quotes are leftovers from the last session and paper fills are simulated");
        }
        return warnings;
    }

    private MarksSource.LegMark stockMark(String sym, String world) {
        return marks.legMark(sym, Leg.stock(LegAction.BUY, 1, BigDecimal.ONE), world)
                .orElseThrow(() -> new TradeRejectedException(List.of("No quote available for " + sym)));
    }

    private void requireExecutableEvidence(MarksSource.LegMark mark, String world, String symbol) {
        var lane = io.liftandshift.strikebench.market.MarketLane.of(world, fixturesOnly);
        if (!mark.evidence().executableIn(lane)) {
            throw new TradeRejectedException(List.of("Cannot trade shares of " + symbol + " in the " + lane
                    + " market using " + mark.evidence().provenance() + " data (" + mark.evidence().source() + ")"));
        }
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
                    .warn("A completed share action could not be added to the activity record: {}", action);
            org.slf4j.LoggerFactory.getLogger(PositionsService.class).debug("Share-action audit detail for " + action, e);
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
