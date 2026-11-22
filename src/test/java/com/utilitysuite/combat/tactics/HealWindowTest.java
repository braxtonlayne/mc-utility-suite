package com.utilitysuite.combat.tactics;

import org.junit.jupiter.api.Test;

import static com.utilitysuite.combat.tactics.HealWindow.*;
import static org.junit.jupiter.api.Assertions.*;

/** Healing in windows: trigger in hits left, window scoring, the make-a-window order, never later than the emergency. */
class HealWindowTest {

    private static final float LOW = 8f;

    @Test
    void triggerInHitsLeftAtSeveralDamageValues() {
        // 4 per hit, k 2.5: heal at 10 or below
        assertTrue(wantHeal(10f, 10f, 20f, 4.0, 2.5, LOW));
        assertFalse(wantHeal(10.5f, 10.5f, 20f, 4.0, 2.5, LOW));
        // 6 per hit (a stronger kit): heal already at 15
        assertTrue(wantHeal(15f, 15f, 20f, 6.0, 2.5, LOW));
        assertFalse(wantHeal(15.5f, 15.5f, 20f, 6.0, 2.5, LOW));
        // k 4 against 3 per hit: 12
        assertTrue(wantHeal(12f, 12f, 20f, 3.0, 4.0, LOW));
        assertFalse(wantHeal(12.5f, 12.5f, 20f, 3.0, 1.5, LOW));
        // nothing to heal at full health, whatever the hits left (absorption above full health)
        assertFalse(wantHeal(24f, 20f, 20f, 10.0, 2.5, LOW));
        assertEquals(2.5, hitsLeft(10f, 4.0), 1e-9);
        assertTrue(Double.isInfinite(hitsLeft(10f, 0.0)));
    }

    @Test
    void fallsBackToTheFixedThresholdWhileNoHitIsMeasured() {
        assertTrue(wantHeal(8f, 8f, 20f, 0.0, 2.5, LOW));
        assertFalse(wantHeal(8.5f, 8.5f, 20f, 0.0, 2.5, LOW));
    }

    @Test
    void neverLaterThanTheEmergencyThreshold() {
        for (float low = 2f; low <= 16f; low += 2f) {
            for (double hit = 0.0; hit <= 10.0; hit += 0.5) {
                for (double k = MIN_K; k <= MAX_K; k += 0.5) {
                    for (float eff = 0.5f; eff <= 20f; eff += 0.5f) {
                        if (eff <= low) {
                            assertTrue(wantHeal(eff, eff, 20f, hit, k, low),
                                    "eff " + eff + " low " + low + " hit " + hit + " k " + k);
                        }
                    }
                }
            }
        }
    }

    private static final double MIN_K = 1.5;
    private static final double MAX_K = 4.0;

    @Test
    void sprintKnockbackHitOpensAWindow() {
        // the tick after a sprint hit: 3.5 blocks away, flying off at 0.9 b/t, 8 air ticks left
        int knock = knockTicksLeft(9, false);
        assertEquals(KNOCK_AIR_TICKS - 1, knock);
        int vsPlayer = safeTicks(3.5, 0.9, knock, 3.0, PLAYER_APPROACH, 0, true);
        assertTrue(windowOpen(vsPlayer, EAT_TICKS), "knocked player: " + vsPlayer);
        int vsMob = safeTicks(3.5, 0.9, knock, 3.0, MOB_APPROACH, 0, true);
        assertTrue(vsMob > vsPlayer, "a slower mob gives a longer window");
        // the same distance without the knockback: no window
        int noKnock = safeTicks(3.5, 0.0, 0, 3.0, PLAYER_APPROACH, 0, true);
        assertFalse(windowOpen(noKnock, EAT_TICKS), "in reach, no knockback: " + noKnock);
    }

    @Test
    void knockbackFlightEndsOnLanding() {
        assertEquals(0, knockTicksLeft(0, false), "not hit");
        assertEquals(KNOCK_AIR_TICKS, knockTicksLeft(10, true), "the hit tick: still on the ground, about to fly");
        assertEquals(0, knockTicksLeft(6, true), "landed again");
        assertEquals(0, knockTicksLeft(1, false), "flight time used up");
    }

    @Test
    void longCooldownOpensAWindow() {
        assertTrue(windowOpen(safeTicks(2.5, 0.0, 0, 3.0, PLAYER_APPROACH, 30, true), EAT_TICKS));
        assertFalse(windowOpen(safeTicks(2.5, 0.0, 0, 3.0, PLAYER_APPROACH, 5, true), EAT_TICKS));
        assertEquals(30, safeTicks(2.5, 0.0, 0, 3.0, PLAYER_APPROACH, 30, true), "in reach: the cooldown is the window");
    }

    @Test
    void distanceOpensAWindow() {
        int far = safeTicks(14.0, 0.0, 0, 3.0, PLAYER_APPROACH, 0, true);
        int near = safeTicks(6.0, 0.0, 0, 3.0, PLAYER_APPROACH, 0, true);
        assertTrue(windowOpen(far, EAT_TICKS), "14 blocks: " + far);
        assertFalse(windowOpen(near, EAT_TICKS), "6 blocks: " + near);
        int closing = safeTicks(14.0, -0.6, 0, 3.0, PLAYER_APPROACH, 0, true);
        assertTrue(closing < far, "a fast closer shrinks the window");
        assertEquals(MAX_SAFE, safeTicks(500.0, 0.0, 0, 3.0, PLAYER_APPROACH, 0, true));
    }

    @Test
    void brokenLineOfSightOpensAWindow() {
        int seen = safeTicks(8.0, 0.0, 0, 3.0, PLAYER_APPROACH, 0, true);
        int hidden = safeTicks(8.0, 0.0, 0, 3.0, PLAYER_APPROACH, 0, false);
        assertEquals(seen + NO_SIGHT_BONUS, hidden);
        assertFalse(windowOpen(seen, EAT_TICKS));
        assertTrue(windowOpen(hidden, EAT_TICKS), "behind cover at 8 blocks: " + hidden);
    }

    @Test
    void makeAWindowOrder() {
        // a window: eat
        assertEquals(Plan.EAT, plan(30, EAT_TICKS, true, 1f));
        // no window, in reach with a charged attack: fight for the knockback hit
        assertEquals(Plan.FIGHT_FOR_KNOCKBACK, plan(3, EAT_TICKS, true, 0.95f));
        // no window, attack not charged: make distance along the route
        assertEquals(Plan.RETREAT, plan(3, EAT_TICKS, true, 0.4f));
        // no window, out of reach: make distance
        assertEquals(Plan.RETREAT, plan(10, EAT_TICKS, false, 1f));
        // "most of the eat": the margin
        assertEquals(Plan.EAT, plan(EAT_TICKS - EAT_MARGIN, EAT_TICKS, true, 1f));
        assertNotEquals(Plan.EAT, plan(EAT_TICKS - EAT_MARGIN - 1, EAT_TICKS, true, 1f));
    }

    @Test
    void topUpOnlyWithoutWasteAndAFullWindow() {
        assertTrue(topUp(14, 6, 60, 32), "steak-sized gap, bread fits");
        assertFalse(topUp(17, 6, 60, 32), "would waste 3");
        assertFalse(topUp(18, 2, 60, 32), "18 regenerates already");
        assertFalse(topUp(10, 6, 20, 32), "the window does not cover the eat");
        assertFalse(topUp(10, 0, 60, 32), "not food");
    }
}
