package com.utilitysuite.travel.movement;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LandingHoldAllocationTest {

    @Test
    void updateAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        LandingHold h = new LandingHold();
        int[] tick = new int[1];
        long bytes = AllocationProbe.measure(2_000, () -> {
            int t = tick[0]++ % 24;
            boolean ground = t < 4 || t > 14;
            int keys = t == 20 ? 0 : LandingHold.FORWARD;
            h.update(ground, keys, false, t == 14, t == 15, t > 17);
            h.forwardAxis(keys);
        });
        assertTrue(bytes < 1_024, "allocated " + bytes + " bytes");
    }
}
