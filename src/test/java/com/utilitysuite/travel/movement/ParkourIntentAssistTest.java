package com.utilitysuite.travel.movement;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.travel.movement.MovementPhysicsTest.Grid;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The intent assist in the brain: target-aware jumps and air steering, ideal takeoff yaw, sticking the landing. */
class ParkourIntentAssistTest {

    /** Takeoff z &lt;= 0, a wide landing (cells x -3..0, z 4..10), a 1-block pillar at (px, 19, pz), a lethal pit. */
    private static Grid pillarCourse(int px, int pz) {
        Grid g = new Grid();
        g.fill(-3, 19, -30, 3, 19, 0);
        g.fill(-3, 19, 4, 0, 19, 10);
        g.fill(px, 19, pz, px, 19, pz);
        g.fill(-40, -40, -40, 79, -40, 79);
        return g;
    }

    /** Runs up from z = -6 holding forward + sprint at yaw 0 (no strafe), the brain jumping and steering. */
    private static MovementPhysics runJump(MovementAssistBrain b, Grid g, double x0) {
        MovementPhysics p = new MovementPhysics().set(x0, 20, -6, 0, -0.0784, 0, true);
        boolean air = false;
        for (int t = 0; t < 120; t++) {
            b.decide(p, 0f, true, 1, 0, false, 20f, g);
            int f = b.forward() == MovementAssistBrain.KEEP ? 1 : b.forward();
            int s = b.strafe() == MovementAssistBrain.KEEP ? 0 : b.strafe();
            p.tick(f, s, 0f, true, b.jump(), g);
            air |= !p.onGround;
            if (air && p.onGround) {
                return p;
            }
        }
        return p;
    }

    private static boolean onBlock(MovementPhysics p, int bx, int by, int bz) {
        JumpSearch s = new JumpSearch();
        s.setTarget(bx, by, bz);
        return s.missDistance(p.x, p.y, p.z) <= 0.0;
    }

    @Test
    void withATargetOffTheHeadingTheAssistLandsOnThePillarTheUnassistedRunMisses() {
        Grid g = pillarCourse(1, 4);
        MovementPhysics plain = runJump(new MovementAssistBrain(), g, 0.5);
        assertEquals(20.0, plain.y, 1e-9, "without a target it still lands safely (on the wide side)");
        assertFalse(onBlock(plain, 1, 19, 4), "but beside the pillar");

        MovementAssistBrain b = new MovementAssistBrain();
        b.setIntentTarget(1, 19, 4, 0.9);
        MovementPhysics aimed = runJump(b, g, 0.5);
        assertTrue(onBlock(aimed, 1, 19, 4), "with the target it lands on the pillar: " + aimed.x + ", " + aimed.z);
        assertTrue(b.budget().usedThisJump() <= b.budget().cap(), "within the authorship budget");
    }

    @Test
    void aTargetBelowTheConfidenceFloorOrWithIntentOffChangesNothing() {
        Grid g = pillarCourse(1, 4);
        MovementAssistBrain low = new MovementAssistBrain();
        low.setIntentTarget(1, 19, 4, IntentEstimator.MIN_CONFIDENCE - 0.1);
        assertFalse(onBlock(runJump(low, g, 0.5), 1, 19, 4));
        MovementAssistBrain off = new MovementAssistBrain();
        off.intentAssist = false;
        off.setIntentTarget(1, 19, 4, 0.9);
        assertFalse(onBlock(runJump(off, g, 0.5), 1, 19, 4));
    }

    @Test
    void withoutATargetTheDecisionsMatchTheBrainBeforeThisChange() {
        // the progressive benchmark course, plus the hand-made states of MovementAssistBrainTest
        ParkourCourse course = ParkourCourse.standard();
        MovementAssistBrain now = new MovementAssistBrain();
        LegacyMovementAssistBrain old = new LegacyMovementAssistBrain();
        ParkourRun run = new ParkourRun(course);
        MovementPhysics p = new MovementPhysics().set(course.startX(), 1, course.startZ(), 0, -0.0784, 0, true);
        int compared = 0;
        for (int t = 1; t <= ParkourBenchmarkTest.MAX_TICKS; t++) {
            float yaw = run.aimYaw(p.x, p.z);
            now.decide(p, yaw, true, 1, 0, false, 20f, course);
            old.decide(p, yaw, true, 1, 0, false, 20f, course);
            assertSame(old, now, "tick " + t);
            compared++;
            int f = now.forward() == MovementAssistBrain.KEEP ? 1 : now.forward();
            int s = now.strafe() == MovementAssistBrain.KEEP ? 0 : now.strafe();
            p.tick(f, s, yaw, true, now.jump(), course);
            if (!run.update(t, p.x, p.y, p.z, p.onGround)) {
                break;
            }
        }
        assertTrue(compared > 200, "compared " + compared + " states");

        Grid air = new Grid();
        air.fill(-40, -10, -40, 79, -10, 79);
        air.fill(-2, 15, 1, -1, 15, 3);
        MovementPhysics[] states = {
                new MovementPhysics().set(0.5, 20, 0.5, 0, 0, 0.2, false),
                new MovementPhysics().set(0.5, 20.8, 0.5, 0, 0.1, 0.25, false),
                new MovementPhysics().set(0.5, 20, 0.5, 0, 0, 0.1, false)};
        for (MovementPhysics st : states) {
            for (int f = -1; f <= 1; f++) {
                for (int s = -1; s <= 1; s++) {
                    MovementAssistBrain fresh = new MovementAssistBrain();
                    fresh.decide(st, 0f, false, f, s, false, 20f, air);
                    old.decide(st, 0f, false, f, s, false, 20f, air);
                    assertSame(old, fresh, "air state f=" + f + " s=" + s);
                }
            }
        }
    }

    private static void assertSame(LegacyMovementAssistBrain old, MovementAssistBrain now, String where) {
        assertEquals(old.jump(), now.jump(), where + " jump");
        assertEquals(old.forward(), now.forward(), where + " forward");
        assertEquals(old.strafe(), now.strafe(), where + " strafe");
        assertEquals(old.corrected(), now.corrected() && !now.sneak(), where + " corrected");
        assertEquals(old.hasLanding(), now.hasLanding(), where + " landing");
        assertEquals(old.landX(), now.landX(), 1e-12, where + " landX");
        assertEquals(old.landY(), now.landY(), 1e-12, where + " landY");
        assertEquals(old.landZ(), now.landZ(), 1e-12, where + " landZ");
        assertEquals(old.edgeNoLanding(), now.edgeNoLanding(), where + " edgeNoLanding");
        assertFalse(now.sprint(), where + " no sprint request without a target");
        assertFalse(now.takeoffShaped(), where + " no takeoff shaping without a target");
    }

    @Test
    void thePillarRunLandsWithTakeoffShapingOnOrOff() {
        Grid g = pillarCourse(1, 4);
        MovementAssistBrain off = new MovementAssistBrain();
        off.takeoffShaping = false;
        off.setIntentTarget(1, 19, 4, 0.9);
        assertTrue(onBlock(runJump(off, g, 0.5), 1, 19, 4), "the pre-shaping path still lands it");
        MovementAssistBrain on = new MovementAssistBrain();
        on.setIntentTarget(1, 19, 4, 0.9);
        assertTrue(onBlock(runJump(on, g, 0.5), 1, 19, 4), "with shaping too");
        assertTrue(on.budget().usedThisJump() <= on.budget().cap());
    }

    @Test
    void idealTakeoffYawIsWithinACoupleOfDegreesOfTheBestYaw() {
        Grid g = new Grid();
        g.fill(-6, 19, -30, 6, 19, 0);
        g.fill(2, 19, 4, 2, 19, 4); // the target, off to the left (+X) of a run facing +Z
        g.fill(-40, -40, -40, 79, -40, 79);
        MovementPhysics p = new MovementPhysics().set(0.5, 20, -2.5, 0, -0.0784, 0.25, true);
        // brute force: the yaw whose edge jump lands closest to the target's centre
        JumpSearch s = new JumpSearch();
        s.setTarget(2, 19, 4);
        float best = 0f;
        double bestScore = JumpSearch.NO_LANDING;
        for (float y = -40f; y <= 10f; y += 0.25f) {
            double sc = s.edgeRun(p, 1, 0, y, true, 20f, g);
            if (sc > bestScore) {
                bestScore = sc;
                best = y;
            }
        }
        assertTrue(bestScore > JumpSearch.NO_LANDING, "some yaw lands it");
        MovementAssistBrain b = new MovementAssistBrain();
        b.setIntentTarget(2, 19, 4, 0.9);
        for (int i = 0; i < MovementAssistBrain.YAW_OFFSETS.length; i++) {
            b.decide(p, 0f, true, 1, 0, false, 20f, g); // a still state: the candidates spread over the ticks
        }
        assertTrue(b.hasIdealTakeoffYaw());
        assertEquals(best, b.idealTakeoffYaw(), 2.0, "ideal takeoff yaw vs brute force " + best);
        assertTrue(b.missDistance() > 0.5, "facing straight ahead misses it by " + b.missDistance());
        assertTrue(b.turnGainHelp(p) > 0.0, "a miss with an ideal yaw asks for turn gain");
    }

    @Test
    void noTurnGainWhenTheJumpAlreadyLandsOrThereIsNoTarget() {
        Grid g = new Grid();
        g.fill(-6, 19, -30, 6, 19, 0);
        g.fill(0, 19, 4, 0, 19, 4); // straight ahead
        g.fill(-40, -40, -40, 79, -40, 79);
        MovementPhysics p = new MovementPhysics().set(0.5, 20, -2.5, 0, -0.0784, 0.25, true);
        MovementAssistBrain b = new MovementAssistBrain();
        b.decide(p, 0f, true, 1, 0, false, 20f, g);
        assertEquals(0.0, b.turnGainHelp(p), "no target");
        b.setIntentTarget(0, 19, 4, 0.9);
        b.decide(p, 0f, true, 1, 0, false, 20f, g);
        assertEquals(0.0, b.missDistance(), 1e-9, "the straight jump lands on it");
        assertEquals(0.0, b.turnGainHelp(p), "nothing to correct");
    }

    // ---- stick the landing ------------------------------------------------------------------------------------------

    private static Grid landing(int lengthZ) {
        Grid g = new Grid();
        g.fill(0, 19, 5, 0, 19, 4 + lengthZ);
        g.fill(-40, -40, -40, 79, -40, 79);
        return g;
    }

    /** One airborne decision, then the touchdown tick on the block at z = 5 moving +Z at sprint-landing speed. */
    private static MovementAssistBrain touchdown(Grid g, boolean userJump) {
        MovementAssistBrain b = new MovementAssistBrain();
        b.decide(new MovementPhysics().set(0.5, 20.3, 4.9, 0, -0.3, 0.25, false), 0f, true, 1, 0, false, 20f, g);
        b.decide(new MovementPhysics().set(0.5, 20, 5.3, 0, -0.0784, 0.25, true), 0f, true, 1, 0, userJump, 20f, g);
        return b;
    }

    @Test
    void sneaksOnAOneBlockLandingThatWouldSlideOffButNotOnALongPlatform() {
        MovementAssistBrain one = touchdown(landing(1), false);
        assertTrue(one.touchedDown());
        assertTrue(one.sneak(), "1-block target, forward still held: tap sneak");
        assertFalse(touchdown(landing(8), false).sneak(), "long platform: nothing to stick");
        MovementAssistBrain off = new MovementAssistBrain();
        off.stickLanding = false;
        off.decide(new MovementPhysics().set(0.5, 20.3, 4.9, 0, -0.3, 0.25, false), 0f, true, 1, 0, false, 20f,
                landing(1));
        off.decide(new MovementPhysics().set(0.5, 20, 5.3, 0, -0.0784, 0.25, true), 0f, true, 1, 0, false, 20f,
                landing(1));
        assertFalse(off.sneak(), "setting off");
    }

    @Test
    void neverSneaksWhenTheUserChainsAJump() {
        assertFalse(touchdown(landing(1), true).sneak(), "jump held for the next jump: no sneak");
    }

    @Test
    void releasesTheSneakOnceStableOrWhenTheKeysChange() {
        Grid g = landing(1);
        MovementAssistBrain b = touchdown(g, false);
        assertTrue(b.sneak());
        b.decide(new MovementPhysics().set(0.5, 20, 5.5, 0, -0.0784, 0.01, true), 0f, true, 1, 0, false, 20f, g);
        assertFalse(b.sneak(), "stopped on the block: released");
        MovementAssistBrain c = touchdown(g, false);
        c.decide(new MovementPhysics().set(0.5, 20, 5.5, 0, -0.0784, 0.2, true), 0f, true, 0, 1, false, 20f, g);
        assertFalse(c.sneak(), "the user changed keys: their move");
    }

    // ---- SafeWalk ---------------------------------------------------------------------------------------------------

    @Test
    void withSafeWalkADamagingAssistedJumpStaysInvalidWithATarget() {
        Grid g = new Grid();
        g.fill(-3, 19, -30, 3, 19, 0);
        g.fill(-3, 13, 5, 3, 13, 30);
        g.fill(-40, -40, -40, 79, -40, 79);
        MovementPhysics edge = new MovementPhysics().set(0.5, 20, 1.31, 0, 0, 0.153, true);
        MovementAssistBrain b = new MovementAssistBrain();
        b.setIntentTarget(0, 13, 6, 0.9);
        b.decide(edge, 0f, true, 1, 0, false, 20f, g);
        assertTrue(b.jump(), "without SafeWalk the target landing is worth a little damage");
        b.safeOnly = true;
        MovementPhysics before = new MovementPhysics().set(0.5, 20, 1.03, 0, 0, 0.153, true);
        b.setYawRate(-8.0);
        b.decide(before, 0f, true, 1, 0, false, 20f, g);
        assertFalse(b.jump(), "no early (tick-phase) jump either");
        b.decide(edge, 0f, true, 1, 0, false, 20f, g);
        assertFalse(b.jump(), "SafeWalk on: a jump that costs health is not the assist's to make");
    }

    // ---- performance --------------------------------------------------------------------------------------------------

    @Test
    void decideWithATargetAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        Grid g = pillarCourse(1, 4);
        MovementPhysics ground = new MovementPhysics().set(0.5, 20, 0.9, 0, -0.0784, 0.25, true);
        MovementPhysics air = new MovementPhysics().set(0.5, 20.8, 2.0, 0, 0.1, 0.3, false);
        MovementAssistBrain b = new MovementAssistBrain();
        b.setIntentTarget(1, 19, 4, 0.9);
        b.setYawRate(3.0);
        b.jumpBuffer().setEnabled(true);
        long bytes = AllocationProbe.measure(500, () -> {
            b.decide(ground, 0f, true, 1, 0, false, 20f, g);
            b.decide(air, 0f, true, 1, 0, false, 20f, g);
        });
        assertTrue(bytes < 1_024, "decide allocated " + bytes + " bytes");
    }

    @Test
    void simulatedTicksPerDecisionAreBounded() {
        Grid g = pillarCourse(1, 4);
        MovementAssistBrain b = new MovementAssistBrain();
        b.setIntentTarget(1, 19, 4, 0.9);
        b.setYawRate(-5.0);
        b.jumpBuffer().setEnabled(true);
        b.jumpBuffer().setMaxTicks(3);
        int max = 0;
        for (double z = -6; z <= 1.3; z += 0.07) {
            b.decide(new MovementPhysics().set(0.5, 20, z, 0, -0.0784, 0.28, true), 0f, true, 1, 0, false, 20f, g);
            max = Math.max(max, b.lastSimTicks());
        }
        for (double y = 18; y <= 21; y += 0.25) {
            b.decide(new MovementPhysics().set(0.5, y, 2.0, 0, 0.2, 0.3, false), 0f, true, 1, 0, false, 20f, g);
            max = Math.max(max, b.lastSimTicks());
        }
        assertTrue(max > 0);
        assertTrue(max <= MovementAssistBrain.MAX_SIM_TICKS, "simulated " + max + " ticks in one decision");
    }
}
