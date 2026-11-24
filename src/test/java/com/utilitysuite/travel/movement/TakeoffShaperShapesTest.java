package com.utilitysuite.travel.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Takeoff shaping onto targets that are not unit cubes: the simulated flight is judged against the target's real top
 * (a bottom slab's is half a block down) and the landing margin against the real top face (a post, a pane).
 */
class TakeoffShaperShapesTest {

    static final float S = 1f / 16f;

    /** Run-up platform (top y 20) up to z = 0 and a far floor; the caller adds the target cell at (0, 19, tz). */
    static ShapeGrid runUp() {
        ShapeGrid g = new ShapeGrid();
        g.fill(-3, 19, -6, 3, 19, 0);
        g.fill(-10, -40, -10, 10, -40, 20);
        return g;
    }

    static JumpSearch target(int tz, double top, float x0, float z0, float x1, float z1) {
        JumpSearch s = new JumpSearch();
        s.setTarget(0, 19, tz);
        s.setTargetTop(top, x0, tz + z0, x1, tz + z1);
        return s;
    }

    /**
     * Moves the start along the run-up until the plain run-up lands on the target; returns the shaper after that
     * evaluate, or null when no start lands.
     */
    static TakeoffShaper firstPlainLanding(ShapeGrid g, JumpSearch search, boolean sprint) {
        TakeoffShaper sh = new TakeoffShaper();
        MovementPhysics p = new MovementPhysics();
        for (int i = 0; i < 400; i++) {
            double z = -1.0 + i * 0.005;
            p.set(0.5, 20, z, 0, -0.0784, sprint ? 0.2 : 0.11, true);
            sh.evaluate(p, 0f, sprint, 1, 0, 0.0, false, false, false, 20f, search, g);
            if (sh.plainJumpTick() >= 0 && sh.plainMiss() <= 0.0) {
                return sh;
            }
        }
        return null;
    }

    @Test
    void thePlainRunUpLandsOnABottomSlab() {
        ShapeGrid g = runUp();
        g.slabBottom(0, 19, 3); // 2 air blocks, top at 19.5
        JumpSearch search = target(3, 19.5, 0f, 0f, 1f, 1f);
        TakeoffShaper sh = firstPlainLanding(g, search, false);
        assertNotNull(sh, "some run-up lands on the slab: the flight is not cut off half a block above it");
        assertTrue(sh.plainMargin() > 0.0 && sh.plainMargin() <= 0.8 + 1.0e-9, "margin " + sh.plainMargin());
    }

    @Test
    void theMarginOnAFencePostIsMeasuredOnThePost() {
        JumpSearch search = target(3, 20.5, 6 * S, 6 * S, 10 * S, 10 * S);
        assertEquals(0.125 + 0.299, TakeoffShaper.margin(0.5, 20.5, 3.5, search), 1.0e-9, "centre of the post");
        assertEquals(0.024, TakeoffShaper.margin(0.9, 20.5, 3.5, search), 1.0e-9, "nearly off the post in x");
        assertEquals(0.024, TakeoffShaper.margin(0.5, 20.5, 3.1, search), 1.0e-9, "nearly off the post in z");
        assertTrue(TakeoffShaper.margin(0.95, 20.5, 3.5, search) < 0.0, "off the post is a miss");
    }

    @Test
    void theMarginOnAPaneIsMeasuredOnThePane() {
        JumpSearch search = target(3, 20.0, 7 * S, 7 * S, 9 * S, 9 * S);
        assertEquals(0.0625 + 0.299, TakeoffShaper.margin(0.5, 20.0, 3.5, search), 1.0e-9);
        assertEquals(0.0115, TakeoffShaper.margin(0.85, 20.0, 3.5, search), 1.0e-9);
    }

    @Test
    void aLandingOnAFencePostNeverReportsACubeMargin() {
        ShapeGrid g = runUp();
        g.fencePost(0, 19, 2); // 1 air block, top at 20.5
        JumpSearch search = target(2, 20.5, 6 * S, 6 * S, 10 * S, 10 * S);
        TakeoffShaper sh = firstPlainLanding(g, search, false);
        assertNotNull(sh, "some run-up lands on the post");
        assertTrue(sh.plainMargin() <= 0.125 + 0.299 + 1.0e-9,
                "no landing on a 0.25 wide post has more room than the post: " + sh.plainMargin());
    }

    @Test
    void aLandingOnAPaneNeverReportsACubeMargin() {
        ShapeGrid g = runUp();
        g.pane(0, 19, 3); // 2 air blocks, top level with the run-up
        JumpSearch search = target(3, 20.0, 7 * S, 7 * S, 9 * S, 9 * S);
        TakeoffShaper sh = firstPlainLanding(g, search, false);
        assertNotNull(sh, "some run-up lands on the pane");
        assertTrue(sh.plainMargin() <= 0.0625 + 0.299 + 1.0e-9,
                "no landing on a pane has more room than the pane: " + sh.plainMargin());
    }
}
