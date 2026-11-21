package com.utilitysuite.core.input;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Starting and stepping a camera momentum release allocates nothing. */
class MomentumReleaseAllocationTest {

    private static final long BUDGET = 1_024;

    @Test
    void startAndAdvance() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        MomentumRelease m = new MomentumRelease();
        int[] i = {0};
        double[] sink = {0};
        long bytes = AllocationProbe.measure(200_000, () -> {
            int k = i[0]++;
            if ((k & 7) == 0) {
                m.start(300.0 - (k & 15) * 10, 80.0, (k & 8) != 0);
            }
            m.advance(0.016, (k % 3) - 1, ((k >> 2) % 3) - 1);
            sink[0] += m.yawStep() + m.pitchStep() + m.speed() + (m.isActive() ? 1 : 0);
        });
        assertTrue(bytes < BUDGET, "momentum allocated " + bytes + " bytes");
    }
}
