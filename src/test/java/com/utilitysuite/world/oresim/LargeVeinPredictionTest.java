package com.utilitysuite.world.oresim;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.world.BlockPosPacking;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LargeVeinPredictionTest {

    private static final long SEED = 987654321L;
    private static final int CX = 5;
    private static final int CZ = -3;

    /** Fake terrain with a few vein ore blocks in the chunk; unknown otherwise. */
    private static final class VeinTerrain implements TerrainOracle {
        int veinCalls;

        @Override
        public byte classify(OreSpec spec, int x, int y, int z) {
            return UNKNOWN;
        }

        @Override
        public int surfaceHeight(int x, int z) {
            return UNKNOWN_HEIGHT; // same as UNKNOWN_TERRAIN, so decoration output is comparable
        }

        @Override
        public boolean largeVeins(int chunkX, int chunkZ, LargeVeinSink sink) {
            veinCalls++;
            int x0 = chunkX << 4;
            int z0 = chunkZ << 4;
            sink.acceptVeinOre(false, x0 + 1, -40, z0 + 2);
            sink.acceptVeinOre(false, x0 + 1, -39, z0 + 2);
            sink.acceptVeinOre(true, x0 + 7, 20, z0 + 9);
            return true;
        }
    }

    private static ChunkOreSnapshot simulate(OreSpec[] specs, TerrainOracle terrain) {
        ChunkOreSnapshot.Builder b = new ChunkOreSnapshot.Builder(CX, CZ, 1, false, specs);
        new OreSimulator().simulateChunk(SEED, CX, CZ, specs, VanillaOreTables.OVERWORLD_MIN_Y,
                VanillaOreTables.OVERWORLD_HEIGHT, terrain, BiomeOracle.UNKNOWN_BIOMES, OreSimService.EXACT_CONFIDENCE, b);
        return b.build();
    }

    private static int find(ChunkOreSnapshot s, int x, int y, int z) {
        long p = BlockPosPacking.pack(x, y, z);
        for (int i = 0; i < s.size(); i++) {
            if (s.pos(i) == p) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void largeVeinSpecsOnlyAddedForOverworldSelectedResources() {
        OreSpec[] ow = VanillaOreTables.selectWithLargeVeins(VanillaOreTables.OVERWORLD, Set.of("iron"));
        assertEquals(VanillaOreTables.select(VanillaOreTables.OVERWORLD, Set.of("iron")).length + 1, ow.length);
        assertTrue(ow[ow.length - 1].largeVein());
        assertEquals("iron", ow[ow.length - 1].resource());
        OreSpec[] diamonds = VanillaOreTables.selectWithLargeVeins(VanillaOreTables.OVERWORLD, Set.of("diamond"));
        for (OreSpec s : diamonds) {
            assertFalse(s.largeVein());
        }
    }

    @Test
    void veinOresArePredictedWithTheirBlockIdsAndExactConfidence() {
        OreSpec[] specs = VanillaOreTables.selectWithLargeVeins(VanillaOreTables.OVERWORLD, Set.of("iron", "copper"));
        ChunkOreSnapshot s = simulate(specs, new VeinTerrain());
        int iron = find(s, (CX << 4) + 1, -40, (CZ << 4) + 2);
        int copper = find(s, (CX << 4) + 7, 20, (CZ << 4) + 9);
        assertTrue(iron >= 0 && copper >= 0);
        assertEquals("minecraft:deepslate_iron_ore", s.blockId(iron));
        assertEquals("minecraft:copper_ore", s.blockId(copper));
        assertEquals(OreSimService.EXACT_CONFIDENCE, s.confidence(iron), 1e-6);
        assertTrue(s.spec(iron).largeVein());
    }

    @Test
    void veinsDoNotChangeDecorationPredictions() {
        Set<String> res = Set.of("iron", "copper", "diamond");
        ChunkOreSnapshot plain = simulate(VanillaOreTables.select(VanillaOreTables.OVERWORLD, res),
                TerrainOracle.UNKNOWN_TERRAIN);
        ChunkOreSnapshot withVeins = simulate(VanillaOreTables.selectWithLargeVeins(VanillaOreTables.OVERWORLD, res),
                new VeinTerrain());
        int decorationCount = 0;
        for (int i = 0; i < withVeins.size(); i++) {
            if (!withVeins.spec(i).largeVein()) {
                decorationCount++;
            }
        }
        // every decoration prediction is still there, except where a vein block claimed the position first
        int matched = 0;
        for (int i = 0; i < plain.size(); i++) {
            int j = find(withVeins, BlockPosPacking.x(plain.pos(i)), BlockPosPacking.y(plain.pos(i)),
                    BlockPosPacking.z(plain.pos(i)));
            assertTrue(j >= 0);
            if (!withVeins.spec(j).largeVein()) {
                assertEquals(plain.spec(i).featureId(), withVeins.spec(j).featureId());
                matched++;
            }
        }
        assertEquals(decorationCount, matched);
    }

    @Test
    void veinSpecsWithoutNoiseAccessPredictNothing() {
        OreSpec[] specs = VanillaOreTables.selectWithLargeVeins(VanillaOreTables.OVERWORLD, Set.of("iron"));
        ChunkOreSnapshot s = simulate(specs, TerrainOracle.UNKNOWN_TERRAIN);
        for (int i = 0; i < s.size(); i++) {
            assertFalse(s.spec(i).largeVein());
        }
    }

    @Test
    void veinForwardingDoesNotAllocate() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        OreSpec[] specs = VanillaOreTables.selectWithLargeVeins(VanillaOreTables.OVERWORLD, Set.of("iron", "copper"));
        OreSimulator sim = new OreSimulator();
        VeinTerrain terrain = new VeinTerrain();
        OreSink discard = (spec, x, y, z, vein, conf) -> { };
        long bytes = AllocationProbe.measure(2_000, () -> sim.simulateChunk(SEED, CX, CZ, specs,
                VanillaOreTables.OVERWORLD_MIN_Y, VanillaOreTables.OVERWORLD_HEIGHT, terrain,
                BiomeOracle.UNKNOWN_BIOMES, OreSimService.EXACT_CONFIDENCE, discard));
        assertTrue(bytes < 64 * 1024, "allocated " + bytes + " bytes over 2000 simulated chunks");
    }
}
