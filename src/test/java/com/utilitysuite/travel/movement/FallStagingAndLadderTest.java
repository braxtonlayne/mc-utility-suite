package com.utilitysuite.travel.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Early staging risk (A2), the clutch option ladder (A6) and the glide guard. */
class FallStagingAndLadderTest {

    @Test
    void shallowDropIsNoRisk() {
        assertEquals(0.0, FallRiskEstimator.risk(0.5, 3.0, 0.3, 0, 2));
    }

    @Test
    void sprintingTowardADeepDropStages() {
        FallRiskEstimator e = new FallRiskEstimator();
        assertTrue(e.update(1.5, 20.0, 0.28, 1000, 0));
    }

    @Test
    void walkingAwayIsNoRisk() {
        assertEquals(0.0, FallRiskEstimator.risk(1.0, 20.0, -0.2, 1000, 0));
    }

    @Test
    void knockbackOnALedgeStages() {
        assertTrue(FallRiskEstimator.risk(0.8, 20.0, 0.0, 3, 0) >= FallRiskEstimator.RISE);
        assertEquals(0.0, FallRiskEstimator.risk(4.0, 20.0, 0.0, 3, 0));
    }

    @Test
    void fightOnALedgeStages() {
        assertTrue(FallRiskEstimator.risk(0.5, 30.0, 0.0, 1000, 1) >= FallRiskEstimator.RISE);
    }

    @Test
    void stagingHasHysteresis() {
        FallRiskEstimator e = new FallRiskEstimator();
        assertTrue(e.update(1.0, 20.0, 0.28, 1000, 0));
        for (int i = 0; i < FallRiskEstimator.HOLD_TICKS - 1; i++) {
            assertTrue(e.update(10.0, 20.0, 0.0, 1000, 0), "still staged at " + i);
        }
        assertFalse(e.update(10.0, 20.0, 0.0, 1000, 0));
    }

    @Test
    void elytraBeatsHayBlockClutch() {
        ClutchLadder l = new ClutchLadder();
        l.begin(30, 18f);
        l.offer(ClutchLadder.Option.BLOCK, 20, 3.6f); // hay bale: a fifth of the damage
        l.offerElytra(true);
        assertEquals(ClutchLadder.Option.ELYTRA, l.best());
        assertEquals(18f, l.damageAverted());
    }

    @Test
    void waterBlockClutchBeatsElytraOnTimeTie() {
        ClutchLadder l = new ClutchLadder();
        l.begin(30, 18f);
        l.offer(ClutchLadder.Option.BLOCK, 1, 0f);
        l.offerElytra(true);
        assertEquals(ClutchLadder.Option.BLOCK, l.best());
    }

    @Test
    void elytraNeedsTimeToSlowTheFall() {
        ClutchLadder l = new ClutchLadder();
        l.begin(4, 18f);
        l.offerElytra(true);
        assertNull(l.best());
    }

    @Test
    void waterSteerWithinStrafeReach() {
        assertEquals(0, ClutchLadder.steerTicks(0.1, 5));
        assertTrue(ClutchLadder.steerTicks(2.0, 30) > 0);
        assertEquals(-1, ClutchLadder.steerTicks(6.0, 10));
        ClutchLadder l = new ClutchLadder();
        l.begin(30, 20f);
        l.offer(ClutchLadder.Option.BLOCK, 25, 4f);
        l.offerWaterSteer(2.0, true);
        assertEquals(ClutchLadder.Option.WATER_STEER, l.best());
        l.begin(30, 20f);
        l.offerWaterSteer(2.0, false);
        assertNull(l.best());
    }

    @Test
    void pearlOnlyWhenTheCappedTurnFits() {
        // 180 degrees at the 450 deg/s cap: 22.5 deg/tick -> 8 ticks + ease + throw + 10 flight = 20
        assertEquals(20, ClutchLadder.pearlTicks(180, 450, 10));
        // a faster requested rate is still capped at 450 deg/s
        assertEquals(20, ClutchLadder.pearlTicks(180, 2000, 10));
        ClutchLadder l = new ClutchLadder();
        l.begin(15, 20f);
        l.offerPearl(true, 180, 450, 10, 5f);
        assertNull(l.best(), "turn + flight do not fit before impact");
        l.begin(40, 20f);
        l.offerPearl(true, 180, 450, 10, 5f);
        assertEquals(ClutchLadder.Option.PEARL, l.best());
        l.offer(ClutchLadder.Option.BLOCK, 30, 0f);
        assertEquals(ClutchLadder.Option.BLOCK, l.best(), "a damage-free block clutch beats the pearl's 5");
    }

    @Test
    void glideGuardPullsUpBeforeAHardHit() {
        assertTrue(GlideGuard.shouldPullUp(8.0, -0.8, 1.5, 4f));
        assertFalse(GlideGuard.shouldPullUp(80.0, -0.8, 1.5, 4f));
        assertFalse(GlideGuard.shouldPullUp(8.0, -0.8, 0.3, 4f));
        assertEquals(Integer.MAX_VALUE, GlideGuard.ticksToGround(5.0, 0.1));
    }
}
