package com.utilitysuite.world.redstone;

import com.utilitysuite.world.BlockPosPacking;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.utilitysuite.world.redstone.RedstoneCell.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hand-built networks for the engine-free tracer. */
class RedstoneTracerTest {

    private final Long2IntOpenHashMap grid = new Long2IntOpenHashMap();
    private final RedstoneTracer.View view = (x, y, z) -> grid.get(BlockPosPacking.pack(x, y, z));
    private final RedstoneTracer tracer = new RedstoneTracer();

    @BeforeEach
    void setUp() {
        grid.defaultReturnValue(NONE);
    }

    private void put(int x, int y, int z, int cell) {
        grid.put(BlockPosPacking.pack(x, y, z), cell);
    }

    private void floor(int x0, int x1, int z) {
        for (int x = x0; x <= x1; x++) {
            put(x, -1, z, of(SOLID, 0));
        }
    }

    private int trace(int x, int y, int z) {
        return tracer.trace(view, x, y, z, 512, 100_000);
    }

    @Test
    void leverDustRepeaterPiston() {
        floor(0, 6, 0);
        put(0, 0, 0, of(LEVER, DOWN, 0, true));
        put(1, 0, 0, of(DUST, 0, 15, true));
        put(2, 0, 0, of(DUST, 0, 14, true));
        put(3, 0, 0, of(DUST, 0, 13, true));
        put(4, 0, 0, of(REPEATER, EAST, 2, true));
        put(5, 0, 0, of(DUST, 0, 15, true));
        put(6, 0, 0, of(PISTON, EAST));
        assertTrue(trace(0, 0, 0) >= 7);
        int piston = tracer.indexOf(6, 0, 0);
        assertTrue(piston >= 0);
        assertEquals(4, tracer.delay(piston), "repeater at delay 2 = 4 game ticks");
        assertEquals(14, tracer.nodeStrength(tracer.indexOf(2, 0, 0)));
        assertEquals(15, tracer.nodeStrength(tracer.indexOf(0, 0, 0)));
        assertEquals(-1, tracer.nodeStrength(piston));
        assertEquals(0, tracer.delay(tracer.indexOf(3, 0, 0)));
        // the chain of parents leads back to the lever
        int i = piston;
        int hops = 0;
        while (tracer.parent(i) >= 0) {
            i = tracer.parent(i);
            hops++;
        }
        assertEquals(tracer.indexOf(0, 0, 0), i);
        assertEquals(tracer.depth(piston), hops);
        // QC zone above the piston
        assertEquals(1, tracer.qcCount());
        assertEquals(BlockPosPacking.pack(6, 1, 0), tracer.qcZone(0));
        assertFalse(tracer.truncated());
    }

    @Test
    void repeaterIntoBlockFlipsAttachedTorch() {
        put(0, 0, 0, of(LEVER, DOWN, 0, true));
        put(0, -1, 0, of(SOLID, 0));
        put(1, 0, 0, of(REPEATER, EAST, 1, false));
        put(2, 0, 0, of(SOLID, 0));
        put(3, 0, 0, of(TORCH, WEST, 0, true)); // wall torch on the east face of the block
        put(4, 0, 0, of(LAMP, 0));
        trace(0, 0, 0);
        int torch = tracer.indexOf(3, 0, 0);
        assertTrue(torch >= 0, "torch on the strongly powered block is reached");
        assertEquals(4, tracer.delay(torch), "repeater 2 + torch 2");
        assertEquals(4, tracer.delay(tracer.indexOf(4, 0, 0)));
    }

    @Test
    void dustDoesNotPowerDustThroughABlock() {
        put(0, 0, 0, of(REDSTONE_BLOCK, 0));
        put(1, 0, 0, of(DUST, 0, 15, true));
        put(2, 0, 0, of(SOLID, 0));
        put(3, 0, 0, of(DUST, 0, 0, false));
        put(3, 1, 0, of(LAMP, 0)); // irrelevant
        put(2, 0, 1, of(LAMP, 0)); // next to the weakly powered block: lit
        trace(0, 0, 0);
        assertEquals(-1, tracer.indexOf(3, 0, 0), "weak power does not reach dust");
        assertTrue(tracer.indexOf(2, 0, 1) >= 0, "weak power reaches a lamp");
    }

    @Test
    void quasiConnectivityPowersPistonBelow() {
        put(0, 0, 0, of(PISTON, UP));
        put(1, 1, 0, of(LEVER, UP, 0, true)); // next to the block above the piston, not to the piston
        put(1, 2, 0, of(SOLID, 0)); // ceiling the lever hangs from
        trace(1, 1, 0);
        int piston = tracer.indexOf(0, 0, 0);
        assertTrue(piston >= 0);
        assertTrue(tracer.viaQuasiConnectivity(piston));
        assertEquals(BlockPosPacking.pack(0, 1, 0), tracer.qcZone(0));
    }

    @Test
    void observerWatchingDustFires() {
        put(0, 0, 0, of(REDSTONE_BLOCK, 0));
        put(1, 0, 0, of(DUST, 0, 15, true));
        put(1, 0, 1, of(OBSERVER, NORTH, 0, false)); // watches north = the dust; outputs south
        put(1, 0, 2, of(LAMP, 0));
        trace(0, 0, 0);
        int obs = tracer.indexOf(1, 0, 1);
        assertTrue(obs >= 0);
        assertEquals(2, tracer.delay(obs));
        assertEquals(2, tracer.delay(tracer.indexOf(1, 0, 2)));
    }

    @Test
    void repeaterIgnoresPowerFromItsFront() {
        put(0, 0, 0, of(REDSTONE_BLOCK, 0));
        put(1, 0, 0, of(REPEATER, WEST, 1, false)); // outputs toward the redstone block
        put(2, 0, 0, of(LAMP, 0));
        trace(0, 0, 0);
        assertEquals(-1, tracer.indexOf(1, 0, 0));
    }

    @Test
    void fastestRouteWins() {
        // lamp reachable through a delay-4 repeater (8 gt) and through plain dust (0 gt)
        put(0, 0, 0, of(REDSTONE_BLOCK, 0));
        put(1, 0, 0, of(REPEATER, EAST, 4, false));
        put(2, 0, 0, of(DUST, 0, 15, true));
        put(0, 0, 1, of(DUST, 0, 15, true));
        put(1, 0, 1, of(DUST, 0, 14, true));
        put(2, 0, 1, of(DUST, 0, 13, true));
        put(3, 0, 1, of(LAMP, 0));
        put(3, 0, 0, of(LAMP, 0));
        trace(0, 0, 0);
        assertEquals(0, tracer.delay(tracer.indexOf(3, 0, 1)));
        // (2,0,0) dust is fed by both the repeater and the (2,0,1) dust: the dust path is faster
        assertEquals(0, tracer.delay(tracer.indexOf(2, 0, 0)));
    }

    @Test
    void dustStepsUpAndDown() {
        put(0, 0, 0, of(REDSTONE_BLOCK, 0));
        put(1, 0, 0, of(DUST, 0, 15, true));
        put(2, 0, 0, of(SOLID, 0));
        put(2, 1, 0, of(DUST, 0, 14, true)); // on top of the block
        put(3, 0, 0, of(DUST, 0, 13, true)); // back down
        put(3, -1, 0, of(SOLID, 0));
        trace(0, 0, 0);
        assertTrue(tracer.indexOf(2, 1, 0) >= 0);
        assertTrue(tracer.indexOf(3, 0, 0) >= 0);
    }

    @Test
    void nodeCapAndLookupBudgetStopTheTrace() {
        put(0, 0, 0, of(REDSTONE_BLOCK, 0));
        for (int x = 1; x <= 2000; x++) {
            put(x, 0, 0, of(DUST, 0, 1, true));
        }
        assertEquals(64, tracer.trace(view, 0, 0, 0, 64, 1_000_000));
        assertTrue(tracer.truncated());
        tracer.trace(view, 0, 0, 0, 1024, 200);
        assertTrue(tracer.lookups() <= 200);
        assertTrue(tracer.truncated());
    }

    @Test
    void labels() {
        assertEquals("+4gt 15", RedstoneLabels.format(4, 15, false, false));
        assertEquals("+2gt ?", RedstoneLabels.format(2, -2, false, false));
        assertEquals("+0gt ext+2 QC", RedstoneLabels.format(0, -1, true, true));
        assertEquals(4, delayGt(of(REPEATER, EAST, 2, false)));
        assertEquals(2, delayGt(of(COMPARATOR, EAST)));
        assertEquals(-1, RedstoneCell.strength(of(COMPARATOR, EAST)));
        assertEquals(7, RedstoneCell.strength(of(DUST, 0, 7, true)));
    }

    @Test
    void notAComponentTracesNothing() {
        put(0, 0, 0, of(SOLID, 0));
        assertEquals(0, trace(0, 0, 0));
        assertEquals(0, trace(5, 5, 5));
    }

    @Test
    void retraceAllocatesNothingOnceWarm() {
        floor(0, 40, 0);
        put(0, 0, 0, of(LEVER, DOWN, 0, true));
        for (int x = 1; x <= 40; x++) {
            put(x, 0, 0, x % 8 == 0 ? of(REPEATER, EAST, 1, true) : of(DUST, 0, 15, true));
        }
        for (int i = 0; i < 50; i++) {
            trace(0, 0, 0); // warm the hash maps
        }
        java.lang.management.ThreadMXBean mx = java.lang.management.ManagementFactory.getThreadMXBean();
        if (!(mx instanceof com.sun.management.ThreadMXBean smx) || !smx.isThreadAllocatedMemorySupported()) {
            return;
        }
        long tid = Thread.currentThread().getId();
        long before = smx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 200; i++) {
            trace(0, 0, 0);
        }
        long bytes = smx.getThreadAllocatedBytes(tid) - before;
        // the grid lambda boxes nothing; allow a little slack for the JIT / measurement itself
        assertTrue(bytes < 64_000, "allocated " + bytes + " bytes over 200 traces");
        assertEquals(10, tracer.delay(tracer.indexOf(40, 0, 0)), "five 1-tick repeaters");
    }
}
