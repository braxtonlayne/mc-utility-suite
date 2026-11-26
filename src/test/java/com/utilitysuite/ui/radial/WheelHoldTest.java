package com.utilitysuite.ui.radial;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WheelHoldTest {

    @Test
    void heldFollowsTheKeyWithoutACancel() {
        WheelHold h = new WheelHold();
        assertFalse(h.held(false));
        assertTrue(h.held(true));
        assertTrue(h.held(true));
        assertFalse(h.held(false));
    }

    @Test
    void aCancelKeepsTheWheelClosedUntilTheKeyIsReleased() {
        WheelHold h = new WheelHold();
        assertTrue(h.held(true));
        h.suppressUntilRelease(); // Stop-All while the key is held
        assertFalse(h.held(true));
        assertFalse(h.held(true));
        assertTrue(h.suppressed());
        assertFalse(h.held(false)); // released: the latch clears
        assertFalse(h.suppressed());
        assertTrue(h.held(true)); // a new press opens it again
    }

    @Test
    void aCancelWithTheKeyAlreadyUpDoesNotBlockTheNextPress() {
        WheelHold h = new WheelHold();
        h.suppressUntilRelease();
        assertFalse(h.held(false));
        assertTrue(h.held(true));
    }
}
