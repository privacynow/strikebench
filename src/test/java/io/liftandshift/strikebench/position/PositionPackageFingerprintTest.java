package io.liftandshift.strikebench.position;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PositionPackageFingerprintTest {
    @Test void focusedIdentityIgnoresArtifactTimeButBindsBasisAndImmutableEntryProvenance() {
        var leg = new PositionPackage.Leg(0, "SELL", "OPTION", "AAPL", "CALL",
                new BigDecimal("265.00"), LocalDate.parse("2026-07-31"), 1, 100,
                new BigDecimal("3.20"), PositionDomain.PriceAuthority.OBSERVED);
        var first = new PositionPackage("trade-a", PositionDomain.PackageSource.PRACTICE_TRADE,
                PositionDomain.ExecutionLane.PRACTICE, "AAPL", 1, 32_000L,
                OffsetDateTime.parse("2026-07-20T12:00:00Z"), List.of(leg));
        var later = new PositionPackage("trade-b", first.source(), first.lane(), first.symbol(),
                first.packageQuantity(), first.exactPackageCashCents(),
                OffsetDateTime.parse("2026-07-21T12:00:00Z"), first.legs());
        var provenance = new PositionPackageFingerprint.EntryProvenance(
                "2026-07-20T10:00:00Z", "OBSERVED", "DELAYED", "cboe", "a".repeat(64));

        String initial = PositionPackageFingerprint.fingerprint(
                PositionPackageFingerprint.focusedIdentity(first, -31_863, provenance));
        assertThat(PositionPackageFingerprint.fingerprint(
                PositionPackageFingerprint.focusedIdentity(later, -31_863, provenance)))
                .isEqualTo(initial);
        assertThat(PositionPackageFingerprint.fingerprint(
                PositionPackageFingerprint.focusedIdentity(first, -31_862, provenance)))
                .isNotEqualTo(initial);
        var changedSource = new PositionPackageFingerprint.EntryProvenance(
                provenance.createdAt(), provenance.dataProvenance(), provenance.dataAge(),
                "broker statement", provenance.entrySnapshotFingerprint());
        assertThat(PositionPackageFingerprint.fingerprint(
                PositionPackageFingerprint.focusedIdentity(first, -31_863, changedSource)))
                .isNotEqualTo(initial);
    }

    @Test void trackedIdentityBindsCurrentRevisionReceiptAndOpeningLots() {
        var leg = new PositionPackage.Leg(0, "BUY", "OPTION", "AAPL", "CALL",
                new BigDecimal("250"), LocalDate.parse("2026-08-21"), 1, 100,
                new BigDecimal("4.50"), PositionDomain.PriceAuthority.BROKER_REPORTED);
        var position = new PositionPackage("structure-a", PositionDomain.PackageSource.TRACKED_STRUCTURE,
                PositionDomain.ExecutionLane.REAL, "AAPL", 1, 45_000L,
                OffsetDateTime.parse("2026-07-20T12:00:00Z"), List.of(leg));
        var lot = new PositionPackageFingerprint.TrackedLotProvenance(
                "lot-a", "transaction-a", 0, "2026-07-18T15:00:00Z",
                "BROKER", "order-41", "b".repeat(64));
        var source = new PositionPackageFingerprint.SourceIdentity(
                "revision-a", "receipt-a", "2026-07-18T15:00:01Z", List.of(lot));
        var provenance = new PositionPackageFingerprint.EntryProvenance(
                "2026-07-18T15:00:01Z", "BROKER_REPORTED", "RECORDED",
                "BROKER", null, source);
        String initial = PositionPackageFingerprint.fingerprint(
                PositionPackageFingerprint.focusedIdentity(position, 45_000L, provenance));

        var changedRevision = new PositionPackageFingerprint.SourceIdentity(
                "revision-b", source.receiptId(), source.revisionCreatedAt(), source.lots());
        var changedLot = new PositionPackageFingerprint.SourceIdentity(
                source.structureRevisionId(), source.receiptId(), source.revisionCreatedAt(),
                List.of(new PositionPackageFingerprint.TrackedLotProvenance(
                        "lot-b", lot.openingTransactionId(), lot.openingLegNo(), lot.openedAt(),
                        lot.transactionSource(), lot.externalRef(), lot.importPayloadFingerprint())));
        assertThat(PositionPackageFingerprint.fingerprint(PositionPackageFingerprint.focusedIdentity(
                position, 45_000L, new PositionPackageFingerprint.EntryProvenance(
                        provenance.createdAt(), provenance.dataProvenance(), provenance.dataAge(),
                        provenance.dataSource(), provenance.entrySnapshotFingerprint(), changedRevision))))
                .isNotEqualTo(initial);
        assertThat(PositionPackageFingerprint.fingerprint(PositionPackageFingerprint.focusedIdentity(
                position, 45_000L, new PositionPackageFingerprint.EntryProvenance(
                        provenance.createdAt(), provenance.dataProvenance(), provenance.dataAge(),
                        provenance.dataSource(), provenance.entrySnapshotFingerprint(), changedLot))))
                .isNotEqualTo(initial);
    }
}
