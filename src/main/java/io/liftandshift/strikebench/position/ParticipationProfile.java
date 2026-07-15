package io.liftandshift.strikebench.position;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Three participation meanings; basis points avoid vague floating-point percentages. */
public record ParticipationProfile(
        int localParticipationBps,
        int terminalUpsideCaptureBps,
        long intervalStartCents,
        long intervalEndCents,
        List<RegimePoint> regimePoints
) {
    public ParticipationProfile {
        if (intervalEndCents <= intervalStartCents) throw new IllegalArgumentException("participation interval must rise");
        regimePoints = regimePoints == null ? List.of() : List.copyOf(regimePoints);
    }

    public record RegimePoint(long priceCents, String meaning) {
        public RegimePoint {
            if (priceCents <= 0 || meaning == null || meaning.isBlank()) {
                throw new IllegalArgumentException("a participation regime point needs a price and meaning");
            }
        }
    }

    public static ParticipationProfile fromExactValues(long positionDollarDeltaCents,
                                                        long equivalentShareExposureCents,
                                                        long positionValueAtStartCents,
                                                        long positionValueAtEndCents,
                                                        long equivalentShares,
                                                        long intervalStartCents,
                                                        long intervalEndCents,
                                                        List<RegimePoint> regimePoints) {
        if (equivalentShareExposureCents <= 0 || equivalentShares <= 0) {
            throw new IllegalArgumentException("participation needs a positive equivalent-share position");
        }
        long underlyingMove = Math.multiplyExact(Math.subtractExact(intervalEndCents, intervalStartCents),
                equivalentShares);
        if (underlyingMove <= 0) throw new IllegalArgumentException("participation interval must rise");
        int local = ratioBps(positionDollarDeltaCents, equivalentShareExposureCents);
        int terminal = ratioBps(Math.subtractExact(positionValueAtEndCents, positionValueAtStartCents), underlyingMove);
        return new ParticipationProfile(local, terminal, intervalStartCents, intervalEndCents, regimePoints);
    }

    private static int ratioBps(long numerator, long denominator) {
        return BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(10_000))
                .divide(BigDecimal.valueOf(denominator), 0, RoundingMode.HALF_UP).intValueExact();
    }
}
