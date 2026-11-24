package com.utilitysuite.core.arbiter;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.ArbiterEventKind;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IArbiterHistory;
import com.utilitysuite.core.api.LeaseDenial;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.ManualInput;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.utilitysuite.core.api.ControlChannel.CAMERA;
import static com.utilitysuite.core.api.ControlChannel.MOVEMENT;
import static org.junit.jupiter.api.Assertions.*;

/** 5.4: the arbiter records grants, preemptions, denials and ends in a fixed ring, newest first. */
class ArbiterHistoryTest {

    private static final int MOVE = 1 << MOVEMENT.ordinal();
    private static final int CAM = 1 << CAMERA.ordinal();

    private ActionArbiter arbiter;
    private IArbiterHistory h;

    @BeforeEach
    void setUp() {
        arbiter = new ActionArbiter();
        arbiter.bind(null, null, null);
        h = arbiter.history();
    }

    private ControlLease grant(String module, ControlPriority p, com.utilitysuite.core.api.ControlChannel first,
                               com.utilitysuite.core.api.ControlChannel... rest) {
        return arbiter.request(LeaseRequest.of(module, p, null, first, rest)).orElseThrow();
    }

    @Test
    void startsEmptyAndIsTheSameInstance() {
        assertEquals(0, h.size());
        assertEquals(0, h.lastStop().size());
        assertSame(h, arbiter.history());
    }

    @Test
    void grantPreemptionAndReleaseAreRecordedNewestFirst() {
        arbiter.tick();
        ControlLease low = grant("travel", ControlPriority.TRAVEL, MOVEMENT, CAMERA);
        arbiter.tick();
        ControlLease high = grant("combat", ControlPriority.COMBAT, MOVEMENT);
        assertFalse(low.isActive());
        high.close();

        assertEquals(4, h.size());
        assertEquals(ArbiterEventKind.ENDED, h.kind(0));
        assertEquals(LeaseEndReason.RELEASED, h.endReason(0));
        assertEquals("combat", h.by(0));

        assertEquals(ArbiterEventKind.GRANTED, h.kind(1));
        assertEquals("combat", h.owner(1));
        assertEquals(MOVE, h.channelMask(1));
        assertEquals("", h.by(1));
        assertEquals(2L, h.tick(1));

        assertEquals(ArbiterEventKind.PREEMPTED, h.kind(2));
        assertEquals("travel", h.owner(2));
        assertEquals(MOVE | CAM, h.channelMask(2));
        assertEquals(LeaseEndReason.PREEMPTED, h.endReason(2));
        assertEquals("combat", h.by(2));
        assertEquals("preempted", h.reasonLabel(2));

        assertEquals(ArbiterEventKind.GRANTED, h.kind(3));
        assertEquals(1L, h.tick(3));

        // the release is not a stop: the last stop is still the preemption
        assertEquals(1, h.lastStop().size());
        assertEquals(ArbiterEventKind.PREEMPTED, h.lastStop().kind(0));
        assertEquals("travel", h.lastStop().owner(0));
        assertEquals(2L, h.now());
    }

    @Test
    void denialsNameTheBlockingChannelAndHolder() {
        grant("combat", ControlPriority.COMBAT, CAMERA);
        assertTrue(arbiter.request(LeaseRequest.of("travel", ControlPriority.TRAVEL, null, MOVEMENT, CAMERA))
                .isEmpty());
        assertEquals(ArbiterEventKind.DENIED, h.kind(0));
        assertEquals(LeaseDenial.HIGHER_HOLDER, h.denial(0));
        assertNull(h.endReason(0));
        assertEquals("travel", h.owner(0));
        assertEquals("combat", h.by(0));
        assertEquals(CAM, h.channelMask(0));
        assertEquals("held higher", h.reasonLabel(0));
        assertSame(LeaseDenial.HIGHER_HOLDER, h.lastStop().denial(0));
    }

    @Test
    void manualOverrideAndItsPauseAreRecorded() {
        grant("travel", ControlPriority.TRAVEL, MOVEMENT);
        arbiter.onManualInput(ManualInput.MOVEMENT_KEY);
        assertEquals(ArbiterEventKind.PREEMPTED, h.kind(0));
        assertEquals(LeaseEndReason.MANUAL_OVERRIDE, h.endReason(0));
        assertEquals(ActionArbiter.BY_MANUAL, h.by(0));
        assertEquals("manual override", h.reasonLabel(0));

        assertTrue(arbiter.request(LeaseRequest.of("travel", ControlPriority.TRAVEL, null, MOVEMENT)).isEmpty());
        assertEquals(LeaseDenial.OVERRIDE_PAUSE, h.denial(0));
        assertEquals(ActionArbiter.BY_MANUAL, h.by(0));
    }

    @Test
    void stopAllAndRequestsWhileStoppedAreRecorded() {
        grant("travel", ControlPriority.TRAVEL, MOVEMENT);
        arbiter.stopAll("test");
        assertEquals(ArbiterEventKind.ENDED, h.kind(0));
        assertEquals(LeaseEndReason.STOP_ALL, h.endReason(0));
        assertEquals(ActionArbiter.BY_STOP_ALL, h.by(0));
        assertEquals(LeaseEndReason.STOP_ALL, h.lastStop().endReason(0));

        assertTrue(arbiter.request(LeaseRequest.of("travel", ControlPriority.TRAVEL, null, MOVEMENT, CAMERA))
                .isEmpty());
        assertEquals(LeaseDenial.AUTOMATION_STOPPED, h.denial(0));
        assertEquals(MOVE | CAM, h.channelMask(0));
    }

    @Test
    void timeoutIsAStop() {
        arbiter.request(LeaseRequest.of("travel", ControlPriority.TRAVEL, null, MOVEMENT).withTimeout(1));
        arbiter.tick();
        arbiter.tick();
        assertEquals(LeaseEndReason.TIMED_OUT, h.endReason(0));
        assertEquals("timed out", h.lastStop().reasonLabel(0));
    }

    @Test
    void repeatedIdenticalDenialsFoldIntoOneEntry() {
        grant("combat", ControlPriority.COMBAT, MOVEMENT);
        int before = h.size();
        for (int i = 0; i < 50; i++) {
            arbiter.tick();
            arbiter.request(LeaseRequest.of("travel", ControlPriority.TRAVEL, null, MOVEMENT));
        }
        assertEquals(before + 1, h.size());
        assertEquals(50, h.repeats(0));
        assertEquals(50L, h.tick(0));
        assertEquals(1, h.repeats(1));
    }

    @Test
    void ringKeepsTheLastSixteen() {
        ArbiterHistory ring = new ArbiterHistory(IArbiterHistory.CAPACITY, true);
        String[] owners = {"a", "b", "c"};
        for (int i = 0; i < 40; i++) {
            ring.record(i, ArbiterEventKind.GRANTED, owners[i % 3], i & 15, null, null, "");
        }
        assertEquals(IArbiterHistory.CAPACITY, ring.size());
        for (int i = 0; i < IArbiterHistory.CAPACITY; i++) {
            assertEquals(39L - i, ring.tick(i));
        }
        assertEquals(0, ring.lastStop().size(), "grants are not stops");
        assertThrows(IndexOutOfBoundsException.class, () -> ring.tick(IArbiterHistory.CAPACITY));
    }

    @Test
    void recordingAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        ArbiterHistory ring = new ArbiterHistory(IArbiterHistory.CAPACITY, true);
        String[] owners = {"movement_assist", "combat_assistant", "travel_autopilot"};
        ArbiterEventKind[] kinds = ArbiterEventKind.values();
        LeaseEndReason[] ends = LeaseEndReason.values();
        long[] t = {0};
        long bytes = AllocationProbe.measure(20_000, () -> {
            long i = ++t[0];
            int k = (int) (i % kinds.length);
            ring.setNow(i);
            ring.record(i, kinds[k], owners[(int) (i % 3)], (int) (i & 15),
                    k == 1 || k == 3 ? ends[(int) (i % ends.length)] : null,
                    k == 2 ? LeaseDenial.HIGHER_HOLDER : null, owners[(int) ((i + 1) % 3)]);
            ring.reasonLabel(0);
            ring.lastStop().size();
        });
        assertEquals(0L, bytes, "history recording allocated " + bytes + " bytes");
    }
}
