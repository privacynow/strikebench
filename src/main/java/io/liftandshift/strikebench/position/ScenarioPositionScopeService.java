package io.liftandshift.strikebench.position;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.Symbol;
import io.liftandshift.strikebench.paper.PositionsService;
import io.liftandshift.strikebench.paper.TrackedPackageReadService;
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
    private final TrackedPackageReadService trackedPackages;

    public ScenarioPositionScopeService(Db db, TradeService trades, PositionsService positions) {
        this.db = db;
        this.trades = trades;
        this.positions = positions;
        this.trackedPackages = new TrackedPackageReadService(db);
    }

    public List<Scoped> list(String userId, String practiceAccountId, String rawSymbol,
                             LocalDate anchorDate) {
        String owner = OwnerScope.id(userId);
        String symbol;
        try {
            symbol = Symbol.normalize(rawSymbol);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("canvas symbol is required");
        }
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
        out.addAll(trackedPackages(owner, symbol, asOf));
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
        String symbol;
        try {
            symbol = Symbol.normalize(rawSymbol);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("canvas symbol is required");
        }
        String key = rawKey == null ? "" : rawKey.trim();
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
        for (Scoped scoped : trackedPackages(owner, symbol, asOf)) {
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
        var entryPrice = TradeService.recordedEntryPrice(trade);
        long entryBasis = Math.negateExact(entryPrice.afterFeeNetCents());
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
                entryPrice.grossPackageNetCents(), asOf, legs);
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
        List<AccountMarket> rows = db.query(io.liftandshift.strikebench.paper.AccountService.LANE_SQL,
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

    private List<Scoped> trackedPackages(String owner, String symbol, OffsetDateTime asOf) {
        return trackedPackages.activeForSymbol(owner, symbol).stream()
                // A single-underlying canvas cannot truthfully value a multi-symbol exact package.
                // The package remains available in the tracked Book with its complete symbols
                // receipt; it is never sliced under the original focus identity.
                .filter(p -> p.symbols().size() == 1 && p.symbols().contains(symbol))
                .map(p -> trackedPackage(p, symbol, asOf))
                .toList();
    }

    private static Scoped trackedPackage(
            TrackedPackageReadService.OpenPackage tracked,
            String symbol,
            OffsetDateTime asOf) {
        if (tracked.symbols().size() != 1 || !tracked.symbols().contains(symbol)) {
            throw new IllegalArgumentException(
                    "A multi-symbol tracked package cannot be represented as one underlying.");
        }
        List<TrackedPackageReadService.OpenLot> selectedLots = tracked.lots();
        List<PositionPackage.Leg> legs = new ArrayList<>();
        List<PositionPackageFingerprint.TrackedLotProvenance> provenance = new ArrayList<>();
        long entry = 0;
        for (int i = 0; i < selectedLots.size(); i++) {
            TrackedPackageReadService.OpenLot lot = selectedLots.get(i);
            int legNo = tracked.grouping() == TrackedPackageReadService.Grouping.TRACKED_STRUCTURE
                    ? lot.legNo() : i;
            legs.add(packageLeg(legNo, lot));
            provenance.add(trackedLotProvenance(lot));
            entry = Math.addExact(entry, "SHORT".equals(lot.side())
                    ? Math.negateExact(lot.openAmountCents()) : lot.openAmountCents());
        }
        PositionDomain.PackageSource source =
                tracked.grouping() == TrackedPackageReadService.Grouping.TRACKED_STRUCTURE
                        ? PositionDomain.PackageSource.TRACKED_STRUCTURE
                        : PositionDomain.PackageSource.TRACKED_HOLDING;
        // PositionPackage uses signed package cash (credit positive), while Scoped.entryCostCents
        // uses valuation cost (debit positive). A recorded short lot has a negative entry cost and
        // therefore a positive package cash receipt.
        long exactPackageCashCents = Math.negateExact(entry);
        var positionPackage = new PositionPackage(tracked.focusKey(), source,
                PositionDomain.ExecutionLane.REAL, symbol, 1, exactPackageCashCents, asOf, legs);
        return new Scoped(tracked.label(), tracked.accountName(), positionPackage, entry,
                trackedEntryProvenance(tracked.createdAt(),
                        new PositionPackageFingerprint.SourceIdentity(
                                tracked.structureRevisionId(), tracked.receiptId(),
                                tracked.createdAt(), provenance)));
    }

    private static PositionPackage.Leg packageLeg(
            int legNo, TrackedPackageReadService.OpenLot row) {
        BigDecimal perUnit = BigDecimal.valueOf(row.openAmountCents())
                    .divide(BigDecimal.valueOf(row.quantity() * (long) row.multiplier()),
                            6, RoundingMode.HALF_UP)
                    .movePointLeft(2);
        return new PositionPackage.Leg(legNo, "SHORT".equals(row.side()) ? "SELL" : "BUY",
                row.instrumentType(), row.symbol(), row.optionType(),
                row.strike(), row.expiration(), row.quantity(), row.multiplier(), perUnit,
                trackedPriceAuthority(row.transactionSource()));
    }

    private static PositionDomain.PriceAuthority priceAuthority(String provenance) {
        if ("BROKER".equalsIgnoreCase(provenance)) {
            return PositionDomain.PriceAuthority.BROKER_REPORTED;
        }
        return "OBSERVED".equalsIgnoreCase(provenance)
                ? PositionDomain.PriceAuthority.OBSERVED : PositionDomain.PriceAuthority.MODELED;
    }
    private static PositionPackageFingerprint.TrackedLotProvenance trackedLotProvenance(
            TrackedPackageReadService.OpenLot row) {
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
    private static String pretty(String value) {
        String s = value == null ? "Practice position" : value.replace('_', ' ').toLowerCase(Locale.ROOT);
        return s.isBlank() ? "Practice position" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private record AccountMarket(String type, String worldId) {}
}
