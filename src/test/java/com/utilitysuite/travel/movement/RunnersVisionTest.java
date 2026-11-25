package com.utilitysuite.travel.movement;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.travel.movement.MovementPhysicsTest.Grid;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunnersVisionTest {

    private static MovementPhysics runner() {
        // already sprinting along +z (yaw 0) on the floor at y=20
        return new MovementPhysics().set(0.5, 20, -19.5, 0, -0.0784, 0.28, true);
    }

    private static RunnersVision search(Grid g) {
        RunnersVision v = new RunnersVision();
        v.begin(runner(), 0f, 20f);
        int guard = 0;
        while (!v.step(RunnersVision.DEFAULT_BUDGET, g)) {
            assertTrue(++guard < 50, "search completes");
        }
        return v;
    }

    private static float maxZ(RunnersVision v) {
        float m = -1e9f;
        for (int i = 0; i < v.count(); i++) {
            m = Math.max(m, v.z(i));
        }
        return m;
    }

    @Test
    void flatGroundRunsToTheRangeWithoutJumps() {
        Grid g = new Grid();
        g.fill(-3, 19, -25, 3, 19, 40);
        RunnersVision v = search(g);
        assertEquals(RunnersVision.END_RANGE, v.end());
        assertEquals(0, v.jumps());
        assertTrue(maxZ(v) - (-19.5f) >= RunnersVision.RANGE - 0.5, "reaches the range");
    }

    @Test
    void jumpsAGapItCanClear() {
        Grid g = new Grid();
        g.fill(-3, 19, -25, 3, 19, 0); // edge at z = 1
        g.fill(-3, 19, 4, 3, 19, 40); // 3-block gap over a deadly drop
        RunnersVision v = search(g);
        assertEquals(1, v.jumps());
        assertEquals(RunnersVision.END_RANGE, v.end());
        assertTrue(maxZ(v) > 4, "crossed the gap");
        boolean arc = false;
        for (int i = 0; i < v.count(); i++) {
            arc |= v.air(i) && v.y(i) > 20.5f;
        }
        assertTrue(arc, "the jump arc is on the line");
    }

    @Test
    void stopsAtAGapNoJumpClears() {
        Grid g = new Grid();
        g.fill(-3, 19, -25, 3, 19, 0);
        g.fill(-3, 19, 7, 3, 19, 40); // 6-block gap: no sprint jump makes it
        RunnersVision v = search(g);
        assertEquals(RunnersVision.END_STOP, v.end());
        assertEquals(0, v.jumps());
        assertTrue(maxZ(v) < 1.6f, "the line ends at the edge");
    }

    @Test
    void walksOffAHarmlessDropWithoutJumping() {
        Grid g = new Grid();
        g.fill(-3, 19, -25, 3, 19, 0);
        g.fill(-3, 17, -25, 3, 17, 40); // 2 blocks down: no damage
        RunnersVision v = search(g);
        assertEquals(RunnersVision.END_RANGE, v.end());
    }

    @Test
    void jumpsAOneBlockStepAndStopsAtAWall() {
        Grid g = new Grid();
        g.fill(-3, 19, -25, 3, 19, 40);
        g.fill(-3, 20, -10, 3, 20, 40); // a one-block step up
        RunnersVision v = search(g);
        assertTrue(v.jumps() >= 1, "jumped the step");
        assertTrue(maxZ(v) > -9, "on top of the step");

        Grid wall = new Grid();
        wall.fill(-3, 19, -25, 3, 19, 40);
        wall.fill(-3, 20, -10, 3, 21, -10); // two blocks high
        RunnersVision w = search(wall);
        assertEquals(RunnersVision.END_STOP, w.end());
        assertTrue(maxZ(w) < -10, "stops in front of the wall");
    }

    @Test
    void searchIsBudgetedAndKeepsTheOldLineUntilDone() {
        Grid g = new Grid();
        g.fill(-3, 19, -25, 3, 19, 0);
        g.fill(-3, 19, 4, 3, 19, 40);
        RunnersVision v = search(g);
        int before = v.count();
        v.begin(runner(), 0f, 20f);
        int calls = 0;
        boolean done = false;
        while (!done) {
            done = v.step(40, g);
            calls++;
            if (!done) {
                assertEquals(before, v.count(), "the published line stays while searching");
                assertTrue(v.lastStepTicks() <= 40 + 3 * RunnersVision.EVAL_TICKS + 4, "budget per step");
            }
        }
        assertTrue(calls > 2, "spread over several ticks");
        assertFalse(v.searching());
        assertEquals(before, v.count(), "deterministic");
    }

    @Test
    void searchAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        Grid g = new Grid();
        g.fill(-3, 19, -25, 3, 19, 0);
        g.fill(-3, 19, 4, 3, 19, 40);
        RunnersVision v = new RunnersVision();
        MovementPhysics start = runner();
        long bytes = AllocationProbe.measure(200, () -> {
            if (!v.searching()) {
                v.begin(start, 0f, 20f);
            }
            v.step(RunnersVision.DEFAULT_BUDGET, g);
        });
        assertTrue(bytes < 1_024, "allocated " + bytes + " bytes");
    }
}
