package com.utilitysuite.travel.pursuit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.IRoutePlanner;
import com.utilitysuite.core.api.IRouteRunner;
import com.utilitysuite.travel.Packed;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Route replan on obstruction ({@link RouteRunnerCore}): a block turning solid on one of the next
 * {@link RouteRunnerCore#WATCH_CELLS} route cells, or a floor disappearing under one, replans on the next tick instead
 * of waiting for the cadence; changes elsewhere do nothing; obstruction replans are debounced; without a block-change
 * feed a slow poll catches the change. Engine-free.
 */
class RouteObstructionTest {

    /** Solid blocks of a flat lane along +x at feet y 64 (floor y 63), as a small primitive set. */
    static final class World implements CellProbe {
        final long[] solid = new long[256];
        int count;

        void set(int x, int y, int z, boolean on) {
            long p = Packed.pack(x, y, z);
            for (int i = 0; i < count; i++) {
                if (solid[i] == p) {
                    if (!on) {
                        solid[i] = solid[--count];
                    }
                    return;
                }
            }
            if (on) {
                solid[count++] = p;
            }
        }

        boolean solid(int x, int y, int z) {
            long p = Packed.pack(x, y, z);
            for (int i = 0; i < count; i++) {
                if (solid[i] == p) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public int cellBits(long cell) {
            int x = Packed.x(cell);
            int y = Packed.y(cell);
            int z = Packed.z(cell);
            int bits = 0;
            if (!solid(x, y, z) && !solid(x, y + 1, z)) {
                bits |= CLEAR;
            }
            if (solid(x, y - 1, z)) {
                bits |= FLOOR;
            }
            return bits;
        }
    }

    /** A follower stand-in that keeps the route's cells and a settable progress index. */
    static final class Steer implements Steering {
        final long[] cells = new long[256];
        int length;
        int progress;
        boolean hasRoute;
        int setRouteCalls;

        public void setRoute(long[] p, int n) {
            System.arraycopy(p, 0, cells, 0, n);
            length = n;
            progress = 0;
            hasRoute = n > 0;
            setRouteCalls++;
        }

        public void clear() {
            hasRoute = false;
            length = 0;
        }

        public boolean hasRoute() {
            return hasRoute;
        }

        public void update(float yaw, float health) {
        }

        public int forward() {
            return 1;
        }

        public int strafe() {
            return 0;
        }

        public boolean jump() {
            return false;
        }

        public boolean sprint() {
            return true;
        }

        public float wantYaw() {
            return 0f;
        }

        public boolean finished() {
            return false;
        }

        public boolean offRoute() {
            return false;
        }

        @Override
        public int progressIndex() {
            return hasRoute ? progress : -1;
        }

        @Override
        public int length() {
            return length;
        }

        @Override
        public long cell(int i) {
            return cells[i];
        }
    }

    /** A planner whose READY path is the 41-cell lane x = 0..40 at y 64, z 0. */
    static final class Planner implements IRoutePlanner {
        int planToCalls;
        PlanState state = PlanState.NONE;
        int serial;

        public boolean isAvailable() {
            return true;
        }

        public boolean planTo(int x, int y, int z, int r) {
            planToCalls++;
            state = PlanState.CALCULATING;
            return true;
        }

        public boolean planAway(int x, int y, int z, int d) {
            state = PlanState.CALCULATING;
            return true;
        }

        public PlanState state() {
            return state;
        }

        public int planSerial() {
            return serial;
        }

        public int copyPath(long[] out) {
            for (int i = 0; i <= 40; i++) {
                out[i] = Packed.pack(i, 64, 0);
            }
            return 41;
        }

        public void cancelPlan() {
            state = PlanState.NONE;
        }

        void ready() {
            serial++;
            state = PlanState.READY;
        }
    }

    final World world = new World();
    final RouteRunnerCoreTest.Body body = new RouteRunnerCoreTest.Body();
    final Steer steer = new Steer();
    final RouteRunnerCoreTest.Input input = new RouteRunnerCoreTest.Input();
    final Planner planner = new Planner();
    final RouteRunnerCore core = new RouteRunnerCore(body, steer, (x, y, z, out) -> 0, world);
    final RouteRunnerCoreTest.Lease lease = new RouteRunnerCoreTest.Lease(1);

    RouteObstructionTest() {
        core.bind(input, planner);
        core.setBlockFeedBound(true);
        for (int x = -2; x <= 42; x++) {
            world.set(x, 63, 0, true);
        }
    }

    /** Starts a far pursuit and runs until it walks the planner's lane, the body at route cell {@code progress}. */
    private void runningOnTheLane(int progress) {
        assertTrue(core.pursue(lease, 40.5, 64, 0.5, 1, false));
        core.tick(); // far: the planner is asked, the local stopgap meanwhile
        planner.ready();
        core.tick(); // the lane arrives
        assertEquals(IRouteRunner.State.RUNNING, core.state());
        steer.progress = progress;
        core.tick(); // the window ahead of the body is read as it is
    }

    /** Changes a block, tells the core (as the feed does) and runs one tick. */
    private void change(int x, int y, int z, boolean solid) {
        world.set(x, y, z, solid);
        core.onBlockChanged(Packed.pack(x, y, z));
        core.tick();
    }

    @Test
    void aBlockTurningSolidOnACellAheadReplansOnTheNextTick() {
        runningOnTheLane(2);
        int calls = planner.planToCalls;
        change(4, 64, 0, true); // feet of route cell 4 (watched: 3..6), two ticks after the last plan
        assertEquals(calls + 1, planner.planToCalls, "replanned at once, not on the cadence");
        assertEquals(1, core.obstructionReplans());
        assertEquals(IRouteRunner.State.PLANNING, core.state(), "the obstructed lane is not walked on meanwhile");
    }

    @Test
    void aHeadBlockAndAVanishingFloorAlsoReplan() {
        runningOnTheLane(2);
        change(5, 65, 0, true); // head height of cell 5
        assertEquals(1, core.obstructionReplans());
        planner.ready();
        core.tick();
        steer.progress = 2;
        for (int t = 0; t < RouteRunnerCore.OBSTRUCTION_DEBOUNCE_TICKS; t++) {
            core.tick();
        }
        change(5, 65, 0, false);
        change(6, 63, 0, false); // the floor under cell 6 is gone
        assertEquals(2, core.obstructionReplans());
    }

    @Test
    void changesBeyondTheWindowOrOffTheRouteDoNothing() {
        runningOnTheLane(2);
        int calls = planner.planToCalls;
        change(20, 64, 0, true); // on the route, far ahead
        change(4, 64, 3, true); // beside the route
        change(4, 67, 0, true); // above the head
        change(1, 64, 0, true); // behind the body
        change(4, 63, 0, true); // the floor was already there: no change of what the cell offers
        assertEquals(calls, planner.planToCalls);
        assertEquals(0, core.obstructionReplans());
    }

    @Test
    void aGapTheRouteJumpsIsNoObstruction() {
        world.set(5, 63, 0, false); // the route crosses a one-block gap at cell 5 (no floor from the start)
        runningOnTheLane(2);
        change(5, 62, 0, false); // something under the gap changes
        world.set(5, 63, 0, false);
        core.onBlockChanged(Packed.pack(5, 63, 0));
        core.tick();
        assertEquals(0, core.obstructionReplans());
    }

    @Test
    void aFlickeringBlockIsDebounced() {
        runningOnTheLane(2);
        int ticks = 60;
        for (int t = 0; t < ticks; t++) {
            if (planner.state == IRoutePlanner.PlanState.CALCULATING) {
                planner.ready(); // the planner keeps handing back the same lane
            }
            steer.progress = 2;
            change(4, 64, 0, (t & 1) == 0);
        }
        int replans = core.obstructionReplans();
        assertTrue(replans >= 2, "still reacts: " + replans);
        assertTrue(replans <= ticks / RouteRunnerCore.OBSTRUCTION_DEBOUNCE_TICKS + 1, "replans " + replans);
    }

    @Test
    void withoutAFeedASlowPollFindsIt() {
        core.setBlockFeedBound(false);
        runningOnTheLane(2);
        world.set(4, 64, 0, true); // no event
        for (int t = 0; t < RouteRunnerCore.POLL_TICKS && core.obstructionReplans() == 0; t++) {
            core.tick();
        }
        assertEquals(1, core.obstructionReplans());
    }

    @Test
    void theFeedBoundWatchDoesNotPoll() {
        runningOnTheLane(2);
        world.set(4, 64, 0, true); // no event: with a feed bound, nothing reads the cells again
        for (int t = 0; t < 3 * RouteRunnerCore.POLL_TICKS; t++) {
            core.tick();
        }
        assertEquals(0, core.obstructionReplans());
    }

    @Test
    void watchingAndObstructionReplansAllocateNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        runningOnTheLane(2);
        int[] t = {0};
        Runnable tick = () -> {
            if (planner.state == IRoutePlanner.PlanState.CALCULATING) {
                planner.ready();
            }
            steer.progress = 2 + (t[0] % 3);
            boolean on = (t[0]++ & 1) == 0;
            world.set(6, 64, 0, on);
            core.onBlockChanged(Packed.pack(6, 64, 0));
            core.onBlockChanged(Packed.pack(30, 64, 0));
            core.tick();
        };
        long bytes = AllocationProbe.measure(100_000, tick);
        assertTrue(bytes < 4096, "allocated " + bytes + " bytes in 100k ticks");
        assertTrue(core.obstructionReplans() > 0);
    }
}
