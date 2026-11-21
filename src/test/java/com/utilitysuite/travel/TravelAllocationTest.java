package com.utilitysuite.travel;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.travel.autopilot.BoatSteering;
import com.utilitysuite.travel.autopilot.BoatThrottle;
import com.utilitysuite.travel.autopilot.DutyCycle;
import com.utilitysuite.travel.autopilot.ElytraPitchController;
import com.utilitysuite.travel.autopilot.RocketPolicy;
import com.utilitysuite.travel.terrain.CollisionQuery;
import com.utilitysuite.travel.terrain.CorrectionCounter;
import com.utilitysuite.travel.terrain.EdgeClamp;
import com.utilitysuite.travel.terrain.FootprintSupportProbe;
import com.utilitysuite.travel.terrain.HazardScanner;
import com.utilitysuite.travel.terrain.HazardWorld;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Performance mandate: the per-tick / per-move paths of the travel modules allocate nothing in steady state. */
class TravelAllocationTest {

    /** A few KB of slack for JIT/probe noise over 100k iterations; real per-call allocation would be MBs. */
    private static final long SLACK_BYTES = 16 * 1024;
    private static final int ITERATIONS = 100_000;

    private static final CollisionQuery CLIFF = (x, y, z, lo, hi) -> x <= 0 && y < 64 && y + 1.0 >= lo && y <= hi;

    private static final HazardWorld WORLD = new HazardWorld() {
        @Override
        public boolean solid(int x, int y, int z) {
            return x == 3 ? y < 50 : y < 64;
        }

        @Override
        public boolean lava(int x, int y, int z) {
            return x == -2 && z == 2 && y == 62;
        }

        @Override
        public boolean loaded(int x, int z) {
            return true;
        }
    };

    @BeforeAll
    static void supported() {
        Assumptions.assumeTrue(AllocationProbe.supported(), "thread allocation counters unavailable");
    }

    @Test
    void walkBridgePlanningIsAllocationFree() {
        com.utilitysuite.travel.bridge.WalkBridgePlanner planner = new com.utilitysuite.travel.bridge.WalkBridgePlanner();
        com.utilitysuite.travel.bridge.WalkBridgePlanner.Cells air = (x, y, z) -> x > 0;
        long bytes = AllocationProbe.measure(ITERATIONS, () -> {
            planner.plan(air, 0.7, 0.5, 0.28, 0.05, 64, 3, 0.6);
            planner.plan(air, 0.5, 0.5, 0.2, 0.2, 64, 6, 0.6);
        });
        assertTrue(bytes < SLACK_BYTES, "allocated " + bytes);
    }

    @Test
    void safeWalkClampIsAllocationFree() {
        EdgeClamp clamp = new EdgeClamp();
        FootprintSupportProbe probe = new FootprintSupportProbe();
        long bytes = AllocationProbe.measure(ITERATIONS, () -> {
            probe.configure(CLIFF, 0.9, 64, 0.2, 1.5, 0.8, 1.5);
            clamp.clamp(0.2, 0.1, probe);
            EdgeClamp.atEdge(probe, 0.28, 0.0, 1.5);
        });
        assertTrue(bytes < SLACK_BYTES, "allocated " + bytes);
    }

    @Test
    void hazardScanIsAllocationFreeOnceWarm() {
        HazardScanner scanner = new HazardScanner();
        scanner.configure(12, 96, 4);
        long bytes = AllocationProbe.measure(ITERATIONS, () -> scanner.tick(WORLD, 0, 64, 0));
        assertTrue(bytes < SLACK_BYTES, "allocated " + bytes);
    }

    @Test
    void flightAndBoatControlMathIsAllocationFree() {
        ElytraPitchController controller = new ElytraPitchController();
        RocketPolicy rockets = new RocketPolicy();
        DutyCycle duty = new DutyCycle();
        CorrectionCounter counter = new CorrectionCounter();
        double[] clearances = new double[BoatSteering.OFFSETS.length];
        BlockQuery wall = (x, y, z) -> x == 20;
        long[] tick = {0};
        long bytes = AllocationProbe.measure(ITERATIONS, () -> {
            controller.pitch(90, 100, -0.1, 1.2, false);
            rockets.shouldFire(90, 100, 1.2, false, 50, 10, 3);
            for (int i = 0; i < clearances.length; i++) {
                clearances[i] = GridRay.march(0.5, 62.5, 0.5, Angles.dirX(BoatSteering.OFFSETS[i] - 90), 0,
                        Angles.dirZ(BoatSteering.OFFSETS[i] - 90), 24, wall);
            }
            double heading = BoatSteering.chooseHeading(-90, clearances, 24, 100);
            duty.next(BoatThrottle.throttle(Angles.delta(-80, heading), 12, 60, 100, 0.5));
            counter.record(tick[0]++, 0.3);
        });
        assertTrue(bytes < SLACK_BYTES, "allocated " + bytes);
    }
}
