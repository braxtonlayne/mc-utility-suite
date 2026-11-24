package com.utilitysuite.combat.tactics;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpponentHitDelayTest {

    private static void pair(OpponentModel m, int id, long swing, int gap) {
        m.noteSwing(id, swing, 1.6);
        m.recordHit(id, swing + gap, 4f);
    }

    @Test
    void fallbackUntilEnoughSamples() {
        OpponentModel m = new OpponentModel();
        assertEquals(1, m.hitDelay(7, 1));
        pair(m, 7, 100, 3);
        assertEquals(1, m.hitDelay(7, 1), "one sample is not a read");
        pair(m, 7, 200, 3);
        assertEquals(3, m.hitDelay(7, 1));
    }

    @Test
    void averageFollowsAndIsClamped() {
        OpponentModel m = new OpponentModel();
        for (int i = 0; i < 6; i++) {
            pair(m, 1, 100L * (i + 1), 0);
        }
        assertEquals(0, m.hitDelay(1, 1));
        for (int i = 0; i < 12; i++) {
            pair(m, 1, 1000L + 100L * i, 6);
        }
        assertEquals(OpponentModel.MAX_HIT_DELAY, m.hitDelay(1, 1), "clamped to the cap");
    }

    @Test
    void aMissThenALaterHitIsNotASample() {
        OpponentModel m = new OpponentModel();
        pair(m, 1, 100, 2);
        pair(m, 1, 200, 2);
        m.noteSwing(1, 300, 1.6);
        m.recordHit(1, 330, 4f); // the swing missed, this hit is another swing's
        m.recordHit(1, 331, 4f); // already paired
        assertEquals(2, m.hitDelay(1, 1));
    }

    @Test
    void perOpponentAndClear() {
        OpponentModel m = new OpponentModel();
        pair(m, 1, 100, 3);
        pair(m, 1, 200, 3);
        pair(m, 2, 100, 1);
        pair(m, 2, 200, 1);
        assertEquals(3, m.hitDelay(1, 1));
        assertEquals(1, m.hitDelay(2, 9));
        m.clear();
        assertEquals(5, m.hitDelay(1, 5));
    }

    @Test
    void shieldRaisesLaterAgainstASlowHitAndEarlierAgainstAFastOne() {
        ShieldDuel d = new ShieldDuel();
        d.blockEnabled = true;
        d.hasShield = true;
        d.oppInCone = true;
        d.oppInReach = true;
        d.oppReadyIn = ShieldDuel.RAISE_DELAY + ShieldDuel.REACTION_MARGIN;
        assertTrue(d.step(), "default lead unchanged");
        d.reset();
        d.oppHitDelay = ShieldDuel.HIT_LEAD + 1;
        assertFalse(d.step(), "its hit lands a tick later: wait");
        d.oppReadyIn--;
        assertTrue(d.step());
        d.reset();
        d.oppHitDelay = 0;
        d.oppReadyIn = ShieldDuel.RAISE_DELAY + ShieldDuel.REACTION_MARGIN + 1;
        assertTrue(d.step(), "its hit lands a tick sooner: raise a tick earlier");
    }

    @Test
    void hitDelayPathAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        OpponentModel m = new OpponentModel();
        long[] t = {0};
        long bytes = AllocationProbe.measure(50_000, () -> {
            t[0] += 20;
            m.noteSwing(1, t[0], 1.6);
            m.recordHit(1, t[0] + 2, 4f);
            m.hitDelay(1, 1);
        });
        assertTrue(bytes < 1_024, "allocated " + bytes + " bytes");
    }
}
