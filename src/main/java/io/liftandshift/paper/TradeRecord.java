package io.liftandshift.paper;

import com.fasterxml.jackson.core.type.TypeReference;
import io.liftandshift.model.Leg;
import io.liftandshift.util.Json;

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
        long sharesLocked     // held shares pledged as short-call coverage while ACTIVE
) {
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
