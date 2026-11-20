package com.utilitysuite.world.camera;

/**
 * Detached camera movement, integrated once per client tick from the user's own movement input (forward/strafe
 * impulses, jump = up, sneak = down) relative to the camera's yaw. Flies through terrain (no collision). Keeps the
 * previous position for render interpolation. Pure; allocation-free.
 */
public final class FreecamMotion {

    private double x;
    private double y;
    private double z;
    private double prevX;
    private double prevY;
    private double prevZ;
    private double vx;
    private double vy;
    private double vz;
    private double speed = 1.0;
    private double sprintMultiplier = 2.5;
    /** Fraction of velocity kept per tick when no input (0 = stop instantly). */
    private double damping = 0.55;

    public void configure(double blocksPerTick, double sprintMultiplier, double damping) {
        this.speed = Math.max(0.05, blocksPerTick);
        this.sprintMultiplier = Math.max(1.0, sprintMultiplier);
        this.damping = Math.max(0.0, Math.min(0.95, damping));
    }

    public void reset(double x, double y, double z) {
        this.x = this.prevX = x;
        this.y = this.prevY = y;
        this.z = this.prevZ = z;
        vx = vy = vz = 0;
    }

    /**
     * @param forward  forward impulse (-1..1), vanilla {@code Input.forwardImpulse}
     * @param left     strafe impulse (-1..1), vanilla {@code Input.leftImpulse}
     * @param up       jump held
     * @param down     sneak held
     * @param yawDeg   camera yaw in degrees
     */
    public void tick(float forward, float left, boolean up, boolean down, boolean sprint, float yawDeg) {
        prevX = x;
        prevY = y;
        prevZ = z;
        double s = speed * (sprint ? sprintMultiplier : 1.0);
        double rad = Math.toRadians(yawDeg);
        double sin = Math.sin(rad);
        double cos = Math.cos(rad);
        double len = Math.sqrt(forward * forward + left * left);
        double f = forward;
        double l = left;
        if (len > 1.0) {
            f /= len;
            l /= len;
        }
        // Vanilla convention: yaw 0 looks toward +Z; forward = (-sin, cos), left = (cos, sin).
        double tx = (-sin * f + cos * l) * s;
        double tz = (cos * f + sin * l) * s;
        double ty = ((up ? 1 : 0) - (down ? 1 : 0)) * s;
        boolean anyH = forward != 0 || left != 0;
        vx = anyH ? tx : vx * damping;
        vz = anyH ? tz : vz * damping;
        vy = (up || down) ? ty : vy * damping;
        x += vx;
        y += vy;
        z += vz;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public double prevX() {
        return prevX;
    }

    public double prevY() {
        return prevY;
    }

    public double prevZ() {
        return prevZ;
    }
}
