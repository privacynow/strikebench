package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.recommend.LegView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Historical structure-fit: containment for defined-range structures, breakeven percentile for
 * one-sided structures, expected-move coverage — always labeled history ≠ forecast, silent on
 * thin history.
 */
class HistoryFitTest {

    /** Gentle oscillation around $252 — a range-bound history a condor would have contained. */
    private static List<Double> chopCloses(int n) {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < n; i++) closes.add(252.0 + 6.0 * Math.sin(i / 5.0));
        return closes;
    }

    private Candidate condor() {
        List<LegView> legs = List.of(
                new LegView("SELL", "PUT", "245", "2026-08-21", 1, "2.00", 100, "OPEN"),
                new LegView("BUY", "PUT", "240", "2026-08-21", 1, "1.20", 100, "OPEN"),
                new LegView("SELL", "CALL", "260", "2026-08-21", 1, "2.10", 100, "OPEN"),
                new LegView("BUY", "CALL", "265", "2026-08-21", 1, "1.30", 100, "OPEN"));
        return new Candidate("IRON_CONDOR", "Iron condor", "range_credit", "245/240/260/265",
                legs, 1, 16_000L, 16_000L, 34_000L, List.of("243.40", "261.60"),
                0.55, 900L, 0.70, "DELAYED", List.of(), 0.6,
                "Range income", "Keep the credit inside the range", "A breakout through either wing",
                "A close beyond a wing", "You collect the credit",
                "INCOME", List.of("INCOME"), 0.30, null, null, null, false, null, null);
    }

    private Candidate longCall() {
        List<LegView> legs = List.of(new LegView("BUY", "CALL", "260", "2026-08-21", 1, "4.00", 100, "OPEN"));
        return new Candidate("LONG_CALL", "Long call", "single_long", "BUY 260C",
                legs, 1, -40_000L, null, 40_000L, List.of("264.00"),
                0.35, -500L, 0.70, "DELAYED", List.of(), 0.6,
                "Upside bet", "Uncapped above the strike", "Theta if it stalls",
                "No move by expiry", "You pay the debit",
                "DIRECTIONAL", List.of("DIRECTIONAL"), null, null, null, null, false, null, null);
    }

    private EvalContext ctx(List<Double> closes) {
        return new EvalContext("AAPL", 25_200L, java.time.LocalDate.parse("2026-07-22"), 21, 0.30, 0.25,
                List.of(0.22, 0.30, 0.38), 10_000_000L, true, 65, 0, 0.04,
                io.liftandshift.strikebench.model.DataEvidence.of("treasury",
                        io.liftandshift.strikebench.model.Freshness.EOD), null, null, null, closes);
    }

    @Test void definedRangeStructuresGetContainmentAndExpectedMoveCoverage() {
        List<String> lines = HistoryFit.sentences(condor(), ctx(chopCloses(250)));
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains("21-session windows")
                .contains("stayed inside this range")
                .contains("History is not a forecast");
        assertThat(lines.get(1)).contains("expected move").contains("x the options-implied");
    }

    @Test void rangeBoundHistoryContainsBetterThanTrendingHistory() {
        // Containment measures RELATIVE terminal moves per window, so even a range-bound
        // oscillation leaves some windows outside; the discriminating property is that a
        // trending history scores clearly worse than a ranging one for the same range.
        List<Double> trend = new ArrayList<>();
        for (int i = 0; i < 250; i++) trend.add(200.0 * Math.pow(1.0018, i)); // ~+9% per 50 sessions
        int ranging = containedPct(HistoryFit.sentences(condor(), ctx(chopCloses(250))).getFirst());
        int trending = containedPct(HistoryFit.sentences(condor(), ctx(trend)).getFirst());
        assertThat(ranging).isGreaterThan(55);
        assertThat(ranging).isGreaterThan(trending + 20);
    }

    private static int containedPct(String containment) {
        var m = java.util.regex.Pattern.compile("(\\d+)% of the time").matcher(containment);
        assertThat(m.find()).isTrue();
        return Integer.parseInt(m.group(1));
    }

    @Test void oneSidedStructuresGetABreakevenPercentileNotContainment() {
        List<String> lines = HistoryFit.sentences(longCall(), ctx(chopCloses(250)));
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst()).contains("breakeven sits")
                .contains("at least that large")
                .contains("History is not a forecast");
    }

    @Test void thinHistoryYieldsNothingRatherThanAGuess() {
        assertThat(HistoryFit.sentences(condor(), ctx(chopCloses(40)))).isEmpty();
        assertThat(HistoryFit.sentences(condor(), ctx(List.of()))).isEmpty();
    }
}
