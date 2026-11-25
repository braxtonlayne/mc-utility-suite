package com.utilitysuite.combat.tactics;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaceDivePlannerTest {

    @Test
    void smashBonusMatchesVanillaTiers() {
        assertEquals(0f, MaceDivePlanner.smashBonus(1.5f, 0), 1e-6f, "1.5 blocks is not a smash");
        assertEquals(8f, MaceDivePlanner.smashBonus(2f, 0), 1e-6f);
        assertEquals(12f, MaceDivePlanner.smashBonus(3f, 0), 1e-6f);
        assertEquals(22f, MaceDivePlanner.smashBonus(8f, 0), 1e-6f);
        assertEquals(24f, MaceDivePlanner.smashBonus(10f, 0), 1e-6f);
        // Density V: +2.5 per block
        assertEquals(12f + 7.5f, MaceDivePlanner.smashBonus(3f, 5), 1e-5f);
    }

    @Test
    void fallForBonusInvertsTheCurve() {
        for (float b : new float[]{7f, 12f, 16f, 22f, 30f}) {
            float f = MaceDivePlanner.fallForBonus(b, 0);
            assertTrue(MaceDivePlanner.smashBonus(f, 0) >= b - 1e-3f, "bonus " + b);
            assertTrue(MaceDivePlanner.smashBonus(f - 0.01f, 0) < b, "smallest fall for " + b);
        }
        assertTrue(MaceDivePlanner.fallForBonus(22f, 3) < 8f, "Density lowers the fall needed");
        assertTrue(MaceDivePlanner.fallFor(MaceDivePlanner.Tier.HEAVY) > 3f);
    }

    @Test
    void windLaunchReachesHeavyTierAndSwingsBeforeLanding() {
        MaceDivePlanner.Plan p = new MaceDivePlanner.Plan();
        float need = MaceDivePlanner.fallFor(MaceDivePlanner.Tier.HEAVY);
        MaceDivePlanner.planWindLaunch(p, 0, 1.8, 0.4, 3.0, need, MaceDivePlanner.WIND_LAUNCH_VY, 0);
        assertTrue(p.feasible, "launch apex " + p.apex);
        assertTrue(p.apex > 4.0, "apex " + p.apex);
        assertTrue(p.fallAtSwing >= need);
        assertTrue(p.bonusAtSwing >= 12f);
        // the swing is the last airborne tick in reach: one tick later the feet are on the ground
        double y = 0, vy = MaceDivePlanner.JUMP_VY;
        for (int t = 1; t <= p.swingTick; t++) {
            if (t == 1) {
                vy += MaceDivePlanner.WIND_LAUNCH_VY;
            }
            y += vy;
            vy = (vy - 0.08) * 0.98;
        }
        assertTrue(y > 0, "still airborne at the swing");
        assertTrue(y + vy <= 0, "lands right after the swing (the fall ends within reach)");
    }

    @Test
    void plainJumpCannotSmashAndFullTierNeedsMoreThanALaunch() {
        MaceDivePlanner.Plan p = new MaceDivePlanner.Plan();
        MaceDivePlanner.planWindLaunch(p, 0, 1.8, 0.4, 3.0, MaceDivePlanner.fallFor(MaceDivePlanner.Tier.SMASH),
                0.0, 0);
        assertFalse(p.feasible, "a bare jump falls 1.25 blocks: no smash");
        MaceDivePlanner.planWindLaunch(p, 0, 1.8, 0.4, 3.0, MaceDivePlanner.fallFor(MaceDivePlanner.Tier.FULL),
                MaceDivePlanner.WIND_LAUNCH_VY, 0);
        assertFalse(p.feasible, "one wind charge does not give an 8-block fall");
    }

    @Test
    void targetOutOfHorizontalReachIsInfeasible() {
        MaceDivePlanner.Plan p = new MaceDivePlanner.Plan();
        MaceDivePlanner.planWindLaunch(p, 0, 1.8, 3.5, 3.0, 2f, MaceDivePlanner.WIND_LAUNCH_VY, 0);
        assertFalse(p.feasible);
    }

    @Test
    void elytraDiveFindsStopPointAndMinimumHeight() {
        MaceDivePlanner.Plan p = new MaceDivePlanner.Plan();
        float need = MaceDivePlanner.fallFor(MaceDivePlanner.Tier.FULL);
        MaceDivePlanner.planElytraDive(p, 20, 1.8, 1.0, -0.3, 3.0, need, 0);
        assertTrue(p.feasible);
        assertTrue(p.fallAtSwing >= need && p.bonusAtSwing >= 22f);
        assertTrue(p.stopDistance > 1.0, "stop gliding before reaching the target: " + p.stopDistance);
        double min = MaceDivePlanner.minDiveHeight(p, 1.8, 1.0, -0.3, 3.0, need, 0);
        assertTrue(min > need && min < 20, "min height " + min);
        MaceDivePlanner.planElytraDive(p, min - 1.0, 1.8, 1.0, -0.3, 3.0, need, 0);
        assertFalse(p.feasible, "below the minimum the fall is too short");
    }

    @Test
    void fairSwingRulesForbidSwapMidHit() {
        assertFalse(MaceDivePlanner.swingAllowed(true, 0, false, 5f, 3.05f), "swapped this tick: wait one tick");
        assertTrue(MaceDivePlanner.swingAllowed(true, 1, false, 5f, 3.05f));
        assertFalse(MaceDivePlanner.swingAllowed(false, 5, false, 5f, 3.05f), "the mace must be the held item");
        assertFalse(MaceDivePlanner.swingAllowed(true, 5, true, 5f, 3.05f), "gliding cancels the smash");
        assertFalse(MaceDivePlanner.swingAllowed(true, 5, false, 2f, 3.05f), "fall short of the tier");
        assertFalse(MaceDivePlanner.swapAwayAllowed(0));
        assertTrue(MaceDivePlanner.swapAwayAllowed(1));
    }

    @Test
    void launchWaitsForSmoothedPitch() {
        assertEquals(0, MaceDivePlanner.launchStep(true, true, true, true, 30f));
        assertEquals(1, MaceDivePlanner.launchStep(true, true, true, true, 85f));
        assertEquals(-1, MaceDivePlanner.launchStep(false, true, true, true, 85f));
        assertEquals(-1, MaceDivePlanner.launchStep(true, false, true, true, 85f));
        assertEquals(-1, MaceDivePlanner.launchStep(true, true, true, false, 85f));
    }

    @Test
    void plansAllocateNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        MaceDivePlanner.Plan p = new MaceDivePlanner.Plan();
        double[] sink = {0};
        long bytes = AllocationProbe.measure(20_000, () -> {
            MaceDivePlanner.planWindLaunch(p, 0, 1.8, 0.4, 3.0, 3.05f, MaceDivePlanner.WIND_LAUNCH_VY, 0);
            sink[0] += p.swingTick;
            MaceDivePlanner.planElytraDive(p, 20, 1.8, 1.0, -0.3, 3.0, 8.05f, 0);
            sink[0] += p.stopDistance;
        });
        assertTrue(bytes < 1_024, "plans allocated " + bytes + " bytes");
    }
}
