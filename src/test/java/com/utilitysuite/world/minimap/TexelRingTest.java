package com.utilitysuite.world.minimap;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TexelRingTest {

    private static final TexelRing.PixelSink NO_SINK = (x, y, c) -> { };

    @Test
    void slotWrapsNegativeCoordinates() {
        assertEquals(0, TexelRing.slot(0, 16));
        assertEquals(15, TexelRing.slot(-1, 16));
        assertEquals(1, TexelRing.slot(17, 16));
        assertEquals(0, TexelRing.slot(-16, 16));
        TexelRing ring = new TexelRing(16);
        assertEquals(15 * 16 + 15, ring.index(-1, -1));
        assertEquals(ring.index(3, 5), ring.index(3 + 16, 5 - 32));
    }

    @Test
    void spiralIsCentreOutAndUniqueModSize() {
        int[] spiral = TexelRing.buildSpiral(7);
        assertEquals((0x8000 << 16) | 0x8000, spiral[0]);
        int prev = 0;
        boolean[] seen = new boolean[256];
        for (int packed : spiral) {
            int dx = (packed >>> 16) - 32768;
            int dz = (packed & 0xFFFF) - 32768;
            int d2 = dx * dx + dz * dz;
            assertTrue(d2 >= prev);
            prev = d2;
            int slot = Math.floorMod(dz, 16) * 16 + Math.floorMod(dx, 16);
            assertFalse(seen[slot], "radius size/2-1 never wraps onto itself");
            seen[slot] = true;
        }
    }

    @Test
    void fillsCentreFirstUnderBudget() {
        TexelRing ring = new TexelRing(16);
        int[] count = {0};
        TexelRing.Sampler counting = (x, z) -> {
            count[0]++;
            return 0xFF000000;
        };
        int n = ring.update(100, -50, 5, 10_000, 0, counting, NO_SINK);
        assertEquals(5, n);
        assertTrue(ring.holds(100, -50));
        assertFalse(ring.settled());
        while (!ring.settled()) {
            ring.update(100, -50, 64, 10_000, 0, counting, NO_SINK);
        }
        assertEquals(ring.spiralLength(), count[0], "each texel sampled exactly once");
    }

    @Test
    void recentreOnlyResamplesNewTexels() {
        TexelRing ring = new TexelRing(32);
        while (!ring.settled()) {
            ring.update(0, 0, 4096, 100_000, 0, (x, z) -> 0, NO_SINK);
        }
        int[] sampled = {0};
        // Move one texel east: only the new eastern edge (one column of the disc) is stale.
        while (true) {
            ring.update(1, 0, 4096, 100_000, 0, (x, z) -> {
                sampled[0]++;
                assertTrue(x > 0, "only eastern texels are new");
                return 0;
            }, NO_SINK);
            if (ring.settled()) {
                break;
            }
        }
        assertTrue(sampled[0] > 0 && sampled[0] < 40, "edge only, got " + sampled[0]);
        assertTrue(ring.holds(1, 0));
        assertTrue(ring.holds(1 + 15, 0));
    }

    @Test
    void sinkReceivesRingSlotCoordinates() {
        TexelRing ring = new TexelRing(16);
        int[] last = new int[2];
        ring.update(-1, -1, 1, 10, 0, (x, z) -> 0, (x, y, c) -> {
            last[0] = x;
            last[1] = y;
        });
        assertEquals(15, last[0]);
        assertEquals(15, last[1]);
    }

    @Test
    void tickUpdateDoesNotAllocate() {
        assumeTrue(AllocationProbe.supported());
        TexelRing ring = new TexelRing(256);
        TexelRing.Sampler sampler = (x, z) -> x ^ z;
        int[] pos = {0};
        long bytes = AllocationProbe.measure(2_000, () -> {
            pos[0]++;
            ring.update(pos[0] >> 3, 7, 512, 65_536, 64, sampler, NO_SINK);
        });
        assertTrue(bytes < 2_000, "allocated " + bytes + " bytes");
    }
}
