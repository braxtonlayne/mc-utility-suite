package com.utilitysuite.world.seed;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;

/**
 * Pure-Java 1.21.1 {@code Xoroshiro128++} positional random verifier for vanilla {@code vertical_gradient}
 * bedrock and deepslate rules. Allows 64-bit world-seed verification and lifting even when
 * {@code BiomeManager.biomeZoomSeed} is absent or spoofed.
 */
public final class BedrockPatternVerifier {

    public enum Layer {
        /** Overworld floor bedrock at y = -63..-60 ({@code minecraft:bedrock_floor}, -64 to -59). */
        OVERWORLD_FLOOR,
        /** Overworld deepslate/stone transition at y = 1..7 ({@code minecraft:deepslate}, 0 to 8). */
        OVERWORLD_DEEPSLATE,
        /** Nether floor bedrock at y = 1..4 ({@code minecraft:bedrock_floor}, 0 to 5). */
        NETHER_FLOOR,
        /** Nether roof bedrock at y = 123..126 ({@code minecraft:bedrock_roof}, 122 to 127, inverted). */
        NETHER_ROOF
    }

    public record Sample(Layer layer, int x, int y, int z, boolean present) {
    }

    private static final long GOLDEN_RATIO_64 = -7046029254386353131L;
    private static final long SILVER_RATIO_64 = 7640891576956012809L;

    private static final long FLOOR_HASH_LO;
    private static final long FLOOR_HASH_HI;
    private static final long ROOF_HASH_LO;
    private static final long ROOF_HASH_HI;
    private static final long DEEPSLATE_HASH_LO;
    private static final long DEEPSLATE_HASH_HI;

    static {
        long[] floor = md5Pair("minecraft:bedrock_floor");
        FLOOR_HASH_LO = floor[0];
        FLOOR_HASH_HI = floor[1];

        long[] roof = md5Pair("minecraft:bedrock_roof");
        ROOF_HASH_LO = roof[0];
        ROOF_HASH_HI = roof[1];

        long[] deepslate = md5Pair("minecraft:deepslate");
        DEEPSLATE_HASH_LO = deepslate[0];
        DEEPSLATE_HASH_HI = deepslate[1];
    }

    private BedrockPatternVerifier() {
    }

    private static long[] md5Pair(String name) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(name.getBytes(StandardCharsets.UTF_8));
            return new long[]{longFromBytes(d, 0), longFromBytes(d, 8)};
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    private static long longFromBytes(byte[] b, int o) {
        return ((long) b[o] & 0xFFL) << 56
                | ((long) b[o + 1] & 0xFFL) << 48
                | ((long) b[o + 2] & 0xFFL) << 40
                | ((long) b[o + 3] & 0xFFL) << 32
                | ((long) b[o + 4] & 0xFFL) << 24
                | ((long) b[o + 5] & 0xFFL) << 16
                | ((long) b[o + 6] & 0xFFL) << 8
                | ((long) b[o + 7] & 0xFFL);
    }

    static long mixStafford13(long seed) {
        seed = (seed ^ (seed >>> 30)) * -4658895280553007687L;
        seed = (seed ^ (seed >>> 27)) * -7723592293110705685L;
        return seed ^ (seed >>> 31);
    }

    static long blockSeed(int x, int y, int z) {
        long i = (long) (x * 3129871) ^ (long) z * 116129781L ^ (long) y;
        i = i * i * 42317861L + i * 11L;
        return i >> 16;
    }

    private static float positionalFloat(long factoryLo, long factoryHi, int x, int y, int z) {
        long lo = factoryLo ^ blockSeed(x, y, z);
        long hi = factoryHi;
        if ((lo | hi) == 0L) {
            lo = GOLDEN_RATIO_64;
            hi = SILVER_RATIO_64;
        }
        long bits24 = (Long.rotateLeft(lo + hi, 17) + lo) >>> 40;
        return (float) bits24 * 5.9604645E-8F;
    }

    /** Probability of bedrock/deepslate being placed at {@code y} for {@code layer}. */
    public static double probability(Layer layer, int y) {
        return switch (layer) {
            case OVERWORLD_FLOOR -> clamp01((-59.0 - y) / 5.0);
            case OVERWORLD_DEEPSLATE -> clamp01((8.0 - y) / 8.0);
            case NETHER_FLOOR -> clamp01((5.0 - y) / 5.0);
            case NETHER_ROOF -> 1.0 - clamp01((127.0 - y) / 5.0);
        };
    }

    private static double clamp01(double v) {
        return v <= 0.0 ? 0.0 : (v >= 1.0 ? 1.0 : v);
    }

    /** Predicts whether {@code layer} places bedrock (or deepslate) at {@code (x, y, z)} for {@code worldSeed}. */
    public static boolean predict(long worldSeed, Layer layer, int x, int y, int z) {
        long l0 = mixStafford13(worldSeed ^ SILVER_RATIO_64);
        long h0 = mixStafford13((worldSeed ^ SILVER_RATIO_64) + GOLDEN_RATIO_64);
        if ((l0 | h0) == 0L) {
            l0 = GOLDEN_RATIO_64;
            h0 = SILVER_RATIO_64;
        }
        // WorldRandom.forkPositional()
        long baseLo = Long.rotateLeft(l0 + h0, 17) + l0;
        long t = h0 ^ l0;
        l0 = Long.rotateLeft(l0, 49) ^ t ^ (t << 21);
        h0 = Long.rotateLeft(t, 28);
        long baseHi = Long.rotateLeft(l0 + h0, 17) + l0;

        return predictFromBase(baseLo, baseHi, layer, x, y, z);
    }

    private static boolean predictFromBase(long baseLo, long baseHi, Layer layer, int x, int y, int z) {
        long hashLo;
        long hashHi;
        switch (layer) {
            case OVERWORLD_FLOOR, NETHER_FLOOR -> {
                hashLo = FLOOR_HASH_LO;
                hashHi = FLOOR_HASH_HI;
            }
            case NETHER_ROOF -> {
                hashLo = ROOF_HASH_LO;
                hashHi = ROOF_HASH_HI;
            }
            case OVERWORLD_DEEPSLATE -> {
                hashLo = DEEPSLATE_HASH_LO;
                hashHi = DEEPSLATE_HASH_HI;
            }
            default -> throw new IllegalStateException("Unexpected layer: " + layer);
        }

        // fromHashOf(name).forkPositional()
        long nl = baseLo ^ hashLo;
        long nh = baseHi ^ hashHi;
        if ((nl | nh) == 0L) {
            nl = GOLDEN_RATIO_64;
            nh = SILVER_RATIO_64;
        }
        long facLo = Long.rotateLeft(nl + nh, 17) + nl;
        long nt = nh ^ nl;
        nl = Long.rotateLeft(nl, 49) ^ nt ^ (nt << 21);
        nh = Long.rotateLeft(nt, 28);
        long facHi = Long.rotateLeft(nl + nh, 17) + nl;

        float f = positionalFloat(facLo, facHi, x, y, z);
        return switch (layer) {
            case OVERWORLD_FLOOR -> (double) f < clamp01((-59.0 - y) / 5.0);
            case OVERWORLD_DEEPSLATE -> (double) f < clamp01((8.0 - y) / 8.0);
            case NETHER_FLOOR -> (double) f < clamp01((5.0 - y) / 5.0);
            case NETHER_ROOF -> !((double) f < clamp01((127.0 - y) / 5.0));
        };
    }

    public static boolean matches(long worldSeed, Sample sample) {
        return predict(worldSeed, sample.layer(), sample.x(), sample.y(), sample.z()) == sample.present();
    }

    /** Zero-allocation verification of all {@code samples} against {@code worldSeed}. */
    public static boolean verifyWorldSeed(long worldSeed, List<Sample> samples) {
        if (samples == null || samples.isEmpty()) {
            return false;
        }
        long l0 = mixStafford13(worldSeed ^ SILVER_RATIO_64);
        long h0 = mixStafford13((worldSeed ^ SILVER_RATIO_64) + GOLDEN_RATIO_64);
        if ((l0 | h0) == 0L) {
            l0 = GOLDEN_RATIO_64;
            h0 = SILVER_RATIO_64;
        }
        long baseLo = Long.rotateLeft(l0 + h0, 17) + l0;
        long t = h0 ^ l0;
        l0 = Long.rotateLeft(l0, 49) ^ t ^ (t << 21);
        h0 = Long.rotateLeft(t, 28);
        long baseHi = Long.rotateLeft(l0 + h0, 17) + l0;

        for (int i = 0, n = samples.size(); i < n; i++) {
            Sample s = samples.get(i);
            if (predictFromBase(baseLo, baseHi, s.layer(), s.x(), s.y(), s.z()) != s.present()) {
                return false;
            }
        }
        return true;
    }

    /** Primitive-array zero-allocation overload for tight loops. */
    public static boolean verifyWorldSeed(long worldSeed, Layer[] layers, int[] x, int[] y, int[] z,
                                          boolean[] isBedrock, int count) {
        if (count <= 0) {
            return false;
        }
        long l0 = mixStafford13(worldSeed ^ SILVER_RATIO_64);
        long h0 = mixStafford13((worldSeed ^ SILVER_RATIO_64) + GOLDEN_RATIO_64);
        if ((l0 | h0) == 0L) {
            l0 = GOLDEN_RATIO_64;
            h0 = SILVER_RATIO_64;
        }
        long baseLo = Long.rotateLeft(l0 + h0, 17) + l0;
        long t = h0 ^ l0;
        l0 = Long.rotateLeft(l0, 49) ^ t ^ (t << 21);
        h0 = Long.rotateLeft(t, 28);
        long baseHi = Long.rotateLeft(l0 + h0, 17) + l0;

        for (int i = 0; i < count; i++) {
            if (predictFromBase(baseLo, baseHi, layers[i], x[i], y[i], z[i]) != isBedrock[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Lifts a 48-bit structure seed to the unique 64-bit world seed matching {@code samples}, scanning all 65,536
     * upper 16-bit prefixes. Returns empty if none (or more than one) matches, or if cancelled.
     */
    public static OptionalLong liftToWorldSeed(long structureSeed48, List<Sample> samples, BooleanSupplier cancelled) {
        if (samples == null || samples.isEmpty()) {
            return OptionalLong.empty();
        }
        int n = samples.size();
        Layer[] layers = new Layer[n];
        int[] xs = new int[n];
        int[] ys = new int[n];
        int[] zs = new int[n];
        boolean[] present = new boolean[n];
        for (int i = 0; i < n; i++) {
            Sample s = samples.get(i);
            layers[i] = s.layer();
            xs[i] = s.x();
            ys[i] = s.y();
            zs[i] = s.z();
            present[i] = s.present();
        }

        long base = structureSeed48 & ((1L << 48) - 1);
        long match = 0L;
        int hits = 0;
        for (int hi = 0; hi < 65536; hi++) {
            if ((hi & 4095) == 0 && cancelled != null && cancelled.getAsBoolean()) {
                return OptionalLong.empty();
            }
            long candidate = ((long) hi << 48) | base;
            if (verifyWorldSeed(candidate, layers, xs, ys, zs, present, n)) {
                match = candidate;
                hits++;
                if (hits > 1) {
                    return OptionalLong.empty();
                }
            }
        }
        return hits == 1 ? OptionalLong.of(match) : OptionalLong.empty();
    }
}
