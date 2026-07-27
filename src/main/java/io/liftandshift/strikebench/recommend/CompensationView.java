package io.liftandshift.strikebench.recommend;

import io.liftandshift.strikebench.eval.EvaluationService;
import io.liftandshift.strikebench.eval.StrategyEvaluation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The compensation view (folded Phase 10.3): premium per unit of realized risk, ranked BESIDE
 * the decision score on scouting surfaces, never replacing it. Premium-collecting structures
 * only; every component is named and explained so the ordering never travels as a bare number.
 */
public final class CompensationView {
    private CompensationView() {}

    public static final String BASIS = "Premium compensation 35%: collateral-backed packages use "
            + "annualized premium yield on named cash/share collateral; defined-risk packages use "
            + "the non-annualized opening premium divided by exact economic exposure. The two metrics "
            + "are never substituted or given the same label. "
            + "variance risk premium 20%, gap frequency 15% (sessions opening >2% from the prior close), "
            + "earnings proximity 10%, liquidity 10%, and capital efficiency 10%. Missing evidence is "
            + "neutral, never silently favorable. This view sits beside the Decision score; it never replaces it.";

    public record CompensationEntry(String symbol, String strategy, String label, double score,
                                    PremiumMetric premium,
                                    List<CompensationComponent> components, String evaluationId) {}
    public record CompensationComponent(String name, double weight, double value, String note) {}
    public enum PremiumMetricKind {
        COLLATERAL_PREMIUM_YIELD,
        DEFINED_RISK_PERIOD_PREMIUM
    }
    public record PremiumMetric(PremiumMetricKind kind, long premiumCents, long denominatorCents,
                                int holdingPeriodDays, double periodReturnPct,
                                Double annualizedPct, String basis) {
        public PremiumMetric {
            if (kind == null || premiumCents <= 0 || denominatorCents <= 0
                    || holdingPeriodDays < 0 || basis == null || basis.isBlank()) {
                throw new IllegalArgumentException(
                        "premium compensation needs a typed positive numerator, denominator and basis");
            }
            if (kind == PremiumMetricKind.DEFINED_RISK_PERIOD_PREMIUM
                    && annualizedPct != null) {
                throw new IllegalArgumentException(
                        "defined-risk period premium must not masquerade as annualized collateral yield");
            }
        }
    }

    /** Premium-collecting structures ranked by named compensation components; others are absent by design. */
    public static List<CompensationEntry> compute(List<StrategyEvaluation> evaluationsRanked,
                                                  EvaluationService evaluations, String worldId) {
        List<CompensationEntry> out = new ArrayList<>();
        java.util.Map<String, Double> gapCache = new java.util.HashMap<>();
        java.util.Map<String, io.liftandshift.strikebench.market.EventService.EarningsProximity> eventCache =
                new java.util.HashMap<>();
        for (StrategyEvaluation evaluation : evaluationsRanked) {
            var candidate = evaluation.candidate();
            // Premium collectors only, by design. An UNPRICED package is not a collector either:
            // "premium per unit of risk" has no numerator without a package price, and admitting it
            // with an assumed zero would rank an unknown package as if it collected nothing (§3.2).
            // Absence here is silent by the same design that omits every non-collector.
            Long optionPremium = candidate.price() == null ? null
                    : candidate.price().optionNetPremiumCents();
            if (optionPremium == null || optionPremium <= 0) continue;
            Long openingFees = candidate.price().openingFeesCents();
            if (openingFees == null) continue;
            long premium = optionPremium - openingFees;
            if (premium <= 0) continue;
            PremiumMetric premiumMetric = premiumMetric(evaluation, premium);
            if (premiumMetric == null) continue;
            String symbol = evaluation.spec().symbol();
            Double gap = gapCache.computeIfAbsent(symbol, sym -> {
                try { return evaluations.gapFrequency(sym, worldId); }
                catch (RuntimeException e) { return null; }
            });
            List<CompensationComponent> components = new ArrayList<>();
            double premiumNorm;
            if (premiumMetric.kind() == PremiumMetricKind.COLLATERAL_PREMIUM_YIELD) {
                premiumNorm = clamp01(premiumMetric.annualizedPct() / 30.0);
                components.add(new CompensationComponent("Collateral premium yield", 0.35, premiumNorm,
                        String.format("$%,d premium / $%,d collateral = %.2f%% over %d days; "
                                        + "%.1f%%/yr IF repeatable",
                                premiumMetric.premiumCents() / 100,
                                premiumMetric.denominatorCents() / 100,
                                premiumMetric.periodReturnPct(), premiumMetric.holdingPeriodDays(),
                                premiumMetric.annualizedPct())));
            } else {
                premiumNorm = clamp01(premiumMetric.periodReturnPct() / 10.0);
                components.add(new CompensationComponent("Defined-risk period premium", 0.35,
                        premiumNorm, String.format("$%,d premium / $%,d exact economic exposure "
                                        + "= %.2f%% for this %d-day period; not an annualized yield",
                                premiumMetric.premiumCents() / 100,
                                premiumMetric.denominatorCents() / 100,
                                premiumMetric.periodReturnPct(), premiumMetric.holdingPeriodDays())));
            }
            Double vrp = evaluation.volatility() == null ? null : evaluation.volatility().varianceRiskPremium();
            components.add(new CompensationComponent("Variance risk premium", 0.20,
                    vrp == null ? 0.5 : clamp01(0.5 + vrp * 5.0),
                    vrp == null ? "IV vs realized unavailable — treated as neutral"
                            : String.format("options price %.0f vol points %s realized",
                                    Math.abs(vrp * 100), vrp >= 0 ? "over" : "under")));
            components.add(new CompensationComponent("Gap risk", 0.15,
                    gap == null ? 0.5 : clamp01(1.0 - gap * 8.0),
                    gap == null ? "gap history too thin — treated as neutral"
                            : String.format("%.0f%% of sessions opened >2%% from the prior close", gap * 100)));
            LocalDate packageEnd = latestExpiration(candidate);
            String eventKey = symbol + "|" + packageEnd + "|" + worldId;
            var event = eventCache.computeIfAbsent(eventKey,
                    ignored -> evaluations.eventProximity(symbol, packageEnd, worldId));
            double eventValue = !event.available() ? 0.5 : event.likelyBefore() ? 0.0 : 1.0;
            String eventNote = event.note() + (!event.available()
                    ? "; treated as neutral, not as no event"
                    : event.likelyBefore()
                        ? "; compensation score reduced for event-gap exposure"
                        : "; no event-window penalty for this dated package");
            components.add(new CompensationComponent("Earnings proximity", 0.10,
                    eventValue, eventNote));
            components.add(new CompensationComponent("Liquidity", 0.10,
                    clamp01(candidate.liquidityScore()), "tighter spreads keep the premium real"));
            Double roc = evaluation.capital() == null ? null : evaluation.capital().returnOnCapitalPct();
            components.add(new CompensationComponent("Capital efficiency", 0.10,
                    roc == null ? 0.4 : clamp01(roc / (roc + 50.0)),
                    roc == null ? "return on capital undefined"
                            : String.format("%.0f%% best-case return on the capital at work", roc)));
            double score = 0;
            for (CompensationComponent component : components) score += component.weight() * component.value();
            out.add(new CompensationEntry(symbol, candidate.strategy(), candidate.label(),
                    Math.round(score * 1000.0) / 10.0, premiumMetric, components, evaluation.id()));
        }
        out.sort(java.util.Comparator.comparingDouble(CompensationEntry::score).reversed());
        return out;
    }

    private static LocalDate latestExpiration(Candidate candidate) {
        LocalDate max = null;
        for (LegView leg : candidate.legs() == null ? List.<LegView>of() : candidate.legs()) {
            if (leg.expiration() == null) continue;
            try {
                LocalDate expiration = LocalDate.parse(leg.expiration());
                if (max == null || expiration.isAfter(max)) max = expiration;
            } catch (RuntimeException ignored) { /* invalid package date remains unavailable evidence */ }
        }
        return max;
    }

    private static PremiumMetric premiumMetric(StrategyEvaluation evaluation, long premiumCents) {
        Candidate candidate = evaluation.candidate();
        var capital = candidate.capital();
        int days = evaluation.capital() == null ? 0 : evaluation.capital().daysToExpiry();
        if (candidate.annualizedYieldPct() != null) {
            Long collateral = collateral(candidate, capital);
            if (collateral == null || collateral <= 0) return null;
            return new PremiumMetric(PremiumMetricKind.COLLATERAL_PREMIUM_YIELD,
                    premiumCents, collateral, days,
                    100.0 * premiumCents / collateral, candidate.annualizedYieldPct(),
                    "Net option premium after opening commission divided by exact cash or share collateral.");
        }
        Long exposure = capital.economicExposureCents();
        if (exposure == null || exposure <= 0) return null;
        return new PremiumMetric(PremiumMetricKind.DEFINED_RISK_PERIOD_PREMIUM,
                premiumCents, exposure, days, 100.0 * premiumCents / exposure, null,
                "Net opening premium after commission divided by exact defined-risk economic exposure.");
    }

    private static Long collateral(Candidate candidate,
                                   io.liftandshift.strikebench.strategy.CapitalRequirement capital) {
        if ("CASH_SECURED_PUT".equals(candidate.strategy())) return capital.reserveCents();
        if (!"COVERED_CALL".equals(candidate.strategy())) return null;
        Long stockCash = candidate.price().stockCashFlowCents();
        if (stockCash != null && stockCash < 0) return Math.negateExact(stockCash);
        if (candidate.combinedMaxLossCents() == null
                || candidate.price().optionNetPremiumCents() == null) return null;
        return Math.addExact(candidate.combinedMaxLossCents(),
                Math.max(0L, candidate.price().optionNetPremiumCents()));
    }

    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }
}
