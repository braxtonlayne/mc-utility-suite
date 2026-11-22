package com.utilitysuite.combat.assistant;

import com.utilitysuite.combat.tactics.ArenaField;
import com.utilitysuite.combat.tactics.ArenaQuery;
import com.utilitysuite.combat.tactics.TacticUtility;

/**
 * Turns the arena read into movement for the two positional tactics:
 * <ul>
 *   <li>{@link TacticUtility.Tactic#HIGH_GROUND}: walk to a nearby spot above the target and fight from there (crits
 *       come easier, our knockback sends it downhill).</li>
 *   <li>{@link TacticUtility.Tactic#CEILING_DENY}: against a crit-hopper, back under a low ceiling so its hops bonk,
 *       and hold there.</li>
 * </ul>
 * {@link #assess} rates how available each is (inputs of {@link TacticUtility}); {@link #plan} steers while one is the
 * chosen tactic. Keys are relative to facing the target (the aim stays on it), so walking to a spot is a mix of
 * forward/back and strafe: with forward = (fx, fz) toward the target, Minecraft's left is (fz, -fx). Pure and
 * allocation free.
 * <p>
 * Ratings: high ground = {@code min(1, gain/2) * (1 - dist/SEARCH_RANGE)}, 0 when the player already stands at least
 * one block above the target; ceiling = {@code min(1, columns/6) * (1 - dist/SEARCH_RANGE)}.
 */
public final class ArenaTactics {

    /** Spots are looked for within this many blocks of the player. */
    public static final double SEARCH_RANGE = 10.0;
    /** Components of the walk vector smaller than this (blocks) press no key. */
    public static final double DEAD_ZONE = 0.3;
    /** Sprint only when moving forward and farther than this from the spot. */
    public static final double SPRINT_DISTANCE = 3.0;
    /** The player counts as on the spot within this distance (horizontal)... */
    public static final double HOLD_RADIUS = 0.6;
    /** ...and keeps holding until knocked farther than this. */
    public static final double HOLD_LEAVE_RADIUS = 1.0;
    /** Give up (plan answers false) after this many ticks without getting closer. */
    public static final int TIMEOUT_TICKS = 100;
    private static final double PROGRESS = 0.05;

    private double highRating;
    private double ceilRating;
    private boolean hasHigh;
    private boolean hasCeil;
    private double highX;
    private double highZ;
    private double ceilX;
    private double ceilZ;

    private TacticUtility.Tactic lastTactic;
    private double bestDist;
    private int stalled;
    private boolean gaveUp;

    private int forward = SpacingController.HOLD;
    private int side = StrafePlanner.NONE;
    private boolean sprint;
    private boolean jump;
    private boolean holding;

    /**
     * Rates both tactics for the current positions from the scanned field. Call a few times a second, not every tick
     * (it runs the field's spot searches).
     */
    public void assess(CombatSnapshot s, ArenaQuery field) {
        boolean hadHigh = hasHigh;
        hasHigh = false;
        highRating = 0.0;
        if (s.py < s.ty + 1.0 && field.findHighGround(s.px, s.py, s.pz, s.tx, s.ty, s.tz, SEARCH_RANGE)) {
            double x = field.spotX();
            double z = field.spotZ();
            double gain = Math.min(1.0, field.spotGain() / 2.0);
            highRating = gain * closeness(s, x, z);
            if (highRating > 0.0) {
                if (!hadHigh || Math.abs(x - highX) >= 1.0 || Math.abs(z - highZ) >= 1.0) {
                    gaveUp = false;
                    stalled = 0;
                    bestDist = Double.MAX_VALUE;
                }
                hasHigh = true;
                highX = x;
                highZ = z;
            }
        }
        hasCeil = false;
        ceilRating = 0.0;
        if (field.findLowCeiling(s.px, s.py, s.pz, s.tx, s.ty, s.tz, SEARCH_RANGE)) {
            double x = field.spotX();
            double z = field.spotZ();
            double gain = Math.min(1.0, field.spotGain() / 6.0);
            ceilRating = gain * closeness(s, x, z);
            if (ceilRating > 0.0) {
                hasCeil = true;
                ceilX = x;
                ceilZ = z;
            }
        }
    }


    private static double closeness(CombatSnapshot s, double x, double z) {
        double dx = x - s.px;
        double dz = z - s.pz;
        return Math.max(0.0, 1.0 - Math.sqrt(dx * dx + dz * dz) / SEARCH_RANGE);
    }

    /** 0..1: a useful higher spot is close (0 = none, or we already hold the high ground). */
    public double highGround() {
        return highRating;
    }

    /** 0..1: a low-ceiling spot is close (0 = none). Only worth anything against a hopping opponent. */
    public double ceilingDeny() {
        return ceilRating;
    }

    /**
     * Steers toward the spot of the given tactic, or holds it once there.
     *
     * @return true when this tick's movement is planned ({@link #forward()}.. are valid); false = the tactic has no
     *         spot any more or is not a positional one, the caller runs its normal spacing
     */
    public boolean plan(CombatSnapshot s, ArenaQuery field, TacticUtility.Tactic tactic) {
        forward = SpacingController.HOLD;
        side = StrafePlanner.NONE;
        sprint = false;
        jump = false;
        boolean wasHolding = holding;
        holding = false;

        double sx;
        double sz;
        if (tactic == TacticUtility.Tactic.HIGH_GROUND && hasHigh) {
            sx = highX;
            sz = highZ;
        } else if (tactic == TacticUtility.Tactic.CEILING_DENY && hasCeil) {
            sx = ceilX;
            sz = ceilZ;
        } else {
            return false;
        }
        if (tactic != lastTactic) {
            lastTactic = tactic;
            gaveUp = false;
            bestDist = Double.MAX_VALUE;
            stalled = 0;
        }
        double vx = sx - s.px;
        double vz = sz - s.pz;
        double dist = Math.sqrt(vx * vx + vz * vz);

        if (dist <= (wasHolding ? HOLD_LEAVE_RADIUS : HOLD_RADIUS)) {
            holding = true;
            stalled = 0;
            bestDist = dist;
            return true;
        }
        if (gaveUp) {
            return false;
        }
        if (dist < bestDist - PROGRESS) {
            bestDist = dist;
            stalled = 0;
        } else if (++stalled >= TIMEOUT_TICKS) {
            gaveUp = true;
            return false;
        }

        double fx = s.tx - s.px;
        double fz = s.tz - s.pz;
        double fl = Math.sqrt(fx * fx + fz * fz);
        if (fl < 1.0E-6) {
            fx = 1.0;
            fz = 0.0;
        } else {
            fx /= fl;
            fz /= fl;
        }
        double fw = vx * fx + vz * fz;
        double lat = vx * fz - vz * fx; // along left = (fz, -fx)
        if (fw > DEAD_ZONE && !s.hazardFront) {
            forward = SpacingController.FORWARD;
        } else if (fw < -DEAD_ZONE && !s.hazardBack) {
            forward = SpacingController.BACK;
        }
        if (lat > DEAD_ZONE && !s.hazardLeft) {
            side = StrafePlanner.LEFT;
        } else if (lat < -DEAD_ZONE && !s.hazardRight) {
            side = StrafePlanner.RIGHT;
        }
        sprint = forward == SpacingController.FORWARD && dist > SPRINT_DISTANCE;

        if (s.onGround && dist > 1.0E-6) {
            int cx = (int) Math.floor(s.px);
            int cz = (int) Math.floor(s.pz);
            int nx = (int) Math.floor(s.px + vx / dist);
            int nz = (int) Math.floor(s.pz + vz / dist);
            if (nx != cx || nz != cz) {
                int here = field.floorY(cx, cz);
                int next = field.floorY(nx, nz);
                jump = here != ArenaField.NO_FLOOR && next != ArenaField.NO_FLOOR && next == here + 1;
            }
        }
        return true;
    }

    /** {@link SpacingController#FORWARD}, {@link SpacingController#BACK} or {@link SpacingController#HOLD}. */
    public int forward() {
        return forward;
    }

    /** {@link StrafePlanner#LEFT}, {@link StrafePlanner#RIGHT} or {@link StrafePlanner#NONE}. */
    public int side() {
        return side;
    }

    public boolean sprint() {
        return sprint;
    }

    /** The step toward the spot needs a jump this tick (a 1-block step up). */
    public boolean jump() {
        return jump;
    }

    /** True once the player stands on the spot (the caller fights from it: normal swings, no hops under a ceiling). */
    public boolean holding() {
        return holding;
    }

    public void reset() {
        highRating = 0.0;
        ceilRating = 0.0;
        hasHigh = false;
        hasCeil = false;
        lastTactic = null;
        stalled = 0;
        bestDist = Double.MAX_VALUE;
        gaveUp = false;
        forward = SpacingController.HOLD;
        side = StrafePlanner.NONE;
        sprint = false;
        jump = false;
        holding = false;
    }
}
