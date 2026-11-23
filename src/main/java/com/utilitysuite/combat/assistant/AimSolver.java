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

    /**
     * Does the view ray from the eye along (yaw, pitch) meet the box within {@code range}? This is what vanilla's pick
     * (GameRenderer.pick, run at the start of every client tick right before the attack key is handled) will report
     * for that box, minus block occlusion. An eye inside the box counts as a hit at distance 0. Allocation free.
     */
    public static boolean viewRayHits(double ex, double ey, double ez, float yawDeg, float pitchDeg, double minX,
            double minY, double minZ, double maxX, double maxY, double maxZ, double range) {
        double yr = Math.toRadians(yawDeg);
        double pr = Math.toRadians(pitchDeg);
        double cp = Math.cos(pr);
        double dx = -Math.sin(yr) * cp;
        double dy = -Math.sin(pr);
        double dz = Math.cos(yr) * cp;
        double tEnter = 0.0;
        double tExit = range;
        // slab test per axis
        if (Math.abs(dx) < 1.0e-12) {
            if (ex < minX || ex > maxX) {
                return false;
            }
        } else {
            double a = (minX - ex) / dx;
            double b = (maxX - ex) / dx;
            tEnter = Math.max(tEnter, Math.min(a, b));
            tExit = Math.min(tExit, Math.max(a, b));
        }
        if (Math.abs(dy) < 1.0e-12) {
            if (ey < minY || ey > maxY) {
                return false;
            }
        } else {
            double a = (minY - ey) / dy;
            double b = (maxY - ey) / dy;
            tEnter = Math.max(tEnter, Math.min(a, b));
            tExit = Math.min(tExit, Math.max(a, b));
        }
        if (Math.abs(dz) < 1.0e-12) {
            if (ez < minZ || ez > maxZ) {
                return false;
            }
        } else {
            double a = (minZ - ez) / dz;
            double b = (maxZ - ez) / dz;
            tEnter = Math.max(tEnter, Math.min(a, b));
            tExit = Math.min(tExit, Math.max(a, b));
        }
        return tEnter <= tExit;
    }

    /** @param out {@code out[0]} = yaw, {@code out[1]} = pitch */
    public static void solve(CombatSnapshot s, double leadTicks, float[] out) {
        solve(s, s.tvx * leadTicks, s.tvy * leadTicks, s.tvz * leadTicks, out);
    }

    /**
     * Same aim point, with the target's box moved by an explicit world offset (where the target is expected to be,
     * e.g. from {@code StrafePhaseModel.predictOffset}) instead of velocity times a lead time.
     *
     * @param out {@code out[0]} = yaw, {@code out[1]} = pitch
     */
    public static void solve(CombatSnapshot s, double ox, double oy, double oz, float[] out) {
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

    /**
     * Yaw rate (degrees per tick, magnitude) the aim needs to keep tracking the target given both lateral
     * velocities: the target's velocity across the line of sight minus our own, over the horizontal distance.
     *
     * @param ownLateralPerTick our own strafe velocity across the line of sight in blocks per tick, + = our right
     *                          while facing the target (so {@code StrafePlanner.RIGHT} moves it positive)
     */
    public static double neededYawRateDegPerTick(CombatSnapshot s, double ownLateralPerTick) {
        double dx = s.tx - s.px;
        double dz = s.tz - s.pz;
        double r = Math.sqrt(dx * dx + dz * dz);
        if (r < 1.0e-3) {
            return 0.0;
        }
        double ux = dx / r;
        double uz = dz / r;
        // right of a forward vector (ux, uz) is (-uz, ux) in Minecraft's yaw convention
        double targetRight = s.tvx * -uz + s.tvz * ux;
        double relative = targetRight - ownLateralPerTick;
        return Math.abs(Math.toDegrees(relative / Math.max(r, 0.5)));
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
