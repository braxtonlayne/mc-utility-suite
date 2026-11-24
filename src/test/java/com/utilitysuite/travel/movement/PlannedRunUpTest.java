package com.utilitysuite.travel.movement;

import com.utilitysuite.core.api.IPlannedJump;
import com.utilitysuite.travel.movement.SupportRegionTest.ShapeGrid;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The run-up signal SafeWalk yields to ({@link MovementAssistBrain#plannedTakeoffIn()}, published through
 * {@link IPlannedJump}): every ground tick that a planned jump needs unclamped (its move leaves support, or it is one of
 * the last ticks before the takeoff) reports the takeoff within {@link IPlannedJump#RUN_UP_TICKS}, so SafeWalk never
 * clamps the run-up of a valid plan; NUDGE (no plan) and a damaging target under SafeWalk report nothing.
 */
class PlannedRunUpTest {

    /** Outcome of one run-up with the assist taking the jump. */
    private static final class Roll {
        int takeoffTick = -1;
        boolean landed;
        int[] reported = new int[400];
        boolean[] steppedOff = new boolean[400];
    }

    private static Roll roll(MovementAssistBrain b, ShapeGrid g, int tz, double topY) {
        MovementPhysics p = new MovementPhysics().set(0.5, 20, -8, 0, -0.0784, 0, true);
        Roll r = new Roll();
        java.util.Arrays.fill(r.reported, -2);
        boolean wasGround = true;
        for (int t = 0; t < 200; t++) {
            b.setIntentTarget(0, (int) Math.floor(topY - 1e-6), tz, 0.9);
            b.decide(p, 0f, p.sprinting, 1, 0, false, 20f, g);
            r.reported[t] = b.plannedTakeoffIn();
            int f = b.forward() == MovementAssistBrain.KEEP ? 1 : b.forward();
            int s = b.strafe() == MovementAssistBrain.KEEP ? 0 : b.strafe();
            boolean ground = p.onGround;
            p.tick(f, s, 0f, !b.sprintOff(), b.jump(), b.sneak(), g);
            // a ground tick whose move ended past the support without a jump: SafeWalk would have clamped it
            r.steppedOff[t] = ground && !b.jump() && !p.supported(g);
            if (wasGround && !p.onGround && r.takeoffTick < 0) {
                r.takeoffTick = t;
            }
            wasGround = p.onGround;
            if (r.takeoffTick >= 0 && p.onGround && Math.abs(p.y - topY) < 1e-6) {
                r.landed = true;
                break;
            }
            if (p.y < 10) {
                break;
            }
        }
        return r;
    }

    @Test
    void theRunUpOfAPlannedJumpIsReportedUpToTheTakeoff() {
        Assumptions.assumeTrue(ApproachControlTest.shapes(), "needs the shape-physics replica");
        StringBuilder fails = new StringBuilder();
        int checked = 0;
        for (JumpCommit.Mode mode : new JumpCommit.Mode[]{JumpCommit.Mode.GUIDE, JumpCommit.Mode.CARRY}) {
            for (int gap = 1; gap <= 3; gap++) {
                ShapeGrid g = ApproachControlTest.course(ShapeGrid.FULL, gap);
                MovementAssistBrain b = ApproachControlTest.brain(mode, false);
                b.safeOnly = true;
                Roll r = roll(b, g, 2 + gap, 20.0);
                String id = mode + " gap " + gap;
                if (r.takeoffTick < 0 || !r.landed) {
                    continue; // only the jumps the brain plans and lands are this test's subject
                }
                checked++;
                for (int t = 0; t < r.takeoffTick; t++) {
                    boolean needed = r.steppedOff[t] || r.takeoffTick - t <= 1;
                    int rep = r.reported[t];
                    if (needed && (rep < 0 || rep > IPlannedJump.RUN_UP_TICKS)) {
                        fails.append(id).append(": tick ").append(t).append(" (takeoff ").append(r.takeoffTick)
                                .append(", stepped off ").append(r.steppedOff[t]).append(") reported ").append(rep)
                                .append('\n');
                    }
                }
            }
        }
        Assumptions.assumeTrue(checked > 0, "no planned jump landed on this course");
        assertTrue(fails.length() == 0, "\n" + fails);
    }

    /**
     * A technique script that runs straight off the edge and takes off on the step-off tick (vanilla resolves Y before
     * X/Z, so that tick still ends on the ground): the step-off move itself is a ground tick that leaves support with
     * no jump, exactly the move SafeWalk would clamp. It must be reported as the run-up.
     */
    static final class StepOffScript implements TechniqueSource {
        final FlightScript s = new FlightScript();
        final MovementPhysics m = new MovementPhysics();

        @Override
        public boolean plan(MovementPhysics now, float yaw, boolean sprinting, int userForward, int userStrafe,
                            SupportRegion target, boolean yawAllowed, float maxYawDegPerTick, boolean safeOnly,
                            float health, MovementPhysics.Voxels w) {
            s.clear();
            s.name = "step-off";
            m.copyFrom(now);
            int n = 0;
            boolean took = false;
            while (n < FlightScript.MAX_TICKS && (!took || n < s.takeoffIndex + 6)) {
                boolean j = !took && m.onGround && !m.supported(w);
                if (j) {
                    took = true;
                    s.takeoffIndex = n;
                }
                s.forward[n] = 1;
                s.sprint[n] = true;
                s.jump[n] = j;
                s.yaw[n] = yaw;
                m.tick(1, 0, yaw, true, j, w);
                s.x[n] = m.x;
                s.y[n] = m.y;
                s.z[n] = m.z;
                n++;
            }
            s.length = n;
            return took;
        }

        @Override
        public FlightScript script() {
            return s;
        }

        @Override
        public int lastSimTicks() {
            return 40;
        }

        @Override
        public void reset() {
        }
    }

    @Test
    void aScriptTakingOffOnTheStepOffTickIsReportedThroughTheStepOff() {
        Assumptions.assumeTrue(ApproachControlTest.shapes(), "needs the shape-physics replica");
        StringBuilder fails = new StringBuilder();
        for (JumpCommit.Mode mode : new JumpCommit.Mode[]{JumpCommit.Mode.GUIDE, JumpCommit.Mode.CARRY}) {
            // a pane five blocks out: nothing in the approach family lands, so the technique source is asked
            ShapeGrid g = ApproachControlTest.course(ShapeGrid.PANE_POST, 5);
            MovementAssistBrain b = ApproachControlTest.brain(mode, false);
            b.safeOnly = true;
            b.setTechniques(new StepOffScript());
            MovementPhysics p = new MovementPhysics().set(0.5, 20, -8, 0, -0.0784, 0, true);
            int stepOffs = 0;
            for (int t = 0; t < 120 && p.y > 15; t++) {
                b.setIntentTarget(0, 19, 7, 0.9);
                b.decide(p, 0f, p.sprinting, 1, 0, false, 20f, g);
                int rep = b.plannedTakeoffIn();
                int f = b.forward() == MovementAssistBrain.KEEP ? 1 : b.forward();
                int s = b.strafe() == MovementAssistBrain.KEEP ? 0 : b.strafe();
                boolean ground = p.onGround;
                p.tick(f, s, 0f, !b.sprintOff(), b.jump(), b.sneak(), g);
                if (ground && !b.jump() && p.y >= 20 - 1e-6 && !p.supported(g)) {
                    stepOffs++;
                    if (rep < 0 || rep > IPlannedJump.RUN_UP_TICKS) {
                        fails.append(mode).append(": step-off tick ").append(t).append(" reported ").append(rep)
                                .append(" (script ").append(b.scriptActive()).append(")\n");
                    }
                }
                if (!p.onGround && !ground) {
                    break; // airborne after the takeoff: the run-up is over
                }
            }
            if (stepOffs == 0) {
                fails.append(mode).append(": the script never stepped off before its takeoff\n");
            }
        }
        assertTrue(fails.length() == 0, "\n" + fails);
    }

    @Test
    void nudgeHasNoPlanToReport() {
        Assumptions.assumeTrue(ApproachControlTest.shapes(), "needs the shape-physics replica");
        MovementAssistBrain b = ApproachControlTest.brain(JumpCommit.Mode.NUDGE, false);
        Roll r = roll(b, ApproachControlTest.course(ShapeGrid.FULL, 2), 4, 20.0);
        for (int t = 0; t < r.reported.length && r.reported[t] != -2; t++) {
            assertEquals(-1, r.reported[t], "tick " + t);
        }
    }

    @Test
    void aDamagingTargetUnderSafeWalkIsNoRunUp() {
        Assumptions.assumeTrue(ApproachControlTest.shapes(), "needs the shape-physics replica");
        // the target top 6 blocks below the run-up: SafeWalk holds this edge, so no run-up is reported
        ShapeGrid g = new ShapeGrid().fill(-3, 19, -12, 3, 19, 1, ShapeGrid.FULL).put(0, 13, 4, ShapeGrid.FULL);
        g.fill(-6, 5, -12, 6, 5, 14, ShapeGrid.FULL);
        MovementAssistBrain b = ApproachControlTest.brain(JumpCommit.Mode.CARRY, false);
        b.safeOnly = true;
        Roll r = roll(b, g, 4, 14.0);
        for (int t = 0; t < r.reported.length && r.reported[t] != -2; t++) {
            assertFalse(r.reported[t] >= 0, "tick " + t + " reported " + r.reported[t]);
        }
    }

    @Test
    void theContractDefaultIsTheRunUpWindow() {
        assertFalse(IPlannedJump.NONE.runningUpToPlannedJump());
        assertFalse(IPlannedJump.NONE.jumpingThisTick());
        IPlannedJump at = new IPlannedJump() {
            int t;

            @Override
            public boolean jumpingThisTick() {
                return false;
            }

            @Override
            public int ticksToPlannedTakeoff() {
                return t++;
            }
        };
        for (int i = 0; i <= IPlannedJump.RUN_UP_TICKS; i++) {
            assertTrue(at.runningUpToPlannedJump(), "takeoff in " + i);
        }
        assertFalse(at.runningUpToPlannedJump());
    }
}
