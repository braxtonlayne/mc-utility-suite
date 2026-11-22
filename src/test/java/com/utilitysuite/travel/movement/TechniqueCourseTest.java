package com.utilitysuite.travel.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The technique scenes of {@link ParkourCourse#TECHNIQUE_CASES}: the technique library plans a verified script for
 * each, keys-only and with yaw, from the course's run-up. The existing lists stay as they were.
 */
class TechniqueCourseTest {

    @Test
    void existingCourseListsAreUnchanged() {
        assertEquals(19, ParkourCourse.STANDARD.size());
        assertEquals(7, ParkourCourse.EDGE_CASES.size());
        assertEquals("slab up", ParkourCourse.EDGE_CASES.get(ParkourCourse.EDGE_CASES.size() - 1).name());
        assertFalse(ParkourCourse.standard().shaped());
    }

    @Test
    void everyTechniqueCaseIsPlannedAndReplays() {
        StringBuilder report = new StringBuilder();
        for (int i = 0; i < ParkourCourse.TECHNIQUE_CASES.size(); i++) {
            ParkourCourse c = ParkourCourse.techniqueCase(i);
            String name = ParkourCourse.TECHNIQUE_CASES.get(i).name();
            SupportRegion top = TechniqueLibraryTest.top(c, c.padX(1), c.padY(1), c.padZ0(1));
            for (boolean yawAllowed : new boolean[]{false, true}) {
                TechniqueLibraryTest.Run r = TechniqueLibraryTest.search(c, top, c.startX(), 1.0, 2.5, yawAllowed,
                        TechniqueLibraryTest.MAX_CALLS);
                assertTrue(r.found(), name + " (yaw " + yawAllowed + "): no script");
                TechniqueLibraryTest.verify(r, c, yawAllowed);
                report.append(name).append(yawAllowed ? " [yaw]" : " [keys]").append(": ").append(r.script().name)
                        .append(" in ").append(r.calls()).append(" calls, margin ")
                        .append(String.format("%.2f", r.script().margin)).append('\n');
            }
        }
        System.out.print("Technique cases:\n" + report);
    }

    @Test
    void theCornerColumnBlocksThePlainLine() {
        ParkourCourse c = ParkourCourse.techniqueCase(0);
        SupportRegion top = TechniqueLibraryTest.top(c, c.padX(1), c.padY(1), c.padZ0(1));
        // the plain helper starts at feet y 20; run it on the course raised by nothing: course floor top is y 1
        float yaw = TechniqueLibraryTest.aim(top, c.startX(), 2.5);
        MovementPhysics p = new MovementPhysics().set(c.startX(), 1, 2.5, 0, -0.0784, 0, true);
        MovementPhysics probe = new MovementPhysics();
        boolean landed = false;
        boolean jumped = false;
        for (int t = 0; t < 120 && p.y > -2; t++) {
            boolean jump = false;
            if (!jumped) {
                probe.copyFrom(p).tick(1, 0, yaw, true, false, false, c);
                jump = !probe.supported(c);
            }
            jumped |= jump;
            p.tick(1, 0, yaw, true, jump, false, c);
            if (jumped && !jump && p.onGround) {
                landed = Math.abs(p.y - top.top()) < 1e-6 && top.contains(p.x, p.z);
                break;
            }
        }
        assertFalse(landed, "the straight sprint jump clips the column");
    }
}
