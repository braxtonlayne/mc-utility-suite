package com.utilitysuite.world.atlas;

/**
 * Engine-free projection for the 3D hologram mode: world points relative to a centre are rotated by {@code yaw} about
 * the vertical axis, tilted by {@code pitch} (0 = straight down, up to 80 degrees towards the horizon) and projected
 * orthographically onto the screen around (originX, originY). Heights are exaggerated by {@code heightScale}.
 * {@link #project} writes into {@link #sx}, {@link #sy} and {@link #depth} (larger depth = farther from the viewer), so
 * per-frame projection allocates nothing.
 */
public final class AtlasIso {

    public static final double MIN_PITCH = 0.0;
    public static final double MAX_PITCH = Math.toRadians(80);

    private double yaw = Math.toRadians(45);
    private double pitch = Math.toRadians(55);
    private double scale = 2.0;
    private double heightScale = 1.0;
    private double originX;
    private double originY;
    private double cy = 1;
    private double sy0;
    private double cp = 1;
    private double sp;

    // Outputs of the last project call.
    public double sx;
    public double sy;
    public double depth;

    public AtlasIso() {
        trig();
    }

    public void setOrigin(double x, double y) {
        originX = x;
        originY = y;
    }

    public void setScale(double s) {
        scale = AtlasView.clampScale(s);
    }

    public double scale() {
        return scale;
    }

    public void setHeightScale(double h) {
        heightScale = h;
    }

    public double yaw() {
        return yaw;
    }

    public double pitch() {
        return pitch;
    }

    /** Mouse-drag style rotation: dx turns, dy tilts (clamped). */
    public void rotate(double dYaw, double dPitch) {
        yaw = wrap(yaw + dYaw);
        pitch = Math.max(MIN_PITCH, Math.min(MAX_PITCH, pitch + dPitch));
        trig();
    }

    public void set(double yawRad, double pitchRad) {
        yaw = wrap(yawRad);
        pitch = Math.max(MIN_PITCH, Math.min(MAX_PITCH, pitchRad));
        trig();
    }

    private void trig() {
        cy = Math.cos(yaw);
        sy0 = Math.sin(yaw);
        cp = Math.cos(pitch);
        sp = Math.sin(pitch);
    }

    private static double wrap(double a) {
        double t = Math.PI * 2;
        a %= t;
        return a < 0 ? a + t : a;
    }

    /** Projects a point given relative to the centre (dx east, dz south, dy up). */
    public void project(double dx, double dy, double dz) {
        double rx = dx * cy - dz * sy0;
        double rz = dx * sy0 + dz * cy; // +rz = towards the bottom of the screen (nearer the viewer)
        double h = dy * heightScale;
        sx = originX + rx * scale;
        sy = originY + (rz * cp - h * sp) * scale;
        depth = -(rz * sp + h * cp);
    }

    /**
     * Whether the grid should be walked with increasing x / z for back-to-front (painter's) order: the far side of
     * the grid comes first. Returns bit 0 = walk x ascending, bit 1 = walk z ascending.
     */
    public int paintOrder() {
        // depth of +x and +z unit steps (ignoring height)
        double depthX = -(sy0 * sp);
        double depthZ = -(cy * sp);
        int bits = 0;
        if (depthX <= 0) {
            bits |= 1; // +x is nearer: start from low x (far) and walk up
        }
        if (depthZ <= 0) {
            bits |= 2;
        }
        return bits;
    }
}
