package com.utilitysuite.travel.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IntentEstimatorTest {

    /** Small dense voxel grid: x,z in -30..29, y in 40..79; everything else is air. */
    static final class Grid implements MovementPhysics.Voxels {
        final boolean[] cells = new boolean[60 * 40 * 60];

        static int idx(int x, int y, int z) {
            return ((x + 30) * 40 + (y - 40)) * 60 + (z + 30);
        }

        static boolean in(int x, int y, int z) {
            return x >= -30 && x < 30 && y >= 40 && y < 80 && z >= -30 && z < 30;
        }

        Grid set(int x, int y, int z) {
            cells[idx(x, y, z)] = true;
            return this;
        }

        @Override
        public boolean solid(int x, int y, int z) {
            return in(x, y, z) && cells[idx(x, y, z)];
        }
    }

    static final int FLOOR = 63; // block the player stands on; feet at y = 64

    static MovementPhysics player(double x, double z, double vx, double vz) {
        MovementPhysics p = new MovementPhysics();
        p.set(x, FLOOR + 1, z, vx, 0, vz, true);
        return p;
    }

    /** Yaw (Minecraft convention) from the player to the centre of a block's top face. */
    static float yawTo(double px, double pz, int bx, int bz) {
        return (float) Math.toDegrees(Math.atan2(-(bx + 0.5 - px), bz + 0.5 - pz));
    }

    static float pitchTo(double px, double pz, int bx, int by, int bz) {
        double h = Math.hypot(bx + 0.5 - px, bz + 0.5 - pz);
        return (float) Math.toDegrees(Math.atan2(FLOOR + 1 + 1.62 - (by + 1), h));
    }

    static Grid start() {
        return new Grid().set(0, FLOOR, 0);
    }

    @Test
    void lookedAtPillarOfTwoIsSelected() {
        Grid g = start().set(-2, FLOOR, 3).set(2, FLOOR, 3);
        double px = 0.5;
        double pz = 0.5;
        for (int side = -1; side <= 1; side += 2) {
            int bx = 2 * side;
            IntentEstimator e = new IntentEstimator();
            float yaw = yawTo(px, pz, bx, 3);
            float pitch = pitchTo(px, pz, bx, FLOOR, 3);
            boolean ok = e.estimate(player(px, pz, 0, 0.2), yaw, pitch, 1, 0, true, g);
            assertEquals(bx, e.targetX(), "side " + side);
            assertEquals(3, e.targetZ());
            assertEquals(FLOOR, e.targetY());
            assertTrue(e.confidence() > 0.0);
            assertEquals(ok, e.hasTarget());
        }
    }

    @Test
    void headingLineBeatsOffsetNeighbourWhenLookIsSlightlyOff() {
        // velocity and forward key straight along +Z; look 6 degrees toward the neighbour pillar
        Grid g = start().set(0, FLOOR, 4).set(-2, FLOOR, 4);
        IntentEstimator e = new IntentEstimator();
        float yawOnLine = yawTo(0.5, 0.5, 0, 4);
        e.estimate(player(0.5, 0.5, 0, 0.25), yawOnLine + 6f, pitchTo(0.5, 0.5, 0, FLOOR, 4), 1, 0, true, g);
        assertEquals(0, e.targetX());
        assertEquals(4, e.targetZ());
    }

    @Test
    void jumpLinePrefersNextPillarOnTheLine() {
        // row of pillars along +Z every 3 blocks (player on z=3), plus an off-line neighbour at the same look distance
        Grid g = new Grid().set(0, FLOOR, 0).set(0, FLOOR, 3).set(0, FLOOR, 6).set(3, FLOOR, 6);
        double px = 0.5;
        double pz = 3.5;
        // look midway between the on-line pillar (x 0.5) and the neighbour (x 3.5): x = 2
        float yaw = (float) Math.toDegrees(Math.atan2(-(2.0 - px), 6.5 - pz));
        float pitch = (float) Math.toDegrees(Math.atan2(1.62, Math.hypot(2.0 - px, 6.5 - pz)));
        IntentEstimator withLine = new IntentEstimator();
        withLine.noteJump(0.5, -2.5, 0.5, 0.5);
        withLine.noteJump(0.5, 0.5, 0.5, 3.5);
        withLine.estimate(player(px, pz, 0, 0.2), yaw, pitch, 1, 0, true, g);
        assertEquals(0, withLine.targetX());
        assertEquals(6, withLine.targetZ());
    }

    @Test
    void unreachableLookedAtBlockLosesToReachableNeighbour() {
        // looked-at block 6 flat blocks away vs a reachable one at (1, 4), roughly 14 degrees off the ray
        Grid g = start().set(0, FLOOR, 6).set(1, FLOOR, 4);
        IntentEstimator e = new IntentEstimator();
        e.estimate(player(0.5, 0.5, 0, 0.25), yawTo(0.5, 0.5, 0, 6), pitchTo(0.5, 0.5, 0, FLOOR, 6), 1, 0, true, g);
        assertEquals(1, e.targetX());
        assertEquals(4, e.targetZ());
    }

    @Test
    void symmetricCandidatesAreNotConfident() {
        Grid g = start().set(-2, FLOOR, 3).set(2, FLOOR, 3);
        IntentEstimator e = new IntentEstimator();
        float pitch = (float) Math.toDegrees(Math.atan2(1.62, 3.0));
        boolean ok = e.estimate(player(0.5, 0.5, 0, 0.2), 0f, pitch, 1, 0, true, g);
        assertFalse(ok);
        assertFalse(e.hasTarget());
        assertTrue(e.confidence() < IntentEstimator.MIN_CONFIDENCE);
    }

    @Test
    void targetDoesNotFlipUnderLookJitter() {
        Grid g = start().set(-1, FLOOR, 4).set(1, FLOOR, 4);
        IntentEstimator e = new IntentEstimator();
        double px = 0.5;
        double pz = 0.5;
        float baseYaw = yawTo(px, pz, 1, 4);
        float pitch = pitchTo(px, pz, 1, FLOOR, 4);
        e.estimate(player(px, pz, 0, 0.25), baseYaw, pitch, 1, 0, true, g);
        int first = e.targetX();
        for (int i = 0; i < 200; i++) {
            float jitter = (float) (2.0 * Math.sin(i * 1.7));
            e.estimate(player(px, pz, 0, 0.25), baseYaw + jitter, pitch + jitter * 0.5f, 1, 0, true, g);
            assertEquals(first, e.targetX(), "tick " + i);
        }
    }

    @Test
    void standingStillHasNoTarget() {
        Grid g = start().set(0, FLOOR, 3);
        IntentEstimator e = new IntentEstimator();
        assertFalse(e.estimate(player(0.5, 0.5, 0, 0), 0f, 20f, 0, 0, false, g));
        assertFalse(e.hasTarget());
        assertEquals(0.0, e.confidence());
    }

    @Test
    void flatFloorWithoutGapsHasNoTarget() {
        Grid g = new Grid();
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                g.set(x, FLOOR, z);
            }
        }
        IntentEstimator e = new IntentEstimator();
        assertFalse(e.estimate(player(0.5, 0.5, 0, 0.25), 0f, 20f, 1, 0, true, g));
        assertEquals(0, e.lastCandidateCount());
    }

    @Test
    void idealYawPointsAtTopFaceCentre() {
        Grid g = start().set(3, FLOOR, 3);
        IntentEstimator e = new IntentEstimator();
        float yaw = yawTo(0.5, 0.5, 3, 3);
        e.estimate(player(0.5, 0.5, 0, 0.25), yaw, pitchTo(0.5, 0.5, 3, FLOOR, 3), 1, 0, true, g);
        assertEquals(3, e.targetX());
        // from (0.5,0.5) to (3.5,3.5): dx = +3, dz = +3 -> yaw -45
        assertEquals(-45.0f, e.idealYaw(), 0.01f);
    }

    @Test
    void candidateAndLookupCapsHoldOnNoisyGrid() {
        Grid g = new Grid();
        long seed = 12345;
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                for (int y = FLOOR - 6; y <= FLOOR + 1; y++) {
                    seed = seed * 6364136223846793005L + 1442695040888963407L;
                    if (((seed >>> 33) & 3) == 0) {
                        g.set(x, y, z);
                    }
                }
            }
        }
        IntentEstimator e = new IntentEstimator();
        for (int yawDeg = 0; yawDeg < 360; yawDeg += 15) {
            e.estimate(player(0.5, 0.5, 0, 0.25), yawDeg, 20f, 1, 0, true, g);
            assertTrue(e.lastCandidateCount() <= IntentEstimator.MAX_CANDIDATES);
            assertTrue(e.lastLookups() <= IntentEstimator.MAX_LOOKUPS + 64);
            assertTrue(e.confidence() >= 0.0 && e.confidence() <= 1.0);
        }
    }

    @Test
    void resetClearsTargetAndJumpLine() {
        Grid g = start().set(0, FLOOR, 4);
        IntentEstimator e = new IntentEstimator();
        e.noteJump(0, 0, 0, 3);
        e.estimate(player(0.5, 0.5, 0, 0.25), 0f, pitchTo(0.5, 0.5, 0, FLOOR, 4), 1, 0, true, g);
        assertTrue(e.hasTarget());
        e.reset();
        assertFalse(e.hasTarget());
        assertEquals(0.0, e.confidence());
    }

    @Test
    void sharedChainLookaheadReusesScannedColumnsWithSameResult() {
        Grid g = start()
                .set(0, FLOOR, 3)
                .set(0, FLOOR, 6)
                .set(1, FLOOR, 9);
        MovementPhysics now = player(0.5, 0.5, 0f, 0.25);
        ChainLookahead chain = new ChainLookahead();
        for (int t = 0; t < 20; t++) {
            chain.update(now, 0f, g);
        }

        IntentEstimator baseline = new IntentEstimator();
        assertTrue(baseline.estimate(now, 0f, pitchTo(0.5, 0.5, 0, FLOOR, 3), 1, 0, true, g));

        IntentEstimator shared = new IntentEstimator();
        shared.setChain(chain);
        assertTrue(shared.estimate(now, 0f, pitchTo(0.5, 0.5, 0, FLOOR, 3), 1, 0, true, g));

        assertTrue(shared.lastReusedColumns() > 0);
        assertTrue(shared.lastLookups() < baseline.lastLookups(),
                "expected fewer lookups with shared chain: shared=" + shared.lastLookups()
                        + " baseline=" + baseline.lastLookups());
        assertEquals(baseline.targetX(), shared.targetX());
        assertEquals(baseline.targetY(), shared.targetY());
        assertEquals(baseline.targetZ(), shared.targetZ());
        assertEquals(baseline.confidence(), shared.confidence(), 1.0e-9);
    }
}
