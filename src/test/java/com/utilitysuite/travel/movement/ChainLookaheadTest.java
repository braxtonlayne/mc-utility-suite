package com.utilitysuite.travel.movement;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.travel.movement.MovementPhysicsTest.Grid;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainLookaheadTest {

    /** Single blocks at y 9 (feet 10) every {@code step} blocks along +z from z 0, {@code n} of them. */
    static Grid row(int step, int n) {
        Grid g = new Grid();
        for (int i = 0; i < n; i++) {
            g.fill(0, 9, i * step, 0, 9, i * step);
        }
        return g;
    }

    static MovementPhysics standing(double x, double y, double z) {
        return new MovementPhysics().set(x, y, z, 0, -0.0784, 0, true);
    }

    /** Updates until the corridor is built and solved; returns the ticks it took. */
    static int settle(ChainLookahead c, MovementPhysics at, float yaw, MovementPhysics.Voxels w) {
        int t = 0;
        do {
            c.update(at, yaw, w);
            assertTrue(c.lastLookups() <= ChainLookahead.LOOKUPS_PER_TICK + 512, "budget " + c.lastLookups());
            t++;
        } while (c.busy() && t < 400);
        assertFalse(c.busy(), "settled");
        return t;
    }

    @Test
    void aRowOfThreeGapJumpsIsOneChainOfJumps() {
        Grid g = row(4, 9); // 3 air between blocks, a sprint jump each, 32 blocks of course
        ChainLookahead c = new ChainLookahead();
        int ticks = settle(c, standing(0.5, 10, 0.5), 0f, g);
        assertTrue(c.ready());
        assertEquals(9, c.length(), "every block of the row is a link");
        for (int i = 0; i < c.length(); i++) {
            assertEquals(i * 4, c.linkZ(i));
            assertEquals(10.0, c.linkTop(i), 1e-9);
            assertEquals(i > 0, c.linkJump(i));
        }
        assertEquals(0, c.indexAt(0.5, 10, 0.5));
        assertEquals(1, c.nextJump(0));
        assertEquals(-1, c.nextJump(8), "the chain ends on the last block");
        assertTrue(ticks < 20, "ready in " + ticks + " ticks");
    }

    @Test
    void aGapTooLongEndsTheChain() {
        Grid g = row(4, 4);
        g.fill(0, 9, 12 + 6, 0, 9, 12 + 6); // 5 air after the last: out of reach
        ChainLookahead c = new ChainLookahead();
        settle(c, standing(0.5, 10, 0.5), 0f, g);
        assertEquals(4, c.length());
        assertEquals(12, c.linkZ(3));
    }

    @Test
    void theChainStaysOnTheCourseOverALowerRunway() {
        // a wide floor 3 below runs the whole corridor: walking it is legal, but the drop costs more than the jumps
        Grid g = row(4, 9);
        g.fill(-6, 6, 2, 6, 6, 40);
        ChainLookahead c = new ChainLookahead();
        settle(c, standing(0.5, 10, 0.5), 0f, g);
        for (int i = 0; i < c.length(); i++) {
            assertEquals(10.0, c.linkTop(i), 1e-9, "link " + i + " stays on the course");
        }
        assertEquals(9, c.length());
    }

    @Test
    void aZigzagInsideTheCorridorIsFollowed() {
        Grid g = new Grid();
        int[] xs = {0, 2, -1, 1, -1, 2, 0, 3};
        for (int i = 0; i < xs.length; i++) {
            g.fill(xs[i], 9, i * 3, xs[i], 9, i * 3);
        }
        ChainLookahead c = new ChainLookahead();
        settle(c, standing(0.5, 10, 0.5), 0f, g);
        assertEquals(xs.length, c.length());
        for (int i = 0; i < xs.length; i++) {
            assertEquals(xs[i], c.linkX(i));
            assertEquals(i * 3, c.linkZ(i));
        }
    }

    @Test
    void stairsUpAndDownAreLinks() {
        Grid g = new Grid();
        g.fill(0, 9, 0, 0, 9, 0);
        g.fill(0, 10, 3, 0, 10, 3); // one up, 2 air
        g.fill(0, 11, 6, 0, 11, 6); // one up again
        g.fill(0, 8, 10, 0, 8, 10); // three down, 3 air
        ChainLookahead c = new ChainLookahead();
        settle(c, standing(0.5, 10, 0.5), 0f, g);
        assertEquals(4, c.length());
        assertEquals(11.0, c.linkTop(1), 1e-9);
        assertEquals(12.0, c.linkTop(2), 1e-9);
        assertEquals(9.0, c.linkTop(3), 1e-9);
    }

    @Test
    void aWallInTheFlightIsNoLink() {
        Grid g = row(4, 3);
        g.fill(0, 10, 6, 0, 11, 6); // a 2-high wall between the 2nd and 3rd block
        ChainLookahead c = new ChainLookahead();
        settle(c, standing(0.5, 10, 0.5), 0f, g);
        assertEquals(2, c.length(), "the chain stops before the wall");
    }

    @Test
    void aHeadingAlongXFindsTheChainThere() {
        Grid g = new Grid();
        for (int i = 0; i < 8; i++) {
            g.fill(-i * 4, 9, 0, -i * 4, 9, 0);
        }
        ChainLookahead c = new ChainLookahead();
        settle(c, standing(0.5, 10, 0.5), 90f, g); // yaw 90 looks toward -x
        assertEquals(8, c.length());
        assertEquals(-28, c.linkX(7));
    }

    @Test
    void thePlanFromTheTakeoffAnswersAtTheTouchdown() {
        Grid g = row(4, 9);
        ChainLookahead c = new ChainLookahead();
        settle(c, standing(0.5, 10, 0.5), 0f, g);
        // airborne over the gap: no rebuild, the plan stays
        MovementPhysics air = new MovementPhysics().set(0.5, 11, 2.5, 0, 0.1, 0.3, false);
        c.update(air, 0f, g);
        // the touchdown tick on the second block: a rebuild starts, the old plan still answers
        MovementPhysics land = standing(0.5, 10, 4.4);
        c.update(land, 0f, g);
        assertTrue(c.ready());
        int i = c.indexAt(land.x, land.y, land.z);
        assertEquals(1, i);
        assertEquals(2, c.nextJump(i));
    }

    @Test
    void standingOnTheRimOfABlockFindsItsTop() {
        Grid g = row(4, 5);
        ChainLookahead c = new ChainLookahead();
        settle(c, standing(0.5, 10, 1.2), 0f, g); // centre over air at z 1.2, hitbox on block z 0
        assertEquals(5, c.length());
    }

    @Test
    void updatingAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        Grid g = row(4, 9);
        g.fill(-6, 6, 2, 6, 6, 40);
        ChainLookahead c = new ChainLookahead();
        MovementPhysics a = standing(0.5, 10, 0.5);
        MovementPhysics b = standing(0.5, 10, 4.5);
        long bytes = AllocationProbe.measure(40, () -> {
            c.update(a, 0f, g);
            c.update(b, 0f, g);
        });
        assertTrue(bytes < 1_024, "update allocated " + bytes + " bytes");
    }
}
