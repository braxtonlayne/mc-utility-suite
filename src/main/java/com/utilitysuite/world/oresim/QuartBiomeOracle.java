package com.utilitysuite.world.oresim;

/**
 * Exact {@code BiomeFilter} answers for one chunk from a snapshot of its noise biomes (quart resolution) plus a one-quart
 * border, using vanilla's {@code BiomeManager.getBiome} zoom ("fiddled distance" with the obfuscated seed). Immutable;
 * built on the client thread, read on the simulation thread.
 */
public final class QuartBiomeOracle implements BiomeOracle {

    private final long zoomSeed;
    private final int originQx;
    private final int originQz;
    private final int minQy;
    private final int quartsY;
    /** [(qx - originQx) * 6 + (qz - originQz)] * quartsY + (qy - minQy); null = unknown. */
    private final String[] grid;

    /**
     * @param zoomSeed {@code BiomeManager.obfuscateSeed(worldSeed)}
     * @param minQy    lowest quart Y of the dimension
     * @param quartsY  number of quart layers
     * @param grid     6 x 6 x quartsY biome ids starting at quart (chunkX*4 - 1, minQy, chunkZ*4 - 1)
     */
    public QuartBiomeOracle(long zoomSeed, int chunkX, int chunkZ, int minQy, int quartsY, String[] grid) {
        if (grid.length != 36 * quartsY) {
            throw new IllegalArgumentException("grid must be 6x6x" + quartsY);
        }
        this.zoomSeed = zoomSeed;
        this.originQx = chunkX * 4 - 1;
        this.originQz = chunkZ * 4 - 1;
        this.minQy = minQy;
        this.quartsY = quartsY;
        this.grid = grid;
    }

    public static int gridIndex(int dqx, int dqz, int dqy, int quartsY) {
        return (dqx * 6 + dqz) * quartsY + dqy;
    }

    @Override
    public byte allows(OreSpec spec, int x, int y, int z) {
        if (spec.biomesAllowed() == null && spec.biomesDenied().isEmpty()) {
            return YES;
        }
        String id = biomeAt(x, y, z);
        if (id == null) {
            return UNKNOWN;
        }
        return spec.biomeAllows(id) ? YES : NO;
    }

    /** The biome id vanilla's {@code LevelReader.getBiome(pos)} would return, or null when outside the snapshot. */
    public String biomeAt(int x, int y, int z) {
        long q = selectQuart(zoomSeed, x, y, z);
        int qx = (int) (q >> 42) - originQx;
        int qy = clamp((int) ((q << 22) >> 43), minQy, minQy + quartsY - 1) - minQy;
        int qz = (int) ((q << 43) >> 43) - originQz;
        if (qx < 0 || qx >= 6 || qz < 0 || qz >= 6) {
            return null;
        }
        return grid[gridIndex(qx, qz, qy, quartsY)];
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    /**
     * {@code BiomeManager.getBiome} without the lookup: the selected quart packed as 22-bit signed x (bits 42..63),
     * 21-bit signed y (bits 21..41), 21-bit signed z (bits 0..20).
     */
    public static long selectQuart(long zoomSeed, int x, int y, int z) {
        int i = x - 2;
        int j = y - 2;
        int k = z - 2;
        int l = i >> 2;
        int i1 = j >> 2;
        int j1 = k >> 2;
        double d0 = (double) (i & 3) / 4.0;
        double d1 = (double) (j & 3) / 4.0;
        double d2 = (double) (k & 3) / 4.0;
        int best = 0;
        double bestDist = Double.POSITIVE_INFINITY;
        for (int c = 0; c < 8; c++) {
            boolean fx = (c & 4) == 0;
            boolean fy = (c & 2) == 0;
            boolean fz = (c & 1) == 0;
            int qx = fx ? l : l + 1;
            int qy = fy ? i1 : i1 + 1;
            int qz = fz ? j1 : j1 + 1;
            double nx = fx ? d0 : d0 - 1.0;
            double ny = fy ? d1 : d1 - 1.0;
            double nz = fz ? d2 : d2 - 1.0;
            double dist = fiddledDistance(zoomSeed, qx, qy, qz, nx, ny, nz);
            if (bestDist > dist) {
                best = c;
                bestDist = dist;
            }
        }
        int sx = (best & 4) == 0 ? l : l + 1;
        int sy = (best & 2) == 0 ? i1 : i1 + 1;
        int sz = (best & 1) == 0 ? j1 : j1 + 1;
        return ((long) sx << 42) | (((long) sy & 0x1FFFFFL) << 21) | ((long) sz & 0x1FFFFFL);
    }

    private static long lcg(long left, long right) {
        left *= left * 6364136223846793005L + 1442695040888963407L;
        return left + right;
    }

    private static double fiddledDistance(long seed, int x, int y, int z, double xn, double yn, double zn) {
        long s = lcg(seed, x);
        s = lcg(s, y);
        s = lcg(s, z);
        s = lcg(s, x);
        s = lcg(s, y);
        s = lcg(s, z);
        double d0 = fiddle(s);
        s = lcg(s, seed);
        double d1 = fiddle(s);
        s = lcg(s, seed);
        double d2 = fiddle(s);
        return square(zn + d2) + square(yn + d1) + square(xn + d0);
    }

    private static double square(double v) {
        return v * v;
    }

    private static double fiddle(long seed) {
        double d0 = (double) Math.floorMod(seed >> 24, 1024) / 1024.0;
        return (d0 - 0.5) * 0.9;
    }
}
