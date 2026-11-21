package com.utilitysuite.jobs.planner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StripRoutePlannerTest {

    @Test
    void picksTheDirectionAndLevelThatRunPastTheMostOre() {
        // a 3-block vein east at y 40 in the tunnel's wall (z + 1), one lone ore west at y 45
        long[] ores = {
                PackedPos.pack(10, 40, 1), PackedPos.pack(11, 40, 1), PackedPos.pack(11, 40, 2),
                PackedPos.pack(-12, 45, 0),
        };
        StripRoutePlanner.Route r = new StripRoutePlanner().best(ores, 0, 60, 0, 0, 96, 32);
        assertNotNull(r);
        assertEquals(1, r.dx(), "east");
        assertEquals(0, r.dz());
        assertTrue(r.y() == 40 || r.y() == 39, "a level whose walls touch the vein: " + r.y());
        assertEquals(3, r.ores(), "the whole vein, including the block behind the wall ore");
    }

    @Test
    void aTieGoesToTheShorterDigDown() {
        long[] ores = {PackedPos.pack(5, 50, 0), PackedPos.pack(5, 20, 0)};
        StripRoutePlanner.Route r = new StripRoutePlanner().best(ores, 0, 60, 0, 0, 96, 16);
        assertNotNull(r);
        assertTrue(r.y() >= 49 && r.y() <= 51, "the ore nearer the surface: " + r.y());
    }

    @Test
    void routeTurnsAwayFromALavaPocket() {
        // two equal veins at y 40: east and south. A lava pocket sits beside the east tunnel.
        long[] ores = {
                PackedPos.pack(10, 40, 1), PackedPos.pack(11, 40, 1),
                PackedPos.pack(1, 40, 10), PackedPos.pack(1, 40, 11),
        };
        StripRoutePlanner p = new StripRoutePlanner();
        long[] lava = {PackedPos.pack(6, 40, -2), PackedPos.pack(7, 40, -2), PackedPos.pack(8, 40, -2)};
        p.setFluids(lava, null);
        StripRoutePlanner.Route r = p.best(ores, 0, 40, 0, 40, 40, 16);
        assertNotNull(r);
        assertEquals(0, r.dx(), "not east, past the lava");
        assertEquals(1, r.dz(), "south");
    }

    @Test
    void lavaWeighsMoreThanWater() {
        StripRoutePlanner p = new StripRoutePlanner();
        p.setFluids(new long[]{PackedPos.pack(5, 40, 2)}, null);
        double lava = p.hazardCost(0, 40, 0, 1, 0, 16);
        p.setFluids(null, new long[]{PackedPos.pack(5, 40, 2)});
        double water = p.hazardCost(0, 40, 0, 1, 0, 16);
        assertTrue(lava > water && water > 0, lava + " vs " + water);
        p.setFluids(null, null);
        assertEquals(0.0, p.hazardCost(0, 40, 0, 1, 0, 16));
    }

    @Test
    void torchCadenceFollowsLightFalloff() {
        assertEquals(12, StripRoutePlanner.DEFAULT_TORCH_SPACING);
        assertEquals(12, StripRoutePlanner.torchSpacingFor(8));
        assertTrue(StripRoutePlanner.torchSpacingFor(12) < StripRoutePlanner.torchSpacingFor(4));
        int count = 0;
        int last = 0;
        for (int t = 0; t <= 48; t++) {
            if (StripRoutePlanner.isTorchStep(t, 12)) {
                assertEquals(12, t - last);
                last = t;
                count++;
            }
        }
        assertEquals(4, count);
    }

    @Test
    void wallGlancesAreSparseAndDeterministic() {
        int[] a = new int[16];
        int[] b = new int[16];
        int na = StripRoutePlanner.wallGlances(42L, 64, 10, a);
        int nb = StripRoutePlanner.wallGlances(42L, 64, 10, b);
        assertEquals(na, nb);
        assertTrue(na >= 2 && na <= 15, "sparse: " + na);
        int prev = 0;
        for (int i = 0; i < na; i++) {
            assertEquals(a[i], b[i]);
            int t = Math.abs(a[i]);
            assertTrue(t - prev >= 4 && t <= 64);
            prev = t;
        }
    }

    @Test
    void oreOutOfTunnelReachIsNotCounted() {
        long[] ores = {PackedPos.pack(5, 40, 3)}; // three blocks off every tunnel line through x or z = 0
        assertNull(new StripRoutePlanner().best(ores, 0, 60, 0, 0, 96, 16));
    }
}
