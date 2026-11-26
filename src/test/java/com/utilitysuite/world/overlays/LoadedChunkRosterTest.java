package com.utilitysuite.world.overlays;

import com.utilitysuite.world.BlockPosPacking;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scanner's loaded-chunk roster. Found by the 7-world playtest on 26.3: after a far teleport the client chunk
 * cache dropped the old chunks without {@code ChunkEvent.Unload} (the forget packets arrive after the new cache centre,
 * so {@code ClientChunkCache.drop} sees them out of range), and their ore highlights stayed in the X-Ray index as
 * {@code void_air}. The sweep must find every such chunk within a bounded number of checks.
 */
class LoadedChunkRosterTest {

    @Test
    void addRemoveContainsStayConsistent() {
        LoadedChunkRoster r = new LoadedChunkRoster();
        long a = BlockPosPacking.chunk(1, 2);
        long b = BlockPosPacking.chunk(-3, 4);
        long c = BlockPosPacking.chunk(5, -6);
        assertTrue(r.add(a));
        assertTrue(r.add(b));
        assertTrue(r.add(c));
        assertFalse(r.add(b));
        assertEquals(3, r.size());
        assertTrue(r.remove(a)); // swap-with-last keeps the slot map right
        assertFalse(r.remove(a));
        assertTrue(r.contains(b));
        assertTrue(r.contains(c));
        assertTrue(r.remove(c));
        assertTrue(r.remove(b));
        assertEquals(0, r.size());
        assertTrue(r.add(a));
        assertEquals(1, r.toLongArray().length);
    }

    @Test
    void sweepFindsEverySilentlyDroppedChunkWithinOnePass() {
        LoadedChunkRoster r = new LoadedChunkRoster();
        LongOpenHashSet cache = new LongOpenHashSet(); // what the client chunk cache still holds
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                long k = BlockPosPacking.chunk(x, z);
                r.add(k);
                if ((x + z) % 3 != 0) {
                    cache.add(k); // every third chunk was dropped without an unload event
                }
            }
        }
        int total = r.size();
        int dropped = total - cache.size();
        LongArrayList stale = new LongArrayList();
        int checks = 0;
        int found = 0;
        while (checks < total) { // one pass: each entry checked once (removals do not advance the cursor)
            found += r.sweep(7, cache::contains, stale::add);
            checks += 7;
        }
        assertEquals(dropped, found);
        assertEquals(dropped, stale.size());
        assertEquals(cache.size(), r.size());
        for (int i = 0; i < stale.size(); i++) {
            assertFalse(cache.contains(stale.getLong(i)));
            assertFalse(r.contains(stale.getLong(i)));
        }
        assertEquals(0, r.sweep(total, cache::contains, stale::add)); // steady state: nothing more to drop
    }
}
