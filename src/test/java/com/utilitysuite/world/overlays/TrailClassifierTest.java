package com.utilitysuite.world.overlays;

import com.utilitysuite.world.BlockPosPacking;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrailClassifierTest {

    private final TrailClassifier c = new TrailClassifier();
    private final int[] xs = new int[4096];
    private final int[] zs = new int[4096];
    private int n;

    private void add(int x, int z) {
        xs[n] = x;
        zs[n] = z;
        n++;
    }

    @Test
    void sparseLongStraightLineIsElytraHighway() {
        for (int i = 0; i < 60; i += 2) { // every other chunk along +X
            add(i, i % 4 == 0 ? 0 : 1);
        }
        TrailClassifier.Segment s = c.classify(xs, zs, n, null, null, false, -10, 0);
        assertEquals(TrailClassifier.Kind.ELYTRA_HIGHWAY, s.kind());
        assertTrue(s.dirX() > 0.95, "oriented away from the viewer at -X");
        assertTrue(s.spineX().length >= 2 && s.spineX()[0] < s.spineX()[s.spineX().length - 1]);
    }

    @Test
    void solidLineOnWaterIsBoatRunAndTicksOrientIt() {
        boolean[] water = new boolean[64];
        long[] ticks = new long[64];
        for (int i = 0; i < 30; i++) {
            add(5, i);
            water[n - 1] = true;
            ticks[n - 1] = 1000 - i * 10L; // newest at z = 0
        }
        TrailClassifier.Segment s = c.classify(xs, zs, n, water, ticks, false, 5, 100);
        assertEquals(TrailClassifier.Kind.BOAT_RUN, s.kind());
        assertTrue(s.dirZ() < -0.95, "points toward the newest cells");
        assertEquals(1000L, s.latestTick());
    }

    @Test
    void straightNetherLineIsTunnelWithOverworldScale() {
        for (int i = 0; i < 20; i++) {
            add(i, i);
        }
        TrailClassifier.Segment s = c.classify(xs, zs, n, null, null, true, 0, 0);
        assertEquals(TrailClassifier.Kind.NETHER_TUNNEL, s.kind());
        assertTrue(s.lengthChunks() > 8 * 20);
    }

    @Test
    void parallelLanesAreSearchZigzag() {
        for (int lane = 0; lane < 4; lane++) {
            int z = lane * 4;
            for (int x = 0; x < 24; x++) {
                add(x, z);
            }
            if (lane < 3) { // turn connector
                int x = lane % 2 == 0 ? 23 : 0;
                for (int dz = 1; dz < 4; dz++) {
                    add(x, z + dz);
                }
            }
        }
        TrailClassifier.Segment s = c.classify(xs, zs, n, null, null, false, 0, 0);
        assertEquals(TrailClassifier.Kind.SEARCH_ZIGZAG, s.kind());
        assertEquals(4, s.lanes());
    }

    @Test
    void blobIsWander() {
        for (int x = 0; x < 6; x++) {
            for (int z = 0; z < 6; z++) {
                add(x, z);
            }
        }
        assertEquals(TrailClassifier.Kind.WANDER, c.classify(xs, zs, n, null, null, false, 0, 0).kind());
    }

    @Test
    void clusteringSplitsDisconnectedTrails() {
        long[] keys = {
                BlockPosPacking.chunk(0, 0), BlockPosPacking.chunk(1, 1), BlockPosPacking.chunk(2, 2),
                BlockPosPacking.chunk(10, 10), BlockPosPacking.chunk(-5, 3)};
        int[] label = TrailClassifier.cluster(keys, keys.length);
        assertEquals(label[0], label[1]);
        assertEquals(label[1], label[2]);
        assertTrue(label[3] != label[0] && label[4] != label[0] && label[3] != label[4]);
    }

    @Test
    void catmullRomPassesThroughControlPoints() {
        float[] px = {0, 10, 20};
        float[] pz = {0, 5, 0};
        float[] ox = new float[32];
        float[] oz = new float[32];
        int w = TrailClassifier.catmullRom(px, pz, 3, 8, ox, oz);
        assertEquals(17, w);
        assertEquals(0f, ox[0], 1e-5);
        assertEquals(10f, ox[8], 1e-5);
        assertEquals(5f, oz[8], 1e-5);
        assertEquals(20f, ox[16], 1e-5);
    }
}
