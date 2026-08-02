package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.model.Symbol;
import io.liftandshift.strikebench.util.OwnerScope;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Normalized package grouping for active tracked-account lots.
 *
 * <p>An explicit open {@code portfolio_structure} remains one package. Open quantities not
 * allocated to a structure remain grouped by their exact opening transaction, rather than being
 * flattened into an account/symbol position. That preserves the destination account and the
 * transaction identity returned by broker-import confirmation without introducing another
 * position ledger.</p>
 */
public final class TrackedPackageReadService {
    public enum Grouping { TRACKED_STRUCTURE, OPENING_TRANSACTION }

    public record OpenLot(
            int legNo,
            String lotId,
            String openingTransactionId,
            int openingLegNo,
            String openedAt,
            String transactionSource,
            String externalRef,
            String importPayloadFingerprint,
            String instrumentType,
            String side,
            String symbol,
            String optionType,
            BigDecimal strike,
            LocalDate expiration,
            int multiplier,
            long quantity,
            long openAmountCents
    ) {
        public OpenLot {
            required(lotId, "lot id");
            required(openingTransactionId, "opening transaction id");
            required(openedAt, "opened at");
            required(transactionSource, "transaction source");
            required(instrumentType, "instrument type");
            required(side, "side");
            required(symbol, "symbol");
            if (legNo < 0 || openingLegNo < 0 || multiplier <= 0 || quantity <= 0
                    || openAmountCents < 0) {
                throw new IllegalArgumentException("tracked package lot quantities and amounts are invalid");
            }
        }
    }

    /**
     * One exact focusable package in a tracked account.
     *
     * <p>For {@link Grouping#OPENING_TRANSACTION}, {@code itemKind} is
     * {@code EXACT_TRANSACTION} and {@code itemId}/{@code focusKey} are the exact transaction id
     * returned by broker-import or manual-entry confirmation. For a structure they are the
     * structure id. Every lot retains its own opening transaction provenance.</p>
     */
    public record OpenPackage(
            String focusKey,
            Grouping grouping,
            String itemKind,
            String itemId,
            String portfolioAccountId,
            String accountName,
            String label,
            String structureId,
            String structureRevisionId,
            String artifactId,
            String createdAt,
            String transactionSource,
            String externalRef,
            String importPayloadFingerprint,
            List<String> symbols,
            long entryCostCents,
            List<OpenLot> lots,
            String basis
    ) {
        public OpenPackage {
            required(focusKey, "focus key");
            if (grouping == null) throw new IllegalArgumentException("package grouping is required");
            required(itemKind, "item kind");
            required(itemId, "item id");
            required(portfolioAccountId, "portfolio account id");
            required(accountName, "account name");
            required(label, "package label");
            required(createdAt, "package created at");
            symbols = symbols == null ? List.of() : List.copyOf(symbols);
            lots = lots == null ? List.of() : List.copyOf(lots);
            if (symbols.isEmpty() || lots.isEmpty()) {
                throw new IllegalArgumentException("tracked package requires symbols and open lots");
            }
            required(basis, "package basis");
        }

        /** Whether this exact grouping can enter a single-underlying Position/Scenario canvas. */
        @com.fasterxml.jackson.annotation.JsonProperty("singleUnderlyingAnalysisEligible")
        public boolean singleUnderlyingAnalysisEligible() {
            return symbols.size() == 1;
        }

        /** Explicit API result so clients block Analyze rather than coercing to the first symbol. */
        @com.fasterxml.jackson.annotation.JsonProperty("singleUnderlyingAnalysisBlockedReason")
        @com.fasterxml.jackson.annotation.JsonInclude(
                com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public String singleUnderlyingAnalysisBlockedReason() {
            return singleUnderlyingAnalysisEligible() ? null
                    : "This exact tracked package spans " + symbols.size()
                            + " underlyings and cannot be represented by a single-underlying analysis.";
        }
    }

    private final Db db;

    public TrackedPackageReadService(Db db) {
        this.db = db;
    }

    /** Every focusable package in every active tracked account owned by the user. */
    public List<OpenPackage> active(String userId) {
        return read(OwnerScope.id(userId), null, null);
    }

    /** Every same-symbol tracked package that can participate in Position/Scenario focus. */
    public List<OpenPackage> activeForSymbol(String userId, String rawSymbol) {
        String symbol = Symbol.normalize(rawSymbol);
        // Filter complete packages after grouping. Filtering lots in SQL created a partial package
        // under the original focusKey/revision, which was a false exact-package identity.
        return active(userId).stream()
                .filter(p -> p.symbols().contains(symbol))
                .toList();
    }

    private List<OpenPackage> read(String owner, String accountId, String symbol) {
        return db.with(c -> {
            String accountClause = accountId == null ? "" : " AND pa.id=?";
            String symbolClause = symbol == null ? "" : " AND pl.symbol=?";
            List<Object> structureParams = new ArrayList<>();
            structureParams.add(owner);
            if (accountId != null) structureParams.add(accountId);
            if (symbol != null) structureParams.add(symbol);

            List<StructureLot> structured = Db.queryOn(c,
                    "SELECT s.id structure_id,s.label,s.current_revision_id,pa.id account_id,"
                            + "pa.name account_name,psr.created_at revision_created_at,"
                            + "(SELECT pr.id FROM position_artifact pr "
                            + " WHERE pr.structure_revision_id=s.current_revision_id "
                            + " ORDER BY pr.created_at DESC,pr.id DESC LIMIT 1) artifact_id,"
                            + "psm.leg_no,psm.allocated_quantity,pl.id lot_id,"
                            + "pl.opening_transaction_id,pl.opening_leg_no,pl.opened_at,"
                            + "pt.source transaction_source,pt.external_ref,"
                            + "pt.import_payload_fingerprint,pl.instrument_type,pl.side,pl.symbol,"
                            + "pl.option_type,pl.strike::text strike,pl.expiration::text expiration,"
                            + "pl.multiplier,pl.remaining_quantity,"
                            + "pl.economic_remaining_open_amount_cents "
                            + "FROM portfolio_structure s "
                            + "JOIN portfolio_account pa ON pa.id=s.portfolio_account_id "
                            + "JOIN portfolio_structure_revision psr ON psr.id=s.current_revision_id "
                            + "JOIN portfolio_structure_member psm ON psm.revision_id=psr.id "
                            + "JOIN portfolio_lot pl ON pl.id=psm.lot_id "
                            + "JOIN portfolio_transaction pt ON pt.id=pl.opening_transaction_id "
                            + "WHERE s.user_id=? AND pa.status='ACTIVE' AND s.status='OPEN' "
                            + "AND s.current_revision_id IS NOT NULL AND pl.remaining_quantity>0"
                            + accountClause + symbolClause
                            + " ORDER BY pa.name,s.created_at,s.id,psm.leg_no,pl.id",
                    r -> new StructureLot(
                            r.str("structure_id"), r.str("label"), r.str("current_revision_id"),
                            r.str("account_id"), r.str("account_name"),
                            r.odt("revision_created_at").toString(), r.str("artifact_id"),
                            rawLot(r.intv("leg_no"), r, r.lng("allocated_quantity"))),
                    structureParams.toArray());

            LinkedHashMap<String, AllocationCursor> allocations = new LinkedHashMap<>();
            LinkedHashMap<String, PackageBuilder> structureGroups = new LinkedHashMap<>();
            for (StructureLot row : structured) {
                RawLot lot = row.lot();
                long quantity = Math.min(lot.allocatedQuantity(), lot.remainingQuantity());
                if (quantity <= 0) continue;
                PackageBuilder group = structureGroups.computeIfAbsent(row.structureId(),
                        ignored -> PackageBuilder.structure(row));
                group.add(lot, quantity, allocation(allocations, lot).next(quantity));
            }

            List<Object> freeParams = new ArrayList<>();
            freeParams.add(owner);
            if (accountId != null) freeParams.add(accountId);
            if (symbol != null) freeParams.add(symbol);
            List<FreeLot> free = Db.queryOn(c,
                    "SELECT pa.id account_id,pa.name account_name,pt.occurred_at,"
                            + "pl.id lot_id,pl.opening_transaction_id,pl.opening_leg_no,pl.opened_at,"
                            + "pt.source transaction_source,pt.external_ref,"
                            + "pt.import_payload_fingerprint,pl.instrument_type,pl.side,pl.symbol,"
                            + "pl.option_type,pl.strike::text strike,pl.expiration::text expiration,"
                            + "pl.multiplier,pl.remaining_quantity,"
                            + "pl.economic_remaining_open_amount_cents,"
                            + "GREATEST(0,pl.remaining_quantity-COALESCE(("
                            + " SELECT SUM(psm.allocated_quantity)"
                            + " FROM portfolio_structure_member psm"
                            + " JOIN portfolio_structure_revision psr ON psr.id=psm.revision_id"
                            + " JOIN portfolio_structure s ON s.current_revision_id=psr.id"
                            + " WHERE psm.lot_id=pl.id AND s.status='OPEN'),0)) free_quantity "
                            + "FROM portfolio_lot pl "
                            + "JOIN portfolio_account pa ON pa.id=pl.portfolio_account_id "
                            + "JOIN portfolio_transaction pt ON pt.id=pl.opening_transaction_id "
                            + "WHERE pa.user_id=? AND pa.status='ACTIVE' AND pl.remaining_quantity>0"
                            + accountClause + symbolClause
                            + " ORDER BY pa.name,pt.occurred_at,pt.record_seq,pl.opening_leg_no,pl.id",
                    r -> new FreeLot(
                            r.str("account_id"), r.str("account_name"),
                            r.odt("occurred_at").toString(),
                            rawLot(r.intv("opening_leg_no"), r, r.lng("free_quantity")),
                            r.lng("free_quantity")),
                    freeParams.toArray());

            LinkedHashMap<String, PackageBuilder> transactionGroups = new LinkedHashMap<>();
            for (FreeLot row : free) {
                if (row.freeQuantity() <= 0) continue;
                String transactionId = row.lot().openingTransactionId();
                PackageBuilder group = transactionGroups.computeIfAbsent(transactionId,
                        ignored -> PackageBuilder.transaction(row));
                group.add(row.lot(), row.freeQuantity(),
                        allocation(allocations, row.lot()).next(row.freeQuantity()));
            }
            allocations.values().forEach(AllocationCursor::requireComplete);

            List<OpenPackage> result = new ArrayList<>();
            for (PackageBuilder group : structureGroups.values()) result.add(group.build());
            for (PackageBuilder group : transactionGroups.values()) result.add(group.build());
            return List.copyOf(result);
        });
    }

    private static RawLot rawLot(int legNo, Db.Row r, long allocatedQuantity) {
        return new RawLot(legNo, r.str("lot_id"), r.str("opening_transaction_id"),
                r.intv("opening_leg_no"), r.odt("opened_at").toString(),
                r.str("transaction_source"), r.str("external_ref"),
                r.str("import_payload_fingerprint"), r.str("instrument_type"), r.str("side"),
                r.str("symbol"), r.str("option_type"), decimal(r.str("strike")),
                date(r.str("expiration")), r.intv("multiplier"), r.lng("remaining_quantity"),
                r.lng("economic_remaining_open_amount_cents"),
                allocatedQuantity);
    }

    private static AllocationCursor allocation(
            LinkedHashMap<String, AllocationCursor> cursors, RawLot lot) {
        AllocationCursor cursor = cursors.computeIfAbsent(lot.lotId(),
                ignored -> new AllocationCursor(
                        lot.lotId(), lot.openAmountCents(), lot.remainingQuantity()));
        cursor.requireSame(lot.openAmountCents(), lot.remainingQuantity());
        return cursor;
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static LocalDate date(String value) {
        return value == null ? null : LocalDate.parse(value);
    }

    private static void required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
    }

    private record RawLot(
            int legNo, String lotId, String openingTransactionId, int openingLegNo,
            String openedAt, String transactionSource, String externalRef,
            String importPayloadFingerprint, String instrumentType, String side, String symbol,
            String optionType, BigDecimal strike, LocalDate expiration, int multiplier,
            long remainingQuantity, long openAmountCents,
            long allocatedQuantity) {}

    private record StructureLot(
            String structureId, String label, String revisionId, String accountId,
            String accountName, String revisionCreatedAt, String artifactId, RawLot lot) {}

    private record FreeLot(
            String accountId, String accountName, String occurredAt, RawLot lot,
            long freeQuantity) {}

    /**
     * Allocates a lot's integer cents across all of its structure/free slices cumulatively.
     * Independent HALF_UP calls can turn one cent split across two units into two cents; rounding
     * the cumulative boundary makes every slice deterministic while the final sum is exact.
     */
    private static final class AllocationCursor {
        private final String lotId;
        private final long totalCents;
        private final long totalQuantity;
        private long allocatedQuantity;
        private long allocatedCents;

        private AllocationCursor(String lotId, long totalCents, long totalQuantity) {
            if (totalCents < 0 || totalQuantity <= 0) {
                throw new IllegalArgumentException("invalid tracked lot allocation");
            }
            this.lotId = lotId;
            this.totalCents = totalCents;
            this.totalQuantity = totalQuantity;
        }

        private void requireSame(long cents, long quantity) {
            if (cents != totalCents || quantity != totalQuantity) {
                throw new IllegalStateException(
                        "tracked lot " + lotId + " changed while its package was assembled");
            }
        }

        private long next(long quantity) {
            if (quantity <= 0 || quantity > totalQuantity - allocatedQuantity) {
                throw new IllegalArgumentException("invalid tracked lot allocation");
            }
            long throughQuantity = Math.addExact(allocatedQuantity, quantity);
            long throughCents = BigDecimal.valueOf(totalCents)
                    .multiply(BigDecimal.valueOf(throughQuantity))
                    .divide(BigDecimal.valueOf(totalQuantity), 0, RoundingMode.HALF_UP)
                    .longValueExact();
            long slice = Math.subtractExact(throughCents, allocatedCents);
            allocatedQuantity = throughQuantity;
            allocatedCents = throughCents;
            return slice;
        }

        private void requireComplete() {
            if (allocatedQuantity != totalQuantity || allocatedCents != totalCents) {
                throw new IllegalStateException(
                        "tracked lot " + lotId + " was not allocated exactly once");
            }
        }
    }

    private static final class PackageBuilder {
        private final Grouping grouping;
        private final String itemId;
        private final String accountId;
        private final String accountName;
        private final String explicitLabel;
        private final String structureId;
        private final String revisionId;
        private final String artifactId;
        private final String createdAt;
        private final List<OpenLot> lots = new ArrayList<>();
        private final LinkedHashSet<String> symbols = new LinkedHashSet<>();
        private long entryCostCents;

        private PackageBuilder(Grouping grouping, String itemId, String accountId,
                               String accountName, String explicitLabel, String structureId,
                               String revisionId, String artifactId, String createdAt) {
            this.grouping = grouping;
            this.itemId = itemId;
            this.accountId = accountId;
            this.accountName = accountName;
            this.explicitLabel = explicitLabel;
            this.structureId = structureId;
            this.revisionId = revisionId;
            this.artifactId = artifactId;
            this.createdAt = createdAt;
        }

        static PackageBuilder structure(StructureLot row) {
            return new PackageBuilder(Grouping.TRACKED_STRUCTURE, row.structureId(),
                    row.accountId(), row.accountName(), row.label(), row.structureId(),
                    row.revisionId(), row.artifactId(), row.revisionCreatedAt());
        }

        static PackageBuilder transaction(FreeLot row) {
            return new PackageBuilder(Grouping.OPENING_TRANSACTION,
                    row.lot().openingTransactionId(), row.accountId(), row.accountName(),
                    null, null, null, null, row.occurredAt());
        }

        void add(RawLot row, long quantity, long openAmount) {
            entryCostCents = Math.addExact(entryCostCents,
                    "SHORT".equals(row.side()) ? Math.negateExact(openAmount) : openAmount);
            symbols.add(row.symbol());
            lots.add(new OpenLot(row.legNo(), row.lotId(), row.openingTransactionId(),
                    row.openingLegNo(), row.openedAt(), row.transactionSource(),
                    row.externalRef(), row.importPayloadFingerprint(), row.instrumentType(),
                    row.side(), row.symbol(), row.optionType(), row.strike(), row.expiration(),
                    row.multiplier(), quantity, openAmount));
        }

        OpenPackage build() {
            OpenLot first = lots.getFirst();
            String label = explicitLabel;
            if (label == null || label.isBlank()) {
                if (symbols.size() == 1 && lots.stream().allMatch(l -> "STOCK".equals(l.instrumentType()))) {
                    long shares = lots.stream().mapToLong(OpenLot::quantity).sum();
                    label = shares + " " + symbols.getFirst() + " shares";
                } else if (symbols.size() == 1) {
                    label = symbols.getFirst() + " tracked package";
                } else {
                    label = symbols.size() + "-symbol tracked package";
                }
            }
            boolean oneTransaction = lots.stream()
                    .allMatch(lot -> first.openingTransactionId().equals(lot.openingTransactionId()));
            String source = unanimous(OpenLot::transactionSource);
            String externalRef = oneTransaction ? first.externalRef() : null;
            String fingerprint = oneTransaction ? first.importPayloadFingerprint() : null;
            return new OpenPackage(itemId, grouping,
                    grouping == Grouping.OPENING_TRANSACTION
                            ? "EXACT_TRANSACTION" : "TRACKED_STRUCTURE",
                    itemId, accountId, accountName, label, structureId, revisionId, artifactId,
                    createdAt, source, externalRef, fingerprint, List.copyOf(symbols),
                    entryCostCents, lots,
                    grouping == Grouping.OPENING_TRANSACTION
                            ? "Remaining open lots grouped by their exact opening transaction; "
                                    + "quantities assigned to a current structure are excluded."
                            : "Current open structure revision and its allocated remaining lot quantities.");
        }

        private String unanimous(java.util.function.Function<OpenLot, String> value) {
            String firstValue = value.apply(lots.getFirst());
            return lots.stream().allMatch(lot -> java.util.Objects.equals(firstValue, value.apply(lot)))
                    ? firstValue : "MIXED";
        }
    }
}
