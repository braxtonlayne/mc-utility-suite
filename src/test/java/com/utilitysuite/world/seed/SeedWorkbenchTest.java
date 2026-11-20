package com.utilitysuite.world.seed;

import com.utilitysuite.world.BlockPosPacking;
import com.utilitysuite.world.oresim.BiomeOracle;
import com.utilitysuite.world.oresim.OreSimulator;
import com.utilitysuite.world.oresim.OreSpec;
import com.utilitysuite.world.oresim.TerrainOracle;
import com.utilitysuite.world.oresim.VanillaOreTables;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeedWorkbenchTest {

    private static final String OW = VanillaOreTables.OVERWORLD;

    @Test
    void verdictRule() {
        SeedWorkbench wb = new SeedWorkbench();
        assertEquals(SeedWorkbench.Verdict.AMBIGUOUS, wb.verdict(0, 0, 0), "no decided evidence");
        assertEquals(SeedWorkbench.Verdict.AMBIGUOUS, wb.verdict(3, 0, 0), "too little evidence");
        assertEquals(SeedWorkbench.Verdict.MATCH, wb.verdict(4, 0, 0));
        assertEquals(SeedWorkbench.Verdict.MATCH, wb.verdict(20, 1, 0), "an outlier among many matches");
        assertEquals(SeedWorkbench.Verdict.AMBIGUOUS, wb.verdict(5, 1, 0));
        assertEquals(SeedWorkbench.Verdict.NO_MATCH, wb.verdict(2, 2, 0), "half wrong");
        assertEquals(SeedWorkbench.Verdict.NO_MATCH, wb.verdict(1, 1, 1), "a structure that cannot start there");
        assertEquals(SeedWorkbench.Verdict.MATCH, wb.verdict(20, 1, 1), "one structure outlier among many matches");
        assertEquals(SeedWorkbench.Verdict.AMBIGUOUS, wb.verdict(12, 1, 1), "1 in 13 wrong is not clean enough");
    }

    @Test
    void parsesSeedRepliesAndManualSeeds() {
        assertEquals(OptionalLong.of(-4172144997902289642L),
                SeedText.parseSeedMessage("Seed: [-4172144997902289642]"));
        assertEquals(OptionalLong.of(12345L), SeedText.parseSeedMessage("Seed: 12345"));
        assertTrue(SeedText.parseSeedMessage("<player> Seed: [1]?").isEmpty());
        assertTrue(SeedText.parseSeedMessage("hello").isEmpty());
        assertEquals(OptionalLong.of(42L), SeedText.parseManualSeed(" 42 "));
        assertEquals(OptionalLong.of("Glacier".hashCode()), SeedText.parseManualSeed("Glacier"));
        assertTrue(SeedText.parseManualSeed("   ").isEmpty());
    }

    /**
     * {@code RandomSpreadStructurePlacement.getPotentialStructureChunk} restated over the vanilla {@code WorldgenRandom}
     * (the placement class itself needs the game bootstrap to load). LINEAR = nextInt(n), TRIANGULAR = mean of two.
     */
    private static int[] vanillaPotentialChunk(long seed, StructureSpread.Placement p, int cx, int cz) {
        int i = Math.floorDiv(cx, p.spacing());
        int j = Math.floorDiv(cz, p.spacing());
        WorldgenRandom r = new WorldgenRandom(new LegacyRandomSource(0L));
        r.setLargeFeatureWithSalt(seed, i, j, p.salt());
        int k = p.spacing() - p.separation();
        int l = p.triangular() ? (r.nextInt(k) + r.nextInt(k)) / 2 : r.nextInt(k);
        int m = p.triangular() ? (r.nextInt(k) + r.nextInt(k)) / 2 : r.nextInt(k);
        return new int[] {i * p.spacing() + l, j * p.spacing() + m};
    }

    @Test
    void structureSpreadMatchesVanillaPlacement() {
        Random r = new Random(1);
        for (var e : StructureSpread.PLACEMENTS.entrySet()) {
            StructureSpread.Placement p = e.getValue();
            for (int i = 0; i < 100; i++) {
                long seed = r.nextLong();
                int cx = r.nextInt(20000) - 10000;
                int cz = r.nextInt(20000) - 10000;
                int[] v = vanillaPotentialChunk(seed, p, cx, cz);
                int[] ours = StructureSpread.potentialChunk(seed, p, cx, cz);
                assertEquals(v[0], ours[0], e.getKey());
                assertEquals(v[1], ours[1], e.getKey());
            }
        }
    }

    /** Diamonds the simulator puts in a few chunks for a seed (deep, stream-independent features only). */
    private static List<SeedWorkbench.Evidence> diamondsFor(long seed) {
        OreSpec[] specs = VanillaOreTables.select(OW, java.util.Set.of("diamond"));
        List<SeedWorkbench.Evidence> out = new ArrayList<>();
        for (int c = 0; c < 12 && out.size() < 8; c++) {
            int cx = c * 7;
            int cz = -c * 3;
            List<long[]> found = new ArrayList<>();
            new OreSimulator().simulateChunk(seed, cx, cz, specs, -64, 384, TerrainOracle.UNKNOWN_TERRAIN,
                    BiomeOracle.UNKNOWN_BIOMES, 1f, (si, x, y, z, v, conf) -> {
                        if (found.isEmpty()) {
                            found.add(new long[] {BlockPosPacking.pack(x, y, z)});
                        }
                    });
            if (!found.isEmpty()) {
                long p = found.get(0)[0];
                out.add(SeedWorkbench.ore(OW, p, BlockPosPacking.y(p) < 0 ? "minecraft:deepslate_diamond_ore"
                        : "minecraft:diamond_ore"));
            }
        }
        return out;
    }

    @Test
    void oreEvidenceConfirmsTheRightSeedAndNotAnother() {
        long seed = 8675309L;
        List<SeedWorkbench.Evidence> evidence = diamondsFor(seed);
        assertTrue(evidence.size() >= 4);
        SeedWorkbench.Report right = new SeedWorkbench().compare(seed, evidence);
        assertEquals(SeedWorkbench.Verdict.MATCH, right.verdict(), right.toString());
        assertEquals(evidence.size(), right.matches());

        SeedWorkbench.Report wrong = new SeedWorkbench().compare(seed + 1, evidence);
        assertTrue(wrong.verdict() != SeedWorkbench.Verdict.MATCH, wrong.toString());
        assertTrue(wrong.matches() < evidence.size());
    }

    @Test
    void structureEvidenceIsDecisive() {
        long seed = 99L;
        StructureSpread.Placement village = StructureSpread.PLACEMENTS.get("village");
        int[] start = StructureSpread.potentialChunk(seed, village, 0, 0);
        SeedWorkbench wb = new SeedWorkbench();
        SeedWorkbench.Report ok = wb.compare(seed, List.of(new SeedWorkbench.Evidence.Structure("village", start[0],
                start[1])));
        assertEquals(1, ok.matches());
        SeedWorkbench.Report bad = wb.compare(seed, List.of(new SeedWorkbench.Evidence.Structure("village",
                start[0] + 1, start[1])));
        assertEquals(SeedWorkbench.Verdict.NO_MATCH, bad.verdict());
        assertEquals(1, bad.strongMismatches());
    }

    @Test
    void generatorProfileFlagsUncertainty() {
        GeneratorProfile none = GeneratorProfile.none();
        assertTrue(!none.seedKnown() && !none.uncertain());
        GeneratorProfile manual = none.withSeed(5L, GeneratorProfile.Source.MANUAL);
        assertTrue(manual.seedKnown());
        assertEquals(5L, manual.seed());
        assertTrue(new GeneratorProfile(5L, true, "1.17.1", GeneratorProfile.Source.MANUAL, false).uncertain());
        assertTrue(new GeneratorProfile(5L, true, GeneratorProfile.CURRENT_VERSION, GeneratorProfile.Source.MANUAL,
                true).uncertain());
    }
}
