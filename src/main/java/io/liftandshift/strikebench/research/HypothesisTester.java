package io.liftandshift.strikebench.research;

import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.model.DataAge;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.DataProvenance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Compatibility adapter for the original hypothesis endpoint. The old implementation duplicated
 * event detection and compared momentum with a bare 50% coin flip. It now delegates to the same
 * baseline-relative {@link ResearchQuestionEngine} used by the Research workspace, preserving the
 * request/response shape without maintaining a second statistical engine.
 */
public final class HypothesisTester {

    private final ResearchQuestionEngine engine;

    public HypothesisTester(MarketDataService market) { this.engine = new ResearchQuestionEngine(market); }

    public record HypothesisRequest(String symbol, String from, String to,
                                    Integer lookbackDays, Double thresholdPct, Integer forwardDays) {}

    public record HypothesisResult(String symbol, String hypothesis, int sample, int wins,
                                   double winRate, double expectedByChance, double edgePct,
                                   double zScore, boolean significant, String verdict,
                                   DataEvidence evidence, List<String> notes) {}

    public HypothesisResult test(HypothesisRequest req) {
        return test(req, io.liftandshift.strikebench.db.AnalysisContext.OBSERVED, null);
    }

    /** Context-aware variant: the hypothesis runs over the caller's analysis dataset. */
    public HypothesisResult test(HypothesisRequest req, io.liftandshift.strikebench.db.AnalysisContext actx) {
        return test(req, actx, null);
    }

    /** Context- and world-aware variant: history must come from the selected analysis lane. */
    public HypothesisResult test(HypothesisRequest req, io.liftandshift.strikebench.db.AnalysisContext actx,
                                 String worldId) {
        Map<String, Object> params = Map.of(
                "lookback", req.lookbackDays() == null ? 20 : req.lookbackDays(),
                "thresholdPct", req.thresholdPct() == null ? 0.0 : req.thresholdPct(),
                "forward", req.forwardDays() == null ? 10 : req.forwardDays());
        var r = engine.run(new ResearchQuestionEngine.RunRequest(
                "momentum", req.symbol(), req.from(), req.to(), params), actx, worldId);
        int sample = r.conditioned().sample();
        double winRate = r.conditioned().winRatePct() / 100.0;
        int wins = (int) Math.round(sample * winRate);
        List<String> notes = new ArrayList<>(r.notes());
        notes.add("Compatibility response: computed by the shared Research event-study engine against the stock's non-signal baseline, not a 50% coin flip.");
        return new HypothesisResult(r.symbol(), r.question(), sample, wins, round(winRate),
                round(r.baseline().winRatePct() / 100.0), r.winRateEdgePct(), r.zScore(),
                r.significant(), r.verdict(), evidence(r.evidence()), notes);
    }

    private static DataEvidence evidence(String label) {
        return switch (label == null ? "MISSING" : label) {
            case "OBSERVED_LIVE" -> new DataEvidence(DataProvenance.OBSERVED, DataAge.REALTIME, "event study");
            case "OBSERVED_DELAYED" -> new DataEvidence(DataProvenance.OBSERVED, DataAge.DELAYED, "event study");
            case "OBSERVED_EOD" -> new DataEvidence(DataProvenance.OBSERVED, DataAge.EOD, "event study");
            case "DEMO_FIXTURE" -> new DataEvidence(DataProvenance.DEMO, DataAge.NOT_APPLICABLE, "demo event study");
            case "MODELED" -> new DataEvidence(DataProvenance.MODELED, DataAge.NOT_APPLICABLE, "modeled event study");
            default -> DataEvidence.missing("event study");
        };
    }

    private static double round(double v) { return Math.round(v * 1000.0) / 1000.0; }
}
