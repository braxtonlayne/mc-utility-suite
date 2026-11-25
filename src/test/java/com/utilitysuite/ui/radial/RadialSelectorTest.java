package com.utilitysuite.ui.radial;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RadialSelectorTest {

    @Test
    void eightDirectionsMapClockwiseFromUp() {
        double t = 10;
        assertEquals(0, RadialSelector.sliceFor(0, -20, t, 8)); // up
        assertEquals(1, RadialSelector.sliceFor(20, -20, t, 8)); // up-right
        assertEquals(2, RadialSelector.sliceFor(20, 0, t, 8)); // right
        assertEquals(3, RadialSelector.sliceFor(20, 20, t, 8));
        assertEquals(4, RadialSelector.sliceFor(0, 20, t, 8)); // down
        assertEquals(5, RadialSelector.sliceFor(-20, 20, t, 8));
        assertEquals(6, RadialSelector.sliceFor(-20, 0, t, 8)); // left
        assertEquals(7, RadialSelector.sliceFor(-20, -20, t, 8));
        assertEquals(0, RadialSelector.sliceFor(-3, -20, t, 8)); // slightly left of up still slice 0
    }

    @Test
    void insideThresholdSelectsNothing() {
        assertEquals(-1, RadialSelector.sliceFor(5, 5, 10, 8));
        RadialSelector s = new RadialSelector(8, 10);
        assertEquals(-1, s.add(3, 0));
        assertEquals(2, s.add(9, 0));
    }

    @Test
    void stickIsClampedSoAReverseFlickReselectsQuickly() {
        RadialSelector s = new RadialSelector(8, 10);
        s.add(500, 0); // long flick right, clamped to 20
        assertEquals(2, s.selected());
        assertEquals(6, s.add(-45, 0)); // 20 - 45 = -25: already left
        s.reset();
        assertEquals(-1, s.selected());
    }
}
