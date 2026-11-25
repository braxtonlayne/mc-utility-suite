package com.utilitysuite.world.spawn;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.world.render.LineSink;
import com.utilitysuite.world.render.Shapes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaveHeatGridTest {

    @Test
    void zonesFollowVanillaDistances() {
        assertEquals(CaveHeatGrid.ZONE_NO_SPAWN, CaveHeatGrid.zone(23.9 * 23.9));
        assertEquals(CaveHeatGrid.ZONE_SAFE, CaveHeatGrid.zone(24 * 24));
        assertEquals(CaveHeatGrid.ZONE_SAFE, CaveHeatGrid.zone(32 * 32));
        assertEquals(CaveHeatGrid.ZONE_RANDOM_DESPAWN, CaveHeatGrid.zone(33 * 33));
        assertEquals(CaveHeatGrid.ZONE_RANDOM_DESPAWN, CaveHeatGrid.zone(128 * 128));
        assertEquals(CaveHeatGrid.ZONE_INSTANT_DESPAWN, CaveHeatGrid.zone(129 * 129));
        assertFalse(CaveHeatGrid.countsForCap(10 * 10));
        assertTrue(CaveHeatGrid.countsForCap(60 * 60));
        assertFalse(CaveHeatGrid.countsForCap(130 * 130));
    }

    @Test
    void biggestClusterWinsOverMoreScatteredSpots() {
        CaveHeatGrid g = new CaveHeatGrid();
        g.reset(0, 0, -4, 24); // sections y -4..19 (blocks -64..319)
        // Cluster A: two touching sections near (64, -20, 64), 30 spots.
        for (int i = 0; i < 20; i++) {
            g.add(64 + (i % 4), -20, 64);
        }
        for (int i = 0; i < 10; i++) {
            g.add(80 + (i % 4), -20, 64); // the next section along X
        }
        // Scattered: three isolated sections with 12 spots each (36 total, but not touching).
        for (int i = 0; i < 12; i++) {
            g.add(-100, 0, 0);
            g.add(0, 0, -100);
            g.add(100, 60, -100);
        }
        g.computeClusters();
        assertEquals(66, g.total());
        assertEquals(30, g.bestSpots());
        assertEquals(2, g.bestSections());
        // Weighted centroid: section centres x 72 (20 spots) and 88 (10 spots) -> 77.33.
        assertEquals((72 * 20 + 88 * 10) / 30.0, g.bestX(), 1e-6);
        assertEquals(-24.0, g.bestY(), 1e-6); // section -2 centre
        assertEquals(72.0, g.bestZ(), 1e-6);
        assertEquals(4, g.bestMinSX());
        assertEquals(5, g.bestMaxSX());
    }

    @Test
    void outsideTheGridIsIgnoredAndResetClears() {
        CaveHeatGrid g = new CaveHeatGrid();
        g.reset(0, 0, 0, 16);
        assertFalse(g.add(1000, 10, 0));
        assertFalse(g.add(0, -10, 0));
        assertTrue(g.add(0, 10, 0));
        g.reset(0, 0, 0, 16);
        g.computeClusters();
        assertEquals(0, g.total());
        assertEquals(0, g.bestSpots());
    }

    @Test
    void domeIsTheUpperHalfOfTheSphere() {
        double[] minY = {Double.MAX_VALUE};
        double[] maxR = {0};
        int[] n = {0};
        LineSink s = (x1, y1, z1, x2, y2, z2, c) -> {
            n[0]++;
            minY[0] = Math.min(minY[0], Math.min(y1, y2));
            maxR[0] = Math.max(maxR[0], Math.sqrt(x1 * x1 + y1 * y1 + z1 * z1));
        };
        Shapes.dome(s, 0, 0, 0, 32f, -1);
        assertEquals(Shapes.domeLines(), n[0]);
        assertTrue(minY[0] > -1e-3, "dome dips below its base: " + minY[0]);
        assertEquals(32.0, maxR[0], 1e-3);
    }

    @Test
    void clusteringDoesNotAllocate() {
        if (!AllocationProbe.supported()) {
            return;
        }
        CaveHeatGrid g = new CaveHeatGrid();
        g.reset(0, 0, -4, 24);
        long bytes = AllocationProbe.measure(200, () -> {
            g.reset(0, 0, -4, 24);
            for (int i = 0; i < 64; i++) {
                g.add(i * 3 - 90, -30 + i, 40);
            }
            g.computeClusters();
        });
        assertTrue(bytes < 1024, "allocated " + bytes);
    }
}
