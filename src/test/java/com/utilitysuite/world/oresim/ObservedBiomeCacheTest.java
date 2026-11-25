package com.utilitysuite.world.oresim;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservedBiomeCacheTest {

    private static final long ZOOM = 0x1234_5678_9ABCL;
    private static final int MIN_QY = -16;
    private static final int QUARTS_Y = 96;
    private static final String PEAKS = "minecraft:jagged_peaks";
    private static final String PLAINS = "minecraft:plains";

    private static final OreSpec EMERALD = VanillaOreTables.select(VanillaOreTables.OVERWORLD, Set.of("emerald"))[0];

    private static String[] filled(String id) {
        String[] a = new String[16 * QUARTS_Y];
        Arrays.fill(a, id);
        return a;
    }

    private static void fill3x3(ObservedBiomeCache cache, int cx, int cz, String id) {
        for (int x = cx - 1; x <= cx + 1; x++) {
            for (int z = cz - 1; z <= cz + 1; z++) {
                cache.put(x, z, filled(id));
            }
        }
    }

    @Test
    void emeraldSpecIsBiomeRestricted() {
        assertTrue(EMERALD.biomesAllowed() != null && EMERALD.biomesAllowed().contains(PEAKS));
        assertFalse(EMERALD.biomeAllows(PLAINS));
    }

    @Test
    void observedChunksAnswerAndUnseenChunksAreUnknown() {
        ObservedBiomeCache cache = new ObservedBiomeCache(ZOOM, MIN_QY, QUARTS_Y, 64);
        fill3x3(cache, 0, 0, PEAKS);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                assertEquals(PEAKS, cache.biomeAt(x, 40, z));
                assertEquals(BiomeOracle.YES, cache.allows(EMERALD, x, 40, z));
            }
        }
        assertNull(cache.biomeAt(500, 40, 500));
        assertEquals(BiomeOracle.UNKNOWN, cache.allows(EMERALD, 500, 40, 500));

        fill3x3(cache, 40, 40, PLAINS);
        assertEquals(BiomeOracle.NO, cache.allows(EMERALD, 40 * 16 + 8, 40, 40 * 16 + 8));
    }

    @Test
    void zoomMatchesTheSelectedQuart() {
        ObservedBiomeCache cache = new ObservedBiomeCache(ZOOM, MIN_QY, QUARTS_Y, 64);
        // Every quart gets its own id so the lookup must pick exactly the quart vanilla's zoom selects.
        for (int cx = -1; cx <= 1; cx++) {
            for (int cz = -1; cz <= 1; cz++) {
                String[] a = new String[16 * QUARTS_Y];
                for (int dx = 0; dx < 4; dx++) {
                    for (int dz = 0; dz < 4; dz++) {
                        for (int dy = 0; dy < QUARTS_Y; dy++) {
                            a[ObservedBiomeCache.index(dx, dz, dy, QUARTS_Y)] =
                                    (cx * 4 + dx) + "," + (MIN_QY + dy) + "," + (cz * 4 + dz);
                        }
                    }
                }
                cache.put(cx, cz, a);
            }
        }
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int y = 12;
                long q = QuartBiomeOracle.selectQuart(ZOOM, x, y, z);
                String expect = (int) (q >> 42) + "," + (int) ((q << 22) >> 43) + "," + (int) ((q << 43) >> 43);
                assertEquals(expect, cache.biomeAt(x, y, z));
            }
        }
        // Y outside the dimension clamps like vanilla.
        assertTrue(cache.biomeAt(8, -10_000, 8).contains("," + MIN_QY + ","));
    }

    @Test
    void boundedCacheEvictsLeastRecentlyStored() {
        ObservedBiomeCache cache = new ObservedBiomeCache(ZOOM, MIN_QY, QUARTS_Y, 2);
        cache.put(0, 0, filled(PEAKS));
        cache.put(1, 0, filled(PEAKS));
        cache.put(2, 0, filled(PEAKS));
        assertEquals(2, cache.size());
        assertFalse(cache.contains(0, 0));
        assertTrue(cache.contains(2, 0));
    }

    @Test
    void layeredPrefersObservedThenFallsBackToRebuilt() {
        ObservedBiomeCache cache = new ObservedBiomeCache(ZOOM, MIN_QY, QUARTS_Y, 64);
        fill3x3(cache, 0, 0, PLAINS);
        BiomeOracle rebuilt = new BiomeOracle() {
            @Override
            public byte allows(OreSpec spec, int x, int y, int z) {
                return BiomeOracle.answer(spec, biomeAt(x, y, z));
            }

            @Override
            public String biomeAt(int x, int y, int z) {
                return PEAKS;
            }
        };
        BiomeOracle layered = BiomeOracle.layered(cache, rebuilt);
        assertEquals(PLAINS, layered.biomeAt(8, 40, 8));
        assertEquals(BiomeOracle.NO, layered.allows(EMERALD, 8, 40, 8));
        assertEquals(PEAKS, layered.biomeAt(5000, 40, 5000));
        assertEquals(BiomeOracle.YES, layered.allows(EMERALD, 5000, 40, 5000));

        BiomeOracle nothing = BiomeOracle.layered(cache, BiomeOracle.UNKNOWN_BIOMES);
        assertEquals(BiomeOracle.UNKNOWN, nothing.allows(EMERALD, 5000, 40, 5000));
        assertNull(nothing.biomeAt(5000, 40, 5000));
    }

    @Test
    void lookupsAllocateNothingAfterWarmUp() {
        ObservedBiomeCache cache = new ObservedBiomeCache(ZOOM, MIN_QY, QUARTS_Y, 64);
        fill3x3(cache, 0, 0, PEAKS);
        com.sun.management.ThreadMXBean mx =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().getId();
        int sink = 0;
        for (int i = 0; i < 20_000; i++) {
            sink += cache.allows(EMERALD, i & 15, 40, (i >> 4) & 15);
        }
        long before = mx.getThreadAllocatedBytes(id);
        for (int i = 0; i < 100_000; i++) {
            sink += cache.allows(EMERALD, i & 15, 40, (i >> 4) & 15);
        }
        long allocated = mx.getThreadAllocatedBytes(id) - before;
        assertTrue(sink > 0);
        assertTrue(allocated < 64 * 1024, "allocated " + allocated + " bytes");
    }
}
