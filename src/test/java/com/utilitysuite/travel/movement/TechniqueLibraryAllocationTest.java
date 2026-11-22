package com.utilitysuite.travel.movement;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link TechniqueLibrary#plan} after warm-up: no allocation, never more than the per-call tick budget. */
class TechniqueLibraryAllocationTest {

    static final int CALLS = 600;

    private static void measure(ShapeGrid g, SupportRegion top, double sx, double sz, boolean yawAllowed) {
        float yaw = TechniqueLibraryTest.aim(top, sx, sz);
        // a body a few ticks before the edge, at sprint speed (searches, keeps and re-bases scripts there)
        MovementPhysics run = new MovementPhysics().set(sx, 20, sz, 0, -0.0784, 0, true);
        for (int t = 0; t < 12; t++) {
            run.tick(1, 0, yaw, true, false, false, g);
        }
        MovementPhysics at = new MovementPhysics().copyFrom(run);
        TechniqueLibrary lib = new TechniqueLibrary();
        int[] maxTicks = {0};
        for (int i = 0; i < 400; i++) {
            lib.plan(at, yaw, true, 1, 0, top, yawAllowed, 6f, true, 20f, g);
            maxTicks[0] = Math.max(maxTicks[0], lib.lastSimTicks());
        }
        long bytes = AllocationProbe.measure(CALLS, () -> {
            lib.plan(at, yaw, true, 1, 0, top, yawAllowed, 6f, true, 20f, g);
            if (lib.lastSimTicks() > maxTicks[0]) {
                maxTicks[0] = lib.lastSimTicks();
            }
        });
        assertTrue(bytes < 1_024, "plan allocated " + bytes + " bytes over " + CALLS + " calls");
        assertTrue(maxTicks[0] <= TechniqueSource.MAX_SIM_TICKS_PER_CALL, "sim ticks " + maxTicks[0]);
        long t0 = System.nanoTime();
        for (int i = 0; i < CALLS; i++) {
            lib.plan(at, yaw, true, 1, 0, top, yawAllowed, 6f, true, 20f, g);
        }
        double us = (System.nanoTime() - t0) / 1000.0 / CALLS;
        System.out.printf("TechniqueLibrary: %.1f us per plan (%d ticks max, yaw %s)%n", us, maxTicks[0], yawAllowed);
    }

    @Test
    void planAllocatesNothingAfterWarmUp() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        ShapeGrid corner = TechniqueLibraryTest.cornerScene(false);
        SupportRegion top = TechniqueLibraryTest.top(corner, 1, 19, 4);
        measure(corner, top, 0.5, -4, false);
        measure(corner, top, 0.5, -4, true);
        ShapeGrid neo = TechniqueLibraryTest.neoScene();
        measure(neo, TechniqueLibraryTest.top(neo, 0, 19, 4), 0.5, -4, true);
    }

    @Test
    void simTicksNeverExceedTheBudgetAlongRunUps() {
        ShapeGrid g = TechniqueLibraryTest.headhitterScene(3);
        SupportRegion top = TechniqueLibraryTest.top(g, 0, 19, 5);
        for (boolean yawAllowed : new boolean[]{false, true}) {
            TechniqueLibrary lib = new TechniqueLibrary();
            MovementPhysics p = new MovementPhysics().set(0.5, 20, -6, 0, -0.0784, 0, true);
            for (int t = 0; t < 60 && p.onGround; t++) {
                lib.plan(p, 0f, true, 1, 0, top, yawAllowed, 6f, true, 20f, g);
                assertTrue(lib.lastSimTicks() <= TechniqueSource.MAX_SIM_TICKS_PER_CALL, "ticks " + lib.lastSimTicks());
                p.tick(1, 0, 0f, true, false, false, g);
            }
        }
    }
}
