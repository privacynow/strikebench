package io.liftandshift.strikebench.model;

import java.util.Arrays;

/**
 * The eight named market stories shared by risk checkpoints and stored-ensemble conditioning.
 *
 * <p>This is the sole numeric policy for a story. Evaluation prices its terminal move; scenario
 * conditioning uses its default volatility shift, elapsed sessions and trajectory. Browsers
 * receive the story identity and resolved values—they never maintain another numeric catalog.</p>
 */
public enum ScenarioStory {
    MARKET_CRASH(-20, 14, 5),
    GAP_DOWN(-9, 8, 1),
    ORDERLY_PULLBACK(-6, 4, 3),
    CHOPPY_SIDEWAYS(-1, 1, 5),
    FLAT_RANGE(0, 0, 7),
    GRIND_HIGHER(6, -2, 10),
    STRONG_RALLY(13, -4, 10),
    MELT_UP(20, -6, 12);

    private final double movePct;
    private final double ivShiftPoints;
    private final int elapsedSessions;

    ScenarioStory(double movePct, double ivShiftPoints, int elapsedSessions) {
        this.movePct = movePct;
        this.ivShiftPoints = ivShiftPoints;
        this.elapsedSessions = elapsedSessions;
    }

    public double movePct() {
        return movePct;
    }

    public double underlyingMoveFraction() {
        return movePct / 100.0;
    }

    public double ivShiftPoints() {
        return ivShiftPoints;
    }

    public int elapsedSessions() {
        return elapsedSessions;
    }

    /** The same named trajectory scaled to an explicit user terminal-move override. */
    public double[] waypointMoveFractions(double terminalMoveFraction) {
        double move = terminalMoveFraction;
        return switch (this) {
            case MARKET_CRASH -> new double[] {-.03, -.10, move};
            case GAP_DOWN -> new double[] {move, move * .92, move};
            case ORDERLY_PULLBACK -> new double[] {move * .25, move * .58, move};
            case CHOPPY_SIDEWAYS -> new double[] {-.025, .015, move};
            case FLAT_RANGE -> new double[] {.01, -.008, move};
            case GRIND_HIGHER -> new double[] {move * .22, move * .58, move};
            case STRONG_RALLY -> new double[] {move * .15, move * .55, move};
            case MELT_UP -> new double[] {move * .08, move * .38, move};
        };
    }

    public static ScenarioStory atMoveFraction(double moveFraction) {
        return Arrays.stream(values())
                .filter(story -> Math.abs(story.underlyingMoveFraction() - moveFraction) < 1e-9)
                .findFirst().orElse(null);
    }
}
