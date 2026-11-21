package com.utilitysuite.jobs.baritone;

import com.utilitysuite.core.SuiteEventBus;
import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.INavigator;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.LeaseListener;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.ManualInput;
import com.utilitysuite.core.api.NavigationRun;
import com.utilitysuite.core.api.NavigationStatus;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.arbiter.ActionArbiter;
import com.utilitysuite.core.input.FakeInputBackend;
import com.utilitysuite.core.input.InputSmoother;
import com.utilitysuite.jobs.planner.PackedPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.LongPredicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Navigator runs started by waypoint Go / Take Me Back, the schematic walk and overland autopilot hold a lease, are
 * cancelled when it is preempted, overridden or revoked, and Stop-All cancels every Baritone process unconditionally.
 * Uses the real {@link ActionArbiter} and a fake {@link PathBackend}; no Minecraft.
 */
class LeasedNavigatorRunTest {

    /** Records commands; "pathing" until cancelled. */
    static final class FakeBackend implements PathBackend {
        final List<String> log = new ArrayList<>();
        int cancels;
        boolean pathing;

        @Override public void mine(List<String> blockIds) { log.add("mine " + blockIds); pathing = true; }
        @Override public void goTo(int x, int y, int z, int range) { log.add("goTo " + x + " " + y + " " + z); pathing = true; }
        @Override public void goToBlock(int x, int y, int z) { log.add("goToBlock " + x + " " + y + " " + z); pathing = true; }
        @Override public void cancel() { cancels++; pathing = false; }
        @Override public boolean isMining() { return pathing; }
        @Override public boolean isGoalActive() { return pathing; }
        @Override public boolean isInGoal() { return false; }
        @Override public boolean isPathing() { return pathing; }
        @Override public long firstPathPos(LongPredicate test) { return Long.MIN_VALUE; }
        @Override public void configure() { }
        @Override public void clearArea(int x1, int y1, int z1, int x2, int y2, int z2) { log.add("clear"); }
        @Override public boolean isBuilding() { return false; }
        @Override public void setLegitMining(boolean legit, int yLevel) { log.add("legit " + legit + " " + yLevel); }
    }

    private ActionArbiter arbiter;
    private FakeBackend backend;
    private BaritoneAdapter adapter;
    private INavigator nav;

    @BeforeEach
    void setUp() {
        Services.resetForTests();
        arbiter = new ActionArbiter();
        SuiteEventBus events = new SuiteEventBus();
        arbiter.bind(new InputSmoother(new FakeInputBackend(), new Random(1)), null, events);
        Services.registerArbiter(arbiter);
        Services.registerEvents(events);
        backend = new FakeBackend();
        adapter = new BaritoneAdapter((dim, pos) -> false, backend);
        adapter.subscribeStopAll(events);
        nav = adapter.navigator();
        Services.registerNavigator(nav);
    }

    @AfterEach
    void tearDown() {
        Services.resetForTests();
    }

    /** What WorldTab's waypoint "Go" button does. */
    private NavigationRun waypointGo() {
        Optional<NavigationRun> run = Services.navigatorOrNull()
                .goToLeased("waypoints", ControlPriority.TRAVEL, 100, 64, -200, 2);
        assertTrue(run.isPresent(), "lease granted");
        return run.get();
    }

    @Test
    void waypointGoHoldsMovementCameraAndInteractLease() {
        NavigationRun run = waypointGo();
        assertTrue(run.isActive());
        assertEquals(NavigationStatus.PATHING, run.status());
        assertTrue(arbiter.isHeldBy(ControlChannel.MOVEMENT, "waypoints"));
        assertTrue(arbiter.isHeldBy(ControlChannel.CAMERA, "waypoints"));
        assertTrue(arbiter.isHeldBy(ControlChannel.WORLD_INTERACT, "waypoints"));
        assertEquals(List.of("goTo 100 64 -200"), backend.log);
    }

    @Test
    void stopAllCancelsWaypointGo() {
        NavigationRun run = waypointGo();
        int before = backend.cancels;
        arbiter.stopAll("test");
        assertTrue(backend.cancels > before, "Baritone cancelled");
        assertFalse(backend.pathing);
        assertFalse(run.isActive());
        assertEquals(LeaseEndReason.STOP_ALL, run.endReason());
        assertEquals(NavigationStatus.IDLE, nav.status());
        assertTrue(arbiter.holder(ControlChannel.MOVEMENT).isEmpty());
        // Refused while stopped.
        assertTrue(nav.goToLeased("waypoints", ControlPriority.TRAVEL, 0, 64, 0, 2).isEmpty());
        assertFalse(backend.pathing);
    }

    @Test
    void stopAllCancelsEvenUnleasedPathing() {
        nav.goTo(5, 70, 5, 1); // legacy unleased call
        assertTrue(backend.pathing);
        arbiter.stopAll("test");
        assertFalse(backend.pathing);
        assertEquals(NavigationStatus.IDLE, nav.status());
    }

    @Test
    void preemptionCancelsPath() {
        NavigationRun run = nav.followPathLeased("waypoints", ControlPriority.TRAVEL,
                new long[]{PackedPos.pack(1, 64, 1), PackedPos.pack(2, 64, 2)}).orElseThrow();
        Optional<ControlLease> combat = arbiter.request(LeaseRequest.of("combat_assistant", ControlPriority.COMBAT,
                LeaseListener.NONE, ControlChannel.CAMERA));
        assertTrue(combat.isPresent());
        assertFalse(backend.pathing, "Baritone cancelled when the lease was preempted");
        assertFalse(run.isActive());
        assertEquals(LeaseEndReason.PREEMPTED, run.endReason());
    }

    @Test
    void manualInputCancelsPath() {
        NavigationRun run = waypointGo();
        arbiter.onManualInput(ManualInput.MOVEMENT_KEY);
        assertFalse(backend.pathing);
        assertEquals(LeaseEndReason.MANUAL_OVERRIDE, run.endReason());
    }

    @Test
    void lowerPriorityWalkIsRefusedWhileTravelRuns() {
        waypointGo();
        assertTrue(nav.goToLeased("schematic_toolkit.walk", ControlPriority.GOAL, 0, 64, 0, 4).isEmpty());
        assertEquals(List.of("goTo 100 64 -200"), backend.log, "refused walk issued no command");
    }

    @Test
    void cancellingRunReleasesLease() {
        NavigationRun run = waypointGo();
        run.cancel();
        assertFalse(backend.pathing);
        assertTrue(arbiter.holder(ControlChannel.MOVEMENT).isEmpty());
        assertNull(run.endReason());
    }

    @Test
    void supersededRunReleasesLeaseWithoutCancellingNewCommand() {
        NavigationRun first = waypointGo();
        NavigationRun second = nav.goToLeased("travel_autopilot", ControlPriority.SURVIVAL, 7, 64, 7, 3).orElseThrow();
        assertFalse(first.isActive());
        assertTrue(second.isActive());
        assertTrue(backend.pathing);
        assertTrue(arbiter.isHeldBy(ControlChannel.MOVEMENT, "travel_autopilot"));
    }

    @Test
    void goalCommandSuspendedByRunIsResumedAfterwards() {
        // Goal engine (GOAL lease) mining through the driver.
        ControlLease goal = arbiter.request(LeaseRequest.of("goal_engine", ControlPriority.GOAL,
                (l, reason, by) -> adapter.pause(), ControlChannel.MOVEMENT, ControlChannel.CAMERA,
                ControlChannel.WORLD_INTERACT)).orElseThrow();
        adapter.mineByYield("minecraft:coal", 8, List.of("minecraft:coal_ore"));
        NavigationRun run = waypointGo();
        assertFalse(goal.isActive());
        run.cancel();
        adapter.resume();
        assertEquals("mine [minecraft:coal_ore]", backend.log.get(backend.log.size() - 1));
        assertTrue(backend.pathing);
    }
}
