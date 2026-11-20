package com.utilitysuite.combat.assistant;

/**
 * Aim point: the point of the target's bounding box nearest to the player's eye (slightly inset so the crosshair ray
 * lands inside), optionally led by the target's velocity. Writes yaw/pitch in degrees (Minecraft convention) into a
 * caller-owned array. Allocation free.
 */
public final class AimSolver {

    private static final double INSET = 0.08;

    private AimSolver() {
    }

    /** @param out {@code out[0]} = yaw, {@code out[1]} = pitch */
    public static void solve(CombatSnapshot s, double leadTicks, float[] out) {
        double ox = s.tvx * leadTicks;
        double oy = s.tvy * leadTicks;
        double oz = s.tvz * leadTicks;
        double x = clampInset(s.px, s.minX + ox, s.maxX + ox);
        double y = clampInset(s.eyeY, s.minY + oy, s.maxY + oy);
        double z = clampInset(s.pz, s.minZ + oz, s.maxZ + oz);
        double dx = x - s.px;
        double dy = y - s.eyeY;
        double dz = z - s.pz;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz < 1.0e-4) {
            // Directly above/below or inside the box: aim at the box centre instead.
            dx = (s.minX + s.maxX) * 0.5 + ox - s.px;
            dz = (s.minZ + s.maxZ) * 0.5 + oz - s.pz;
            horiz = Math.sqrt(dx * dx + dz * dz);
        }
        out[0] = (float) Math.toDegrees(Math.atan2(-dx, dz));
        out[1] = (float) -Math.toDegrees(Math.atan2(dy, Math.max(horiz, 1.0e-4)));
    }

    private static double clampInset(double v, double lo, double hi) {
        double a = lo + INSET;
        double b = hi - INSET;
        if (a > b) {
            return (lo + hi) * 0.5;
        }
        return v < a ? a : Math.min(v, b);
    }

    /** Smallest signed difference between two yaw angles in degrees. */
    public static float yawDelta(float a, float b) {
        float d = (a - b) % 360f;
        if (d >= 180f) {
            d -= 360f;
        } else if (d < -180f) {
            d += 360f;
        }
        return d;
    }
}
