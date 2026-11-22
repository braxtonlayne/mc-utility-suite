package com.utilitysuite.jobs.survival;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FoodSelectorAllocationTest {

    @Test
    void chooseOverAllSlotsAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        String[] ids = new String[36];
        int[] nut = new int[36];
        float[] sat = new float[36];
        String[] pool = {"minecraft:bread", "minecraft:rotten_flesh", "minecraft:cooked_beef", null};
        for (int i = 0; i < 36; i++) {
            ids[i] = pool[i % pool.length];
            nut[i] = ids[i] == null ? 0 : 4 + i % 5;
            sat[i] = 0.5f + i % 3;
        }
        int[] sink = new int[1];
        long bytes = AllocationProbe.measure(50_000, () -> sink[0] += FoodSelector.choose(ids, nut, sat, 9, true));
        assertEquals(0, bytes, "choose() over 36 slots must not allocate");
    }
}
