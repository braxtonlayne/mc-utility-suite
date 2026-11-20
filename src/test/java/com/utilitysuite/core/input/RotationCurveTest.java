package com.utilitysuite.core.input;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RotationCurveTest {

    private static final double FRAME = 1.0 / 144.0;

    /** Runs the curve to completion and returns the number of frames used. */
    private static int runToEnd(RotationCurve c) {
        int frames = 0;
        while (c.isActive() && frames < 10_000) {
            c.advance(FRAME);
            frames++;
        }
        return frames;
    }

    @RepeatedTest(20)
    void reachesTargetExactly() {
        Random rnd = new Random();
        RotationCurve c = new RotationCurve(rnd);
        float fromYaw = rnd.nextFloat() * 720f - 360f;
        float fromPitch = rnd.nextFloat() * 180f - 90f;
        float toYaw = rnd.nextFloat() * 720f - 360f;
        float toPitch = rnd.nextFloat() * 180f - 90f;
        c.start(fromYaw, fromPitch, toYaw, toPitch, 1f);
        int frames = runToEnd(c);
        assertFalse(c.isActive());
        assertTrue(frames < 10_000);
        assertEquals(0.0, RotationCurve.wrapDegrees(c.yaw() - toYaw), 1e-3);
        assertEquals(toPitch, c.pitch(), 1e-3);
        assertEquals(0.0, c.remainingError(), 1e-3);
    }

    @Test
    void takesShortestArcAcrossTheWrap() {
        RotationCurve c = new RotationCurve(new Random(7));
        c.setDriftAmplitude(0);
        c.setCurvature(0);
        c.start(170f, 0f, -170f, 0f, 1f); // 20 degrees clockwise, not 340 back
        assertEquals(190f, c.targetYaw(), 1e-4, "target unwrapped next to the start yaw");
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        while (c.isActive()) {
            c.advance(FRAME);
            min = Math.min(min, c.yaw());
            max = Math.max(max, c.yaw());
        }
        assertTrue(min >= 170f - 1e-3, "never turned the long way: min " + min);
        assertTrue(max <= 190f + c.maxOvershoot() + 1e-3, "max " + max);
        assertEquals(190f, c.yaw(), 1e-3);
    }

    @RepeatedTest(20)
    void overshootIsBounded() {
        Random rnd = new Random();
        RotationCurve c = new RotationCurve(rnd);
        float toYaw = 30f + rnd.nextFloat() * 150f;
        float toPitch = rnd.nextFloat() * 60f - 30f;
        c.start(0f, 0f, toYaw, toPitch, 1f);
        double dist = Math.hypot(toYaw, toPitch);
        double ux = toYaw / dist;
        double uy = toPitch / dist;
        double maxBeyond = 0;
        while (c.isActive()) {
            c.advance(FRAME);
            double along = (c.yaw() - toYaw) * ux + (c.pitch() - toPitch) * uy;
            maxBeyond = Math.max(maxBeyond, along);
        }
        assertTrue(maxBeyond <= c.maxOvershoot() + c.driftAmplitude() + 1e-3,
                "overshoot " + maxBeyond + " exceeds bound");
    }

    @Test
    void overshootActuallyHappensOnLargeMoves() {
        RotationCurve c = new RotationCurve(new Random(3));
        c.setDriftAmplitude(0);
        c.setCurvature(0);
        c.start(0f, 0f, 120f, 0f, 1f);
        float max = 0;
        while (c.isActive()) {
            c.advance(FRAME);
            max = Math.max(max, c.yaw());
        }
        assertTrue(max > 120f, "natural overshoot past the target");
        assertEquals(120f, c.yaw(), 1e-4);
    }

    @Test
    void motionIsEasedAndCurved() {
        RotationCurve c = new RotationCurve(new Random(11));
        c.setDriftAmplitude(0);
        c.start(0f, 0f, 90f, 0f, 1f);
        c.advance(c.totalDuration() * 0.05);
        double early = c.yaw();
        c.advance(c.totalDuration() * 0.45); // now at ~50% of time
        double mid = c.yaw();
        assertTrue(early < 90 * 0.05, "slow start (eased), got " + early);
        assertTrue(mid > 30 && mid < 70, "mid-way around half, got " + mid);
        // Off the straight line at some point (sideways bend on pitch for a pure yaw move).
        RotationCurve d = new RotationCurve(new Random(11));
        d.setDriftAmplitude(0);
        d.start(0f, 0f, 90f, 0f, 1f);
        double maxPitch = 0;
        while (d.isActive()) {
            d.advance(FRAME);
            maxPitch = Math.max(maxPitch, Math.abs(d.pitch()));
        }
        assertTrue(maxPitch > 0.1, "curved path");
    }

    @Test
    void pitchIsClamped() {
        RotationCurve c = new RotationCurve(new Random(5));
        c.start(0f, 80f, 0f, 120f, 1f);
        assertEquals(90f, c.targetPitch());
        while (c.isActive()) {
            c.advance(FRAME);
            assertTrue(c.pitch() <= 90f && c.pitch() >= -90f);
        }
    }

    @Test
    void speedScaleShortensDuration() {
        RotationCurve a = new RotationCurve(new Random(1));
        RotationCurve b = new RotationCurve(new Random(1));
        a.start(0, 0, 90, 0, 1f);
        b.start(0, 0, 90, 0, 3f);
        assertTrue(b.totalDuration() < a.totalDuration());
    }

    @Test
    void retargetKeepsMotionAndReachesNewTarget() {
        RotationCurve c = new RotationCurve(new Random(2));
        c.start(0f, 0f, 60f, 0f, 1f);
        c.advance(0.1);
        c.retarget(65f, 5f);
        runToEnd(c);
        assertEquals(65f, c.yaw(), 1e-3);
        assertEquals(5f, c.pitch(), 1e-3);
    }

    @Test
    void tinyMoveFinishesImmediately() {
        RotationCurve c = new RotationCurve(new Random(2));
        c.start(10f, 10f, 10.0001f, 10f, 1f);
        assertFalse(c.isActive());
        assertEquals(10.0001f, c.yaw(), 1e-5);
    }

    @Test
    void valueNoiseIsBoundedAndContinuous() {
        double prev = RotationCurve.valueNoise(0);
        for (double x = 0; x < 50; x += 0.01) {
            double v = RotationCurve.valueNoise(x);
            assertTrue(v >= -1 && v <= 1);
            assertTrue(Math.abs(v - prev) < 0.1);
            prev = v;
        }
    }
}
