package io.liftandshift.strikebench.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.liftandshift.strikebench.paper.TradeRecord;
import io.liftandshift.strikebench.recommend.LegView;
import io.liftandshift.strikebench.util.Json;

import java.util.List;
import java.util.Map;

/** Wire form of a trade: plain types + LegViews, ready for JSON. */
public record TradeView(
        String id,
        String symbol,
        String strategy,
        String status,
        int qty,
        List<LegView> legs,
        String thesis,
        String horizon,
        String riskMode,
        long entryUnderlyingCents,
        long entryNetPremiumCents,
        long maxLossCents,
        Long maxProfitCents,
        List<String> breakevens,
        Double popEntry,
        long feesOpenCents,
        long feesCloseCents,
        Long realizedPnlCents,
        Long decisionPnlCents,
        String closeReason,
        Map<String, Object> entrySnapshot,
        boolean isLive,
        String createdAt,
        String closedAt,
        String updatedAt,
        String intent,
        long sharesLocked,
        Long proposedNetCents,
        String dataProvenance,
        String dataAge,
        String dataSource,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long unrealizedPnlCents,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long decisionUnrealizedPnlCents
) {
    @SuppressWarnings("unchecked")
    public static TradeView of(TradeRecord t) {
        Map<String, Object> snapshot = t.entrySnapshotJson() == null || t.entrySnapshotJson().isBlank()
                ? Map.of() : Json.read(t.entrySnapshotJson(), Map.class);
        return new TradeView(t.id(), t.symbol(), t.strategy(), t.status(), t.qty(),
                t.legs().stream().map(LegView::of).toList(),
                t.thesis(), t.horizon(), t.riskMode(),
                t.entryUnderlyingCents(), t.entryNetPremiumCents(), t.maxLossCents(), t.maxProfitCents(),
                t.breakevens(), t.popEntry(), t.feesOpenCents(), t.feesCloseCents(), t.realizedPnlCents(),
                t.decisionPnlCents(),
                t.closeReason(), snapshot, t.isLive(), t.createdAt(), t.closedAt(), t.updatedAt(),
                t.intent(), t.sharesLocked(), t.proposedNetCents(), t.dataProvenance(),
                t.dataAge(), t.dataSource(), null, null);
    }

    public TradeView withUnrealized(Long unrealized, Long decisionUnrealized) {
        return new TradeView(id, symbol, strategy, status, qty, legs, thesis, horizon, riskMode,
                entryUnderlyingCents, entryNetPremiumCents, maxLossCents, maxProfitCents,
                breakevens, popEntry, feesOpenCents, feesCloseCents, realizedPnlCents,
                decisionPnlCents, closeReason, entrySnapshot, isLive, createdAt, closedAt,
                updatedAt, intent, sharesLocked, proposedNetCents, dataProvenance, dataAge,
                dataSource, unrealized, decisionUnrealized);
    }
}
