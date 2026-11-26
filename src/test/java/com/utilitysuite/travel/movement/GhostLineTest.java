package com.utilitysuite.travel.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.core.api.visual.IHoloWorld;
import com.utilitysuite.travel.Packed;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class GhostLineTest {

    private static long[] straight(int n) {
        long[] c = new long[n];
        for (int i = 0; i < n; i++) {
            c[i] = Packed.pack(i, 64, 0);
        }
        return c;
    }

    @Test
    void straightRoutePlacesGhostAtSpeedTimesLead() {
        GhostLine g = new GhostLine();
        long[] cells = straight(20);
        g.updateRoute(cells, cells.length, 1, 0.5, 64, 0.5, 0.2, 20); // 4 blocks ahead
        assertTrue(g.active());
        assertEquals(GhostLine.ROUTE, g.mode());
        assertEquals(4.5f, g.ghostX(), 1e-4);
        assertEquals(0.5f, g.ghostZ(), 1e-4);
        assertEquals(64f, g.ghostY(), 1e-4);
        // trail: the player, the cells passed, the ghost last
        assertEquals(0.5f, g.trailX(0), 1e-4);
        assertEquals(g.ghostX(), g.trailX(g.trailCount() - 1), 1e-4);
    }

    /** Playtest find: a lethal jump (no landing) never got its MISS mark because the arc needed a landing. */
    @Test
    void lethalJumpIsShownWhileAirborne() {
        assertTrue(GhostLine.showsJump(true, true, false, false), "airborne, nothing survivable: shown (MISS)");
        assertEquals(GhostLine.MISS, GhostLine.quality(true, 0f, 0.0));
        assertTrue(GhostLine.showsJump(true, true, true, true), "on the ground, a jump that lands");
        assertFalse(GhostLine.showsJump(true, true, false, true), "on the ground, no landing: hidden");
        assertFalse(GhostLine.showsJump(false, true, true, false), "Movement Assist off");
        assertFalse(GhostLine.showsJump(true, false, true, true), "standing still");
    }

    @Test
    void lShapedRouteTurnsTheCorner() {
        GhostLine g = new GhostLine();
        long[] cells = new long[11];
        for (int i = 0; i <= 5; i++) {
            cells[i] = Packed.pack(i, 64, 0);
        }
        for (int i = 1; i <= 5; i++) {
            cells[5 + i] = Packed.pack(5, 64, i);
        }
        g.updateRoute(cells, cells.length, 1, 0.5, 64, 0.5, 0.35, 20); // 7 blocks: 5 along x, 2 along z
        assertEquals(5.5f, g.ghostX(), 1e-4);
        assertEquals(2.5f, g.ghostZ(), 1e-4);
    }

    /** Playtest find: with the runner's index a few cells behind the body, the ghost walked back and sat behind it. */
    @Test
    void laggingRouteIndexStillPutsTheGhostAhead() {
        GhostLine g = new GhostLine();
        long[] cells = straight(20);
        g.updateRoute(cells, cells.length, 0, 3.2, 64, 0.5, 0.0, 20); // index 0, body already at x 3.2
        assertEquals(3.2 + GhostLine.MIN_LEAD, g.ghostX(), 1e-4);
        for (int i = 1; i < g.trailCount(); i++) {
            assertTrue(g.trailX(i) >= g.trailX(i - 1), "trail never steps back");
        }
        // a corner still ahead of the body is kept (only cells past the midpoint to the next are skipped)
        long[] l = new long[8];
        for (int i = 0; i <= 3; i++) {
            l[i] = Packed.pack(i, 64, 0);
        }
        for (int i = 1; i <= 4; i++) {
            l[3 + i] = Packed.pack(3, 64, i);
        }
        g.updateRoute(l, 8, 3, 2.0, 64, 0.7, 0.0, 20); // corner cell (3, 0) ahead, body slightly toward the next leg
        assertEquals(3.5f, g.trailX(1), 1e-4);
        assertEquals(0.5f, g.trailZ(1), 1e-4);
    }

    @Test
    void clampsAtTheEndOfTheRoute() {
        GhostLine g = new GhostLine();
        long[] cells = straight(6);
        g.updateRoute(cells, cells.length, 1, 0.5, 64, 0.5, 1.0, 40);
        assertEquals(5.5f, g.ghostX(), 1e-4);
        assertEquals(5.5f, g.trailX(g.trailCount() - 1), 1e-4);
    }

    @Test
    void zeroSpeedUsesTheMinimumLead() {
        GhostLine g = new GhostLine();
        long[] cells = straight(20);
        g.updateRoute(cells, cells.length, 1, 0.5, 64, 0.5, 0.0, 20);
        assertEquals(0.5 + GhostLine.MIN_LEAD, g.ghostX(), 1e-4);
    }

    @Test
    void ghostTooCloseToThePlayerIsHidden() {
        GhostLine g = new GhostLine();
        long[] cells = straight(2); // route ends 1 block ahead
        g.updateRoute(cells, cells.length, 0, 0.5, 64, 0.5, 0.0, 20);
        assertFalse(g.active());
        g.updateJump(0, 64, 0, 1.0, 64, 0.5);
        assertFalse(g.active());
    }

    @Test
    void jumpArcEndpointsAndApex() {
        GhostLine g = new GhostLine();
        g.updateJump(0, 64, 0, 4, 63, 0);
        assertEquals(GhostLine.JUMP, g.mode());
        assertEquals(4f, g.ghostX(), 1e-5);
        assertEquals(63f, g.ghostY(), 1e-5);
        int n = g.trailCount();
        assertEquals(GhostLine.ARC_SEGMENTS + 1, n);
        assertEquals(0f, g.trailX(0), 1e-5);
        assertEquals(64f, g.trailY(0), 1e-5);
        assertEquals(4f, g.trailX(n - 1), 1e-5);
        assertEquals(63f, g.trailY(n - 1), 1e-5);
        float apex = -1e9f;
        for (int i = 0; i < n; i++) {
            apex = Math.max(apex, g.trailY(i));
        }
        // the apex is ARC_RISE above the chord midpoint (63.5), shifted a little toward the higher takeoff end
        assertTrue(apex >= 63.5f + (float) GhostLine.ARC_RISE - 1e-4f && apex < 64.7f, "apex " + apex);
        assertEquals(63.5f + (float) GhostLine.ARC_RISE, g.trailY(n / 2), 1e-4);
    }

    @Test
    void interpolatesBetweenTicks() {
        GhostLine g = new GhostLine();
        long[] cells = straight(30);
        g.updateRoute(cells, cells.length, 1, 0.5, 64, 0.5, 0.2, 20);
        g.updateRoute(cells, cells.length, 1, 1.5, 64, 0.5, 0.2, 20); // ghost moves 4.5 -> 5.5
        float[] out = new float[3];
        g.interpolate(0.5f, out);
        assertEquals(5.0f, out[0], 1e-4);
        g.interpolate(1f, out);
        assertEquals(5.5f, out[0], 1e-4);
    }

    @Test
    void jumpQualityGrades() {
        assertEquals(GhostLine.GOOD, GhostLine.quality(false, 0f, 0.0));
        assertEquals(GhostLine.MARGINAL, GhostLine.quality(false, 2f, 0.0));
        assertEquals(GhostLine.MARGINAL, GhostLine.quality(false, 0f, 0.3));
        assertEquals(GhostLine.MISS, GhostLine.quality(false, 0f, 0.8));
        assertEquals(GhostLine.MISS, GhostLine.quality(true, 0f, 0.0));
        assertEquals(HoloToken.ACCENT, GhostLine.qualityToken(GhostLine.GOOD));
        assertEquals(HoloToken.WARN, GhostLine.qualityToken(GhostLine.MARGINAL));
        assertEquals(HoloToken.DANGER, GhostLine.qualityToken(GhostLine.MISS));
        GhostLine g = new GhostLine();
        g.setQuality(GhostLine.MISS);
        g.clear();
        assertEquals(GhostLine.GOOD, g.quality(), "cleared with the prediction");
    }

    @Test
    void bandsCutThePathByArcLength() {
        float[] path = {0, 0, 0, 1, 0, 0, 1, 0, 2}; // 1 along x, then 2 along z
        assertEquals(3f, GhostLine.length(path, 3), 1e-5);
        float[] out = new float[(3 + 2) * 3];
        int c = GhostLine.band(path, 3, 0.5f, 2f, out);
        assertEquals(3, c, "start inside the first edge, the corner, end inside the second edge");
        assertEquals(0.5f, out[0], 1e-5);
        assertEquals(1f, out[3], 1e-5);
        assertEquals(1f, out[6], 1e-5);
        assertEquals(1f, out[8], 1e-5);
        assertEquals(0, GhostLine.band(path, 3, 2f, 2f, out));
        // bands are whole dash periods so a dashed pattern continues across them
        float bl = GhostLine.bandLength(20f, 0.6f, 24);
        assertEquals(0f, (bl / 0.6f) % 1f, 1e-4);
        assertTrue(20f / bl <= 24f);
    }

    /** Records what Ghost Line hands the world kit. */
    private static final class RecordingWorld implements IHoloWorld {
        int polylines;
        int dashed;
        int marks;
        int boxes;
        HoloToken markToken;
        float firstDashAlpha = -1f;
        float lastDashAlpha = -1f;

        @Override
        public void ribbon(float x0, float y0, float z0, float x1, float y1, float z1, HoloToken token, float alpha) {
        }

        @Override
        public void polyline(float[] xyz, int count, float dash, HoloToken token, float alpha) {
            polylines++;
            if (dash > 0f) {
                dashed++;
                if (firstDashAlpha < 0f) {
                    firstDashAlpha = alpha;
                }
                lastDashAlpha = alpha;
            }
        }

        @Override
        public void ring(float cx, float cy, float cz, float radius, HoloToken token, float alpha) {
        }

        @Override
        public void boxEdges(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, HoloToken token,
                             float alpha) {
            boxes++;
        }

        @Override
        public void topBrackets(float minX, float minZ, float maxX, float maxZ, float y, float arm, HoloToken token,
                                float alpha) {
        }

        @Override
        public void groundMark(float x, float y, float z, float size, HoloToken token, float alpha) {
            marks++;
            markToken = token;
        }
    }

    private static float[] arcPath(GhostLine g) {
        float[] p = new float[GhostLine.MAX_TRAIL * 3];
        for (int i = 0; i < g.trailCount(); i++) {
            p[i * 3] = g.trailX(i);
            p[i * 3 + 1] = g.trailY(i) - 64f;
            p[i * 3 + 2] = g.trailZ(i) + 3f;
        }
        return p;
    }

    @Test
    void jumpDrawsFadingDashesAndAQualityMark() {
        GhostLine g = new GhostLine();
        g.updateJump(0, 64, 0, 4, 64, 0);
        g.setQuality(GhostLine.MARGINAL);
        float[] path = arcPath(g);
        float[] band = new float[(GhostLine.MAX_TRAIL + 2) * 3];
        RecordingWorld w = new RecordingWorld();
        GhostLine.draw(w, path, g.trailCount(), band, 0.5f, true, g.quality(), 4f, 0f, 3f);
        assertTrue(w.dashed >= 2, "the path is cut into fade bands: " + w.dashed);
        assertEquals(w.dashed + 1, w.polylines, "plus one faint continuous base");
        assertTrue(w.lastDashAlpha < w.firstDashAlpha, "alpha fades along the path");
        assertEquals(1, w.marks);
        assertEquals(HoloToken.WARN, w.markToken);
        assertEquals(0, w.boxes, "a jump shows the landing mark, not the ghost body");
    }

    @Test
    void routeDrawsTheGhostBodyAndNoTrailWhenHidden() {
        RecordingWorld w = new RecordingWorld();
        GhostLine.draw(w, new float[3], 0, new float[6], 0.5f, false, GhostLine.GOOD, 3f, 0f, 3f);
        assertEquals(0, w.polylines);
        assertEquals(1, w.boxes);
        assertEquals(0, w.marks);
    }

    @Test
    void drawAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        GhostLine g = new GhostLine();
        g.updateJump(0, 64, 0, 4, 63, 1);
        float[] path = arcPath(g);
        float[] band = new float[(GhostLine.MAX_TRAIL + 2) * 3];
        RecordingWorld w = new RecordingWorld();
        int n = g.trailCount();
        long a = AllocationProbe.measure(5_000,
                () -> GhostLine.draw(w, path, n, band, 0.5f, true, GhostLine.GOOD, 4f, -1f, 4f));
        assertTrue(a < 4096, "draw allocated " + a);
    }

    @Test
    void updatesAllocateNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        GhostLine g = new GhostLine();
        long[] cells = straight(40);
        float[] out = new float[3];
        long a = AllocationProbe.measure(5_000, () -> {
            g.updateRoute(cells, cells.length, 2, 2.5, 64, 0.5, 0.25, 20);
            g.interpolate(0.3f, out);
        });
        assertTrue(a < 4096, "route allocated " + a);
        long b = AllocationProbe.measure(5_000, () -> g.updateJump(0, 64, 0, 4, 64, 1));
        assertTrue(b < 4096, "jump allocated " + b);
    }
}
