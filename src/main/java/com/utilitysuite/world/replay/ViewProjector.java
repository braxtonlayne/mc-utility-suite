package com.utilitysuite.world.replay;

/**
 * Pinhole projection from a virtual camera pose (Minecraft yaw/pitch convention: yaw 0 faces +Z, yaw 90 faces -X,
 * pitch +90 looks down) to normalised panel coordinates (x right, y up, both -1..1 inside the view). Used by the
 * picture-in-picture panel (rear view, parked drone) and by the replay screen's orbit view. Also builds the rear-view
 * and orbit poses. Allocation free. Engine free.
 */
public final class ViewProjector {

    private static final double NEAR = 0.05;

    private double cx;
    private double cy;
    private double cz;
    private double fx;
    private double fy;
    private double fz;
    private double rx;
    private double ry;
    private double rz;
    private double ux;
    private double uy;
    private double uz;
    private double focal = 1.0;
    private double aspect = 1.0;
    private float yaw;
    private float pitch;

    /** Sets the camera. {@code fovY} in degrees; {@code aspect} = width / height. */
    public void set(double x, double y, double z, float yawDeg, float pitchDeg, double fovY, double aspect) {
        cx = x;
        cy = y;
        cz = z;
        yaw = yawDeg;
        pitch = pitchDeg;
        double ya = Math.toRadians(yawDeg);
        double pa = Math.toRadians(pitchDeg);
        double cp = Math.cos(pa);
        fx = -Math.sin(ya) * cp;
        fy = -Math.sin(pa);
        fz = Math.cos(ya) * cp;
        rx = -Math.cos(ya);
        ry = 0;
        rz = -Math.sin(ya);
        // up = right x forward
        ux = ry * fz - rz * fy;
        uy = rz * fx - rx * fz;
        uz = rx * fy - ry * fx;
        focal = 1.0 / Math.tan(Math.toRadians(Math.max(5, Math.min(170, fovY))) * 0.5);
        this.aspect = aspect <= 0 ? 1.0 : aspect;
    }

    public double camX() {
        return cx;
    }

    public double camY() {
        return cy;
    }

    public double camZ() {
        return cz;
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    /** Projects a world point; writes ndc x, y and view depth to out[0..2]. False when behind the near plane. */
    public boolean project(double x, double y, double z, float[] out) {
        double dx = x - cx;
        double dy = y - cy;
        double dz = z - cz;
        double d = dx * fx + dy * fy + dz * fz;
        if (d < NEAR) {
            return false;
        }
        double sx = (dx * rx + dy * ry + dz * rz) * focal / (d * aspect);
        double sy = (dx * ux + dy * uy + dz * uz) * focal / d;
        out[0] = (float) sx;
        out[1] = (float) sy;
        out[2] = (float) d;
        return true;
    }

    /** Rear-view pose from the eye: yaw turned 180 degrees, pitch levelled to a slight downward tilt. */
    public void setRearView(double eyeX, double eyeY, double eyeZ, float yawDeg, double fovY, double aspect) {
        set(eyeX, eyeY, eyeZ, wrap(yawDeg + 180f), 8f, fovY, aspect);
    }

    /**
     * Orbit pose around a target: the camera sits {@code distance} back along the view direction given by
     * {@code yawDeg}/{@code pitchDeg}, looking at the target.
     */
    public void setOrbit(double tx, double ty, double tz, float yawDeg, float pitchDeg, double distance, double fovY,
            double aspect) {
        set(tx, ty, tz, yawDeg, pitchDeg, fovY, aspect);
        double ox = tx - fx * distance;
        double oy = ty - fy * distance;
        double oz = tz - fz * distance;
        cx = ox;
        cy = oy;
        cz = oz;
    }

    private final float[] corner = new float[3];
    private final float[] cornerXY = new float[16];
    private final boolean[] cornerOk = new boolean[8];
    private static final int[] EDGES = {0, 1, 1, 3, 3, 2, 2, 0, 4, 5, 5, 7, 7, 6, 6, 4, 0, 4, 1, 5, 2, 6, 3, 7};

    /**
     * Projects the 12 edges of an axis-aligned box into {@code segs} as (x1, y1, x2, y2) ndc quadruples starting at
     * {@code off}; edges with an endpoint behind the camera are skipped. Returns the new offset (never past the
     * array). Allocation free.
     */
    public int projectBox(double x0, double y0, double z0, double x1, double y1, double z1, float[] segs, int off) {
        for (int c = 0; c < 8; c++) {
            double x = (c & 1) == 0 ? x0 : x1;
            double y = (c & 4) == 0 ? y0 : y1;
            double z = (c & 2) == 0 ? z0 : z1;
            cornerOk[c] = project(x, y, z, corner);
            cornerXY[c * 2] = corner[0];
            cornerXY[c * 2 + 1] = corner[1];
        }
        for (int e = 0; e < EDGES.length; e += 2) {
            int a = EDGES[e];
            int b = EDGES[e + 1];
            if (!cornerOk[a] || !cornerOk[b] || off + 4 > segs.length) {
                continue;
            }
            segs[off++] = cornerXY[a * 2];
            segs[off++] = cornerXY[a * 2 + 1];
            segs[off++] = cornerXY[b * 2];
            segs[off++] = cornerXY[b * 2 + 1];
        }
        return off;
    }

    /** Projects one segment; returns the new offset (unchanged if an end is behind the camera). */
    public int projectSegment(double ax, double ay, double az, double bx, double by, double bz, float[] segs,
            int off) {
        if (off + 4 > segs.length || !project(ax, ay, az, corner)) {
            return off;
        }
        float x1 = corner[0];
        float y1 = corner[1];
        if (!project(bx, by, bz, corner)) {
            return off;
        }
        segs[off] = x1;
        segs[off + 1] = y1;
        segs[off + 2] = corner[0];
        segs[off + 3] = corner[1];
        return off + 4;
    }

    static float wrap(float deg) {
        float r = deg % 360f;
        if (r >= 180f) {
            r -= 360f;
        }
        if (r < -180f) {
            r += 360f;
        }
        return r;
    }
}
