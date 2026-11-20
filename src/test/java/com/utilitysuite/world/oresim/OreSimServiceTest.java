package com.utilitysuite.world.oresim;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
