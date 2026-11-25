package com.utilitysuite.world.overlays;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RibbonGeometryTest {

    private static float[] straightX(int n) {
        float[] p = new float[n * 3];
        for (int i = 0; i < n; i++) {
            p[i * 3] = i;
            p[i * 3 + 1] = 70f;
        }
        return p;
    }

    @Test
    void pathCopiesOnePointPerTickCameraRelative() {
        TrajectoryIntegrator sim = new TrajectoryIntegrator(50);
        // pearl thrown level from y 100 over a floor at y 64
        sim.simulate(TrajectoryIntegrator.Kind.THROWN, 0.5, 100, 0.5, 1.5, 0, 0,
                (x, y, z) -> y < 64 ? TrajectoryIntegrator.Collider.SOLID : TrajectoryIntegrator.Collider.FREE);
        float[] out = new float[51 * 3];
        int n = RibbonGeometry.path(sim, 0.5, 100, 0.5, out);
        assertEquals(sim.count(), n);
        assertEquals(0f, out[0], 1e-6f);
        assertEquals(0f, out[1], 1e-6f);
        assertEquals(1.5f, out[3], 1e-4f); // tick 1: the launch velocity
        // tick-spaced samples: the drop per tick grows with gravity
        float d1 = out[1] - out[4];
        float d2 = out[4] - out[7];
        assertTrue(d2 > d1);
    }

    @Test
    void chevronsEveryNTicksPointAlongTheFlight() {
        float[] pts = straightX(21);
        float[] out = new float[64 * RibbonGeometry.SEG];
        int segs = RibbonGeometry.chevrons(pts, 21, 2, 0.4f, out);
        // ticks 2, 4, ... 18 (first and last point skipped): 9 chevrons, two arms each
        assertEquals(18, segs);
        // first chevron at tick 2 (x = 2): arms start behind, meet at the tip ahead
        assertEquals(1.8f, out[0], 1e-5f);
        assertEquals(2.2f, out[3], 1e-5f);
        assertEquals(2.2f, out[9], 1e-5f);
        // spread sideways (z) symmetrically, level in y
        assertEquals(-out[2], out[8], 1e-5f);
        assertTrue(Math.abs(out[2]) > 0.2f);
        assertEquals(70f, out[1], 1e-5f);
        assertEquals(0, RibbonGeometry.chevrons(pts, 21, 0, 0.4f, out));
        assertEquals(0, RibbonGeometry.chevrons(pts, 2, 1, 0.4f, out));
    }

    @Test
    void chevronsStopAtTheOutputCapacity() {
        float[] pts = straightX(50);
        float[] out = new float[3 * RibbonGeometry.SEG];
        assertEquals(2, RibbonGeometry.chevrons(pts, 50, 1, 0.4f, out));
    }

    @Test
    void verticalFlightStillGetsChevrons() {
        float[] pts = new float[5 * 3];
        for (int i = 0; i < 5; i++) {
            pts[i * 3 + 1] = -i;
        }
        float[] out = new float[16 * RibbonGeometry.SEG];
        assertEquals(6, RibbonGeometry.chevrons(pts, 5, 1, 0.4f, out));
        for (float f : out) {
            assertTrue(Float.isFinite(f));
        }
    }

    @Test
    void ghostIsAPlayerSizedFigureStandingOnTheSpot() {
        float[] g = new float[RibbonGeometry.GHOST_BOXES * RibbonGeometry.BOX];
        RibbonGeometry.ghost(10f, 64f, -3f, g);
        assertEquals(64f, g[1], 1e-6f);            // legs start at the feet
        assertEquals(66f, g[2 * 6 + 4], 1e-6f);    // head tops out at 2 blocks
        assertEquals(g[4], g[6 + 1], 1e-6f);       // body sits on the legs
        assertEquals(g[6 + 4], g[12 + 1], 1e-6f);  // head sits on the body
        for (int b = 0; b < 3; b++) {
            assertEquals(10f, (g[b * 6] + g[b * 6 + 3]) / 2f, 1e-5f);
            assertEquals(-3f, (g[b * 6 + 2] + g[b * 6 + 5]) / 2f, 1e-5f);
        }
    }

    @Test
    void fadeRunsFromOpaqueToFaint() {
        assertEquals(1f, RibbonGeometry.fade(0, 10), 1e-6f);
        assertEquals(0.35f, RibbonGeometry.fade(9, 10), 1e-6f);
        assertTrue(RibbonGeometry.fade(5, 10) < 1f);
    }

    @Test
    void chevronsAndGhostAllocateNothing() {
        if (!AllocationProbe.supported()) {
            return;
        }
        float[] pts = straightX(160);
        float[] out = new float[200 * RibbonGeometry.SEG];
        float[] g = new float[18];
        long bytes = AllocationProbe.measure(2000, () -> {
            RibbonGeometry.chevrons(pts, 160, 2, 0.3f, out);
            RibbonGeometry.ghost(1f, 2f, 3f, g);
        });
        assertEquals(0L, bytes);
    }
}
