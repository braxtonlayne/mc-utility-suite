package com.utilitysuite.travel;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.ManualInput;
import com.utilitysuite.core.arbiter.ActionArbiter;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TravelCommonTest {

    @Test
    void packedMatchesBlockPosLayout() {
        Random r = new Random(7);
        for (int i = 0; i < 1000; i++) {
            int x = r.nextInt(60_000_000) - 30_000_000;
            int y = r.nextInt(4096) - 2048;
            int z = r.nextInt(60_000_000) - 30_000_000;
            long p = Packed.pack(x, y, z);
            assertEquals(x, Packed.x(p));
            assertEquals(y, Packed.y(p));
            assertEquals(z, Packed.z(p));
            assertEquals(net.minecraft.core.BlockPos.asLong(x, y, z), p);
        }
    }

    @Test
    void anglesFollowMinecraftConventions() {
        assertEquals(0.0, Angles.yawTo(0, 1), 1e-9);
        assertEquals(90.0, Angles.yawTo(-1, 0), 1e-9);
        assertEquals(-90.0, Angles.yawTo(1, 0), 1e-9);
        assertEquals(45.0, Angles.pitchTo(1, -1), 1e-9, "looking down is positive");
        assertEquals(-160.0, Angles.delta(175, 15), 1e-9);
        assertEquals(20.0, Angles.delta(170, -170), 1e-9);
        assertEquals(1.0, Angles.dirZ(0), 1e-9);
        assertEquals(-1.0, Angles.dirX(90), 1e-9);
    }

    @Test
    void gridRayFindsTheFirstBlockingCell() {
        BlockQuery wallAtX5 = (x, y, z) -> x == 5;
        assertEquals(4.5, GridRay.march(0.5, 64.5, 0.5, 1, 0, 0, 32, wallAtX5), 1e-9);
        assertEquals(32.0, GridRay.march(0.5, 64.5, 0.5, -1, 0, 0, 32, wallAtX5), 1e-9);
        assertEquals(0.0, GridRay.march(5.5, 64.5, 0.5, 1, 0, 0, 32, wallAtX5), 1e-9);
        // Diagonal: reaches x = 5 after 4.5 * sqrt(2).
        assertEquals(4.5 * Math.sqrt(2), GridRay.march(0.5, 64.5, 0.5, 1, 0, 1, 32, wallAtX5), 1e-9);
        BlockQuery floor = (x, y, z) -> y < 60;
        assertEquals(4.5, GridRay.march(0.5, 64.5, 0.5, 0, -1, 0, 32, floor), 1e-9);
    }

    @Test
    void leaseSlotPausesOnPreemptionAndResumesWhenFree() {
        ActionArbiter arbiter = new ActionArbiter();
        LeaseSlot slot = new LeaseSlot("travel_test", () -> arbiter);
        LeaseRequest req = slot.request(ControlPriority.TRAVEL, 0, ControlChannel.MOVEMENT, ControlChannel.CAMERA);
        ControlLease first = slot.ensure(req);
        assertNotNull(first);
        assertTrue(slot.ensure(req) == first, "active lease is reused, no new request");

        ControlLease combat = arbiter.request(LeaseRequest.of("combat", ControlPriority.COMBAT, null,
                ControlChannel.CAMERA)).orElseThrow();
        assertFalse(first.isActive());
        assertTrue(slot.isSuspended());
        assertNull(slot.ensure(req), "no re-request while suspended");

        combat.close(); // channels free again -> onResumable
        assertFalse(slot.isSuspended());
        ControlLease resumed = slot.ensure(req);
        assertNotNull(resumed);
        assertTrue(resumed.isActive());
    }

    @Test
    void leaseSlotYieldsToManualInputUntilThePauseEnds() {
        ActionArbiter arbiter = new ActionArbiter();
        arbiter.setManualOverridePauseTicks(5);
        LeaseSlot slot = new LeaseSlot("travel_test", () -> arbiter);
        LeaseRequest req = slot.request(ControlPriority.TRAVEL, 0, ControlChannel.MOVEMENT);
        assertNotNull(slot.ensure(req));
        arbiter.onManualInput(ManualInput.MOVEMENT_KEY);
        assertTrue(slot.isSuspended());
        for (int i = 0; i < 5; i++) {
            assertNull(slot.ensure(req));
            arbiter.tick();
        }
        assertNotNull(slot.ensure(req), "resumes once the manual-override pause elapsed");
    }

    @Test
    void stopAllTerminatesUntilReset() {
        ActionArbiter arbiter = new ActionArbiter();
        LeaseSlot slot = new LeaseSlot("travel_test", () -> arbiter);
        LeaseRequest req = slot.request(ControlPriority.TRAVEL, 0, ControlChannel.MOVEMENT);
        assertNotNull(slot.ensure(req));
        arbiter.stopAll("test");
        assertTrue(slot.terminated());
        assertNull(slot.ensure(req));
        arbiter.resumeAutomation();
        slot.reset();
        assertNotNull(slot.ensure(req));
    }

    @Test
    void refusedRequestsAreThrottled() {
        ActionArbiter arbiter = new ActionArbiter();
        arbiter.request(LeaseRequest.of("combat", ControlPriority.COMBAT, null, ControlChannel.MOVEMENT));
        LeaseSlot slot = new LeaseSlot("travel_test", () -> arbiter);
        LeaseRequest req = slot.request(ControlPriority.TRAVEL, 0, ControlChannel.MOVEMENT);
        assertNull(slot.ensure(req));
        for (int i = 0; i < LeaseSlot.RETRY_TICKS; i++) {
            assertNull(slot.ensure(req)); // cooling down, no request made
        }
        assertNull(slot.ensure(req)); // retried and refused again
    }
}
