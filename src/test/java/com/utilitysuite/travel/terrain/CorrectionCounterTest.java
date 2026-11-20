package com.utilitysuite.travel.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorrectionCounterTest {

    @Test
    void disablesAfterThresholdWithinWindow() {
        CorrectionCounter c = new CorrectionCounter();
        c.configure(3, 100, 8.0);
        assertFalse(c.record(10, 0.5));
        assertFalse(c.record(40, 0.5));
        assertTrue(c.record(90, 0.5), "third correction within 100 ticks disables the mode");
    }

    @Test
    void spreadOutCorrectionsAreTolerated() {
        CorrectionCounter c = new CorrectionCounter();
        c.configure(3, 100, 8.0);
        assertFalse(c.record(0, 1.0));
        assertFalse(c.record(80, 1.0));
        assertFalse(c.record(150, 1.0), "the first one fell out of the window");
        assertEquals(2, c.count(150));
        assertTrue(c.record(170, 1.0));
    }

    @Test
    void teleportsAreIgnored() {
        CorrectionCounter c = new CorrectionCounter();
        c.configure(2, 100, 8.0);
        assertFalse(c.record(1, 50.0));
        assertFalse(c.record(2, 200.0));
        assertEquals(0, c.count(2));
        assertFalse(c.record(3, 0.2));
        assertTrue(c.record(4, 0.2));
    }

    @Test
    void resetStartsOver() {
        CorrectionCounter c = new CorrectionCounter();
        c.configure(2, 100, 8.0);
        c.record(1, 0.2);
        c.reset();
        assertFalse(c.record(2, 0.2));
    }

    @Test
    void ringBufferWrapsWithoutLosingRecentCorrections() {
        CorrectionCounter c = new CorrectionCounter();
        c.configure(CorrectionCounter.MAX_THRESHOLD, 1000, 8.0);
        for (int i = 0; i < CorrectionCounter.MAX_THRESHOLD * 3; i++) {
            c.record(i, 0.1);
        }
        assertEquals(CorrectionCounter.MAX_THRESHOLD, c.count(CorrectionCounter.MAX_THRESHOLD * 3));
    }
}
