package com.utilitysuite.jobs.planner;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Picks a strip mine that "happens" to run past ore: a straight 1x2 tunnel a player could have chosen (start under
 * their feet, one of the four directions, a level in the ore's band), scored by how much known ore its walls, floor
 * and ceiling would expose, counting whole veins (mining an exposed ore exposes its neighbours). The knowledge only
 * picks the route; the job still mines just what the tunnel uncovers. Engine free.
 * <p>
 * Not for per-tick use: one call scans every level of the band in four directions (a few hundred thousand set
 * lookups at most), done once per route.
 */
public final class StripRoutePlanner {

    /** Direction offsets: +x, -x, +z, -z. */
    static final int[] DX = {1, -1, 0, 0};
    static final int[] DZ = {0, 0, 1, -1};
    /** Score lost per block of descent (a shorter dig down wins a tie). */
    static final double DESCENT_COST = 0.1;

    /**
     * A tunnel from (x, y, z) heading (dx, dz) for {@code length} blocks, its feet at y. {@code ores}: ore blocks the
     * tunnel and the veins it uncovers would yield; {@code descent}: blocks to dig down (or up) to reach y.
     */
    public record Route(int x, int y, int z, int dx, int dz, int length, int ores, int descent) {

        /** Packed feet cell of tunnel step {@code t} (0 = start). */
        public long cell(int t) {
            return PackedPos.pack(x + dx * t, y, z + dz * t);
        }
    }

    /** Score lost per known lava cell within {@link #HAZARD_RADIUS} of a tunnel cell (lava weighs above water). */
    static final double LAVA_COST = 3.0;
    /** Score lost per known water cell near the tunnel (floods the tunnel, washes torches out). */
    static final double WATER_COST = 0.75;
    /** Chebyshev radius around each tunnel cell in which a fluid counts as a hazard. */
    static final int HAZARD_RADIUS = 2;
    /** Torch light at the torch itself; block light falls by one per block of walking distance. */
    static final int TORCH_LIGHT = 14;
    /** Default torch spacing: keeps block light at 8 or more between torches (a player's every-12-blocks habit). */
    public static final int DEFAULT_TORCH_SPACING = torchSpacingFor(8);

    private final LongOpenHashSet ores = new LongOpenHashSet();
    private final LongOpenHashSet lava = new LongOpenHashSet();
    private final LongOpenHashSet water = new LongOpenHashSet();
    private final LongOpenHashSet counted = new LongOpenHashSet();
    private final LongArrayList queue = new LongArrayList();

    /**
     * @param orePositions packed ore positions (observed or predicted), already filtered to minable ones
     * @param minY         lowest level of the ore's good band (inclusive)
     * @param maxY         highest level (inclusive)
     * @return the best route, or null when no tunnel in the band exposes any ore
     */
    /**
     * Known fluid pockets to route around (packed positions; either may be null). They stay until the next call.
     * Lava near a tunnel costs {@link #LAVA_COST} per cell, water {@link #WATER_COST}.
     */
    public void setFluids(long[] lavaPositions, long[] waterPositions) {
        lava.clear();
        water.clear();
        if (lavaPositions != null) {
            for (long p : lavaPositions) {
                lava.add(p);
            }
        }
        if (waterPositions != null) {
            for (long p : waterPositions) {
                water.add(p);
            }
        }
    }

    public Route best(long[] orePositions, int px, int py, int pz, int minY, int maxY, int length) {
        ores.clear();
        for (long p : orePositions) {
            ores.add(p);
        }
        Route best = null;
        double bestScore = 0;
        for (int y = minY; y <= maxY; y++) {
            for (int d = 0; d < 4; d++) {
                int n = score(px, y, pz, DX[d], DZ[d], length);
                if (n == 0) {
                    continue;
                }
                int descent = Math.abs(py - y);
                double hazard = hazardCost(px, y, pz, DX[d], DZ[d], length);
                if (hazard > 0 && n - hazard <= 0) {
                    continue; // the fluid around it outweighs the ore
                }
                double s = n - DESCENT_COST * descent - hazard;
                if (best == null || s > bestScore) {
                    best = new Route(px, y, pz, DX[d], DZ[d], length, n, descent);
                    bestScore = s;
                }
            }
        }
        return best;
    }

    /**
     * Fluid cost of a tunnel: every known lava/water cell within {@link #HAZARD_RADIUS} of any tunnel cell, counted
     * once per (cell, fluid) pair, so a pocket the tunnel runs alongside costs more than one it only grazes.
     */
    double hazardCost(int x, int y, int z, int dx, int dz, int length) {
        if (lava.isEmpty() && water.isEmpty()) {
            return 0;
        }
        double cost = 0;
        int r = HAZARD_RADIUS;
        for (int t = 1; t <= length; t++) {
            int cx = x + dx * t;
            int cz = z + dz * t;
            for (int ox = -r; ox <= r; ox++) {
                for (int oy = -r; oy <= r + 1; oy++) { // the tunnel is two tall
                    for (int oz = -r; oz <= r; oz++) {
                        long p = PackedPos.pack(cx + ox, y + oy, cz + oz);
                        if (lava.contains(p)) {
                            cost += LAVA_COST;
                        } else if (water.contains(p)) {
                            cost += WATER_COST;
                        }
                    }
                }
            }
        }
        return cost;
    }

    // ---- torch cadence and wall glances ------------------------------------------------------------------------

    /**
     * Torch spacing that keeps block light at {@code minLight} or more halfway between two torches: light falls by
     * one per block from {@link #TORCH_LIGHT}, so the gap is twice the falloff allowance. {@code minLight 8} gives 12.
     */
    public static int torchSpacingFor(int minLight) {
        int keep = Math.max(1, Math.min(TORCH_LIGHT - 1, minLight));
        return Math.max(2, 2 * (TORCH_LIGHT - keep));
    }

    /** True when tunnel step {@code t} (0 = start cell) gets a torch at the given spacing. */
    public static boolean isTorchStep(int t, int spacing) {
        return t > 0 && spacing > 0 && t % spacing == 0;
    }

    /**
     * Tunnel steps on which the miner glances at a side wall: sparse (one per about {@code meanGap} steps, never two
     * within 4 steps), deterministic from {@code seed}. Writes the steps into {@code out} with the side as the sign
     * (negative = left wall, positive = right) and returns how many were written. Allocation free.
     */
    public static int wallGlances(long seed, int length, int meanGap, int[] out) {
        int gapMean = Math.max(4, meanGap);
        int n = 0;
        long s = seed ^ 0x9E3779B97F4A7C15L;
        int t = 2;
        while (n < out.length) {
            s = mix(s);
            int gap = 4 + (int) Math.floorMod(s, (long) (2 * gapMean - 7)); // uniform in [4, 2*mean-4]
            t += gap;
            if (t > length) {
                break;
            }
            s = mix(s);
            out[n++] = (s & 1) == 0 ? -t : t;
        }
        return n;
    }

    /** splitmix64 step. */
    private static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Ore blocks a tunnel would yield: those its cells touch (6-neighbours), plus their whole veins. */
    int score(int x, int y, int z, int dx, int dz, int length) {
        counted.clear();
        queue.clear();
        for (int t = 1; t <= length; t++) {
            int cx = x + dx * t;
            int cz = z + dz * t;
            for (int h = 0; h < 2; h++) {
                exposeAround(cx, y + h, cz);
            }
        }
        // veins: an ore mined out of the wall exposes its own neighbours
        for (int i = 0; i < queue.size(); i++) {
            long p = queue.getLong(i);
            int ox = PackedPos.x(p);
            int oy = PackedPos.y(p);
            int oz = PackedPos.z(p);
            exposeAround(ox, oy, oz);
        }
        return counted.size();
    }

    /** Adds ore in the six cells around (x, y, z) not counted yet. The tunnel cells themselves count too. */
    private void exposeAround(int x, int y, int z) {
        add(x, y, z);
        add(x + 1, y, z);
        add(x - 1, y, z);
        add(x, y + 1, z);
        add(x, y - 1, z);
        add(x, y, z + 1);
        add(x, y, z - 1);
    }

    private void add(int x, int y, int z) {
        long p = PackedPos.pack(x, y, z);
        if (ores.contains(p) && counted.add(p)) {
            queue.add(p);
        }
    }
}
