package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.recommend.LegView;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * Composite-objective LENS machinery (folded Phase 9): a lens is DATA — an applicability
 * predicate over the declared objective plus a contribution function — so the next composite
 * objective is a registry entry, not new code paths. Lenses add named score components and
 * plain-language cautions; they never touch economics, risk, or the coherence verdict.
 *
 * First registered lens: INCOME-WHILE-ACCUMULATING — declared income with an appetite for
 * assignment that ADDS shares. Deep-discount short puts are better accumulation entries; income
 * is honest only as a %-of-capital-deployed with the "if repeatable" label; renting out upside
 * of shares being accumulated erodes the position; assignment concentrates the book.
 */
public final class ObjectiveLenses {
    private ObjectiveLenses() {}

    public record LensComponent(String name, double weight, double value, String note) {}
    public record LensResult(List<LensComponent> components, List<String> cautions) {
        static final LensResult NONE = new LensResult(List.of(), List.of());
    }

    private record Lens(String name, Predicate<DeclaredObjective> appliesTo,
                        BiFunction<Candidate, EvalContext, LensResult> contribute) {}

    private static final List<Lens> REGISTRY = List.of(
            new Lens("income-while-accumulating",
                    declared -> declared != null
                            && declared.objective() != null
                            && Set.of("INCOME", "ACCUMULATE").contains(declared.objective())
                            && declared.assignmentPreference() != null
                            && Set.of("PREFER_BELOW_BASIS", "SEEK").contains(declared.assignmentPreference()),
                    ObjectiveLenses::incomeWhileAccumulating));

    /** Every applicable lens's contribution, merged. Absent declaration = no lens, no cost. */
    public static LensResult apply(DeclaredObjective declared, Candidate c, EvalContext ctx) {
        List<LensComponent> components = null;
        List<String> cautions = null;
        for (Lens lens : REGISTRY) {
            if (!lens.appliesTo().test(declared)) continue;
            LensResult result = lens.contribute().apply(c, ctx);
            if (!result.components().isEmpty()) {
                if (components == null) components = new ArrayList<>();
                components.addAll(result.components());
            }
            if (!result.cautions().isEmpty()) {
                if (cautions == null) cautions = new ArrayList<>();
                cautions.addAll(result.cautions());
            }
        }
        if (components == null && cautions == null) return LensResult.NONE;
        return new LensResult(components == null ? List.of() : components,
                cautions == null ? List.of() : cautions);
    }

    private static LensResult incomeWhileAccumulating(Candidate c, EvalContext ctx) {
        List<LensComponent> components = new ArrayList<>();
        List<String> cautions = new ArrayList<>();

        // Deep-discount entries: a short put IS a resting accumulation bid at its strike.
        // Deeper below the current price = a better entry for the position being built.
        BigDecimal shortPutStrike = shortPutStrike(c);
        if (shortPutStrike != null && ctx.underlyingCents() > 0) {
            double spot = ctx.underlyingCents() / 100.0;
            double discount = (spot - shortPutStrike.doubleValue()) / spot;
            double value = Math.max(0.0, Math.min(1.0, discount / 0.15)); // ~15% below spot = full credit
            components.add(new LensComponent("Accumulation entry discount", 0.08, value,
                    String.format("the $%s strike is %.0f%% below the current price — you are paid to bid there",
                            shortPutStrike.stripTrailingZeros().toPlainString(), discount * 100)));
            if (c.annualizedYieldPct() != null) {
                long premium = Math.max(0, c.entryNetPremiumCents());
                long collateral = Math.round(shortPutStrike.doubleValue() * 100) * 100L;
                long redeployShares = collateral > 0 ? premium / (collateral / 100) : 0;
                cautions.add(String.format("Income honesty: %.1f%% annualized on the strike collateral "
                                + "IF this cycle is repeatable — one cycle proves nothing. Redeployed instead of "
                                + "taken, this cycle's premium buys ~%d more share%s at the strike.",
                        c.annualizedYieldPct(), redeployShares, redeployShares == 1 ? "" : "s"));
            }
        }

        // NAV erosion: renting out the upside of shares you are trying to ACCUMULATE points the
        // structure against the objective — call assignment SELLS the position being built.
        if (hasShortCallOverShares(c)) {
            cautions.add("Accumulation vs. rented upside: assignment on the short call would SELL shares "
                    + "you declared you are building. Premium collected now erodes position growth if the "
                    + "stock runs — judge this trade against that trade-off, not premium alone.");
        }

        // Concentration: assignment converts cash into MORE of the same name.
        PortfolioExposureContext exposure = ctx.portfolioExposure();
        long obligation = assignmentObligationCents(c);
        if (exposure != null && exposure.complete() && obligation > 0) {
            long grossAfter = exposure.grossDollarDeltaCents() + obligation;
            double share = grossAfter > 0
                    ? (double) (exposure.symbolGrossDollarDeltaCents() + obligation) / grossAfter : 0;
            if (share > 0.25) {
                cautions.add(String.format("Concentration: if assigned, this name would be ~%.0f%% of your "
                        + "book's gross exposure (%s). Accumulating is a choice; concentrating is a consequence "
                        + "— make it deliberately.", share * 100, exposure.basis()));
            }
        }
        return new LensResult(components, cautions);
    }

    private static BigDecimal shortPutStrike(Candidate c) {
        if (c.legs() == null) return null;
        return c.legs().stream()
                .filter(l -> "PUT".equalsIgnoreCase(l.type()) && "SELL".equalsIgnoreCase(l.action()))
                .map(LegView::strike)
                .filter(java.util.Objects::nonNull)
                .map(BigDecimal::new)
                .findFirst().orElse(null);
    }

    private static boolean hasShortCallOverShares(Candidate c) {
        if (Boolean.TRUE.equals(c.usesHeldShares())) {
            return c.legs() != null && c.legs().stream()
                    .anyMatch(l -> "CALL".equalsIgnoreCase(l.type()) && "SELL".equalsIgnoreCase(l.action()));
        }
        if (c.legs() == null) return false;
        boolean stock = c.legs().stream().anyMatch(l -> "STOCK".equalsIgnoreCase(l.type())
                && "BUY".equalsIgnoreCase(l.action()));
        boolean shortCall = c.legs().stream().anyMatch(l -> "CALL".equalsIgnoreCase(l.type())
                && "SELL".equalsIgnoreCase(l.action()));
        return stock && shortCall;
    }

    /** Cash the book must convert into shares if every short put is assigned (strike × units). */
    private static long assignmentObligationCents(Candidate c) {
        if (c.legs() == null) return 0;
        long total = 0;
        for (LegView leg : c.legs()) {
            if (!"PUT".equalsIgnoreCase(leg.type()) || !"SELL".equalsIgnoreCase(leg.action())
                    || leg.strike() == null) continue;
            long strikeCents = new BigDecimal(leg.strike()).movePointRight(2).longValue();
            total = Math.addExact(total, Math.multiplyExact(strikeCents,
                    (long) leg.ratio() * leg.multiplier() * Math.max(1, c.qty())));
        }
        return total;
    }
}
