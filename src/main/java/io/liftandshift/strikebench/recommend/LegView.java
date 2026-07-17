package io.liftandshift.strikebench.recommend;

import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Wire form of a leg: plain strings only (no java.time / BigDecimal in JSON DTOs). */
public record LegView(
        String action,       // BUY | SELL
        String type,         // CALL | PUT | STOCK
        String strike,       // decimal string, null for stock
        String expiration,   // ISO date, null for stock
        int ratio,
        String entryPrice,   // decimal string per share, may be null on requests
        int multiplier,      // required deliverable units per ratio unit
        String positionEffect // OPEN | CLOSE; pricing consumes OPEN, transformation preview consumes both
) {
    public LegView {
        if (action == null || action.isBlank()) throw new IllegalArgumentException("leg action required");
        if (type == null || type.isBlank()) throw new IllegalArgumentException("leg type required");
        if (ratio < 1) throw new IllegalArgumentException("leg ratio must be >= 1");
        if (multiplier < 1 || multiplier > 10_000) {
            throw new IllegalArgumentException("leg multiplier must be 1..10,000");
        }
        if (positionEffect == null || !("OPEN".equalsIgnoreCase(positionEffect)
                || "CLOSE".equalsIgnoreCase(positionEffect))) {
            throw new IllegalArgumentException("leg positionEffect must be OPEN or CLOSE");
        }
    }

    public static LegView of(Leg leg) {
        return new LegView(
                leg.action().name(),
                leg.isStock() ? "STOCK" : leg.type().name(),
                // Canonical decimal formatting (strip trailing zeros) so a candidate's legs round-trip
                // byte-identically through the custom-builder store, which persists + re-emits via the
                // same stripped form. Without this, "13.20" (engine) vs "13.2" (store) broke exact-leg
                // equality whenever a strip-sensitive price surfaced at candidates[0].
                leg.isStock() ? null : leg.strike().stripTrailingZeros().toPlainString(),
                leg.isStock() ? null : leg.expiration().toString(),
                leg.ratio(),
                leg.entryPrice().stripTrailingZeros().toPlainString(),
                leg.multiplier(),
                "OPEN");
    }

    public Leg toLeg() {
        LegAction a = LegAction.valueOf(action.toUpperCase(java.util.Locale.ROOT));
        BigDecimal price = entryPrice == null ? BigDecimal.ZERO : new BigDecimal(entryPrice);
        if ("STOCK".equalsIgnoreCase(type)) {
            return new Leg(a, null, null, null, ratio, price, multiplier);
        }
        return Leg.option(a, OptionType.valueOf(type.toUpperCase(java.util.Locale.ROOT)),
                new BigDecimal(strike), LocalDate.parse(expiration), ratio, price, multiplier);
    }
}
