package com.utilitysuite.travel.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The movement benchmark in the physics replica: a runner that only holds forward + sprint and aims at the next pad
 * (like a player who never presses jump) runs {@link ParkourCourse#standard()}; Movement Assist does every jump and
 * air correction. The in-game playtest runs the same course.
 */
class ParkourBenchmarkTest {

    static final int MAX_TICKS = 2400;

    /** Runs the course; returns the run. {@code assist=false} is the baseline (nobody jumps). */
    static ParkourRun run(ParkourCourse course, boolean assist, StringBuilder log) {
        MovementAssistBrain brain = new MovementAssistBrain();
        brain.edgeJump = assist;
        brain.airSteer = assist;
        ParkourRun run = new ParkourRun(course);
        MovementPhysics p = new MovementPhysics().set(course.startX(), 1, course.startZ(), 0, -0.0784, 0, true);
        for (int t = 1; t <= MAX_TICKS; t++) {
            float yaw = run.aimYaw(p.x, p.z);
            brain.decide(p, yaw, true, 1, 0, false, 20f, course);
            int f = brain.forward() == MovementAssistBrain.KEEP ? 1 : brain.forward();
            int s = brain.strafe() == MovementAssistBrain.KEEP ? 0 : brain.strafe();
            if (log != null && brain.jump()) {
                log.append(String.format("t=%d jump at (%.2f, %.2f, %.2f) yaw %.1f%n", t, p.x, p.y, p.z, yaw));
            }
            p.tick(f, s, yaw, true, brain.jump(), course);
            if (!run.update(t, p.x, p.y, p.z, p.onGround)) {
                break;
            }
        }
        return run;
    }

    @Test
    void assistClearsTheWholeProgressiveCourse() {
        ParkourCourse course = ParkourCourse.standard();
        StringBuilder log = new StringBuilder();
        ParkourRun r = run(course, true, log);
        String report = "\n" + r.report() + log;
        System.out.println("Parkour benchmark (sim): " + r.cleared() + "/" + course.segments().size() + report);
        assertEquals(course.segments().size(), r.cleared(), report);
        assertTrue(r.finished());
    }

    @Test
    void withoutTheAssistTheRunnerFallsAtTheFirstGap() {
        ParkourRun r = run(ParkourCourse.standard(), false, null);
        assertEquals(0, r.cleared());
        assertEquals(1, r.fellOnSegment());
        assertFalse(r.finished());
    }

    @Test
    void everySegmentIsClearableByAPerfectJump() {
        StringBuilder bad = new StringBuilder();
        for (ParkourCourse.Segment s : ParkourCourse.STANDARD) {
            if (!ParkourFeasibility.clearable(s)) {
                bad.append(s.name()).append("; ");
            }
        }
        assertEquals("", bad.toString(), "impossible segments in the course");
    }

    @Test
    void courseIsProgressive() {
        ParkourCourse c = ParkourCourse.standard();
        int prevGap = 0;
        for (int i = 0; i < 3; i++) {
            assertTrue(c.segments().get(i).gap() > prevGap, "opens with rising gap length");
            prevGap = c.segments().get(i).gap();
        }
        assertTrue(c.segments().size() >= 12);
        assertEquals(1, c.segments().get(c.segments().size() - 1).width(), "ends on a strip");
        assertTrue(c.segments().stream().anyMatch(s -> s.ceiling() > 0), "has low-ceiling jumps");
    }
}
