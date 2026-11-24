package com.utilitysuite.world.seed;

import com.utilitysuite.world.oresim.WorldgenRng;
import net.minecraft.world.level.biome.BiomeManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeedSolverTest {

    private static List<SeedSolver.StructureClue> generateStructures(long seed, String[] types, int perType) {
        List<SeedSolver.StructureClue> out = new ArrayList<>();
        WorldgenRng rng = WorldgenRng.legacy();
        for (int t = 0; t < types.length; t++) {
            String type = types[t];
            StructureSpread.Placement p = StructureSpread.PLACEMENTS.get(type);
            int found = 0;
            // Different biome structures inhabit distinct regions in real worlds
            int startR = -12 + t * 5;
            for (int rx = startR; rx <= startR + 24 && found < perType; rx++) {
                for (int rz = startR - t * 2; rz <= startR + 24 && found < perType; rz++) {
                    int[] c = StructureSpread.potentialChunk(seed, p, rx * p.spacing(), rz * p.spacing());
                    if ("pillager_outpost".equals(type) && !StructureSpread.canStartOutpost(seed, c[0], c[1], rng)) {
                        continue;
                    }
                    out.add(new SeedSolver.StructureClue(type, c[0], c[1]));
                    found++;
                }
            }
        }
        return out;
    }

    @Test
    void solvesExact64BitWorldSeedFromOverworldStructures() {
        long[] testSeeds = {
                (0x1357L << 48) | 0x2468_ABCD_1234L,
                -8492013748192034812L,
                0x0000_7FFF_FFFF_FFFFL
        };
        String[] types = {"desert_pyramid", "swamp_hut", "jungle_temple", "igloo", "ruined_portal"};
        for (long worldSeed : testSeeds) {
            List<SeedSolver.StructureClue> clues = generateStructures(worldSeed, types, 1);
            SeedSolver.Clues input = new SeedSolver.Clues(BiomeManager.obfuscateSeed(worldSeed), true, -1,
                    clues, List.of(), List.of());
            SeedSolver.Readiness readiness = SeedSolver.assess(input);
            assertTrue(readiness.ready(), "expected ready: " + readiness);
            SeedSolver.Result res = SeedSolver.solve(input, () -> false);
            assertEquals(SeedSolver.Status.FOUND_WORLD_SEED, res.status(), res.detail());
            assertEquals(worldSeed, res.worldSeed());
        }
    }

    @Test
    void solvesExact64BitWorldSeedFromEndPillarsAndThreeStructures() {
        long worldSeed = (0xBEEFL << 48) | 0x0987_6543_210FL;
        int pillarSeed = EndPillars.pillarSeedOf(worldSeed);
        List<SeedSolver.StructureClue> insufficient = generateStructures(worldSeed, new String[] {"desert_pyramid"}, 1);
        SeedSolver.Clues weak = new SeedSolver.Clues(BiomeManager.obfuscateSeed(worldSeed), true, pillarSeed,
                insufficient, List.of(), List.of());
        assertEquals(SeedSolver.Status.NEED_MORE_CLUES, SeedSolver.solve(weak, () -> false).status());

        List<SeedSolver.StructureClue> clues = generateStructures(worldSeed,
                new String[] {"desert_pyramid", "swamp_hut", "ruined_portal"}, 1);
        SeedSolver.Clues input = new SeedSolver.Clues(BiomeManager.obfuscateSeed(worldSeed), true, pillarSeed,
                clues, List.of(), List.of());
        assertTrue(SeedSolver.assess(input).ready());
        SeedSolver.Result res = SeedSolver.solve(input, () -> false);
        assertEquals(SeedSolver.Status.FOUND_WORLD_SEED, res.status(), res.detail());
        assertEquals(worldSeed, res.worldSeed());
    }

    @Test
    void recoversSeedWhenOneStructureClueIsFalsePositive() {
        long worldSeed = -419283741298374L;
        List<SeedSolver.StructureClue> clues = new ArrayList<>(generateStructures(worldSeed,
                new String[] {"desert_pyramid", "swamp_hut", "jungle_temple", "igloo", "ruined_portal"}, 1));
        // Inject one bogus structure clue inside a valid region range
        SeedSolver.StructureClue valid = clues.get(0);
        clues.add(new SeedSolver.StructureClue("desert_pyramid", valid.chunkX() + 33, valid.chunkZ() + 1));

        SeedSolver.Clues input = new SeedSolver.Clues(BiomeManager.obfuscateSeed(worldSeed), true, -1,
                clues, List.of(), List.of());
        SeedSolver.Result res = SeedSolver.solve(input, () -> false);
        assertEquals(SeedSolver.Status.FOUND_WORLD_SEED, res.status(), res.detail());
        assertEquals(worldSeed, res.worldSeed());
    }

    @Test
    void cancellationStopsPromptlyAndOutpostTreasureHelpersMatch() {
        long worldSeed = 777777777777L;
        List<SeedSolver.StructureClue> clues = generateStructures(worldSeed,
                new String[] {"desert_pyramid", "swamp_hut", "jungle_temple", "igloo", "ruined_portal"}, 1);
        SeedSolver.Clues input = new SeedSolver.Clues(BiomeManager.obfuscateSeed(worldSeed), true, -1,
                clues, List.of(), List.of());
        SeedSolver.Result cancelled = SeedSolver.solve(input, () -> true);
        assertEquals(SeedSolver.Status.CANCELLED, cancelled.status());

        WorldgenRng rng = WorldgenRng.legacy();
        List<SeedSolver.StructureClue> outposts = generateStructures(worldSeed, new String[] {"pillager_outpost"}, 2);
        for (SeedSolver.StructureClue o : outposts) {
            assertTrue(StructureSpread.canStartOutpost(worldSeed, o.chunkX(), o.chunkZ(), rng));
        }
        int treasureHits = 0;
        for (int cx = -20; cx <= 20; cx++) {
            for (int cz = -20; cz <= 20; cz++) {
                if (StructureSpread.isBuriedTreasureChunk(worldSeed, cx, cz, rng)) {
                    treasureHits++;
                }
            }
        }
        assertTrue(treasureHits > 0 && treasureHits < 45);
    }
}
