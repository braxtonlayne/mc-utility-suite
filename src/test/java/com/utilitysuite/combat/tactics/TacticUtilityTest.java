package com.utilitysuite.combat.tactics;

import com.utilitysuite.combat.tactics.TacticUtility.Tactic;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TacticUtilityTest {

    private static Tactic settle(TacticUtility u) {
        Tactic t = null;
        for (int i = 0; i < TacticUtility.MIN_DWELL_TICKS + 2; i++) {
            t = u.choose();
        }
        return t;
    }

    @Test
    void evenFightAtFullHealthFights() {
        TacticUtility u = new TacticUtility();
        assertEquals(Tactic.FIGHT, settle(u));
    }

    @Test
    void lowHealthWithHealingRetreatsToHeal() {
        TacticUtility u = new TacticUtility();
        u.ownHealth = 4;
        u.healingItems = 2;
        assertEquals(Tactic.RETREAT_HEAL, settle(u));
    }

    @Test
    void losingBadlyWithNothingToHealFleesAtOnce() {
        TacticUtility u = new TacticUtility();
        u.ownHealth = 3;
        u.oppDps = 15;
        assertEquals(Tactic.FLEE, u.choose(), "urgent flee skips the dwell");
    }

    @Test
    void critHopperInAnEvenTradeGetsKited() {
        TacticUtility u = new TacticUtility();
        u.oppHopRate = 1.5;
        assertEquals(Tactic.KITE, settle(u));
    }

    @Test
    void hysteresisKeepsTheCurrentTacticOnSmallChanges() {
        TacticUtility u = new TacticUtility();
        settle(u);
        u.oppHopRate = 0.6; // KITE edges ahead but by less than the hysteresis
        for (int i = 0; i < 50; i++) {
            u.oppHopRate = i % 2 == 0 ? 0.6 : 0.4;
            assertEquals(Tactic.FIGHT, u.choose());
        }
    }

    @Test
    void dwellDelaysANonUrgentSwitch() {
        TacticUtility u = new TacticUtility();
        u.choose();
        u.ownHealth = 4;
        u.healingItems = 2;
        assertEquals(Tactic.FIGHT, u.choose(), "first tick after the change still FIGHT");
        assertEquals(Tactic.RETREAT_HEAL, settle(u));
    }
}
