package com.utilitysuite.combat.tactics;

/**
 * Incoming straight-flying projectiles (ghast fireballs, wind charges): is it heading at us, how long until it
 * arrives, and can it be hit back with a legitimately smoothed turn before it does. Engine free, allocation free.
 * <p>
 * The projectile is modelled as moving with its current per-tick velocity (hurting projectiles keep a near constant
 * speed: their acceleration balances the 0.95 drag). "Heading at us" is a closest-approach test of that ray against a
 * sphere of {@code radius} around the body centre within {@code horizonTicks}. The deflect plan picks the earliest
 * whole tick at which the projectile is within attack reach of the eye and the camera can get there under the
 * speed cap ({@code maxDegPerTick}, the smoother's cap) with {@code reactionTicks} to spare; no such tick means skip.
 */
public final class DeflectPlanner {

    private double impactTicks;
    private double missDistance;
    private int strikeTick;
    private float strikeYaw, strikePitch;

    /**
     * @return true when the ray passes within {@code radius} of {@code (cx, cy, cz)} in {@code (0, horizonTicks]};
     *         {@link #impactTicks()} and {@link #missDistance()} then describe the closest approach
     */
    public boolean incoming(double px, double py, double pz, double vx, double vy, double vz,
                            double cx, double cy, double cz, double radius, double horizonTicks) {
        double rx = px - cx, ry = py - cy, rz = pz - cz;
        double vv = vx * vx + vy * vy + vz * vz;
        if (vv < 1.0e-8) {
            return false;
        }
        double t = -(rx * vx + ry * vy + rz * vz) / vv;
        if (t <= 0.0 || t > horizonTicks) {
            return false;
        }
        double mx = rx + vx * t, my = ry + vy * t, mz = rz + vz * t;
        missDistance = Math.sqrt(mx * mx + my * my + mz * mz);
        impactTicks = t;
        return missDistance <= radius;
    }

    /**
     * Plans the swing for a projectile already judged {@link #incoming}.
     *
     * @param ex, ey, ez     eye position
     * @param yaw, pitch     current camera
     * @param reach          attack reach (blocks, eye to projectile centre)
     * @param maxDegPerTick  the smoother's rotation speed cap
     * @param reactionTicks  ticks kept spare on top of the turn (input latency, the swing tick)
     * @return true when a strike tick exists; {@link #strikeTick()} and the look in {@link #strikeYaw()} /
     *         {@link #strikePitch()}
     */
    public boolean planStrike(double px, double py, double pz, double vx, double vy, double vz,
                              double ex, double ey, double ez, float yaw, float pitch, double reach,
                              double maxDegPerTick, int reactionTicks) {
        int last = (int) Math.ceil(impactTicks);
        double reach2 = reach * reach;
        for (int k = 0; k <= last; k++) {
            double qx = px + vx * k - ex, qy = py + vy * k - ey, qz = pz + vz * k - ez;
            double d2 = qx * qx + qy * qy + qz * qz;
            if (d2 > reach2) {
                continue;
            }
            float y = (float) (Math.toDegrees(Math.atan2(qz, qx)) - 90.0);
            float p = (float) -Math.toDegrees(Math.atan2(qy, Math.sqrt(qx * qx + qz * qz)));
            double turn = Math.max(Math.abs(wrap(y - yaw)), Math.abs(p - pitch));
            int needed = (int) Math.ceil(turn / Math.max(1.0e-6, maxDegPerTick)) + reactionTicks;
            if (needed <= k) {
                strikeTick = k;
                strikeYaw = y;
                strikePitch = p;
                return true;
            }
        }
        return false;
    }

    private static double wrap(double d) {
        d %= 360.0;
        if (d >= 180.0) {
            d -= 360.0;
        }
        if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }

    public double impactTicks() {
        return impactTicks;
    }

    public double missDistance() {
        return missDistance;
    }

    /** Ticks from now at which the swing should land. */
    public int strikeTick() {
        return strikeTick;
    }

    public float strikeYaw() {
        return strikeYaw;
    }

    public float strikePitch() {
        return strikePitch;
    }

    /**
     * Pearl landing countdown text, e.g. {@code "Pearl 1.4s"}, into a reusable builder; nothing when the sim found no
     * landing. Uses {@link PearlLandingSim} (the same thrown-projectile physics).
     *
     * @return ticks to landing, or -1 when unknown
     */
    public static int pearlCountdown(PearlLandingSim sim, double x, double y, double z, double vx, double vy,
                                     double vz, CombatVoxels w, StringBuilder out) {
        out.setLength(0);
        if (!sim.simulate(x, y, z, vx, vy, vz, w, PearlLandingSim.MAX_TICKS)) {
            return -1;
        }
        int ticks = sim.flightTicks();
        int tenths = (ticks + 1) / 2; // 20 ticks/s -> tenths of a second, rounded
        out.append("Pearl ").append(tenths / 10).append('.').append(tenths % 10).append('s');
        return ticks;
    }
}
