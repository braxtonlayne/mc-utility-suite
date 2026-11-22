package com.utilitysuite.core.input;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-frame turn-gain hook and the per-tick request calls allocate nothing. */
class ManualLookAssistAllocationTest {

    private static final long BUDGET = 1_024;
    private static final int CALLS = 1_000_000;

    @Test
    void frameHookWithoutRequest() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        ManualLookAssist a = new ManualLookAssist();
        int[] i = {0};
        double[] sink = {0};
        long bytes = AllocationProbe.measure(CALLS, () -> {
            int k = i[0]++;
            sink[0] += a.onFrameYaw(k * 0.37f, ((k & 15) - 7) * 0.15, 0.007, false);
            if ((k & 255) == 0) {
                a.beginTick();
            }
        });
        assertTrue(bytes < BUDGET, "hook without a request allocated " + bytes + " bytes (" + sink[0] + ")");
    }

    @Test
    void frameHookAndRequestsWithLiveRequest() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        ManualLookAssist a = new ManualLookAssist();
        InputSmoother s = new InputSmoother(new FakeInputBackend(), new java.util.Random(3));
        a.setSmoother(s);
        String[] ids = {"travel.movement", "combat.assistant", "jobs.goal"};
        int[] i = {0};
        double[] sink = {0};
        long bytes = AllocationProbe.measure(CALLS, () -> {
            int k = i[0]++;
            if ((k & 7) == 0) {
                a.setYawGain(ids[k % 3], (k % 720) - 360f, 1.0 + (k & 3) * 0.2, 0.6, 3);
            }
            if ((k & 31) == 0) {
                a.beginTick();
                sink[0] += a.manualYawRateDegPerTick() + a.assistedYawDegLastTick();
            }
            if ((k & 1023) == 0) {
                a.clearYawGain(ids[(k >> 10) % 3]);
            }
            double dt = (k & 1) == 0 ? 0.001 : 0.016;
            sink[0] += a.onFrameYaw((k * 1.3f) % 360f, ((k % 41) - 20) * 0.15, dt, (k & 63) == 0);
            sink[0] += ManualLookAssist.scale(k * 0.5, (k % 9) - 4, dt, 33.0, 1.5, 0.5);
        });
        assertTrue(bytes < BUDGET, "hook with a request allocated " + bytes + " bytes (" + sink[0] + ")");
    }
}
