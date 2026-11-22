package com.utilitysuite.combat.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class ReadRingGeometryTest {

    private static final float R = 1f;

    private static double length(float[] v, int firstVertex, int count) {
        double sum = 0;
        for (int k = firstVertex; k < firstVertex + count; k += 2) {
            int i = k * 3;
            double dx = v[i + 3] - v[i];
            double dy = v[i + 4] - v[i + 1];
            double dz = v[i + 5] - v[i + 2];
            sum += Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        return sum;
    }

    @Test
    void outlineHasThirtyTwoSegments() {
        float[] v = new float[ReadRingGeometry.MAX_FLOATS];
        int arc = ReadRingGeometry.fill(v, 0, 0, 0, R, 0f, 0f, 0f);
        assertEquals(0, arc);
        assertEquals(64, ReadRingGeometry.OUTLINE_VERTS);
        assertEquals(2 * Math.PI, length(v, 0, ReadRingGeometry.OUTLINE_VERTS), 0.02);
    }

    @Test
    void arcLengthIsProportionalToCharge() {
        float[] v = new float[ReadRingGeometry.MAX_FLOATS];
        int fullArc = ReadRingGeometry.fill(v, 0, 0, 0, R, 1f, 0f, 0f);
        assertEquals(ReadRingGeometry.OUTLINE_VERTS, fullArc);
        double full = length(v, ReadRingGeometry.OUTLINE_VERTS, fullArc);
        assertEquals(length(v, 0, ReadRingGeometry.OUTLINE_VERTS), full, 1e-4);
        for (float c : new float[]{0.1f, 0.25f, 0.5f, 0.77f, 0.99f}) {
            int arc = ReadRingGeometry.fill(v, 0, 0, 0, R, c, 0f, 0f);
            assertEquals(c, length(v, ReadRingGeometry.OUTLINE_VERTS, arc) / full, 0.01, "charge " + c);
        }
    }

    @Test
    void chargeEdgeCasesClamp() {
        float[] v = new float[ReadRingGeometry.MAX_FLOATS];
        assertEquals(0, ReadRingGeometry.fill(v, 0, 0, 0, R, -1f, 0f, 0f));
        assertEquals(0, ReadRingGeometry.fill(v, 0, 0, 0, R, 0f, 0f, 0f));
        assertEquals(64, ReadRingGeometry.fill(v, 0, 0, 0, R, 1f, 0f, 0f));
        assertEquals(64, ReadRingGeometry.fill(v, 0, 0, 0, R, 5f, 0f, 0f));
    }

    @Test
    void zeroTiltIsFlatAndCentred() {
        float[] v = new float[ReadRingGeometry.MAX_FLOATS];
        ReadRingGeometry.fill(v, 3, 7, -2, 2f, 0.5f, 0f, 0f);
        for (int k = 0; k < ReadRingGeometry.OUTLINE_VERTS; k++) {
            assertEquals(7f, v[k * 3 + 1], 1e-6);
            double dx = v[k * 3] - 3;
            double dz = v[k * 3 + 2] + 2;
            assertEquals(2.0, Math.sqrt(dx * dx + dz * dz), 1e-4);
        }
    }

    @Test
    void tiltDipsTheSideItLeansToAndCapsAtMaxAngle() {
        float[] v = new float[ReadRingGeometry.MAX_FLOATS];
        // vertex 0 is angle 0 = +X; tilt toward +X dips it, the opposite side (angle 180, vertex 32*2) rises
        ReadRingGeometry.fill(v, 0, 0, 0, R, 0f, 5f, 0f); // length 5 caps at 1
        float dipY = v[1];
        assertTrue(dipY < 0f);
        double angle = Math.toDegrees(Math.atan2(-dipY, v[0]));
        assertEquals(ReadRingGeometry.MAX_TILT_DEG, angle, 0.01);
        int opposite = 16 * 2; // angle 180 degrees
        assertTrue(v[opposite * 3 + 1] > 0f);
        assertEquals(-v[0], v[opposite * 3], 1e-4);
        // tilt toward +Z leaves the +X point level
        ReadRingGeometry.fill(v, 0, 0, 0, R, 0f, 0f, 1f);
        assertEquals(0f, v[1], 1e-5);
        // half the strength gives half the angle
        ReadRingGeometry.fill(v, 0, 0, 0, R, 0f, 0.5f, 0f);
        assertEquals(ReadRingGeometry.MAX_TILT_DEG / 2, Math.toDegrees(Math.atan2(-v[1], v[0])), 0.01);
    }

    @Test
    void fillAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        float[] v = new float[ReadRingGeometry.MAX_FLOATS];
        long bytes = AllocationProbe.measure(10_000, () -> ReadRingGeometry.fill(v, 1, 2, 3, 0.6f, 0.43f, 0.3f, -0.4f));
        assertTrue(bytes < 4096, "allocated " + bytes);
    }
}
