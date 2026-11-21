package com.utilitysuite.combat.coach;

/** Pure geometry for danger warnings. Allocation free. */
public final class ThreatMath {

    /** A projectile passing closer than this (blocks) to the player's centre counts as incoming. */
    public static final double HIT_RADIUS = 1.2;
    public static final int HORIZON_TICKS = 30;

    private ThreatMath() {
    }

    /**
     * Squared closest distance over the next {@code horizonTicks} between a point moving with constant velocity and a
     * fixed point. {@code rx,ry,rz} is the moving point's position relative to the fixed point.
     */
    public static double closestApproachSq(double rx, double ry, double rz, double vx, double vy, double vz,
                                           int horizonTicks) {
        double vv = vx * vx + vy * vy + vz * vz;
        double t = 0.0;
        if (vv > 1.0e-9) {
            t = -(rx * vx + ry * vy + rz * vz) / vv;
            t = Math.max(0.0, Math.min(horizonTicks, t));
        }
        double cx = rx + vx * t;
        double cy = ry + vy * t;
        double cz = rz + vz * t;
        return cx * cx + cy * cy + cz * cz;
    }

    public static boolean incoming(double rx, double ry, double rz, double vx, double vy, double vz) {
        double vv = vx * vx + vy * vy + vz * vz;
        if (vv < 0.01) {
            return false; // resting (arrow in the ground) or drifting
        }
        return closestApproachSq(rx, ry, rz, vx, vy, vz, HORIZON_TICKS) <= HIT_RADIUS * HIT_RADIUS;
    }

    /** Yaw of a direction relative to the view yaw, in (-180, 180]; positive = to the right. */
    public static float relativeYaw(double dx, double dz, float viewYaw) {
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float d = (yaw - viewYaw) % 360f;
        if (d > 180f) {
            d -= 360f;
        } else if (d <= -180f) {
            d += 360f;
        }
        return d;
    }

    /** World yaw (Minecraft convention: 0 = +Z, 90 = -X) of the direction (dx, dz). */
    public static float worldYaw(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /**
     * Vanilla explosion damage to an unarmoured, fully exposed entity at {@code distance} from a blast of {@code power}
     * (creeper 3, charged creeper 6, TNT 4): {@code (i*i + i) * 7 * power + 1} with {@code i = 1 - d / (2 * power)}.
     */
    public static double blastDamage(double distance, double power) {
        double i = 1.0 - distance / (2.0 * power);
        return i <= 0.0 ? 0.0 : (i * i + i) * 7.0 * power + 1.0;
    }
}
