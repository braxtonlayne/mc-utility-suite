package com.utilitysuite.world.changes;

import com.utilitysuite.world.BlockPosPacking;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.util.function.LongPredicate;

/**
 * Compares a stored {@link ChunkSnapshot} with a fresh capture of the same chunk. Detailed sections give per-block
 * results: air to solid (or one block replaced by another) is "added", solid to air is "removed". Coarse sections
 * only report that the palette changed. Containers known non-empty at the snapshot that are now empty (or gone) are
 * "emptied". Runs off-thread; results are immutable arrays of packed block positions.
 */
public final class SnapshotDiff {

    /** Immutable diff output. */
    public record Result(long[] added, long[] removed, long[] emptied, int changedSections, int comparedChunks) {
        public boolean isEmpty() {
            return added.length == 0 && removed.length == 0 && emptied.length == 0 && changedSections == 0;
        }
    }

    public static final int MAX_MARKS = 20_000;

    private final LongArrayList added = new LongArrayList();
    private final LongArrayList removed = new LongArrayList();
    private final LongArrayList emptied = new LongArrayList();
    private int changedSections;
    private int compared;

    /**
     * Adds one chunk's comparison to the running result.
     *
     * @param containerEmptied true for a packed position whose container is now known empty or no longer exists
     */
    public void compare(ChunkSnapshot before, ChunkSnapshot now, LongPredicate containerEmptied) {
        if (before == null || now == null || before.chunkX != now.chunkX || before.chunkZ != now.chunkZ) {
            return;
        }
        compared++;
        int baseX = before.chunkX << 4;
        int baseZ = before.chunkZ << 4;
        int lo = Math.max(before.minSectionY, now.minSectionY);
        int hi = Math.min(before.minSectionY + before.sectionCount(), now.minSectionY + now.sectionCount());
        for (int sy = lo; sy < hi; sy++) {
            int bi = sy - before.minSectionY;
            int ni = sy - now.minSectionY;
            if (!before.detailed() || !now.detailed()) {
                if (before.paletteHash[bi] != now.paletteHash[ni]) {
                    changedSections++;
                }
                continue;
            }
            // Detailed: a null section array means all air. Equal palette hashes can still hide moved blocks.
            byte[] a = before.blocks[bi];
            byte[] b = now.blocks[ni];
            if (a == null && b == null) {
                continue;
            }
            boolean any = false;
            int baseY = sy << 4;
            for (int i = 0; i < ChunkSnapshot.SECTION_VOLUME; i++) {
                byte va = a == null ? 0 : a[i];
                byte vb = b == null ? 0 : b[i];
                if (va == vb) {
                    continue;
                }
                any = true;
                if (added.size() + removed.size() >= MAX_MARKS) {
                    break;
                }
                long pos = BlockPosPacking.pack(baseX + (i & 15), baseY + (i >>> 8), baseZ + ((i >>> 4) & 15));
                if (vb == 0) {
                    removed.add(pos);
                } else {
                    added.add(pos);
                }
            }
            if (any) {
                changedSections++;
            }
        }
        if (containerEmptied != null) {
            for (long c : before.containers) {
                if (containerEmptied.test(c)) {
                    emptied.add(c);
                }
            }
        }
    }

    public Result result() {
        return new Result(added.toLongArray(), removed.toLongArray(), emptied.toLongArray(), changedSections,
                compared);
    }

    public void reset() {
        added.clear();
        removed.clear();
        emptied.clear();
        changedSections = 0;
        compared = 0;
    }
}
