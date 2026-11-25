package com.utilitysuite.world.stealth;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.world.render.LineSink;
import com.utilitysuite.world.render.Shapes;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import com.utilitysuite.world.BlockPosPacking;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StealthMathTest {

    private static StealthMath.CellTest cells(LongOpenHashSet set) {
        return (x, y, z) -> set.contains(BlockPosPacking.pack(x, y, z));
    }

    @Test
    void endermanStareMatchesVanillaThreshold() {
        // Looking straight at him from 10 blocks: stare.
        assertTrue(StealthMath.endermanStare(0, 0, 1, 0, 0, 10));
        // Off by 1 block sideways at 10 blocks: dot = 10/sqrt(101) = 0.995 < 1 - 0.0025: no stare.
        assertFalse(StealthMath.endermanStare(0, 0, 1, 1, 0, 10));
        // Off by 0.5 at 10: dot 0.99875 > 0.9975: stare.
        assertTrue(StealthMath.endermanStare(0, 0, 1, 0.5, 0, 10));
        // Beyond 64 blocks never.
        assertFalse(StealthMath.endermanStare(0, 0, 1, 0, 0, 70));
    }

    @Test
    void gazeRadiusIsTheConeEdge() {
        for (double d : new double[] {4, 10, 32, 60}) {
            double r = StealthMath.endermanGazeRadius(d);
            assertEquals(Math.sqrt(0.05 * d), r, 0.02 * Math.sqrt(d));
            // Just inside and just outside the drawn radius flip the stare test.
            double inside = r * 0.95;
            double outside = r * 1.05;
            double along = Math.sqrt(d * d - inside * inside);
            assertTrue(StealthMath.endermanStare(0, 0, 1, inside, 0, along), "inside at " + d);
            along = Math.sqrt(d * d - outside * outside);
            assertFalse(StealthMath.endermanStare(0, 0, 1, outside, 0, along), "outside at " + d);
        }
    }

    @Test
    void fovConeAndLookVector() {
        double cos70 = Math.cos(Math.toRadians(70));
        // Yaw 0 looks toward +Z.
        assertEquals(1.0, StealthMath.lookZ(0, 0), 1e-9);
        assertEquals(-1.0, StealthMath.lookX(90, 0), 1e-9);
        assertTrue(StealthMath.inCone(0, 0, 1, 1, 0, 5, cos70, 32));
        assertTrue(StealthMath.inCone(0, 0, 1, 5, 0, 2, cos70, 32)); // 68 degrees off axis
        assertFalse(StealthMath.inCone(0, 0, 1, 5, 0, 1, cos70, 32)); // 79 degrees
        assertFalse(StealthMath.inCone(0, 0, 1, 0, 0, -5, cos70, 32)); // behind
        assertFalse(StealthMath.inCone(0, 0, 1, 0, 0, 40, cos70, 32)); // out of range
    }

    @Test
    void rayWalkVisitsCellsOnTheSegment() {
        LongOpenHashSet wall = new LongOpenHashSet();
        wall.add(BlockPosPacking.pack(5, 0, 0));
        assertTrue(StealthMath.blocked(0.5, 0.5, 0.5, 9.5, 0.5, 0.5, cells(wall)));
        assertFalse(StealthMath.blocked(0.5, 0.5, 0.5, 4.5, 0.5, 0.5, cells(wall)));
        assertFalse(StealthMath.blocked(0.5, 1.5, 0.5, 9.5, 1.5, 0.5, cells(wall)));
        // Diagonal through (3,3,0).
        LongOpenHashSet diag = new LongOpenHashSet();
        diag.add(BlockPosPacking.pack(3, 3, 0));
        assertTrue(StealthMath.blocked(0.5, 0.5, 0.5, 6.2, 6.4, 0.5, cells(diag)));
        // Negative direction.
        assertTrue(StealthMath.blocked(9.5, 0.5, 0.5, 0.5, 0.5, 0.5, cells(wall)));
    }

    @Test
    void woolWallOccludesVibrationsAndAGapDoesNot() {
        LongOpenHashSet wool = new LongOpenHashSet();
        for (int y = -3; y <= 3; y++) {
            for (int z = -3; z <= 3; z++) {
                wool.add(BlockPosPacking.pack(4, y, z));
            }
        }
        assertTrue(StealthMath.vibrationOccluded(0, 0, 0, 8, 0, 0, cells(wool)));
        wool.remove(BlockPosPacking.pack(4, 0, 0)); // hole on the line
        assertFalse(StealthMath.vibrationOccluded(0, 0, 0, 8, 0, 0, cells(wool)));
        assertFalse(StealthMath.vibrationOccluded(0, 0, 0, 0, 0, 8, cells(wool))); // wall not between
    }

    @Test
    void fanAndArrowEmitBoundedGeometry() {
        int[] n = new int[1];
        double[] maxR = new double[1];
        LineSink s = (x1, y1, z1, x2, y2, z2, c) -> {
            n[0]++;
            maxR[0] = Math.max(maxR[0], Math.sqrt(x2 * x2 + z2 * z2));
        };
        Shapes.fan(s, 0, 0, 0, 0f, (float) Math.toRadians(70), 16f, -1);
        assertEquals(Shapes.FAN_SEGMENTS + 2, n[0]);
        assertEquals(16.0, maxR[0], 1e-3);
        n[0] = 0;
        Shapes.arrow(s, 0, 0, 0, 10, 0, 0, 1f, -1);
        assertEquals(3, n[0]);
    }

    @Test
    void sectionSweepVisitsEverySectionOnce() {
        SectionSweep sw = new SectionSweep();
        sw.restart(10, 4, -3, 2, 1);
        LongOpenHashSet seen = new LongOpenHashSet();
        int n = 0;
        while (sw.next()) {
            assertTrue(Math.abs(sw.x() - 10) <= 2 && Math.abs(sw.y() - 4) <= 1 && Math.abs(sw.z() + 3) <= 2);
            seen.add(BlockPosPacking.pack(sw.x(), sw.y(), sw.z()));
            n++;
        }
        assertEquals(5 * 5 * 3, n);
        assertEquals(n, seen.size());
        assertTrue(sw.done());
        assertFalse(sw.next());
    }

    @Test
    void mathDoesNotAllocate() {
        if (!AllocationProbe.supported()) {
            return;
        }
        StealthMath.CellTest none = (x, y, z) -> false;
        LineSink s = (x1, y1, z1, x2, y2, z2, c) -> { };
        long bytes = AllocationProbe.measure(2_000, () -> {
            StealthMath.endermanStare(0, 0, 1, 0.3, 0.1, 12);
            StealthMath.vibrationOccluded(0, 0, 0, 7, 3, -2, none);
            StealthMath.inCone(0, 0, 1, 2, 0, 5, 0.34, 32);
            Shapes.fan(s, 0, 0, 0, 1f, 1.2f, 16f, -1);
            Shapes.dome(s, 0, 0, 0, 128f, -1);
        });
        assertTrue(bytes < 1024, "allocated " + bytes);
    }
}
