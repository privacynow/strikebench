package io.liftandshift.strikebench.eval;

/** Volatility-entry context stated separately from the economic verdict. */
public record IvContext(
        EntrySide entrySide,
        Band band,
        Double ivRankPct,
        int observedHistoryDays,
        String message
) {
    public enum EntrySide { DEBIT, CREDIT, FLAT }
    public enum Band { VERY_LOW, LOW, MIDDLE, HIGH, VERY_HIGH, UNAVAILABLE }

    public IvContext {
        if (entrySide == null || band == null || message == null || message.isBlank()) {
            throw new IllegalArgumentException("IV context requires side, band, and explanation");
        }
    }

    static IvContext from(long entryNetPremiumCents, VolatilityProfile volatility) {
        EntrySide side = entryNetPremiumCents < 0 ? EntrySide.DEBIT
                : entryNetPremiumCents > 0 ? EntrySide.CREDIT : EntrySide.FLAT;
        Double rank = volatility == null ? null : volatility.ivRankPct();
        int days = volatility == null ? 0 : volatility.historyDays();
        if (rank == null) {
            String source = volatility == null ? "volatility context unavailable" : volatility.source();
            return new IvContext(side, Band.UNAVAILABLE, null, days,
                    "IV rank is unavailable: " + source + ". No volatility-richness claim is made.");
        }
        Band band = rank >= 90 ? Band.VERY_HIGH : rank >= 70 ? Band.HIGH
                : rank <= 10 ? Band.VERY_LOW : rank <= 30 ? Band.LOW : Band.MIDDLE;
        String message;
        if (side == EntrySide.DEBIT && rank >= 90) {
            message = String.format(java.util.Locale.ROOT,
                    "This debit position buys options with IV rank at %.0f out of 100. A volatility crush can reduce the option value even if direction is partly right.", rank);
        } else if (side == EntrySide.DEBIT && rank >= 70) {
            message = String.format(java.util.Locale.ROOT,
                    "This debit position buys above-normal volatility (IV rank %.0f). Direction must overcome both time decay and possible volatility contraction.", rank);
        } else {
            message = String.format(java.util.Locale.ROOT,
                    "Entry is a %s and IV rank is %.0f based on %d observed snapshot days.",
                    side.name().toLowerCase(java.util.Locale.ROOT), rank, days);
        }
        return new IvContext(side, band, rank, days, message);
    }
}
