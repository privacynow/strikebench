package io.liftandshift.strikebench.position;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.paper.PositionsService;
import io.liftandshift.strikebench.paper.TradeRecord;
import io.liftandshift.strikebench.paper.TradeService;
import io.liftandshift.strikebench.util.OwnerScope;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Canonical read adapter for Scenario Canvas symbol scope. It composes existing Practice trades,
 * Practice shares, tracked structures, and unallocated tracked lots into {@link PositionPackage};
 * it never writes or maintains a parallel position ledger.
 */
public final class ScenarioPositionScopeService {
    public record Scoped(String label, String accountName, PositionPackage packageView,
                         long entryCostCents) {}

    private final Db db;
    private final TradeService trades;
    private final PositionsService positions;

    public ScenarioPositionScopeService(Db db, TradeService trades, PositionsService positions) {
        this.db = db; this.trades = trades; this.positions = positions;
    }

    public List<Scoped> list(String userId, String practiceAccountId, String rawSymbol,
                             LocalDate anchorDate) {
        String owner = OwnerScope.id(userId);
        String symbol = rawSymbol == null ? "" : rawSymbol.trim().toUpperCase(Locale.ROOT);
        if (symbol.isBlank()) throw new IllegalArgumentException("canvas symbol is required");
        OffsetDateTime asOf = anchorDate.atStartOfDay().atOffset(ZoneOffset.UTC);
        List<Scoped> out = new ArrayList<>();
        for (TradeRecord trade : trades.list(practiceAccountId, TradeRecord.ACTIVE, symbol,
                null, 0, 500).trades()) {
            List<PositionPackage.Leg> legs = new ArrayList<>();
            for (int i = 0; i < trade.legs().size(); i++) {
                Leg leg = trade.legs().get(i);
                legs.add(new PositionPackage.Leg(i, leg.action().name(), leg.isStock() ? "STOCK" : "OPTION",
                        symbol, leg.isStock() ? null : leg.type().name(), leg.strike(), leg.expiration(),
                        Math.multiplyExact(trade.qty(), (long) leg.ratio()), leg.multiplier(), leg.entryPrice(),
                        priceAuthority(trade.dataProvenance())));
            }
            var p = new PositionPackage(trade.id(), PositionDomain.PackageSource.PRACTICE_TRADE,
                    PositionDomain.ExecutionLane.PRACTICE, symbol, trade.qty(),
                    trade.entryNetPremiumCents(), asOf, legs);
            out.add(new Scoped(pretty(trade.strategy()), "Practice", p,
                    Math.negateExact(trade.entryNetPremiumCents())));
        }
        for (PositionsService.PositionView holding : positions.list(practiceAccountId)) {
            if (!symbol.equalsIgnoreCase(holding.symbol()) || holding.shares() <= 0) continue;
            var leg = new PositionPackage.Leg(0, "BUY", "STOCK", symbol, null, null, null,
                    holding.shares(), 1, BigDecimal.valueOf(holding.avgCostCents(), 2),
                    PositionDomain.PriceAuthority.OBSERVED);
            var p = new PositionPackage("practice-shares-" + symbol,
                    PositionDomain.PackageSource.PRACTICE_HOLDING, PositionDomain.ExecutionLane.PRACTICE,
                    symbol, 1, Math.multiplyExact(holding.avgCostCents(), holding.shares()), asOf,
                    List.of(leg));
            out.add(new Scoped(holding.shares() + " shares", "Practice", p,
                    Math.multiplyExact(holding.avgCostCents(), holding.shares())));
        }
        out.addAll(trackedStructures(owner, symbol, asOf));
        out.addAll(unallocatedTrackedLots(owner, symbol, asOf));
        return List.copyOf(out);
    }

    private List<Scoped> trackedStructures(String owner, String symbol, OffsetDateTime asOf) {
        return db.with(c -> {
            List<Structure> structures = Db.queryOn(c,
                    "SELECT s.id,s.label,s.current_revision_id,pa.name account_name "
                            + "FROM portfolio_structure s JOIN portfolio_account pa ON pa.id=s.portfolio_account_id "
                            + "WHERE s.user_id=? AND s.symbol=? AND s.status='OPEN' AND s.current_revision_id IS NOT NULL "
                            + "ORDER BY pa.name,s.created_at,s.id",
                    r -> new Structure(r.str("id"), r.str("label"), r.str("current_revision_id"),
                            r.str("account_name")), owner, symbol);
            List<Scoped> out = new ArrayList<>();
            for (Structure structure : structures) {
                List<LotRow> rows = Db.queryOn(c,
                        "SELECT psm.leg_no,psm.allocated_quantity,pl.instrument_type,pl.side,pl.symbol,"
                                + "pl.option_type,pl.strike::text strike,pl.expiration::text expiration,pl.multiplier,"
                                + "pl.original_quantity,pl.remaining_quantity,pl.economic_original_open_amount_cents "
                                + "FROM portfolio_structure_member psm JOIN portfolio_lot pl ON pl.id=psm.lot_id "
                                + "WHERE psm.revision_id=? AND pl.remaining_quantity>0 ORDER BY psm.leg_no",
                        ScenarioPositionScopeService::allocatedLotRow, structure.revisionId());
                if (rows.isEmpty()) continue;
                List<PositionPackage.Leg> legs = new ArrayList<>();
                long entry = 0;
                for (LotRow row : rows) {
                    long quantity = Math.min(row.allocatedQuantity(), row.remainingQuantity());
                    if (quantity <= 0) continue;
                    long allocatedCash = Math.round(row.openAmountCents() * (double) quantity / row.originalQuantity());
                    entry = Math.addExact(entry, "SHORT".equals(row.side()) ? -allocatedCash : allocatedCash);
                    legs.add(packageLeg(row.legNo(), row, quantity));
                }
                var p = new PositionPackage(structure.id(), PositionDomain.PackageSource.TRACKED_STRUCTURE,
                        PositionDomain.ExecutionLane.REAL, symbol, 1, entry, asOf, legs);
                out.add(new Scoped(structure.label() == null ? "Tracked structure" : structure.label(),
                        structure.accountName(), p, entry));
            }
            return out;
        });
    }

    /** Remaining lots not allocated to any current open structure; grouped per tracked account. */
    private List<Scoped> unallocatedTrackedLots(String owner, String symbol, OffsetDateTime asOf) {
        return db.with(c -> {
            List<FreeLot> rows = Db.queryOn(c,
                    "SELECT pl.id,pa.id account_id,pa.name account_name,pl.instrument_type,pl.side,pl.symbol,"
                            + "pl.option_type,pl.strike::text strike,pl.expiration::text expiration,pl.multiplier,"
                            + "pl.original_quantity,pl.remaining_quantity,pl.economic_original_open_amount_cents,"
                            + "GREATEST(0,pl.remaining_quantity-COALESCE((SELECT SUM(psm.allocated_quantity) "
                            + "FROM portfolio_structure_member psm JOIN portfolio_structure_revision psr ON psr.id=psm.revision_id "
                            + "JOIN portfolio_structure s ON s.current_revision_id=psr.id "
                            + "WHERE psm.lot_id=pl.id AND s.status='OPEN'),0)) free_quantity "
                            + "FROM portfolio_lot pl JOIN portfolio_account pa ON pa.id=pl.portfolio_account_id "
                            + "WHERE pa.user_id=? AND pa.status='ACTIVE' AND pl.symbol=? AND pl.remaining_quantity>0 "
                            + "ORDER BY pa.name,pl.opened_at,pl.id",
                    r -> new FreeLot(r.str("id"), r.str("account_id"), r.str("account_name"),
                            freeLotRow(r), r.lng("free_quantity")), owner, symbol);
            LinkedHashMap<String, FreeGroup> groups = new LinkedHashMap<>();
            for (FreeLot row : rows) {
                if (row.freeQuantity() <= 0) continue;
                FreeGroup group = groups.computeIfAbsent(row.accountId(),
                        k -> new FreeGroup(row.accountName(), new ArrayList<>(), 0));
                int legNo = group.legs().size();
                LotRow lot = row.lot();
                group.legs().add(packageLeg(legNo, lot, row.freeQuantity()));
                long cash = Math.round(lot.openAmountCents() * (double) row.freeQuantity() / lot.originalQuantity());
                group.entry = Math.addExact(group.entry, "SHORT".equals(lot.side()) ? -cash : cash);
            }
            List<Scoped> out = new ArrayList<>();
            for (var e : groups.entrySet()) {
                FreeGroup group = e.getValue();
                if (group.legs().isEmpty()) continue;
                String label = group.legs().stream().allMatch(l -> "STOCK".equals(l.instrumentType()))
                        ? group.legs().stream().mapToLong(PositionPackage.Leg::quantity).sum() + " unallocated shares"
                        : "Unallocated tracked lots";
                var p = new PositionPackage("tracked-free-" + e.getKey() + "-" + symbol,
                        PositionDomain.PackageSource.TRACKED_HOLDING, PositionDomain.ExecutionLane.REAL,
                        symbol, 1, group.entry, asOf, group.legs());
                out.add(new Scoped(label, group.accountName(), p, group.entry));
            }
            return out;
        });
    }

    private static PositionPackage.Leg packageLeg(int legNo, LotRow row, long quantity) {
        BigDecimal perUnit = row.originalQuantity() <= 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(row.openAmountCents())
                    .divide(BigDecimal.valueOf(row.originalQuantity() * (long) row.multiplier()),
                            6, RoundingMode.HALF_UP)
                    .movePointLeft(2);
        return new PositionPackage.Leg(legNo, "SHORT".equals(row.side()) ? "SELL" : "BUY",
                row.instrumentType(), row.symbol(), row.optionType(),
                row.strike() == null ? null : new BigDecimal(row.strike()),
                row.expiration() == null ? null : LocalDate.parse(row.expiration()),
                quantity, row.multiplier(), perUnit, PositionDomain.PriceAuthority.BROKER_REPORTED);
    }

    private static LotRow allocatedLotRow(Db.Row r) {
        return lotRow(r.intv("leg_no"), r, r.lng("allocated_quantity"));
    }
    private static LotRow freeLotRow(Db.Row r) {
        return lotRow(0, r, r.lng("remaining_quantity"));
    }
    private static LotRow lotRow(int legNo, Db.Row r, long allocatedQuantity) {
        return new LotRow(legNo, r.str("instrument_type"), r.str("side"), r.str("symbol"),
                r.str("option_type"), r.str("strike"), r.str("expiration"), r.intv("multiplier"),
                r.lng("original_quantity"), r.lng("remaining_quantity"),
                r.lng("economic_original_open_amount_cents"),
                allocatedQuantity);
    }

    private static PositionDomain.PriceAuthority priceAuthority(String provenance) {
        return "OBSERVED".equalsIgnoreCase(provenance) || "BROKER".equalsIgnoreCase(provenance)
                ? PositionDomain.PriceAuthority.OBSERVED : PositionDomain.PriceAuthority.MODELED;
    }
    private static String pretty(String value) {
        String s = value == null ? "Practice position" : value.replace('_', ' ').toLowerCase(Locale.ROOT);
        return s.isBlank() ? "Practice position" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private record Structure(String id, String label, String revisionId, String accountName) {}
    private record LotRow(int legNo, String instrumentType, String side, String symbol,
                          String optionType, String strike, String expiration, int multiplier,
                          long originalQuantity, long remainingQuantity, long openAmountCents,
                          long allocatedQuantity) {}
    private record FreeLot(String id, String accountId, String accountName, LotRow lot, long freeQuantity) {}
    private static final class FreeGroup {
        final String accountName; final List<PositionPackage.Leg> legs; long entry;
        FreeGroup(String accountName, List<PositionPackage.Leg> legs, long entry) {
            this.accountName = accountName; this.legs = legs; this.entry = entry;
        }
        String accountName() { return accountName; } List<PositionPackage.Leg> legs() { return legs; }
    }
}
