package com.utilitysuite.ui.holo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.visual.Glyph;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Geometry of the HUD kit through its vertex and fill sinks: no GL context needed. */
class HoloKitTest {

    /** Counts what the emitters write and checks every quad is complete. */
    private static final class CountingSink implements HoloKit.Geometry.VertexSink, HoloKit.Geometry.FillSink {
        int vertices;
        int fills;
        int fillArea;
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float minU = Float.MAX_VALUE;
        float maxU = -Float.MAX_VALUE;

        @Override
        public void vertex(float x, float y, float u, float v, int argb) {
            vertices++;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
        }

        @Override
        public void fill(int x0, int y0, int x1, int y1, int argb) {
            fills++;
            fillArea += (x1 - x0) * (y1 - y0);
        }
    }

    @Test
    void bracketsAreEightFillsOnTheEdge() {
        CountingSink s = new CountingSink();
        HoloKit.Geometry.brackets(s, 10, 10, 40, 20, 4, 0xFF3DE8FF);
        assertEquals(8, s.fills);
        // 4 horizontal arms of 4 px + 4 vertical arms of 3 px (the corner texel belongs to the horizontal arm)
        assertEquals(4 * 4 + 4 * 3, s.fillArea);
        CountingSink none = new CountingSink();
        HoloKit.Geometry.brackets(none, 0, 0, 40, 20, 4, 0x003DE8FF);
        assertEquals(0, none.fills, "a fully transparent colour draws nothing");
    }

    @Test
    void armIsClampedToTheSmallerSide() {
        CountingSink s = new CountingSink();
        HoloKit.Geometry.brackets(s, 0, 0, 3, 3, 10, 0xFFFFFFFF);
        assertEquals(8, s.fills);
        assertTrue(s.fillArea <= 4 * 3 + 4 * 2);
    }

    @Test
    void arcSegmentCountsFollowTheSweep() {
        assertEquals(0, HoloKit.Geometry.arcSegments(0f));
        assertEquals(1, HoloKit.Geometry.arcSegments(3f));
        assertEquals(15, HoloKit.Geometry.arcSegments(90f));
        assertEquals(60, HoloKit.Geometry.arcSegments(360f));
        assertEquals(60, HoloKit.Geometry.arcSegments(-360f));
        assertEquals(HoloKit.Geometry.MAX_ARC_SEGMENTS, HoloKit.Geometry.arcSegments(3600f));

        CountingSink s = new CountingSink();
        int quads = HoloKit.Geometry.arc(s, 100f, 100f, 20f, 0f, 360f, 1.5f, 0xFFFFFFFF, HoloKit.Geometry.ROW_SOFT);
        assertEquals(60, quads);
        assertEquals(4 * 60, s.vertices);
        float half = HoloKit.Geometry.halfWidth(1.5f, HoloKit.Geometry.ROW_SOFT);
        assertEquals(100f - 20f - half, s.minX, 0.05f);
        assertEquals(100f + 20f + half, s.maxX, 0.05f);
        assertEquals(100f - 20f - half, s.minY, 0.05f);
        assertEquals(100f + 20f + half, s.maxY, 0.05f);
    }

    @Test
    void quarterArcStartsUpAndRunsClockwise() {
        CountingSink s = new CountingSink();
        HoloKit.Geometry.arc(s, 0f, 0f, 10f, 0f, 90f, 1f, 0xFFFFFFFF, HoloKit.Geometry.ROW_HARD);
        // from 12 o'clock to 3 o'clock: x >= 0 and y <= 0 (screen y grows downward)
        assertTrue(s.minX > -0.5f, "minX " + s.minX);
        assertTrue(s.maxY < 0.5f, "maxY " + s.maxY);
    }

    @Test
    void ribbonIsOneQuadAcrossTheFalloffRow() {
        CountingSink s = new CountingSink();
        assertEquals(1, HoloKit.Geometry.ribbon(s, 0f, 0f, 10f, 0f, 1.5f, 0xFFFFFFFF, HoloKit.Geometry.ROW_SOFT));
        assertEquals(4, s.vertices);
        float half = HoloKit.Geometry.halfWidth(1.5f, HoloKit.Geometry.ROW_SOFT);
        assertEquals(-half, s.minY, 1e-4f);
        assertEquals(half, s.maxY, 1e-4f);
        assertTrue(s.minU > 0f && s.maxU < 16f / HoloKit.Geometry.ATLAS_W, "U stays inside the 16 texel row");
        CountingSink zero = new CountingSink();
        assertEquals(0, HoloKit.Geometry.ribbon(zero, 5f, 5f, 5f, 5f, 1f, 0xFFFFFFFF, HoloKit.Geometry.ROW_HARD));
        assertEquals(0, zero.vertices);
    }

    @Test
    void segmentedBarLightsWholeAndPartialCells() {
        CountingSink s = new CountingSink();
        HoloKit.Geometry.bar(s, 0, 0, 39, 2, 0.5f, 4, 0xFFFFFFFF, 0xFF000000);
        // 4 cells of 9 px with 1 px gaps: 2 lit, 2 track
        assertEquals(4, s.fills);
        CountingSink p = new CountingSink();
        HoloKit.Geometry.bar(p, 0, 0, 39, 2, 0.6f, 4, 0xFFFFFFFF, 0xFF000000);
        assertEquals(5, p.fills, "the partial cell is track plus a partial fill");
        CountingSink plain = new CountingSink();
        HoloKit.Geometry.bar(plain, 0, 0, 20, 2, 0f, 1, 0xFFFFFFFF, 0xFF000000);
        assertEquals(1, plain.fills, "empty bar is just the track");
    }

    @Test
    void glyphsSampleTheirOwnAtlasCell() {
        CountingSink s = new CountingSink();
        HoloKit.Geometry.glyph(s, Glyph.ACTING.ordinal(), 4, 4, 0xFFFFFFFF);
        assertEquals(4, s.vertices);
        float u0 = (HoloKit.Geometry.GLYPH_X + Glyph.ACTING.ordinal() * HoloKit.Geometry.GLYPH_STEP)
                / (float) HoloKit.Geometry.ATLAS_W;
        assertEquals(u0, s.minU, 1e-6f);
        assertEquals(u0 + Glyph.SIZE / (float) HoloKit.Geometry.ATLAS_W, s.maxU, 1e-6f);
        int lastCellEnd = HoloKit.Geometry.GLYPH_X + Glyph.values().length * HoloKit.Geometry.GLYPH_STEP;
        assertTrue(lastCellEnd <= HoloKit.Geometry.ATLAS_W, "every glyph fits the atlas");
    }

    @Test
    void emittersAllocateNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        CountingSink s = new CountingSink();
        long bytes = AllocationProbe.measure(5_000, () -> {
            HoloKit.Geometry.arc(s, 50f, 50f, 12f, 30f, 240f, 1.5f, 0xFF3DE8FF, HoloKit.Geometry.ROW_SOFT);
            HoloKit.Geometry.ribbon(s, 0f, 0f, 30f, 12f, 1.5f, 0xFF3DE8FF, HoloKit.Geometry.ROW_GLOW);
            HoloKit.Geometry.brackets(s, 0, 0, 60, 20, 5, 0xFF3DE8FF);
            HoloKit.Geometry.bar(s, 0, 0, 40, 2, 0.4f, 5, 0xFF3DE8FF, 0x293DE8FF);
            HoloKit.Geometry.glyph(s, 3, 0, 0, 0xFF3DE8FF);
        });
        assertEquals(0L, bytes, "kit emitters allocated " + bytes + " bytes");
    }
}
