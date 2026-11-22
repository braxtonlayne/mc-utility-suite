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

    static ParkourRun run(ParkourCourse course, JumpCommit.Mode mode, StringBuilder log) {
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
        for (int t = 1; t <= ParkourBenchmarkTest.MAX_TICKS; t++) {
            float yaw = run.aimYaw(p.x, p.z);
            if (p.onGround) {
                groundX = p.x;
                groundZ = p.z;
            }
            intent.setCommitThreshold(IntentEstimator.GUIDE_THRESHOLD);
            brain.intentThreshold = intent.commitThreshold();
            if (intent.estimate(p, yaw, 15f, 1, 0, true, course) && intent.hasTarget()) {
                brain.setIntentBlocked(intent.targetBlocked());
                brain.setIntentLook(intent.lookConfidence());
                brain.setIntentTarget(intent.targetX(), intent.targetY(), intent.targetZ(), intent.confidence());
            } else {
                brain.clearIntentTarget();
            }
            brain.decide(p, yaw, true, 1, 0, false, 20f, course);
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
            p.tick(f, s, yaw, f > 0, brain.jump(), brain.sneak(), course);
            if (!run.update(t, p.x, p.y, p.z, p.onGround)) {
                break;
            }
        }
        return run;
    }

    @Test
    void coPilotWithTheRealEstimatorClearsTheWholeCourse() {
        ParkourCourse course = ParkourCourse.standard();
        StringBuilder log = new StringBuilder();
        ParkourRun r = run(course, JumpCommit.Mode.GUIDE, log);
        String report = "\n" + r.report() + log;
        System.out.println("Parkour benchmark (sim, estimator, Co-pilot): " + r.cleared() + "/"
                + course.segments().size());
        assertEquals(course.segments().size(), r.cleared(), report);
    }
}
