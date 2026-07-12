package io.liftandshift.strikebench.pricing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Shared risk-neutral probability and EV calculation for ticket review and outcome evaluation. */
public final class RiskNeutralAnalyzer {
    private RiskNeutralAnalyzer() {}

    public record Sensitivity(double ivScale, long evCents) {}
    public record Result(ProbabilityMap.Result probabilityMap, long expectedValueCents,
                         List<Sensitivity> sensitivity) {}

    public static Result analyze(PayoffCurve curve, double spot, double marketIv, double years,
                                 double riskFreeRate, List<BigDecimal> shortStrikes) {
        if (curve == null) throw new IllegalArgumentException("payoff curve is required");
        if (!(marketIv > 0)) throw new IllegalArgumentException("market IV is required");
        var map = ProbabilityMap.of(curve, spot, marketIv, years, riskFreeRate, shortStrikes);
        List<Sensitivity> sensitivity = new ArrayList<>();
        for (double scale : new double[]{0.8, 1.0, 1.2}) {
            sensitivity.add(new Sensitivity(scale,
                    curve.riskNeutralExpectedValueCents(spot, marketIv * scale, years, riskFreeRate)));
        }
        return new Result(map, curve.riskNeutralExpectedValueCents(spot, marketIv, years, riskFreeRate),
                List.copyOf(sensitivity));
    }
}
