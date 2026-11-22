package com.utilitysuite.ui.holo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootSequenceTest {

    @BeforeEach
    void reset() {
        BootSequence.resetSessionForTests();
    }

    @Test
    void phasesFollowTheDesignTimeline() {
        assertEquals(BootSequence.Phase.LINE, BootSequence.phaseAt(0));
        assertEquals(BootSequence.Phase.LINE, BootSequence.phaseAt(119));
        assertEquals(BootSequence.Phase.UNFOLD, BootSequence.phaseAt(120));
        assertEquals(BootSequence.Phase.UNFOLD, BootSequence.phaseAt(299));
        assertEquals(BootSequence.Phase.COLUMNS, BootSequence.phaseAt(300));
        assertEquals(BootSequence.Phase.COLUMNS, BootSequence.phaseAt(519));
        assertEquals(BootSequence.Phase.READOUT, BootSequence.phaseAt(520));
        assertEquals(BootSequence.Phase.READOUT, BootSequence.phaseAt(899));
        assertEquals(BootSequence.Phase.DONE, BootSequence.phaseAt(900));
        assertEquals(BootSequence.Phase.DONE, BootSequence.phaseAt(-1));
    }

    @Test
    void curvesStartAndEndWhereTheyShould() {
        assertEquals(0f, BootSequence.line(0));
        assertEquals(1f, BootSequence.line(120));
        assertEquals(0f, BootSequence.unfold(120));
        assertEquals(1f, BootSequence.unfold(300));
        assertTrue(BootSequence.flicker(120));
        assertFalse(BootSequence.flicker(160));
        // columns stagger 40 ms apart and are all revealed by the end of COLUMNS
        assertEquals(0f, BootSequence.column(0, 300));
        assertTrue(BootSequence.column(0, 340) > BootSequence.column(1, 340));
        assertEquals(0f, BootSequence.column(2, 380));
        for (int i = 0; i < 3; i++) {
            assertEquals(1f, BootSequence.column(i, 520));
        }
        assertEquals(0, BootSequence.readoutPercent(520));
        assertEquals(100, BootSequence.readoutPercent(760));
        assertEquals(1f, BootSequence.readoutBright(760));
        assertEquals(0f, BootSequence.readoutBright(900));
        float prev = -1f;
        for (long t = 120; t <= 300; t += 10) { // brackets snap: overshoot allowed, settles at 1
            prev = BootSequence.brackets(t);
        }
        assertEquals(1f, prev, 1e-4f);
    }

    @Test
    void playsOncePerSessionAndOnlyWithFullMotion() {
        BootSequence b = new BootSequence();
        assertTrue(b.begin(1000, Motion.FULL));
        assertEquals(0L, b.elapsed(1000));
        assertEquals(450L, b.elapsed(1450));
        assertEquals(-1L, b.elapsed(1900));
        assertEquals(-1L, b.elapsed(1500)); // finished stays finished
        assertFalse(new BootSequence().begin(5000, Motion.FULL));

        BootSequence.resetSessionForTests();
        assertFalse(new BootSequence().begin(0, Motion.REDUCED));
        assertFalse(new BootSequence().begin(0, Motion.FULL)); // the session flag was spent
        BootSequence.resetSessionForTests();
        assertFalse(new BootSequence().begin(0, Motion.OFF));
    }

    @Test
    void anyInputSkips() {
        BootSequence b = new BootSequence();
        assertTrue(b.begin(0, Motion.FULL));
        assertEquals(100L, b.elapsed(100));
        b.skip();
        assertEquals(-1L, b.elapsed(200));
    }
}
