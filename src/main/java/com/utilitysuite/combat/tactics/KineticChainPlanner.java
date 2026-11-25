package com.utilitysuite.combat.tactics;

/**
 * Wind-charge self-launch calculator (engine free, allocation free) for the kinetic chain overlay: for a burst point on
 * a floor or wall near the player, the knockback the burst gives, the predicted apex of the launch and, with a mace, the
 * smash damage from that apex onto a target height.
 * <p>
 * Knockback shape follows vanilla 1.21.1 {@code Explosion.explode} for a wind charge (radius {@code 2 * 1.2 = 2.4},
 * knockback multiplier 1.22): strength {@code (1 - dist/2.4)} with {@code dist} from the burst to the player's feet,
 * direction from the burst to the eyes. The magnitude is scaled so a burst at the feet on the jump tick
 * ({@link #REF_DIST} blocks below the feet) gives {@link MaceDivePlanner#WIND_LAUNCH_VY}, the planner-wide estimate.
 * CALIBRATION: that estimate still needs measuring in game; change it there and both planners follow.
 */
public final class KineticChainPlanner {

    public static final double RADIUS = 2.4;
    /** Burst-to-feet distance of the reference feet throw on the jump tick. */
    public static final double REF_DIST = 0.42;
    /** Mace attack damage (player base 1 + mace 5) before the smash bonus. */
    public static final float MACE_BASE_DAMAGE = 6f;
    public static final int HORIZON = 200;

    private KineticChainPlanner() {
    }

    /** Knockback magnitude for a burst {@code dist} blocks from the feet (0 outside the radius). */
    public static double strength(double dist) {
        if (dist >= RADIUS) {
            return 0;
        }
        double shape = 1.0 - dist / RADIUS;
        return MaceDivePlanner.WIND_LAUNCH_VY * shape / (1.0 - REF_DIST / RADIUS);
    }

    /** Result; reuse one instance. */
    public static final class Launch {
        public boolean inRange;
        /** Velocity added by the burst. */
        public double kx, ky, kz;
        /** Apex height above the feet at the burst, ticks to it, horizontal distance to it. */
        public double apex;
        public int apexTick;
        public double apexTravel;
    }

    /**
     * Evaluates a burst at (bx, by, bz) for a player with feet at (px, py, pz) and current velocity (vx, vy, vz).
     */
    public static Launch evaluate(Launch out, double px, double py, double pz, double vx, double vy, double vz,
                                  double bx, double by, double bz) {
        double fx = px - bx, fy = py - by, fz = pz - bz;
        double dist = Math.sqrt(fx * fx + fy * fy + fz * fz);
        double s = strength(dist);
        out.inRange = s > 0;
        if (!out.inRange) {
            out.kx = out.ky = out.kz = 0;
            out.apex = 0;
            out.apexTick = 0;
            out.apexTravel = 0;
            return out;
        }
        double ex = px - bx, ey = py + MaceDivePlanner.EYE - by, ez = pz - bz;
        double len = Math.sqrt(ex * ex + ey * ey + ez * ez);
        if (len < 1e-6) {
            ex = 0;
            ey = 1;
            ez = 0;
            len = 1;
        }
        out.kx = ex / len * s;
        out.ky = ey / len * s;
        out.kz = ez / len * s;
        apex(out, vx + out.kx, vy + out.ky, vz + out.kz);
        return out;
    }

    /** Fills apex fields from a start velocity under player air physics. */
    static void apex(Launch out, double vx, double vy, double vz) {
        double y = 0, h = 0, vh = Math.sqrt(vx * vx + vz * vz);
        int t = 0;
        while (vy > 0 && t < HORIZON) {
            y += vy;
            h += vh;
            vy = (vy - MaceDivePlanner.GRAVITY) * MaceDivePlanner.DRAG;
            vh *= MaceDivePlanner.AIR_FRICTION;
            t++;
        }
        out.apex = y;
        out.apexTick = t;
        out.apexTravel = h;
    }

    /**
     * Predicted mace hit damage (base + smash bonus) falling from the apex onto a spot {@code targetDy} blocks relative
     * to the launch feet (negative = below). 0 when the fall does not reach a smash.
     */
    public static float smashDamage(double apex, double targetDy, int density) {
        float fall = (float) (apex - targetDy);
        float bonus = MaceDivePlanner.smashBonus(fall, density);
        return bonus > 0 ? MACE_BASE_DAMAGE + bonus : 0f;
    }

    /** Burst point of a throw at a block face: the face centre offset 0.05 out of the block. */
    public static double faceCoord(int block, int normal) {
        return block + 0.5 + normal * 0.55;
    }
}
