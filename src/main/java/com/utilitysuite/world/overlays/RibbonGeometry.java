package com.utilitysuite.world.overlays;

/**
 * Engine-free geometry for the time-slice projectile ribbon: copies a simulated path (one point per game tick, from
 * {@link TrajectoryIntegrator}) into a camera-relative polyline, places tick-spaced chevrons pointing along the flight
 * direction, and lays out the ghost silhouette (head / body / legs boxes of a standing player) at a landing point.
 * Every method writes into caller-owned preallocated arrays and allocates nothing.
 */
public final class RibbonGeometry {

    /** Floats per segment: x0, y0, z0, x1, y1, z1. */
    public static final int SEG = 6;
    /** Floats per box: minX, minY, minZ, maxX, maxY, maxZ. */
    public static final int BOX = 6;
    /** Boxes in the ghost silhouette (legs, body, head). */
    public static final int GHOST_BOXES = 3;
    /** Segments per chevron (two arms). */
    public static final int CHEVRON_SEGS = 2;

    private RibbonGeometry() {
    }

    /**
     * Copies the integrator's points into {@code out} as camera-relative x, y, z triples. Returns the points copied
     * (at most {@code out.length / 3}).
     */
    public static int path(TrajectoryIntegrator sim, double camX, double camY, double camZ, float[] out) {
        int n = Math.min(sim.count(), out.length / 3);
        for (int i = 0; i < n; i++) {
            out[i * 3] = (float) (sim.x(i) - camX);
            out[i * 3 + 1] = (float) (sim.y(i) - camY);
            out[i * 3 + 2] = (float) (sim.z(i) - camZ);
        }
        return n;
    }

    /**
     * Chevrons along a polyline of {@code n} points (x, y, z triples, one per tick): one every {@code every} ticks,
     * skipping the first and last point. Each is a "V" whose tip points along the local direction (central
     * difference), arms {@code size} long, spread sideways (horizontal perpendicular; X axis for a vertical path).
     * Writes {@link #CHEVRON_SEGS} segments per chevron into {@code out}; returns the segments written.
     */
    public static int chevrons(float[] pts, int n, int every, float size, float[] out) {
        if (every < 1 || n < 3) {
            return 0;
        }
        int segs = 0;
        int cap = out.length / SEG;
        for (int i = every; i < n - 1; i += every) {
            if (segs + CHEVRON_SEGS > cap) {
                break;
            }
            int a = (i - 1) * 3;
            int b = (i + 1) * 3;
            float dx = pts[b] - pts[a];
            float dy = pts[b + 1] - pts[a + 1];
            float dz = pts[b + 2] - pts[a + 2];
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1.0e-5f) {
                continue;
            }
            dx /= len;
            dy /= len;
            dz /= len;
            // side = dir x up = (-dz, 0, dx)
            float sx = -dz;
            float sz = dx;
            float sl = (float) Math.sqrt(sx * sx + sz * sz);
            if (sl < 1.0e-4f) {
                sx = 1f;
                sz = 0f;
            } else {
                sx /= sl;
                sz /= sl;
            }
            int p = i * 3;
            float h = size * 0.5f;
            float tx = pts[p] + dx * h;
            float ty = pts[p + 1] + dy * h;
            float tz = pts[p + 2] + dz * h;
            float bx = pts[p] - dx * h;
            float by = pts[p + 1] - dy * h;
            float bz = pts[p + 2] - dz * h;
            float w = size * 0.6f;
            int o = segs * SEG;
            out[o] = bx + sx * w;
            out[o + 1] = by;
            out[o + 2] = bz + sz * w;
            out[o + 3] = tx;
            out[o + 4] = ty;
            out[o + 5] = tz;
            out[o + 6] = bx - sx * w;
            out[o + 7] = by;
            out[o + 8] = bz - sz * w;
            out[o + 9] = tx;
            out[o + 10] = ty;
            out[o + 11] = tz;
            segs += CHEVRON_SEGS;
        }
        return segs;
    }

    /**
     * Ghost silhouette of a standing player with feet at (x, y, z): legs (0..0.75), body (0.75..1.5) and head
     * (1.5..2.0) boxes, written as {@link #GHOST_BOXES} boxes into {@code out}. Player-sized (0.6 wide, 1.8 tall
     * collision box; the head is drawn a little wider for readability).
     */
    public static void ghost(float x, float y, float z, float[] out) {
        box(out, 0, x, y, z, 0.25f, 0.13f, 0f, 0.75f);      // legs
        box(out, 1, x, y, z, 0.25f, 0.13f, 0.75f, 1.5f);    // body
        box(out, 2, x, y, z, 0.25f, 0.25f, 1.5f, 2.0f);     // head
    }

    private static void box(float[] out, int i, float x, float y, float z, float hx, float hz, float y0, float y1) {
        int o = i * BOX;
        out[o] = x - hx;
        out[o + 1] = y + y0;
        out[o + 2] = z - hz;
        out[o + 3] = x + hx;
        out[o + 4] = y + y1;
        out[o + 5] = z + hz;
    }

    /** Opacity of the ribbon near point {@code i} of {@code n}: fades from 1 at the hand to 0.35 at the end. */
    public static float fade(int i, int n) {
        if (n <= 1) {
            return 1f;
        }
        float t = Math.max(0f, Math.min(1f, i / (float) (n - 1)));
        return 1f - 0.65f * t;
    }
}
