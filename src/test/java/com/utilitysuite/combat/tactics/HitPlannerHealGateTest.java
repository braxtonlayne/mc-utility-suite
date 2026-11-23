package com.utilitysuite.combat.tactics;

import com.utilitysuite.combat.assistant.CombatSnapshot;
import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Crits only against fast healers" ({@link HitPlanner#healGate}, 2026-11-23): crit-or-nothing needs observed healing
 * that outpaces plain hits; out-damaged by a player that does not, every charged in-reach tick swings.
 */
class HitPlannerHealGateTest {

    static HitPlanner outGeared(boolean outHeals) {
        HitPlanner p = HitPlannerTest.afterOpener();
        p.targetIsPlayer = true;
        p.gearRatio = 3.0;
        p.healGate = true;
        p.oppOutHeals = outHeals;
        return p;
    }

    @Test
    void outHealsComparesItsHealingWithHalfOurPlainDps() {
        // iron sword into diamond: about 1.56 per hit, 12.5-tick cooldown = 2.5 per second; half of that is 1.25
        assertFalse(HitPlanner.outHeals(0.0, 1.56, 12.5), "no healing seen");
        assertFalse(HitPlanner.outHeals(0.25, 1.56, 12.5), "slow regeneration (1 HP per 4 s)");
        assertTrue(HitPlanner.outHeals(2.0, 1.56, 12.5), "fast regeneration (1 HP per half second)");
        assertTrue(HitPlanner.outHeals(1.25, 1.56, 12.5), "the boundary counts");
    }

    @Test
    void critOrNothingNeedsHealingEvidence() {
        assertFalse(outGeared(false).critOrNothing(), "plain hits outpace a slow healer: not crit-or-nothing");
        assertTrue(outGeared(true).critOrNothing(), "it out-heals plain hits: only crits make progress");
        HitPlanner old = HitPlannerTest.afterOpener();
        old.targetIsPlayer = true;
        old.gearRatio = 3.0;
        assertTrue(old.critOrNothing(), "gate off: the gear ratio alone, as before");
    }

    @Test
    void outGearedBySlowHealerSwingsOnTheFirstChargedTickInReach() {
        HitPlanner p = outGeared(false);
        p.oppCharged = true;
        CombatSnapshot s = HitPlannerTest.snap(2.5);
        p.decide(s);
        assertTrue(p.tempo());
        assertEquals(HitPlanner.Kind.SPRINT_KB, p.kind(), "knockback while it fights");
        assertTrue(p.swing(), "no held tick");
        assertFalse(p.jump(), "no hop into its charged hit");
    }

    @Test
    void outGearedByAFastHealerStillCrits() {
        HitPlanner p = outGeared(true);
        p.oppCharged = true;
        p.decide(HitPlannerTest.snap(2.5));
        assertEquals(HitPlanner.Kind.CRIT, p.kind());
        assertTrue(p.jump(), "crit-or-nothing hops even into its charged hit");
        assertFalse(p.swing(), "and waits for the fall");
    }

    @Test
    void tempoStillCritsAWalledTargetButDoesNotHoldTheGroundSwing() {
        HitPlanner p = outGeared(false);
        CombatSnapshot s = HitPlannerTest.snap(2.5);
        s.knockValue = 0.6;
        s.knockKind = ArenaField.KNOCK_WALL;
        s.attackStrength = HitPlannerTest.readyIn(4, 1.6);
        p.decide(s);
        assertEquals(HitPlanner.Kind.CRIT, p.kind(), "pinned on a wall: the crit is free");
        s.attackStrength = 1f;
        HitPlanner q = outGeared(false);
        q.decide(s);
        assertEquals(HitPlanner.Kind.CRIT, q.kind());
        assertTrue(q.swing(), "charged in reach on the ground: the hit goes now, no wait for a fall");
    }

    @Test
    void evenGearIsUnchangedByTheGate() {
        HitPlanner p = HitPlannerTest.afterOpener();
        p.targetIsPlayer = true;
        p.healGate = true;
        p.gearRatio = 1.0;
        assertFalse(p.tempo());
        p.decide(HitPlannerTest.snap(2.5));
        assertEquals(HitPlanner.Kind.CRIT, p.kind(), "no combo, even gear: the crit as before");
    }

    @Test
    void mobsAreUnchanged() {
        HitPlanner p = HitPlannerTest.afterOpener();
        p.healGate = true;
        p.gearRatio = 3.0;
        assertFalse(p.tempo(), "players only");
    }

    @Test
    void decideWithTheGateAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        HitPlanner p = outGeared(false);
        CombatSnapshot s = HitPlannerTest.snap(2.5);
        long[] tick = {0};
        long bytes = AllocationProbe.measure(50_000, () -> {
            long t = tick[0]++;
            s.attackStrength = (t % 13) / 12f;
            p.oppOutHeals = (t & 64) == 0;
            p.oppCharged = (t & 3) == 0;
            p.decide(s);
            if (p.swing()) {
                p.onSwing();
            }
        });
        assertTrue(bytes < 1_024, "HitPlanner with the heal gate allocated " + bytes + " bytes");
    }
}
