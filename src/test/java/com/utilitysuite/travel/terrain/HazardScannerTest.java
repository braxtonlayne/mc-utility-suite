package com.utilitysuite.travel.terrain;

import com.utilitysuite.travel.Packed;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HazardScannerTest {

    private static boolean contains(LongArrayList l, int x, int y, int z) {
        return l.contains(Packed.pack(x, y, z));
    }

    private static void sweep(HazardScanner s, HazardWorld w, int x, int fy, int z) {
        for (int i = 0; i < 100 && !s.hasMarkers(); i++) {
            s.tick(w, x, fy, z);
        }
    }

    @Test
    void marksDropsLavaAndLowCeilings() {
        HeightField w = new HeightField(64)
                .top(3, 3, -10, 10, 50)        // a deep trench at x = 3
                .top(-4, -4, 0, 0, 62)         // a shallow 2-block pit (below the drop threshold)
                .lavaAt(0, 62, 4)
                .solidAt(-2, 65, -2);          // ceiling one block above the floor
        HazardScanner s = new HazardScanner();
        s.configure(6, 32, 4);
        sweep(s, w, 0, 64, 0);
        assertTrue(contains(s.markers(HazardScanner.DROP), 3, 63, 0));
        assertFalse(contains(s.markers(HazardScanner.DROP), -4, 63, 0), "2-block pit is not steep");
        assertTrue(contains(s.markers(HazardScanner.LAVA), 0, 62, 4));
        assertTrue(contains(s.markers(HazardScanner.LOW_CEILING), -2, 64, -2));
        assertEquals(0, s.markers(HazardScanner.LOW_CEILING).size() - 1);
    }

    @Test
    void respectsBudgetAndPublishesOnlyCompleteSweeps() {
        HeightField w = new HeightField(64).top(3, 3, -10, 10, 40);
        HazardScanner s = new HazardScanner();
        s.configure(4, 10, 4); // 81 columns, 10 per tick
        for (int t = 0; t < 8; t++) {
            assertEquals(10, s.tick(w, 0, 64, 0));
            assertFalse(s.hasMarkers(), "nothing published mid-sweep");
        }
        s.tick(w, 0, 64, 0); // completes 81
        assertTrue(s.hasMarkers());
    }

    @Test
    void movingFarRestartsTheSweep() {
        HeightField w = new HeightField(64).top(3, 3, -10, 10, 40);
        HazardScanner s = new HazardScanner();
        s.configure(4, 40, 4);
        s.tick(w, 0, 64, 0);
        s.tick(w, 10, 64, 0); // moved > 3 blocks: restart from the new origin
        s.tick(w, 10, 64, 0);
        s.tick(w, 10, 64, 0);
        assertFalse(contains(s.markers(HazardScanner.DROP), 3, 63, 0), "old area not in the new sweep");
    }
}
