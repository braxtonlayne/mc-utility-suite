package com.utilitysuite.travel.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElytraGlideFootprintTest {

    @Test
    void bestGlideIsShallowNoseDownWithAboutTenToOne() {
        System.out.println("best pitch " + ElytraGlideSim.BEST_GLIDE_PITCH + " ratio " + ElytraGlideSim.BEST_GLIDE_RATIO);
        assertTrue(ElytraGlideSim.BEST_GLIDE_PITCH > -5 && ElytraGlideSim.BEST_GLIDE_PITCH < 15);
        assertTrue(ElytraGlideSim.BEST_GLIDE_RATIO > 7 && ElytraGlideSim.BEST_GLIDE_RATIO < 14);
    }

    @Test
    void steepDiveIsWorseThanBestGlide() {
        ElytraGlideSim s = new ElytraGlideSim();
        double best = s.set(0.6, 0, 100).distanceForDrop(ElytraGlideSim.BEST_GLIDE_PITCH, 100, 5000);
        double dive = s.set(0.6, 0, 100).distanceForDrop(60, 100, 5000);
        assertTrue(best > dive * 2, best + " vs " + dive);
    }

    @Test
    void pullingUpTradesSpeedForHeight() {
        ElytraGlideSim s = new ElytraGlideSim().set(2.0, 0, 0);
        for (int i = 0; i < 10; i++) {
            s.step(-40);
        }
        assertTrue(s.y > 3, "climbed " + s.y);
        assertTrue(s.vh < 2.0);
    }

    @Test
    void stallAndFlightPath() {
        assertTrue(ElytraGlideSim.stalled(0.3, -10, 0.5));
        assertFalse(ElytraGlideSim.stalled(0.3, 30, 0.5));
        assertFalse(ElytraGlideSim.stalled(1.0, -10, 0.5));
        assertEquals(45.0, ElytraGlideSim.flightPathDeg(1, 1), 1e-9);
        assertEquals(-45.0, ElytraGlideSim.flightPathDeg(1, -1), 1e-9);
    }

    @Test
    void flatFootprintIsARingAtGlideRange() {
        GlideFootprint fp = new GlideFootprint(16, 3000);
        fp.computeAll(0.5, 100, 0.5, 0.6, 0, (x, z) -> 64);
        for (int i = 0; i < 16; i++) {
            assertTrue(fp.landed[i]);
            assertEquals(64, fp.ringY[i], 1e-9);
            assertEquals(fp.reach[0], fp.reach[i], 1e-6);
        }
        double expected = 36 * ElytraGlideSim.BEST_GLIDE_RATIO;
        assertTrue(Math.abs(fp.reach[0] - expected) < expected * 0.25, fp.reach[0] + " vs " + expected);
        // heading 0 is south (+z), heading 4 of 16 is west (-x)
        assertTrue(fp.ringZ[0] > 100);
        assertTrue(fp.ringX[4] < -100);
    }

    @Test
    void mountainCutsTheRingShort() {
        GlideFootprint fp = new GlideFootprint(4, 3000);
        // a 200-high wall 50 blocks south
        fp.computeAll(0.5, 100, 0.5, 0.6, 0, (x, z) -> z >= 50 ? 200 : 0);
        assertTrue(fp.landed[0]);
        assertTrue(fp.reach[0] < 52, "south " + fp.reach[0]);
        assertTrue(fp.reach[2] > 500, "north " + fp.reach[2]);
    }

    @Test
    void airborneWhenOutOfTicks() {
        GlideFootprint fp = new GlideFootprint(1, 5);
        fp.computeHeading(0, 0, 300, 0, 0.6, 0, (x, z) -> 0);
        assertFalse(fp.landed[0]);
    }

    @Test
    void waveCueHysteresis() {
        assertEquals(GlideFootprint.WaveCue.DIVE, GlideFootprint.waveCue(GlideFootprint.WaveCue.NONE, 1.0, -0.5));
        assertEquals(GlideFootprint.WaveCue.CLIMB, GlideFootprint.waveCue(GlideFootprint.WaveCue.DIVE, 2.5, -1));
        assertEquals(GlideFootprint.WaveCue.CLIMB, GlideFootprint.waveCue(GlideFootprint.WaveCue.CLIMB, 1.5, 0.3));
        assertEquals(GlideFootprint.WaveCue.DIVE, GlideFootprint.waveCue(GlideFootprint.WaveCue.CLIMB, 1.5, -0.1));
    }

    @Test
    void waveComparisonRunsAndLowAltitudeNeverWaves() {
        ElytraGlideSim s = new ElytraGlideSim();
        assertFalse(GlideFootprint.waveBeneficial(s, 0.6, 0, 10));
        double straight = GlideFootprint.flatDistance(s, 0.6, 0, 150, false, 4000);
        double wave = GlideFootprint.flatDistance(s, 0.6, 0, 150, true, 4000);
        System.out.println("straight " + straight + " wave " + wave);
        assertTrue(straight > 0 && wave > 0);
    }
}
