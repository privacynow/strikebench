package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.recommend.Candidate;

import java.util.ArrayList;
import java.util.List;

/**
 * Historical structure-fit (folded Phase 10.3): how this exact structure's geometry would have
 * fared against the lane's own delivered history — terminal containment for defined-range
 * structures, breakeven distance as a percentile of delivered moves, and range width against
 * the options-implied expected move. Every sentence carries the "history ≠ forecast" honesty
 * label; thin history yields nothing rather than a guess.
 */
public final class HistoryFit {
    private static final int MIN_CLOSES = 60;

    private HistoryFit() {}

    public static List<String> sentences(Candidate c, EvalContext ctx) {
        List<Double> closes = ctx.trailingCloses();
        int dte = Math.max(1, ctx.daysToExpiry());
        long spotCents = ctx.underlyingCents();
        if (closes.size() < MIN_CLOSES + dte || spotCents <= 0 || c.breakevens() == null
                || c.breakevens().isEmpty()) {
            return List.of();
        }
        List<Double> breakevens = new ArrayList<>();
        for (String raw : c.breakevens()) {
            try {
                breakevens.add(Double.parseDouble(raw.replaceAll("[^0-9.\\-]", "")));
            } catch (RuntimeException ignored) { /* label-formatted breakeven; skip */ }
        }
        if (breakevens.isEmpty()) return List.of();
        double spot = spotCents / 100.0;

        // Terminal moves over overlapping DTE-session windows of the lane's own closes.
        List<Double> terminalMovesPct = new ArrayList<>();
        for (int i = 0; i + dte < closes.size(); i++) {
            double start = closes.get(i);
            double end = closes.get(i + dte);
            if (start > 0) terminalMovesPct.add((end / start - 1.0) * 100.0);
        }
        if (terminalMovesPct.size() < MIN_CLOSES / 2) return List.of();

        List<String> out = new ArrayList<>();
        if (breakevens.size() >= 2) {
            double lower = breakevens.stream().mapToDouble(Double::doubleValue).min().orElse(spot);
            double upper = breakevens.stream().mapToDouble(Double::doubleValue).max().orElse(spot);
            double lowerPct = (lower / spot - 1.0) * 100.0;
            double upperPct = (upper / spot - 1.0) * 100.0;
            long contained = terminalMovesPct.stream()
                    .filter(m -> m >= lowerPct && m <= upperPct).count();
            out.add(String.format("History fit: over %d overlapping %d-session windows, the terminal move "
                            + "stayed inside this range ($%.2f to $%.2f, %.1f%% to %+.1f%%) %.0f%% of the time. "
                            + "History is not a forecast — it is the same market that set these prices.",
                    terminalMovesPct.size(), dte, lower, upper, lowerPct, upperPct,
                    100.0 * contained / terminalMovesPct.size()));
        } else {
            double breakeven = breakevens.getFirst();
            double neededPct = Math.abs(breakeven / spot - 1.0) * 100.0;
            long reached = terminalMovesPct.stream()
                    .filter(m -> Math.abs(m) >= neededPct).count();
            out.add(String.format("History fit: the breakeven sits %.1f%% away; over %d overlapping "
                            + "%d-session windows the terminal move was at least that large %.0f%% of the time "
                            + "(percentile of delivered moves, either direction). History is not a forecast.",
                    neededPct, terminalMovesPct.size(), dte, 100.0 * reached / terminalMovesPct.size()));
        }
        if (ctx.atmIv() != null && ctx.atmIv() > 0 && breakevens.size() >= 2) {
            double expectedMovePct = ctx.atmIv() * Math.sqrt(dte / 365.0) * 100.0;
            double lower = breakevens.stream().mapToDouble(Double::doubleValue).min().orElse(spot);
            double upper = breakevens.stream().mapToDouble(Double::doubleValue).max().orElse(spot);
            double halfWidthPct = (upper - lower) / 2.0 / spot * 100.0;
            if (expectedMovePct > 0) {
                out.add(String.format("The range's half-width is %.1f%% — %.1fx the options-implied "
                                + "expected move of %.1f%% over %d sessions.",
                        halfWidthPct, halfWidthPct / expectedMovePct, expectedMovePct, dte));
            }
        }
        return out;
    }
}
