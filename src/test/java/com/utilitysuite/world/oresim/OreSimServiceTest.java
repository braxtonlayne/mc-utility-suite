package com.utilitysuite.world.oresim;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OreSimServiceTest {

    /** Counts surface-height queries, i.e. how much simulation work actually ran. */
    private static final class CountingTerrain implements TerrainOracle {
        final AtomicInteger heightCalls = new AtomicInteger();

        @Override
        public byte classify(OreSpec spec, int x, int y, int z) {
            return UNKNOWN;
        }

        @Override
        public int surfaceHeight(int x, int z) {
            heightCalls.incrementAndGet();
            return 64;
        }
    }

    private static OreSimService.Config config() {
        OreSpec[] specs = VanillaOreTables.select(VanillaOreTables.OVERWORLD, Set.of("diamond", "iron"));
        return new OreSimService.Config(12345L, VanillaOreTables.OVERWORLD, specs, VanillaOreTables.OVERWORLD_MIN_Y,
                VanillaOreTables.OVERWORLD_HEIGHT, false);
    }

    @Test
    void queuedWorkIsSkippedAfterDisable() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        OreSimService service = new OreSimService(queue::add);
        service.configure(config());
        CountingTerrain terrain = new CountingTerrain();
        for (int i = 0; i < 4; i++) {
            assertTrue(service.request(i, 0, terrain, BiomeOracle.UNKNOWN_BIOMES, false));
        }
        service.configure(null); // module disabled while 4 chunks are still queued
        while (!queue.isEmpty()) {
            queue.poll().run();
        }
        assertEquals(0, terrain.heightCalls.get(), "stale chunks must not be simulated");
        assertEquals(0, service.drainCompleted(32));
    }

    @Test
    void currentWorkStillRuns() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        OreSimService service = new OreSimService(queue::add);
        service.configure(config());
        CountingTerrain terrain = new CountingTerrain();
        assertTrue(service.request(3, -2, terrain, BiomeOracle.UNKNOWN_BIOMES, false));
        while (!queue.isEmpty()) {
            queue.poll().run();
        }
        assertEquals(1, service.drainCompleted(32));
        assertNotNull(service.get(3, -2));
        assertEquals(1, service.cachedCount());
        assertTrue(terrain.heightCalls.get() > 0, "the simulation samples surface heights");
    }

    /**
     * A chunk simulated across a reconfigure finishes with the old generation. Draining that stale result must not
     * clear the chunk's pending mark of the current generation, or the chunk is queued and simulated twice.
     */
    @Test
    void staleResultDoesNotUnmarkThePendingChunkOfTheNewGeneration() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        OreSimService service = new OreSimService(queue::add);
        service.configure(config());
        CountingTerrain plain = new CountingTerrain();
        boolean[] reconfigured = new boolean[1];
        TerrainOracle reconfiguringMidRun = new TerrainOracle() {
            @Override
            public byte classify(OreSpec spec, int x, int y, int z) {
                return UNKNOWN;
            }

            @Override
            public int surfaceHeight(int x, int z) {
                if (!reconfigured[0]) {
                    // stands in for the client thread reconfiguring and re-requesting while the worker is busy
                    reconfigured[0] = true;
                    service.configure(config());
                    assertTrue(service.request(5, 5, plain, BiomeOracle.UNKNOWN_BIOMES, false));
                }
                return 64;
            }
        };
        assertTrue(service.request(5, 5, reconfiguringMidRun, BiomeOracle.UNKNOWN_BIOMES, false));
        queue.poll().run(); // old-generation run completes after the reconfigure
        assertTrue(reconfigured[0]);
        assertEquals(1, queue.size(), "the new generation's request is queued");

        assertEquals(0, service.drainCompleted(32), "the stale snapshot is not published");
        assertTrue(service.isKnownOrPending(5, 5), "still pending for the new generation");
        assertFalse(service.request(5, 5, plain, BiomeOracle.UNKNOWN_BIOMES, false), "not queued a second time");

        queue.poll().run();
        assertEquals(1, service.drainCompleted(32));
        assertNotNull(service.get(5, 5));
        assertTrue(queue.isEmpty());
    }

    /** isKnownOrPending / a refused request / an empty drain run every tick: the steady state must not allocate. */
    @Test
    void steadyStateQueriesAllocateNothing() {
        if (!AllocationProbe.supported()) {
            return;
        }
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        OreSimService service = new OreSimService(queue::add);
        service.configure(config());
        CountingTerrain terrain = new CountingTerrain();
        assertTrue(service.request(1, 1, terrain, BiomeOracle.UNKNOWN_BIOMES, false));
        assertTrue(service.request(2, 2, terrain, BiomeOracle.UNKNOWN_BIOMES, false)); // stays pending
        queue.poll().run();
        assertEquals(1, service.drainCompleted(32));
        long bytes = AllocationProbe.measure(100_000, () -> {
            service.isKnownOrPending(1, 1);
            service.isKnownOrPending(2, 2);
            service.isKnownOrPending(9, 9);
            service.request(1, 1, terrain, BiomeOracle.UNKNOWN_BIOMES, false);
            service.request(2, 2, terrain, BiomeOracle.UNKNOWN_BIOMES, false);
            service.drainCompleted(32);
        });
        assertTrue(bytes < 2048, "steady-state queries allocated " + bytes + " bytes");
    }
}
