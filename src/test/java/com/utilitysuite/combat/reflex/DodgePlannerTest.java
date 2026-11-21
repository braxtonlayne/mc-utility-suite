package com.utilitysuite.combat.reflex;

import com.utilitysuite.core.api.SimKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Player at the origin facing +Z (yaw 0): forward = +Z, strafe-left = +X, strafe-right = -X. */
class DodgePlannerTest {

    private final DodgePlanner p = new DodgePlanner();

    @Test
    void arrowPassingOnTheLeftDodgesRight() {
        // arrives from the front, passes 0.5 to the player's left (+X)
        assertTrue(p.planDodge(0.5, 0, 10, 0, 0, -2, 0f, false, false, false, false));
        assertEquals(SimKey.RIGHT, p.key());
        assertEquals(5, p.arrivalTick());
        assertEquals(0, p.startInTicks());
    }

    @Test
    void arrowPassingOnTheRightDodgesLeft() {
        assertTrue(p.planDodge(-0.5, 0, 10, 0, 0, -2, 0f, false, false, false, false));
        assertEquals(SimKey.LEFT, p.key());
    }

    @Test
    void blockedSideCrossesThePathInstead() {
        assertTrue(p.planDodge(0.5, 0, 10, 0, 0, -2, 0f, false, false, false, true));
        assertEquals(SimKey.LEFT, p.key());
    }

    @Test
    void bothSidesBlockedMeansNoDodge() {
        assertFalse(p.planDodge(0.0, 0, 10, 0, 0, -2, 0f, false, false, true, true));
    }

    @Test
    void noDodgeWhenTheArrowAlreadyMisses() {
        assertFalse(p.planDodge(2.0, 0, 10, 0, 0, -2, 0f, false, false, false, false));
        assertNull(p.key());
    }

    @Test
    void noDodgeForAnArrowFlyingAway() {
        assertFalse(p.planDodge(0, 0, 10, 0, 0, 2, 0f, false, false, false, false));
    }

    @Test
    void slowFarArrowWaitsBeforeTapping() {
        assertTrue(p.planDodge(0.0, 0, 25, 0, 0, -1, 0f, false, false, false, false));
        assertEquals(25, p.arrivalTick());
        assertTrue(p.startInTicks() > 0, "start later, not now");
        assertTrue(p.reachable());
        assertTrue(p.startInTicks() + p.tapTicks() < p.arrivalTick() + 1);
    }

    @Test
    void arrowFromTheSideUsesForwardOrBack() {
        // from +X (the player's left) flying toward -X, passing 0.4 in front (+Z)
        assertTrue(p.planDodge(10, 0, 0.4, -2, 0, 0, 0f, false, false, false, false));
        assertEquals(SimKey.BACK, p.key());
    }

    @Test
    void escapeBacksAwayFromACreeperInFront() {
        assertTrue(p.planEscape(0, 2, 20, 6.0, 0f, false, false, false, false));
        assertEquals(SimKey.BACK, p.key());
        assertNull(p.key2());
        assertFalse(p.sprint(), "no backward sprint");
        assertTrue(p.reachable());
    }

    @Test
    void escapeSprintsForwardFromACreeperBehind() {
        assertTrue(p.planEscape(0, -2, 20, 6.0, 0f, false, false, false, false));
        assertEquals(SimKey.FORWARD, p.key());
        assertTrue(p.sprint());
    }

    @Test
    void escapeNeverStepsOffALedge() {
        assertFalse(p.planEscape(0, 2, 20, 6.0, 0f, false, true, false, false));
    }

    @Test
    void escapeDiagonalUsesTwoKeys() {
        // creeper front-left: go back-right
        assertTrue(p.planEscape(1.5, 1.5, 20, 6.0, 0f, false, false, false, false));
        assertEquals(SimKey.BACK, p.key());
        assertEquals(SimKey.RIGHT, p.key2());
    }

    @Test
    void noEscapeOutsideTheBlastRadius() {
        assertFalse(p.planEscape(0, 7, 20, 6.0, 0f, false, false, false, false));
    }

    @Test
    void deadCentreArrowWithLongLateralNeedClampsTapAndIsUnreachable() {
        // needs 1.35 blocks of strafe (about 12 ticks) but arrives in 5: the tap clamps to MAX_TAP and is flagged
        assertTrue(p.planDodge(0, 0, 10, 0, 0, -2, 0f, false, false, false, false));
        assertEquals(DodgePlanner.MAX_TAP, p.tapTicks());
        assertEquals(0, p.startInTicks());
        assertFalse(p.reachable());
    }

    @Test
    void arrowArrivingExactlyAtTheHorizonIsPlannedButOneTickLaterIsNot() {
        // t = 30 exactly (HORIZON_TICKS): planned
        assertTrue(p.planDodge(0.5, 0, 60, 0, 0, -2, 0f, false, false, false, false));
        assertEquals(30, p.arrivalTick());
        // t = 30.25: re-planned next tick instead
        assertFalse(p.planDodge(0.5, 0, 60.5, 0, 0, -2, 0f, false, false, false, false));
    }

    @Test
    void missExactlyAtHitRadiusStillHits() {
        // closest approach 1.2 (HIT_RADIUS) is still a hit; 1.25 misses
        assertTrue(p.planDodge(0, 1.2, 10, 0, 0, -2, 0f, false, false, false, false));
        assertFalse(p.planDodge(0, 1.25, 10, 0, 0, -2, 0f, false, false, false, false));
    }

    @Test
    void zeroAndStraightDownVelocityDoNotDivideByZero() {
        assertFalse(p.planDodge(0, 0, 10, 0, 0, 0, 0f, false, false, false, false));
        assertFalse(Double.isNaN(p.missDistance()));
        assertEquals(0.0, p.missDistance());
        // dropping straight down: horizontal speed is zero, nothing to strafe out of
        assertFalse(p.planDodge(0, 3, 0, 0, -1, 0, 0f, false, false, false, false));
        assertFalse(Double.isNaN(p.missDistance()));
    }

    @Test
    void sideArrowWithForwardAndBackBlockedGivesNoDodge() {
        // arrow flies along -X and passes in front of the player; only forward/back could carry it clear,
        // and both are hazards, so no strafe is chosen even though left and right are open
        assertFalse(p.planDodge(10, 0, 0.4, -2, 0, 0, 0f, true, true, false, false));
        assertNull(p.key());
    }

    @Test
    void repeatedPlansWithTheSameInputsAgreeAndDoNotLeakState() {
        assertTrue(p.planDodge(0.5, 0, 10, 0, 0, -2, 0f, false, false, false, false));
        SimKey firstKey = p.key();
        int firstTap = p.tapTicks();
        int firstStart = p.startInTicks();
        int firstArrival = p.arrivalTick();

        // a plan that does not dodge must clear the outputs of the one before it
        assertFalse(p.planDodge(2.0, 0, 10, 0, 0, -2, 0f, false, false, false, false));
        assertNull(p.key());
        assertEquals(0, p.tapTicks());

        assertTrue(p.planDodge(0.5, 0, 10, 0, 0, -2, 0f, false, false, false, false));
        assertEquals(firstKey, p.key());
        assertEquals(firstTap, p.tapTicks());
        assertEquals(firstStart, p.startInTicks());
        assertEquals(firstArrival, p.arrivalTick());
    }

    @Test
    void escapeExactlyAtTheBlastRadiusIsOutside() {
        assertFalse(p.planEscape(0, 6.0, 20, 6.0, 0f, false, false, false, false));
        assertTrue(p.planEscape(0, 5.999, 20, 6.0, 0f, false, false, false, false));
    }

    @Test
    void diagonalEscapeWithBackBlockedUsesOnlyTheLateralKey() {
        // creeper front-left, back is a ledge: back is dropped and the lateral key (right) is used alone
        assertTrue(p.planEscape(1.5, 1.5, 20, 6.0, 0f, false, true, false, false));
        assertEquals(SimKey.RIGHT, p.key());
        assertNull(p.key2());
        assertFalse(p.sprint());
    }
}
