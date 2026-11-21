package com.utilitysuite.core.autopilot;

import com.utilitysuite.core.api.HazardKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VitalsMonitorTest {

    private final DangerModelTest.ManualClock clock = new DangerModelTest.ManualClock();
    private final DangerModel danger = new DangerModel(clock);
    private final VitalsMonitor m = new VitalsMonitor();
    private final VitalsMonitor.Vitals v = new VitalsMonitor.Vitals();

    private void healthy() {
        v.present = true;
        v.health = 20f;
        v.maxHealth = 20f;
        v.air = 300;
        v.maxAir = 300;
    }

    @Test
    void healthyReportsNothing() {
        healthy();
        assertEquals(VitalsMonitor.NOTHING, m.update(v, danger, 6f));
        assertFalse(danger.any());
    }

    @Test
    void lowHealthAirAndFireAreReported() {
        healthy();
        v.health = 4f;
        v.air = 10;
        v.fireTicks = 40;
        m.update(v, danger, 100f);
        assertTrue(danger.has(HazardKind.LOW_HEALTH));
        assertEquals(30, danger.ticksToImpact(HazardKind.DROWNING));
        assertTrue(danger.has(HazardKind.FIRE));
        v.fireImmune = true;
        danger.clearAll();
        m.update(v, danger, 100f);
        assertFalse(danger.has(HazardKind.FIRE), "fire resistance: burning does not hurt");
    }

    @Test
    void bigHitAndDeathAreSeenOnce() {
        healthy();
        m.update(v, danger, 6f);
        v.health = 15f;
        assertEquals(VitalsMonitor.NOTHING, m.update(v, danger, 6f), "5 is under the threshold");
        v.health = 8f;
        assertEquals(VitalsMonitor.BIG_HIT, m.update(v, danger, 6f));
        assertEquals(7f, m.lastDrop());
        v.health = 0f;
        v.dead = true;
        assertEquals(VitalsMonitor.DIED, m.update(v, danger, 6f));
        assertEquals(VitalsMonitor.NOTHING, m.update(v, danger, 6f), "still dead: no second death");
    }
}
