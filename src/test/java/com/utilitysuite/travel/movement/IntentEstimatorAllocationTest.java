package com.utilitysuite.travel.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Test;

class IntentEstimatorAllocationTest {

    private static final int CALLS = 200_000;

    @Test
    void estimateAllocatesNothingOnPillarField() {
        assumeTrue(AllocationProbe.supported());
        // pillar field: a block every 3 columns, varying height; x,z in -16..15
        boolean[] cells = new boolean[32 * 32 * 8];
        for (int x = -15; x < 15; x += 3) {
            for (int z = -15; z < 15; z += 3) {
                int h = 3 + ((x + z) & 3);
                cells[((x + 16) * 32 + (z + 16)) * 8 + h] = true;
            }
        }
        MovementPhysics.Voxels w = (x, y, z) -> {
            int yy = y - 60;
            return x >= -16 && x < 16 && z >= -16 && z < 16 && yy >= 0 && yy < 8
                    && cells[((x + 16) * 32 + (z + 16)) * 8 + yy];
        };
        MovementPhysics p = new MovementPhysics();
        p.set(0.5, 64, 0.5, 0.05, 0, 0.25, true);
        IntentEstimator e = new IntentEstimator();
        e.noteJump(0.5, -2.5, 0.5, 0.5);
        int[] n = new int[1];
        Runnable body = () -> {
            int i = n[0]++;
            float yaw = (i & 63) * 5.6f;
            e.estimate(p, yaw, 25f, 1, 0, true, w);
        };
        long bytes = AllocationProbe.measure(CALLS, body);
        assertEquals(0L, bytes, "estimate() must not allocate");

        long t0 = System.nanoTime();
        for (int i = 0; i < 20_000; i++) {
            body.run();
        }
        double perCallUs = (System.nanoTime() - t0) / 20_000.0 / 1000.0;
        System.out.println("IntentEstimator.estimate: " + perCallUs + " us/call, candidates="
                + e.lastCandidateCount() + ", lookups=" + e.lastLookups());
    }

    /**
     * Exact shapes with the feasibility and dead-end rolls running: the stance moves every call, so cached rolls go
     * stale and are re-done (the worst case for the budget).
     */
    @Test
    void estimateAllocatesNothingWithRollsActive() {
        assumeTrue(AllocationProbe.supported());
        ShapeGrid g = new ShapeGrid().fill(-1, 63, -1, 1, 63, 1);
        // a small course of shaped tops ahead and to the sides
        g.fencePost(0, 63, 4).pane(-2, 63, 4).slabBottom(2, 63, 4).wallPost(-3, 63, 2).cube(3, 63, 2)
                .cube(0, 62, 6).cube(-2, 63, 7).fence(2, 63, 7, true, true, false, false).cube(0, 64, 3);
        MovementPhysics[] stances = new MovementPhysics[8];
        for (int i = 0; i < stances.length; i++) {
            stances[i] = new MovementPhysics().set(-0.6 + 0.3 * (i & 3), 64, 0.5 + 0.4 * (i >> 2), 0.02 * (i & 1),
                    0, 0.12 + 0.03 * i, true);
        }
        IntentEstimator e = new IntentEstimator();
        e.noteJump(0.5, -3.5, 0.5, 0.5);
        long[] stats = new long[4]; // rolls, max lookups, max sim ticks, calls
        int[] n = new int[1];
        Runnable body = () -> {
            int i = n[0]++;
            MovementPhysics p = stances[(i * 3) & 7];
            float yaw = -40f + (i % 9) * 10f;
            e.estimate(p, yaw, 30f + (i & 3) * 4f, 1, (i & 16) == 0 ? 0 : 1, (i & 32) == 0, g);
            stats[0] += e.lastRolls();
            if (e.lastLookups() > stats[1]) {
                stats[1] = e.lastLookups();
            }
            if (e.lastSimTicks() > stats[2]) {
                stats[2] = e.lastSimTicks();
            }
            stats[3]++;
        };
        long bytes = AllocationProbe.measure(50_000, body);
        assertEquals(0L, bytes, "estimate() with rolls must not allocate");
        assertTrue(stats[0] > stats[3] / 2, "rolls were active: " + stats[0] + " over " + stats[3] + " calls");
        assertTrue(stats[1] <= IntentEstimator.MAX_LOOKUPS, "lookups " + stats[1]);
        assertTrue(stats[2] <= IntentEstimator.MAX_SIM_TICKS, "sim ticks " + stats[2]);
        assertEquals(IntentEstimator.MAX_ROLLS_PER_ESTIMATE * IntentEstimator.ROLL_TICKS,
                IntentEstimator.MAX_SIM_TICKS);

        long t0 = System.nanoTime();
        for (int i = 0; i < 5_000; i++) {
            body.run();
        }
        double perCallUs = (System.nanoTime() - t0) / 5_000.0 / 1000.0;
        System.out.println("IntentEstimator.estimate (shapes, rolls): " + perCallUs + " us/call, rolls/call="
                + (double) stats[0] / stats[3] + ", max lookups=" + stats[1] + ", max sim ticks=" + stats[2]);
    }
}
