package com.utilitysuite.travel.movement;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.travel.Packed;
import com.utilitysuite.travel.movement.MovementPhysicsTest.Grid;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EscapePlannerTest {

    private static Grid field() {
        Grid g = new Grid();
        g.fill(-30, 9, -30, 30, 9, 30);
        return g;
    }

    @Test
    void noThreatNoPath() {
        EscapePlanner e = new EscapePlanner();
        assertFalse(e.plan(0.5, 10, 0.5, field()));
        assertEquals(0, e.pathLength());
    }

    @Test
    void runsAwayFromAThreatOnOpenGround() {
        EscapePlanner e = new EscapePlanner();
        e.addThreat(4.5, 10, 0.5, 0.2, false); // east
        assertTrue(e.plan(0.5, 10, 0.5, field()));
        long end = e.pathCell(e.pathLength() - 1);
        assertTrue(Packed.x(end) <= -6, "heads west, away from the threat: end x " + Packed.x(end));
        assertEquals(Packed.pack(0, 10, 0), e.pathCell(0), "starts where the player stands");
        assertTrue(e.bestScore() > e.startScore() + EscapePlanner.MIN_GAIN);
        assertConnected(e);
    }

    @Test
    void crossesAGapAMobCannotFollow() {
        // the player's ledge ends at x = 2; a 2-air gap; another ledge from x = 5. The mob stands on the player's ledge.
        Grid g = new Grid();
        g.fill(-6, 9, -1, 2, 9, 1);
        g.fill(5, 9, -1, 14, 9, 1);
        g.fill(-40, -40, -40, 79, -40, 79); // far below: no walking around the gap
        EscapePlanner e = new EscapePlanner();
        e.addThreat(-5.5, 10, 0.5, 0.2, false);
        assertTrue(e.plan(0.5, 10, 0.5, g));
        boolean jumped = false;
        for (int i = 1; i < e.pathLength(); i++) {
            jumped |= e.pathJump(i);
        }
        assertTrue(jumped, "the route jumps the gap");
        assertTrue(Packed.x(e.pathCell(e.pathLength() - 1)) >= 5, "ends across the gap");
        assertConnected(e);
    }

    @Test
    void neverPlansAnImpossibleJump() {
        Grid g = new Grid();
        g.fill(-6, 9, -1, 2, 9, 1);
        g.fill(7, 9, -1, 14, 9, 1); // 4-air gap: too far
        g.fill(-40, -40, -40, 79, -40, 79);
        EscapePlanner e = new EscapePlanner();
        e.addThreat(-5.5, 10, 0.5, 0.2, false);
        e.plan(0.5, 10, 0.5, g);
        for (int i = 0; i < e.pathLength(); i++) {
            assertTrue(Packed.x(e.pathCell(i)) <= 2, "stays on its ledge");
        }
    }

    @Test
    void cornerdWithNothingBetterReportsNoPath() {
        Grid g = new Grid();
        g.fill(-1, 9, -1, 1, 9, 1); // a 3x3 pillar top in the void
        EscapePlanner e = new EscapePlanner();
        e.addThreat(3.5, 10, 0.5, 0.28, true);
        assertFalse(e.plan(0.5, 10, 0.5, g));
    }

    @Test
    void planningAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        Grid g = field();
        EscapePlanner e = new EscapePlanner();
        e.addThreat(4.5, 10, 0.5, 0.2, false);
        e.addThreat(-3.5, 10, 6.5, 0.28, true);
        e.plan(0.5, 10, 0.5, g);
        long bytes = AllocationProbe.measure(20, () -> e.plan(0.5, 10, 0.5, g));
        assertTrue(bytes < 1_024, "plan allocated " + bytes + " bytes");
        assertTrue(e.expanded() <= EscapePlanner.MAX_NODES);
    }

    private static void assertConnected(EscapePlanner e) {
        for (int i = 1; i < e.pathLength(); i++) {
            long a = e.pathCell(i - 1);
            long b = e.pathCell(i);
            int dx = Math.abs(Packed.x(b) - Packed.x(a));
            int dz = Math.abs(Packed.z(b) - Packed.z(a));
            if (!e.pathJump(i)) {
                assertTrue(dx <= 1 && dz <= 1, "walk steps are adjacent at " + i);
            } else {
                assertTrue(dx <= 4 && dz <= 4, "jump within reach at " + i);
            }
        }
    }
}
