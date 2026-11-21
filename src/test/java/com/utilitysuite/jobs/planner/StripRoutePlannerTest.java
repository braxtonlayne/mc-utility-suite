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
    void oreOutOfTunnelReachIsNotCounted() {
        long[] ores = {PackedPos.pack(5, 40, 3)}; // three blocks off every tunnel line through x or z = 0
        assertNull(new StripRoutePlanner().best(ores, 0, 60, 0, 0, 96, 16));
    }
}
