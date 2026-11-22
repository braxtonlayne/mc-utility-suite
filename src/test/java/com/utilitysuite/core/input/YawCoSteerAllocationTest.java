package com.utilitysuite.core.input;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-frame co-steer path (follower, request, tick) and the facts feed allocate nothing. */
class YawCoSteerAllocationTest {

    @Test
    void perFrameCoSteerAndFactsAllocateNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        YawCoSteer s = new YawCoSteer();
        ManualFactsTracker f = new ManualFactsTracker();
        ManualLookAssist gain = new ManualLookAssist();
        gain.setYawGain("travel.movement", 45f, 1.3, 0.8, 1_000_000);
        com.utilitysuite.core.api.SimKey[] keys = com.utilitysuite.core.api.SimKey.all();
        int[] i = {0};
        double[] sink = {0};
        float[] yaw = {0f};
        long bytes = AllocationProbe.measure(1_000_000, () -> {
            int k = i[0]++;
            if ((k & 7) == 0) {
                s.beginTick();
                f.beginTick();
                s.request((k % 360) - 180f, 100 + (k & 255), 30 + (k & 63), 0.25 + (k & 3) * 0.25);
                f.onPress(keys[k % keys.length]);
                f.onRelease(keys[(k >> 3) % keys.length]);
                sink[0] += f.heldTicks(keys[k % keys.length]) + f.handYawDegLastTick();
                if (f.heldSince(keys[(k >> 2) % keys.length], f.tick() - 3)) {
                    sink[0]++;
                }
            }
            if ((k & 4095) == 0) {
                s.reset();
            }
            double raw = ((k & 15) - 7) * 0.05;
            f.addHandYaw(raw);
            double out = gain.onFrameYaw(yaw[0], raw, 0.004, false);
            double add = s.onFrame(yaw[0], raw, out, (k & 1) == 0 ? 0.004 : 0.016, (k & 1023) == 0);
            yaw[0] += (float) (out + add);
            sink[0] += s.opposingDeg();
        });
        assertTrue(bytes < 1_024, "co-steer frame path allocated " + bytes + " bytes (" + sink[0] + ")");
    }
}
