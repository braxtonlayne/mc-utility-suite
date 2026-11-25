package com.utilitysuite.world.spawn;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlimeAfkFinderTest {

    private static final long SEED = -4_172_144_997_891_116_543L;

    /** The 1.21.1 formula as written with hex constants, through java.util.Random. */
    private static boolean formula(long seed, int x, int z) {
        Random r = new Random(seed + (long) (x * x * 0x4c1906) + (long) (x * 0x5ac0db) + (long) (z * z) * 0x4307a7L
                + (long) (z * 0x5f24f) ^ 0x3ad8025fL);
        return r.nextInt(10) == 0;
    }

    @Test
    void pinnedSlimeChunksMatchTheFormula() {
        int found = 0;
        for (int x = -20; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) {
                boolean want = formula(SEED, x, z);
                assertEquals(want, SlimeChunks.isSlimeChunk(SEED, x, z), x + "," + z);
                found += want ? 1 : 0;
            }
        }
        assertTrue(found > 0);
    }

    @Test
    void diskStaysInsideTheSpawnSphereAndSkipsTheNoSpawnZone() {
        int[] d = SlimeAfkFinder.disk();
        assertTrue(d.length / 2 > 150 && d.length / 2 < 200, "cells " + d.length / 2);
        for (int i = 0; i < d.length; i += 2) {
            long d2 = (long) (d[i] * 16) * (d[i] * 16) + (long) (d[i + 1] * 16) * (d[i + 1] * 16);
            assertTrue(d2 <= 120L * 120 && d2 > 24L * 24);
        }
    }

    @Test
    void bestSpotIsTheBruteForceMaximum() {
        int cx = 10;
        int cz = -7;
        int search = 6;
        List<SlimeAfkFinder.Spot> spots = SlimeAfkFinder.best(SEED, cx, cz, search, 3, 8);
        int max = 0;
        for (int dx = -search; dx <= search; dx++) {
            for (int dz = -search; dz <= search; dz++) {
                max = Math.max(max, SlimeAfkFinder.countAround(SEED, cx + dx, cz + dz));
            }
        }
        assertEquals(max, spots.get(0).slimeChunks());
        assertEquals(max, SlimeAfkFinder.countAround(SEED, spots.get(0).chunkX(), spots.get(0).chunkZ()));
        for (int i = 1; i < spots.size(); i++) {
            assertTrue(spots.get(i).slimeChunks() <= spots.get(i - 1).slimeChunks());
            SlimeAfkFinder.Spot a = spots.get(0);
            SlimeAfkFinder.Spot b = spots.get(i);
            assertTrue(Math.max(Math.abs(a.chunkX() - b.chunkX()), Math.abs(a.chunkZ() - b.chunkZ())) >= 8);
        }
        assertEquals(spots.get(0).chunkX() * 16 + 8, spots.get(0).blockX());
    }
}
