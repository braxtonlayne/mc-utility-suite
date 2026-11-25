package com.utilitysuite.world.render;

/**
 * Allocation-free geometry emitters shared by the overlays (light-level crosses, blast rings, spawn spheres, chunk
 * outlines). Circles come from one precomputed unit circle; nothing is computed with trig per frame.
 */
public final class Shapes {

    /** Segments of the precomputed unit circle. */
    public static final int SEGMENTS = 48;
    private static final float[] COS = new float[SEGMENTS + 1];
    private static final float[] SIN = new float[SEGMENTS + 1];
    /** Latitudes (sine values) drawn for a sphere: -60, -30, 0, 30, 60 degrees. */
    private static final float[] LAT_SIN = new float[5];
    private static final float[] LAT_COS = new float[5];
    /** Meridians drawn for a sphere (half circles each side: 6 full great circles through the poles = 12 halves). */
    public static final int MERIDIANS = 6;

    static {
        for (int i = 0; i <= SEGMENTS; i++) {
            double a = Math.PI * 2.0 * i / SEGMENTS;
            COS[i] = (float) Math.cos(a);
            SIN[i] = (float) Math.sin(a);
        }
        for (int i = 0; i < 5; i++) {
            double lat = Math.toRadians(-60 + 30 * i);
            LAT_SIN[i] = (float) Math.sin(lat);
            LAT_COS[i] = (float) Math.cos(lat);
        }
    }

    private Shapes() {
    }

    /** Flat X across the top face of the block at camera-relative (x, y, z): y is the face height. */
    public static void cross(LineSink s, float x, float y, float z, int argb) {
        s.line(x + 0.15f, y, z + 0.15f, x + 0.85f, y, z + 0.85f, argb);
        s.line(x + 0.85f, y, z + 0.15f, x + 0.15f, y, z + 0.85f, argb);
    }

    /** Horizontal circle of radius r centred at (cx, y, cz). */
    public static void ring(LineSink s, float cx, float y, float cz, float r, int argb) {
        for (int i = 0; i < SEGMENTS; i++) {
            s.line(cx + COS[i] * r, y, cz + SIN[i] * r, cx + COS[i + 1] * r, y, cz + SIN[i + 1] * r, argb);
        }
    }

    /** Vertical circle of radius r in the XY plane (axisX = true) or the ZY plane. */
    public static void verticalRing(LineSink s, float cx, float cy, float cz, float r, boolean axisX, int argb) {
        for (int i = 0; i < SEGMENTS; i++) {
            float a0 = COS[i] * r;
            float a1 = COS[i + 1] * r;
            float y0 = cy + SIN[i] * r;
            float y1 = cy + SIN[i + 1] * r;
            if (axisX) {
                s.line(cx + a0, y0, cz, cx + a1, y1, cz, argb);
            } else {
                s.line(cx, y0, cz + a0, cx, y1, cz + a1, argb);
            }
        }
    }

    /** Sphere outline: one horizontal ring plus two vertical great circles (blast spheres). */
    public static void sphereOutline(LineSink s, float cx, float cy, float cz, float r, int argb) {
        ring(s, cx, cy, cz, r, argb);
        verticalRing(s, cx, cy, cz, r, true, argb);
        verticalRing(s, cx, cy, cz, r, false, argb);
    }

    /** Latitude/longitude sphere: 5 latitude rings and {@link #MERIDIANS} great circles through the poles. */
    public static void globe(LineSink s, float cx, float cy, float cz, float r, int argb) {
        for (int i = 0; i < 5; i++) {
            ring(s, cx, cy + LAT_SIN[i] * r, cz, LAT_COS[i] * r, argb);
        }
        // Meridian k lies in the vertical plane at angle k * 180 / MERIDIANS; reuse the unit circle (SEGMENTS / 2 /
        // MERIDIANS steps apart = 4 segments for 48 / 12).
        int step = SEGMENTS / (2 * MERIDIANS);
        for (int k = 0; k < MERIDIANS; k++) {
            float dx = COS[k * step];
            float dz = SIN[k * step];
            for (int i = 0; i < SEGMENTS; i++) {
                float h0 = COS[i] * r;
                float h1 = COS[i + 1] * r;
                s.line(cx + dx * h0, cy + SIN[i] * r, cz + dz * h0, cx + dx * h1, cy + SIN[i + 1] * r, cz + dz * h1,
                        argb);
            }
        }
    }

    /** Horizontal square outline (chunk borders). */
    public static void square(LineSink s, float x0, float y, float z0, float x1, float z1, int argb) {
        s.line(x0, y, z0, x1, y, z0, argb);
        s.line(x1, y, z0, x1, y, z1, argb);
        s.line(x1, y, z1, x0, y, z1, argb);
        s.line(x0, y, z1, x0, y, z0, argb);
    }

    /**
     * Upper hemisphere (dome) standing on the plane y = cy: rings at 0, 30 and 60 degrees latitude plus
     * {@link #MERIDIANS} half great circles over the top. Lighter than {@link #globe} for ground-anchored radii.
     */
    public static void dome(LineSink s, float cx, float cy, float cz, float r, int argb) {
        for (int i = 2; i < 5; i++) { // latitudes 0, 30, 60
            ring(s, cx, cy + LAT_SIN[i] * r, cz, LAT_COS[i] * r, argb);
        }
        int step = SEGMENTS / (2 * MERIDIANS);
        int half = SEGMENTS / 2;
        for (int k = 0; k < MERIDIANS; k++) {
            float dx = COS[k * step];
            float dz = SIN[k * step];
            for (int i = 0; i < half; i++) { // angles 0..180: sine >= 0, the upper half
                float h0 = COS[i] * r;
                float h1 = COS[i + 1] * r;
                s.line(cx + dx * h0, cy + SIN[i] * r, cz + dz * h0, cx + dx * h1, cy + SIN[i + 1] * r, cz + dz * h1,
                        argb);
            }
        }
    }

    /** Lines emitted by {@link #dome}. */
    public static int domeLines() {
        return 3 * SEGMENTS + MERIDIANS * (SEGMENTS / 2);
    }

    /** Arc segments of {@link #fan}. */
    public static final int FAN_SEGMENTS = 12;

    /**
     * Flat horizontal view cone (fan) of radius r at height y: two edges from the apex plus an arc. {@code yawRad} is
     * the centre direction measured like Minecraft yaw (0 = +Z, 90 degrees = -X), {@code halfRad} the half-angle.
     */
    public static void fan(LineSink s, float cx, float y, float cz, float yawRad, float halfRad, float r, int argb) {
        float a0 = yawRad - halfRad;
        float step = 2f * halfRad / FAN_SEGMENTS;
        float px = cx - (float) Math.sin(a0) * r;
        float pz = cz + (float) Math.cos(a0) * r;
        s.line(cx, y, cz, px, y, pz, argb);
        for (int i = 1; i <= FAN_SEGMENTS; i++) {
            float a = a0 + step * i;
            float qx = cx - (float) Math.sin(a) * r;
            float qz = cz + (float) Math.cos(a) * r;
            s.line(px, y, pz, qx, y, qz, argb);
            px = qx;
            pz = qz;
        }
        s.line(px, y, pz, cx, y, cz, argb);
    }

    /** Arrow from (x0, y0, z0) to (x1, y1, z1) with a two-line horizontal head of length {@code head}. */
    public static void arrow(LineSink s, float x0, float y0, float z0, float x1, float y1, float z1, float head,
                             int argb) {
        s.line(x0, y0, z0, x1, y1, z1, argb);
        float dx = x1 - x0;
        float dz = z1 - z0;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0e-4f) {
            return;
        }
        dx /= len;
        dz /= len;
        // Back along the shaft, then +/- 45 degrees sideways.
        float bx = x1 - dx * head;
        float bz = z1 - dz * head;
        s.line(x1, y1, z1, bx - dz * head, y1, bz + dx * head, argb);
        s.line(x1, y1, z1, bx + dz * head, y1, bz - dx * head, argb);
    }

    /** Lines emitted by {@link #globe}. */
    public static int globeLines() {
        return 5 * SEGMENTS + MERIDIANS * SEGMENTS;
    }
}
