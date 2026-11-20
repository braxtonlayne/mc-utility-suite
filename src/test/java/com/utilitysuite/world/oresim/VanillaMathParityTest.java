package com.utilitysuite.world.oresim;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The simulator's random and biome-zoom math against the real vanilla 1.21.1 classes (they load without a game
 * bootstrap): {@code WorldgenRandom} over xoroshiro (decoration) and legacy (structure placement), and
 * {@code BiomeManager.getBiome}'s quart selection.
 */
class VanillaMathParityTest {

    @Test
    void decorationStreamMatchesVanilla() {
        Random seeds = new Random(42);
        for (int t = 0; t < 200; t++) {
            long worldSeed = seeds.nextLong();
            int bx = (seeds.nextInt(20000) - 10000) * 16;
            int bz = (seeds.nextInt(20000) - 10000) * 16;
            int index = seeds.nextInt(40);
            int step = seeds.nextInt(11);

            WorldgenRandom vanilla = new WorldgenRandom(new XoroshiroRandomSource(0L));
            long vDeco = vanilla.setDecorationSeed(worldSeed, bx, bz);
            vanilla.setFeatureSeed(vDeco, index, step);

            WorldgenRng ours = WorldgenRng.decoration();
            long oDeco = ours.setDecorationSeed(worldSeed, bx, bz);
            ours.setFeatureSeed(oDeco, index, step);

            assertEquals(vDeco, oDeco, "decoration seed");
            for (int i = 0; i < 64; i++) {
                switch (i % 5) {
                    case 0 -> assertEquals(vanilla.nextInt(16), ours.nextInt(16));
                    case 1 -> assertEquals(vanilla.nextFloat(), ours.nextFloat());
                    case 2 -> assertEquals(vanilla.nextDouble(), ours.nextDouble());
                    case 3 -> assertEquals(vanilla.nextInt(3), ours.nextInt(3));
                    default -> assertEquals(vanilla.nextInt(1000) , ours.nextInt(1000));
                }
            }
            assertEquals(vanilla.nextLong(), ours.nextLong());
        }
    }

    @Test
    void legacyStructureStreamMatchesVanilla() {
        Random seeds = new Random(7);
        for (int t = 0; t < 200; t++) {
            long worldSeed = seeds.nextLong();
            int rx = seeds.nextInt(2000) - 1000;
            int rz = seeds.nextInt(2000) - 1000;
            int salt = seeds.nextInt(100_000_000);
            WorldgenRandom vanilla = new WorldgenRandom(new LegacyRandomSource(0L));
            vanilla.setLargeFeatureWithSalt(worldSeed, rx, rz, salt);
            WorldgenRng ours = WorldgenRng.legacy();
            ours.setLargeFeatureWithSalt(worldSeed, rx, rz, salt);
            for (int i = 0; i < 16; i++) {
                assertEquals(vanilla.nextInt(27), ours.nextInt(27));
                assertEquals(vanilla.nextInt(32), ours.nextInt(32)); // power-of-two path
            }
        }
    }

    @Test
    void biomeZoomSelectsTheSameQuartAsVanilla() {
        Random r = new Random(99);
        for (int t = 0; t < 2000; t++) {
            long seed = r.nextLong();
            long zoomSeed = BiomeManager.obfuscateSeed(seed);
            int x = r.nextInt(200_000) - 100_000;
            int y = r.nextInt(384) - 64;
            int z = r.nextInt(200_000) - 100_000;
            int[] picked = new int[3];
            BiomeManager manager = new BiomeManager((qx, qy, qz) -> {
                picked[0] = qx;
                picked[1] = qy;
                picked[2] = qz;
                return null;
            }, zoomSeed);
            manager.getBiome(new BlockPos(x, y, z));
            long q = QuartBiomeOracle.selectQuart(zoomSeed, x, y, z);
            assertEquals(picked[0], (int) (q >> 42), "quart x");
            assertEquals(picked[1], (int) ((q << 22) >> 43), "quart y");
            assertEquals(picked[2], (int) ((q << 43) >> 43), "quart z");
        }
    }
}
