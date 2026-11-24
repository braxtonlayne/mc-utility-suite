package com.utilitysuite.combat.assistant;

/**
 * Multi-attacker positioning: with a second melee attacker near, which strafe side keeps the target between us and
 * it. Let {@code a} = self to target and {@code b} = target to the second attacker; the angle between them is 0 when
 * the target stands exactly between us and the second attacker (it has to walk around the target) and grows as the
 * second attacker gets a clear line to us. Each side is scored by the angle after one block of lateral travel, and
 * the side that opens the angle is penalised: the planner is only <em>biased</em> toward the other side (a soft
 * preference through {@link StrafePlanner}'s move-with channel), never forced, so hazards, forced sides and every
 * retreat or escape path still win. Allocation free; results are in fields after {@link #plan}.
 */
public final class FlankGuard {

    /** Lateral trial step in blocks. */
    static final double STEP = 1.0;
    /** An angle difference between the two sides that counts as a full-strength preference (radians). */
    static final double FULL_DELTA = 0.5;
    /** Closer than this to the target the geometry is degenerate: no preference. */
    static final double MIN_RANGE = 0.5;

    private int side = StrafePlanner.NONE;
    private double strength;

    /**
     * @return true when there is a preference; {@link #side()} is then LEFT or RIGHT and {@link #strength()} 0..1
     */
    public boolean plan(double px, double pz, double tx, double tz, double sx, double sz) {
        side = StrafePlanner.NONE;
        strength = 0.0;
        double ax = tx - px;
        double az = tz - pz;
        double al = Math.sqrt(ax * ax + az * az);
        double bx = sx - tx;
        double bz = sz - tz;
        if (al < MIN_RANGE || bx * bx + bz * bz < 1.0e-6) {
            return false;
        }
        // our left in world axes (x east, z south) for a facing (ax, az): (az, -ax), as the threat stage uses
        double lx = az / al;
        double lz = -ax / al;
        double left = angle(tx - (px + lx * STEP), tz - (pz + lz * STEP), bx, bz);
        double right = angle(tx - (px - lx * STEP), tz - (pz - lz * STEP), bx, bz);
        strength = Math.min(1.0, Math.abs(left - right) / FULL_DELTA);
        if (strength <= 0.0) {
            return false;
        }
        side = left < right ? StrafePlanner.LEFT : StrafePlanner.RIGHT;
        return true;
    }

    /** The unsigned angle between two horizontal vectors, 0..pi. */
    static double angle(double ax, double az, double bx, double bz) {
        return Math.atan2(Math.abs(ax * bz - az * bx), ax * bx + az * bz);
    }

    public int side() {
        return side;
    }

    public double strength() {
        return strength;
    }
}
