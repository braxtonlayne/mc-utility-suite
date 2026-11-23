package com.utilitysuite.travel.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live trace of 2026-11-23, course J23 (1 air, level, onto a lone glass pane), Co-pilot with a flat look: the
 * estimator's pick faded on the run-up and dropped out on the last ground ticks (the pane is under its minimum range
 * there), the commitment went IDLE, the approach's planned takeoff (and the press it held for it) went with it, and
 * the body walked off the edge without a jump.
 */
class PanePostLoopTest {
    static boolean debug;

    /** A 3-wide pad ending at z 1, one air, a lone pane at z 2, a pad at z 5..8. */
    static ShapeGrid scene() {
        return new ShapeGrid().fill(-1, 19, -3, 1, 19, 0).pane(0, 19, 2).fill(-1, 19, 5, 1, 19, 8)
                .fill(-8, 5, -12, 8, 5, 16);
    }

    /** W + sprint from z -2.4 at {@code pitch}; jump pressed for two ticks at tick {@code pressAt} (-1: never). */
    static String loop(float pitch, int pressAt) {
        return loop(JumpCommit.Mode.GUIDE, pitch, pressAt);
    }

    static String loop(JumpCommit.Mode mode, float pitch, int pressAt) {
        ShapeGrid g = scene();
        SupportRegion pane = TechniqueLibraryTest.top(g, 0, 19, 2);
        MovementAssistBrain b = new MovementAssistBrain();
        b.mode = mode;
        b.landingHold = true;
        b.sprintWithholdAvailable = true;
        b.setTechniques(new TechniqueLibrary());
        b.setKeyChangeLimits(6, 2, 0);
        IntentEstimator intent = new IntentEstimator();
        MovementPhysics p = new MovementPhysics().set(0.5, 20, -2.4, 0, -0.0784, 0, true);
        boolean sprint = true;
        int onPane = 0;
        for (int t = 0; t < 60; t++) {
            intent.setCommitThreshold(mode == JumpCommit.Mode.CARRY ? IntentEstimator.CARRY_THRESHOLD
                    : IntentEstimator.GUIDE_THRESHOLD);
            b.intentThreshold = intent.commitThreshold();
            if (intent.estimate(p, 0f, pitch, 1, 0, sprint, g) && intent.hasTarget()) {
                b.setIntentBlocked(intent.targetBlocked());
                b.setIntentLook(intent.lookConfidence());
                b.setIntentTarget(intent.targetX(), intent.targetY(), intent.targetZ(), intent.confidence());
            } else {
                b.clearIntentTarget();
            }
            boolean uj = pressAt >= 0 && (t == pressAt || t == pressAt + 1) && p.onGround;
            b.decide(p, 0f, sprint, 1, 0, uj, 20f, g);
            if (debug) {
                System.out.printf("PANE %d z%.3f y%.3f g%s %s jump%s wh%s uj%s f%s%n", t, p.z, p.y, p.onGround,
                        b.commit().phase(), b.jump(), b.withholdJump(), uj, b.forward());
            }
            int f = b.forward() == MovementAssistBrain.KEEP ? 1 : b.forward();
            // the sprint key stays down: a sprint withheld for a walking run-up starts again on the ground
            sprint = (sprint || b.sprint() || p.onGround) && !b.sprintOff() && f > 0;
            p.tick(f, 0, 0f, sprint, (uj && !b.withholdJump()) || b.jump(), b.sneak(), g);
            if (p.onGround && Math.abs(p.y - pane.top()) < 1e-6 && pane.contains(p.x, p.z)) {
                if (++onPane >= 5) { // stopped on it (the next jump, to the pad, arms after the hold)
                    return "held";
                }
            } else if (onPane > 0) {
                return String.format("slid off the pane at z%.2f", p.z);
            }
            if (p.y < 18) {
                return String.format("fell at z%.2f", p.z);
            }
        }
        return String.format("stuck at z%.2f y%.2f", p.z, p.y);
    }

    @Test
    void anEarlyPressOntoAPaneOneGapOutLandsWithAFlatLook() {
        int held = 0;
        int tried = 0;
        StringBuilder out = new StringBuilder();
        for (float pitch : new float[] {13f, 17f, 21f}) {
            for (int pressAt = 6; pressAt <= 15; pressAt++) {
                tried++;
                String r = loop(pitch, pressAt);
                if (r.equals("held")) {
                    held++;
                } else {
                    out.append(String.format(" [p%.0f press %d: %s]", pitch, pressAt, r));
                }
            }
        }
        System.out.println("PANE LOOP " + held + "/" + tried + out);
        assertTrue(held * 10 >= tried * 9, held + " of " + tried + out);
    }

    /**
     * Live Takeover lap of 2026-11-23 17:55, J23: no press, a flat look; on the last ground ticks the pane is under the
     * estimator's range and its pick moved to the pad behind it, which reset the pane's approach one tick before the
     * edge, and the body walked off. Takeover takes off by itself, so no press here.
     */
    @Test
    void takeoverWithNoPressLandsOnThePaneNotThePadBehindIt() {
        int held = 0;
        int tried = 0;
        StringBuilder out = new StringBuilder();
        for (float pitch = 11f; pitch <= 23f; pitch += 2f) {
            tried++;
            String r = loop(JumpCommit.Mode.CARRY, pitch, -1);
            if (r.equals("held")) {
                held++;
            } else {
                out.append(String.format(" [p%.0f: %s]", pitch, r));
            }
        }
        System.out.println("PANE TAKEOVER " + held + "/" + tried + out);
        assertTrue(held * 10 >= tried * 9, held + " of " + tried + out);
    }
}
