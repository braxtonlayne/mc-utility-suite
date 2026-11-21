package com.utilitysuite.combat.trigger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TriggerGateTest {

    private static boolean valid(float strength) {
        return TriggerGate.valid(5, true, true, false, true, strength, false, false, false);
    }

    @Test
    void needsAFullCooldownAndAValidTarget() {
        assertTrue(valid(1f));
        assertFalse(valid(0.8f), "cooldown not full");
        assertFalse(TriggerGate.valid(-1, false, false, false, false, 1f, false, false, false), "nothing aimed");
        assertFalse(TriggerGate.valid(5, false, true, false, true, 1f, false, false, false), "dead or not living");
        assertFalse(TriggerGate.valid(5, true, true, true, true, 1f, false, false, false), "ally");
        assertFalse(TriggerGate.valid(5, true, false, false, true, 1f, false, false, false), "kind filtered");
        assertFalse(TriggerGate.valid(5, true, true, false, false, 1f, false, false, false), "out of reach");
        assertFalse(TriggerGate.valid(5, true, true, false, true, 1f, true, false, false), "screen open");
        assertFalse(TriggerGate.valid(5, true, true, false, true, 1f, false, true, false), "using an item");
        assertFalse(TriggerGate.valid(5, true, true, false, true, 1f, false, false, true), "manual click wins");
    }

    @Test
    void swingsAfterTheReactionDelay() {
        TriggerGate g = new TriggerGate();
        assertTrue(g.needsDelay(5));
        assertFalse(g.tick(5, true, 2));
        assertFalse(g.tick(5, true, 99));
        assertTrue(g.tick(5, true, 99));
        // cooldown drains after the swing, then a fresh delay
        assertFalse(g.tick(5, false, 0));
        assertTrue(g.needsDelay(5));
        assertFalse(g.tick(5, true, 1));
        assertTrue(g.tick(5, true, 1));
    }

    @Test
    void changingTargetRestartsTheDelay() {
        TriggerGate g = new TriggerGate();
        assertFalse(g.tick(5, true, 2));
        assertFalse(g.tick(5, true, 2));
        assertFalse(g.tick(6, true, 2), "new target: new delay");
        assertFalse(g.tick(6, true, 2));
        assertTrue(g.tick(6, true, 2));
    }
}
