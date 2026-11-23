package com.utilitysuite.travel.pursuit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IInputSmoother;
import com.utilitysuite.core.api.IRoutePlanner;
import com.utilitysuite.core.api.IRouteRunner;
import com.utilitysuite.core.api.RotationTarget;
import com.utilitysuite.core.api.SimKey;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class RouteRunnerCoreTest {

    static final class Lease implements ControlLease {
        final long id;
        boolean active = true;
        final Set<ControlChannel> channels = EnumSet.of(ControlChannel.MOVEMENT, ControlChannel.CAMERA);

        Lease(long id) {
            this.id = id;
        }

        public long id() {
            return id;
        }

        public String moduleId() {
            return "test";
        }

        public ControlPriority priority() {
            return ControlPriority.COMBAT;
        }

        public Set<ControlChannel> channels() {
            return channels;
        }

        public boolean covers(ControlChannel c) {
            return channels.contains(c);
        }

        public boolean isActive() {
            return active;
        }

        public void close() {
            active = false;
        }
    }

    static final class Body implements BodySource {
        double x, y = 64, z;
        boolean present = true;

        public boolean refresh() {
            return present;
        }

        public boolean available() {
            return present;
        }

        public double x() {
            return x;
        }

        public double y() {
            return y;
        }

        public double z() {
            return z;
        }

        public float yaw() {
            return 0f;
        }

        public float health() {
            return 20f;
        }
    }

    static final class Steer implements Steering {
        int forward = 1, strafe;
        boolean jump, sprint = true, finished, offRoute, hasRoute;
        float yaw;
        int setRouteCalls;

        public void setRoute(long[] p, int n) {
            setRouteCalls++;
            hasRoute = n > 0;
            offRoute = false;
        }

        public void clear() {
            hasRoute = false;
        }

        public boolean hasRoute() {
            return hasRoute;
        }

        public void update(float yaw, float health) {
        }

        public int forward() {
            return forward;
        }

        public int strafe() {
            return strafe;
        }

        public boolean jump() {
            return jump;
        }

        public boolean sprint() {
            return sprint;
        }

        public float wantYaw() {
            return yaw;
        }

        public boolean finished() {
            return finished;
        }

        public boolean offRoute() {
            return offRoute;
        }
    }

    static final class Input implements IInputSmoother {
        final boolean[] down = new boolean[SimKey.all().length];
        int keyCalls, rotCalls, clearCalls;
        boolean refuse;
        ControlLease lastLease;
        boolean wrongLease;
        ControlLease expected;

        public boolean setRotationTarget(ControlLease l, RotationTarget t) {
            rotCalls++;
            return true;
        }

        public void clearRotation(ControlLease l) {
            clearCalls++;
        }

        public boolean isRotationSettled(float tol) {
            return true;
        }

        public boolean setServerPose(ControlLease l, Boolean sneak, Boolean sprint) {
            return true;
        }

        public boolean setKeyHeld(ControlLease l, SimKey key, boolean d) {
            keyCalls++;
            if (expected != null && l != expected) {
                wrongLease = true;
            }
            if (refuse) {
                return false;
            }
            down[key.ordinal()] = d;
            return true;
        }

        public boolean scheduleKeyTap(ControlLease l, SimKey k, int a, int b) {
            return true;
        }

        public boolean isKeyHeldByAutomation(SimKey k) {
            return down[k.ordinal()];
        }

        public boolean selectHotbarSlot(ControlLease l, int s) {
            return true;
        }

        public double nextGaussianDelayMs(double m, double s, double lo, double hi) {
            return m;
        }

        public int nextGaussianTicks(double m, double s, int lo, int hi) {
            return (int) m;
        }

        public void releaseFor(ControlLease l) {
        }

        public void releaseAll() {
        }

        boolean anyDown() {
            for (boolean b : down) {
                if (b) {
                    return true;
                }
            }
            return false;
        }
    }

    static final class Planner implements IRoutePlanner {
        boolean available = true;
        int planToCalls, planAwayCalls, cancelCalls;
        PlanState state = PlanState.NONE;
        int serial;
        int pathLen = 5;

        public boolean isAvailable() {
            return available;
        }

        public boolean planTo(int x, int y, int z, int r) {
            planToCalls++;
            state = PlanState.CALCULATING;
            return true;
        }

        public boolean planAway(int x, int y, int z, int d) {
            planAwayCalls++;
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
            return Math.min(pathLen, out.length);
        }

        public void cancelPlan() {
            cancelCalls++;
            state = PlanState.NONE;
        }

        void ready() {
            serial++;
            state = PlanState.READY;
        }
    }

    final Body body = new Body();
    final Steer steer = new Steer();
    final Input input = new Input();
    final Planner planner = new Planner();
    final int[] fleeCells = {0};
    final RouteRunnerCore core = new RouteRunnerCore(body, steer, (x, y, z, out) -> fleeCells[0]);
    final Lease lease = new Lease(1);

    RouteRunnerCoreTest() {
        core.bind(input, planner);
        input.expected = lease;
    }

    @Test
    void stateMachineIdlePlanningRunningArrived() {
        assertEquals(IRouteRunner.State.IDLE, core.state());
        assertTrue(core.pursue(lease, 40, 64, 0, 2, false));
        assertEquals(IRouteRunner.State.PLANNING, core.state());
        core.tick();
        assertEquals(IRouteRunner.State.PLANNING, core.state()); // far: waiting for the planner
        assertEquals(1, planner.planToCalls);
        assertTrue(input.down[SimKey.FORWARD.ordinal()]); // moves on the local plan meanwhile
        planner.ready();
        core.tick();
        assertEquals(IRouteRunner.State.RUNNING, core.state());
        assertTrue(core.isRunning(lease));
        body.x = 39;
        core.tick();
        assertEquals(IRouteRunner.State.ARRIVED, core.state());
        assertFalse(input.anyDown());
        assertFalse(input.wrongLease);
    }

    @Test
    void deadLeaseFails() {
        core.pursue(lease, 5, 64, 0, 1, false);
        core.tick();
        lease.active = false;
        core.tick();
        assertEquals(IRouteRunner.State.FAILED, core.state());
    }

    @Test
    void refusedKeyFails() {
        core.pursue(lease, 5, 64, 0, 1, false);
        input.refuse = true;
        core.tick();
        assertEquals(IRouteRunner.State.FAILED, core.state());
    }

    @Test
    void replanIsThrottled() {
        core.pursue(lease, 100, 64, 0, 2, false);
        double tx = 100;
        for (int t = 0; t < 200; t++) {
            tx += 0.5; // drifts 100 blocks over the run, far more than 3 blocks every few ticks
            core.pursue(lease, tx, 64, 0, 2, false);
            core.tick();
        }
        assertTrue(planner.planToCalls >= 2, "drift must replan");
        assertTrue(planner.planToCalls <= 200 / RouteRunnerCore.MIN_REPLAN_TICKS + 1, "calls=" + planner.planToCalls);
    }

    @Test
    void plannerNullUsesLocalPlan() {
        core.bind(input, null);
        core.pursue(lease, 40, 64, 0, 2, false);
        core.tick();
        assertEquals(IRouteRunner.State.RUNNING, core.state());
        assertTrue(steer.setRouteCalls >= 1);
        assertTrue(input.down[SimKey.FORWARD.ordinal()]);
    }

    @Test
    void plannerNeverCalledInsideTwelveBlocks() {
        core.pursue(lease, 8, 64, 0, 1, false);
        for (int t = 0; t < 100; t++) {
            core.pursue(lease, 8 + (t % 5), 64, 0, 1, false);
            core.tick();
        }
        assertEquals(0, planner.planToCalls);
        assertEquals(0, planner.planAwayCalls);
        assertEquals(IRouteRunner.State.RUNNING, core.state());
    }

    @Test
    void fleeUsesPlannerWhenFarFromGoalAndLocalPlanOtherwise() {
        fleeCells[0] = 3;
        core.flee(lease, 2, 64, 0, 40, false);
        core.tick();
        assertEquals(1, planner.planAwayCalls);
        core.stop(lease);
        planner.planAwayCalls = 0;
        core.flee(lease, 2, 64, 0, 10, false); // only 8 blocks left to gain
        core.tick();
        assertEquals(0, planner.planAwayCalls);
        assertEquals(IRouteRunner.State.RUNNING, core.state());
        body.x = -20;
        core.tick();
        assertEquals(IRouteRunner.State.ARRIVED, core.state());
    }

    @Test
    void fleeWithoutAnyPlanFails() {
        fleeCells[0] = 0;
        core.bind(input, null);
        core.flee(lease, 2, 64, 0, 10, false);
        core.tick();
        assertEquals(IRouteRunner.State.FAILED, core.state());
    }

    @Test
    void keysReleasedOnStopThroughTheGivenLease() {
        core.pursue(lease, 8, 64, 0, 1, true);
        core.tick();
        assertTrue(input.anyDown());
        core.stop(lease);
        assertFalse(input.anyDown());
        assertEquals(IRouteRunner.State.IDLE, core.state());
        assertEquals(1, input.clearCalls);
        assertFalse(input.wrongLease);
    }

    @Test
    void foreignLeaseStopIgnored() {
        core.pursue(lease, 8, 64, 0, 1, false);
        core.tick();
        core.stop(new Lease(2));
        assertEquals(IRouteRunner.State.RUNNING, core.state());
        assertTrue(input.anyDown());
        assertFalse(core.isRunning(new Lease(2)));
    }

    @Test
    void failAndArriveCancelThePlan() {
        core.pursue(lease, 40, 64, 0, 2, false);
        core.tick();
        lease.active = false;
        core.tick();
        assertEquals(1, planner.cancelCalls);
        assertFalse(input.anyDown());
    }

    @Test
    void cameraOnlyWhenLookAlongRoute() {
        core.pursue(lease, 8, 64, 0, 1, false);
        core.tick();
        assertEquals(0, input.rotCalls);
        core.stop(lease);
        steer.yaw = 90f;
        core.pursue(lease, 8, 64, 0, 1, true);
        core.tick();
        core.tick();
        assertEquals(1, input.rotCalls); // unchanged yaw is not resent
        steer.yaw = 120f;
        core.tick();
        assertEquals(2, input.rotCalls);
    }

    @Test
    void noPlayerResets() {
        core.pursue(lease, 8, 64, 0, 1, false);
        core.tick();
        body.present = false;
        core.tick();
        assertEquals(IRouteRunner.State.IDLE, core.state());
        assertFalse(core.pursue(lease, 8, 64, 0, 1, false));
        assertFalse(core.isAvailable());
    }

    @Test
    void runningPursuitAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        core.bind(input, planner);
        core.pursue(lease, 8, 64, 0, 0.5, false);
        double[] j = {0};
        Runnable tick = () -> {
            j[0] = (j[0] + 0.37) % 6.0;
            core.pursue(lease, 20 + j[0], 64, 0, 0.5, false);
            core.tick();
            planner.state = IRoutePlanner.PlanState.NONE;
        };
        long bytes = AllocationProbe.measure(100_000, tick);
        assertTrue(bytes < 4096, "allocated " + bytes + " bytes in 100k ticks");
        assertTrue(core.isRunning(lease));
    }

    @Test
    void followRunsTheCallersRouteWithoutAPlannerAndArrivesAtItsEnd() {
        long[] route = {com.utilitysuite.travel.Packed.pack(0, 56, 0), com.utilitysuite.travel.Packed.pack(0, 57, 1),
                com.utilitysuite.travel.Packed.pack(0, 64, 9)};
        assertFalse(core.follow(lease, route, 1, true), "a one-cell route is no route");
        assertTrue(core.follow(lease, route, 3, true));
        assertEquals(IRouteRunner.State.RUNNING, core.state());
        assertEquals(1, steer.setRouteCalls);
        // the end cell is horizontally close: a pursuit would "arrive" at once; a follow runs until the route ends
        body.x = 0.5;
        body.z = 8.5;
        core.tick();
        assertEquals(IRouteRunner.State.RUNNING, core.state());
        assertTrue(input.down[SimKey.FORWARD.ordinal()]);
        assertTrue(input.rotCalls > 0, "looks along the route");
        assertEquals(0, planner.planToCalls, "never asks the planner");
        steer.finished = true;
        core.tick();
        assertEquals(IRouteRunner.State.ARRIVED, core.state());
        assertFalse(input.anyDown());
        assertFalse(input.wrongLease);
    }

    @Test
    void followFailsOffRouteAndAReplacementKeepsTheKeys() {
        long[] route = {com.utilitysuite.travel.Packed.pack(0, 64, 0), com.utilitysuite.travel.Packed.pack(5, 64, 0)};
        assertTrue(core.follow(lease, route, 2, false));
        core.tick();
        int keyCalls = input.keyCalls;
        assertTrue(core.follow(lease, route, 2, false)); // replaced while running
        assertEquals(keyCalls, input.keyCalls, "no key released on a replacement");
        assertEquals(IRouteRunner.State.RUNNING, core.state());
        steer.offRoute = true;
        core.tick();
        assertEquals(IRouteRunner.State.FAILED, core.state());
        assertFalse(input.anyDown());
    }
}
