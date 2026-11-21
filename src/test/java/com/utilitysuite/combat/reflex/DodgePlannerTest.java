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
}
