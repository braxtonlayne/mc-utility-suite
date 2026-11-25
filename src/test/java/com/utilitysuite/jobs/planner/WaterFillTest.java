package com.utilitysuite.jobs.planner;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaterFillTest {

    @Test
    void findsNearestWaterSourceOrCauldronWithinTheBox() {
        Map<Long, Integer> world = new HashMap<>();
        world.put(PackedPos.pack(10, 63, 0), WaterFill.SOURCE);
        world.put(PackedPos.pack(-3, 64, 2), WaterFill.CAULDRON);
        world.put(PackedPos.pack(0, 40, 0), WaterFill.SOURCE); // far below the search box
        int[] lookups = {0};
        WaterFill.Probe probe = (x, y, z) -> {
            lookups[0]++;
            return world.getOrDefault(PackedPos.pack(x, y, z), WaterFill.NONE);
        };
        assertEquals(PackedPos.pack(-3, 64, 2), WaterFill.nearest(probe, 0, 64, 0, 16, 6, 3));
        assertTrue(lookups[0] <= 33 * 33 * 10, "bounded: at most (2r+1)^2 * (down+up+1) lookups");

        world.remove(PackedPos.pack(-3, 64, 2));
        assertEquals(PackedPos.pack(10, 63, 0), WaterFill.nearest(probe, 0, 64, 0, 16, 6, 3));
        world.remove(PackedPos.pack(10, 63, 0));
        assertEquals(PlanStep.NO_POS, WaterFill.nearest(probe, 0, 64, 0, 16, 6, 3), "no water in range: fail");
    }

    @Test
    void usesNeededIsCappedByCarriedBottles() {
        assertEquals(3, WaterFill.usesNeeded(3, 0, 10));
        assertEquals(1, WaterFill.usesNeeded(3, 2, 10));
        assertEquals(2, WaterFill.usesNeeded(6, 0, 2));
        assertEquals(0, WaterFill.usesNeeded(3, 5, 10));
    }
}
