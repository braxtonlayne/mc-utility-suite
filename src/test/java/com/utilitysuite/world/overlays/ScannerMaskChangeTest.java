package com.utilitysuite.world.overlays;

import com.utilitysuite.world.BlockPosPacking;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Mask changes of the shared scanner: a category nobody asks for any more is stripped in place (the other modules'
 * matches survive, no rescan), and a requeue visits the chunks nearest the player first. Found by the 17-worldextra
 * playtest: switching Trial Chambers on next to World Knowledge's ore scan cleared ~57k ore matches and requeued
 * ~14.5k sections in load order, so a vault placed next to the player was still unlisted 10 seconds later.
 */
class ScannerMaskChangeTest {

    @Test
    void retainCategoriesKeepsOtherModulesMatches() {
        HighlightIndex idx = new HighlightIndex();
        int ore = idx.idIndex("minecraft:diamond_ore");
        int vault = idx.idIndex("minecraft:vault");
        long a = BlockPosPacking.pack(1, 2, 3);
        long b = BlockPosPacking.pack(4, 2, 3);
        long c = BlockPosPacking.pack(17, 2, 3); // next section
        idx.replaceSection(HighlightIndex.sectionOf(a), new LongArrayList(new long[] {a, b}),
                new IntArrayList(new int[] {HighlightIndex.value(BlockCategories.ORE_RARE, true, ore),
                        HighlightIndex.value(BlockCategories.SPAWNER, true, vault)}));
        idx.setBlock(c, HighlightIndex.value(BlockCategories.SPAWNER | BlockCategories.CUSTOM, false, vault));
        idx.rebuildRenderListIfDirty();
        assertEquals(3, idx.renderCount());

        idx.retainCategories(BlockCategories.ORES | BlockCategories.CUSTOM); // the spawner module was switched off
        idx.rebuildRenderListIfDirty();
        assertEquals(2, idx.size());
        assertEquals(2, idx.renderCount());
        assertEquals(HighlightIndex.value(BlockCategories.ORE_RARE, true, ore), idx.get(a)); // untouched
        assertEquals(0, idx.get(b));
        assertEquals(HighlightIndex.value(BlockCategories.CUSTOM, false, vault), idx.get(c)); // bit stripped only

        // The section lists stay consistent: replacing a section later drops exactly its members.
        idx.replaceSection(HighlightIndex.sectionOf(a), new LongArrayList(), new IntArrayList());
        assertEquals(1, idx.size());
        idx.retainCategories(BlockCategories.ORES);
        assertEquals(0, idx.size());
        idx.setBlock(c, HighlightIndex.value(BlockCategories.ORE_COMMON, true, ore)); // section re-created cleanly
        assertEquals(1, idx.size());
    }

    @Test
    void requeueOrderIsNearestChunksFirst() {
        LongOpenHashSet chunks = new LongOpenHashSet();
        for (int x = -20; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) {
                chunks.add(BlockPosPacking.chunk(100 + x, -50 + z));
            }
        }
        long[] order = WorldScanner.nearestFirst(chunks, 100, -50);
        assertEquals(chunks.size(), order.length);
        assertEquals(BlockPosPacking.chunk(100, -50), order[0]);
        int last = 0;
        for (int i = 0; i < order.length; i++) {
            int ring = Math.max(Math.abs(BlockPosPacking.chunkX(order[i]) - 100),
                    Math.abs(BlockPosPacking.chunkZ(order[i]) + 50));
            assertEquals(true, ring >= last, "ring order at " + i);
            last = ring;
        }
        // The 3x3 around the player come before anything else.
        for (int i = 0; i < 9; i++) {
            assertEquals(true, Math.abs(BlockPosPacking.chunkX(order[i]) - 100) <= 1
                    && Math.abs(BlockPosPacking.chunkZ(order[i]) + 50) <= 1);
        }
    }
}
