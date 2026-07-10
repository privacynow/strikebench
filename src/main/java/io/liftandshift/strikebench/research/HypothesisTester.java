package io.liftandshift.strikebench.research;

import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.model.Candle;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Phase-5 research lab: tests a simple, mechanical market hypothesis against history with an honest
 * significance test. The built-in hypothesis: "after N-day momentum ≥ threshold, is the next
 * M-day return positive more often than chance (50%)?" — evaluated over the underlying's candles
 * with a two-sided z-test on the win rate. Reports the sample, edge, z-score, and a plain verdict
 * (including "too few samples"). No look-ahead: each signal only looks forward from its own bar.
 */
public final class HypothesisTester {

    private static final double Z_95 = 1.96;
    private static final int MIN_SAMPLE = 20;

    private final MarketDataService market;

    public HypothesisTester(MarketDataService market) { this.market = market; }

    public record HypothesisRequest(String symbol, String from, String to,
                                    Integer lookbackDays, Double thresholdPct, Integer forwardDays) {}

    public record HypothesisResult(String symbol, String hypothesis, int sample, int wins,
                                   double winRate, double expectedByChance, double edgePct,
                                   double zScore, boolean significant, String verdict, List<String> notes) {}

    public HypothesisResult test(HypothesisRequest req) {
        return test(req, io.liftandshift.strikebench.db.AnalysisContext.OBSERVED);
    }

    /** Context-aware variant: the hypothesis runs over the caller's analysis dataset. */
    public HypothesisResult test(HypothesisRequest req, io.liftandshift.strikebench.db.AnalysisContext actx) {
        String symbol = req.symbol() == null ? "" : req.symbol().trim().toUpperCase(Locale.ROOT);
        if (symbol.isEmpty()) throw new IllegalArgumentException("symbol is required");
        LocalDate from = LocalDate.parse(req.from());
        LocalDate to = LocalDate.parse(req.to());
        int lookback = Math.clamp(req.lookbackDays() == null ? 20 : req.lookbackDays(), 1, 250);
        double threshold = req.thresholdPct() == null ? 0.0 : req.thresholdPct();
        int forward = Math.clamp(req.forwardDays() == null ? 10 : req.forwardDays(), 1, 120);

        List<Candle> candles = market.candles(symbol, from.minusDays(lookback + 15L), to, actx).stream()
                .filter(c -> !c.date().isAfter(to)).toList();
        List<String> notes = new ArrayList<>();

        int sample = 0, wins = 0;
        for (int i = lookback; i + forward < candles.size(); i++) {
            double c0 = candles.get(i).close().doubleValue();
            double base = candles.get(i - lookback).close().doubleValue();
            if (base <= 0) continue;
            double momentum = c0 / base - 1.0;
            if (momentum >= threshold / 100.0) {
                sample++;
                double fwd = candles.get(i + forward).close().doubleValue() / c0 - 1.0;
                if (fwd > 0) wins++;
            }
        }

        double winRate = sample == 0 ? 0 : (double) wins / sample;
        double z = sample == 0 ? 0 : (winRate - 0.5) / Math.sqrt(0.25 / sample);
        boolean significant = sample >= MIN_SAMPLE && Math.abs(z) >= Z_95;
        String hypothesis = "After " + lookback + "-day momentum ≥ " + threshold + "%, the next "
                + forward + "-day return on " + symbol + " is positive more often than chance.";
        String verdict;
        if (sample < MIN_SAMPLE) {
            verdict = "Too few signals (" + sample + ") to conclude — widen the window or relax the threshold.";
        } else if (significant) {
            verdict = winRate > 0.5 ? "Supported — a statistically significant positive edge in this window."
                    : "Rejected — a statistically significant NEGATIVE edge (the opposite of the claim).";
        } else {
            verdict = "Not statistically significant — consistent with chance.";
        }
        notes.add("Model result over one historical window, not a forecast. Survivorship/regime effects apply.");

        return new HypothesisResult(symbol, hypothesis, sample, wins, round(winRate), 0.5,
                round((winRate - 0.5) * 100), round(z), significant, verdict, notes);
    }

    private static double round(double v) { return Math.round(v * 1000.0) / 1000.0; }
}
