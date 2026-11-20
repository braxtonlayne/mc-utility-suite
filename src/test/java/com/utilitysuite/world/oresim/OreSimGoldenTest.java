package com.utilitysuite.world.oresim;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.world.BlockPosPacking;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OreSim against golden values recorded from vanilla 1.21.1.
 *
 * <h2>How accuracy was checked</h2>
 * A development-only harness ran vanilla's own {@code PlacedFeature.placeWithBiomeCheck} (real registries, real
 * {@code OreFeature}/{@code ScatteredOreFeature}, real placement modifiers and {@code WorldgenRandom}) on a fake
 * {@code WorldGenLevel} backed by {@code ProtoChunk}s filled with the synthetic terrain below, for every ore feature of
 * the overworld and the nether in vanilla order, and compared the placed blocks with this simulator fed the same
 * terrain: 3 seeds x 10 chunks x 6 biomes (plains, windswept hills, badlands, dripstone caves, nether wastes, basalt
 * deltas), all 25 ore features, 116,309 vanilla-placed blocks, <b>0 mismatches</b>. The terrain mixes in air pockets so
 * the air-exposure discard rolls (and the stream drift they cause) are exercised, and surfaces are high or low enough
 * to exercise the surface test. That harness needs the game bootstrap, so only its results for seed 12345, chunk
 * (5, -3) are kept here as golden values: per feature the block count, the lowest packed position and a hash of the
 * sorted packed positions. Features absent from a table placed nothing in vanilla and must place nothing here.
 */
class OreSimGoldenTest {

    private static final long SEED = 12345L;
    private static final int CX = 5;
    private static final int CZ = -3;

    /** biome, surface, feature, count, first x, y, z, hash. */
    private static final String GOLDEN = """
            plains 70 ore_coal_lower 81 78 54 -37 -1799565482211922136
            plains 70 ore_iron_middle 50 80 10 -36 605622973367781742
            plains 70 ore_iron_small 16 80 -55 -37 -1134793570969192938
            plains 70 ore_gold 13 82 -39 -38 -9113671447485690447
            plains 70 ore_gold_lower 9 85 -53 -47 -2998901758379601713
            plains 70 ore_redstone 21 79 -7 -41 7563790401266381019
            plains 70 ore_redstone_lower 36 80 -62 -45 555191601502093549
            plains 70 ore_diamond 2 82 -56 -45 733374249953529
            plains 70 ore_diamond_medium 7 89 -48 -41 6491215171776064242
            plains 70 ore_diamond_buried 2 87 -18 -37 776255204494749
            plains 70 ore_lapis 9 86 -7 -38 2122444557555558897
            plains 70 ore_lapis_buried 7 82 -55 -41 246664163249180872
            plains 70 ore_copper 94 81 33 -46 1098761792603558403
            windswept_hills 300 ore_coal_upper 541 79 233 -40 -3910645440328111682
            windswept_hills 300 ore_coal_lower 299 78 110 -46 2116684901938284483
            windswept_hills 300 ore_iron_upper 460 79 99 -44 -1246445775651663158
            windswept_hills 300 ore_iron_middle 51 80 10 -36 4560061344848474308
            windswept_hills 300 ore_iron_small 16 80 -55 -37 -1134793570969192938
            windswept_hills 300 ore_gold 13 82 -39 -38 -9113671447485690447
            windswept_hills 300 ore_gold_lower 9 85 -53 -47 -2998901758379601713
            windswept_hills 300 ore_redstone 21 79 -7 -41 7563790401266381019
            windswept_hills 300 ore_redstone_lower 36 80 -62 -45 555191601502093549
            windswept_hills 300 ore_diamond 2 82 -56 -45 733374249953529
            windswept_hills 300 ore_diamond_medium 7 89 -48 -41 6491215171776064242
            windswept_hills 300 ore_diamond_buried 2 87 -18 -37 776255204494749
            windswept_hills 300 ore_lapis 9 86 -7 -38 2122444557555558897
            windswept_hills 300 ore_lapis_buried 7 82 -55 -41 246664163249180872
            windswept_hills 300 ore_copper 119 81 80 -47 -3676254659471981617
            windswept_hills 300 ore_emerald 25 80 208 -47 -3473177778711992686
            badlands 70 ore_coal_lower 81 78 54 -37 -1799565482211922136
            badlands 70 ore_iron_middle 50 80 10 -36 605622973367781742
            badlands 70 ore_iron_small 16 80 -55 -37 -1134793570969192938
            badlands 70 ore_gold 13 82 -39 -38 -9113671447485690447
            badlands 70 ore_gold_lower 9 85 -53 -47 -2998901758379601713
            badlands 70 ore_redstone 21 79 -7 -41 7563790401266381019
            badlands 70 ore_redstone_lower 36 80 -62 -45 555191601502093549
            badlands 70 ore_diamond 2 82 -56 -45 733374249953529
            badlands 70 ore_diamond_medium 7 89 -48 -41 6491215171776064242
            badlands 70 ore_diamond_buried 2 87 -18 -37 776255204494749
            badlands 70 ore_lapis 9 86 -7 -38 2122444557555558897
            badlands 70 ore_lapis_buried 7 82 -55 -41 246664163249180872
            badlands 70 ore_copper 94 81 33 -46 1098761792603558403
            badlands 70 ore_gold_extra 39 83 32 -43 -1726756855955972287
            dripstone_caves 70 ore_coal_lower 81 78 54 -37 -1799565482211922136
            dripstone_caves 70 ore_iron_middle 50 80 10 -36 605622973367781742
            dripstone_caves 70 ore_iron_small 16 80 -55 -37 -1134793570969192938
            dripstone_caves 70 ore_gold 13 82 -39 -38 -9113671447485690447
            dripstone_caves 70 ore_gold_lower 9 85 -53 -47 -2998901758379601713
            dripstone_caves 70 ore_redstone 21 79 -7 -41 7563790401266381019
            dripstone_caves 70 ore_redstone_lower 36 80 -62 -45 555191601502093549
            dripstone_caves 70 ore_diamond 2 82 -56 -45 733374249953529
            dripstone_caves 70 ore_diamond_medium 7 89 -48 -41 6491215171776064242
            dripstone_caves 70 ore_diamond_buried 2 87 -18 -37 776255204494749
            dripstone_caves 70 ore_lapis 9 86 -7 -38 2122444557555558897
            dripstone_caves 70 ore_lapis_buried 7 82 -55 -41 246664163249180872
            dripstone_caves 70 ore_copper_large 364 78 39 -41 1603429316068860447
            nether_wastes 127 ore_gold_nether 81 80 113 -41 5539108625166128328
            nether_wastes 127 ore_quartz_nether 240 79 51 -49 -6315147773991240432
            nether_wastes 127 ore_ancient_debris_large 2 88 14 -44 783127151112641
            nether_wastes 127 ore_debris_small 2 94 50 -47 835903708857920
            basalt_deltas 127 ore_gold_deltas 171 79 15 -44 7324337307675738457
            basalt_deltas 127 ore_quartz_deltas 493 78 95 -43 6227444199396929245
            basalt_deltas 127 ore_ancient_debris_large 2 88 14 -44 783127151112641
            basalt_deltas 127 ore_debris_small 2 94 50 -47 835903708857920
            """;

    private record Row(String feature, int count, int x, int y, int z, long hash) {
    }

    /** The synthetic terrain of the vanilla harness: stone/deepslate/netherrack with hashed air pockets. */
    static TerrainOracle syntheticTerrain(boolean nether, int minY, int height, int surface) {
        return new TerrainOracle() {
            @Override
            public byte classify(OreSpec spec, int x, int y, int z) {
                if (y < minY || y >= minY + height || y > surface) {
                    return AIR;
                }
                int h = (x * 73428767 ^ y * 912931 ^ z * 42317861);
                h ^= h >>> 13;
                h *= 0x5bd1e995;
                h ^= h >>> 15;
                return (h & (nether ? 63 : 7)) == 0 ? AIR : TARGET;
            }

            @Override
            public int surfaceHeight(int x, int z) {
                return surface;
            }
        };
    }

    static OreSpec[] sortedSpecs(String dimension) {
        OreSpec[] specs = VanillaOreTables.forDimension(dimension).toArray(new OreSpec[0]);
        Arrays.sort(specs, Comparator.comparingInt(OreSpec::step).thenComparingInt(OreSpec::index));
        return specs;
    }

    @Test
    void matchesVanillaGoldenValues() {
        Map<String, List<Row>> byBiome = new HashMap<>();
        Map<String, Integer> surfaces = new HashMap<>();
        for (String line : GOLDEN.strip().split("\n")) {
            String[] p = line.strip().split(" ");
            byBiome.computeIfAbsent(p[0], k -> new ArrayList<>()).add(new Row("minecraft:" + p[2],
                    Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5]), Integer.parseInt(p[6]),
                    Long.parseLong(p[7])));
            surfaces.put(p[0], Integer.parseInt(p[1]));
        }
        int checkedFeatures = 0;
        for (Map.Entry<String, List<Row>> e : byBiome.entrySet()) {
            String biome = "minecraft:" + e.getKey();
            boolean nether = biome.equals("minecraft:nether_wastes") || biome.equals("minecraft:basalt_deltas");
            String dim = nether ? VanillaOreTables.NETHER : VanillaOreTables.OVERWORLD;
            int minY = VanillaOreTables.minY(dim);
            int height = VanillaOreTables.height(dim);
            OreSpec[] specs = sortedSpecs(dim);
            List<TreeSet<Long>> placed = new ArrayList<>();
            for (int i = 0; i < specs.length; i++) {
                placed.add(new TreeSet<>());
            }
            new OreSimulator().simulateChunk(SEED, CX, CZ, specs, minY, height,
                    syntheticTerrain(nether, minY, height, surfaces.get(e.getKey())),
                    (spec, x, y, z) -> spec.biomeAllows(biome) ? BiomeOracle.YES : BiomeOracle.NO, 1f,
                    (si, x, y, z, v, c) -> placed.get(si).add(BlockPosPacking.pack(x, y, z)));
            for (int i = 0; i < specs.length; i++) {
                String id = specs[i].featureId();
                Row row = e.getValue().stream().filter(r -> r.feature().equals(id)).findFirst().orElse(null);
                TreeSet<Long> got = placed.get(i);
                if (row == null) {
                    assertTrue(got.isEmpty(), biome + " " + id + " placed nothing in vanilla but " + got.size() + " here");
                    continue;
                }
                assertEquals(row.count(), got.size(), biome + " " + id + " count");
                long first = got.first();
                assertEquals(row.x(), BlockPosPacking.x(first), biome + " " + id + " first x");
                assertEquals(row.y(), BlockPosPacking.y(first), biome + " " + id + " first y");
                assertEquals(row.z(), BlockPosPacking.z(first), biome + " " + id + " first z");
                long h = 0;
                for (long q : got) {
                    h = h * 31 + q;
                }
                assertEquals(row.hash(), h, biome + " " + id + " position hash");
                checkedFeatures++;
            }
        }
        assertEquals(64, checkedFeatures);
    }

    @Test
    void deterministicAcrossRunsAndInstances() {
        OreSpec[] specs = sortedSpecs(VanillaOreTables.OVERWORLD);
        TerrainOracle terrain = syntheticTerrain(false, -64, 384, 70);
        long[] a = run(new OreSimulator(), specs, terrain);
        OreSimulator reused = new OreSimulator();
        run(reused, specs, terrain);
        long[] b = run(reused, specs, terrain); // a reused simulator must not carry state between chunks
        long[] c = run(new OreSimulator(), specs, terrain);
        assertTrue(a.length > 0);
        assertEquals(Arrays.toString(a), Arrays.toString(b));
        assertEquals(Arrays.toString(a), Arrays.toString(c));
    }

    private static long[] run(OreSimulator sim, OreSpec[] specs, TerrainOracle terrain) {
        TreeSet<Long> out = new TreeSet<>();
        sim.simulateChunk(SEED, CX, CZ, specs, -64, 384, terrain, BiomeOracle.UNKNOWN_BIOMES, 1f,
                (si, x, y, z, v, c) -> out.add(BlockPosPacking.pack(x, y, z) * 31 + si));
        return out.stream().mapToLong(Long::longValue).toArray();
    }

    @Test
    void simulationIsAllocationFreeAfterWarmUp() {
        if (!AllocationProbe.supported()) {
            return;
        }
        OreSpec[] specs = VanillaOreTables.select(VanillaOreTables.OVERWORLD, java.util.Set.of("diamond"));
        OreSimulator sim = new OreSimulator();
        TerrainOracle terrain = TerrainOracle.UNKNOWN_TERRAIN;
        OreSink sink = (si, x, y, z, v, c) -> { };
        int[] chunk = {0};
        long bytes = AllocationProbe.measure(2_000, () -> {
            int c = chunk[0]++ & 63;
            sim.simulateChunk(SEED, c, -c, specs, -64, 384, terrain, BiomeOracle.UNKNOWN_BIOMES, 1f, sink);
        });
        // The placed-block set may grow its table once; anything per-chunk would show up as megabytes here.
        assertTrue(bytes < 64 * 1024, "allocated " + bytes + " bytes over 2000 chunk simulations");
    }
}
