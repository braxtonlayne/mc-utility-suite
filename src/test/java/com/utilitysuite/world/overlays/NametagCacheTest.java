package com.utilitysuite.world.overlays;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NametagCacheTest {

    private static NametagCache cache() {
        return new NametagCache(NametagCache.DEFAULT_FORMAT, String::length);
    }

    @Test
    void noRebuildWhileValuesAreUnchanged() {
        NametagCache c = cache();
        c.beginFrame();
        NametagCache.Entry e = c.get(7, 20, 15, 12);
        String text = e.text;
        assertEquals("❤ 20  ⛨ 15  12m", text);
        assertEquals(text.length(), e.width);
        for (int i = 0; i < 100; i++) {
            c.beginFrame();
            assertSame(text, c.get(7, 20, 15, 12).text);
        }
        assertEquals(1, c.rebuilds());
        c.get(7, 19, 15, 12);
        assertEquals(2, c.rebuilds());
        assertEquals("❤ 19  ⛨ 15  12m", c.get(7, 19, 15, 12).text);
        assertEquals(2, c.rebuilds());
    }

    @Test
    void staleEntriesAreSwept() {
        NametagCache c = cache();
        c.beginFrame();
        c.get(1, 20, 0, 5);
        c.get(2, 20, 0, 5);
        for (int i = 0; i <= NametagCache.STALE_FRAMES; i++) {
            c.beginFrame();
            c.get(2, 20, 0, 5);
        }
        c.sweep();
        assertEquals(1, c.size());
    }

    @Test
    void cachedLookupDoesNotAllocate() {
        if (!AllocationProbe.supported()) {
            return;
        }
        NametagCache c = cache();
        for (int id = 0; id < 32; id++) {
            c.get(id, 20, 10, id);
        }
        long bytes = AllocationProbe.measure(20_000, () -> {
            c.beginFrame();
            for (int id = 0; id < 32; id++) {
                c.get(id, 20, 10, id);
            }
        });
        assertTrue(bytes < 1024, "allocated " + bytes);
    }
}
