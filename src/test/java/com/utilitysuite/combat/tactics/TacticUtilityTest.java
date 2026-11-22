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
    void zeroArenaInputsScoreZeroForTheNewTactics() {
        TacticUtility u = new TacticUtility();
        u.oppHopRate = 1.5;
        u.scoreAll();
        assertEquals(0.0, u.score(Tactic.HIGH_GROUND));
        assertEquals(0.0, u.score(Tactic.CEILING_DENY));
    }

    @Test
    void highGroundWinsAnEvenFight() {
        TacticUtility u = new TacticUtility();
        u.highGround = 0.9;
        assertEquals(Tactic.HIGH_GROUND, settle(u));
        u.highGround = 0.0;
        assertEquals(Tactic.FIGHT, settle(u), "spot gone: back to fighting");
    }

    @Test
    void highGroundNeverBeatsFightWhenClearlyWinningInReach() {
        TacticUtility u = new TacticUtility();
        u.ownDps = 12;
        u.highGround = 1.0;
        u.distance = 2.5;
        assertEquals(Tactic.FIGHT, settle(u));
    }

    @Test
    void ceilingOnlyAgainstAHopper() {
        TacticUtility u = new TacticUtility();
        u.ceilingDeny = 0.9;
        assertEquals(Tactic.FIGHT, settle(u));
        u.oppHopRate = 1.5;
        assertEquals(Tactic.CEILING_DENY, settle(u));
    }

    @Test
    void arenaTacticHasHysteresis() {
        TacticUtility u = new TacticUtility();
        u.highGround = 0.9;
        settle(u);
        for (int i = 0; i < 40; i++) {
            u.highGround = i % 2 == 0 ? 0.8 : 0.9;
            assertEquals(Tactic.HIGH_GROUND, u.choose());
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
