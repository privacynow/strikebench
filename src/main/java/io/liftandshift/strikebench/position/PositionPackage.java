package io.liftandshift.strikebench.position;

import io.liftandshift.strikebench.model.Symbol;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One pricing input for a hypothetical draft, a practice trade, or a tracked structure revision.
 * It carries facts only; pricing and classification remain owned by their existing engines.
 */
public record PositionPackage(
        String id,
        PositionDomain.PackageSource source,
        PositionDomain.BookType bookType,
        String symbol,
        long packageQuantity,
        Long exactPackageCashCents,
        OffsetDateTime asOf,
        List<Leg> legs
) {
    public PositionPackage {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("position package id is required");
        if (source == null || bookType == null) throw new IllegalArgumentException("position package provenance is required");
        symbol = Symbol.normalize(symbol);
        if (packageQuantity <= 0) throw new IllegalArgumentException("position package quantity must be positive");
        legs = legs == null ? List.of() : List.copyOf(legs);
        if (legs.isEmpty()) throw new IllegalArgumentException("position package requires at least one leg");
    }

    public record Leg(
            int index,
            String action,
            String instrumentType,
            String symbol,
            String optionType,
            BigDecimal strike,
            LocalDate expiration,
            long quantity,
            int multiplier,
            BigDecimal price,
            PositionDomain.PriceAuthority priceAuthority
    ) {
        public Leg {
            if (index < 0) throw new IllegalArgumentException("leg index cannot be negative");
            symbol = Symbol.normalize(symbol);
            if (quantity <= 0 || multiplier <= 0) throw new IllegalArgumentException("leg quantity and multiplier must be positive");
            if (price != null && price.signum() < 0) throw new IllegalArgumentException("leg price cannot be negative");
        }
    }
}
