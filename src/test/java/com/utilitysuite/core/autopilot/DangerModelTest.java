package com.utilitysuite.core.autopilot;

import com.utilitysuite.core.api.HazardKind;
import com.utilitysuite.core.api.IDangerModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DangerModelTest {

    static final class ManualClock implements TickClock {
        long t = 1000;

        @Override
        public long now() {
            return t;
        }
    }

    private final ManualClock clock = new ManualClock();
    private final DangerModel m = new DangerModel(clock);

    @Test
    void emptyModelHasNothing() {
        assertFalse(m.any());
        assertNull(m.worst());
        assertEquals(Integer.MAX_VALUE, m.ticksToImpact(HazardKind.FALL));
        assertEquals(0f, m.expectedDamage(HazardKind.FALL));
        assertTrue(Float.isNaN(m.bearingYawDeg(HazardKind.FALL)));
    }

    @Test
    void nearerReportWins() {
        m.report(HazardKind.PROJECTILE, 8, 4f, 90f);
        m.report(HazardKind.PROJECTILE, 3, 5f, 10f);
        assertEquals(3, m.ticksToImpact(HazardKind.PROJECTILE));
        assertEquals(5f, m.expectedDamage(HazardKind.PROJECTILE));
        m.report(HazardKind.PROJECTILE, 6, 9f, 45f);
        assertEquals(3, m.ticksToImpact(HazardKind.PROJECTILE), "a farther report does not replace a nearer one");
        assertEquals(10f, m.bearingYawDeg(HazardKind.PROJECTILE));
    }

    @Test
    void countsDownAndExpires() {
        m.report(HazardKind.FALL, 30, 8f, IDangerModel.NO_BEARING);
        clock.t += 4;
        assertEquals(26, m.ticksToImpact(HazardKind.FALL));
        clock.t += DangerModel.STALE_TICKS;
        assertFalse(m.has(HazardKind.FALL), "a report not refreshed goes stale");
        assertFalse(m.any());
    }

    @Test
    void expiresJustAfterImpact() {
        m.report(HazardKind.EXPLOSION, 2, 12f, 0f);
        clock.t += 2;
        assertTrue(m.has(HazardKind.EXPLOSION), "live on the impact tick");
        assertEquals(0, m.ticksToImpact(HazardKind.EXPLOSION));
        clock.t += 1;
        assertFalse(m.has(HazardKind.EXPLOSION));
    }

    @Test
    void anExpiredSlotTakesAFartherReport() {
        m.report(HazardKind.MELEE, 1, 3f, 0f);
        clock.t += 3;
        m.report(HazardKind.MELEE, 6, 3f, 0f);
        assertEquals(6, m.ticksToImpact(HazardKind.MELEE));
    }

    @Test
    void clearDropsTheKind() {
        m.report(HazardKind.LAVA, 5, 4f, 0f);
        m.clear(HazardKind.LAVA);
        assertFalse(m.any());
    }

    @Test
    void worstIsMostDamagePerTick() {
        m.report(HazardKind.FALL, 20, 10f, IDangerModel.NO_BEARING);  // 0.5 per tick
        m.report(HazardKind.PROJECTILE, 4, 4f, 0f);                     // 1.0 per tick
        m.report(HazardKind.DROWNING, 0, 0.5f, IDangerModel.NO_BEARING); // 0.5 (ticks clamp to 1)
        assertEquals(HazardKind.PROJECTILE, m.worst());
        m.clear(HazardKind.PROJECTILE);
        assertEquals(HazardKind.FALL, m.worst(), "tie at 0.5 goes to the lower ordinal");
    }
}
