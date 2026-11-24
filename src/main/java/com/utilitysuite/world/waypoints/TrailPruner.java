package com.utilitysuite.world.waypoints;

import com.utilitysuite.world.BlockPosPacking;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * Cuts loops out of a breadcrumb path before "Take Me Back" walks it. From each point the walk jumps to the latest later
 * point that is close (within {@link #REACH} blocks horizontally and {@link #RISE} vertically), so a cave figure-8 is
 * walked once instead of retraced. The navigator paths between consecutive points, so a shortcut through a thin wall
 * becomes a detour, never a stuck walk.
 * <p>
 * Linear time: points are bucketed in {@link #REACH}-sized columns remembering the latest index, and each step looks at
 * the 3x3 columns around it (a bucket that holds a vertically distant later point simply yields no shortcut there).
 * One-shot on a click; allocates its output and a map. Engine-free.
 */
public final class TrailPruner {

    /** Horizontal shortcut distance in blocks. */
    public static final int REACH = 4;
    /** Vertical tolerance in blocks. */
    public static final int RISE = 2;

    private TrailPruner() {
    }

    /** The path (packed positions, walk order) with loops removed; the first and last points are always kept. */
    public static long[] prune(long[] path) {
        int n = path.length;
        if (n < 3) {
            return path.clone();
        }
        Long2IntOpenHashMap latest = new Long2IntOpenHashMap(Math.max(16, n));
        for (int i = 0; i < n; i++) {
            latest.put(cell(BlockPosPacking.x(path[i]) >> 2, BlockPosPacking.z(path[i]) >> 2), i); // later wins
        }
        long[] out = new long[n];
        int size = 0;
        int i = 0;
        while (true) {
            out[size++] = path[i];
            if (i == n - 1) {
                break;
            }
            int x = BlockPosPacking.x(path[i]);
            int y = BlockPosPacking.y(path[i]);
            int z = BlockPosPacking.z(path[i]);
            int next = i + 1;
            for (int cx = (x >> 2) - 1; cx <= (x >> 2) + 1; cx++) {
                for (int cz = (z >> 2) - 1; cz <= (z >> 2) + 1; cz++) {
                    int j = latest.getOrDefault(cell(cx, cz), -1);
                    if (j > next && close(x, y, z, path[j])) {
                        next = j;
                    }
                }
            }
            i = next;
        }
        return java.util.Arrays.copyOf(out, size);
    }

    static boolean close(int x, int y, int z, long p) {
        int dx = BlockPosPacking.x(p) - x;
        int dy = BlockPosPacking.y(p) - y;
        int dz = BlockPosPacking.z(p) - z;
        return dx * dx + dz * dz <= REACH * REACH && Math.abs(dy) <= RISE;
    }

    private static long cell(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }
}
