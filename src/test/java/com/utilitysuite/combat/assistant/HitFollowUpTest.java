package com.utilitysuite.combat.assistant;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HitFollowUpTest {

    @Test
    void resetNeedsAConfirmedHit() {
        HitFollowUp f = new HitFollowUp();
        assertFalse(f.confirmed(5), "nothing armed, nothing to reset");
        f.armReset(10);
        assertTrue(f.confirmed(11));
        assertFalse(f.confirmed(11), "the confirmation consumes the arm");
    }

    @Test
    void aMissExpiresTheArm() {
        HitFollowUp f = new HitFollowUp();
        f.armReset(10);
        f.expire(10 + HitFollowUp.CONFIRM_WINDOW);
        assertTrue(f.armed());
        f.expire(10 + HitFollowUp.CONFIRM_WINDOW + 1);
        assertFalse(f.armed());
        assertFalse(f.confirmed(20));
    }

    @Test
    void lateConfirmationDoesNotCount() {
        HitFollowUp f = new HitFollowUp();
        f.armReset(10);
        assertFalse(f.confirmed(10 + HitFollowUp.CONFIRM_WINDOW + 1));
    }

    @Test
    void hurtTimeRisingEdgeOnlyOnTheSameTarget() {
        HitFollowUp f = new HitFollowUp();
        assertFalse(f.hurtRose(3, 0));
        assertTrue(f.hurtRose(3, 10));
        assertFalse(f.hurtRose(3, 9));
        assertFalse(f.hurtRose(4, 10), "a new target is no edge");
    }

    @Test
    void landingStrafeAlternatesAndExpires() {
        HitFollowUp f = new HitFollowUp();
        f.noteSwing(false);
        assertTrue(f.critPending());
        f.onGround(true, true, 4);
        assertTrue(f.landingActive());
        int first = f.landingSide();
        assertNotEquals(StrafePlanner.NONE, first);
        for (int i = 0; i < 4; i++) {
            assertTrue(f.landingActive());
            f.spendLanding();
        }
        assertFalse(f.landingActive());
        f.noteSwing(false);
        f.onGround(true, true, 3);
        assertEquals(-first, f.landingSide(), "the next landing leans the other way");
    }

    @Test
    void windowLengthIsClamped() {
        HitFollowUp f = new HitFollowUp();
        f.noteSwing(false);
        f.onGround(true, true, 99);
        int n = 0;
        while (f.landingActive()) {
            f.spendLanding();
            n++;
        }
        assertEquals(HitFollowUp.LANDING_TICKS_MAX, n);
    }

    @Test
    void groundSwingMakesNoLandingStrafe() {
        HitFollowUp f = new HitFollowUp();
        f.noteSwing(true);
        f.onGround(false, true, 3);
        f.onGround(true, true, 3);
        assertFalse(f.landingActive());
    }

    @Test
    void airSwingThatNeverLandsGoesNowhere() {
        HitFollowUp f = new HitFollowUp();
        f.noteSwing(false);
        f.onGround(false, false, 0);
        assertFalse(f.landingActive());
        assertTrue(f.critPending());
        f.reset();
        assertFalse(f.critPending());
    }

    @Test
    void followUpAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        HitFollowUp f = new HitFollowUp();
        long bytes = AllocationProbe.measure(10_000, () -> {
            f.armReset(1);
            f.hurtRose(3, 10);
            f.confirmed(2);
            f.expire(3);
            f.noteSwing(false);
            f.onGround(true, true, 3);
            f.landingSide();
            f.spendLanding();
            f.endLanding();
        });
        assertEquals(0, bytes);
    }
}
