package io.liftandshift.strikebench.paper;

import com.fasterxml.jackson.core.type.TypeReference;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.util.Json;

import java.util.List;

/** A persisted trade. Mirrors the trades table; legs/breakevens are JSON columns. */
public record TradeRecord(
        String id,
        String accountId,
        String symbol,
        String strategy,
        String status,
        int qty,
        List<Leg> legs,
        String thesis,
        String horizon,
        String riskMode,
        long entryUnderlyingCents,
        long entryNetPremiumCents,
        long maxLossCents,
        Long maxProfitCents,          // null = unbounded upside (stock-backed only)
        List<String> breakevens,
        Double popEntry,
        long feesOpenCents,
        long feesCloseCents,
        Long realizedPnlCents,
        String closeReason,
        String entrySnapshotJson,
        boolean isLive,
        String createdAt,
        String closedAt,
        String updatedAt,
        String intent,        // StrategyIntent name the trade was placed under, nullable
        long sharesLocked,    // held shares pledged as short-call coverage while ACTIVE
        String origin,        // PAPER | EXTERNAL (a real broker fill recorded for the learning loop)
        Long proposedNetCents,// the USER'S package price when one was set (provenance), nullable
        String executedAt,    // real execution time for EXTERNAL trades (ISO), nullable
        String broker,        // e.g. ETRADE, nullable
        String orderRef,      // broker order id, nullable
        String dataProvenance,
        String dataAge,
        String dataSource
) {
    /** Pre-provenance shape. */
    public TradeRecord(String id, String accountId, String symbol, String strategy, String status,
                       int qty, List<Leg> legs, String thesis, String horizon, String riskMode,
                       long entryUnderlyingCents, long entryNetPremiumCents, long maxLossCents,
                       Long maxProfitCents, List<String> breakevens, Double popEntry,
                       long feesOpenCents, long feesCloseCents, Long realizedPnlCents,
                       String closeReason, String entrySnapshotJson, boolean isLive,
                       String createdAt, String closedAt, String updatedAt, String intent,
                       long sharesLocked, String origin) {
        this(id, accountId, symbol, strategy, status, qty, legs, thesis, horizon, riskMode,
                entryUnderlyingCents, entryNetPremiumCents, maxLossCents, maxProfitCents, breakevens,
                popEntry, feesOpenCents, feesCloseCents, realizedPnlCents, closeReason,
                entrySnapshotJson, isLive, createdAt, closedAt, updatedAt, intent, sharesLocked, origin,
                null, null, null, null, null, null, null);
    }

    /** Pre-origin shape. */
    public TradeRecord(String id, String accountId, String symbol, String strategy, String status,
                       int qty, List<Leg> legs, String thesis, String horizon, String riskMode,
                       long entryUnderlyingCents, long entryNetPremiumCents, long maxLossCents,
                       Long maxProfitCents, List<String> breakevens, Double popEntry,
                       long feesOpenCents, long feesCloseCents, Long realizedPnlCents,
                       String closeReason, String entrySnapshotJson, boolean isLive,
                       String createdAt, String closedAt, String updatedAt, String intent, long sharesLocked) {
        this(id, accountId, symbol, strategy, status, qty, legs, thesis, horizon, riskMode,
                entryUnderlyingCents, entryNetPremiumCents, maxLossCents, maxProfitCents, breakevens,
                popEntry, feesOpenCents, feesCloseCents, realizedPnlCents, closeReason,
                entrySnapshotJson, isLive, createdAt, closedAt, updatedAt, intent, sharesLocked, "PAPER");
    }

    public boolean external() { return "EXTERNAL".equals(origin); }

    public static final String ACTIVE = "ACTIVE";
    public static final String CLOSED = "CLOSED";
    public static final String EXPIRED = "EXPIRED";
    public static final String DELETED = "DELETED";

    public static List<Leg> legsFromJson(String json) {
        return Json.read(json, new TypeReference<List<Leg>>() {});
    }

    public static List<String> breakevensFromJson(String json) {
        return Json.read(json, new TypeReference<List<String>>() {});
    }
}
