package io.liftandshift.strikebench.eval;

/** Volatility-entry context stated separately from the economic verdict. */
public record IvContext(
        EntrySide entrySide,
        Band band,
        Double ivRankPct,
        int observedHistoryDays,
        String message
) {
    /**
     * FLAT and UNAVAILABLE are different facts and must never be conflated: FLAT means the package
     * was priced and opened at zero net; UNAVAILABLE means there is no package price at all, so
     * whether the trader pays or collects is simply unknown (§3.2).
     */
    public enum EntrySide { DEBIT, CREDIT, FLAT, UNAVAILABLE }
    public enum Band { VERY_LOW, LOW, MIDDLE, HIGH, VERY_HIGH, UNAVAILABLE }

    public IvContext {
        if (entrySide == null || band == null || message == null || message.isBlank()) {
            throw new IllegalArgumentException("IV context requires side, band, and explanation");
        }
    }

    /**
     * @param entryNetPremiumCents the package net, or null when the §7.2 receipt has no price
     * @param unpricedReason       why there is no price; required whenever the net is null
     */
    static IvContext from(Long entryNetPremiumCents, VolatilityProfile volatility,
                          String unpricedReason) {
        EntrySide side = entryNetPremiumCents == null ? EntrySide.UNAVAILABLE
                : entryNetPremiumCents < 0 ? EntrySide.DEBIT
                : entryNetPremiumCents > 0 ? EntrySide.CREDIT : EntrySide.FLAT;
        Double rank = volatility == null ? null : volatility.ivRankPct();
        int days = volatility == null ? 0 : volatility.historyDays();
        // The IV rank itself is a property of the market, not of this package's price, so it stays
        // available. Only the debit/credit reading of it is withheld.
        if (side == EntrySide.UNAVAILABLE) {
            Band unpricedBand = rank == null ? Band.UNAVAILABLE : bandOf(rank);
            return new IvContext(side, unpricedBand, rank, days,
                    "This package has no price, so no debit-or-credit volatility reading is made: "
                            + (unpricedReason == null || unpricedReason.isBlank()
                                    ? "the package price is unavailable" : unpricedReason)
                            + (rank == null ? "" : String.format(java.util.Locale.ROOT,
                                    " IV rank is %.0f based on %d observed snapshot days.", rank, days)));
        }
        if (rank == null) {
            String source = volatility == null ? "volatility context unavailable" : volatility.source();
            return new IvContext(side, Band.UNAVAILABLE, null, days,
                    "IV rank is unavailable: " + source + ". No volatility-richness claim is made.");
        }
        Band band = bandOf(rank);
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

    /** The one rank-to-band mapping, so the priced and unpriced lanes cannot band differently. */
    private static Band bandOf(double rank) {
        return rank >= 90 ? Band.VERY_HIGH : rank >= 70 ? Band.HIGH
                : rank <= 10 ? Band.VERY_LOW : rank <= 30 ? Band.LOW : Band.MIDDLE;
    }
}
