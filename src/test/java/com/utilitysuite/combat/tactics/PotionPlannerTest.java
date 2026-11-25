package com.utilitysuite.combat.tactics;

import com.utilitysuite.combat.assistant.CombatSnapshot;
import com.utilitysuite.combat.assistant.SustainPlanner;
import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PotionPlannerTest {

    private static PotionPlanner planner(float health) {
        PotionPlanner p = new PotionPlanner();
        p.enabled = true;
        p.health = health;
        return p;
    }

    @Test
    void offByDefaultDoesNothing() {
        PotionPlanner p = new PotionPlanner();
        p.health = 2f;
        p.drinkHealSlot = 3;
        assertEquals(PotionPlanner.Action.NONE, p.step(0));
    }

    @Test
    void drinksHealingBelowThresholdWhenStandingFree() {
        PotionPlanner p = planner(6f);
        p.drinkHealSlot = 3;
        p.splashHealSlot = 4;
        assertEquals(PotionPlanner.Action.DRINK, p.step(0));
        assertEquals(3, p.slot());
        assertFalse(p.splash());
    }

    @Test
    void healthyPlayerDrinksNothing() {
        PotionPlanner p = planner(18f);
        p.drinkHealSlot = 3;
        p.drinkRegenSlot = 5;
        assertEquals(PotionPlanner.Action.NONE, p.step(0));
    }

    @Test
    void regenerationOnlyWhenNotAlreadyRegenerating() {
        assertEquals(PotionPlanner.Need.REGEN, PotionPlanner.need(11f, false, 8f, 12f));
        assertEquals(PotionPlanner.Need.NONE, PotionPlanner.need(11f, true, 8f, 12f));
        assertEquals(PotionPlanner.Need.HEAL, PotionPlanner.need(7f, true, 8f, 12f));
        PotionPlanner p = planner(11f);
        p.absorption = 4f; // 15 effective: fine
        p.drinkRegenSlot = 2;
        assertEquals(PotionPlanner.Action.NONE, p.step(0));
    }

    @Test
    void chasedPrefersSplashAndAimsSmoothlyBeforeThrowing() {
        PotionPlanner p = planner(6f);
        p.drinkHealSlot = 3;
        p.splashHealSlot = 4;
        p.beingChased = true;
        p.pitch = 0f;
        assertEquals(PotionPlanner.Action.AIM_FEET, p.step(0));
        assertTrue(p.aiming());
        assertEquals(4, p.slot());
        // the core smoother moves the camera a capped amount per tick; no throw before it arrives
        p.pitch = 40f;
        assertEquals(PotionPlanner.Action.AIM_FEET, p.step(1));
        p.pitch = 70f;
        assertEquals(PotionPlanner.Action.AIM_FEET, p.step(2));
        p.pitch = 85f;
        assertEquals(PotionPlanner.Action.THROW_SPLASH, p.step(3));
        assertFalse(p.aiming());
        // cooldown: the splash lands before the next decision
        assertEquals(PotionPlanner.Action.NONE, p.step(4));
    }

    @Test
    void splashUsedWhenNoDrinkableAndAimGivesUpOnTimeout() {
        PotionPlanner p = planner(6f);
        p.splashHealSlot = 1;
        p.pitch = 10f;
        assertEquals(PotionPlanner.Action.AIM_FEET, p.step(0));
        PotionPlanner.Action last = PotionPlanner.Action.AIM_FEET;
        for (int t = 1; t <= PotionPlanner.AIM_TIMEOUT + 1; t++) {
            last = p.step(t);
        }
        assertEquals(PotionPlanner.Action.NONE, last);
        assertFalse(p.aiming());
    }

    @Test
    void manualInputWinsMidAim() {
        PotionPlanner p = planner(6f);
        p.splashHealSlot = 1;
        p.pitch = 10f;
        assertEquals(PotionPlanner.Action.AIM_FEET, p.step(0));
        p.manualOverride = true;
        p.pitch = 89f;
        assertEquals(PotionPlanner.Action.NONE, p.step(1));
        assertFalse(p.aiming());
    }

    @Test
    void noHealingPotionFallsBackToRegeneration() {
        PotionPlanner p = planner(5f);
        p.drinkRegenSlot = 6;
        assertEquals(PotionPlanner.Action.DRINK, p.step(0));
        assertEquals(6, p.slot());
    }

    @Test
    void postPopPressureSkipsHealingUntilCritical() {
        PotionPlanner p = planner(7f);
        p.criticalHealth = 4f;
        p.drinkHealSlot = 3;
        p.onOpponentPop(100);
        assertTrue(p.pressureActive(110));
        assertEquals(PotionPlanner.Action.NONE, p.step(110), "keep pressure after the pop");
        p.health = 4f;
        assertEquals(PotionPlanner.Action.DRINK, p.step(111), "critical health still heals");
        PotionPlanner q = planner(7f);
        q.drinkHealSlot = 3;
        q.onOpponentPop(100);
        assertFalse(q.pressureActive(100 + q.pressureTicks));
        assertEquals(PotionPlanner.Action.DRINK, q.step(100 + q.pressureTicks), "window over: heal");
    }

    @Test
    void sustainRetreatHonoursPressure() {
        CombatSnapshot s = new CombatSnapshot();
        s.health = 6f;
        assertEquals(SustainPlanner.Action.RETREAT, SustainPlanner.decide(s, 8f, 2f, true, true, false));
        assertEquals(SustainPlanner.Action.NONE, SustainPlanner.decide(s, 8f, 2f, true, true, true));
        // lethal damage still pulls a totem
        s.health = 3f;
        s.totemInvSlot = 12;
        assertEquals(SustainPlanner.Action.EQUIP_TOTEM, SustainPlanner.decide(s, 8f, 2f, true, true, true));
        assertTrue(PotionPlanner.pressureHoldsRetreat(true, 7f, 4f));
        assertFalse(PotionPlanner.pressureHoldsRetreat(true, 4f, 4f));
        assertFalse(PotionPlanner.pressureHoldsRetreat(false, 7f, 4f));
    }

    @Test
    void stepAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        PotionPlanner p = planner(6f);
        p.drinkHealSlot = 3;
        p.splashHealSlot = 4;
        long[] t = {0};
        long bytes = AllocationProbe.measure(50_000, () -> {
            long k = t[0]++;
            p.beingChased = (k & 1) == 0;
            p.pitch = (k & 3) * 30f;
            p.step(k);
        });
        assertTrue(bytes < 1_024, "step allocated " + bytes + " bytes");
    }
}
