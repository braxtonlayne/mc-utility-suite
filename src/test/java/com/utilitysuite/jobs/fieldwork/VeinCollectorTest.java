package com.utilitysuite.jobs.fieldwork;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.jobs.planner.PackedPos;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VeinCollectorTest {

    private static VeinCollector.Lookup in(LongOpenHashSet blocks) {
        return (x, y, z) -> blocks.contains(PackedPos.pack(x, y, z));
    }

    private static void runToEnd(VeinCollector c, VeinCollector.Lookup l, int budget) {
        for (int i = 0; i < 10_000 && !c.step(l, budget); i++) {
            // spread over "ticks"
        }
    }

    @Test
    void capIsRespected() {
        LongOpenHashSet blocks = new LongOpenHashSet();
        for (int x = 0; x < 20; x++) {
            for (int y = 0; y < 20; y++) {
                blocks.add(PackedPos.pack(x, y, 0));
            }
        }
        VeinCollector c = new VeinCollector();
        c.begin(PackedPos.pack(0, 0, 0), false, 50);
        runToEnd(c, in(blocks), 30);
        assertEquals(50, c.size());
        assertTrue(c.isCapped());
    }

    @Test
    void budgetSpreadsTheScanOverTicks() {
        LongOpenHashSet blocks = new LongOpenHashSet();
        for (int x = 1; x < 10; x++) {
            blocks.add(PackedPos.pack(x, 0, 0));
        }
        VeinCollector c = new VeinCollector();
        c.begin(PackedPos.pack(0, 0, 0), false, 256);
        assertFalse(c.step(in(blocks), 26), "one neighbourhood per call at this budget");
        runToEnd(c, in(blocks), 26);
        assertEquals(9, c.size());
    }

    @Test
    void oresJoinDiagonallyAndOrderIsNearestFirst() {
        LongOpenHashSet blocks = new LongOpenHashSet();
        blocks.add(PackedPos.pack(1, 1, 1)); // corner neighbour of the start
        blocks.add(PackedPos.pack(2, 2, 2));
        blocks.add(PackedPos.pack(3, 3, 3));
        VeinCollector c = new VeinCollector();
        c.begin(PackedPos.pack(0, 0, 0), false, 256);
        runToEnd(c, in(blocks), 64);
        assertEquals(3, c.size());
        assertEquals(PackedPos.pack(3, 3, 3), c.nearest(3.5, 3.5, 3.5, 10));
        assertEquals(PackedPos.pack(1, 1, 1), c.nearest(0.5, 0.5, 0.5, 10));
        assertEquals(Long.MIN_VALUE, c.nearest(50, 50, 50, 4.5), "nothing within reach");
        c.remove(PackedPos.pack(1, 1, 1));
        assertEquals(PackedPos.pack(2, 2, 2), c.nearest(0.5, 0.5, 0.5, 10));
    }

    @Test
    void treesTakeDiagonalsUpButNotDown() {
        LongOpenHashSet blocks = new LongOpenHashSet();
        blocks.add(PackedPos.pack(0, 1, 0));   // trunk above the broken log
        blocks.add(PackedPos.pack(1, 2, 1));   // branch diagonally up
        blocks.add(PackedPos.pack(2, 1, 2));   // diagonally down from the branch: the next tree's log
        VeinCollector c = new VeinCollector();
        c.begin(PackedPos.pack(0, 0, 0), true, 256);
        runToEnd(c, in(blocks), 64);
        assertTrue(c.contains(PackedPos.pack(0, 1, 0)));
        assertTrue(c.contains(PackedPos.pack(1, 2, 1)));
        assertFalse(c.contains(PackedPos.pack(2, 1, 2)));
        // as an ore vein, the same diagonal joins
        c.begin(PackedPos.pack(0, 0, 0), false, 256);
        runToEnd(c, in(blocks), 64);
        assertTrue(c.contains(PackedPos.pack(2, 1, 2)));
    }

    @Test
    void tiesGoToTheLowerLog() {
        LongOpenHashSet blocks = new LongOpenHashSet();
        blocks.add(PackedPos.pack(0, 1, 0));
        blocks.add(PackedPos.pack(0, 2, 0));
        blocks.add(PackedPos.pack(0, 3, 0));
        VeinCollector c = new VeinCollector();
        c.begin(PackedPos.pack(0, 0, 0), true, 256);
        runToEnd(c, in(blocks), 64);
        // eye at y 2.5 is equally far from the logs at y 1 and y 3
        assertEquals(PackedPos.pack(0, 2, 0), c.nearest(0.5, 2.5, 0.5, 10));
        c.remove(PackedPos.pack(0, 2, 0));
        assertEquals(PackedPos.pack(0, 1, 0), c.nearest(0.5, 2.5, 0.5, 10));
    }

    @Test
    void perTickStepsDoNotAllocate() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        LongOpenHashSet blocks = new LongOpenHashSet();
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                blocks.add(PackedPos.pack(x, y, 0));
            }
        }
        VeinCollector c = new VeinCollector();
        VeinCollector.Lookup l = in(blocks);
        // warm the collections to their working size first
        c.begin(PackedPos.pack(0, 0, 0), false, 256);
        runToEnd(c, l, 1000);
        long bytes = AllocationProbe.measure(2_000, () -> {
            c.begin(PackedPos.pack(0, 0, 0), false, 256);
            while (!c.step(l, 64)) {
                // budgeted steps
            }
            c.nearest(3.5, 3.5, 0.5, 6);
        });
        assertTrue(bytes < 64 * 1024, "allocated " + bytes + " bytes over 2000 runs");
    }

    @Test
    void beginAtQueuesTheStandingStartBlockToo() {
        // Debris Blasting hand-off: the revealed block is still there and must be mined with its neighbours.
        LongOpenHashSet blocks = new LongOpenHashSet();
        blocks.add(PackedPos.pack(0, 15, 0));
        blocks.add(PackedPos.pack(1, 15, 0));
        VeinCollector c = new VeinCollector();
        c.beginAt(PackedPos.pack(0, 15, 0), false, 256);
        runToEnd(c, in(blocks), 64);
        assertEquals(2, c.size());
        assertTrue(c.contains(PackedPos.pack(0, 15, 0)));
        assertTrue(c.contains(PackedPos.pack(1, 15, 0)));

        c.begin(PackedPos.pack(0, 15, 0), false, 256);
        runToEnd(c, in(blocks), 64);
        assertEquals(1, c.size(), "begin (user already broke the start) leaves the start out");
    }
}
