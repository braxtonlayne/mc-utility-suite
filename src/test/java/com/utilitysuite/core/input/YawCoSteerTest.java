package com.utilitysuite.core.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The co-steer follower: rate and acceleration limits, no overshoot, allowance, the 450 deg/s cap, the hand. */
class YawCoSteerTest {

    private static final double EPS = 1e-9;

    /** Result of a simulated run. */
    private static final class Run {
        double yaw;
        double maxStepPerSec;
        double maxDv;
        double maxDvAllowed;
        boolean crossed;
        double arrivedAt = -1;
        double totalAdded;
    }

    /**
     * Simulates {@code seconds} at {@code fps} with a request renewed every tick and the hand producing
     * {@code handDegPerFrame}. The camera turns by hand + added each frame.
     */
    private static Run simulate(YawCoSteer s, double startYaw, float ideal, double rate, double total, double authority,
                                int fps, double seconds, double handDegPerFrame) {
        Run r = new Run();
        r.yaw = startYaw;
        double dt = 1.0 / fps;
        int framesPerTick = Math.max(1, fps / 20);
        int frames = (int) Math.round(seconds * fps);
        double sign0 = Math.signum(RotationCurve.wrapDegrees(ideal - startYaw));
        double prevV = 0.0;
        for (int i = 0; i < frames; i++) {
            if (i % framesPerTick == 0) {
                s.beginTick();
                s.request(ideal, rate, total, authority);
            }
            double add = s.onFrame((float) r.yaw, handDegPerFrame, handDegPerFrame, dt, false);
            r.maxStepPerSec = Math.max(r.maxStepPerSec, Math.abs(add) / dt);
            double v = s.velocityDegPerSec();
            r.maxDv = Math.max(r.maxDv, Math.abs(v - prevV));
            prevV = v;
            r.yaw += handDegPerFrame + add;
            double err = RotationCurve.wrapDegrees(ideal - r.yaw);
            if (handDegPerFrame == 0.0 && Math.signum(err) == -sign0 && Math.abs(err) > 1e-4) {
                r.crossed = true;
            }
            if (r.arrivedAt < 0 && Math.abs(err) < 0.05) {
                r.arrivedAt = i * dt;
            }
        }
        r.maxDvAllowed = YawCoSteer.ACCEL_DEG_PER_S2 * authority * dt;
        r.totalAdded = s.totalAddedDeg();
        return r;
    }

    @Test
    void reachesTheIdealWithoutOvershoot() {
        YawCoSteer s = new YawCoSteer();
        Run r = simulate(s, 0.0, 40f, 300, 360, 1.0, 120, 2.0, 0.0);
        assertFalse(r.crossed, "never past the ideal");
        assertEquals(40.0, r.yaw, 0.05);
        assertTrue(r.arrivedAt > 0 && r.arrivedAt < 1.5, "arrived at " + r.arrivedAt);
    }

    @Test
    void negativeDirectionAndWrapAround() {
        YawCoSteer s = new YawCoSteer();
        Run r = simulate(s, 170.0, -170f, 300, 360, 1.0, 120, 2.0, 0.0); // the short way is +20 through 180
        assertFalse(r.crossed);
        assertEquals(0.0, RotationCurve.wrapDegrees(-170.0 - r.yaw), 0.05);
        assertTrue(r.totalAdded < 21.0, "took the short way: " + r.totalAdded);

        s = new YawCoSteer();
        r = simulate(s, 0.0, -30f, 300, 360, 1.0, 60, 2.0, 0.0);
        assertFalse(r.crossed);
        assertEquals(-30.0, r.yaw, 0.05);
    }

    @Test
    void rateCapIsRespected() {
        YawCoSteer s = new YawCoSteer();
        Run r = simulate(s, 0.0, 170f, 90, 360, 1.0, 240, 3.0, 0.0);
        assertTrue(r.maxStepPerSec <= 90.0 + EPS, "max " + r.maxStepPerSec);
    }

    @Test
    void authorityScalesTheRate() {
        YawCoSteer s = new YawCoSteer();
        Run r = simulate(s, 0.0, 170f, 200, 360, 0.25, 240, 3.0, 0.0);
        assertTrue(r.maxStepPerSec <= 50.0 + EPS, "max " + r.maxStepPerSec);
    }

    @Test
    void accelerationCapIsRespectedAtAnyFrameRate() {
        for (int fps : new int[]{30, 60, 144, 240, 500}) {
            YawCoSteer s = new YawCoSteer();
            Run r = simulate(s, 0.0, 120f, 450, 360, 1.0, fps, 2.0, 0.0);
            assertTrue(r.maxDv <= r.maxDvAllowed + EPS, fps + " fps: dv " + r.maxDv + " > " + r.maxDvAllowed);
            assertFalse(r.crossed);
        }
    }

    @Test
    void feelIsTheSameAt60And240Fps() {
        Run a = simulate(new YawCoSteer(), 0.0, 60f, 300, 360, 1.0, 60, 2.0, 0.0);
        Run b = simulate(new YawCoSteer(), 0.0, 60f, 300, 360, 1.0, 240, 2.0, 0.0);
        assertEquals(a.arrivedAt, b.arrivedAt, 0.1, "arrival time should not depend on the frame rate");
        assertEquals(a.yaw, b.yaw, 0.05);
    }

    @Test
    void totalAllowanceIsNeverExceeded() {
        YawCoSteer s = new YawCoSteer();
        Run r = simulate(s, 0.0, 90f, 300, 10, 1.0, 120, 3.0, 0.0);
        assertEquals(10.0, r.totalAdded, 1e-6);
        assertEquals(10.0, r.yaw, 1e-6);
    }

    @Test
    void absurdRequestIsHardCappedAt450() {
        YawCoSteer s = new YawCoSteer();
        Run r = simulate(s, 0.0, 179f, 1.0e9, 1.0e9, 50.0, 240, 2.0, 0.0);
        assertTrue(r.maxStepPerSec <= RotationLimits.MAX_DEG_PER_SEC + EPS, "max " + r.maxStepPerSec);
        // a frame hitch never widens the step beyond 450 x 0.1 s
        YawCoSteer h = new YawCoSteer();
        h.request(179f, 1.0e9, 1.0e9, 1.0);
        double first = 0;
        for (int i = 0; i < 50; i++) {
            first = Math.max(first, Math.abs(h.onFrame(0f, 0.0, 0.0, 5.0, false)));
        }
        assertTrue(first <= RotationLimits.MAX_DEG_PER_SEC * ManualLookAssist.MAX_FRAME_SECONDS + EPS);
    }

    @Test
    void blockedNotLiveAndBadInputAddExactlyZero() {
        YawCoSteer s = new YawCoSteer();
        assertEquals(0.0, s.onFrame(0f, 1.0, 1.0, 0.016, false), "no request");
        s.request(90f, 300, 90, 1);
        assertEquals(0.0, s.onFrame(0f, 0.0, 0.0, 0.016, true), "blocked");
        assertEquals(0.0, s.onFrame(0f, 0.0, 0.0, 0.0, false), "zero frame time");
        assertEquals(0.0, s.onFrame(0f, 0.0, 0.0, Double.NaN, false));
        assertEquals(0.0, s.onFrame(0f, Double.NaN, 0.0, 0.016, false));
        s.request(Float.NaN, 300, 90, 1);
        s.beginTick();
        s.beginTick();
        assertFalse(s.isLive(), "a NaN request does not renew");
    }

    @Test
    void handMotionTowardTheIdealLeavesLessToAdd() {
        Run still = simulate(new YawCoSteer(), 0.0, 30f, 300, 360, 1.0, 120, 0.5, 0.0);
        Run helping = simulate(new YawCoSteer(), 0.0, 30f, 300, 360, 1.0, 120, 0.5, 0.1);
        assertTrue(helping.totalAdded < still.totalAdded,
                "helping hand " + helping.totalAdded + " vs still " + still.totalAdded);
    }

    @Test
    void handPastTheIdealIsNeverPushedBack() {
        YawCoSteer s = new YawCoSteer();
        s.request(10f, 300, 90, 1);
        for (int i = 0; i < 20; i++) {
            s.onFrame(0f, 0.0, 0.0, 0.01, false);
        }
        // the hand turns 15 degrees in one frame: the camera ends past the ideal; the follower adds nothing back
        assertEquals(0.0, s.onFrame(0f, 15.0, 15.0, 0.01, false));
        assertEquals(0.0, s.velocityDegPerSec());
    }

    @Test
    void opposingHandMotionIsAccumulated() {
        YawCoSteer s = new YawCoSteer();
        s.request(90f, 300, 90, 1);
        s.onFrame(0f, 0.5, 0.5, 0.01, false);   // toward: not counted
        s.onFrame(0f, -1.0, -1.0, 0.01, false); // against
        s.onFrame(0f, -2.5, -2.5, 0.01, false); // against
        assertEquals(3.5, s.opposingDeg(), 1e-12);
        s.reset();
        assertEquals(0.0, s.opposingDeg());
        assertEquals(0.0, s.totalAddedDeg());
    }

    @Test
    void lapsesWithoutARenewedRequest() {
        YawCoSteer s = new YawCoSteer();
        s.request(90f, 300, 90, 1);
        s.beginTick();
        assertTrue(s.isLive());
        assertTrue(s.onFrame(0f, 0.0, 0.0, 0.016, false) > 0.0);
        s.beginTick();
        assertFalse(s.isLive());
        assertEquals(0.0, s.onFrame(0f, 0.0, 0.0, 0.016, false));
        assertEquals(0.0, s.velocityDegPerSec());
    }
}
