package com.utilitysuite.combat.tactics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ComboEscapeTest {

    static boolean step(ComboEscape e, long clock, int unanswered, long taken, long landed) {
        return e.update(clock, unanswered, taken, landed, false, false, false, false, ComboEscape.RIGHT);
    }

    @Test
    void triggersAfterTwoUnansweredHitsOnly() {
        ComboEscape e = new ComboEscape();
        assertFalse(step(e, 10, 1, 10, 2), "one hit is a trade, not a combo");
        assertTrue(step(e, 20, 2, 20, 2), "two hits without landing one");
        assertEquals(1, e.escapes());
        assertNotEquals(ComboEscape.NONE, e.side(), "strafes out of the line");
        assertEquals(0, e.forward(), "never backpedals");

        ComboEscape stale = new ComboEscape();
        assertFalse(step(stale, 40, 2, 30, 2), "a pair from a while ago does not start an escape");
    }

    @Test
    void handsBackWhenOurHitLandsOrTheWindowEnds() {
        ComboEscape e = new ComboEscape();
        assertTrue(step(e, 20, 2, 20, 2));
        assertTrue(step(e, 23, 2, 20, 2));
        assertFalse(step(e, 24, 0, 20, 24), "our hit landed");

        ComboEscape w = new ComboEscape();
        assertTrue(step(w, 20, 2, 20, 2));
        int driven = 1;
        for (long t = 21; t < 40; t++) {
            if (step(w, t, 2, 20, 2)) {
                driven++;
            }
        }
        assertEquals(ComboEscape.WINDOW_TICKS, driven, "about half a second");
        assertFalse(w.active());
        assertTrue(step(w, 45, 3, 45, 2), "a third hit starts the next escape");
    }

    @Test
    void sideChoiceWithHazards() {
        ComboEscape e = new ComboEscape();
        assertTrue(e.update(20, 2, 20, 0, false, false, false, true, ComboEscape.RIGHT));
        assertEquals(ComboEscape.LEFT, e.side(), "away from the hazard on the right");
        e.reset();
        assertTrue(e.update(30, 2, 30, 0, false, false, true, false, ComboEscape.LEFT));
        assertEquals(ComboEscape.RIGHT, e.side());
        e.reset();
        assertTrue(e.update(40, 2, 40, 0, false, false, false, false, ComboEscape.LEFT));
        assertEquals(ComboEscape.LEFT, e.side(), "both open: the preferred side");
        // a hazard appears on the chosen side mid-escape: switch
        assertTrue(e.update(41, 2, 40, 0, false, false, true, false, ComboEscape.LEFT));
        assertEquals(ComboEscape.RIGHT, e.side());
        // boxed in on both sides: step in, never back
        e.reset();
        assertTrue(e.update(50, 2, 50, 0, false, false, true, true, ComboEscape.LEFT));
        assertEquals(ComboEscape.NONE, e.side());
        assertEquals(1, e.forward());
        e.reset();
        assertTrue(e.update(60, 2, 60, 0, true, true, true, true, ComboEscape.LEFT));
        assertEquals(0, e.forward(), "closed in front too: hold");
    }

    @Test
    void pinnedAgainstAWallSlidesTowardTheOpenSide() {
        ComboEscape e = new ComboEscape();
        assertTrue(e.update(20, 2, 20, 0, false, true, true, false, ComboEscape.LEFT));
        assertTrue(e.pinned());
        assertEquals(ComboEscape.RIGHT, e.side(), "along the wall, to the open side");
        assertEquals(0, e.forward());
        e.reset();
        assertTrue(e.update(30, 2, 30, 0, false, true, false, true, ComboEscape.RIGHT));
        assertEquals(ComboEscape.LEFT, e.side());
    }

    @Test
    void neverBackpedals() {
        ComboEscape e = new ComboEscape();
        for (int mask = 0; mask < 16; mask++) {
            e.reset();
            boolean f = (mask & 1) != 0, b = (mask & 2) != 0, l = (mask & 4) != 0, r = (mask & 8) != 0;
            assertTrue(e.update(100 + mask * 20, 2, 100 + mask * 20, 0, f, b, l, r, ComboEscape.RIGHT));
            assertTrue(e.forward() >= 0, "mask " + mask);
        }
    }
}
