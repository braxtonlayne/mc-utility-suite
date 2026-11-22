package com.utilitysuite.travel.movement;

import com.utilitysuite.travel.movement.MovementPhysicsTest.Grid;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Takeoff shaping in the physics replica. Course: a run-up platform (x -3..3, z -run+1..0, top y 20), a 1-block target
 * pillar at (dx, 19, 1 + gap) (so {@code gap} air blocks), a lethal pit below. The runner starts standing at the back
 * of the run-up, holds forward at yaw 0 (and sprint when {@code sprintKey}), never presses jump; the brain does the
 * edge jump, air steering and shaping.
 */
class TakeoffShaperTest {

    static Grid course(int run, int gap, int dx) {
        Grid g = new Grid();
        g.fill(-3, 19, -run + 1, 3, 19, 0);
        g.fill(dx, 19, 1 + gap, dx, 19, 1 + gap);
        g.fill(-40, -40, -40, 79, -40, 79);
        return g;
    }

    /** What happened over one run. */
    static final class Run {
        MovementPhysics end;
        boolean onTarget;
        int strafeTicks;
        int sprintRequests;
        int yawShapeTicks;
        int keyOverrides;
        boolean neededSeen;
        boolean foundSeen;
        double firstGain = Double.NaN;
        int firstStrafe;
        float maxYawOff;
        /** The body on the first tick the shaper found a sprint-timing shape. */
        MovementPhysics firstSprintShapeState;
    }

    static Run play(MovementAssistBrain b, int run, int gap, int dx, boolean sprintKey, int userS, double yawRate) {
        Grid g = course(run, gap, dx);
        Run r = new Run();
        MovementPhysics p = new MovementPhysics().set(0.5, 20, -run + 1 + 0.3, 0, -0.0784, 0, true);
        b.setIntentTarget(dx, 19, 1 + gap, 0.9);
        b.setYawRate(yawRate);
        boolean sprinting = sprintKey;
        boolean air = false;
        for (int t = 0; t < 120; t++) {
            b.decide(p, 0f, sprinting, 1, userS, false, 20f, g);
            TakeoffShaper sh = b.shaper();
            if (p.onGround) {
                r.neededSeen |= sh.needed();
                if (sh.found() && !r.foundSeen) {
                    r.firstGain = sh.gainBlocks();
                }
                r.foundSeen |= sh.found();
                if (sh.found() && sh.sprintTick() >= 0 && r.firstSprintShapeState == null) {
                    r.firstSprintShapeState = new MovementPhysics().copyFrom(p);
                }
                if (sh.found()) {
                    r.maxYawOff = Math.max(r.maxYawOff, Math.abs(sh.idealYaw()));
                }
                if (b.forward() != MovementAssistBrain.KEEP
                        || (userS != 0 && b.strafe() != MovementAssistBrain.KEEP)
                        || (sprintKey && b.sprint())) {
                    r.keyOverrides++;
                }
            }
            if (b.sprint()) {
                assertFalse(sprinting, "sprint is only ever turned on");
                sprinting = true;
                r.sprintRequests++;
            }
            if (b.takeoffShaped() && b.strafe() != MovementAssistBrain.KEEP) {
                if (r.strafeTicks == 0) {
                    r.firstStrafe = b.strafe();
                }
                r.strafeTicks++;
            }
            if (b.shaper().yawShape()) {
                r.yawShapeTicks++;
                assertFalse(b.takeoffShaped(), "a yaw shape fills no keys, it only names a turn-gain yaw");
            }
            int f = b.forward() == MovementAssistBrain.KEEP ? 1 : b.forward();
            int s = b.strafe() == MovementAssistBrain.KEEP ? userS : b.strafe();
            p.tick(f, s, 0f, sprinting, b.jump(), g);
            air |= !p.onGround;
            if ((air && p.onGround) || p.y < 0) {
                break;
            }
        }
        r.end = p;
        JumpSearch s = new JumpSearch();
        s.setTarget(dx, 19, 1 + gap);
        r.onTarget = p.onGround && s.missDistance(p.x, p.y, p.z) <= 0.0;
        return r;
    }

    static Run plain(int run, int gap, int dx, boolean sprintKey) {
        MovementAssistBrain b = new MovementAssistBrain();
        b.takeoffShaping = false;
        return play(b, run, gap, dx, sprintKey, 0, 0.0);
    }

    static Run shaped(int run, int gap, int dx, boolean sprintKey) {
        return play(new MovementAssistBrain(), run, gap, dx, sprintKey, 0, 0.0);
    }

    /**
     * The 4-block gap to a 1-block pillar, straight ahead, in this model. From a 2-block run-up plain W + sprint
     * (with the edge jump) already lands: nothing to shape. From a 1- or 3-block run-up it misses (the takeoff phase
     * falls badly) and no keys-only shape at the current yaw lands it: the model says the jump cannot be made that way,
     * and the shaper says so instead of trying. Walking (no sprint) from a 2-block run-up: even the best sprint timing
     * falls short.
     */
    @Test
    void fourBlockGapToAPillarIsWhatTheModelSaysItIs() {
        Run two = plain(2, 4, 0, true);
        assertTrue(two.onTarget, "2-block run-up, sprinting: the plain jump lands the 4-block gap");
        Run twoShaped = shaped(2, 4, 0, true);
        assertTrue(twoShaped.onTarget);
        assertEquals(0, twoShaped.strafeTicks + twoShaped.sprintRequests, "nothing shaped when it lands");

        for (int run : new int[]{1, 3}) {
            assertFalse(plain(run, 4, 0, true).onTarget, run + "-block run-up, sprinting: plain misses");
            Run r = shaped(run, 4, 0, true);
            assertTrue(r.neededSeen, "the shaper looked");
            assertFalse(r.foundSeen, "and found no shape that lands (model verdict, see the notes)");
            assertFalse(r.onTarget);
        }
        Run walk = shaped(2, 4, 0, false);
        assertTrue(walk.neededSeen);
        assertFalse(walk.onTarget, "walking from 2 blocks: no sprint timing makes 4 blocks");
    }

    @Test
    void aStrafeKeyLandsAPillarThePlainRunUpMisses() {
        // 3-block gap, pillar one block to the left of the run (+X), yaw 0, sprinting from a 2-block run-up
        Run off = plain(2, 3, 1, true);
        assertFalse(off.onTarget, "plain W + sprint (with edge jump and air steering) misses the pillar");
        Run on = shaped(2, 3, 1, true);
        assertTrue(on.onTarget, "the shaped takeoff lands on it: " + on.end.x + ", " + on.end.z);
        assertTrue(on.strafeTicks >= 1);
        assertEquals(1, on.firstStrafe, "the left key (toward +X at yaw 0)");
        assertTrue(on.firstGain > 0.0, "a predicted gain in blocks");
        assertEquals(0, on.sprintRequests, "already sprinting");
        assertEquals(0.0f, on.maxYawOff, "keys only: the shape uses the current yaw");

        Run mirror = shaped(2, 3, -1, true);
        assertTrue(mirror.onTarget);
        assertEquals(-1, mirror.firstStrafe, "the right key for a pillar to the right");
    }

    @Test
    void sprintTimingLandsAWalkingRunUpAndPicksTheFastestTakeoff() {
        Run off = plain(2, 3, 0, false);
        assertFalse(off.onTarget, "walking, the jump falls short");
        Run on = shaped(2, 3, 0, false);
        assertTrue(on.onTarget, "sprint engaged for the user lands it");
        assertEquals(1, on.sprintRequests, "one sprint tick authored (vanilla keeps sprinting)");
        assertEquals(0, on.strafeTicks);

        // brute force at the first tick a sprint shape was chosen: its engage tick has the highest takeoff speed
        MovementPhysics at = on.firstSprintShapeState;
        assertNotNull(at);
        Grid g = course(2, 3, 0);
        JumpSearch search = new JumpSearch();
        search.setTarget(0, 19, 4);
        TakeoffShaper sh = new TakeoffShaper();
        assertTrue(sh.evaluate(at, 0f, false, 1, 0, 0.0, false, true, false, 20f, search, g));
        int best = -1;
        double bestSpeed = -1.0;
        for (int k = 0; k <= sh.plainJumpTick(); k++) {
            double v = sh.sprintCandidateSpeed(at, 0f, 1, k, false, 20f, search, g);
            if (!Double.isNaN(v) && v > bestSpeed + 1.0e-9) {
                bestSpeed = v;
                best = k;
            }
        }
        assertTrue(sh.evaluate(at, 0f, false, 1, 0, 0.0, false, true, false, 20f, search, g));
        assertEquals(best, sh.sprintTick(), "the engage tick that maximises takeoff speed among those that land");
        assertEquals(bestSpeed, sh.shapeTakeoffSpeed(), 1.0e-12);
        assertTrue(sh.shapeTakeoffSpeed() > sh.plainTakeoffSpeed(), "faster than the walking takeoff");
    }

    @Test
    void noShapingWhenThePlainJumpLands() {
        // {run-up, gap, dx, sprinting}
        int[][] cases = {{2, 3, 0, 1}, {4, 3, 0, 1}, {2, 4, 0, 1}, {2, 2, 0, 0}, {4, 1, 0, 0}};
        for (int[] c : cases) {
            boolean sk = c[3] == 1;
            assertTrue(plain(c[0], c[1], c[2], sk).onTarget, "precondition: plain lands " + c[0] + "/" + c[1]);
            Run r = shaped(c[0], c[1], c[2], sk);
            assertTrue(r.onTarget);
            assertFalse(r.foundSeen, "no shape chosen when the user's own jump lands");
            assertEquals(0, r.strafeTicks + r.sprintRequests);
        }
    }

    @Test
    void stillMouseOrLegitNeverNeedsAYawChange() {
        for (int run = 1; run <= 4; run++) {
            for (int gap = 2; gap <= 4; gap++) {
                for (int dx = -2; dx <= 2; dx++) {
                    for (boolean sk : new boolean[]{true, false}) {
                        // Legit: no yaw shapes allowed, mouse moving
                        MovementAssistBrain legit = new MovementAssistBrain();
                        legit.shapeYawAllowed = false;
                        Run a = play(legit, run, gap, dx, sk, 0, 6.0);
                        assertEquals(0, a.yawShapeTicks);
                        assertEquals(0.0f, a.maxYawOff, "Legit: every shape works at the current yaw");
                        // turn gain allowed, but a still mouse
                        MovementAssistBrain still = new MovementAssistBrain();
                        still.shapeYawAllowed = true;
                        Run b = play(still, run, gap, dx, sk, 0, 0.0);
                        assertEquals(0, b.yawShapeTicks);
                        assertEquals(0.0f, b.maxYawOff, "still mouse: every shape works at the current yaw");
                    }
                }
            }
        }
    }

    @Test
    void neverOverridesAHeldUserKey() {
        // the user holds right (D) toward nothing useful while the pillar is to the left: the strafe axis is theirs
        Run held = play(new MovementAssistBrain(), 2, 3, 1, true, -1, 0.0);
        assertEquals(0, held.keyOverrides, "no held key changed on the ground");
        assertEquals(0, held.strafeTicks, "no strafe filled over a held strafe key");
        // sprinting already: sprint is never requested; forward is never touched on the ground
        for (int dx = -2; dx <= 2; dx++) {
            Run r = shaped(2, 3, dx, true);
            assertEquals(0, r.sprintRequests);
            assertEquals(0, r.keyOverrides);
        }
    }

    @Test
    void safeWalkVetoesAShapeThatLandsWithFallDamage() {
        // the pillar is 3 blocks below the run-up: any landing on it costs health
        Grid g = new Grid();
        g.fill(-3, 19, -2, 3, 19, 0);
        g.fill(1, 16, 5, 1, 16, 5);
        g.fill(-40, -40, -40, 79, -40, 79);
        JumpSearch search = new JumpSearch();
        search.setTarget(1, 16, 5);
        MovementPhysics at = new MovementPhysics().set(0.5, 20, 0.8, 0, -0.0784, 0.15, true);
        TakeoffShaper sh = new TakeoffShaper();
        assertTrue(sh.evaluate(at, 0f, true, 1, 0, 0.0, false, false, false, 20f, search, g),
                "without SafeWalk a shape lands on the low pillar");
        assertTrue(sh.shapeMargin() > 0.0);
        assertFalse(sh.evaluate(at, 0f, true, 1, 0, 0.0, false, false, true, 20f, search, g),
                "with SafeWalk a damaging shaped landing is invalid");

        MovementAssistBrain b = new MovementAssistBrain();
        b.safeOnly = true;
        b.setIntentTarget(1, 16, 5, 0.9);
        b.decide(at, 0f, true, 1, 0, false, 20f, g);
        assertFalse(b.takeoffShaped());
        assertFalse(b.sprint());
    }

    @Test
    void anExhaustedBudgetDisablesShaping() {
        MovementAssistBrain zero = new MovementAssistBrain();
        zero.budget().setCap(0);
        Run r = play(zero, 2, 3, 1, true, 0, 0.0);
        assertEquals(0, r.strafeTicks, "cap 0: no corrections at all");
        assertFalse(r.onTarget);

        MovementAssistBrain used = new MovementAssistBrain();
        for (int i = 0; i < AuthorshipBudget.DEFAULT_CAP; i++) {
            used.budget().noteUse(); // this jump's corrections are spent before the approach
        }
        Run u = play(used, 2, 3, 1, true, 0, 0.0);
        assertEquals(0, u.strafeTicks, "budget spent: no shaped ticks");

        MovementAssistBrain walk = new MovementAssistBrain();
        walk.budget().setCap(0);
        assertEquals(0, play(walk, 2, 3, 0, false, 0, 0.0).sprintRequests, "no sprint either");
    }

    @Test
    void settingOffOrNoTargetMeansNoShaping() {
        MovementAssistBrain off = new MovementAssistBrain();
        off.takeoffShaping = false;
        Run r = play(off, 2, 3, 1, true, 0, 0.0);
        assertEquals(0, r.strafeTicks + r.sprintRequests);

        MovementAssistBrain noTarget = new MovementAssistBrain();
        MovementPhysics p = new MovementPhysics().set(0.5, 20, 0.4, 0, -0.0784, 0.15, true);
        noTarget.decide(p, 0f, false, 1, 0, false, 20f, course(2, 3, 0));
        assertFalse(noTarget.takeoffShaped());
        assertFalse(noTarget.sprint());
    }
}
