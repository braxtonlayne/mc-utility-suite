package com.utilitysuite.world.lighting;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.world.BlockPosPacking;
import com.utilitysuite.world.render.LineSink;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnableRuleTest {

    @Test
    void darkValidFloorSpawnsAlways() {
        assertEquals(SpawnableRule.ALWAYS, SpawnableRule.classify(true, true, true, 0, 0, 0));
    }

    @Test
    void skyLitFloorSpawnsAtNightOnly() {
        assertEquals(SpawnableRule.NIGHT, SpawnableRule.classify(true, true, true, 0, 15, 0));
    }

    @Test
    void blockLightAboveThresholdIsSafe() {
        assertEquals(SpawnableRule.NONE, SpawnableRule.classify(true, true, true, 1, 0, 0));
        assertEquals(SpawnableRule.ALWAYS, SpawnableRule.classify(true, true, true, 7, 0, 7));
        assertEquals(SpawnableRule.NONE, SpawnableRule.classify(true, true, true, 8, 0, 7));
    }

    @Test
    void needsFloorAndTwoEmptyBlocks() {
        assertEquals(SpawnableRule.NONE, SpawnableRule.classify(false, true, true, 0, 0, 0));
        assertEquals(SpawnableRule.NONE, SpawnableRule.classify(true, false, true, 0, 0, 0));
        assertEquals(SpawnableRule.NONE, SpawnableRule.classify(true, true, false, 0, 0, 0));
    }

    // ---- scheduler ------------------------------------------------------------------------------------------------

    @Test
    void schedulerNeverExceedsBudgetAndCoversEverything() {
        ColumnScanScheduler s = new ColumnScanScheduler();
        int r = 10;
        int budget = 37;
        s.restart(100, -50, r);
        LongOpenHashSet seen = new LongOpenHashSet();
        int ticks = 0;
        while (s.passActive() && ticks < 1000) {
            s.beginTick(budget);
            int n = 0;
            while (s.next()) {
                n++;
                assertTrue(Math.abs(s.x() - 100) <= r && Math.abs(s.z() + 50) <= r);
                seen.add(((long) s.x() << 32) | (s.z() & 0xFFFFFFFFL));
            }
            assertTrue(n <= budget, "took " + n);
            ticks++;
        }
        int side = 2 * r + 1;
        assertEquals(side * side, seen.size());
        assertEquals((side * side + budget - 1) / budget, ticks);
        assertEquals(1, s.passes());
    }

    @Test
    void dirtyColumnsJumpTheQueueAndDeduplicate() {
        ColumnScanScheduler s = new ColumnScanScheduler();
        s.restart(0, 0, 4);
        s.markDirty(3, 3);
        s.markDirty(3, 3);
        s.markDirty(99, 99); // outside: ignored
        s.beginTick(2);
        assertTrue(s.next());
        assertEquals(3, s.x());
        assertEquals(3, s.z());
        assertTrue(s.next());
        assertEquals(-4, s.x()); // back to the pass, not the duplicate
        assertEquals(-4, s.z());
        assertTrue(!s.next());
    }

    @Test
    void schedulerTickDoesNotAllocate() {
        if (!AllocationProbe.supported()) {
            return;
        }
        ColumnScanScheduler s = new ColumnScanScheduler();
        long bytes = AllocationProbe.measure(20_000, () -> {
            s.restart(0, 0, 8);
            s.markDirty(1, 1);
            s.beginTick(64);
            while (s.next()) {
                // consume
            }
        });
        assertTrue(bytes < 1024, "allocated " + bytes);
    }

    @Test
    void crossEmitterDoesNotAllocate() {
        int n = 500;
        long[] pos = new long[n];
        byte[] kind = new byte[n];
        for (int i = 0; i < n; i++) {
            pos[i] = BlockPosPacking.pack(i % 20, 64, i / 20);
            kind[i] = (byte) (i % 2 == 0 ? SpawnableRule.ALWAYS : SpawnableRule.NIGHT);
        }
        int[] lines = new int[1];
        LineSink sink = (x1, y1, z1, x2, y2, z2, argb) -> lines[0]++;
        LightOverlayModule.emit(sink, pos, kind, n, 0.5, 64.0, 0.5);
        assertEquals(2 * n, lines[0]);
        if (!AllocationProbe.supported()) {
            return;
        }
        long bytes = AllocationProbe.measure(2_000, () -> LightOverlayModule.emit(sink, pos, kind, n, 0.5, 64.0, 0.5));
        assertTrue(bytes < 1024, "allocated " + bytes);
    }
}
