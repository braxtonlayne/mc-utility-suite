package com.utilitysuite.world.seed;

import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockPatternVerifierTest {

    @Test
    void matchesVanillaXoroshiroPositionalRandomAcrossAllLayers() {
        long worldSeed = -5123456789012345678L;
        PositionalRandomFactory base = new XoroshiroRandomSource(worldSeed).forkPositional();
        PositionalRandomFactory floor = base.fromHashOf(Identifier.withDefaultNamespace("bedrock_floor")).forkPositional();
        PositionalRandomFactory roof = base.fromHashOf(Identifier.withDefaultNamespace("bedrock_roof")).forkPositional();
        PositionalRandomFactory deepslate = base.fromHashOf(Identifier.withDefaultNamespace("deepslate")).forkPositional();

        for (int i = 0; i < 12; i++) {
            int x = -120 + i * 37;
            int z = 85 - i * 29;
            for (int y = -63; y <= -60; y++) {
                RandomSource r = floor.at(x, y, z);
                boolean expected = (double) r.nextFloat() < ((-59.0 - y) / 5.0);
                assertEquals(expected, BedrockPatternVerifier.predict(
                        worldSeed, BedrockPatternVerifier.Layer.OVERWORLD_FLOOR, x, y, z));
            }
            for (int y = 1; y <= 4; y++) {
                RandomSource r = floor.at(x, y, z);
                boolean expected = (double) r.nextFloat() < ((5.0 - y) / 5.0);
                assertEquals(expected, BedrockPatternVerifier.predict(
                        worldSeed, BedrockPatternVerifier.Layer.NETHER_FLOOR, x, y, z));
            }
            for (int y = 123; y <= 126; y++) {
                RandomSource r = roof.at(x, y, z);
                boolean expected = !((double) r.nextFloat() < ((127.0 - y) / 5.0));
                assertEquals(expected, BedrockPatternVerifier.predict(
                        worldSeed, BedrockPatternVerifier.Layer.NETHER_ROOF, x, y, z));
            }
            for (int y = 1; y <= 7; y += 2) {
                RandomSource r = deepslate.at(x, y, z);
                boolean expected = (double) r.nextFloat() < ((8.0 - y) / 8.0);
                assertEquals(expected, BedrockPatternVerifier.predict(
                        worldSeed, BedrockPatternVerifier.Layer.OVERWORLD_DEEPSLATE, x, y, z));
            }
        }
    }

    @Test
    void lifts48BitSeedToUnique64BitWorldSeedFromBedrockSamples() {
        long worldSeed = (0xCAFEBABEL << 32) | 0x1234_5678L;
        long structureSeed48 = worldSeed & SeedSolver.MASK48;

        List<BedrockPatternVerifier.Sample> samples = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            int x = i * 7 - 50;
            int z = i * 11 + 20;
            int y = -62 + (i & 3);
            if (y == -59) {
                y = -63;
            }
            boolean present = BedrockPatternVerifier.predict(
                    worldSeed, BedrockPatternVerifier.Layer.OVERWORLD_FLOOR, x, y, z);
            samples.add(new BedrockPatternVerifier.Sample(
                    BedrockPatternVerifier.Layer.OVERWORLD_FLOOR, x, y, z, present));
        }

        assertTrue(BedrockPatternVerifier.verifyWorldSeed(worldSeed, samples));
        assertFalse(BedrockPatternVerifier.verifyWorldSeed(worldSeed ^ (1L << 48), samples));

        OptionalLong lifted = BedrockPatternVerifier.liftToWorldSeed(structureSeed48, samples, () -> false);
        assertTrue(lifted.isPresent());
        assertEquals(worldSeed, lifted.getAsLong());
    }
}
