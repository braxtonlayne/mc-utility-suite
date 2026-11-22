package com.utilitysuite.travel.movement;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JumpCommitAllocationTest {

    @Test
    void tickResolveAndLimitAllocateNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        for (JumpCommit.Mode mode : JumpCommit.Mode.values()) {
            JumpCommit c = new JumpCommit();
            c.mode = mode;
            c.maxKeyChanges = 3;
            int[] tick = new int[1];
            long bytes = AllocationProbe.measure(2_000, () -> {
                int t = tick[0]++ % 30;
                boolean ground = t < 5 || t > 16;
                int keys = t == 10 ? LandingHold.FORWARD | LandingHold.LEFT : LandingHold.FORWARD;
                c.tick(ground, t == 5, t == 17, keys, false, (t % 7) * 0.5f, 0.5, true, t == 17, t > 16 && t < 22,
                        1, t == 29);
                int f = c.resolveAxis(0, 1, t % 3 - 1);
                int s = c.resolveAxis(1, t == 10 ? 1 : 0, -1);
                c.limit(f, s, 1, 0);
                c.authority();
            });
            assertTrue(bytes < 1_024, mode + " allocated " + bytes + " bytes");
        }
    }
}
