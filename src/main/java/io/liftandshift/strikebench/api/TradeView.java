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
        // The existing MarkView component-availability authority, lifted onto roster rows so a
        // missing current fact always says why rather than becoming an unexplained null.
        @JsonInclude(JsonInclude.Include.NON_NULL)
        TradeService.CurrentMarketAvailability currentMarketAvailability,
        // B2: the exact terminal-payoff polyline for a HELD line, same receipt shape the idea
        // candidate carries, so the held bloom/spectrum interpolates a server curve, never legs.
        @JsonInclude(JsonInclude.Include.NON_NULL)
        io.liftandshift.strikebench.eval.RiskProfile.TerminalPayoff terminalPayoff,
        // B6: held greeks in the ONE canonical unit (deltaShares, gammaSharesPerDollar,
        // thetaCentsPerDay, vegaCentsPerPoint) — the same contract ideas and the canvas report.
        @JsonInclude(JsonInclude.Include.NON_NULL)
        GreeksView greeks,
        // One priced checkpoint per NAMED story move, the same shape and move set an idea candidate
        // carries. Held lines had no per-move receipt at all, so the desk priced the stories itself.
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        java.util.List<io.liftandshift.strikebench.eval.RiskProfile.Scenario> scenarios,
        // §5.4: "If price holds" — the terminalPayoff curve evaluated by the ENGINE at one declared
        // spot. The browser used to interpolate this figure itself and print it as a financial fact.
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
                t.dataAge(), t.dataSource(), null, null, null, null, null, null, null);
    }

    public TradeView withCurrentMark(Long unrealized, Long decisionUnrealized,
                                     TradeService.CurrentMarketAvailability availability) {
        return new TradeView(id, symbol, strategy, status, qty, legs, thesis, horizon, riskMode,
                entryUnderlyingCents, entryPrice, maxLossCents, maxProfitCents,
                breakevens, popEntry, realizedPnlCents,
                decisionPnlCents, closeReason, entrySnapshot, isLive, createdAt, closedAt,
                updatedAt, intent, sharesLocked, orderInstruction, dataProvenance, dataAge,
                dataSource, unrealized, decisionUnrealized, availability, terminalPayoff, greeks,
                scenarios, spotPnl);
    }

    /**
     * Attach the held-line display receipts: the ONE terminal-payoff curve, the ONE canonical greeks
     * view, the named-story checkpoints, and the engine's own "if price holds" P/L read off that
     * same curve. Tail analysis is deliberately absent here: the exact lifecycle evaluation owns
     * it, and a roster row must not manufacture a second tail from fallback IV/event assumptions.
     */
    public TradeView withHeldReceipts(io.liftandshift.strikebench.eval.RiskProfile.TerminalPayoff payoff,
                                      GreeksView heldGreeks,
                                      java.util.List<io.liftandshift.strikebench.eval.RiskProfile.Scenario> heldScenarios,
                                      ApiResponses.HeldSpotPnl heldSpotPnl) {
        return new TradeView(id, symbol, strategy, status, qty, legs, thesis, horizon, riskMode,
                entryUnderlyingCents, entryPrice, maxLossCents, maxProfitCents,
                breakevens, popEntry, realizedPnlCents,
                decisionPnlCents, closeReason, entrySnapshot, isLive, createdAt, closedAt,
                updatedAt, intent, sharesLocked, orderInstruction, dataProvenance, dataAge,
                dataSource, unrealizedPnlCents, decisionUnrealizedPnlCents,
                currentMarketAvailability, payoff, heldGreeks,
                heldScenarios, heldSpotPnl);
    }
}
