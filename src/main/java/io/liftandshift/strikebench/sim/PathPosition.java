package io.liftandshift.strikebench.sim;

import io.liftandshift.strikebench.market.MarketHours;
import io.liftandshift.strikebench.model.Leg;

import java.time.LocalDate;
import java.util.List;

/** Canonical strategy package for path valuation: one validated model-leg list at one lane date. */
public record PathPosition(LocalDate asOf, List<Leg> legs) {
    public PathPosition {
        if (asOf == null) throw new IllegalArgumentException("path valuation date is required");
        if (legs == null || legs.isEmpty()) throw new IllegalArgumentException("at least one leg is required");
        legs = List.copyOf(legs);
        if (legs.size() > 8) throw new IllegalArgumentException("at most 8 legs");
        for (Leg leg : legs) {
            if (leg == null) throw new IllegalArgumentException("position legs cannot be null");
            if (leg.ratio() > 10) throw new IllegalArgumentException("leg ratio must be 1..10");
            if (!leg.isStock() && leg.expiration().isBefore(asOf)) {
                throw new IllegalArgumentException("option expiration cannot precede the path valuation date");
            }
        }
    }

    /** Sessions remaining in (asOf, expiration]; zero means the current session's closing bell. */
    public int expiryDay(Leg leg) {
        return leg.isStock() ? 0 : MarketHours.tradingDaysBetween(asOf, leg.expiration());
    }
}
