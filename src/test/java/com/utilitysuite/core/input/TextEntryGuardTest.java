package com.utilitysuite.core.input;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The typed-character guard that keeps menu movement off while the user types into a text field. */
class TextEntryGuardTest {

    @Test
    void unhandledCharactersNeverRaiseTheGuard() {
        TextEntryGuard g = new TextEntryGuard();
        for (int tick = 0; tick < 100; tick++) {
            // W held in a chest screen: the OS repeats the character, nothing takes it
            g.onCharOffered();
            g.onCharUnhandled();
            assertFalse(g.isTyping());
            g.tick(true);
            assertFalse(g.isTyping());
        }
    }

    @Test
    void consumedCharacterRaisesTheGuardBeforeTheNextTick() {
        TextEntryGuard g = new TextEntryGuard();
        g.tick(true);
        g.onCharOffered(); // the text field took it: no "unhandled" follows
        assertTrue(g.isTyping(), "must already hold on the first tick that sees the key down");
        g.tick(true);
        assertTrue(g.isTyping());
    }

    @Test
    void consumedThenUnhandledInOneTickStillCounts() {
        TextEntryGuard g = new TextEntryGuard();
        g.onCharOffered(); // consumed
        g.onCharOffered(); // next one
        g.onCharUnhandled(); // ... which nothing took
        assertTrue(g.isTyping());
        g.tick(true);
        assertTrue(g.isTyping());
    }

    @Test
    void guardHoldsThroughTheKeyRepeatDelayThenDrops() {
        TextEntryGuard g = new TextEntryGuard();
        g.onCharOffered();
        for (int tick = 0; tick < TextEntryGuard.HOLD_TICKS; tick++) {
            g.tick(true);
            assertTrue(g.isTyping(), "tick " + tick);
        }
        g.tick(true);
        assertFalse(g.isTyping());
    }

    @Test
    void repeatedConsumedCharactersKeepRefreshingTheHold() {
        TextEntryGuard g = new TextEntryGuard();
        for (int tick = 0; tick < 200; tick++) {
            if (tick % 20 == 0) { // a slow key repeat, one character a second
                g.onCharOffered();
            }
            g.tick(true);
            assertTrue(g.isTyping(), "tick " + tick);
        }
    }

    @Test
    void closingTheScreenClearsTheGuard() {
        TextEntryGuard g = new TextEntryGuard();
        g.onCharOffered();
        g.tick(true);
        assertTrue(g.isTyping());
        g.tick(false);
        assertFalse(g.isTyping());
        g.tick(true);
        assertFalse(g.isTyping());
    }

    @Test
    void guardAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        TextEntryGuard g = new TextEntryGuard();
        int[] i = {0};
        int[] sink = {0};
        long bytes = AllocationProbe.measure(1_000_000, () -> {
            int k = i[0]++;
            g.onCharOffered();
            if ((k & 3) != 0) {
                g.onCharUnhandled();
            }
            g.tick((k & 1023) != 0);
            if (g.isTyping()) {
                sink[0]++;
            }
        });
        assertTrue(bytes < 1024, "allocated " + bytes + " bytes (" + sink[0] + ")");
    }
}
