package com.utilitysuite.travel.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact collision shapes in the replica and the sneak edge guard, against hand-computed values. */
class ShapePhysicsTest {

    private static final double STEADY_VY = -0.0784;

    /** Drops a still body from 3 blocks onto the cell's top and returns where it comes to rest. */
    private static MovementPhysics drop(ShapeGrid g) {
        MovementPhysics p = new MovementPhysics().set(0.5, 3.0, 0.5, 0, 0, 0, false);
        for (int t = 0; t < 40 && !p.onGround; t++) {
            p.tick(0, 0, 0f, false, false, g);
        }
        assertTrue(p.onGround, "landed");
        return p;
    }

    @Test
    void landingHeightOnAFencePostIsOneAndAHalf() {
        MovementPhysics p = drop(new ShapeGrid().fencePost(0, 0, 0));
        assertEquals(1.5, p.y, 1e-9);
        assertTrue(p.supported(new ShapeGrid().fencePost(0, 0, 0)));
    }

    @Test
    void landingHeightsOnWallPaneAndSlab() {
        assertEquals(1.5, drop(new ShapeGrid().wallPost(0, 0, 0)).y, 1e-9, "wall post");
        assertEquals(1.5, drop(new ShapeGrid().wallAlongZ(0, 0, 0)).y, 1e-9, "wall side");
        assertEquals(1.0, drop(new ShapeGrid().pane(0, 0, 0)).y, 1e-9, "pane");
        assertEquals(0.5, drop(new ShapeGrid().slabBottom(0, 0, 0)).y, 1e-9, "bottom slab");
        assertEquals(1.5, drop(new ShapeGrid().fence(0, 0, 0, true, true, false, false)).y, 1e-9, "fence with arms");
    }

    @Test
    void aBodyNextToANarrowPostFallsPastIt() {
        // body centre 0.45 from the post centre: 0.3 half-width + 0.125 half-post = 0.425 is the support limit
        ShapeGrid g = new ShapeGrid().fencePost(0, 0, 0);
        MovementPhysics p = new MovementPhysics().set(0.95, 3.0, 0.5, 0, 0, 0, false);
        for (int t = 0; t < 30; t++) {
            p.tick(0, 0, 0f, false, false, g);
        }
        assertTrue(p.y < 1.0, "missed the post, y=" + p.y);
        MovementPhysics q = new MovementPhysics().set(0.9, 3.0, 0.5, 0, 0, 0, false);
        for (int t = 0; t < 30; t++) {
            q.tick(0, 0, 0f, false, false, g);
        }
        assertEquals(1.5, q.y, 1e-9, "0.4 from the centre still lands");
    }

    @Test
    void aBoxRisingAboveItsCellBlocksFromTheCellAbove() {
        // the post lives in cell y=0 but reaches 1.5; a body flying level at y=1.2 must hit it
        ShapeGrid g = new ShapeGrid().fencePost(0, 0, 0);
        MovementPhysics p = new MovementPhysics().set(0.5, 1.2, -0.5, 0, 0, 0.7, false);
        p.tick(0, 0, 0f, false, false, g);
        assertEquals(0.375 - 0.3, p.z, 1e-12, "stopped at the post's face");
        assertTrue(p.horizontalCollision);
        assertEquals(0.0, p.vz, 0.0);
        assertFalse(p.onGround);
    }

    @Test
    void trapdoorCeilingClipsTheJump() {
        ShapeGrid g = new ShapeGrid().fill(-2, 0, -2, 2, 0, 2).trapdoorTop(0, 2, 0);
        MovementPhysics p = new MovementPhysics().set(0.5, 1.0, 0.5, 0, STEADY_VY, 0, true);
        p.tick(0, 0, 0f, false, true, g);
        assertEquals(1.0125, p.y, 1e-12, "head 1.8 above the feet stops at 2.8125");
        assertEquals(-0.08 * (double) 0.98f, p.vy, 1e-12, "vertical speed zeroed by the hit, then gravity (0.98F)");
        double max = p.y;
        for (int t = 0; t < 10; t++) {
            p.tick(0, 0, 0f, false, false, g);
            max = Math.max(max, p.y);
        }
        assertEquals(1.0125, max, 1e-12);
        assertEquals(1.0, p.y, 1e-9);
        assertTrue(p.onGround);
    }

    /** Body standing centred on a lone fence post, holding forward for 100 ticks. */
    private static MovementPhysics walkOnPost(boolean sneak, float yaw, ShapeGrid g, boolean[] leftTop) {
        MovementPhysics p = new MovementPhysics().set(0.5, 1.5, 0.5, 0, STEADY_VY, 0, true);
        for (int t = 0; t < 100; t++) {
            p.tick(1, 0, yaw, false, false, sneak, g);
            if (p.y != 1.5) {
                leftTop[0] = true;
            }
        }
        return p;
    }

    @Test
    void sneakingOnAFencePostWithForwardHeldNeverLeavesTheTop() {
        ShapeGrid g = new ShapeGrid().fencePost(0, 0, 0);
        for (float yaw : new float[]{0f, 90f, 180f, -90f, 37f, 135f}) {
            boolean[] left = {false};
            MovementPhysics p = walkOnPost(true, yaw, g, left);
            assertFalse(left[0], "yaw " + yaw);
            assertEquals(1.5, p.y, 1e-9);
            assertTrue(p.supported(g), "yaw " + yaw);
            assertTrue(Math.abs(p.x - 0.5) <= 0.425 && Math.abs(p.z - 0.5) <= 0.425, "yaw " + yaw);
            assertTrue(p.crouching);
        }
    }

    @Test
    void withoutSneakTheBodyWalksOffTheFencePost() {
        ShapeGrid g = new ShapeGrid().fencePost(0, 0, 0);
        boolean[] left = {false};
        MovementPhysics p = walkOnPost(false, 0f, g, left);
        assertTrue(left[0]);
        assertTrue(p.y < 0, "fell to the void, y=" + p.y);
    }

    @Test
    void sneakGuardsWallAndPaneTopsToo() {
        for (ShapeGrid g : new ShapeGrid[]{new ShapeGrid().wallPost(0, 0, 0), new ShapeGrid().wallAlongZ(0, 0, 0)}) {
            boolean[] left = {false};
            walkOnPost(true, 90f, g, left);
            assertFalse(left[0]);
        }
        ShapeGrid pane = new ShapeGrid().pane(0, 0, 0);
        MovementPhysics p = new MovementPhysics().set(0.5, 1.0, 0.5, 0, STEADY_VY, 0, true);
        for (int t = 0; t < 100; t++) {
            p.tick(1, 1, 20f, false, false, true, pane);
            assertEquals(1.0, p.y, 1e-9, "tick " + t);
        }
    }

    @Test
    void touchdownTickIsNotGuarded() {
        // airborne over the post's far edge, moving on: the landing tick resolves Y, then slides past the support
        ShapeGrid g = new ShapeGrid().fencePost(0, 0, 0);
        MovementPhysics sneak = new MovementPhysics().set(0.5, 1.8, 0.75, 0, -0.4, 0.3, false);
        sneak.fallDistance = 1.0;
        MovementPhysics plain = new MovementPhysics().copyFrom(sneak);
        sneak.tick(0, 0, 0f, false, false, true, g);
        plain.tick(0, 0, 0f, false, false, false, g);
        assertEquals(1.5, sneak.y, 1e-9);
        assertTrue(sneak.onGround);
        assertEquals(1.05, sneak.z, 1e-12, "no back-off on the touchdown tick");
        assertEquals(plain.z, sneak.z, 0.0);
        assertEquals(plain.vz, sneak.vz, 0.0);
        assertFalse(sneak.supported(g), "the end of the touchdown tick is off the support region");
        for (int t = 0; t < 3; t++) {
            sneak.tick(0, 0, 0f, false, false, true, g);
        }
        assertTrue(sneak.y < 1.5, "falls although sneak is held");
    }

    @Test
    void backOffStartsTheTickAfterTouchdown() {
        // same landing, but ending inside the support region: the next tick's slide is backed off at the edge
        ShapeGrid g = new ShapeGrid().fencePost(0, 0, 0);
        MovementPhysics p = new MovementPhysics().set(0.5, 1.8, 0.4, 0, -0.4, 0.3, false);
        p.fallDistance = 1.0;
        p.tick(0, 0, 0f, false, false, true, g);
        assertEquals(0.7, p.z, 1e-12);
        assertTrue(p.supported(g));
        for (int t = 0; t < 40; t++) {
            p.tick(0, 0, 0f, false, false, true, g);
            assertEquals(1.5, p.y, 1e-9, "tick " + t);
        }
        assertTrue(p.z <= 0.925, "z=" + p.z);
    }

    @Test
    void lowFallWithGroundWithinStepHeightIsGuardedLikeVanilla() {
        // Player.isAboveGround: fallDistance < 0.6 and ground within 0.6 - fallDistance counts as above ground
        ShapeGrid g = new ShapeGrid().fill(-2, 0, -2, 0, 0, 2);
        MovementPhysics p = new MovementPhysics().set(0.5, 1.1, 0.5, 1.0, -0.05, 0, false);
        p.fallDistance = 0.1;
        MovementPhysics plain = new MovementPhysics().copyFrom(p);
        p.tick(0, 0, 0f, false, false, true, g);
        plain.tick(0, 0, 0f, false, false, false, g);
        // 1.0 - 4 * 0.05 is 0.7999999999999998 in doubles, so the shifted box (min x 0.9999999999999998) still overlaps
        // the floor that ends at 1.0 and vanilla stops one step early, exactly like this
        assertEquals(1.3, p.x, 1e-9, "backed off in 0.05 steps until the body keeps ground under it");
        assertEquals(1.5, plain.x, 1e-9);
        // a long fall is not guarded
        MovementPhysics high = new MovementPhysics().set(0.5, 1.1, 0.5, 1.0, -0.05, 0, false);
        high.fallDistance = 0.7;
        high.tick(0, 0, 0f, false, false, true, g);
        assertEquals(1.5, high.x, 1e-9);
    }

    // ---- course landing kinds --------------------------------------------------------------------------------------

    @Test
    void standardCourseStaysACubeGrid() {
        assertFalse(ParkourCourse.standard().shaped());
    }

    @Test
    void edgeCasesCoverTheBrief() {
        String[] required = {"2 gap onto a fence post", "3 gap onto a wall", "corner onto a block",
                "corner onto a fence", "1 gap onto a pane", "slab up"};
        for (String name : required) {
            assertTrue(ParkourCourse.EDGE_CASES.stream().anyMatch(s -> s.name().equals(name)), name);
        }
    }

    @Test
    void fencePostCourseAnswersVanillaBoxesAndPads() {
        ParkourCourse c = ParkourCourse.edgeCase(0); // 2 gap onto a fence post
        assertTrue(c.shaped());
        assertEquals(ParkourCourse.Landing.FENCE, c.padLanding(1));
        assertEquals(1.5, c.padTop(1), 0.0);
        int z = ParkourCourse.RUNUP + 2;
        assertEquals(1, c.boxCount(0, 0, z), "lone post");
        float[] b = new float[6];
        c.box(0, 0, z, 0, b, 0);
        assertEquals(0.375f, b[0]);
        assertEquals(1.5f, b[4]);
        assertEquals(0.625f, b[5]);
        assertFalse(c.fullBlock(0, 0, z));
        assertTrue(c.fullBlock(0, 0, 0), "runway stays cubes");
        assertEquals(1, c.padAt(0.5, 1.5, z + 0.5));
        assertEquals(-1, c.padAt(0.5, 1.0, z + 0.5));
        MovementPhysics p = new MovementPhysics().set(0.5, 3, z + 0.5, 0, 0, 0, false);
        for (int t = 0; t < 30; t++) {
            p.tick(0, 0, 0f, false, false, c);
        }
        assertEquals(1.5, p.y, 1e-9);
    }

    @Test
    void connectedRowsUseArmsAndWallsDropTheMiddlePost() {
        ParkourCourse fences = ParkourCourse.edgeCase(1); // fence row of 3
        int z0 = ParkourCourse.RUNUP + 2;
        assertEquals(2, fences.boxCount(0, 0, z0), "end post + one arm");
        assertEquals(3, fences.boxCount(0, 0, z0 + 1), "middle post + two arms");
        ParkourCourse walls = new ParkourCourse(java.util.List.of(
                new ParkourCourse.Segment("wall row", 2, 0, 0, 1, 3, 0, ParkourCourse.Landing.WALL)));
        assertEquals(2, walls.boxCount(0, 0, z0 + 1), "straight wall: two sides, no post");
        float[] b = new float[6];
        walls.box(0, 0, z0 + 1, 0, b, 0);
        assertEquals(5f / 16f, b[0]);
        assertEquals(11f / 16f, b[3]);
        ParkourCourse slab = ParkourCourse.edgeCase(ParkourCourse.EDGE_CASES.size() - 1);
        assertEquals(ParkourCourse.Landing.SLAB_BOTTOM, slab.padLanding(1));
        assertEquals(1.5, slab.padTop(1), 0.0, "slab one cell up: +0.5 over the run-up");
        assertNotNull(slab.landingAt(0, 1, ParkourCourse.RUNUP + 1));
    }
}
