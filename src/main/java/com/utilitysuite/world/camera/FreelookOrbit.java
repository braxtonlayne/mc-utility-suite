package com.utilitysuite.world.camera;

/**
 * Engine-free freelook state: a yaw/pitch offset of the camera from the player's real heading. While the key is held,
 * mouse turns add to the offset (pitch clamped so the view never passes straight up or down); after release the offset
 * eases back to zero with an exponential approach, never faster than {@link #MAX_RETURN_DEG_PER_S} (the suite's
 * rotation speed cap: no snapping, not even for the camera) and never slower than {@link #MIN_RETURN_DEG_PER_S} so it
 * always finishes. Allocation-free.
 */
public final class FreelookOrbit {

    public static final double MAX_RETURN_DEG_PER_S = 450.0;
    public static final double MIN_RETURN_DEG_PER_S = 60.0;
    /** Exponential return rate (1/s): about 90 % of the way back in 0.23 s, before the speed cap. */
    public static final double RETURN_RATE = 10.0;
    private static final double DONE_DEG = 0.05;

    private double yaw;
    private double pitch;

    /** Adds a mouse turn in degrees; {@code basePitch} is the player's pitch so the sum stays within [-90, 90]. */
    public void addTurn(double dYaw, double dPitch, double basePitch) {
        yaw += dYaw;
        if (yaw > 180.0 || yaw < -180.0) {
            yaw = wrap(yaw);
        }
        double total = Math.max(-90.0, Math.min(90.0, basePitch + pitch + dPitch));
        pitch = total - basePitch;
    }

    /** Eases the offset toward zero over {@code dtSeconds}. */
    public void ease(double dtSeconds) {
        if (dtSeconds <= 0.0) {
            return;
        }
        double mag = Math.sqrt(yaw * yaw + pitch * pitch);
        if (mag < DONE_DEG) {
            yaw = 0.0;
            pitch = 0.0;
            return;
        }
        double step = mag * (1.0 - Math.exp(-RETURN_RATE * dtSeconds));
        step = Math.max(step, MIN_RETURN_DEG_PER_S * dtSeconds);
        step = Math.min(step, MAX_RETURN_DEG_PER_S * dtSeconds);
        if (step >= mag) {
            yaw = 0.0;
            pitch = 0.0;
            return;
        }
        double k = (mag - step) / mag;
        yaw *= k;
        pitch *= k;
    }

    public boolean active() {
        return yaw != 0.0 || pitch != 0.0;
    }

    public double yaw() {
        return yaw;
    }

    public double pitch() {
        return pitch;
    }

    public void reset() {
        yaw = 0.0;
        pitch = 0.0;
    }

    /** Camera pitch for a given player pitch, clamped to [-90, 90]. */
    public double cameraPitch(double basePitch) {
        return Math.max(-90.0, Math.min(90.0, basePitch + pitch));
    }

    static double wrap(double deg) {
        double d = deg % 360.0;
        if (d > 180.0) {
            d -= 360.0;
        } else if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }
}
