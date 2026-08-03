package io.liftandshift.strikebench.paper;

/** A paper (or, later, shadow-live) account. All balances are exact integer cents. */
public record Account(
        String id,
        String name,
        String type,
        long startingCashCents,
        long cashCents,
        long reservedCents,
        boolean hasTraded,
        String createdAt,
        String updatedAt,
        String worldId    // null = observed modes; set = a SIMULATION account bound to one world
) {
    public long buyingPowerCents() { return cashCents - reservedCents; }
    public boolean simulation() { return worldId != null && !worldId.isBlank(); }

    /** The explicit market token used outside persistence; observed is never represented as null. */
    public String marketWorld() {
        if ("DEMO".equals(type)) return "demo";
        return simulation() ? worldId : "observed";
    }
}
