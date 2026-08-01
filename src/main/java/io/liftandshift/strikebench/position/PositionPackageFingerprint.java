package io.liftandshift.strikebench.position;

import io.liftandshift.strikebench.util.Json;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** Stable economic/provenance identity shared by position analyses and transformations. */
public final class PositionPackageFingerprint {
    public static final String SCHEMA_VERSION = "focused-position-package-2";

    private PositionPackageFingerprint() {}

    public record TrackedLotProvenance(String lotId, String openingTransactionId,
                                       int openingLegNo, String openedAt,
                                       String transactionSource, String externalRef,
                                       String importPayloadFingerprint) {}
    public record SourceIdentity(String structureRevisionId, String artifactId,
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
    public record NormalizedPackage(PositionDomain.PackageSource source,
                                   PositionDomain.BookType bookType,
                                   String symbol,
                                   long packageQuantity,
                                   Long exactPackageCashCents,
                                   List<NormalizedLeg> legs) {}
    public record NormalizedLeg(String action, String instrumentType, String symbol,
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
    public record FocusedIdentity(String schemaVersion, NormalizedPackage positionPackage,
                                  long entryBasisCents, EntryProvenance entryProvenance) {}

    /** Excludes artifact id and valuation time; the route separately binds the exact focus key. */
    public static NormalizedPackage normalized(PositionPackage position) {
        if (position == null) return null;
        List<NormalizedLeg> legs = position.legs().stream().map(leg -> new NormalizedLeg(
                        upper(leg.action()), upper(leg.instrumentType()), leg.symbol(),
                        upper(leg.optionType()), decimal(leg.strike()),
                        leg.expiration() == null ? null : leg.expiration().toString(),
                        leg.quantity(), leg.multiplier(), decimal(leg.price()), leg.priceAuthority()))
                .sorted(Comparator.comparing(NormalizedLeg::sortKey)).toList();
        return new NormalizedPackage(position.source(), position.bookType(), position.symbol(),
                position.packageQuantity(), position.exactPackageCashCents(), legs);
    }

    public static FocusedIdentity focusedIdentity(PositionPackage position, long entryBasisCents,
                                                   EntryProvenance provenance) {
        return new FocusedIdentity(SCHEMA_VERSION, normalized(position), entryBasisCents, provenance);
    }

    public static String fingerprint(FocusedIdentity identity) {
        return sha256(Json.stable(v2IdentityEncoding(identity)));
    }

    /**
     * Keep the byte representation used by stored V2 fingerprints stable while allowing the
     * public Java/API model to use clear domain names. This private encoder is not an API or a
     * compatibility response; it is the immutable input to already-persisted SHA-256 identities.
     */
    private static ObjectNode v2IdentityEncoding(FocusedIdentity identity) {
        ObjectNode root = Json.obj();
        root.put("contractVersion", identity.schemaVersion());
        NormalizedPackage position = identity.positionPackage();
        if (position != null) {
            ObjectNode packageNode = root.putObject("positionPackage");
            packageNode.put("source", position.source().name());
            packageNode.put("lane", switch (position.bookType()) {
                case TRACKED -> "REAL";
                case PRACTICE -> "PRACTICE";
                case NONE -> "NONE";
            });
            putText(packageNode, "symbol", position.symbol());
            packageNode.put("packageQuantity", position.packageQuantity());
            if (position.exactPackageCashCents() != null) {
                packageNode.put("exactPackageCashCents", position.exactPackageCashCents());
            }
            ArrayNode legs = packageNode.putArray("legs");
            for (NormalizedLeg leg : position.legs()) {
                ObjectNode item = legs.addObject();
                putText(item, "action", leg.action());
                putText(item, "instrumentType", leg.instrumentType());
                putText(item, "symbol", leg.symbol());
                putText(item, "optionType", leg.optionType());
                putText(item, "strike", leg.strike());
                putText(item, "expiration", leg.expiration());
                item.put("quantity", leg.quantity());
                item.put("multiplier", leg.multiplier());
                putText(item, "price", leg.price());
                if (leg.priceAuthority() != null) {
                    item.put("priceAuthority", leg.priceAuthority().name());
                }
            }
        }
        root.put("entryBasisCents", identity.entryBasisCents());
        if (identity.entryProvenance() != null) {
            root.set("entryProvenance", v2EntryEncoding(identity.entryProvenance()));
        }
        return root;
    }

    private static ObjectNode v2EntryEncoding(EntryProvenance entry) {
        ObjectNode node = Json.obj();
        putText(node, "createdAt", entry.createdAt());
        putText(node, "dataProvenance", entry.dataProvenance());
        putText(node, "dataAge", entry.dataAge());
        putText(node, "dataSource", entry.dataSource());
        putText(node, "entrySnapshotFingerprint", entry.entrySnapshotFingerprint());
        SourceIdentity source = entry.sourceIdentity();
        if (source != null) {
            ObjectNode sourceNode = node.putObject("sourceIdentity");
            putText(sourceNode, "structureRevisionId", source.structureRevisionId());
            putText(sourceNode, "receiptId", source.artifactId());
            putText(sourceNode, "revisionCreatedAt", source.revisionCreatedAt());
            ArrayNode lots = sourceNode.putArray("lots");
            for (TrackedLotProvenance lot : source.lots()) {
                ObjectNode item = lots.addObject();
                putText(item, "lotId", lot.lotId());
                putText(item, "openingTransactionId", lot.openingTransactionId());
                item.put("openingLegNo", lot.openingLegNo());
                putText(item, "openedAt", lot.openedAt());
                putText(item, "transactionSource", lot.transactionSource());
                putText(item, "externalRef", lot.externalRef());
                putText(item, "importPayloadFingerprint", lot.importPayloadFingerprint());
            }
        }
        return node;
    }

    private static void putText(ObjectNode node, String key, String value) {
        if (value != null) node.put(key, value);
    }

    public static String entrySnapshotFingerprint(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) return null;
        return sha256(Json.stable(Json.parse(rawJson)));
    }

    private static String sha256(String normalized) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
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
