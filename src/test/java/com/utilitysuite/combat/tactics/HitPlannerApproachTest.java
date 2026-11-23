package com.utilitysuite.combat.tactics;

import com.utilitysuite.combat.assistant.CombatSnapshot;
import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static com.utilitysuite.combat.tactics.HitPlannerTest.snap;
import static com.utilitysuite.combat.tactics.HitPlannerTest.target;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The approach crit: hop as the gap closes so the first falling tick meets the opponent's entry into reach. */
class HitPlannerApproachTest {

    /**
     * The target walks in from {@code start} blocks at {@code speed} blocks per tick; we stand still. Returns the
     * tick the hop was pressed (-1 for none) and, through {@code entry}, the tick the target's box entered reach.
     */
    private static int walkIn(HitPlanner p, CombatSnapshot s, double start, double speed, int[] entry) {
        int hop = -1;
        entry[0] = -1;
        s.tdx = -speed; // it walks west, toward us
        for (int t = 0; t < 40; t++) {
            target(s, start - speed * t);
            if (entry[0] < 0 && s.inReach()) {
                entry[0] = t;
            }
            p.decide(s);
            if (p.jump()) {
                assertEquals(-1, hop, "one hop per approach");
                hop = t;
            }
        }
        return hop;
    }

    @Test
    void hopsSoTheFallMeetsTheEntryIntoReach() {
        for (double speed : new double[]{0.25, 0.4, 0.56}) {
            HitPlanner p = new HitPlanner(); // the opener: an approach crit overrides its sprint hit
            p.targetIsPlayer = true;
            CombatSnapshot s = snap(12);
            int[] entry = new int[1];
            int hop = walkIn(p, s, 12, speed, entry);
            assertTrue(hop >= 0, "hopped at speed " + speed);
            int lead = HitPlanner.hopLead(s.headroom);
            int fallFrom = hop + lead;
            // falling from fallFrom for about five ticks: the entry lands inside that window (or just before it)
            assertTrue(entry[0] >= fallFrom - 2 && entry[0] <= fallFrom + 4,
                    "speed " + speed + ": hop " + hop + ", entry " + entry[0] + ", falling from " + fallFrom);
            assertEquals(HitPlanner.Kind.CRIT, p.kind());
            assertTrue(p.approachHop());
        }
    }

    @Test
    void noHopForAStillOrRetreatingTarget() {
        HitPlanner p = new HitPlanner();
        CombatSnapshot s = snap(6);
        int[] entry = new int[1];
        assertEquals(-1, walkIn(p, s, 6, 0.0, entry));
        p = new HitPlanner();
        assertEquals(-1, walkIn(p, s, 4, -0.3, entry));
        p = new HitPlanner();
        assertEquals(-1, walkIn(p, s, 12, 0.1, entry), "a slow drift is not an approach");
    }

    @Test
    void noHopWhenTheHitIsNotChargedByThen() {
        HitPlanner p = new HitPlanner();
        CombatSnapshot s = snap(12);
        s.attackStrength = 0.1f; // about 11 ticks from ready with a sword
        s.ownAttackSpeed = 1.6;
        s.tdx = -0.4;
        int hop = -1;
        for (int t = 0; t < 12 && hop < 0; t++) {
            target(s, 6 - 0.4 * t);
            p.decide(s);
            if (p.jump() && p.approachHop()) {
                hop = t;
            }
        }
        assertEquals(-1, hop);
    }

    @Test
    void hopsEvenWhileItsHitIsChargedBecauseItStartsOutsideItsReach() {
        HitPlanner p = new HitPlanner();
        p.targetIsPlayer = true;
        p.oppCharged = true;
        CombatSnapshot s = snap(12);
        int[] entry = new int[1];
        assertTrue(walkIn(p, s, 12, 0.5, entry) >= 0);
    }

    @Test
    void safetyRulesAndTheSettingStillVetoIt() {
        int[] entry = new int[1];
        CombatSnapshot s = snap(12);
        s.hazardBack = true;
        assertEquals(-1, walkIn(new HitPlanner(), s, 12, 0.5, entry), "drop behind");
        s = snap(12);
        s.selfKnockDepth = 10;
        s.selfKnockDist = 5.0;
        assertEquals(-1, walkIn(new HitPlanner(), s, 12, 0.5, entry), "ravine 5 blocks behind: an air hit carries");
        s = snap(12);
        s.headroom = 2;
        assertEquals(-1, walkIn(new HitPlanner(), s, 12, 0.5, entry), "low ceiling");
        s = snap(12);
        s.knockValue = 0.8;
        s.knockKind = ArenaField.KNOCK_LAVA;
        assertEquals(-1, walkIn(new HitPlanner(), s, 12, 0.5, entry), "lava behind it: knockback");
        HitPlanner off = new HitPlanner();
        off.approachCrit = false;
        assertFalse(walkIn(off, snap(12), 12, 0.5, entry) >= 0 && off.approachHop(), "setting off");
        HitPlanner kb = new HitPlanner();
        kb.policy = HitPlanner.Policy.KNOCKBACK;
        assertEquals(-1, walkIn(kb, snap(12), 12, 0.5, entry), "knockback policy");
    }

    @Test
    void entryTicksPredictsTheGap() {
        assertEquals(4.0, HitPlanner.entryTicks(5.0, 3.0, 0.5), 1e-9);
        assertEquals(-1.0, HitPlanner.entryTicks(2.5, 3.0, 0.5), 1e-9, "already in reach");
        assertEquals(-1.0, HitPlanner.entryTicks(6.0, 3.0, 0.0), 1e-9, "not closing");
    }

    @Test
    void approachPlanningAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        HitPlanner p = new HitPlanner();
        CombatSnapshot s = snap(8);
        s.tdx = -0.4;
        double[] d = {8.0};
        long bytes = AllocationProbe.measure(100_000, () -> {
            d[0] = d[0] < 3.0 ? 8.0 : d[0] - 0.4;
            target(s, d[0]);
            p.decide(s);
            if (p.swing()) {
                p.onSwing();
            }
        });
        assertTrue(bytes < 1_024, "decide allocated " + bytes + " bytes");
    }
}
