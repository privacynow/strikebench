package io.liftandshift.strikebench.pricing;

import io.liftandshift.strikebench.market.OptionTime;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.model.ScenarioStory;
import io.liftandshift.strikebench.paper.PackagePriceReceipt;
import io.liftandshift.strikebench.util.Json;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;

/** Shared risk-neutral probability and EV calculation for ticket review and outcome evaluation. */
public final class RiskNeutralAnalyzer {
    private RiskNeutralAnalyzer() {}

    public record Sensitivity(double ivScale, long evCents) {}
    public record ScenarioMass(ScenarioStory story, double underlyingMovePct, double probability) {}

    /**
     * Chance that at least one short leg finishes in the money at one shared expiration, using
     * each selected short leg's captured IV and its exact lane-aware option clock.
     *
     * <p>This lives with the market-implied risk owner—not Recommendation or Trade—so proposal and
     * exact-ticket paths cannot use different CDF, time, or missing-IV policies. A missing selected
     * short-leg IV makes the fact unavailable; a silent 30% substitute is not evidence. Mixed
     * expirations also stay unavailable here: their events are dependent observations of one
     * underlying through time, so summing marginal probabilities would overstate the union. A
     * supplied joint path ensemble is the only honest owner for that different question.</p>
     */
    public static Double shortSideExpirationItmProbability(
            List<Leg> legs, List<Double> ivsAligned, long underlyingCents,
            Instant laneNow, double riskFreeRate) {
        if (legs == null || underlyingCents <= 0 || laneNow == null
                || !Double.isFinite(riskFreeRate)) return null;
        java.util.Map<LocalDate, Integer> lowestCall = new LinkedHashMap<>();
        java.util.Map<LocalDate, Integer> highestPut = new LinkedHashMap<>();
        for (int i = 0; i < legs.size(); i++) {
            Leg leg = legs.get(i);
            if (leg == null || leg.isStock() || leg.action() != LegAction.SELL
                    || leg.expiration() == null || leg.strike() == null) continue;
            java.util.Map<LocalDate, Integer> target =
                    leg.type() == OptionType.CALL ? lowestCall : highestPut;
            Integer prior = target.get(leg.expiration());
            if (prior == null || (leg.type() == OptionType.CALL
                    ? leg.strike().compareTo(legs.get(prior).strike()) < 0
                    : leg.strike().compareTo(legs.get(prior).strike()) > 0)) {
                target.put(leg.expiration(), i);
            }
        }
        java.util.Set<LocalDate> expirations = new java.util.LinkedHashSet<>(lowestCall.keySet());
        expirations.addAll(highestPut.keySet());
        if (expirations.isEmpty()) return null;
        if (expirations.size() != 1) return null;
        for (Integer index : lowestCall.values()) {
            if (!validIv(alignedIv(ivsAligned, index))) return null;
        }
        for (Integer index : highestPut.values()) {
            if (!validIv(alignedIv(ivsAligned, index))) return null;
        }

        double spot = underlyingCents / 100.0;
        double total = 0;
        for (LocalDate expiration : expirations) {
            OptionTime.Measure time = OptionTime.toExpiry(laneNow, expiration);
            if (!time.hasModelTime()) return null;
            Integer callIndex = lowestCall.get(expiration);
            Integer putIndex = highestPut.get(expiration);
            if (callIndex != null && putIndex != null
                    && legs.get(putIndex).strike().compareTo(legs.get(callIndex).strike()) >= 0) {
                total += 1.0;
                continue;
            }
            if (callIndex != null) {
                Double probability = finishItmProbability(
                        legs.get(callIndex), alignedIv(ivsAligned, callIndex),
                        spot, time, riskFreeRate);
                if (probability == null) return null;
                total += probability;
            }
            if (putIndex != null) {
                Double probability = finishItmProbability(
                        legs.get(putIndex), alignedIv(ivsAligned, putIndex),
                        spot, time, riskFreeRate);
                if (probability == null) return null;
                total += probability;
            }
        }
        return Math.min(1.0, total);
    }

    private static Double alignedIv(List<Double> ivs, int index) {
        return ivs != null && index >= 0 && index < ivs.size() ? ivs.get(index) : null;
    }

    private static boolean validIv(Double iv) {
        return iv != null && Double.isFinite(iv) && iv > 0;
    }

    private static Double finishItmProbability(
            Leg leg, Double iv, double spot, OptionTime.Measure time, double riskFreeRate) {
        if (!validIv(iv)) return null;
        double t = time.years();
        double d1 = BlackScholes.d1(spot, leg.strike().doubleValue(), t,
                riskFreeRate, 0, iv);
        double d2 = d1 - iv * Math.sqrt(t);
        return leg.type() == OptionType.CALL ? BlackScholes.normCdf(d2)
                : BlackScholes.normCdf(-d2);
    }

    /**
     * A typed risk-neutral baseline, distinct from an option-package evaluation because buy and
     * hold has no package-price receipt or option expiry. Discovery may publish the projection,
     * but it cannot calculate it: this analyzer owns the curve, distribution, EV and fingerprint.
     */
    public record BaselineReceipt(
            String schemaVersion,
            String modelVersion,
            String key,
            boolean available,
            String unavailableReason,
            String fingerprint,
            Long underlyingCents,
            Double marketIv,
            Double riskFreeRate,
            OptionTime.Measure time,
            ProbabilityMap.Result probabilityMap,
            Long expectedValueCents
    ) {
        public static final String SCHEMA = "risk-neutral-baseline-1";
        public static final String MODEL = "risk-neutral-lognormal-q0-1";

        public BaselineReceipt {
            schemaVersion = schemaVersion == null ? SCHEMA : schemaVersion;
            modelVersion = modelVersion == null ? MODEL : modelVersion;
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("a market-implied baseline needs a key");
            }
            if (available && (fingerprint == null || fingerprint.isBlank()
                    || expectedValueCents == null)) {
                throw new IllegalArgumentException(
                        "an available market-implied baseline needs its fingerprint and EV");
            }
            if (!available && (unavailableReason == null || unavailableReason.isBlank())) {
                throw new IllegalArgumentException(
                        "an unavailable market-implied baseline needs a reason");
            }
        }

        @com.fasterxml.jackson.annotation.JsonProperty("pop")
        public Double pop() {
            return probabilityMap == null ? null : probabilityMap.pAnyProfit();
        }

        public Long cvar95Cents() {
            return probabilityMap == null ? ("CASH".equals(key) ? 0L : null)
                    : probabilityMap.cvar95Cents();
        }

        public Long stressLossCents() {
            return probabilityMap == null ? null : probabilityMap.stressLossCents();
        }

        public static BaselineReceipt cash() {
            return new BaselineReceipt(SCHEMA, MODEL, "CASH", true, null,
                    "cash-baseline-v1", null, null, null, null, null, 0L);
        }
    }

    /**
     * One immutable market-implied evaluation. The fingerprint binds every published probability
     * and EV to the exact package price, underlying, IV, rate and option clock that produced it.
     * Realized-volatility economics remain a separate evaluator lane and never enter this receipt.
     */
    public record Receipt(
            String schemaVersion,
            String modelVersion,
            boolean available,
            String unavailableReason,
            String fingerprint,
            String priceFingerprint,
            Long underlyingCents,
            Double marketIv,
            Double riskFreeRate,
            OptionTime.Measure time,
            ProbabilityMap.Result probabilityMap,
            Long expectedValueCents,
            List<Sensitivity> sensitivity,
            List<ScenarioMass> scenarioMasses
    ) {
        public static final String SCHEMA = "risk-neutral-evaluation-1";
        public static final String MODEL = "risk-neutral-lognormal-q0-1";

        public Receipt {
            schemaVersion = schemaVersion == null ? SCHEMA : schemaVersion;
            modelVersion = modelVersion == null ? MODEL : modelVersion;
            sensitivity = sensitivity == null ? List.of() : List.copyOf(sensitivity);
            scenarioMasses = scenarioMasses == null ? List.of() : List.copyOf(scenarioMasses);
            if (available) {
                if (!SCHEMA.equals(schemaVersion) || !MODEL.equals(modelVersion)) {
                    throw new IllegalArgumentException(
                            "market-implied receipt schema/model does not match this evaluator");
                }
                if (unavailableReason != null) {
                    throw new IllegalArgumentException("available market-implied receipt cannot carry an unavailable reason");
                }
                if (fingerprint == null || fingerprint.isBlank()
                        || priceFingerprint == null || priceFingerprint.isBlank()
                        || underlyingCents == null || underlyingCents <= 0
                        || marketIv == null || !(marketIv > 0) || !Double.isFinite(marketIv)
                        || riskFreeRate == null || !Double.isFinite(riskFreeRate)
                        || time == null || !time.hasModelTime()
                        || probabilityMap == null || expectedValueCents == null
                        || scenarioMasses.size() != ScenarioStory.values().length) {
                    throw new IllegalArgumentException(
                            "available market-implied receipt requires its complete fingerprinted input and result");
                }
                for (Sensitivity point : sensitivity) {
                    if (point == null || !Double.isFinite(point.ivScale())
                            || !(point.ivScale() > 0)) {
                        throw new IllegalArgumentException(
                                "market-implied sensitivity points must be complete and finite");
                    }
                }
                ScenarioStory[] stories = ScenarioStory.values();
                double totalMass = 0;
                for (int i = 0; i < stories.length; i++) {
                    ScenarioMass mass = scenarioMasses.get(i);
                    if (mass == null || mass.story() != stories[i]
                            || Double.compare(mass.underlyingMovePct(),
                                    stories[i].underlyingMoveFraction()) != 0
                            || !Double.isFinite(mass.probability())
                            || mass.probability() < 0 || mass.probability() > 1) {
                        throw new IllegalArgumentException(
                                "market-implied scenario masses must cover the canonical stories in order");
                    }
                    totalMass += mass.probability();
                }
                if (Math.abs(totalMass - 1.0) > 0.001) {
                    throw new IllegalArgumentException(
                            "market-implied scenario masses must exhaust the terminal distribution");
                }
                String expected = RiskNeutralAnalyzer.fingerprint(priceFingerprint, underlyingCents, marketIv,
                        riskFreeRate, time);
                if (!fingerprint.equals(expected)) {
                    throw new IllegalArgumentException(
                            "market-implied receipt fingerprint does not match its captured inputs");
                }
            } else {
                if (unavailableReason == null || unavailableReason.isBlank()) {
                    throw new IllegalArgumentException(
                            "unavailable market-implied receipt requires a reason");
                }
                if (probabilityMap != null || expectedValueCents != null
                        || !sensitivity.isEmpty() || !scenarioMasses.isEmpty()) {
                    throw new IllegalArgumentException(
                            "unavailable market-implied receipt cannot carry modeled results");
                }
            }
        }

        @com.fasterxml.jackson.annotation.JsonProperty("pop")
        public Double pop() {
            return available ? probabilityMap.pAnyProfit() : null;
        }

        public static Receipt unavailable(String reason) {
            return new Receipt(SCHEMA, MODEL, false, reason, null, null, null, null, null,
                    null, null, null, List.of(), List.of());
        }
    }

    public static Receipt analyze(PayoffCurve curve, PackagePriceReceipt price,
                                  long underlyingCents, double marketIv,
                                  OptionTime.Measure time, double riskFreeRate,
                                  List<BigDecimal> shortStrikes) {
        if (curve == null) throw new IllegalArgumentException("payoff curve is required");
        if (price == null || !price.priced() || price.fingerprint() == null
                || price.fingerprint().isBlank()) {
            throw new IllegalArgumentException(
                    "market-implied evaluation requires the exact package-price fingerprint");
        }
        if (underlyingCents <= 0) {
            throw new IllegalArgumentException("market-implied evaluation requires the underlying price");
        }
        if (!(marketIv > 0)) throw new IllegalArgumentException("market IV is required");
        if (time == null || !time.hasModelTime()) {
            throw new IllegalArgumentException("market-implied evaluation requires a live option clock");
        }
        double spot = underlyingCents / 100.0;
        double years = time.years();
        var map = ProbabilityMap.of(curve, spot, marketIv, years, riskFreeRate, shortStrikes);
        List<Sensitivity> sensitivity = new ArrayList<>();
        for (double scale : new double[]{0.8, 1.0, 1.2}) {
            sensitivity.add(new Sensitivity(scale,
                    curve.riskNeutralExpectedValueCents(spot, marketIv * scale, years, riskFreeRate)));
        }
        LognormalTerminal terminal = LognormalTerminal.of(spot, marketIv, years, riskFreeRate);
        ScenarioStory[] stories = ScenarioStory.values();
        List<ScenarioMass> masses = new ArrayList<>(stories.length);
        for (int i = 0; i < stories.length; i++) {
            double move = stories[i].underlyingMoveFraction();
            double lower = i == 0 ? 0.0 : spot * (1.0
                    + (stories[i - 1].underlyingMoveFraction() + move) / 2.0);
            double upper = i == stories.length - 1 ? Double.POSITIVE_INFINITY : spot * (1.0
                    + (move + stories[i + 1].underlyingMoveFraction()) / 2.0);
            double mass = (Double.isInfinite(upper) ? 1.0 : terminal.cdf(upper))
                    - terminal.cdf(lower);
            masses.add(new ScenarioMass(stories[i], move,
                    io.liftandshift.strikebench.util.Numbers.round4(
                            Math.max(0, Math.min(1, mass)))));
        }
        String fingerprint = fingerprint(price.fingerprint(), underlyingCents, marketIv,
                riskFreeRate, time);
        return new Receipt(Receipt.SCHEMA, Receipt.MODEL, true, null, fingerprint,
                price.fingerprint(), underlyingCents, marketIv, riskFreeRate, time, map,
                curve.riskNeutralExpectedValueCents(spot, marketIv, years, riskFreeRate),
                List.copyOf(sensitivity), List.copyOf(masses));
    }

    /** Canonical 100-share buy-and-hold projection over the supplied comparison horizon. */
    public static BaselineReceipt analyzeBuyAndHold(long underlyingCents, double marketIv,
                                                    OptionTime.Measure time,
                                                    double riskFreeRate) {
        if (underlyingCents <= 0) {
            throw new IllegalArgumentException("buy-and-hold baseline requires the underlying price");
        }
        if (!(marketIv > 0) || !Double.isFinite(marketIv)) {
            throw new IllegalArgumentException("buy-and-hold baseline requires volatility");
        }
        if (time == null || !time.hasModelTime()) {
            throw new IllegalArgumentException("buy-and-hold baseline requires a model horizon");
        }
        BigDecimal spot = BigDecimal.valueOf(underlyingCents, 2);
        PayoffCurve curve = PayoffCurve.of(List.of(
                io.liftandshift.strikebench.model.Leg.stock(
                        io.liftandshift.strikebench.model.LegAction.BUY, 1, spot)), 1);
        double spotDollars = spot.doubleValue();
        ProbabilityMap.Result probability = ProbabilityMap.of(curve, spotDollars, marketIv,
                time.years(), riskFreeRate, List.of());
        String fingerprint = baselineFingerprint("BUY_AND_HOLD", underlyingCents, marketIv,
                riskFreeRate, time);
        return new BaselineReceipt(BaselineReceipt.SCHEMA, BaselineReceipt.MODEL,
                "BUY_AND_HOLD", true, null, fingerprint, underlyingCents, marketIv,
                riskFreeRate, time, probability,
                curve.riskNeutralExpectedValueCents(
                        spotDollars, marketIv, time.years(), riskFreeRate));
    }

    private static String baselineFingerprint(String key, long underlyingCents,
                                              double marketIv, double riskFreeRate,
                                              OptionTime.Measure time) {
        try {
            LinkedHashMap<String, Object> material = new LinkedHashMap<>();
            material.put("schemaVersion", BaselineReceipt.SCHEMA);
            material.put("modelVersion", BaselineReceipt.MODEL);
            material.put("key", key);
            material.put("underlyingCents", underlyingCents);
            material.put("marketIv", marketIv);
            material.put("riskFreeRate", riskFreeRate);
            material.put("timeState", time.state().name());
            material.put("sessions", time.sessions());
            material.put("calendarDays", time.calendarDays());
            material.put("years", time.years());
            material.put("expiration", time.expiration() == null ? null : time.expiration().toString());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Json.canonical(material).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("cannot fingerprint market-implied baseline", e);
        }
    }

    private static String fingerprint(String priceFingerprint, long underlyingCents,
                                      double marketIv, double riskFreeRate,
                                      OptionTime.Measure time) {
        try {
            LinkedHashMap<String, Object> material = new LinkedHashMap<>();
            material.put("schemaVersion", Receipt.SCHEMA);
            material.put("modelVersion", Receipt.MODEL);
            material.put("priceFingerprint", priceFingerprint);
            material.put("underlyingCents", underlyingCents);
            material.put("marketIv", marketIv);
            material.put("riskFreeRate", riskFreeRate);
            material.put("timeState", time.state().name());
            material.put("sessions", time.sessions());
            material.put("calendarDays", time.calendarDays());
            material.put("years", time.years());
            material.put("expiration", time.expiration() == null ? null : time.expiration().toString());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Json.canonical(material).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("cannot fingerprint market-implied evaluation", e);
        }
    }
}
