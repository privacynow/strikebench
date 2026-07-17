package io.liftandshift.strikebench.eval;

/**
 * The market regime a discovery surface frames its structures against (folded Phase 10.3):
 * trend state and drawdown from the lane's own candles, vol richness from the shared
 * volatility profile, event proximity from the events surface. Every dimension is honestly
 * nullable — a thin history yields "unknown", never a fabricated regime. Regime CONDITIONS
 * framing and warnings; it never re-ranks and never overwrites the four outputs.
 */
public record RegimeSnapshot(
        Trend trend,                 // null when the candle history is too thin to say
        Double trendReturnPct,       // total % move over the lookback window
        Integer trendSessions,       // sessions actually observed in the window
        Double drawdownPct,          // % below the lookback high (<= 0), null with trend
        Double varianceRiskPremium,  // atmIv - realizedVol30; positive = options rich vs realized
        Double ivRankPct,            // 0..100 within the trailing observed window
        Boolean eventSoon,           // true/false with evidence; null means proximity unavailable
        String eventBasis,           // estimate/provenance or an explicit unavailable explanation
        String basis                 // provenance: what window, which lane
) {
    public enum Trend { UP, DOWN, SIDEWAYS }

    public boolean trendKnown() { return trend != null && trendReturnPct != null; }

    /** One plain sentence a discovery surface can lead with; empty string when unknown. */
    public String headline() {
        if (!trendKnown()) return "";
        String direction = switch (trend) {
            case UP -> "up";
            case DOWN -> "down";
            case SIDEWAYS -> "sideways";
        };
        StringBuilder sb = new StringBuilder(String.format("The market here has been %s %.0f%% over the last %d sessions",
                direction, Math.abs(trendReturnPct), trendSessions));
        if (drawdownPct != null && drawdownPct < -5) {
            sb.append(String.format(" and sits %.0f%% below its recent high", Math.abs(drawdownPct)));
        }
        if (varianceRiskPremium != null) {
            sb.append(varianceRiskPremium > 0.02
                    ? "; options are pricing more movement than the stock has delivered (rich premium)"
                    : varianceRiskPremium < -0.02
                        ? "; options are pricing less movement than the stock has delivered (thin premium)"
                        : "; option prices roughly match delivered movement");
        }
        return sb.append('.').toString();
    }
}
