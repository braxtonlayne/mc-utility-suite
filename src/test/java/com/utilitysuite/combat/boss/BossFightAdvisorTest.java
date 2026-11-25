package com.utilitysuite.combat.boss;

import com.utilitysuite.combat.boss.BossFightAdvisor.DragonState;
import com.utilitysuite.combat.boss.BossFightAdvisor.ShotVerdict;
import com.utilitysuite.combat.tactics.RangedBallistics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BossFightAdvisorTest {

    @Test
    void dragonPhases() {
        assertEquals(DragonState.CIRCLING, BossFightAdvisor.dragonState(0));
        assertEquals(DragonState.LANDING, BossFightAdvisor.dragonState(3));
        assertEquals(DragonState.PERCHED, BossFightAdvisor.dragonState(5));
        assertEquals(DragonState.PERCHED, BossFightAdvisor.dragonState(6));
        assertEquals(DragonState.PERCHED, BossFightAdvisor.dragonState(7));
        assertEquals(DragonState.CHARGING, BossFightAdvisor.dragonState(8));
        assertEquals(DragonState.UNKNOWN, BossFightAdvisor.dragonState(42));
    }

    @Test
    void perchFallbackAndCountdown() {
        assertTrue(BossFightAdvisor.looksPerched(1.0, -2.0, 0.0));
        assertFalse(BossFightAdvisor.looksPerched(30.0, 0.0, 0.0));
        assertFalse(BossFightAdvisor.looksPerched(1.0, 1.0, 1.0));
        assertEquals(5, BossFightAdvisor.perchSecondsLeft(6, 0));
        assertEquals(1, BossFightAdvisor.perchSecondsLeft(6, 99));
        assertEquals(0, BossFightAdvisor.perchSecondsLeft(6, 150));
        assertEquals(-1, BossFightAdvisor.perchSecondsLeft(0, 10));
        assertEquals(13, BossFightAdvisor.perchChanceDenominator(10));
        assertEquals(3, BossFightAdvisor.perchChanceDenominator(0));
    }

    @Test
    void crystalShots() {
        RangedBallistics rb = new RangedBallistics();
        assertEquals(ShotVerdict.CAGED, BossFightAdvisor.bowShot(rb, 20, 30, BossFightAdvisor.caged(8), true));
        assertEquals(ShotVerdict.CLEAR, BossFightAdvisor.bowShot(rb, 20, 30, BossFightAdvisor.caged(0), true));
        assertEquals(ShotVerdict.BLOCKED, BossFightAdvisor.bowShot(rb, 20, 30, false, false));
        assertEquals(ShotVerdict.OUT_OF_RANGE, BossFightAdvisor.bowShot(rb, 20, 200, false, true));
    }

    @Test
    void breathCloud() {
        assertTrue(BossFightAdvisor.inCloud(2, 64, 0, 0, 64, 0, 3, 0));
        assertFalse(BossFightAdvisor.inCloud(4, 64, 0, 0, 64, 0, 3, 0));
        assertTrue(BossFightAdvisor.inCloud(4, 64, 0, 0, 64, 0, 3, 1.5));
        assertFalse(BossFightAdvisor.inCloud(0, 70, 0, 0, 64, 0, 3, 0));
    }

    @Test
    void witherArmourAndSkulls() {
        assertFalse(BossFightAdvisor.witherArmoured(300, 300));
        assertTrue(BossFightAdvisor.witherArmoured(150, 300));
        assertEquals("Wither armoured: melee only", BossFightAdvisor.witherHint(100, 300));
        // skull at x=-10 flying +x towards the player at origin
        assertTrue(BossFightAdvisor.skullIncoming(-10, 0, 0, 0.5, 0, 0, false, 0, 0, 0, 1.0, 60));
        // flying away
        assertFalse(BossFightAdvisor.skullIncoming(-10, 0, 0, -0.5, 0, 0, false, 0, 0, 0, 1.0, 60));
        // offset by 3 blocks sideways: passes, not a hit at radius 1
        assertFalse(BossFightAdvisor.skullIncoming(-10, 0, 3, 0.5, 0, 0, true, 0, 0, 0, 1.0, 60));
        // blue skulls slow down: a slow blue skull from far away never arrives
        assertFalse(BossFightAdvisor.skullIncoming(-40, 0, 0, 0.1, 0, 0, true, 0, 0, 0, 1.0, 100));
    }
}
