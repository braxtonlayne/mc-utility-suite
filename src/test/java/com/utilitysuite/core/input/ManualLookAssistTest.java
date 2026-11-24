package com.utilitysuite.core.input;

import com.utilitysuite.core.api.IManualLookAssist;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

class ManualLookAssistTest {

    private static final double EPS = 1.0e-9;
    private static final double DT = 1 / 144.0;

    private static double scale(double yaw, double raw, double dt, double ideal, double toward, double away) {
        return ManualLookAssist.scale(yaw, raw, dt, ideal, toward, away);
    }

    // ---- scaling ----------------------------------------------------------------------------------------------------

    @Test
    void towardAndAwayAreScaledSeparately() {
        // ideal 90 deg ahead (positive), far outside the fade, small deltas well under the cap
        assertEquals(0.3, scale(0, 0.2, DT, 90, 1.5, 0.5), EPS);   // toward: x1.5
        assertEquals(-0.1, scale(0, -0.2, DT, 90, 1.5, 0.5), EPS); // away: x0.5
        // ideal behind (negative): negative motion is toward
        assertEquals(-0.3, scale(0, -0.2, DT, -90, 1.5, 0.5), EPS);
        assertEquals(0.1, scale(0, 0.2, DT, -90, 1.5, 0.5), EPS);
        // unit gains leave the delta untouched
        assertEquals(0.2, scale(0, 0.2, DT, 90, 1.0, 1.0), 0.0);
    }

    @Test
    void gainsAreClamped() {
        assertEquals(0.3, scale(0, 0.2, DT, 90, 9.0, 1.0), EPS);
        assertEquals(0.1, scale(0, 0.2, DT, 90, 0.0, 1.0), EPS);
        assertEquals(-0.1, scale(0, -0.2, DT, 90, 1.0, -4.0), EPS);
        assertEquals(-0.3, scale(0, -0.2, DT, 90, 1.0, 12.0), EPS);
        assertEquals(0.2, scale(0, 0.2, DT, 90, Double.NaN, 1.0), EPS); // NaN gain = 1
        assertEquals(1.5, ManualLookAssist.clampGain(Double.POSITIVE_INFINITY));
        assertEquals(0.5, ManualLookAssist.clampGain(Double.NEGATIVE_INFINITY));
        assertEquals(IManualLookAssist.MIN_GAIN, ManualLookAssist.MIN_GAIN);
    }

    @Test
    void zeroInIsZeroOut() {
        assertEquals(0.0, scale(0, 0.0, DT, 90, 1.5, 0.5), 0.0);
        assertEquals(0.0, scale(10, 0.0, 0.05, -30, 0.5, 1.5), 0.0);
        ManualLookAssist a = new ManualLookAssist();
        a.setYawGain("m", 90f, 1.5, 0.5, 5);
        assertEquals(0.0, a.onFrameYaw(0f, 0.0, DT, false), 0.0);
        a.beginTick();
        assertEquals(0.0, a.manualYawRateDegPerTick(), 0.0);
        assertEquals(0.0, a.assistedYawDegLastTick(), 0.0);
    }

    @Test
    void aYawRequestLeavesPitchUntouched() {
        // pitch has its own opt-in request table (setPitchGain, ManualLookAssistPitchTest); a yaw request never
        // scales the pitch delta
        ManualLookAssist a = new ManualLookAssist();
        a.setYawGain("m", 90f, 1.5, 0.5, 5);
        assertFalse(a.hasLivePitchRequest());
        assertEquals(0.7, a.onFramePitch(10f, 0.7, DT, false), 0.0);
        assertEquals(-0.3, a.onFramePitch(10f, -0.3, DT, false), 0.0);
    }

    @Test
    void nonFiniteInputsPassThrough() {
        assertEquals(0.2, scale(Double.NaN, 0.2, DT, 90, 1.5, 0.5), 0.0);
        assertEquals(0.2, scale(0, 0.2, DT, Double.POSITIVE_INFINITY, 1.5, 0.5), 0.0);
        assertEquals(0.2, scale(0, 0.2, Double.NaN, 90, 1.5, 0.5), 0.0);
        assertEquals(0.2, scale(0, 0.2, -1.0, 90, 1.5, 0.5), 0.0);
        assertTrue(Double.isNaN(scale(0, Double.NaN, DT, 90, 1.5, 0.5)));
        assertEquals(Double.POSITIVE_INFINITY, scale(0, Double.POSITIVE_INFINITY, DT, 90, 1.5, 0.5));
    }

    // ---- dead zone, fade, overshoot ---------------------------------------------------------------------------------

    @Test
    void deadZoneHasUnitGain() {
        assertEquals(0.01, scale(0, 0.01, DT, 0.4, 1.5, 0.5), 0.0);
        assertEquals(-0.01, scale(0, -0.01, DT, 0.4, 1.5, 0.5), 0.0);
        assertEquals(0.01, scale(0, 0.01, DT, -0.5, 1.5, 0.5), 0.0);
    }

    @Test
    void gainFadesTowardOneNearTheIdeal() {
        double raw = 0.01;
        double far = scale(0, raw, DT, 20, 1.5, 1.0) / raw;
        double mid = scale(0, raw, DT, 5.25, 1.5, 1.0) / raw;
        double near = scale(0, raw, DT, 1.0, 1.5, 1.0) / raw;
        assertEquals(1.5, far, 1e-9);
        assertEquals(1.25, mid, 1e-9); // halfway through the fade band (0.5 .. 10)
        assertTrue(near > 1.0 && near < mid, "near " + near);
        // away gain fades the same way
        assertEquals(0.75, scale(0, -raw, DT, 5.25, 1.0, 0.5) / -raw, 1e-9);
    }

    @Test
    void amplifiedTurnStopsAtTheIdeal() {
        // 3 deg toward an ideal 4 deg away: x1.5 would give 4.5 (past the ideal); limited to the 4 deg error
        double out = scale(0, 3.0, 0.05, 20, 1.5, 1.0); // fade 1 at 20 deg; use err below
        assertEquals(4.5, out, 1e-9);
        double e = 12.0;
        out = scale(0, 10.0, 0.05, e, 1.5, 1.0);
        assertEquals(e, out, 1e-9, "the gain carries the camera to the ideal, not past it");
        // the raw delta alone already crosses the ideal: no amplification at all
        assertEquals(15.0, scale(0, 15.0, 0.05, e, 1.5, 1.0), 0.0);
        assertEquals(-15.0, scale(0, -15.0, 0.05, -e, 1.5, 1.0), 0.0);
        // reductions toward the ideal are not limited by the error
        assertEquals(7.5, scale(0, 15.0, 0.05, e, 0.5, 1.0), 1e-9);
    }

    @Test
    void noOvershootPropertyAcrossInputs() {
        SplittableRandom r = new SplittableRandom(7);
        for (int i = 0; i < 100_000; i++) {
            double yaw = r.nextDouble(-540, 540);
            double ideal = r.nextDouble(-540, 540);
            double raw = r.nextDouble(-40, 40);
            double out = scale(yaw, raw, r.nextDouble(0.001, 0.2), ideal, r.nextDouble(0.3, 1.8), 1.0);
            double err = RotationCurve.wrapDegrees(ideal - yaw);
            boolean toward = (err > 0) == (raw > 0);
            if (toward && Math.abs(err) > ManualLookAssist.DEAD_ZONE_DEG) {
                double rawPast = Math.max(0, Math.abs(raw) - Math.abs(err));
                double outPast = Math.max(0, Math.abs(out) - Math.abs(err));
                assertTrue(outPast <= rawPast + 1e-9, "overshoot created: yaw " + yaw + " ideal " + ideal + " raw "
                        + raw + " out " + out);
            }
        }
    }

    // ---- 450 deg/s cap ----------------------------------------------------------------------------------------------

    @Test
    void addedDegreesAreCappedForEveryFrameTime() {
        double[] dts = {0.001, 0.007, 0.016, 0.05};
        for (double dt : dts) {
            double cap = RotationLimits.MAX_DEG_PER_SEC * dt;
            // extreme toward delta far from the ideal (ideal 179 away so the error is not the limit)
            double raw = 100.0;
            double out = scale(0, raw, dt, 179, 1.5, 1.0);
            assertEquals(raw + Math.min(cap, Math.min(50.0, 179 - raw)), out, 1e-9, "dt " + dt);
            assertTrue(out - raw <= cap + 1e-12);
            // extreme away delta: the removed degrees are capped too
            out = scale(0, -raw, dt, 179, 1.0, 0.5);
            assertEquals(-raw + cap, out, 1e-9, "dt " + dt);
            // a small delta is not affected by the cap
            assertEquals(0.015, scale(0, 0.01, dt, 179, 1.5, 1.0), 1e-12);
        }
        // a long hitch never widens the cap past 0.1 s worth
        double out = scale(0, 170.0, 2.0, -179.9, 0.5, 0.5);
        assertTrue(Math.abs(out - 170.0) <= RotationLimits.MAX_DEG_PER_SEC * ManualLookAssist.MAX_FRAME_SECONDS + 1e-9);
    }

    @Test
    void propertySignCapAndBound() {
        SplittableRandom r = new SplittableRandom(42);
        for (int i = 0; i < 200_000; i++) {
            double yaw = r.nextDouble(-1000, 1000);
            double ideal = r.nextDouble(-1000, 1000);
            double raw = r.nextInt(4) == 0 ? r.nextDouble(-2000, 2000) : r.nextDouble(-3, 3); // extreme sensitivities
            double dt = r.nextInt(3) == 0 ? r.nextDouble(0.0, 1.0) : r.nextDouble(0.0005, 0.06);
            double out = scale(yaw, raw, dt, ideal, r.nextDouble(-1, 3), r.nextDouble(-1, 3));
            double cap = RotationLimits.MAX_DEG_PER_SEC * Math.min(dt, ManualLookAssist.MAX_FRAME_SECONDS);
            assertEquals(Math.signum(raw), Math.signum(out), "sign");
            assertTrue(Math.abs(out - raw) <= cap + 1e-9, "cap: raw " + raw + " out " + out + " dt " + dt);
            assertTrue(Math.abs(out) <= 1.5 * Math.abs(raw) + 1e-9, "bound");
            assertTrue(Math.abs(out) >= 0.5 * Math.abs(raw) - 1e-9, "lower bound");
        }
    }

    @Test
    void yawWrapsAcrossPlusMinus180() {
        // camera at 175, ideal at -175: the short way is +10 (crossing 180), so positive motion is toward
        assertEquals(0.15, scale(175, 0.1, DT, -175, 1.5, 0.5), 1e-9);
        assertEquals(-0.05, scale(175, -0.1, DT, -175, 1.5, 0.5), 1e-9);
        // and the mirror case
        assertEquals(-0.15, scale(-175, -0.1, DT, 175, 1.5, 0.5), 1e-9);
        // unwrapped yaw values (yaw keeps counting past 360)
        assertEquals(0.15, scale(175 + 720, 0.1, DT, -175, 1.5, 0.5), 1e-9);
    }

    // ---- requests ---------------------------------------------------------------------------------------------------

    @Test
    void ttlExpires() {
        ManualLookAssist a = new ManualLookAssist();
        a.setYawGain("m", 90f, 1.5, 1.0, 2);
        assertEquals(0.3, a.scaleYawDelta(0f, 0.2, DT), EPS);
        a.beginTick();
        assertTrue(a.hasLiveRequest());
        assertEquals(0.3, a.scaleYawDelta(0f, 0.2, DT), EPS);
        a.beginTick();
        assertFalse(a.hasLiveRequest());
        assertEquals(0.2, a.scaleYawDelta(0f, 0.2, DT), 0.0);
        a.setYawGain("m", 90f, 1.5, 1.0, 0); // ttl 0 = clear
        assertFalse(a.hasLiveRequest());
    }

    @Test
    void latestRequestWins() {
        ManualLookAssist a = new ManualLookAssist();
        a.setYawGain("a", 90f, 1.5, 1.0, 10);
        a.setYawGain("b", 90f, 0.5, 1.0, 10);
        assertEquals(0.1, a.scaleYawDelta(0f, 0.2, DT), EPS);
        a.setYawGain(new String("a"), 90f, 1.25, 1.0, 10); // same id, different instance: replaces a's request
        assertEquals(0.25, a.scaleYawDelta(0f, 0.2, DT), EPS);
        a.clearYawGain("a");
        assertEquals(0.1, a.scaleYawDelta(0f, 0.2, DT), EPS); // b is the latest remaining
        // b lapsing earlier than a would hand back to a; here only b is left
        a.clearYawGain("b");
        assertFalse(a.hasLiveRequest());
    }

    @Test
    void tableOverflowReplacesTheOldest() {
        ManualLookAssist a = new ManualLookAssist();
        for (int i = 0; i < ManualLookAssist.CAPACITY + 3; i++) {
            a.setYawGain("m" + i, 90f, 1.5, 1.0, 10);
        }
        assertTrue(a.hasLiveRequest());
        a.setYawGain("last", 90f, 0.5, 1.0, 10);
        assertEquals(0.1, a.scaleYawDelta(0f, 0.2, DT), EPS);
    }

    @Test
    void clearAndClearAll() {
        ManualLookAssist a = new ManualLookAssist();
        a.setYawGain("a", 90f, 1.5, 1.0, 10);
        a.setYawGain("b", 90f, 1.5, 1.0, 10);
        a.clearYawGain("missing");
        a.clearYawGain(null);
        assertTrue(a.hasLiveRequest());
        a.onFrameYaw(0f, 0.2, DT, false);
        a.clearAll();
        assertFalse(a.hasLiveRequest());
        assertEquals(0.2, a.onFrameYaw(0f, 0.2, DT, false), 0.0);
        a.setYawGain(null, 90f, 1.5, 1.0, 10);
        a.setYawGain("x", Float.NaN, 1.5, 1.0, 10);
        assertFalse(a.hasLiveRequest());
    }

    @Test
    void stopAllAndUnloadClearThroughTheSmoother() {
        InputSmoother s = new InputSmoother(new FakeInputBackend(), new java.util.Random(1));
        ManualLookAssist a = new ManualLookAssist();
        a.setSmoother(s);
        s.setManualLookAssist(a);
        a.setYawGain("m", 90f, 1.5, 1.0, 100);
        assertEquals(0.3, a.onFrameYaw(0f, 0.2, DT, false), EPS);
        s.releaseAll(); // what the arbiter's stopAll and resetForWorldUnload call
        assertFalse(a.hasLiveRequest());
    }

    @Test
    void blockedFramesPassRawButStillCount() {
        ManualLookAssist a = new ManualLookAssist();
        a.setYawGain("m", 90f, 1.5, 1.0, 10);
        assertEquals(0.2, a.onFrameYaw(0f, 0.2, DT, true), 0.0);
        a.beginTick();
        assertEquals(0.2, a.manualYawRateDegPerTick(), EPS);
        assertEquals(0.0, a.assistedYawDegLastTick(), 0.0);
    }

    @Test
    void perTickReadOuts() {
        ManualLookAssist a = new ManualLookAssist();
        // without a request: the user's motion is still reported
        a.onFrameYaw(0f, 0.4, DT, false);
        a.onFrameYaw(0f, -0.1, DT, false);
        assertEquals(0.0, a.manualYawRateDegPerTick(), 0.0); // nothing completed yet
        a.beginTick();
        assertEquals(0.3, a.manualYawRateDegPerTick(), EPS);
        assertEquals(0.0, a.assistedYawDegLastTick(), 0.0);
        // with a request: after-gain motion and the absolute degrees the gain added or removed
        a.setYawGain("m", 90f, 1.5, 0.5, 10);
        a.onFrameYaw(0f, 0.2, DT, false);  // -> 0.3, +0.1
        a.onFrameYaw(0f, -0.2, DT, false); // -> -0.1, 0.1 removed
        a.beginTick();
        assertEquals(0.2, a.manualYawRateDegPerTick(), EPS);
        assertEquals(0.2, a.assistedYawDegLastTick(), EPS);
        a.beginTick(); // an idle tick reports a still mouse
        assertEquals(0.0, a.manualYawRateDegPerTick(), 0.0);
        assertEquals(0.0, a.assistedYawDegLastTick(), 0.0);
    }

    @Test
    void automatedLookBlocksTheGain() {
        FakeInputBackend backend = new FakeInputBackend();
        InputSmoother s = new InputSmoother(backend, new java.util.Random(1));
        com.utilitysuite.core.arbiter.ActionArbiter arbiter = new com.utilitysuite.core.arbiter.ActionArbiter();
        arbiter.bind(s, null, null);
        ManualLookAssist a = new ManualLookAssist();
        a.setSmoother(s);
        s.setManualLookAssist(a);
        a.setYawGain("m", 90f, 1.5, 1.0, 10);
        assertFalse(s.isAutomatedLookRunning());
        assertEquals(0.3, a.onFrameYaw(0f, 0.2, DT, false), EPS);

        var lease = arbiter.request(com.utilitysuite.core.api.LeaseRequest.of("test",
                com.utilitysuite.core.api.ControlPriority.GOAL, null,
                com.utilitysuite.core.api.ControlChannel.CAMERA)).orElseThrow();
        assertTrue(s.setRotationTarget(lease, com.utilitysuite.core.api.RotationTarget.visible(60f, 0f)));
        s.onFrame(1 / 60.0);
        assertTrue(s.isAutomatedLookRunning());
        assertEquals(0.2, a.onFrameYaw(backend.yaw, 0.2, DT, false), 0.0, "no gain while an automated turn runs");
        s.releaseAll();
        SilentRotation.clear();
    }
}
