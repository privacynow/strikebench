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
        Long proposedNetCents,
        Long feesOverrideCents,
        String source,
        List<String> acknowledgedRisks,
        String ackToken,
        String fillNature,
        OrderInstruction orderInstruction
) {
    /** Source-compatible constructor for internal callers written before typed order instructions. */
    public TradeOpenRequest(String symbol, String strategy, Integer qty, List<LegView> legs,
                            String thesis, String horizon, String riskMode, String intent,
                            Boolean useHeldShares, String recommendationId, Long proposedNetCents,
                            Long feesOverrideCents, String source, List<String> acknowledgedRisks,
                            String ackToken, String fillNature) {
        this(symbol, strategy, qty, legs, thesis, horizon, riskMode, intent, useHeldShares,
                recommendationId, proposedNetCents, feesOverrideCents, source,
                acknowledgedRisks, ackToken, fillNature, null);
    }
}
