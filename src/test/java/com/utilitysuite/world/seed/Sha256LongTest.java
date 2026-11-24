package com.utilitysuite.world.seed;

import net.minecraft.world.level.biome.BiomeManager;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Sha256LongTest {

    @Test
    void matchesVanillaBiomeManagerObfuscateSeedAcrossEdgeAndRandomSeeds() {
        long[] edges = {0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 0x1234_5678_9ABC_DEF0L, -98765432101234567L};
        for (long s : edges) {
            assertEquals(BiomeManager.obfuscateSeed(s), Sha256Long.hashLong(s), "edge seed " + s);
        }
        SplittableRandom rng = new SplittableRandom(42L);
        for (int i = 0; i < 1000; i++) {
            long s = rng.nextLong();
            assertEquals(BiomeManager.obfuscateSeed(s), Sha256Long.hashLong(s));
        }
    }

    @Test
    void lifts48BitStructureSeedToExact64BitWorldSeed() {
        long worldSeed = (0xABCDL << 48) | 0x1234_5678_9ABCL;
        long low48 = worldSeed & SeedSolver.MASK48;
        long hashed = BiomeManager.obfuscateSeed(worldSeed);
        OptionalLong lifted = Sha256Long.liftToWorldSeed(low48, hashed, () -> false);
        assertTrue(lifted.isPresent());
        assertEquals(worldSeed, lifted.getAsLong());

        long negWorldSeed = -7421983741298374123L;
        OptionalLong liftedNeg = Sha256Long.liftToWorldSeed(negWorldSeed & SeedSolver.MASK48,
                BiomeManager.obfuscateSeed(negWorldSeed), () -> false);
        assertTrue(liftedNeg.isPresent());
        assertEquals(negWorldSeed, liftedNeg.getAsLong());
    }
}
