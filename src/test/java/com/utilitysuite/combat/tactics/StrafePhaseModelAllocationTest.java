package com.utilitysuite.combat.tactics;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link StrafePhaseModel#observe} and {@link StrafePhaseModel#predictOffset} run every engaged tick. */
class StrafePhaseModelAllocationTest {

    private static final long BUDGET = 1_024;

    @Test
    void observeAndPredictAllocateNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        StrafePhaseModel m = new StrafePhaseModel();
        double[] out = new double[2];
        long[] tick = {0};
        double[] x = {0};
        double[] sink = {0};
        long bytes = AllocationProbe.measure(50_000, () -> {
            long t = tick[0]++;
            int id = (int) (t >> 10) & 15; // cycles through more ids than slots: exercises slot reuse
            double v = ((t / 12) & 1L) == 0 ? 0.2 : -0.2;
            m.observe(id, t, x[0], 3.0, v, 0.01, 0.0, 0.0);
            x[0] += v;
            sink[0] += m.predictOffset(id, t, 3.0, v, 0.01, out) + out[0] + out[1];
            sink[0] += m.ticksToReversal(id, t) + m.lateralSignAt(id, t, 4) + m.confidence(id);
        });
        assertTrue(bytes < BUDGET, "StrafePhaseModel allocated " + bytes + " bytes over 50k calls");
        assertTrue(sink[0] != 12345.678);
    }
}
