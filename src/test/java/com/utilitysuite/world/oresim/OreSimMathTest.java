package com.utilitysuite.world.oresim;

import com.utilitysuite.world.BlockPosPacking;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Height sampling, in-chunk placement and the air-exposure discard rule. */
class OreSimMathTest {

    /** Vanilla {@code TrapezoidHeight.sample} / {@code UniformHeight.sample} with resolved anchors. */
    private static int vanillaHeight(WorldgenRandom r, OreSpec s) {
        int i = s.minY();
        int j = s.maxY();
        if (i > j) {
            return i;
        }
        if (!s.trapezoid()) {
            return Mth.randomBetweenInclusive(r, i, j);
        }
        int k = j - i;
        if (s.plateau() >= k) {
            return Mth.randomBetweenInclusive(r, i, j);
        }
        int l = (k - s.plateau()) / 2;
        int i1 = k - l;
        return i + Mth.randomBetweenInclusive(r, 0, i1) + Mth.randomBetweenInclusive(r, 0, l);
    }

    @Test
    void heightSamplingMatchesVanillaProviders() {
        List<OreSpec> all = new ArrayList<>(VanillaOreTables.forDimension(VanillaOreTables.OVERWORLD));
        all.addAll(VanillaOreTables.forDimension(VanillaOreTables.NETHER));
        for (OreSpec s : all) {
            WorldgenRandom vanilla = new WorldgenRandom(new XoroshiroRandomSource(0L));
            vanilla.setFeatureSeed(vanilla.setDecorationSeed(99L, 160, -48), s.index(), s.step());
            WorldgenRng ours = WorldgenRng.decoration();
            ours.setFeatureSeed(ours.setDecorationSeed(99L, 160, -48), s.index(), s.step());
            for (int n = 0; n < 500; n++) {
                int y = OreSimulator.sampleHeight(ours, s);
                assertEquals(vanillaHeight(vanilla, s), y, s.featureId());
                assertTrue(y >= s.minY() && y <= s.maxY(), s.featureId() + " y " + y);
            }
        }
    }

    @Test
    void trapezoidPeaksInTheMiddle() {
        OreSpec diamond = byId("minecraft:ore_diamond");
        WorldgenRng r = WorldgenRng.decoration();
        r.setSeed(5L);
        int[] buckets = new int[3];
        int range = diamond.maxY() - diamond.minY();
        for (int i = 0; i < 30_000; i++) {
            int y = OreSimulator.sampleHeight(r, diamond) - diamond.minY();
            buckets[Math.min(2, y * 3 / (range + 1))]++;
        }
        assertTrue(buckets[1] > buckets[0] && buckets[1] > buckets[2], java.util.Arrays.toString(buckets));
    }

    @Test
    void veinOriginsStayInTheirChunkColumn() {
        OreSpec[] specs = VanillaOreTables.select(VanillaOreTables.OVERWORLD, java.util.Set.of("iron"));
        int[] minMax = {Integer.MAX_VALUE, Integer.MIN_VALUE};
        new OreSimulator().simulateChunk(1L, 3, 7, specs, -64, 384, solid(), BiomeOracle.UNKNOWN_BIOMES, 1f,
                (si, x, y, z, v, c) -> {
                    minMax[0] = Math.min(minMax[0], x);
                    minMax[1] = Math.max(minMax[1], x);
                });
        // Veins are centred in the chunk (48..63) and spill at most ~ size/8 + 2 blocks.
        assertTrue(minMax[0] >= 48 - 8 && minMax[1] <= 63 + 8, minMax[0] + ".." + minMax[1]);
    }

    private static TerrainOracle solid() {
        return new TerrainOracle() {
            @Override
            public byte classify(OreSpec spec, int x, int y, int z) {
                return TARGET;
            }

            @Override
            public int surfaceHeight(int x, int z) {
                return 400;
            }
        };
    }

    /** Every block with odd (x + y + z) is air: every target block touches air. */
    private static TerrainOracle checkerboard() {
        return new TerrainOracle() {
            @Override
            public byte classify(OreSpec spec, int x, int y, int z) {
                return ((x + y + z) & 1) == 0 ? TARGET : AIR;
            }

            @Override
            public int surfaceHeight(int x, int z) {
                return 400;
            }
        };
    }

    private static OreSpec withDiscard(OreSpec s, float discard) {
        return new OreSpec(s.featureId(), s.step(), s.index(), s.countMin(), s.countMax(), s.rarityChance(),
                s.trapezoid(), s.minY(), s.maxY(), s.plateau(), s.size(), discard, s.scattered(), s.oreId(),
                s.deepslateOreId(), s.biomesAllowed(), s.biomesDenied(), s.resource());
    }

    private static int count(OreSpec spec, TerrainOracle terrain) {
        int[] n = {0};
        for (int c = 0; c < 20; c++) {
            new OreSimulator().simulateChunk(77L, c, c * 3, new OreSpec[] {spec}, -64, 384, terrain,
                    BiomeOracle.UNKNOWN_BIOMES, 1f, (si, x, y, z, v, conf) -> n[0]++);
        }
        return n[0];
    }

    @Test
    void airExposedBlocksAreDiscardedByChance() {
        OreSpec base = byId("minecraft:ore_diamond_buried");
        assertEquals(1.0f, base.discardOnAir());
        int never = count(withDiscard(base, 0f), checkerboard());
        int half = count(withDiscard(base, 0.5f), checkerboard());
        int always = count(base, checkerboard());
        int buriedInSolid = count(base, solid());
        assertTrue(never > 0, "discard 0 places into exposed targets");
        assertEquals(0, always, "discard 1.0 never places a block that touches air");
        assertTrue(half > 0 && half < never, "discard 0.5 places some: " + half + " of " + never);
        assertTrue(buriedInSolid > 0, "nothing is exposed in solid terrain");
        assertFalse(base.terrainDependentStream(), "1.0 skips the roll, so the stream does not depend on terrain");
        assertTrue(withDiscard(base, 0.5f).terrainDependentStream());
    }

    @Test
    void scatteredDebrisNeverTouchesAir() {
        OreSpec debris = byId("minecraft:ore_debris_small");
        TerrainOracle board = new TerrainOracle() {
            @Override
            public byte classify(OreSpec spec, int x, int y, int z) {
                return ((x + y + z) & 1) == 0 ? TARGET : AIR;
            }

            @Override
            public int surfaceHeight(int x, int z) {
                return 200;
            }
        };
        int[] n = {0};
        for (int c = 0; c < 50; c++) {
            new OreSimulator().simulateChunk(3L, c, -c, new OreSpec[] {debris}, 0, 256, board,
                    BiomeOracle.UNKNOWN_BIOMES, 1f, (si, x, y, z, v, conf) -> {
                        assertTrue(BlockPosPacking.pack(x, y, z) != 0L);
                        n[0]++;
                    });
        }
        assertEquals(0, n[0]);
    }

    static OreSpec byId(String id) {
        for (String dim : List.of(VanillaOreTables.OVERWORLD, VanillaOreTables.NETHER)) {
            for (OreSpec s : VanillaOreTables.forDimension(dim)) {
                if (s.featureId().equals(id)) {
                    return s;
                }
            }
        }
        throw new IllegalArgumentException(id);
    }
}
