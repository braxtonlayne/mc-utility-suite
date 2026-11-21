package com.utilitysuite.combat.tactics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Opponent at the origin, observer at (0, 5): the opponent faces +Z, its right is -X. */
class OpponentModelTest {

    @Test
    void ringWrapsAtCapacity() {
        OpponentModel m = new OpponentModel();
        for (int t = 0; t < 40; t++) {
            m.observe(7, t, 0, 0, -0.2, 0, 0, 5, true, false);
        }
        assertEquals(OpponentModel.RING, m.samples(7));
        assertEquals(1.0, m.strafeBias(7), 1e-6);
    }

    @Test
    void biasIsTheSignedShareOfLateralMovement() {
        OpponentModel m = new OpponentModel();
        long t = 0;
        for (int i = 0; i < 24; i++) {
            m.observe(1, t++, 0, 0, -0.2, 0, 0, 5, true, false); // to its right
        }
        for (int i = 0; i < 8; i++) {
            m.observe(1, t++, 0, 0, 0.2, 0, 0, 5, true, false); // to its left
        }
        assertEquals(0.5, m.strafeBias(1), 1e-6);
        // wrap: 32 more to the left replace everything
        for (int i = 0; i < 32; i++) {
            m.observe(1, t++, 0, 0, 0.2, 0, 0, 5, true, false);
        }
        assertEquals(-1.0, m.strafeBias(1), 1e-6);
    }

    @Test
    void hopRateCountsTakeOffs() {
        OpponentModel m = new OpponentModel();
        // a take-off every 8 ticks over 32 ticks = 4 hops in 1.6 s = 2.5 per second
        for (int t = 0; t < 32; t++) {
            m.observe(2, t, 0, 0, 0, 0, 0, 5, t % 8 != 1, false);
        }
        assertEquals(2.5, m.hopRate(2), 1e-6);
    }

    @Test
    void shieldUseAndDps() {
        OpponentModel m = new OpponentModel();
        for (int t = 0; t < 10; t++) {
            m.observe(3, t, 0, 0, 0, 0, 0, 5, true, t < 5);
        }
        assertEquals(0.5, m.shieldUse(3), 1e-6);
        m.recordHit(3, 10, 5f);
        m.recordHit(3, 30, 5f);
        m.recordHit(3, 50, 5f);
        assertEquals(3.0, m.dps(3, 60), 1e-6);
        assertEquals(1.0, m.dps(3, 140), 1e-6, "only the hit at 50 is inside the window");
    }

    @Test
    void leastRecentlySeenOpponentIsEvicted() {
        OpponentModel m = new OpponentModel();
        for (int id = 0; id < OpponentModel.MAX_OPPONENTS; id++) {
            m.observe(id, id, 0, 0, 0, 0, 0, 5, true, false);
        }
        m.observe(0, 100, 0, 0, 0, 0, 0, 5, true, false); // id 0 refreshed; id 1 is now the oldest
        m.observe(99, 101, 0, 0, 0, 0, 0, 5, true, false);
        assertEquals(-1, m.slotOf(1));
        assertEquals(2, m.samples(0));
        assertEquals(1, m.samples(99));
    }
}
