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
}
