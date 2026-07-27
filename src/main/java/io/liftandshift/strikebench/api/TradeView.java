package io.liftandshift.strikebench.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.liftandshift.strikebench.model.GreeksView;
import io.liftandshift.strikebench.paper.OrderInstruction;
import io.liftandshift.strikebench.paper.PackagePriceReceipt;
import io.liftandshift.strikebench.paper.TradeRecord;
import io.liftandshift.strikebench.paper.TradeService;
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
        PackagePriceReceipt entryPrice,
        long maxLossCents,
        Long maxProfitCents,
        List<String> breakevens,
        Double popEntry,
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
        @JsonInclude(JsonInclude.Include.NON_NULL) OrderInstruction orderInstruction,
        String dataProvenance,
        String dataAge,
        String dataSource,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long unrealizedPnlCents,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long decisionUnrealizedPnlCents,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long currentUnderlyingCents,
        @JsonInclude(JsonInclude.Include.NON_NULL) PackagePriceReceipt currentClosePrice,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long indicativeUnrealizedPnlCents,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long indicativeDecisionUnrealizedPnlCents,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        TradeService.CurrentMarketAvailability currentMarketAvailability,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        io.liftandshift.strikebench.eval.RiskProfile.TerminalPayoff terminalPayoff,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        GreeksView greeks,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        ApiResponses.HeldScenarios scenarios,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        ApiResponses.HeldSpotPnl spotPnl
) {
    public TradeView {
        if (entryPrice == null) {
            throw new IllegalArgumentException("held trade wire requires its recorded entry-price receipt");
        }
    }

    @SuppressWarnings("unchecked")
    public static TradeView of(TradeRecord t) {
        Map<String, Object> snapshot = t.entrySnapshotJson() == null || t.entrySnapshotJson().isBlank()
                ? Map.of() : Json.read(t.entrySnapshotJson(), Map.class);
        OrderInstruction orderInstruction = snapshot.get("orderInstruction") == null
                ? (t.orderLimitNetCents() == null ? null : OrderInstruction.limit(t.orderLimitNetCents()))
                : Json.MAPPER.convertValue(snapshot.get("orderInstruction"), OrderInstruction.class);
        return new TradeView(t.id(), t.symbol(), t.strategy(), t.status(), t.qty(),
                t.legs().stream().map(LegView::of).toList(),
                t.thesis(), t.horizon(), t.riskMode(),
                t.entryUnderlyingCents(), TradeService.recordedEntryPrice(t),
                t.maxLossCents(), t.maxProfitCents(),
                t.breakevens(), t.popEntry(), t.realizedPnlCents(),
                t.decisionPnlCents(),
                t.closeReason(), snapshot, t.isLive(), t.createdAt(), t.closedAt(), t.updatedAt(),
                t.intent(), t.sharesLocked(), orderInstruction, t.dataProvenance(),
                t.dataAge(), t.dataSource(), null, null, null, null, null, null, null,
                null, null, null, null);
    }

    public TradeView withCurrentMark(Long currentUnderlying, PackagePriceReceipt closePrice,
                                     Long unrealized, Long decisionUnrealized,
                                     Long indicativeUnrealized, Long indicativeDecisionUnrealized,
                                     TradeService.CurrentMarketAvailability availability) {
        return new TradeView(id, symbol, strategy, status, qty, legs, thesis, horizon, riskMode,
                entryUnderlyingCents, entryPrice, maxLossCents, maxProfitCents,
                breakevens, popEntry, realizedPnlCents,
                decisionPnlCents, closeReason, entrySnapshot, isLive, createdAt, closedAt,
                updatedAt, intent, sharesLocked, orderInstruction, dataProvenance, dataAge,
                dataSource, unrealized, decisionUnrealized, currentUnderlying, closePrice,
                indicativeUnrealized, indicativeDecisionUnrealized, availability, terminalPayoff,
                greeks, scenarios, spotPnl);
    }

    public TradeView withHeldReceipts(io.liftandshift.strikebench.eval.RiskProfile.TerminalPayoff payoff,
                                      GreeksView heldGreeks,
                                      ApiResponses.HeldScenarios heldScenarios,
                                      ApiResponses.HeldSpotPnl heldSpotPnl) {
        return new TradeView(id, symbol, strategy, status, qty, legs, thesis, horizon, riskMode,
                entryUnderlyingCents, entryPrice, maxLossCents, maxProfitCents,
                breakevens, popEntry, realizedPnlCents,
                decisionPnlCents, closeReason, entrySnapshot, isLive, createdAt, closedAt,
                updatedAt, intent, sharesLocked, orderInstruction, dataProvenance, dataAge,
                dataSource, unrealizedPnlCents, decisionUnrealizedPnlCents,
                currentUnderlyingCents, currentClosePrice, indicativeUnrealizedPnlCents,
                indicativeDecisionUnrealizedPnlCents, currentMarketAvailability, payoff, heldGreeks,
                heldScenarios, heldSpotPnl);
    }
}
