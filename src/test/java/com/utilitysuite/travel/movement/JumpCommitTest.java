package com.utilitysuite.travel.movement;

import com.utilitysuite.travel.movement.JumpCommit.Mode;
import com.utilitysuite.travel.movement.JumpCommit.Phase;
import org.junit.jupiter.api.Test;

import static com.utilitysuite.travel.movement.LandingHold.BACK;
import static com.utilitysuite.travel.movement.LandingHold.FORWARD;
import static com.utilitysuite.travel.movement.LandingHold.LEFT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link JumpCommit} transitions, one test per edge of the state machine, and the GUIDE / CARRY output rules. */
class JumpCommitTest {

    private static final int KEEP = JumpCommit.KEEP;

    /** Ground tick. */
    private static void ground(JumpCommit c, int keys, boolean plan) {
        c.tick(true, false, false, keys, false, 0f, 0, plan, false, false, 1, false);
    }

    private static void takeoff(JumpCommit c, int keys) {
        c.tick(false, true, false, keys, false, 0f, 0, true, false, false, 1, false);
    }

    private static void air(JumpCommit c, int keys, boolean plan) {
        c.tick(false, false, false, keys, false, 0f, 0, plan, false, false, 1, false);
    }

    private static void touchdown(JumpCommit c, int keys, boolean narrow) {
        c.tick(true, false, true, keys, false, 0f, 0, true, narrow, narrow, 1, false);
    }

    /** IDLE -> ARMED -> TAKEOFF -> FLIGHT, holding W from the run-up. */
    private static JumpCommit inFlight(Mode mode) {
        JumpCommit c = new JumpCommit();
        c.mode = mode;
        ground(c, FORWARD, true);
        assertEquals(Phase.ARMED, c.phase());
        takeoff(c, FORWARD);
        assertEquals(Phase.TAKEOFF, c.phase());
        air(c, FORWARD, true);
        assertEquals(Phase.FLIGHT, c.phase());
        return c;
    }

    @Test
    void idleToArmedNeedsAPlanAndKeysAndNotNudge() {
        JumpCommit c = new JumpCommit();
        ground(c, FORWARD, false);
        assertEquals(Phase.IDLE, c.phase(), "no plan");
        ground(c, 0, true);
        assertEquals(Phase.IDLE, c.phase(), "no movement key");
        ground(c, FORWARD, true);
        assertEquals(Phase.ARMED, c.phase());
        JumpCommit n = new JumpCommit();
        n.mode = Mode.NUDGE;
        ground(n, FORWARD, true);
        takeoff(n, FORWARD);
        assertEquals(Phase.IDLE, n.phase(), "Corrections never commits");
    }

    @Test
    void armedBackToIdleWhenThePlanIsLost() {
        JumpCommit c = new JumpCommit();
        ground(c, FORWARD, true);
        ground(c, FORWARD, false);
        assertEquals(Phase.IDLE, c.phase());
        assertEquals(0f, c.authority());
    }

    @Test
    void idleStraightToTakeoffWhenTheJumpComesFirst() {
        JumpCommit c = new JumpCommit();
        ground(c, FORWARD, false);
        takeoff(c, FORWARD);
        assertEquals(Phase.TAKEOFF, c.phase());
        assertEquals(FORWARD, c.staleMask(), "W was down before the takeoff: stale");
    }

    @Test
    void narrowTouchdownSettlesThenReleasesOverSixTicks() {
        JumpCommit c = inFlight(Mode.GUIDE);
        touchdown(c, FORWARD, true);
        assertTrue(c.touchdownPassed());
        assertEquals(Phase.SETTLE, c.phase());
        c.tick(true, false, false, FORWARD, false, 0f, 0, true, false, true, 1, false);
        assertEquals(Phase.SETTLE, c.phase(), "hold still on");
        c.tick(true, false, false, FORWARD, false, 0f, 0, true, false, false, 1, false);
        assertEquals(Phase.RELEASE, c.phase());
        assertEquals(JumpCommit.RELEASE_TICKS_GUIDE, c.releaseTotal());
        float prev = c.authority();
        int ticks = 0;
        while (c.phase() == Phase.RELEASE) {
            ground(c, FORWARD, false);
            assertTrue(c.authority() <= prev + 1e-6f, "authority only drains");
            prev = c.authority();
            ticks++;
        }
        assertEquals(JumpCommit.RELEASE_TICKS_GUIDE, ticks);
        assertEquals(Phase.IDLE, c.phase());
    }

    @Test
    void wideTouchdownReleasesAtOnceAndCarryTakesEightTicks() {
        JumpCommit c = inFlight(Mode.CARRY);
        touchdown(c, FORWARD, false);
        assertEquals(Phase.RELEASE, c.phase());
        assertEquals(JumpCommit.RELEASE_TICKS_CARRY, c.releaseTotal());
        assertTrue(c.edgeGuardHorizon() >= 2 && c.edgeGuardHorizon() <= 6);
    }

    @Test
    void sneakAbortsAndHandsBackInTwoTicks() {
        JumpCommit c = inFlight(Mode.CARRY);
        c.tick(false, false, false, FORWARD, true, 0f, 0, true, false, false, 1, false);
        assertEquals(Phase.ABORT, c.phase());
        assertTrue(c.hardOverrideThisTick());
        assertFalse(c.driving(), "nothing is driven from the abort tick on");
        air(c, FORWARD, true);
        assertEquals(Phase.RELEASE, c.phase());
        assertEquals(JumpCommit.RELEASE_TICKS_HARD, c.releaseTotal());
        assertEquals(0, c.edgeGuardHorizon(), "after a hard override nothing is withheld");
        air(c, FORWARD, true);
        air(c, FORWARD, true);
        assertEquals(Phase.IDLE, c.phase());
    }

    @Test
    void planLostInFlightAborts() {
        JumpCommit c = inFlight(Mode.GUIDE);
        air(c, FORWARD, false);
        assertEquals(Phase.ABORT, c.phase());
    }

    @Test
    void backAgainstAForwardPlanForTwoTicksAborts() {
        JumpCommit c = inFlight(Mode.GUIDE);
        air(c, FORWARD | BACK, true);
        assertEquals(Phase.FLIGHT, c.phase(), "one tick of back: not yet");
        air(c, FORWARD | BACK, true);
        assertEquals(Phase.ABORT, c.phase());
    }

    @Test
    void aTurnFarOffTheCommitLineAborts() {
        JumpCommit c = inFlight(Mode.GUIDE);
        c.tick(false, false, false, FORWARD, false, 10f, 10, true, false, false, 1, false);
        assertEquals(Phase.FLIGHT, c.phase(), "10 degrees: a trim");
        c.tick(false, false, false, FORWARD, false, 30f, 15, true, false, false, 1, false);
        assertEquals(Phase.ABORT, c.phase(), "30 degrees off the line");
    }

    @Test
    void externalAbortsWinAtEveryPhase() {
        JumpCommit armed = new JumpCommit();
        ground(armed, FORWARD, true);
        armed.tick(true, false, false, FORWARD, false, 0f, 0, true, false, false, 1, true);
        assertEquals(Phase.ABORT, armed.phase());
        JumpCommit flying = inFlight(Mode.GUIDE);
        flying.tick(false, false, false, FORWARD, false, 0f, 0, true, false, false, 1, true);
        assertEquals(Phase.ABORT, flying.phase());
        JumpCommit settling = inFlight(Mode.GUIDE);
        touchdown(settling, FORWARD, true);
        settling.abort();
        assertEquals(Phase.ABORT, settling.phase());
    }

    @Test
    void twoAxesTakenIsAHardOverride() {
        JumpCommit c = inFlight(Mode.GUIDE);
        int keys = FORWARD | LEFT;
        for (int t = 0; t < 3; t++) {
            air(c, keys, true);
        }
        assertEquals(Phase.FLIGHT, c.phase(), "one fresh axis (strafe) taken: the jump goes on");
        JumpCommit d = new JumpCommit();
        ground(d, 0, false);
        takeoff(d, 0);
        assertEquals(Phase.IDLE, d.phase(), "no key, no commitment");
        JumpCommit e = new JumpCommit();
        ground(e, FORWARD, true);
        takeoff(e, FORWARD);
        air(e, 0, true); // W released: forward axis free
        for (int t = 0; t < 3; t++) {
            // fresh back and fresh left held, with a plan that does not go forward (so only the axis rule applies)
            e.tick(false, false, false, BACK | LEFT, false, 0f, 0, true, false, false, 0, false);
        }
        assertEquals(Phase.ABORT, e.phase(), "both axes taken on the third tick");
    }

    /** A fresh key that agrees with the plan's key on its axis is not an override; one that differs still is. */
    @Test
    void aFreshKeyThatAgreesWithThePlanIsNotAnOverride() {
        for (Mode mode : new Mode[] {Mode.GUIDE, Mode.CARRY}) {
            JumpCommit c = inFlight(mode);
            for (int t = 0; t < 6; t++) {
                c.notePlan(1, 1);
                air(c, FORWARD | LEFT, true);
                assertFalse(c.overrideThisTick(), mode + ": the plan presses left too (tick " + t + ")");
            }
            assertEquals(Phase.FLIGHT, c.phase());
            assertNotEquals(KEEP, c.resolveAxis(1, 0, 1), mode + ": no axis was taken");
            // the plan let the key go on the last tick and the user keeps it: the axis is the user's
            c.notePlan(1, 0);
            air(c, FORWARD | LEFT, true);
            assertTrue(c.overrideThisTick(), mode + ": the key now differs from the plan");
            assertEquals(KEEP, c.resolveAxis(1, 0, 1), mode + ": the strafe axis is the user's");

            JumpCommit o = inFlight(mode);
            boolean taken = false;
            for (int t = 0; t < 3; t++) {
                o.notePlan(1, -1);
                air(o, FORWARD | LEFT, true);
                taken |= o.overrideThisTick();
            }
            assertTrue(taken, mode + ": a key against the plan takes its axis");

            JumpCommit n = inFlight(mode);
            taken = false;
            for (int t = 0; t < 3; t++) {
                air(n, FORWARD | LEFT, true); // no plan noted: as before
                taken |= n.overrideThisTick();
            }
            assertTrue(taken, mode + ": without a plan a held fresh key takes its axis");
        }
    }

    @Test
    void guideNeverOutputsAgainstAFreshKey() {
        JumpCommit c = inFlight(Mode.GUIDE);
        // W stale; now press A fresh and hold it for a while
        for (int t = 0; t < 6; t++) {
            air(c, FORWARD | LEFT, true);
            for (int plan = -1; plan <= 1; plan++) {
                assertEquals(KEEP, c.resolveAxis(1, 1, plan), "fresh left never replaced, plan " + plan);
            }
        }
        // the stale W may be withheld, never turned around
        assertEquals(0, c.resolveAxis(0, 1, 0));
        assertEquals(0, c.resolveAxis(0, 1, -1));
        assertEquals(KEEP, c.resolveAxis(0, 1, 1));
        // free axes are filled
        JumpCommit d = inFlight(Mode.GUIDE);
        assertEquals(-1, d.resolveAxis(1, 0, -1));
        assertEquals(KEEP, d.resolveAxis(1, 0, KEEP));
        // a stale key released and pressed again is fresh
        air(d, 0, true);
        air(d, FORWARD, true);
        assertEquals(KEEP, d.resolveAxis(0, 1, 0));
        // the limiter never brings a kept choice back over a fresh key
        JumpCommit e = inFlight(Mode.GUIDE);
        e.limit(KEEP, -1, 1, 0);
        assertEquals(-1, e.limitedStrafe());
        air(e, FORWARD | LEFT, true); // fresh left the next tick
        e.limit(KEEP, KEEP, 1, 1);
        assertNotEquals(-1, e.limitedStrafe(), "the kept right fill yields to the fresh left");
    }

    @Test
    void carryTrimsTheFirstTwoTicksOfAFreshKeyThenItIsTheUsers() {
        JumpCommit c = inFlight(Mode.CARRY);
        air(c, FORWARD | LEFT, true);
        assertEquals(-1, c.resolveAxis(1, 1, -1), "tick 1 of a fresh key: the plan's key");
        air(c, FORWARD | LEFT, true);
        assertEquals(-1, c.resolveAxis(1, 1, -1), "tick 2: still a trim");
        air(c, FORWARD | LEFT, true);
        assertEquals(KEEP, c.resolveAxis(1, 1, -1), "tick 3: the user's");
        assertEquals(-1, c.resolveAxis(0, 1, -1), "CARRY may turn a stale key around");
    }

    @Test
    void keyChangeLimitsAndReactionDelay() {
        JumpCommit c = inFlight(Mode.CARRY);
        c.maxKeyChanges = 2;
        c.minChangeHoldTicks = 1;
        c.limit(KEEP, 1, 0, 0);
        assertEquals(1, c.limitedStrafe());
        c.limit(KEEP, -1, 0, 0);
        assertEquals(-1, c.limitedStrafe());
        c.limit(0, 1, 0, 0);
        assertEquals(KEEP, c.limitedStrafe(), "third change refused at a limit of 2");
        assertEquals(2, c.keyChanges());
        JumpCommit d = new JumpCommit();
        d.mode = Mode.CARRY;
        d.reactionTicks = 3;
        ground(d, FORWARD, true);
        takeoff(d, FORWARD);
        air(d, FORWARD, true);
        d.limit(KEEP, 1, 1, 0);
        assertEquals(KEEP, d.limitedStrafe(), "inside the reaction delay");
        air(d, FORWARD, true);
        air(d, FORWARD, true);
        d.limit(KEEP, 1, 1, 0);
        assertEquals(1, d.limitedStrafe(), "after the reaction delay");
    }

    @Test
    void resetForgetsWithoutAHandBack() {
        JumpCommit c = inFlight(Mode.GUIDE);
        c.reset();
        assertEquals(Phase.IDLE, c.phase());
        assertEquals(0, c.releaseLeft());
        assertEquals(0f, c.authority());
    }
}
