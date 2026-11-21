package com.utilitysuite.core.input;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.ManualInput;
import com.utilitysuite.core.api.RotationTarget;
import com.utilitysuite.core.arbiter.ActionArbiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MomentumReleaseTest {

    private static final double DT = 1 / 144.0;
    private static final double SLACK = 1.0e-6;

    @AfterEach
    void tearDown() {
        SilentRotation.clear();
    }

    @Test
    void decaysMonotonicallyToZeroWithinTheWindow() {
        MomentumRelease m = new MomentumRelease();
        m.start(300, -100);
        assertTrue(m.isActive());
        double prevStep = Double.MAX_VALUE;
        double prevSpeed = m.speed();
        double t = 0;
        while (m.isActive()) {
            assertTrue(m.advance(DT, 0, 0) || !m.isActive());
            double step = Math.hypot(m.yawStep(), m.pitchStep());
            assertTrue(step <= prevStep + SLACK, "step grows at " + t);
            assertTrue(m.speed() <= prevSpeed + SLACK, "speed grows at " + t);
            assertTrue(m.yawStep() >= 0 && m.pitchStep() <= 0, "keeps the turn's direction");
            prevStep = step;
            prevSpeed = m.speed();
            t += DT;
            assertTrue(t < 0.3, "release runs too long");
        }
        assertTrue(t >= 0.05, "release too short: " + t);
        assertEquals(0.0, m.speed());
        assertFalse(m.advance(DT, 0, 0));
    }

    @Test
    void neverExceedsTheCap() {
        MomentumRelease m = new MomentumRelease();
        m.start(5000, 5000); // a bogus velocity is capped
        assertTrue(m.speed() <= RotationLimits.MAX_DEG_PER_SEC + SLACK);
        for (double dt : new double[]{1 / 30.0, 1 / 60.0, 1 / 240.0, 0.1}) {
            m.start(450, 0);
            while (m.isActive()) {
                m.advance(dt, 0, 0);
                assertTrue(Math.hypot(m.yawStep(), m.pitchStep()) <= RotationLimits.MAX_DEG_PER_SEC * dt + SLACK);
            }
        }
    }

    @Test
    void cancelledByStopAndByOpposingMouse() {
        MomentumRelease m = new MomentumRelease();
        m.start(200, 50);
        m.cancel();
        assertFalse(m.isActive());
        assertFalse(m.advance(DT, 0, 0));
        assertEquals(0.0, m.yawStep());

        m.start(200, 50);
        m.advance(DT, -1, 0); // the user moves left against a rightward turn: yaw momentum gone at once
        assertEquals(0.0, m.yawStep());
        assertTrue(m.pitchStep() > 0);
        m.advance(DT, 1, 0);
        assertEquals(0.0, m.yawStep(), "zeroed axis does not come back");

        m.start(1.0, 0.0); // too slow to bother
        assertFalse(m.isActive());
        m.start(Double.NaN, 0.0);
        assertFalse(m.isActive());
    }

    // ---- through the smoother and the arbiter -----------------------------------------------------------------------

    private record Rig(FakeInputBackend backend, InputSmoother smoother, ActionArbiter arbiter, ControlLease lease) {
    }

    private static Rig turningRig() {
        FakeInputBackend backend = new FakeInputBackend();
        InputSmoother smoother = new InputSmoother(backend, new PrimitiveRandom(3L));
        ActionArbiter arbiter = new ActionArbiter();
        arbiter.bind(smoother, null, null);
        ControlLease lease = arbiter.request(LeaseRequest.of("test", ControlPriority.GOAL, null,
                ControlChannel.CAMERA)).orElseThrow();
        assertTrue(smoother.setRotationTarget(lease, RotationTarget.visible(170f, 0f)));
        for (int i = 0; i < 20; i++) {
            smoother.onFrame(DT);
        }
        assertTrue(smoother.isVisibleRotationMoving());
        return new Rig(backend, smoother, arbiter, lease);
    }

    private static void mousePreempt(Rig r) {
        r.smoother().setManualLookPreempting(true);
        r.arbiter().onManualInput(ManualInput.MOUSE_LOOK);
        r.smoother().setManualLookPreempting(false);
    }

    @Test
    void manualLookReleasesWithDecayingMomentum() {
        Rig r = turningRig();
        mousePreempt(r);
        assertFalse(r.lease().isActive());
        assertTrue(r.smoother().isMomentumActive());
        float prevYaw = r.backend().yaw;
        double prevStep = Double.MAX_VALUE;
        int frames = 0;
        while (r.smoother().isMomentumActive()) {
            r.smoother().onFrame(DT);
            double step = r.backend().yaw - prevYaw;
            assertTrue(step >= 0, "keeps turning the same way");
            assertTrue(step <= RotationLimits.MAX_DEG_PER_SEC * DT + 1e-3, "over the cap: " + step);
            assertTrue(step <= prevStep + 1e-4, "decays");
            prevStep = step;
            prevYaw = r.backend().yaw;
            assertTrue(++frames < 60, "release runs too long");
        }
        assertTrue(frames > 3);
    }

    @Test
    void stopAllAndPlainReleaseDoNotCarryMomentum() {
        Rig r = turningRig();
        r.arbiter().stopAll("test");
        assertFalse(r.smoother().isMomentumActive());

        Rig q = turningRig();
        q.lease().close(); // a module ending its own turn stops it as before
        assertFalse(q.smoother().isMomentumActive());

        Rig s = turningRig();
        mousePreempt(s);
        assertTrue(s.smoother().isMomentumActive());
        s.smoother().releaseAll(); // world unload
        assertFalse(s.smoother().isMomentumActive());

        Rig o = turningRig();
        o.smoother().setMomentumRelease(false);
        mousePreempt(o);
        assertFalse(o.smoother().isMomentumActive());
    }
}
