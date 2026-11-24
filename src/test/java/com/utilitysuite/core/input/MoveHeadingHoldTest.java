package com.utilitysuite.core.input;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IInputSmoother;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.ManualInput;
import com.utilitysuite.core.arbiter.ActionArbiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link IInputSmoother#holdMoveHeading}: the core re-aims this tick's movement keys for a lease that has the camera
 * turned away, only inside the movement-input event and only while the lease lives; and the foreign rotation guard's
 * re-aim when the event opens.
 */
class MoveHeadingHoldTest {

    private FakeInputBackend backend;
    private InputSmoother smoother;
    private ActionArbiter arbiter;

    @BeforeEach
    void setUp() {
        backend = new FakeInputBackend();
        smoother = new InputSmoother(backend, new Random(7));
        arbiter = new ActionArbiter();
        arbiter.bind(smoother, null, null);
        arbiter.setManualOverridePauseTicks(0);
    }

    @AfterEach
    void tearDown() {
        smoother.releaseAll();
        SilentRotation.clear();
    }

    private ControlLease lease(ControlPriority priority, ControlChannel first, ControlChannel... rest) {
        return arbiter.request(LeaseRequest.of("placer", priority, null, first, rest)).orElseThrow();
    }

    /** The user's keys of this tick, as vanilla computed them. */
    private void keys(float forward, float left) {
        backend.move.forwardImpulse = forward;
        backend.move.leftImpulse = left;
        backend.move.up = forward > 0;
        backend.move.down = forward < 0;
        backend.move.left = left > 0;
        backend.move.right = left < 0;
    }

    /** A new tick up to the module listeners of the movement-input event. */
    private void openTick(float forward, float left) {
        smoother.preTick();
        keys(forward, left);
        smoother.beginMoveInput();
    }

    /** The rotation the modules did themselves before the core owned it (ParkourBlocks.rotateImpulses). */
    private static float[] reference(float f, float s, float cameraYaw, float heading) {
        float w = (cameraYaw - heading) % 360f;
        if (w >= 180f) {
            w -= 360f;
        }
        if (w < -180f) {
            w += 360f;
        }
        double d = Math.toRadians(w);
        double cos = Math.cos(d);
        double sin = Math.sin(d);
        return new float[]{(float) (f * cos - s * sin), (float) (s * cos + f * sin)};
    }

    @Test
    void forwardStaysAlongTheHeadingWhileTheCameraLooksAside() {
        ControlLease l = lease(ControlPriority.TRAVEL, ControlChannel.CAMERA, ControlChannel.HOTBAR);
        backend.yaw = 90f; // the placement turned the head a quarter turn from the jump heading
        openTick(1f, 0f);
        assertTrue(smoother.holdMoveHeading(l, 0f));
        assertEquals(0f, backend.move.forwardImpulse, 1e-6f);
        assertEquals(1f, backend.move.leftImpulse, 1e-6f);
        assertFalse(backend.move.up);
        assertFalse(backend.move.down);
        assertTrue(backend.move.left);
        assertFalse(backend.move.right);
    }

    @Test
    void sameNumbersAsTheModulesOwnRotation() {
        ControlLease l = lease(ControlPriority.TRAVEL, ControlChannel.CAMERA);
        float[][] cases = {{1f, 0f, 37.5f, -12f}, {1f, -1f, -170f, 175f}, {0.3f, 0.3f, 725f, 10f}, {-1f, 1f, 0f, 0f},
                {0f, 0f, 90f, 0f}, {1f, 0f, 190f, 0f}};
        for (float[] c : cases) {
            backend.yaw = c[2];
            openTick(c[0], c[1]);
            assertTrue(smoother.holdMoveHeading(l, c[3]));
            float[] want = reference(c[0], c[1], c[2], c[3]);
            assertEquals(want[0], backend.move.forwardImpulse, 0f);
            assertEquals(want[1], backend.move.leftImpulse, 0f);
            assertEquals(want[0] > 0.05f, backend.move.up);
            assertEquals(want[0] < -0.05f, backend.move.down);
            assertEquals(want[1] > 0.05f, backend.move.left);
            assertEquals(want[1] < -0.05f, backend.move.right);
            smoother.endMoveInput();
        }
    }

    @Test
    void onlyInsideTheMovementInputEventOfThisTick() {
        ControlLease l = lease(ControlPriority.TRAVEL, ControlChannel.CAMERA);
        backend.yaw = 90f;
        smoother.preTick();
        keys(1f, 0f);
        assertFalse(smoother.holdMoveHeading(l, 0f), "before the core opened the input");
        smoother.beginMoveInput();
        assertTrue(smoother.isMoveInputOpen());
        assertTrue(smoother.holdMoveHeading(l, 0f));
        smoother.endMoveInput();
        assertFalse(smoother.isMoveInputOpen());
        int writes = backend.moveWrites;
        assertFalse(smoother.holdMoveHeading(l, 0f), "after the shared-control writes");
        assertEquals(writes, backend.moveWrites);

        // an event that never closed (no LOW listener ran) does not leak into the next tick
        smoother.beginMoveInput();
        smoother.preTick();
        assertFalse(smoother.isMoveInputOpen());
        assertFalse(smoother.holdMoveHeading(l, 0f));
        assertEquals(writes, backend.moveWrites);
    }

    @Test
    void oneTickOnly_aModuleThatStopsCallingLeavesTheKeysAlone() {
        ControlLease l = lease(ControlPriority.TRAVEL, ControlChannel.CAMERA);
        backend.yaw = 90f;
        openTick(1f, 0f);
        assertTrue(smoother.holdMoveHeading(l, 0f));
        smoother.endMoveInput();
        openTick(1f, 0f); // next tick, nobody asks
        smoother.endMoveInput();
        assertEquals(1f, backend.move.forwardImpulse);
        assertEquals(0f, backend.move.leftImpulse);
        assertTrue(backend.move.up);
    }

    @Test
    void needsALiveLeaseOnCamera() {
        backend.yaw = 90f;
        openTick(1f, 0f);
        assertFalse(smoother.holdMoveHeading(null, 0f));
        ControlLease keysOnly = lease(ControlPriority.TRAVEL, ControlChannel.MOVEMENT, ControlChannel.HOTBAR);
        assertFalse(smoother.holdMoveHeading(keysOnly, 0f), "no CAMERA on the lease");
        ControlLease cam = lease(ControlPriority.TRAVEL, ControlChannel.CAMERA);
        assertFalse(smoother.holdMoveHeading(cam, Float.NaN));
        assertEquals(0, backend.moveWrites);
        assertEquals(1f, backend.move.forwardImpulse);
    }

    @Test
    void theHoldEndsWithTheLease() {
        backend.yaw = 90f;
        ControlLease released = lease(ControlPriority.TRAVEL, ControlChannel.CAMERA);
        openTick(1f, 0f);
        released.close();
        assertFalse(smoother.holdMoveHeading(released, 0f), "released");

        ControlLease stopped = lease(ControlPriority.TRAVEL, ControlChannel.CAMERA);
        arbiter.stopAll("test");
        assertFalse(smoother.holdMoveHeading(stopped, 0f), "Stop-All");
        arbiter.resumeAutomation();

        ControlLease overridden = lease(ControlPriority.TRAVEL, ControlChannel.CAMERA);
        arbiter.onManualInput(ManualInput.MOUSE_LOOK); // the user's own mouse wins the camera
        assertFalse(overridden.isActive());
        assertFalse(smoother.holdMoveHeading(overridden, 0f), "manual mouse look");

        ControlLease preempted = lease(ControlPriority.TRAVEL, ControlChannel.CAMERA);
        arbiter.request(LeaseRequest.of("combat", ControlPriority.COMBAT, null, ControlChannel.CAMERA)).orElseThrow();
        assertFalse(smoother.holdMoveHeading(preempted, 0f), "preempted");

        assertEquals(0, backend.moveWrites);
        assertEquals(1f, backend.move.forwardImpulse);
        assertEquals(0f, backend.move.leftImpulse);
    }

    @Test
    void anEmergencyLeaseKeepsItsHoldThroughTheUsersMouse() {
        ControlLease clutch = lease(ControlPriority.EMERGENCY, ControlChannel.CAMERA, ControlChannel.WORLD_INTERACT);
        backend.yaw = -90f;
        openTick(1f, 0f);
        arbiter.onManualInput(ManualInput.MOUSE_LOOK);
        assertTrue(clutch.isActive());
        assertTrue(smoother.holdMoveHeading(clutch, 0f));
        assertEquals(-1f, backend.move.leftImpulse, 1e-6f);
        assertTrue(backend.move.right);
    }

    @Test
    void foreignTurnReAimsTheKeysWhenTheEventOpens() {
        ControlLease job = lease(ControlPriority.GOAL, ControlChannel.CAMERA, ControlChannel.MOVEMENT);
        assertTrue(job.isActive());
        smoother.setForeignRotationGuard(true);
        backend.yaw = 0f;
        smoother.preTick();
        backend.yaw = 90f; // code outside the core snapped the head toward where it walks
        smoother.captureForeignRotation();
        assertEquals(0f, backend.yaw, "the snap is undone");
        keys(1f, 0f);
        smoother.beginMoveInput();
        // the keys were meant along yaw 90; the camera still looks along 0
        float[] want = reference(1f, 0f, backend.yaw, 90f);
        assertEquals(want[0], backend.move.forwardImpulse, 0f);
        assertEquals(want[1], backend.move.leftImpulse, 0f);
        assertTrue(backend.move.right);
        smoother.endMoveInput();

        smoother.setForeignRotationGuard(false);
        openTick(1f, 0f);
        assertEquals(1f, backend.move.forwardImpulse, "guard off: the keys are left alone");
    }

    @Test
    void noGuardNoHoldNoWrite() {
        openTick(1f, -1f);
        smoother.endMoveInput();
        assertEquals(0, backend.moveWrites);
    }

    @Test
    void holdMoveHeadingAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        ControlLease l = lease(ControlPriority.TRAVEL, ControlChannel.CAMERA, ControlChannel.HOTBAR);
        int[] i = {0};
        boolean[] ok = {true};
        long bytes = AllocationProbe.measure(1_000_000, () -> {
            int k = i[0]++;
            backend.yaw = (k % 360) - 180f;
            smoother.preTick();
            backend.move.forwardImpulse = 1f;
            backend.move.leftImpulse = (k % 3) - 1;
            smoother.beginMoveInput();
            ok[0] &= smoother.holdMoveHeading(l, 15f);
            smoother.endMoveInput();
        });
        assertTrue(ok[0]);
        assertEquals(0L, bytes, "per-tick heading hold must not allocate");
    }
}
