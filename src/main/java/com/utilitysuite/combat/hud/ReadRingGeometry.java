package com.utilitysuite.combat.hud;

/**
 * Engine-free geometry of "The Read" ring: a horizontal circle of {@link #SEGMENTS} segments around a centre, tilted up
 * to {@link #MAX_TILT_DEG} degrees toward a horizontal direction (the side the ring tilts toward dips down, like a
 * plate leaning that way), plus a bright arc over the first {@code charge} of the circle starting at angle 0 (+X) and
 * running toward +Z.
 * <p>
 * Output is a line list in a caller-owned float array: three floats (x, y, z) per vertex, two vertices per segment.
 * Vertices {@code 0 .. OUTLINE_VERTS-1} are the full dim outline; the next {@code returned} vertices are the arc.
 * Nothing is allocated; sin/cos of the segment angles come from tables.
 */
public final class ReadRingGeometry {

    public static final int SEGMENTS = 32;
    public static final int OUTLINE_VERTS = SEGMENTS * 2;
    /** Floats a buffer needs: the outline plus a full arc. */
    public static final int MAX_FLOATS = OUTLINE_VERTS * 2 * 3;
    public static final float MAX_TILT_DEG = 25f;

    private static final float MAX_TILT_RAD = (float) Math.toRadians(MAX_TILT_DEG);
    private static final float[] COS = new float[SEGMENTS + 1];
    private static final float[] SIN = new float[SEGMENTS + 1];

    static {
        for (int i = 0; i <= SEGMENTS; i++) {
            double a = Math.PI * 2.0 * i / SEGMENTS;
            COS[i] = (float) Math.cos(a);
            SIN[i] = (float) Math.sin(a);
        }
    }

    private ReadRingGeometry() {
    }

    /**
     * Fills {@code out} (at least {@link #MAX_FLOATS} long) and returns the number of arc vertices that follow the
     * outline (0 for charge 0, {@link #OUTLINE_VERTS} for charge 1). {@code tiltX/tiltZ} is the tilt vector: its
     * direction is the side that dips, its length (capped at 1) scales {@link #MAX_TILT_DEG}.
     */
    public static int fill(float[] out, float cx, float cy, float cz, float radius, float charge, float tiltX,
                           float tiltZ) {
        float len = (float) Math.sqrt(tiltX * tiltX + tiltZ * tiltZ);
        float dx = 1f;
        float dz = 0f;
        float sinT = 0f;
        float cosT = 1f;
        if (len > 1.0e-4f) {
            dx = tiltX / len;
            dz = tiltZ / len;
            float a = Math.min(len, 1f) * MAX_TILT_RAD;
            sinT = (float) Math.sin(a);
            cosT = (float) Math.cos(a);
        }
        int o = 0;
        for (int i = 0; i < SEGMENTS; i++) {
            o = put(out, o, cx, cy, cz, radius, COS[i], SIN[i], dx, dz, sinT, cosT);
            o = put(out, o, cx, cy, cz, radius, COS[i + 1], SIN[i + 1], dx, dz, sinT, cosT);
        }
        float c = charge < 0f ? 0f : Math.min(charge, 1f);
        float full = c * SEGMENTS;
        int whole = (int) full;
        float frac = full - whole;
        int arcFloats = 0;
        for (int i = 0; i < whole; i++) {
            o = put(out, o, cx, cy, cz, radius, COS[i], SIN[i], dx, dz, sinT, cosT);
            o = put(out, o, cx, cy, cz, radius, COS[i + 1], SIN[i + 1], dx, dz, sinT, cosT);
            arcFloats += 6;
        }
        if (frac > 1.0e-4f && whole < SEGMENTS) {
            double a = Math.PI * 2.0 * (whole + frac) / SEGMENTS;
            o = put(out, o, cx, cy, cz, radius, COS[whole], SIN[whole], dx, dz, sinT, cosT);
            o = put(out, o, cx, cy, cz, radius, (float) Math.cos(a), (float) Math.sin(a), dx, dz, sinT, cosT);
            arcFloats += 6;
        }
        return arcFloats / 3;
    }

    private static int put(float[] out, int o, float cx, float cy, float cz, float r, float c, float s, float dx,
                           float dz, float sinT, float cosT) {
        float lx = r * c;
        float lz = r * s;
        float u = lx * dx + lz * dz; // component along the tilt direction
        float k = u * (cosT - 1f);
        out[o] = cx + lx + dx * k;
        out[o + 1] = cy - u * sinT;
        out[o + 2] = cz + lz + dz * k;
        return o + 3;
    }

    /** Tilt confidence below which no strafe side is shown. */
    public static final float SIDE_MIN_CONFIDENCE = 0.2f;

    /**
     * The side of the screen the predicted strafe points to, for the reticle chevron: +1 right, -1 left, 0 when the
     * tilt is weaker than {@link #SIDE_MIN_CONFIDENCE} or mostly along the view. {@code yawDeg} is the viewer's yaw in
     * the Minecraft convention (0 = facing +Z, 90 = facing -X); the viewer's right is then (-cos yaw, -sin yaw).
     */
    public static int screenSide(float tiltX, float tiltZ, float yawDeg) {
        float len2 = tiltX * tiltX + tiltZ * tiltZ;
        if (len2 < SIDE_MIN_CONFIDENCE * SIDE_MIN_CONFIDENCE) {
            return 0;
        }
        double yaw = Math.toRadians(yawDeg);
        double dot = tiltX * -Math.cos(yaw) + tiltZ * -Math.sin(yaw);
        if (Math.abs(dot) < Math.sqrt(len2) * 0.25) {
            return 0; // mostly toward or away from the viewer
        }
        return dot > 0 ? 1 : -1;
    }
}
