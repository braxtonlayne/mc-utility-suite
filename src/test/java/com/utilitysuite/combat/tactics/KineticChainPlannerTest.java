package com.utilitysuite.combat.tactics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KineticChainPlannerTest {

    @Test
    void strengthIsVanillaExplosionKnockback() {
        assertEquals(1.22, KineticChainPlanner.strength(0), 1e-9);
        assertEquals(1.22 * (1 - 0.25 / 2.4), KineticChainPlanner.strength(0.25), 1e-9);
        assertEquals(0, KineticChainPlanner.strength(2.4), 1e-9);
        assertTrue(KineticChainPlanner.strength(0) > KineticChainPlanner.strength(1));
    }

    @Test
    void floorThrowLaunchesStraightUp() {
        KineticChainPlanner.Launch l = KineticChainPlanner.evaluate(new KineticChainPlanner.Launch(),
                0, 64.42, 0, 0, 0.42 - 0.08, 0, 0, 64, 0);
        assertTrue(l.inRange);
        assertEquals(0, l.kx, 1e-9);
        assertEquals(KineticChainPlanner.strength(0.42), l.ky, 1e-9);
        assertTrue(l.apex > 4 && l.apex < 12, "apex " + l.apex);
        assertEquals(0, l.apexTravel, 1e-9);
    }

    /**
     * In-game calibration (playtest 19-combatextra): standing on the floor (vy -0.0784 while grounded) and throwing a
     * wind charge straight down at the block under the feet rose the player 6.08 blocks. The old scaling to
     * {@code MaceDivePlanner.WIND_LAUNCH_VY} predicted 3.6.
     */
    @Test
    void standingFloorThrowMatchesTheMeasuredLaunch() {
        double burstY = KineticChainPlanner.faceCoord(63, 1); // top face of the floor block at y 63
        KineticChainPlanner.Launch l = KineticChainPlanner.evaluate(new KineticChainPlanner.Launch(),
                0.5, 64, 0.5, 0, -0.0784, 0, 0.5, burstY, 0.5);
        assertEquals(64.25, burstY, 1e-9);
        assertEquals(6.08, l.apex, 0.15);
    }

    @Test
    void wallThrowPushesAwayFromWall() {
        KineticChainPlanner.Launch l = KineticChainPlanner.evaluate(new KineticChainPlanner.Launch(),
                0, 64, 0, 0, 0, 0, 1.0, 65, 0);
        assertTrue(l.inRange);
        assertTrue(l.kx < 0, "pushed west");
        assertTrue(l.apexTravel > 0);
    }

    @Test
    void outOfRadiusDoesNothing() {
        KineticChainPlanner.Launch l = KineticChainPlanner.evaluate(new KineticChainPlanner.Launch(),
                0, 64, 0, 0, 0, 0, 0, 60, 0);
        assertFalse(l.inRange);
        assertEquals(0, l.apex, 1e-9);
    }

    @Test
    void smashDamageFromApex() {
        assertEquals(0f, KineticChainPlanner.smashDamage(1.0, 0, 0), 1e-6);
        float d = KineticChainPlanner.smashDamage(7.0, -1.0, 0); // 8 block fall
        assertEquals(KineticChainPlanner.MACE_BASE_DAMAGE + MaceDivePlanner.smashBonus(8f, 0), d, 1e-4);
        assertTrue(KineticChainPlanner.smashDamage(7.0, -1.0, 3) > d);
    }

    @Test
    void faceCoordinates() {
        assertEquals(10.5 + 0.75, KineticChainPlanner.faceCoord(10, 1), 1e-9);
        assertEquals(10.5, KineticChainPlanner.faceCoord(10, 0), 1e-9);
    }
}
