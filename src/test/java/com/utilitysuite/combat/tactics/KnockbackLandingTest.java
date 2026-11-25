package com.utilitysuite.combat.tactics;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnockbackLandingTest {

    /** Flat floor: solid at y <= 63. */
    private static final CombatVoxels FLAT = (x, y, z) -> y <= 63 ? CombatVoxels.SOLID : CombatVoxels.AIR;

    /** Floor at 63 for x < 2, then a 5-block drop (floor top at 59). */
    private static final CombatVoxels CLIFF = (x, y, z) ->
            y <= (x < 2 ? 63 : 58) ? CombatVoxels.SOLID : CombatVoxels.AIR;

    /** Attacker at x = -1.5 facing +x (yaw -90), target standing at (0.5, 64, 0.5) with resting gravity motion. */
    private static KnockbackLanding hit(boolean sprint, double resistance, CombatVoxels w) {
        KnockbackLanding k = new KnockbackLanding();
        k.predict(0.5, 64, 0.5, 0, -0.0784, 0, true, -1.5, 0.5, -90f, sprint ? 1.0 : 0.0, resistance, w);
        return k;
    }

    @Test
    void vanillaLaunchVelocities() {
        KnockbackLanding plain = hit(false, 0, FLAT);
        assertEquals(0.4, plain.launchX(), 1e-9);
        assertEquals(0.0, plain.launchZ(), 1e-9);
        assertEquals(-0.0392 + 0.4, plain.launchY(), 1e-9); // min(0.4, vy / 2 + 0.4)
        KnockbackLanding sprint = hit(true, 0, FLAT);
        // the sprint knockback halves the first and adds 0.5 along the attacker's facing; y capped at 0.4
        assertEquals(0.4 / 2 + 0.5, sprint.launchX(), 1e-9);
        assertEquals(0.4, sprint.launchY(), 1e-9);
    }

    @Test
    void landsAwayFromTheAttackerSprintFarther() {
        KnockbackLanding plain = hit(false, 0, FLAT);
        KnockbackLanding sprint = hit(true, 0, FLAT);
        assertTrue(plain.landX() > 1.5, "plain " + plain.landX());
        assertTrue(sprint.landX() > plain.landX() + 1.0, "sprint " + sprint.landX());
        assertEquals(64.0, plain.landY(), 1e-9);
        assertEquals(0.0, plain.drop(), 1e-9);
        assertFalse(plain.ledge());
        assertEquals(0.5, plain.landZ(), 1e-9);
    }

    @Test
    void fullKnockbackResistanceDoesNotMove() {
        KnockbackLanding k = hit(true, 1.0, FLAT);
        assertEquals(0.0, k.launchX(), 1e-9);
        assertEquals(0.5, k.landX(), 1e-9);
    }

    @Test
    void ledgeWhenTheHitLandsOverADrop() {
        KnockbackLanding sprint = hit(true, 0, CLIFF);
        assertTrue(sprint.landX() >= 2.0, "x " + sprint.landX());
        assertEquals(59.0, sprint.landY(), 1e-9);
        assertEquals(5.0, sprint.drop(), 1e-9);
        assertTrue(sprint.ledge());
    }

    @Test
    void voidCountsAsALedge() {
        CombatVoxels edge = (x, y, z) -> x < 2 && y <= 63 ? CombatVoxels.SOLID
                : (y < 0 ? CombatVoxels.LAVA : CombatVoxels.AIR);
        assertTrue(hit(true, 0, edge).ledge());
    }

    @Test
    void wallStopsTheFlight() {
        CombatVoxels wall = (x, y, z) -> y <= 63 || x >= 2 ? CombatVoxels.SOLID : CombatVoxels.AIR;
        KnockbackLanding k = hit(true, 0, wall);
        assertTrue(k.landX() < 2.0);
        assertFalse(k.ledge());
    }

    @Test
    void reachIsEyeToNearestBoxPoint() {
        assertEquals(0.0, KnockbackLanding.reach(0, 1.6, 0, -0.3, 0, -0.3, 0.3, 1.8, 0.3), 1e-9);
        assertEquals(2.7, KnockbackLanding.reach(-3, 1.6, 0, -0.3, 0, -0.3, 0.3, 1.8, 0.3), 1e-9);
        assertEquals(Math.sqrt(2.7 * 2.7 + 0.2 * 0.2),
                KnockbackLanding.reach(-3, 2.0, 0, -0.3, 0, -0.3, 0.3, 1.8, 0.3), 1e-9);
    }

    @Test
    void predictionAllocatesNothing() {
        if (!AllocationProbe.supported()) {
            return;
        }
        KnockbackLanding k = new KnockbackLanding();
        long bytes = AllocationProbe.measure(2000, () ->
                k.predict(0.5, 64, 0.5, 0.1, 0, 0, true, -1.5, 0.5, -90f, 1.0, 0, CLIFF));
        assertEquals(0L, bytes);
    }
}
