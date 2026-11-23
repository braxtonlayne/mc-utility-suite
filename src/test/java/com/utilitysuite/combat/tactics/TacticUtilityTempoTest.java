package com.utilitysuite.combat.tactics;

import com.utilitysuite.combat.tactics.TacticUtility.Tactic;
import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The retreat read and the out-ranged hop pressure in {@link TacticUtility} (2026-11-23). */
class TacticUtilityTempoTest {

    private static Tactic settle(TacticUtility u) {
        Tactic t = null;
        for (int i = 0; i < TacticUtility.MIN_DWELL_TICKS + 2; i++) {
            t = u.choose();
        }
        return t;
    }

    @Test
    void aRetreatingOpponentMakesFightWinAtOnce() {
        TacticUtility u = new TacticUtility();
        u.ownHealth = 4;
        u.healingItems = 2;
        u.oppHopRate = 1.5;
        assertEquals(Tactic.RETREAT_HEAL, settle(u));
        u.oppRetreating = true;
        assertEquals(Tactic.FIGHT, u.choose(), "no dwell: it is running, chase it");
        assertEquals(0.0, u.score(Tactic.KITE));
        assertEquals(0.0, u.score(Tactic.RETREAT_HEAL));
        assertEquals(0.0, u.score(Tactic.FLEE));
        u.oppRetreating = false;
        assertEquals(Tactic.FIGHT, u.choose(), "and the dwell holds FIGHT for a moment after it turns back");
    }

    @Test
    void outRangedHopsPressLess() {
        // out-geared (advantage about 0.2) against a steady hopper: KITE used to win on the hops alone
        TacticUtility u = new TacticUtility();
        u.gearRatio = 3.0;
        u.oppHopRate = 1.5;
        assertEquals(Tactic.KITE, settle(u));
        TacticUtility r = new TacticUtility();
        r.gearRatio = 3.0;
        r.oppHopRate = 1.5;
        r.outRanged = true;
        assertEquals(Tactic.FIGHT, settle(r), "we out-reach it: the solver ranges the hops, no kiting");
    }

    @Test
    void chooseAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        TacticUtility u = new TacticUtility();
        long[] tick = {0};
        long bytes = AllocationProbe.measure(50_000, () -> {
            long t = tick[0]++;
            u.oppRetreating = (t & 32) == 0;
            u.outRanged = (t & 64) == 0;
            u.oppHopRate = (t % 7) * 0.3;
            u.choose();
        });
        assertTrue(bytes < 1_024, "TacticUtility allocated " + bytes + " bytes");
    }
}
