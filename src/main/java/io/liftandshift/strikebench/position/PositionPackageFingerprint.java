package io.liftandshift.strikebench.position;

import io.liftandshift.strikebench.util.Json;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** Stable economic/provenance identity shared by position receipts and transformations. */
public final class PositionPackageFingerprint {
    public static final String CONTRACT_VERSION = "focused-position-package-2";

    private PositionPackageFingerprint() {}

    public record TrackedLotProvenance(String lotId, String openingTransactionId,
                                       int openingLegNo, String openedAt,
                                       String transactionSource, String externalRef,
                                       String importPayloadFingerprint) {}
    public record SourceIdentity(String structureRevisionId, String receiptId,
                                 String revisionCreatedAt,
                                 List<TrackedLotProvenance> lots) {
        public SourceIdentity {
            lots = lots == null ? List.of() : lots.stream()
                    .sorted(Comparator.comparing(TrackedLotProvenance::lotId)
                            .thenComparing(TrackedLotProvenance::openingTransactionId)
                            .thenComparingInt(TrackedLotProvenance::openingLegNo))
                    .toList();
        }
    }
    public record EntryProvenance(String createdAt, String dataProvenance, String dataAge,
                                  String dataSource, String entrySnapshotFingerprint,
                                  SourceIdentity sourceIdentity) {
        public EntryProvenance(String createdAt, String dataProvenance, String dataAge,
                               String dataSource, String entrySnapshotFingerprint) {
            this(createdAt, dataProvenance, dataAge, dataSource, entrySnapshotFingerprint, null);
        }
    }
    public record CanonicalPackage(PositionDomain.PackageSource source,
                                   PositionDomain.ExecutionLane lane,
                                   String symbol,
                                   long packageQuantity,
                                   Long exactPackageCashCents,
                                   List<CanonicalLeg> legs) {}
    public record CanonicalLeg(String action, String instrumentType, String symbol,
                               String optionType, String strike, String expiration,
                               long quantity, int multiplier, String price,
                               PositionDomain.PriceAuthority priceAuthority) {
        String sortKey() {
            return String.join("|", action, instrumentType, symbol, optionType, strike,
                    expiration == null ? "" : expiration, String.valueOf(quantity),
                    String.valueOf(multiplier), price,
                    priceAuthority == null ? "" : priceAuthority.name());
        }
    }
    public record FocusedIdentity(String contractVersion, CanonicalPackage positionPackage,
                                  long entryBasisCents, EntryProvenance entryProvenance) {}

    /** Excludes artifact id and valuation time; the route separately binds the exact focus key. */
    public static CanonicalPackage canonical(PositionPackage position) {
        if (position == null) return null;
        List<CanonicalLeg> legs = position.legs().stream().map(leg -> new CanonicalLeg(
                        upper(leg.action()), upper(leg.instrumentType()), upper(leg.symbol()),
                        upper(leg.optionType()), decimal(leg.strike()),
                        leg.expiration() == null ? null : leg.expiration().toString(),
                        leg.quantity(), leg.multiplier(), decimal(leg.price()), leg.priceAuthority()))
                .sorted(Comparator.comparing(CanonicalLeg::sortKey)).toList();
        return new CanonicalPackage(position.source(), position.lane(), upper(position.symbol()),
                position.packageQuantity(), position.exactPackageCashCents(), legs);
    }

    public static FocusedIdentity focusedIdentity(PositionPackage position, long entryBasisCents,
                                                   EntryProvenance provenance) {
        return new FocusedIdentity(CONTRACT_VERSION, canonical(position), entryBasisCents, provenance);
    }

    public static String fingerprint(FocusedIdentity identity) {
        return sha256(Json.canonical(identity));
    }

    public static String entrySnapshotFingerprint(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) return null;
        return sha256(Json.canonical(Json.parse(rawJson)));
    }

    private static String sha256(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String decimal(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
