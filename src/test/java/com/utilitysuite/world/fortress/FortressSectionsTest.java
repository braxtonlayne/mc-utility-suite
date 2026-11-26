package com.utilitysuite.world.fortress;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FortressSectionsTest {

    @Test
    void summaryRoundTrips() {
        long s = FortressSections.summary(4096, 0, 3, 5, 15, 9, 12);
        assertEquals(4096, FortressSections.count(s));
        assertEquals(0, FortressSections.minX(s));
        assertEquals(3, FortressSections.minY(s));
        assertEquals(5, FortressSections.minZ(s));
        assertEquals(15, FortressSections.maxX(s));
        assertEquals(9, FortressSections.maxY(s));
        assertEquals(12, FortressSections.maxZ(s));
        assertEquals(0L, FortressSections.summary(0, 1, 1, 1, 2, 2, 2));
    }

    @Test
    void touchingSectionsClusterAndBoxesApproximateThePieces() {
        FortressSections f = new FortressSections();
        // A corridor along X: sections (10..12, 4, -3), bricks y 64..72 (local 0..8), z local 4..10.
        for (int sx = 10; sx <= 12; sx++) {
            f.put(sx, 4, -3, FortressSections.summary(300, 0, 0, 4, 15, 8, 10));
        }
        // Diagonal neighbour joins (26-neighbourhood).
        f.put(13, 5, -2, FortressSections.summary(100, 0, 0, 0, 7, 3, 3));
        // A separate small player build far away.
        f.put(-40, 4, 30, FortressSections.summary(20, 2, 2, 2, 4, 3, 4));
        f.cluster();
        assertEquals(2, f.clusterCount());
        int fortress = -1;
        int build = -1;
        for (int i = 0; i < f.size(); i++) {
            if (f.sectionX(i) == 10) {
                fortress = f.clusterOf(i);
            }
            if (f.sectionX(i) == -40) {
                build = f.clusterOf(i);
            }
        }
        assertNotEquals(fortress, build);
        assertEquals(1000, f.clusterBricks(fortress));
        assertEquals(20, f.clusterBricks(build));
        assertEquals(160, f.clusterBox(fortress, 0));
        assertEquals(64, f.clusterBox(fortress, 1));
        assertEquals(-48 + 4, f.clusterBox(fortress, 2));
        assertEquals(13 * 16 + 8, f.clusterBox(fortress, 3));
        assertEquals(5 * 16 + 4, f.clusterBox(fortress, 4));
        // Per-section box of the first corridor section.
        for (int i = 0; i < f.size(); i++) {
            if (f.sectionX(i) == 11) {
                assertEquals(176, f.boxMinX(i));
                assertEquals(192, f.boxMaxX(i));
                assertEquals(73, f.boxMaxY(i));
            }
        }
    }

    @Test
    void removeAndPruneKeepTheIndexConsistent() {
        FortressSections f = new FortressSections();
        for (int i = 0; i < 100; i++) {
            f.put(i, 4, 0, FortressSections.summary(10, 0, 0, 0, 1, 1, 1));
        }
        assertEquals(100, f.size());
        f.put(5, 4, 0, 0L); // empty section: removed
        assertEquals(99, f.size());
        f.put(6, 4, 0, FortressSections.summary(50, 0, 0, 0, 1, 1, 1)); // update in place
        assertEquals(99, f.size());
        f.pruneOutside(0, 0, 20);
        assertEquals(20, f.size()); // 0..20 minus 5
        f.cluster();
        assertEquals(2, f.clusterCount()); // 0..4 and 6..20 (5 is gone)
        f.clear();
        assertEquals(0, f.size());
    }

    @Test
    void spawnClassFollowsNetherLightAndHeadroom() {
        int max = FortressSections.NETHER_MONSTER_LIGHT;
        assertEquals(FortressSections.SPAWN_WITHER, FortressSections.spawnClass(true, 3, 7, max));
        assertEquals(FortressSections.SPAWN_BLAZE, FortressSections.spawnClass(true, 3, 8, max)); // too bright
        assertEquals(FortressSections.SPAWN_BLAZE, FortressSections.spawnClass(true, 2, 0, max)); // too low
        assertEquals(FortressSections.SPAWN_NONE, FortressSections.spawnClass(true, 1, 0, max));
        assertEquals(FortressSections.SPAWN_NONE, FortressSections.spawnClass(false, 3, 0, max));
    }

    @Test
    void supportPillarsBelowThePieceBandAreSkipped() {
        // Vanilla moves every fortress piece inside y 48..70; the bricks below are fillColumnDown pillars.
        assertEquals(48, FortressSections.FORTRESS_MIN_Y);
        assertEquals(16, FortressSections.firstPieceLocalY(0)); // y 0..15: pillars only
        assertEquals(16, FortressSections.firstPieceLocalY(1)); // y 16..31
        assertEquals(16, FortressSections.firstPieceLocalY(2)); // y 32..47
        assertEquals(0, FortressSections.firstPieceLocalY(3)); // y 48..63: pieces start at its bottom
        assertEquals(0, FortressSections.firstPieceLocalY(4));
        assertEquals(16, FortressSections.firstPieceLocalY(-1));
        for (int sy = -4; sy <= 8; sy++) {
            int first = FortressSections.firstPieceLocalY(sy);
            for (int ly = 0; ly < 16; ly++) {
                boolean scanned = ly >= first;
                assertEquals((sy << 4) + ly >= FortressSections.FORTRESS_MIN_Y, scanned, "sy " + sy + " ly " + ly);
            }
        }
    }

    @Test
    void steadyStateClusteringDoesNotAllocate() {
        if (!AllocationProbe.supported()) {
            return;
        }
        FortressSections f = new FortressSections();
        for (int i = 0; i < 40; i++) {
            f.put(i % 8, 4 + i / 32, i / 8, FortressSections.summary(200, 0, 0, 0, 15, 15, 15));
        }
        f.cluster();
        long bytes = AllocationProbe.measure(500, () -> {
            f.put(3, 4, 2, FortressSections.summary(150, 1, 1, 1, 14, 14, 14));
            f.cluster();
        });
        assertTrue(bytes < 1024, "allocated " + bytes);
    }
}
