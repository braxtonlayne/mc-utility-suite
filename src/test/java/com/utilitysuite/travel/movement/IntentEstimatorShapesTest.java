package com.utilitysuite.travel.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Intent estimator v2 on exact shapes (design 2026-11-22, section 9): real tops, simulated reach, gaze, course. */
class IntentEstimatorShapesTest {

    static final int FLOOR = 63; // the start block; feet at 64
    static final double PX = 0.5;
    static final double PZ = 0.5;

    static MovementPhysics player(double x, double z, double vx, double vz) {
        MovementPhysics p = new MovementPhysics();
        p.set(x, FLOOR + 1, z, vx, 0, vz, true);
        return p;
    }

    static float yawTo(double px, double pz, double x, double z) {
        return (float) Math.toDegrees(Math.atan2(-(x - px), z - pz));
    }

    static float pitchTo(double px, double pz, double x, double y, double z) {
        double h = Math.hypot(x - px, z - pz);
        return (float) Math.toDegrees(Math.atan2(FLOOR + 1 + 1.62 - y, h));
    }

    static ShapeGrid start() {
        return new ShapeGrid().cube(0, FLOOR, 0);
    }

    /** Looks at the top centre of a cell whose top is at {@code topY}. */
    static boolean lookAt(IntentEstimator e, ShapeGrid g, MovementPhysics p, int bx, double topY, int bz,
                          boolean sprint) {
        return e.estimate(p, yawTo(p.x, p.z, bx + 0.5, bz + 0.5), pitchTo(p.x, p.z, bx + 0.5, topY, bz + 0.5), 1, 0,
                sprint, g);
    }

    @Test
    void fencePostAcrossTwoGapIsSelectedWithItsTrueTop() {
        ShapeGrid g = start().fencePost(0, FLOOR, 3); // gap z = 1, 2; post top at 64.5
        IntentEstimator e = new IntentEstimator();
        for (int t = 0; t < 3; t++) {
            lookAt(e, g, player(PX, PZ, 0, 0.2), 0, FLOOR + 1.5, 3, true);
        }
        assertEquals(0, e.targetX());
        assertEquals(FLOOR, e.targetY());
        assertEquals(3, e.targetZ());
        assertEquals(FLOOR + 1.5, e.targetTopY(), 1.0e-6);
        assertEquals(1, e.feasibility(0, FLOOR, 3), "the 1.5 landing height is reachable in the replica");
        assertTrue(e.hasTarget());
    }

    @Test
    void wallAndSlabTopsCarryTheirHeights() {
        ShapeGrid g = start().wallPost(-1, FLOOR, 3).slabBottom(1, FLOOR, 3);
        IntentEstimator e = new IntentEstimator();
        for (int t = 0; t < 3; t++) {
            lookAt(e, g, player(PX, PZ, 0, 0.2), -1, FLOOR + 1.5, 3, true);
        }
        assertEquals(-1, e.targetX());
        assertEquals(FLOOR + 1.5, e.targetTopY(), 1.0e-6);
        IntentEstimator s = new IntentEstimator();
        for (int t = 0; t < 3; t++) {
            lookAt(s, g, player(PX, PZ, 0, 0.2), 1, FLOOR + 0.5, 3, true);
        }
        assertEquals(1, s.targetX());
        assertEquals(FLOOR + 0.5, s.targetTopY(), 1.0e-6);
    }

    @Test
    void panePostIsSelectedOverTheFloorBlockBehindIt() {
        ShapeGrid g = start().pane(0, FLOOR, 3).cube(0, FLOOR, 4);
        IntentEstimator e = new IntentEstimator();
        for (int t = 0; t < 3; t++) {
            lookAt(e, g, player(PX, PZ, 0, 0.2), 0, FLOOR + 1.0, 3, true);
        }
        assertEquals(3, e.targetZ(), "the pane post, not the block behind it");
        assertEquals(0, e.targetX());
        assertEquals(1, e.feasibility(0, FLOOR, 3));
    }

    @Test
    void harmfulTopIsNotACandidate() {
        ShapeGrid g = start().cube(0, FLOOR, 3).surface(0, FLOOR, 3, MovementPhysics.SURFACE_HARM);
        IntentEstimator e = new IntentEstimator();
        assertFalse(lookAt(e, g, player(PX, PZ, 0, 0.2), 0, FLOOR + 1.0, 3, true));
        assertEquals(0, e.lastCandidateCount());
    }

    @Test
    void unmodelledTopStaysBelowEveryCommitThreshold() {
        ShapeGrid g = start().cube(0, FLOOR, 3).surface(0, FLOOR, 3, MovementPhysics.SURFACE_UNMODELLED);
        IntentEstimator e = new IntentEstimator();
        e.setCommitThreshold(IntentEstimator.CARRY_THRESHOLD);
        for (int t = 0; t < 3; t++) {
            assertFalse(lookAt(e, g, player(PX, PZ, 0, 0.2), 0, FLOOR + 1.0, 3, true));
        }
        assertEquals(3, e.targetZ());
        assertTrue(e.confidence() <= IntentEstimator.UNMODELLED_CONFIDENCE);
    }

    @Test
    void topOutOfRealReachIsDroppedAlthoughTheTableAllowedIt() {
        // not sprinting: table reach (4.3 + 0.4) * 0.8 = 3.76, plus the 1.5 soft band lets a top at 5.0 in
        ShapeGrid g = start().cube(0, FLOOR, 5).cube(1, FLOOR, 2);
        IntentEstimator e = new IntentEstimator();
        for (int t = 0; t < 3; t++) {
            lookAt(e, g, player(PX, PZ, 0, 0.1), 0, FLOOR + 1.0, 5, false);
        }
        assertEquals(2, e.lastCandidateCount(), "both tops pass the table pre-filter");
        assertEquals(0, e.feasibility(0, FLOOR, 5), "the replica cannot reach it");
        assertEquals(1, e.feasibility(1, FLOOR, 2));
        assertEquals(1, e.targetX());
        assertEquals(2, e.targetZ());
    }

    @Test
    void oneTickGlanceKeepsTheTargetAndASustainedLookMovesIt() {
        ShapeGrid g = start().cube(-1, FLOOR, 3).cube(2, FLOOR, 3);
        IntentEstimator e = new IntentEstimator();
        MovementPhysics p = player(PX, PZ, 0, 0.2);
        for (int t = 0; t < 10; t++) {
            lookAt(e, g, p, -1, FLOOR + 1.0, 3, true);
        }
        assertEquals(-1, e.targetX());
        lookAt(e, g, p, 2, FLOOR + 1.0, 3, true); // the glance
        assertEquals(-1, e.targetX(), "a one-tick glance does not flip the target");
        for (int t = 0; t < 4; t++) {
            lookAt(e, g, p, -1, FLOOR + 1.0, 3, true);
            assertEquals(-1, e.targetX());
        }
        int switchedAt = -1;
        for (int t = 0; t < 10; t++) {
            lookAt(e, g, p, 2, FLOOR + 1.0, 3, true);
            if (switchedAt < 0 && e.targetX() == 2) {
                switchedAt = t;
            }
        }
        assertEquals(2, e.targetX(), "a sustained look moves the target");
        assertTrue(switchedAt >= 1 && switchedAt <= 7, "switched on look tick " + switchedAt);
    }

    @Test
    void platformJustLeftIsNotChosenWhenTurningAroundBriefly() {
        // course P0 (z -3) -> P1 (z 0, the player) -> P2 (z 3)
        ShapeGrid g = start().cube(0, FLOOR, -3).cube(0, FLOOR, 3);
        IntentEstimator e = new IntentEstimator();
        e.estimate(player(PX, -2.5, 0, 0.1), 0f, 30f, 1, 0, true, g); // standing on P0 before the jump
        e.estimate(airborne(PX, -1.0), 0f, 30f, 1, 0, true, g);
        e.noteJump(PX, -2.5, PX, PZ);
        MovementPhysics p = player(PX, PZ, 0, 0.05);
        for (int t = 0; t < 6; t++) {
            lookAt(e, g, p, 0, FLOOR + 1.0, 3, true);
        }
        assertEquals(3, e.targetZ());
        assertTrue(e.hasTarget());
        // turn around for three ticks, forward held (movement now points back at P0)
        for (int t = 0; t < 3; t++) {
            lookAt(e, g, p, 0, FLOOR + 1.0, -3, true);
            assertFalse(e.hasTarget() && e.targetZ() == -3, "tick " + t + ": the platform just left");
        }
        for (int t = 0; t < 6; t++) {
            lookAt(e, g, p, 0, FLOOR + 1.0, 3, true);
        }
        assertEquals(3, e.targetZ());
        assertTrue(e.hasTarget());

        // control: without the course ring the same look commits to P0
        IntentEstimator fresh = new IntentEstimator();
        for (int t = 0; t < 3; t++) {
            lookAt(fresh, g, p, 0, FLOOR + 1.0, -3, true);
        }
        assertEquals(-3, fresh.targetZ());
        assertTrue(fresh.hasTarget());
    }

    static MovementPhysics airborne(double x, double z) {
        MovementPhysics p = new MovementPhysics();
        p.set(x, FLOOR + 1.6, z, 0, 0.1, 0.25, false);
        return p;
    }

    @Test
    void deadEndLosesTheTieToTheFinalistThatContinues() {
        for (int side = -1; side <= 1; side += 2) {
            // finalists A (-1, 3) and B (1, 3); the course continues past the one on `side`
            int cont = side < 0 ? -1 : 1;
            ShapeGrid g = start().cube(-1, FLOOR, 3).cube(1, FLOOR, 3)
                    .cube(cont, FLOOR, 6).cube(cont, FLOOR, 7).cube(cont, FLOOR, 8);
            IntentEstimator e = new IntentEstimator();
            MovementPhysics p = player(PX, PZ, 0, 0.2);
            float pitch = pitchTo(PX, PZ, 0.5, FLOOR + 1.0, 3.5);
            for (int t = 0; t < 5; t++) {
                e.estimate(p, 0f, pitch, 1, 0, true, g);
            }
            assertEquals(1, e.continues(cont, FLOOR, 3), "side " + side);
            assertEquals(0, e.continues(-cont, FLOOR, 3), "side " + side);
            assertEquals(cont, e.targetX(), "side " + side);
        }
    }

    @Test
    void noFlappingOverFortyTicksOfJitteredYaw() {
        ShapeGrid g = start().cube(-1, FLOOR, 4).cube(2, FLOOR, 4);
        IntentEstimator e = new IntentEstimator();
        MovementPhysics p = player(PX, PZ, 0, 0.2);
        // look between the two tops: near-equal candidates
        float yaw = yawTo(PX, PZ, 1.0, 4.5);
        float pitch = pitchTo(PX, PZ, 1.0, FLOOR + 1.0, 4.5);
        e.estimate(p, yaw, pitch, 1, 0, true, g);
        int first = e.targetX();
        int changes = 0;
        int last = first;
        long seed = 99;
        for (int t = 0; t < 40; t++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            float jitter = (float) (((seed >>> 33) & 1023) / 1023.0 * 4.0 - 2.0);
            e.estimate(p, yaw + jitter, pitch, 1, 0, true, g);
            if (e.targetX() != last) {
                changes++;
                last = e.targetX();
            }
        }
        assertEquals(0, changes, "the target flapped");
    }

    @Test
    void commitThresholdByMode() {
        IntentEstimator e = new IntentEstimator();
        assertEquals(IntentEstimator.MIN_CONFIDENCE, e.commitThreshold());
        e.setCommitThreshold(IntentEstimator.CARRY_THRESHOLD);
        assertEquals(0.35, e.commitThreshold());
        e.setCommitThreshold(IntentEstimator.GUIDE_THRESHOLD);
        assertEquals(0.5, e.commitThreshold());
    }

    @Test
    void lookupsAndSimTicksStayUnderTheCaps() {
        ShapeGrid g = new ShapeGrid();
        long seed = 7;
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                for (int y = FLOOR - 4; y <= FLOOR + 1; y++) {
                    seed = seed * 6364136223846793005L + 1442695040888963407L;
                    int r = (int) ((seed >>> 33) & 15);
                    if (r == 0) {
                        g.cube(x, y, z);
                    } else if (r == 1) {
                        g.fence(x, y, z, true, false, true, false);
                    } else if (r == 2) {
                        g.slabBottom(x, y, z);
                    } else if (r == 3) {
                        g.pane(x, y, z);
                    }
                }
            }
        }
        g.cube(0, FLOOR, 0);
        IntentEstimator e = new IntentEstimator();
        int rolled = 0;
        for (int yawDeg = 0; yawDeg < 720; yawDeg += 15) {
            e.estimate(player(PX + (yawDeg % 30) * 0.05, PZ, 0, 0.25), yawDeg, 25f, 1, 0, true, g);
            assertTrue(e.lastLookups() <= IntentEstimator.MAX_LOOKUPS, "lookups " + e.lastLookups());
            assertTrue(e.lastSimTicks() <= IntentEstimator.MAX_SIM_TICKS, "sim ticks " + e.lastSimTicks());
            assertTrue(e.lastRolls() <= IntentEstimator.MAX_ROLLS_PER_ESTIMATE);
            assertTrue(e.lastCandidateCount() <= IntentEstimator.MAX_CANDIDATES);
            rolled += e.lastRolls();
        }
        assertTrue(rolled > 0);
    }
}
