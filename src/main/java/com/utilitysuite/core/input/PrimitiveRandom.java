package com.utilitysuite.core.input;

import java.util.random.RandomGenerator;

/**
 * SplitMix64 generator held in one {@code long} field, plus a cached second Gaussian from the polar method. Nothing
 * allocates per call, so it is safe on per-tick and per-frame paths. Deterministic for a given seed (tests rely on
 * it). Not thread safe: client thread only, like everything in the input core.
 */
public final class PrimitiveRandom implements RandomGenerator {

    private long state;
    private boolean hasSpare;
    private double spare;

    public PrimitiveRandom(long seed) {
        this.state = seed;
    }

    /** A seed from the clock, for one generator per session. */
    public static long sessionSeed() {
        return System.nanoTime() ^ (System.currentTimeMillis() * 0x9E3779B97F4A7C15L);
    }

    @Override
    public long nextLong() {
        long z = (state += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    @Override
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    @Override
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive");
        }
        // 64-bit multiply-shift: bias is below 2^-32, irrelevant for timing jitter
        return (int) (((nextLong() >>> 32) * bound) >>> 32);
    }

    /** Standard normal (Marsaglia polar method); the second value of each pair is cached in a field. */
    @Override
    public double nextGaussian() {
        if (hasSpare) {
            hasSpare = false;
            return spare;
        }
        double u;
        double v;
        double s;
        do {
            u = nextDouble() * 2.0 - 1.0;
            v = nextDouble() * 2.0 - 1.0;
            s = u * u + v * v;
        } while (s >= 1.0 || s == 0.0);
        double m = Math.sqrt(-2.0 * Math.log(s) / s);
        spare = v * m;
        hasSpare = true;
        return u * m;
    }
}
