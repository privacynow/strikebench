package io.liftandshift.strikebench.sim;

/**
 * THE shared deterministic randomness foundation: counter-based gaussians that are a pure function
 * of (seed, stream, key, index). No hidden state, no consumption order — two callers can never
 * shift each other's draws, and any draw is reproducible in isolation. The live simulated market
 * ({@code SimulatedWorld}) and the research resampling machinery ({@code BootstrapSampler},
 * conditional-bootstrap path producers) all draw from here.
 *
 * <p>{@code PathGenerator}'s legacy stateful RNG remains for its existing modes because migrating
 * it CHANGES every generated path — that migration is a recorded, model-version-gated follow-up,
 * never a silent swap (validated outputs must not drift under a refactor).
 */
public final class RandomStreams {

    private RandomStreams() {}

    /** splitmix64 finalizer — the shared bit mixer. */
    public static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Standard normal draw, pure in (seed, stream, key, index) — Box–Muller on mixed uniforms. */
    public static double gaussian(long seed, long stream, long key, long index) {
        long h = seed;
        h = mix(h ^ stream); h = mix(h ^ key); h = mix(h ^ index);
        long h2 = mix(h ^ 0xD1B54A32D192ED03L);
        double u1 = (h >>> 11) * 0x1.0p-53 + 1e-12;
        double u2 = (h2 >>> 11) * 0x1.0p-53;
        return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
    }

    /** Uniform int in [0, bound), pure in (seed, stream, key, index). */
    public static int uniformInt(long seed, long stream, long key, long index, int bound) {
        long h = seed;
        h = mix(h ^ stream); h = mix(h ^ key); h = mix(h ^ index);
        return (int) Math.floorMod(h, Math.max(1, bound));
    }
}
