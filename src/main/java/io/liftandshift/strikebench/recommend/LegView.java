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
        String entryPrice    // decimal string per share, may be null on requests
) {
    public static LegView of(Leg leg) {
        return new LegView(
                leg.action().name(),
                leg.isStock() ? "STOCK" : leg.type().name(),
                leg.isStock() ? null : leg.strike().toPlainString(),
                leg.isStock() ? null : leg.expiration().toString(),
                leg.ratio(),
                leg.entryPrice().toPlainString());
    }

    public Leg toLeg() {
        LegAction a = LegAction.valueOf(action.toUpperCase(java.util.Locale.ROOT));
        BigDecimal price = entryPrice == null ? BigDecimal.ZERO : new BigDecimal(entryPrice);
        if ("STOCK".equalsIgnoreCase(type)) {
            return Leg.stock(a, ratio, price);
        }
        return Leg.option(a, OptionType.valueOf(type.toUpperCase(java.util.Locale.ROOT)),
                new BigDecimal(strike), LocalDate.parse(expiration), ratio, price);
    }
}
