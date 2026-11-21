package com.utilitysuite.jobs.fieldwork;

import com.utilitysuite.jobs.planner.PackedPos;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Engine-free flood fill of connected same-type blocks for the tree feller / vein miner. Budgeted: {@link #step} visits
 * at most N cells per call, so a scan spreads over ticks; the vein is capped. All collections are allocated once and
 * reused, so stepping and picking allocate nothing.
 * <p>
 * Neighbourhoods: ores use all 26 neighbours (diagonal veins). Logs use the full 3x3 layer at the same level and one
 * level up (branches and diagonal trunks grow up and out) plus straight down, never diagonally down, so a feller does
 * not wander into the neighbouring tree's roots.
 */
public final class VeinCollector {

    /** Answers whether a cell holds the same block type as the vein's start. Must not allocate. */
    @FunctionalInterface
    public interface Lookup {
        boolean matches(int x, int y, int z);
    }

    public static final int DEFAULT_CAP = 256;
    /** Hard ceiling whatever the setting says. */
    public static final int MAX_CAP = 512;

    private static final int[] ORE_OFFSETS = buildOreOffsets();
    private static final int[] TREE_OFFSETS = buildTreeOffsets();

    private final LongOpenHashSet seen = new LongOpenHashSet(MAX_CAP * 4);
    private final LongArrayFIFOQueue frontier = new LongArrayFIFOQueue(MAX_CAP);
    private final LongArrayList found = new LongArrayList(MAX_CAP);
    private int[] offsets = ORE_OFFSETS;
    private int cap = DEFAULT_CAP;
    private boolean active;

    /** Starts a new vein at the broken block. The start itself is already gone and is not part of the result. */
    public void begin(long start, boolean tree, int cap) {
        seen.clear();
        frontier.clear();
        found.clear();
        offsets = tree ? TREE_OFFSETS : ORE_OFFSETS;
        this.cap = Math.max(1, Math.min(MAX_CAP, cap));
        seen.add(start);
        frontier.enqueue(start);
        active = true;
    }

    /**
     * Expands the flood fill by up to {@code budget} lookups.
     *
     * @return true when the fill is complete (frontier empty or cap reached)
     */
    public boolean step(Lookup lookup, int budget) {
        if (!active) {
            return true;
        }
        int used = 0;
        while (!frontier.isEmpty() && found.size() < cap) {
            long p = frontier.firstLong();
            int x = PackedPos.x(p), y = PackedPos.y(p), z = PackedPos.z(p);
            int[] o = offsets;
            // one cell's neighbourhood per iteration; resume from the same cell when the budget runs out mid-way
            if (used + o.length / 3 > budget && used > 0) {
                return false;
            }
            frontier.dequeueLong();
            for (int i = 0; i < o.length; i += 3) {
                int nx = x + o[i], ny = y + o[i + 1], nz = z + o[i + 2];
                long n = PackedPos.pack(nx, ny, nz);
                used++;
                if (seen.add(n) && lookup.matches(nx, ny, nz)) {
                    found.add(n);
                    if (found.size() >= cap) {
                        break;
                    }
                    frontier.enqueue(n);
                }
            }
        }
        return true;
    }

    public int size() {
        return found.size();
    }

    public boolean isEmpty() {
        return found.isEmpty();
    }

    public boolean isCapped() {
        return found.size() >= cap;
    }

    /** True when the cell is still a pending part of the vein. */
    public boolean contains(long p) {
        return found.contains(p);
    }

    /** Drops a cell (mined, or found changed). */
    public void remove(long p) {
        int i = found.indexOf(p);
        if (i >= 0) {
            found.removeLong(i);
        }
    }

    /**
     * The pending cell nearest the eye position (squared distance from the block centre), or {@link Long#MIN_VALUE}
     * when none is within {@code maxDist}. Ties go to the lower cell (fell a trunk from the bottom up).
     */
    public long nearest(double ex, double ey, double ez, double maxDist) {
        long best = Long.MIN_VALUE;
        double bestD = maxDist * maxDist;
        int bestY = Integer.MAX_VALUE;
        for (int i = 0, n = found.size(); i < n; i++) {
            long p = found.getLong(i);
            double dx = PackedPos.x(p) + 0.5 - ex, dy = PackedPos.y(p) + 0.5 - ey, dz = PackedPos.z(p) + 0.5 - ez;
            double d = dx * dx + dy * dy + dz * dz;
            int y = PackedPos.y(p);
            if (d < bestD - 1e-9 || (Math.abs(d - bestD) <= 1e-9 && y < bestY)) {
                best = p;
                bestD = d;
                bestY = y;
            }
        }
        return best;
    }

    /** Pending cell {@code i} in discovery order (for overlays and tests). */
    public long get(int i) {
        return found.getLong(i);
    }

    public void clear() {
        seen.clear();
        frontier.clear();
        found.clear();
        active = false;
    }

    private static int[] buildOreOffsets() {
        int[] o = new int[26 * 3];
        int k = 0;
        // faces first so a capped fill keeps the tightly connected blocks
        int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] f : faces) {
            o[k++] = f[0];
            o[k++] = f[1];
            o[k++] = f[2];
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int m = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (m >= 2) {
                        o[k++] = dx;
                        o[k++] = dy;
                        o[k++] = dz;
                    }
                }
            }
        }
        return o;
    }

    private static int[] buildTreeOffsets() {
        int[] o = new int[(1 + 8 + 9) * 3];
        int k = 0;
        o[k++] = 0; // straight down
        o[k++] = -1;
        o[k++] = 0;
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dy == 0 && dx == 0 && dz == 0) {
                        continue;
                    }
                    o[k++] = dx;
                    o[k++] = dy;
                    o[k++] = dz;
                }
            }
        }
        return o;
    }
}
