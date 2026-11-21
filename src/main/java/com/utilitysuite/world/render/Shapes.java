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

    /** Lines emitted by {@link #globe}. */
    public static int globeLines() {
        return 5 * SEGMENTS + MERIDIANS * SEGMENTS;
    }
}
