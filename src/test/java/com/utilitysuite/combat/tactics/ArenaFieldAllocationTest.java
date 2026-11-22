package com.utilitysuite.combat.tactics;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The arena read runs every engaged tick: recenter, scan, knock and both finds must allocate nothing. */
class ArenaFieldAllocationTest {

    private static final long BUDGET = 1_024;

    /** Lava east, a stair hill north-east, a low corridor west. */
    static final CombatVoxels MIXED = (x, y, z) -> {
        if (x >= 1 && x <= 4 && z >= -2 && z <= 0 && y == 63) {
            return CombatVoxels.LAVA;
        }
        if (x >= -6 && x <= -2 && z == 0 && y == 66) {
            return CombatVoxels.SOLID;
        }
        if (z >= 3 && z <= 6 && x >= 1 && x <= 4) {
            int top = z == 3 ? 64 : 65;
            return y <= top ? CombatVoxels.SOLID : CombatVoxels.AIR;
        }
        return y <= 63 ? CombatVoxels.SOLID : CombatVoxels.AIR;
    };

    @Test
    void arenaReadAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        ArenaField f = new ArenaField();
        int[] i = {0};
        double[] sink = {0};
        long bytes = AllocationProbe.measure(20_000, () -> {
            int k = i[0]++;
            f.recenter((k >> 6) & 1, 64, 0);
            f.scan(MIXED, 16);
            f.evalKnock(0.5, 64, 0.5);
            boolean a = f.findHighGround(-0.5, 64, 0.5, 0.5, 64, 0.5, 8);
            boolean b = f.findLowCeiling(-0.5, 64, 0.5, 2.5, 64, 0.5, 8);
            sink[0] += f.knockValue() + f.spotX() + (a ? 1 : 0) + (b ? 2 : 0) + f.coverage();
        });
        assertTrue(bytes < BUDGET, "arena read allocated " + bytes + " bytes over 20k iterations");

        ArenaField g = new ArenaField();
        g.recenter(0, 64, 0);
        g.scan(MIXED, ArenaField.SIZE * ArenaField.SIZE);
        int n = 20_000;
        long t0 = System.nanoTime();
        for (int k = 0; k < n; k++) {
            sink[0] += g.scan(MIXED, 16);
        }
        double usScan = (System.nanoTime() - t0) / 1000.0 / n;
        t0 = System.nanoTime();
        for (int k = 0; k < n; k++) {
            sink[0] += g.findHighGround(-0.5, 64, 0.5, 0.5, 64, 0.5, 8) ? 1 : 0;
        }
        double usHigh = (System.nanoTime() - t0) / 1000.0 / n;
        t0 = System.nanoTime();
        for (int k = 0; k < n; k++) {
            sink[0] += g.findLowCeiling(-0.5, 64, 0.5, 2.5, 64, 0.5, 8) ? 1 : 0;
        }
        double usLow = (System.nanoTime() - t0) / 1000.0 / n;
        t0 = System.nanoTime();
        for (int k = 0; k < n; k++) {
            sink[0] += g.evalKnock(0.5, 64, 0.5) ? 1 : 0;
        }
        double usKnock = (System.nanoTime() - t0) / 1000.0 / n;
        System.out.printf("ArenaField: scan(16) %.2f us, findHighGround %.2f us, findLowCeiling %.2f us,"
                + " evalKnock %.2f us%n", usScan, usHigh, usLow, usKnock);
        // generous ceilings so a busy build machine does not flake; targets are 30 us / 100 us
        assertTrue(usScan < 150.0, "scan(16) took " + usScan + " us");
        assertTrue(usHigh < 500.0 && usLow < 500.0, "finds took " + usHigh + " / " + usLow + " us");
        assertTrue(sink[0] != 12345.678);
    }
}
