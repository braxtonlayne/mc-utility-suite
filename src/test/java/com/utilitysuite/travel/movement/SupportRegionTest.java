package com.utilitysuite.travel.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SupportRegion} on hand-built box grids: fence post, wall, pane, full block. */
class SupportRegionTest {

    /** A shaped grid: per cell a list of block-local boxes (minX, minY, minZ, maxX, maxY, maxZ). */
    static final class ShapeGrid implements MovementPhysics.Voxels {
        static final float[] FULL = {0, 0, 0, 1, 1, 1};
        static final float[] FENCE_POST = {6 / 16f, 0, 6 / 16f, 10 / 16f, 1.5f, 10 / 16f};
        static final float[] WALL_POST = {4 / 16f, 0, 4 / 16f, 12 / 16f, 1.5f, 12 / 16f};
        static final float[] PANE_POST = {7 / 16f, 0, 7 / 16f, 9 / 16f, 1f, 9 / 16f};

        private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<float[]> cells =
                new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();

        ShapeGrid put(int x, int y, int z, float[] boxes) {
            cells.put(key(x, y, z), boxes);
            return this;
        }

        ShapeGrid fill(int x0, int y0, int z0, int x1, int y1, int z1, float[] boxes) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        put(x, y, z, boxes);
                    }
                }
            }
            return this;
        }

        private static long key(int x, int y, int z) {
            return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
        }

        private float[] at(int x, int y, int z) {
            return cells.get(key(x, y, z));
        }

        @Override
        public boolean solid(int x, int y, int z) {
            return at(x, y, z) != null;
        }

        @Override
        public boolean fullBlock(int x, int y, int z) {
            return at(x, y, z) == FULL;
        }

        @Override
        public boolean shaped() {
            return true;
        }

        @Override
        public int boxCount(int x, int y, int z) {
            float[] b = at(x, y, z);
            return b == null ? 0 : b.length / 6;
        }

        @Override
        public void box(int x, int y, int z, int i, float[] out, int off) {
            System.arraycopy(at(x, y, z), 6 * i, out, off, 6);
        }
    }

    private static SupportRegion region(ShapeGrid g, double x, double top, double z) {
        SupportRegion r = new SupportRegion();
        assertTrue(r.build(g, x, top, z), "a top at " + top);
        return r;
    }

    @Test
    void fencePostMarginIsHalfThePostPlusTheBodyHalfWidth() {
        ShapeGrid g = new ShapeGrid().put(0, 19, 0, ShapeGrid.FENCE_POST);
        SupportRegion probe = new SupportRegion();
        assertEquals(20.5, probe.topOf(g, 0, 19, 0), 1e-6, "a fence post stands 1.5 high");
        SupportRegion r = region(g, 0.5, 20.5, 0.5);
        assertEquals(1, r.count());
        assertEquals(0.125 + 0.3, r.margin(0.5, 0.5), 1e-3);
        assertEquals(0.025, r.margin(0.9, 0.5), 1e-3);
        assertEquals(-0.075, r.margin(1.0, 0.5), 1e-3, "off the post");
        assertFalse(r.wide(0.5, 0.5), "a fence post is always narrow");
        assertEquals(0, new SupportRegion().build(g, 0.5, 20.0, 0.5) ? 1 : 0, "no top at the cube height");
    }

    @Test
    void wallAndPaneMargins() {
        ShapeGrid wall = new ShapeGrid().put(0, 19, 0, ShapeGrid.WALL_POST);
        assertEquals(0.25 + 0.3, region(wall, 0.5, 20.5, 0.5).margin(0.5, 0.5), 1e-3);
        ShapeGrid pane = new ShapeGrid().put(0, 19, 0, ShapeGrid.PANE_POST);
        SupportRegion p = region(pane, 0.5, 20.0, 0.5);
        assertEquals(0.0625 + 0.3, p.margin(0.5, 0.5), 1e-3);
        assertFalse(p.wide(0.5, 0.5));
    }

    @Test
    void fullBlockPillarRowAndPlatform() {
        ShapeGrid one = new ShapeGrid().put(0, 19, 0, ShapeGrid.FULL);
        SupportRegion r = region(one, 0.5, 20.0, 0.5);
        assertEquals(0.8, r.margin(0.5, 0.5), 1e-3);
        assertTrue(r.contains(1.29, 0.5));
        assertFalse(r.contains(1.31, 0.5));
        ShapeGrid row = new ShapeGrid().fill(0, 19, 0, 0, 19, 4, ShapeGrid.FULL);
        SupportRegion rr = region(row, 0.5, 20.0, 2.5);
        assertEquals(0.8, rr.margin(0.5, 2.5), 1e-3, "adjacent tops merge: the strip is as deep as it is wide");
        assertEquals(-0.1, rr.margin(-0.4, 2.5), 1e-3);
        ShapeGrid plat = new ShapeGrid().fill(-1, 19, -1, 1, 19, 1, ShapeGrid.FULL);
        SupportRegion pr = region(plat, 0.5, 20.0, 0.5);
        assertTrue(pr.wide(0.5, 0.5), "3x3 platform, centre");
        assertTrue(pr.margin(0.5, 0.5) > 1.7);
        assertFalse(pr.wide(1.9, 0.5), "near the edge of the platform");
    }

    @Test
    void aBlockStandingOnATopHidesIt() {
        ShapeGrid g = new ShapeGrid().put(0, 19, 0, ShapeGrid.FULL).put(0, 20, 0, ShapeGrid.FULL)
                .put(1, 19, 0, ShapeGrid.FULL);
        SupportRegion r = region(g, 0.5, 20.0, 0.5);
        assertEquals(1, r.count(), "only the uncovered neighbour");
        assertFalse(r.contains(0.5, 0.5));
        assertTrue(r.contains(1.5, 0.5));
    }

    @Test
    void cubeGridsAnswerThroughTheDefaultBoxes() {
        MovementPhysicsTest.Grid g = new MovementPhysicsTest.Grid();
        g.fill(0, 19, 0, 0, 19, 0);
        SupportRegion r = new SupportRegion();
        assertTrue(r.build(g, 0.5, 20.0, 0.5));
        assertEquals(0.8, r.margin(0.5, 0.5), 1e-3);
    }

    @Test
    void stopPointsFollowTheReplicaFriction() {
        SupportRegion r = new SupportRegion();
        r.computeStop(0.5, 0.5, 0, 0.25, 0, 0, 0f, false, 0.1, 0.6, false);
        double f = 0.6 * 0.91;
        assertEquals(0.5 + 0.25 / (1 - f), r.stopZ(), 0.005, "no keys: geometric slide");
        assertEquals(0.5, r.stopX(), 1e-9);
        r.computeStop(0.5, 0.5, 0, 0.25, 0, 0, 0f, false, 0.1, 0.98, false);
        assertEquals(0.5 + 0.25 / (1 - 0.98 * 0.91), r.stopZ(), 0.01, "ice slides much farther");
        r.computeStop(0.5, 0.5, 0, 0, 1, 0, 0f, true, 0.1, 0.6, false);
        assertTrue(r.stopZ() > 4.0, "forward held keeps going");
        r.computeStop(0.5, 0.5, 0, 0, 1, 0, 0f, true, 0.1, 0.6, true);
        double sneaking = r.stopZ();
        assertTrue(sneaking > 0.5 && sneaking < 2.5, "sneak scales the input: " + sneaking);
    }

    @Test
    void stableNeedsLowSpeedAndAStopPointInside() {
        ShapeGrid g = new ShapeGrid().put(0, 19, 0, ShapeGrid.FENCE_POST);
        SupportRegion r = region(g, 0.5, 20.5, 0.5);
        assertTrue(r.stable(0.5, 0.5, 0, 0.01, 0.6));
        assertFalse(r.stable(0.5, 0.5, 0, 0.2, 0.6), "too fast");
        assertFalse(r.stable(0.88, 0.5, 0.02, 0, 0.6), "slides past the margin");
    }

    @Test
    void marginAndWideAllocateNothing() {
        org.junit.jupiter.api.Assumptions.assumeTrue(com.utilitysuite.core.AllocationProbe.supported());
        ShapeGrid g = new ShapeGrid().fill(-1, 19, -1, 1, 19, 1, ShapeGrid.FULL).put(3, 19, 0, ShapeGrid.FENCE_POST);
        SupportRegion r = new SupportRegion();
        double[] sink = new double[1];
        long bytes = com.utilitysuite.core.AllocationProbe.measure(500, () -> {
            r.build(g, 0.5, 20.0, 0.5);
            sink[0] += r.margin(0.7, 0.3) + (r.wide(0.5, 0.5) ? 1 : 0);
            r.computeStop(0.5, 0.5, 0.1, 0.2, 1, 0, 10f, true, 0.1, 0.6, false);
        });
        assertTrue(bytes < 1_024, "allocated " + bytes + " bytes");
    }
}
