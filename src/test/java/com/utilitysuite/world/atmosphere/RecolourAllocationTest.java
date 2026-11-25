package com.utilitysuite.world.atmosphere;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.world.WorldHooks;
import com.utilitysuite.world.overlays.TrailRecency;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Performance mandate check for the per-frame paths added with R5/R6: the sky/cloud channel blend the mixin calls
 * six times a frame, the per-tick sky target maths, and the trail fade/alpha/age-label lookups the radar does per cell
 * and per segment while drawing. None may allocate.
 */
class RecolourAllocationTest {

    @Test
    void perFrameRecolourAndRecencyAllocateNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        AtmosphereMath.ColorRamp ramp = AtmosphereMath.ColorRamp.fourKey(0xF0A070, 0xA8CCF8, 0xE07850, 0x141C38);
        double[] sink = {0};
        long[] t = {0};
        long now = 1_700_000_000_000L;
        long bytes = AllocationProbe.measure(100_000, () -> {
            long k = t[0]++;
            float daylight = AtmosphereMath.daylight((k % 1000) / 1000f);
            int target = ramp.sample(k * 7);
            float w = AtmosphereMath.weatherDamp(0.6f, (k & 1) * 0.5f, 0f);
            WorldHooks.setSkyTint(target, w);
            WorldHooks.setCloudTint(AtmosphereMath.cloudFor(target, daylight), w * 0.6f);
            sink[0] += WorldHooks.skyChannel(0, 0.4) + WorldHooks.skyChannel(1, 0.5) + WorldHooks.skyChannel(2, 0.9)
                    + WorldHooks.cloudChannel(0, 1.0) + WorldHooks.cloudChannel(1, 1.0)
                    + WorldHooks.cloudChannel(2, 1.0);
            long wall = now - k * 997;
            float f = TrailRecency.fade(wall, now, 3_600_000L);
            sink[0] += TrailRecency.withAlpha(0xA0FF4040, f);
            sink[0] += TrailRecency.ageLabel(wall, now).length();
        });
        WorldHooks.clearSkyTint();
        assertTrue(bytes < 4096, "recolour/recency allocated " + bytes + " bytes (sink " + sink[0] + ")");
    }
}
