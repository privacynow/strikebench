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
        @JsonInclude(JsonInclude.Include.NON_NULL) Long decisionUnrealizedPnlCents,
        // B2: the exact terminal-payoff polyline for a HELD line, same receipt shape the idea
        // candidate carries, so the held bloom/spectrum interpolates a server curve, never legs.
        @JsonInclude(JsonInclude.Include.NON_NULL)
        io.liftandshift.strikebench.eval.RiskProfile.TerminalPayoff terminalPayoff,
        // B6: held greeks in the ONE canonical unit (deltaShares, gammaSharesPerDollar,
        // thetaCentsPerDay, vegaCentsPerPoint) — the same contract ideas and the canvas report.
        @JsonInclude(JsonInclude.Include.NON_NULL)
        io.liftandshift.strikebench.sim.ScenarioCanvasValuator.Greeks greeks,
        // B13: the real-world / tail lane (Merton jump-mixture) for the held line, so trade.popEntry
        // can read a tail-aware POP and the desk gap dial reads a backend receipt. The full-fidelity
        // tail (live IV / IV-rank / DTE) rides the position-detail analysis; this roster row uses the
        // desk's documented fallbacks (IV-rank 55, expected move 6%) over the recorded entry curve.
        @JsonInclude(JsonInclude.Include.NON_NULL)
        io.liftandshift.strikebench.pricing.JumpMixtureTerminal.Tail jumpTail
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
                t.dataAge(), t.dataSource(), null, null, null, null, null);
    }

    public TradeView withUnrealized(Long unrealized, Long decisionUnrealized) {
        return new TradeView(id, symbol, strategy, status, qty, legs, thesis, horizon, riskMode,
                entryUnderlyingCents, entryNetPremiumCents, maxLossCents, maxProfitCents,
                breakevens, popEntry, feesOpenCents, feesCloseCents, realizedPnlCents,
                decisionPnlCents, closeReason, entrySnapshot, isLive, createdAt, closedAt,
                updatedAt, intent, sharesLocked, proposedNetCents, dataProvenance, dataAge,
                dataSource, unrealized, decisionUnrealized, terminalPayoff, greeks, jumpTail);
    }

    /** Attach the held-line display receipts (terminal payoff curve + canonical greeks + tail lane). */
    public TradeView withHeldReceipts(io.liftandshift.strikebench.eval.RiskProfile.TerminalPayoff payoff,
                                      io.liftandshift.strikebench.sim.ScenarioCanvasValuator.Greeks heldGreeks,
                                      io.liftandshift.strikebench.pricing.JumpMixtureTerminal.Tail heldJumpTail) {
        return new TradeView(id, symbol, strategy, status, qty, legs, thesis, horizon, riskMode,
                entryUnderlyingCents, entryNetPremiumCents, maxLossCents, maxProfitCents,
                breakevens, popEntry, feesOpenCents, feesCloseCents, realizedPnlCents,
                decisionPnlCents, closeReason, entrySnapshot, isLive, createdAt, closedAt,
                updatedAt, intent, sharesLocked, proposedNetCents, dataProvenance, dataAge,
                dataSource, unrealizedPnlCents, decisionUnrealizedPnlCents, payoff, heldGreeks, heldJumpTail);
    }
}
