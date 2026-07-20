package io.liftandshift.strikebench.paper;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Execution instructions for one signed option package.
 *
 * <p>The package amount is always signed economics: positive means a credit and negative means
 * a debit.  MARKET/LIMIT describes how that economic package may be executed; it never describes
 * whether the package is a credit or debit.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderInstruction(Type type, Long limitNetCents, TimeInForce timeInForce) {
    public enum Type { MARKET, LIMIT }
    public enum TimeInForce { DAY }
    public enum Executability { IMMEDIATE, RESTING, UNAVAILABLE }

    public OrderInstruction {
        type = type == null ? Type.MARKET : type;
        timeInForce = timeInForce == null ? TimeInForce.DAY : timeInForce;
        if (type == Type.MARKET && limitNetCents != null) {
            throw new IllegalArgumentException("MARKET orders cannot carry a limitNetCents value");
        }
        if (type == Type.LIMIT && limitNetCents == null) {
            throw new IllegalArgumentException("LIMIT orders require a signed limitNetCents value");
        }
    }

    public static OrderInstruction market() {
        return new OrderInstruction(Type.MARKET, null, TimeInForce.DAY);
    }

    public static OrderInstruction limit(long signedNetCents) {
        return new OrderInstruction(Type.LIMIT, signedNetCents, TimeInForce.DAY);
    }

    /** Compatibility for the former nullable package-price field at API and internal boundaries. */
    public static OrderInstruction fromLegacy(Long proposedNetCents) {
        return proposedNetCents == null ? market() : limit(proposedNetCents);
    }

    /**
     * A larger signed net is always more favorable to the customer: more credit or less debit.
     * A limit is therefore marketable when it is less than or equal to the natural executable net.
     */
    public Executability executability(Long executableNetCents, boolean executableBook) {
        if (!executableBook || executableNetCents == null) return Executability.UNAVAILABLE;
        if (type == Type.MARKET) return Executability.IMMEDIATE;
        return limitNetCents <= executableNetCents ? Executability.IMMEDIATE : Executability.RESTING;
    }
}
