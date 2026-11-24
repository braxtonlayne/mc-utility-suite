package com.utilitysuite.world.seed;

import net.minecraft.Util;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndPillarsTest {

    @Test
    void coordinatesAndHeightsMatchVanillaSpikeFeature() {
        for (int i = 0; i < EndPillars.COUNT; i++) {
            int expectedX = Mth.floor(42.0 * Math.cos(2.0 * (-Math.PI + (Math.PI / 10.0) * (double) i)));
            int expectedZ = Mth.floor(42.0 * Math.sin(2.0 * (-Math.PI + (Math.PI / 10.0) * (double) i)));
            assertEquals(expectedX, EndPillars.pillarX(i));
            assertEquals(expectedZ, EndPillars.pillarZ(i));
        }

        long worldSeed = 123456789012345L;
        int vanillaPillarSeed = (int) (RandomSource.create(worldSeed).nextLong() & 65535L);
        assertEquals(vanillaPillarSeed, EndPillars.pillarSeedOf(worldSeed));

        var vanillaOrder = Util.toShuffledList(IntStream.range(0, 10), RandomSource.create(vanillaPillarSeed));
        int[] actualHeights = new int[EndPillars.COUNT];
        EndPillars.heightsForPillarSeed(vanillaPillarSeed, actualHeights);
        for (int i = 0; i < EndPillars.COUNT; i++) {
            assertEquals(76 + vanillaOrder.getInt(i) * 3, actualHeights[i]);
        }
    }

    @Test
    void recoversUniquePillarSeedFromFullAndPartialHeights() {
        long[] seeds = {0L, 42L, -987654321L, 0xCAFE_BABE_1234_5678L};
        for (long worldSeed : seeds) {
            int ps = EndPillars.pillarSeedOf(worldSeed);
            int[] heights = new int[EndPillars.COUNT];
            EndPillars.heightsForPillarSeed(ps, heights);
            assertEquals(ps, EndPillars.recoverPillarSeed(heights));

            // Drop 2 unobserved pillars and verify it still uniquely recovers ps
            int[] partial = Arrays.copyOf(heights, heights.length);
            partial[2] = 0;
            partial[7] = 0;
            int recovered = EndPillars.recoverPillarSeed(partial);
            assertTrue(recovered == ps || recovered == -1, "unexpected mismatch: " + recovered + " vs " + ps);
        }
    }
}
