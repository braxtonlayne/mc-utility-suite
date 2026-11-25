package com.utilitysuite.combat.tactics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Test;

class DeflectPlannerTest {

    private final DeflectPlanner d = new DeflectPlanner();

    @Test
    void fireballStraightAtUsIsIncoming() {
        // 20 blocks south (+z), flying north at 1 block/tick, body centre at origin
        assertTrue(d.incoming(0, 1, 20, 0, 0, -1, 0, 1, 0, 1.0, 60));
        assertEquals(20.0, d.impactTicks(), 1e-9);
        assertEquals(0.0, d.missDistance(), 1e-9);
    }

    @Test
    void passingOrRecedingIsNot() {
        assertFalse(d.incoming(5, 1, 20, 0, 0, -1, 0, 1, 0, 1.0, 60)); // misses by 5
        assertFalse(d.incoming(0, 1, 20, 0, 0, 1, 0, 1, 0, 1.0, 60));  // flying away
        assertFalse(d.incoming(0, 1, 80, 0, 0, -1, 0, 1, 0, 1.0, 60)); // beyond the horizon
    }

    @Test
    void strikeWhenAlreadyFacingIt() {
        assertTrue(d.incoming(0, 1.6, 20, 0, 0, -1, 0, 1, 0, 1.0, 60));
        // facing south (yaw 0 = +z), level
        assertTrue(d.planStrike(0, 1.6, 20, 0, 0, -1, 0, 1.6, 0, 0f, 0f, 3.0, 20.0, 1));
        assertEquals(17, d.strikeTick()); // first tick within 3 blocks
        assertEquals(0f, d.strikeYaw(), 1e-3);
        assertEquals(0f, d.strikePitch(), 1e-3);
    }

    @Test
    void skipWhenTurnCannotBeMadeInTime() {
        // fireball from behind (we face north, yaw 180), arriving in 4 ticks: 180 degrees at 20 deg/tick = 9 ticks
        assertTrue(d.incoming(0, 1.6, 4, 0, 0, -1, 0, 1, 0, 1.0, 60));
        assertFalse(d.planStrike(0, 1.6, 4, 0, 0, -1, 0, 1.6, 0, 180f, 0f, 3.0, 20.0, 1));
        // the same turn with a long approach is fine
        assertTrue(d.incoming(0, 1.6, 40, 0, 0, -1, 0, 1, 0, 1.0, 60));
        assertTrue(d.planStrike(0, 1.6, 40, 0, 0, -1, 0, 1.6, 0, 180f, 0f, 3.0, 20.0, 1));
        assertTrue(d.strikeTick() >= 10);
    }

    @Test
    void pearlCountdownFormatsTicks() {
        CombatVoxels floor = (x, y, z) -> y <= 0 ? CombatVoxels.SOLID : CombatVoxels.AIR;
        PearlLandingSim sim = new PearlLandingSim();
        StringBuilder sb = new StringBuilder();
        int ticks = DeflectPlanner.pearlCountdown(sim, 0, 5, 0, 1.0, 0.5, 0, floor, sb);
        assertTrue(ticks > 0);
        assertTrue(sb.toString().startsWith("Pearl "), sb.toString());
        assertTrue(sb.toString().endsWith("s"));
        CombatVoxels unknown = (x, y, z) -> CombatVoxels.UNKNOWN;
        assertEquals(-1, DeflectPlanner.pearlCountdown(sim, 0, 5, 0, 1.0, 0.5, 0, unknown, sb));
        assertEquals(0, sb.length());
    }

    @Test
    void planningDoesNotAllocate() {
        if (!AllocationProbe.supported()) {
            return;
        }
        RangedBallistics b = new RangedBallistics();
        long bytes = AllocationProbe.measure(2_000, () -> {
            d.incoming(0, 1.6, 40, 0, 0, -1, 0, 1, 0, 1.0, 60);
            d.planStrike(0, 1.6, 40, 0, 0, -1, 0, 1.6, 0, 180f, 0f, 3.0, 20.0, 1);
            b.solveLead(0, 0, 0, 30, 0, 0, 0, 0, 0.2, 3.0, 0.6, false);
        });
        assertTrue(bytes < 1024, "allocated " + bytes + " bytes");
    }
}
