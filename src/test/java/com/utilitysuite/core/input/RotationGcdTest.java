package com.utilitysuite.core.input;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.RotationTarget;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RotationGcdTest {

    private static final class StubLease implements ControlLease {
        private final Set<ControlChannel> channels = EnumSet.allOf(ControlChannel.class);
        boolean active = true;

        @Override public long id() { return 1L; }
        @Override public String moduleId() { return "gcd-test"; }
        @Override public ControlPriority priority() { return ControlPriority.TRAVEL; }
        @Override public Set<ControlChannel> channels() { return channels; }
        @Override public boolean covers(ControlChannel channel) { return active && channels.contains(channel); }
        @Override public boolean isActive() { return active; }
        @Override public void close() { active = false; }
    }

    @Test
    void stepDegMatchesVanillaFormulaAndDisablesOnNaN() {
        assertEquals(0.0, RotationGcd.stepDeg(Double.NaN));
        assertEquals(0.0, RotationGcd.stepDeg(Double.POSITIVE_INFINITY));
        // Vanilla default sensitivity = 0.5 -> (0.5 * 0.6f + 0.2f)^3 * 8 * 0.15 ~= 0.15 deg
        double step = RotationGcd.stepDeg(0.5);
        assertEquals(0.15, step, 1.0e-5);
        assertTrue(RotationGcd.stepDeg(0.0) > 0.0);
        assertTrue(RotationGcd.stepDeg(1.0) > step);
    }

    @Test
    void quantizeTargetRespectsGridMaxStepAndPitchClamp() {
        double step = RotationGcd.stepDeg(0.5);
        float[] out = new float[2];
        double maxStep = 2.0;
        RotationGcd.quantizeTarget(10.0f, -89.8f, 40.0f, -95.0f, step, maxStep, out);
        double dy = RotationCurve.wrapDegrees((double) out[0] - 10.0);
        assertEquals(0.0, dy - Math.round(dy / step) * step, 1.0e-4);
        assertEquals(-90.0f, out[1], 1.0e-6f);
        assertTrue(RotationLimits.distance(10.0f, -89.8f, out[0], out[1]) <= maxStep + 0.3);
    }

    @Test
    void quantizeDeltaConservesSlowAccumulation() {
        double step = RotationGcd.stepDeg(0.5);
        double rem = 0.0;
        double emitted = 0.0;
        double inc = step * 0.25;
        for (int i = 0; i < 40; i++) {
            double q = RotationGcd.quantizeDelta(inc, rem, step);
            rem = RotationGcd.remainder(inc, rem, q);
            double multiples = Math.round(q / step);
            assertEquals(multiples * step, q, 1.0e-9);
            emitted += q;
        }
        assertEquals(40 * inc, emitted + rem, 1.0e-9);
        assertEquals(10.0 * step, emitted, 1.0e-6);
    }

    @Test
    void visibleAndSilentTurnsEmitExactGcdSteps() {
        FakeInputBackend backend = new FakeInputBackend();
        backend.sensitivity = 0.5;
        double step = RotationGcd.stepDeg(0.5);
        InputSmoother smoother = new InputSmoother(backend, new SplittableRandom(7));
        StubLease lease = new StubLease();

        assertTrue(smoother.setRotationTarget(lease, RotationTarget.visible(45f, -15f)));
        for (int i = 0; i < 60; i++) {
            float prevYaw = backend.yaw;
            float prevPitch = backend.pitch;
            smoother.onFrame(0.016);
            assertGridAligned(RotationCurve.wrapDegrees((double) backend.yaw - prevYaw), step);
            assertGridAligned((double) backend.pitch - prevPitch, step);
        }
        assertEquals(45.0, backend.yaw, step);
        assertEquals(-15.0, backend.pitch, step);

        // SILENT path on postTick also quantizes every tick delta
        try {
            assertTrue(smoother.setRotationTarget(lease, RotationTarget.silent(-60f, 10f)));
            for (int t = 0; t < 20; t++) {
                float prevYaw = SilentRotation.yaw();
                float prevPitch = SilentRotation.pitch();
                smoother.preTick();
                smoother.postTick();
                if (SilentRotation.isActive()) {
                    assertGridAligned(RotationCurve.wrapDegrees((double) SilentRotation.yaw() - prevYaw), step);
                    assertGridAligned((double) SilentRotation.pitch() - prevPitch, step);
                }
            }
        } finally {
            smoother.releaseAll();
        }
    }

    @Test
    void releasedMomentumMovesInGcdSteps() {
        FakeInputBackend backend = new FakeInputBackend();
        backend.sensitivity = 0.4;
        double step = RotationGcd.stepDeg(backend.sensitivity);
        InputSmoother smoother = new InputSmoother(backend, new SplittableRandom(11));
        StubLease lease = new StubLease();

        assertTrue(smoother.setRotationTarget(lease, RotationTarget.visible(120f, 0f)));
        for (int i = 0; i < 6; i++) {
            smoother.onFrame(0.016);
        }
        smoother.setManualLookPreempting(true);
        try {
            lease.close();
            smoother.releaseFor(lease);
        } finally {
            smoother.setManualLookPreempting(false);
        }
        int movedFrames = 0;
        for (int i = 0; i < 30; i++) {
            float prevYaw = backend.yaw;
            float prevPitch = backend.pitch;
            smoother.onFrame(0.016);
            double dy = RotationCurve.wrapDegrees((double) backend.yaw - prevYaw);
            double dp = (double) backend.pitch - prevPitch;
            if (dy != 0.0 || dp != 0.0) {
                movedFrames++;
                assertGridAligned(dy, step);
                assertGridAligned(dp, step);
            }
        }
        assertTrue(movedFrames > 0);
    }

    private static void assertGridAligned(double delta, double step) {
        if (Math.abs(delta) < 1.0e-6) {
            return;
        }
        double n = Math.round(delta / step);
        assertEquals(n * step, delta, 2.0e-4, "delta " + delta + " is not a multiple of step " + step);
    }
}
