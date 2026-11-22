package com.utilitysuite.travel.movement;

import com.utilitysuite.travel.movement.SupportRegionTest.ShapeGrid;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full rolls of a sloppy player (sprint + W held through the landing, never releases it) onto narrow shaped tops, in
 * every control mode: once on top, the body stays there for 40 ticks. Needs the shape-physics replica (shaped
 * clipping, sneak edge back-off); the probe below skips these cleanly on a cube-only replica.
 */
@Tag("needs-shape-physics")
class LandingJointTest {

    static final int STAY_TICKS = 40;

    /** True when the replica lands a body on a fence post's real top (1.5) instead of the cube top. */
    static boolean replicaHonoursShapes() {
        ShapeGrid g = new ShapeGrid().put(0, 19, 0, ShapeGrid.FENCE_POST);
        MovementPhysics p = new MovementPhysics().set(0.5, 22.0, 0.5, 0, 0, 0, false);
        for (int t = 0; t < 40 && !p.onGround; t++) {
            p.tick(0, 0, 0f, false, false, false, g);
        }
        return p.onGround && Math.abs(p.y - 20.5) < 1e-6;
    }

    /** Run-up floor at y = 19 for z <= 1, a target block at (tx, 19, tz), a floor far below. */
    private static ShapeGrid course(float[] target, int tx, int tz) {
        ShapeGrid g = new ShapeGrid().fill(-3, 19, -12, 3, 19, 1, ShapeGrid.FULL).put(tx, 19, tz, target);
        g.fill(-6, 5, -12, 6, 5, 12, ShapeGrid.FULL);
        return g;
    }

    /**
     * Runs the sloppy player at the target; returns the ticks spent on the target's top after first landing on it
     * (STAY_TICKS when it stayed the whole time), or -1 when it never landed on it.
     */
    private static int roll(JumpCommit.Mode mode, ShapeGrid g, int tx, int tz) {
        SupportRegion top = new SupportRegion();
        double topY = top.topOf(g, tx, 19, tz);
        top.build(g, tx + 0.5, topY, tz + 0.5);
        MovementAssistBrain b = new MovementAssistBrain();
        b.mode = mode;
        b.landingHold = true;
        MovementPhysics p = new MovementPhysics().set(0.5, 20, -8, 0, -0.0784, 0, true);
        int landedAt = -1;
        float yaw = 0f;
        for (int t = 0; t < 400; t++) {
            double dx = tx + 0.5 - p.x;
            double dz = tz + 0.5 - p.z;
            if (dx * dx + dz * dz > 1.0) {
                // aim at the target on the way in; within a block the head stays put (an aim recomputed on top of
                // the target flips 180 degrees the moment the body passes its centre, which no player does)
                yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            }
            b.setIntentTarget(tx, 19, tz, 0.9);
            b.decide(p, yaw, true, 1, 0, false, 20f, g);
            int f = b.forward() == MovementAssistBrain.KEEP ? 1 : b.forward();
            int s = b.strafe() == MovementAssistBrain.KEEP ? 0 : b.strafe();
            p.tick(f, s, yaw, f > 0, b.jump(), b.sneak(), g);
            boolean onTop = p.onGround && Math.abs(p.y - topY) < 1e-6 && top.contains(p.x, p.z);
            if (landedAt < 0) {
                if (onTop) {
                    landedAt = t;
                } else if (p.y < 15) {
                    return -1;
                }
            } else if (!onTop) {
                return t - landedAt;
            } else if (t - landedAt >= STAY_TICKS) {
                return STAY_TICKS;
            }
        }
        return landedAt < 0 ? -1 : STAY_TICKS;
    }

    private static void staysOnTop(float[] shape, String name) {
        staysOnTop(shape, name, false);
    }

    /**
     * @param nudgeMayMiss the top is level with the takeoff and narrow, so a held sprint jump overflies it unless the
     *                     flight is braked; Corrections (NUDGE) has no air brake by design, so there only "a landing
     *                     that was made holds" is asserted
     */
    private static void staysOnTop(float[] shape, String name, boolean nudgeMayMiss) {
        Assumptions.assumeTrue(replicaHonoursShapes(), "needs the shape-physics replica");
        for (JumpCommit.Mode mode : JumpCommit.Mode.values()) {
            int stayed = roll(mode, course(shape, 0, 4), 0, 4);
            if (stayed == -1 && nudgeMayMiss && mode == JumpCommit.Mode.NUDGE) {
                continue;
            }
            assertTrue(stayed != -1, mode + ": never landed on the " + name);
            assertTrue(stayed == STAY_TICKS, mode + ": walked off the " + name + " after " + stayed + " ticks");
        }
    }

    @Test
    void fencePost() {
        staysOnTop(ShapeGrid.FENCE_POST, "fence post");
    }

    @Test
    void wall() {
        staysOnTop(ShapeGrid.WALL_POST, "wall");
    }

    @Test
    void pane() {
        staysOnTop(ShapeGrid.PANE_POST, "pane", true);
    }

    @Test
    void cornerJumpOntoAFence() {
        Assumptions.assumeTrue(replicaHonoursShapes(), "needs the shape-physics replica");
        for (JumpCommit.Mode mode : JumpCommit.Mode.values()) {
            // a column beside the gap that the straight line to the post clips: the corner
            ShapeGrid g = course(ShapeGrid.FENCE_POST, 1, 4).fill(1, 20, 2, 1, 21, 2, ShapeGrid.FULL);
            int stayed = roll(mode, g, 1, 4);
            // landing a corner is the technique library's job (a later volley); a landing that was made must hold
            assertTrue(stayed == -1 || stayed == STAY_TICKS, mode + ": walked off the fence after " + stayed);
        }
    }
}
