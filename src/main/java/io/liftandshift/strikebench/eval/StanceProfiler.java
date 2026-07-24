package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.position.ParticipationProfile;
import io.liftandshift.strikebench.pricing.BlackScholes;
import io.liftandshift.strikebench.pricing.PayoffCurve;
import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.util.Money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** One quantitative primitive for stance, participation, and later book aggregation. */
final class StanceProfiler {

    record Result(StanceVector stance, ParticipationProfile participation,
                  ImpliedStance impliedStance, DataCoverageReceipt coverage) {}

    Result profile(Candidate candidate, EvalContext ctx, EvidenceProfile evidence,
                   VolatilityProfile volatility) {
        if (candidate == null || ctx == null || ctx.underlyingCents() <= 0) {
            throw new IllegalArgumentException("stance needs a priced candidate and underlying");
        }
        List<Leg> legs = RiskProfiler.combinedLegs(candidate, ctx);
        double spot = ctx.underlyingCents() / 100.0;
        double sigma = modelVol(ctx);
        double deltaShares = 0, gammaSharesPerDollar = 0, thetaDollarsPerYear = 0, vegaDollarsPerOne = 0;
        int duration = 0;
        long equivalentShares = 0;
        LocalDate dominantExpiry = null;
        for (Leg leg : legs) {
            double sign = leg.action() == LegAction.BUY ? 1.0 : -1.0;
            long units = Math.multiplyExact((long) leg.ratio() * Math.max(1, candidate.qty()), leg.multiplier());
            equivalentShares = Math.max(equivalentShares, units);
            if (leg.isStock()) {
                deltaShares += sign * units;
                continue;
            }
            int dte = Math.max(0, (int) ChronoUnit.DAYS.between(ctx.asOfDate(), leg.expiration()));
            duration = Math.max(duration, dte);
            if (dominantExpiry == null || leg.expiration().isAfter(dominantExpiry)) dominantExpiry = leg.expiration();
            double t = dte / 365.0;
            boolean call = leg.type() == OptionType.CALL;
            double strike = leg.strike().doubleValue();
            deltaShares += sign * units * BlackScholes.delta(call, spot, strike, t,
                    ctx.riskFreeRate(), 0, sigma);
            gammaSharesPerDollar += sign * units * BlackScholes.gamma(spot, strike, t,
                    ctx.riskFreeRate(), 0, sigma);
            thetaDollarsPerYear += sign * units * BlackScholes.theta(call, spot, strike, t,
                    ctx.riskFreeRate(), 0, sigma);
            vegaDollarsPerOne += sign * units * BlackScholes.vega(spot, strike, t,
                    ctx.riskFreeRate(), 0, sigma);
        }
        if (equivalentShares <= 0) throw new IllegalArgumentException("stance needs positive deliverable units");

        long dollarDelta = Money.toCents(deltaShares * spot);
        long gammaDollarDelta = Money.toCents(gammaSharesPerDollar * (spot * 0.01) * spot);
        long theta = Money.toCents(thetaDollarsPerYear / 365.0);
        long vega = Money.toCents(vegaDollarsPerOne / 100.0);

        int expirations = (int) legs.stream().filter(leg -> !leg.isStock())
                .map(Leg::expiration).distinct().count();
        double move = Math.max(0.01, sigma * Math.sqrt(Math.max(1, duration) / 365.0));
        Long down1 = null, down2 = null, up1 = null, up2 = null;
        PayoffCurve curve = null;
        if (expirations <= 1) {
            curve = RiskProfiler.payoffCurve(candidate, ctx);
            down1 = lossAt(curve, spot * Math.max(0, 1 - move));
            down2 = lossAt(curve, spot * Math.max(0, 1 - 2 * move));
            up1 = lossAt(curve, spot * (1 + move));
            up2 = lossAt(curve, spot * (1 + 2 * move));
        }
        StanceVector stance = new StanceVector(dollarDelta, gammaDollarDelta, vega, theta,
                down1, down2, up1, up2, duration);

        long intervalStart = ctx.underlyingCents();
        long intervalEnd = Math.max(intervalStart + 1, Money.toCents(spot * (1 + move)));
        int localBps = ratioBps(dollarDelta, Math.multiplyExact(equivalentShares, ctx.underlyingCents()));
        Integer terminalBps = curve == null ? null : ratioBps(
                Math.subtractExact(curve.profitAtCents(Money.priceFromCents(intervalEnd)),
                        curve.profitAtCents(Money.priceFromCents(intervalStart))),
                Math.multiplyExact(Math.subtractExact(intervalEnd, intervalStart), equivalentShares));
        String terminalBasis = curve == null
                ? "unavailable: multiple expirations require path valuation"
                : "expiration payoff over the +1 sigma interval; fees excluded";
        ParticipationProfile participation = new ParticipationProfile(localBps, terminalBps,
                intervalStart, intervalEnd, dominantExpiry, "current dollar delta versus "
                + equivalentShares + " equivalent shares", terminalBasis, regimePoints(legs));

        ImpliedStance implied = implied(stance, localBps);
        DataCoverageReceipt coverage = coverage(evidence, volatility, expirations > 1);
        return new Result(stance, participation, implied, coverage);
    }

    private static double modelVol(EvalContext ctx) {
        if (ctx.atmIv() != null && ctx.atmIv() > 0) return ctx.atmIv();
        if (ctx.realizedVol30() != null && ctx.realizedVol30() > 0) return ctx.realizedVol30();
        return 0.30;
    }

    private static long lossAt(PayoffCurve curve, double price) {
        return Math.max(0, Math.negateExact(Math.min(0, curve.profitAtCents(BigDecimal.valueOf(price)))));
    }

    private static int ratioBps(long numerator, long denominator) {
        if (denominator <= 0) throw new IllegalArgumentException("participation denominator must be positive");
        return BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(10_000))
                .divide(BigDecimal.valueOf(denominator), 0, java.math.RoundingMode.HALF_UP)
                .intValueExact();
    }

    private static List<ParticipationProfile.RegimePoint> regimePoints(List<Leg> legs) {
        List<ParticipationProfile.RegimePoint> points = new ArrayList<>();
        for (Leg leg : legs) {
            if (leg.isStock()) continue;
            String meaning = switch (leg.type()) {
                case CALL -> leg.action() == LegAction.SELL
                        ? "short-call cap: upside participation falls above this price"
                        : "long-call threshold: upside participation rises above this price";
                case PUT -> leg.action() == LegAction.SELL
                        ? "short-put threshold: the share-purchase obligation grows below this price"
                        : "long-put threshold: downside protection grows below this price";
            };
            var point = new ParticipationProfile.RegimePoint(Money.toCents(leg.strike()), meaning);
            if (!points.contains(point)) points.add(point);
        }
        points.sort(java.util.Comparator.comparingLong(ParticipationProfile.RegimePoint::priceCents));
        return List.copyOf(points);
    }

    private static ImpliedStance implied(StanceVector stance, int localBps) {
        ImpliedStance.Direction direction = localBps > 500 ? ImpliedStance.Direction.BULLISH
                : localBps < -500 ? ImpliedStance.Direction.BEARISH : ImpliedStance.Direction.NEUTRAL;
        ImpliedStance.Shape convexity = shape(stance.gammaDollarDeltaCentsPerOnePercentMove());
        ImpliedStance.Shape volatility = shape(stance.vegaCentsPerVolPoint());
        ImpliedStance.Carry carry = stance.thetaCentsPerDay() > 1 ? ImpliedStance.Carry.POSITIVE
                : stance.thetaCentsPerDay() < -1 ? ImpliedStance.Carry.NEGATIVE : ImpliedStance.Carry.FLAT;
        ImpliedStance.Tail tail = tail(stance);
        String label = direction.name().toLowerCase() + " · " + convexity.name().toLowerCase()
                + " convexity · " + volatility.name().toLowerCase() + " volatility · "
                + carry.name().toLowerCase() + " carry";
        String summary = "The position is " + direction.name().toLowerCase() + " locally, has "
                + convexity.name().toLowerCase() + " convexity and " + volatility.name().toLowerCase()
                + " volatility exposure; its primary modeled tail is " + tail.name().toLowerCase() + ".";
        return new ImpliedStance(direction, convexity, volatility, carry, tail, label, summary);
    }

    private static ImpliedStance.Shape shape(long value) {
        return value > 1 ? ImpliedStance.Shape.LONG : value < -1 ? ImpliedStance.Shape.SHORT
                : ImpliedStance.Shape.FLAT;
    }

    private static ImpliedStance.Tail tail(StanceVector stance) {
        Long down = stance.downsideLossTwoSigmaCents(), up = stance.upsideLossTwoSigmaCents();
        if (down == null || up == null) return ImpliedStance.Tail.UNKNOWN;
        if (down == 0 && up == 0) return ImpliedStance.Tail.LIMITED;
        if (down > 0 && up > 0) {
            double ratio = Math.max(down, up) / (double) Math.max(1L, Math.min(down, up));
            if (ratio < 1.5) return ImpliedStance.Tail.BOTH;
        }
        return down >= up ? ImpliedStance.Tail.DOWNSIDE : ImpliedStance.Tail.UPSIDE;
    }

    private static DataCoverageReceipt coverage(EvidenceProfile evidence, VolatilityProfile volatility,
                                                boolean multiExpiration) {
        LinkedHashMap<String, DataCoverageReceipt.InputCoverage> inputs = new LinkedHashMap<>();
        if (evidence != null) evidence.perDimension().forEach((name, level) -> inputs.put(name,
                new DataCoverageReceipt.InputCoverage(level, coverageDetail(name, level, volatility))));
        List<String> limits = new ArrayList<>();
        if (volatility == null || volatility.ivRankPct() == null) {
            limits.add(volatility == null ? "IV context unavailable"
                    : volatility.source());
        }
        if (multiExpiration) limits.add("Terminal participation and sigma-tail losses require path valuation for multiple expirations.");
        limits.add("Option valuation uses European Black-Scholes with q=0 and an intrinsic floor; early assignment is a heuristic warning, not a predicted event.");
        return new DataCoverageReceipt(inputs,
                "European Black-Scholes, q=0, market IV when available; payoff curve uses exact contract geometry",
                limits);
    }

    private static String coverageDetail(String name, EvidenceLevel level, VolatilityProfile volatility) {
        return switch (name) {
            case "pricing" -> level.label() + " bid/ask package inputs";
            case "greeks" -> level.label() + " inputs repriced through the disclosed model";
            case "volatility" -> volatility == null ? "Volatility input unavailable" : volatility.source();
            case "liquidity" -> level.label() + " spread and book-quality evidence";
            case "history" -> level == EvidenceLevel.UNKNOWN
                    ? "Daily history missing; realized-volatility outcomes are unavailable"
                    : level.label() + " daily history";
            case "rates" -> level.label() + " risk-free-rate input";
            default -> level.label();
        };
    }
}
