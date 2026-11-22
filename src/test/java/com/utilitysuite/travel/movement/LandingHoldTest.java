package com.utilitysuite.travel.movement;

import org.junit.jupiter.api.Test;

import static com.utilitysuite.travel.movement.LandingHold.BACK;
import static com.utilitysuite.travel.movement.LandingHold.FORWARD;
import static com.utilitysuite.travel.movement.LandingHold.JUMP;
import static com.utilitysuite.travel.movement.LandingHold.LEFT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link LandingHold} decisions on scripted states (no physics). */
class LandingHoldTest {

    /** Air ticks with {@code keys}, the last one predicting a narrow touchdown, then the touchdown tick. */
    private static LandingHold land(int keys, boolean narrow) {
        LandingHold h = new LandingHold();
        h.update(true, keys, false, false, false, false); // run-up
        h.update(false, keys, false, false, false, false); // flight
        h.update(false, keys, false, narrow, false, false); // last air tick
        assertEquals(narrow && (keys & JUMP) == 0, h.sneak(), "sneak guard from the last air tick");
        h.update(true, keys, false, false, narrow, false); // touchdown
        return h;
    }

    @Test
    void holdingWThroughANarrowTouchdownWithholdsWUntilReleasedAndPressedAgain() {
        LandingHold h = land(FORWARD, true);
        assertTrue(h.active());
        assertTrue(h.sneak());
        assertEquals(FORWARD, h.latched());
        assertEquals(0, h.forwardAxis(FORWARD), "W is held back");
        for (int t = 0; t < 5; t++) {
            h.update(true, FORWARD, false, false, false, true);
            assertTrue(h.sneak(), "stable but no fresh input: the hold stays");
            assertEquals(0, h.forwardAxis(FORWARD));
        }
        h.update(true, 0, false, false, false, true); // W released
        assertEquals(0, h.latched());
        assertTrue(h.sneak(), "releasing is not a fresh press");
        h.update(true, FORWARD, false, false, false, true); // pressed again
        assertFalse(h.active(), "stable and a fresh press: the hold ends");
        assertFalse(h.sneak());
        assertEquals(1, h.forwardAxis(FORWARD), "the fresh W applies at once");
    }

    @Test
    void aFreshPressAppliesAtOnceEvenBeforeStable() {
        LandingHold h = land(FORWARD, true);
        h.update(true, FORWARD | LEFT, false, false, false, false);
        assertEquals(1, h.strafeAxis(FORWARD | LEFT), "a key pressed on the ground is never withheld");
        assertEquals(0, h.forwardAxis(FORWARD | LEFT), "the stale W still is");
        assertTrue(h.sneak(), "not stable yet: the guard stays down");
        h.update(true, FORWARD | LEFT, false, false, false, true);
        assertFalse(h.sneak(), "stable after a fresh press: the guard lifts");
        assertEquals(0, h.forwardAxis(FORWARD | LEFT), "the latch outlives the hold");
    }

    @Test
    void noTimerPathReleasesALatchedKey() {
        for (LandingHold.Release mode : LandingHold.Release.values()) {
            LandingHold h = land(FORWARD | BACK, true);
            h.release = mode;
            for (int t = 0; t < 400; t++) {
                h.update(true, FORWARD | BACK, false, false, false, t % 2 == 0);
                assertEquals(FORWARD | BACK, h.latched(), mode + " tick " + t);
            }
            assertFalse(h.sneak(), mode + ": the sneak guard lifts by itself after the limit");
        }
    }

    @Test
    void holdLimitLiftsTheSneakAfterStableTicksButKeepsTheLatch() {
        LandingHold h = land(FORWARD, true);
        h.holdLimitTicks = 12;
        int sneakTicks = 0;
        for (int t = 0; t < 30; t++) {
            h.update(true, FORWARD, false, false, false, true);
            if (h.sneak()) {
                sneakTicks++;
            }
        }
        assertEquals(12, sneakTicks);
        assertEquals(FORWARD, h.latched());
    }

    @Test
    void neverUnstableForeverEither() {
        LandingHold h = land(FORWARD, true);
        for (int t = 0; t < LandingHold.MAX_HOLD_TICKS + 2; t++) {
            h.update(true, FORWARD, false, false, false, false);
        }
        assertFalse(h.sneak(), "ice or knockback: the guard lifts after the safety limit");
        assertEquals(FORWARD, h.latched());
    }

    @Test
    void wideLandingIsUntouched() {
        LandingHold h = land(FORWARD, false);
        assertFalse(h.active());
        assertFalse(h.sneak());
        assertEquals(0, h.latched());
        assertEquals(1, h.forwardAxis(FORWARD));
    }

    @Test
    void userSneakOrAChainJumpSkipsIt() {
        LandingHold chain = land(FORWARD | JUMP, true);
        assertFalse(chain.active(), "jump held through the landing: a chain");
        assertEquals(0, chain.latched());
        LandingHold h = land(FORWARD, true);
        h.update(true, FORWARD, true, false, false, false);
        assertFalse(h.active(), "sneak hands everything back");
        assertEquals(0, h.latched());
        assertEquals(1, h.forwardAxis(FORWARD));
        LandingHold j = land(FORWARD, true);
        j.update(true, FORWARD | JUMP, false, false, false, false);
        assertFalse(j.active(), "jump pressed on the top: the next jump starts");
        assertEquals(0, j.latched());
    }

    @Test
    void aKeyPressedOnTheTouchdownTickIsFresh() {
        LandingHold h = new LandingHold();
        h.update(false, 0, false, true, false, false);
        h.update(true, FORWARD, false, false, true, false);
        assertTrue(h.active());
        assertEquals(0, h.latched(), "W went down on the touchdown tick itself: the user's");
    }

    @Test
    void disabledDoesNothing() {
        LandingHold h = new LandingHold();
        h.enabled = false;
        h.update(false, FORWARD, false, true, false, false);
        assertFalse(h.sneak());
        h.update(true, FORWARD, false, false, true, false);
        assertFalse(h.active());
        assertEquals(0, h.latched());
    }
}
