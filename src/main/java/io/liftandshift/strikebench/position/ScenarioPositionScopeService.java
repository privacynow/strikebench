package io.liftandshift.strikebench.position;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.paper.PositionsService;
import io.liftandshift.strikebench.paper.TradeRecord;
import io.liftandshift.strikebench.paper.TradeService;
import io.liftandshift.strikebench.util.OwnerScope;
import io.liftandshift.strikebench.util.ResourceNotFoundException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Canonical read adapter for Scenario Canvas symbol scope. It composes existing Practice trades,
 * Practice shares, tracked structures, and unallocated tracked lots into {@link PositionPackage};
 * it never writes or maintains a parallel position ledger.
 */
public final class ScenarioPositionScopeService {
    public record Scoped(String label, String accountName, PositionPackage packageView,
                         long entryCostCents,
                         PositionPackageFingerprint.EntryProvenance entryProvenance) {
        public Scoped(String label, String accountName, PositionPackage packageView,
                      long entryCostCents) {
            this(label, accountName, packageView, entryCostCents, null);
        }
    }

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
        PositionDomain.PriceAuthority practiceHoldingAuthority =
                practiceHoldingAuthority(practiceAccountId);
        List<Scoped> out = new ArrayList<>();
        for (TradeRecord trade : trades.list(practiceAccountId, TradeRecord.ACTIVE, symbol,
                null, 0, 500).trades()) {
            out.add(practiceTrade(trade, symbol, asOf));
        }
        for (PositionsService.PositionView holding : positions.list(practiceAccountId)) {
            if (!symbol.equalsIgnoreCase(holding.symbol()) || holding.shares() <= 0) continue;
            out.add(practiceHolding(holding, symbol, asOf, practiceHoldingAuthority));
        }
        out.addAll(trackedStructures(owner, symbol, asOf));
        out.addAll(unallocatedTrackedLots(owner, symbol, asOf));
        return List.copyOf(out);
    }

    /**
     * Resolve one exact package without truncating Practice trades to the comparison-view page.
     * A mismatched account, symbol, or lifecycle state is deliberately indistinguishable from an
     * unknown key; callers must not learn facts about a package outside their active scope.
     */
    public Scoped focused(String userId, String practiceAccountId, String rawSymbol,
                          LocalDate anchorDate, String rawKey, String activePlanTradeId) {
        String owner = OwnerScope.id(userId);
        String symbol = rawSymbol == null ? "" : rawSymbol.trim().toUpperCase(Locale.ROOT);
        String key = rawKey == null ? "" : rawKey.trim();
        if (symbol.isBlank()) throw new IllegalArgumentException("canvas symbol is required");
        if (key.isBlank()) throw new IllegalArgumentException("focused package key is required");
        OffsetDateTime asOf = anchorDate.atStartOfDay().atOffset(ZoneOffset.UTC);

        if (key.equals(activePlanTradeId)) {
            TradeRecord trade;
            try {
                trade = trades.get(key);
            } catch (ResourceNotFoundException e) {
                throw unknownFocus(key);
            }
            if (!practiceAccountId.equals(trade.accountId())
                    || !TradeRecord.ACTIVE.equals(trade.status())
                    || !symbol.equalsIgnoreCase(trade.symbol())) {
                throw unknownFocus(key);
            }
            return practiceTrade(trade, symbol, asOf);
        }

        if (("practice-shares-" + symbol).equals(key)) {
            for (PositionsService.PositionView holding : positions.list(practiceAccountId)) {
                if (symbol.equalsIgnoreCase(holding.symbol()) && holding.shares() > 0) {
                    return practiceHolding(holding, symbol, asOf,
                            practiceHoldingAuthority(practiceAccountId));
                }
            }
            throw unknownFocus(key);
        }
        for (Scoped scoped : trackedStructures(owner, symbol, asOf)) {
            if (key.equals(scoped.packageView().id())) return scoped;
        }
        for (Scoped scoped : unallocatedTrackedLots(owner, symbol, asOf)) {
            if (key.equals(scoped.packageView().id())) return scoped;
        }
        throw unknownFocus(key);
    }

    private static Scoped practiceTrade(TradeRecord trade, String symbol, OffsetDateTime asOf) {
        List<PositionPackage.Leg> legs = new ArrayList<>();
        PositionDomain.PriceAuthority authority = priceAuthority(trade.dataProvenance());
        for (int i = 0; i < trade.legs().size(); i++) {
            Leg leg = trade.legs().get(i);
            legs.add(new PositionPackage.Leg(i, leg.action().name(), leg.isStock() ? "STOCK" : "OPTION",
                    symbol, leg.isStock() ? null : leg.type().name(), leg.strike(), leg.expiration(),
                    Math.multiplyExact(trade.qty(), (long) leg.ratio()), leg.multiplier(), leg.entryPrice(),
                    authority));
        }
        long heldShares = TradeService.heldShareContextSharesForDisplay(trade);
        long entryBasis = Math.addExact(Math.negateExact(trade.entryNetPremiumCents()),
                trade.feesOpenCents());
        if (heldShares > 0) {
            if (trade.entryUnderlyingCents() <= 0) {
                throw new IllegalStateException("held-share position is missing its exact entry underlying price");
            }
            legs.add(new PositionPackage.Leg(legs.size(), "BUY", "STOCK", symbol, null, null, null,
                    heldShares, 1, BigDecimal.valueOf(trade.entryUnderlyingCents(), 2), authority));
            entryBasis = Math.addExact(entryBasis,
                    Math.multiplyExact(trade.entryUnderlyingCents(), heldShares));
        }
        var p = new PositionPackage(trade.id(), PositionDomain.PackageSource.PRACTICE_TRADE,
                PositionDomain.ExecutionLane.PRACTICE, symbol, trade.qty(),
                trade.entryNetPremiumCents(), asOf, legs);
        var provenance = new PositionPackageFingerprint.EntryProvenance(
                trade.createdAt(), trade.dataProvenance(), trade.dataAge(), trade.dataSource(),
                PositionPackageFingerprint.entrySnapshotFingerprint(trade.entrySnapshotJson()));
        return new Scoped(pretty(trade.strategy()), "Practice", p, entryBasis, provenance);
    }

    private static Scoped practiceHolding(PositionsService.PositionView holding, String symbol,
                                          OffsetDateTime asOf,
                                          PositionDomain.PriceAuthority authority) {
        var leg = new PositionPackage.Leg(0, "BUY", "STOCK", symbol, null, null, null,
                holding.shares(), 1, BigDecimal.valueOf(holding.avgCostCents(), 2),
                authority);
        long basis = Math.multiplyExact(holding.avgCostCents(), holding.shares());
        var p = new PositionPackage("practice-shares-" + symbol,
                PositionDomain.PackageSource.PRACTICE_HOLDING, PositionDomain.ExecutionLane.PRACTICE,
                symbol, 1, basis, asOf, List.of(leg));
        return new Scoped(holding.shares() + " shares", "Practice", p, basis);
    }

    /** The cost basis belongs to the account's own lane; Demo/simulation basis is never observed. */
    private PositionDomain.PriceAuthority practiceHoldingAuthority(String accountId) {
        List<AccountMarket> rows = db.query("SELECT type,world_id FROM accounts WHERE id=?",
                r -> new AccountMarket(r.str("type"), r.str("world_id")), accountId);
        if (rows.isEmpty()) throw new IllegalArgumentException("no such account " + accountId);
        AccountMarket account = rows.getFirst();
        return "DEMO".equalsIgnoreCase(account.type())
                || "SIMULATION".equalsIgnoreCase(account.type())
                || account.worldId() != null && !account.worldId().isBlank()
                ? PositionDomain.PriceAuthority.MODELED
                : PositionDomain.PriceAuthority.OBSERVED;
    }

    private static IllegalArgumentException unknownFocus(String key) {
        return new IllegalArgumentException("focusPositionKey '" + key
                + "' does not name the active same-symbol package owned by this Plan/account scope");
    }

    private List<Scoped> trackedStructures(String owner, String symbol, OffsetDateTime asOf) {
        return db.with(c -> {
            List<Structure> structures = Db.queryOn(c,
                    "SELECT s.id,s.label,s.current_revision_id,pa.name account_name,"
                            + "psr.created_at revision_created_at,"
                            + "(SELECT pr.id FROM position_receipt pr "
                            + " WHERE pr.structure_revision_id=s.current_revision_id "
                            + " ORDER BY pr.created_at DESC,pr.id DESC LIMIT 1) receipt_id "
                            + "FROM portfolio_structure s JOIN portfolio_account pa ON pa.id=s.portfolio_account_id "
                            + "JOIN portfolio_structure_revision psr ON psr.id=s.current_revision_id "
                            + "WHERE s.user_id=? AND s.symbol=? AND s.status='OPEN' AND s.current_revision_id IS NOT NULL "
                            + "ORDER BY pa.name,s.created_at,s.id",
                    r -> new Structure(r.str("id"), r.str("label"), r.str("current_revision_id"),
                            r.str("account_name"), r.odt("revision_created_at").toString(),
                            r.str("receipt_id")), owner, symbol);
            List<Scoped> out = new ArrayList<>();
            for (Structure structure : structures) {
                List<LotRow> rows = Db.queryOn(c,
                        "SELECT psm.leg_no,psm.allocated_quantity,pl.id lot_id,pl.opening_transaction_id,"
                                + "pl.opening_leg_no,pl.opened_at,pt.source transaction_source,pt.external_ref,"
                                + "pt.import_payload_fingerprint,pl.instrument_type,pl.side,pl.symbol,"
                                + "pl.option_type,pl.strike::text strike,pl.expiration::text expiration,pl.multiplier,"
                                + "pl.original_quantity,pl.remaining_quantity,pl.economic_original_open_amount_cents "
                                + "FROM portfolio_structure_member psm JOIN portfolio_lot pl ON pl.id=psm.lot_id "
                                + "JOIN portfolio_transaction pt ON pt.id=pl.opening_transaction_id "
                                + "WHERE psm.revision_id=? AND pl.remaining_quantity>0 ORDER BY psm.leg_no",
                        ScenarioPositionScopeService::allocatedLotRow, structure.revisionId());
                if (rows.isEmpty()) continue;
                List<PositionPackage.Leg> legs = new ArrayList<>();
                List<PositionPackageFingerprint.TrackedLotProvenance> lotProvenance = new ArrayList<>();
                long entry = 0;
                for (LotRow row : rows) {
                    long quantity = Math.min(row.allocatedQuantity(), row.remainingQuantity());
                    if (quantity <= 0) continue;
                    long allocatedCash = Math.round(row.openAmountCents() * (double) quantity / row.originalQuantity());
                    entry = Math.addExact(entry, "SHORT".equals(row.side()) ? -allocatedCash : allocatedCash);
                    legs.add(packageLeg(row.legNo(), row, quantity));
                    lotProvenance.add(trackedLotProvenance(row));
                }
                var p = new PositionPackage(structure.id(), PositionDomain.PackageSource.TRACKED_STRUCTURE,
                        PositionDomain.ExecutionLane.REAL, symbol, 1, entry, asOf, legs);
                out.add(new Scoped(structure.label() == null ? "Tracked structure" : structure.label(),
                        structure.accountName(), p, entry,
                        trackedEntryProvenance(structure.revisionCreatedAt(),
                                new PositionPackageFingerprint.SourceIdentity(structure.revisionId(),
                                        structure.receiptId(), structure.revisionCreatedAt(), lotProvenance))));
            }
            return out;
        });
    }

    /** Remaining lots not allocated to any current open structure; grouped per tracked account. */
    private List<Scoped> unallocatedTrackedLots(String owner, String symbol, OffsetDateTime asOf) {
        return db.with(c -> {
            List<FreeLot> rows = Db.queryOn(c,
                    "SELECT pl.id lot_id,pa.id account_id,pa.name account_name,pl.opening_transaction_id,"
                            + "pl.opening_leg_no,pl.opened_at,pt.source transaction_source,pt.external_ref,"
                            + "pt.import_payload_fingerprint,pl.instrument_type,pl.side,pl.symbol,"
                            + "pl.option_type,pl.strike::text strike,pl.expiration::text expiration,pl.multiplier,"
                            + "pl.original_quantity,pl.remaining_quantity,pl.economic_original_open_amount_cents,"
                            + "GREATEST(0,pl.remaining_quantity-COALESCE((SELECT SUM(psm.allocated_quantity) "
                            + "FROM portfolio_structure_member psm JOIN portfolio_structure_revision psr ON psr.id=psm.revision_id "
                            + "JOIN portfolio_structure s ON s.current_revision_id=psr.id "
                            + "WHERE psm.lot_id=pl.id AND s.status='OPEN'),0)) free_quantity "
                            + "FROM portfolio_lot pl JOIN portfolio_account pa ON pa.id=pl.portfolio_account_id "
                            + "JOIN portfolio_transaction pt ON pt.id=pl.opening_transaction_id "
                            + "WHERE pa.user_id=? AND pa.status='ACTIVE' AND pl.symbol=? AND pl.remaining_quantity>0 "
                            + "ORDER BY pa.name,pl.opened_at,pl.id",
                    r -> new FreeLot(r.str("account_id"), r.str("account_name"),
                            freeLotRow(r), r.lng("free_quantity")), owner, symbol);
            LinkedHashMap<String, FreeGroup> groups = new LinkedHashMap<>();
            for (FreeLot row : rows) {
                if (row.freeQuantity() <= 0) continue;
                FreeGroup group = groups.computeIfAbsent(row.accountId(),
                        k -> new FreeGroup(row.accountName(), new ArrayList<>(), 0));
                int legNo = group.legs().size();
                LotRow lot = row.lot();
                group.legs().add(packageLeg(legNo, lot, row.freeQuantity()));
                group.lotProvenance().add(trackedLotProvenance(lot));
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
                out.add(new Scoped(label, group.accountName(), p, group.entry,
                        trackedEntryProvenance(earliestOpenedAt(group.lotProvenance()),
                                new PositionPackageFingerprint.SourceIdentity(null, null, null,
                                        group.lotProvenance()))));
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
                quantity, row.multiplier(), perUnit, trackedPriceAuthority(row.transactionSource()));
    }

    private static LotRow allocatedLotRow(Db.Row r) {
        return lotRow(r.intv("leg_no"), r, r.lng("allocated_quantity"));
    }
    private static LotRow freeLotRow(Db.Row r) {
        return lotRow(0, r, r.lng("remaining_quantity"));
    }
    private static LotRow lotRow(int legNo, Db.Row r, long allocatedQuantity) {
        return new LotRow(legNo, r.str("lot_id"), r.str("opening_transaction_id"),
                r.intv("opening_leg_no"), r.odt("opened_at").toString(),
                r.str("transaction_source"), r.str("external_ref"),
                r.str("import_payload_fingerprint"), r.str("instrument_type"), r.str("side"), r.str("symbol"),
                r.str("option_type"), r.str("strike"), r.str("expiration"), r.intv("multiplier"),
                r.lng("original_quantity"), r.lng("remaining_quantity"),
                r.lng("economic_original_open_amount_cents"),
                allocatedQuantity);
    }

    private static PositionDomain.PriceAuthority priceAuthority(String provenance) {
        if ("BROKER".equalsIgnoreCase(provenance)) {
            return PositionDomain.PriceAuthority.BROKER_REPORTED;
        }
        return "OBSERVED".equalsIgnoreCase(provenance)
                ? PositionDomain.PriceAuthority.OBSERVED : PositionDomain.PriceAuthority.MODELED;
    }
    private static PositionPackageFingerprint.TrackedLotProvenance trackedLotProvenance(LotRow row) {
        return new PositionPackageFingerprint.TrackedLotProvenance(row.lotId(),
                row.openingTransactionId(), row.openingLegNo(), row.openedAt(),
                row.transactionSource(), row.externalRef(), row.importPayloadFingerprint());
    }
    private static PositionPackageFingerprint.EntryProvenance trackedEntryProvenance(
            String createdAt, PositionPackageFingerprint.SourceIdentity sourceIdentity) {
        String sources = sourceIdentity.lots().stream()
                .map(PositionPackageFingerprint.TrackedLotProvenance::transactionSource)
                .filter(java.util.Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty())
                .map(String::toUpperCase).distinct().sorted().collect(Collectors.joining("+"));
        String authorities = sourceIdentity.lots().stream()
                .map(PositionPackageFingerprint.TrackedLotProvenance::transactionSource)
                .map(ScenarioPositionScopeService::trackedPriceAuthority).map(Enum::name)
                .distinct().sorted().collect(Collectors.joining("+"));
        return new PositionPackageFingerprint.EntryProvenance(createdAt,
                authorities.isEmpty() ? "UNKNOWN" : authorities,
                "RECORDED", sources.isEmpty() ? "TRACKED_LEDGER" : sources, null, sourceIdentity);
    }
    private static PositionDomain.PriceAuthority trackedPriceAuthority(String source) {
        if ("BROKER".equalsIgnoreCase(source)) return PositionDomain.PriceAuthority.BROKER_REPORTED;
        if ("CALCULATED".equalsIgnoreCase(source)) return PositionDomain.PriceAuthority.MODELED;
        return PositionDomain.PriceAuthority.USER_REPORTED;
    }
    private static String earliestOpenedAt(
            List<PositionPackageFingerprint.TrackedLotProvenance> lots) {
        return lots.stream().map(PositionPackageFingerprint.TrackedLotProvenance::openedAt)
                .filter(java.util.Objects::nonNull).min(String::compareTo).orElse(null);
    }
    private static String pretty(String value) {
        String s = value == null ? "Practice position" : value.replace('_', ' ').toLowerCase(Locale.ROOT);
        return s.isBlank() ? "Practice position" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private record Structure(String id, String label, String revisionId, String accountName,
                             String revisionCreatedAt, String receiptId) {}
    private record AccountMarket(String type, String worldId) {}
    private record LotRow(int legNo, String lotId, String openingTransactionId,
                          int openingLegNo, String openedAt, String transactionSource,
                          String externalRef, String importPayloadFingerprint,
                          String instrumentType, String side, String symbol,
                          String optionType, String strike, String expiration, int multiplier,
                          long originalQuantity, long remainingQuantity, long openAmountCents,
                          long allocatedQuantity) {}
    private record FreeLot(String accountId, String accountName, LotRow lot, long freeQuantity) {}
    private static final class FreeGroup {
        final String accountName; final List<PositionPackage.Leg> legs;
        final List<PositionPackageFingerprint.TrackedLotProvenance> lotProvenance;
        long entry;
        FreeGroup(String accountName, List<PositionPackage.Leg> legs, long entry) {
            this.accountName = accountName; this.legs = legs; this.entry = entry;
            this.lotProvenance = new ArrayList<>();
        }
        String accountName() { return accountName; } List<PositionPackage.Leg> legs() { return legs; }
        List<PositionPackageFingerprint.TrackedLotProvenance> lotProvenance() { return lotProvenance; }
    }
}
