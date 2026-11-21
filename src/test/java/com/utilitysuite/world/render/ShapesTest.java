package com.utilitysuite.world.render;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShapesTest {

    /** Fake vertex sink: counts lines and tracks the farthest endpoint from the origin. */
    private static final class CountingSink implements LineSink {
        int lines;
        double maxR;
        double minR = Double.MAX_VALUE;

        @Override
        public void line(float x1, float y1, float z1, float x2, float y2, float z2, int argb) {
            lines++;
            double r = Math.sqrt(x1 * x1 + y1 * y1 + z1 * z1);
            maxR = Math.max(maxR, r);
            minR = Math.min(minR, r);
        }
    }

    @Test
    void ringStaysOnTheRadius() {
        CountingSink s = new CountingSink();
        Shapes.ring(s, 0, 0, 0, 8f, -1);
        assertEquals(Shapes.SEGMENTS, s.lines);
        assertEquals(8.0, s.maxR, 1e-4);
        assertEquals(8.0, s.minR, 1e-4);
    }

    @Test
    void globeIsASphere() {
        CountingSink s = new CountingSink();
        Shapes.globe(s, 0, 0, 0, 24f, -1);
        assertEquals(Shapes.globeLines(), s.lines);
        assertEquals(24.0, s.maxR, 1e-3);
        assertEquals(24.0, s.minR, 1e-3);
    }

    @Test
    void sphereOutlineIsThreeRings() {
        CountingSink s = new CountingSink();
        Shapes.sphereOutline(s, 0, 0, 0, 6f, -1);
        assertEquals(3 * Shapes.SEGMENTS, s.lines);
        assertEquals(6.0, s.minR, 1e-4);
    }

    @Test
    void emittersDoNotAllocate() {
        if (!AllocationProbe.supported()) {
            return;
        }
        CountingSink s = new CountingSink();
        long bytes = AllocationProbe.measure(2_000, () -> {
            Shapes.globe(s, 1, 2, 3, 128f, 0xFF00FF00);
            Shapes.sphereOutline(s, 1, 2, 3, 6f, 0xFF00FF00);
            Shapes.ring(s, 1, 2, 3, 8f, 0xFF00FF00);
            Shapes.cross(s, 1, 2, 3, 0xFF00FF00);
            Shapes.square(s, 0, 1, 0, 16, 16, 0xFF00FF00);
        });
        assertTrue(bytes < 1024, "allocated " + bytes);
    }
}
