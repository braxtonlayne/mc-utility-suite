package com.utilitysuite.core.input;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/** The opt-in pitch turn gain: same math as yaw, own request table, NaN target = off. */
class ManualLookAssistPitchTest {

    private static final double EPS = 1.0e-9;
    private static final double DT = 1 / 144.0;

    @Test
    void noRequestOrNanTargetLeavesPitchRaw() {
        ManualLookAssist a = new ManualLookAssist();
        assertFalse(a.hasLivePitchRequest());
        assertEquals(0.2, a.onFramePitch(0f, 0.2, DT, false), 0.0);
        a.setPitchGain("m", Float.NaN, 1.5, 0.5, 5);
        assertFalse(a.hasLivePitchRequest());
        assertEquals(0.2, a.onFramePitch(0f, 0.2, DT, false), 0.0);
    }

    @Test
    void stillMouseNeverMoves() {
        ManualLookAssist a = new ManualLookAssist();
        a.setPitchGain("m", 45f, 1.5, 0.5, 5);
        assertEquals(0.0, a.onFramePitch(-20f, 0.0, DT, false), 0.0);
    }

    @Test
    void scalesTowardAndAwayAndMatchesYawMath() {
        ManualLookAssist a = new ManualLookAssist();
        a.setPitchGain("m", 45f, 1.5, 0.5, 5); // looking level, ideal 45 down (positive)
        assertEquals(0.3, a.onFramePitch(0f, 0.2, DT, false), EPS);
        assertEquals(-0.1, a.onFramePitch(0f, -0.2, DT, false), EPS);
        assertEquals(ManualLookAssist.scale(0, 0.2, DT, 45, 1.5, 0.5), a.onFramePitch(0f, 0.2, DT, false), EPS);
    }

    @Test
    void extremePitchesDoNotWrapTheError() {
        ManualLookAssist a = new ManualLookAssist();
        a.setPitchGain("m", 90f, 1.5, 0.5, 5);
        // looking straight up (-90): the ideal is 180 degrees away; down is toward, with no wrap to the other side
        assertEquals(0.3, a.onFramePitch(-90f, 0.2, DT, false), EPS);
        assertEquals(-0.1, a.onFramePitch(-90f, -0.2, DT, false), EPS);
    }

    @Test
    void blockedAndTtlAndClear() {
        ManualLookAssist a = new ManualLookAssist();
        a.setPitchGain("m", 45f, 1.5, 0.5, 2);
        assertEquals(0.2, a.onFramePitch(0f, 0.2, DT, true), 0.0);
        a.beginTick();
        assertTrue(a.hasLivePitchRequest());
        a.beginTick();
        assertFalse(a.hasLivePitchRequest());
        a.setPitchGain("m", 45f, 1.5, 0.5, 9);
        a.clearPitchGain("m");
        assertFalse(a.hasLivePitchRequest());
        a.setPitchGain("m", 45f, 1.5, 0.5, 9);
        a.setPitchGain("m", Float.NaN, 1.5, 0.5, 9); // NaN switches this module's request off
        assertFalse(a.hasLivePitchRequest());
        a.setPitchGain("m", 45f, 1.5, 0.5, 9);
        a.clearAll();
        assertFalse(a.hasLivePitchRequest());
    }

    @Test
    void yawAndPitchTablesAreIndependent() {
        ManualLookAssist a = new ManualLookAssist();
        a.setYawGain("m", 90f, 1.5, 0.5, 5);
        assertFalse(a.hasLivePitchRequest());
        a.setPitchGain("m", 30f, 1.5, 0.5, 5);
        a.clearYawGain("m");
        assertTrue(a.hasLivePitchRequest());
    }

    @Test
    void invariantsHoldForRandomInput() {
        SplittableRandom r = new SplittableRandom(11);
        ManualLookAssist a = new ManualLookAssist();
        for (int i = 0; i < 20000; i++) {
            float pitch = (float) (r.nextDouble() * 180 - 90);
            float ideal = (float) (r.nextDouble() * 180 - 90);
            a.setPitchGain("m", ideal, 0.5 + r.nextDouble(), 0.5 + r.nextDouble(), 3);
            double raw = (r.nextDouble() - 0.5) * 6;
            double dt = r.nextDouble() * 0.05 + 1e-4;
            double out = a.onFramePitch(pitch, raw, dt, false);
            assertEquals(Math.signum(raw), Math.signum(out), 0.0);
            assertTrue(Math.abs(out) >= 0.5 * Math.abs(raw) - 1e-9 && Math.abs(out) <= 1.5 * Math.abs(raw) + 1e-9);
            assertTrue(Math.abs(out - raw) <= RotationLimits.MAX_DEG_PER_SEC * Math.min(dt, 0.1) + 1e-9);
        }
    }
}
