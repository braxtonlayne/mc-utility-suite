package com.utilitysuite.world.oresim;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColumnHeightCacheTest {

    /** Deterministic fake terrain, filled a 4 x 4 cell at a time like the generator-backed filler. */
    private static final class FakeCells implements ColumnHeightCache.CellFiller {
        int fills;

        static int height(int x, int z) {
            return 60 + Math.floorMod(x * 31 + z * 17, 40) - 20;
        }

        @Override
        public void fillCell(int x, int z, ColumnHeightCache sink) {
            fills++;
            int x0 = Math.floorDiv(x, 4) * 4;
            int z0 = Math.floorDiv(z, 4) * 4;
            for (int dx = 0; dx < 4; dx++) {
                for (int dz = 0; dz < 4; dz++) {
                    sink.store(x0 + dx, z0 + dz, height(x0 + dx, z0 + dz));
                }
            }
        }
    }

    @Test
    void returnsFillerHeightsIncludingNegativeCoordinates() {
        FakeCells cells = new FakeCells();
        ColumnHeightCache cache = new ColumnHeightCache(cells, 64);
        for (int x = -40; x < 40; x++) {
            for (int z = -40; z < 40; z++) {
                assertEquals(FakeCells.height(x, z), cache.surfaceHeight(x, z), "column " + x + "," + z);
            }
        }
        // 80 x 80 columns = 20 x 20 cells, each filled exactly once
        assertEquals(400, cells.fills);
        assertEquals(400, cache.cellFills());
    }

    @Test
    void eachCellComputedOnceAcrossRepeatedNeighbourhoodQueries() {
        FakeCells cells = new FakeCells();
        ColumnHeightCache cache = new ColumnHeightCache(cells, 64);
        // simulate 3 x 3 chunk neighbourhoods of 5 x 5 simulated chunks asking for their vein-box columns
        for (int sc = 0; sc < 25; sc++) {
            int baseX = (sc % 5) * 16;
            int baseZ = (sc / 5) * 16;
            for (int x = baseX - 16; x < baseX + 32; x += 3) {
                for (int z = baseZ - 16; z < baseZ + 32; z += 3) {
                    cache.surfaceHeight(x, z);
                }
            }
        }
        long firstPass = cells.fills;
        for (int x = -16; x < 96; x++) {
            for (int z = -16; z < 96; z++) {
                cache.surfaceHeight(x, z);
            }
        }
        // the whole 7 x 7 chunk area is 28 x 28 cells; nothing filled twice
        assertEquals(28 * 28, cells.fills);
        assertTrue(firstPass <= 28 * 28);
    }

    @Test
    void evictsLeastRecentlyUsedChunksAndRecomputesThem() {
        FakeCells cells = new FakeCells();
        ColumnHeightCache cache = new ColumnHeightCache(cells, 4);
        for (int c = 0; c < 10; c++) {
            assertEquals(FakeCells.height(c * 16, 0), cache.surfaceHeight(c * 16, 0));
        }
        assertEquals(4, cache.cachedChunks());
        int before = cells.fills;
        assertEquals(FakeCells.height(0, 0), cache.surfaceHeight(0, 0)); // evicted: recomputed, still correct
        assertEquals(before + 1, cells.fills);
    }

    @Test
    void failingFillerDegradesToUnknownOnce() {
        int[] calls = {0};
        ColumnHeightCache cache = new ColumnHeightCache((x, z, sink) -> {
            calls[0]++;
            throw new IllegalStateException("server stopping");
        }, 8);
        assertEquals(TerrainOracle.UNKNOWN_HEIGHT, cache.surfaceHeight(5, 5));
        assertEquals(TerrainOracle.UNKNOWN_HEIGHT, cache.surfaceHeight(5, 5));
        assertEquals(1, calls[0]);
    }

    @Test
    void lookupsAndEvictionsDoNotAllocateAfterWarmup() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        FakeCells cells = new FakeCells();
        // small capacity so the measured loop keeps evicting and recycling chunk arrays
        ColumnHeightCache cache = new ColumnHeightCache(cells, 16);
        int[] cursor = {0};
        long bytes = AllocationProbe.measure(200_000, () -> {
            int i = cursor[0]++;
            int x = (i * 7) % 2048 - 1024;
            int z = (i * 13) % 2048 - 1024;
            cache.surfaceHeight(x, z);
        });
        // budget covers JIT/probe noise only: a per-lookup or per-chunk allocation would be megabytes here
        assertTrue(bytes < 64 * 1024, "allocated " + bytes + " bytes over 200k lookups");
    }
}
