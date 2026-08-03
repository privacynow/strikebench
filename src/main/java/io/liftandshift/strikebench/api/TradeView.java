package io.liftandshift.strikebench.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.liftandshift.strikebench.paper.OrderInstruction;
import io.liftandshift.strikebench.paper.PackagePrice;
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
        PackagePrice entryPrice,
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
        @JsonInclude(JsonInclude.Include.NON_NULL)
        io.liftandshift.strikebench.eval.RiskProfile.TerminalPayoff terminalPayoff,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        ApiResponses.HeldScenarios scenarios,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        ApiResponses.HeldSpotPnl spotPnl
) {
    public TradeView {
        if (entryPrice == null) {
            throw new IllegalArgumentException("held trade wire requires its recorded entry-price result");
        }
    }

    @SuppressWarnings("unchecked")
    public static TradeView of(TradeRecord t) {
        Map<String, Object> snapshot = t.entrySnapshotJson() == null || t.entrySnapshotJson().isBlank()
                ? Map.of() : Json.read(t.entrySnapshotJson(), Map.class);
        return new TradeView(t.id(), t.symbol(), t.strategy(), t.status(), t.qty(),
                t.legs().stream().map(leg -> LegView.of(leg, null)).toList(),
                t.thesis(), t.horizon(), t.riskMode(),
                t.entryUnderlyingCents(), TradeService.recordedEntryPrice(t),
                t.maxLossCents(), t.maxProfitCents(),
                t.breakevens(), t.popEntry(), t.realizedPnlCents(),
                t.decisionPnlCents(),
                t.closeReason(), snapshot, t.isLive(), t.createdAt(), t.closedAt(), t.updatedAt(),
                t.intent(), t.sharesLocked(), t.orderInstruction(), t.dataProvenance(),
                t.dataAge(), t.dataSource(), null, null, null);
    }

    public TradeView withHeldAnalyses(io.liftandshift.strikebench.eval.RiskProfile.TerminalPayoff payoff,
                                      ApiResponses.HeldScenarios heldScenarios,
                                      ApiResponses.HeldSpotPnl heldSpotPnl) {
        return new TradeView(id, symbol, strategy, status, qty, legs, thesis, horizon, riskMode,
                entryUnderlyingCents, entryPrice, maxLossCents, maxProfitCents,
                breakevens, popEntry, realizedPnlCents,
                decisionPnlCents, closeReason, entrySnapshot, isLive, createdAt, closedAt,
                updatedAt, intent, sharesLocked, orderInstruction, dataProvenance, dataAge,
                dataSource, payoff, heldScenarios, heldSpotPnl);
    }
}
