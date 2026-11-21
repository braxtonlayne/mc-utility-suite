package com.utilitysuite.core.input;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.RotationMode;
import com.utilitysuite.core.api.RotationTarget;
import com.utilitysuite.core.arbiter.ActionArbiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hard no-head-snapping rule: no rotation path (visible camera per frame, server-visible SILENT angle per tick,
 * releasing a SILENT rotation) may move the look direction faster than {@link RotationLimits#MAX_DEG_PER_SEC}, whatever
 * the configured speed, speed multiplier, retargets or Stop-All.
 */
class RotationNoSnapTest {

    private static final double SLACK = 1.0e-3;

    private FakeInputBackend backend;
    private InputSmoother smoother;
    private ActionArbiter arbiter;

    @BeforeEach
    void setUp() {
        backend = new FakeInputBackend();
        smoother = new InputSmoother(backend, new Random(7));
        smoother.curve().setBaseSpeed(100_000); // a misconfigured / maxed setting must not matter
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

    @Test
    void visibleTurnsNeverExceedTheCapPerFrame() {
        ControlLease l = camera();
        Random r = new Random(1);
        double dt = 1.0 / 240.0;
        double maxStep = 0;
        for (int i = 0; i < 4000; i++) {
            if (i % 37 == 0) { // frequent retargets, small and large, at a huge speed multiplier
                float yaw = (float) (r.nextDouble() * 720 - 360);
                float pitch = (float) (r.nextDouble() * 180 - 90);
                smoother.setRotationTarget(l, new RotationTarget(yaw, pitch, RotationMode.VISIBLE, 50f));
            } else if (i % 11 == 0) {
                smoother.setRotationTarget(l, new RotationTarget(backend.yaw + 10f, backend.pitch * 0.5f,
                        RotationMode.VISIBLE, 50f));
            }
            float y0 = backend.yaw;
            float p0 = backend.pitch;
            smoother.onFrame(dt);
            maxStep = Math.max(maxStep, RotationLimits.distance(y0, p0, backend.yaw, backend.pitch));
        }
        assertTrue(maxStep <= RotationLimits.MAX_DEG_PER_SEC * dt + SLACK, "frame step " + maxStep);
        assertTrue(maxStep > RotationLimits.MAX_DEG_PER_SEC * dt * 0.9, "the cap is actually reached");
    }

    @Test
    void aCappedTurnStillArrives() {
        ControlLease l = camera();
        smoother.setRotationTarget(l, new RotationTarget(180f, 0f, RotationMode.VISIBLE, 20f));
        int frames = 0;
        while (!smoother.isRotationSettled(0.5f) && frames < 2000) {
            smoother.onFrame(1.0 / 60.0);
            frames++;
        }
        assertTrue(RotationLimits.distance(backend.yaw, backend.pitch, 180f, 0f) < 0.5, "yaw " + backend.yaw);
        // a 180 at 450 deg/s takes at least 0.4 s (24 frames at 60 fps)
        assertTrue(frames >= 24, "took " + frames + " frames");
    }

    @Test
    void silentAnglesNeverExceedTheCapPerTickIncludingRelease() {
        ControlLease l = camera();
        backend.yaw = 0f;
        backend.pitch = 0f;
        smoother.setRotationTarget(l, new RotationTarget(170f, 60f, RotationMode.SILENT, 50f));
        float y = SilentRotation.yaw();
        float p = SilentRotation.pitch();
        assertEquals(0f, y, 1e-3, "starts at the angle the server already sees");
        double maxStep = 0;
        for (int t = 0; t < 10; t++) {
            smoother.postTick();
            maxStep = Math.max(maxStep, RotationLimits.distance(y, p, SilentRotation.yaw(), SilentRotation.pitch()));
            y = SilentRotation.yaw();
            p = SilentRotation.pitch();
        }
        // a retarget the opposite way, then a hard release (Stop-All) mid-turn
        smoother.setRotationTarget(l, new RotationTarget(-120f, -40f, RotationMode.SILENT, 50f));
        for (int t = 0; t < 3; t++) {
            smoother.postTick();
            maxStep = Math.max(maxStep, RotationLimits.distance(y, p, SilentRotation.yaw(), SilentRotation.pitch()));
            y = SilentRotation.yaw();
            p = SilentRotation.pitch();
        }
        smoother.releaseAll();
        assertTrue(SilentRotation.isActive(), "release eases back, it does not jump to the camera");
        int ticks = 0;
        while (SilentRotation.isActive() && ticks < 100) {
            smoother.postTick();
            if (SilentRotation.isActive()) {
                maxStep = Math.max(maxStep,
                        RotationLimits.distance(y, p, SilentRotation.yaw(), SilentRotation.pitch()));
                y = SilentRotation.yaw();
                p = SilentRotation.pitch();
            } else {
                // the packet after clearing carries the camera angle: that last step is capped too
                maxStep = Math.max(maxStep, RotationLimits.distance(y, p, backend.yaw, backend.pitch));
            }
            ticks++;
        }
        assertFalse(SilentRotation.isActive(), "the server angle got back to the camera");
        assertTrue(ticks > 1, "took " + ticks + " ticks");
        assertTrue(maxStep <= RotationLimits.MAX_DEG_PER_TICK + SLACK, "tick step " + maxStep);
    }

    @Test
    void switchingFromSilentToVisibleEasesTheServerAngle() {
        ControlLease l = camera();
        smoother.setRotationTarget(l, new RotationTarget(150f, 0f, RotationMode.SILENT, 1f));
        for (int t = 0; t < 40; t++) {
            smoother.postTick();
        }
        assertTrue(Math.abs(SilentRotation.yaw() - 150f) < 1f);
        smoother.setRotationTarget(l, new RotationTarget(10f, 0f, RotationMode.VISIBLE, 1f));
        assertTrue(SilentRotation.isActive(), "no jump from 150 back to the camera");
        float before = SilentRotation.yaw();
        smoother.postTick();
        assertTrue(Math.abs(before - SilentRotation.yaw()) <= RotationLimits.MAX_DEG_PER_TICK + SLACK);
    }

    @Test
    void settingsCannotRaiseTheCap() {
        RotationCurve c = new RotationCurve(new Random(3));
        c.setBaseSpeed(5000);
        assertEquals(RotationLimits.MAX_DEG_PER_SEC, c.baseSpeed(), 1e-9);
    }
}
