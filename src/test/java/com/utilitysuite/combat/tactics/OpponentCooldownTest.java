package com.utilitysuite.combat.tactics;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpponentCooldownTest {

    @Test
    void periodFollowsAttackSpeed() {
        OpponentModel m = new OpponentModel();
        m.noteSwing(1, 100, 1.6);
        m.noteSwing(2, 100, 1.0);
        m.noteSwing(3, 100, 4.0);
        assertEquals(12.5, m.cooldownTicks(1), 1e-9);
        assertEquals(20.0, m.cooldownTicks(2), 1e-9);
        assertEquals(5.0, m.cooldownTicks(3), 1e-9);
    }

    @Test
    void attackSpeedIsClamped() {
        OpponentModel m = new OpponentModel();
        m.noteSwing(1, 0, 0.0);
        assertEquals(20.0 / OpponentModel.MIN_ATTACK_SPEED, m.cooldownTicks(1), 1e-9);
        m.noteSwing(1, 0, -3.0);
        assertEquals(80.0, m.cooldownTicks(1), 1e-9);
        m.noteSwing(1, 0, 1000.0);
        assertEquals(20.0 / OpponentModel.MAX_ATTACK_SPEED, m.cooldownTicks(1), 1e-9);
        m.noteSwing(1, 0, Double.NaN);
        assertEquals(12.5, m.cooldownTicks(1), 1e-9);
    }

    @Test
    void chargeCurveIsLinearAndClamped() {
        OpponentModel m = new OpponentModel();
        m.noteSwing(1, 100, 1.0); // 20 ticks
        assertEquals(0f, m.attackCharge(1, 100), 1e-6f);
        assertEquals(0.25f, m.attackCharge(1, 105), 1e-6f);
        assertEquals(0.5f, m.attackCharge(1, 110), 1e-6f);
        assertEquals(1f, m.attackCharge(1, 120), 1e-6f);
        assertEquals(1f, m.attackCharge(1, 500), 1e-6f);
        assertEquals(0f, m.attackCharge(1, 90), 1e-6f, "a clock before the swing clamps to 0");
    }

    @Test
    void readyInRoundsUp() {
        OpponentModel m = new OpponentModel();
        m.noteSwing(1, 0, 1.6); // 12.5 ticks
        assertEquals(13, m.attackReadyIn(1, 0));
        assertEquals(12, m.attackReadyIn(1, 1));
        assertEquals(1, m.attackReadyIn(1, 12));
        assertEquals(0, m.attackReadyIn(1, 13));
        assertEquals(0, m.attackReadyIn(1, 99));
        m.noteSwing(2, 0, 1.0); // integral period: exactly 20
        assertEquals(20, m.attackReadyIn(2, 0));
        assertEquals(1, m.attackReadyIn(2, 19));
        assertEquals(0, m.attackReadyIn(2, 20));
    }

    @Test
    void unknownOpponentCountsAsReady() {
        OpponentModel m = new OpponentModel();
        assertEquals(0, m.attackReadyIn(9, 50));
        assertEquals(1f, m.attackCharge(9, 50), 0f);
        assertEquals(12.5, m.cooldownTicks(9), 0.0);
        // seen (observed) but never swinging: still ready
        m.observe(9, 10, 0, 0, 0, 0, 0, 5, true, false);
        assertEquals(0, m.attackReadyIn(9, 11));
        assertEquals(1f, m.attackCharge(9, 11), 0f);
        assertEquals(12.5, m.cooldownTicks(9), 0.0);
    }

    @Test
    void reSwingMidCooldownRestarts() {
        OpponentModel m = new OpponentModel();
        m.noteSwing(1, 0, 1.0);
        assertEquals(10, m.attackReadyIn(1, 10));
        m.noteSwing(1, 10, 1.6);
        assertEquals(13, m.attackReadyIn(1, 10));
        assertEquals(0f, m.attackCharge(1, 10), 1e-6f);
        assertEquals(12.5, m.cooldownTicks(1), 1e-9);
    }

    @Test
    void evictionAndClearReset() {
        OpponentModel m = new OpponentModel();
        for (int i = 0; i < OpponentModel.MAX_OPPONENTS; i++) {
            m.noteSwing(100 + i, i, 1.0);
        }
        // a ninth opponent evicts the least recently seen slot (id 100) and starts clean
        m.noteSwing(999, 50, 4.0);
        assertEquals(-1, m.slotOf(100));
        assertEquals(0, m.attackReadyIn(100, 50), "evicted id is unknown again");
        assertEquals(5.0, m.cooldownTicks(999), 1e-9);
        // a slot reused by observe() must not inherit the evicted swing state
        OpponentModel n = new OpponentModel();
        for (int i = 0; i < OpponentModel.MAX_OPPONENTS; i++) {
            n.noteSwing(i, i, 1.0);
        }
        n.observe(500, 60, 0, 0, 0, 0, 0, 5, true, false);
        assertEquals(0, n.attackReadyIn(500, 60));
        assertEquals(12.5, n.cooldownTicks(500), 0.0);
        n.clear();
        for (int i = 0; i < OpponentModel.MAX_OPPONENTS; i++) {
            assertEquals(0, n.attackReadyIn(i, 0));
            assertEquals(12.5, n.cooldownTicks(i), 0.0);
        }
        n.noteSwing(3, 5, 1.0);
        n.clear();
        assertEquals(0, n.attackReadyIn(3, 5));
        assertEquals(1f, n.attackCharge(3, 5), 0f);
    }

    @Test
    void swingBookkeepingIsAllocationFree() {
        if (!AllocationProbe.supported()) {
            return;
        }
        OpponentModel m = new OpponentModel();
        long[] clock = {0};
        long bytes = AllocationProbe.measure(200_000, () -> {
            long t = clock[0]++;
            m.noteSwing((int) (t & 3), t, 1.6);
            m.attackReadyIn((int) (t & 3), t + 3);
            m.attackCharge((int) (t & 3), t + 3);
            m.cooldownTicks((int) (t & 3));
        });
        assertTrue(bytes < 64_000, "allocated " + bytes + " bytes over 200k calls");
    }
}
