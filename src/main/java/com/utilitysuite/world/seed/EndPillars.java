package com.utilitysuite.world.seed;

import com.utilitysuite.world.oresim.WorldgenRng;

/**
 * Pure-Java mirror of vanilla 1.21.1 End Obsidian Pillars ({@code SpikeFeature.SpikeCacheLoader}).
 * <p>
 * The 10 pillar heights only depend on {@code pillarSeed = (int) (RandomSource.create(worldSeed).nextLong() & 65535L)}
 * (16 bits). Scanning all 65,536 pillar seeds uniquely recovers that 16-bit intermediate from observed pillar heights,
 * collapsing the 48-bit structure-seed search down to 32 bits.
 */
public final class EndPillars {

    public static final int COUNT = 10;
    private static final int[] PILLAR_X = new int[COUNT];
    private static final int[] PILLAR_Z = new int[COUNT];

    static {
        for (int i = 0; i < COUNT; i++) {
            double angle = 2.0 * (-Math.PI + (Math.PI / 10.0) * (double) i);
            PILLAR_X[i] = (int) Math.floor(42.0 * Math.cos(angle));
            PILLAR_Z[i] = (int) Math.floor(42.0 * Math.sin(angle));
        }
    }

    private EndPillars() {
    }

    public static int pillarX(int index) {
        return PILLAR_X[index];
    }

    public static int pillarZ(int index) {
        return PILLAR_Z[index];
    }

    /** Computes the 16-bit {@code pillarSeed} derived from a world or 48-bit structure seed. */
    public static int pillarSeedOf(long structureSeed) {
        return pillarSeedOf(structureSeed, WorldgenRng.legacy());
    }

    /** Allocation-free overload reusing the caller's {@link WorldgenRng#legacy()}. */
    public static int pillarSeedOf(long structureSeed, WorldgenRng rng) {
        rng.setSeed(structureSeed);
        return (int) (rng.nextLong() & 65535L);
    }

    /** Writes the 10 bedrock-cap Y levels ({@code 76..103}) for {@code pillarSeed} into {@code out10}. */
    public static void heightsForPillarSeed(int pillarSeed, int[] out10) {
        heightsForPillarSeed(pillarSeed, out10, WorldgenRng.legacy());
    }

    /** Allocation-free overload reusing the caller's {@link WorldgenRng#legacy()}. */
    public static void heightsForPillarSeed(int pillarSeed, int[] out10, WorldgenRng rng) {
        for (int i = 0; i < COUNT; i++) {
            out10[i] = i;
        }
        rng.setSeed(pillarSeed & 65535L);
        for (int j = COUNT; j > 1; j--) {
            int k = rng.nextInt(j);
            int tmp = out10[k];
            out10[k] = out10[j - 1];
            out10[j - 1] = tmp;
        }
        for (int i = 0; i < COUNT; i++) {
            out10[i] = 76 + out10[i] * 3;
        }
    }

    /**
     * Scans all 65,536 possible 16-bit pillar seeds against {@code observedHeights10} (entries {@code <= 0} mean
     * unobserved). Requires at least 7 valid distinct vanilla pillar heights ({@code 76 + 3*k}, {@code k in 0..9});
     * returns the unique matching pillar seed, or {@code -1} if 0 or more than 1 seed matches.
     */
    public static int recoverPillarSeed(int[] observedHeights10) {
        if (observedHeights10 == null || observedHeights10.length < COUNT) {
            return -1;
        }
        int seenMask = 0;
        int knownCount = 0;
        for (int i = 0; i < COUNT; i++) {
            int h = observedHeights10[i];
            if (h <= 0) {
                continue;
            }
            if (h < 76 || h > 103 || (h - 76) % 3 != 0) {
                return -1;
            }
            int rank = (h - 76) / 3;
            int bit = 1 << rank;
            if ((seenMask & bit) != 0) {
                return -1; // vanilla heights are a permutation of 76..103; duplicates mean corrupted measurement
            }
            seenMask |= bit;
            knownCount++;
        }
        if (knownCount < 7) {
            return -1;
        }
        WorldgenRng rng = WorldgenRng.legacy();
        int[] candidate = new int[COUNT];
        int found = -1;
        for (int ps = 0; ps < 65536; ps++) {
            heightsForPillarSeed(ps, candidate, rng);
            boolean ok = true;
            for (int i = 0; i < COUNT; i++) {
                if (observedHeights10[i] > 0 && candidate[i] != observedHeights10[i]) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                if (found >= 0) {
                    return -1; // ambiguous
                }
                found = ps;
            }
        }
        return found;
    }
}
