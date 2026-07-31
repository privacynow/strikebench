package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.model.Leg;

import java.util.List;

/**
 * The one server-owned minimum price increment for signed package limits.
 *
 * <p>The order contract carries a total signed package amount in integer cents, while the live
 * adapter transmits a per-unit price with four decimals. A total that cannot be represented at
 * that precision must be rejected here, before it reaches the broker adapter. Exchange/class
 * minimum ticks remain a distinct fact requiring authoritative instrument metadata.</p>
 */
public final class PackageLimitTickPolicy {
    private PackageLimitTickPolicy() {}

    public record Receipt(long tickCents, String basis) {}

    public static Receipt receipt(List<Leg> legs, int quantity) {
        requirePackage(legs);
        long tick = tickCents(legs, quantity);
        return new Receipt(tick,
                "Minimum total-net edit that remains exactly representable at the live "
                        + "adapter's four-decimal per-unit precision for quantity " + quantity + ".");
    }

    public static void requireValid(OrderInstruction instruction, List<Leg> legs, int quantity) {
        requirePackage(legs);
        if (instruction == null || instruction.type() != OrderInstruction.Type.LIMIT) return;
        requireAligned(instruction, tickCents(legs, quantity), quantity);
    }

    /** Adapter-side defense using the exact unit count it will transmit. */
    public static void requireValid(OrderInstruction instruction, boolean stockOnly,
                                    long pricedUnits, int quantity) {
        if (instruction == null || instruction.type() != OrderInstruction.Type.LIMIT) return;
        if (quantity < 1 || pricedUnits <= 0) {
            throw new IllegalArgumentException("package has no priced units");
        }
        long tick = stockOnly
                ? pricedUnits / gcd(pricedUnits, 100L)
                : optionPackageTickCents(quantity);
        requireAligned(instruction, tick, quantity);
    }

    private static void requireAligned(
            OrderInstruction instruction, long tick, int quantity) {
        long limit = instruction.limitNetCents();
        if (Math.floorMod(limit, tick) != 0) {
            throw new IllegalArgumentException(
                    "limitNetCents must align to the total package tick of " + tick
                            + " cents for quantity " + quantity);
        }
    }

    /**
     * Total-net cents that map exactly to the adapter's four-decimal per-unit price.
     * Option and mixed packages are transmitted per 100-share contract. Stock-only packages use
     * their actual ratio/multiplier unit count, matching the adapter.
     */
    public static long tickCents(List<Leg> legs, int quantity) {
        requirePackage(legs);
        if (quantity < 1) throw new IllegalArgumentException("package quantity must be positive");
        if (legs.stream().anyMatch(leg -> !leg.isStock())) {
            return quantity;
        }
        long units = 0;
        for (Leg leg : legs) {
            units = Math.addExact(units, Math.multiplyExact((long) quantity,
                    Math.multiplyExact((long) leg.ratio(), (long) leg.multiplier())));
        }
        if (units <= 0) throw new IllegalArgumentException("stock package has no priced units");
        return units / gcd(units, 100L);
    }

    /** Package-price receipts are option-package receipts and disclose their package quantity. */
    public static long optionPackageTickCents(int quantity) {
        if (quantity < 1) throw new IllegalArgumentException("package quantity must be positive");
        return quantity;
    }

    private static long gcd(long a, long b) {
        long x = Math.abs(a);
        long y = Math.abs(b);
        while (y != 0) {
            long next = x % y;
            x = y;
            y = next;
        }
        return x;
    }

    private static void requirePackage(List<Leg> legs) {
        if (legs == null || legs.isEmpty()) {
            throw new IllegalArgumentException("package tick policy requires at least one leg");
        }
    }
}
