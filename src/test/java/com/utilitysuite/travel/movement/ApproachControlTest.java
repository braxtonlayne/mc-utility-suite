package com.utilitysuite.travel.movement;

import com.utilitysuite.travel.movement.SupportRegionTest.ShapeGrid;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Approach and takeoff control ({@link ApproachController} in {@link MovementAssistBrain}): the in-game lane case. A
 * player sprints at a narrow top with W + sprint held throughout and presses jump for one tick on the ground 0.15
 * before the edge (or never, and the assist takes the jump). In GUIDE and CARRY the body must land on the real top
 * and still be there 40 ticks later with W held; NUDGE may miss, but a landing it makes must hold.
 */
@Tag("needs-shape-physics")
class ApproachControlTest {

    static final int STAY_TICKS = 40;
    /** Run-up edge (the last run-up block is z = 1) and the lane's jump trigger 0.15 before it. */
    static final double EDGE_Z = 2.0;
    static final double TRIGGER_Z = EDGE_Z - 0.15;

    /** Run-up floor at y = 19 for z <= 1, a target block {@code gap} blocks past the edge, a floor far below. */
    static ShapeGrid course(float[] target, int gap) {
        ShapeGrid g = new ShapeGrid().fill(-3, 19, -12, 3, 19, 1, ShapeGrid.FULL).put(0, 19, 2 + gap, target);
        g.fill(-6, 5, -12, 6, 5, 14, ShapeGrid.FULL);
        return g;
    }

    /** Outcome of one roll. */
    static final class Run {
        int stayed = -1; // ticks on top after landing (STAY_TICKS = held), -1 never landed
        int maxCommitSim;
        boolean userPressed;
        double jumpZ = Double.NaN;
        double maxZ = -1e9;
        String trace = "";
        boolean aborted;
    }

    static MovementAssistBrain brain(JumpCommit.Mode mode, boolean walk) {
        MovementAssistBrain b = new MovementAssistBrain();
        b.mode = mode;
        b.landingHold = true;
        b.sprintWithholdAvailable = walk;
        return b;
    }

    /**
     * @param userJumps the user presses jump for one tick at the trigger; otherwise the assist takes the jump
     */
    static Run roll(MovementAssistBrain b, ShapeGrid g, int gap, boolean userJumps) {
        int tz = 2 + gap;
        // the target's own top only: a pane level with the run-up must not count the platform edge as the target
        SupportRegion top = new SupportRegion();
        double topY = top.topOf(g, 0, 19, tz);
        double b0 = top.cellTopBound(0), b1 = top.cellTopBound(1), b2 = top.cellTopBound(2), b3 = top.cellTopBound(3);
        top.clear();
        top.setTop(topY);
        top.addTopBox(b0, b1, b2, b3);
        MovementPhysics p = new MovementPhysics().set(0.5, 20, -8, 0, -0.0784, 0, true);
        Run r = new Run();
        StringBuilder tr = new StringBuilder();
        int landedAt = -1;
        float yaw = 0f;
        boolean wasGround = true;
        for (int t = 0; t < 400; t++) {
            double dx = 0.5 - p.x;
            double dz = tz + 0.5 - p.z;
            if (dx * dx + dz * dz > 1.0) {
                yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            }
            // the lane script: one press on the run-up (never again after any takeoff, the assist's included)
            boolean uj = userJumps && !r.userPressed && Double.isNaN(r.jumpZ) && p.onGround && p.z >= TRIGGER_Z;
            r.userPressed |= uj;
            b.setIntentTarget(0, 19, tz, 0.9);
            b.decide(p, yaw, p.sprinting, 1, 0, uj, 20f, g);
            r.maxCommitSim = Math.max(r.maxCommitSim, b.lastCommitSimTicks());
            r.aborted |= b.commit().phase() == JumpCommit.Phase.ABORT;
            int f = b.forward() == MovementAssistBrain.KEEP ? 1 : b.forward();
            int s = b.strafe() == MovementAssistBrain.KEEP ? 0 : b.strafe();
            boolean jump = (uj && !b.withholdJump()) || b.jump();
            if (tr.length() < 3000 && p.z > 1.0 && (landedAt < 0 || t - landedAt < 4) && p.y > 17) {
                ApproachController a = b.approach();
                tr.append(String.format("[%d z%.2f y%.2f f%d%s%s%s %s e%s c%d tt%d n%d s%d fm%.2f]", t, p.z, p.y, f,
                        jump ? " J" : "", b.withholdJump() ? " W" : "", b.sprintOff() ? " S0" : "",
                        b.commit().phase(), a.engaged() ? "1" : "0", a.planCost(), a.ticksToTakeoff(), a.evaluated(),
                        a.simTicks(), b.flight().found() ? b.flight().bestMargin() : -9.0) + (b.sneak() ? " SN" : "") + " L" + b.hold().latched() + " g" + p.onGround + String.format(" x%.2f vz%.3f", p.x, p.vz));
            }
            p.tick(f, s, yaw, !b.sprintOff(), jump, b.sneak(), g);
            if (wasGround && !p.onGround && Double.isNaN(r.jumpZ)) {
                r.jumpZ = p.z;
            }
            wasGround = p.onGround;
            r.maxZ = Math.max(r.maxZ, p.z);
            boolean onTop = p.onGround && Math.abs(p.y - topY) < 1e-6 && top.contains(p.x, p.z);
            if (landedAt < 0) {
                if (onTop) {
                    landedAt = t;
                } else if (p.y < 15) {
                    break;
                }
            } else if (!onTop) {
                r.stayed = t - landedAt;
                break;
            } else if (t - landedAt >= STAY_TICKS) {
                r.stayed = STAY_TICKS;
                break;
            }
        }
        if (landedAt >= 0 && r.stayed == -1) {
            r.stayed = STAY_TICKS;
        }
        r.trace = String.format("jumpZ %.2f maxZ %.2f %s", r.jumpZ, r.maxZ, tr);
        return r;
    }

    static boolean shapes() {
        return LandingJointTest.replicaHonoursShapes();
    }

    private static final Object[][] TARGETS = {
            {ShapeGrid.FENCE_POST, "fence", new int[]{1, 2, 3}},
            {ShapeGrid.WALL_POST, "wall", new int[]{1, 2, 3}},
            {ShapeGrid.PANE_POST, "pane", new int[]{1, 2}},
    };

    private static void matrix(boolean userJumps, boolean walk) {
        matrix(userJumps, walk, true);
    }

    private static void matrix(boolean userJumps, boolean walk, boolean edgeJump) {
        Assumptions.assumeTrue(shapes(), "needs the shape-physics replica");
        StringBuilder fails = new StringBuilder();
        for (Object[] tg : TARGETS) {
            float[] shape = (float[]) tg[0];
            for (int gap : (int[]) tg[2]) {
                for (JumpCommit.Mode mode : JumpCommit.Mode.values()) {
                    MovementAssistBrain b = brain(mode, walk);
                    b.edgeJump = edgeJump;
                    Run r = roll(b, course(shape, gap), gap, userJumps);
                    String id = tg[1] + " gap " + gap + " " + mode + (walk ? " walk" : "")
                            + (edgeJump ? "" : " no-edge-jump");
                    if (userJumps && !edgeJump && !r.userPressed) {
                        fails.append(id).append(": the user's press never came\n");
                    }
                    if (r.maxCommitSim > MovementAssistBrain.MAX_COMMIT_SIM_TICKS) {
                        fails.append(id).append(": commit sim ").append(r.maxCommitSim).append('\n');
                    }
                    if (mode == JumpCommit.Mode.NUDGE) {
                        if (r.stayed != -1 && r.stayed != STAY_TICKS) {
                            fails.append(id).append(": walked off after ").append(r.stayed).append('\n');
                        }
                        continue;
                    }
                    if (r.stayed != STAY_TICKS) {
                        fails.append(id).append(": stayed ").append(r.stayed).append(' ').append(r.trace)
                                .append('\n');
                    }
                }
            }
        }
        assertTrue(fails.length() == 0, "\n" + fails);
    }

    @Test
    void userJumpAtTheTriggerKeysOnly() {
        matrix(true, false);
    }

    @Test
    void userJumpAtTheTriggerWithSprintWithhold() {
        matrix(true, true);
    }

    /** Edge jump off: the takeoff is the user's own press, which the assist may only hold back a few ticks. */
    @Test
    void userJumpOnlyKeysOnly() {
        matrix(true, false, false);
    }

    @Test
    void userJumpOnlyWithSprintWithhold() {
        matrix(true, true, false);
    }

    @Test
    void assistJumpKeysOnly() {
        matrix(false, false);
    }

    @Test
    void assistJumpWithSprintWithhold() {
        matrix(false, true);
    }
}
