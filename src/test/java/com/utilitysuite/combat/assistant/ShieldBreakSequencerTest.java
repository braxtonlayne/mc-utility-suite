package com.utilitysuite.combat.assistant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The give-up loop against a turtling opponent: retries back off with a growing wait. */
class ShieldBreakSequencerTest {

    static CombatSnapshot turtle() {
        CombatSnapshot s = new CombatSnapshot();
        s.px = 0;
        s.py = 64;
        s.pz = 0;
        s.eyeY = 65.62;
        s.tx = 2.5;
        s.ty = 64;
        s.tz = 0;
        s.minX = 2.2;
        s.maxX = 2.8;
        s.minY = 64;
        s.maxY = 65.8;
        s.minZ = -0.3;
        s.maxZ = 0.3;
        s.reach = 3.0;
        s.targetBlocking = true;
        s.axeSlot = 2;
        s.selectedSlot = 0;
        s.crosshairOnTarget = false; // the swing never becomes possible
        return s;
    }

    /** Runs until the next SELECT_AXE; returns the ticks it took (or -1). */
    static int ticksToNextTry(ShieldBreakSequencer seq, CombatSnapshot s, int limit) {
        for (int t = 1; t <= limit; t++) {
            if (seq.step(s, true) == ShieldBreakSequencer.Action.SELECT_AXE) {
                return t;
            }
        }
        return -1;
    }

    @Test
    void retriesBackOffWithAGrowingWait() {
        ShieldBreakSequencer seq = new ShieldBreakSequencer();
        CombatSnapshot s = turtle();
        assertEquals(ShieldBreakSequencer.Action.SELECT_AXE, seq.step(s, true));
        int prev = 0;
        for (int round = 0; round < 4; round++) {
            int gap = ticksToNextTry(seq, s, 500);
            assertTrue(gap > 0);
            // a try takes MAX_WAIT + 1 ticks to give up, then the back-off runs before the next try
            assertTrue(gap > prev, "round " + round + ": gap " + gap + " grows past " + prev);
            prev = gap;
        }
        assertTrue(prev >= ShieldBreakSequencer.MAX_WAIT + ShieldBreakSequencer.BACKOFF_BASE * 8);
    }

    @Test
    void backOffIsCappedAndNotActive() {
        ShieldBreakSequencer seq = new ShieldBreakSequencer();
        CombatSnapshot s = turtle();
        seq.step(s, true);
        for (int round = 0; round < 8; round++) {
            ticksToNextTry(seq, s, 1000);
        }
        // give up once more and look at the back-off
        for (int t = 0; t <= ShieldBreakSequencer.MAX_WAIT; t++) {
            seq.step(s, true);
        }
        assertFalse(seq.active(), "the rest of the fight loop runs during the back-off");
        assertTrue(seq.backoff() <= ShieldBreakSequencer.BACKOFF_MAX);
        assertTrue(seq.backoff() > 0);
    }

    @Test
    void aLoweredShieldOrALandedSwingClearsTheBackOff() {
        ShieldBreakSequencer seq = new ShieldBreakSequencer();
        CombatSnapshot s = turtle();
        seq.step(s, true);
        for (int t = 0; t <= ShieldBreakSequencer.MAX_WAIT; t++) {
            seq.step(s, true);
        }
        assertTrue(seq.backoff() > 0);
        s.targetBlocking = false;
        assertEquals(ShieldBreakSequencer.Action.NONE, seq.step(s, true));
        assertEquals(0, seq.backoff());
        s.targetBlocking = true;
        assertEquals(ShieldBreakSequencer.Action.SELECT_AXE, seq.step(s, true), "a fresh raise is tried at once");

        // swing lands: no back-off afterwards
        s.selectedSlot = 2;
        s.crosshairOnTarget = true;
        assertEquals(ShieldBreakSequencer.Action.SWING, seq.step(s, true));
        assertEquals(ShieldBreakSequencer.Action.SELECT_WEAPON, seq.step(s, true));
        assertEquals(0, seq.backoff());
        seq.reset();
        assertEquals(0, seq.backoff());
    }
}
