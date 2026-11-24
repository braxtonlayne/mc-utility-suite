package com.utilitysuite.core.arbiter;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.SuiteEventBus;
import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.LeaseListener;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.ManualInput;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.core.api.SuiteEvent;
import com.utilitysuite.core.input.FakeInputBackend;
import com.utilitysuite.core.input.InputSmoother;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.utilitysuite.core.api.ControlChannel.CAMERA;
import static com.utilitysuite.core.api.ControlChannel.HOTBAR;
import static com.utilitysuite.core.api.ControlChannel.MOVEMENT;
import static com.utilitysuite.core.api.ControlChannel.WORLD_INTERACT;
import static org.junit.jupiter.api.Assertions.*;

class ActionArbiterTest {

    /** Records callbacks as strings like "preempted:PREEMPTED:combat" / "resumable". */
    static final class Recorder implements LeaseListener {
        final List<String> log = new ArrayList<>();

        @Override
        public void onPreempted(ControlLease lease, LeaseEndReason reason, String by) {
            log.add("preempted:" + reason + ":" + by);
        }

        @Override
        public void onResumable(ControlLease formerLease) {
            log.add("resumable");
        }
    }

    private ActionArbiter arbiter;
    private FakeInputBackend backend;
    private InputSmoother smoother;
    private SuiteEventBus events;
    private final List<SuiteEvent> posted = new ArrayList<>();

    @BeforeEach
    void setUp() {
        arbiter = new ActionArbiter();
        backend = new FakeInputBackend();
        smoother = new InputSmoother(backend, new Random(1));
        events = new SuiteEventBus();
        events.subscribe(SuiteEvent.class, posted::add);
        arbiter.bind(smoother, null, events);
    }

    private ControlLease grant(String module, ControlPriority p, LeaseListener l, ControlChannel first,
                               ControlChannel... rest) {
        return arbiter.request(LeaseRequest.of(module, p, l, first, rest)).orElseThrow();
    }

    @Test
    void equalOrLowerPriorityIsRefused() {
        grant("a", ControlPriority.TRAVEL, null, MOVEMENT);
        assertTrue(arbiter.request(LeaseRequest.of("b", ControlPriority.TRAVEL, null, MOVEMENT)).isEmpty());
        assertTrue(arbiter.request(LeaseRequest.of("b", ControlPriority.GOAL, null, MOVEMENT, CAMERA)).isEmpty());
        // Atomic: CAMERA was free but the request failed as a whole.
        assertTrue(arbiter.holder(CAMERA).isEmpty());
        assertTrue(arbiter.request(LeaseRequest.of("b", ControlPriority.GOAL, null, CAMERA)).isPresent());
    }

    @Test
    void preemptionEndsWholeLeaseAndResumesAfterRelease() {
        Recorder goal = new Recorder();
        ControlLease g = grant("goal", ControlPriority.GOAL, goal, MOVEMENT, CAMERA);
        ControlLease s = grant("survival", ControlPriority.SURVIVAL, null, CAMERA);
        assertFalse(g.isActive());
        assertEquals(List.of("preempted:PREEMPTED:survival"), goal.log);
        assertTrue(arbiter.holder(MOVEMENT).isEmpty(), "whole preempted lease ends, MOVEMENT is free");
        s.close();
        assertEquals(List.of("preempted:PREEMPTED:survival", "resumable"), goal.log);
        assertEquals(0, arbiter.suspendedCount());
    }

    @Test
    void reRequestReplacesOwnLease() {
        Recorder r = new Recorder();
        ControlLease first = grant("goal", ControlPriority.GOAL, r, MOVEMENT);
        ControlLease second = grant("goal", ControlPriority.GOAL, r, MOVEMENT, CAMERA);
        assertFalse(first.isActive());
        assertEquals(LeaseEndReason.RELEASED, ((Lease) first).endReason());
        assertTrue(second.isActive());
        assertTrue(r.log.isEmpty(), "replacing your own lease is not a preemption");
        // Refused re-request keeps the old lease.
        grant("combat", ControlPriority.COMBAT, null, HOTBAR);
        assertTrue(arbiter.request(LeaseRequest.of("goal", ControlPriority.GOAL, r, MOVEMENT, HOTBAR)).isEmpty());
        assertTrue(second.isActive());
    }

    @Test
    void manualOverrideEndsLeaseBlocksAndThenResumes() {
        arbiter.setManualOverridePauseTicks(5);
        Recorder r = new Recorder();
        ControlLease l = grant("travel", ControlPriority.TRAVEL, r, MOVEMENT);
        smoother.setKeyHeld(l, SimKey.FORWARD, true);
        assertTrue(backend.isDown(SimKey.FORWARD));

        arbiter.onManualInput(ManualInput.MOVEMENT_KEY);
        assertFalse(l.isActive());
        assertEquals(List.of("preempted:MANUAL_OVERRIDE:manual"), r.log);
        assertFalse(backend.isDown(SimKey.FORWARD), "automation key released on override");
        assertTrue(arbiter.isManualOverrideActive(MOVEMENT));
        assertTrue(arbiter.request(LeaseRequest.of("x", ControlPriority.COMBAT, null, MOVEMENT)).isEmpty());
        // Other channels are unaffected; EMERGENCY ignores the pause.
        assertTrue(arbiter.request(LeaseRequest.of("x", ControlPriority.COMBAT, null, CAMERA)).isPresent());
        ControlLease em = grant("em", ControlPriority.EMERGENCY, null, MOVEMENT);
        em.close();

        for (int i = 0; i < 4; i++) {
            arbiter.tick();
            assertFalse(r.log.contains("resumable"));
        }
        arbiter.tick(); // 5th tick: pause over
        assertFalse(arbiter.isManualOverrideActive(MOVEMENT));
        assertEquals("resumable", r.log.get(r.log.size() - 1));
        assertTrue(arbiter.request(LeaseRequest.of("travel", ControlPriority.TRAVEL, r, MOVEMENT)).isPresent());
    }

    @Test
    void emergencyIsNotOverriddenByManualInput() {
        ControlLease em = grant("em", ControlPriority.EMERGENCY, null, CAMERA);
        arbiter.onManualInput(ManualInput.MOUSE_LOOK);
        assertTrue(em.isActive());
    }

    @Test
    void timeoutEndsLeaseWithoutResume() {
        Recorder low = new Recorder();
        Recorder timed = new Recorder();
        grant("low", ControlPriority.IDLE, low, HOTBAR);
        ControlLease t = arbiter.request(new LeaseRequest("timed", ControlPriority.GOAL,
                java.util.EnumSet.of(HOTBAR), 3, timed)).orElseThrow();
        arbiter.tick();
        arbiter.tick();
        assertTrue(t.isActive());
        arbiter.tick();
        assertFalse(t.isActive());
        assertEquals(List.of("preempted:TIMED_OUT:timeout"), timed.log);
        // The lease preempted by the timed one may resume; the timed one itself never gets onResumable.
        assertEquals(List.of("preempted:PREEMPTED:timed", "resumable"), low.log);
        for (int i = 0; i < 10; i++) {
            arbiter.tick();
        }
        assertEquals(1, timed.log.size());
    }

    @Test
    void eventsArePosted() {
        ControlLease l = grant("a", ControlPriority.GOAL, null, CAMERA);
        l.close();
        assertInstanceOf(SuiteEvent.LeaseGranted.class, posted.get(0));
        SuiteEvent.LeaseEnded ended = assertInstanceOf(SuiteEvent.LeaseEnded.class, posted.get(1));
        assertEquals(LeaseEndReason.RELEASED, ended.reason());
        l.close(); // idempotent
        assertEquals(2, posted.size());
    }

    @Test
    void stopAllRefusesUntilResumed() {
        Recorder r = new Recorder();
        grant("a", ControlPriority.GOAL, r, CAMERA);
        arbiter.stopAll("test");
        assertEquals(List.of("preempted:STOP_ALL:stop_all"), r.log);
        assertTrue(arbiter.isAutomationStopped());
        assertTrue(arbiter.request(LeaseRequest.of("e", ControlPriority.EMERGENCY, null, CAMERA)).isEmpty());
        arbiter.resumeAutomation();
        assertTrue(arbiter.request(LeaseRequest.of("e", ControlPriority.EMERGENCY, null, CAMERA)).isPresent());
        assertFalse(r.log.contains("resumable"));
    }

    @Test
    void tickDoesNotAllocate() {
        if (!AllocationProbe.supported()) {
            return;
        }
        grant("a", ControlPriority.GOAL, null, CAMERA, MOVEMENT);
        arbiter.request(new LeaseRequest("b", ControlPriority.GOAL, java.util.EnumSet.of(HOTBAR), 0, null));
        arbiter.setManualOverridePauseTicks(1_000_000_000);
        arbiter.onManualInput(ManualInput.ATTACK_OR_USE); // keeps a pause counter running
        long bytes = AllocationProbe.measure(200_000, arbiter::tick);
        assertTrue(bytes < 2048, "arbiter.tick allocated " + bytes + " bytes over 200k ticks");
    }

    @Test
    void playerSwapEndsLeasesAndReleasesHeldInput() {
        Recorder r = new Recorder();
        ControlLease l = grant("travel", ControlPriority.TRAVEL, r, MOVEMENT, CAMERA);
        smoother.setKeyHeld(l, SimKey.FORWARD, true);
        smoother.setKeyHeld(l, SimKey.SNEAK, true);
        assertTrue(smoother.setRotationTarget(l, com.utilitysuite.core.api.RotationTarget.silent(90f, 10f)));
        assertTrue(smoother.setServerPose(l, Boolean.TRUE, null));
        smoother.postTick();
        assertTrue(backend.isDown(SimKey.FORWARD));
        assertTrue(smoother.isSilentRotationActive());
        assertTrue(com.utilitysuite.core.input.ServerPose.sneak(false));

        arbiter.resetForPlayerSwap(); // respawn or dimension change: a new LocalPlayer, same keys and smoother

        assertFalse(l.isActive());
        assertEquals(List.of("preempted:TIMED_OUT:player_swap"), r.log, "the holder is told, so it drops its caches");
        assertFalse(backend.anyDown(), "no simulated key survives into the new player");
        assertFalse(smoother.isSilentRotationActive());
        assertFalse(com.utilitysuite.core.input.ServerPose.sneak(false), "reported sneak pose ended");
        // the core then drops the eased return too: the new player's packets carry the camera angle at once
        assertTrue(smoother.isSilentReturning());
        smoother.dropSilentReturn();
        assertFalse(smoother.isSilentReturning());
        assertFalse(com.utilitysuite.core.input.SilentRotation.isActive(), "no stale silent angle in packets");
        smoother.postTick();
        assertFalse(com.utilitysuite.core.input.SilentRotation.isActive());
        smoother.setKeyHeld(l, SimKey.FORWARD, true);
        smoother.preTick();
        assertFalse(backend.anyDown(), "the ended lease drives nothing");
        // the world goes on: nothing fires onResumable for the ended lease, and control can be requested again
        for (int i = 0; i < 5; i++) {
            arbiter.tick();
        }
        assertEquals(1, r.log.size());
        assertTrue(arbiter.request(LeaseRequest.of("travel", ControlPriority.TRAVEL, r, MOVEMENT)).isPresent());
    }

    @Test
    void playerSwapKeepsStopAllAndOffersFreedChannelsNextTick() {
        Recorder low = new Recorder();
        grant("low", ControlPriority.IDLE, low, HOTBAR);
        grant("high", ControlPriority.GOAL, null, HOTBAR); // "low" is remembered
        arbiter.resetForPlayerSwap();
        assertEquals(List.of("preempted:PREEMPTED:high"), low.log, "no resume callback inside the swap itself");
        arbiter.tick();
        assertEquals(List.of("preempted:PREEMPTED:high", "resumable"), low.log);

        arbiter.stopAll("test");
        arbiter.resetForPlayerSwap();
        assertTrue(arbiter.isAutomationStopped(), "a respawn must not undo the user's Stop-All");
        assertTrue(arbiter.request(LeaseRequest.of("e", ControlPriority.EMERGENCY, null, CAMERA)).isEmpty());
    }
}
