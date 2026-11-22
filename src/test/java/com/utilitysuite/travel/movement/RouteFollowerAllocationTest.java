package com.utilitysuite.travel.movement;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.travel.Angles;
import com.utilitysuite.travel.Packed;
import com.utilitysuite.travel.movement.MovementPhysicsTest.Grid;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteFollowerAllocationTest {

    /** A square loop (10 x 10 cells, hollow middle) with a 2-block gap in one leg; the route is reset each lap. */
    private static final class Course {
        final Grid g = new Grid();
        final long[] route = new long[64];
        int length;
        final RouteFollower f = new RouteFollower();
        final MovementPhysics p = new MovementPhysics().set(0.5, 20, 0.5, 0, 0, 0, true);
        float yaw;
        int laps;
        int resets;
        int maxSim;
        int maxSamples;

        Course() {
            g.fill(-2, 19, -2, 12, 19, 12);
            clear(2, 8, 2, 8);   // hollow middle
            clear(9, 12, 4, 5);  // 2-block gap across the +X leg
            for (int z = 0; z <= 10; z++) {
                add(0, z);
            }
            for (int x = 1; x <= 10; x++) {
                add(x, 10);
            }
            for (int z = 9; z >= 6; z--) {
                add(10, z);
            }
            for (int z = 3; z >= 0; z--) {
                add(10, z);
            }
            for (int x = 9; x >= 0; x--) {
                add(x, 0);
            }
            f.setRoute(route, length);
        }

        private void clear(int x0, int x1, int z0, int z1) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    g.cells[((x + 40) * 120 + (19 + 40)) * 120 + (z + 40)] = false;
                }
            }
        }

        private void add(int x, int z) {
            route[length++] = Packed.pack(x, 20, z);
        }

        void step() {
            f.update(p, yaw, 20f, g);
            if (f.lastSimTicks() > maxSim) {
                maxSim = f.lastSimTicks();
            }
            if (f.lastSamples() > maxSamples) {
                maxSamples = f.lastSamples();
            }
            if (f.finished() || f.offRoute()) {
                if (f.offRoute() || p.y < 19) {
                    p.set(0.5, 20, 0.5, 0, 0, 0, true);
                    resets++;
                } else {
                    laps++;
                }
                f.setRoute(route, length);
                return;
            }
            p.tick(f.forward(), f.strafe(), yaw, f.sprint(), f.jump(), g);
            double d = Angles.delta(yaw, f.wantYaw());
            yaw = (float) Angles.wrap(yaw + Math.max(-22.5, Math.min(22.5, d)));
        }
    }

    @Test
    void loopingCourseAllocatesNothingAndKeepsTheBudget() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        Course c = new Course();
        long bytes = AllocationProbe.measure(100_000, c::step);
        assertTrue(bytes < 1_024, "100k updates allocated " + bytes + " bytes");
        assertTrue(c.laps > 10, "the course was run, laps " + c.laps + ", resets " + c.resets);
        assertTrue(c.resets == 0, "never left the course, resets " + c.resets);
        assertTrue(c.maxSim <= RouteFollower.SIM_BUDGET, "simulated ticks per update " + c.maxSim);
        assertTrue(c.maxSamples <= RouteFollower.SAMPLE_BUDGET, "corridor samples per update " + c.maxSamples);
    }
}
