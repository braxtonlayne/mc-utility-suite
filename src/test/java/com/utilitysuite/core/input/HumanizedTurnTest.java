package com.utilitysuite.core.input;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.RotationTarget;
import com.utilitysuite.core.arbiter.ActionArbiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The smoother with humanization on: Fitts timing for ordinary turns, never faster than the cap, no allocation. */
class HumanizedTurnTest {

    private static final double DT = 1 / 120.0;

    @AfterEach
    void tearDown() {
        SilentRotation.clear();
    }

    private FakeInputBackend backend;
    private InputSmoother smoother;
    private ActionArbiter arbiter;
    private Humanizer humanizer;

    private void setUp(double baseSpeed) {
        backend = new FakeInputBackend();
        PrimitiveRandom random = new PrimitiveRandom(17L);
        humanizer = new Humanizer(random);
        smoother = new InputSmoother(backend, random);
        smoother.setHumanizer(humanizer);
        smoother.curve().setBaseSpeed(baseSpeed);
        arbiter = new ActionArbiter();
        arbiter.bind(smoother, null, null);
    }

    private ControlLease lease(ControlPriority p) {
        return arbiter.request(LeaseRequest.of("test", p, null, ControlChannel.CAMERA)).orElseThrow();
    }

    /** Frames until settled, asserting the per-frame no-snap cap on the way. */
    private int turn(ControlLease l, RotationTarget target) {
        assertTrue(smoother.setRotationTarget(l, target));
        int frames = 0;
        while (smoother.isVisibleRotationMoving() && frames < 2000) {
            float y = backend.yaw;
            float p = backend.pitch;
            smoother.onFrame(DT);
            double step = RotationLimits.distance(y, p, backend.yaw, backend.pitch);
            assertTrue(step <= RotationLimits.MAX_DEG_PER_SEC * DT + 1e-3, "snap: " + step + " deg in one frame");
            frames++;
        }
        return frames;
    }

    @Test
    void noCurveStepExceedsTheCapWithHumanization() {
        for (double skill : new double[]{0.0, 0.75, 1.0}) {
            setUp(100_000); // maxed speed setting
            humanizer.setSkillOverride(skill);
            ControlLease l = lease(ControlPriority.GOAL);
            float yaw = 0;
            for (int i = 0; i < 40; i++) {
                yaw += (float) ((humanizer.nextUnit() * 2 - 1) * 180);
                turn(l, RotationTarget.visible(yaw, (float) ((humanizer.nextUnit() * 2 - 1) * 80)));
            }
            ControlLease e = lease(ControlPriority.EMERGENCY);
            turn(e, RotationTarget.visible(yaw + 179, -60));
        }
    }

    @Test
    void smallTurnsAreSlowerWithHumanizationAndEmergencyIsNot() {
        setUp(240);
        humanizer.setSkillOverride(0.75);
        ControlLease l = lease(ControlPriority.GOAL);
        int human = turn(l, RotationTarget.visible(10f, 0f));
        humanizer.setEnabled(false);
        int plain = turn(l, RotationTarget.visible(0f, 0f));
        assertTrue(human > plain, "humanized small turn " + human + " frames vs plain " + plain);

        humanizer.setEnabled(true);
        l.close();
        ControlLease e = lease(ControlPriority.EMERGENCY);
        int emergency = turn(e, RotationTarget.visible(10f, 0f));
        assertTrue(emergency < human, "emergency turn is not humanized: " + emergency + " vs " + human);

        // an explicit speed request keeps the base-speed timing
        e.close();
        ControlLease g = lease(ControlPriority.GOAL);
        int fast = turn(g, new RotationTarget(10f, 0f, com.utilitysuite.core.api.RotationMode.VISIBLE, 1.01f));
        assertTrue(fast <= plain + 2, "explicit speed: " + fast + " vs plain " + plain);
    }

    private static double sink;

    @Test
    void smootherTickAndFrameAllocateNothingWithHumanization() {
        if (!AllocationProbe.supported()) {
            return;
        }
        setUp(240);
        ControlLease l = lease(ControlPriority.GOAL);
        RotationTarget a = RotationTarget.visible(90f, 10f);
        RotationTarget b = RotationTarget.visible(-90f, -10f);
        int[] n = new int[1];
        long bytes = AllocationProbe.measure(50_000, () -> {
            n[0]++;
            if ((n[0] & 63) == 0) {
                smoother.setRotationTarget(l, (n[0] & 64) == 0 ? a : b);
            }
            smoother.preTick();
            smoother.onFrame(DT);
            smoother.postTick();
            sink += smoother.nextGaussianDelayMs(100, 20, 50, 150) + smoother.nextGaussianTicks(4, 1, 1, 8);
        });
        assertTrue(bytes < 1024, "allocated " + bytes + " bytes");
    }

    @Test
    void momentumFramesAllocateNothing() {
        if (!AllocationProbe.supported()) {
            return;
        }
        MomentumRelease m = new MomentumRelease();
        long bytes = AllocationProbe.measure(100_000, () -> {
            if (!m.isActive()) {
                m.start(300, 40);
            }
            m.advance(DT, 0, 0);
            sink += m.yawStep();
        });
        assertTrue(bytes < 1024, "allocated " + bytes + " bytes");
    }
}
