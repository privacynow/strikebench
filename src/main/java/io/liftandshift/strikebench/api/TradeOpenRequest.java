package io.liftandshift.strikebench.api;

import io.liftandshift.strikebench.recommend.LegView;
import io.liftandshift.strikebench.paper.OrderInstruction;

import java.util.List;

/** Complete wire request for previewing or opening a paper position. */
public record TradeOpenRequest(
        String symbol,
        String strategy,
        Integer qty,
        List<LegView> legs,
        String thesis,
        String horizon,
        String riskMode,
        String intent,
        Boolean useHeldShares,
        String recommendationId,
        Long feesOverrideCents,
        String source,
        List<String> acknowledgedRisks,
        String ackToken,
        String fillNature,
        OrderInstruction orderInstruction,
        String holdingsProvenance,
        String holdingsDestinationAccountId,
        String holdingsAccountType,
        Long holdingsObservedAtEpochMs
) {
    public TradeOpenRequest(String symbol, String strategy, Integer qty, List<LegView> legs,
                            String thesis, String horizon, String riskMode, String intent,
                            Boolean useHeldShares, String recommendationId, Long feesOverrideCents,
                            String source, List<String> acknowledgedRisks, String ackToken,
                            String fillNature, OrderInstruction orderInstruction,
                            String holdingsProvenance) {
        this(symbol, strategy, qty, legs, thesis, horizon, riskMode, intent, useHeldShares,
                recommendationId, feesOverrideCents, source, acknowledgedRisks, ackToken,
                fillNature, orderInstruction, holdingsProvenance, null, null, null);
    }

    public TradeOpenRequest(String symbol, String strategy, Integer qty, List<LegView> legs,
                            String thesis, String horizon, String riskMode, String intent,
                            Boolean useHeldShares, String recommendationId, Long feesOverrideCents,
                            String source, List<String> acknowledgedRisks, String ackToken,
                            String fillNature, OrderInstruction orderInstruction) {
        this(symbol, strategy, qty, legs, thesis, horizon, riskMode, intent, useHeldShares,
                recommendationId, feesOverrideCents, source, acknowledgedRisks, ackToken,
                fillNature, orderInstruction, null, null, null, null);
    }
}
