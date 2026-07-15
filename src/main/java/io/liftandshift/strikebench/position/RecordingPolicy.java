package io.liftandshift.strikebench.position;

import java.math.BigDecimal;
import java.util.List;

/** Event-aware rules that keep modeled or missing fills out of the authoritative ledger. */
public final class RecordingPolicy {
    private RecordingPolicy() {}

    public enum EventType { TRADE, ROLL, EXPIRATION, ASSIGNMENT, EXERCISE, MARK_TO_MARKET, ADJUSTMENT }

    public static void validate(EventType event, List<LegFact> legs) {
        if (event == null) throw new IllegalArgumentException("recording event is required");
        if (legs == null || legs.isEmpty()) throw new IllegalArgumentException(event + " requires at least one leg");
        switch (event) {
            case TRADE, ROLL, ADJUSTMENT -> validateOrdinaryFills(legs);
            case EXPIRATION -> validateExpiration(legs);
            case ASSIGNMENT, EXERCISE -> validateConversion(legs);
            case MARK_TO_MARKET -> validateMark(legs);
        }
    }

    private static void validateOrdinaryFills(List<LegFact> legs) {
        for (LegFact leg : legs) {
            requirePrice(leg);
            if (leg.price().signum() == 0) throw new IllegalArgumentException("market transaction leg prices must be positive");
            if (leg.authority() == PositionDomain.PriceAuthority.MODELED) {
                throw new IllegalArgumentException("modeled marks cannot be recorded as real fills");
            }
        }
    }

    private static void validateExpiration(List<LegFact> legs) {
        for (LegFact leg : legs) {
            requirePrice(leg);
            if (!"OPTION".equals(leg.instrumentType()) || !"CLOSE".equals(leg.positionEffect())
                    || leg.price().signum() != 0) {
                throw new IllegalArgumentException("expiration requires option CLOSE legs at zero");
            }
        }
    }

    private static void validateConversion(List<LegFact> legs) {
        List<LegFact> options = legs.stream().filter(l -> "OPTION".equals(l.instrumentType())).toList();
        List<LegFact> stocks = legs.stream().filter(l -> "STOCK".equals(l.instrumentType())).toList();
        if (options.isEmpty() || stocks.isEmpty()) throw new IllegalArgumentException("assignment and exercise require option and stock legs");
        for (LegFact option : options) {
            requirePrice(option);
            if (!"CLOSE".equals(option.positionEffect()) || option.price().signum() != 0 || option.strike() == null) {
                throw new IllegalArgumentException("the converted option must CLOSE at zero with its strike recorded");
            }
            boolean deliveredAtStrike = stocks.stream().anyMatch(stock -> stock.symbol().equals(option.symbol())
                    && stock.price() != null && stock.price().compareTo(option.strike()) == 0);
            if (!deliveredAtStrike) throw new IllegalArgumentException("the delivered stock leg must be recorded at the option strike");
        }
        for (LegFact stock : stocks) {
            requirePrice(stock);
            if (stock.price().signum() <= 0 || stock.authority() == PositionDomain.PriceAuthority.MODELED) {
                throw new IllegalArgumentException("the delivered stock leg requires a factual strike price");
            }
        }
        for (String symbol : options.stream().map(LegFact::symbol).distinct().toList()) {
            long optionUnits = options.stream().filter(l -> symbol.equals(l.symbol()))
                    .mapToLong(l -> Math.multiplyExact(l.quantity(), (long) l.multiplier()))
                    .reduce(0L, Math::addExact);
            long stockUnits = stocks.stream().filter(l -> symbol.equals(l.symbol()))
                    .mapToLong(LegFact::quantity).reduce(0L, Math::addExact);
            if (optionUnits != stockUnits) {
                throw new IllegalArgumentException("the delivered stock quantity must equal the converted option deliverable");
            }
        }
    }

    private static void validateMark(List<LegFact> legs) {
        for (LegFact leg : legs) {
            requirePrice(leg);
            if (leg.price().signum() < 0) throw new IllegalArgumentException("mark prices cannot be negative");
        }
    }

    private static void requirePrice(LegFact leg) {
        if (leg == null || leg.price() == null) throw new IllegalArgumentException("every recorded leg requires an exact price");
        if (leg.authority() == null) throw new IllegalArgumentException("every recorded leg requires price provenance");
    }

    public record LegFact(String instrumentType, String positionEffect, String symbol,
                          BigDecimal strike, BigDecimal price, long quantity, int multiplier,
                          PositionDomain.PriceAuthority authority) {
        public LegFact {
            instrumentType = instrumentType == null ? null : instrumentType.trim().toUpperCase();
            positionEffect = positionEffect == null ? null : positionEffect.trim().toUpperCase();
            symbol = symbol == null ? null : symbol.trim().toUpperCase();
            if (quantity <= 0 || multiplier <= 0) {
                throw new IllegalArgumentException("recorded leg quantity and multiplier must be positive");
            }
        }
    }
}
