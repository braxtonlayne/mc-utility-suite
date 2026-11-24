package com.utilitysuite.combat.assistant;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.SharedGrant;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The co-pilot's decisions: co-steer bounds and hand-back, W-only spacing nudges, sprint resets, the swing gate. */
class CoPilotPlannerTest {

    CoPilotPlanner p;

    @BeforeEach
    void setUp() {
        p = new CoPilotPlanner();
        // a duel at 2.9 blocks, target 20 degrees right of the view, charging weapon, crosshair off the target
        p.yaw = 0f;
        p.aimYaw = 20f;
        p.distance = 2.9;
        p.inReach = true;
        p.crosshairOnTarget = false;
        p.attackStrength = 0.4f;
        p.jitter = 0;
    }

    void step(int n) {
        for (int i = 0; i < n; i++) {
            p.tick++;
            p.decide();
        }
    }

    // ---- co-steer ---------------------------------------------------------------------------------------------------

    @Test
    void steersTowardANearTargetInsideTheConeWithLowAuthority() {
        step(1);
        assertTrue(p.steer());
        assertEquals(20f, p.steerYaw());
        assertTrue(p.authority() >= CoPilotPlanner.AUTH_MIN && p.authority() <= CoPilotPlanner.AUTH_MAX);
        assertEquals(CoPilotPlanner.WINDOW_BUDGET_DEG, p.allowance(), 1e-9);
    }

    @Test
    void authorityRisesTowardTheSwingMomentAndNeverPassesTheCap() {
        p.attackStrength = 0f;
        step(1);
        assertEquals(CoPilotPlanner.AUTH_MIN, p.authority(), 1e-9, "nothing charged: the low end");
        p.attackStrength = 1f;
        step(1);
        assertEquals(CoPilotPlanner.AUTH_MAX, p.authority(), 1e-9, "charged and near reach: the high end");
        p.distance = 5.5; // inside the steer range, well outside reach
        step(1);
        assertEquals(CoPilotPlanner.AUTH_MIN, p.authority(), 1e-9);
    }

    @Test
    void neverTurnsTheUserRoundOrReachesFarOrBehindWalls() {
        p.aimYaw = 150f; // behind: the user looks (and moves) elsewhere
        step(1);
        assertFalse(p.steer());
        p.aimYaw = 20f;
        p.distance = p.spacingMax + CoPilotPlanner.STEER_RANGE_EXTRA + 0.1;
        step(1);
        assertFalse(p.steer());
        p.distance = 2.9;
        p.lineOfSight = false;
        step(1);
        assertFalse(p.steer());
        p.lineOfSight = true;
        p.aimYaw = 0.2f; // already on it
        step(1);
        assertFalse(p.steer());
        p.aimYaw = 20f;
        p.steerOn = false;
        step(1);
        assertFalse(p.steer());
    }

    @Test
    void theBudgetIsPerSwingCycle() {
        step(1);
        p.noteSteerAdded(12.0);
        step(1);
        assertEquals(CoPilotPlanner.WINDOW_BUDGET_DEG - 12.0, p.allowance(), 1e-9);
        p.noteSteerAdded(8.0);
        step(1);
        assertFalse(p.steer(), "the window's budget is spent");
        // a swing (charge from ready to spent) opens the next window
        p.attackStrength = 1f;
        step(1);
        p.attackStrength = 0.1f;
        step(1);
        assertTrue(p.steer());
        assertEquals(CoPilotPlanner.WINDOW_BUDGET_DEG, p.allowance(), 1e-9);
    }

    @Test
    void theBudgetAlsoRefreshesAfterTheWindowWithoutASwing() {
        step(1);
        p.noteSteerAdded(CoPilotPlanner.WINDOW_BUDGET_DEG);
        step(1);
        assertFalse(p.steer());
        step(CoPilotPlanner.WINDOW_MAX_TICKS);
        assertTrue(p.steer());
    }

    @Test
    void theHandAgainstItHandsTheYawBack() {
        step(3);
        assertTrue(p.steer());
        p.opposingTotal = CoPilotPlanner.OPPOSE_DEG + 1.0; // a firm flick against it
        step(1);
        assertFalse(p.steer());
        assertTrue(p.holdOffTicks() > 0);
        step(CoPilotPlanner.HOLD_OFF_TICKS - 2);
        assertFalse(p.steer(), "still the user's");
        step(3);
        assertTrue(p.steer(), "offered again after the hold-off");
    }

    @Test
    void slowDriftAgainstItIsNoOverride() {
        for (int i = 0; i < 40; i++) {
            p.opposingTotal += 0.5; // under 4 degrees in any 5 ticks
            step(1);
            assertTrue(p.steer());
        }
    }

    // ---- spacing nudge ----------------------------------------------------------------------------------------------

    @Test
    void crowdingWithWHeldIsANudgeOfAFewTicksThenARest() {
        p.aimYaw = 0f;
        p.forwardHeld = true;
        p.forwardPressTick = 0L;
        p.distance = 1.2;
        int off = 0;
        for (int i = 0; i < CoPilotPlanner.NUDGE_MAX_TICKS; i++) {
            step(1);
            assertEquals(SharedGrant.OFF, p.forward());
            off++;
        }
        for (int i = 0; i < CoPilotPlanner.NUDGE_REST_TICKS; i++) {
            step(1);
            assertEquals(SharedGrant.KEEP, p.forward(), "rest tick " + i);
        }
        step(1);
        assertEquals(SharedGrant.OFF, p.forward(), "nudges again after the rest");
        assertEquals(off + 1, p.nudgeTicks());
    }

    @Test
    void neverPressesAKeyAndOnlyNudgesWhenTheUserRunsAtIt() {
        p.aimYaw = 0f;
        p.distance = 1.2;
        p.forwardHeld = false;
        step(5);
        assertEquals(SharedGrant.KEEP, p.forward(), "no W held: nothing to hold back");
        p.forwardHeld = true;
        p.aimYaw = 90f; // W held, but running past it
        step(1);
        assertEquals(SharedGrant.KEEP, p.forward());
        p.aimYaw = 0f;
        p.spacingOn = false;
        step(1);
        assertEquals(SharedGrant.KEEP, p.forward());
        p.spacingOn = true;
        p.distance = 2.0; // between crowding and spacing min, nothing charged against us
        step(1);
        assertEquals(SharedGrant.KEEP, p.forward());
    }

    @Test
    void walkingIntoTheirChargedHitWithOurSwingStillChargingIsHeldBack() {
        p.aimYaw = 0f;
        p.forwardHeld = true;
        p.distance = 2.5;
        p.oppCharged = true;
        p.attackStrength = 0.5f;
        step(1);
        assertEquals(SharedGrant.OFF, p.forward());
        p.attackStrength = 1f; // our hit is ready: walking in is the play
        step(1);
        assertEquals(SharedGrant.KEEP, p.forward());
    }

    @Test
    void aFreshWPressDuringANudgeMakesWTheUsers() {
        p.aimYaw = 0f;
        p.forwardHeld = true;
        p.forwardPressTick = 0L;
        p.distance = 1.2;
        step(2);
        assertEquals(SharedGrant.OFF, p.forward());
        p.forwardPressTick = p.tick + 1; // released and pressed again
        step(1);
        assertEquals(SharedGrant.KEEP, p.forward());
        step(CoPilotPlanner.NUDGE_YIELD_TICKS - 2);
        assertEquals(SharedGrant.KEEP, p.forward(), "still the user's");
    }

    // ---- sprint reset -----------------------------------------------------------------------------------------------

    @Test
    void aHitWhileRunningAtItWithholdsSprintForOneOrTwoTicks() {
        p.aimYaw = 0f;
        p.forwardHeld = true;
        p.crosshairOnTarget = true;
        p.swingOn = false;
        p.attackStrength = 1f;
        step(1);
        assertFalse(p.withholdSprint());
        p.attackStrength = 0.05f; // the swing went out (the user's own click)
        p.jitter = 1;
        step(1);
        assertTrue(p.withholdSprint());
        step(1);
        assertTrue(p.withholdSprint(), "two ticks with this draw");
        step(1);
        assertFalse(p.withholdSprint(), "the sprint is the user's again");
    }

    @Test
    void noResetForAWhiffWhileBackingOffOrWithTheSettingOff() {
        p.aimYaw = 0f;
        p.swingOn = false;
        p.forwardHeld = true;
        p.crosshairOnTarget = false; // a whiff
        p.attackStrength = 1f;
        step(1);
        p.attackStrength = 0f;
        step(1);
        assertFalse(p.withholdSprint());

        p.crosshairOnTarget = true;
        p.forwardHeld = false; // backing off
        p.attackStrength = 1f;
        step(1);
        p.attackStrength = 0f;
        step(1);
        assertFalse(p.withholdSprint());

        p.forwardHeld = true;
        p.sprintReset = CombatSettings.SprintReset.OFF;
        p.attackStrength = 1f;
        step(1);
        p.attackStrength = 0f;
        step(1);
        assertFalse(p.withholdSprint());
    }

    // ---- swing ------------------------------------------------------------------------------------------------------

    @Test
    void swingsOnlyWithTheUsersCrosshairOnTheChargedTargetInReach() {
        p.attackStrength = 1f;
        p.crosshairOnTarget = false;
        step(3);
        assertFalse(p.swing(), "the crosshair is the user's: no hit on anything they do not look at");
        p.crosshairOnTarget = true;
        p.inReach = false;
        step(1);
        assertFalse(p.swing());
        p.inReach = true;
        p.attackStrength = 0.8f;
        step(1);
        assertFalse(p.swing(), "not charged");
        p.attackStrength = 1f;
        step(1);
        assertTrue(p.swing());
        p.swingOn = false;
        step(1);
        assertFalse(p.swing());
    }

    @Test
    void noSwingIntoAFacingShieldWhileUsingAnItemOrWhileTheUserClicks() {
        p.attackStrength = 1f;
        p.crosshairOnTarget = true;
        p.shieldFacing = true;
        step(2);
        assertFalse(p.swing());
        p.shieldFacing = false;
        p.usingItem = true;
        step(2);
        assertFalse(p.swing(), "the user blocks or eats: USE is theirs");
        p.usingItem = false;
        p.manualClick = true;
        step(2);
        assertFalse(p.swing(), "the user's own click wins");
        p.manualClick = false;
        step(1);
        assertTrue(p.swing());
    }

    @Test
    void theReactionJitterDelaysTheFirstSwing() {
        p.attackStrength = 1f;
        p.crosshairOnTarget = true;
        p.jitter = 2;
        step(1);
        assertFalse(p.swing());
        step(1);
        assertFalse(p.swing());
        step(1);
        assertTrue(p.swing());
    }

    @Test
    void aRisingHopWaitsForTheFallButNotForever() {
        p.attackStrength = 1f;
        p.crosshairOnTarget = true;
        p.onGround = false;
        p.verticalSpeed = 0.3;
        step(1);
        assertFalse(p.swing(), "rising: the crit comes on the way down");
        p.verticalSpeed = -0.1;
        p.fallingForCrit = true;
        step(1);
        assertTrue(p.swing());

        p.fallingForCrit = false;
        p.verticalSpeed = 0.3; // stuck rising (a ladder-like case): the wait is capped
        int waited = 0;
        do {
            step(1);
            waited++;
        } while (!p.swing() && waited < 50);
        assertTrue(p.swing());
        assertTrue(waited <= CoPilotPlanner.MAX_CRIT_WAIT + 1);

        p.critPossible = false; // sprinting: no crit to wait for
        step(1);
        assertTrue(p.swing());
    }

    @Test
    void decideAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        p.forwardHeld = true;
        p.crosshairOnTarget = true;
        long bytes = AllocationProbe.measure(50_000, () -> {
            p.tick++;
            p.attackStrength = (p.tick & 15) == 0 ? 0.1f : 1f;
            p.distance = (p.tick & 7) == 0 ? 1.2 : 2.9;
            p.opposingTotal += (p.tick & 63) == 0 ? 5.0 : 0.0;
            p.noteSteerAdded(0.3);
            p.decide();
        });
        assertTrue(bytes < 1_024, "decide allocated " + bytes + " bytes over 50k ticks");
    }
}
