package com.utilitysuite.world.sonar;

import com.utilitysuite.world.BlockPosPacking;

/**
 * Engine-free surface sampling for the sonar pulse (worker thread): every solid block that touches air or a passable
 * block (terrain surfaces, cave walls) within {@code range} of the origin, every spawner, and the extra marks handed in
 * (seed-predicted ores). When there are more than {@code maxPoints}, the nearest are kept (a counting sort on the
 * whole-block distance, so the cut is exact per shell). Two passes over the box, no per-cell allocation.
 */
public final class SurfaceExtractor {

    private SurfaceExtractor() {
    }

    /**
     * @param extraPos   packed world positions of extra marks (e.g. predicted ores), may be null
     * @param extraKind  their kinds ({@link PointCloud#ORE} ...), parallel to {@code extraPos}
     * @param extraCount how many of the extra entries are used
     */
    public static PointCloud extract(VoxelGrid g, int originX, int originY, int originZ, int range, int maxPoints,
                                     boolean spawners, long[] extraPos, byte[] extraKind, int extraCount) {
        int buckets = range + 1;
        long r2 = (long) range * range;
        // Pass 1: count per whole-block distance shell.
        int[] count = new int[buckets];
        fill(g, originX, originY, originZ, r2, spawners, extraPos, extraKind, extraCount, count, null, null, null,
                null, null);
        // Slots per shell: every shell nearer than the cap in full, the cap's shell partly, nothing beyond.
        int[] start = new int[buckets + 1];
        int acc = 0;
        for (int b = 0; b < buckets; b++) {
            start[b] = acc;
            acc += Math.min(count[b], Math.max(0, maxPoints - acc));
        }
        start[buckets] = acc;
        // Pass 2: write each point into its shell's slots.
        long[] pos = new long[acc];
        float[] dist = new float[acc];
        byte[] kind = new byte[acc];
        int[] cursor = start.clone();
        fill(g, originX, originY, originZ, r2, spawners, extraPos, extraKind, extraCount, null, cursor, start, pos,
                dist, kind);
        return new PointCloud(originX, originY, originZ, pos, dist, kind, start);
    }

    /** One walk over the box: counts per shell (count != null) or writes into the shell slots (cursor != null). */
    private static void fill(VoxelGrid g, int ox, int oy, int oz, long r2, boolean spawners, long[] extraPos,
                             byte[] extraKind, int extraCount, int[] count, int[] cursor, int[] start, long[] pos,
                             float[] dist, byte[] kind) {
        int sx = g.sizeX();
        int sy = g.sizeY();
        int sz = g.sizeZ();
        int gx = g.originX();
        int gy = g.originY();
        int gz = g.originZ();
        for (int ly = 0; ly < sy; ly++) {
            int dy = gy + ly - oy;
            for (int lz = 0; lz < sz; lz++) {
                int dz = gz + lz - oz;
                for (int lx = 0; lx < sx; lx++) {
                    byte k = g.at(g.index(lx, ly, lz));
                    if (!VoxelGrid.solid(k)) {
                        continue;
                    }
                    int dx = gx + lx - ox;
                    long d2 = (long) dx * dx + (long) dy * dy + (long) dz * dz;
                    if (d2 > r2) {
                        continue;
                    }
                    byte pk;
                    if (k == VoxelGrid.SPAWNER) {
                        if (!spawners) {
                            continue;
                        }
                        pk = PointCloud.SPAWNER;
                    } else if (touchesOpen(g, lx, ly, lz)) {
                        pk = PointCloud.SURFACE;
                    } else {
                        continue;
                    }
                    put(d2, BlockPosPacking.pack(gx + lx, gy + ly, gz + lz), pk, count, cursor, start, pos, dist,
                            kind);
                }
            }
        }
        for (int i = 0; i < extraCount; i++) {
            long p = extraPos[i];
            long dx = BlockPosPacking.x(p) - ox;
            long dy = BlockPosPacking.y(p) - oy;
            long dz = BlockPosPacking.z(p) - oz;
            long d2 = dx * dx + dy * dy + dz * dz;
            if (d2 <= r2) {
                put(d2, p, extraKind[i], count, cursor, start, pos, dist, kind);
            }
        }
    }

    private static void put(long d2, long p, byte k, int[] count, int[] cursor, int[] start, long[] pos,
                            float[] dist, byte[] kind) {
        float d = (float) Math.sqrt((double) d2);
        int b = (int) d;
        if (count != null) {
            count[b]++;
            return;
        }
        int i = cursor[b];
        if (i >= start[b + 1]) {
            return; // shell full (cap reached inside this shell)
        }
        cursor[b] = i + 1;
        pos[i] = p;
        dist[i] = d;
        kind[i] = k;
    }

    /** True when one of the six neighbours is air or passable (out-of-box neighbours do not count). */
    static boolean touchesOpen(VoxelGrid g, int lx, int ly, int lz) {
        return openAt(g, lx + 1, ly, lz) || openAt(g, lx - 1, ly, lz) || openAt(g, lx, ly + 1, lz)
                || openAt(g, lx, ly - 1, lz) || openAt(g, lx, ly, lz + 1) || openAt(g, lx, ly, lz - 1);
    }

    private static boolean openAt(VoxelGrid g, int lx, int ly, int lz) {
        return g.inside(lx, ly, lz) && VoxelGrid.open(g.get(lx, ly, lz));
    }
}
