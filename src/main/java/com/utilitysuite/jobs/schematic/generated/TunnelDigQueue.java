package com.utilitysuite.jobs.schematic.generated;

import com.utilitysuite.jobs.planner.PackedPos;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * The cells of a 3-wide tunnel still to dig (the {@link HighwayDesign.Segment#digCells} of the published segments:
 * side columns and the third row as well as the walking space). Picks the next cell like a player clears a tunnel:
 * nearest column first, top-down inside a column, only within reach, never the block the player stands on. Cells
 * that are no longer solid (dug by the navigator, the user or a previous pick) are dropped as they are met.
 * <p>
 * Engine-agnostic (packed {@link PackedPos} cells, a {@link Solid} probe) and allocation free after the backing array
 * has grown; one pick is a linear scan of the pending cells (a few hundred at most).
 */
public final class TunnelDigQueue {

    public static final long NONE = Long.MIN_VALUE;

    /** True when the cell still holds a block to break. */
    public interface Solid {
        boolean solidAt(int x, int y, int z);
    }

    private long[] cells = new long[128];
    private int size;
    private final LongOpenHashSet members = new LongOpenHashSet();

    /** Adds the cells (duplicates across segment seams are ignored). */
    public void addAll(long[] packed) {
        for (long c : packed) {
            if (!members.add(c)) {
                continue;
            }
            if (size == cells.length) {
                long[] grown = new long[cells.length * 2];
                System.arraycopy(cells, 0, grown, 0, size);
                cells = grown;
            }
            cells[size++] = c;
        }
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void clear() {
        size = 0;
        members.clear();
    }

    /** Removes a cell (dug, or given up after a failed break). */
    public void remove(long cell) {
        if (!members.remove(cell)) {
            return;
        }
        for (int i = 0; i < size; i++) {
            if (cells[i] == cell) {
                cells[i] = cells[--size];
                return;
            }
        }
    }

    /**
     * The next cell to break, or {@link #NONE} when no pending cell is within reach.
     *
     * @param eyeX     player eye position (reach and column distance are measured from it)
     * @param reach    block reach in blocks (eye to cell centre)
     * @param standMinX columns under the player's feet ({@code floor} of the box's min/max x and z) at
     * @param standY   the y of the block the player stands on; those cells are never picked
     */
    public long pick(double eyeX, double eyeY, double eyeZ, double reach, int standMinX, int standMaxX,
                     int standMinZ, int standMaxZ, int standY, Solid solid) {
        double reach2 = reach * reach;
        long best = NONE;
        double bestCol = Double.MAX_VALUE;
        int bestY = Integer.MIN_VALUE;
        int i = 0;
        while (i < size) {
            long c = cells[i];
            int x = PackedPos.x(c);
            int y = PackedPos.y(c);
            int z = PackedPos.z(c);
            if (!solid.solidAt(x, y, z)) {
                members.remove(c);
                cells[i] = cells[--size];
                continue; // re-check the cell swapped into slot i
            }
            i++;
            if (y == standY && x >= standMinX && x <= standMaxX && z >= standMinZ && z <= standMaxZ) {
                continue;
            }
            double dx = x + 0.5 - eyeX;
            double dy = y + 0.5 - eyeY;
            double dz = z + 0.5 - eyeZ;
            double col = dx * dx + dz * dz;
            if (col + dy * dy > reach2) {
                continue;
            }
            if (col < bestCol - 1e-9 || (Math.abs(col - bestCol) <= 1e-9 && y > bestY)) {
                best = c;
                bestCol = col;
                bestY = y;
            }
        }
        return best;
    }

    /** The pending cell whose column is nearest (horizontally) to the position, or {@link #NONE} when empty. */
    public long nearest(double px, double pz) {
        long best = NONE;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            long c = cells[i];
            double dx = PackedPos.x(c) + 0.5 - px;
            double dz = PackedPos.z(c) + 0.5 - pz;
            double d = dx * dx + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        return best;
    }
}
