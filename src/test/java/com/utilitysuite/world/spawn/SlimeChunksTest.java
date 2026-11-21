package com.utilitysuite.world.spawn;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.world.render.LineSink;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlimeChunksTest {

    /** The vanilla expression, written the way the game writes it, with a real java.util.Random. */
    private static boolean reference(long seed, int x, int z) {
        Random r = new Random(seed + (long) (x * x * 4987142) + (long) (x * 5947611) + (long) (z * z) * 4392871L
                + (long) (z * 389711) ^ 987234911L);
        return r.nextInt(10) == 0;
    }

    @Test
    void matchesJavaUtilRandomReference() {
        long[] seeds = {0L, 1L, -1L, 12345L, 8_675_309L, Long.MIN_VALUE, Long.MAX_VALUE, -4_172_144_997_891_116_543L};
        int slimes = 0;
        int total = 0;
        for (long seed : seeds) {
            for (int x = -40; x <= 40; x++) {
                for (int z = -40; z <= 40; z++) {
                    boolean want = reference(seed, x, z);
                    assertEquals(want, SlimeChunks.isSlimeChunk(seed, x, z), seed + " @ " + x + "," + z);
                    slimes += want ? 1 : 0;
                    total++;
                }
            }
        }
        double share = slimes / (double) total;
        assertTrue(share > 0.08 && share < 0.12, "share " + share); // about one chunk in ten
    }

    @Test
    void largeCoordinatesOverflowLikeTheGame() {
        for (int x : new int[] {1_875_000, -1_875_000, 46_341, 100_000}) {
            assertEquals(reference(42L, x, -x), SlimeChunks.isSlimeChunk(42L, x, -x));
        }
    }

    @Test
    void noAllocation() {
        if (!AllocationProbe.supported()) {
            return;
        }
        long bytes = AllocationProbe.measure(20_000, () -> {
            for (int i = 0; i < 16; i++) {
                SlimeChunks.isSlimeChunk(12345L, i, -i);
            }
        });
        assertTrue(bytes < 1024, "allocated " + bytes);
    }

    @Test
    void slimeOutlineEmitterDoesNotAllocate() {
        long[] chunks = new long[40];
        for (int i = 0; i < chunks.length; i++) {
            chunks[i] = ((long) i << 32) | ((-i) & 0xFFFFFFFFL);
        }
        int[] count = new int[1];
        LineSink sink = (x1, y1, z1, x2, y2, z2, argb) -> count[0]++;
        SpawnGeometryModule.emitSlime(sink, chunks, chunks.length, 1f, 3.0, 4.0);
        assertEquals(4 * chunks.length, count[0]);
        if (!AllocationProbe.supported()) {
            return;
        }
        long bytes = AllocationProbe.measure(5_000,
                () -> SpawnGeometryModule.emitSlime(sink, chunks, chunks.length, 1f, 3.0, 4.0));
        assertTrue(bytes < 1024, "allocated " + bytes);
    }
}
