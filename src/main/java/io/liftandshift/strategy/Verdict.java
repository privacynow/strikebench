package io.liftandshift.strategy;

import java.util.List;

/** Result of a guardrail check. BLOCK reasons prevent the trade; warnings ride along. */
public record Verdict(Level level, List<String> blockReasons, List<String> warnings) {

    public enum Level { ALLOW, WARN, BLOCK }

    public static Verdict of(List<String> blockReasons, List<String> warnings) {
        Level level = !blockReasons.isEmpty() ? Level.BLOCK : !warnings.isEmpty() ? Level.WARN : Level.ALLOW;
        return new Verdict(level, List.copyOf(blockReasons), List.copyOf(warnings));
    }

    public boolean blocked() { return level == Level.BLOCK; }
}
