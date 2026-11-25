package com.utilitysuite.ui.holo;

import com.utilitysuite.core.api.visual.HudContextState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudContextMachineTest {

    private static HudContextMachine on() {
        HudContextMachine m = new HudContextMachine();
        m.enabled = true;
        m.idleTicks = 20;
        m.combatHoldTicks = 10;
        m.idleAlpha = 0.3f;
        m.quietAlpha = 0.5f;
        return m;
    }

    private static void frames(HudContextMachine m, float seconds) {
        for (float t = 0f; t < seconds; t += 0.016f) {
            m.advance(0.016f);
        }
    }

    @Test
    void disabledChangesNothing() {
        HudContextMachine m = new HudContextMachine();
        for (int i = 0; i < 400; i++) {
            m.tick(i % 2 == 0, true, true, true);
            m.advance(0.05f);
        }
        assertEquals(HudContextState.EXPLORING, m.state());
        assertEquals(1f, m.hudAlpha());
        assertEquals(1f, m.tacticalAlpha());
        assertEquals(1f, m.tacticalScale());
    }

    @Test
    void priorityFallingOverCombatOverBuildingOverIdle() {
        HudContextMachine m = on();
        m.tick(true, true, true, true);
        assertEquals(HudContextState.FALLING, m.state());
        m.tick(true, false, true, true);
        assertEquals(HudContextState.COMBAT, m.state());
        m.tick(false, false, true, true);
        assertEquals(HudContextState.COMBAT, m.state(), "combat holds after the last signal");
        for (int i = 0; i < 10; i++) {
            m.tick(false, false, false, false);
        }
        assertEquals(HudContextState.BUILDING, m.state(), "building holds after the last placement");
        for (int i = 0; i < HudContextMachine.BUILD_HOLD_TICKS; i++) {
            m.tick(false, false, false, false);
        }
        assertEquals(HudContextState.EXPLORING, m.state());
    }

    @Test
    void idleViewFadesWholeHudAndInputRestoresIt() {
        HudContextMachine m = on();
        for (int i = 0; i < 19; i++) {
            m.tick(false, false, false, true);
        }
        assertEquals(HudContextState.EXPLORING, m.state());
        m.tick(false, false, false, true);
        assertEquals(HudContextState.IDLE_VIEW, m.state());
        frames(m, 0.2f);
        float mid = m.hudAlpha();
        assertTrue(mid < 1f && mid > 0.3f, "fades gradually: " + mid);
        frames(m, 5f);
        assertEquals(0.3f, m.hudAlpha(), 1e-3f);
        m.tick(false, false, false, false); // moved
        assertEquals(HudContextState.EXPLORING, m.state());
        frames(m, 1f);
        assertEquals(1f, m.hudAlpha(), 1e-3f);
    }

    @Test
    void combatStartAnimatesTacticalLayersIn() {
        HudContextMachine m = on();
        m.tick(false, false, false, false);
        frames(m, 1f);
        assertEquals(0.5f, m.tacticalAlpha(), 1e-3f, "quiet outside combat");
        assertEquals(1f, m.tacticalScale());
        m.tick(true, false, false, false);
        assertEquals(HudContextMachine.POP_SCALE, m.tacticalScale(), 1e-4f, "pop starts large");
        m.advance(0.016f);
        float a = m.tacticalAlpha();
        assertTrue(a > 0.5f && a < 1f, "eases in: " + a);
        frames(m, 1f);
        assertEquals(1f, m.tacticalAlpha(), 1e-3f);
        assertEquals(1f, m.tacticalScale(), "pop settles at 1");
        m.tick(true, false, false, false);
        assertEquals(1f, m.tacticalScale(), "no new pop while combat continues");
    }

    @Test
    void popOffNeverScales() {
        HudContextMachine m = on();
        m.pop = false;
        m.tick(false, true, false, false);
        assertEquals(1f, m.tacticalScale());
    }

    @Test
    void easeIsMonotoneAndSnaps() {
        float v = 0f;
        for (int i = 0; i < 200; i++) {
            float n = HudContextMachine.ease(v, 1f, 0.016f, 0.06f);
            assertTrue(n >= v);
            v = n;
        }
        assertEquals(1f, v);
    }
}
