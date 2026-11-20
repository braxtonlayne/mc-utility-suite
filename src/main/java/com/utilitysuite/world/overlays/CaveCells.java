package com.utilitysuite.world.overlays;

import com.utilitysuite.world.BlockPosPacking;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

/**
 * Coarse cave map for the cave wireframe: every chunk section is split into 4 x 4 x 4 cells of 4 blocks; a cell is
 * "cave" when enough of its blocks are open and it lies below the surface. One section is exactly one {@code long}
 * bitmask (64 cells), so a 48-block radius costs a few kilobytes. Pure; client thread.
 */
public final class CaveCells {

    private final Long2LongOpenHashMap sections = new Long2LongOpenHashMap();
    private final LongArrayList evictScratch = new LongArrayList();
    private boolean dirty;
    private long[] renderCells = new long[256];
    private int renderCount;

    /** Bit index of cell (cx, cy, cz), each 0..3. */
    public static int bit(int cx, int cy, int cz) {
        return (cy << 4) | (cz << 2) | cx;
    }

    public void setSection(long sectionKey, long mask) {
        long prev = mask == 0L ? sections.remove(sectionKey) : sections.put(sectionKey, mask);
        if (prev != mask) {
            dirty = true;
        }
    }

    public long mask(long sectionKey) {
        return sections.get(sectionKey);
    }

    public int sectionCount() {
        return sections.size();
    }

    /** Drops sections farther than {@code radiusSections} (Chebyshev) from the centre section. */
    public void evictOutside(int sx, int sy, int sz, int radiusSections) {
        evictScratch.clear();
        for (Long2LongMap.Entry e : sections.long2LongEntrySet()) {
            long k = e.getLongKey();
            if (Math.abs(BlockPosPacking.x(k) - sx) > radiusSections || Math.abs(BlockPosPacking.y(k) - sy) > radiusSections
                    || Math.abs(BlockPosPacking.z(k) - sz) > radiusSections) {
                evictScratch.add(k);
            }
        }
        for (int i = 0; i < evictScratch.size(); i++) {
            sections.remove(evictScratch.getLong(i));
            dirty = true;
        }
    }

    public void clear() {
        if (!sections.isEmpty()) {
            sections.clear();
            dirty = true;
        }
    }

    /** Rebuilds the flat list of cave cell origins (packed block positions of each cell's min corner). */
    public void rebuildRenderListIfDirty() {
        if (!dirty) {
            return;
        }
        dirty = false;
        int n = 0;
        for (Long2LongMap.Entry e : sections.long2LongEntrySet()) {
            n += Long.bitCount(e.getLongValue());
        }
        if (renderCells.length < n) {
            renderCells = new long[Math.max(n, renderCells.length * 2)];
        }
        int i = 0;
        for (Long2LongMap.Entry e : sections.long2LongEntrySet()) {
            long k = e.getLongKey();
            long m = e.getLongValue();
            int bx = BlockPosPacking.x(k) << 4;
            int by = BlockPosPacking.y(k) << 4;
            int bz = BlockPosPacking.z(k) << 4;
            while (m != 0L) {
                int b = Long.numberOfTrailingZeros(m);
                m &= m - 1;
                renderCells[i++] = BlockPosPacking.pack(bx + ((b & 3) << 2), by + ((b >> 4) << 2),
                        bz + (((b >> 2) & 3) << 2));
            }
        }
        renderCount = i;
    }

    public int renderCount() {
        return renderCount;
    }

    public long renderCell(int i) {
        return renderCells[i];
    }
}
