package com.utilitysuite.core.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MomentumReleaseInvertTest {

    private static final double DT = 1 / 144.0;

    /** Momentum pitching down (+). Raw user dy sign +1 means down with vanilla axes, up when inverted. */
    @Test
    void invertOffKeepsVanillaBehaviour() {
        MomentumRelease m = new MomentumRelease();
        m.start(0, 200, false);
        m.advance(DT, 0, 1); // along
        assertTrue(m.pitchStep() > 0);
        m.advance(DT, 0, -1); // against
        assertEquals(0.0, m.pitchStep());
    }

    @Test
    void invertOnCancelsOnOppositeUserMovement() {
        MomentumRelease m = new MomentumRelease();
        m.start(0, 200, true);
        m.advance(DT, 0, 1); // raw dy > 0 with invert = pitch up, against the downward momentum
        assertEquals(0.0, m.pitchStep());
    }

    @Test
    void invertOnKeepsMomentumAlongUserMovement() {
        MomentumRelease m = new MomentumRelease();
        m.start(0, 200, true);
        m.advance(DT, 0, -1); // raw dy < 0 with invert = pitch down, along
        assertTrue(m.pitchStep() > 0);
    }

    @Test
    void yawUnaffectedByInvert() {
        for (boolean inv : new boolean[] {false, true}) {
            MomentumRelease m = new MomentumRelease();
            m.start(200, 0, inv);
            m.advance(DT, 1, 0);
            assertTrue(m.yawStep() > 0);
            m.advance(DT, -1, 0);
            assertEquals(0.0, m.yawStep());
        }
    }

    @Test
    void invertFlagResetsOnCancelAndPlainStart() {
        MomentumRelease m = new MomentumRelease();
        m.start(0, 200, true);
        m.cancel();
        m.start(0, 200);
        m.advance(DT, 0, -1);
        assertEquals(0.0, m.pitchStep());
    }
}
