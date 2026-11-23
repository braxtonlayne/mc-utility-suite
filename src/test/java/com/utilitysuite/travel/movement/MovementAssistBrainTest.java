package com.utilitysuite.travel.movement;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.travel.movement.MovementPhysicsTest.Grid;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementAssistBrainTest {

    private static Grid gap(int air) {
        Grid g = new Grid();
        g.fill(-3, 19, -30, 3, 19, 0);
        g.fill(-3, 19, 1 + air, 3, 19, 30);
        return g;
    }

    /** The last tick on the block before a gap, sprinting at steady speed toward +Z. */
    private static MovementPhysics atEdge() {
        return new MovementPhysics().set(0.5, 20, 1.31, 0, 0, 0.153, true);
    }

    @Test
    void jumpsAtTheEdgeOfAGapItCanClear() {
        MovementAssistBrain b = new MovementAssistBrain();
        b.decide(atEdge(), 0f, true, 1, 0, false, 20f, gap(3));
        assertTrue(b.jump());
        b.decide(atEdge(), 0f, true, 0, 0, false, 20f, gap(3));
        assertFalse(b.jump(), "not pressing forward: not the user's run, no jump");
        b.edgeJump = false;
        b.decide(atEdge(), 0f, true, 1, 0, false, 20f, gap(3));
        assertFalse(b.jump(), "setting off");
    }

    @Test
    void edgeJumpWithTheRealOnGroundVerticalVelocity() {
        // in game a player on the ground carries vy = -0.0784 (gravity, cancelled by the floor each tick)
        MovementPhysics run = new MovementPhysics().set(0.5, 20, 1.27, 0, -0.0784, 0.153, true);
        MovementAssistBrain b = new MovementAssistBrain();
        b.decide(run, 0f, true, 1, 0, false, 20f, gap(3));
        assertTrue(b.jump(), "the step-off tick is detected even though Y collides first");
        MovementPhysics early = new MovementPhysics().set(0.5, 20, 0.6, 0, -0.0784, 0.153, true);
        b.decide(early, 0f, true, 1, 0, false, 20f, gap(3));
        assertFalse(b.jump(), "not yet at the edge");
    }

    @Test
    void withSafeWalkOnADamagingJumpIsNotAValidJump() {
        // 4 air blocks, the far side 6 blocks lower (a jump lands with fall damage), a deep pit between
        Grid g = new Grid();
        g.fill(-3, 19, -30, 3, 19, 0);
        g.fill(-3, 13, 5, 3, 13, 30);
        g.fill(-40, -40, -40, 79, -40, 79);
        MovementAssistBrain b = new MovementAssistBrain();
        b.decide(atEdge(), 0f, true, 1, 0, false, 20f, g);
        assertTrue(b.jump(), "without SafeWalk a hurtful landing beats falling into the pit");
        b.safeOnly = true;
        b.decide(atEdge(), 0f, true, 1, 0, false, 20f, g);
        assertFalse(b.jump(), "with SafeWalk on, a jump that costs health is invalid: SafeWalk holds the edge");
        b.decide(atEdge(), 0f, true, 1, 0, false, 20f, gap(3));
        assertTrue(b.jump(), "a clean jump still takes priority over SafeWalk");
    }

    @Test
    void airSteeringFillsTheFreeAxisAndNeverFightsAHeldKey() {
        // falling toward a lethal pit; a ledge just to the right (-X) is the only survivable landing
        Grid g = new Grid();
        g.fill(-40, -10, -40, 79, -10, 79);
        g.fill(-2, 15, 1, -1, 15, 3);
        MovementPhysics air = new MovementPhysics().set(0.5, 20, 0.5, 0, 0, 0.2, false);
        MovementAssistBrain b = new MovementAssistBrain();
        b.decide(air, 0f, false, 1, 0, false, 20f, g);
        assertTrue(b.corrected());
        assertEquals(-1, b.strafe(), "presses right for the user");
        assertEquals(MovementAssistBrain.KEEP, b.forward(), "the forward key the user holds stays");
        b.decide(air, 0f, false, 1, 1, false, 20f, g);
        assertEquals(MovementAssistBrain.KEEP, b.strafe(), "the user holds left: their choice wins");
    }

    @Test
    void leavesAGoodTrajectoryAlone() {
        Grid g = new Grid();
        g.fill(-3, 19, -30, 3, 19, 30); // flat ground everywhere
        MovementPhysics air = new MovementPhysics().set(0.5, 20.8, 0.5, 0, 0.1, 0.25, false);
        MovementAssistBrain b = new MovementAssistBrain();
        b.decide(air, 0f, true, 1, 0, false, 20f, g);
        assertFalse(b.corrected());
        assertTrue(b.hasLanding());
        assertEquals(20.0, b.landY(), 1e-9);
        assertEquals(0f, b.landDamage());
    }

    @Test
    void sprintHopOnOpenGroundAndHeadHittersButNotIntoAWall() {
        Grid open = new Grid();
        open.fill(-3, 19, -30, 3, 19, 40);
        MovementPhysics run = new MovementPhysics().set(0.5, 20, 0.5, 0, 0, 0.153, true);
        MovementAssistBrain b = new MovementAssistBrain();
        b.sprintHop = true;
        b.decide(run, 0f, true, 1, 0, false, 20f, open);
        assertTrue(b.jump(), "a sprint jump outruns sprinting on open flat ground");
        Grid tunnel = new Grid();
        tunnel.fill(-3, 19, -30, 3, 19, 40);
        tunnel.fill(-3, 22, -30, 3, 22, 40); // 2-block-high tunnel
        b.decide(run, 0f, true, 1, 0, false, 20f, tunnel);
        assertTrue(b.jump(), "head-hitter sprint jumps are faster in vanilla too");
        Grid wall = new Grid();
        wall.fill(-3, 19, -30, 3, 19, 40);
        wall.fill(-3, 20, 2, 3, 21, 2); // a wall two blocks ahead
        b.decide(run, 0f, true, 1, 0, false, 20f, wall);
        assertFalse(b.jump(), "no hop into a wall");
    }

    @Test
    void predictsAnUnsurvivableFallAsLethal() {
        Grid g = new Grid();
        g.fill(-40, -30, -40, 79, -30, 79);
        MovementPhysics air = new MovementPhysics().set(0.5, 20, 0.5, 0, 0, 0.1, false);
        MovementAssistBrain b = new MovementAssistBrain();
        b.decide(air, 0f, false, 0, 0, false, 20f, g);
        assertTrue(b.lethal());
        assertFalse(b.corrected(), "nothing better exists: keys untouched");
    }

    @Test
    void reachFindsLandingsAcrossAClearableGapOnly() {
        JumpReach r = new JumpReach();
        Grid three = gap(3);
        MovementPhysics stand = new MovementPhysics().set(0.5, 20, 0.5, 0, 0, 0, true);
        r.compute(stand, three);
        boolean across = false;
        for (int i = 0; i < r.count(); i++) {
            across |= com.utilitysuite.travel.Packed.z(r.cell(i)) >= 4;
        }
        // from standing (no run-up) a sprint jump covers less than from a run: it still reaches its own side
        assertTrue(r.count() > 0, "some landings found");
        assertFalse(across, "a standing sprint jump does not clear 3 air blocks");
    }

    @Test
    void sprintOutputOnlyWithATargetAndNeverWhileSprinting() {
        // no target: the edge-jump states never ask for sprint or shape a takeoff, sprinting or not
        MovementAssistBrain b = new MovementAssistBrain();
        for (boolean sprinting : new boolean[]{true, false}) {
            b.decide(atEdge(), 0f, sprinting, 1, 0, false, 20f, gap(3));
            assertFalse(b.sprint());
            assertFalse(b.takeoffShaped());
            assertFalse(b.shaper().found());
        }
        // a walking run-up to a target 3 air blocks out: sprint is requested once, and never again while sprinting
        Grid g = gap(3);
        MovementAssistBrain t = new MovementAssistBrain();
        t.setIntentTarget(0, 19, 4, 0.9);
        MovementPhysics p = new MovementPhysics().set(0.5, 20, -1.7, 0, -0.0784, 0, true);
        boolean sprinting = false;
        int requests = 0;
        for (int i = 0; i < 30 && p.onGround; i++) {
            t.decide(p, 0f, sprinting, 1, 0, false, 20f, g);
            if (t.sprint()) {
                assertFalse(sprinting, "sprint is only requested while not sprinting");
                sprinting = true;
                requests++;
            }
            assertEquals(MovementAssistBrain.KEEP, t.forward(), "forward stays the user's");
            p.tick(1, t.strafe() == MovementAssistBrain.KEEP ? 0 : t.strafe(), 0f, sprinting, t.jump(), g);
        }
        assertTrue(requests <= 1, "at most one sprint tick: " + requests);
    }

    /** A 1-block pillar at (0, 19, 5) after a 3-block gap, a floor far below. */
    private static Grid pillarAfterGap() {
        Grid g = new Grid();
        g.fill(-3, 19, -30, 3, 19, 1);
        g.fill(0, 19, 5, 0, 19, 5);
        g.fill(-40, -40, -40, 79, -40, 79);
        return g;
    }

    /** Runs a sloppy player (W + sprint held throughout, never jumps) at the pillar in a mode; returns the brain. */
    private static MovementPhysics runAtPillar(MovementAssistBrain b, Grid g, int ticks, int[] maxCommitTicks) {
        MovementPhysics p = new MovementPhysics().set(0.5, 20, -6, 0, -0.0784, 0, true);
        for (int t = 0; t < ticks; t++) {
            b.setIntentTarget(0, 19, 5, 0.9);
            b.decide(p, 0f, true, 1, 0, false, 20f, g);
            if (maxCommitTicks != null) {
                maxCommitTicks[0] = Math.max(maxCommitTicks[0], b.lastCommitSimTicks());
                assertTrue(b.lastSimTicks() <= MovementAssistBrain.MAX_SIM_TICKS, "search bound");
            }
            int f = b.forward() == MovementAssistBrain.KEEP ? 1 : b.forward();
            int s = b.strafe() == MovementAssistBrain.KEEP ? 0 : b.strafe();
            p.tick(f, s, 0f, f > 0, b.jump(), b.sneak(), g);
        }
        return p;
    }

    @Test
    void everyModeLandsThePillarHoldingWAndStaysOnIt() {
        for (JumpCommit.Mode mode : JumpCommit.Mode.values()) {
            MovementAssistBrain b = new MovementAssistBrain();
            b.mode = mode;
            b.landingHold = true;
            int[] commitTicks = new int[1];
            MovementPhysics p = runAtPillar(b, pillarAfterGap(), 120, commitTicks);
            System.out.println("commit sim ticks per decision, " + mode + ": max " + commitTicks[0]);
            assertTrue(p.onGround && Math.abs(p.y - 20.0) < 1e-6 && Math.abs(p.z - 5.5) <= 0.8 + 1e-9 && Math.abs(p.x - 0.5) <= 0.8,
                    mode + ": still on the pillar after 120 ticks of W: (" + p.x + ", " + p.y + ", " + p.z + ")");
            assertTrue(commitTicks[0] <= MovementAssistBrain.MAX_COMMIT_SIM_TICKS,
                    mode + ": commitment simulated " + commitTicks[0] + " ticks in one decision");
        }
    }

    /**
     * Owner, 2026-11-22: the hold must not leave W dead until it is pressed again. Landing on a single block that
     * continues as a 1-wide path, the still-held W walks on once the body is steady; a lone pillar still holds (the
     * test above).
     */
    @Test
    void heldForwardWalksOnWhenTheSingleBlockContinues() {
        for (JumpCommit.Mode mode : JumpCommit.Mode.values()) {
            Grid g = pillarAfterGap();
            g.fill(0, 19, 6, 0, 19, 60); // the landing block continues as a 1-wide strip
            MovementAssistBrain b = new MovementAssistBrain();
            b.mode = mode;
            b.landingHold = true;
            MovementPhysics p = runAtPillar(b, g, 90, null);
            assertTrue(p.onGround && Math.abs(p.y - 20.0) < 1e-6 && p.z > 9.0,
                    mode + ": W held from the flight walks on along the strip: (" + p.x + ", " + p.y + ", " + p.z
                            + ")");
        }
    }

    /** A single block followed by a 2-gap to more course at the same height: the held W runs on and the edge jump takes it. */
    @Test
    void heldForwardCarriesIntoTheNextJumpOfTheCourse() {
        Grid g = pillarAfterGap();
        g.fill(-1, 19, 8, 1, 19, 30); // next platform after a 2-block gap from the pillar
        MovementAssistBrain b = new MovementAssistBrain();
        b.mode = JumpCommit.Mode.GUIDE;
        b.landingHold = true;
        MovementPhysics p = runAtPillar(b, g, 120, null);
        assertTrue(p.onGround && Math.abs(p.y - 20.0) < 1e-6 && p.z > 8.0,
                "held W carried on into the next jump: (" + p.x + ", " + p.y + ", " + p.z + ")");
    }

    /** Owner, 2026-11-22: a chain of single blocks over 1-block gaps is handled with W held, no stop on each block. */
    @Test
    void chainsSingleBlocksOverOneGapsWithoutStopping() {
        for (JumpCommit.Mode mode : JumpCommit.Mode.values()) {
            Grid g = singleBlockChain();
            MovementAssistBrain b = new MovementAssistBrain();
            b.mode = mode;
            b.landingHold = true;
            MovementPhysics p = new MovementPhysics().set(0.5, 20, -6, 0, -0.0784, 0, true);
            int arrived = -1;
            int stalled = 0;
            for (int t = 0; t < 160 && arrived < 0; t++) {
                // the user looks at the next block of the chain (two blocks ahead of the one under them)
                int next = (int) Math.floor(p.z) + 2;
                b.setIntentTarget(0, 19, Math.max(5, Math.min(15, next | 1)), 0.9);
                b.decide(p, 0f, true, 1, 0, false, 20f, g);
                int f = b.forward() == MovementAssistBrain.KEEP ? 1 : b.forward();
                int s = b.strafe() == MovementAssistBrain.KEEP ? 0 : b.strafe();
                p.tick(f, s, 0f, f > 0, b.jump(), b.sneak(), g);
                if (p.onGround && Math.abs(p.vz) < 0.05 && p.z > 4) {
                    stalled++;
                }
                if (p.y < 19) {
                    break;
                }
                if (p.onGround && p.z > 15.5) {
                    arrived = t;
                }
            }
            assertTrue(arrived > 0, mode + ": crossed the chain looking ahead with W held: (" + p.x + ", " + p.y
                    + ", " + p.z + ")");
            assertTrue(stalled <= 2, mode + ": no stop on the blocks of the chain, stalled ticks " + stalled);
        }
    }

    /** Single blocks at z 5, 7, 9, 11, 13 (1-block gaps after the first 3-gap), then a wide platform from z 15. */
    private static Grid singleBlockChain() {
        Grid g = pillarAfterGap();
        for (int z = 7; z <= 13; z += 2) {
            g.fill(0, 19, z, 0, 19, z);
        }
        g.fill(-3, 19, 15, 3, 19, 40);
        return g;
    }

    /**
     * W alone is not "going on". The estimator lifts the only top ahead to its commit confidence whatever the camera
     * shows (sole-candidate floor); the landing hold reads the confidence without that floor, so the same chain with
     * the same target but no look at it is not flown through: the body holds on the first block first.
     */
    @Test
    void wTowardTheOnlyTopAheadIsNotGoingOn() {
        for (JumpCommit.Mode mode : JumpCommit.Mode.values()) {
            int[] stalled = new int[2];
            for (int look = 0; look < 2; look++) {
                Grid g = singleBlockChain();
                MovementAssistBrain b = new MovementAssistBrain();
                b.mode = mode;
                b.landingHold = true;
                MovementPhysics p = new MovementPhysics().set(0.5, 20, -6, 0, -0.0784, 0, true);
                for (int t = 0; t < 90 && p.y >= 19 && p.z < 6.9; t++) {
                    int next = (int) Math.floor(p.z) + 2;
                    // the floored confidence is the same in both runs; only the look confidence differs
                    b.setIntentLook(look == 1 ? IntentEstimator.SOLE_CONFIDENCE : 0.2);
                    b.setIntentTarget(0, 19, Math.max(5, Math.min(15, next | 1)), IntentEstimator.SOLE_CONFIDENCE);
                    b.decide(p, 0f, true, 1, 0, false, 20f, g);
                    int f = b.forward() == MovementAssistBrain.KEEP ? 1 : b.forward();
                    int s = b.strafe() == MovementAssistBrain.KEEP ? 0 : b.strafe();
                    p.tick(f, s, 0f, f > 0, b.jump(), b.sneak(), g);
                    if (p.onGround && Math.abs(p.vz) < 0.05 && p.z > 4) {
                        stalled[look]++;
                    }
                }
            }
            assertTrue(stalled[1] <= 2, mode + ": looking at the next block, no stop: " + stalled[1]);
            assertTrue(stalled[0] >= 3, mode + ": W alone must hold on the first block, stalled ticks " + stalled[0]);
        }
    }

    /**
     * Owner, 2026-11-23 (Co-pilot run): chains slowed down. A player looking two or three jumps ahead gives the
     * one-jump estimator no confident look at the next block; with the chain lookahead, the view on a link further
     * down the path is going on, so the held W is not latched on each block. The same run looking off the course
     * (W alone) still holds on the first block.
     */
    @Test
    void lookingDownTheChainBeyondTheNextBlockIsGoingOn() {
        for (JumpCommit.Mode mode : JumpCommit.Mode.values()) {
            int[] stalled = new int[2];
            for (int look = 0; look < 2; look++) {
                Grid g = singleBlockChain();
                MovementAssistBrain b = new MovementAssistBrain();
                ChainLookahead chain = new ChainLookahead();
                b.setChain(chain);
                b.mode = mode;
                b.landingHold = true;
                MovementPhysics p = new MovementPhysics().set(0.5, 20, -6, 0, -0.0784, 0, true);
                for (int t = 0; t < 90 && p.y >= 19 && p.z < 6.9; t++) {
                    chain.update(p, 0f, g);
                    // the view rests on the course about six blocks ahead: past the estimator's next-block look
                    double ahead = Math.min(15.5, p.z + 6.0) - p.z;
                    b.viewYaw = look == 1 ? 0f : 40f;
                    b.viewPitch = (float) Math.toDegrees(Math.atan2(1.62, Math.max(1.0, ahead)));
                    int next = (int) Math.floor(p.z) + 2;
                    b.setIntentLook(0.2);
                    b.setIntentTarget(0, 19, Math.max(5, Math.min(15, next | 1)), IntentEstimator.SOLE_CONFIDENCE);
                    b.decide(p, 0f, true, 1, 0, false, 20f, g);
                    int f = b.forward() == MovementAssistBrain.KEEP ? 1 : b.forward();
                    int s = b.strafe() == MovementAssistBrain.KEEP ? 0 : b.strafe();
                    p.tick(f, s, 0f, f > 0, b.jump(), b.sneak(), g);
                    if (p.onGround && Math.abs(p.vz) < 0.05 && p.z > 4) {
                        stalled[look]++;
                    }
                }
            }
            assertTrue(stalled[1] <= 2, mode + ": looking down the chain, no stop: " + stalled[1]);
            assertTrue(stalled[0] >= 3, mode + ": W alone must hold on the first block, stalled ticks " + stalled[0]);
        }
    }

    /** Owner, 2026-11-22: never force a series of jumps on someone who meant one. W let go on landing: they stay. */
    @Test
    void lettingGoOfForwardOnLandingNeverChains() {
        for (JumpCommit.Mode mode : JumpCommit.Mode.values()) {
            Grid g = singleBlockChain();
            MovementAssistBrain b = new MovementAssistBrain();
            b.mode = mode;
            b.landingHold = true;
            MovementPhysics p = new MovementPhysics().set(0.5, 20, -6, 0, -0.0784, 0, true);
            boolean airborne = false;
            boolean landed = false;
            for (int t = 0; t < 140; t++) {
                int uf = landed ? 0 : 1;
                b.setIntentTarget(0, 19, 5, 0.9);
                b.decide(p, 0f, true, uf, 0, false, 20f, g);
                int f = b.forward() == MovementAssistBrain.KEEP ? uf : b.forward();
                int s = b.strafe() == MovementAssistBrain.KEEP ? 0 : b.strafe();
                p.tick(f, s, 0f, f > 0, b.jump(), b.sneak(), g);
                airborne |= !p.onGround;
                landed |= airborne && p.onGround;
            }
            assertTrue(p.onGround && Math.abs(p.y - 20.0) < 1e-6 && p.z > 4.2 && p.z < 6.8,
                    mode + ": stayed on the first block: (" + p.x + ", " + p.y + ", " + p.z + ")");
        }
    }

    @Test
    void guideNeverReplacesAFreshKey() {
        Grid g = pillarAfterGap();
        MovementAssistBrain b = new MovementAssistBrain();
        b.mode = JumpCommit.Mode.GUIDE;
        b.landingHold = true;
        MovementPhysics p = new MovementPhysics().set(0.5, 20, -6, 0, -0.0784, 0, true);
        boolean sawAir = false;
        for (int t = 0; t < 80; t++) {
            b.setIntentTarget(0, 19, 5, 0.9);
            // the user taps strafe left mid-flight: a fresh key
            int us = !p.onGround && sawAir ? 1 : 0;
            b.decide(p, 0f, true, 1, us, false, 20f, g);
            if (us != 0 && b.commit().driving()) {
                assertEquals(MovementAssistBrain.KEEP, b.strafe(), "tick " + t + ": the fresh left key stands");
            }
            sawAir |= !p.onGround;
            int f = b.forward() == MovementAssistBrain.KEEP ? 1 : b.forward();
            int s = b.strafe() == MovementAssistBrain.KEEP ? us : b.strafe();
            p.tick(f, s, 0f, f > 0, b.jump(), b.sneak(), g);
        }
    }

    @Test
    void decideAllocatesNothingInEveryMode() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        Grid g = pillarAfterGap();
        for (JumpCommit.Mode mode : JumpCommit.Mode.values()) {
            MovementAssistBrain b = new MovementAssistBrain();
            b.mode = mode;
            b.landingHold = true;
            MovementPhysics[] states = new MovementPhysics[160];
            MovementPhysics p = new MovementPhysics().set(0.5, 20, -6, 0, -0.0784, 0, true);
            MovementAssistBrain driver = new MovementAssistBrain();
            driver.mode = mode;
            driver.landingHold = true;
            for (int t = 0; t < states.length; t++) {
                states[t] = new MovementPhysics().copyFrom(p);
                driver.setIntentTarget(0, 19, 5, 0.9);
                driver.decide(p, 0f, true, 1, 0, false, 20f, g);
                int f = driver.forward() == MovementAssistBrain.KEEP ? 1 : driver.forward();
                p.tick(f, 0, 0f, f > 0, driver.jump(), driver.sneak(), g);
            }
            int[] i = new int[1];
            long bytes = AllocationProbe.measure(300, () -> {
                MovementPhysics s = states[i[0]++ % states.length];
                b.setIntentTarget(0, 19, 5, 0.9);
                b.decide(s, 0f, true, (i[0] & 8) == 0 ? 1 : 0, 0, false, 20f, g);
            });
            assertTrue(bytes < 1_024, mode + ": decide allocated " + bytes + " bytes");
        }
    }

    @Test
    void airborneDecisionAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        Grid g = new Grid();
        g.fill(-40, -10, -40, 79, -10, 79);
        g.fill(-2, 15, 1, -1, 15, 3);
        MovementPhysics air = new MovementPhysics().set(0.5, 20, 0.5, 0, 0, 0.2, false);
        MovementAssistBrain b = new MovementAssistBrain();
        long bytes = AllocationProbe.measure(500, () -> b.decide(air, 0f, false, 1, 0, false, 20f, g));
        assertTrue(bytes < 1_024, "decide allocated " + bytes + " bytes");
    }
}
