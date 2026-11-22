package com.utilitysuite.travel.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The movement benchmark the way the playtest runs it: a runner that only holds forward + sprint and aims at the next
 * pad, with the REAL intent estimator feeding the brain, Co-pilot (the autopilot's parkour reflex is at least
 * Co-pilot), the landing hold on, and no view change (automation holds the camera). {@link ParkourBenchmarkTest} runs
 * the same course with a bare brain; this one is the wiring of {@code MovementAssist.onMovementInput}.
 * <p>
 * It exists because the hold can stop the runner for good: a held W that is latched on a strip only works again when
 * walking on is safe, and "safe" is judged against the locked target. A target the body cannot reach (once kept as a
 * candidate by mistake) left the runner standing on segment 12's strip in the playtest of 2026-11-22.
 */
class ParkourBenchmarkIntentTest {

    static float PITCH = System.getenv("BENCH_PITCH") != null ? Float.parseFloat(System.getenv("BENCH_PITCH")) : 15f;

    static ParkourRun run(ParkourCourse course, JumpCommit.Mode mode, float camDegPerTick, StringBuilder log) {
        MovementAssistBrain brain = new MovementAssistBrain();
        brain.mode = mode;
        brain.landingHold = true;
        brain.setTechniques(new TechniqueLibrary());
        brain.setKeyChangeLimits(6, 2, 0);
        brain.coSteerAllowed = false;
        IntentEstimator intent = new IntentEstimator();
        ParkourRun run = new ParkourRun(course);
        MovementPhysics p = new MovementPhysics().set(course.startX(), 1, course.startZ(), 0, -0.0784, 0, true);
        double groundX = p.x;
        double groundZ = p.z;
        double takeoffX = 0;
        double takeoffZ = 0;
        boolean haveTakeoff = false;
        boolean sprinting = true;
        float yaw = run.aimYaw(p.x, p.z);
        for (int t = 1; t <= ParkourBenchmarkTest.MAX_TICKS; t++) {
            float aim = run.aimYaw(p.x, p.z);
            float turn = aim - yaw;
            turn = turn > 180f ? turn - 360f : (turn < -180f ? turn + 360f : turn);
            yaw += Math.max(-camDegPerTick, Math.min(camDegPerTick, turn)); // the runner's camera turns at a finite speed
            if (p.onGround) {
                groundX = p.x;
                groundZ = p.z;
            }
            intent.setCommitThreshold(IntentEstimator.GUIDE_THRESHOLD);
            brain.intentThreshold = intent.commitThreshold();
            if (intent.estimate(p, yaw, PITCH, 1, 0, sprinting, course) && intent.hasTarget()) {
                brain.setIntentBlocked(intent.targetBlocked());
                brain.setIntentLook(intent.lookConfidence());
                brain.setIntentTarget(intent.targetX(), intent.targetY(), intent.targetZ(), intent.confidence());
            } else {
                brain.clearIntentTarget();
            }
            brain.decide(p, yaw, sprinting, 1, 0, false, 20f, course);
            if (brain.tookOff()) {
                haveTakeoff = true;
                takeoffX = groundX;
                takeoffZ = groundZ;
            } else if (brain.touchedDown() && haveTakeoff) {
                intent.noteJump(takeoffX, takeoffZ, p.x, p.y, p.z);
                haveTakeoff = false;
            }
            int f = brain.forward() == MovementAssistBrain.KEEP ? 1 : brain.forward();
            int s = brain.strafe() == MovementAssistBrain.KEEP ? 0 : brain.strafe();
            if (log != null && (brain.jump() || (t % 20 == 0 && brain.hold().latched() != 0))) {
                log.append(String.format("t=%d %s at (%.2f, %.2f, %.2f) latched %d target %s (%d, %d, %d) blocked %s%n",
                        t, brain.jump() ? "jump" : "held", p.x, p.y, p.z, brain.hold().latched(),
                        brain.intentActive(), brain.targetX(), brain.targetY(), brain.targetZ(),
                        intent.targetBlocked()));
            }
            // vanilla: no sprint without forward; with the sprint key held it comes back with W on the next tick
            p.tick(f, s, yaw, f > 0, brain.jump(), brain.sneak(), course);
            sprinting = f > 0 && !brain.sneak();
            if (!run.update(t, p.x, p.y, p.z, p.onGround)) {
                break;
            }
        }
        return run;
    }

    @Test
    void coPilotWithTheRealEstimatorClearsTheWholeCourse() {
        ParkourCourse course = ParkourCourse.standard();
        // the runner's camera: instant, at the hard cap (450 deg/s), at the default speed (240), slow (120)
        for (float cam : new float[] {360f, 22.5f, 12f, 6f}) {
            StringBuilder log = new StringBuilder();
            ParkourRun r = run(course, JumpCommit.Mode.GUIDE, cam, log);
            String report = "\ncamera " + cam + " deg/tick\n" + r.report() + log;
            System.out.println("Parkour benchmark (sim, estimator, Co-pilot, camera " + cam + " deg/tick): "
                    + r.cleared() + "/" + course.segments().size());
            assertEquals(course.segments().size(), r.cleared(), report);
        }
    }
}
