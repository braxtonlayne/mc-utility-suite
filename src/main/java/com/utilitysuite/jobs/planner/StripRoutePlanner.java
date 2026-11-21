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

    private final LongOpenHashSet ores = new LongOpenHashSet();
    private final LongOpenHashSet counted = new LongOpenHashSet();
    private final LongArrayList queue = new LongArrayList();

    /**
     * @param orePositions packed ore positions (observed or predicted), already filtered to minable ones
     * @param minY         lowest level of the ore's good band (inclusive)
     * @param maxY         highest level (inclusive)
     * @return the best route, or null when no tunnel in the band exposes any ore
     */
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
                double s = n - DESCENT_COST * descent;
                if (best == null || s > bestScore) {
                    best = new Route(px, y, pz, DX[d], DZ[d], length, n, descent);
                    bestScore = s;
                }
            }
        }
        return best;
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
