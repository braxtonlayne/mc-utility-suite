package com.utilitysuite.jobs.baritone;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.SuiteEventBus;
import com.utilitysuite.core.api.IRoutePlanner;
import com.utilitysuite.core.api.IRoutePlanner.PlanState;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.arbiter.ActionArbiter;
import com.utilitysuite.core.input.FakeInputBackend;
import com.utilitysuite.core.input.InputSmoother;
import com.utilitysuite.jobs.planner.PackedPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The plan-only {@link IRoutePlanner} view of {@link BaritoneAdapter}: availability gating against jobs, the
 * NONE -> CALCULATING -> READY/FAILED lifecycle, serials, truncating copies, cancellation that never touches a running
 * job, and allocation-free polling. Uses a fake {@link PathBackend} that mimics the bridge's plan process; no game.
 */
class RoutePlannerTest {

    /** Job side as in {@link LeasedNavigatorRunTest.FakeBackend}, plus a scripted plan process. */
    static final class PlanFakeBackend implements PathBackend {
        final List<String> log = new ArrayList<>();
        int cancels;
        int planCancels;
        boolean pathing;
        PlanState planState = PlanState.NONE;
        int serial;
        long[] route = new long[0];

        @Override public void mine(List<String> blockIds) { log.add("mine"); pathing = true; }
        @Override public void goTo(int x, int y, int z, int range) { log.add("goTo " + x + " " + y + " " + z); pathing = true; }
        @Override public void goToBlock(int x, int y, int z) { log.add("goToBlock"); pathing = true; }
        @Override public void cancel() {
            cancels++;
            pathing = false;
            // Baritone's cancel-everything takes control from the plan process (onLostControl): a running plan ends.
            if (planState == PlanState.CALCULATING) {
                planState = PlanState.NONE;
            }
        }
        @Override public boolean isMining() { return pathing; }
        @Override public boolean isGoalActive() { return pathing; }
        @Override public boolean isInGoal() { return false; }
        /** An executor exists but no process (job) is active: what a plan calculation can leave for a tick. */
        boolean executorOnly;

        @Override public boolean isPathing() { return pathing || executorOnly; }
        @Override public long firstPathPos(java.util.function.LongPredicate test) { return Long.MIN_VALUE; }
        @Override public void configure() { }
        @Override public void clearArea(int x1, int y1, int z1, int x2, int y2, int z2) { log.add("clear"); pathing = true; }
        @Override public boolean isBuilding() { return false; }
        @Override public void setLegitMining(boolean legit, int yLevel) { }

        @Override public void planTo(int x, int y, int z, int range) {
            log.add("planTo " + x + " " + y + " " + z + " r" + range);
            planState = PlanState.CALCULATING;
            route = new long[0];
        }
        @Override public void planAway(int x, int y, int z, int distance) {
            log.add("planAway " + x + " " + y + " " + z + " d" + distance);
            planState = PlanState.CALCULATING;
            route = new long[0];
        }
        @Override public PlanState planState() { return planState; }
        @Override public int planSerial() { return serial; }
        @Override public int copyPlannedPath(long[] out) {
            if (planState != PlanState.READY) {
                return 0;
            }
            int n = Math.min(route.length, out.length);
            System.arraycopy(route, 0, out, 0, n);
            return n;
        }
        @Override public void cancelPlan() {
            planCancels++;
            planState = PlanState.NONE;
            route = new long[0];
        }

        /** The calculation finished (what the bridge's plan process does on the tick the executor appears). */
        void finishWith(long... positions) {
            route = positions.clone();
            serial++;
            planState = PlanState.READY;
        }

        void failCalc() {
            planState = PlanState.FAILED;
        }
    }

    private ActionArbiter arbiter;
    private PlanFakeBackend backend;
    private BaritoneAdapter adapter;
    private IRoutePlanner planner;

    @BeforeEach
    void setUp() {
        Services.resetForTests();
        arbiter = new ActionArbiter();
        SuiteEventBus events = new SuiteEventBus();
        arbiter.bind(new InputSmoother(new FakeInputBackend(), new Random(1)), null, events);
        Services.registerArbiter(arbiter);
        Services.registerEvents(events);
        backend = new PlanFakeBackend();
        adapter = new BaritoneAdapter((dim, pos) -> false, backend);
        adapter.subscribeStopAll(events);
        planner = adapter.routePlanner();
        Services.registerNavigator(adapter.navigator());
        Services.registerRoutePlanner(planner);
    }

    @AfterEach
    void tearDown() {
        Services.resetForTests();
    }

    private static long[] route(int n) {
        long[] r = new long[n];
        for (int i = 0; i < n; i++) {
            r[i] = PackedPos.pack(i, 64, -i);
        }
        return r;
    }

    @Test
    void lifecycleNoneCalculatingReady() {
        assertTrue(planner.isAvailable());
        assertEquals(PlanState.NONE, planner.state());
        assertEquals(0, planner.copyPath(new long[8]), "nothing ready yet");

        assertTrue(planner.planTo(10, 64, 20, 2));
        assertEquals(List.of("planTo 10 64 20 r2"), backend.log);
        assertEquals(PlanState.CALCULATING, planner.state());
        assertEquals(0, planner.copyPath(new long[8]), "no route while calculating");
        assertEquals(0, backend.cancels, "planning never cancels Baritone");

        backend.finishWith(route(5));
        assertEquals(PlanState.READY, planner.state());
        long[] out = new long[8];
        assertEquals(5, planner.copyPath(out));
        assertArrayEquals(route(5), java.util.Arrays.copyOf(out, 5));
        assertTrue(planner.isAvailable(), "a ready plan leaves the planner available");
    }

    @Test
    void lifecycleFailed() {
        assertTrue(planner.planAway(0, 64, 0, 24));
        assertEquals(List.of("planAway 0 64 0 d24"), backend.log);
        backend.failCalc();
        assertEquals(PlanState.FAILED, planner.state());
        assertEquals(0, planner.copyPath(new long[4]));
    }

    @Test
    void serialBumpsOncePerNewRoute() {
        int s0 = planner.planSerial();
        planner.planTo(1, 64, 1, 0);
        assertEquals(s0, planner.planSerial(), "no bump while calculating");
        backend.finishWith(route(3));
        int s1 = planner.planSerial();
        assertEquals(s0 + 1, s1);
        // Polling does not bump.
        planner.state();
        planner.copyPath(new long[3]);
        assertEquals(s1, planner.planSerial());
        planner.planTo(2, 64, 2, 0);
        assertEquals(s1, planner.planSerial());
        backend.finishWith(route(4));
        assertEquals(s1 + 1, planner.planSerial());
    }

    @Test
    void copyPathTruncatesToOutLength() {
        planner.planTo(1, 64, 1, 1);
        backend.finishWith(route(10));
        long[] small = new long[4];
        assertEquals(4, planner.copyPath(small));
        assertArrayEquals(java.util.Arrays.copyOf(route(10), 4), small);
        assertEquals(0, planner.copyPath(new long[0]));
    }

    @Test
    void unavailableWhileAJobRuns() {
        adapter.gotoCoord(PackedPos.pack(50, 64, 50), 1); // a goal-engine travel command
        assertFalse(planner.isAvailable());
        assertFalse(planner.planTo(1, 64, 1, 1), "refused");
        assertFalse(planner.planAway(1, 64, 1, 10), "refused");
        assertFalse(backend.log.stream().anyMatch(s -> s.startsWith("plan")), "nothing started");
        assertEquals(PlanState.NONE, planner.state());

        int before = backend.cancels;
        planner.cancelPlan();
        assertEquals(before, backend.cancels, "cancelPlan never cancels the job's path");
        assertTrue(backend.pathing, "the job still runs");
    }

    @Test
    void unavailableWhilePausedJobAndWhileBaritoneIsBusyOnItsOwn() {
        adapter.mineByYield("minecraft:coal", 8, List.of("minecraft:coal_ore"));
        adapter.pause();
        assertFalse(planner.isAvailable(), "a paused job keeps the planner unavailable");
        adapter.cancel();
        assertTrue(planner.isAvailable());

        backend.pathing = true; // Baritone executes something the adapter did not start (e.g. a typed command)
        assertFalse(planner.isAvailable());
    }

    @Test
    void unavailableWithoutBackend() {
        BaritoneAdapter none = new BaritoneAdapter((dim, pos) -> false, null);
        IRoutePlanner p = none.routePlanner();
        assertFalse(p.isAvailable());
        assertFalse(p.planTo(0, 64, 0, 1));
        assertEquals(PlanState.NONE, p.state());
        assertEquals(0, p.planSerial());
        assertEquals(0, p.copyPath(new long[4]));
        p.cancelPlan(); // no throw
    }

    @Test
    void cancelPlanClearsPlanWithoutCancellingBaritone() {
        planner.planTo(3, 64, 3, 0);
        planner.cancelPlan();
        assertEquals(PlanState.NONE, planner.state());
        assertEquals(0, backend.cancels);

        planner.planTo(3, 64, 3, 0);
        backend.finishWith(route(3));
        planner.cancelPlan();
        assertEquals(PlanState.NONE, planner.state());
        assertEquals(0, planner.copyPath(new long[3]), "route dropped");
        assertEquals(0, backend.cancels);
    }

    @Test
    void stopAllClearsPlan() {
        planner.planTo(3, 64, 3, 0);
        backend.finishWith(route(3));
        arbiter.stopAll("test");
        assertEquals(PlanState.NONE, planner.state());
        assertEquals(0, planner.copyPath(new long[3]));
        assertTrue(backend.planCancels > 0);
    }

    @Test
    void newRequestReplacesRunningPlan() {
        planner.planTo(1, 64, 1, 0);
        assertTrue(planner.isAvailable(), "a running plan does not block a replacement");
        assertTrue(planner.planAway(5, 64, 5, 16));
        assertEquals(List.of("planTo 1 64 1 r0", "planAway 5 64 5 d16"), backend.log);
        assertEquals(PlanState.CALCULATING, planner.state());
        assertEquals(0, backend.cancels, "replacing a plan never cancels Baritone");
    }

    @Test
    void jobStartReplacesPlan() {
        planner.planTo(1, 64, 1, 0);
        backend.finishWith(route(2));
        adapter.gotoCoord(PackedPos.pack(9, 64, 9), 1);
        assertEquals(PlanState.NONE, planner.state(), "the job drops the plan-only route");
        assertTrue(backend.pathing);
    }

    @Test
    void momentaryPathingDuringOwnCalculationKeepsPlannerAvailable() {
        planner.planTo(1, 64, 1, 0);
        backend.executorOnly = true; // the executor a plan calculation leaves for at most a tick
        assertTrue(planner.isAvailable());
        backend.finishWith(route(2));
        assertFalse(planner.isAvailable(), "Baritone pathing with no plan running means someone else drives");
    }

    @Test
    void pollingAndCopyAreAllocationFree() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        planner.planTo(1, 64, 1, 0);
        backend.finishWith(route(48));
        long[] out = new long[32];
        long[] sink = new long[1];
        long bytes = AllocationProbe.measure(100_000, () -> {
            if (planner.isAvailable() && planner.state() == PlanState.READY) {
                sink[0] += planner.planSerial() + planner.copyPath(out) + out[31];
            }
        });
        assertTrue(bytes < 4096, "polling allocated " + bytes + " bytes");
        assertEquals(32, planner.copyPath(out));
    }
}
