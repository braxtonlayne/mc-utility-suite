package com.utilitysuite.combat.reflex;

import com.utilitysuite.combat.coach.ThreatMath;
import com.utilitysuite.combat.hud.PopCounter;
import com.utilitysuite.combat.tactics.OpponentModel;
import com.utilitysuite.combat.tactics.TacticUtility;
import com.utilitysuite.combat.trigger.TriggerGate;
import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-tick paths of the reflexes, tactics, trigger gate and pop counter allocate nothing. */
class CombatHotPathAllocationTest {

    private static final long BUDGET = 1_024;

    @Test
    void dodgeAndEscapePlanning() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        DodgePlanner p = new DodgePlanner();
        long bytes = AllocationProbe.measure(100_000, () -> {
            p.planDodge(0.5, 0, 10, 0, 0, -2, 0f, false, false, false, false);
            p.planEscape(1.5, 1.5, 20, 6.0, 37f, false, false, false, false);
        });
        assertTrue(bytes < BUDGET, "planner allocated " + bytes + " bytes");
    }

    @Test
    void shieldDecision() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        long bytes = AllocationProbe.measure(100_000, () -> ShieldDecision.decide(true, false, 5, 6f, 10f, 70, true,
                false));
        assertTrue(bytes < BUDGET, "decide allocated " + bytes + " bytes");
    }

    @Test
    void tacticsAndOpponentModel() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        TacticUtility u = new TacticUtility();
        OpponentModel m = new OpponentModel();
        long[] tick = {0};
        long bytes = AllocationProbe.measure(100_000, () -> {
            long t = tick[0]++;
            m.observe((int) (t & 15), t, 0, 0, 0.1, 0, 0, 5, (t & 3) != 0, false);
            u.oppHopRate = m.hopRate(1);
            u.oppDps = m.dps(1, t) + 1;
            u.choose();
            m.strafeBias(1);
            m.shieldUse(1);
        });
        assertTrue(bytes < BUDGET, "tactics allocated " + bytes + " bytes");
    }

    @Test
    void triggerGateAndPopCounter() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        TriggerGate g = new TriggerGate();
        PopCounter c = new PopCounter();
        int[] i = {0};
        long bytes = AllocationProbe.measure(100_000, () -> {
            int k = i[0]++;
            g.tick(k & 7, TriggerGate.valid(k & 7, true, true, false, true, 1f, false, false, false), 2);
            c.pop(k & 63);
            c.count(5);
        });
        assertTrue(bytes < BUDGET, "trigger/pops allocated " + bytes + " bytes");
    }

    @Test
    void popCounterKeepsCountsAndReusesSlots() {
        PopCounter c = new PopCounter();
        c.pop(3);
        c.pop(3);
        assertEquals(2, c.count(3));
        c.forget(3);
        assertEquals(0, c.count(3));
        for (int k = 0; k < PopCounter.CAPACITY + 5; k++) {
            c.pop(100 + k);
        }
        assertEquals(1, c.count(100 + PopCounter.CAPACITY + 4));
    }

    @Test
    void blastDamageMatchesVanillaAtTheCentreAndEdge() {
        assertEquals(43.0, ThreatMath.blastDamage(0, 3), 1e-9);
        assertEquals(0.0, ThreatMath.blastDamage(6, 3), 1e-9);
    }
}
