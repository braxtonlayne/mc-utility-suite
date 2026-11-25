package com.utilitysuite.world.seed;

import com.utilitysuite.world.BlockPosPacking;

import java.util.Arrays;

/**
 * Pure-Java deterministic terrain, carver, and aquifer occupancy estimator for Minecraft 1.21.1 worlds using
 * {@code Xoroshiro128++}, paired with morphological anomaly detection for artificial underground bases and strip-mine
 * tunnels.
 * <p>
 * Section bitmask layout ({@code long[64]}): 4096 blocks indexed by {@code (y << 8) | (z << 4) | x} where
 * {@code x, y, z in 0..15}. Bit {@code 1} = solid block, bit {@code 0} = air/non-solid.
 * Heightmap layout ({@code int[256]}): indexed by {@code (z << 4) | x}.
 */
public final class TerrainBaseline {

    public static final int FLAG_TUNNEL = 1;
    public static final int FLAG_CHAMBER = 1 << 1;
    public static final int FLAG_OCCUPANCY = 1 << 2;

    private static final long GOLDEN_RATIO_64 = -7046029254386353131L;
    private static final long SILVER_RATIO_64 = 7640891576956012809L;

    private static final int MAX_HINTS = 16;

    /** Immutable diff result for one 16x16x16 chunk section. */
    public record DiffResult(float score, int flags, long[] hints) {
        public static final DiffResult NONE = new DiffResult(0f, 0, new long[0]);

        public boolean hasTunnel() {
            return (flags & FLAG_TUNNEL) != 0;
        }

        public boolean hasChamber() {
            return (flags & FLAG_CHAMBER) != 0;
        }

        public boolean hasOccupancy() {
            return (flags & FLAG_OCCUPANCY) != 0;
        }
    }

    // Reusable scratch buffers (one TerrainBaseline per worker thread via ThreadLocal).
    private final long[] expectedMask = new long[64];
    private final int[] estimatedHeights = new int[256];
    private final long[] hintScratch = new long[MAX_HINTS];
    private final boolean[] flatFloor = new boolean[256];
    private final Xoroshiro128PlusPlus rng = new Xoroshiro128PlusPlus();

    public static int bitIndex(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    public static boolean isSolid(long[] mask64, int x, int y, int z) {
        int idx = bitIndex(x, y, z);
        return ((mask64[idx >>> 6] >>> (idx & 63)) & 1L) != 0L;
    }

    public static void setSolid(long[] mask64, int x, int y, int z, boolean solid) {
        int idx = bitIndex(x, y, z);
        int word = idx >>> 6;
        long bit = 1L << (idx & 63);
        if (solid) {
            mask64[word] |= bit;
        } else {
            mask64[word] &= ~bit;
        }
    }

    /**
     * Computes expected surface height {@code [minY, maxY]} bounds for chunk {@code (cx, cz)} into {@code outRange2}.
     */
    public void expectedSurfaceRange(long seed, int cx, int cz, int[] outRange2) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int baseX = cx << 4;
        int baseZ = cz << 4;
        for (int z = 0; z < 16; z += 4) {
            for (int x = 0; x < 16; x += 4) {
                int h = estimateSurfaceHeight(seed, baseX + x, baseZ + z);
                if (h < minY) {
                    minY = h;
                }
                if (h > maxY) {
                    maxY = h;
                }
            }
        }
        outRange2[0] = Math.max(-64, minY - 6);
        outRange2[1] = Math.min(319, maxY + 6);
    }

    /** Deterministic 1.21.1-style multi-octave surface height estimate for world column {@code (wx, wz)}. */
    public int estimateSurfaceHeight(long seed, int wx, int wz) {
        double continental = valueNoise2D(seed ^ 0x9E3779B97F4A7C15L, wx * 0.004, wz * 0.004);
        double erosion = valueNoise2D(seed ^ 0xBF58476D1CE4E5B9L, wx * 0.012, wz * 0.012);
        double detail = valueNoise2D(seed ^ 0x94D049BB133111EBL, wx * 0.045, wz * 0.045);
        double h = 64.0 + continental * 28.0 + erosion * 10.0 + detail * 4.0;
        return (int) Math.round(Math.max(-60.0, Math.min(256.0, h)));
    }

    /**
     * Returns true if {@code (wx, wy, wz)} falls inside a deterministic natural carver/cheese cave or fluid aquifer
     * envelope for {@code seed}.
     */
    public boolean isNaturalCaveOrAquifer(long seed, int wx, int wy, int wz) {
        // Deep lava / water aquifer tolerance.
        if (wy <= -52) {
            return true;
        }
        // Cheese cave 3D noise (low-frequency smooth pockets).
        double cheese = valueNoise3D(seed ^ 0x517CC1B727220A95L, wx * 0.055, wy * 0.075, wz * 0.055);
        if (cheese > 0.56) {
            return true;
        }
        // Spaghetti / noodle carver proxy: two orthogonal 3D noise fields near zero simultaneously.
        double s1 = valueNoise3D(seed ^ 0x2545F4914F6CDD1DL, wx * 0.08, wy * 0.09, wz * 0.08);
        double s2 = valueNoise3D(seed ^ 0x85EBCA6B1B873593L, wx * 0.08 + 31.7, wy * 0.09 - 17.3, wz * 0.08 + 11.1);
        return (s1 * s1 + s2 * s2) < 0.022;
    }

    /**
     * Populates {@code outSolidMask64} with the expected natural solid/air occupancy for section {@code (cx, sy, cz)}.
     */
    public void expectedSectionMask(long seed, int cx, int sy, int cz, int[] surfaceHeights256, long[] outSolidMask64) {
        Arrays.fill(outSolidMask64, 0L);
        int baseX = cx << 4;
        int baseY = sy << 4;
        int baseZ = cz << 4;
        int[] heights = surfaceHeights256 != null ? surfaceHeights256 : fillEstimatedHeights(seed, baseX, baseZ);

        // Quick section-level bounds check.
        int minSurf = Integer.MAX_VALUE;
        int maxSurf = Integer.MIN_VALUE;
        for (int i = 0; i < 256; i++) {
            int h = heights[i];
            if (h < minSurf) minSurf = h;
            if (h > maxSurf) maxSurf = h;
        }
        if (baseY > maxSurf) {
            return; // Entirely above surface -> all air.
        }

        for (int y = 0; y < 16; y++) {
            int wy = baseY + y;
            if (wy < -64) {
                continue;
            }
            for (int z = 0; z < 16; z++) {
                int wz = baseZ + z;
                int rowOff = z << 4;
                for (int x = 0; x < 16; x++) {
                    int surf = heights[rowOff | x];
                    if (wy > surf) {
                        continue;
                    }
                    // Bedrock floor is always solid.
                    if (wy <= -60) {
                        setSolid(outSolidMask64, x, y, z, true);
                        continue;
                    }
                    if (!isNaturalCaveOrAquifer(seed, baseX + x, wy, wz)) {
                        setSolid(outSolidMask64, x, y, z, true);
                    }
                }
            }
        }
    }

    /**
     * Compares an observed 16x16x16 section solid mask against the deterministic seed baseline and morphological
     * underground anomaly detectors (1x2 strip-mine tunnels, flat artificial chambers, and cleared rooms).
     */
    public DiffResult diffSection(long seed, int cx, int sy, int cz, long[] actualSolidMask64, int[] surfaceHeights256) {
        if (actualSolidMask64 == null || actualSolidMask64.length != 64) {
            return DiffResult.NONE;
        }
        int baseX = cx << 4;
        int baseY = sy << 4;
        int baseZ = cz << 4;
        int[] heights = surfaceHeights256 != null ? surfaceHeights256 : fillEstimatedHeights(seed, baseX, baseZ);

        // Skip sections that are entirely above the surface (sky/builds above ground are not underground tunnels/bases).
        int maxSurf = Integer.MIN_VALUE;
        for (int i = 0; i < 256; i++) {
            if (heights[i] > maxSurf) {
                maxSurf = heights[i];
            }
        }
        if (baseY > maxSurf) {
            return DiffResult.NONE;
        }

        expectedSectionMask(seed, cx, sy, cz, heights, expectedMask);

        int hintCount = 0;
        int flags = 0;

        // 1. Detect 1x2 strip-mine tunnels (underground).
        float tunnelScore = detectTunnels(actualSolidMask64, heights, baseX, baseY, baseZ, hintScratch, hintCount);
        if (tunnelScore >= 0.45f) {
            flags |= FLAG_TUNNEL;
            if (hintCount < MAX_HINTS) {
                hintCount++;
            }
        }

        // 2. Detect flat artificial rooms / cleared chambers.
        float chamberScore = detectChambers(actualSolidMask64, heights, baseX, baseY, baseZ, hintScratch, hintCount);
        if (chamberScore >= 0.45f) {
            flags |= FLAG_CHAMBER;
            if (hintCount < MAX_HINTS) {
                hintCount++;
            }
        }

        // 3. Seed-diff occupancy discrepancy (unexpected underground voids where baseline predicts solid rock).
        float occupancyScore = detectOccupancyAnomaly(seed, actualSolidMask64, expectedMask, heights, baseX, baseY, baseZ);
        if (occupancyScore >= 0.50f && (flags != 0 || occupancyScore >= 0.70f)) {
            flags |= FLAG_OCCUPANCY;
            if (hintCount == 0) {
                hintScratch[hintCount++] = BlockPosPacking.pack(baseX + 8, baseY + 8, baseZ + 8);
            }
        }

        float score = Math.max(tunnelScore, Math.max(chamberScore, occupancyScore));
        if (score < 0.15f) {
            return DiffResult.NONE;
        }
        long[] hints = Arrays.copyOf(hintScratch, hintCount);
        return new DiffResult(Math.min(1.0f, score), flags, hints);
    }

    private float detectTunnels(long[] mask, int[] heights, int baseX, int baseY, int baseZ, long[] hints, int hintIdx) {
        float bestScore = 0f;
        long bestHint = 0L;

        // Scan X-aligned 1x2 tunnels: for each (y, z), find contiguous air runs of height 2 with solid floor/ceiling/walls.
        for (int y = 1; y <= 13; y++) {
            int wy = baseY + y;
            for (int z = 1; z <= 14; z++) {
                int run = 0;
                int enclosed = 0;
                int runStart = 0;
                for (int x = 0; x < 16; x++) {
                    int surf = heights[(z << 4) | x];
                    boolean underground = wy + 2 < surf - 1;
                    boolean tunnelCell = underground
                            && !isSolid(mask, x, y, z)
                            && !isSolid(mask, x, y + 1, z)
                            && isSolid(mask, x, y - 1, z)
                            && isSolid(mask, x, y + 2, z);
                    if (tunnelCell) {
                        if (run == 0) {
                            runStart = x;
                        }
                        run++;
                        if (isSolid(mask, x, y, z - 1) && isSolid(mask, x, y, z + 1)
                                && isSolid(mask, x, y + 1, z - 1) && isSolid(mask, x, y + 1, z + 1)) {
                            enclosed++;
                        }
                    } else {
                        if (run >= 6 && enclosed >= (run * 3) / 4) {
                            float s = Math.min(1.0f, 0.55f + (run - 6) * 0.05f + (enclosed / (float) run) * 0.25f);
                            if (s > bestScore) {
                                bestScore = s;
                                bestHint = BlockPosPacking.pack(baseX + runStart + run / 2, wy, baseZ + z);
                            }
                        }
                        run = 0;
                        enclosed = 0;
                    }
                }
                if (run >= 6 && enclosed >= (run * 3) / 4) {
                    float s = Math.min(1.0f, 0.55f + (run - 6) * 0.05f + (enclosed / (float) run) * 0.25f);
                    if (s > bestScore) {
                        bestScore = s;
                        bestHint = BlockPosPacking.pack(baseX + runStart + run / 2, wy, baseZ + z);
                    }
                }
            }
        }

        // Scan Z-aligned 1x2 tunnels: for each (y, x).
        for (int y = 1; y <= 13; y++) {
            int wy = baseY + y;
            for (int x = 1; x <= 14; x++) {
                int run = 0;
                int enclosed = 0;
                int runStart = 0;
                for (int z = 0; z < 16; z++) {
                    int surf = heights[(z << 4) | x];
                    boolean underground = wy + 2 < surf - 1;
                    boolean tunnelCell = underground
                            && !isSolid(mask, x, y, z)
                            && !isSolid(mask, x, y + 1, z)
                            && isSolid(mask, x, y - 1, z)
                            && isSolid(mask, x, y + 2, z);
                    if (tunnelCell) {
                        if (run == 0) {
                            runStart = z;
                        }
                        run++;
                        if (isSolid(mask, x - 1, y, z) && isSolid(mask, x + 1, y, z)
                                && isSolid(mask, x - 1, y + 1, z) && isSolid(mask, x + 1, y + 1, z)) {
                            enclosed++;
                        }
                    } else {
                        if (run >= 6 && enclosed >= (run * 3) / 4) {
                            float s = Math.min(1.0f, 0.55f + (run - 6) * 0.05f + (enclosed / (float) run) * 0.25f);
                            if (s > bestScore) {
                                bestScore = s;
                                bestHint = BlockPosPacking.pack(baseX + x, wy, baseZ + runStart + run / 2);
                            }
                        }
                        run = 0;
                        enclosed = 0;
                    }
                }
                if (run >= 6 && enclosed >= (run * 3) / 4) {
                    float s = Math.min(1.0f, 0.55f + (run - 6) * 0.05f + (enclosed / (float) run) * 0.25f);
                    if (s > bestScore) {
                        bestScore = s;
                        bestHint = BlockPosPacking.pack(baseX + x, wy, baseZ + runStart + run / 2);
                    }
                }
            }
        }

        if (bestScore >= 0.45f && hintIdx < MAX_HINTS) {
            hints[hintIdx] = bestHint;
        }
        return bestScore;
    }

    private float detectChambers(long[] mask, int[] heights, int baseX, int baseY, int baseZ, long[] hints, int hintIdx) {
        float bestScore = 0f;
        long bestHint = 0L;

        for (int y = 1; y <= 12; y++) {
            int wy = baseY + y;
            int flatCount = 0;
            int sumX = 0;
            int sumZ = 0;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int idx = (z << 4) | x;
                    boolean isFlatFloor = (wy + 2 < heights[idx] - 1)
                            && isSolid(mask, x, y - 1, z)
                            && !isSolid(mask, x, y, z)
                            && !isSolid(mask, x, y + 1, z);
                    flatFloor[idx] = isFlatFloor;
                    if (isFlatFloor) {
                        flatCount++;
                        sumX += x;
                        sumZ += z;
                    }
                }
            }
            if (flatCount < 20) {
                continue;
            }

            // Measure bounding box & rectangularity of the flat floor region + flat ceiling.
            int minX = 16, maxX = -1, minZ = 16, maxZ = -1;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (flatFloor[(z << 4) | x]) {
                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (z < minZ) minZ = z;
                        if (z > maxZ) maxZ = z;
                    }
                }
            }
            int w = maxX - minX + 1;
            int d = maxZ - minZ + 1;
            if (w < 4 || d < 4) {
                continue;
            }
            int rectArea = w * d;
            float fillRatio = flatCount / (float) rectArea;
            if (fillRatio < 0.72f) {
                continue;
            }

            // Check for a flat ceiling at h = 2..5 blocks above the floor.
            float bestCeilRatio = 0f;
            for (int h = 2; h <= 5 && y + h < 16; h++) {
                int ceilSolid = 0;
                for (int z = minZ; z <= maxZ; z++) {
                    for (int x = minX; x <= maxX; x++) {
                        if (flatFloor[(z << 4) | x] && isSolid(mask, x, y + h, z)) {
                            ceilSolid++;
                        }
                    }
                }
                float r = ceilSolid / (float) flatCount;
                if (r > bestCeilRatio) {
                    bestCeilRatio = r;
                }
            }
            if (bestCeilRatio >= 0.75f) {
                float sizeFactor = Math.min(1.0f, (flatCount - 16) / 33.0f);
                float score = 0.55f + 0.25f * fillRatio + 0.20f * sizeFactor;
                if (score > bestScore) {
                    bestScore = score;
                    bestHint = BlockPosPacking.pack(baseX + sumX / flatCount, wy, baseZ + sumZ / flatCount);
                }
            }
        }

        if (bestScore >= 0.45f && hintIdx < MAX_HINTS) {
            hints[hintIdx] = bestHint;
        }
        return bestScore;
    }

    private float detectOccupancyAnomaly(long seed, long[] actual, long[] expected, int[] heights,
                                         int baseX, int baseY, int baseZ) {
        int unexpectedVoids = 0;
        int undergroundTotal = 0;
        for (int y = 0; y < 16; y++) {
            int wy = baseY + y;
            if (wy <= -56) {
                continue;
            }
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int surf = heights[(z << 4) | x];
                    // Ignore near-surface transition band (trees, grass, dirt variance).
                    if (wy >= surf - 3) {
                        continue;
                    }
                    undergroundTotal++;
                    if (isSolid(expected, x, y, z) && !isSolid(actual, x, y, z)) {
                        if (!isNaturalCaveOrAquifer(seed, baseX + x, wy, baseZ + z)) {
                            unexpectedVoids++;
                        }
                    }
                }
            }
        }
        if (undergroundTotal < 64 || unexpectedVoids < 32) {
            return 0f;
        }
        float ratio = unexpectedVoids / (float) undergroundTotal;
        // Large unexpected cleared volume in solid rock.
        return Math.min(0.85f, ratio * 2.2f);
    }

    private int[] fillEstimatedHeights(long seed, int baseX, int baseZ) {
        for (int z = 0; z < 16; z++) {
            int row = z << 4;
            for (int x = 0; x < 16; x++) {
                estimatedHeights[row | x] = estimateSurfaceHeight(seed, baseX + x, baseZ + z);
            }
        }
        return estimatedHeights;
    }

    private double valueNoise2D(long seed, double x, double z) {
        int xi = (int) Math.floor(x);
        int zi = (int) Math.floor(z);
        double xf = x - xi;
        double zf = z - zi;
        double u = xf * xf * (3.0 - 2.0 * xf);
        double v = zf * zf * (3.0 - 2.0 * zf);

        double n00 = hashToUnit(seed, xi, 0, zi);
        double n10 = hashToUnit(seed, xi + 1, 0, zi);
        double n01 = hashToUnit(seed, xi, 0, zi + 1);
        double n11 = hashToUnit(seed, xi + 1, 0, zi + 1);

        double nx0 = n00 + u * (n10 - n00);
        double nx1 = n01 + u * (n11 - n01);
        return nx0 + v * (nx1 - nx0);
    }

    private double valueNoise3D(long seed, double x, double y, double z) {
        int xi = (int) Math.floor(x);
        int yi = (int) Math.floor(y);
        int zi = (int) Math.floor(z);
        double xf = x - xi;
        double yf = y - yi;
        double zf = z - zi;
        double u = xf * xf * (3.0 - 2.0 * xf);
        double v = yf * yf * (3.0 - 2.0 * yf);
        double w = zf * zf * (3.0 - 2.0 * zf);

        double n000 = hashToUnit(seed, xi, yi, zi);
        double n100 = hashToUnit(seed, xi + 1, yi, zi);
        double n010 = hashToUnit(seed, xi, yi + 1, zi);
        double n110 = hashToUnit(seed, xi + 1, yi + 1, zi);
        double n001 = hashToUnit(seed, xi, yi, zi + 1);
        double n101 = hashToUnit(seed, xi + 1, yi, zi + 1);
        double n011 = hashToUnit(seed, xi, yi + 1, zi + 1);
        double n111 = hashToUnit(seed, xi + 1, yi + 1, zi + 1);

        double nx00 = n000 + u * (n100 - n000);
        double nx10 = n010 + u * (n110 - n010);
        double nx01 = n001 + u * (n101 - n001);
        double nx11 = n011 + u * (n111 - n011);

        double nxy0 = nx00 + v * (nx10 - nx00);
        double nxy1 = nx01 + v * (nx11 - nx01);
        return nxy0 + w * (nxy1 - nxy0);
    }

    private double hashToUnit(long seed, int x, int y, int z) {
        long h = seed ^ (x * 341873128712L) ^ (y * 132897987541L) ^ (z * 2971215073L);
        rng.setSeed(h);
        return (rng.nextLong() >>> 11) * 1.1102230246251565E-16 * 2.0 - 1.0;
    }

    /** Allocation-free 1.21.1 {@code Xoroshiro128++} PRNG. */
    public static final class Xoroshiro128PlusPlus {
        private long lo;
        private long hi;

        public void setSeed(long seed) {
            long l = seed ^ SILVER_RATIO_64;
            long h = l + GOLDEN_RATIO_64;
            lo = mixStafford13(l);
            hi = mixStafford13(h);
            if ((lo | hi) == 0L) {
                lo = GOLDEN_RATIO_64;
                hi = SILVER_RATIO_64;
            }
        }

        public long nextLong() {
            long l = lo;
            long h = hi;
            long result = Long.rotateLeft(l + h, 17) + l;
            h ^= l;
            lo = Long.rotateLeft(l, 49) ^ h ^ (h << 21);
            hi = Long.rotateLeft(h, 28);
            return result;
        }

        public float nextFloat() {
            return (float) (nextLong() >>> 40) * 5.9604645E-8F;
        }

        private static long mixStafford13(long z) {
            z = (z ^ (z >>> 30)) * -4658895280553007687L;
            z = (z ^ (z >>> 27)) * -7723592293110705685L;
            return z ^ (z >>> 31);
        }
    }
}
