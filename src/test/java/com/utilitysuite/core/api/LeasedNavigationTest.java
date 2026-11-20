package com.utilitysuite.core.api;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.SuiteEventBus;
import com.utilitysuite.core.arbiter.ActionArbiter;
import com.utilitysuite.core.input.FakeInputBackend;
import com.utilitysuite.core.input.InputSmoother;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** The default {@link INavigator#goToLeased} contract, with a fake navigator (no Baritone). */
class LeasedNavigationTest {

    /** Minimal navigator: PATHING after a command until cancelled or told it arrived. */
    static final class FakeNavigator implements INavigator {
        NavigationStatus status = NavigationStatus.IDLE;
        int cancels;

        @Override public boolean isAvailable() { return true; }
        @Override public void goTo(int x, int y, int z, int range) { status = NavigationStatus.PATHING; }
        @Override public void followPath(long[] packedPositions) { status = NavigationStatus.PATHING; }
        @Override public NavigationStatus status() { return status; }
        @Override public void pause() { }
        @Override public void resume() { }
        @Override public void cancel() { cancels++; status = NavigationStatus.IDLE; }
    }

    private ActionArbiter arbiter;
    private FakeNavigator nav;

    @BeforeEach
    void setUp() {
        Services.resetForTests();
        arbiter = new ActionArbiter();
        arbiter.bind(new InputSmoother(new FakeInputBackend(), new Random(1)), null, new SuiteEventBus());
        Services.registerArbiter(arbiter);
        nav = new FakeNavigator();
    }

    @AfterEach
    void tearDown() {
        Services.resetForTests();
    }

    @Test
    void arrivalReleasesLease() {
        NavigationRun run = nav.goToLeased("waypoints", ControlPriority.TRAVEL, 1, 2, 3, 2).orElseThrow();
        assertTrue(arbiter.isHeldBy(ControlChannel.MOVEMENT, "waypoints"));
        nav.status = NavigationStatus.ARRIVED;
        assertEquals(NavigationStatus.ARRIVED, run.status());
        assertFalse(run.isActive());
        assertTrue(arbiter.holder(ControlChannel.MOVEMENT).isEmpty());
        assertEquals(0, nav.cancels);
    }

    @Test
    void stopAllCancelsDefaultRun() {
        NavigationRun run = nav.goToLeased("waypoints", ControlPriority.TRAVEL, 1, 2, 3, 2).orElseThrow();
        arbiter.stopAll("test");
        assertEquals(1, nav.cancels);
        assertEquals(LeaseEndReason.STOP_ALL, run.endReason());
        assertFalse(run.isActive());
    }

    @Test
    void refusedLeaseIssuesNoCommand() {
        arbiter.request(LeaseRequest.of("combat_assistant", ControlPriority.COMBAT, LeaseListener.NONE,
                ControlChannel.MOVEMENT)).orElseThrow();
        assertTrue(nav.goToLeased("waypoints", ControlPriority.TRAVEL, 1, 2, 3, 2).isEmpty());
        assertEquals(NavigationStatus.IDLE, nav.status);
        assertTrue(nav.followPathLeased("waypoints", ControlPriority.TRAVEL, new long[0]).isEmpty());
    }

    @Test
    void perTickUpdateDoesNotAllocate() {
        LeasedNavigation run = LeasedNavigation.begin(arbiter, nav, "travel_autopilot", ControlPriority.TRAVEL,
                () -> nav.goTo(0, 64, 0, 3)).orElseThrow();
        if (!AllocationProbe.supported()) {
            return;
        }
        long bytes = AllocationProbe.measure(10_000, () -> {
            run.update();
            run.status();
        });
        assertEquals(0, bytes, "update()/status() allocate");
        assertTrue(run.isActive());
    }
}
