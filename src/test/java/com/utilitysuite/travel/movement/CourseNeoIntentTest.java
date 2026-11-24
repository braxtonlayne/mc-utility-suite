package com.utilitysuite.travel.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live trace of 2026-11-23 (Co-pilot, J36): walking the takeoff strip with W toward the pillar, the estimator had no
 * target on any ground tick, so the brain never armed and the neo was never searched.
 */
class CourseNeoIntentTest {

    /**
     * The whole loop as the module runs it (estimator, brain with the technique library, the core's co-steer
     * follower): the user holds W + sprint with the view on the line and presses jump at the edge.
     *
     * @return a one-line outcome; landed when the body stands on the strip behind the pillar
     */
    static String loop(JumpCommit.Mode mode, double startZ, float pitch, StringBuilder trace) {
        return loop(mode, 0.5, startZ, pitch, trace);
    }

    static String loop(JumpCommit.Mode mode, double startX, double startZ, float pitch, StringBuilder trace) {
        return loop(mode, startX, startZ, pitch, 0f, true, trace);
    }

    /** {@code viewOff}: the user's view this far off the line (degrees); {@code sprintKey}: sprinting at the start. */
    static String loop(JumpCommit.Mode mode, double startX, double startZ, float pitch, float viewOff, boolean sprintKey,
                       StringBuilder trace) {
        return loop(mode, startX, startZ, pitch, viewOff, sprintKey, null, trace);
    }

    /**
     * {@code agree}: when not null, the user holds in the air the strafe key the script plays on that tick (a fresh
     * key that agrees with the plan); {@code agree[0]} counts those ticks, {@code agree[1]} the scripts dropped in the
     * air before their landing tick.
     */
    static String loop(JumpCommit.Mode mode, double startX, double startZ, float pitch, float viewOff, boolean sprintKey,
                       int[] agree, StringBuilder trace) {
        ShapeGrid g = TechniqueLibraryTest.courseNeoScene();
        SupportRegion top = TechniqueLibraryTest.top(g, 0, 19, 5);
        MovementAssistBrain b = new MovementAssistBrain();
        b.mode = mode;
        b.landingHold = true;
        b.sprintWithholdAvailable = true;
        b.setTechniques(new TechniqueLibrary());
        b.setKeyChangeLimits(6, 2, 0);
        IntentEstimator intent = new IntentEstimator();
        boolean carry = mode == JumpCommit.Mode.CARRY;
        float steerRate = mode == JumpCommit.Mode.NUDGE ? 0f : carry ? 12f : 7.5f;
        float steerMax = carry ? 90f : 25f;
        com.utilitysuite.core.input.YawCoSteer steer = new com.utilitysuite.core.input.YawCoSteer();
        MovementPhysics p = new MovementPhysics().set(startX, 20, startZ, 0, -0.0784, 0, true);
        float yaw = viewOff;
        boolean sprint = sprintKey;
        boolean pressed = false;
        for (int t = 0; t < 80; t++) {
            boolean uj = !pressed && p.onGround && p.z >= 2.75;
            pressed |= uj;
            intent.setCommitThreshold(carry ? IntentEstimator.CARRY_THRESHOLD : IntentEstimator.GUIDE_THRESHOLD);
            b.intentThreshold = intent.commitThreshold();
            if (intent.estimate(p, yaw, pitch, 1, 0, sprint, g) && intent.hasTarget()) {
                b.setIntentBlocked(intent.targetBlocked());
                b.setIntentLook(intent.lookConfidence());
                b.setIntentTarget(intent.targetX(), intent.targetY(), intent.targetZ(), intent.confidence());
            } else {
                b.clearIntentTarget();
            }
            b.coSteerAllowed = steerRate > 0f;
            b.coSteerMaxDegPerTick = steerRate;
            b.coSteerMaxTotalDeg = steerMax;
            if (!b.commit().live()) {
                steer.reset();
            }
            int us = 0;
            int next = b.scriptIndex() + 1;
            boolean midScript = b.scriptActive() && !p.onGround && next < b.activeScript().landIndex;
            if (agree != null && midScript) {
                us = b.activeScript().strafe[next];
                agree[0] += us != 0 ? 1 : 0;
            }
            b.decide(p, yaw, sprint, 1, us, uj, 20f, g);
            if (agree != null && midScript && !b.scriptActive()) {
                agree[1]++;
            }
            int f = b.forward() == MovementAssistBrain.KEEP ? 1 : b.forward();
            int s = b.strafe() == MovementAssistBrain.KEEP ? us : b.strafe();
            boolean jump = (uj && !b.withholdJump()) || b.jump();
            steer.beginTick();
            if (b.wantsCoSteer() && steerRate > 0f) {
                steer.request(b.coSteerYaw(), steerRate * 20.0, Math.min(steerMax, b.coSteerTotalDeg()), b.commit().authority());
            }
            if (trace != null) {
                trace.append(String.format("%n[%d x%.2f z%.2f y%.2f yaw%.1f f%d s%d%s %s blk%s int=%s %s]", t, p.x, p.z,
                        p.y, yaw, f, s, jump ? " J" : "", b.commit().phase(), b.techniqueFirst() ? "1" : "0",
                        b.intentActive() ? b.targetZ() : "-",
                        b.scriptActive() ? b.activeScript().name + "@" + b.scriptIndex() : ""));
            }
            // vanilla: a sprint the module started stays on until forward ends it (or the user's key restarts it)
            sprint = (sprint || sprintKey || b.sprint()) && !b.sprintOff() && f > 0;
            if (trace != null && b.scriptActive()) {
                FlightScript sc = b.activeScript();
                int i = b.scriptIndex();
                trace.append(String.format(" script f%d s%d spr%s jmp%s yaw%.2f -> x%.3f z%.3f | used spr%s sneak%s land%d/%d at x%.2f y%.2f z%.2f",
                        sc.forward[i], sc.strafe[i], sc.sprint[i] ? 1 : 0, sc.jump[i] ? 1 : 0, sc.yaw[i], sc.x[i],
                        sc.z[i], sprint ? 1 : 0, b.sneak() ? 1 : 0, sc.landIndex, sc.length, sc.x[sc.landIndex], sc.y[sc.landIndex], sc.z[sc.landIndex]));
            }
            float used = yaw;
            p.tick(f, s, used, sprint, jump, b.sneak(), g);
            for (int k = 0; k < 3; k++) {
                yaw += (float) steer.onFrame(yaw, 0.0, 0.0, 0.05 / 3, false);
            }
            if (p.onGround && Math.abs(p.y - top.top()) < 1e-6 && top.contains(p.x, p.z)) {
                return "landed";
            }
            if (p.y < 18) {
                return String.format("fell at x%.2f z%.2f", p.x, p.z);
            }
        }
        return String.format("stuck at x%.2f z%.2f", p.x, p.z);
    }

    @Test
    void coPilotAndTakeoverTakeTheNeoInTheFullLoop() {
        // Corrections (NUDGE) never turns the view, and keys alone are one tick-perfect input here: not offered
        for (JumpCommit.Mode mode : new JumpCommit.Mode[] {JumpCommit.Mode.GUIDE, JumpCommit.Mode.CARRY}) {
            int landed = 0;
            int tried = 0;
            StringBuilder out = new StringBuilder();
            for (double x = 0.3; x <= 0.71; x += 0.2) {
                for (double z = -0.8; z <= 0.41; z += 0.3) {
                    for (float pitch : new float[] {15f, 30f}) {
                        tried++;
                        String r = loop(mode, x, z, pitch, null);
                        if (r.equals("landed")) {
                            landed++;
                        } else {
                            out.append(String.format(" [x%.1f z%.1f p%.0f: %s]", x, z, pitch, r));
                        }
                    }
                }
            }
            System.out.println("NEO LOOP " + mode + " " + landed + "/" + tried + out);
            assertTrue(landed * 10 >= tried * 9, mode + ": " + landed + " of " + tried + out);
        }
    }

    /**
     * Live trace of 2026-11-23: after J35's landing hold the user walked (no sprint) toward the pillar with the view
     * 10-18 degrees off the line. Every such run-up lands: the neo is timed from a run along the line, the stages take
     * turns so the neo is reached early, and the replay fills in the sprint.
     */
    @Test
    void theLiveRunUpViewOffTheLineAndWalking() {
        StringBuilder out = new StringBuilder();
        int landed = 0;
        int tried = 0;
        for (float off : new float[] {-18f, -10f, 10f, 18f}) {
            for (boolean spr : new boolean[] {true, false}) {
                tried++;
                String r = loop(JumpCommit.Mode.GUIDE, 0.7, -0.6, 20f, off, spr, null);
                if (r.equals("landed")) {
                    landed++;
                } else {
                    out.append(String.format(" [off %.0f sprint %s: %s]", off, spr, r));
                }
            }
        }
        System.out.println("NEO LIVE " + landed + "/" + tried + out);
        assertTrue(landed == tried, landed + " of " + tried + out);
    }

    /**
     * Live trace of 2026-11-23 (J34 corner-wall): the user held the strafe key the script was already playing; after
     * three ticks the key counted as an axis override and the script was dropped in the air. A key that agrees with
     * the script is not an override: the script goes on and the jump lands.
     */
    @Test
    void aHeldStrafeKeyThatAgreesWithTheScriptKeepsIt() {
        for (JumpCommit.Mode mode : new JumpCommit.Mode[] {JumpCommit.Mode.GUIDE, JumpCommit.Mode.CARRY}) {
            int[] agree = new int[2];
            StringBuilder trace = new StringBuilder();
            String r = loop(mode, 0.5, -0.5, 20f, 0f, true, agree, trace);
            assertTrue(agree[1] == 0, mode + ": script dropped in the air under an agreeing key" + trace);
            assertTrue(agree[0] >= 4, mode + ": the script strafes in the air for " + agree[0] + " ticks" + trace);
            assertTrue(r.equals("landed"), mode + ": " + r + trace);
        }
    }

    @Test
    void theStripBehindThePillarIsTheTargetFromTheRunUp() {
        for (float off : new float[] {0f, 20f, -20f}) {
            ShapeGrid g = TechniqueLibraryTest.courseNeoScene();
            IntentEstimator e = new IntentEstimator();
            float yaw = off;
            MovementPhysics p = new MovementPhysics().set(0.5, 20, -0.5, 0, -0.0784, 0, true);
            int ground = 0;
            int hit = 0;
            StringBuilder s = new StringBuilder();
            for (int t = 0; t < 14 && p.onGround && p.z < 2.6; t++) {
                boolean ok = e.estimate(p, yaw, 25f, 1, 0, true, g) && e.hasTarget();
                ground++;
                if (ok && e.targetZ() >= 4 && e.targetZ() <= 6) {
                    hit++;
                }
                s.append(String.format(" z%.2f:%s", p.z, ok ? e.targetZ() + "/" + String.format("%.2f", e.confidence())
                        + (e.targetBlocked() ? "B" : "") : "-"));
                p.tick(1, 0, 0f, true, false, false, g); // runs straight along the strip whatever the view
            }
            System.out.println("NEO INTENT off " + off + ":" + s);
            assertTrue(hit * 2 >= ground, "view " + off + ": target behind the pillar on " + hit + " of " + ground
                    + " ground ticks:" + s);
        }
    }
}
