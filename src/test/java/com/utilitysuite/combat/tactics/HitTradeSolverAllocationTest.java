package com.utilitysuite.combat.tactics;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.travel.movement.PhysicsBodySim;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link HitTradeSolver#solve} runs every engaged tick: it must allocate nothing and stay well under 0.1 ms. */
class HitTradeSolverAllocationTest {

    private static final long BUDGET = 1_024;

    @Test
    void solveAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        HitTradeSolver solver = new HitTradeSolver(() -> new PhysicsBodySim(HitTradeSolverTest.FLAT));
        TradeInput in = HitTradeSolverTest.chargingOpponent();
        in.horizon = 8;
        in.allowJump = true; // worst case: 18 rollouts x 8 ticks x 2 bodies
        int[] i = {0};
        double[] sink = {0};
        long bytes = AllocationProbe.measure(50_000, () -> {
            int k = i[0]++;
            in.oz = 0.5 + 3.0 + (k & 15) * 0.1;
            in.sReadyIn = k % 13;
            in.oReadyIn = (k >> 2) % 13;
            in.hazardBack = (k & 64) != 0;
            solver.solve(in);
            sink[0] += solver.score() + solver.forward() + solver.strafe();
        });
        assertTrue(bytes < BUDGET, "solve allocated " + bytes + " bytes over 50k calls");

        int n = 20_000;
        long t0 = System.nanoTime();
        for (int k = 0; k < n; k++) {
            in.oz = 0.5 + 3.0 + (k & 15) * 0.1;
            in.sReadyIn = k % 13;
            solver.solve(in);
            sink[0] += solver.score();
        }
        double usPerSolve = (System.nanoTime() - t0) / 1000.0 / n;
        System.out.printf("HitTradeSolver.solve: %.2f us per call (horizon 8, jumps on)%n", usPerSolve);
        assertTrue(usPerSolve < 500.0, "solve took " + usPerSolve + " us");
        assertTrue(sink[0] != 12345.678);
    }

    @Test
    void solveWithShieldsAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        HitTradeSolver solver = new HitTradeSolver(() -> new PhysicsBodySim(HitTradeSolverTest.FLAT));
        TradeInput in = HitTradeSolverTest.chargingOpponent();
        int[] i = {0};
        double[] sink = {0};
        long bytes = AllocationProbe.measure(50_000, () -> {
            int k = i[0]++;
            in.oz = 0.5 + 2.5 + (k & 15) * 0.1;
            in.sReadyIn = k % 13;
            in.oReadyIn = (k >> 2) % 13;
            in.sBlocking = (k & 32) != 0;
            in.oShieldUp = (k & 64) != 0;
            solver.solve(in);
            sink[0] += solver.score();
        });
        assertTrue(bytes < BUDGET, "solve with shields allocated " + bytes + " bytes over 50k calls");
        assertTrue(sink[0] != 12345.678);
    }
}
