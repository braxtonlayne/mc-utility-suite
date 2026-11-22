package com.utilitysuite.combat.tactics;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.travel.movement.PhysicsBodySim;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ComboKeeper#begin} runs on every hit we land and {@link ComboKeeper#step} every tick after: no garbage. */
class ComboKeeperAllocationTest {

    private static final long BUDGET = 1_024;

    @Test
    void beginAndStepAllocateNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        ComboKeeper keeper = new ComboKeeper(() -> new PhysicsBodySim(HitTradeSolverTest.FLAT));
        TradeInput in = ComboKeeperTest.sprintingIn(2.8);
        int[] i = {0};
        double[] sink = {0};
        long bytes = AllocationProbe.measure(50_000, () -> {
            int k = i[0]++;
            if ((k & 7) == 0) {
                in.ox = 0.5;
                in.oz = 0.5 + 2.2 + (k >> 3 & 7) * 0.12;
                in.oReadyIn = (k >> 6) % 13;
                in.oOnGround = (k & 512) == 0;
                in.oy = in.oOnGround ? HitTradeSolverTest.FLOOR_Y : HitTradeSolverTest.FLOOR_Y + 0.5;
                keeper.begin(in, (k & 8) != 0, (k & 16) != 0);
            } else {
                in.oz += 0.05;
                keeper.step(in);
            }
            sink[0] += keeper.forward() + keeper.hitInTicks() + keeper.landZ();
        });
        assertTrue(bytes < BUDGET, "begin/step allocated " + bytes + " bytes over 50k calls");

        int n = 5_000;
        long t0 = System.nanoTime();
        for (int k = 0; k < n; k++) {
            in.ox = 0.5;
            in.oy = HitTradeSolverTest.FLOOR_Y;
            in.oOnGround = true;
            in.oz = 0.5 + 2.2 + (k & 7) * 0.12;
            in.oReadyIn = k % 13;
            keeper.begin(in, true, true);
            sink[0] += keeper.hitInTicks();
        }
        double usPerBegin = (System.nanoTime() - t0) / 1000.0 / n;
        System.out.printf("ComboKeeper.begin: %.2f us per call (sprint hit, back taps allowed)%n", usPerBegin);
        assertTrue(usPerBegin < 1000.0, "begin took " + usPerBegin + " us");
        assertTrue(sink[0] != 12345.678);
    }
}
