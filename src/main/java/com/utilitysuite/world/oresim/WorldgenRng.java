package com.utilitysuite.world.oresim;

/**
 * Allocation-free mirror of vanilla 1.21.1 {@code WorldgenRandom} wrapping either {@code XoroshiroRandomSource}
 * (feature decoration) or {@code LegacyRandomSource} (structure placement).
 * <p>
 * The important detail: {@code WorldgenRandom} extends {@code LegacyRandomSource} and therefore inherits the
 * {@code BitRandomSource} defaults, so {@code nextInt(bound)}, {@code nextLong()}, {@code nextFloat()} and
 * {@code nextDouble()} are all built from {@code next(bits)}, which for a Xoroshiro source is
 * {@code (int)(xoro.nextLong() >>> (64 - bits))}. Mirroring the Xoroshiro source's own {@code nextInt(bound)} would
 * produce different numbers.
 */
public final class WorldgenRng {

    private static final long GOLDEN_RATIO_64 = -7046029254386353131L;
    private static final long SILVER_RATIO_64 = 7640891576956012809L;
    private static final long LEGACY_MULTIPLIER = 25214903917L;
    private static final long LEGACY_MASK = (1L << 48) - 1;

    private final boolean xoroshiro;
    private long lo;
    private long hi;
    private long legacy;

    private WorldgenRng(boolean xoroshiro) {
        this.xoroshiro = xoroshiro;
    }

    /** The random used by {@code ChunkGenerator.applyBiomeDecoration}. */
    public static WorldgenRng decoration() {
        return new WorldgenRng(true);
    }

    /** The random used by {@code RandomSpreadStructurePlacement.getPotentialStructureChunk}. */
    public static WorldgenRng legacy() {
        return new WorldgenRng(false);
    }

    public void setSeed(long seed) {
        if (xoroshiro) {
            long l = seed ^ SILVER_RATIO_64;
            long h = l + GOLDEN_RATIO_64;
            lo = mixStafford13(l);
            hi = mixStafford13(h);
            if ((lo | hi) == 0L) {
                lo = GOLDEN_RATIO_64;
                hi = SILVER_RATIO_64;
            }
        } else {
            legacy = (seed ^ LEGACY_MULTIPLIER) & LEGACY_MASK;
        }
    }

    static long mixStafford13(long seed) {
        seed = (seed ^ seed >>> 30) * -4658895280553007687L;
        seed = (seed ^ seed >>> 27) * -7723592293110705685L;
        return seed ^ seed >>> 31;
    }

    private long xoroNextLong() {
        long l = lo;
        long h = hi;
        long result = Long.rotateLeft(l + h, 17) + l;
        h ^= l;
        lo = Long.rotateLeft(l, 49) ^ h ^ h << 21;
        hi = Long.rotateLeft(h, 28);
        return result;
    }

    public int next(int bits) {
        if (xoroshiro) {
            return (int) (xoroNextLong() >>> 64 - bits);
        }
        long next = legacy * LEGACY_MULTIPLIER + 11L & LEGACY_MASK;
        legacy = next;
        return (int) (next >> 48 - bits);
    }

    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("Bound must be positive");
        }
        if ((bound & bound - 1) == 0) {
            return (int) ((long) bound * (long) next(31) >> 31);
        }
        int bits;
        int val;
        do {
            bits = next(31);
            val = bits % bound;
        } while (bits - val + (bound - 1) < 0);
        return val;
    }

    public long nextLong() {
        int i = next(32);
        int j = next(32);
        return ((long) i << 32) + (long) j;
    }

    public float nextFloat() {
        return (float) next(24) * 5.9604645E-8F;
    }

    public double nextDouble() {
        int i = next(26);
        int j = next(27);
        long k = ((long) i << 27) + (long) j;
        return (float) k * 1.110223E-16F; // 26.3: long * float, so float precision (1.21.1 multiplied in double)
    }

    /** {@code Mth.randomBetweenInclusive}. */
    public int betweenInclusive(int min, int max) {
        return nextInt(max - min + 1) + min;
    }

    /** {@code WorldgenRandom.setDecorationSeed}; returns the decoration seed. */
    public long setDecorationSeed(long levelSeed, int minChunkBlockX, int minChunkBlockZ) {
        setSeed(levelSeed);
        long a = nextLong() | 1L;
        long b = nextLong() | 1L;
        long k = (long) minChunkBlockX * a + (long) minChunkBlockZ * b ^ levelSeed;
        setSeed(k);
        return k;
    }

    /** {@code WorldgenRandom.setFeatureSeed}. */
    public void setFeatureSeed(long decorationSeed, int index, int decorationStep) {
        setSeed(decorationSeed + (long) index + (long) (10000 * decorationStep));
    }

    /** {@code WorldgenRandom.setLargeFeatureWithSalt}. */
    public void setLargeFeatureWithSalt(long levelSeed, int regionX, int regionZ, int salt) {
        setSeed((long) regionX * 341873128712L + (long) regionZ * 132897987541L + levelSeed + (long) salt);
    }
}
