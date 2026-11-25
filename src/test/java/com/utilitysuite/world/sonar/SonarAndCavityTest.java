package com.utilitysuite.world.sonar;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.world.BlockPosPacking;
import com.utilitysuite.world.render.LineSink;
import com.utilitysuite.world.render.Shapes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SonarAndCavityTest {

    // ---- wavefront timing -----------------------------------------------------------------------------------------

    @Test
    void wavefrontExpandsAndFades() {
        Wavefront w = new Wavefront();
        assertEquals(-1f, w.radius(0));
        w.start(1000, 20f, 40f, 1500f);
        assertEquals(0f, w.radius(1000), 1e-6);
        assertEquals(10f, w.radius(1500), 1e-4);
        assertEquals(40f, w.radius(9000), 1e-4); // capped at the range
        // Point at 10 blocks: dark before the front, full when hit, half after 750 ms, gone after 1500 ms.
        assertEquals(0f, w.alpha(10f, 1400), 1e-6);
        assertEquals(1f, w.alpha(10f, 1500), 1e-4);
        assertEquals(0.5f, w.alpha(10f, 2250), 1e-4);
        assertEquals(0f, w.alpha(10f, 3000), 1e-6);
        assertEquals(0f, w.alpha(50f, 9000), 1e-6); // beyond range never lights
        assertTrue(w.expanding(2000));
        assertTrue(!w.expanding(3000) && w.active(3000)); // front done at 3000 ms, last points still fading
        assertTrue(!w.active(1000 + 2000 + 1500));
        assertEquals(10f, w.minVisible(1000 + 1500 + 500), 1e-4);
    }

    // ---- surface extraction ---------------------------------------------------------------------------------------

    /** 16^3 box: solid below y=4 (local), air above; one spawner buried at (8,1,8). */
    private static VoxelGrid floorGrid() {
        VoxelGrid g = new VoxelGrid();
        g.reset(0, 0, 0, 16, 16, 16);
        g.fill(0, 0, 0, 16, 4, 16, VoxelGrid.SOLID);
        g.fill(0, 4, 0, 16, 12, 16, VoxelGrid.AIR);
        g.set(8, 1, 8, VoxelGrid.SPAWNER);
        return g;
    }

    @Test
    void surfacePointsAreSolidBlocksTouchingAir() {
        VoxelGrid g = floorGrid();
        PointCloud c = SurfaceExtractor.extract(g, 8, 5, 8, 6, 10_000, true, null, null, 0);
        int surface = 0;
        int spawners = 0;
        float last = -1f;
        for (int i = 0; i < c.size(); i++) {
            long p = c.pos(i);
            assertTrue((int) c.dist(i) >= (int) last, "ordered by distance shell");
            last = c.dist(i);
            if (c.kind(i) == PointCloud.SPAWNER) {
                spawners++;
                assertEquals(BlockPosPacking.pack(8, 1, 8), p);
            } else {
                surface++;
                assertEquals(3, BlockPosPacking.y(p), "only the top solid layer is a surface");
            }
        }
        assertEquals(1, spawners);
        // Top layer y=3 within 6 of (8,5,8): dy=2, so horizontal radius^2 <= 32.
        int expected = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if ((x - 8) * (x - 8) + (z - 8) * (z - 8) <= 32) {
                    expected++;
                }
            }
        }
        assertEquals(expected, surface);
    }

    @Test
    void capKeepsNearestPointsAndExtraMarks() {
        VoxelGrid g = floorGrid();
        long[] ores = {BlockPosPacking.pack(8, 2, 8), BlockPosPacking.pack(100, 2, 100)};
        byte[] kinds = {PointCloud.ORE, PointCloud.ORE};
        PointCloud all = SurfaceExtractor.extract(g, 8, 4, 8, 12, 100_000, false, ores, kinds, 2);
        PointCloud capped = SurfaceExtractor.extract(g, 8, 4, 8, 12, 20, false, ores, kinds, 2);
        assertEquals(20, capped.size());
        float maxKept = 0f;
        for (int i = 0; i < capped.size(); i++) {
            maxKept = Math.max(maxKept, capped.dist(i));
        }
        int nearer = 0;
        int ore = 0;
        for (int i = 0; i < all.size(); i++) {
            if ((int) all.dist(i) < (int) maxKept) {
                nearer++;
            }
            if (all.kind(i) == PointCloud.ORE) {
                ore++;
            }
        }
        assertTrue(nearer <= 20, "every shell nearer than the cut is kept in full");
        assertEquals(1, ore, "out-of-range prediction dropped, in-range one kept");
        assertEquals(capped.size(), capped.firstAtOrBeyond(13f));
        assertEquals(0, capped.firstAtOrBeyond(0f));
    }

    @Test
    void emitterLightsOnlyThePassedShellAndDoesNotAllocate() {
        VoxelGrid g = floorGrid();
        PointCloud c = SurfaceExtractor.extract(g, 8, 4, 8, 12, 100_000, true, null, null, 0);
        Wavefront w = new Wavefront();
        w.start(0, 10f, 12f, 1000f);
        int[] lines = new int[1];
        LineSink sink = (x1, y1, z1, x2, y2, z2, argb) -> lines[0]++;
        int drawn = c.emit(sink, w, 500, 8, 70, 8, 0xFFFFFF, 8, 4, 8); // radius 5
        int expected = 0;
        for (int i = 0; i < c.size(); i++) {
            if (c.dist(i) <= 5f) {
                expected++;
            }
        }
        assertEquals(expected, drawn);
        assertEquals(drawn * 3 + Shapes.SEGMENTS, lines[0]);
        assertEquals(0, c.emit(sink, w, 10_000, 8, 70, 8, 0xFFFFFF, 8, 4, 8));
        if (!AllocationProbe.supported()) {
            return;
        }
        long bytes = AllocationProbe.measure(2_000, () -> c.emit(sink, w, 900, 8, 70, 8, 0xFFFFFF, 8, 4, 8));
        assertTrue(bytes < 1024, "allocated " + bytes);
    }

    // ---- cavity tomography ----------------------------------------------------------------------------------------

    /** 20^3 solid box with the sky above local y=15 (open air on top), with carved rooms. */
    private static VoxelGrid solidGrid() {
        VoxelGrid g = new VoxelGrid();
        g.reset(100, 0, 200, 20, 20, 20);
        g.fill(0, 0, 0, 20, 16, 20, VoxelGrid.SOLID);
        g.fill(0, 16, 0, 20, 4, 20, VoxelGrid.AIR);
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 20; z++) {
                g.setSkyY(x, z, 16);
            }
        }
        return g;
    }

    @Test
    void boxVoidBecomesTwelveEdges() {
        VoxelGrid g = solidGrid();
        g.fill(2, 2, 2, 4, 3, 5, VoxelGrid.AIR); // 4x3x5 room = 60 cells, 14 below the sky
        CavityTomography t = new CavityTomography();
        CavityTomography.Model m = t.scan(g, 8, 100_000);
        assertEquals(1, m.voids);
        assertEquals(60, m.voidCells);
        assertEquals(12, m.segments(), "a cuboid's contour is its 12 edges, merged into straight runs");
        int total = 0;
        for (int i = 0; i < m.segments(); i++) {
            total += Math.abs(m.coord(i, 3) - m.coord(i, 0)) + Math.abs(m.coord(i, 4) - m.coord(i, 1))
                    + Math.abs(m.coord(i, 5) - m.coord(i, 2));
            assertTrue(m.coord(i, 0) >= 102 && m.coord(i, 3) <= 106, "world coordinates");
        }
        assertEquals(4 * (4 + 3 + 5), total);
    }

    @Test
    void skyConnectedAirAndTinyVoidsAreIgnored() {
        VoxelGrid g = solidGrid();
        g.fill(2, 2, 2, 4, 3, 5, VoxelGrid.AIR); // enclosed room
        g.fill(10, 5, 10, 1, 11, 1, VoxelGrid.AIR); // shaft from y=5 up to the open air at 16
        g.fill(10, 5, 11, 3, 3, 3, VoxelGrid.AIR); // cave at the shaft's foot: sky-connected
        g.fill(15, 3, 15, 1, 2, 1, VoxelGrid.AIR); // 2-cell pocket: too small
        CavityTomography t = new CavityTomography();
        CavityTomography.Model m = t.scan(g, 8, 100_000);
        assertEquals(1, m.voids);
        assertEquals(60, m.voidCells);
        CavityTomography.Model withTiny = t.scan(g, 2, 100_000);
        assertEquals(2, withTiny.voids);
    }

    @Test
    void lShapedVoidKeepsConcaveEdges() {
        VoxelGrid g = solidGrid();
        g.fill(2, 2, 2, 4, 1, 1, VoxelGrid.AIR);
        g.fill(2, 2, 3, 1, 1, 3, VoxelGrid.AIR); // L in the XZ plane, one block high
        CavityTomography.Model m = new CavityTomography().scan(g, 2, 100_000);
        assertEquals(1, m.voids);
        // An L prism: 6 vertical edges, and the outline of the L top and bottom (6 sides each).
        assertEquals(6 + 6 + 6, m.segments());
    }

    @Test
    void depthColourAndEmitterAllocationFree() {
        assertTrue(CavityTomography.depthColor(0) != CavityTomography.depthColor(60));
        VoxelGrid g = solidGrid();
        g.fill(2, 2, 2, 4, 3, 5, VoxelGrid.AIR);
        g.fill(10, 8, 10, 3, 3, 3, VoxelGrid.AIR);
        CavityTomography.Model m = new CavityTomography().scan(g, 8, 100_000);
        assertEquals(2, m.voids);
        int[] lines = new int[1];
        LineSink sink = (x1, y1, z1, x2, y2, z2, argb) -> lines[0]++;
        assertEquals(24, m.emit(sink, 110, 10, 210, 64f));
        assertEquals(0, m.emit(sink, 10_000, 10, 210, 64f), "beyond the draw distance");
        if (!AllocationProbe.supported()) {
            return;
        }
        long bytes = AllocationProbe.measure(5_000, () -> m.emit(sink, 110, 10, 210, 64f));
        assertTrue(bytes < 1024, "allocated " + bytes);
    }

    @Test
    void edgeCapBoundsTheContour() {
        VoxelGrid g = solidGrid();
        for (int i = 0; i < 4; i++) {
            g.fill(1 + i * 4, 2, 2, 2, 2, 2, VoxelGrid.AIR);
        }
        CavityTomography.Model m = new CavityTomography().scan(g, 2, 20);
        assertEquals(4, m.voids);
        assertTrue(m.segments() <= 20);
    }
}
