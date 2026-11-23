package com.utilitysuite.combat.tactics;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The threat reads added to {@link OpponentModel} (2026-11-23): reach learned from its hits, recovery after our hit,
 * healing, chase pace, reaction hops and the retreat read.
 */
class OpponentThreatReadsTest {

    static final int ID = 7;

    // ---- reach -------------------------------------------------------------------------------------------------------

    @Test
    void reachFallsBackUntilThreeHitsThenTakesTheSecondLongest() {
        OpponentModel m = new OpponentModel();
        m.noteHitDistance(ID, 1, 2.9);
        m.noteHitDistance(ID, 2, 2.5);
        assertFalse(m.reachLearned(ID));
        assertEquals(3.0, m.learnedReach(ID, 3.0), 1e-9, "two hits prove nothing");
        m.noteHitDistance(ID, 3, 2.8);
        assertTrue(m.reachLearned(ID));
        assertEquals(2.9, m.learnedReach(ID, 3.0), 1e-6, "three samples: the longest");
        m.noteHitDistance(ID, 4, 3.6); // one lag outlier
        assertEquals(2.9, m.learnedReach(ID, 3.0), 1e-6, "four or more: the second longest ignores one outlier");
        m.noteHitDistance(ID, 5, 3.5);
        assertEquals(3.5, m.learnedReach(ID, 3.0), 1e-6, "two long hits are a real long reach");
    }

    @Test
    void closeRangeHitsNeverClaimAReachBelowTheFloor() {
        OpponentModel m = new OpponentModel();
        for (int i = 0; i < 8; i++) {
            m.noteHitDistance(ID, i, 1.2);
        }
        assertEquals(OpponentModel.MIN_LEARNED_REACH, m.learnedReach(ID, 3.0), 1e-9);
        m.noteHitDistance(ID, 9, Double.NaN);
        assertEquals(8, m.reachSamples(ID), "NaN is ignored");
    }

    // ---- recovery after our hit ----------------------------------------------------------------------------------------

    @Test
    void stunIsTheShortestRecoveryWhileChargedAndZeroUntilLearned() {
        OpponentModel m = new OpponentModel();
        assertEquals(0, m.hitStun(ID));
        // charged (never swung), our hit at 10, its answer at 17: 7 ticks
        m.noteOwnHit(ID, 10);
        m.noteSwing(ID, 17, 1.6);
        assertEquals(0, m.hitStun(ID), "one sample is not enough");
        // charged again at 40; it answers at 44
        m.noteOwnHit(ID, 40);
        m.noteSwing(ID, 44, 1.6);
        assertEquals(3, m.hitStun(ID), "min(7, 4) - 1");
    }

    @Test
    void anUnchargedOpponentGivesNoStunSampleAndSilenceCaps() {
        OpponentModel m = new OpponentModel();
        m.noteSwing(ID, 0, 1.6);
        m.noteOwnHit(ID, 3); // its cooldown, not a stun, keeps it from answering
        m.noteSwing(ID, 13, 1.6);
        assertEquals(0, m.hitStun(ID));
        for (int k = 0; k < 2; k++) {
            long hit = 100 + k * 100L;
            m.noteOwnHit(ID, hit);
            for (long t = hit; t <= hit + OpponentModel.STUN_SAMPLE_CAP; t++) {
                m.observe(ID, t, 3, 0, 0, 0, 0, 0, true, false);
            }
        }
        assertEquals(OpponentModel.MAX_STUN, m.hitStun(ID), "no answer for a second: capped");
    }

    // ---- healing ---------------------------------------------------------------------------------------------------

    @Test
    void healRateCountsOnlyRisesInTheWindow() {
        OpponentModel m = new OpponentModel();
        m.noteHealth(ID, 0, 20f);
        m.noteHealth(ID, 1, 15f); // our hit: not healing
        for (int t = 2; t <= 21; t++) {
            m.noteHealth(ID, t, 15f + (t - 1) * 0.25f); // 5 points over 20 ticks
        }
        assertEquals(5.0 * 20.0 / OpponentModel.HEAL_WINDOW, m.healRate(ID, 21), 1e-4);
        assertEquals(0.0, m.healRate(ID, 21 + OpponentModel.HEAL_WINDOW + 1), 1e-9, "old healing drops out");
    }

    // ---- hops and pace -------------------------------------------------------------------------------------------

    @Test
    void takeOffsRightAfterOurHitAreNotPressure() {
        OpponentModel m = new OpponentModel();
        long t = 0;
        // a free crit hop
        m.observe(ID, t++, 3, 0, 0, 0, 0, 0, true, false);
        m.observe(ID, t++, 3, 0, 0, 0, 0, 0, false, false);
        m.observe(ID, t++, 3, 0, 0, 0, 0, 0, true, false);
        // our hit lifts it, then it hops on landing (a combo break)
        m.noteOwnHit(ID, t);
        m.observe(ID, t++, 3, 0, 0, 0, 0, 0, false, false);
        m.observe(ID, t++, 3, 0, 0, 0, 0, 0, true, false);
        m.observe(ID, t++, 3, 0, 0, 0, 0, 0, false, false);
        assertEquals(3 * 20.0 / 6, m.hopRate(ID), 1e-9, "every take-off");
        assertEquals(20.0 / 6, m.pressureHopRate(ID), 1e-9, "only its own hop");
    }

    @Test
    void chaseSpeedAveragesItsRunsAtUs() {
        OpponentModel m = new OpponentModel();
        assertTrue(Double.isNaN(m.chaseSpeed(ID)));
        // it stands at x=4, we at 0; moving -x closes in
        m.observe(ID, 0, 4, 0, -0.26, 0, 0, 0, true, false);
        assertEquals(0.26, m.chaseSpeed(ID), 1e-9);
        m.observe(ID, 1, 4, 0, -0.05, 0, 0, 0, true, false); // a strafe tick: not a run
        assertEquals(0.26, m.chaseSpeed(ID), 1e-9);
        m.observe(ID, 2, 4, 0, -0.16, 0, 0, 0, true, false);
        assertEquals(0.26 + OpponentModel.CHASE_ALPHA * (0.16 - 0.26), m.chaseSpeed(ID), 1e-9);
    }

    // ---- retreat -------------------------------------------------------------------------------------------------

    /** It stands at x=4 (we at the origin); yaw -90 looks toward +X (away from us). */
    static void run(OpponentModel m, long from, int ticks, float yaw, double vx) {
        for (int i = 0; i < ticks; i++) {
            m.observe(ID, from + i, 4, 0, vx, 0, 0, 0, true, false);
            m.observeRetreat(ID, from + i, yaw, 4, 0, vx, 0, 0, 0);
        }
    }

    @Test
    void retreatNeedsFacingAwayRunningAndQuietForFiveTicks() {
        OpponentModel m = new OpponentModel();
        run(m, 0, OpponentModel.RETREAT_TICKS - 1, -90f, 0.26);
        assertFalse(m.retreating(ID));
        run(m, 4, 1, -90f, 0.26);
        assertTrue(m.retreating(ID));

        OpponentModel back = new OpponentModel();
        run(back, 0, 20, 90f, 0.26); // back-pedalling while facing us: it can still hit
        assertFalse(back.retreating(ID));

        OpponentModel slow = new OpponentModel();
        run(slow, 0, 20, -90f, 0.1);
        assertFalse(slow.retreating(ID), "a walk away is spacing, not a retreat");

        OpponentModel swinging = new OpponentModel();
        swinging.noteSwing(ID, 0, 1.6);
        run(swinging, 1, OpponentModel.RETREAT_QUIET - 2, -90f, 0.26);
        assertFalse(swinging.retreating(ID), "it swung a moment ago");
    }

    @Test
    void retreatHoldsAgainstAWallAndEndsWhenItTurnsOrSwings() {
        OpponentModel m = new OpponentModel();
        run(m, 0, 6, -90f, 0.26);
        assertTrue(m.retreating(ID));
        run(m, 6, 10, -90f, 0.05); // stuck at a wall, still facing away
        assertTrue(m.retreating(ID), "slowing at a wall does not end it");
        run(m, 16, 1, 90f, 0.0); // turns round
        assertFalse(m.retreating(ID));

        run(m, 17, 6, -90f, 0.26);
        assertTrue(m.retreating(ID));
        m.noteSwing(ID, 23, 1.6);
        assertFalse(m.retreating(ID), "a swing ends it at once");
    }

    // ---- allocation ------------------------------------------------------------------------------------------------

    @Test
    void perTickReadsAllocateNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        OpponentModel m = new OpponentModel();
        long[] tick = {0};
        double[] sink = {0};
        long bytes = AllocationProbe.measure(50_000, () -> {
            long t = tick[0]++;
            int id = (int) (t % 11); // more ids than slots: slot reuse
            double vx = (t & 16) == 0 ? 0.26 : -0.2;
            m.observe(id, t, 4, 0, vx, 0.01, 0, 0, (t & 7) != 0, false);
            m.noteHealth(id, t, 10f + (t % 9));
            m.observeRetreat(id, t, (float) (t % 360), 4, 0, vx, 0.01, 0, 0);
            if (t % 13 == 0) {
                m.noteOwnHit(id, t);
            }
            if (t % 17 == 0) {
                m.noteSwing(id, t, 1.6);
                m.noteHitDistance(id, t, 2.0 + (t % 10) * 0.1);
            }
            sink[0] += m.learnedReach(id, 3.0) + m.hitStun(id) + m.healRate(id, t) + m.pressureHopRate(id)
                    + (m.retreating(id) ? 1 : 0) + (m.reachLearned(id) ? 1 : 0);
            double c = m.chaseSpeed(id);
            if (c == c) {
                sink[0] += c;
            }
        });
        assertTrue(bytes < 1_024, "OpponentModel threat reads allocated " + bytes + " bytes over 50k ticks");
        assertTrue(sink[0] != 12345.678);
    }
}
