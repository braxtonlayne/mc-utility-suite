package com.utilitysuite.travel.movement;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.travel.movement.SupportRegionTest.ShapeGrid;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MovementAssistBrain#decide} with approach control engaged (the lane run-up, the user's press at the trigger,
 * the flight and the landing) allocates nothing after warm-up, in every mode, with and without sprint withhold, and
 * with a technique script being replayed.
 */
class ApproachControlAllocationTest {

    private static final class Frame {
        final MovementPhysics body = new MovementPhysics();
        boolean jump;
    }

    /** Records a user-jump lane roll's body states (the decisions are then replayed on a fresh brain). */
    private static Frame[] record(JumpCommit.Mode mode, ShapeGrid g, int tz, boolean walk) {
        MovementAssistBrain d = ApproachControlTest.brain(mode, walk);
        d.edgeJump = false;
        Frame[] frames = new Frame[90];
        MovementPhysics p = new MovementPhysics().set(0.5, 20, -8, 0, -0.0784, 0, true);
        boolean pressed = false;
        for (int t = 0; t < frames.length; t++) {
            Frame fr = new Frame();
            fr.body.copyFrom(p);
            boolean uj = !pressed && p.onGround && p.z >= ApproachControlTest.TRIGGER_Z;
            pressed |= uj;
            fr.jump = uj;
            frames[t] = fr;
            d.setIntentTarget(0, 19, tz, 0.9);
            d.decide(p, 0f, p.sprinting, 1, 0, uj, 20f, g);
            int f = d.forward() == MovementAssistBrain.KEEP ? 1 : d.forward();
            int s = d.strafe() == MovementAssistBrain.KEEP ? 0 : d.strafe();
            p.tick(f, s, 0f, !d.sprintOff(), (uj && !d.withholdJump()) || d.jump(), d.sneak(), g);
        }
        return frames;
    }

    @Test
    void decideWithApproachControlAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        Assumptions.assumeTrue(ApproachControlTest.shapes(), "needs the shape-physics replica");
        ShapeGrid g = ApproachControlTest.course(ShapeGrid.PANE_POST, 1);
        for (JumpCommit.Mode mode : JumpCommit.Mode.values()) {
            for (boolean walk : new boolean[]{false, true}) {
                Frame[] frames = record(mode, g, 3, walk);
                MovementAssistBrain b = ApproachControlTest.brain(mode, walk);
                b.edgeJump = false;
                b.coSteerAllowed = true;
                int[] i = new int[1];
                long bytes = AllocationProbe.measure(600, () -> {
                    Frame fr = frames[i[0]++ % frames.length];
                    b.setIntentTarget(0, 19, 3, 0.9);
                    b.decide(fr.body, 0f, fr.body.sprinting, 1, 0, fr.jump, 20f, g);
                });
                assertTrue(bytes < 1_024, mode + (walk ? " walk" : "") + ": decide allocated " + bytes + " bytes");
            }
        }
    }

    @Test
    void replayingATechniqueScriptAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        Assumptions.assumeTrue(ApproachControlTest.shapes(), "needs the shape-physics replica");
        ShapeGrid g = ApproachControlTest.course(ShapeGrid.PANE_POST, 5);
        MovementAssistBrain b = ApproachControlTest.brain(JumpCommit.Mode.CARRY, false);
        b.setTechniques(new ApproachControlTest.FakeTechniques());
        MovementPhysics[] states = new MovementPhysics[60];
        MovementPhysics p = new MovementPhysics().set(0.5, 20, -8, 0, -0.0784, 0, true);
        for (int t = 0; t < states.length; t++) {
            states[t] = new MovementPhysics().copyFrom(p);
            p.tick(1, 0, 0f, true, false, g);
        }
        int[] i = new int[1];
        long bytes = AllocationProbe.measure(600, () -> {
            MovementPhysics s = states[i[0]++ % states.length];
            b.setIntentTarget(0, 19, 7, 0.9);
            b.decide(s, 0f, s.sprinting, 1, 0, false, 20f, g);
        });
        assertTrue(bytes < 1_024, "decide allocated " + bytes + " bytes");
    }
}
