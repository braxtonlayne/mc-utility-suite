package com.utilitysuite.travel;

/**
 * Minecraft angle conventions, engine-free. Yaw 0 faces +Z (south), 90 faces -X (west); pitch is positive looking down.
 * A yaw's horizontal direction is {@code (-sin(yaw), cos(yaw))}.
 */
public final class Angles {

    private Angles() {
    }

    /** Wraps degrees into [-180, 180). */
    public static double wrap(double deg) {
        double d = deg % 360.0;
        if (d >= 180.0) {
            d -= 360.0;
        } else if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }

    /** Signed shortest turn from {@code fromDeg} to {@code toDeg}, in [-180, 180). */
    public static double delta(double fromDeg, double toDeg) {
        return wrap(toDeg - fromDeg);
    }

    /** Yaw that faces the horizontal offset (dx, dz). */
    public static double yawTo(double dx, double dz) {
        return Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** Pitch (positive down) that looks along (horizontalDistance, dy). */
    public static double pitchTo(double horizontalDistance, double dy) {
        return -Math.toDegrees(Math.atan2(dy, horizontalDistance));
    }

    public static double dirX(double yawDeg) {
        return -Math.sin(Math.toRadians(yawDeg));
    }

    public static double dirZ(double yawDeg) {
        return Math.cos(Math.toRadians(yawDeg));
    }
}
