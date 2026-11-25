package com.utilitysuite.world.seed;

import com.utilitysuite.world.oresim.WorldgenRng;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureLocatorTest {

    private static final long SEED = 123456789L;

    @Test
    void spiralCoversRingsInOrderWithoutRepeats() {
        int[] sp = StructureLocator.spiral(3);
        assertEquals(49 * 2, sp.length);
        assertEquals(0, sp[0]);
        assertEquals(0, sp[1]);
        Set<Long> seen = new HashSet<>();
        int lastRing = 0;
        for (int i = 0; i < sp.length; i += 2) {
            int ring = Math.max(Math.abs(sp[i]), Math.abs(sp[i + 1]));
            assertTrue(ring >= lastRing, "rings are visited inside-out");
            lastRing = ring;
            assertTrue(seen.add(((long) sp[i] << 32) | (sp[i + 1] & 0xFFFFFFFFL)), "no repeats");
        }
        // ring 1 starts at its (-1,-1) corner and walks the top edge first
        assertEquals(-1, sp[2]);
        assertEquals(-1, sp[3]);
        assertEquals(0, sp[4]);
        assertEquals(-1, sp[5]);
        // consecutive cells of a ring are neighbours (a real spiral walk)
        for (int i = 4; i < 18; i += 2) {
            assertEquals(1, Math.abs(sp[i] - sp[i - 2]) + Math.abs(sp[i + 1] - sp[i - 1]));
        }
    }

    @Test
    void potentialChunkStaysInsideItsRegionAndMatchesVanillaCheck() {
        WorldgenRng rng = WorldgenRng.legacy();
        for (String type : StructureSpread.PLACEMENTS.keySet()) {
            StructureSpread.Placement p = StructureSpread.PLACEMENTS.get(type);
            for (int rx = -3; rx <= 3; rx++) {
                for (int rz = -3; rz <= 3; rz++) {
                    long packed = StructureLocator.potentialChunk(SEED, p, rx, rz, rng);
                    int cx = StructureLocator.unpackX(packed);
                    int cz = StructureLocator.unpackZ(packed);
                    int ox = cx - rx * p.spacing();
                    int oz = cz - rz * p.spacing();
                    assertTrue(ox >= 0 && ox < p.spacing() - p.separation(), type + " x offset " + ox);
                    assertTrue(oz >= 0 && oz < p.spacing() - p.separation(), type + " z offset " + oz);
                    assertTrue(StructureSpread.canStartIn(SEED, p, cx, cz), type + " agrees with canStartIn");
                    int[] ref = StructureSpread.potentialChunk(SEED, p, cx, cz);
                    assertEquals(cx, ref[0]);
                    assertEquals(cz, ref[1]);
                }
            }
        }
    }

    @Test
    void regionMathsHandlesNegativeChunks() {
        StructureSpread.Placement village = StructureSpread.PLACEMENTS.get("village");
        long packed = StructureLocator.potentialChunk(SEED, village, -1, -1, WorldgenRng.legacy());
        assertTrue(StructureLocator.unpackX(packed) < 0 && StructureLocator.unpackX(packed) >= -34);
        assertTrue(StructureLocator.unpackZ(packed) < 0 && StructureLocator.unpackZ(packed) >= -34);
    }

    @Test
    void nearestIsSortedWithinRadiusAndCapped() {
        List<StructureLocator.Candidate> found = StructureLocator.nearest(SEED, StructureLocator.Dim.OVERWORLD,
                100, -200, 2048, 2, 12);
        assertFalse(found.isEmpty());
        assertTrue(found.size() <= 12);
        long last = -1;
        for (StructureLocator.Candidate c : found) {
            assertTrue(c.distSq() >= last);
            last = c.distSq();
            assertTrue(c.distance() <= 2048);
            assertTrue(StructureLocator.OVERWORLD_TYPES.contains(c.type()));
            assertTrue(StructureSpread.canStartIn(SEED, StructureSpread.PLACEMENTS.get(c.type()), c.chunkX(), c.chunkZ()));
        }
        // the nearest village candidate is the nearest potential village chunk around the player
        List<StructureLocator.Candidate> villages = StructureLocator.nearest(SEED, StructureLocator.Dim.OVERWORLD,
                100, -200, 2048, 5, 100).stream().filter(c -> c.type().equals("village")).toList();
        StructureSpread.Placement p = StructureSpread.PLACEMENTS.get("village");
        long best = Long.MAX_VALUE;
        for (int rx = -4; rx <= 4; rx++) {
            for (int rz = -4; rz <= 4; rz++) {
                long packed = StructureLocator.potentialChunk(SEED, p, rx, rz - 1, WorldgenRng.legacy());
                long dx = (StructureLocator.unpackX(packed) << 4) + 8 - 100;
                long dz = (StructureLocator.unpackZ(packed) << 4) + 8 + 200;
                best = Math.min(best, dx * dx + dz * dz);
            }
        }
        assertEquals(best, villages.get(0).distSq());
    }

    @Test
    void dimensionsPickTheirSets() {
        for (StructureLocator.Candidate c : StructureLocator.nearest(SEED, StructureLocator.Dim.NETHER, 0, 0, 1500, 3, 20)) {
            assertTrue(StructureLocator.NETHER_TYPES.contains(c.type()));
        }
        for (StructureLocator.Candidate c : StructureLocator.nearest(SEED, StructureLocator.Dim.END, 0, 0, 3000, 3, 20)) {
            assertEquals("end_city", c.type());
        }
        assertEquals("Fortress / bastion", StructureLocator.label("nether_complex"));
        assertEquals("Pillager outpost", StructureLocator.label("pillager_outpost"));
    }

    @Test
    void searchSnapshotSkipsSlimeOutsideOverworld() {
        SeedLocatorModule.Snapshot s = SeedLocatorModule.search(1, SEED, StructureLocator.Dim.NETHER, 0, 0,
                true, 1024, 2, 8, true, 8);
        assertTrue(s.seedKnown());
        assertTrue(s.slimeSpots().isEmpty());
        SeedLocatorModule.Snapshot o = SeedLocatorModule.search(2, SEED, StructureLocator.Dim.OVERWORLD, 0, 0,
                false, 1024, 2, 8, true, 8);
        assertTrue(o.structures().isEmpty());
        assertFalse(o.slimeSpots().isEmpty());
    }
}
