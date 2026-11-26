package com.utilitysuite.jobs.fieldwork;

import com.utilitysuite.core.api.IInventoryView;
import com.utilitysuite.jobs.planner.PackedPos;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;

/**
 * Engine-free crop table and target picker for crop harvest and replant. The world side offers each crop cell of the
 * reach box (a budgeted scan spread over ticks); only fully grown crops are kept, and {@link #next} picks the nearest
 * one, preferring crops that can be replanted from what the player carries. Allocation free after construction.
 */
public final class CropPlanner {

    /** Crop kinds, indices into the tables below. */
    public static final String[] CROP_BLOCKS = {
            "minecraft:wheat", "minecraft:carrots", "minecraft:potatoes", "minecraft:beetroots",
            "minecraft:nether_wart", "minecraft:cocoa"};
    /** Age at which each crop is fully grown. */
    static final int[] MATURE_AGE = {7, 7, 7, 3, 3, 2};
    /** Item that replants each crop. */
    public static final String[] SEED_ITEMS = {
            "minecraft:wheat_seeds", "minecraft:carrot", "minecraft:potato", "minecraft:beetroot_seeds",
            "minecraft:nether_wart", "minecraft:cocoa_beans"};
    public static final int COCOA = 5;

    private final LongArrayList cells = new LongArrayList(64);
    private final IntArrayList kinds = new IntArrayList(64);
    private boolean includeCocoa;

    public void setIncludeCocoa(boolean on) {
        includeCocoa = on;
    }

    /** Crop kind of a block id, or -1. */
    public static int kindOf(String blockId) {
        for (int i = 0; i < CROP_BLOCKS.length; i++) {
            if (CROP_BLOCKS[i].equals(blockId)) {
                return i;
            }
        }
        return -1;
    }

    public static boolean isMature(int kind, int age) {
        return kind >= 0 && age >= MATURE_AGE[kind];
    }

    /** True when the player carries the item that replants this crop. */
    public static boolean canReplant(int kind, IInventoryView inv) {
        return kind >= 0 && inv != null && inv.count(SEED_ITEMS[kind]) > 0;
    }

    /** Starts a fresh snapshot of the reach box. */
    public void clear() {
        cells.clear();
        kinds.clear();
    }

    /** Offers one scanned crop cell; kept only when it is a fully grown crop this module works. */
    public void offer(long pos, int kind, int age) {
        if (kind < 0 || (kind == COCOA && !includeCocoa) || !isMature(kind, age)) {
            return;
        }
        cells.add(pos);
        kinds.add(kind);
    }

    public int size() {
        return cells.size();
    }

    /** Drops a cell (harvested, or found changed). */
    public void remove(long pos) {
        int i = cells.indexOf(pos);
        if (i >= 0) {
            cells.removeLong(i);
            kinds.removeInt(i);
        }
    }

    /**
     * Index of the next crop to harvest: nearest to the eye within {@code reach}, preferring crops the player can
     * replant when {@code needSeeds} is set (otherwise a harvest would leave the farmland bare). -1 when none.
     *
     * @param hasSeed per-kind flags: the player carries that crop's seed item
     */
    public int next(double ex, double ey, double ez, double reach, boolean needSeeds, boolean[] hasSeed) {
        int best = -1;
        double bestD = reach * reach;
        for (int i = 0, n = cells.size(); i < n; i++) {
            if (needSeeds && !hasSeed[kinds.getInt(i)]) {
                continue;
            }
            long p = cells.getLong(i);
            double dx = PackedPos.x(p) + 0.5 - ex, dy = PackedPos.y(p) + 0.5 - ey, dz = PackedPos.z(p) + 0.5 - ez;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) {
                best = i;
                bestD = d;
            }
        }
        return best;
    }

    public long cell(int i) {
        return cells.getLong(i);
    }

    public int kind(int i) {
        return kinds.getInt(i);
    }

    // ---- replant aim ------------------------------------------------------------------------------------------------

    /** Points tried on the soil's top face when replanting: the centre, four corners, four edge midpoints. */
    public static final int AIM_POINTS = 9;
    /** How far the corner and edge points stay inside the face. */
    static final double AIM_INSET = 0.2;

    /** Visibility of one aim point (horizontal offsets 0..1 inside the cell) from the player's eye. */
    @FunctionalInterface
    public interface AimTest {
        boolean visible(double fx, double fz);
    }

    /**
     * Where to aim at the soil under a crop at cell ({@code x}, {@code z}) so the replant click lands on its top face:
     * the first of {@link #AIM_POINTS} candidate points that {@code test} finds visible, tried centre first, then the
     * corners and the edge midpoints nearest to the eye ({@code ex}, {@code ez}) first. A crop next to a taller one
     * (seen past it at a flat angle) often hides the centre of its soil but not a near corner, and only the clicked
     * block and face decide where the seed goes. Writes the offsets inside the cell (0..1) to {@code out[0..1]}.
     * Allocation free.
     *
     * @return false when no candidate is visible (the crop should be left standing: a harvest would leave bare soil)
     */
    public static boolean replantAim(double ex, double ez, int x, int z, AimTest test, double[] out) {
        if (test.visible(0.5, 0.5)) {
            out[0] = 0.5;
            out[1] = 0.5;
            return true;
        }
        double lo = AIM_INSET;
        double hi = 1.0 - AIM_INSET;
        // corners, then edge midpoints, each group nearest to the eye first (selection over 4 entries)
        for (int group = 0; group < 2; group++) {
            int tried = 0;
            for (int k = 0; k < 4; k++) {
                int best = -1;
                double bestD = Double.MAX_VALUE;
                for (int c = 0; c < 4; c++) {
                    if ((tried & (1 << c)) != 0) {
                        continue;
                    }
                    double fx = aimX(group, c, lo, hi);
                    double fz = aimZ(group, c, lo, hi);
                    double dx = x + fx - ex;
                    double dz = z + fz - ez;
                    double d = dx * dx + dz * dz;
                    if (d < bestD) {
                        bestD = d;
                        best = c;
                    }
                }
                tried |= 1 << best;
                double fx = aimX(group, best, lo, hi);
                double fz = aimZ(group, best, lo, hi);
                if (test.visible(fx, fz)) {
                    out[0] = fx;
                    out[1] = fz;
                    return true;
                }
            }
        }
        return false;
    }

    private static double aimX(int group, int c, double lo, double hi) {
        if (group == 0) {
            return (c & 1) == 0 ? lo : hi;
        }
        return c == 0 ? lo : c == 1 ? hi : 0.5;
    }

    private static double aimZ(int group, int c, double lo, double hi) {
        if (group == 0) {
            return (c & 2) == 0 ? lo : hi;
        }
        return c == 2 ? lo : c == 3 ? hi : 0.5;
    }
}
