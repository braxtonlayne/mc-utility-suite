package com.utilitysuite.travel.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
