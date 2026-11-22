package com.utilitysuite.ui.holo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.visual.HoloToken;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** The world kit's geometry through its sink: segment counts, the per-frame cap, the plain fallback, allocation. */
class HoloWorldKitTest {

    private static final float WPP = 0.001f; // world units per pixel at distance 1
    private static final float CORE = 1.5f;

    /** Counts quads and lines and records the farthest midpoint distance emitted. */
    private static final class CountingSink implements HoloWorldKit.Sink {
        int vertices;
        int lines;
        float maxDist;
        float lineLength;
        final float[] quad = new float[12];

        @Override
        public void vertex(float x, float y, float z, float u, float v, int argb) {
            int k = (vertices % 4) * 3;
            quad[k] = x;
            quad[k + 1] = y;
            quad[k + 2] = z;
            vertices++;
            if (vertices % 4 == 0) {
                float mx = (quad[0] + quad[6]) * 0.5f;
                float my = (quad[1] + quad[7]) * 0.5f;
                float mz = (quad[2] + quad[8]) * 0.5f;
                maxDist = Math.max(maxDist, (float) Math.sqrt(mx * mx + my * my + mz * mz));
            }
        }

        @Override
        public void line(float x0, float y0, float z0, float x1, float y1, float z1, int argb) {
            lines++;
            float dx = x1 - x0;
            float dy = y1 - y0;
            float dz = z1 - z0;
            lineLength += (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            float mx = (x0 + x1) * 0.5f;
            float my = (y0 + y1) * 0.5f;
            float mz = (z0 + z1) * 0.5f;
            maxDist = Math.max(maxDist, (float) Math.sqrt(mx * mx + my * my + mz * mz));
        }

        int quads() {
            return vertices / 4;
        }
    }

    private static final HoloWorldKit.Sink NO_OP = new HoloWorldKit.Sink() {
        @Override
        public void vertex(float x, float y, float z, float u, float v, int argb) {
        }

        @Override
        public void line(float x0, float y0, float z0, float x1, float y1, float z1, int argb) {
        }
    };

    @Test
    void ribbonsAreOneQuadPerSegmentAndGlowAddsOne() {
        HoloWorldKit k = new HoloWorldKit();
        k.begin(false, false, WPP, CORE, 0f);
        k.ribbon(0, 0, 5, 1, 0, 5, HoloToken.ACCENT, 1f);
        CountingSink s = new CountingSink();
        assertEquals(1, k.flush(s));
        assertEquals(1, s.quads());
        assertEquals(0, s.lines);

        k.begin(false, true, WPP, CORE, 0f);
        k.ribbon(0, 0, 5, 1, 0, 5, HoloToken.ACCENT, 1f);
        CountingSink g = new CountingSink();
        k.flush(g);
        assertEquals(2, g.quads(), "FULL: core + glow");
    }

    @Test
    void plainModeDrawsOnePixelLinesOnly() {
        HoloWorldKit k = new HoloWorldKit();
        k.begin(true, true, WPP, CORE, 0f);
        k.boxEdges(-1, -1, 4, 1, 1, 6, HoloToken.LINE_STRONG, 1f);
        CountingSink s = new CountingSink();
        assertEquals(12, k.flush(s));
        assertEquals(12, s.lines);
        assertEquals(0, s.vertices, "no ribbons with effects off or a shader pack");
    }

    @Test
    void primitiveSegmentCounts() {
        HoloWorldKit k = new HoloWorldKit();
        k.begin(true, false, WPP, CORE, 0f);
        k.boxEdges(0, 0, 3, 1, 1, 4, HoloToken.ACCENT, 1f);
        assertEquals(12, k.count());
        k.topBrackets(0, 3, 1, 4, 1, 0.25f, HoloToken.ACCENT, 1f);
        assertEquals(20, k.count());
        k.groundMark(0, 0, 5, 0.8f, HoloToken.WARN, 1f);
        assertEquals(30, k.count());
        k.ring(0, 0, 5, 1f, HoloToken.ACCENT, 1f);
        assertEquals(30 + HoloWorldKit.RING_SEGMENTS, k.count());
        k.ribbon(0, 0, 5, 1, 0, 5, HoloToken.ACCENT, 0f);
        assertEquals(30 + HoloWorldKit.RING_SEGMENTS, k.count(), "fully transparent: nothing");
        k.cancel();
        k.ribbon(0, 0, 5, 1, 0, 5, HoloToken.ACCENT, 1f);
        assertEquals(0, k.count(), "outside a pass nothing is collected");
    }

    @Test
    void capKeepsTheNearestSegments() {
        HoloWorldKit k = new HoloWorldKit();
        k.begin(true, false, WPP, CORE, 0f);
        int submitted = 1500;
        // shuffled distances 1..1500 so the cut is not just the submission order
        int[] order = new int[submitted];
        for (int i = 0; i < submitted; i++) {
            order[i] = i + 1;
        }
        Random r = new Random(7);
        for (int i = submitted - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = order[i];
            order[i] = order[j];
            order[j] = t;
        }
        for (int d : order) {
            k.ribbon(-0.1f, 0, d, 0.1f, 0, d, HoloToken.ACCENT, 1f);
        }
        CountingSink s = new CountingSink();
        assertEquals(HoloWorldKit.MAX_SEGMENTS, k.flush(s));
        assertEquals(HoloWorldKit.MAX_SEGMENTS, s.lines);
        assertEquals(HoloWorldKit.MAX_SEGMENTS, s.maxDist, 1e-3, "the farthest are dropped first");
    }

    @Test
    void capKeepsExactlyTheCapWithTies() {
        HoloWorldKit k = new HoloWorldKit();
        k.begin(true, false, WPP, CORE, 0f);
        for (int i = 0; i < 800; i++) {
            k.ribbon(0, 0, 5, 1, 0, 5, HoloToken.ACCENT, 1f); // all at one distance
        }
        CountingSink s = new CountingSink();
        assertEquals(HoloWorldKit.MAX_SEGMENTS, k.flush(s));
    }

    @Test
    void storeOverflowIsCounted() {
        HoloWorldKit k = new HoloWorldKit();
        k.begin(true, false, WPP, CORE, 0f);
        for (int i = 0; i < HoloWorldKit.STORE + 10; i++) {
            k.ribbon(0, 0, 5, 1, 0, 5, HoloToken.ACCENT, 1f);
        }
        assertEquals(HoloWorldKit.STORE, k.count());
        assertEquals(10, k.dropped());
        k.cancel();
    }

    @Test
    void selectFindsTheKthSmallest() {
        Random r = new Random(3);
        float[] a = new float[700];
        for (int trial = 0; trial < 20; trial++) {
            for (int i = 0; i < a.length; i++) {
                a[i] = r.nextInt(300); // many ties
            }
            float[] sorted = a.clone();
            Arrays.sort(sorted);
            int kth = r.nextInt(a.length);
            assertEquals(sorted[kth], HoloWorldKit.select(a, a.length, kth));
        }
    }

    @Test
    void dashesCoverHalfThePathAndAdvanceWithTheClock() {
        float[] path = {0, 0, 5, 3, 0, 5};
        HoloWorldKit k = new HoloWorldKit();
        k.begin(true, false, WPP, CORE, 0f);
        k.polyline(path, 2, 0.3f, HoloToken.ACCENT, 1f);
        CountingSink s = new CountingSink();
        k.flush(s);
        assertEquals(5, s.lines, "0-0.3, 0.6-0.9, ..., 2.4-2.7");
        assertEquals(1.5f, s.lineLength, 1e-3);

        // the pattern moved 0.45 toward the end: partial dashes at both ends, the same lit length
        k.begin(true, false, WPP, CORE, 0.45f / HoloWorldKit.DASH_SPEED);
        k.polyline(path, 2, 0.3f, HoloToken.ACCENT, 1f);
        CountingSink m = new CountingSink();
        k.flush(m);
        assertEquals(6, m.lines);
        assertEquals(1.5f, m.lineLength, 1e-3);
    }

    @Test
    void solidPolylineIsOneSegmentPerEdge() {
        float[] path = {0, 0, 5, 1, 0, 5, 1, 1, 5, 2, 1, 5};
        HoloWorldKit k = new HoloWorldKit();
        k.begin(false, false, WPP, CORE, 0f);
        k.polyline(path, 4, 0f, HoloToken.ACCENT, 1f);
        assertEquals(3, k.count());
        k.cancel();
    }

    @Test
    void ribbonWidthHoldsConstantOnScreen() {
        HoloWorldKit k = new HoloWorldKit();
        CountingSink near = new CountingSink();
        k.begin(false, false, WPP, CORE, 0f);
        k.ribbon(-0.01f, 0, 1, 0.01f, 0, 1, HoloToken.ACCENT, 1f);
        k.flush(near);
        float wNear = Math.abs(near.quad[1] - near.quad[4]);
        CountingSink far = new CountingSink();
        k.begin(false, false, WPP, CORE, 0f);
        k.ribbon(-0.1f, 0, 10, 0.1f, 0, 10, HoloToken.ACCENT, 1f);
        k.flush(far);
        float wFar = Math.abs(far.quad[1] - far.quad[4]);
        assertTrue(wNear > 0f);
        assertEquals(10f, wFar / wNear, 0.05f, "world width grows with distance, screen width stays");
    }

    @Test
    void idlePassEmitsNothing() {
        HoloWorldKit k = new HoloWorldKit();
        k.begin(false, true, WPP, CORE, 0f);
        CountingSink s = new CountingSink();
        assertEquals(0, k.flush(s));
        assertEquals(0, s.vertices + s.lines);
    }

    @Test
    void aFrameAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        HoloWorldKit k = new HoloWorldKit();
        float[] path = new float[64 * 3];
        for (int i = 0; i < 64; i++) {
            path[i * 3] = i * 0.25f;
            path[i * 3 + 1] = (float) Math.sin(i * 0.2);
            path[i * 3 + 2] = 4f;
        }
        long a = AllocationProbe.measure(2_000, () -> {
            k.begin(false, true, WPP, CORE, 1.7f);
            k.polyline(path, 64, 0.3f, HoloToken.ACCENT, 0.8f);
            k.polyline(path, 64, 0f, HoloToken.ACCENT, 0.2f);
            k.boxEdges(0, 0, 3, 1, 2, 4, HoloToken.LINE_STRONG, 1f);
            k.groundMark(2, 0, 6, 0.8f, HoloToken.WARN, 1f);
            k.ring(0, 0, 8, 2f, HoloToken.ACCENT, 1f);
            for (int i = 0; i < 600; i++) {
                k.ribbon(0, 0, i, 1, 0, i, HoloToken.ACCENT, 1f); // over the cap: the selection path runs too
            }
            k.flush(NO_OP);
        });
        assertTrue(a < 4096, "a world frame allocated " + a);
    }
}
