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
        g.fill(-6, 6, 2, 6, 6, 32); // ends with the course: the longer corridor would rightly prefer a floor that goes on
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
        g.fill(-6, 6, 2, 6, 6, 32); // ends with the course: the longer corridor would rightly prefer a floor that goes on
        ChainLookahead c = new ChainLookahead();
        MovementPhysics a = standing(0.5, 10, 0.5);
        MovementPhysics b = standing(0.5, 10, 4.5);
        long bytes = AllocationProbe.measure(40, () -> {
            c.look(a, 0f, 10f, g);
            c.update(a, 0f, g);
            c.look(b, 0f, 10f, g);
            c.update(b, 0f, g);
        });
        assertTrue(bytes < 1_024, "update allocated " + bytes + " bytes");
    }

    /** A Y: from the start block, one branch of blocks at x -2 and one at x +2, every 3 blocks along +z. */
    static Grid fork() {
        Grid g = new Grid();
        g.fill(0, 9, 0, 0, 9, 0);
        for (int k = 1; k <= 8; k++) {
            g.fill(-2, 9, 3 * k, -2, 9, 3 * k);
            g.fill(2, 9, 3 * k, 2, 9, 3 * k);
        }
        return g;
    }

    /** Yaw and pitch from the eyes of a body at {@code at} to a point. */
    static float[] viewTo(MovementPhysics at, double x, double y, double z) {
        double dx = x - at.x;
        double dy = y - (at.y + 1.62);
        double dz = z - at.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new float[] {yaw, pitch};
    }

    @Test
    void theBranchTheUserLooksAtIsTheChain() {
        for (int side : new int[] {-1, 1}) {
            Grid g = fork();
            MovementPhysics at = standing(0.5, 10, 0.5);
            // the crosshair on the front face of the far block of one branch
            float[] v = viewTo(at, side * 2 + 0.5, 9.5, 18.0);
            ChainLookahead c = new ChainLookahead();
            c.look(at, v[0], v[1], g);
            assertTrue(c.gazing(), "a block far down the branch is looked at");
            settle(c, at, 0f, g);
            int last = c.length() - 1;
            assertTrue(last >= 3, "the chain runs down the branch: " + c.length());
            assertEquals(side * 2, c.linkX(last), "the chain ends on the branch looked at");
            assertTrue(c.linkZ(last) >= 12, "and far down it: z " + c.linkZ(last));
            // looking at the other branch re-picks the end without a rebuild
            float[] o = viewTo(at, -side * 2 + 0.5, 9.5, 18.0);
            c.look(at, o[0], o[1], g);
            c.update(at, 0f, g);
            assertEquals(-side * 2, c.linkX(c.length() - 1), "the end follows the gaze to the other branch");
        }
    }

    @Test
    void aBlockAtTheFeetIsNoGaze() {
        Grid g = fork();
        MovementPhysics at = standing(0.5, 10, 0.5);
        ChainLookahead c = new ChainLookahead();
        c.look(at, 0f, 80f, g); // looking down at the start block
        assertFalse(c.gazing());
        c.look(at, 0f, -30f, g); // at the sky
        assertFalse(c.gazing());
        assertTrue(c.gazeCells() <= (ChainLookahead.LENGTH + 7) * 3, "one test per visited cell");
    }

    // ---- thin shapes under the crosshair (the ray is intersected with the real boxes) -------------------------------

    @Test
    void theCrosshairOnADistantFencePostPicksIt() {
        for (int px : new int[] {0, 2, -3}) {
            ShapeGrid g = new ShapeGrid().cube(0, 9, 0).cube(px, 9, 15).fencePost(px, 10, 15);
            MovementPhysics at = standing(0.5, 10, 0.5);
            ChainLookahead c = new ChainLookahead();
            // the centre of the post (a quarter block wide, entered well outside its box)
            float[] v = viewTo(at, px + 0.5, 10.75, 15.5);
            c.look(at, v[0], v[1], g);
            assertTrue(c.gazing(), "the post at x " + px + " is looked at");
            assertEquals(px + 0.5, c.gazeX(), 1e-9);
            assertEquals(15.5, c.gazeZ(), 1e-9);
            assertEquals(11.5, c.gazeTop(), 1e-6, "the top of the post, not of the block under it");
            // the half block the post rises into the cell above its own
            float[] up = viewTo(at, px + 0.5, 11.25, 15.5);
            c.look(at, up[0], up[1], g);
            assertTrue(c.gazing(), "the part of the post above its cell is looked at");
            assertEquals(11.5, c.gazeTop(), 1e-6);
            // a hand's width beside the post: nothing
            float[] miss = viewTo(at, px + 0.9, 10.75, 15.5);
            c.look(at, miss[0], miss[1], g);
            assertFalse(c.gazing(), "beside the post is air");
        }
    }

    @Test
    void theCrosshairOnADistantBottomSlabPicksIt() {
        for (int px : new int[] {0, 3}) {
            ShapeGrid g = new ShapeGrid().cube(0, 9, 0).slabBottom(px, 9, 15);
            MovementPhysics at = standing(0.5, 10, 0.5);
            ChainLookahead c = new ChainLookahead();
            // the middle of the slab's top face: the ray enters the cell above the slab's half
            float[] v = viewTo(at, px + 0.5, 9.49, 15.5);
            c.look(at, v[0], v[1], g);
            assertTrue(c.gazing(), "the slab at x " + px + " is looked at");
            assertEquals(px + 0.5, c.gazeX(), 1e-9);
            assertEquals(15.5, c.gazeZ(), 1e-9);
            assertEquals(9.5, c.gazeTop(), 1e-6);
        }
    }

    @Test
    void theCrosshairOnADistantPaneOrWallPicksIt() {
        MovementPhysics at = standing(0.5, 10, 0.5);
        ChainLookahead c = new ChainLookahead();
        ShapeGrid pane = new ShapeGrid().cube(0, 9, 0).pane(1, 10, 20);
        float[] v = viewTo(at, 1.5, 10.5, 20.5);
        c.look(at, v[0], v[1], pane);
        assertTrue(c.gazing(), "a pane an eighth of a block wide");
        assertEquals(20.5, c.gazeZ(), 1e-9);
        ShapeGrid wall = new ShapeGrid().cube(0, 9, 0).wallPost(-2, 10, 12);
        v = viewTo(at, -1.5, 10.75, 12.5);
        c.look(at, v[0], v[1], wall);
        assertTrue(c.gazing(), "a wall post");
        assertEquals(-1.5, c.gazeX(), 1e-9);
    }

    @Test
    void lookingAtThinShapesAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        ShapeGrid g = new ShapeGrid().cube(0, 9, 0).cube(2, 9, 15).fencePost(2, 10, 15).slabBottom(-2, 9, 15);
        MovementPhysics at = standing(0.5, 10, 0.5);
        float[] v = viewTo(at, 2.5, 10.75, 15.5);
        float[] s = viewTo(at, -1.5, 9.49, 15.5);
        ChainLookahead c = new ChainLookahead();
        long bytes = AllocationProbe.measure(200, () -> {
            c.look(at, v[0], v[1], g);
            c.look(at, s[0], s[1], g);
            c.look(at, 0f, -30f, g);
        });
        assertTrue(bytes < 1_024, "look allocated " + bytes + " bytes");
        assertTrue(c.gazeCells() <= (ChainLookahead.LENGTH + 7) * 3, "cells " + c.gazeCells());
    }

    // ---- tops a chain never lands on --------------------------------------------------------------------------------

    /**
     * Blocks at z 0, 8 and 12 on the heading line with the direct stepping stone at z 4, and a detour of two blocks
     * at x 2 (z 3 and 6) that costs one jump more.
     */
    static ShapeGrid detour() {
        return new ShapeGrid().cube(0, 9, 0).cube(0, 9, 4).cube(0, 9, 8).cube(0, 9, 12).cube(2, 9, 3).cube(2, 9, 6);
    }

    static void assertRoutesAround(ChainLookahead c, String what) {
        assertEquals(5, c.length(), what + ": the chain takes the detour");
        int[] xs = {0, 2, 2, 0, 0};
        int[] zs = {0, 3, 6, 8, 12};
        for (int i = 0; i < 5; i++) {
            assertEquals(xs[i], c.linkX(i), what + ": link " + i);
            assertEquals(zs[i], c.linkZ(i), what + ": link " + i);
        }
    }

    @Test
    void theDirectStoneIsTakenWhenItIsSafe() {
        for (byte s : new byte[] {MovementPhysics.SURFACE_NORMAL, MovementPhysics.SURFACE_ICE,
                MovementPhysics.SURFACE_SLIME, MovementPhysics.SURFACE_HONEY, MovementPhysics.SURFACE_SOUL_SAND}) {
            ShapeGrid g = detour().surface(0, 9, 4, s);
            ChainLookahead c = new ChainLookahead();
            settle(c, standing(0.5, 10, 0.5), 0f, g);
            assertEquals(4, c.length(), "surface " + s + " is a link");
            assertEquals(0, c.linkX(1));
            assertEquals(4, c.linkZ(1));
        }
    }

    @Test
    void aHarmfulTrappingOrUnmodelledTopIsNoLink() {
        for (byte s : new byte[] {MovementPhysics.SURFACE_HARM, MovementPhysics.SURFACE_STUCK,
                MovementPhysics.SURFACE_UNMODELLED}) {
            ShapeGrid g = detour().surface(0, 9, 4, s); // magma, powder snow, scaffolding
            ChainLookahead c = new ChainLookahead();
            settle(c, standing(0.5, 10, 0.5), 0f, g);
            assertRoutesAround(c, "surface " + s);
        }
    }

    @Test
    void aCactusTopIsNoLink() {
        float i = 1f / 16f;
        ShapeGrid g = detour().boxes(0, 9, 4, i, 0, i, 1 - i, 1 - i, 1 - i).surface(0, 9, 4,
                MovementPhysics.SURFACE_HARM);
        ChainLookahead c = new ChainLookahead();
        settle(c, standing(0.5, 10, 0.5), 0f, g);
        assertRoutesAround(c, "cactus");
    }

    @Test
    void aTopUnderFireOrACobwebIsNoLink() {
        for (byte s : new byte[] {MovementPhysics.SURFACE_HARM, MovementPhysics.SURFACE_STUCK}) {
            for (int up = 1; up <= 2; up++) {
                // no collision box, but the body would stand in it (feet or head)
                ShapeGrid g = detour().surface(0, 9 + up, 4, s);
                ChainLookahead c = new ChainLookahead();
                settle(c, standing(0.5, 10, 0.5), 0f, g);
                assertRoutesAround(c, "surface " + s + " " + up + " above the top");
            }
        }
    }

    @Test
    void theHarmfulTopUnderTheFeetStillStartsTheChain() {
        ShapeGrid g = new ShapeGrid().cube(0, 9, 0).cube(0, 9, 4).cube(0, 9, 8)
                .surface(0, 9, 0, MovementPhysics.SURFACE_HARM); // standing on magma (sneaking, frost walker)
        ChainLookahead c = new ChainLookahead();
        settle(c, standing(0.5, 10, 0.5), 0f, g);
        assertTrue(c.ready());
        assertEquals(3, c.length(), "the way off the magma is planned");
    }

    /** A long floor with scattered pillars and steps: plenty of tops per column and plenty of choices. */
    static Grid scatter() {
        Grid g = new Grid();
        g.fill(-3, 9, -4, 3, 9, 120);
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < 160; i++) {
            int x = r.nextInt(25) - 12;
            int z = r.nextInt(120);
            int y = 6 + r.nextInt(6);
            g.fill(x, y, z, x, y, z);
        }
        for (int i = 0; i < 40; i++) {
            int px = r.nextInt(7) - 3;
            int pz = 6 + r.nextInt(110);
            g.fill(px, 10, pz, px, 10, pz);
        }
        return g;
    }

    static void assertSamePlan(ChainLookahead a, ChainLookahead b, String at) {
        assertEquals(b.ready(), a.ready(), at);
        assertEquals(b.length(), a.length(), at + " length");
        for (int i = 0; i < b.length(); i++) {
            assertEquals(b.linkX(i), a.linkX(i), at + " x" + i);
            assertEquals(b.linkZ(i), a.linkZ(i), at + " z" + i);
            assertEquals(b.linkBlockY(i), a.linkBlockY(i), at + " y" + i);
            assertEquals(b.linkTop(i), a.linkTop(i), 0.0, at + " top" + i);
            assertEquals(b.linkJump(i), a.linkJump(i), at + " jump" + i);
        }
    }

    @Test
    void anIncrementallyMovedCorridorPlansLikeAFreshOne() {
        Grid g = scatter();
        ChainLookahead inc = new ChainLookahead();
        int reusedTotal = 0;
        long incLookups = 0;
        long freshLookups = 0;
        double z = 0.5;
        double x = 0.5;
        int step = 0;
        int compared = 0;
        while (z < 70) {
            // a slow weave: heading within a few degrees, a block or two a move
            float yaw = (float) (6.0 * Math.sin(step * 0.7));
            MovementPhysics at = standing(x, 10, z);
            int restartsBefore = inc.restarts();
            long l0 = incLookups;
            int t = 0;
            do {
                inc.update(at, yaw, g);
                incLookups += inc.lastLookups();
                if (t++ == 0 && inc.restarts() != restartsBefore) {
                    reusedTotal += inc.reusedColumns();
                }
            } while (inc.busy() && t < 400);
            if (inc.restarts() != restartsBefore) { // a restart this step: it must plan like a fresh corridor
                ChainLookahead fresh = new ChainLookahead();
                t = 0;
                do {
                    fresh.update(at, yaw, g);
                    freshLookups += fresh.lastLookups();
                } while (fresh.busy() && ++t < 400);
                assertSamePlan(inc, fresh, "step " + step + " z " + z);
                compared++;
            } else {
                incLookups = l0; // no restart, no scan: not part of the comparison
            }
            step++;
            double d = 1.0 + (step % 2); // 1 and 2 blocks
            z += d * Math.cos(Math.toRadians(yaw));
            x -= d * Math.sin(Math.toRadians(yaw));
        }
        assertTrue(compared >= 10, "restarts compared " + compared);
        assertTrue(reusedTotal > 0, "columns were reused");
        assertTrue(incLookups < freshLookups, "lookups " + incLookups + " vs fresh " + freshLookups);
        System.out.println("ChainLookahead walk: incremental " + incLookups + " lookups, fresh " + freshLookups
                + ", reused columns " + reusedTotal + ", restarts compared " + compared);
    }

    @Test
    void movingTheCorridorAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        Grid g = scatter();
        ChainLookahead c = new ChainLookahead();
        MovementPhysics a = standing(0.5, 10, 0.5);
        MovementPhysics b = standing(0.5, 10, 3.5);
        MovementPhysics d = standing(1.5, 10, 6.5);
        for (int i = 0; i < 20; i++) { // warm
            c.update(a, 0f, g);
            c.update(b, 4f, g);
            c.update(d, 8f, g);
        }
        long bytes = AllocationProbe.measure(40, () -> {
            c.update(a, 0f, g);
            c.update(b, 4f, g);
            c.update(d, 8f, g);
            c.update(b, 4f, g);
        });
        assertTrue(bytes < 1_024, "update allocated " + bytes + " bytes");
    }
}
