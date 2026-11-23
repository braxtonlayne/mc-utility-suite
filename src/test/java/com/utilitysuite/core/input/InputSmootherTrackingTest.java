package com.utilitysuite.core.input;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.RotationMode;
import com.utilitysuite.core.api.RotationTarget;
import com.utilitysuite.core.arbiter.ActionArbiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tracking rotation targets through the smoother: a tracking stream drives the camera with the velocity-continuous
 * {@link RotationTracker}, a far fresh target is approached with the curve first and handed over without a velocity
 * jump, every frame still passes the no-snap limiter, and non-tracking targets keep the curve.
 */
class InputSmootherTrackingTest {

    private static final double FRAME = 1.0 / 60.0;

    private FakeInputBackend backend;
    private InputSmoother smoother;
    private ActionArbiter arbiter;

    @BeforeEach
    void setUp() {
        backend = new FakeInputBackend();
        smoother = new InputSmoother(backend, new Random(42));
        smoother.curve().setBaseSpeed(240);
        arbiter = new ActionArbiter();
        arbiter.bind(smoother, null, null);
    }

    @AfterEach
    void tearDown() {
        smoother.releaseAll();
        SilentRotation.clear();
    }

    private ControlLease camera() {
        return arbiter.request(LeaseRequest.of("test", ControlPriority.GOAL, null, ControlChannel.CAMERA))
                .orElseThrow();
    }

    /** One client tick: preTick, the tracking sample (as a module sends it in ClientTickEvent.Post), three frames. */
    private double[] tickFrames(ControlLease l, double targetYaw, double targetPitch, double[] prevVel) {
        smoother.preTick();
        smoother.setRotationTarget(l, RotationTarget.tracking((float) targetYaw, (float) targetPitch, 1f));
        smoother.postTick();
        double maxStep = 0;
        double maxDv = 0;
        for (int f = 0; f < 3; f++) {
            float y0 = backend.yaw;
            float p0 = backend.pitch;
            smoother.onFrame(FRAME);
            maxStep = Math.max(maxStep, RotationLimits.distance(y0, p0, backend.yaw, backend.pitch));
            double vy = (backend.yaw - y0) / FRAME;
            double vp = (backend.pitch - p0) / FRAME;
            maxDv = Math.max(maxDv, Math.hypot(vy - prevVel[0], vp - prevVel[1]));
            prevVel[0] = vy;
            prevVel[1] = vp;
        }
        return new double[] {maxStep, maxDv};
    }

    @Test
    void trackingStreamFollowsAMovingTargetContinuously() {
        ControlLease l = camera();
        backend.yaw = 30f;
        backend.pitch = 0f;
        double[] vel = {0, 0};
        double maxErrAfterLock = 0;
        double maxStep = 0;
        double maxDv = 0;
        for (int tick = 0; tick < 60; tick++) {
            double t = tick * 0.05;
            double[] r = tickFrames(l, 30 + 120 * t, 0, vel);
            maxStep = Math.max(maxStep, r[0]);
            if (tick > 0) {
                maxDv = Math.max(maxDv, r[1]);
            }
            assertTrue(smoother.isTracking(), "tracker drives the camera");
            assertTrue(smoother.isVisibleRotationMoving());
            double now = 30 + 120 * (t + 0.05);
            if (t + 0.05 >= 0.25) {
                maxErrAfterLock = Math.max(maxErrAfterLock, Math.abs(RotationCurve.wrapDegrees(now - backend.yaw)));
            }
        }
        assertTrue(maxErrAfterLock < 2.0, "error after lock " + maxErrAfterLock);
        assertTrue(maxStep <= RotationLimits.MAX_DEG_PER_SEC * FRAME + 1e-3, "frame step " + maxStep);
        double accelPerFrame = smoother.tracker().maxAccel() * FRAME;
        assertTrue(maxDv <= accelPerFrame + 1.0, "velocity change per frame " + maxDv + " > " + accelPerFrame);
    }

    @Test
    void farTargetIsApproachedWithTheCurveThenTrackedWithoutAVelocityJump() {
        ControlLease l = camera();
        backend.yaw = 0f;
        backend.pitch = 0f;
        double[] vel = {0, 0};
        double base = 90; // 90 degrees away, moving at 60 deg/s
        smoother.preTick();
        smoother.setRotationTarget(l, RotationTarget.tracking((float) base, 0f, 1f));
        assertFalse(smoother.isTracking(), "a far fresh target starts with the approach curve");
        boolean handedOver = false;
        double dvAtHandover = 0;
        double maxStep = 0;
        for (int tick = 0; tick < 40; tick++) {
            if (tick > 0) {
                smoother.preTick();
                smoother.setRotationTarget(l, RotationTarget.tracking((float) (base + 60 * tick * 0.05), 0f, 1f));
            }
            for (int f = 0; f < 3; f++) {
                boolean before = smoother.isTracking();
                float y0 = backend.yaw;
                float p0 = backend.pitch;
                smoother.onFrame(FRAME);
                double vy = (backend.yaw - y0) / FRAME;
                double vp = (backend.pitch - p0) / FRAME;
                maxStep = Math.max(maxStep, RotationLimits.distance(y0, p0, backend.yaw, backend.pitch));
                if (!before && smoother.isTracking()) {
                    handedOver = true;
                    dvAtHandover = Math.hypot(vy - vel[0], vp - vel[1]);
                }
                vel[0] = vy;
                vel[1] = vp;
            }
        }
        assertTrue(handedOver, "the tracker took over");
        assertTrue(dvAtHandover <= smoother.tracker().maxAccel() * FRAME + 1.0,
                "velocity jump at handover " + dvAtHandover);
        assertTrue(maxStep <= RotationLimits.MAX_DEG_PER_SEC * FRAME + 1e-3, "frame step " + maxStep);
        double target = base + 60 * 39 * 0.05 + 60 * FRAME * 3;
        assertEquals(target, backend.yaw, 2.0, "locked on at the end");
    }

    @Test
    void aPlainTargetAfterTrackingUsesTheCurveAgain() {
        ControlLease l = camera();
        double[] vel = {0, 0};
        for (int tick = 0; tick < 10; tick++) {
            tickFrames(l, 10 * tick, 0, vel);
        }
        assertTrue(smoother.isTracking());
        smoother.setRotationTarget(l, RotationTarget.visible(backend.yaw + 20f, 0f));
        assertFalse(smoother.isTracking(), "a non-tracking target is a curve again");
        for (int i = 0; i < 120; i++) {
            smoother.onFrame(FRAME);
        }
        assertTrue(smoother.isRotationSettled(0.01f));
        assertFalse(smoother.isVisibleRotationMoving(), "a finished plain turn stops");
    }

    @Test
    void silentTargetsIgnoreTheTrackingFlag() {
        ControlLease l = camera();
        assertTrue(smoother.setRotationTarget(l, new RotationTarget(40f, 0f, RotationMode.SILENT, 1f, true)));
        assertFalse(smoother.isTracking());
        assertTrue(smoother.isSilentRotationActive());
    }

    @Test
    void trackingEndsWithTheLease() {
        ControlLease l = camera();
        double[] vel = {0, 0};
        tickFrames(l, 5, 0, vel);
        assertTrue(smoother.isTracking());
        smoother.clearRotation(l);
        assertFalse(smoother.isTracking());
        assertFalse(smoother.isVisibleRotationMoving());
        float y = backend.yaw;
        smoother.onFrame(FRAME);
        assertEquals(y, backend.yaw, "nothing moves the camera after the clear");
    }

    @Test
    void trackingFramesAndSamplesAllocateNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        ControlLease l = camera();
        RotationTarget[] samples = new RotationTarget[64];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = RotationTarget.tracking((float) (40 * Math.sin(i * 0.3)), (float) (10 * Math.cos(i * 0.2)), 1f);
        }
        smoother.setRotationTarget(l, samples[0]);
        int[] k = {0};
        long bytes = AllocationProbe.measure(500_000, () -> {
            int i = k[0]++;
            if ((i & 3) == 0) {
                smoother.preTick();
                smoother.setRotationTarget(l, samples[(i >> 2) & 63]);
                smoother.postTick();
            }
            smoother.onFrame((i & 1) == 0 ? 0.004 : 0.016);
        });
        assertTrue(bytes < 1_024, "tracking path allocated " + bytes + " bytes");
        assertTrue(smoother.isTracking());
    }

    @Test
    void trackerMathAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        RotationTracker t = new RotationTracker(new Random(1));
        t.setMaxSpeed(240);
        t.reset(0f, 0f);
        float[] cam = {0f, 0f};
        int[] k = {0};
        long bytes = AllocationProbe.measure(1_000_000, () -> {
            int i = k[0]++;
            if ((i & 3) == 0) {
                t.sample((float) ((i & 1023) * 0.3), (float) ((i & 63) - 32), 0.05);
            }
            t.advance(1.0 / 144.0, cam[0], cam[1]);
            cam[0] = t.yaw();
            cam[1] = t.pitch();
        });
        assertTrue(bytes < 1_024, "tracker allocated " + bytes + " bytes");
    }
}
