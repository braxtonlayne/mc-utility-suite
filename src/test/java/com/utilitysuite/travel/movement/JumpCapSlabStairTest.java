package com.utilitysuite.travel.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code .jumpcap} of 2026-11-26, "slab stair jump" (rebuilt block for block from
 * {@code src/test/resources/jumpcap/slab-stair-headhitter.json}): from the back edge of a 1x1 plank to the top step of a
 * stair 3 east and 2 south, at the same height, with a bottom slab floating at body height (y + 1 .. 1.5) over the
 * straight line. Movement Assist found nothing. A brute force showed the jump lands from the user's own view with keys
 * alone: strafe out round the slab's south-west corner over the last ground ticks, take off late (the step-off tick),
 * strafe back in, and come down on the stair's low step, which the landing tick's step-up turns into its top. Two things
 * stopped the planners: they gave up on a roll the moment it fell below the top (before the step-up), and the neo with
 * the view was timed from the straight run's edge although a run strafing out stays on the plank longer.
 */
class JumpCapSlabStairTest {

    private static final JumpCapScene SCENE = JumpCapScene.load("slab-stair-headhitter.json");

    /** The next tick of W + sprint along {@code yaw} leaves the support. */
    static boolean atEdge(MovementPhysics probe, MovementPhysics p, float yaw, MovementPhysics.Voxels g) {
        probe.copyFrom(p).tick(1, 0, yaw, true, false, false, g);
        return !probe.supported(g);
    }

    private static SupportRegion stairTop() {
        return TechniqueLibraryTest.top(SCENE.grid, 3, 19, 3);
    }

    @Test
    void theCaptureRebuildsTheScene() {
        assertTrue(SCENE.grid.solid(0, 19, 1), "the plank under the takeoff");
        assertTrue(Math.abs(SCENE.x - 0.15) < 0.01 && Math.abs(SCENE.z - 0.79) < 0.01 && SCENE.y == 20.0,
                "player at " + SCENE.x + "," + SCENE.y + "," + SCENE.z);
        assertFalse(TechniqueLibraryTest.plainLands(SCENE.grid, stairTop(), SCENE.x, SCENE.z),
                "the straight line runs into the slab");
    }

    @Test
    void theLibraryFindsItOnTheCapturedRunUp() {
        TechniqueLibraryTest.Run r = TechniqueLibraryTest.search(SCENE.grid, stairTop(), SCENE.x, SCENE.y, SCENE.z,
                true, TechniqueLibraryTest.MAX_CALLS, true);
        assertTrue(r.found(), "no script in " + r.calls() + " calls");
        TechniqueLibraryTest.verify(r, SCENE.grid, true);
    }

    @Test
    void coPilotAndTakeoverLandItInTheFullLoop() {
        SupportRegion top = stairTop();
        MovementPhysics probe = new MovementPhysics();
        StringBuilder out = new StringBuilder();
        for (JumpCommit.Mode mode : new JumpCommit.Mode[] {JumpCommit.Mode.GUIDE, JumpCommit.Mode.CARRY}) {
            for (float off : new float[] {0f, -5f}) {
                float yaw0 = SCENE.yaw + off;
                // the user runs along the view and presses jump at the plank's edge
                String r = CourseNeoIntentTest.loopIn(SCENE.grid, top, mode, SCENE.x, SCENE.z, 20f, yaw0, true, null,
                        null, p -> atEdge(probe, p, yaw0, SCENE.grid));
                if (!r.equals("landed")) {
                    out.append(String.format(" [%s view %+.0f: %s]", mode, off, r));
                }
            }
        }
        assertTrue(out.isEmpty(), out.toString());
    }
}
