package com.utilitysuite.core.arbiter;

import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.ManualInput;
import com.utilitysuite.core.api.SharedGrant;
import com.utilitysuite.core.input.FakeInputBackend;
import com.utilitysuite.core.input.InputSmoother;
import com.utilitysuite.core.input.ManualFactsTracker;
import com.utilitysuite.core.input.SharedInputOverlay;
import com.utilitysuite.core.input.YawCoSteer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Random;

import static com.utilitysuite.core.api.ControlChannel.CAMERA;
import static com.utilitysuite.core.api.ControlChannel.MOVEMENT;
import static org.junit.jupiter.api.Assertions.*;

/** Shared grant lifecycle in the arbiter: every refusal and every end reason. */
class SharedGrantArbiterTest {

    private ActionArbiter arbiter;
    private SharedInputOverlay overlay;
    private final boolean[] screen = {false};
    private final boolean[] legit = {false};
    private ActionArbiterTest.Recorder recorder;

    @BeforeEach
    void setUp() {
        arbiter = new ActionArbiter();
        arbiter.bind(new InputSmoother(new FakeInputBackend(), new Random(1)), null, null);
        overlay = new SharedInputOverlay(arbiter, new ManualFactsTracker(), new YawCoSteer(),
                () -> screen[0], () -> legit[0]);
        recorder = new ActionArbiterTest.Recorder();
    }

    private Optional<ControlLease> lease(String module, ControlPriority p) {
        return arbiter.request(LeaseRequest.of(module, p, recorder, MOVEMENT));
    }

    @Test
    void grantHoldsMovementForItsModule() {
        SharedGrant g = overlay.begin("movement", ControlPriority.TRAVEL, true);
        assertNotNull(g);
        assertTrue(g.isActive());
        assertTrue(arbiter.isHeldBy(MOVEMENT, "movement"));
        assertFalse(arbiter.isHeldBy(CAMERA, "movement"));
        assertEquals("movement", arbiter.sharedModuleId());
        assertEquals(ControlPriority.TRAVEL, arbiter.sharedPriority());
        assertTrue(arbiter.holder(MOVEMENT).isEmpty(), "a grant is not a lease");
    }

    @Test
    void requestsAtOrBelowAreRefusedWhileLive() {
        SharedGrant g = overlay.begin("movement", ControlPriority.TRAVEL, false);
        assertTrue(lease("goal", ControlPriority.GOAL).isEmpty());
        assertTrue(lease("other", ControlPriority.TRAVEL).isEmpty());
        // a request not covering MOVEMENT is unaffected
        assertTrue(arbiter.request(LeaseRequest.of("cam", ControlPriority.IDLE, recorder, CAMERA)).isPresent());
        assertTrue(g.isActive());
    }

    @Test
    void strictlyHigherLeasePreemptsTheGrant() {
        SharedGrant g = overlay.begin("movement", ControlPriority.TRAVEL, false);
        assertTrue(lease("combat", ControlPriority.COMBAT).isPresent());
        assertFalse(g.isActive());
        assertEquals(LeaseEndReason.PREEMPTED, overlay.lastEndReason());
        assertNull(arbiter.sharedModuleId());
        assertTrue(arbiter.isHeldBy(MOVEMENT, "combat"));
    }

    @Test
    void emergencyAlwaysWins() {
        SharedGrant g = overlay.begin("movement", ControlPriority.EMERGENCY, false);
        assertNotNull(g);
        assertTrue(lease("clutch", ControlPriority.EMERGENCY).isPresent());
        assertFalse(g.isActive());
        assertEquals(LeaseEndReason.PREEMPTED, overlay.lastEndReason());
    }

    @Test
    void ownLeaseReplacesOwnGrant() {
        SharedGrant g = overlay.begin("movement", ControlPriority.TRAVEL, false);
        assertTrue(lease("movement", ControlPriority.TRAVEL).isPresent());
        assertFalse(g.isActive());
        assertEquals(LeaseEndReason.RELEASED, overlay.lastEndReason());
    }

    @Test
    void manualMovementKeyDoesNotEndGrantButEndsLease() {
        SharedGrant g = overlay.begin("movement", ControlPriority.TRAVEL, false);
        arbiter.onManualInput(ManualInput.MOVEMENT_KEY);
        assertTrue(g.isActive(), "the grant reports manual keys, it is not ended by them");
        assertTrue(arbiter.isManualOverrideActive(MOVEMENT));
        g.end();

        ControlLease l = lease("goal", ControlPriority.TRAVEL).orElse(null);
        assertNull(l, "the override pause still refuses others");
        for (int i = 0; i < 25; i++) {
            arbiter.tick();
        }
        l = lease("goal", ControlPriority.TRAVEL).orElseThrow();
        arbiter.onManualInput(ManualInput.MOVEMENT_KEY);
        assertFalse(l.isActive());
        assertEquals(LeaseEndReason.MANUAL_OVERRIDE, ((Lease) l).endReason);
    }

    @Test
    void stopAllEndsTheGrantAndRefusesNewOnes() {
        SharedGrant g = overlay.begin("movement", ControlPriority.TRAVEL, true);
        arbiter.stopAll("test");
        assertFalse(g.isActive());
        assertEquals(LeaseEndReason.STOP_ALL, overlay.lastEndReason());
        assertNull(overlay.begin("movement", ControlPriority.TRAVEL, true));
        arbiter.resumeAutomation();
        assertNotNull(overlay.begin("movement", ControlPriority.TRAVEL, true));
    }

    @Test
    void worldUnloadEndsTheGrant() {
        SharedGrant g = overlay.begin("movement", ControlPriority.TRAVEL, false);
        arbiter.resetForWorldUnload();
        assertFalse(g.isActive());
        assertEquals(LeaseEndReason.RELEASED, overlay.lastEndReason());
        assertNull(arbiter.sharedModuleId());
    }

    @Test
    void screenRefusesBeginAndEndsALiveGrant() {
        screen[0] = true;
        assertNull(overlay.begin("movement", ControlPriority.TRAVEL, false));
        screen[0] = false;
        SharedGrant g = overlay.begin("movement", ControlPriority.TRAVEL, false);
        overlay.beginTick(true);
        assertFalse(g.isActive());
        assertNull(arbiter.sharedModuleId());
        g = overlay.begin("movement", ControlPriority.TRAVEL, false);
        overlay.onScreenOpening();
        assertFalse(g.isActive());
        assertEquals(LeaseEndReason.PREEMPTED, overlay.lastEndReason());
    }

    @Test
    void leaseAtOrAboveRefusesBegin() {
        lease("goal", ControlPriority.TRAVEL).orElseThrow();
        assertNull(overlay.begin("movement", ControlPriority.TRAVEL, false));
        assertNull(arbiter.sharedModuleId());
    }

    @Test
    void beginPreemptsALowerLeaseWhichResumesAfterTheGrant() {
        ControlLease l = lease("goal", ControlPriority.GOAL).orElseThrow();
        SharedGrant g = overlay.begin("movement", ControlPriority.TRAVEL, false);
        assertNotNull(g);
        assertFalse(l.isActive());
        assertTrue(recorder.log.contains("preempted:PREEMPTED:movement"));
        assertFalse(recorder.log.contains("resumable"), "MOVEMENT is not free while the grant lives");
        g.end();
        assertTrue(recorder.log.contains("resumable"));
    }

    @Test
    void oneGrantAtATime() {
        SharedGrant a = overlay.begin("movement", ControlPriority.TRAVEL, false);
        assertNull(overlay.begin("other", ControlPriority.COMBAT, false));
        assertTrue(a.isActive());
        // the same module may begin again (restart, same instance)
        assertSame(a, overlay.begin("movement", ControlPriority.TRAVEL, false));
    }

    @Test
    void endIsIdempotentAndFreesMovement() {
        SharedGrant g = overlay.begin("movement", ControlPriority.TRAVEL, false);
        g.end();
        g.end();
        assertFalse(g.isActive());
        assertEquals(LeaseEndReason.RELEASED, overlay.lastEndReason());
        assertFalse(arbiter.isHeldBy(MOVEMENT, "movement"));
        assertTrue(lease("goal", ControlPriority.GOAL).isPresent());
    }
}
