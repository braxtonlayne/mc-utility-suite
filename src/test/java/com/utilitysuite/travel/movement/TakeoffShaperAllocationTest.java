package com.utilitysuite.travel.movement;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.travel.movement.MovementPhysicsTest.Grid;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The brain's decide with takeoff shaping active: allocation free, bounded and fast. */
class TakeoffShaperAllocationTest {

    static final int CALLS = 20_000;

    /** A ground state on the 2-block run-up where the brain fills a strafe key (pillar at (1, 19, 4)). */
    private static MovementPhysics shapedState(Grid g) {
        MovementAssistBrain b = new MovementAssistBrain();
        b.setIntentTarget(1, 19, 4, 0.9);
        MovementPhysics p = new MovementPhysics().set(0.5, 20, -0.7, 0, -0.0784, 0, true);
        for (int t = 0; t < 40 && p.onGround; t++) {
            b.decide(p, 0f, true, 1, 0, false, 20f, g);
            if (b.takeoffShaped()) {
                return new MovementPhysics().copyFrom(p);
            }
            int s = b.strafe() == MovementAssistBrain.KEEP ? 0 : b.strafe();
            p.tick(1, s, 0f, true, b.jump(), g);
        }
        return null;
    }

    @Test
    void decideWithShapingActiveAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        Grid g = TakeoffShaperTest.course(2, 3, 1);
        MovementPhysics at = shapedState(g);
        assertNotNull(at, "the scenario shapes a takeoff");
        MovementAssistBrain b = new MovementAssistBrain();
        b.setIntentTarget(1, 19, 4, 0.9);
        b.shapeYawAllowed = true;
        b.setYawRate(4.0);
        // warm up (JIT) and check the state really shapes
        for (int i = 0; i < 2_000; i++) {
            b.budget().onTakeoff(false); // keep the budget open so every call shapes
            b.decide(at, 0f, true, 1, 0, false, 20f, g);
        }
        assertTrue(b.takeoffShaped(), "shaping active in the measured decide");
        long bytes = AllocationProbe.measure(CALLS, () -> {
            b.budget().onTakeoff(false);
            b.decide(at, 0f, true, 1, 0, false, 20f, g);
        });
        assertTrue(bytes < 1_024, "decide with shaping allocated " + bytes + " bytes over " + CALLS + " calls");

        long t0 = System.nanoTime();
        for (int i = 0; i < CALLS; i++) {
            b.budget().onTakeoff(false);
            b.decide(at, 0f, true, 1, 0, false, 20f, g);
        }
        double us = (System.nanoTime() - t0) / 1000.0 / CALLS;
        System.out.printf("TakeoffShaper: %.1f us per decide with shaping active, %d simulated ticks (shaper %d)%n",
                us, b.lastSimTicks(), b.shaper().lastTicks());
        assertTrue(us < 200.0, "decide took " + us + " us");
        assertTrue(b.lastSimTicks() <= MovementAssistBrain.MAX_SIM_TICKS, "sim ticks " + b.lastSimTicks());
    }

    @Test
    void simulatedTicksStayBoundedAlongShapedRunUps() {
        int max = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (boolean sprint : new boolean[]{true, false}) {
                Grid g = TakeoffShaperTest.course(4, 3, dx);
                MovementAssistBrain b = new MovementAssistBrain();
                b.setIntentTarget(dx, 19, 4, 0.9);
                b.shapeYawAllowed = true;
                b.setYawRate(-8.0);
                for (double z = -2.7; z <= 1.3; z += 0.05) {
                    b.budget().onTakeoff(false);
                    b.decide(new MovementPhysics().set(0.5, 20, z, 0, -0.0784, sprint ? 0.15 : 0.1, true), 0f, sprint,
                            1, 0, false, 20f, g);
                    max = Math.max(max, b.lastSimTicks());
                }
            }
        }
        System.out.println("TakeoffShaper: max simulated ticks per decision on shaped run-ups " + max);
        assertTrue(max <= MovementAssistBrain.MAX_SIM_TICKS, "max " + max);
    }
}
