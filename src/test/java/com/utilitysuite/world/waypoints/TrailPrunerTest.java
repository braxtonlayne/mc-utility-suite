package com.utilitysuite.world.waypoints;

import com.utilitysuite.world.BlockPosPacking;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrailPrunerTest {

    private static long p(int x, int y, int z) {
        return BlockPosPacking.pack(x, y, z);
    }

    /** Points every 3 blocks along a straight line in x. */
    private static List<Long> line(int x0, int x1, int y, int z) {
        List<Long> out = new ArrayList<>();
        int step = x1 >= x0 ? 3 : -3;
        for (int x = x0; step > 0 ? x <= x1 : x >= x1; x += step) {
            out.add(p(x, y, z));
        }
        return out;
    }

    private static long[] arr(List<Long> l) {
        long[] a = new long[l.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = l.get(i);
        }
        return a;
    }

    @Test
    void aStraightTrailIsUnchanged() {
        long[] path = arr(line(0, 60, 64, 0));
        assertArrayEquals(path, TrailPruner.prune(path));
    }

    @Test
    void aLoopBackToAnEarlierSpotIsCut() {
        List<Long> l = new ArrayList<>(line(0, 30, 64, 0)); // out along x
        // loop: up z, back along x, down z to near (30, 0) again, then continue
        for (int z = 3; z <= 30; z += 3) {
            l.add(p(30, 64, z));
        }
        for (int z = 27; z >= 3; z -= 3) {
            l.add(p(33, 64, z));
        }
        int loopEnd = l.size();
        l.addAll(line(33, 60, 64, 1));
        long[] path = arr(l);
        long[] pruned = TrailPruner.prune(path);
        assertTrue(pruned.length < path.length - 15, "loop kept: " + pruned.length + " of " + path.length);
        assertEquals(path[0], pruned[0]);
        assertEquals(path[path.length - 1], pruned[pruned.length - 1]);
        for (long q : pruned) { // nothing from deep inside the loop survives
            assertTrue(BlockPosPacking.z(q) < 10, "walked the loop at " + BlockPosPacking.z(q));
        }
        assertTrue(loopEnd > 0);
    }

    @Test
    void aPassAboveOrBelowIsNotAShortcut() {
        List<Long> l = new ArrayList<>(line(0, 12, 64, 0));
        for (int y = 61; y >= 40; y -= 3) { // dig down
            l.add(p(12, y, 0));
        }
        l.addAll(line(9, 0, 40, 0)); // walk back underneath the start
        long[] path = arr(l);
        assertArrayEquals(path, TrailPruner.prune(path));
    }

    @Test
    void stepsOnlyGoForwardAndKeepEnds() {
        long[] path = {p(0, 64, 0), p(2, 64, 0), p(0, 64, 1), p(2, 64, 1), p(9, 64, 0)};
        long[] pruned = TrailPruner.prune(path);
        assertEquals(path[0], pruned[0]);
        assertEquals(path[4], pruned[pruned.length - 1]);
        assertTrue(pruned.length <= 3, "pruned to " + pruned.length);
    }

    @Test
    void shortPathsAreCopied() {
        long[] two = {p(0, 0, 0), p(1, 0, 0)};
        assertArrayEquals(two, TrailPruner.prune(two));
        assertEquals(0, TrailPruner.prune(new long[0]).length);
    }
}
