package com.utilitysuite.ui.holo;

import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HoloCardBookTest {

    @Test
    void dwellFiresOnceAfterTheDwellTimeAndDropsOnLeave() {
        HoloCardBook b = new HoloCardBook();
        b.dwellTicks = 6;
        for (int i = 0; i < 5; i++) {
            assertFalse(b.dwell(42L));
        }
        assertTrue(b.dwell(42L), "6th tick on the same target builds the card");
        assertTrue(b.active);
        for (int i = 0; i < 20; i++) {
            assertFalse(b.dwell(42L), "built once per target");
        }
        assertFalse(b.dwell(HoloCardBook.NO_TARGET));
        assertFalse(b.active, "leaving hides the card at once");
        for (int i = 0; i < 5; i++) {
            assertFalse(b.dwell(42L));
        }
        assertTrue(b.dwell(42L), "coming back dwells again");
    }

    @Test
    void switchingTargetsRestartsTheDwell() {
        HoloCardBook b = new HoloCardBook();
        b.dwellTicks = 3;
        b.dwell(1L);
        b.dwell(1L);
        assertFalse(b.dwell(2L));
        assertFalse(b.dwell(2L));
        assertTrue(b.dwell(2L));
        assertEquals(2L, b.shownKey());
    }

    @Test
    void villagerWithoutOffersAsksToOpenOnce() {
        HoloCardBook b = new HoloCardBook();
        b.fillVillager(7, "Librarian", 3);
        assertEquals(HoloCardBook.Kind.VILLAGER, b.card.kind);
        assertEquals("Librarian", b.card.title);
        assertEquals("Journeyman - " + HoloCardBook.LEARN, b.card.subtitle);
        assertEquals(0, b.card.rows);
    }

    @Test
    void villagerShowsCachedOffers() {
        HoloCardBook b = new HoloCardBook();
        b.recordOffers(7, new String[]{"minecraft:paper", "minecraft:emerald"}, new int[]{24, 9},
                new String[]{"minecraft:emerald", "minecraft:enchanted_book"}, new int[]{1, 1});
        b.fillVillager(7, "Librarian", 9);
        assertEquals("Master", b.card.subtitle);
        assertEquals(2, b.card.rows);
        assertEquals("minecraft:paper", b.card.iconA[0]);
        assertEquals(24, b.card.countA[0]);
        assertEquals("minecraft:enchanted_book", b.card.iconB[1]);
        b.fillVillager(8, "Farmer", 1);
        assertEquals(0, b.card.rows, "offers belong to one entity id");
        assertNull(b.card.iconA[0], "rows cleared on rebuild");
    }

    @Test
    void storageShowsTopItemsByCount() {
        HoloCardBook b = new HoloCardBook();
        b.fillStorage("Chest", Map.of("minecraft:dirt", 640, "minecraft:stone", 64, "minecraft:diamond", 3,
                "minecraft:iron_ingot", 128, "minecraft:coal", 32), false);
        assertEquals(HoloCardBook.ROWS, b.card.rows);
        assertEquals("minecraft:dirt", b.card.iconA[0]);
        assertEquals("minecraft:iron_ingot", b.card.iconA[1]);
        assertEquals("minecraft:stone", b.card.iconA[2]);
        assertEquals("minecraft:coal", b.card.iconA[3]);
        assertEquals("", b.card.subtitle);
        b.fillStorage("Chest", null, false);
        assertEquals(HoloCardBook.UNKNOWN, b.card.subtitle);
        b.fillStorage("Barrel", Map.of(), false);
        assertEquals(HoloCardBook.EMPTY, b.card.subtitle);
    }

    @Test
    void progressIsAnEstimateFromTheLastMenu() {
        HoloCardBook b = new HoloCardBook();
        b.fillProgress("Furnace", "minecraft:overworld", 99L);
        assertEquals(HoloCardBook.LEARN, b.card.subtitle);
        assertEquals(-1f, b.card.progress);

        b.recordProgress("minecraft:overworld", 99L, 0.5f, 200, true, 1000L);
        b.fillProgress("Furnace", "minecraft:overworld", 99L);
        assertTrue(b.card.estimate);
        assertEquals(HoloCardBook.ESTIMATE, b.card.subtitle);
        assertEquals(0.5f, b.card.progressAt(1000L), 1e-6f);
        assertEquals(0.75f, b.card.progressAt(1000L + 2500L), 1e-4f, "50 ticks of a 200 tick cook");
        assertEquals(1f, b.card.progressAt(1000L + 60_000L), "capped at done");

        b.fillProgress("Furnace", "minecraft:the_nether", 99L);
        assertEquals(-1f, b.card.progress, "other dimension");

        b.recordProgress("minecraft:overworld", 99L, 0.2f, 200, false, 1000L);
        b.fillProgress("Furnace", "minecraft:overworld", 99L);
        assertEquals(HoloCardBook.IDLE, b.card.subtitle);
        assertEquals(0.2f, b.card.progressAt(99_000L), 1e-6f, "idle stays put");
    }

    @Test
    void steadyDwellAndProgressReadAllocateNothing() {
        HoloCardBook b = new HoloCardBook();
        b.recordProgress("d", 5L, 0.1f, 100, true, 0L);
        b.fillProgress("Smoker", "d", 5L);
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        float sink = 0f;
        for (int i = 0; i < 20_000; i++) { // warm up
            b.dwell(5L);
            sink += b.card.progressAt(i);
        }
        long best = Long.MAX_VALUE;
        for (int r = 0; r < 5; r++) {
            long before = mx.getThreadAllocatedBytes(tid);
            for (int i = 0; i < 10_000; i++) {
                b.dwell(5L);
                sink += b.card.progressAt(i);
            }
            best = Math.min(best, mx.getThreadAllocatedBytes(tid) - before);
        }
        assertTrue(sink > 0f);
        assertEquals(0L, best);
    }
}
