package io.liftandshift.strikebench.strategy;

import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionQuote;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.pricing.PayoffCurve;
import io.liftandshift.strikebench.util.Money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Risk screen for proposed trades. BLOCKs: undefined risk, blocked-by-default families,
 * untradable marks, chainless symbols, insufficient buying power. WARNs: 0DTE gamma,
 * earnings/dividend events, assignment risk on short ITM calls near ex-div, wide spreads,
 * thin open interest/volume, non-realtime data.
 */
public final class Guardrails {

    /** Spread wider than this fraction of mid is flagged as expensive to trade. */
    static final double WIDE_SPREAD = 0.10;
    static final long LOW_OI = 100;
    static final long LOW_VOLUME = 10;

    private Guardrails() {}

    /**
     * @param quotes current contract quotes aligned by index with legs (null entries for stock legs)
     * @param spot current underlying price, may be null when unknown
     * @param lockedShareLots 100-share lots of HELD underlying shares the account will lock to this
     *                        trade (total across qty). Held shares cover short calls the way an
     *                        explicit stock leg would — they turn a "naked" call into a covered one.
     */
    public record Proposal(
            StrategyFamily family,
            List<Leg> legs,
            int qty,
            List<OptionQuote> quotes,
            BigDecimal spot,
            Freshness freshness,
            LocalDate today,
            long buyingPowerCents,
            boolean allowUndefinedRisk,
            boolean earningsSoon,
            boolean exDividendSoon,
            long lockedShareLots
    ) {
        /** Historical shape without held-share coverage. */
        public Proposal(StrategyFamily family, List<Leg> legs, int qty, List<OptionQuote> quotes,
                        BigDecimal spot, Freshness freshness, LocalDate today, long buyingPowerCents,
                        boolean allowUndefinedRisk, boolean earningsSoon, boolean exDividendSoon) {
            this(family, legs, qty, quotes, spot, freshness, today, buyingPowerCents,
                    allowUndefinedRisk, earningsSoon, exDividendSoon, 0);
        }
    }

    public static Verdict check(Proposal p) {
        List<String> blocks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (p.family() != null && p.family().blockedByDefault() && !p.allowUndefinedRisk()) {
            blocks.add(p.family().display() + " has undefined risk and is blocked by default. "
                    + "The maximum loss is unlimited (or effectively so) — a defined-risk alternative covers the same thesis.");
        }

        if (p.legs() == null || p.legs().isEmpty()) {
            blocks.add("No legs provided");
            return Verdict.of(blocks, warnings);
        }

        for (Leg leg : p.legs()) {
            if (!leg.isStock() && leg.expiration().isBefore(p.today())) {
                blocks.add("Contract " + leg.strike() + " " + leg.type() + " " + leg.expiration() + " is already expired");
            }
        }
        if (!blocks.isEmpty()) return Verdict.of(blocks, warnings);

        // Marks / chain availability
        boolean anyOption = p.legs().stream().anyMatch(l -> !l.isStock());
        for (int i = 0; i < p.legs().size(); i++) {
            Leg leg = p.legs().get(i);
            if (leg.isStock()) continue;
            OptionQuote q = p.quotes() != null && i < p.quotes().size() ? p.quotes().get(i) : null;
            if (q == null || !q.hasMark()) {
                blocks.add("No tradable market for " + leg.strike() + " " + leg.type() + " " + leg.expiration()
                        + " — the symbol may not have listed options");
                continue;
            }
            if (q.markFreshness() == Freshness.STALE || q.markFreshness() == Freshness.MISSING) {
                blocks.add("Quote for " + q.occSymbol() + " is " + q.markFreshness() + "; refusing to size a trade against it");
            }
            // Impossible price: an option marked below intrinsic vs the same feed's underlying
            if (p.spot() != null && q.mid() != null) {
                java.math.BigDecimal intrinsic = leg.intrinsicPerShare(p.spot());
                java.math.BigDecimal tolerance = intrinsic.multiply(new java.math.BigDecimal("0.02")).max(new java.math.BigDecimal("0.05"));
                if (q.mid().compareTo(intrinsic.subtract(tolerance)) < 0) {
                    blocks.add("Quote integrity: " + q.occSymbol() + " marked " + q.mid().toPlainString()
                            + " below its intrinsic value " + intrinsic.toPlainString() + " — stale or dead quote");
                }
            }
            double spreadPct = q.spreadPct();
            if (!Double.isNaN(spreadPct) && spreadPct > WIDE_SPREAD) {
                warnings.add(String.format("Wide bid/ask on %s (%.0f%% of mid) — entering and exiting will cost real edge",
                        q.occSymbol(), spreadPct * 100));
            }
            if (q.openInterest() != null && q.openInterest() < LOW_OI) {
                warnings.add("Low open interest (" + q.openInterest() + ") on " + q.occSymbol() + " — may be hard to exit at fair value");
            } else if (q.volume() != null && q.volume() < LOW_VOLUME) {
                warnings.add("Very low volume today on " + q.occSymbol());
            }
        }
        if (!blocks.isEmpty()) return Verdict.of(blocks, warnings);

        // Risk shape. Held shares locked to the trade act as call cover: evaluate the position
        // as if the locked lots were explicit long-stock legs (that is exactly what they are,
        // economically — the trade layer enforces the lock).
        int lotsPerUnit = p.qty() > 0 ? (int) Math.min(Integer.MAX_VALUE, p.lockedShareLots() / p.qty()) : 0;
        List<Leg> riskLegs = p.legs();
        if (lotsPerUnit > 0 && p.spot() != null) {
            riskLegs = new ArrayList<>(p.legs());
            riskLegs.add(Leg.stock(LegAction.BUY, lotsPerUnit, p.spot()));
        }
        boolean mixedExpirations = p.legs().stream().filter(l -> !l.isStock())
                .map(Leg::expiration).distinct().count() > 1;
        if (mixedExpirations) {
            long net = PayoffCurve.of(p.legs(), p.qty()).entryNetPremiumCents();
            boolean shareCovered = lotsPerUnit > 0 && CoverageCheck.uncoveredShorts(p.legs(), lotsPerUnit).isEmpty();
            if (net >= 0 && !p.allowUndefinedRisk() && !shareCovered) {
                blocks.add("Multi-expiration credit positions can carry undefined risk after the near leg expires; blocked by default");
            }
            // Structural check: every short unit must be covered by a longer-dated, loss-capping
            // long (or stock — including held shares locked to the trade). Catches reverse
            // calendars, inverted diagonals, net-short ratios that look like harmless "debits"
            // to a premium-sign test.
            if (!p.allowUndefinedRisk()) {
                blocks.addAll(CoverageCheck.uncoveredShorts(p.legs(), lotsPerUnit));
            }
            if (net < 0 && -net > p.buyingPowerCents()) {
                blocks.add("Debit " + Money.fmt(-net) + " exceeds available buying power " + Money.fmt(p.buyingPowerCents()));
            }
        } else {
            PayoffCurve optionCurve = PayoffCurve.of(p.legs(), p.qty());
            boolean shareCovered = optionCurve.maxLossUnbounded()
                    && lotsPerUnit > 0 && CoverageCheck.uncoveredShorts(p.legs(), lotsPerUnit).isEmpty();
            PayoffCurve curve = shareCovered && riskLegs != p.legs() ? PayoffCurve.of(riskLegs, p.qty()) : optionCurve;
            if (curve.maxLossUnbounded() && !p.allowUndefinedRisk()) {
                blocks.add("Undefined (unlimited) maximum loss — blocked. Add a protective wing to cap the risk.");
            } else if (!curve.maxLossUnbounded()) {
                long maxLoss = curve.maxLossCents();
                if (maxLoss <= 0 && !shareCovered) {
                    blocks.add("Priced as risk-free — impossible in real markets; the quote data is unreliable (stale, crossed, or expired book)");
                } else if (!shareCovered && maxLoss > p.buyingPowerCents()) {
                    blocks.add("Max loss " + Money.fmt(maxLoss) + " exceeds available buying power " + Money.fmt(p.buyingPowerCents()));
                }
            }
            if (shareCovered) {
                warnings.add("Short calls are covered by " + p.lockedShareLots() * 100
                        + " held shares locked to this trade — upside above the strike is given up, and the shares keep their own downside");
            }
        }

        // Event / timing warnings
        LocalDate today = p.today();
        if (anyOption && p.legs().stream().anyMatch(l -> !l.isStock() && l.expiration().equals(today))) {
            warnings.add("0DTE position: gamma risk is extreme and the value can go to zero within hours");
        }
        if (p.earningsSoon()) {
            warnings.add("Event-like news detected (earnings/guidance keywords in recent headlines) — "
                    + "implied volatility and price can gap around events. This is a news signal, not a confirmed calendar date.");
        }
        if (p.exDividendSoon()) {
            warnings.add("Ex-dividend date falls before expiration");
            if (p.spot() != null) {
                boolean shortItmCall = p.legs().stream().anyMatch(l -> !l.isStock()
                        && l.action() == LegAction.SELL && l.type() == OptionType.CALL
                        && l.strike().compareTo(p.spot()) < 0);
                if (shortItmCall) {
                    warnings.add("Short in-the-money call near ex-dividend: early assignment risk is elevated");
                }
            }
        }
        if (p.freshness() == Freshness.DELAYED || p.freshness() == Freshness.EOD) {
            warnings.add("Pricing uses " + p.freshness() + " data — real quotes may differ");
        }

        return Verdict.of(blocks, warnings);
    }
}
