package io.liftandshift.util;

import java.security.SecureRandom;

/** Short, prefixed, URL-safe random identifiers. */
public final class Ids {

    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz".toCharArray();

    private Ids() {}

    public static String newId(String prefix) {
        StringBuilder sb = new StringBuilder(prefix.length() + 17);
        sb.append(prefix).append('_');
        for (int i = 0; i < 16; i++) sb.append(ALPHABET[RNG.nextInt(ALPHABET.length)]);
        return sb.toString();
    }

    public static String account() { return newId("acct"); }
    public static String trade() { return newId("tr"); }
    public static String order() { return newId("ord"); }
    public static String backtest() { return newId("bt"); }
}
