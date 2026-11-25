package com.utilitysuite.travel.autopilot;

import com.utilitysuite.travel.movement.ElytraGlideSim;

/**
 * Riptide launch planning (engine free, allocation free). Vanilla 1.21.1 {@code TridentItem.releaseUsing}: after at
 * least {@link #MIN_CHARGE_TICKS} of use while in water or rain, the player is pushed along the look vector by the spin
 * strength (Riptide I/II/III = 1.5 / 2.25 / 3.0) and, when on the ground, moved up 1.2 blocks. The flight then follows
 * player air physics: {@code vy = (vy - 0.08) * 0.98}, horizontal speed times 0.91 per tick.
 * <p>
 * The planner picks the launch pitch (negative = up) that maximizes distance along the route: the riptide arc to its
 * apex, then (with an elytra) a best-pitch glide from the apex back down to the launch height, or (without) the rest of
 * the ballistic arc. Water drag on the first tick when launching from inside water is ignored (calibrate in game).
 */
public final class RiptideLaunchPlanner {

    public static final int MIN_CHARGE_TICKS = 10;
    public static final double GROUND_LIFT = 1.2;

    /** Riptide spin strength for an enchantment level (0 when none). */
    public static double strength(int level) {
        return level <= 0 ? 0 : 1.5 + 0.75 * (level - 1);
    }

    /** Plan result; reuse one instance (no allocation). */
    public static final class Plan {
        public boolean feasible;
        /** Launch pitch in degrees (Minecraft convention: negative looks up). */
        public float pitch;
        /** Ticks from release to apex, apex height above the launch feet, horizontal distance at the apex. */
        public int apexTick;
        public double apexHeight, apexTravel;
        /** Speeds at the apex (for the glide hand-off). */
        public double apexVh;
        /** Total predicted horizontal distance back to the launch height. */
        public double totalDistance;
    }

    private final ElytraGlideSim glide = new ElytraGlideSim();
    private final Plan scratch = new Plan();

    /** Plans one launch pitch: fills {@code out} for that pitch. */
    public Plan evaluate(Plan out, float pitchDeg, int level, boolean onGround, boolean elytra) {
        out.feasible = false;
        out.pitch = pitchDeg;
        double s = strength(level);
        if (s <= 0) {
            return out;
        }
        double rad = Math.toRadians(pitchDeg);
        double vh = Math.cos(rad) * s;
        double vy = -Math.sin(rad) * s;
        double y = onGround ? GROUND_LIFT : 0;
        double h = 0;
        int t = 0;
        // rise to the apex
        while (t < 400) {
            double ny = (vy - 0.08) * 0.98;
            if (vy <= 0) {
                break;
            }
            y += vy;
            h += vh;
            vy = ny;
            vh *= 0.91;
            t++;
        }
        out.apexTick = t;
        out.apexHeight = y;
        out.apexTravel = h;
        out.apexVh = vh;
        if (elytra) {
            glide.set(vh, Math.min(0, vy), y);
            double d = glide.distanceForDrop(ElytraGlideSim.BEST_GLIDE_PITCH, y, 4000);
            out.totalDistance = h + d;
        } else {
            while (y > 0 && t < 800) {
                y += vy;
                h += vh;
                vy = (vy - 0.08) * 0.98;
                vh *= 0.91;
                t++;
            }
            out.totalDistance = h;
        }
        out.feasible = true;
        return out;
    }

    /** Best launch pitch over -90..0 degrees in 2.5 degree steps; {@code out} holds the winning plan. */
    public Plan plan(Plan out, int level, boolean onGround, boolean elytra) {
        out.feasible = false;
        double best = -1;
        for (float p = -90f; p <= 0.001f; p += 2.5f) {
            evaluate(scratch, p, level, onGround, elytra);
            if (scratch.feasible && scratch.totalDistance > best) {
                best = scratch.totalDistance;
                copy(scratch, out);
            }
        }
        return out;
    }

    private static void copy(Plan a, Plan b) {
        b.feasible = a.feasible;
        b.pitch = a.pitch;
        b.apexTick = a.apexTick;
        b.apexHeight = a.apexHeight;
        b.apexTravel = a.apexTravel;
        b.apexVh = a.apexVh;
        b.totalDistance = a.totalDistance;
    }
}
