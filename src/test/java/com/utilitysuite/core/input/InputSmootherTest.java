package com.utilitysuite.core.input;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IContainerClicker;
import com.utilitysuite.core.api.IInputSmoother;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.RotationTarget;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.core.arbiter.ActionArbiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class InputSmootherTest {

    private FakeInputBackend backend;
    private InputSmoother smoother;
    private ActionArbiter arbiter;

    @BeforeEach
    void setUp() {
        backend = new FakeInputBackend();
        smoother = new InputSmoother(backend, new Random(42));
        arbiter = new ActionArbiter();
        arbiter.bind(smoother, null, null);
    }

    @AfterEach
    void tearDown() {
        smoother.releaseAll();
        SilentRotation.clear(); // release eases the static server angle back over ticks; reset it between tests
    }

    private ControlLease lease(ControlChannel first, ControlChannel... rest) {
        return arbiter.request(LeaseRequest.of("test", ControlPriority.GOAL, null, first, rest)).orElseThrow();
    }

    @Test
    void gaussianDelayIsClamped() {
        for (int i = 0; i < 10_000; i++) {
            double v = smoother.nextGaussianDelayMs(100, 80, 50, 150);
            assertTrue(v >= 50 && v <= 150, "out of range: " + v);
        }
        assertEquals(70.0, smoother.nextGaussianDelayMs(10, 0, 70, 90)); // no spread: clamped mean
        double swapped = smoother.nextGaussianDelayMs(100, 30, 150, 50); // min/max given the wrong way round
        assertTrue(swapped >= 50 && swapped <= 150);
        double nan = smoother.nextGaussianDelayMs(Double.NaN, 10, 20, 40);
        assertTrue(nan >= 20 && nan <= 40);
    }

    @Test
    void gaussianTicksAreClampedAndCentred() {
        long sum = 0;
        int n = 20_000;
        for (int i = 0; i < n; i++) {
            int t = smoother.nextGaussianTicks(10, 3, 4, 16);
            assertTrue(t >= 4 && t <= 16);
            sum += t;
        }
        assertEquals(10.0, sum / (double) n, 0.2);
    }

    @Test
    void callsValidateTheLease() {
        ControlLease move = lease(ControlChannel.MOVEMENT);
        assertFalse(smoother.setKeyHeld(move, SimKey.ATTACK, true), "ATTACK needs WORLD_INTERACT");
        assertFalse(smoother.selectHotbarSlot(move, 2), "needs HOTBAR");
        assertFalse(smoother.setRotationTarget(move, RotationTarget.visible(10, 0)), "needs CAMERA");
        assertFalse(smoother.queueClick(move, 5, 0, IContainerClicker.Click.PICKUP), "needs HOTBAR");
        assertTrue(smoother.setKeyHeld(move, SimKey.FORWARD, true));
        move.close();
        assertFalse(move.isActive());
        assertFalse(backend.isDown(SimKey.FORWARD), "released when the lease ended");
        assertFalse(smoother.setKeyHeld(move, SimKey.FORWARD, true), "inactive lease is refused");
        assertFalse(smoother.setKeyHeld(null, SimKey.FORWARD, true));
    }

    @Test
    void heldKeysAreReassertedAndReleasedToPhysicalState() {
        ControlLease l = lease(ControlChannel.MOVEMENT);
        smoother.setKeyHeld(l, SimKey.JUMP, true);
        smoother.setKeyHeld(l, SimKey.SNEAK, true);
        backend.down[SimKey.JUMP.ordinal()] = false; // vanilla cleared it (e.g. a hardware release event)
        smoother.preTick();
        assertTrue(backend.isDown(SimKey.JUMP), "re-asserted each tick");

        backend.physical[SimKey.SNEAK.ordinal()] = true; // the user is holding sneak themselves
        smoother.releaseFor(l);
        assertFalse(backend.isDown(SimKey.JUMP));
        assertTrue(backend.isDown(SimKey.SNEAK), "user's own key is not clobbered");
        assertFalse(smoother.isKeyHeldByAutomation(SimKey.SNEAK));
    }

    @Test
    void tapReleasesAfterItsDuration() {
        ControlLease l = lease(ControlChannel.WORLD_INTERACT);
        assertTrue(smoother.scheduleKeyTap(l, SimKey.ATTACK, 3, 3));
        assertTrue(backend.isDown(SimKey.ATTACK));
        assertEquals(1, backend.clicks[SimKey.ATTACK.ordinal()], "a tap registers one click");
        for (int i = 0; i < 3; i++) {
            smoother.preTick();
            assertTrue(backend.isDown(SimKey.ATTACK), "still down during vanilla tick " + (i + 1));
        }
        smoother.preTick();
        assertFalse(backend.isDown(SimKey.ATTACK));
        assertFalse(smoother.isKeyHeldByAutomation(SimKey.ATTACK));
    }

    @Test
    void containerClicksAreSpacedAndCancellable() {
        ControlLease l = lease(ControlChannel.HOTBAR);
        smoother.setClickTiming(100, 0, 100, 100); // exactly 2 ticks apart
        for (int i = 0; i < 3; i++) {
            assertTrue(smoother.queueClick(l, 10 + i, 0, IContainerClicker.Click.QUICK_MOVE));
        }
        assertEquals(3, smoother.pending());
        int[] executedAt = new int[3];
        int done = 0;
        for (int t = 1; t <= 10 && done < 3; t++) {
            smoother.preTick();
            smoother.postTick();
            if (backend.containerClicks.size() > done) {
                executedAt[done++] = t;
            }
        }
        assertEquals(3, done);
        assertEquals(2, executedAt[1] - executedAt[0]);
        assertEquals(2, executedAt[2] - executedAt[1]);
        assertEquals(10, backend.containerClicks.get(0)[0]);

        smoother.queueClick(l, 1, 0, IContainerClicker.Click.PICKUP);
        smoother.queueClick(l, 2, 0, IContainerClicker.Click.PICKUP);
        smoother.cancel(l);
        assertEquals(0, smoother.pending());
    }

    @Test
    void hotbarSelection() {
        ControlLease l = lease(ControlChannel.HOTBAR);
        assertTrue(smoother.selectHotbarSlot(l, 4));
        assertEquals(4, backend.selectedSlot);
        assertFalse(smoother.selectHotbarSlot(l, 9));
    }

    @Test
    void aForeignSnapIsUndoneAndBecomesASmoothedTurn() {
        ControlLease l = lease(ControlChannel.CAMERA);
        smoother.setCameraHolderLookup(() -> l);
        smoother.setForeignRotationGuard(true);
        backend.yaw = 0f;
        backend.pitch = 0f;
        smoother.preTick();
        backend.yaw = 180f; // a path library turns the player all the way round in one tick
        backend.pitch = 30f;
        smoother.captureForeignRotation();
        assertEquals(0f, backend.yaw, 1e-4, "the snap is undone before the packet");
        assertEquals(180f, smoother.foreignHeading(), 1e-4, "the keys are re-aimed along the wanted heading");
        assertFalse(smoother.isRotationSettled(1f), "a smoothed turn toward it runs instead");
        float last = backend.yaw;
        float worst = 0f;
        for (int i = 0; i < 2000 && smoother.isVisibleRotationMoving(); i++) {
            smoother.onFrame(1 / 60.0);
            worst = Math.max(worst, Math.abs(backend.yaw - last));
            last = backend.yaw;
        }
        assertTrue(worst <= IInputSmoother.MAX_TURN_DEG_PER_SEC / 60.0 + 1e-3, "capped per frame: " + worst);
        assertEquals(180f, Math.abs(backend.yaw), 1e-3);
    }

    @Test
    void theGuardIsOffByDefault() {
        backend.yaw = 0f;
        smoother.preTick();
        backend.yaw = 90f;
        smoother.captureForeignRotation();
        assertEquals(90f, backend.yaw, 1e-4);
        assertTrue(Float.isNaN(smoother.foreignHeading()));
    }

    @Test
    void visibleRotationWritesPlayerPerFrame() {
        ControlLease l = lease(ControlChannel.CAMERA);
        backend.yaw = 350f;
        assertTrue(smoother.setRotationTarget(l, RotationTarget.visible(20f, -10f)));
        assertFalse(smoother.isRotationSettled(1f));
        for (int i = 0; i < 1000 && smoother.isVisibleRotationMoving(); i++) {
            smoother.onFrame(1 / 60.0);
        }
        assertTrue(smoother.isRotationSettled(0.01f));
        assertEquals(380f, backend.yaw, 1e-3, "shortest arc: 350 -> 380 (= 20)");
        assertEquals(-10f, backend.pitch, 1e-3);
        assertFalse(SilentRotation.isActive());
    }

    @Test
    void silentRotationLeavesCameraAndDrivesPacketAngles() {
        ControlLease l = lease(ControlChannel.CAMERA);
        backend.yaw = 0f;
        backend.pitch = 0f;
        assertTrue(smoother.setRotationTarget(l, RotationTarget.silent(90f, 30f)));
        assertTrue(SilentRotation.isActive());
        for (int i = 0; i < 100; i++) {
            smoother.preTick();
            smoother.postTick();
            smoother.onFrame(0.05);
        }
        assertEquals(0, backend.rotationWrites, "rendered camera untouched");
        assertEquals(90f, SilentRotation.yaw(), 1e-3);
        assertEquals(30f, SilentRotation.pitch(), 1e-3);
        assertTrue(smoother.isRotationSettled(0.01f));
        l.close();
        assertTrue(SilentRotation.isActive(), "no server-side snap: the head eases back over a few ticks");
        for (int i = 0; i < 20 && SilentRotation.isActive(); i++) {
            smoother.postTick();
        }
        assertFalse(SilentRotation.isActive(), "packets go back to the real camera");
    }

    @Test
    void steadyStateTickAndFrameDoNotAllocate() {
        if (!AllocationProbe.supported()) {
            return;
        }
        ControlLease l = lease(ControlChannel.MOVEMENT, ControlChannel.CAMERA, ControlChannel.WORLD_INTERACT);
        smoother.setKeyHeld(l, SimKey.FORWARD, true);
        smoother.setKeyHeld(l, SimKey.ATTACK, true);
        smoother.setRotationTarget(l, RotationTarget.visible(170f, 20f));
        RotationTarget a = RotationTarget.visible(170f, 20f);
        RotationTarget b = RotationTarget.visible(0f, -20f);
        boolean[] toA = {false};
        long bytes = AllocationProbe.measure(200_000, () -> {
            smoother.preTick();
            smoother.postTick();
            smoother.onFrame(0.004);
            if (!smoother.isVisibleRotationMoving()) {
                toA[0] = !toA[0];
                smoother.setRotationTarget(l, toA[0] ? a : b); // restarts the curve: must not allocate either
            }
        });
        assertTrue(bytes < 2048, "smoother tick/frame allocated " + bytes + " bytes over 200k iterations");
    }
}
